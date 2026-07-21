package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.gateway.command.DefaultCommandService;
import com.jimuqu.solon.claw.support.CommandServiceTestSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import org.junit.jupiter.api.Test;

public class VersionUpdateCommandTest {
    @Test
    void shouldHandleVersionSubcommandsOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CapturingAppUpdateService updateService = new CapturingAppUpdateService(env.appConfig);
        DefaultCommandService commandService = commandService(env, updateService);
        GatewayMessage message = env.message("room", "user", "/version");

        GatewayReply versionReply = commandService.handle(message, "/version");
        GatewayReply helpReply = commandService.handle(message, "/version help");
        GatewayReply checkReply = commandService.handle(message, "/version check");
        GatewayReply runReply = commandService.handle(message, "/version update");

        assertThat(versionReply.getContent()).contains("搴旂敤鐗堟湰");
        assertThat(updateService.formatCalls).isEqualTo(2);
        assertThat(updateService.startCalls).isEqualTo(1);
        assertThat(helpReply.getContent())
                .contains("/version - 查看当前版本")
                .contains("/version check - 检查是否存在新版本")
                .contains("/version update - 下载并应用更新");
        assertThat(checkReply.getContent()).contains("搴旂敤鐗堟湰");
        assertThat(runReply.getContent()).contains("started update");
        assertThat(CommandRegistry.resolve("update")).isNull();
        assertThat(CommandRegistry.resolve("skin")).isNull();
    }

    private DefaultCommandService commandService(
            TestEnvironment env, AppUpdateService updateService) {
        return CommandServiceTestSupport.commandServiceWithUpdate(env, updateService);
    }

    private static class CapturingAppUpdateService extends AppUpdateService {
        private int formatCalls;
        private int startCalls;

        private CapturingAppUpdateService(com.jimuqu.solon.claw.config.AppConfig appConfig) {
            super(appConfig, new AppVersionService(appConfig));
        }

        @Override
        public String formatVersionReport(boolean forceRefresh) {
            formatCalls++;
            return "搴旂敤鐗堟湰: v0.0.1";
        }

        @Override
        public UpdateResult startUpdate() {
            startCalls++;
            return UpdateResult.ok("started update");
        }
    }
}
