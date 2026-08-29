package com.smy101.reader.web;

import com.smy101.reader.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局 CORS(M2 桌面壳,spec User Story 13–16):
 * 放行任意 origin 的跨域调用,静态 token 鉴权与 CORS 正交——跨域不带 token 仍 401,preflight 不被鉴权误杀。
 * 口径:不放行 credentials(鉴权走 Authorization 头而非 cookie)。
 */
class CorsIntegrationTest extends IntegrationTestBase {

    /** 桌面壳 WebView2 的页面 origin 形态 */
    private static final String ORIGIN = "http://tauri.localhost";

    /** 浏览器真实形态的 preflight 头:小写头名、无 Authorization(preflight 不携带凭据)。 */
    private HttpHeaders preflightHeaders(String method, String requestHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, ORIGIN);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (requestHeaders != null) {
            headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        }
        return headers;
    }

    @Test
    void preflight握手被放行并返回正确CORS头_且不需token() {
        ResponseEntity<String> res = rest.exchange("/api/books", HttpMethod.OPTIONS,
                new HttpEntity<>(preflightHeaders("GET", "authorization, content-type")), String.class);

        // preflight 由 CORS 层直接应答:2xx,绝不因缺 token 被 401 拦截(浏览器语义)
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
        assertThat(lower(res.getHeaders().getAccessControlAllowHeaders()))
                .contains("authorization", "content-type");
        assertThat(allowMethods(res)).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    @Test
    void preflight对POST上传同样成立() {
        ResponseEntity<String> res = rest.exchange("/api/books", HttpMethod.OPTIONS,
                new HttpEntity<>(preflightHeaders("POST", "authorization, content-type")), String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(allowMethods(res)).contains("POST");
    }

    @Test
    void 跨域实际请求带token_业务行为与同源一致() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        headers.set(HttpHeaders.ORIGIN, ORIGIN);

        ResponseEntity<String> res = rest.exchange("/api/books", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
        assertThat(res.getBody()).isEqualTo("[]"); // 与同源请求(见 AuthIntegrationTest)完全一致
    }

    @Test
    void 跨域下的错误响应_文案与同源一致且带CORS头() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        headers.set(HttpHeaders.ORIGIN, ORIGIN);

        ResponseEntity<String> res = rest.exchange("/api/books/999", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
        // 404 文案与同源口径一致:浏览器跨域下可读,UI 无需任何跨域特判
        assertThat(res.getBody()).contains("error");
    }

    @Test
    void 跨域不带token_仍401且响应可被浏览器读取() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, ORIGIN);

        ResponseEntity<String> res = rest.exchange("/api/books", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 文案与同源 401 完全一致(TokenAuthFilter 直出)
        assertThat(res.getBody()).isEqualTo("{\"error\":\"未授权:请携带正确的 Bearer token\"}");
        // 401 响应也必须带 CORS 头,否则浏览器把可读文案拦成 CORS 网络错误
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
    }

    @Test
    void 不放行credentials_无AllowCredentials头() {
        // 注:直接断言原始头而非 HttpHeaders 访问器——6.2 起访问器返回原始 boolean,头缺失时不再返回 null
        ResponseEntity<String> preflight = rest.exchange("/api/books", HttpMethod.OPTIONS,
                new HttpEntity<>(preflightHeaders("GET", "authorization")), String.class);
        assertThat(preflight.getHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        headers.set(HttpHeaders.ORIGIN, ORIGIN);
        ResponseEntity<String> actual = rest.exchange("/api/books", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(actual.getHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    private static List<String> lower(List<String> headers) {
        return headers == null ? List.of() : headers.stream().map(h -> h.toLowerCase().trim()).toList();
    }

    private static List<String> allowMethods(ResponseEntity<String> res) {
        String raw = res.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
        return raw == null ? List.of() : Arrays.stream(raw.split(",")).map(String::trim).toList();
    }
}
