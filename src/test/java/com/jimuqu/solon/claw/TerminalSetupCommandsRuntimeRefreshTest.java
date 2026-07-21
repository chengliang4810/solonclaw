package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.tui.TerminalSetupCommands;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

/** 验证 TUI 本地配置命令写入后，同一长进程能立即读取新的运行时状态。 */
class TerminalSetupCommandsRuntimeRefreshTest {
    @Test
    void authAddActivateRefreshesCurrentProcessProviderState() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-auth-refresh").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    name: 启动 Provider\n"
                        + "    baseUrl: https://startup.example.com\n"
                        + "    apiKey: startup-key\n"
                        + "    defaultModel: startup-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: startup-model\n",
                new File(workspaceHome, "config.yml"));

        TerminalSetupCommands commands = commands(workspaceHome);

        String written =
                commands.render(
                        "auth add backup --api-key Sk-Test-AuthRefreshSecret123 "
                                + "--base-url https://backup.example.com/v1 --model backup-model "
                                + "--dialect openai --activate");
        String authStatus = commands.render("auth status backup");
        String modelStatus = commands.render("model");

        assertThat(written)
                .contains("provider=backup")
                .doesNotContain("Sk-Test-AuthRefreshSecret123");
        assertThat(authStatus)
                .contains("provider=backup")
                .contains("api_key=configured")
                .contains("model=backup-model")
                .contains("dialect=openai");
        assertThat(modelStatus)
                .contains("active.provider=backup")
                .contains("active.model=backup-model")
                .contains("dialect=openai")
                .contains("api_url=https://backup.example.com/v1/chat/completions")
                .contains("api_key=configured");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("models:")
                .contains("- backup-model");
    }

    /** 未指定 --activate 时只登记 Provider，不得改动当前 Profile 的默认模型。 */
    @Test
    void authAddWithoutActivateKeepsCurrentDefaultModel() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-auth-register").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    baseUrl: https://primary.example.com/v1\n"
                        + "    apiKey: primary-key\n"
                        + "    defaultModel: primary-model\n"
                        + "    models: [primary-model]\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: primary-model\n",
                configFile);
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        TerminalSetupCommands commands = new TerminalSetupCommands(config, null);

        String result =
                commands.render(
                        "auth add backup --api-key backup-key "
                                + "--base-url https://backup.example.com/v1 --model backup-model "
                                + "--dialect openai");

        assertThat(result).contains("provider=backup");
        assertThat(config.getProviders()).containsKey("backup");
        assertThat(config.getModel().getProviderKey()).isEqualTo("default");
        assertThat(config.getModel().getDefault()).isEqualTo("primary-model");
        assertThat(FileUtil.readUtf8String(configFile))
                .contains("backup:")
                .contains("defaultModel: backup-model")
                .contains("providerKey: default")
                .contains("default: primary-model");
    }

    /** auth logout 必须同时清除 YAML 和当前进程中的 Provider 凭据。 */
    @Test
    void authLogoutClearsPersistedAndRuntimeCredential() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-auth-logout").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    baseUrl: https://primary.example.com/v1\n"
                        + "    apiKey: primary-secret\n"
                        + "    defaultModel: primary-model\n"
                        + "    models: [primary-model]\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: primary-model\n",
                configFile);
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        TerminalSetupCommands commands = new TerminalSetupCommands(config, null);

        String result = commands.render("auth logout default");

        assertThat(result).contains("provider=default").contains("api_key=missing");
        assertThat(config.getProviders().get("default").getApiKey()).isEmpty();
        assertThat(config.getLlm().getApiKey()).isEmpty();
        assertThat(FileUtil.readUtf8String(configFile))
                .doesNotContain("primary-secret")
                .doesNotContain("apiKey:");
        assertThat(commands.render("auth status default")).contains("api_key=missing");
    }

    @Test
    void configSetRejectsModelAndProviderKeys() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-config-set-guard").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    name: 启动 Provider\n"
                        + "    baseUrl: https://startup.example.com\n"
                        + "    apiKey: startup-key\n"
                        + "    defaultModel: startup-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: startup-model\n",
                configFile);

        TerminalSetupCommands commands = commands(workspaceHome);

        String rejectedProvider = commands.render("config set model.providerKey backup");
        String rejectedSecret =
                commands.render("config set providers.default.apiKey runtime-secret");

        assertThat(rejectedProvider)
                .contains("模型与 provider 配置请使用专用入口")
                .contains("solonclaw model set");
        assertThat(rejectedSecret)
                .contains("模型与 provider 配置请使用专用入口")
                .contains("solonclaw auth add");
        assertThat(FileUtil.readUtf8String(configFile))
                .contains("providerKey: default")
                .contains("startup-model");
    }

    @Test
    void fallbackCommandsRefreshCurrentProcessFallbackChain() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-fallback-refresh").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    name: 主 Provider\n"
                        + "    baseUrl: https://primary.example.com/v1\n"
                        + "    apiKey: primary-key\n"
                        + "    defaultModel: primary-model\n"
                        + "    dialect: openai\n"
                        + "  backup:\n"
                        + "    name: 备用 Provider\n"
                        + "    baseUrl: https://backup.example.com/v1\n"
                        + "    apiKey: backup-key\n"
                        + "    defaultModel: backup-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: primary-model\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        TerminalSetupCommands commands = new TerminalSetupCommands(config, null);
        LlmProviderService providerService = new LlmProviderService(config);

        String before = FileUtil.readUtf8String(new File(workspaceHome, "config.yml"));
        String rejected = commands.render("fallback add --provider backup --model missing-model");
        assertThat(rejected).contains("模型未加入 provider backup 的模型列表").contains("missing-model");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .isEqualTo(before);

        String added = commands.render("fallback add --provider backup --model backup-model");
        String listed = commands.render("fallback list");

        assertThat(added).contains("chain_size=1");
        assertThat(listed).contains("1. backup / backup-model");
        assertThat(providerService.resolveFallbackProviders())
                .hasSize(1)
                .first()
                .extracting(LlmProviderService.ResolvedProvider::getProviderKey)
                .isEqualTo("backup");

        String removed = commands.render("fallback remove --provider backup --model backup-model");

        assertThat(removed).contains("chain_size=0");
        assertThat(providerService.resolveFallbackProviders()).isEmpty();
    }

    /** fallback 不得把仅存在于初始化模板、尚未写入配置的 Provider 当成可用目标。 */
    @Test
    void fallbackAddRejectsUnconfiguredProviderTemplateWithoutWritingConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-fallback-template").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    baseUrl: https://primary.example.com/v1\n"
                        + "    apiKey: primary-key\n"
                        + "    defaultModel: primary-model\n"
                        + "    models: [primary-model]\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: primary-model\n",
                configFile);
        TerminalSetupCommands commands = commands(workspaceHome);
        String before = FileUtil.readUtf8String(configFile);

        String result =
                commands.render("fallback add --provider anthropic --model claude-sonnet-4-5");

        assertThat(result)
                .contains("未知 provider：anthropic")
                .contains("solonclaw auth add anthropic");
        assertThat(FileUtil.readUtf8String(configFile)).isEqualTo(before);
    }

    /** 构造绑定临时工作区的终端命令服务。 */
    private TerminalSetupCommands commands(File workspaceHome) {
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        return new TerminalSetupCommands(config, null);
    }
}
