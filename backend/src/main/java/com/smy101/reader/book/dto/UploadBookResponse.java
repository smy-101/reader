package com.smy101.reader.book.dto;

import java.util.List;

/**
 * 上传响应(FR-101/FR-102):入库书籍的完整元数据 + 章节概览。
 * duplicate=true 表示同 file_hash 已在书库(幂等返回,D-30),前端据此提示"已在书库"。
 */
public record UploadBookResponse(
        Long id,
        String title,
        String author,
        String language,
        String coverUrl,
        String fileHash,
        long fileSize,
        boolean duplicate,
        List<ChapterSummary> chapters) {

    public record ChapterSummary(int seq, String title, String href, int textLength) {
    }
}
