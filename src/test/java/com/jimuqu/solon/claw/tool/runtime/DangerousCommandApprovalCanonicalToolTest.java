package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 验证工具网关把所有公开浏览器动作归入统一浏览器安全分类。 */
class DangerousCommandApprovalCanonicalToolTest {
    @Test
    void shouldCanonicalizeAllCurrentBrowserActions() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        Method canonical =
                DangerousCommandApprovalService.class.getDeclaredMethod(
                        "canonicalGatewayToolName", String.class);
        canonical.setAccessible(true);

        for (String toolName :
                Arrays.asList(
                        "browser_snapshot",
                        "browser_scroll",
                        "browser_back",
                        "browser_press",
                        "browser_get_images",
                        "browser_vision",
                        "browser_console",
                        "browser_cdp",
                        "browser_dialog")) {
            assertThat(canonical.invoke(service, toolName))
                    .as(toolName)
                    .isEqualTo(ToolNameConstants.BROWSER);
        }
    }
}
