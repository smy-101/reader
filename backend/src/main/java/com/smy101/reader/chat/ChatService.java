package com.smy101.reader.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smy101.reader.book.Book;
import com.smy101.reader.book.BookMapper;
import com.smy101.reader.book.Chapter;
import com.smy101.reader.book.ChapterMapper;
import com.smy101.reader.chat.budget.BudgetCalculator;
import com.smy101.reader.chat.budget.TokenEstimator;
import com.smy101.reader.chat.dto.ChatDtos;
import com.smy101.reader.llm.LlmAdapter;
import com.smy101.reader.llm.LlmException;
import com.smy101.reader.settings.ModelSettings;
import com.smy101.reader.settings.ModelSettingsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话/消息域 + 提问链路(M3-03,FR-301/302/303/304 · D-31/D-32/D-25)。
 * <p>
 * 提问 = 同步受理(校验 → 会话路由 → 用户消息落库 → 预算装配,失败 4xx 可读文案)
 * + 异步流式(虚拟线程 SSE 中继:meta → delta… → done;上游失败转 error 事件收尾不悬挂,
 * 中断时已到内容照常落库)。
 */
@Slf4j
@Service
public class ChatService {

    private static final int TITLE_MAX_CHARS = 30;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final BookMapper bookMapper;
    private final ChapterMapper chapterMapper;
    private final ModelSettingsMapper settingsMapper;
    private final LlmAdapter llmAdapter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** SSE 中继线程:阻塞式读上游,虚拟线程轻量(Java 21) */
    private final ExecutorService streamingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(ChatSessionMapper sessionMapper,
                       ChatMessageMapper messageMapper,
                       BookMapper bookMapper,
                       ChapterMapper chapterMapper,
                       ModelSettingsMapper settingsMapper,
                       LlmAdapter llmAdapter) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.bookMapper = bookMapper;
        this.chapterMapper = chapterMapper;
        this.settingsMapper = settingsMapper;
        this.llmAdapter = llmAdapter;
    }

    // ---- 提问(S1 与 S2 同一通路,D-32:带 selection 即 S1) ----

    /**
     * 受理提问:全部可前置校验在此完成(书/会话/目标章/选中文字/模型设置/预算),
     * 用户消息即落库;通过则返回 SSE emitter,流式部分转 {@link #stream} 异步执行。
     */
    public PreparedAsk prepare(long bookId, ChatDtos.AskRequest request) {
        Book book = requireBook(bookId);
        String content = request == null ? null : request.content();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提问内容不能为空");
        }
        content = content.strip();

        // 目标章(D-31):显式 chapterId 为准;不反查 reading_progress
        Chapter targetChapter = null;
        if (request.chapterId() != null) {
            targetChapter = chapterMapper.selectById(request.chapterId());
            if (targetChapter == null || targetChapter.getBookId() != bookId) {
                throw new IllegalArgumentException("目标章不属于这本书");
            }
        }

        // S1 选中文字:作为该条用户消息的引用落 refs,并占预算的书内容槽
        ChatDtos.AskRequest.SelectionInput selection = request.selection();
        if (selection != null && (selection.text() == null || selection.text().isBlank())) {
            throw new IllegalArgumentException("选中文字不能为空");
        }

        // 模型设置就绪检查:未配置给引导文案(US 14);先于会话路由,失败不留孤儿会话
        ModelSettings settings = settingsMapper.selectById(1);
        if (settings == null) {
            throw new IllegalArgumentException("尚未配置模型设置:请先在 AI 设置页填写 Base URL 与 Chat 模型");
        }

        // 会话路由三态(D-32):指定 session_id 校验归属 / 缺省取最近活跃 / 都没有先不建
        ChatSession session = resolveSession(bookId, request.sessionId());

        // 预算装配(FR-302):书内容优先占额 → 剩余装最近对话(不含本条提问,提问恒发)
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getBookId, bookId)
                .orderByAsc(Chapter::getSeq));
        int wholeBookTokens = chapters.stream()
                .mapToInt(c -> TokenEstimator.estimate(c.getContent()))
                .sum();
        Integer targetChapterTokens = targetChapter == null
                ? null
                : TokenEstimator.estimate(targetChapter.getContent());
        Integer selectionTokens = selection == null
                ? null
                : TokenEstimator.estimate(selection.text());

        List<BudgetCalculator.MessageCandidate> history =
                session == null ? List.of() : historyCandidates(session.getId());
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                settings.getChatContextTokens(),
                wholeBookTokens,
                targetChapterTokens,
                selectionTokens,
                history,
                false)); // 检索式 M3 不可达,M4 翻真
        if (plan.mode() == BudgetCalculator.Mode.INSUFFICIENT) {
            throw new IllegalArgumentException(plan.note()); // 优雅报错:不建会话不落消息
        }

        if (session == null) {
            session = createSession(bookId, content); // 标题 = 首条提问截断
        }

        // 用户消息受理即落库(spec:提问受理时即落库),refs 随之入库(FR-301 引用)
        ChatMessage userMessage = insertMessage(session.getId(), "user", content,
                buildRefs(selection, targetChapter, request.cfi()));

        String bookContent = assembleBookContent(plan, chapters, targetChapter, selection);
        List<LlmAdapter.LlmMessage> prompt = buildPrompt(book, bookContent, plan.keptMessages(), content);
        return new PreparedAsk(settings, session, userMessage.getId(), plan, prompt);
    }

    /** SSE 流式中继:meta → delta… → done;上游失败/中断 → error 事件收尾,已到内容照常落库。 */
    public void stream(PreparedAsk prepared, SseEmitter emitter) {
        ModelSettings settings = prepared.settings();
        ChatSession session = prepared.session();
        StringBuilder assistantContent = new StringBuilder();
        streamingExecutor.execute(() -> {
            boolean clientGone = false;
            try {
                send(emitter, "meta", new ChatDtos.AskMeta(
                        session.getId(), session.getTitle(), prepared.userMessageId()));
                llmAdapter.streamChat(new LlmAdapter.ChatCompletionRequest(
                                settings.getBaseUrl(),
                                settings.getApiKey() == null ? "" : settings.getApiKey(),
                                settings.getChatModel(),
                                prepared.prompt()),
                        text -> {
                            assistantContent.append(text);
                            send(emitter, "delta", Map.of("text", text));
                        });
                // 流结束:助手消息落库(会话 updated_at 随之刷新)
                ChatMessage assistant = insertMessage(session.getId(), "assistant",
                        assistantContent.toString(), null);
                send(emitter, "done", new ChatDtos.AskDone(assistant.getId(), prepared.plan().note()));
                emitter.complete();
            } catch (LlmException e) {
                savePartialIfNeeded(session.getId(), assistantContent);
                sendQuietly(emitter, "error", new ChatDtos.AskError(e.getMessage()));
                emitter.complete();
            } catch (UncheckedIOException e) {
                // emitter.send 失败 = 客户端已断开:不再向其发事件,尽量保住已到内容
                clientGone = true;
                savePartialIfNeeded(session.getId(), assistantContent);
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("提问流式中继异常 session={}", session.getId(), e);
                savePartialIfNeeded(session.getId(), assistantContent);
                sendQuietly(emitter, "error", new ChatDtos.AskError("AI 服务异常,请稍后重试"));
                emitter.complete();
            } finally {
                if (clientGone) {
                    log.info("客户端在流式期间断开 session={} 已收内容 {} 字",
                            session.getId(), assistantContent.length());
                }
            }
        });
    }

    // ---- 会话 CRUD(FR-301/304) ----

    /** 某书会话列表,按最近活跃(updated_at)排序。 */
    public List<ChatDtos.SessionDto> listSessions(long bookId) {
        requireBook(bookId);
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getBookId, bookId)
                        .orderByDesc(ChatSession::getUpdatedAt)
                        .orderByDesc(ChatSession::getId))
                .stream().map(this::toSessionDto).toList();
    }

    /** 会话全部消息(含 refs),按时间序;打开会话一次拿齐(D-24 同精神)。 */
    public List<ChatDtos.MessageDto> listMessages(long sessionId) {
        ChatSession session = requireSession(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .orderByAsc(ChatMessage::getId))
                .stream().map(this::toMessageDto).toList();
    }

    /** 重命名(FR-304);updated_at 随之刷新(重命名也是一次活跃)。 */
    public ChatDtos.SessionDto rename(long sessionId, ChatDtos.RenameRequest request) {
        ChatSession session = requireSession(sessionId);
        String title = request == null ? null : request.title();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getTitle, title.strip())
                .setSql("updated_at = now()")); // 服务器时钟(D-19)
        return toSessionDto(requireSession(sessionId));
    }

    /** 删除会话(消息经外键级联清)。 */
    public void deleteSession(long sessionId) {
        requireSession(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    // ---- 内部 ----

    /** 受理结果:流式所需的一切(settings + 会话 + 用户消息 id + 装配计划 + 最终 prompt)。 */
    public record PreparedAsk(
            ModelSettings settings,
            ChatSession session,
            long userMessageId,
            BudgetCalculator.BudgetPlan plan,
            List<LlmAdapter.LlmMessage> prompt) {
    }

    /** 会话路由前半段(D-32):指定 session_id 校验归属;缺省取最近活跃;都没有返回 null(预算通过后再建)。 */
    private ChatSession resolveSession(long bookId, Long sessionId) {
        if (sessionId != null) {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session == null || session.getBookId() == null || session.getBookId() != bookId) {
                throw new NoSuchElementException("会话不存在或不属于这本书");
            }
            return session;
        }
        return sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getBookId, bookId)
                .orderByDesc(ChatSession::getUpdatedAt)
                .orderByDesc(ChatSession::getId)
                .last("LIMIT 1"));
    }

    private ChatSession createSession(long bookId, String firstQuestion) {
        ChatSession created = new ChatSession();
        created.setBookId(bookId);
        created.setTitle(truncateTitle(firstQuestion));
        sessionMapper.insert(created);
        return sessionMapper.selectById(created.getId());
    }

    /** 会话历史消息(旧→新)转预算候选;不含正在受理的这条提问(提问恒发)。 */
    private List<BudgetCalculator.MessageCandidate> historyCandidates(long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream()
                .map(m -> new BudgetCalculator.MessageCandidate(m.getRole(), m.getContent(),
                        TokenEstimator.estimate(m.getContent())))
                .toList();
    }

    /** refs 落库形状(首版):selection{text,cfi} + chapter{chapterId,chapterTitle}。 */
    private String buildRefs(ChatDtos.AskRequest.SelectionInput selection, Chapter targetChapter, String cfi) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (selection != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", "selection");
            ref.put("text", selection.text());
            if (selection.cfi() != null && !selection.cfi().isBlank()) ref.put("cfi", selection.cfi().strip());
            refs.add(ref);
        }
        if (targetChapter != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", "chapter");
            ref.put("chapterId", targetChapter.getId());
            ref.put("chapterTitle", targetChapter.getTitle());
            ref.put("seq", targetChapter.getSeq());
            if (cfi != null && !cfi.isBlank()) ref.put("cfi", cfi.strip());
            refs.add(ref);
        }
        if (refs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (IOException e) {
            throw new IllegalStateException("refs 序列化失败", e);
        }
    }

    /** 按装配计划取书内容文本:整书(全章拼接)/ 目标章 / 选中文字(S1)。 */
    private String assembleBookContent(BudgetCalculator.BudgetPlan plan,
                                       List<Chapter> chapters,
                                       Chapter targetChapter,
                                       ChatDtos.AskRequest.SelectionInput selection) {
        return switch (plan.mode()) {
            case WHOLE_BOOK -> {
                StringBuilder sb = new StringBuilder();
                for (Chapter chapter : chapters) {
                    sb.append(chapterHeading(chapter)).append('\n')
                            .append(chapter.getContent()).append("\n\n");
                }
                yield sb.toString().stripTrailing();
            }
            case TARGET_CHAPTER -> chapterHeading(targetChapter) + "\n" + targetChapter.getContent();
            case SELECTION -> "【选中文字】\n" + selection.text();
            default -> throw new IllegalStateException("装配模式不可用:" + plan.mode());
        };
    }

    private String chapterHeading(Chapter chapter) {
        String title = chapter.getTitle() == null ? "" : " " + chapter.getTitle();
        return "【第" + chapter.getSeq() + "章" + title + "】";
    }

    /** 最终 prompt:系统说明(含书内容)+ 保留的历史 + 本条提问。 */
    private List<LlmAdapter.LlmMessage> buildPrompt(Book book, String bookContent,
                                                    List<BudgetCalculator.MessageCandidate> history,
                                                    String question) {
        String system = """
                你是一个 AI 阅读助手,正在陪读者阅读《%s》。\
                请依据下面提供的书籍内容回答读者问题;内容不足以回答时如实说明,不要编造。

                %s""".formatted(book.getTitle(), bookContent);
        List<LlmAdapter.LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmAdapter.LlmMessage("system", system));
        for (BudgetCalculator.MessageCandidate candidate : history) {
            messages.add(new LlmAdapter.LlmMessage(candidate.role(), candidate.content()));
        }
        messages.add(new LlmAdapter.LlmMessage("user", question));
        return messages;
    }

    /** 落一条消息并刷新会话活跃度(新消息 = 一次活跃)。 */
    private ChatMessage insertMessage(long sessionId, String role, String content, String refs) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setRefs(refs);
        messageMapper.insert(message);
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .setSql("updated_at = now()"));
        return message;
    }

    /** 中断兜底:已到内容照常落库(可读优先,v1 不做完成度标记)。 */
    private void savePartialIfNeeded(long sessionId, StringBuilder assistantContent) {
        if (assistantContent.length() > 0) {
            insertMessage(sessionId, "assistant", assistantContent.toString(), null);
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            throw new UncheckedIOException(new IOException("客户端连接不可用", e));
        }
    }

    /** 尽力发送错误事件(客户端仍在则可见,不在则安静收尾)。 */
    private void sendQuietly(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            log.debug("错误事件发送失败(客户端可能已断开):{}", e.getMessage());
        }
    }

    private String truncateTitle(String content) {
        String compact = content.replaceAll("\\s+", " ").strip();
        return compact.length() <= TITLE_MAX_CHARS ? compact : compact.substring(0, TITLE_MAX_CHARS) + "…";
    }

    private Book requireBook(long id) {
        Book book = bookMapper.selectById(id);
        if (book == null) {
            throw new NoSuchElementException("书籍不存在");
        }
        return book;
    }

    private ChatSession requireSession(long id) {
        ChatSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new NoSuchElementException("会话不存在");
        }
        return session;
    }

    private ChatDtos.SessionDto toSessionDto(ChatSession row) {
        return new ChatDtos.SessionDto(row.getId(), row.getBookId(), row.getTitle(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    @SuppressWarnings("unchecked")
    private ChatDtos.MessageDto toMessageDto(ChatMessage row) {
        List<Map<String, Object>> refs = null;
        if (row.getRefs() != null && !row.getRefs().isBlank()) {
            try {
                refs = objectMapper.readValue(row.getRefs(), List.class);
            } catch (IOException e) {
                log.warn("refs 解析失败 messageId={}", row.getId(), e);
            }
        }
        return new ChatDtos.MessageDto(row.getId(), row.getSessionId(), row.getRole(),
                row.getContent(), refs, row.getCreatedAt());
    }
}
