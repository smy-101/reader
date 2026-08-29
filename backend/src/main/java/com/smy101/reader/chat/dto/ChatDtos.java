package com.smy101.reader.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 会话/消息对外形状(FR-301/304)。 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** 会话条目;"最近活跃" = updated_at 服务器时钟(新消息/重命名刷新,D-19 同源)。 */
    public record SessionDto(
            Long id,
            Long bookId,
            String title,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** 重命名请求。 */
    public record RenameRequest(String title) {
    }

    /** 消息(含引用来源 refs;refs 形状:{type: selection|chapter, ...})。 */
    public record MessageDto(
            Long id,
            Long sessionId,
            String role,
            String content,
            List<Map<String, Object>> refs,
            OffsetDateTime createdAt) {
    }

    /**
     * 书级提问请求(D-31/D-32):S1 与 S2 同一通路——带 selection 即 S1,不带即 S2,
     * retrieval=true 即 S3(M4;S1 优先级最高,与 selection 同传时 selection 生效)。
     * content 必填;sessionId 可选(缺省 = 该书最近活跃会话,无则新建,标题取首条提问截断);
     * chapterId 可选(目标章,由前端把当前阅读位置映射后填入,服务端不反查 reading_progress);
     * cfi 可选(阅读位置 CFI,仅随 refs 落库供回溯);selection 可选(S1 选中文字 + CFI)。
     */
    public record AskRequest(
            String content,
            Long sessionId,
            Long chapterId,
            String cfi,
            SelectionInput selection,
            Boolean retrieval) {

        /** S1 选中文字引用。 */
        public record SelectionInput(String text, String cfi) {
        }
    }

    /** 检索引用(S3 与 S2 检索式降级;章节标识 + 标题 + 原文摘录,摘录即向量块全文)。 */
    public record CitationDto(
            Long chapterId,
            String chapterTitle,
            Integer chapterSeq,
            Integer chunkSeq,
            String excerpt) {
    }

    /** SSE meta 事件:落定的会话与用户消息标识 + 检索引用(可为 null;S3 在流式开始前即可渲染)。 */
    public record AskMeta(Long sessionId, String sessionTitle, Long userMessageId, List<CitationDto> citations) {
    }

    /** SSE done 事件:助手消息标识 + 预算说明(降级/断尾文案,可为 null)。 */
    public record AskDone(Long assistantMessageId, String note) {
    }

    /** SSE error 事件:可读中文文案。 */
    public record AskError(String message) {
    }
}
