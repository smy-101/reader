package com.smy101.reader.llm;

import java.util.List;

/**
 * LLM 流式对话适配口(FR-402:仅实现 OpenAI 兼容协议,预留 Adapter;
 * 不做 Anthropic/Google 原生协议)。
 * <p>
 * 契约:阻塞直至流结束(正常返回 = 流完成);内容经 {@link StreamListener#onDelta} 回调;
 * listener 回调抛出的异常原样向上传播(终止流);上游失败(连接/超时/断流/非 2xx)抛
 * {@link LlmException},message 为可读中文文案。
 */
public interface LlmAdapter {

    void streamChat(ChatCompletionRequest request, StreamListener listener);

    /** OpenAI 兼容 chat completions 请求(stream=true)。 */
    record ChatCompletionRequest(
            String baseUrl,
            String apiKey,
            String model,
            List<LlmMessage> messages) {
    }

    record LlmMessage(String role, String content) {
    }

    interface StreamListener {

        /** 一段增量文本(可能为空串,忽略即可)。 */
        void onDelta(String text);
    }
}
