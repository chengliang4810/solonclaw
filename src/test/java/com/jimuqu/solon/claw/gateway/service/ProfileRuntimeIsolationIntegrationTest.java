package com.jimuqu.solon.claw.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.SolonClawApp;
import com.jimuqu.solon.claw.bootstrap.DashboardConfiguration;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.MemoryArchiveService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.profile.ProfileBeanResolver;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.web.DashboardChatController;
import com.jimuqu.solon.claw.web.DashboardProfileController;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.handle.Filter;

/** 通过真实 Solon 根容器验证命名 Profile 子运行图不复用 Bean，也不污染主 HTTP 链。 */
class ProfileRuntimeIsolationIntegrationTest {
    /** 临时 default Profile 根目录。 */
    private static Path root;

    /** 测试前的 Profile 根属性。 */
    private static String previousProfileRoot;

    /** 测试前的 Profile 名属性。 */
    private static String previousProfileName;

    /** 测试前的工作区属性。 */
    private static String previousWorkspace;

    /** 启动真实服务端根容器和一个命名 Profile。 */
    @BeforeAll
    static void startApplication() throws Exception {
        root = Files.createTempDirectory("solonclaw-profile-runtime");
        Path worker = root.resolve("profiles/worker");
        Files.createDirectories(worker);
        Files.writeString(root.resolve("config.yml"), config("root", "root-key", true));
        Files.writeString(worker.resolve("config.yml"), config("worker", "worker-key", true));

        previousProfileRoot = System.getProperty("solonclaw.profile.root");
        previousProfileName = System.getProperty("solonclaw.profile.name");
        previousWorkspace = System.getProperty("solonclaw.workspace");
        System.setProperty("solonclaw.profile.root", root.toString());
        System.setProperty("solonclaw.profile.name", "default");
        System.setProperty("solonclaw.workspace", root.toString());

        Solon.start(
                SolonClawApp.class,
                new String[] {
                    "--server.port=" + freePort(),
                    "--solonclaw.workspace=" + root,
                    "--solonclaw.scheduler.enabled=false"
                },
                app -> {
                    // 本测试验证完整 Bean 图与 Profile 隔离，不依赖真实网络监听。
                    app.enableHttp(false);
                    app.enableWebSocket(false);
                });
    }

    /** 停止根容器并恢复测试前系统属性。 */
    @AfterAll
    static void stopApplication() {
        try {
            Solon.stopBlock(false, 0);
        } finally {
            restoreProperty("solonclaw.profile.root", previousProfileRoot);
            restoreProperty("solonclaw.profile.name", previousProfileName);
            restoreProperty("solonclaw.workspace", previousWorkspace);
            if (root != null) {
                FileUtil.del(root.toFile());
            }
        }
    }

    /** 真实根容器必须完成工具、对话与扫码服务装配，防止构造注入再次形成依赖环。 */
    @Test
    void wiresConversationToolAndQrBeansWithoutDependencyCycle() {
        AppContext rootContext = Solon.context();

        assertThat(rootContext.getBean(ToolRegistry.class)).isNotNull();
        assertThat(rootContext.getBean(ConversationOrchestrator.class)).isNotNull();
        assertThat(rootContext.getBean(DomesticQrSetupService.class)).isNotNull();
    }

