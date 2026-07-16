package com.jimuqu.solon.claw.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.ToolResult;

/** 验证工具事件使用执行层的结构化失败状态，而非匹配自由文本。 */
class SolonAiLlmGatewayToolEventTest {
    /** MCP 超时会在工具链异常处理后形成 ToolResult.error，必须传递为失败事件。 */
    @Test
    void toolResultErrorKeepsMcpTimeoutAsStructuredFailure() {
        String timeout = "MCP call timed out after 3.0s";

        assertThat(SolonAiLlmGateway.structuredToolError(ToolResult.error(timeout), timeout))
                .isEqualTo(timeout);
    }

    /** 统一工具 envelope 的 status=error 也必须转换为明确失败原因。 */
    @Test
    void errorEnvelopeKeepsItsStructuredFailureReason() {
        String observation = "{\"status\":\"error\",\"error\":\"command failed\"}";

        assertThat(
                        SolonAiLlmGateway.structuredToolError(
                                ToolResult.success(observation), observation))
                .isEqualTo("command failed");
    }

    /** 记忆工具使用 message 返回业务失败原因时也必须标记为失败。 */
    @Test
    void errorEnvelopeAcceptsMessageAsStructuredFailureReason() {
        String observation = "{\"status\":\"error\",\"message\":\"未找到可替换的记忆条目。\"}";

        assertThat(SolonAiLlmGateway.structuredToolError(null, observation))
                .isEqualTo("未找到可替换的记忆条目。");
    }
}
