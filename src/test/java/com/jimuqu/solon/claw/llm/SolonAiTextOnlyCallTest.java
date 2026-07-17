package com.jimuqu.solon.claw.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 纯文本辅助模型调用的工具隔离测试。 */
class SolonAiTextOnlyCallTest {
    /** 验证纯文本调用期间禁用内建工具，并在调用结束后恢复普通行为。 */
    @Test
    void shouldSuppressBuiltinToolsOnlyWithinTextOnlyCall() throws Exception {
        InspectingGateway gateway = new InspectingGateway();

        gateway.chatTextOnly(new SessionRecord(), "system", "user");

        assertThat(gateway.suppressedDuringCall).isTrue();
        assertThat(gateway.shouldAttachBuiltinTools()).isTrue();
    }

    /** 在不访问真实模型的情况下观察纯文本调用范围。 */
    private static final class InspectingGateway extends SolonAiLlmGateway {
        /** 调用期间是否已关闭内建工具。 */
        private boolean suppressedDuringCall;

        /** 创建观察网关。 */
        private InspectingGateway() {
            super(new AppConfig());
        }

        /** 记录纯文本调用范围，不访问模型。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            suppressedDuringCall = !shouldAttachBuiltinTools() && toolObjects.isEmpty();
            return new LlmResult();
        }
    }
}
