package com.jimuqu.solon.claw.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.bootstrap.PluginConfiguration;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.gateway.command.DefaultCommandService;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.annotation.Bean;
import org.noear.solon.core.Props;

class PluginRuntimeIntegrationTest {
    @TempDir Path tempDir;

    /** 插件管理器必须随所属应用上下文关闭，避免插件资源跨 Profile 生命周期残留。 */
    @Test
    void pluginManagerBeanShutsDownWithItsApplicationContext() throws Exception {
        Method factory = PluginConfiguration.class.getMethod("agentPluginManager", AppConfig.class);

        Bean bean = factory.getAnnotation(Bean.class);

        assertThat(bean).isNotNull();
        assertThat(bean.destroyMethod()).isEqualTo("shutdown");
    }

    /** 插件 Bean 创建时必须立即完成发现，避免带参数的初始化方法被框架忽略。 */
    @Test
    void pluginManagerBeanLoadsBundledPluginsDuringCreation() {
        PluginConfiguration plugins = new PluginConfiguration();
        AgentPluginManager manager = plugins.agentPluginManager(new AppConfig());
        try {
            assertThat(manager.listPlugins()).isNotEmpty();
            assertThat(manager.diagnostics()).isNotEmpty();
        } finally {
            manager.shutdown();
        }
    }

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
                        new SecurityPolicyService(env.appConfig),
                        env.processRegistry,
                        null,
                        plugins.pluginTools());

        assertThat(registry.listToolNames()).contains("plugin_echo");
        assertThat(registry.listToolNames().stream().filter("websearch"::equals).count())
                .isEqualTo(1);

        Object pluginTool =
                registry.resolveEnabledTools("MEMORY:room:user").stream()
                        .filter(
                                t ->
                                        t instanceof FunctionTool
                                                && "plugin_echo".equals(((FunctionTool) t).name()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("text", "ok");
        assertThat(((FunctionTool) pluginTool).handle(args)).isEqualTo("plugin:ok");
    }

    @Test
    void pluginToolDoesNotLeakIntoDelegatedSourceByDefault() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        PluginConfiguration plugins = new PluginConfiguration();
        plugins.onToolRegistered(
                new ToolRegistration(
                        "plugin_echo",
                        "plugin",
                        Collections.<String, Object>emptyMap(),
                        args -> "plugin:" + args.get("text")));
        DefaultToolRegistry registry =
                new DefaultToolRegistry(
                        env.appConfig,
                        new SqlitePreferenceStore(env.sqliteDatabase),
                        env.sessionRepository,
                        env.agentProfileService,
                        new CronJobService(env.appConfig, env.cronJobRepository),
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
                        new SecurityPolicyService(env.appConfig),
                        env.processRegistry,
                        null,
                        plugins.pluginTools());

        assertThat(registry.resolveEnabledToolNames("MEMORY:room:user")).contains("plugin_echo");
        assertThat(registry.resolveEnabledToolNames("MEMORY:room:user:delegate:child"))
                .doesNotContain("plugin_echo");
    }

    @Test
    void pluginCommandIsDispatchedWithoutOverridingBuiltinCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        PluginConfiguration plugins = new PluginConfiguration();
        plugins.onCommandRegistered("plugin_echo", args -> "plugin:" + args, "Plugin echo");
        plugins.onCommandRegistered("help", args -> "must-not-override", "Help override");
        DefaultCommandService commandService = pluginCommandService(env, plugins, null);

        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "room", "user", "/plugin_echo hello");
        GatewayReply reply = commandService.handle(message, "/plugin_echo hello");
        GatewayReply help = commandService.handle(message, "/help");

        assertThat(reply.getContent()).isEqualTo("plugin:hello");
        assertThat(reply.isCommandHandled()).isTrue();
        assertThat(help.getContent()).doesNotContain("must-not-override");
    }

    @Test
    void pluginsSlashCommandListsLoadedPluginDiagnostics() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path pluginDir = tempDir.resolve("status-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(
                pluginDir.resolve("plugin.yaml"),
                "name: status-plugin\n"
                        + "version: 1.0.0\n"
                        + "kind: backend\n"
                        + "description: Status plugin\n"
                        + "enabled: true\n"
                        + "entry: StatusPlugin\n");
        Files.writeString(
                pluginDir.resolve("StatusPlugin.java"),
                "import com.jimuqu.solon.claw.plugin.*;\n"
                        + "public class StatusPlugin implements AgentPlugin {\n"
                        + "  public void register(AgentPluginContext ctx) {}\n"
                        + "}\n");
        AgentPluginManager manager =
                new AgentPluginManager(
                        new AgentHookRegistry(),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        tempDir);
        PluginConfiguration plugins = new PluginConfiguration();
        manager.discoverAndLoad(plugins);
        DefaultCommandService commandService = pluginCommandService(env, plugins, manager);
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "room", "user", "/plugins");

        GatewayReply reply = commandService.handle(message, "/plugins");

        assertThat(reply.getContent())
                .contains("插件状态")
                .contains("loaded=1")
                .contains("status-plugin")
                .contains("loaded");
        assertThat(reply.getRuntimeMetadata())
                .containsEntry("command_status", "handled")
                .containsEntry("command", "plugins")
                .containsEntry("plugin_loaded", Integer.valueOf(1));
    }

    @Test
    void pluginConfigurationTreatsBuiltinsAsReservedNames() {
        PluginConfiguration plugins = new PluginConfiguration();

        assertThat(plugins.hasTool("websearch")).isTrue();
        assertThat(plugins.hasCommand("help")).isTrue();
        plugins.onToolRegistered(
                new ToolRegistration(
                        "websearch",
                        "plugin",
                        Collections.<String, Object>emptyMap(),
                        args -> "must-not-register"));
        plugins.onCommandRegistered("help", args -> "must-not-register", "Help override");

        assertThat(plugins.pluginTools()).isEmpty();
        assertThat(plugins.pluginCommands()).isEmpty();
    }

    @Test
    void appConfigLoadsPluginEnableDisableLists() {
        Props props = new Props();
        props.put("solonclaw.workspace", "target/test-runtime/plugin-config");
        props.put("solonclaw.plugins.enabled", "alpha,beta");
        props.put("solonclaw.plugins.disabled", "gamma, delta");

        AppConfig config = AppConfig.load(props);

        assertThat(config.getPlugins().getEnabled()).containsExactly("alpha", "beta");
        assertThat(config.getPlugins().getDisabled()).containsExactly("gamma", "delta");
    }

    private DefaultCommandService pluginCommandService(
            TestEnvironment env, PluginConfiguration plugins, AgentPluginManager pluginManager) {
        AppVersionService versionService = new AppVersionService(env.appConfig);
        LlmProviderService providerService = new LlmProviderService(env.appConfig);
        DashboardConfigService dashboardConfigService =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        DashboardRuntimeConfigService dashboardRuntimeConfigService =
                new DashboardRuntimeConfigService(env.appConfig, env.gatewayRuntimeRefreshService);
        DashboardProviderService dashboardProviderService =
                new DashboardProviderService(
                        env.appConfig, env.gatewayRuntimeRefreshService, providerService);
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
        return new DefaultCommandService(
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
                null,
                null,
                null,
                null,
                null,
                env.gatewayRestartCoordinator,
                env.slashConfirmService,
                plugins.pluginCommands(),
                pluginManager,
                null,
                null,
                null,
                null,
                null);
    }
}
