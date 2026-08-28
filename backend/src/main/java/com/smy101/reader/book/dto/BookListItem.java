package com.smy101.reader.book.dto;

/**
 * 书库列表项(FR-103):封面、标题、作者 + 进度百分比(M1 起接 reading_progress;无进度为 null)。
 */
public record BookListItem(
        Long id,
        String title,
        String author,
        String coverUrl,
        Integer progressPercent
) {
}
