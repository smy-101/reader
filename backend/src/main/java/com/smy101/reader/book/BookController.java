package com.smy101.reader.book;

import com.smy101.reader.book.dto.BookListItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

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
