package com.smy101.reader.reading;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smy101.reader.book.Book;
import com.smy101.reader.book.BookMapper;
import com.smy101.reader.reading.dto.HighlightDto;
import com.smy101.reader.reading.dto.ProgressDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 阅读同步域:划线 CRUD + 单行进度 upsert(术语见 CONTEXT.md,D-24 全量拉取 / D-19 LWW 服务器时钟)。
 * <p>
 * 冲突一律整行 LWW 后写胜:updated_at 全部由 DB now() 打,客户端时间戳不参与裁决;
 * 双端同开接受"重开该书才可见"(D-44,无推送/轮询)。
 */
@Service
@RequiredArgsConstructor
public class ReadingService {

    private final HighlightMapper highlightMapper;
    private final ReadingProgressMapper progressMapper;
    private final BookMapper bookMapper;

    // ---- 划线 ----

    /** 按书全量拉取(打开书一次拿齐,D-24),按创建顺序稳定排序。 */
    public List<HighlightDto> listHighlights(long bookId) {
        requireBook(bookId);
        return highlightMapper.selectList(new LambdaQueryWrapper<Highlight>()
                        .eq(Highlight::getBookId, bookId)
                        .orderByAsc(Highlight::getId))
                .stream().map(this::toDto).toList();
    }

    /** 书库列表用:全部书的进度百分比(book_id → percent)。 */
    public Map<Long, Integer> progressPercentByBookId() {
        return progressMapper.selectList(null).stream()
                .collect(Collectors.toMap(ReadingProgress::getBookId, ReadingProgress::getPercent));
    }

    /** 创建划线:CFI + 文字快照必填;颜色/备注/设备标识可选。 */
    public HighlightDto createHighlight(long bookId, HighlightDto.CreateRequest request) {
        requireBook(bookId);
        requireText(request == null ? null : request.cfi(), "划线 CFI 不能为空");
        requireText(request.text(), "划线文字快照不能为空");

        Highlight row = new Highlight();
        row.setBookId(bookId);
        row.setCfi(request.cfi().strip());
        row.setText(request.text());
        row.setNote(request.note());
        row.setColor(request.color());
        row.setDevice(request.device());
        highlightMapper.insert(row);
        return toDto(highlightMapper.selectById(row.getId()));
    }

    /** 单条更新(颜色/备注,提供哪个改哪个);整行 LWW,updated_at 服务器时钟。 */
    public HighlightDto updateHighlight(long id, HighlightDto.UpdateRequest request) {
        Highlight row = requireHighlight(id);
        LambdaUpdateWrapper<Highlight> update = new LambdaUpdateWrapper<Highlight>()
                .eq(Highlight::getId, id)
                .setSql("updated_at = now()"); // DB now(),服务器时钟(D-19),不以参数绑定字符串
        if (request != null && request.color() != null) update.set(Highlight::getColor, request.color());
        if (request != null && request.note() != null) update.set(Highlight::getNote, request.note());
        highlightMapper.update(null, update);
        return toDto(highlightMapper.selectById(row.getId()));
    }

    /** 单条删除;不存在抛 NoSuchElementException(→ 404)。 */
    public void deleteHighlight(long id) {
        requireHighlight(id);
        highlightMapper.deleteById(id);
    }

    // ---- 进度 ----

    /** 按书单行读取;该书还没有进度时抛 NoSuchElementException(→ 404,端上从书首开始)。 */
    public ProgressDto getProgress(long bookId) {
        requireBook(bookId);
        ReadingProgress row = progressMapper.selectById(bookId);
        if (row == null) {
            throw new NoSuchElementException("暂无阅读进度");
        }
        return new ProgressDto(row.getBookId(), row.getCfi(), row.getPercent(), row.getUpdatedAt());
    }

    /** 单条 upsert(重复覆盖,不产生多行);CFI + 百分比必填,percent ∈ [0,100]。 */
    public ProgressDto upsertProgress(long bookId, ProgressDto.UpsertRequest request) {
        requireBook(bookId);
        requireText(request == null ? null : request.cfi(), "进度 CFI 不能为空");
        if (request.percent() == null || request.percent() < 0 || request.percent() > 100) {
            throw new IllegalArgumentException("进度百分比必须是 0-100 的整数");
        }

        ReadingProgress row = new ReadingProgress();
        row.setBookId(bookId);
        row.setCfi(request.cfi().strip());
        row.setPercent(request.percent());
        progressMapper.upsert(row);
        ReadingProgress saved = progressMapper.selectById(bookId);
        return new ProgressDto(saved.getBookId(), saved.getCfi(), saved.getPercent(), saved.getUpdatedAt());
    }

    // ---- 内部 ----

    private Book requireBook(long id) {
        Book book = bookMapper.selectById(id);
        if (book == null) {
            throw new NoSuchElementException("书籍不存在");
        }
        return book;
    }

    private Highlight requireHighlight(long id) {
        Highlight row = highlightMapper.selectById(id);
        if (row == null) {
            throw new NoSuchElementException("划线不存在");
        }
        return row;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private HighlightDto toDto(Highlight row) {
        return new HighlightDto(row.getId(), row.getBookId(), row.getCfi(), row.getText(),
                row.getNote(), row.getColor(), row.getDevice(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
