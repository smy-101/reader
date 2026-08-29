package com.smy101.reader.book;

/** 新书入库完成事件(M4-02:嵌入任务域据此自动建任务;上传响应不等嵌入)。 */
public record BookUploadedEvent(long bookId) {
}
