package com.smy101.reader.book.epub;

/** EPUB 无法按 zip/OPF 结构解析(文件损坏口径,D-29)。消息为面向用户的可读文案。 */
public class EpubParseException extends RuntimeException {

    public EpubParseException(String message) {
        super(message);
    }

    public EpubParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
