package com.smy101.reader.web;

import com.smy101.reader.book.epub.EpubDrmException;
import com.smy101.reader.book.epub.EpubParseException;
import com.smy101.reader.llm.LlmException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 全局错误响应(User Story 14):一切错误以 {"error": 可读文案} 返回,不外漏堆栈。
 * 口径:损坏/DRM → 400 且文案明确区分(D-29);超限 → 413(FR-105);未知 → 500 记日志。
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EpubParseException.class)
    public ResponseEntity<Map<String, String>> corrupt(EpubParseException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(EpubDrmException.class)
    public ResponseEntity<Map<String, String>> drm(EpubDrmException e) {
        return badRequest(e.getMessage());
    }

    /** FR-105:超过 multipart 上限;Tomcat 常以 MultipartException 包装抛出。 */
    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<Map<String, String>> tooLarge(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "文件超过上传大小上限"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage() == null ? "资源不存在" : e.getMessage()));
    }

    /** 请求参数不合法(如划线缺 CFI、进度百分比越界):400 + 可读文案。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException e) {
        return badRequest(e.getMessage());
    }

    /** 接口不存在(如后端版本落后于前端):404 可读文案,不冒充 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "接口不存在(" + e.getResourcePath() + "):请确认后端已更新并重启"));
    }

    /** 上游 AI/embedding 服务失败(如 S3 检索时问题向量化失败):502 + 可读中文文案。 */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, String>> upstream(LlmException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage() == null ? "上游 AI 服务失败" : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误"));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", message == null ? "请求无效" : message));
    }
}
