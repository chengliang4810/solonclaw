package com.jimuqu.solon.claw.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.bootstrap.PluginConfiguration;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.gateway.command.DefaultCommandService;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;

class PluginRuntimeIntegrationTest {

    @Test
    void pluginToolIsExposedByToolRegistryWithoutOverridingBuiltinTool() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        PluginConfiguration plugins = new PluginConfiguration();
        plugins.onToolRegistered(
                new ToolRegistration(
                                "plugin_echo",
                                "plugin",
                                Collections.<String, Object>emptyMap(),
                                args -> "plugin:" + args.get("text"))
                        .description("Plugin echo"));
        plugins.onToolRegistered(
                new ToolRegistration(
                        "websearch",
                        "plugin",
                        Collections.<String, Object>emptyMap(),
                        args -> "must-not-override"));
        DefaultToolRegistry registry =
                new DefaultToolRegistry(
                        env.appConfig,
                        new SqlitePreferenceStore(env.sqliteDatabase),
                        env.sessionRepository,
                        env.agentProfileService,
                        new CronJobService(env.appConfig, env.cronJobRepository),
                        env.kanbanService,
                        env.deliveryService,
                        env.memoryService,
                        env.sessionSearchService,
                        env.localSkillService,
                        env.skillHubService,
                        env.checkpointService,
                        env.delegationService,
                        null,
                        null,
                        env.gatewayRuntimeRefreshService,
                        null,
                        env.processRegistry,
                        null,
                        plugins.pluginTools());

        assertThat(registry.listToolNames()).contains("plugin_echo");
        assertThat(registry.listToolNames().stream().filter("websearch"::equals).count()).isEqualTo(1);

        Object pluginTool =
                registry.resolveEnabledTools("MEMORY:room:user").stream()
                        .filter(t -> t instanceof FunctionTool && "plugin_echo".equals(((FunctionTool) t).name()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("text", "ok");
        assertThat(((FunctionTool) pluginTool).handle(args)).isEqualTo("plugin:ok");
    }

    @Test
    void pluginCommandIsDispatchedWithoutOverridingBuiltinCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        PluginConfiguration plugins = new PluginConfiguration();
        plugins.onCommandRegistered("plugin_echo", args -> "plugin:" + args, "Plugin echo");
        plugins.onCommandRegistered("help", args -> "must-not-override", "Help override");
        AppVersionService versionService = new AppVersionService(env.appConfig);
        LlmProviderService providerService = new LlmProviderService(env.appConfig);
        DashboardConfigService dashboardConfigService =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        DashboardRuntimeConfigService dashboardRuntimeConfigService =
                new DashboardRuntimeConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        DashboardProviderService dashboardProviderService =
                new DashboardProviderService(env.appConfig, env.gatewayRuntimeRefreshService, providerService);
        RuntimeSettingsService runtimeSettingsService =
                new RuntimeSettingsService(
                        env.appConfig,
                        env.globalSettingRepository,
                        env.deliveryService,
                        dashboardConfigService,
                        dashboardRuntimeConfigService,
                        versionService,
                        providerService,
                        dashboardProviderService);
        DefaultCommandService commandService =
                new DefaultCommandService(
                        env.sessionRepository,
                        env.toolRegistry,
                        env.localSkillService,
                        env.cronJobRepository,
                        env.conversationOrchestrator,
                        sourceKey -> "",
                        env.contextCompressionService,
                        env.deliveryService,
                        env.gatewayAuthorizationService,
                        env.checkpointService,
                        env.skillHubService,
                        env.appConfig,
                        env.globalSettingRepository,
                        env.processRegistry,
                        runtimeSettingsService,
                        new DisplaySettingsService(env.appConfig, env.globalSettingRepository),
                        new AppUpdateService(env.appConfig, versionService),
                        env.dangerousCommandApprovalService,
                        env.agentRunControlService,
                        env.agentProfileService,
                        null,
                        env.kanbanService,
                        null,
                        null,
                        null,
                        null,
                        env.gatewayRestartCoordinator,
                        env.slashConfirmService,
                        plugins.pluginCommands());

        GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "room", "user", "/plugin_echo hello");
        GatewayReply reply = commandService.handle(message, "/plugin_echo hello");
        GatewayReply help = commandService.handle(message, "/help");

        assertThat(reply.getContent()).isEqualTo("plugin:hello");
        assertThat(reply.isCommandHandled()).isTrue();
        assertThat(help.getContent()).doesNotContain("must-not-override");
    }
}
