package com.smy101.reader.llm;

import java.util.List;

/**
 * Embeddings 适配口(FR-402 / D-28 / D-12):OpenAI 兼容 POST {生效 base_url}/embeddings,
 * 与 chat 同一协议族。embedding 独立 base_url/api_key(空 = 跟随 chat)由调用方
 * 解析生效值后传入;本口不感知设置存储。
 * <p>
 * 契约:阻塞直至全部批次完成;返回与输入<b>同序</b>的向量数组;
 * 上游失败(连接/超时/非 2xx)抛 {@link LlmException},message 为可读中文文案。
 */
public interface EmbeddingsClient {

    /**
     * 批量嵌入:inputs 在实现内分批有界(大书不打爆上游),逐批调用上游,
     * 拼回与输入同序的结果。
     */
    List<float[]> embed(EmbeddingRequest request);

    /** OpenAI 兼容 embeddings 请求(生效的 base_url / api_key / model + 待嵌文本)。 */
    record EmbeddingRequest(
            String baseUrl,
            String apiKey,
            String model,
            List<String> inputs) {
    }
}
