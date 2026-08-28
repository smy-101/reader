package com.smy101.reader.book;

import com.smy101.reader.config.ReaderProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 书源文件与封面的落盘(D-29:先解析后落盘;目录口径见 .gitignore 与 ADR-0002 部署记录)。
 * <p>
 * 布局(相对 reader.storage.root):{@code books/<file_hash>.epub}、{@code covers/<file_hash>.<ext>}。
 * 文件名由 file_hash 决定:同书幂等(D-30)、重写无害,失败残留不产生书籍记录。
 */
@Component
public class FileStorage {

    private static final String BOOKS_DIR = "books";
    private static final String COVERS_DIR = "covers";

    private final Path root;

    public FileStorage(ReaderProperties properties) {
        this.root = Path.of(properties.getStorage().getRoot());
    }

    /** 保存书源文件,返回相对 root 的路径(books/<hash>.epub)。 */
    public String saveBookFile(String fileHash, byte[] bytes) throws IOException {
        return write(BOOKS_DIR + "/" + fileHash + ".epub", bytes);
    }

    /** 保存封面,返回相对 root 的路径(covers/<hash>.<ext>)。 */
    public String saveCover(String fileHash, byte[] bytes, String extension) throws IOException {
        return write(COVERS_DIR + "/" + fileHash + "." + extension, bytes);
    }

    /** 按 book.cover_path(相对 root)读取文件内容。 */
    public byte[] read(String relativePath) throws IOException {
        return Files.readAllBytes(root.resolve(relativePath));
    }

    /** 文件是否存在(book.cover_path 非空但文件缺失时不至于 500)。 */
    public boolean exists(String relativePath) {
        return Files.exists(root.resolve(relativePath));
    }

    private String write(String relativePath, byte[] bytes) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return relativePath;
    }
}
