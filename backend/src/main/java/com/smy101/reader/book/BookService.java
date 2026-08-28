package com.smy101.reader.book;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smy101.reader.book.dto.UploadBookResponse;
import com.smy101.reader.book.epub.EpubParser;
import com.smy101.reader.book.epub.ParsedEpub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 书籍入库(术语见 CONTEXT.md:书籍/章节/书源文件/书库)。
 * <p>
 * 上传流程(FR-101,D-29 先解析后落盘 / D-30 同 hash 幂等 / D-41 同步解析):
 * hash → 查重(命中即返回已存在书,不再解析落盘)→ 解析 → 文件落盘 → 事务内入库。
 * 文件先于 DB 写:入库失败至多残留同名 hash 文件(无害、可覆盖重试);
 * 反之会出现有书无文件的记录。
 */
@Service
@RequiredArgsConstructor
public class BookService {

    private final BookMapper bookMapper;
    private final ChapterMapper chapterMapper;
    private final EpubParser epubParser;
    private final FileStorage fileStorage;
    private final TransactionTemplate transactionTemplate;

    public List<Book> listAll() {
        return bookMapper.selectList(null);
    }

    public UploadBookResponse upload(byte[] epubBytes) {
        String hash = sha256Hex(epubBytes);

        Book existing = bookMapper.selectOne(
                new LambdaQueryWrapper<Book>().eq(Book::getFileHash, hash));
        if (existing != null) {
            return toResponse(existing, true);
        }

        ParsedEpub parsed = epubParser.parse(epubBytes);

        writeFiles(hash, parsed, epubBytes);

        Book book = transactionTemplate.execute(tx -> insertBookAndChapters(hash, parsed, epubBytes));
        return toResponse(book, false);
    }

    // ---- 内部 ----

    private void writeFiles(String hash, ParsedEpub parsed, byte[] epubBytes) {
        try {
            if (parsed.cover() != null) {
                fileStorage.saveCover(hash, parsed.cover().bytes(), parsed.cover().extension());
            }
            fileStorage.saveBookFile(hash, epubBytes);
        } catch (IOException e) {
            throw new IllegalStateException("书源文件落盘失败", e);
        }
    }

    private Book insertBookAndChapters(String hash, ParsedEpub parsed, byte[] epubBytes) {
        Book book = new Book();
        book.setTitle(parsed.title());
        book.setAuthor(parsed.author());
        book.setLanguage(parsed.language());
        book.setCoverPath(parsed.cover() == null ? null : "covers/" + hash + "." + parsed.cover().extension());
        book.setFileHash(hash);
        book.setFileSize((long) epubBytes.length);
        bookMapper.insert(book);

        for (ParsedEpub.ParsedChapter chapter : parsed.chapters()) {
            Chapter row = new Chapter();
            row.setBookId(book.getId());
            row.setSeq(chapter.seq());
            row.setTitle(chapter.title());
            row.setHref(chapter.href());
            row.setContent(chapter.content());
            row.setTextLength(chapter.content().length());
            chapterMapper.insert(row);
        }
        return book;
    }

    private UploadBookResponse toResponse(Book book, boolean duplicate) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getBookId, book.getId())
                        .orderByAsc(Chapter::getSeq));
        return new UploadBookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getLanguage(),
                book.getCoverPath() == null ? null : "/api/books/" + book.getId() + "/cover",
                book.getFileHash(),
                book.getFileSize(),
                duplicate,
                chapters.stream()
                        .map(c -> new UploadBookResponse.ChapterSummary(
                                c.getSeq(), c.getTitle(), c.getHref(), c.getTextLength()))
                        .toList());
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
