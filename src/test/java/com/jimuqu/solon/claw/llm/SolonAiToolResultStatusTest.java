package com.jimuqu.solon.claw.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证工具业务失败可在安全包装前被统一识别。 */
class SolonAiToolResultStatusTest {
    /** 统一 envelope 的 error 状态不得被当作成功完成。 */
    @Test
    void recognizesStructuredErrorObservation() {
        String error =
                SolonAiLlmGateway.structuredToolError(
                        null, "{\"status\":\"error\",\"error\":\"command failed\"}");

        assertThat(error).isEqualTo("command failed");
    }
}
