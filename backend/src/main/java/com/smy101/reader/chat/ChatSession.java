package com.smy101.reader.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 会话:围绕一本书的 AI 对话序列(术语见 CONTEXT.md);book_id 可空 = 跨书会话(M4 预留)。 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookId;

    private String title;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
