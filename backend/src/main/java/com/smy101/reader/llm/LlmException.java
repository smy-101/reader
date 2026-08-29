package com.smy101.reader.llm;

/**
 * 上游 LLM 失败;message 可读中文,直接面向用户(FR-303:错误显式下发,不悬挂)。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
