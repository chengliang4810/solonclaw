package com.jimuqu.solon.claw.web;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** 验证 Slash confirm 过期投影由状态对象提供，避免 Dashboard 与服务层各自手算。 */
class SlashConfirmExpiryReuseTest {
    /** Dashboard 诊断输出不应复制 SlashConfirmService 的默认过期时间计算。 */
    @Test
    void dashboardDiagnosticsShouldReusePendingConfirmExpiryProjection() throws Exception {
        String source =
                new String(
                        Files.readAllBytes(
                                Paths.get(
                                        "src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java")),
                        StandardCharsets.UTF_8);

        assertFalse(
                source.contains("pending.getCreatedAt() + SlashConfirmService.DEFAULT_TIMEOUT_MS"));
        assertFalse(source.contains("remainingMillis = expiresAt - now"));
    }
}
