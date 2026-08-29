package com.smy101.reader.chat;

import com.smy101.reader.chat.dto.ChatDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 对话 API(M3-03,FR-301/303/304):书级提问(SSE 流式,D-25)+ 会话 CRUD。
 * 提问受理期的可前置失败(书/会话/目标章/选中文字/设置/预算)以 4xx JSON 返回;
 * 流式期失败以 SSE error 事件收尾(FR-303 不悬挂)。均受既有 token 拦截。
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 书级提问(S1 与 S2 同一通路,D-32):content 必填,其余可选;
     * 响应 text/event-stream:meta → delta… → done / error。
     */
    @PostMapping(value = "/api/books/{bookId}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable long bookId, @RequestBody ChatDtos.AskRequest request) {
        ChatService.PreparedAsk prepared = chatService.prepare(bookId, request);
        SseEmitter emitter = new SseEmitter(0L); // 流式期间不设整体超时(上游空闲超时在 LlmAdapter 兜底)
        chatService.stream(prepared, emitter);
        return emitter;
    }

    /** 某书会话列表,按最近活跃排序。 */
    @GetMapping("/api/books/{bookId}/sessions")
    public List<ChatDtos.SessionDto> listSessions(@PathVariable long bookId) {
        return chatService.listSessions(bookId);
    }

    /** 会话全部消息(含 refs),打开会话一次拿齐。 */
    @GetMapping("/api/sessions/{sessionId}/messages")
    public List<ChatDtos.MessageDto> listMessages(@PathVariable long sessionId) {
        return chatService.listMessages(sessionId);
    }

    /** 重命名会话(FR-304)。 */
    @PatchMapping("/api/sessions/{sessionId}")
    public ChatDtos.SessionDto rename(@PathVariable long sessionId,
                                      @RequestBody ChatDtos.RenameRequest request) {
        return chatService.rename(sessionId, request);
    }

    /** 删除会话(消息级联清)。 */
    @DeleteMapping("/api/sessions/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable long sessionId) {
        chatService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
