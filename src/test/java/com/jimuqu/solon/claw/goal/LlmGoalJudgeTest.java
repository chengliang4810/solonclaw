package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 校验 {@link LlmGoalJudge} 的 JSON 解析、fail-open 与不可解析异常行为。 */
class LlmGoalJudgeTest {
    /** 可编程返回内容的 LlmGateway，用于模拟 judge 响应。 */
    static class ScriptedLlmGateway extends FakeLlmGateway {
        /** 脚本化的返回内容。 */
        String scriptedResponse;

        @Override
        public LlmResult chat(
                SessionRecord session, String systemPrompt, String userMessage, List<Object> toolObjects)
                throws Exception {
            if (scriptedResponse != null) {
                LlmResult r = new LlmResult();
                r.setAssistantMessage(ChatMessage.ofAssistant(scriptedResponse));
                r.setRawResponse(scriptedResponse);
                return r;
            }
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }
    }

    /** 构造一个带 5 秒超时的 goal 配置。 */
    private AppConfig.GoalConfig goalConfig() {
        AppConfig.GoalConfig c = new AppConfig.GoalConfig();
        c.setJudgeTimeoutSeconds(5);
        return c;
    }

    @Test
    void parsesDoneVerdict() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"all tests pass\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isDone()).isTrue();
        assertThat(r.getReason()).isEqualTo("all tests pass");
    }

    @Test
    void parsesWaitPidVerdict() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"wait\",\"wait_on_pid\":1234,\"reason\":\"编译中\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitOnPid()).isEqualTo(1234);
    }

    @Test
    void stripsJsonFence() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "```json\n{\"verdict\":\"continue\",\"reason\":\"more work\"}\n```";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
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
        LlmGoalJudge j = new LlmGoalJudge(broken, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isContinue()).isTrue(); // fail-open
    }

    @Test
    void unparseableJsonThrowsForBackstop() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "not json at all";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        // 模型有返回但不可解析 → 抛 GoalJudgeUnparseableException，让上层累计 parseFailures
        assertThatThrownByGoalJudgeUnparseable(j);
    }

    /** 断言 judge 抛出 GoalJudgeUnparseableException。 */
    private void assertThatThrownByGoalJudgeUnparseable(LlmGoalJudge j) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> j.judge(new GoalJudgeRequest("g", "resp", null, null)))
                .isInstanceOf(GoalJudgeUnparseableException.class);
    }
}
