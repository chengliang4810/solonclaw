package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliMode;
import com.jimuqu.solon.claw.cli.CliModeParser;
import org.junit.jupiter.api.Test;

public class CliModeParserTest {
    @Test
    void shouldDefaultToServerMode() {
        CliMode mode = CliModeParser.parse(new String[0]);

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.SERVER);
        assertThat(mode.isConsoleMode()).isFalse();
    }

    @Test
    void shouldParseCliPromptAndSession() {
        CliMode mode =
                CliModeParser.parse(
                        new String[] {"--cli", "--session", "work", "-p", "/status"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.CLI);
        assertThat(mode.getSessionId()).isEqualTo("work");
        assertThat(mode.getInput()).isEqualTo("/status");
    }

    @Test
    void shouldParseTuiAskRestAsInput() {
        CliMode mode =
                CliModeParser.parse(
                        new String[] {"tui", "--session=alpha", "--ask", "hello", "world"});

        assertThat(mode.getKind()).isEqualTo(CliMode.Kind.TUI);
        assertThat(mode.getSessionId()).isEqualTo("alpha");
        assertThat(mode.getInput()).isEqualTo("hello world");
    }
}
