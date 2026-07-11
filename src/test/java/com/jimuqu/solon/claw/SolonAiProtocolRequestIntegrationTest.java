package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.IoUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

/** 通过本地 HTTP 捕获服务器验证五种模型协议的最终请求，而不是只检查内存配置。 */
public class SolonAiProtocolRequestIntegrationTest {
    /** 验证 reasoning、快速模式、流式与提示词缓存字段进入提供方最终 HTTP 请求。 */
    @Test
    void shouldSendNativeOptionsThroughProviderHttpRequests() throws Exception {
        SessionRecord session = new SessionRecord();
        session.setSessionId("protocol-http-capture");
        session.setReasoningEffortOverride("high");
        session.setServiceTierOverride("priority");

        CapturedRequest openai =
                capture(
                        protocolConfig(
                                "openai", "https://api.openai.com/v1/chat/completions", "gpt-5.4"),
                        session,
                        "/v1/chat/completions",
                        true);
        assertThat(openai.body.get("stream").getBoolean()).isTrue();
        assertThat(openai.body.get("reasoning_effort").getString()).isEqualTo("high");
        assertThat(openai.body.get("service_tier").getString()).isEqualTo("priority");
        assertThat(openai.body.get("prompt_cache_key").getString()).startsWith("solonclaw-");

        CapturedRequest responses =
                capture(
                        protocolConfig(
                                "openai-responses",
                                "https://api.openai.com/v1/responses",
                                "gpt-5.4"),
                        session,
                        "/v1/responses",
                        true);
        assertThat(responses.body.get("stream").getBoolean()).isTrue();
        assertThat(responses.body.get("reasoning").get("effort").getString()).isEqualTo("high");
        assertThat(responses.body.get("reasoning").get("summary").getString()).isEqualTo("auto");
        assertThat(responses.body.get("service_tier").getString()).isEqualTo("priority");
        assertThat(responses.body.get("prompt_cache_key").getString()).startsWith("solonclaw-");

        CapturedRequest ollama =
                capture(
                        protocolConfig("ollama", "http://localhost:11434/api/chat", "qwen3"),
                        session,
                        "/api/chat",
                        true);
        assertThat(ollama.body.get("stream").getBoolean()).isTrue();
        assertThat(ollama.body.get("think").getBoolean()).isTrue();
        assertThat(ollama.body.get("options").get("num_predict").getInt()).isEqualTo(8192);

        CapturedRequest gemini =
                capture(
                        protocolConfig(
                                "gemini",
                                "https://generativelanguage.googleapis.com/v1beta",
                                "gemini-3-pro"),
                        session,
                        "/v1beta",
                        true);
        assertThat(
                        gemini.body
                                .get("generationConfig")
                                .get("thinkingConfig")
                                .get("includeThoughts")
                                .getBoolean())
                .isTrue();
        assertThat(
                        gemini.body
                                .get("generationConfig")
                                .get("thinkingConfig")
                                .get("thinkingLevel")
                                .getString())
                .isEqualTo("HIGH");

        CapturedRequest anthropic =
                capture(
                        protocolConfig(
                                "anthropic",
                                "https://api.anthropic.com/v1/messages",
                                "claude-opus-4-6"),
                        session,
                        "/v1/messages",
                        true);
        assertThat(anthropic.body.get("stream").getBoolean()).isTrue();
        assertThat(anthropic.body.get("thinking").get("type").getString()).isEqualTo("adaptive");
        assertThat(anthropic.body.get("output_config").get("effort").getString()).isEqualTo("high");
        assertThat(anthropic.body.get("speed").getString()).isEqualTo("fast");
        assertThat(anthropic.body.get("system").get(0).get("cache_control").get("type").getString())
                .isEqualTo("ephemeral");
        assertThat(anthropic.anthropicBeta).contains("fast-mode-2026-02-01");
    }

    /** 验证 Responses SSE 的 reasoning summary 与可见文本都经过真实协议解析。 */
    @Test
    void shouldParseResponsesReasoningFromSseStream() throws Exception {
        AppConfig config =
                protocolConfig(
                        "openai-responses", "https://api.openai.com/v1/responses", "gpt-5.4");
        SessionRecord session = new SessionRecord();
        session.setSessionId("responses-reasoning-sse");
        session.setReasoningEffortOverride("high");
        String sse =
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"分析步骤\"}\n\n"
                        + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"最终答复\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-5.4\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5}}}\n\n"
                        + "data: [DONE]\n\n";

        ChatConfig chatConfig = buildChatConfig(config, session);
        try (ProtocolCaptureServer server =
                new ProtocolCaptureServer(200, "text/event-stream", sse)) {
            chatConfig.setApiUrl(server.url("/v1/responses"));
            StringBuilder reasoning = new StringBuilder();
            StringBuilder visible = new StringBuilder();
            chatConfig
                    .toChatModel()
                    .prompt(
                            Prompt.of(
                                    Arrays.asList(
                                            ChatMessage.ofSystem("system"),
                                            ChatMessage.ofUser("hello"))))
                    .stream()
                    .doOnNext(
                            chunk -> {
                                for (ChatChoice choice : chunk.getChoices()) {
                                    if (choice == null || choice.getMessage() == null) {
                                        continue;
                                    }
                                    if (choice.getMessage().isThinking()) {
                                        reasoning.append(choice.getMessage().getContent());
                                    } else {
                                        visible.append(choice.getMessage().getContent());
                                    }
                                }
                            })
                    .blockLast();

            assertThat(reasoning.toString()).contains("分析步骤");
            assertThat(visible.toString()).contains("最终答复");
            ONode request = ONode.ofJson(server.body.get());
            assertThat(request.get("reasoning").get("effort").getString()).isEqualTo("high");
            assertThat(request.get("reasoning").get("summary").getString()).isEqualTo("auto");
        }
    }

