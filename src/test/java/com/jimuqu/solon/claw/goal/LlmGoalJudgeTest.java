package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 校验 {@link LlmGoalJudge} 的 JSON 解析、fail-open 与不可解析异常行为。 */
class LlmGoalJudgeTest {
    /** 可编程返回内容的 LlmGateway，用于模拟 judge 响应，并捕获传入的用户提示。 */
    static class ScriptedLlmGateway extends FakeLlmGateway {
        /** 脚本化的返回内容。 */
        String scriptedResponse;

        /** 捕获最近一次 chat 收到的 userMessage，供断言裁决器是否感知契约/子目标。 */
        String capturedUserMessage;

        /** 记录 executeOnce 是否被调用。 */
        boolean executeOnceCalled;

        /** 记录 chat 是否被调用（judgeProvider 留空时应走此路径兜底）。 */
        boolean chatCalled;

        /** 捕获 executeOnce 收到的 resolved LlmConfig，供断言 provider/model 覆盖。 */
        AppConfig.LlmConfig capturedResolved;

        @Override
        public LlmResult chat(
                SessionRecord session, String systemPrompt, String userMessage, List<Object> toolObjects)
                throws Exception {
            chatCalled = true;
            capturedUserMessage = userMessage;
            if (scriptedResponse != null) {
                LlmResult r = new LlmResult();
                r.setAssistantMessage(ChatMessage.ofAssistant(scriptedResponse));
                r.setRawResponse(scriptedResponse);
                return r;
            }
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }

        @Override
        public LlmResult executeOnce(
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
            executeOnceCalled = true;
            capturedResolved = resolved;
            capturedUserMessage = userMessage;
            if (scriptedResponse != null) {
                LlmResult r = new LlmResult();
                r.setAssistantMessage(ChatMessage.ofAssistant(scriptedResponse));
                r.setRawResponse(scriptedResponse);
                return r;
            }
            return super.executeOnce(
                    session,
                    systemPrompt,
                    userMessage,
                    toolObjects,
                    feedbackSink,
                    eventSink,
                    resume,
                    resolved,
                    runContext);
        }
    }

    /** 构造一个带默认 primary provider 的应用配置，goal 子配置带 5 秒超时。 */
    private AppConfig appConfig() {
        AppConfig config = new AppConfig();
        config.getGoal().setJudgeTimeoutSeconds(5);
        AppConfig.ProviderConfig primary = new AppConfig.ProviderConfig();
        primary.setName("Primary");
        primary.setBaseUrl("https://api.example.com");
        primary.setApiKey("primary-key");
        primary.setDefaultModel("gpt-mini");
        primary.setDialect("openai-responses");
        config.getProviders().put("primary", primary);
        return config;
    }

