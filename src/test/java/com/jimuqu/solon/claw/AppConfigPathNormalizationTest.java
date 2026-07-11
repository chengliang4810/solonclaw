package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class AppConfigPathNormalizationTest {
    @Test
    void shouldResolveRuntimePathsFromWorkspaceOnly() {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome("legacy-runtime");
        config.getWorkspace().setDir("workspace-live-onboarding");
        config.getRuntime().setContextDir("outside/context");
        config.getRuntime().setSkillsDir("outside/skills");
        config.getRuntime().setCacheDir("outside/cache");
        config.getRuntime().setStateDb("outside/state.db");
        config.getRuntime().setLogsDir("outside/logs");

        config.normalizePaths();

        String base = new File(System.getProperty("user.dir")).getAbsolutePath();
        File workspace = new File(base, "workspace-live-onboarding");
        assertThat(config.getWorkspace().getDir()).isEqualTo(workspace.getAbsolutePath());
        assertThat(config.getRuntime().getHome()).isEqualTo(workspace.getAbsolutePath());
        assertThat(config.getRuntime().getContextDir())
                .isEqualTo(new File(workspace, "context").getAbsolutePath());
        assertThat(config.getRuntime().getSkillsDir())
                .isEqualTo(new File(workspace, "skills").getAbsolutePath());
        assertThat(config.getRuntime().getCacheDir())
                .isEqualTo(new File(workspace, "cache").getAbsolutePath());
        assertThat(config.getRuntime().getStateDb())
                .isEqualTo(new File(new File(workspace, "data"), "state.db").getAbsolutePath());
        assertThat(config.getRuntime().getLogsDir())
                .isEqualTo(new File(workspace, "logs").getAbsolutePath());
    }

    @Test
    void shouldDeriveWorkspaceChildPathsFromWorkspaceOnly() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-workspace-home").toFile();
        File outside = Files.createTempDirectory("jimuqu-workspace-outside").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        props.put("solonclaw.logging.dir", new File(outside, "logs").getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getRuntime().getHome()).isEqualTo(workspaceHome.getAbsolutePath());
        assertThat(config.getRuntime().getContextDir())
                .isEqualTo(new File(workspaceHome, "context").getAbsolutePath());
        assertThat(config.getRuntime().getSkillsDir())
                .isEqualTo(new File(workspaceHome, "skills").getAbsolutePath());
        assertThat(config.getRuntime().getCacheDir())
                .isEqualTo(new File(workspaceHome, "cache").getAbsolutePath());
        assertThat(config.getRuntime().getStateDb())
                .isEqualTo(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        assertThat(config.getRuntime().getLogsDir())
                .isEqualTo(new File(workspaceHome, "logs").getAbsolutePath());
    }

    @Test
    void shouldUseWorkspaceForDerivedPaths() throws Exception {
        File outsideContext = Files.createTempDirectory("solonclaw-outside-context").toFile();
        File workspace = Files.createTempDirectory("solonclaw-workspace-home").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspace.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getRuntime().getHome()).isEqualTo(workspace.getAbsolutePath());
        assertThat(config.getRuntime().getContextDir())
                .isEqualTo(new File(workspace, "context").getAbsolutePath());
        assertThat(config.getRuntime().getStateDb())
                .isEqualTo(new File(new File(workspace, "data"), "state.db").getAbsolutePath());
        assertThat(config.getRuntime().getLogsDir())
                .isEqualTo(new File(workspace, "logs").getAbsolutePath());
        assertThat(new File(outsideContext, "config.yml")).doesNotExist();
    }

    @Test
    void shouldSyncConfigExampleIntoWorkspaceOnEveryLoad() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-workspace-example").toFile();
        File runtimeExample = new File(workspaceHome, "config.example.yml");
        FileUtil.writeUtf8String("stale content", runtimeExample);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig.load(props);

        assertThat(runtimeExample).exists();
        assertThat(FileUtil.readUtf8String(runtimeExample))
                .startsWith("# Agent 请注意：这是只读参考模板，请不要修改本文件；需要变更运行配置时请修改同目录的 config.yml。")
                .doesNotContain("stale content");
    }

    @Test
    void shouldExposeSecurityPolicyAtEffectiveTemplateScope() throws Exception {
        File workspaceHome =
                Files.createTempDirectory("solonclaw-workspace-security-example").toFile();
        File runtimeExample = new File(workspaceHome, "config.example.yml");

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig.load(props);

        String content = FileUtil.readUtf8String(runtimeExample).replace("\r\n", "\n");
        assertThat(content)
                .contains("\nsecurity:\n")
                .contains("  guardrailMode: approval")
                .contains("  allowPrivateUrls: false")
                .contains("  websiteBlocklist:")
                .doesNotContain("solonclaw:\n  security:");
    }

    @Test
    void shouldCreateMinimalWorkspaceConfigWhenMissing() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-workspace-init").toFile();
        File runtimeConfig = new File(workspaceHome, "config.yml");

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(runtimeConfig).exists();
        String content = FileUtil.readUtf8String(runtimeConfig);
        assertThat(content)
                .contains("baseUrl: \"https://api.openai.com\"")
                .contains("apiKey: \"\"")
                .contains("dialect: \"openai\"")
                .contains("accessToken: \"\"");
        assertThat(config.getDashboard().getAccessToken()).isEmpty();
        assertThat(config.getLlm().getDialect()).isEqualTo("openai");
        assertThat(config.getLlm().getApiUrl())
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(config.getLlm().getApiKey()).isEqualTo("");
        assertThat(config.getLlm().getModel()).isEqualTo("gpt-5.4");
    }

    /** 首次启动自动创建 config.yml 时，不能让模板空令牌覆盖命令行传入的 Dashboard 访问令牌。 */
    @Test
    void shouldKeepStartupDashboardTokenWhenWorkspaceConfigIsCreated() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-workspace-token").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        props.put("solonclaw.dashboard.accessToken", "startup-dashboard-token");

        AppConfig config = AppConfig.load(props);

        assertThat(new File(workspaceHome, "config.yml")).exists();
        assertThat(config.getDashboard().getAccessToken()).isEqualTo("startup-dashboard-token");
    }
}
