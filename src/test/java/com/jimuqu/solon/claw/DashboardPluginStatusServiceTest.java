package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.AgentHookRegistry;
import com.jimuqu.solon.claw.plugin.AgentPluginManager;
import com.jimuqu.solon.claw.plugin.CommandHandler;
import com.jimuqu.solon.claw.plugin.PlatformRegistration;
import com.jimuqu.solon.claw.plugin.PluginRegistrationSink;
import com.jimuqu.solon.claw.plugin.ToolRegistration;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import com.jimuqu.solon.claw.web.DashboardPluginStatusService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Dashboard 插件状态服务复用插件管理器的真实加载和诊断结果。 */
public class DashboardPluginStatusServiceTest {
    /** 临时插件根目录，用于模拟用户插件发现结果。 */
    @TempDir Path tempDir;

    /** 插件状态应包含加载清单、诊断计数，并避免泄露完整本地目录。 */
    @SuppressWarnings("unchecked")
    @Test
    void shouldExposeLoadedPluginsAndDiagnosticsWithoutRawLocalPaths() throws Exception {
        writePlugin(tempDir.resolve("visible-plugin"), "visible-plugin", true);
        writePlugin(tempDir.resolve("disabled-plugin"), "disabled-plugin", false);
        AgentPluginManager manager =
                new AgentPluginManager(
                        new AgentHookRegistry(),
                        Collections.singleton("visible-plugin"),
                        Collections.emptySet(),
                        tempDir);
        manager.discoverAndLoad(new NoopPluginRegistrationSink());

        Map<String, Object> status = new DashboardPluginStatusService(manager).status();

        assertThat(status)
                .containsEntry("loaded_count", Integer.valueOf(1))
                .containsEntry("skipped_count", Integer.valueOf(1))
                .containsEntry("failed_count", Integer.valueOf(0))
                .containsEntry("diagnostic_count", Integer.valueOf(2));
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) status.get("plugins");
        assertThat(plugins).hasSize(1);
        assertThat(plugins.get(0))
                .containsEntry("name", "visible-plugin")
                .containsEntry("kind", "backend")
                .containsEntry("source", "user")
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("auto_load", Boolean.TRUE);
        assertThat(String.valueOf(plugins.get(0).get("directory_ref")))
                .contains("visible-plugin")
                .doesNotContain(tempDir.toString());

        List<Map<String, Object>> diagnostics =
                (List<Map<String, Object>>) status.get("diagnostics");
        assertThat(diagnostics)
                .extracting(item -> item.get("status"))
                .containsExactlyInAnyOrder("loaded", "skipped");
    }

    /** 写入最小可加载插件。 */
    private void writePlugin(Path pluginDir, String pluginName, boolean enabled) throws Exception {
        Files.createDirectories(pluginDir);
        String className = pluginName.replace("-", "") + "Plugin";
        Files.write(
                pluginDir.resolve("plugin.yaml"),
                ("name: "
                        + pluginName
                        + "\nversion: 1.0.0\nkind: backend\ndescription: Dashboard plugin\n"
                        + "enabled: "
                        + enabled
                        + "\nentry: "
                        + className
                        + "\nprovidesTools:\n  - "
                        + pluginName
                        + "_tool\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                pluginDir.resolve(className + ".java"),
                ("import com.jimuqu.solon.claw.plugin.*;\n"
                        + "public class "
                        + className
                        + " implements AgentPlugin {\n"
                        + "  public void register(AgentPluginContext ctx) {\n"
                        + "    ctx.registerTool(new ToolRegistration(\""
                        + pluginName
                        + "_tool\", \"test\", java.util.Collections.emptyMap(), args -> \"ok\"));\n"
                        + "  }\n"
                        + "}\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** 忽略插件注册副作用，只让插件管理器产生加载状态。 */
    private static class NoopPluginRegistrationSink implements PluginRegistrationSink {
        @Override
        public void onToolRegistered(ToolRegistration registration) {}

        @Override
        public void onCommandRegistered(String name, CommandHandler handler, String description) {}

        @Override
        public void onWebSearchProviderRegistered(WebSearchProvider provider) {}

        @Override
        public void onImageGenProviderRegistered(ImageGenProvider provider) {}

        @Override
        public void onVideoGenProviderRegistered(VideoGenProvider provider) {}

        @Override
        public void onBrowserProviderRegistered(BrowserProvider provider) {}

        @Override
        public void onMemoryProviderRegistered(MemoryProvider provider) {}

        @Override
        public void onPlatformRegistered(PlatformRegistration registration) {}
    }
}
