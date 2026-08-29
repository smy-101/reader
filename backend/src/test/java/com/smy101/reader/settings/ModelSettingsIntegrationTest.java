package com.smy101.reader.settings;

import com.jayway.jsonpath.JsonPath;
import com.smy101.reader.IntegrationTestBase;
import com.smy101.reader.llm.OpenAiStubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型设置域(M3-01,FR-401/404/405 · D-27/D-28):单套配置(id 恒 1)读写、明文回显、
 * 可空字段语义、测试连接双探针(ok/失败/超时/401/404 分类文案、embedding 独立配置、未配置跳过)。
 * 上游一律本地 OpenAI 兼容 stub(网络层替身),CI 零外网依赖。
 */
class ModelSettingsIntegrationTest extends IntegrationTestBase {

    private static final OpenAiStubServer STUB = new OpenAiStubServer();
    private static final OpenAiStubServer EMBEDDING_STUB = new OpenAiStubServer();

    @AfterAll
    static void stopStubs() {
        STUB.close();
        EMBEDDING_STUB.close();
    }

    @DynamicPropertySource
    static void fastProbeTimeout(DynamicPropertyRegistry registry) {
        registry.add("reader.llm.probe-timeout-ms", () -> "600");
    }

    // ---- 读写 ----

    @Test
    void 未配置时读取返回空配置() {
        ResponseEntity<String> res = get();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) JsonPath.read(res.getBody(), "id")).intValue()).isEqualTo(1);
        assertThat((Object) JsonPath.read(res.getBody(), "baseUrl")).isNull();
        assertThat((Object) JsonPath.read(res.getBody(), "apiKey")).isNull();
        assertThat((Object) JsonPath.read(res.getBody(), "chatModel")).isNull();
        assertThat((Object) JsonPath.read(res.getBody(), "chatContextTokens")).isNull();
        assertThat((Object) JsonPath.read(res.getBody(), "embeddingModel")).isNull();
    }

    @Test
    void 保存后回显_明文apiKey_可空字段保持null_单行不变() {
        ResponseEntity<String> saved = put(Map.of(
                "baseUrl", STUB.baseUrl(),
                "apiKey", "sk-plaintext-123",
                "chatModel", "deepseek-chat"));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        // FR-404:明文回显
        assertThat((String) JsonPath.read(saved.getBody(), "apiKey")).isEqualTo("sk-plaintext-123");
        assertThat((String) JsonPath.read(saved.getBody(), "baseUrl")).isEqualTo(STUB.baseUrl());
        assertThat((String) JsonPath.read(saved.getBody(), "chatModel")).isEqualTo("deepseek-chat");
        // 可空项空 = null(空上下文 8k 保守 / embedding 跟随 chat 的端侧语义)
        assertThat((Object) JsonPath.read(saved.getBody(), "chatContextTokens")).isNull();
        assertThat((Object) JsonPath.read(saved.getBody(), "embeddingModel")).isNull();
        assertThat((String) JsonPath.read(saved.getBody(), "updatedAt")).isNotBlank();

        // 再读一致
        ResponseEntity<String> again = get();
        assertThat((String) JsonPath.read(again.getBody(), "apiKey")).isEqualTo("sk-plaintext-123");

        // 更新覆盖,仍是单行(id 恒 1,D-27)
        put(Map.of("baseUrl", STUB.baseUrl(), "apiKey", "sk-second", "chatModel", "qwen-max",
                "chatContextTokens", 4096));
        ResponseEntity<String> updated = get();
        assertThat((String) JsonPath.read(updated.getBody(), "chatModel")).isEqualTo("qwen-max");
        assertThat(((Number) JsonPath.read(updated.getBody(), "chatContextTokens")).intValue()).isEqualTo(4096);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM model_settings", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 缺baseUrl或chat模型返回400可读文案() {
        ResponseEntity<String> noUrl = put(Map.of("chatModel", "deepseek-chat"));
        assertThat(noUrl.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noUrl.getBody()).contains("Base URL");

        ResponseEntity<String> noModel = put(Map.of("baseUrl", STUB.baseUrl()));
        assertThat(noModel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noModel.getBody()).contains("Chat 模型");

        ResponseEntity<String> badTokens = put(Map.of("baseUrl", STUB.baseUrl(), "chatModel", "m", "chatContextTokens", 0));
        assertThat(badTokens.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badTokens.getBody()).contains("上下文上限");
    }

    // ---- 测试连接 ----

    @Test
    void 测试连接_chat通_embedding未配置明示跳过() {
        STUB.models(200, "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-chat\"}]}");
        put(Map.of("baseUrl", STUB.baseUrl(), "apiKey", "sk-live", "chatModel", "deepseek-chat"));

        ResponseEntity<String> res = postTest(null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isTrue();
        assertThat((String) JsonPath.read(res.getBody(), "chat.message")).contains("连接成功");
        assertThat((Boolean) JsonPath.read(res.getBody(), "embedding.skipped")).isTrue();
        assertThat((String) JsonPath.read(res.getBody(), "embedding.message")).contains("跳过");
        // 探针带上了 api key
        assertThat(STUB.lastRequest("/v1/models").bearer()).isEqualTo("Bearer sk-live");
    }

    @Test
    void 测试连接_请求体携带未保存配置也可直接测() {
        STUB.resetRequests();

        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", STUB.baseUrl(),
                "apiKey", "sk-unsaved",
                "chatModel", "deepseek-chat"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isTrue();
        assertThat(STUB.lastRequest("/v1/models").bearer()).isEqualTo("Bearer sk-unsaved");
        // 未保存:后续读取仍是空配置
        assertThat((Object) JsonPath.read(get().getBody(), "apiKey")).isNull();
    }

    @Test
    void 测试连接_chat探针401给出APIkey文案() {
        STUB.models(401, "{\"error\":{\"message\":\"Invalid API key\"}}");
        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-wrong", "chatModel", "deepseek-chat"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isFalse();
        assertThat((String) JsonPath.read(res.getBody(), "chat.message")).contains("API key");
    }

    @Test
    void 测试连接_chat探针404给出地址文案() {
        // 指到 stub 但路径不存在(缺 /v1 前缀是典型配错)
        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", STUB.rawBaseUrl() + "/wrong-prefix",
                "apiKey", "sk-x", "chatModel", "deepseek-chat"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isFalse();
        assertThat((String) JsonPath.read(res.getBody(), "chat.message")).contains("404");
    }

    @Test
    void 测试连接_连接失败给出可读文案() {
        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", OpenAiStubServer.deadServerBaseUrl(),
                "apiKey", "sk-x", "chatModel", "deepseek-chat"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isFalse();
        assertThat((String) JsonPath.read(res.getBody(), "chat.message")).contains("无法连接");
    }

    @Test
    void 测试连接_上游不响应给出超时文案() {
        STUB.hangModels();
        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-x", "chatModel", "deepseek-chat"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isFalse();
        assertThat((String) JsonPath.read(res.getBody(), "chat.message")).contains("超时");
        STUB.models(200, "{}");
    }

    @Test
    void 测试连接_embedding独立baseUrl与apiKey生效_与chat互不干扰() {
        STUB.models(404, "{\"error\":{\"message\":\"no\"}}"); // chat 故意配错
        EMBEDDING_STUB.models(200, "{\"object\":\"list\",\"data\":[{\"id\":\"bge-m3\"}]}");

        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-chat", "chatModel", "deepseek-chat",
                "embeddingModel", "bge-m3",
                "embeddingBaseUrl", EMBEDDING_STUB.baseUrl(),
                "embeddingApiKey", "sk-emb"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // chat 探针失败(404 文案),embedding 探针成功——两家各自判定
        assertThat((Boolean) JsonPath.read(res.getBody(), "chat.ok")).isFalse();
        assertThat((String) JsonPath.read(res.getBody(), "chat.message")).contains("404");
        assertThat((Boolean) JsonPath.read(res.getBody(), "embedding.skipped")).isEqualTo(false);
        assertThat((Boolean) JsonPath.read(res.getBody(), "embedding.ok")).isTrue();
        // embedding 探针走独立 base_url + 独立 key(D-28)
        assertThat(EMBEDDING_STUB.lastRequest("/v1/models").bearer()).isEqualTo("Bearer sk-emb");
    }

    @Test
    void 测试连接_embedding未配独立地址时跟随chat() {
        STUB.models(200, "{\"object\":\"list\",\"data\":[{\"id\":\"bge-m3\"}]}");
        STUB.resetRequests();

        ResponseEntity<String> res = postTest(Map.of(
                "baseUrl", STUB.baseUrl(), "apiKey", "sk-chat", "chatModel", "deepseek-chat",
                "embeddingModel", "bge-m3"));

        assertThat((Boolean) JsonPath.read(res.getBody(), "embedding.ok")).isTrue();
        // 跟随 chat:同一 base_url,同一 key(D-28 空语义)
        assertThat(STUB.requests("/v1/models")).hasSize(2);
        assertThat(STUB.lastRequest("/v1/models").bearer()).isEqualTo("Bearer sk-chat");
    }

    // ---- 防线 ----

    @Test
    void 模型设置端点无token一律401() {
        for (var call : List.of(
                new Call(HttpMethod.GET, "/api/settings/model"),
                new Call(HttpMethod.PUT, "/api/settings/model"),
                new Call(HttpMethod.POST, "/api/settings/model/test"))) {
            ResponseEntity<String> res = rest.exchange(call.path(), call.method(),
                    new HttpEntity<>(jsonHeaders()), String.class);
            assertThat(res.getStatusCode()).as(call.toString()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ---- helpers ----

    private record Call(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private ResponseEntity<String> get() {
        return rest.exchange("/api/settings/model", HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private ResponseEntity<String> put(Map<String, Object> body) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, authJsonHeaders());
        return rest.exchange("/api/settings/model", HttpMethod.PUT, entity, String.class);
    }

    /** 测试连接;body 为 null 时测已保存配置。 */
    private ResponseEntity<String> postTest(Map<String, Object> body) {
        HttpEntity<?> entity = body == null ? new HttpEntity<>(authHeaders()) : new HttpEntity<>(body, authJsonHeaders());
        return rest.exchange("/api/settings/model/test", HttpMethod.POST, entity, String.class);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return headers;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
