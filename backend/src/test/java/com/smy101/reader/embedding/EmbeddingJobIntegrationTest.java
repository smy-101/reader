package com.smy101.reader.embedding;

import com.jayway.jsonpath.JsonPath;
import com.smy101.reader.IntegrationTestBase;
import com.smy101.reader.llm.OpenAiStubServer;
import org.junit.jupiter.api.AfterAll;
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
 * 嵌入任务域(M4-02,Seam A):上传自动建任务 → 后台执行 → 向量块落库(归属/序号/token/维度);
 * 未配置不建任务;上游失败 failed + 重试从头重跑;换模型全量重嵌入(维度变化可断言);
 * 存量书手动触发;embedding 独立 base_url/key(D-28);分批有界;同书串行;401 防线。
 * embedding 一律走 OpenAI 兼容 stub 确定性向量(关键词→维度映射),CI 零外网依赖。
 */
class EmbeddingJobIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();
    /** embedding 独立 base_url 用例的第二 stub(D-28:独立配置时请求改道) */
    private static final OpenAiStubServer EMBED_STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStubs() {
        STUB.close();
        EMBED_STUB.close();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void embeddingBatchSize(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("reader.llm.embedding-batch-size", () -> "2"); // 分批有界断言用
    }

    // ---- 用例 ----

    @Test
    void 已配置embedding_上传自动建任务_进度至完成_向量块落库() throws IOException {
        saveSettings(Map.of("embeddingModel", "bge-m3"));

        long start = System.nanoTime();
        ResponseEntity<String> upload = uploadBook();
        long uploadMs = (System.nanoTime() - start) / 1_000_000;
        long bookId = ((Number) JsonPath.read(upload.getBody(), "id")).longValue();

        // 上传立即返回(不等嵌入,口径 D-41 不变)
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uploadMs).isLessThan(5_000);

        Map<String, Object> status = awaitTerminal(bookId);
        assertThat(status.get("status")).isEqualTo("done");
        assertThat(status.get("model")).isEqualTo("bge-m3");
        assertThat((Integer) status.get("chunkTotal")).isEqualTo(4); // fixture:4 章各 1 块
        assertThat((Integer) status.get("chunkDone")).isEqualTo(4);

        // 向量块行:条数、归属、章内序号、token_count、同书维度一致
        List<Map<String, Object>> chunks = jdbc.queryForList(
                "SELECT c.chapter_id, c.seq, c.content, c.token_count, ch.seq AS chapter_seq "
                        + "FROM document_chunk c JOIN chapter ch ON ch.id = c.chapter_id "
                        + "WHERE c.book_id = ? ORDER BY ch.seq", bookId);
        assertThat(chunks).hasSize(4);
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> chunk = chunks.get(i);
            assertThat(((Number) chunk.get("chapter_seq")).intValue()).isEqualTo(i + 1);
            assertThat(((Number) chunk.get("seq")).intValue()).isEqualTo(1); // 章内第 1 块
            assertThat((String) chunk.get("content")).isNotBlank();
            assertThat((Integer) chunk.get("token_count")).isPositive();
        }
        assertThat(jdbc.queryForList(
                "SELECT vector_dims(embedding) FROM document_chunk WHERE book_id = ?",
                Integer.class, bookId)).containsOnly(256); // 同书维度一致(stub 默认 256)

        // embeddings 请求分批有界:批大小 2,4 块 → 2 次请求,每次 input ≤ 2
        List<OpenAiStubServer.Received> requests = STUB.requests("/v1/embeddings");
        assertThat(requests.size()).isEqualTo(2);
        for (OpenAiStubServer.Received request : requests) {
            assertThat(((List<?>) read(request.body(), "$.input")).size()).isLessThanOrEqualTo(2);
        }
        assertThat((String) read(requests.get(0).body(), "$.model")).isEqualTo("bge-m3");
    }

    @Test
    void 未配置embedding_上传不建任务_状态明示未嵌入() throws IOException {
        saveSettings(null); // 无 embeddingModel

        long bookId = uploadBookId();

        ResponseEntity<String> res = status(bookId);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) read(res.getBody(), "$.status")).isEqualTo("none");
        Integer jobs = jdbc.queryForObject("SELECT count(*) FROM embedding_job", Integer.class);
        Integer chunks = jdbc.queryForObject("SELECT count(*) FROM document_chunk", Integer.class);
        assertThat(jobs).isZero();
        assertThat(chunks).isZero();
    }

    @Test
    void 上游失败_任务failed带可读错误_重试从头重跑至done() throws IOException {
        saveSettings(Map.of("embeddingModel", "bge-m3"));
        STUB.embeddings(500, "{\"error\":{\"message\":\"boom\"}}");

        long bookId = uploadBookId();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId)).isZero();

        Map<String, Object> status = awaitTerminal(bookId);
        assertThat(status.get("status")).isEqualTo("failed");
        assertThat((String) status.get("error")).contains("500");

        // 重试:上游恢复 → 从头重跑,终态 done,块重新入库
        STUB.embeddingsOk();
        ResponseEntity<String> retried = trigger(bookId);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> done = awaitTerminal(bookId);
        assertThat(done.get("status")).isEqualTo("done");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId)).isEqualTo(4);
    }

    @Test
    void 换模型触发_清旧块全量重嵌入_维度随新模型变化_同模型幂等() throws IOException {
        saveSettings(Map.of("embeddingModel", "bge-m3"));
        long bookId = uploadBookId();
        awaitTerminal(bookId);
        assertThat(jdbc.queryForList(
                "SELECT vector_dims(embedding) FROM document_chunk WHERE book_id = ?",
                Integer.class, bookId)).containsOnly(256);

        // 换模型 + 换维度:触发 → 全量重嵌入,旧块清净
        STUB.setEmbeddingDimension(24);
        saveSettings(Map.of("embeddingModel", "bge-m3-v2"));
        ResponseEntity<String> reembed = trigger(bookId);
        assertThat(reembed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) read(reembed.getBody(), "$.model")).isEqualTo("bge-m3-v2");

        Map<String, Object> done = awaitTerminal(bookId);
        assertThat(done.get("status")).isEqualTo("done");
        assertThat(done.get("model")).isEqualTo("bge-m3-v2");
        assertThat(jdbc.queryForList(
                "SELECT vector_dims(embedding) FROM document_chunk WHERE book_id = ?",
                Integer.class, bookId)).containsOnly(24); // 新维度可断言
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId)).isEqualTo(4);
        Integer jobsAfterReembed = jdbc.queryForObject(
                "SELECT count(*) FROM embedding_job WHERE book_id = ?", Integer.class, bookId);
        assertThat(jobsAfterReembed).isEqualTo(2); // 首次 + 重嵌入各一条

        // 同模型且已完成:触发幂等返回当前状态,不新建任务
        ResponseEntity<String> idempotent = trigger(bookId);
        assertThat((String) read(idempotent.getBody(), "$.status")).isEqualTo("done");
        Integer jobs = jdbc.queryForObject(
                "SELECT count(*) FROM embedding_job WHERE book_id = ?", Integer.class, bookId);
        assertThat(jobs).isEqualTo(2);
    }

    @Test
    void 存量书_配置后手动触发首次嵌入至done() throws IOException {
        saveSettings(null); // 先未配置上传(存量书)
        long bookId = uploadBookId();
        assertThat((String) read(status(bookId).getBody(), "$.status")).isEqualTo("none");

        saveSettings(Map.of("embeddingModel", "bge-m3"));
        assertThat(trigger(bookId).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> done = awaitTerminal(bookId);
        assertThat(done.get("status")).isEqualTo("done");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId)).isEqualTo(4);
    }

    @Test
    void embeddings请求发往独立base_url与key() throws IOException {
        // D-28:embedding 独立配置生效;chat 仍走主 stub
        saveSettings(Map.of(
                "embeddingModel", "bge-m3",
                "embeddingBaseUrl", EMBED_STUB.baseUrl(),
                "embeddingApiKey", "sk-embed"));

        long bookId = uploadBookId();
        Map<String, Object> done = awaitTerminal(bookId);
        assertThat(done.get("status")).isEqualTo("done");

        // embeddings 打到独立 stub 且带独立 key;主 stub 一条 embeddings 都不收
        OpenAiStubServer.Received received = EMBED_STUB.lastRequest("/v1/embeddings");
        assertThat(received.bearer()).isEqualTo("Bearer sk-embed");
        assertThat(STUB.requests("/v1/embeddings")).isEmpty();
    }

    @Test
    void 同书任务串行_并发触发被吸收_不重复建任务() throws IOException {
        saveSettings(Map.of("embeddingModel", "bge-m3"));
        long bookId = uploadBookId();

        // done 前连续触发 5 次:全部返回当前状态,不产生额外任务(串行语义吸收)
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> res = trigger(bookId);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        Map<String, Object> done = awaitTerminal(bookId);
        assertThat(done.get("status")).isEqualTo("done");

        Integer jobs = jdbc.queryForObject(
                "SELECT count(*) FROM embedding_job WHERE book_id = ?", Integer.class, bookId);
        assertThat(jobs).isEqualTo(1); // 只有上传自动建的那一条
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId)).isEqualTo(4);
    }

    @Test
    void 触发未配置embedding_400可读文案_书不存在404() {
        saveSettings(null);
        jdbc.update("INSERT INTO book (title, file_hash, file_size) VALUES ('裸书', 'hash-bare', 10)");
        Long bookId = jdbc.queryForObject("SELECT id FROM book WHERE file_hash = 'hash-bare'", Long.class);

        ResponseEntity<String> res = trigger(bookId);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("尚未配置").contains("embedding");

        ResponseEntity<String> missing = trigger(99999);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> missingStatus = rest.exchange("/api/books/99999/embedding", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        assertThat(missingStatus.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 新端点无token一律401() throws IOException {
        saveSettings(Map.of("embeddingModel", "bge-m3"));
        long bookId = uploadBookId();

        ResponseEntity<String> status = rest.exchange("/api/books/" + bookId + "/embedding", HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> trigger = rest.exchange("/api/books/" + bookId + "/embedding/trigger",
                HttpMethod.POST, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(trigger.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
        EMBED_STUB.resetRequests();
    }

    private ResponseEntity<String> uploadBook() throws IOException {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(readFixture("normal.epub")) {
            @Override
            public String getFilename() {
                return "normal.epub";
            }
        });
        return rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
    }

    private long uploadBookId() throws IOException {
        return ((Number) JsonPath.read(uploadBook().getBody(), "id")).longValue();
    }

    private ResponseEntity<String> status(long bookId) {
        return rest.exchange("/api/books/" + bookId + "/embedding", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
    }

    private ResponseEntity<String> trigger(long bookId) {
        return rest.exchange("/api/books/" + bookId + "/embedding/trigger", HttpMethod.POST,
                new HttpEntity<>(authHeaders()), String.class);
    }

    /** 轮询至终态(done/failed),返回最终状态响应体。 */
    private Map<String, Object> awaitTerminal(long bookId) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        String last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> res = status(bookId);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            last = res.getBody();
            String status = (String) read(last, "$.status");
            if ("done".equals(status) || "failed".equals(status)) {
                return jdbc.queryForMap(
                        "SELECT status, model, chunk_done AS \"chunkDone\", chunk_total AS \"chunkTotal\", error "
                                + "FROM embedding_job WHERE book_id = ? ORDER BY id DESC LIMIT 1", bookId);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待嵌入终态被中断", e);
            }
        }
        throw new AssertionError("嵌入任务未在时限内到达终态,最后状态:" + last);
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

    private static Object read(String json, String path) {
        return JsonPath.read(json, path);
    }
}
