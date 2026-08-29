package com.smy101.reader.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * 本地 OpenAI 兼容 stub(spec · Testing Decisions:自动化里 LLM 一律替身,替身打在网络层)。
 * <p>
 * 基于 JDK HttpServer 零依赖起真实 HTTP 服务,行为按路径可配置:
 * GET {base}/models 与 POST {base}/chat/completions(stream=true 的多块 SSE)。
 * 同时记录收到的请求(headers + body),供"发给上游的 prompt 形状"断言(Seam A)。
 * 行为默认:models → 200 {"data":[...]},chat/completions → 单块流式回复。
 */
public class OpenAiStubServer implements AutoCloseable {

    /** 收到的一次请求(供 prompt 形状与鉴权头断言)。 */
    public record Received(String method, String path, Map<String, List<String>> headers, String body) {
        public String bearer() {
            List<String> auth = headers.getOrDefault("Authorization", List.of());
            return auth.isEmpty() ? null : auth.get(0);
        }
    }

    /** 单条路径的行为:状态 + 响应体(2xx 且以 "sse:" 开头时按 SSE 多块下发)。 */
    private record Behavior(int status, String body, boolean hang) {
        static Behavior of(int status, String body) {
            return new Behavior(status, body, false);
        }
    }

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor;
    private final ConcurrentLinkedQueue<Received> requests = new ConcurrentLinkedQueue<>();
    private volatile Behavior modelsBehavior = Behavior.of(200,
            "{\"object\":\"list\",\"data\":[{\"id\":\"stub-chat\"},{\"id\":\"bge-m3\"}]}");
    private volatile Behavior chatBehavior = Behavior.of(200,
            "sse:{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n" +
            "{\"choices\":[{\"delta\":{\"content\":\"这是 stub 回复\"}}]}\n" +
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n" +
            "[DONE]");
    /** embeddings 行为:body == null 表示确定性生成(关键词→维度映射的袋向量) */
    private volatile Behavior embeddingsBehavior = Behavior.of(200, null);
    private volatile int embeddingDimension = 32;

