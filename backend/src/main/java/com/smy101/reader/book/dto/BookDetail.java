package com.smy101.reader.book.dto;

/** 书籍详情:入库元数据 + 章节数;正文不随详情下发。 */
public record BookDetail(
        Long id,
        String title,
        String author,
        String language,
        String coverUrl,
        String fileHash,
        long fileSize,
        int chapterCount) {
}
