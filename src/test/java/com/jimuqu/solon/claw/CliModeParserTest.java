package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import org.junit.jupiter.api.Test;

/** 覆盖顶层终端管理命令到本地 slash 命令的映射，避免启动成服务端模式。 */
class CliModeParserTest {
    @Test
    void shouldMapSetupCommandsToLocalCliSlashCommands() {
        assertLocalCli(new String[] {"model"}, "/setup model");
        assertLocalCli(new String[] {"setup", "model"}, "/setup model");
        assertLocalCli(new String[] {"setup", "gateway"}, "/setup gateway");
        assertLocalCli(new String[] {"gateway", "setup"}, "/setup gateway");
        assertLocalCli(new String[] {"config", "path"}, "/config path");
        assertLocalCli(new String[] {"config", "check"}, "/config check");
    }

    @Test
    void shouldKeepExplicitCliAndTuiPromptModes() {
        CliMode cli = CliModeParser.parse(new String[] {"--cli", "-p", "/setup", "model"});
        assertThat(cli.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(cli.getInput()).isEqualTo("/setup model");

        CliMode tui = CliModeParser.parse(new String[] {"--tui", "-p", "/setup", "gateway"});
        assertThat(tui.getKind()).isEqualTo(CliMode.Kind.TUI);
        assertThat(tui.getInput()).isEqualTo("/setup gateway");
    }

    private static void assertLocalCli(String[] args, String input) {
        CliMode mode = CliModeParser.parse(args);
        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.getInput()).isEqualTo(input);
    }
}