    /** 子容器拥有完整独立运行图，重载和关闭均不改变主 Solon 容器及 HTTP 注册数量。 */
    @Test
    void isolatesRuntimeBeansAndKeepsRootContextAndHttpChainAlive() throws Exception {
        AppContext rootContext = Solon.context();
        ProfileMultiplexRuntimeManager manager =
                rootContext.getBean(ProfileMultiplexRuntimeManager.class);
        assertThat(manager.servedProfiles()).containsExactly("default", "worker");
        ProfileRuntimeBundle worker = manager.runtimes().get("worker");
        assertThat(worker).isNotNull();
        AppContext child = worker.appContext();

        int routesBeforeReload = Solon.app().router().findAll().size();
        int filtersBeforeReload = Solon.app().chains().getFilterNodes().size();
        boolean shutdownHookBeforeReload = gatewayShutdownHookRegistered();
        manager.reload();
        assertThat(Solon.context()).isSameAs(rootContext);
        assertThat(Solon.app().router().findAll()).hasSize(routesBeforeReload);
        assertThat(Solon.app().chains().getFilterNodes()).hasSize(filtersBeforeReload);
        assertThat(gatewayShutdownHookRegistered()).isEqualTo(shutdownHookBeforeReload);

        worker = manager.runtimes().get("worker");
        child = worker.appContext();
        assertThat(child).isNotSameAs(rootContext);
        assertThat(child.app()).isNull();
        assertThat(child.getBeansOfType(Filter.class)).isEmpty();
        assertThat(child.getBean(DashboardConfiguration.class)).isNull();
        assertThat(child.getBean(DashboardChatController.class)).isNull();
        assertThat(child.getBean(DashboardProfileController.class)).isNull();
        assertThat(child.getBean(ProfileManager.class)).isNull();
        assertThat(child.getBean(WeixinQrSetupService.class)).isNull();
        assertThat(child.getBean(DomesticQrSetupService.class)).isNull();
        assertThat(Solon.context()).isSameAs(rootContext);

        assertDifferentBean(rootContext, child, AppConfig.class);
        assertDifferentBean(rootContext, child, SqliteDatabase.class);
        assertDifferentBean(rootContext, child, SessionRepository.class);
        assertDifferentBean(rootContext, child, ConversationOrchestrator.class);
        assertDifferentBean(rootContext, child, DefaultGatewayService.class);
        assertDifferentBean(rootContext, child, DeliveryService.class);
        assertDifferentBean(rootContext, child, ToolRegistry.class);
        assertDifferentBean(rootContext, child, LocalSkillService.class);
        assertDifferentBean(rootContext, child, MemoryService.class);
        assertDifferentBean(rootContext, child, MemoryManager.class);
        assertDifferentBean(rootContext, child, MemoryArchiveService.class);
        assertDifferentBean(rootContext, child, McpRuntimeService.class);
        assertDifferentBean(rootContext, child, ChannelConnectionManager.class);
        assertThat(child.getBean(DashboardWorkspaceService.class)).isNotNull();
        assertThat(child.getBean(ToolRegistry.class).listToolNames()).contains("workspace_manage");

        AppConfig rootConfig = rootContext.getBean(AppConfig.class);
        AppConfig workerConfig = child.getBean(AppConfig.class);
        assertThat(rootConfig.getRuntime().getStateDb())
                .isEqualTo(root.resolve("data/state.db").toString());
        assertThat(workerConfig.getRuntime().getStateDb())
                .isEqualTo(root.resolve("profiles/worker/data/state.db").toString());
        assertThat(rootConfig.getRuntime().getSkillsDir())
                .isEqualTo(root.resolve("skills").toString());
        assertThat(workerConfig.getRuntime().getSkillsDir())
                .isEqualTo(root.resolve("profiles/worker/skills").toString());
        assertThat(rootConfig.getRuntime().getContextDir())
                .isEqualTo(root.resolve("context").toString());
        assertThat(workerConfig.getRuntime().getContextDir())
                .isEqualTo(root.resolve("profiles/worker/context").toString());
        assertThat(rootConfig.getProviders().get("default").getApiKey()).isEqualTo("root-key");
        assertThat(workerConfig.getProviders().get("default").getApiKey()).isEqualTo("worker-key");
        assertThat(rootConfig.getChannels().getFeishu().getAppSecret()).isEqualTo("root-secret");
        assertThat(workerConfig.getChannels().getFeishu().getAppSecret())
                .isEqualTo("worker-secret");
        assertThat(workerConfig.getGateway().isMultiplexProfiles()).isFalse();
        assertThat(rootContext.getBean(DefaultGatewayService.class).profileName())
                .isEqualTo("default");
        assertThat(child.getBean(DefaultGatewayService.class).profileName()).isEqualTo("worker");
        assertThat(ProfileBeanResolver.getBean(LlmGateway.class))
                .isSameAs(rootContext.getBean(LlmGateway.class));
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open("worker", worker.home(), Collections.emptyMap(), child)) {
            assertThat(ProfileBeanResolver.getBean(LlmGateway.class))
                    .isSameAs(child.getBean(LlmGateway.class));
        }

        Map<PlatformType, ChannelAdapter> rootAdapters =
                rootContext.getBean(ChannelConnectionManager.class).adapters();
        Map<PlatformType, ChannelAdapter> workerAdapters =
                child.getBean(ChannelConnectionManager.class).adapters();
        for (PlatformType platform : rootAdapters.keySet()) {
            assertThat(workerAdapters.get(platform)).isNotSameAs(rootAdapters.get(platform));
        }

        SqliteDatabase rootDatabase = rootContext.getBean(SqliteDatabase.class);
        manager.close();
        assertThat(rootContext.isStarted()).isTrue();
        assertThat(Solon.context()).isSameAs(rootContext);
        assertThat(rootContext.getBean(SqliteDatabase.class)).isSameAs(rootDatabase);
        try (Connection connection = rootDatabase.openConnection()) {
            assertThat(connection.isClosed()).isFalse();
        }
        assertThat(Solon.app().router().findAll()).hasSize(routesBeforeReload);
        assertThat(Solon.app().chains().getFilterNodes()).hasSize(filtersBeforeReload);
    }

    /** 断言根容器与命名 Profile 对同一类型返回不同实例。 */
    private static <T> void assertDifferentBean(
            AppContext rootContext, AppContext childContext, Class<T> type) {
        assertThat(rootContext.getBean(type)).as("root bean %s", type.getSimpleName()).isNotNull();
        assertThat(childContext.getBean(type))
                .as("child bean %s", type.getSimpleName())
                .isNotNull();
        assertThat(childContext.getBean(type)).isNotSameAs(rootContext.getBean(type));
    }

    /** 生成关闭网络渠道和后台调度的最小 Profile 配置。 */
    private static String config(String appId, String apiKey, boolean multiplex) {
        return "providers:\n"
                + "  default:\n"
                + "    name: "
                + appId
                + "\n"
                + "    baseUrl: https://api.example.test\n"
                + "    apiKey: "
                + apiKey
                + "\n"
                + "    defaultModel: test-model\n"
                + "    dialect: openai-responses\n"
                + "model:\n"
                + "  providerKey: default\n"
                + "solonclaw:\n"
                + "  scheduler:\n"
                + "    enabled: false\n"
                + "  proactive:\n"
                + "    enabled: false\n"
                + "  gateway:\n"
                + "    multiplexProfiles: "
                + multiplex
                + "\n"
                + "  channels:\n"
                + "    feishu:\n"
                + "      enabled: false\n"
                + "      appId: "
                + appId
                + "\n"
                + "      appSecret: "
                + appId
                + "-secret\n";
    }

    /** 查找一个当前可用的本地 HTTP 端口。 */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 读取主应用的 JVM 关闭钩子注册标记，确认 child reload 不新增全局钩子。 */
    private static boolean gatewayShutdownHookRegistered() throws Exception {
        Field field = SolonClawApp.class.getDeclaredField("gatewayShutdownHookRegistered");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    /** 恢复或清理测试修改的系统属性。 */
    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
