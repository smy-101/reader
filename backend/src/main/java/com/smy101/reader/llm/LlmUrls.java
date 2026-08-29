package com.smy101.reader.llm;

/** OpenAI 兼容 URL 拼装(chat completions / models 探针共用)。 */
final class LlmUrls {

    private LlmUrls() {
    }

    /** {base}/models(测试连接探针,FR-405)。 */
    static String modelsUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + "/models";
    }

    /** {base}/chat/completions(流式对话)。 */
    static String chatUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + "/chat/completions";
    }

    /** {base}/embeddings(向量化,M4-02)。 */
    static String embeddingsUrl(String baseUrl) {
        return trimTrailingSlash(baseUrl) + "/embeddings";
    }

    private static String trimTrailingSlash(String baseUrl) {
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
