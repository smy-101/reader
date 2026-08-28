package com.smy101.reader.book;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smy101.reader.book.dto.BookDetail;
import com.smy101.reader.book.dto.ChapterListItem;
import com.smy101.reader.book.dto.UploadBookResponse;
import com.smy101.reader.book.epub.EpubParser;
import com.smy101.reader.book.epub.ParsedEpub;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 书籍入库与查询(术语见 CONTEXT.md:书籍/章节/书源文件/书库)。
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
        return bookMapper.selectList(new LambdaQueryWrapper<Book>()
                .orderByDesc(Book::getId)); // 新上传在前(书库默认序)
    }

    /** 详情;不存在抛 NoSuchElementException(→ 404)。 */
    public BookDetail detail(long id) {
        Book book = requireBook(id);
        Long count = chapterMapper.selectCount(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getBookId, id));
        return toDetail(book, count == null ? 0 : count.intValue());
    }

    public List<ChapterListItem> listChapters(long bookId) {
        requireBook(bookId);
        return chapterMapper.selectList(
                        new LambdaQueryWrapper<Chapter>()
                                .eq(Chapter::getBookId, bookId)
                                .orderByAsc(Chapter::getSeq))
                .stream()
                .map(c -> new ChapterListItem(c.getId(), c.getSeq(), c.getTitle(), c.getHref(), c.getTextLength()))
                .toList();
    }

    /** 封面文件内容;无封面或书不存在抛 NoSuchElementException(→ 404)。 */
    public byte[] readCover(long bookId) throws IOException {
        Book book = requireBook(bookId);
        if (book.getCoverPath() == null || !fileStorage.exists(book.getCoverPath())) {
            throw new NoSuchElementException("书籍无封面");
        }
        return fileStorage.read(book.getCoverPath());
    }

    /** 书源文件内容(M1-04);书不存在抛 NoSuchElementException(→ 404)。 */
    public byte[] readBookFile(long bookId) throws IOException {
        Book book = requireBook(bookId);
        return fileStorage.readBookFile(book.getFileHash());
    }

    /** 封面扩展名(决定响应 Content-Type);无封面返回 null。 */
    public String coverExtension(long bookId) {
        Book book = requireBook(bookId);
        if (book.getCoverPath() == null) {
            return null;
        }
        String path = book.getCoverPath();
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : path.substring(dot + 1);
    }

    public UploadBookResponse upload(byte[] epubBytes) {
        String hash = sha256Hex(epubBytes);

        Book existing = bookMapper.selectOne(
                new LambdaQueryWrapper<Book>().eq(Book::getFileHash, hash));
        if (existing != null) {
            return toResponse(existing, true);
        }

        ParsedEpub parsed = epubParser.parse(epubBytes);

        String coverPath = writeFiles(hash, parsed, epubBytes);

        Book book;
        try {
            book = transactionTemplate.execute(tx -> insertBookAndChapters(hash, parsed, coverPath, epubBytes));
        } catch (DuplicateKeyException e) {
            // 并发同 hash:另一请求已入库,退回幂等语义(D-30),文件同名无害
            Book winner = bookMapper.selectOne(
                    new LambdaQueryWrapper<Book>().eq(Book::getFileHash, hash));
            if (winner != null) {
                return toResponse(winner, true);
            }
            throw e;
        }
        return toResponse(book, false);
    }

    // ---- 内部 ----

    private Book requireBook(long id) {
        Book book = bookMapper.selectById(id);
        if (book == null) {
            throw new NoSuchElementException("书籍不存在");
        }
        return book;
    }

    private BookDetail toDetail(Book book, int chapterCount) {
        return new BookDetail(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getLanguage(),
                book.getCoverPath() == null ? null : "/api/books/" + book.getId() + "/cover",
                book.getFileHash(),
                book.getFileSize(),
                chapterCount);
    }

    /** 落盘书源文件与封面,返回封面相对路径(无封面为 null);布局知识只在 FileStorage。 */
    private String writeFiles(String hash, ParsedEpub parsed, byte[] epubBytes) {
        try {
            String coverPath = null;
            if (parsed.cover() != null) {
                coverPath = fileStorage.saveCover(hash, parsed.cover().bytes(), parsed.cover().extension());
            }
            fileStorage.saveBookFile(hash, epubBytes);
            return coverPath;
        } catch (IOException e) {
            throw new IllegalStateException("书源文件落盘失败", e);
        }
    }

    private Book insertBookAndChapters(String hash, ParsedEpub parsed, String coverPath, byte[] epubBytes) {
        Book book = new Book();
        book.setTitle(parsed.title());
        book.setAuthor(parsed.author());
        book.setLanguage(parsed.language());
        book.setCoverPath(coverPath);
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
