package com.smy101.reader.reading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 划线:选中文字产生的高亮(可带颜色/备注),以 CFI 定位,LWW 同步(术语见 CONTEXT.md)。 */
@Data
@TableName("highlight")
public class Highlight {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookId;

    /** EPUB CFI(区间),foliate-js 产出 */
    private String cfi;

    /** 选中文字快照(展示与 CFI 失效时降级文案用) */
    private String text;

    private String note;
    private String color;

    /** 端上自报设备标识,仅展示/追溯,不参与裁决 */
    private String device;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
