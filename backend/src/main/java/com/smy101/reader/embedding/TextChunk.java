package com.smy101.reader.embedding;

/**
 * 向量块(术语见 CONTEXT.md):章节正文切块后的文本单位。
 *
 * @param content    块文本(段落间以单换行连接)
 * @param tokenCount 近似 token 数(D-37,与上下文预算共用 {@link com.smy101.reader.chat.budget.TokenEstimator} 口径)
 */
public record TextChunk(String content, int tokenCount) {
}
