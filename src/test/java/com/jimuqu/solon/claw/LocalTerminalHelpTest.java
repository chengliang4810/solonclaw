package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.LocalTerminalHelp;
import org.junit.jupiter.api.Test;

public class LocalTerminalHelpTest {
    @Test
    void shouldRecognizeLocalHelpCommands() {
        assertThat(LocalTerminalHelp.isHelp("/help")).isTrue();
        assertThat(LocalTerminalHelp.isHelp(" /? ")).isTrue();
        assertThat(LocalTerminalHelp.isHelp("/copy")).isFalse();
    }

    @Test
    void shouldDescribeLocalTerminalCommands() {
        assertThat(LocalTerminalHelp.text())
                .contains("/copy")
                .contains("/models")
                .contains("/model pick")
                .contains("/sessions")
                .contains("/session pick")
                .contains("/history")
                .contains("/title")
                .contains("/events")
                .contains("/tasks")
                .contains("/attachments")
                .contains("/tips")
                .contains("/queue")
                .contains("/steer")
                .contains("/skin")
                .contains("Ctrl-G")
                .contains("/exit")
                .contains("/quit")
                .contains("粘贴本地文件路径")
                .contains("/busy");
    }
}
