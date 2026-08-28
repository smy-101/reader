package com.smy101.reader.book;

import com.smy101.reader.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传防线(FR-102/105,D-29/D-30):
 * 同 hash 幂等 / 损坏 400 / DRM 400;一切失败不建记录、不落文件。
 */
class UploadGuardrailsIntegrationTest extends IntegrationTestBase {

    @Test
    void 同file_hash重传幂等返回已存在书_不新增行与文件() throws IOException {
        byte[] epub = BookUploadIntegrationTest.readFixture("normal.epub");
        ResponseEntity<String> first = upload(epub, "normal.epub");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = upload(epub, "重命名再传.epub");
        assertThat(second.getStatusCode()).as("幂等路径必须 200,不是 409(FR-102)").isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(second.getBody(), "duplicate")).isTrue();
        assertThat(((Number) JsonPath.read(second.getBody(), "id")).longValue())
                .isEqualTo(((Number) JsonPath.read(first.getBody(), "id")).longValue());

        // 不重复解析、不新增行/文件
        assertThat(countTable("book")).isEqualTo(1);
        assertThat(countTable("chapter")).isEqualTo(4);
        assertThat(countFilesIn("books")).isEqualTo(1);
        assertThat(countFilesIn("covers")).isEqualTo(1);
    }

    @Test
    void 损坏文件400可读文案_不建记录不落盘() throws IOException {
        byte[] corrupt = BookUploadIntegrationTest.readFixture("corrupt.epub");

        ResponseEntity<String> res = upload(corrupt, "corrupt.epub");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("文件损坏");
        assertThat(res.getBody()).doesNotContain("DRM");
        assertNothingPersisted();
    }

    @Test
    void 疑似DRM书400_文案与损坏明确区分_不建记录不落盘() throws IOException {
        byte[] drm = BookUploadIntegrationTest.readFixture("drm.epub");

        ResponseEntity<String> res = upload(drm, "drm.epub");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("疑似 DRM 保护");
        assertThat(res.getBody()).doesNotContain("文件损坏");
        assertNothingPersisted();
    }

    @Test
    void 仅字体混淆的合法EPUB不误拦_正常导入() throws IOException {
        ResponseEntity<String> res = upload(BookUploadIntegrationTest.readFixture("font-obfuscated.epub"), "font.epub");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "duplicate")).isFalse();
        assertThat(countTable("chapter")).isEqualTo(3);
    }

    @Test
    void 失败后同一本正常书仍可上传成功() throws IOException {
        // 防线不污染后续上传(先坏后好)
        upload(BookUploadIntegrationTest.readFixture("corrupt.epub"), "corrupt.epub");
        ResponseEntity<String> res = upload(BookUploadIntegrationTest.readFixture("normal.epub"), "normal.epub");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countTable("book")).isEqualTo(1);
        assertThat(countTable("chapter")).isEqualTo(4);
    }

    // ---- helpers ----

    private ResponseEntity<String> upload(byte[] bytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return rest.postForEntity("/api/books", new HttpEntity<>(body, headers), String.class);
    }

    /** D-29:失败不留痕迹——book/chapter 零行、storage 下零文件。 */
    private void assertNothingPersisted() throws IOException {
        assertThat(countTable("book")).isZero();
        assertThat(countTable("chapter")).isZero();
        try (Stream<Path> walk = Files.walk(STORAGE_ROOT)) {
            assertThat(walk.filter(Files::isRegularFile).count()).isZero();
        }
    }

    private Integer countTable(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countFilesIn(String dir) throws IOException {
        Path path = STORAGE_ROOT.resolve(dir);
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> list = Files.list(path)) {
            return (int) list.filter(Files::isRegularFile).count();
        }
    }
}
