package com.jimuqu.solon.claw.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;

/** 验证 Profile 复用运行图只在服务端生命周期中批量启动。 */
class GatewayConfigurationProfileMultiplexLifecycleTest {
    /** 临时 default Profile 根目录。 */
    @TempDir Path root;

    /** CLI/TUI Bean 初始化不得拉起全部命名 Profile。 */
    @Test
    void consoleModeDoesNotEagerlyStartNamedProfiles() throws Exception {
        Files.createDirectories(root.resolve("profiles/alpha"));
        AppConfig appConfig = new AppConfig();
        appConfig.getRuntime().setHome(root.toString());
        appConfig.getRuntime().setConfigFile(root.resolve("config.yml").toString());
        appConfig.getWorkspace().setDir(root.toString());
        appConfig.getGateway().setMultiplexProfiles(true);
        Map<PlatformType, ChannelAdapter> adapters = Collections.emptyMap();
        ChannelConnectionManager connections = new ChannelConnectionManager(adapters);
        GatewayRuntimeRefreshService refresh =
                new GatewayRuntimeRefreshService(appConfig, connections);
        DefaultGatewayService gateway =
                new DefaultGatewayService(
                        null, null, null, null, null, null, null, adapters, appConfig);
        AppContext context =
                new AppContext(null, Thread.currentThread().getContextClassLoader(), new Props());
        CliMode previous = StartupModeContext.get();
        StartupModeContext.set(new CliMode(CliMode.Kind.CLI, null, null));
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open("default", root, Collections.emptyMap(), context)) {
            ProfileMultiplexRuntimeManager manager =
                    new GatewayConfiguration()
                            .profileMultiplexRuntimeManager(appConfig, gateway, adapters, refresh);
            assertThat(manager.servedProfiles()).containsExactly("default");
            assertThat(manager.runtimes()).isEmpty();
            manager.close();
        } finally {
            StartupModeContext.set(previous);
            connections.shutdown();
        }
    }
}
