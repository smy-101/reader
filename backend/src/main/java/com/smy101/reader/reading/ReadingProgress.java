package com.smy101.reader.reading;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 阅读进度:每本书单行的接续位置(CFI + 百分比),跨端同步(术语见 CONTEXT.md)。 */
@Data
@TableName("reading_progress")
public class ReadingProgress {

    /** book_id 即主键(每书单行) */
    @TableId
    private Long bookId;

    private String cfi;

    /** 0-100 整数,仅展示用;接续定位靠 CFI */
    private Integer percent;

    private OffsetDateTime updatedAt;
}
