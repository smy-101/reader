package com.smy101.reader.book;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 书籍:服务端登记的一部 EPUB 的元数据(术语见 CONTEXT.md)。 */
@Data
@TableName("book")
public class Book {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String author;
    private String language;

    /** 相对 storage root 的封面路径(如 covers/abc.jpg),无封面为 null */
    private String coverPath;

    /** EPUB 文件 SHA-256 hex,唯一;同 hash 重复上传幂等(D-30) */
    private String fileHash;

    private Long fileSize;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
