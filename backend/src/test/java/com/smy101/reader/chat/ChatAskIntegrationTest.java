package com.smy101.reader.chat;

import com.jayway.jsonpath.JsonPath;
import com.smy101.reader.IntegrationTestBase;
import com.smy101.reader.llm.OpenAiStubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提问链路(M3-03,FR-302/303 · D-31/D-32/D-25):SSE 事件序列(meta→delta…→done/error)、
 * 会话路由三态、用户/助手消息落库时机(受理即落/流结束落/中断落已到内容)、refs 落库、
 * 预算装配经 stub 收到的 prompt 形状抽查(整书进 prompt / 整书超限实为目标章 / S1 选中文字槽)、
 * 未配置引导文案、上游错误形态转 error 事件收尾不悬挂。
 */
class ChatAskIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void fastStreamTimeout(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("reader.llm.stream-idle-timeout-ms", () -> "800");
    }

    private long bookId;
    /** 章节 id 与正文的便捷索引(seq → 章节正文首句特征) */
    private long firstChapterId;

    @BeforeEach
    void 准备书籍与模型设置() throws IOException {
        bookId = uploadBook();
        firstChapterId = chapterIdBySeq(1);
        saveSettings(null); // 默认:stub + 无上下文上限(8k)
        STUB.chatStream(List.of("你", "好", "呀"));
    }

    @Test
    void 正常序列_元数据增量完成_消息与refs落库() {
        ResponseEntity<String> res = ask(Map.of(
                "content", "第一章讲了什么?",
                "chapterId", firstChapterId,
                "cfi", "epubcfi(/6/4!/4)"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType().toString()).contains("text/event-stream");
        String body = res.getBody();

        // 事件序列显式:meta → delta… → done(FR-303)
        assertThat(body).contains("event:meta").contains("event:delta").contains("event:done");
        assertThat(countOccurrences(body, "event:delta")).isEqualTo(3);
        assertThat(body).doesNotContain("event:error");

        String meta = dataLine(body, "meta");
        long sessionId = ((Number) JsonPath.read(meta, "$.sessionId")).longValue();
        long userMessageId = ((Number) JsonPath.read(meta, "$.userMessageId")).longValue();
        assertThat((String) JsonPath.read(meta, "$.sessionTitle")).isEqualTo("第一章讲了什么?");

        String done = dataLine(body, "done");
        long assistantMessageId = ((Number) JsonPath.read(done, "$.assistantMessageId")).longValue();

        // 增量拼装 = 助手消息内容
        String streamed = res.getBody().lines()
                .filter(l -> l.startsWith("data:{\"text\""))
                .map(l -> (String) read(l.substring("data:".length()), "$.text"))
                .collect(Collectors.joining());
        assertThat(streamed).isEqualTo("你好呀");

        // 落库:用户消息(受理即落)+ 助手消息(流结束落);refs 含章节引用
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, role, content, refs::text AS refs FROM chat_message WHERE id = ?", userMessageId);
        assertThat(user.get("role")).isEqualTo("user");
        assertThat(user.get("content")).isEqualTo("第一章讲了什么?");
        assertThat((String) user.get("refs")).contains("\"type\": \"chapter\"").contains("\"chapterId\"");

        Map<String, Object> assistant = jdbc.queryForMap(
                "SELECT id, role, content, refs::text AS refs FROM chat_message WHERE id = ?", assistantMessageId);
        assertThat(assistant.get("role")).isEqualTo("assistant");
        assertThat(assistant.get("content")).isEqualTo("你好呀");
        assertThat(userMessageId).isLessThan(assistantMessageId);

        // 发给上游的 prompt 形状:书内容=整书(默认 8k 装得下),问题在最后
        String system = systemPrompt();
        assertThat(system).contains("第一章").contains("第三章"); // 多章内容都在
        assertThat((String) read(lastPromptBody(), "$.messages[-1].content")).isEqualTo("第一章讲了什么?");
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) read(lastPromptBody(), "$.messages[*].role");
        assertThat(roles.get(0)).isEqualTo("system");
        assertThat(roles.get(roles.size() - 1)).isEqualTo("user");
    }

    @Test
    void 会话路由_缺省落最近活跃_无则新建_指定则用之() {
        // 第一次:无会话 → 新建 A
        long a = askForSessionId("第一个问题");
        // 第二次:缺省 → 落最近活跃 A
        long again = askForSessionId("第二个问题");
        assertThat(again).isEqualTo(a);

        // 显式指定新会话:传不存在的会话 id → 404 可读文案
        ResponseEntity<String> missing = ask(Map.of("content", "问题", "sessionId", 99999));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).contains("会话");

        // 传入属于其他书的会话 → 404(用跨书会话模拟,book_id 为空)
        jdbc.update("INSERT INTO chat_session (book_id, title) VALUES (NULL, '跨书会话')");
        Long crossSession = jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE book_id IS NULL", Long.class);
        ResponseEntity<String> wrongBook = ask(Map.of("content", "问题", "sessionId", crossSession));
        assertThat(wrongBook.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 历史消息进prompt_预算不够丢最旧() {
        // 整书装得下、剩余额度只够最近一两条:最旧被断尾丢弃(不做摘要)
        int whole = wholeBookTokens();
        saveSettings(Map.of("chatContextTokens", whole + 5));
        STUB.chatStream(List.of("嗯"));

        long sessionId = askForSessionId("第一个历史问题独一无二的内容甲");
        ask(Map.of("content", "最新的问题", "sessionId", sessionId));

        String prompt = lastPromptBody();
        assertThat((String) read(prompt, "$.messages[-1].content")).isEqualTo("最新的问题"); // 新消息永远在
        assertThat(prompt).doesNotContain("独一无二的内容甲"); // 最旧被丢
    }

    @Test
    void 整书超限时prompt实为目标章_不带其他章内容() {
        // 目标章 = 第一章;上限夹在"第一章"与"整书"之间 → 降级
        int chapterTokens = chapterTextLength(1);
        int wholeTokens = wholeBookTextLength();
        assertThat(wholeTokens).isGreaterThan(chapterTokens + 120); // fixture 前提:书比单章大得多
        saveSettings(Map.of("chatContextTokens", chapterTokens + 100));

        ResponseEntity<String> res = ask(Map.of("content", "这章讲什么?", "chapterId", firstChapterId));

        String prompt = systemPrompt();
        assertThat(prompt).contains("第一章 起点");            // 目标章内容进 prompt
        assertThat(prompt).doesNotContain("第三章 代码与脚注"); // 其他章不进
        String done = dataLine(res.getBody(), "done");
        assertThat((String) read(done, "$.note")).contains("降级"); // 降级文案随 done 事件下发
    }

    @Test
    void 单章超限且无检索式_400文案指向换模型或配embedding() {
        // 上限压到低于第一章的近似 token 数(D-37 口径)→ 降级链走尽 → 优雅报错,不建会话不落消息
        int chapterTokens = chapterEstimatedTokens(1);
        saveSettings(Map.of("chatContextTokens", Math.max(10, chapterTokens - 5)));

        ResponseEntity<String> res = ask(Map.of("content", "这章讲什么?", "chapterId", firstChapterId));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("上下文不足").contains("embedding");
        Integer sessions = jdbc.queryForObject("SELECT count(*) FROM chat_session", Integer.class);
        assertThat(sessions).isZero();
        Integer messages = jdbc.queryForObject("SELECT count(*) FROM chat_message", Integer.class);
        assertThat(messages).isZero();
    }

    @Test
    void S1选中文字即书内容槽_refs落selection_不装整书() {
        ResponseEntity<String> res = ask(Map.of(
                "content", "这段在说什么?",
                "selection", Map.of("text", "这是被划选的句子", "cfi", "epubcfi(/6/4!/4,/1:0,/1:10)")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String prompt = systemPrompt();
        assertThat(prompt).contains("这是被划选的句子");      // 选中文字进 prompt(S1 槽位)
        assertThat(prompt).doesNotContain("第一章 起点");     // 不装整书/整章

        // refs 落 selection(text + cfi)
        String refs = jdbc.queryForObject(
                "SELECT refs::text FROM chat_message WHERE role = 'user' LIMIT 1", String.class);
        assertThat(refs).contains("\"type\": \"selection\"").contains("epubcfi(/6/4!/4,/1:0,/1:10)");
    }

    @Test
    void 未配置模型设置_400引导文案() {
        jdbc.update("DELETE FROM model_settings"); // BeforeEach 已配,这里显式清空
        ResponseEntity<String> res = ask(Map.of("content", "问题"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("尚未配置模型设置").contains("AI 设置页");
    }

    @Test
    void 提问内容缺失或书不存在() {
        ResponseEntity<String> noContent = ask(Map.of("chapterId", firstChapterId));
        assertThat(noContent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noContent.getBody()).contains("提问内容");

        ResponseEntity<String> missingBook = rest.exchange("/api/books/99999/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "q"), authJsonHeaders()), String.class);
        assertThat(missingBook.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 目标章不属于这本书
        jdbc.update("INSERT INTO book (title, file_hash, file_size) VALUES ('别的书', 'hash-other', 10)");
        Long otherBookId = jdbc.queryForObject("SELECT id FROM book WHERE file_hash = 'hash-other'", Long.class);
        Long otherChapterId = null;
        jdbc.update("INSERT INTO chapter (book_id, seq, href, content, text_length) VALUES (?, 1, 'x.xhtml', '别书正文', 4)", otherBookId);
        otherChapterId = jdbc.queryForObject("SELECT id FROM chapter WHERE book_id = ?", Long.class, otherBookId);
        ResponseEntity<String> wrongChapter = ask(Map.of("content", "q", "chapterId", otherChapterId));
        assertThat(wrongChapter.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongChapter.getBody()).contains("目标章");
    }

    // ---- 上游错误形态(FR-303:一律转 error 事件收尾,不悬挂) ----

    @Test
    void 上游非2xx_错误事件收尾_助手不落库_用户消息在() {
        STUB.chat(500, "{\"error\":{\"message\":\"boom\"}}");
        ResponseEntity<String> res = ask(Map.of("content", "会失败的问题"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK); // SSE 流本身正常
        assertThat(res.getBody()).contains("event:error").doesNotContain("event:done");
        assertThat(res.getBody()).contains("500");
        assertThat(countOccurrences(res.getBody(), "event:delta")).isZero();

        // 用户消息受理即落库;无增量 → 助手消息不落
        Integer users = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE role = 'user'", Integer.class);
        Integer assistants = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE role = 'assistant'", Integer.class);
        assertThat(users).isEqualTo(1);
        assertThat(assistants).isZero();
        STUB.chatStream(List.of("你"));
    }

    @Test
    void 上游断流_错误事件收尾_已到内容照常落库() {
        STUB.chatStreamAbrupt(List.of("半截", "回复"));
        ResponseEntity<String> res = ask(Map.of("content", "会断流的问题"));

        assertThat(res.getBody()).contains("event:error");
        assertThat(res.getBody()).doesNotContain("event:done");
        // 已到内容落库(v1 可读优先)
        String partial = jdbc.queryForObject(
                "SELECT content FROM chat_message WHERE role = 'assistant'", String.class);
        assertThat(partial).isEqualTo("半截回复");
        STUB.chatStream(List.of("你"));
    }

    @Test
    void 上游超时_错误事件收尾_不悬挂() {
        STUB.hangChat();
        long start = System.nanoTime();
        ResponseEntity<String> res = ask(Map.of("content", "会超时的问题"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("event:error").contains("超时");
        assertThat(elapsedMs).isLessThan(10_000); // 不悬挂:秒级收尾(测试环境 800ms 超时)
        STUB.chatStream(List.of("你"));
    }

    @Test
    void 提问端点无token一律401() {
        ResponseEntity<String> res = rest.exchange("/api/books/" + bookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 上游收尾块content为null时不把字面量null流给用户() {
        // OpenAI 兼容服务常在收尾块发 "content":null(Jackson NullNode.asText() 会返 "null")
        STUB.chat(200, "sse:" + String.join("\n", List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"好的\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":null}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]")));

        ResponseEntity<String> res = ask(Map.of("content", "问"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).doesNotContain("\"text\":\"null\""); // 增量里不得出现字面量 null
        assertThat(countOccurrences(res.getBody(), "event:delta")).isEqualTo(1); // 只有一块真增量
        String content = jdbc.queryForObject(
                "SELECT content FROM chat_message WHERE role = 'assistant'", String.class);
        assertThat(content).isEqualTo("好的");
        STUB.chatStream(List.of("你"));
    }

    // ---- helpers ----

    private void saveSettings(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseUrl", STUB.baseUrl());
        body.put("apiKey", "sk-t");
        body.put("chatModel", "stub-chat");
        if (overrides != null) body.putAll(overrides);
        rest.exchange("/api/settings/model", HttpMethod.PUT, new HttpEntity<>(body, authJsonHeaders()), String.class);
        STUB.resetRequests();
    }

    private long uploadBook() throws IOException {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(readFixture("normal.epub")) {
            @Override
            public String getFilename() {
                return "normal.epub";
            }
        });
        ResponseEntity<String> res = rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) JsonPath.read(res.getBody(), "id")).longValue();
    }

    private long chapterIdBySeq(int seq) {
        return jdbc.queryForObject("SELECT id FROM chapter WHERE book_id = ? AND seq = ?",
                Long.class, bookId, seq);
    }

    private int chapterTextLength(int seq) {
        return jdbc.queryForObject("SELECT text_length FROM chapter WHERE book_id = ? AND seq = ?",
                Integer.class, bookId, seq);
    }

    /** 第一章正文的 D-37 近似 token 数(与预算计算器同口径)。 */
    private int chapterEstimatedTokens(int seq) {
        String content = jdbc.queryForObject(
                "SELECT content FROM chapter WHERE book_id = ? AND seq = ?", String.class, bookId, seq);
        return com.smy101.reader.chat.budget.TokenEstimator.estimate(content);
    }

    private int wholeBookTextLength() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(text_length), 0) FROM chapter WHERE book_id = ?",
                Integer.class, bookId);
    }

    private ResponseEntity<String> ask(Map<String, Object> body) {
        return rest.exchange("/api/books/" + bookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(body, authJsonHeaders()), String.class);
    }

    private long askForSessionId(String content) {
        ResponseEntity<String> res = ask(Map.of("content", content));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) read(dataLine(res.getBody(), "meta"), "$.sessionId")).longValue();
    }

    private String systemPrompt() {
        return (String) read(lastPromptBody(), "$.messages[0].content");
    }

    private String lastPromptBody() {
        return STUB.lastRequest("/v1/chat/completions").body();
    }

    private int wholeBookTokens() {
        return jdbc.queryForList("SELECT content FROM chapter WHERE book_id = ?", String.class, bookId)
                .stream().mapToInt(com.smy101.reader.chat.budget.TokenEstimator::estimate).sum();
    }

    private static Object read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /** 取指定事件名的 data 行(首个)。 */
    private String dataLine(String sseBody, String event) {
        boolean inEvent = false;
        for (String line : sseBody.split("\n")) {
            if (line.startsWith("event:")) {
                inEvent = line.strip().equals("event:" + event);
            } else if (inEvent && line.startsWith("data:")) {
                return line.substring("data:".length()).strip();
            }
        }
        throw new AssertionError("SSE 体内无事件 " + event + ":\n" + sseBody);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return headers;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
