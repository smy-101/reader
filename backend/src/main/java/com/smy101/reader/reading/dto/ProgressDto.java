package com.smy101.reader.reading.dto;

import java.time.OffsetDateTime;

/** 阅读进度(CFI + 百分比);updated_at 由服务器时钟统一打(D-19)。 */
public record ProgressDto(
        Long bookId,
        String cfi,
        int percent,
        OffsetDateTime updatedAt) {

    /** upsert 请求体:客户端只传 CFI 与百分比,不传时间戳。 */
    public record UpsertRequest(String cfi, Integer percent) {
    }
}
