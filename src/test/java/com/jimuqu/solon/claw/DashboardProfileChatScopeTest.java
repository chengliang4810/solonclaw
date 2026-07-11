package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.web.DashboardChatController;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;

/** 验证 Dashboard Chat 按请求 Profile 选择独立网关并保留稳定错误语义。 */
public class DashboardProfileChatScopeTest {
    /** 目标 Profile 未启动网关时必须返回 409，未知 Profile 必须返回 404。 */
    @Test
    void shouldRejectUnavailableOrUnknownTargetProfileBeforeLocalChatExecution() throws Exception {
        Path root = Files.createTempDirectory("dashboard-profile-chat-");
        Files.createDirectories(root.resolve("profiles").resolve("worker"));
        ProfileManager manager =
                new ProfileManager(
                        root,
                        Files.createTempDirectory("dashboard-profile-chat-wrappers-"),
                        "solonclaw");
        AppConfig currentConfig = new AppConfig();
        currentConfig.getRuntime().setHome(root.toString());
        currentConfig.getWorkspace().setDir(root.toString());
        DashboardChatController controller =
                new DashboardChatController(
                        null, new DashboardProfileContext(manager, currentConfig));

        Context stopped = request("worker");
        Map<String, Object> unavailable = controller.startRun(stopped);
        assertThat(stopped.status()).isEqualTo(409);
        assertThat(unavailable)
                .containsEntry("success", false)
                .containsEntry("code", "PROFILE_GATEWAY_NOT_RUNNING");

        Context unknown = request("missing");
        Map<String, Object> missing = controller.startRun(unknown);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(missing)
                .containsEntry("success", false)
                .containsEntry("code", "PROFILE_NOT_FOUND");
    }

    /** 创建只携带 Profile 查询参数的空请求。 */
    private Context request(String profile) {
        Context context = ContextEmpty.create();
        context.paramMap().put("profile", profile);
        return context;
    }
}
