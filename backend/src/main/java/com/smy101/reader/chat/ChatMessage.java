package com.smy101.reader.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smy101.reader.db.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 消息:会话内一条发言(role + 内容 + 引用来源 refs,jsonb 文本经 {@link JsonbTypeHandler})。 */
@Data
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** user / assistant */
    private String role;

    private String content;

    /** 引用来源(JSON 数组文本;首版:选中文字引用 + 章节引用;可空) */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String refs;

    private OffsetDateTime createdAt;
}
