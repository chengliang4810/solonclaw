package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** 验证重定向判断与 Location 解析应复用共享工具，避免各模块复制安全边界逻辑。 */
class HttpRedirectSupportReuseTest {
    /** 目标 HTTP 调用点不应再保留同语义私有重定向工具方法。 */
    @Test
    void shouldNotKeepPrivateRedirectHelpers() throws Exception {
        String[] paths =
                new String[] {
                    "src/main/java/com/jimuqu/solon/claw/support/BoundedAttachmentIO.java",
                    "src/main/java/com/jimuqu/solon/claw/skillhub/support/DefaultSkillHubHttpClient.java",
                    "src/main/java/com/jimuqu/solon/claw/web/DashboardMcpService.java",
                    "src/main/java/com/jimuqu/solon/claw/web/DashboardProviderService.java",
                    "src/main/java/com/jimuqu/solon/claw/support/update/AppUpdateService.java",
                    "src/main/java/com/jimuqu/solon/claw/web/WeixinQrSetupService.java",
                    "src/main/java/com/jimuqu/solon/claw/gateway/platform/weixin/WeiXinChannelAdapter.java",
                };
        for (String path : paths) {
            String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            assertFalse(source.contains("private boolean isRedirect("), path);
            assertFalse(source.contains("private static boolean isRedirect("), path);
            assertFalse(source.contains("private String resolveRedirectUrl("), path);
            assertFalse(source.contains("private static String resolveRedirectUrl("), path);
        }
    }
}
