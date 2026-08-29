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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话 CRUD(M3-03,FR-301/304):列表最近活跃排序(新消息/重命名刷新 updated_at)、
 * 消息拉取含 refs、重命名、删除(消息级联清);404/400/401 口径与全站一致。
 * 会话经提问链路产生(上游 stub 流式),同一通路才真实。
 */
class ChatSessionIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private long bookId;

    @BeforeEach
    void 准备书籍与模型设置() throws IOException {
        bookId = uploadBook();
        saveSettings(Map.of("baseUrl", STUB.baseUrl(), "apiKey", "sk-t", "chatModel", "stub-chat"));
        STUB.chatStream(List.of("回复"));
    }

    @Test
    void 会话列表按最近活跃排序_新提问刷新活跃度() {
        long first = askNewSession("第一场对话的问题");

        // 第二场会话直接落库(M3 无显式新建入口;缺省路由只会落最近活跃,不会新开)
        jdbc.update("INSERT INTO chat_session (book_id, title) VALUES (?, '第二场对话')", bookId);
        long second = jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE title = '第二场对话'", Long.class);

        // 向 second 提一问:它变最近活跃,排最前
        ask("{\"content\":\"继续第二场\",\"sessionId\":" + second + "}");
        ResponseEntity<String> list = get("/api/books/" + bookId + "/sessions");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Number> ids = JsonPath.read(list.getBody(), "$[*].id");
        assertThat(ids.stream().mapToLong(Number::longValue).toArray()).containsExactly(second, first);

        // 再向 first 提一问:次序反转
        ask("{\"content\":\"继续第一场\",\"sessionId\":" + first + "}");
        ResponseEntity<String> refreshed = get("/api/books/" + bookId + "/sessions");
        List<Number> reordered = JsonPath.read(refreshed.getBody(), "$[*].id");
        assertThat(reordered.stream().mapToLong(Number::longValue).toArray()).containsExactly(first, second);
    }

    @Test
    void 自动标题取首条提问截断_重命名可改() {
        String longQuestion = "这是一个非常非常非常非常非常非常非常非常非常非常非常长的提问要被截断成标题";
        long sessionId = askNewSession(longQuestion);

        ResponseEntity<String> list = get("/api/books/" + bookId + "/sessions");
        assertThat((String) JsonPath.read(list.getBody(), "$[0].title"))
                .startsWith("这是一个非常")
                .endsWith("…")
                .hasSizeLessThan(longQuestion.length());

        // 重命名(FR-304)
        ResponseEntity<String> renamed = exchange("/api/sessions/" + sessionId, HttpMethod.PATCH, Map.of("title", "方法论讨论"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(renamed.getBody(), "title")).isEqualTo("方法论讨论");
        assertThat((String) JsonPath.read(renamed.getBody(), "updatedAt")).isNotBlank();
    }

    @Test
    void 重命名空标题400_会话不存在404() {
        long sessionId = askNewSession("某问题");

        ResponseEntity<String> blank = exchange("/api/sessions/" + sessionId, HttpMethod.PATCH, Map.of("title", "  "));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody()).contains("标题");

        ResponseEntity<String> missing = exchange("/api/sessions/99999", HttpMethod.PATCH, Map.of("title", "x"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 消息拉取_用户与助手消息齐备() {
        long sessionId = askNewSession("带章节的问题");

        ResponseEntity<String> messages = get("/api/sessions/" + sessionId + "/messages");
        assertThat(messages.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> roles = JsonPath.read(messages.getBody(), "$[*].role");
        assertThat(roles).containsExactly("user", "assistant");
        assertThat((String) JsonPath.read(messages.getBody(), "$[0].content")).isEqualTo("带章节的问题");
        assertThat((String) JsonPath.read(messages.getBody(), "$[1].content")).isEqualTo("回复");
        assertThat((Object) JsonPath.read(messages.getBody(), "$[1].refs")).isNull();
    }

    @Test
    void 删除会话_消息级联清_再删404() {
        long sessionId = askNewSession("将删除的对话");

        ResponseEntity<Void> deleted = rest.exchange("/api/sessions/" + sessionId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer messages = jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE session_id = ?",
                Integer.class, sessionId);
        assertThat(messages).isZero();

        ResponseEntity<String> again = exchange("/api/sessions/" + sessionId, HttpMethod.DELETE, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> list = get("/api/books/" + bookId + "/sessions");
        assertThat((List<?>) JsonPath.read(list.getBody(), "$")).isEmpty();
    }

    @Test
    void 会话端点无token一律401() {
        for (var call : List.of(
                new Call(HttpMethod.GET, "/api/books/" + bookId + "/sessions"),
                new Call(HttpMethod.GET, "/api/sessions/1/messages"),
                new Call(HttpMethod.PATCH, "/api/sessions/1"),
                new Call(HttpMethod.DELETE, "/api/sessions/1"))) {
            ResponseEntity<String> response = rest.exchange(call.path(), call.method(),
                    new HttpEntity<>(jsonHeaders()), String.class);
            assertThat(response.getStatusCode()).as(call.toString()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ---- helpers ----

    private record Call(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private void saveSettings(Map<String, Object> body) {
        rest.exchange("/api/settings/model", HttpMethod.PUT, new HttpEntity<>(body, authJsonHeaders()), String.class);
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
        ResponseEntity<String> response = rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) JsonPath.read(response.getBody(), "id")).longValue();
    }

    /** 提问(缺省会话),返回完整 SSE 响应体。 */
    private ResponseEntity<String> ask(String json) {
        return rest.exchange("/api/books/" + bookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(json, authJsonHeaders()), String.class);
    }

    /** 提问并从 meta 事件取 sessionId。 */
    private long askNewSession(String content) {
        ResponseEntity<String> response = ask("{\"content\":\"" + content + "\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) JsonPath.read(metaLine(response.getBody()), "$.sessionId")).longValue();
    }

    /** 从 SSE 体内抠出首个 data: 行的 JSON(meta 事件)。 */
    private String metaLine(String sseBody) {
        return sseBody.lines().filter(l -> l.startsWith("data:"))
                .findFirst().orElseThrow()
                .substring("data:".length()).strip();
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body) {
        HttpEntity<?> entity = body == null ? new HttpEntity<>(authHeaders()) : new HttpEntity<>(body, authJsonHeaders());
        return rest.exchange(path, method, entity, String.class);
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
