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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阅读进度(M1-06,FR-203/D-24/D-19):单行读取 + 单条 upsert(重复覆盖不产生多行);
 * LWW 后写胜;书库列表进度百分比接通;404/400/401 口径一致。
 */
class ProgressIntegrationTest extends IntegrationTestBase {

    private long bookId;

    @BeforeEach
    void 上传一本书作为进度宿主() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(readFixture("normal.epub")) {
            @Override
            public String getFilename() {
                return "normal.epub";
            }
        });
        ResponseEntity<String> res = rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        bookId = ((Number) JsonPath.read(res.getBody(), "id")).longValue();
    }

    @Test
    void 无进度时读取返回404可读文案() {
        ResponseEntity<String> res = get("/api/books/" + bookId + "/progress");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("暂无阅读进度");
    }

    @Test
    void upsert后可读回_CFI与百分比原样存储() {
        ResponseEntity<String> res = put(Map.of("cfi", "epubcfi(/6/4!/4,/2,/6/1:18)", "percent", 34));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) JsonPath.read(res.getBody(), "bookId")).longValue()).isEqualTo(bookId);
        assertThat((String) JsonPath.read(res.getBody(), "cfi")).isEqualTo("epubcfi(/6/4!/4,/2,/6/1:18)");
        assertThat((Integer) JsonPath.read(res.getBody(), "percent")).isEqualTo(34);
        assertThat((String) JsonPath.read(res.getBody(), "updatedAt")).isNotBlank();
    }

    @Test
    void 重复upsert覆盖不产生多行_最终值为后写() {
        put(Map.of("cfi", "epubcfi(/6/4!/4,/2,/6/1:18)", "percent", 34));
        put(Map.of("cfi", "epubcfi(/6/6!/4,/1:0,/1:3)", "percent", 78)); // LWW:后写胜

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM reading_progress WHERE book_id = ?", Integer.class, bookId);
        assertThat(rows).isEqualTo(1);

        ResponseEntity<String> res = get("/api/books/" + bookId + "/progress");
        assertThat((String) JsonPath.read(res.getBody(), "cfi")).isEqualTo("epubcfi(/6/6!/4,/1:0,/1:3)");
        assertThat((Integer) JsonPath.read(res.getBody(), "percent")).isEqualTo(78);
    }

    @Test
    void 客户端伪造时间戳不参与裁决_updated_at为服务器时钟() {
        ResponseEntity<String> res = rest.exchange("/api/books/" + bookId + "/progress", HttpMethod.PUT,
                new HttpEntity<>(Map.of("cfi", "epubcfi(/6/2!/4/4)", "percent", 1,
                        "updatedAt", "1999-01-01T00:00:00Z"), authJsonHeaders()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) JsonPath.read(res.getBody(), "updatedAt"))
                .isNotEqualTo("1999-01-01T00:00:00Z")
                .startsWith("20");
    }

    @Test
    void 百分比越界或缺CFI返回400() {
        for (int bad : new int[]{-1, 101}) {
            ResponseEntity<String> res = put(Map.of("cfi", "epubcfi(/6)", "percent", bad));
            assertThat(res.getStatusCode()).as("percent=" + bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).contains("百分比");
        }

        ResponseEntity<String> noCfi = put(Map.of("percent", 10));
        assertThat(noCfi.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noCfi.getBody()).contains("CFI");
    }

    @Test
    void 书库列表返回真实进度百分比_无进度的书为空() throws IOException {
        uploadAnotherBook(); // 第二本书:无进度

        put(Map.of("cfi", "epubcfi(/6/4!/4,/2,/6/1:18)", "percent", 34));

        ResponseEntity<String> list = get("/api/books");
        List<Integer> percents = JsonPath.read(list.getBody(),
                "$[?(@.id == " + bookId + ")].progressPercent");
        assertThat(percents).containsExactly(34);
        List<Object> others = JsonPath.read(list.getBody(),
                "$[?(@.id != " + bookId + ")].progressPercent");
        assertThat(others).containsOnly((Object) null); // 无进度的书该字段为空
    }

    @Test
    void 进度端点无token一律401() {
        ResponseEntity<String> get = rest.getForEntity("/api/books/" + bookId + "/progress", String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> put = rest.exchange("/api/books/" + bookId + "/progress", HttpMethod.PUT,
                new HttpEntity<>(Map.of("cfi", "c", "percent", 1)), String.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 删除书籍时进度随外键级联清空() {
        put(Map.of("cfi", "epubcfi(/6/4!/4,/2,/6/1:18)", "percent", 34));
        jdbc.update("DELETE FROM book WHERE id = ?", bookId);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM reading_progress WHERE book_id = ?",
                Integer.class, bookId);
        assertThat(count).isZero();
    }

    // ---- helpers ----

    private void uploadAnotherBook() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(readFixture("font-obfuscated.epub")) {
            @Override
            public String getFilename() {
                return "font-obfuscated.epub";
            }
        });
        ResponseEntity<String> res = rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> put(Object body) {
        return rest.exchange("/api/books/" + bookId + "/progress", HttpMethod.PUT,
                new HttpEntity<>(body, authJsonHeaders()), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
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
