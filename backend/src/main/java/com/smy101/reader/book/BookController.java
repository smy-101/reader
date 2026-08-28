package com.smy101.reader.book;

import com.smy101.reader.book.dto.BookDetail;
import com.smy101.reader.book.dto.BookListItem;
import com.smy101.reader.book.dto.ChapterListItem;
import com.smy101.reader.book.dto.UploadBookResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private static final Map<String, MediaType> COVER_MEDIA_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.parseMediaType("image/webp"),
            "svg", MediaType.parseMediaType("image/svg+xml"));

    private final BookService bookService;

    /** 上传 EPUB(FR-101):同步解析 → 入库 → 落盘;响应含完整元数据。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadBookResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        return bookService.upload(file.getBytes());
    }

    /** 书库列表(FR-103):封面、标题、作者;进度百分比 M0 占位恒空,M1 接通。 */
    @GetMapping
    public List<BookListItem> listBooks() {
        return bookService.listAll().stream()
                .map(book -> new BookListItem(
                        book.getId(),
                        book.getTitle(),
                        book.getAuthor(),
                        book.getCoverPath() == null ? null : "/api/books/" + book.getId() + "/cover",
                        null))
                .toList();
    }

    @GetMapping("/{id}")
    public BookDetail detail(@PathVariable long id) {
        return bookService.detail(id);
    }

    /** 章节列表(按 seq 有序,D-40);正文不随列表下发。 */
    @GetMapping("/{id}/chapters")
    public List<ChapterListItem> chapters(@PathVariable long id) {
        return bookService.listChapters(id);
    }

    /** 封面文件服务(User Story 15):直接返回图片字节。 */
    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable long id) throws IOException {
        byte[] bytes = bookService.readCover(id);
        String ext = bookService.coverExtension(id);
        return ResponseEntity.ok()
                .contentType(COVER_MEDIA_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM))
                .body(bytes);
    }

    /** 书源文件下载(M1-04):渲染引擎带 token 程序化拉取完整 EPUB。 */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id) throws IOException {
        byte[] bytes = bookService.readBookFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/epub+zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("book-" + id + ".epub").build().toString())
                .body(bytes);
    }
}
