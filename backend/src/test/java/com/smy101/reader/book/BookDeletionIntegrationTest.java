package com.smy101.reader.book;

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
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除书籍完整级联(M3-06,FR-104 兑现 · D-33):磁盘书源文件与封面清理、
 * 划线/进度/该书会话(→消息)外键级联全清、跨书(book_id 空)会话不受影响、404/401 口径一致。
 */
class BookDeletionIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void fastEmbeddingTimeout(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // 嵌入中删书竞态用例:上游挂死时客户端快速超时,worker 不长期阻塞
        registry.add("reader.llm.embedding-request-timeout-ms", () -> "2000");
    }

    @Test
    void 删除书籍_文件与四域数据全清_DB无残留() throws IOException {
        long bookId = uploadBook();
        String fileHash = jdbc.queryForObject("SELECT file_hash FROM book WHERE id = ?", String.class, bookId);
        String coverPath = jdbc.queryForObject("SELECT cover_path FROM book WHERE id = ?", String.class, bookId);
        // 封面与书源文件在盘上(normal.epub fixture 带封面)
        assertThat(Files.exists(STORAGE_ROOT.resolve("books/" + fileHash + ".epub"))).isTrue();
        if (coverPath != null) {
            assertThat(Files.exists(STORAGE_ROOT.resolve(coverPath))).isTrue();
        }

        // 造齐四域数据:划线 + 进度 + 会话与消息(经提问链路)
        putJson("/api/books/" + bookId + "/highlights",
                Map.of("cfi", "epubcfi(/6/4!/4,/1:0,/1:5)", "text", "选中文字"));
        putJson("/api/books/" + bookId + "/progress", Map.of("cfi", "epubcfi(/6)", "percent", 42));
        configureStubModel();
        STUB.chatStream(List.of("回"));
        ResponseEntity<String> ask = rest.exchange("/api/books/" + bookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "删除前的问题"), authJsonHeaders()), String.class);
        assertThat(ask.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> deleted = rest.exchange("/api/books/" + bookId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // DB:书与全域名数据无残留(章节/划线/进度/该书会话与消息/向量块/嵌入任务,FR-104 最后一块)
        for (String table : List.of("book", "chapter", "highlight", "reading_progress",
                "chat_session", "chat_message", "document_chunk", "embedding_job")) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            assertThat(count).as(table).isZero();
        }
        // 磁盘:书源文件与封面清除
        assertThat(Files.exists(STORAGE_ROOT.resolve("books/" + fileHash + ".epub"))).isFalse();
        if (coverPath != null) {
            assertThat(Files.exists(STORAGE_ROOT.resolve(coverPath))).isFalse();
        }

        // 再删 404;详情也 404
        ResponseEntity<String> again = rest.exchange("/api/books/" + bookId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> detail = rest.exchange("/api/books/" + bookId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 删除已嵌入书_向量块与嵌入任务一并清净() throws IOException {
        // 配置 embedding → 上传自动嵌入至 done
        rest.exchange("/api/settings/model", HttpMethod.PUT, new HttpEntity<>(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-t", "chatModel", "stub-chat",
                "embeddingModel", "bge-m3"), authJsonHeaders()), String.class);
        long bookId = uploadBook();
        awaitEmbeddingTerminal(bookId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE book_id = ?", Integer.class, bookId)).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM embedding_job WHERE book_id = ?", Integer.class, bookId)).isPositive();

        ResponseEntity<Void> deleted = rest.exchange("/api/books/" + bookId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 向量块与嵌入任务行全清(外键级联),既有级联回归同上
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_job", Integer.class)).isZero();
    }

    @Test
    void 嵌入进行中删书_删除不被阻塞_无残留() throws IOException {
        // 上游 embeddings 挂住:任务停在 running,此时删书
        rest.exchange("/api/settings/model", HttpMethod.PUT, new HttpEntity<>(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-t", "chatModel", "stub-chat",
                "embeddingModel", "bge-m3"), authJsonHeaders()), String.class);
        STUB.hangEmbeddings();
        long bookId = uploadBook();

        // 等到 running(worker 已挂在上游调用里),删书不应被阻塞
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM embedding_job WHERE book_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, bookId);
            if ("running".equals(status)) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        ResponseEntity<Void> deleted = rest.exchange("/api/books/" + bookId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 任务与已入块清净(级联);worker 超时后回写失败 → 任务行已不存在 → 无操作,不留尸块
        assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk", Integer.class)).isZero();
        STUB.embeddingsOk();
    }

    @Test
    void 跨书会话不受级联影响_D33() throws IOException {
        long bookId = uploadBook();
        configureStubModel();
        STUB.chatStream(List.of("回"));
        rest.exchange("/api/books/" + bookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "书内问题"), authJsonHeaders()), String.class);
        // 手工造跨书会话(book_id 空,M4 预留形态)+ 一条消息
        jdbc.update("INSERT INTO chat_session (book_id, title) VALUES (NULL, '跨书会话')");
        Long crossSessionId = jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE book_id IS NULL", Long.class);
        jdbc.update("INSERT INTO chat_message (session_id, role, content) VALUES (?, 'user', '跨书消息')",
                crossSessionId);

        ResponseEntity<Void> deleted = rest.exchange("/api/books/" + bookId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 跨书会话与其消息原样保留(D-33:不级联删)
        String crossTitle = jdbc.queryForObject(
                "SELECT title FROM chat_session WHERE id = ?", String.class, crossSessionId);
        assertThat(crossTitle).isEqualTo("跨书会话");
        Integer crossMessages = jdbc.queryForObject(
                "SELECT count(*) FROM chat_message WHERE session_id = ?", Integer.class, crossSessionId);
        assertThat(crossMessages).isEqualTo(1);
    }

    @Test
    void 删除端点无token一律401() {
        ResponseEntity<String> res = rest.exchange("/api/books/1", HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    /** 轮询至该书最新嵌入任务终态(done/failed)。 */
    private void awaitEmbeddingTerminal(long bookId) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM embedding_job WHERE book_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, bookId);
            if ("done".equals(status) || "failed".equals(status)) {
                assertThat(status).isEqualTo("done");
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("嵌入未在时限内到达终态");
    }

    private void configureStubModel() {
        rest.exchange("/api/settings/model", HttpMethod.PUT, new HttpEntity<>(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-t", "chatModel", "stub-chat"), authJsonHeaders()), String.class);
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

    private ResponseEntity<String> putJson(String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, authJsonHeaders()), String.class);
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
