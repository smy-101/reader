package com.smy101.reader.book;

import com.smy101.reader.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-105:上传超过大小上限 → 413。
 * 用缩小后的 multipart 上限(1MB)驱动真实 Tomcat + multipart 解析路径,
 * 与生产 100MB 上限走的是同一代码路径(spring.servlet.multipart.max-file-size)。
 */
class UploadLimitIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void smallUploadLimit(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.max-file-size", () -> "1MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "2MB");
    }

    @Test
    void 超过大小上限快速返回413可读文案() {
        byte[] big = new byte[1024 * 1024 + 100]; // 1MB + 100B,超过测试上限
        new Random(42).nextBytes(big);

        ResponseEntity<String> res = upload(big, "big.epub");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody()).contains("超过");
        // 不入库、不落盘
        assertThat(countTable("book")).isZero();
        assertThat(countTable("chapter")).isZero();
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

    private Integer countTable(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
