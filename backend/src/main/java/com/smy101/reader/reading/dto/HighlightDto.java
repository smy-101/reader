package com.smy101.reader.reading.dto;

import java.time.OffsetDateTime;

/** 划线响应/创建请求体(CFI + 文字快照 + 颜色 + 备注 + 设备标识)。 */
public record HighlightDto(
        Long id,
        Long bookId,
        String cfi,
        String text,
        String note,
        String color,
        String device,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 创建请求体:无 id 与时间戳(updated_at 由服务器时钟统一打,D-19)。 */
    public record CreateRequest(String cfi, String text, String note, String color, String device) {
    }

    /** 更新请求体:颜色/备注,提供哪个改哪个;其余字段不可改。 */
    public record UpdateRequest(String color, String note) {
    }
}
