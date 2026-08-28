package com.smy101.reader.book.dto;

/**
 * 书库列表项(FR-103):封面、标题、作者 + 进度百分比。
 * progressPercent 为占位字段,M0 恒为 null,待 M1 阅读进度接通后填充。
 */
public record BookListItem(
        Long id,
        String title,
        String author,
        String coverUrl,
        Integer progressPercent
) {
}
