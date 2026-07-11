package com.jimuqu.solon.claw.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;

/** 验证 Profile 复用管理器只公开成功运行时，并逐平台处理重复凭据。 */
class ProfileMultiplexRuntimeManagerTest {
    /** 临时 default Profile 根目录。 */
    @TempDir Path root;

    /** 重复凭据只禁用后出现的平台，不同凭据继续承载，启动失败项不进入状态。 */
    @Test
    void servesOnlySuccessfulRuntimesAndDisablesOnlyDuplicateCredentials() throws Exception {
        writeProfile("alpha", "shared-app", "shared-secret");
        writeProfile("beta", "shared-app", "shared-secret");
        writeProfile("gamma", "gamma-app", "gamma-secret");
        writeProfile("zbroken", "broken-app", "broken-secret");

        AppConfig defaultConfig = defaultConfig();
        AppContext rootContext = detachedContext();
        CapturingFactory factory = new CapturingFactory(rootContext, "zbroken");
        DefaultGatewayService defaultGateway = defaultGateway(defaultConfig);
        ProfileMultiplexRuntimeManager manager =
                new ProfileMultiplexRuntimeManager(
                        defaultConfig, defaultGateway, Collections.emptyMap(), factory);

        GatewayRuntimeStatusService status =
                new GatewayRuntimeStatusService(defaultConfig, "default");
        manager.bindRuntimeStatusService(status);
        manager.start();

        assertThat(manager.servedProfiles()).containsExactly("default", "alpha", "beta", "gamma");
        assertThat(manager.failures()).containsKey("zbroken");
        assertThat(factory.configs.get("alpha").getChannels().getFeishu().isEnabled()).isTrue();
        assertThat(factory.configs.get("beta").getChannels().getFeishu().isEnabled()).isFalse();
        assertThat(factory.configs.get("gamma").getChannels().getFeishu().isEnabled()).isTrue();
        assertThat(manager.credentialConflicts().get("beta"))
                .containsEntry(PlatformType.FEISHU, "alpha");
        assertThat(manager.credentialConflicts()).doesNotContainKey("gamma");
        assertThat(status.readState().get("served_profiles")).isEqualTo(manager.servedProfiles());

        GatewayMessage message = new GatewayMessage(PlatformType.FEISHU, "chat", "user", "hello");
        message.setProfile("alpha");
        GatewayReply reply = defaultGateway.handle(message);
        assertThat(reply.getContent()).isEqualTo("alpha");
        assertThat(message.getProfile()).isEqualTo("alpha");

        GatewayMessage defaultMessage =
                new GatewayMessage(PlatformType.FEISHU, "chat", "user", "hello");
        defaultMessage.setProfile("default");
        GatewayMessage betaMessage =
                new GatewayMessage(PlatformType.FEISHU, "chat", "user", "hello");
        betaMessage.setProfile("beta");
        assertThat(defaultMessage.sourceKey()).isEqualTo("FEISHU:chat:user");
        assertThat(message.sourceKey()).isEqualTo("profile:alpha:FEISHU:chat:user");
        assertThat(betaMessage.sourceKey()).isEqualTo("profile:beta:FEISHU:chat:user");

        defaultConfig.getGateway().setMultiplexProfiles(false);
        manager.reload();
        assertThat(status.readState()).doesNotContainKey("served_profiles");
        manager.close();
    }

    /** 未调用批量 start 时保持 default-only，显式请求只装配目标 Profile。 */
    @Test
    void lazilyStartsOnlyExplicitlyRequestedRuntime() throws Exception {
        writeProfile("alpha", "alpha-app", "alpha-secret");
        writeProfile("beta", "beta-app", "beta-secret");
        AppConfig defaultConfig = defaultConfig();
        CapturingFactory factory = new CapturingFactory(detachedContext(), "never");
        ProfileMultiplexRuntimeManager manager =
                new ProfileMultiplexRuntimeManager(
                        defaultConfig,
                        defaultGateway(defaultConfig),
                        Collections.emptyMap(),
                        factory);

        assertThat(manager.servedProfiles()).containsExactly("default");
        assertThat(manager.findRuntime("alpha")).isNull();

        ProfileRuntimeBundle alpha = manager.requireRuntime("alpha");

        assertThat(alpha).isNotNull();
        assertThat(manager.findRuntime("alpha")).isSameAs(alpha);
        assertThat(manager.findRuntime("beta")).isNull();
        assertThat(manager.servedProfiles()).containsExactly("default", "alpha");
        assertThat(factory.configs).containsOnlyKeys("alpha");
        manager.close();
    }

