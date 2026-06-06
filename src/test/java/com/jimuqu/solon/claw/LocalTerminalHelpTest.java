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
                .contains("/setup [model|gateway]")
                .contains("/setup --quick|--reset|--non-interactive")
                .contains("/setup terminal|tools|agent|tts")
                .contains("/setup gateway <channel> --enabled true")
                .contains("/config path|env-path|show|edit|check|migrate|set <key> <value>")
                .contains("/doctor [--fix|--ack <id>]")
                .contains("/status - 查看本地运行、模型和渠道状态")
                .contains("/version - 查看当前版本和部署形态")
                .contains("/logout - 清理本地登录态")
                .contains("/gateway status|list|run|start|stop|restart")
                .contains("/gateway install|uninstall|migrate-legacy")
                .contains("/pairing list|pending|approved|approve|revoke|clear-pending")
                .contains("/fallback list|add|remove|clear")
                .contains("/postinstall、/login、/auth、/fallback、/secrets、/proxy、/mcp、/migrate、/send")
                .contains("/mcp list|add|test")
                .contains("/model set --provider <key>")
                .contains("/hooks、/dump、/backup、/checkpoints、/import、/bundles、/memory、/dashboard、/logs、/prompt-size")
                .contains("/models")
                .contains("/model pick")
                .contains("/sessions")
                .contains("/session pick")
                .contains("/sessions stats|export|delete|prune|rename")
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
                .contains("/cron guide|capabilities|policy [--json]")
                .contains(
                        "/security audit|status|policy|audit-tool|approvals|slash-confirm|approval-card|approval-audit|mcp-reload|lifecycle|hardline|terminal-guardrails|tirith|tirith-approval|cron-approvals|subagent-approvals|smart-approval|urls|private-urls|website|paths|credentials|skill-credentials|tool-args|mcp|mcp-oauth|mcp-package|schema|attachments|terminal-paste|media-cache|tool-results|patch|code-execution|subprocess-env|terminal-output|sudo|process")
                .contains("/insights")
                .contains("/plugins")
                .contains("/update")
                .contains("/reload-skills")
                .contains("/approve [确认编号|all] [session|always]")
                .contains("/deny [确认编号]")
                .contains("/approve list|status|clear session|clear always|clear all")
                .contains("/deny list|status|all")
                .contains("/confirm")
                .contains("/yolo [status|on|off]")
                .contains("/skin")
                .contains("Ctrl-G")
                .contains("Ctrl-T")
                .contains("/exit")
                .contains("/exit!")
                .contains("/quit")
                .contains("/quit!")
                .contains("粘贴本地文件路径")
                .contains("/busy");
    }
}
