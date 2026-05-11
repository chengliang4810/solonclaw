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
                .contains("/goal [status|pause|resume|clear|目标 --max N]")
                .contains("/recap [条数]")
                .contains("/trajectory [save] [原始问题]")
                .contains("/compact [重点]")
                .contains("/events")
                .contains("/tasks")
                .contains("/transcript")
                .contains("/attachments")
                .contains("/tips")
                .contains("/queue")
                .contains("/steer")
                .contains("/busy [status|queue|steer|interrupt|reject]")
                .contains("/cron guide|capabilities [--json]")
                .contains("/kanban guide|drawer|pipeline|retry|history|dispatch")
                .contains("/security audit|policy|approvals|urls")
                .contains("/approve [确认编号|all] [session|always]")
                .contains("/deny [确认编号]")
                .contains("/approve list|status|clear session|clear always|clear all")
                .contains("/deny list|status|all")
                .contains("/confirm")
                .contains("/yolo [status|on|off]")
                .contains("/skin")
                .contains("Ctrl-G")
                .contains("/exit")
                .contains("/exit!")
                .contains("/quit")
                .contains("/quit!")
                .contains("粘贴本地文件路径")
                .contains("/busy");
    }
}