    public OpenAiStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("stub 启动失败", e);
        }
        // 线程池:挂住连接的探针超时用例不能阻塞后续请求(默认单分发线程会)
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "openai-stub");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** OpenAI 兼容 base URL(含 /v1,与设置页口径一致)。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 实际监听地址(不带 /v1;测试连接指向错误前缀时用)。 */
    public String rawBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---- 行为配置 ----

    public void models(int status, String jsonBody) {
        this.modelsBehavior = Behavior.of(status, jsonBody);
    }

    public void hangModels() {
        this.modelsBehavior = new Behavior(200, null, true);
    }

    public void hangChat() {
        this.chatBehavior = new Behavior(200, null, true);
    }

    public void chat(int status, String bodyOrSse) {
        this.chatBehavior = Behavior.of(status, bodyOrSse);
    }

    /** embeddings 上游失败(非 2xx 可读错误)。 */
    public void embeddings(int status, String jsonBody) {
        this.embeddingsBehavior = Behavior.of(status, jsonBody);
    }

    /** embeddings 上游恢复成功(确定性向量)。 */
    public void embeddingsOk() {
        this.embeddingsBehavior = Behavior.of(200, null);
    }

    /** 确定性向量维度(默认 32;换模型重嵌入的维度变化断言用)。 */
    public void setEmbeddingDimension(int dimension) {
        this.embeddingDimension = dimension;
    }

    public void chatStream(List<String> deltas) {
        StringBuilder sse = new StringBuilder();
        for (String delta : deltas) {
            sse.append("{\"choices\":[{\"delta\":{\"content\":\"").append(delta).append("\"}}]}\n");
        }
        sse.append("[DONE]");
        chatBehavior = Behavior.of(200, "sse:" + sse);
    }

    /** 断流:发若干块后直接关连接(无 [DONE])→ 客户端应报断流,已到内容照常落库。 */
    public void chatStreamAbrupt(List<String> deltas) {
        StringBuilder sse = new StringBuilder();
        for (String delta : deltas) {
            sse.append("{\"choices\":[{\"delta\":{\"content\":\"").append(delta).append("\"}}]}\n");
        }
        chatBehavior = Behavior.of(200, "sse:" + sse);
    }

    /** 流中挂死:发若干块后握住连接不响应 → 客户端空闲超时。 */
    public void chatStreamThenHang(List<String> deltas) {
        StringBuilder sse = new StringBuilder();
        for (String delta : deltas) {
            sse.append("{\"choices\":[{\"delta\":{\"content\":\"").append(delta).append("\"}}]}\n");
        }
        chatBehavior = new Behavior(200, "sse-hang:" + sse, false);
    }

    // ---- 请求记录 ----

    public List<Received> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public Received lastRequest(String path) {
        List<Received> all = requests(path);
        Assertions.assertFalse(all.isEmpty(), "stub 未收到对 " + path + " 的请求");
        return all.get(all.size() - 1);
    }

    public void resetRequests() {
        requests.clear();
    }

    // ---- 内部 ----

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));

            String path = exchange.getRequestURI().getPath();
            Behavior behavior = switch (path) {
                case "/v1/models" -> modelsBehavior;
                case "/v1/chat/completions" -> chatBehavior;
                case "/v1/embeddings" -> embeddingsBehavior;
                default -> null; // 未如路由 → 404(测错前缀的 Base URL)
            };
            if (behavior == null) {
                respond(exchange, 404, "{\"error\":{\"message\":\"Unknown path\"}}");
                return;
            }
            if ("/v1/embeddings".equals(path) && behavior.status() == 200 && behavior.body() == null) {
                respond(exchange, 200, deterministicEmbeddings(body));
                return;
            }
            if (behavior.hang()) {
                // 握住连接不响应,让客户端超时(测试连接超时文案)
                Thread.sleep(10_000);
                return;
            }
            if (behavior.status() == 200 && behavior.body() != null && behavior.body().startsWith("sse:")) {
                respondSse(exchange, behavior.body().substring("sse:".length()));
                return;
            }
            if (behavior.status() == 200 && behavior.body() != null && behavior.body().startsWith("sse-hang:")) {
                respondSseThenHang(exchange, behavior.body().substring("sse-hang:".length()));
                return;
            }
            respond(exchange, behavior.status(), behavior.body() == null ? "" : behavior.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 多块 SSE:按行拆 data:,逐块 flush,模拟真实流式节奏。 */
    private void respondSse(HttpExchange exchange, String ssePayload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0); // chunked
        String[] lines = ssePayload.split("\n", -1);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String line : lines) {
                out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(30); // 逼出"逐块到达"的流式形态
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 多块 SSE 后握住连接:测流中空闲超时。 */
    private void respondSseThenHang(HttpExchange exchange, String ssePayload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0); // chunked
        try (OutputStream out = exchange.getResponseBody()) {
            for (String line : ssePayload.split("\n", -1)) {
                out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Thread.sleep(10_000); // 块发完后挂住
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** 找一个没人在听的端口(测"连接失败"文案用:地址可达端口、服务不存在)。 */
    public static String deadServerBaseUrl() {
        try (var socket = new java.net.ServerSocket(0)) {
            int port = socket.getLocalPort();
            socket.close();
            return "http://127.0.0.1:" + port + "/v1";
        } catch (IOException e) {
            throw new IllegalStateException("无法分配测试端口", e);
        }
    }

    /**
     * 确定性 embeddings(体):每个非空白码点落到固定维度槽(关键词→维度映射的袋向量),
     * 同文本同维度向量恒定——检索排序可断言;维度可经 {@link #setEmbeddingDimension} 换模型时改变。
     */
    private String deterministicEmbeddings(String requestBody) throws IOException {
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(requestBody);
        com.fasterxml.jackson.databind.JsonNode input = root.path("input");
        List<String> texts = new ArrayList<>();
        if (input.isArray()) {
            input.forEach(t -> texts.add(t.asText()));
        } else {
            texts.add(input.asText(""));
        }
        int dim = embeddingDimension;
        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"model\":\"")
                .append(root.path("model").asText("stub-embed"))
                .append("\",\"data\":[");
        for (int i = 0; i < texts.size(); i++) {
            float[] vector = bagOfCodePoints(texts.get(i), dim);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < dim; d++) {
                if (d > 0) {
                    json.append(',');
                }
                json.append(java.math.BigDecimal.valueOf(vector[d]).stripTrailingZeros().toPlainString());
            }
            json.append("]}");
        }
        json.append("],\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}");
        return json.toString();
    }

    private static float[] bagOfCodePoints(String text, int dim) {
        float[] vector = new float[dim];
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                continue;
            }
            int folded = Character.toLowerCase(cp);
            int slot = Math.floorMod(folded * 31 + 7, dim);
            vector[slot] += 1.0f;
        }
        return vector;
    }
}
