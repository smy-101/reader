package com.smy101.reader.book;

import com.smy101.reader.book.dto.BookListItem;
import com.smy101.reader.book.dto.UploadBookResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    /** 上传 EPUB(FR-101):同步解析 → 入库 → 落盘;响应含完整元数据。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadBookResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        return bookService.upload(file.getBytes());
    }

    @GetMapping
    public List<BookListItem> listBooks() {
        return bookService.listAll().stream()
                .map(book -> new BookListItem(
                        book.getId(),
                        book.getTitle(),
                        book.getAuthor(),
                        book.getCoverPath() == null ? null : "/api/books/" + book.getId() + "/cover",
                        null)) // 进度占位,M1 接通
                .toList();
    }
}
