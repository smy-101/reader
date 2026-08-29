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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨书会话域(S4,Seam A,spec · Testing Decisions):全库纯向量多书检索(D-36)+
 * 全局提问端点(SSE 与书级同构)+ citations/refs 携书籍身份与书名快照 + 会话路由(D-32 精神)
 * + 前置两态可读错误 + 就绪集合静默排除 + 全局会话列表。embedding/chat 一律 stub,向量确定性。
 */
class CrossBookAskIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private long normalBookId;
    private long chibiBookId;

    @BeforeEach
    void 准备() throws IOException {
        STUB.chatStream(List.of("跨", "书", "回答"));
    }

    // ---- 主链路:两本书嵌入完成 → 跨书提问 ----

    @Test
    void 跨书提问_SSE同构_citations含书身份且随meta下发_refs落库带书名快照() throws IOException {
        prepareTwoEmbeddedBooks();

        // 问两本书各自词域的内容(袋向量确定性:赤壁词域命中《赤壁赋选》,代码脚注词域命中《fixture 正常书》)
        ResponseEntity<String> res = ask(Map.of("content", "赤壁泛舟与代码脚注两处分别怎么说?"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = res.getBody();
        assertThat(body).contains("event:meta").contains("event:delta").contains("event:done");

        // 引用随开场元数据事件下发(流式开始前即可渲染),携带书身份,命中跨两本书
        String meta = dataLine(body, "meta");
        List<Map<String, Object>> citations = readCitations(meta);
        assertThat(citations).isNotEmpty();
        Set<Long> citedBooks = new HashSet<>();
        for (Map<String, Object> citation : citations) {
            assertThat(citation.get("bookId")).isNotNull();
            assertThat((String) citation.get("bookTitle")).isNotBlank();
            citedBooks.add(((Number) citation.get("bookId")).longValue());
        }
        assertThat(citedBooks).containsExactlyInAnyOrder(normalBookId, chibiBookId);

        // 发给 chat 上游的 prompt 含多书检索块与〔书名·第 N 章〕溯源头
        String system = systemPrompt();
        assertThat(system).contains("检索到的相关段落");
        assertThat(system).contains("《赤壁赋选》·第");
        assertThat(system).contains("《fixture 正常书》·第");
        assertThat(system).contains("壬戌之秋");       // 赤壁赋选内容进了 prompt
        assertThat(system).contains("嵌套内层脚注");    // 正常书第三章独有内容进了 prompt

        // 助手消息 refs 落库带 bookId + 书名快照,重新拉取会话消息引用仍在
        String refs = jdbc.queryForObject(
                "SELECT refs::text FROM chat_message WHERE role = 'assistant'", String.class);
        assertThat(refs).contains("\"type\": \"retrieval\"")
                .contains("bookId").contains("bookTitle")
                .contains("赤壁赋选").contains("fixture 正常书");
        Long sessionId = ((Number) read(meta, "$.sessionId")).longValue();
        ResponseEntity<String> messages = rest.exchange("/api/sessions/" + sessionId + "/messages",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(messages.getBody()).contains("bookTitle");
    }

    @Test
    void 续问落同一跨书会话_缺省与显式id一致() throws IOException {
        prepareTwoEmbeddedBooks();
        long first = askGlobalForSessionId(Map.of("content", "赤壁泛舟与代码脚注两处分别怎么说?"));
        long second = askGlobalForSessionId(Map.of("content", "再展开讲讲?")); // 缺省 → 最近活跃 = first
        assertThat(second).isEqualTo(first);
        long explicit = askGlobalForSessionId(Map.of("content", "继续", "sessionId", first));
        assertThat(explicit).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_session", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message", Integer.class)).isEqualTo(6);
    }

    // ---- 前置两态可读错误 ----

    @Test
    void 前置_未配置embedding_400可读文案() throws IOException {
        prepareBookAndSettings(Map.of()); // 无 embeddingModel
        ResponseEntity<String> res = ask(Map.of("content", "q"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("尚未配置").contains("embedding");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_session", Integer.class)).isZero();
    }

    @Test
    void 前置_全库无就绪书_400可读文案() throws IOException {
        prepareBookAndSettings(Map.of("embeddingModel", "bge-m3")); // 配置了但从未嵌入
        ResponseEntity<String> res = ask(Map.of("content", "q"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("嵌入完成").contains("至少一本");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_session", Integer.class)).isZero();
    }

    // ---- 就绪集合裁决:未就绪书静默排除 ----

    @Test
    void 只嵌一本书_检索只命中该书_未嵌入书静默排除() throws IOException {
        // 正常书嵌入完成;第二本在未配置期上传(无任务)→ 不在就绪集合,静默排除
        prepareEmbeddedBook("normal.epub");
        saveSettings(Map.of()); // 去掉 embedding 配置:下一本上传不自动建任务
        chibiBookId = uploadBook("chibi.epub");
        saveSettings(Map.of("embeddingModel", "bge-m3")); // 重新配置回来(同模型,正常书仍就绪)

        ResponseEntity<String> res = ask(Map.of("content", "赤壁泛舟与代码脚注两处分别怎么说?"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> citations = readCitations(dataLine(res.getBody(), "meta"));
        assertThat(citations).isNotEmpty();
        // 命中全部来自就绪的正常书:即使问题带赤壁词域,chibi 块也不进检索范围
        assertThat(citations.stream().map(c -> ((Number) c.get("bookId")).longValue()))
                .allSatisfy(bookId -> assertThat(bookId).isEqualTo(normalBookId));
        String system = systemPrompt();
        assertThat(system).doesNotContain("《赤壁赋选》");
    }

    @Test
    void 模型已换的书被排除在检索外() throws IOException {
        prepareEmbeddedBook("normal.epub");        // bge-m3 嵌入完成
        saveSettings(Map.of("embeddingModel", "bge-m3-v2")); // 换模型 → 正常书不再就绪
        prepareEmbeddedBook("chibi.epub", "bge-m3-v2");      // chibi 以新模型嵌入完成

        ResponseEntity<String> res = ask(Map.of("content", "赤壁泛舟与代码脚注两处分别怎么说?"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> citations = readCitations(dataLine(res.getBody(), "meta"));
        assertThat(citations).isNotEmpty();
        assertThat(citations.stream().map(c -> ((Number) c.get("bookId")).longValue()))
                .allSatisfy(bookId -> assertThat(bookId).isEqualTo(chibiBookId));
        assertThat(systemPrompt()).doesNotContain("《fixture 正常书》");
    }

    // ---- 会话路由与列表 ----

    @Test
    void 显式会话id指向书级会话_400可读错误() throws IOException {
        prepareTwoEmbeddedBooks();
        // 先做一次书级提问造一个书级会话
        rest.exchange("/api/books/" + normalBookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "书级问题"), authJsonHeaders()), String.class);
        Long bookSessionId = jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE book_id = " + normalBookId, Long.class);

        ResponseEntity<String> res = ask(Map.of("content", "跨书问题", "sessionId", bookSessionId));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); // 与书级会话错配同口径(404 可读)
        assertThat(res.getBody()).contains("跨书会话");
    }

    @Test
    void 全局会话列表只含跨书会话_书级列表不含跨书会话() throws IOException {
        prepareTwoEmbeddedBooks();
        long globalId = askGlobalForSessionId(Map.of("content", "跨书对比问题"));
        rest.exchange("/api/books/" + normalBookId + "/ask", HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "书级问题"), authJsonHeaders()), String.class);
        Long bookSessionId = jdbc.queryForObject(
                "SELECT id FROM chat_session WHERE book_id = " + normalBookId, Long.class);

        ResponseEntity<String> globalList = rest.exchange("/api/sessions",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(globalList.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Number> globalIds = JsonPath.read(globalList.getBody(), "$[*].id");
        assertThat(globalIds.stream().mapToLong(Number::longValue)).containsExactly(globalId);

        ResponseEntity<String> bookList = rest.exchange("/api/books/" + normalBookId + "/sessions",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        List<Number> bookIds = JsonPath.read(bookList.getBody(), "$[*].id");
        assertThat(bookIds.stream().mapToLong(Number::longValue))
                .containsExactly(bookSessionId).doesNotContain(globalId);
    }

    @Test
    void 跨书会话可重命名可删除_经既有会话端点() throws IOException {
        prepareTwoEmbeddedBooks();
        long sessionId = askGlobalForSessionId(Map.of("content", "跨书对比问题"));

        ResponseEntity<String> renamed = rest.exchange("/api/sessions/" + sessionId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "改名后的跨书会话"), authJsonHeaders()), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(renamed.getBody(), "$.title")).isEqualTo("改名后的跨书会话");

        ResponseEntity<String> deleted = rest.exchange("/api/sessions/" + sessionId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message", Integer.class)).isZero();
    }

    // ---- D-33:删书留跨书会话(FK 天然行为钉死为契约) ----

    @Test
    void 删一本书_跨书会话与消息原样保留_refs书名快照不动() throws IOException {
        prepareTwoEmbeddedBooks();
        long sessionId = askGlobalForSessionId(Map.of("content", "赤壁泛舟与代码脚注两处分别怎么说?"));
        String refsBefore = jdbc.queryForObject(
                "SELECT refs::text FROM chat_message WHERE session_id = " + sessionId
                        + " AND role = 'assistant'", String.class);

        // 删掉其中一本(书级会话/向量块随书级联;跨书会话 book_id 为空,级联天然不触及)
        rest.exchange("/api/books/" + chibiBookId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_session WHERE id = " + sessionId, Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE session_id = " + sessionId, Integer.class))
                .isEqualTo(2); // 用户提问 + 助手回答原样
        assertThat(jdbc.queryForObject(
                "SELECT refs::text FROM chat_message WHERE session_id = " + sessionId
                        + " AND role = 'assistant'", String.class)).isEqualTo(refsBefore); // 快照不动

        // 删后再问:就绪集合收敛到剩余书,会话照常续(历史引用里悬空 bookId 不影响新提问)
        ResponseEntity<String> again = ask(Map.of("content", "再聊聊赤壁?", "sessionId", sessionId));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> citations = readCitations(dataLine(again.getBody(), "meta"));
        assertThat(citations.stream().map(c -> ((Number) c.get("bookId")).longValue()))
                .allSatisfy(bookId -> assertThat(bookId).isEqualTo(normalBookId));
    }

    // ---- 书库列表就绪摘要(US 25) ----

    @Test
    void 书库列表带嵌入就绪摘要_未配置全false() throws IOException {
        prepareTwoEmbeddedBooks();
        ResponseEntity<String> list = rest.exchange("/api/books", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        List<Map<String, Object>> books = JsonPath.read(list.getBody(), "$[*]");
        assertThat(books).hasSize(2);
        for (Map<String, Object> book : books) {
            @SuppressWarnings("unchecked")
            Map<String, Object> embedding = (Map<String, Object>) book.get("embedding");
            assertThat((String) embedding.get("status")).isEqualTo("done");
            assertThat((String) embedding.get("model")).isEqualTo("bge-m3");
            assertThat((Boolean) embedding.get("ready")).isTrue();
        }

        // 未配置 embedding(模型置空)→ ready 全 false(入口显隐据此隐藏,FR-403)
        saveSettings(Map.of("embeddingModel", ""));
        ResponseEntity<String> unconfigured = rest.exchange("/api/books", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        for (Map<String, Object> book : (List<Map<String, Object>>) JsonPath.read(unconfigured.getBody(), "$[*]")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> embedding = (Map<String, Object>) book.get("embedding");
            assertThat((Boolean) embedding.get("ready")).isFalse();
        }
    }

    // ---- 鉴权 ----

    @Test
    void 新端点不带token一律401() {
        ResponseEntity<String> ask = rest.postForEntity("/api/ask",
                new HttpEntity<>(Map.of("content", "q")), String.class);
        assertThat(ask.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> list = rest.getForEntity("/api/sessions", String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    /** 配置模型设置 + 上传第一本书;overrides 无 embeddingModel 时即未配置。 */
    private void prepareBookAndSettings(Map<String, Object> overrides) throws IOException {
        saveSettings(overrides);
        normalBookId = uploadBook("normal.epub");
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

    private long uploadBook(String fixture) throws IOException {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(readFixture(fixture)) {
            @Override
            public String getFilename() {
                return fixture;
            }
        });
        ResponseEntity<String> res = rest.postForEntity("/api/books", new HttpEntity<>(form, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) JsonPath.read(res.getBody(), "id")).longValue();
    }

    /** 配置 embedding + 上传指定书 + 等嵌入终态 done。 */
    private void prepareEmbeddedBook(String fixture) throws IOException {
        prepareEmbeddedBook(fixture, "bge-m3");
    }

    private void prepareEmbeddedBook(String fixture, String embeddingModel) throws IOException {
        saveSettings(Map.of("embeddingModel", embeddingModel));
        long bookId = uploadBook(fixture);
        if ("normal.epub".equals(fixture)) {
            normalBookId = bookId;
        } else {
            chibiBookId = bookId;
        }
        awaitEmbedded(bookId);
    }

    /** 两本书先后嵌入完成(同一就绪模型)。 */
    private void prepareTwoEmbeddedBooks() throws IOException {
        prepareEmbeddedBook("normal.epub");
        prepareEmbeddedBook("chibi.epub");
    }

    private void awaitEmbedded(long bookId) {
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
        throw new AssertionError("嵌入未在时限内完成 bookId=" + bookId);
    }

    private ResponseEntity<String> ask(Map<String, Object> body) {
        return rest.exchange("/api/ask", HttpMethod.POST,
                new HttpEntity<>(body, authJsonHeaders()), String.class);
    }

    private long askGlobalForSessionId(Map<String, Object> body) {
        ResponseEntity<String> res = ask(body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) read(dataLine(res.getBody(), "meta"), "$.sessionId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCitations(String metaJson) {
        return (List<Map<String, Object>>) read(metaJson, "$.citations");
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
