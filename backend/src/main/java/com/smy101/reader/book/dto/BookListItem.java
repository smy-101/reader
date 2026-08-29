package com.smy101.reader.book.dto;

import com.smy101.reader.embedding.dto.EmbeddingDtos;

/**
 * 书库列表项(FR-103):封面、标题、作者 + 进度百分比(M1 起接 reading_progress;无进度为 null);
 * S4 增嵌入就绪摘要(US 25:最新任务状态 + 是否就绪,全局 AI 入口显隐与状态卡同源消费)。
 */
public record BookListItem(
        Long id,
        String title,
        String author,
        String coverUrl,
        Integer progressPercent,
        EmbeddingDtos.EmbeddingSummary embedding) {
}
