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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索式上下文(M4-03,Seam A):S3 显式检索式提问(cosine top-k 命中可断言、
 * 引用随 meta 事件下发、refs 落库、prompt 含检索块)、前置四态可读错误、
 * S2 自动降级翻真(小上下文 + 嵌入完成 → 检索式装配)、INSUFFICIENT 文案两态区分、
 * S1 回归不变。embedding/chat 一律 stub,向量确定性。
 */
class RetrievalAskIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private long bookId;

    @BeforeEach
    void 准备() throws IOException {
        STUB.chatStream(List.of("答", "案", "在此"));
    }

    // ---- S3 显式检索式提问 ----

    @Test
    void S3检索式提问_引用随meta下发_refs落库_prompt含检索块() throws IOException {
        prepareEmbeddedBook(null);

        // 问“嵌套脚注与代码块”(仅第三章内容独有的字眼,袋向量检索应命中第三章)
        ResponseEntity<String> res = ask(Map.of(
                "content", "作者在哪里讲了嵌套脚注与代码块?",
                "retrieval", true));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = res.getBody();
        assertThat(body).contains("event:meta").contains("event:delta").contains("event:done");

        // 引用随开场元数据事件下发(流式开始前即可渲染):第一条 = 相关度最高的第三章
        String meta = dataLine(body, "meta");
        assertThat((List<?>) read(meta, "$.citations")).isNotEmpty();
        Number topChapterId = (Number) read(meta, "$.citations[0].chapterId");
        Long ch3 = jdbc.queryForObject(
                "SELECT id FROM chapter WHERE book_id = ? AND seq = 3", Long.class, bookId);
        assertThat(topChapterId.longValue()).isEqualTo(ch3);
        assertThat((String) read(meta, "$.citations[0].excerpt")).isNotBlank();

        // 发给 chat 上游的 prompt 含检索块文本(块前带章节溯源头)
        String system = systemPrompt();
        assertThat(system).contains("检索到的相关段落");
        assertThat(system).contains("第三章 代码与脚注");
        assertThat(system).contains("嵌套内层脚注"); // 第三章独有内容进了 prompt

        // 助手消息 refs 落库(检索引用形状),重新拉取会话仍在
        String refs = jdbc.queryForObject(
                "SELECT refs::text FROM chat_message WHERE role = 'assistant'", String.class);
        assertThat(refs).contains("\"type\": \"retrieval\"").contains("excerpt");
        Long sessionId = ((Number) read(meta, "$.sessionId")).longValue();
        ResponseEntity<String> messages = rest.exchange("/api/sessions/" + sessionId + "/messages",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(messages.getBody()).contains("retrieval");
    }

    @Test
    void S3检索式提问_落入该书最近活跃会话() throws IOException {
        prepareEmbeddedBook(null);
        // 先有一轮普通提问建会话,再 S3:应落同一会话(D-32 精神)
        long first = askForSessionId(Map.of("content", "先聊一个问题"));
        long s3 = askForSessionId(Map.of("content", "嵌套脚注在哪?", "retrieval", true));
        assertThat(s3).isEqualTo(first);
    }

    // ---- 前置四态可读错误 ----

    @Test
    void S3前置_未配置embedding_400可读文案() throws IOException {
        prepareBookAndSettings(Map.of()); // 无 embeddingModel
        ResponseEntity<String> res = ask(Map.of("content", "q", "retrieval", true));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("尚未配置").contains("embedding");
        // 不建会话不落消息
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_session", Integer.class)).isZero();
    }

    @Test
    void S3前置_未嵌入_进行中_失败_模型已更换_各自可读错误() throws IOException {
        // 先未配置上传(存量书),再配置 embedding → 书停留在未嵌入态
        prepareBookAndSettings(Map.of());
        saveSettings(Map.of("embeddingModel", "bge-m3"));

        ResponseEntity<String> notEmbedded = ask(Map.of("content", "q", "retrieval", true));
        assertThat(notEmbedded.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notEmbedded.getBody()).contains("尚未嵌入").contains("触发");

        jdbc.update("INSERT INTO embedding_job (book_id, model, status) VALUES (?, 'bge-m3', 'running')", bookId);
        ResponseEntity<String> inProgress = ask(Map.of("content", "q", "retrieval", true));
        assertThat(inProgress.getBody()).contains("嵌入进行中").contains("等待");

        jdbc.update("DELETE FROM embedding_job");
        jdbc.update("INSERT INTO embedding_job (book_id, model, status, error) VALUES (?, 'bge-m3', 'failed', 'x')", bookId);
        ResponseEntity<String> failed = ask(Map.of("content", "q", "retrieval", true));
        assertThat(failed.getBody()).contains("嵌入失败").contains("重试");

        jdbc.update("DELETE FROM embedding_job");
        jdbc.update("INSERT INTO embedding_job (book_id, model, status) VALUES (?, 'bge-m3', 'done')", bookId);
        jdbc.update("UPDATE model_settings SET embedding_model = 'bge-m3-v2' WHERE id = 1");
        ResponseEntity<String> changed = ask(Map.of("content", "q", "retrieval", true));
        assertThat(changed.getBody()).contains("模型已更换").contains("重新嵌入");
    }

    // ---- S2 检索式降级翻真 ----

    @Test
    void S2小上下文长书_自动降级检索式_不再报上下文不足() throws IOException {
        // 上限压到略低于整书 token 数(不带目标章)→ 整书装不下 → 检索式装配
        prepareEmbeddedBook(null);
        int wholeTokens = jdbc.queryForList(
                "SELECT content FROM chapter WHERE book_id = ?", String.class, bookId)
                .stream().mapToInt(com.smy101.reader.chat.budget.TokenEstimator::estimate).sum();
        saveSettings(Map.of("embeddingModel", "bge-m3", "chatContextTokens", wholeTokens - 10));

        ResponseEntity<String> res = ask(Map.of("content", "嵌套脚注与代码块讲了什么?"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).doesNotContain("event:error");

        // prompt 为检索式而非整书:检索式头部 + 命中块,而非整书装配的【第N章】形态
        String system = systemPrompt();
        assertThat(system).contains("检索到的相关段落");
        assertThat(system).contains("嵌套内层脚注");
        assertThat(system).doesNotContain("【第1章");

        // 降级说明如实告知
        String done = dataLine(res.getBody(), "done");
        assertThat((String) read(done, "$.note")).contains("检索式");
    }

    @Test
    void 已配置未嵌入_单章超限_文案引导等待嵌入_区别于未配置() throws IOException {
        // 存量书:配置了 embedding 但没嵌入 → INSUFFICIENT 引导“等待嵌入”,不是“去配置”
        prepareBookAndSettings(Map.of("embeddingModel", "bge-m3", "chatContextTokens", 30));
        Long chapterId = jdbc.queryForObject(
                "SELECT id FROM chapter WHERE book_id = ? AND seq = 1", Long.class, bookId);

        ResponseEntity<String> res = ask(Map.of("content", "q", "chapterId", chapterId));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("上下文不足").contains("等待该书嵌入完成");
    }

    // ---- S1 回归 ----

    @Test
    void S1选中文字优先级最高_与retrieval同传时selection生效() throws IOException {
        prepareEmbeddedBook(null);
        ResponseEntity<String> res = ask(Map.of(
                "content", "这段在说什么?",
                "selection", Map.of("text", "被划选的独有句子", "cfi", "epubcfi(/6/4!/4,/1:0,/1:10)"),
                "retrieval", true));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String system = systemPrompt();
        assertThat(system).contains("被划选的独有句子"); // S1 槽位
        assertThat(system).doesNotContain("检索到的相关段落"); // 不走检索
        String meta = dataLine(res.getBody(), "meta");
        assertThat((String) read(meta, "$.citations")).isNull(); // 无引用条
    }

    // ---- helpers ----

    /** 配置模型设置 + 上传;overrides 无 embeddingModel 时即未配置。 */
    private void prepareBookAndSettings(Map<String, Object> overrides) throws IOException {
        saveSettings(overrides);

        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(readFixture("normal.epub")) {
            @Override
            public String getFilename() {
                return "normal.epub";
            }
        });
        ResponseEntity<String> res = rest.postForEntity("/api/books", new HttpEntity<>(form, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        bookId = ((Number) JsonPath.read(res.getBody(), "id")).longValue();
    }

    private void saveSettings(Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseUrl", STUB.baseUrl());
        body.put("apiKey", "sk-t");
        body.put("chatModel", "stub-chat");
        if (overrides != null) body.putAll(overrides);
        rest.exchange("/api/settings/model", HttpMethod.PUT, new HttpEntity<>(body, authJsonHeaders()), String.class);
        STUB.resetRequests();
    }

    /** 配置 embedding + 上传 + 等嵌入终态 done。 */
    private void prepareEmbeddedBook(Map<String, Object> overrides) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("embeddingModel", "bge-m3");
        if (overrides != null) body.putAll(overrides);
        prepareBookAndSettings(body);

        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM embedding_job WHERE book_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, bookId);
            if ("done".equals(status)) {
                return;
            }
            assertThat(status).isNotEqualTo("failed");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("嵌入未在时限内完成");
    }

    private ResponseEntity<String> ask(Map<String, Object> body) {
        return rest.exchange("/api/books/" + bookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(body, authJsonHeaders()), String.class);
    }

    private long askForSessionId(Map<String, Object> body) {
        ResponseEntity<String> res = ask(body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) read(dataLine(res.getBody(), "meta"), "$.sessionId")).longValue();
    }

    private String systemPrompt() {
        return (String) read(STUB.lastRequest("/v1/chat/completions").body(), "$.messages[0].content");
    }

    private static Object read(String json, String path) {
        return JsonPath.read(json, path);
    }

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
}
