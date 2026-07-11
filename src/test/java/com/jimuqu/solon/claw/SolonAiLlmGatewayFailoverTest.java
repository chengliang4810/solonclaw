package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;

public class SolonAiLlmGatewayFailoverTest {
    @Test
    void shouldFallbackToNextProviderWhenPrimaryFails() throws Exception {
        RecordingGateway gateway = new RecordingGateway(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-1");

        LlmResult result =
                gateway.chat(
                        session,
                        "system",
                        "hello",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop());

        assertThat(result.getProvider()).isEqualTo("backup");
        assertThat(result.getModel()).isEqualTo("claude-sonnet-4");
        assertThat(gateway.attempts)
                .containsExactly("primary:gpt-5-mini", "backup:claude-sonnet-4");
    }

    @Test
    void shouldRetryPrimaryAgainOnNextTurnAfterFallback() throws Exception {
        RecordingGateway gateway = new RecordingGateway(config());
        SessionRecord session = new SessionRecord();
        session.setSessionId("s-2");

        gateway.chat(
                session,
                "system",
                "hello",
                Collections.emptyList(),
                ConversationFeedbackSink.noop());
        gateway.chat(
                session,
                "system",
                "hello again",
                Collections.emptyList(),
                ConversationFeedbackSink.noop());

        assertThat(gateway.attempts)
                .containsExactly(
                        "primary:gpt-5-mini",
                        "backup:claude-sonnet-4",
                        "primary:gpt-5-mini",
                        "backup:claude-sonnet-4");
    }

    /** 验证主代理使用 react.retryMax，而不是固定重试次数。 */
    @Test
    void shouldUseConfiguredMainRetryCount() throws Exception {
        AppConfig config = config();
        config.getReact().setRetryMax(2);
        config.getReact().setRetryDelayMs(0);
        RetryingGateway gateway = new RetryingGateway(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("main-retry");

        gateway.chat(
                session,
                "system",
                "hello",
                Collections.emptyList(),
                ConversationFeedbackSink.noop());

        assertThat(gateway.attempts)
                .containsExactly(
                        "primary:gpt-5-mini",
                        "primary:gpt-5-mini",
                        "primary:gpt-5-mini",
                        "backup:claude-sonnet-4");
    }

    /** 验证子代理会话独立使用 delegateRetryMax。 */
    @Test
    void shouldUseConfiguredDelegateRetryCount() throws Exception {
        AppConfig config = config();
        config.getReact().setRetryMax(4);
        config.getReact().setRetryDelayMs(0);
        config.getReact().setDelegateRetryMax(1);
        config.getReact().setDelegateRetryDelayMs(0);
        RetryingGateway gateway = new RetryingGateway(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("delegate-retry");
        session.setParentSessionId("parent-session");

        gateway.chat(
                session,
                "system",
                "hello",
                Collections.emptyList(),
                ConversationFeedbackSink.noop());

        assertThat(gateway.attempts)
                .containsExactly(
                        "primary:gpt-5-mini", "primary:gpt-5-mini", "backup:claude-sonnet-4");
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        isolateRuntime(config, "llm-failover");

        AppConfig.ProviderConfig primary = new AppConfig.ProviderConfig();
        primary.setName("Primary");
        primary.setBaseUrl("https://api.openai.com");
        primary.setApiKey("primary-key");
        primary.setDefaultModel("gpt-5-mini");
        primary.setDialect("openai-responses");
        config.getProviders().put("primary", primary);

        AppConfig.ProviderConfig backup = new AppConfig.ProviderConfig();
        backup.setName("Backup");
        backup.setBaseUrl("https://api.anthropic.com");
        backup.setApiKey("backup-key");
        backup.setDefaultModel("claude-sonnet-4");
        backup.setDialect("anthropic");
        config.getProviders().put("backup", backup);

        config.getModel().setProviderKey("primary");
        config.getModel().setDefault("");

        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("backup");
        config.getFallbackProviders().add(fallback);

        config.getLlm().setProvider("primary");
        config.getLlm().setDialect("openai-responses");
        config.getLlm().setApiUrl("https://api.openai.com/v1/responses");
        config.getLlm().setApiKey("primary-key");
        config.getLlm().setModel("gpt-5-mini");
        config.getLlm().setReasoningEffort("medium");
        config.getLlm().setTemperature(0.2D);
        config.getLlm().setMaxTokens(4096);
        return config;
    }

    /**
     * 为 failover 测试隔离工作区目录，避免本机 workspace/config.yml 把 providerKey 覆盖成其他值。
     *
     * @param config 测试配置。
     * @param name runtime 目录名称前缀。
     */
    private void isolateRuntime(AppConfig config, String name) {
        String home = "target/test-runtime/" + name + "-" + System.nanoTime();
        config.getRuntime().setHome(home);
        config.getRuntime().setConfigFile(home + "/config.yml");
    }

    private static class RecordingGateway extends SolonAiLlmGateway {
        private final List<String> attempts = new ArrayList<String>();

        private RecordingGateway(AppConfig config) {
            super(config);
        }

        @Override
        protected LlmResult executeSingle(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            attempts.add(resolved.getProvider() + ":" + resolved.getModel());
            if ("primary".equals(resolved.getProvider())) {
                throw new IllegalStateException("HTTP 401 unauthorized");
            }

            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setRawResponse("ok");
            result.setAssistantMessage(new AssistantMessage("done"));
            return result;
        }
    }

    /** 让主提供方持续返回可重试错误，用于校验配置化重试次数。 */
    private static class RetryingGateway extends SolonAiLlmGateway {
        /** 记录每次实际执行的提供方和模型。 */
        private final List<String> attempts = new ArrayList<String>();

        /** 创建配置化重试测试网关。 */
        private RetryingGateway(AppConfig config) {
            super(config);
        }

        /** 模拟主提供方过载，备用提供方成功。 */
        @Override
        protected LlmResult executeSingle(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            attempts.add(resolved.getProvider() + ":" + resolved.getModel());
            if ("primary".equals(resolved.getProvider())) {
                throw new IllegalStateException("HTTP 503 overloaded");
            }

            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setRawResponse("ok");
            result.setAssistantMessage(new AssistantMessage("done"));
            return result;
        }
    }
}
