package com.smy101.reader.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smy101.reader.config.ReaderProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 embeddings 客户端(M4-02,D-28/D-12):POST {生效 base_url}/embeddings,
 * 非流式 JSON。分批有界(批大小可配)——大书不会一次打爆上游;
 * 与 {@link OpenAiCompatClient} 同一协议口径(URL 拼装 / 错误分类 / 可读中文文案)。
 */
@Component
public class OpenAiCompatEmbeddingsClient implements EmbeddingsClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /** 单批条数上限(分批有界) */
    private final int batchSize;
    /** 单批请求整体超时 */
    private final long requestTimeoutMs;

    public OpenAiCompatEmbeddingsClient(ReaderProperties properties) {
        this.batchSize = Math.max(1, properties.getLlm().getEmbeddingBatchSize());
        this.requestTimeoutMs = properties.getLlm().getEmbeddingRequestTimeoutMs();
    }

    @Override
    public List<float[]> embed(EmbeddingRequest request) {
        List<String> inputs = request.inputs();
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += batchSize) {
            List<String> batch = inputs.subList(start, Math.min(start + batchSize, inputs.size()));
            result.addAll(embedBatch(request, batch));
        }
        return result;
    }

    private List<float[]> embedBatch(EmbeddingRequest request, List<String> batch) {
        String url = LlmUrls.embeddingsUrl(request.baseUrl());
        String body;
        try {
            body = objectMapper.writeValueAsString(new EmbeddingsBody(request.model(), batch));
        } catch (IOException e) {
            throw new LlmException("构造 embedding 请求失败", e);
        }

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Authorization", "Bearer " + request.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new LlmException("Embedding Base URL 格式不正确:" + request.baseUrl(), e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException("Embedding 服务响应超时(" + (requestTimeoutMs / 1000) + "s 无响应),请重试", e);
        } catch (IOException e) {
            throw new LlmException("无法连接到 Embedding 服务:请检查模型设置里的 Base URL 与网络", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Embedding 请求被中断", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmException(classifyUpstreamStatus(response.statusCode()));
        }

        try {
            return parseEmbeddings(response.body(), batch.size());
        } catch (IOException e) {
            throw new LlmException("Embedding 服务响应格式异常,请确认服务为 OpenAI 兼容实现", e);
        }
    }

    /** 解析 {"data":[{"index":i,"embedding":[...]}]}:按 index 归位,保证与输入同序。 */
    private List<float[]> parseEmbeddings(String responseBody, int expected) throws IOException {
        JsonNode data = objectMapper.readTree(responseBody).path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IOException("data 条数(" + data.size() + ")与输入(" + expected + ")不一致");
        }
        float[][] ordered = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode vector = item.path("embedding");
            if (index < 0 || index >= expected || !vector.isArray()) {
                throw new IOException("data 条目缺 index/embedding");
            }
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            ordered[index] = values;
        }
        List<float[]> result = new ArrayList<>(expected);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new IOException("data 缺少 index 对应的向量");
            }
            result.add(vector);
        }
        return result;
    }

    private String classifyUpstreamStatus(int status) {
        return switch (status) {
            case 401, 403 -> "Embedding 服务认证失败(" + status + "):请检查模型设置里的 Embedding API key";
            case 404 -> "Embedding 服务接口不存在(404):请检查模型设置里的 Base URL";
            case 429 -> "Embedding 服务限流(429):请稍后重试";
            default -> "Embedding 服务返回异常状态码 " + status;
        };
    }

    /** OpenAI 兼容请求体(model + input 数组)。 */
    private record EmbeddingsBody(String model, List<String> input) {
    }
}
