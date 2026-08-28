package com.smy101.reader.auth;

import com.smy101.reader.IntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** 鉴权行为(spec User Story 11):无 token / 错 token 一律 401,正确 token 放行。 */
class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void 不带token的请求一律401() {
        ResponseEntity<String> res = rest.getForEntity("/api/books", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 错误token的请求401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("wrong-token");

        ResponseEntity<String> res = rest.exchange("/api/books", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 正确token放行并返回空书库() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);

        ResponseEntity<String> res = rest.exchange("/api/books", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("[]");
    }
}
