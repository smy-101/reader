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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 书源文件下载(M1-04):按书 id 流式返回 EPUB 原文件,渲染引擎的原料供给。
 * 内容与上传时落盘一致;404/401 与其他接口一致;token 只走请求头,不进 URL 参数。
 */
class BookFileDownloadIntegrationTest extends IntegrationTestBase {

    private long bookId;
    private byte[] epubBytes;

    @BeforeEach
    void 上传一本书作为下载源() throws IOException {
        epubBytes = BookUploadIntegrationTest.readFixture("normal.epub");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(epubBytes) {
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
    void 下载内容与上传时落盘的原文件逐字节一致() {
        ResponseEntity<byte[]> res = download("/api/books/" + bookId + "/file");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("application/epub+zip"));
        assertThat(res.getBody()).isEqualTo(epubBytes);
    }

    @Test
    void 响应带attachment文件名_便于端上另存() {
        ResponseEntity<byte[]> res = download("/api/books/" + bookId + "/file");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains(".epub");
    }

    @Test
    void 书不存在返回404可读文案() {
        ResponseEntity<String> res = rest.exchange("/api/books/99999/file", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("书籍不存在");
    }

    @Test
    void 无token或错token一律401() {
        for (HttpHeaders headers : List.of(new HttpHeaders(), wrongTokenHeaders())) {
            ResponseEntity<String> res = rest.exchange("/api/books/" + bookId + "/file", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            assertThat(res.getStatusCode()).as("headers=" + headers).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void token不进URL参数_查询串携带token仍401() {
        // 防线自查:若有人把 token 拼到 URL(会进日志/历史),必须仍然拒绝
        ResponseEntity<String> res = rest.getForEntity(
                "/api/books/" + bookId + "/file?token=" + TOKEN + "&access_token=" + TOKEN, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- helpers ----

    private ResponseEntity<byte[]> download(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), byte[].class);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return headers;
    }

    private HttpHeaders wrongTokenHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("wrong-token");
        return headers;
    }
}