    @Test
    void parsesDoneVerdict() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"all tests pass\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isDone()).isTrue();
        assertThat(r.getReason()).isEqualTo("all tests pass");
    }

    @Test
    void parsesWaitPidVerdict() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"wait\",\"wait_on_pid\":1234,\"reason\":\"编译中\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitOnPid()).isEqualTo(1234);
    }

    @Test
    void stripsJsonFence() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "```json\n{\"verdict\":\"continue\",\"reason\":\"more work\"}\n```";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isContinue()).isTrue();
    }

    @Test
    void failsOpenOnException() {
        // 用一个抛异常的 gateway
        LlmGateway broken =
                new LlmGateway() {
                    @Override
                    public LlmResult chat(
                            SessionRecord s, String sp, String um, List<Object> tools) throws Exception {
                        throw new RuntimeException("network down");
                    }

                    @Override
                    public LlmResult resume(SessionRecord s, String sp, List<Object> tools) {
                        return null;
                    }
                };
        LlmGoalJudge j =
                new LlmGoalJudge(broken, appConfig(), new LlmProviderService(new AppConfig()));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isContinue()).isTrue(); // fail-open
    }

    @Test
    void unparseableJsonThrowsForBackstop() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "not json at all";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        // 模型有返回但不可解析 → 抛 GoalJudgeUnparseableException，让上层累计 parseFailures
        assertThatThrownByGoalJudgeUnparseable(j);
    }

    /** 断言 judge 抛出 GoalJudgeUnparseableException。 */
    private void assertThatThrownByGoalJudgeUnparseable(LlmGoalJudge j) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> j.judge(new GoalJudgeRequest("g", "resp", null, null)))
                .isInstanceOf(GoalJudgeUnparseableException.class);
    }

    @Test
    void judgeSeesContractBlockWhenPresent() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"verified\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        GoalContract contract = new GoalContract();
        contract.setOutcome("产出文档");
        contract.setVerification("文件存在且测试通过");
        j.judge(new GoalJudgeRequest("g", "resp", null, contract));
        // 裁决器用户提示应带上契约块（含 Verification）与 DONE 严格判定语
        assertThat(gw.capturedUserMessage)
                .contains("Completion contract:")
                .contains("- Verification: 文件存在且测试通过")
                .contains("- Outcome: 产出文档")
                .contains("Verification criterion");
    }

    @Test
    void judgeSeesSubgoalsWhenNoContract() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"continue\",\"reason\":\"more\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        j.judge(new GoalJudgeRequest("g", "resp", java.util.Arrays.asList("覆盖A", "覆盖B"), null));
        // 裁决器用户提示应带上编号子目标列表，并提示逐条核对
        assertThat(gw.capturedUserMessage)
                .contains("Additional criteria")
                .contains("- 1. 覆盖A")
                .contains("- 2. 覆盖B")
                .doesNotContain("Completion contract:");
    }

    @Test
    void judgeFoldsSubgoalsIntoContractWhenBothPresent() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"ok\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        GoalContract contract = new GoalContract();
        contract.setVerification("测试通过");
        j.judge(
                new GoalJudgeRequest(
                        "g", "resp", java.util.Arrays.asList("额外准则"), contract));
        // 契约优先；子目标折叠为 Extra criterion，而非作为独立 Additional criteria 块
        assertThat(gw.capturedUserMessage)
                .contains("Completion contract:")
                .contains("- Verification: 测试通过")
                .contains("- Extra criterion 1: 额外准则")
                .doesNotContain("Additional criteria");
    }

    @Test
    void judgeUsesPlainPromptWhenNoContractNoSubgoals() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"continue\",\"reason\":\"more\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, appConfig(), new LlmProviderService(new AppConfig()));
        j.judge(new GoalJudgeRequest("g", "resp", null, null));
        // 裸目标：不含契约块或子目标块
        assertThat(gw.capturedUserMessage)
                .contains("Goal: g")
                .doesNotContain("Completion contract:")
                .doesNotContain("Additional criteria");
    }

    // ===== Task A：judgeProvider 覆盖 =====

    @Test
    void judgeProviderConfiguredRoutesToExecuteOnceWithResolvedConfig() {
        // judgeProvider 配置 → 走 executeOnce，resolved 的 provider/model 与配置一致
        AppConfig config = appConfig();
        config.getGoal().setJudgeProvider("primary");
        config.getGoal().setJudgeModel("gpt-mini");
        config.getGoal().setJudgeMaxTokens(2048);
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"ok\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, config, new LlmProviderService(config));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));

        assertThat(r.isDone()).isTrue();
        assertThat(gw.executeOnceCalled).isTrue();
        assertThat(gw.chatCalled).isFalse();
        // resolved provider/model 命中配置的廉价模型
        assertThat(gw.capturedResolved).isNotNull();
        assertThat(gw.capturedResolved.getProvider()).isEqualTo("primary");
        assertThat(gw.capturedResolved.getModel()).isEqualTo("gpt-mini");
        // 裁决器为非流式，maxTokens 来自 goal 配置
        assertThat(gw.capturedResolved.isStream()).isFalse();
        assertThat(gw.capturedResolved.getMaxTokens()).isEqualTo(2048);
    }

    @Test
    void judgeProviderBlankFallsBackToChat() {
        // judgeProvider 留空 → 按主模型兜底走 chat(...)
        AppConfig config = appConfig();
        // judgeProvider 保持默认空串
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"continue\",\"reason\":\"more\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, config, new LlmProviderService(config));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));

        assertThat(r.isContinue()).isTrue();
        assertThat(gw.chatCalled).isTrue();
        assertThat(gw.executeOnceCalled).isFalse();
    }

    @Test
    void judgeProviderUnresolvedFailsOpenToContinue() {
        // judgeProvider 指向不存在的 provider 键 → fail-open 继续（catch IllegalStateException）
        AppConfig config = appConfig();
        config.getGoal().setJudgeProvider("nonexistent");
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"ok\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, config, new LlmProviderService(config));
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));

        // 解析失败后 fail-open 走 chat 兜底，仍返回裁决结果
        assertThat(r.isDone()).isTrue();
        // 因 fail-open 回退到 chat，executeOnce 未被调用
        assertThat(gw.executeOnceCalled).isFalse();
        assertThat(gw.chatCalled).isTrue();
    }
}
