package com.smy101.reader.reading;

import com.smy101.reader.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 划线 CRUD(M1-06,FR-202/D-24/D-19):按书全量拉取、创建、单条改颜色备注、单条删;
 * LWW 后写胜且 updated_at 为服务器时钟;404/400/401 与全站口径一致。
 */
class HighlightIntegrationTest extends IntegrationTestBase {

    private long bookId;

    @BeforeEach
    void 上传一本书作为划线宿主() throws IOException {
        bookId = uploadBook();
    }

    @Test
    void 创建划线返回完整字段_含服务器时间戳与设备标识() {
        ResponseEntity<String> res = create("""
                {"cfi":"epubcfi(/6/4!/4,/4/1:18,/6/1:16)","text":"选中文字",
                 "color":"yellow","note":"批注","device":"web-abc"}""");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) JsonPath.read(res.getBody(), "id")).longValue()).isNotNull();
        assertThat(((Number) JsonPath.read(res.getBody(), "bookId")).longValue()).isEqualTo(bookId);
        assertThat((String) JsonPath.read(res.getBody(), "cfi")).isEqualTo("epubcfi(/6/4!/4,/4/1:18,/6/1:16)");
        assertThat((String) JsonPath.read(res.getBody(), "text")).isEqualTo("选中文字");
        assertThat((String) JsonPath.read(res.getBody(), "color")).isEqualTo("yellow");
        assertThat((String) JsonPath.read(res.getBody(), "note")).isEqualTo("批注");
        assertThat((String) JsonPath.read(res.getBody(), "device")).isEqualTo("web-abc");
        assertThat((String) JsonPath.read(res.getBody(), "createdAt")).isNotBlank();
        assertThat((String) JsonPath.read(res.getBody(), "updatedAt")).isNotBlank();
    }

    @Test
    void 颜色与备注可省略_设备标识可省略() {
        ResponseEntity<String> res = create("""
                {"cfi":"epubcfi(/6/4!/4,/1:0,/1:5)","text":"无颜色无备注"}""");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Object) JsonPath.read(res.getBody(), "color")).isNull();
        assertThat((Object) JsonPath.read(res.getBody(), "note")).isNull();
        assertThat((Object) JsonPath.read(res.getBody(), "device")).isNull();
    }

    @Test
    void 按书全量拉取_创建顺序稳定() {
        create(highlightJson("cfi-a", "文字甲"));
        create(highlightJson("cfi-b", "文字乙"));
        create(highlightJson("cfi-c", "文字丙"));

        ResponseEntity<String> res = exchange("/api/books/" + bookId + "/highlights", HttpMethod.GET, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cfis = JsonPath.read(res.getBody(), "$[?(@.cfi)].cfi");
        assertThat(cfis).containsExactly("cfi-a", "cfi-b", "cfi-c");
    }

    @Test
    void 单条更新颜色与备注_LWW两次更新后写胜() {
        long id = createAndReturnId(highlightJson("cfi-a", "文字甲", "yellow"));

        ResponseEntity<String> first = exchange("/api/highlights/" + id, HttpMethod.PUT,
                Map.of("color", "red", "note", "第一次批注"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(first.getBody(), "color")).isEqualTo("red");

        // 第二次写(后写胜):改备注,不动颜色
        ResponseEntity<String> second = exchange("/api/highlights/" + id, HttpMethod.PUT,
                Map.of("note", "第二次批注"));
        assertThat((String) JsonPath.read(second.getBody(), "note")).isEqualTo("第二次批注");
        assertThat((String) JsonPath.read(second.getBody(), "color")).isEqualTo("red"); // 未提供的字段保持

        // 最终值 = 后写
        ResponseEntity<String> list = exchange("/api/books/" + bookId + "/highlights", HttpMethod.GET, null);
        assertThat((String) JsonPath.read(list.getBody(), "$[0].note")).isEqualTo("第二次批注");
        assertThat((String) JsonPath.read(list.getBody(), "$[0].color")).isEqualTo("red");
    }

    @Test
    void 更新后updated_at由服务器时钟推进_客户端伪造时间戳被忽略() throws InterruptedException {
        long id = createAndReturnId(highlightJson("cfi-a", "文字甲"));
        ResponseEntity<String> beforeRes = exchange("/api/books/" + bookId + "/highlights", HttpMethod.GET, null);
        String before = (String) JsonPath.read(beforeRes.getBody(), "$[0].updatedAt");

        Thread.sleep(10); // 保证服务器时钟可观测推进
        // 客户端试图传时间戳:未知字段被忽略,不参与裁决(D-19)
        ResponseEntity<String> updated = exchange("/api/highlights/" + id, HttpMethod.PUT,
                Map.of("color", "blue", "updatedAt", "1999-01-01T00:00:00Z", "createdAt", "1999-01-01T00:00:00Z"));
        String after = (String) JsonPath.read(updated.getBody(), "updatedAt");

        assertThat(after).isNotEqualTo("1999-01-01T00:00:00Z");
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void 删除后全量拉取不再包含_再删返回404() {
        long id = createAndReturnId(highlightJson("cfi-a", "文字甲"));

        ResponseEntity<Void> deleted = rest.exchange("/api/highlights/" + id, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> list = exchange("/api/books/" + bookId + "/highlights", HttpMethod.GET, null);
        assertThat((List<?>) JsonPath.read(list.getBody(), "$")).isEmpty();

        ResponseEntity<String> again = exchange("/api/highlights/" + id, HttpMethod.DELETE, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(again.getBody()).contains("划线不存在");
    }

    @Test
    void 缺CFI或缺文字快照返回400可读文案() {
        ResponseEntity<String> noCfi = create("{\"text\":\"没有 CFI\"}");
        assertThat(noCfi.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noCfi.getBody()).contains("CFI");

        ResponseEntity<String> noText = create("{\"cfi\":\"epubcfi(/6)\"}");
        assertThat(noText.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noText.getBody()).contains("文字快照");
    }

    @Test
    void 书不存在或划线不存在一律404() {
        ResponseEntity<String> createMissingBook = exchange("/api/books/99999/highlights", HttpMethod.POST,
                Map.of("cfi", "c", "text", "t"));
        assertThat(createMissingBook.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(createMissingBook.getBody()).contains("书籍不存在");

        ResponseEntity<String> updateMissing = exchange("/api/highlights/99999", HttpMethod.PUT,
                Map.of("color", "red"));
        assertThat(updateMissing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> listMissingBook = exchange("/api/books/99999/highlights", HttpMethod.GET, null);
        assertThat(listMissingBook.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 划线端点无token一律401() {
        for (var call : List.of(
                new Call(HttpMethod.GET, "/api/books/" + bookId + "/highlights"),
                new Call(HttpMethod.POST, "/api/books/" + bookId + "/highlights"),
                new Call(HttpMethod.PUT, "/api/highlights/1"),
                new Call(HttpMethod.DELETE, "/api/highlights/1"))) {
            ResponseEntity<String> res = rest.exchange(call.path(), call.method(), new HttpEntity<>(jsonHeaders()), String.class);
            assertThat(res.getStatusCode()).as(call.toString()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void 删除书籍时划线随外键级联清空() {
        create(highlightJson("cfi-a", "文字甲"));
        jdbc.update("DELETE FROM book WHERE id = ?", bookId);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM highlight WHERE book_id = ?",
                Integer.class, bookId);
        assertThat(count).isZero();
    }

    // ---- helpers ----

    private record Call(HttpMethod method, String path) {
    }

    private long uploadBook() throws IOException {
        HttpHeaders headers = authHeaders();
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

    private String highlightJson(String cfi, String text) {
        return highlightJson(cfi, text, null);
    }

    private String highlightJson(String cfi, String text, String color) {
        String colorPart = color == null ? "" : ",\"color\":\"" + color + "\"";
        return "{\"cfi\":\"" + cfi + "\",\"text\":" + jsonQuote(text) + colorPart + "}";
    }

    private String jsonQuote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private ResponseEntity<String> create(String json) {
        return rest.exchange("/api/books/" + bookId + "/highlights", HttpMethod.POST,
                new HttpEntity<>(json, authJsonHeaders()), String.class);
    }

    private long createAndReturnId(String json) {
        ResponseEntity<String> res = create(json);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) JsonPath.read(res.getBody(), "id")).longValue();
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body) {
        HttpEntity<?> entity = body == null
                ? new HttpEntity<>(authHeaders())
                : new HttpEntity<>(body, authJsonHeaders());
        return rest.exchange(path, method, entity, String.class);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return headers;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.set(org.springframework.http.HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.CONTENT_TYPE, org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }
}
