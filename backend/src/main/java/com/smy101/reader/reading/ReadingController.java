package com.smy101.reader.reading;

import com.smy101.reader.reading.dto.HighlightDto;
import com.smy101.reader.reading.dto.ProgressDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 阅读同步 API(M1-06):划线全量拉取/创建/单条改/单条删 + 进度单行读/upsert。
 * 均受既有 token 拦截;错误一律 {"error": 可读文案}。
 */
@RestController
@RequiredArgsConstructor
public class ReadingController {

    private final ReadingService readingService;

    // ---- 划线 ----

    /** 按书全量拉取(D-24):打开书一次拿齐。 */
    @GetMapping("/api/books/{bookId}/highlights")
    public List<HighlightDto> listHighlights(@PathVariable long bookId) {
        return readingService.listHighlights(bookId);
    }

    @PostMapping("/api/books/{bookId}/highlights")
    public ResponseEntity<HighlightDto> createHighlight(@PathVariable long bookId,
                                                        @RequestBody HighlightDto.CreateRequest request) {
        HighlightDto created = readingService.createHighlight(bookId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 单条更新(颜色/备注);整行 LWW,后写胜。 */
    @PutMapping("/api/highlights/{id}")
    public HighlightDto updateHighlight(@PathVariable long id,
                                        @RequestBody HighlightDto.UpdateRequest request) {
        return readingService.updateHighlight(id, request);
    }

    @DeleteMapping("/api/highlights/{id}")
    public ResponseEntity<Void> deleteHighlight(@PathVariable long id) {
        readingService.deleteHighlight(id);
        return ResponseEntity.noContent().build();
    }

    // ---- 进度 ----

    /** 按书单行读取;无进度 → 404 "暂无阅读进度"(端上从书首开始)。 */
    @GetMapping("/api/books/{bookId}/progress")
    public ProgressDto getProgress(@PathVariable long bookId) {
        return readingService.getProgress(bookId);
    }

    /** 单条 upsert(重复覆盖不产生多行);CFI + percent 由前端 foliate-js 产出,服务端原样存储。 */
    @PutMapping("/api/books/{bookId}/progress")
    public ProgressDto upsertProgress(@PathVariable long bookId,
                                      @RequestBody ProgressDto.UpsertRequest request) {
        return readingService.upsertProgress(bookId, request);
    }
}
