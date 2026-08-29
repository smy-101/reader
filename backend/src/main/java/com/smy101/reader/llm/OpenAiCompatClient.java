package com.smy101.reader.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smy101.reader.config.ReaderProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI 兼容 chat completions 客户端(FR-402,D-12):POST {base_url}/chat/completions,
 * stream=true,SSE 逐块回调。JDK HttpClient 零新依赖;上游空闲超时经 watchdog 关流
 * (HttpClient 对流式响应无逐块读超时,挂死流必须显式断开,前端才不悬挂——FR-303)。
 */
@Component
public class OpenAiCompatClient implements LlmAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /** 上游相邻数据块之间的最大等待(超时关流 → LlmException 超时文案) */
    private final long idleTimeoutMs;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-stream-watchdog");
        t.setDaemon(true);
        return t;
    });

    public OpenAiCompatClient(ReaderProperties properties) {
        this.idleTimeoutMs = properties.getLlm().getStreamIdleTimeoutMs();
    }

    @Override
    public void streamChat(ChatCompletionRequest request, StreamListener listener) {
        String url = LlmUrls.chatUrl(request.baseUrl());
        String body;
        try {
            body = objectMapper.writeValueAsString(new ChatCompletionsBody(
                    request.model(),
                    request.messages().stream().map(m -> new ChatCompletionsBody.Message(m.role(), m.content())).toList(),
                    true));
        } catch (IOException e) {
            throw new LlmException("构造 AI 请求失败", e);
        }

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(idleTimeoutMs)) // 响应头超时;流中空闲由 watchdog 兜底
                    .header("Authorization", "Bearer " + request.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new LlmException("Base URL 格式不正确:" + request.baseUrl(), e);
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new LlmException("AI 服务响应超时(" + formatMillis(idleTimeoutMs) + " 无响应),请重试", e);
        } catch (IOException e) {
            throw new LlmException("无法连接到 AI 服务:请检查模型设置里的 Base URL 与网络", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("AI 请求被中断", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmException(classifyUpstreamStatus(response.statusCode()));
        }

        AtomicLong lastChunkAt = new AtomicLong(System.nanoTime());
        InputStream stream = response.body();
        // watchdog:相邻块间隔超时则强制断流(读循环抛 IOException → 断流/超时文案)
        var future = watchdog.scheduleAtFixedRate(() -> {
            long idleNanos = System.nanoTime() - lastChunkAt.get();
            if (idleNanos > Duration.ofMillis(idleTimeoutMs).toNanos()) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 已关
                }
            }
        }, idleTimeoutMs, Math.max(200, idleTimeoutMs / 4), TimeUnit.MILLISECONDS);

        boolean completed = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (!line.startsWith("data:")) {
                    continue; // 注释行/事件名行等非数据行
                }
                lastChunkAt.set(System.nanoTime());
                String payload = line.substring("data:".length()).strip();
                if (payload.equals("[DONE]")) {
                    completed = true;
                    return;
                }
                String delta = extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    listener.onDelta(delta);
                }
            }
            // 流结束但没等到 [DONE]:上游断流
            throw new LlmException("AI 服务连接中断,请重试");
        } catch (IOException e) {
            // watchdog 关流(空闲超时)与真实网络断流都走到这里;区分:看是否超时
            long idleNanos = System.nanoTime() - lastChunkAt.get();
            if (idleNanos > Duration.ofMillis(idleTimeoutMs).toNanos()) {
                throw new LlmException("AI 服务响应超时(" + formatMillis(idleTimeoutMs) + " 无新内容),请重试");
            }
            throw new LlmException("AI 服务连接中断,请重试", e);
        } finally {
            future.cancel(false);
            if (!completed) {
                // 未正常完成也要关掉底层流,避免连接泄漏
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 已关
                }
            }
        }
    }

    /** 解析 data: {...choices[0].delta.content...};解析失败或非文本(如收尾块的 content:null)返回 null(跳过该块)。 */
    private String extractDelta(String payload) {
        try {
            JsonNode content = objectMapper.readTree(payload).path("choices").path(0).path("delta").path("content");
            return content.isTextual() ? content.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String classifyUpstreamStatus(int status) {
        return switch (status) {
            case 401, 403 -> "AI 服务认证失败(" + status + "):请检查模型设置里的 API key";
            case 404 -> "AI 服务接口不存在(404):请检查模型设置里的 Base URL";
            case 429 -> "AI 服务限流(429):请稍后重试";
            default -> "AI 服务返回异常状态码 " + status;
        };
    }

    private String formatMillis(long ms) {
        return ms >= 1000 ? (ms / 1000) + "s" : ms + "ms";
    }

    /** OpenAI 兼容请求体(messages + stream)。 */
    private record ChatCompletionsBody(String model, List<Message> messages, boolean stream) {
        private record Message(String role, String content) {
        }
    }
}
