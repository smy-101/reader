package com.smy101.reader.book;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 章节:EPUB 中有正文的内容文件,按阅读顺序平铺(D-40);嵌套目录不入库。
 */
@Data
@TableName("chapter")
public class Chapter {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookId;

    /** spine 阅读顺序,从 1 起 */
    private Integer seq;

    private String title;

    /** EPUB 内原文路径(如 OEBPS/ch1.xhtml) */
    private String href;

    /** 清洗后的纯文本(D-40,ADR-0005) */
    private String content;

    /** content 字符数 */
    private Integer textLength;
}
