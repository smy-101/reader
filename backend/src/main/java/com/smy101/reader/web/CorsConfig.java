package com.smy101.reader.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 全局 CORS(M2 桌面壳,spec · Implementation Decisions):
 * <ul>
 * <li>放行<b>任意 origin</b>:静态 token 是唯一防线(R-8 已接受),壳 origin 各平台不一
 * (Windows WebView2 为 {@code http://tauri.localhost}),白名单徒增维护——取舍见 ADR-0006。</li>
 * <li><b>不放行 credentials</b>(无 Access-Control-Allow-Credentials 头):鉴权走 Authorization
 * 头而非 cookie,浏览器侧无 cookie 可滥用。</li>
 * <li>放行 Authorization / Content-Type 头与 GET/POST/PUT/DELETE/OPTIONS 方法。</li>
 * </ul>
 * 实现要点:以 {@link FilterRegistrationBean} 把 {@link CorsFilter} 挂在过滤器链<b>最前</b>
 * (先于 {@code TokenAuthFilter}),由此两件事自然成立——
 * <ol>
 * <li>preflight OPTIONS 由 CORS 层直接应答,不进鉴权(浏览器 preflight 不携带凭据,不得被 401 误杀);</li>
 * <li>一切下游响应(含鉴权 401)都会带上 CORS 头,跨域下的可读错误文案浏览器才读得到。</li>
 * </ol>
 * CORS 与鉴权正交:跨域不带 token 仍 401,见 CorsIntegrationTest。
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(CorsConfiguration.ALL));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
