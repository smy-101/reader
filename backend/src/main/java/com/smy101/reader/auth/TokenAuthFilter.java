package com.smy101.reader.auth;

import com.smy101.reader.config.ReaderProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 静态 Bearer token 鉴权(D-4:单用户、无注册登录,token 为唯一防线)。
 * <p>
 * 拦截一切 HTTP 请求:Authorization 头缺失或不匹配 → 401 + 可读文案(spec User Story 11/14)。
 * token 比较用常量时间,避免时序侧信道逐字符试探。
 */
@Component
@RequiredArgsConstructor
public class TokenAuthFilter extends OncePerRequestFilter {

    private final ReaderProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String expected = properties.getAuth().getToken();
        String presented = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));

        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, presented)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"未授权:请携带正确的 Bearer token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 取 {@code Authorization: Bearer xxx} 的 token;其余情况返回 null。 */
    private String extractBearerToken(String header) {
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (header.length() <= prefix.length() || !header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return header.substring(prefix.length()).trim();
    }

    private boolean constantTimeEquals(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
