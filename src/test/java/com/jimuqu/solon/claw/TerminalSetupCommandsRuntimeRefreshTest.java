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

    /** 构造绑定临时工作区的终端命令服务。 */
    private TerminalSetupCommands commands(File workspaceHome) {
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        return new TerminalSetupCommands(config, null);
    }
}
