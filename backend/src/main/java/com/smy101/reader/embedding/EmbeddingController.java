package com.smy101.reader.embedding;

import com.smy101.reader.embedding.dto.EmbeddingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 嵌入任务 API(M4-02):状态查询 + 触发(一入口多态:首次嵌入 / 失败重试 / 换模型全量重嵌入)。
 * 均受既有 token 拦截(401 防线一致)。
 */
@RestController
@RequiredArgsConstructor
public class EmbeddingController {

    private final EmbeddingJobService jobService;

    /** 某书嵌入状态(最新任务):none / pending / running / done / failed + 进度与错误。 */
    @GetMapping("/api/books/{bookId}/embedding")
    public EmbeddingDtos.StatusDto status(@PathVariable long bookId) {
        return jobService.status(bookId);
    }

    /** 触发嵌入:未配置 embedding → 400 可读文案;并发触发被串行语义吸收。 */
    @PostMapping("/api/books/{bookId}/embedding/trigger")
    public EmbeddingDtos.StatusDto trigger(@PathVariable long bookId) {
        return jobService.trigger(bookId);
    }
}
