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

class GoalContractDrafterTest {
    /** 可脚本化的辅助网关：返回预设响应。 */
    static class Scripted extends FakeLlmGateway {
        String resp;

        @Override
        public LlmResult chat(SessionRecord s, String sp, String um, List<Object> tools)
                throws Exception {
            if (resp != null) {
                LlmResult r = new LlmResult();
                r.setAssistantMessage(ChatMessage.ofAssistant(resp));
                r.setRawResponse(resp);
                return r;
            }
            return super.chat(s, sp, um, tools);
        }
    }

    @Test
    void draftsContractFromObjective() {
        Scripted gw = new Scripted();
        gw.resp =
                "{\"outcome\":\"测试通过\",\"verification\":\"mvn test\","
                        + "\"constraints\":\"不改公共 API\",\"boundaries\":\"\",\"stop_when\":\"遇到阻塞\"}";
        GoalContractDrafter d = new GoalContractDrafter(gw, new AppConfig.GoalConfig());
        GoalContract c = d.draft("补齐测试");
        assertThat(c.getOutcome()).isEqualTo("测试通过");
        assertThat(c.getVerification()).isEqualTo("mvn test");
        assertThat(c.getConstraints()).isEqualTo("不改公共 API");
        assertThat(c.getStopWhen()).isEqualTo("遇到阻塞");
    }

    @Test
    void returnsEmptyContractOnFailure() {
        LlmGateway broken =
                new LlmGateway() {
                    @Override
                    public LlmResult chat(SessionRecord s, String sp, String um, List<Object> t)
                            throws Exception {
                        throw new RuntimeException("down");
                    }

                    @Override
                    public LlmResult resume(SessionRecord s, String sp, List<Object> t) {
                        return null;
                    }
                };
        GoalContractDrafter d = new GoalContractDrafter(broken, new AppConfig.GoalConfig());
        GoalContract c = d.draft("g");
        assertThat(c.isEmpty()).isTrue(); // 失败返回空契约
    }

    @Test
    void returnsEmptyContractOnUnparseableJson() {
        Scripted gw = new Scripted();
        gw.resp = "this is not json at all";
        GoalContractDrafter d = new GoalContractDrafter(gw, new AppConfig.GoalConfig());
        GoalContract c = d.draft("g");
        assertThat(c.isEmpty()).isTrue(); // 解析失败返回空契约
    }
}
