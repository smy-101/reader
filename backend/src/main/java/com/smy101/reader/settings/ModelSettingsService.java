package com.smy101.reader.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smy101.reader.llm.LlmProbe;
import com.smy101.reader.settings.dto.ModelSettingsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 模型设置域(FR-401/404/405):单套配置(id 恒 1,D-27)读写 + 测试连接代理。
 * <p>
 * 语义:保存不校验连通性(测试连接是显式动作);api key 明文存库回显(FR-404);
 * 空白可空字段一律归一为 null(空 = 8k 保守 / 跟随 chat 的语义统一由 null 表达)。
 */
@Service
@RequiredArgsConstructor
public class ModelSettingsService {

    private static final int SINGLE_ROW_ID = 1;

    private final ModelSettingsMapper mapper;
    private final LlmProbe probe;

    /** 读取;从未保存过时返回全空配置(端上据此显示空表单)。 */
    public ModelSettingsDto get() {
        ModelSettings row = mapper.selectById(SINGLE_ROW_ID);
        if (row == null) {
            return new ModelSettingsDto(SINGLE_ROW_ID, null, null, null, null, null, null, null, null);
        }
        return toDto(row);
    }

    /** 保存(整行覆盖);base URL 与 chat 模型必填,上下文上限须为正。 */
    public ModelSettingsDto save(ModelSettingsDto.SaveRequest request) {
        String baseUrl = normalize(request == null ? null : request.baseUrl());
        String chatModel = normalize(request == null ? null : request.chatModel());
        if (baseUrl == null) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL 必须以 http:// 或 https:// 开头");
        }
        if (chatModel == null) {
            throw new IllegalArgumentException("Chat 模型不能为空");
        }
        Integer contextTokens = request.chatContextTokens();
        if (contextTokens != null && contextTokens <= 0) {
            throw new IllegalArgumentException("上下文上限必须为正整数(留空按 8k 保守计)");
        }

        if (mapper.selectById(SINGLE_ROW_ID) == null) {
            ModelSettings row = new ModelSettings();
            row.setId(SINGLE_ROW_ID);
            fill(row, baseUrl, chatModel, request);
            mapper.insert(row);
        } else {
            LambdaUpdateWrapper<ModelSettings> update = new LambdaUpdateWrapper<ModelSettings>()
                    .eq(ModelSettings::getId, SINGLE_ROW_ID)
                    .set(ModelSettings::getBaseUrl, baseUrl)
                    .set(ModelSettings::getApiKey, normalizeToEmpty(request.apiKey()))
                    .set(ModelSettings::getChatModel, chatModel)
                    .set(ModelSettings::getChatContextTokens, contextTokens)
                    .set(ModelSettings::getEmbeddingModel, normalize(request.embeddingModel()))
                    .set(ModelSettings::getEmbeddingBaseUrl, normalize(request.embeddingBaseUrl()))
                    .set(ModelSettings::getEmbeddingApiKey, normalize(request.embeddingApiKey()))
                    .setSql("updated_at = now()"); // 服务器时钟(D-19 同源纪律)
            mapper.update(null, update);
        }
        return get();
    }

    /**
     * 测试连接(FR-405):chat 与 embedding 两探针分别探测各自生效的 base_url;
     * 请求体携带配置时测表单值(未保存也可测),否则测已保存配置。
     */
    public ModelSettingsDto.TestConnectionResult test(ModelSettingsDto.SaveRequest request) {
        String chatUrl;
        String chatKey;
        String embeddingModel;
        String embeddingUrl;
        String embeddingKey;
        if (request != null && normalize(request.baseUrl()) != null) {
            chatUrl = normalize(request.baseUrl());
            chatKey = normalizeToEmpty(request.apiKey());
            embeddingModel = normalize(request.embeddingModel());
            embeddingUrl = firstNonBlank(normalize(request.embeddingBaseUrl()), chatUrl); // 空 = 跟随 chat(D-28)
            embeddingKey = firstNonBlank(normalize(request.embeddingApiKey()), chatKey);
        } else {
            ModelSettings saved = mapper.selectById(SINGLE_ROW_ID);
            if (saved == null) {
                return new ModelSettingsDto.TestConnectionResult(
                        ModelSettingsDto.ProbeOutcome.failure("尚未配置模型设置:请先填写 Base URL 与 Chat 模型"),
                        new ModelSettingsDto.ProbeOutcome(true, false, "未配置,跳过"));
            }
            chatUrl = saved.getBaseUrl();
            chatKey = saved.getApiKey() == null ? "" : saved.getApiKey();
            embeddingModel = saved.getEmbeddingModel();
            embeddingUrl = firstNonBlank(saved.getEmbeddingBaseUrl(), chatUrl);
            embeddingKey = firstNonBlank(saved.getEmbeddingApiKey(), chatKey);
        }

        ModelSettingsDto.ProbeOutcome chatOutcome = toOutcome(probe.probe(chatUrl, chatKey));
        ModelSettingsDto.ProbeOutcome embeddingOutcome = embeddingModel == null
                ? new ModelSettingsDto.ProbeOutcome(true, true, "未配置 embedding 模型,已跳过")
                : toOutcome(probe.probe(embeddingUrl, embeddingKey));
        return new ModelSettingsDto.TestConnectionResult(chatOutcome, embeddingOutcome);
    }

    // ---- 内部 ----

    private void fill(ModelSettings row, String baseUrl, String chatModel, ModelSettingsDto.SaveRequest request) {
        row.setBaseUrl(baseUrl);
        row.setApiKey(normalizeToEmpty(request.apiKey()));
        row.setChatModel(chatModel);
        row.setChatContextTokens(request.chatContextTokens());
        row.setEmbeddingModel(normalize(request.embeddingModel()));
        row.setEmbeddingBaseUrl(normalize(request.embeddingBaseUrl()));
        row.setEmbeddingApiKey(normalize(request.embeddingApiKey()));
    }

    private ModelSettingsDto.ProbeOutcome toOutcome(LlmProbe.ProbeResult result) {
        return result.ok()
                ? ModelSettingsDto.ProbeOutcome.success()
                : ModelSettingsDto.ProbeOutcome.failure(result.message());
    }

    private ModelSettingsDto toDto(ModelSettings row) {
        return new ModelSettingsDto(row.getId(), row.getBaseUrl(), row.getApiKey(), row.getChatModel(),
                row.getChatContextTokens(), row.getEmbeddingModel(), row.getEmbeddingBaseUrl(),
                row.getEmbeddingApiKey(), row.getUpdatedAt());
    }

    /** 空白归一为 null(可空字段统一语义)。 */
    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** api_key 列 NOT NULL:空 key(本地服务)落空串。 */
    private String normalizeToEmpty(String value) {
        String normalized = normalize(value);
        return normalized == null ? "" : normalized;
    }

    private String firstNonBlank(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
