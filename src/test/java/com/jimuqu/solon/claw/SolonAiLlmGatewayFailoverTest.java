package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalDecision;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalJudge;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

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
        assertThat(gateway.contextWindows).containsExactly(400000, 200000);
        assertThat(gateway.config.getLlm().getContextWindowTokens()).isZero();
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

    /** 限流不会耗尽同提供方重试预算，而是立即切换备用模型。 */
    @Test
    void shouldFallbackImmediatelyWhenPrimaryIsRateLimited() throws Exception {
        AppConfig config = config();
        config.getReact().setRetryMax(4);
        RateLimitedGateway gateway = new RateLimitedGateway(config);
        SessionRecord session = new SessionRecord();
        session.setSessionId("rate-limit");

        gateway.chat(
                session,
                "system",
                "hello",
                Collections.emptyList(),
                ConversationFeedbackSink.noop());

        assertThat(gateway.attempts)
                .containsExactly("primary:gpt-5-mini", "backup:claude-sonnet-4");
        assertThat(gateway.resumeFlags).containsExactly(false, true);
        List<ChatMessage> backupMessages = MessageSupport.loadMessages(gateway.backupNdjson);
        assertThat(gateway.toolEffects).isEqualTo(1);
        assertThat(backupMessages.get(backupMessages.size() - 1).getRole())
                .isEqualTo(org.noear.solon.ai.chat.ChatRole.TOOL);
        assertThat(backupMessages)
                .filteredOn(message -> message.getRole() == org.noear.solon.ai.chat.ChatRole.TOOL)
                .hasSize(1);
        assertThat(gateway.backupNdjson).doesNotContain("截断尾巴", "内部续写提示");
    }

    /** 主候选仅追加用户消息后失败时应整轮回滚，备用候选不得误走会话恢复。 */
    @Test
    void shouldRetryOriginalPromptInsteadOfResumingUserOnlyFailure() throws Exception {
        AppConfig config = config();
        config.getReact().setRetryMax(0);
        UserOnlyFailureGateway gateway = new UserOnlyFailureGateway(config);
        SessionRecord synthetic = new SessionRecord();
        synthetic.setSessionId("synthetic-user-only-failover");
        synthetic.setNdjson("");

        LlmResult result =
                gateway.chat(
                        synthetic,
                        "system",
                        "原始问题",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop());

        assertThat(result.getProvider()).isEqualTo("backup");
        assertThat(gateway.resumeFlags).containsExactly(false, false);
        assertThat(gateway.backupNdjson).isBlank();
        assertThat(gateway.userMessages).containsExactly("原始问题", "原始问题");
    }

    /** 智能审批必须复用统一故障切换链，独立审批模型不可用时继续尝试备用模型。 */
    @Test
    void shouldUseFallbackChainForSmartApprovalJudge() throws Exception {
        AppConfig config = config();
        config.getReact().setRetryMax(0);
        config.getApprovals().setModelProvider("primary");
        config.getApprovals().setModel("approval-primary");
        DangerousCommandApprovalService approvalService = new DangerousCommandApprovalService(null);
        ApprovalFallbackGateway gateway = new ApprovalFallbackGateway(config, approvalService);
        Field judgeField =
                DangerousCommandApprovalService.class.getDeclaredField("smartApprovalJudge");
        judgeField.setAccessible(true);
        SmartApprovalJudge judge = (SmartApprovalJudge) judgeField.get(approvalService);

        SmartApprovalDecision decision =
                judge.judge("execute_shell", "pwd", "read current working directory");

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getReason()).isEqualTo("read only");
        assertThat(gateway.attempts)
                .containsExactly("primary:approval-primary", "backup:claude-sonnet-4");
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
        /** 测试配置，用于确认候选解析不会回写共享上下文窗口。 */
        private final AppConfig config;

        /** 记录每个候选模型实际收到的上下文窗口。 */
        private final List<Integer> contextWindows = new ArrayList<Integer>();

        private final List<String> attempts = new ArrayList<String>();

        private RecordingGateway(AppConfig config) {
            super(config);
            this.config = config;
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
            contextWindows.add(Integer.valueOf(resolved.getContextWindowTokens()));
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
        protected final List<String> attempts = new ArrayList<String>();

        /** 创建配置化重试测试网关。 */
        protected RetryingGateway(AppConfig config) {
            super(config);
        }

        /** 模拟主提供方服务端错误，备用提供方成功。 */
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
                throw new IllegalStateException("HTTP 500 server error");
            }

            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setRawResponse("ok");
            result.setAssistantMessage(new AssistantMessage("done"));
            return result;
        }
    }

    /** 模拟主提供方限流，备用提供方成功。 */
    private static class RateLimitedGateway extends RetryingGateway {
        /** 记录故障切换后是否从已有会话快照继续。 */
        private final List<Boolean> resumeFlags = new ArrayList<Boolean>();

        /** 记录工具副作用次数。 */
        private int toolEffects;

        /** 记录备用模型收到的安全历史。 */
        private String backupNdjson;

        /** 创建限流故障切换测试网关。 */
        private RateLimitedGateway(AppConfig config) {
            super(config);
        }

        /** 主提供方返回限流错误。 */
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
            resumeFlags.add(Boolean.valueOf(resume));
            if ("primary".equals(resolved.getProvider())) {
                toolEffects++;
                session.setNdjson(
                        ChatMessage.toNdjson(
                                java.util.Arrays.asList(
                                        assistantWithToolCall("call_rate_limit", "write_file"),
                                        ChatMessage.ofTool(
                                                "created", "write_file", "call_rate_limit"),
                                        ChatMessage.ofAssistant("截断尾巴"),
                                        ChatMessage.ofUser("内部续写提示"))));
                throw new IllegalStateException("HTTP 429 rate limit");
            }
            backupNdjson = session.getNdjson();
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setRawResponse("ok");
            result.setAssistantMessage(new AssistantMessage("done"));
            return result;
        }
    }

    /** 模拟主候选写入用户消息后立即失败的普通故障切换。 */
    private static class UserOnlyFailureGateway extends RetryingGateway {
        /** 记录每个候选是否误走恢复路径。 */
        private final List<Boolean> resumeFlags = new ArrayList<Boolean>();

        /** 记录每个候选收到的原始用户输入。 */
        private final List<String> userMessages = new ArrayList<String>();

        /** 记录备用候选开始前的安全会话快照。 */
        private String backupNdjson;

        /** 创建普通失败回滚测试网关。 */
        private UserOnlyFailureGateway(AppConfig config) {
            super(config);
        }

        /** 主候选模拟追加用户消息后失败，备用候选返回成功。 */
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
            resumeFlags.add(Boolean.valueOf(resume));
            userMessages.add(userMessage);
            if ("primary".equals(resolved.getProvider())) {
                session.setNdjson(
                        ChatMessage.toNdjson(
                                Collections.singletonList(ChatMessage.ofUser(userMessage))));
                throw new IllegalStateException("HTTP 429 rate limit");
            }
            backupNdjson = session.getNdjson();
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setRawResponse("ok");
            result.setAssistantMessage(new AssistantMessage("done"));
            return result;
        }
    }

    /** 模拟审批主模型失败、备用模型返回结构化批准结果。 */
    private static class ApprovalFallbackGateway extends SolonAiLlmGateway {
        /** 记录智能审批实际尝试的 Provider 和模型。 */
        private final List<String> attempts = new ArrayList<String>();

        /** 创建并把网关内部审批 Judge 注册到审批服务。 */
        private ApprovalFallbackGateway(
                AppConfig config, DangerousCommandApprovalService approvalService) {
            super(config, null, approvalService);
        }

        /** 主审批模型模拟服务端失败，备用模型返回批准 JSON。 */
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
                throw new IllegalStateException("HTTP 500 provider unavailable");
            }
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setAssistantMessage(
                    new AssistantMessage("{\"decision\":\"approve\",\"reason\":\"read only\"}"));
            return result;
        }
    }

    /** 构造带完整工具调用的 assistant 消息。 */
    private static AssistantMessage assistantWithToolCall(String callId, String toolName) {
        return new AssistantMessage(
                "",
                false,
                null,
                null,
                Collections.singletonList(
                        new ToolCall(
                                "0",
                                callId,
                                toolName,
                                "{}",
                                Collections.<String, Object>emptyMap())),
                null);
    }
}
