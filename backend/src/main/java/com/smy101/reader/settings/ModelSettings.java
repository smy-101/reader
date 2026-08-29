package com.smy101.reader.settings;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 模型设置:单套(id 恒 1,D-27);字段语义见 V3 迁移注释。 */
@Data
@TableName("model_settings")
public class ModelSettings {

    @TableId
    private Integer id;

    private String baseUrl;
    private String apiKey;
    private String chatModel;
    private Integer chatContextTokens;
    private String embeddingModel;
    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private OffsetDateTime updatedAt;
}
