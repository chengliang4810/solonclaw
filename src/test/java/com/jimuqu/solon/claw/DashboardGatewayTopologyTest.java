package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.DashboardDiagnosticTestSupport.FixedDeliveryService;
import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeStatusService;
import com.jimuqu.solon.claw.profile.ProfileCreateOptions;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.support.FixedSessionRepository;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Dashboard 对外报告真实 Profile 网关拓扑。 */
class DashboardGatewayTopologyTest {
    /** 临时机器级 Profile 根目录。 */
    @TempDir Path tempDir;

    /** default 状态声明多个 served_profiles 时，拓扑为 multiplex 且只公开一个网关。 */
    @Test
    @SuppressWarnings("unchecked")
    void reportsMultiplexTopologyFromRunningDefaultGateway() throws Exception {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        ProfileManager manager = new ProfileManager(root, tempDir.resolve("bin"), "solonclaw");
        manager.createProfile(
                "coder", new ProfileCreateOptions().setNoAlias(true).setNoSkills(true));
        AppConfig config = config(root);
        GatewayRuntimeStatusService runtime = new GatewayRuntimeStatusService(config, "default");
        runtime.setServedProfiles(Arrays.asList("default", "coder"));
        runtime.writePidFile();
        runtime.writeState("running", "ready");

        DashboardStatusService service = service(config, manager);
        Map<String, Object> status = service.getStatus(true);

        assertThat(status.get("profiles")).isEqualTo(Arrays.asList("default", "coder"));
        assertThat(status.get("gateway_mode")).isEqualTo("multiplex");
        List<Map<String, Object>> gateways = (List<Map<String, Object>>) status.get("gateways");
        assertThat(gateways).hasSize(1);
        assertThat(gateways.get(0))
                .containsEntry("profile", "default")
                .containsEntry("served_profiles", Arrays.asList("default", "coder"));
    }

    /** 创建状态聚合依赖。 */
    private DashboardStatusService service(AppConfig config, ProfileManager manager) {
        AppVersionService version = new AppVersionService(config);
        AppUpdateService update =
                new AppUpdateService(config, version) {
                    /** 固定本地版本状态，避免测试访问网络。 */
                    @Override
                    public VersionStatus getVersionStatus(boolean forceRefresh) {
                        VersionStatus status = new VersionStatus();
                        status.setCurrentVersion("0.0.0-test");
                        status.setCurrentTag("v0.0.0-test");
                        status.setDeploymentMode("test");
                        return status;
                    }
                };
        return new DashboardStatusService(
                config,
                new FixedSessionRepository(),
                FixedDeliveryService.empty(),
                null,
                new GatewayRuntimeRefreshService(
                        config, new ChannelConnectionManager(Collections.emptyMap())),
                version,
                update,
                new LlmProviderService(config),
                null,
                null,
                Collections.emptyList(),
                new DashboardProfileContext(manager, config));
    }

    /** 创建 default Profile 配置。 */
    private AppConfig config(Path root) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(root.toString());
        config.getRuntime().setConfigFile(root.resolve("config.yml").toString());
        config.getRuntime().setContextDir(root.resolve("context").toString());
        config.getRuntime().setSkillsDir(root.resolve("skills").toString());
        config.getRuntime().setCacheDir(root.resolve("cache").toString());
        config.getRuntime().setLogsDir(root.resolve("logs").toString());
        config.getRuntime().setStateDb(root.resolve("data/state.db").toString());
        config.getWorkspace().setDir(root.toString());
        config.getDashboard().setBindPort(8080);
        return config;
    }
}