    /** 删除 Profile 前可先从 multiplexer 关闭并移除对应子运行时。 */
    @Test
    void releasesNamedRuntimeBeforeProfileDeletion() throws Exception {
        writeProfile("alpha", "alpha-app", "alpha-secret");
        AppConfig defaultConfig = defaultConfig();
        ProfileMultiplexRuntimeManager manager =
                new ProfileMultiplexRuntimeManager(
                        defaultConfig,
                        defaultGateway(defaultConfig),
                        Collections.emptyMap(),
                        new CapturingFactory(detachedContext(), "never"));
        ProfileRuntimeBundle alpha = manager.requireRuntime("alpha");

        manager.releaseRuntime("alpha");

        assertThat(manager.findRuntime("alpha")).isNull();
        assertThat(manager.servedProfiles()).containsExactly("default");
        assertThatThrownBy(
                        () ->
                                alpha.handle(
                                        new GatewayMessage(
                                                PlatformType.FEISHU,
                                                "chat",
                                                "user",
                                                "hello")))
                .hasMessageContaining("already closed");
        manager.close();
    }

    /** 命名 Profile 只读取自己的 .env，default 环境与另一个 Profile 的安全开关不得泄漏。 */
    @Test
    void loadsIndependentProfileEnvironmentAndForcesChildMultiplexOff() throws Exception {
        Files.writeString(root.resolve(".env"), "SOLONCLAW_ALLOW_PRIVATE_URLS=true\n");
        writeProfile("alpha", "alpha-app", "alpha-secret", "SOLONCLAW_ALLOW_PRIVATE_URLS=true\n");
        writeProfile("beta", "beta-app", "beta-secret", "");
        AppConfig defaultConfig = defaultConfig();
        CapturingFactory factory = new CapturingFactory(detachedContext(), "never");
        ProfileMultiplexRuntimeManager manager =
                new ProfileMultiplexRuntimeManager(
                        defaultConfig,
                        defaultGateway(defaultConfig),
                        Collections.emptyMap(),
                        factory);

        manager.start();

        assertThat(factory.configs.get("alpha").getSecurity().isAllowPrivateUrls()).isTrue();
        assertThat(factory.configs.get("beta").getSecurity().isAllowPrivateUrls()).isFalse();
        assertThat(factory.configs.get("alpha").getGateway().isMultiplexProfiles()).isFalse();
        assertThat(factory.configs.get("beta").getGateway().isMultiplexProfiles()).isFalse();
        manager.close();
    }

    /** secondary Profile 的未确认 open 策略属于配置错误，必须终止复用启动并清理先前子运行时。 */
    @Test
    void failsFastAndCleansStartedRuntimesForUnsafeOpenPolicy() throws Exception {
        writeProfile("alpha", "alpha-app", "alpha-secret");
        writeOpenWecomProfile("beta");
        AppConfig defaultConfig = defaultConfig();
        CapturingFactory factory = new CapturingFactory(detachedContext(), "never");
        ProfileMultiplexRuntimeManager manager =
                new ProfileMultiplexRuntimeManager(
                        defaultConfig,
                        defaultGateway(defaultConfig),
                        Collections.emptyMap(),
                        factory);

        assertThatThrownBy(manager::start)
                .isInstanceOf(
                        com.jimuqu.solon.claw.gateway.authorization.GatewayOpenPolicyStartupGuard
                                .ViolationException.class)
                .hasMessageContaining("beta")
                .hasMessageContaining("wecom");
        assertThat(manager.servedProfiles()).containsExactly("default");
        assertThat(manager.runtimes()).isEmpty();
        assertThat(manager.failures()).containsKey("beta");
        manager.close();
    }

    /** 创建启用 multiplex 的 default 配置。 */
    private AppConfig defaultConfig() {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(root.toString());
        config.getRuntime().setStateDb(root.resolve("data/state.db").toString());
        config.getRuntime().setConfigFile(root.resolve("config.yml").toString());
        config.getWorkspace().setDir(root.toString());
        config.getGateway().setMultiplexProfiles(true);
        config.getDashboard().setBindPort(8080);
        return config;
    }

