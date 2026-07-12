package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 Dashboard 插件开关仅持久化到对应 Profile，且不会在当前 JVM 热重载插件。 */
public class DashboardPluginConfigTest {
    /** 当前 Profile 的开关写入保持运行中插件配置不变，并支持反向切换。 */
    @Test
    void shouldPersistCurrentPluginStateForNextSessionOrRestartOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardRuntimeConfigService service =
                new DashboardRuntimeConfigService(env.appConfig, env.gatewayRuntimeRefreshService);

        Map<String, Object> enabled = service.setPluginEnabled("browser-use", true, null);

        assertThat(enabled)
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("plugin", "browser-use")
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("effective_after", "next_session_or_restart");
        assertThat(env.appConfig.getPlugins().getEnabled()).isEmpty();
        assertThat(env.appConfig.getPlugins().getDisabled()).isEmpty();
        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.open(env.appConfig.getRuntime().getHome());
        assertThat(stringList(resolver.getRaw("solonclaw.plugins.enabled")))
                .containsExactly("browser-use");
        assertThat(stringList(resolver.getRaw("solonclaw.plugins.disabled"))).isEmpty();

        Map<String, Object> disabled = service.setPluginEnabled("browser-use", false, null);

        assertThat(disabled)
                .containsEntry("enabled", Boolean.FALSE)
                .containsEntry("effective_after", "next_session_or_restart");
        assertThat(env.appConfig.getPlugins().getEnabled()).isEmpty();
        assertThat(env.appConfig.getPlugins().getDisabled()).isEmpty();
        assertThat(stringList(resolver.getRaw("solonclaw.plugins.enabled"))).isEmpty();
        assertThat(stringList(resolver.getRaw("solonclaw.plugins.disabled")))
                .containsExactly("browser-use");
    }

    /** 指定非当前 Profile 时只能写目标配置文件，不能刷新当前 JVM 配置。 */
    @Test
    void shouldPersistPluginStateInTargetProfileWithoutChangingCurrentProfile() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path root = Paths.get(env.appConfig.getRuntime().getHome());
        Path workerHome = Files.createDirectories(root.resolve("profiles").resolve("worker"));
        ProfileManager profileManager =
                new ProfileManager(
                        root, Files.createTempDirectory("dashboard-plugin-wrappers-"), "solonclaw");
        DashboardRuntimeConfigService service =
                new DashboardRuntimeConfigService(
                        env.appConfig,
                        env.gatewayRuntimeRefreshService,
                        new DashboardProfileContext(profileManager, env.appConfig));

        Map<String, Object> result = service.setPluginEnabled("worker-plugin", true, "worker");

        assertThat(result)
                .containsEntry("plugin", "worker-plugin")
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("effective_after", "next_session_or_restart");
        assertThat(
                        stringList(
                                RuntimeConfigResolver.open(workerHome.toString())
                                        .getRaw("solonclaw.plugins.enabled")))
                .containsExactly("worker-plugin");
        assertThat(
                        RuntimeConfigResolver.open(env.appConfig.getRuntime().getHome())
                                .getRaw("solonclaw.plugins.enabled"))
                .isNull();
        assertThat(env.appConfig.getPlugins().getEnabled()).isEmpty();
        assertThat(env.appConfig.getPlugins().getDisabled()).isEmpty();
    }

    /** 非法插件名必须在写入配置前被拒绝，避免污染 YAML 结构。 */
    @Test
    void shouldRejectInvalidPluginName() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardRuntimeConfigService service =
                new DashboardRuntimeConfigService(env.appConfig, env.gatewayRuntimeRefreshService);

        assertThatThrownBy(() -> service.setPluginEnabled("bad:name", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("插件名称");
    }

    /** 将运行时配置读取出的 YAML 列表转换为测试断言用字符串列表。 */
    private List<String> stringList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) value;
        return values;
    }
}
