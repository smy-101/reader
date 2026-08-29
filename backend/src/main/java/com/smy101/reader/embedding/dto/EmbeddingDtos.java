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
}