    /** 写入一个只启用飞书的命名 Profile 配置和局部凭据。 */
    private void writeProfile(String name, String appId, String appSecret) throws Exception {
        writeProfile(name, appId, appSecret, "");
    }

    /** 写入一个只启用飞书的命名 Profile 配置、局部凭据和可选环境开关。 */
    private void writeProfile(String name, String appId, String appSecret, String extraEnvironment)
            throws Exception {
        Path home = root.resolve("profiles").resolve(name);
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("config.yml"),
                "solonclaw:\n"
                        + "  gateway:\n"
                        + "    multiplexProfiles: true\n"
                        + "  scheduler:\n"
                        + "    enabled: false\n"
                        + "  proactive:\n"
                        + "    enabled: false\n"
                        + "  channels:\n"
                        + "    feishu:\n"
                        + "      enabled: true\n");
        Files.writeString(
                home.resolve(".env"),
                "SOLONCLAW_CHANNEL_FEISHU_APP_ID="
                        + appId
                        + "\nSOLONCLAW_CHANNEL_FEISHU_APP_SECRET="
                        + appSecret
                        + "\n"
                        + extraEnvironment);
    }

    /** 写入一个启用企微 open 私聊但未确认全量放行的 Profile。 */
    private void writeOpenWecomProfile(String name) throws Exception {
        Path home = root.resolve("profiles").resolve(name);
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("config.yml"),
                "solonclaw:\n"
                        + "  gateway:\n"
                        + "    multiplexProfiles: true\n"
                        + "  scheduler:\n"
                        + "    enabled: false\n"
                        + "  proactive:\n"
                        + "    enabled: false\n"
                        + "  channels:\n"
                        + "    wecom:\n"
                        + "      enabled: true\n"
                        + "      dmPolicy: open\n"
                        + "      groupPolicy: disabled\n"
                        + "      allowAllUsers: false\n");
        Files.writeString(
                home.resolve(".env"),
                "SOLONCLAW_CHANNEL_WECOM_BOT_ID=worker-bot\n"
                        + "SOLONCLAW_CHANNEL_WECOM_SECRET=worker-secret\n");
    }

    /** 创建不绑定 HTTP 或全局 Solon 状态的测试 Bean 容器。 */
    private AppContext detachedContext() {
        return new AppContext(null, Thread.currentThread().getContextClassLoader(), new Props());
    }

    /** 创建只用于路由测试的网关服务。 */
    private DefaultGatewayService gateway(final AppConfig config) {
        return new DefaultGatewayService(
                null, null, null, null, null, null, null, Collections.emptyMap(), config) {
            /** 返回当前作用域 Profile，验证 manager.route 进入完整子运行时上下文。 */
            @Override
            public GatewayReply handle(GatewayMessage message) {
                ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
                return GatewayReply.ok(current == null ? "missing" : current.getProfile());
            }
        };
    }

    /** 创建保留真实 Profile 路由逻辑的 default 网关。 */
    private DefaultGatewayService defaultGateway(AppConfig config) {
        return new DefaultGatewayService(
                null, null, null, null, null, null, null, Collections.emptyMap(), config);
    }

    /** 捕获已解析 Profile 配置，并可按名称模拟子容器启动失败。 */
    private final class CapturingFactory extends ProfileRuntimeBundleFactory {
        /** 每个 Profile 传入工厂的独立配置。 */
        private final Map<String, AppConfig> configs = new LinkedHashMap<String, AppConfig>();

        /** 需要模拟启动失败的 Profile。 */
        private final String failingProfile;

        /** 创建可控测试工厂。 */
        private CapturingFactory(AppContext rootContext, String failingProfile) {
            super(rootContext);
            this.failingProfile = failingProfile;
        }

        /** 捕获配置并返回轻量子运行时。 */
        @Override
        public ProfileRuntimeBundle create(
                String profile, Path home, Map<String, String> environment, AppConfig appConfig) {
            configs.put(profile, appConfig);
            if (profile.equals(failingProfile)) {
                throw new IllegalStateException("simulated startup failure");
            }
            AppContext context = detachedContext();
            return new ProfileRuntimeBundle(
                    profile, home, environment, appConfig, context, gateway(appConfig));
        }
    }
}