    /** 创建开启提示词缓存的协议测试配置。 */
    private AppConfig protocolConfig(String dialect, String apiUrl, String model) {
        AppConfig config = new AppConfig();
        config.getLlm().setProvider(dialect);
        config.getLlm().setDialect(dialect);
        config.getLlm().setApiUrl(apiUrl);
        config.getLlm().setApiKey("sk-test-valid-key");
        config.getLlm().setModel(model);
        config.getLlm().setReasoningEffort("medium");
        config.getLlm().setTemperature(0.2D);
        config.getLlm().setMaxTokens(8192);
        config.getLlm().getPromptCache().setEnabled(true);
        config.getLlm().getPromptCache().setTtl("1h");
        config.getLlm().getPromptCache().setLayout("system_and_3");
        return config;
    }

    /** 先按正式提供方地址生成请求选项，再把实际连接切到本地捕获服务器。 */
    private CapturedRequest capture(
            AppConfig config, SessionRecord session, String localPath, boolean stream)
            throws Exception {
        ChatConfig chatConfig = buildChatConfig(config, session);
        try (ProtocolCaptureServer server = new ProtocolCaptureServer()) {
            chatConfig.setApiUrl(server.url(localPath));
            ChatModel model = chatConfig.toChatModel();
            ChatRequestDesc request =
                    model.prompt(
                            Prompt.of(
                                    Arrays.asList(
                                            ChatMessage.ofSystem("system"),
                                            ChatMessage.ofUser("hello"))));
            try {
                if (stream) {
                    request.stream().collectList().block();
                } else {
                    request.call();
                }
            } catch (Exception expectedProtocolError) {
                // 捕获服务器固定返回错误，测试目标是验证已经发出的最终请求体与请求头。
            }

            assertThat(server.body.get()).isNotBlank();
            return new CapturedRequest(ONode.ofJson(server.body.get()), server.anthropicBeta.get());
        }
    }

    /** 调用网关内部正式配置构建器。 */
    private ChatConfig buildChatConfig(AppConfig config, SessionRecord session) throws Exception {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        Method method =
                SolonAiLlmGateway.class.getDeclaredMethod(
                        "buildChatConfig", AppConfig.LlmConfig.class, SessionRecord.class);
        method.setAccessible(true);
        return (ChatConfig) method.invoke(gateway, config.getLlm(), session);
    }

    /** 保存一次捕获到的模型 HTTP 请求。 */
    private static class CapturedRequest {
        /** 最终请求 JSON。 */
        private final ONode body;

        /** Anthropic beta 请求头。 */
        private final String anthropicBeta;

        /** 创建请求捕获结果。 */
        private CapturedRequest(ONode body, String anthropicBeta) {
            this.body = body;
            this.anthropicBeta = anthropicBeta;
        }
    }

    /** 只接收一次请求并返回错误响应的本地 HTTP 捕获服务器。 */
    private static class ProtocolCaptureServer implements AutoCloseable {
        /** 本地 HTTP 服务。 */
        private final HttpServer server;

        /** 固定响应状态码。 */
        private final int responseStatus;

        /** 固定响应内容类型。 */
        private final String responseContentType;

        /** 固定响应正文。 */
        private final byte[] responseBody;

        /** 捕获的 UTF-8 请求体。 */
        private final AtomicReference<String> body = new AtomicReference<String>();

        /** 捕获的 Anthropic beta 请求头。 */
        private final AtomicReference<String> anthropicBeta = new AtomicReference<String>();

        /** 启动随机端口捕获服务器。 */
        private ProtocolCaptureServer() throws Exception {
            this(400, "application/json", "{\"error\":{\"message\":\"captured\"}}");
        }

        /** 启动带指定响应的随机端口捕获服务器。 */
        private ProtocolCaptureServer(int status, String contentType, String responseText)
                throws Exception {
            responseStatus = status;
            responseContentType = contentType;
            responseBody = responseText.getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::capture);
            server.start();
        }

        /** 返回指定路径的本地访问地址。 */
        private String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        /** 捕获请求并返回测试指定的固定响应。 */
        private void capture(HttpExchange exchange) {
            try {
                body.set(IoUtil.readUtf8(exchange.getRequestBody()));
                anthropicBeta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
                exchange.getResponseHeaders().set("Content-Type", responseContentType);
                exchange.sendResponseHeaders(responseStatus, responseBody.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(responseBody);
                }
            } catch (Exception e) {
                body.compareAndSet(null, "");
            } finally {
                exchange.close();
            }
        }

        /** 停止本地捕获服务器。 */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
