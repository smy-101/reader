package com.smy101.reader.book.dto;

/** 章节列表项(按 seq 有序);不含正文,正文端上从 EPUB 原文件渲染(M1,foliate-js)。 */
public record ChapterListItem(
        Long id,
        int seq,
        String title,
        String href,
        int textLength) {
}
