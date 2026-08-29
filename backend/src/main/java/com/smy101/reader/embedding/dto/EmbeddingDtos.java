package com.smy101.reader.embedding.dto;

import java.time.OffsetDateTime;

/** 嵌入状态/触发端点响应(单一形状);status: none / pending / running / done / failed。 */
public interface EmbeddingDtos {

    record StatusDto(
            long bookId,
            String status,
            String model,
            Integer chunkDone,
            Integer chunkTotal,
            String error,
            OffsetDateTime updatedAt) {

        /** 未嵌入(未建任务;含"未配置 embedding"时的状态查询口径)。 */
        public static StatusDto none(long bookId) {
            return new StatusDto(bookId, "none", null, null, null, null, null);
        }
    }

    /**
     * 书库列表项的嵌入就绪摘要(S4,US 25):每书最新任务状态 + 就绪裁决。
     * ready = done 且模型与当前 embedding 配置一致(S3 单书与 S4 全库同一裁决口径);
     * 前端的全局 AI 入口显隐与嵌入状态卡同源消费,不逐书轮询拼判断。
     */
    record EmbeddingSummary(String status, String model, boolean ready) {
    }
}
