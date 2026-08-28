package com.smy101.reader.book.epub;

import java.util.List;

/** 一部 EPUB 解析后的全部结果(术语见 CONTEXT.md:书籍/章节/书源文件)。 */
public record ParsedEpub(
        String title,
        String author,
        String language,
        List<ParsedChapter> chapters,
        ParsedCover cover) {

    /** 章节:有正文的内容文件,按阅读顺序平铺(D-40)。 */
    public record ParsedChapter(
            int seq,
            String title,
            /** EPUB 内原文路径(如 OEBPS/ch1.xhtml) */
            String href,
            /** 清洗后的纯文本(D-40,ADR-0005) */
            String content) {
    }

    /** 封面图片;extension 为不含点号的扩展名(png/jpg/…)。 */
    public record ParsedCover(byte[] bytes, String extension) {
    }
}
