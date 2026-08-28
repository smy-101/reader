package com.smy101.reader.book;

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
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 书库读路径(FR-103,User Story 7/8/15):列表 / 详情 / 章节列表(按 seq)/ 封面;
 * 未知 id 一律 404;读接口同样受 token 保护。
 */
class LibraryQueryIntegrationTest extends IntegrationTestBase {

    private long bookId;
    private String fileHash;

    @BeforeEach
    void 上传一本书作为读侧数据() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(BookUploadIntegrationTest.readFixture("normal.epub")) {
            @Override
            public String getFilename() {
                return "normal.epub";
            }
        });
        ResponseEntity<String> res = rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        bookId = ((Number) JsonPath.read(res.getBody(), "id")).longValue();
        fileHash = JsonPath.read(res.getBody(), "fileHash");
    }

    @Test
    void 书库列表返回封面URL_标题_作者_进度占位为空() {
        ResponseEntity<String> res = get("/api/books");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) JsonPath.read(res.getBody(), "$")).hasSize(1);
        assertThat((String) JsonPath.read(res.getBody(), "$[0].title")).isEqualTo("fixture 正常书");
        assertThat((String) JsonPath.read(res.getBody(), "$[0].author")).isEqualTo("张三");
        assertThat((String) JsonPath.read(res.getBody(), "$[0].coverUrl")).isEqualTo("/api/books/" + bookId + "/cover");
        assertThat((Object) JsonPath.read(res.getBody(), "$[0].progressPercent")).isNull();
    }

    @Test
    void 书籍详情返回完整元数据() {
        ResponseEntity<String> res = get("/api/books/" + bookId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) JsonPath.read(res.getBody(), "id")).longValue()).isEqualTo(bookId);
        assertThat((String) JsonPath.read(res.getBody(), "title")).isEqualTo("fixture 正常书");
        assertThat((String) JsonPath.read(res.getBody(), "author")).isEqualTo("张三");
        assertThat((String) JsonPath.read(res.getBody(), "language")).isEqualTo("zh-CN");
        assertThat((String) JsonPath.read(res.getBody(), "fileHash")).isEqualTo(fileHash);
        assertThat((Integer) JsonPath.read(res.getBody(), "chapterCount")).isEqualTo(3);
    }

    @Test
    void 章节列表按seq有序_与入库内容一致() {
        ResponseEntity<String> res = get("/api/books/" + bookId + "/chapters");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Integer> seqs = JsonPath.read(res.getBody(), "$[?(@.seq)].seq");
        assertThat(seqs).containsExactly(1, 2, 3);
        assertThat((String) JsonPath.read(res.getBody(), "$[0].title")).isEqualTo("第一章 起点");
        assertThat((String) JsonPath.read(res.getBody(), "$[0].href")).isEqualTo("OEBPS/ch1.xhtml");
        // 列表不携带正文,避免书库浏览拖全文
        assertThat(res.getBody()).doesNotContain("\"content\"");
    }

    @Test
    void 封面URL直接返回图片字节() throws IOException, NoSuchAlgorithmException {
        ResponseEntity<byte[]> res = rest.exchange(
                "/api/books/" + bookId + "/cover", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), byte[].class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(res.getBody()).isEqualTo(coverBytes());
    }

    @Test
    void 未知id的详情_章节_封面一律404_文案可读() {
        for (String path : List.of("/api/books/99999", "/api/books/99999/chapters", "/api/books/99999/cover")) {
            ResponseEntity<String> res = get(path);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(res.getBody()).as(path).contains("书籍不存在");
        }
    }

    @Test
    void 读接口同样受token保护() {
        ResponseEntity<String> res = rest.getForEntity("/api/books/" + bookId + "/chapters", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<byte[]> cover = rest.getForEntity("/api/books/" + bookId + "/cover", byte[].class);
        assertThat(cover.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return headers;
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private byte[] coverBytes() throws IOException, NoSuchAlgorithmException {
        byte[] epub = BookUploadIntegrationTest.readFixture("normal.epub");
        String hash = BookUploadIntegrationTest.sha256Hex(epub);
        return java.nio.file.Files.readAllBytes(STORAGE_ROOT.resolve("covers/" + hash + ".png"));
    }
}
