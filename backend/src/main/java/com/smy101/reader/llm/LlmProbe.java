package com.smy101.reader.llm;

import com.smy101.reader.config.ReaderProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * LLM 服务连通探针(FR-405):向 OpenAI 兼容服务发最小请求(GET {base_url}/models)。
 * <p>
 * 后端代理口径:端上不直连 LLM,探针从后端发出;超时可配(reader.llm.probe-timeout-ms)。
 * 分类文案:连接失败 / 超时 / 401(API key)/ 404(地址)各自可读。
 */
@Component
public class LlmProbe {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final long timeoutMs;

    public LlmProbe(ReaderProperties properties) {
        this.timeoutMs = properties.getLlm().getProbeTimeoutMs();
    }

    /** 探测 {baseUrl}/models;返回 ok 或可读中文错误。 */
    public ProbeResult probe(String baseUrl, String apiKey) {
        String url;
        try {
            url = LlmUrls.modelsUrl(baseUrl);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return classify(response.statusCode());
        } catch (HttpTimeoutException e) {
            return ProbeResult.failure("连接超时:服务在 " + timeoutMs + "ms 内未响应,请检查 Base URL 与网络");
        } catch (IllegalArgumentException e) {
            return ProbeResult.failure("Base URL 格式不正确:" + baseUrl);
        } catch (IOException e) {
            return ProbeResult.failure("无法连接到 " + baseUrl + ":请确认地址正确、服务可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.failure("探测被中断");
        }
    }

    private ProbeResult classify(int status) {
        if (status >= 200 && status < 300) {
            return ProbeResult.success();
        }
        return switch (status) {
            case 401, 403 -> ProbeResult.failure(
                    "认证失败(" + status + "):API key 无效或未授权,请检查 API key");
            case 404 -> ProbeResult.failure(
                    "接口不存在(404):Base URL 可能不正确,应形如 https://api.example.com/v1");
            default -> ProbeResult.failure("服务返回异常状态码 " + status);
        };
    }

    public record ProbeResult(boolean ok, String message) {
        public static ProbeResult success() {
            return new ProbeResult(true, "连接成功");
        }

        public static ProbeResult failure(String message) {
            return new ProbeResult(false, message);
        }
    }
}
