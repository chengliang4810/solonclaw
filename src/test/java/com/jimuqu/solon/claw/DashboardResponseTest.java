package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.web.DashboardResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;

/** 验证控制台响应辅助类在设置 HTTP 状态和错误脱敏时保持统一契约。 */
public class DashboardResponseTest {
    /** 应在构造错误响应时同步设置状态码，并继续沿用已有敏感文本脱敏策略。 */
    @Test
    void shouldSetHttpStatusWhenBuildingDashboardError() {
        Context context = ContextEmpty.create();

        Map<String, Object> response =
                DashboardResponse.error(
                        context, 418, "DASHBOARD_TEST_ERROR", "token=sk-dashboardresponse12345");

        assertThat(context.status()).isEqualTo(418);
        assertThat(response.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(response.get("code")).isEqualTo("DASHBOARD_TEST_ERROR");
        assertThat(String.valueOf(response.get("error"))).contains("token=***");
        assertThat(String.valueOf(response)).doesNotContain("sk-dashboardresponse12345");
    }
}
