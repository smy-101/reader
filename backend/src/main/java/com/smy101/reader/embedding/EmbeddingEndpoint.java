package com.smy101.reader.embedding;

import com.smy101.reader.settings.ModelSettings;

/**
 * Embedding 生效端点(D-28):embedding 独立 base_url/api_key,空 = 跟随 chat;
 * 未配置 embedding 模型(null)时 {@link #from} 返回 null——一切 embedding 相关入口
 * 以此为"未配置"的单一裁决。
 */
public record EmbeddingEndpoint(String baseUrl, String apiKey, String model) {

    /** 从已保存模型设置解析生效端点;未配置 embedding 模型返回 null。 */
    public static EmbeddingEndpoint from(ModelSettings settings) {
        if (settings == null || settings.getEmbeddingModel() == null
                || settings.getEmbeddingModel().isBlank()) {
            return null;
        }
        return new EmbeddingEndpoint(
                firstNonBlank(settings.getEmbeddingBaseUrl(), settings.getBaseUrl()),
                firstNonBlank(settings.getEmbeddingApiKey(), settings.getApiKey()),
                settings.getEmbeddingModel());
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback == null ? "" : fallback;
    }
}
