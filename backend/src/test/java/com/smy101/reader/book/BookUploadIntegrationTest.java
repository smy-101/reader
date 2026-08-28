package com.smy101.reader.book;

import com.smy101.reader.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EPUB 上传全链路(spec User Story 1/6/8/9/10/12):
 * 响应元数据、book/chapter 行、磁盘文件三者齐备;清洗口径符合 D-40。
 */
class BookUploadIntegrationTest extends IntegrationTestBase {

    @Test
    void 上传正常EPUB_响应元数据_入库_落盘三者齐备() throws IOException, NoSuchAlgorithmException {
        byte[] epub = readFixture("normal.epub");
        String hash = sha256Hex(epub);

        ResponseEntity<String> res = upload(epub, "normal.epub");

        // 响应:完整元数据,前端可直接展示导入结果(User Story 12)
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = res.getBody();
        assertThat((String) JsonPath.read(body, "title")).isEqualTo("fixture 正常书");
        assertThat((String) JsonPath.read(body, "author")).isEqualTo("张三");
        assertThat((String) JsonPath.read(body, "language")).isEqualTo("zh-CN");
        assertThat((String) JsonPath.read(body, "fileHash")).isEqualTo(hash);
        assertThat((Integer) JsonPath.read(body, "fileSize")).isEqualTo(epub.length);
        assertThat((Boolean) JsonPath.read(body, "duplicate")).isFalse();
        assertThat((String) JsonPath.read(body, "coverUrl")).isEqualTo("/api/books/" + JsonPath.read(body, "id") + "/cover");

        // 章节:仅"有正文的内容文件"入库,nav 不产生章节(D-40,User Story 9)
        List<String> seqs = JsonPath.read(body, "chapters[?(@.href != null)].href");
        assertThat(seqs).containsExactly("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/ch3.xhtml");
        assertThat((String) JsonPath.read(body, "chapters[0].title")).isEqualTo("第一章 起点");

        // DB:1 行 book + 3 行 chapter
        assertThat(countTable("book")).isEqualTo(1);
        assertThat(countTable("chapter")).isEqualTo(3);

        // 磁盘:书源文件与封面(User Story 6)
        assertThat(Files.readAllBytes(STORAGE_ROOT.resolve("books/" + hash + ".epub"))).isEqualTo(epub);
        assertThat(Files.exists(STORAGE_ROOT.resolve("covers/" + hash + ".png"))).isTrue();

        // book 行的 cover_path 指向落盘封面
        String coverPath = jdbc.queryForObject("SELECT cover_path FROM book", String.class);
        assertThat(coverPath).isEqualTo("covers/" + hash + ".png");
    }

    @Test
    void 章节正文按D40口径清洗_丢图_表格拍平_脚注并章末_代码原样() throws IOException {
        ResponseEntity<String> res = upload(readFixture("normal.epub"), "normal.epub");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 第一章:标题来自 nav,段落保留
        String ch1 = chapterContent(1);
        assertThat(ch1).contains("第一章 起点");
        assertThat(ch1).contains("这是第一章的正文第一段");
        assertThat(ch1).contains("这是第二段。段落之间应当以空行分隔。");

        // 第二章:图片(含 alt 文字)被丢;表格拍平为文本;前后段落保留
        String ch2 = chapterContent(2);
        assertThat(ch2).doesNotContain("插图");           // alt 属于图片,丢弃
        assertThat(ch2).doesNotContain("cover.png");      // src 丢弃
        assertThat(ch2).contains("本章包含一张图片,清洗后应被丢弃,本段文字保留。");
        assertThat(ch2).contains("语言 年份");            // 表头行拍平
        assertThat(ch2).contains("Java 1995");
        assertThat(ch2).contains("Go 2009");
        assertThat(ch2).contains("表格之后仍有一段正文。");

        // 第三章:代码块原样保留(含换行);脚注正文不在原位置、并入章末
        String ch3 = chapterContent(3);
        assertThat(ch3).contains("public class Main {\n    public static void main(String[] args) {");
        assertThat(ch3).contains("System.out.println(\"hello\");");
        assertThat(ch3.indexOf("这是脚注一的正文内容。"))
                .as("脚注并入章末,应出现在末段正文之后")
                .isGreaterThan(ch3.indexOf("脚注元素本体不应出现在原位置"));
    }

    @Test
    void text_length等于清洗后正文字符数() throws IOException {
        upload(readFixture("normal.epub"), "normal.epub");

        for (int seq = 1; seq <= 3; seq++) {
            String content = chapterContent(seq);
            Integer textLength = jdbc.queryForObject(
                    "SELECT text_length FROM chapter WHERE seq = ?", Integer.class, seq);
            assertThat(textLength).isEqualTo(content.length());
        }
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

    private String chapterContent(int seq) {
        return jdbc.queryForObject(
                "SELECT content FROM chapter WHERE seq = ? ORDER BY book_id", String.class, seq);
    }

    private Integer countTable(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static byte[] readFixture(String name) throws IOException {
        return new ClassPathResource("fixtures/" + name).getInputStream().readAllBytes();
    }

    static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
