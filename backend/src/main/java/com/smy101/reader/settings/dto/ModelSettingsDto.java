package com.smy101.reader.settings.dto;

import java.time.OffsetDateTime;

/**
 * 模型设置 DTO(FR-401:5+2 项):api key 明文回显(FR-404 已接受姿态)。
 * 可空项(null)语义:上下文上限 = 8k 保守(D-27);embedding 独立配置 = 跟随 chat(D-28)。
 */
public record ModelSettingsDto(
        Integer id,
        String baseUrl,
        String apiKey,
        String chatModel,
        Integer chatContextTokens,
        String embeddingModel,
        String embeddingBaseUrl,
        String embeddingApiKey,
        OffsetDateTime updatedAt) {

    /** 保存请求(PUT 与测试连接 POST /test 复用;测试连接用未保存的表单值探测)。 */
    public record SaveRequest(
            String baseUrl,
            String apiKey,
            String chatModel,
            Integer chatContextTokens,
            String embeddingModel,
            String embeddingBaseUrl,
            String embeddingApiKey) {
    }

    /** 测试连接结果:chat 与 embedding 两探针各自判定(FR-405)。 */
    public record TestConnectionResult(ProbeOutcome chat, ProbeOutcome embedding) {
    }

    /**
     * 单探针结果:ok / skipped(仅 embedding:未配置明示跳过)/ message 可读中文文案。
     */
    public record ProbeOutcome(boolean ok, boolean skipped, String message) {
        public static ProbeOutcome success() {
            return new ProbeOutcome(true, false, "连接成功");
        }

        public static ProbeOutcome failure(String message) {
            return new ProbeOutcome(false, false, message);
        }
    }
}
