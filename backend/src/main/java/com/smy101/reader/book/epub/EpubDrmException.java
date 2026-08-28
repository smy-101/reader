package com.smy101.reader.book.epub;

/** EPUB 带 `META-INF/encryption.xml` 加密标记(疑似 DRM 口径,D-29;不解密、不导入)。 */
public class EpubDrmException extends RuntimeException {

    public EpubDrmException(String message) {
        super(message);
    }
}
