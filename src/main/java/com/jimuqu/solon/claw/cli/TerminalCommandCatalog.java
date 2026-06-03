package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared slash command catalog for CLI, TUI, and generated shell completion. */
public final class TerminalCommandCatalog {
    private static final String[] LEGACY_SUBCOMMANDS =
            new String[] {
                "/model pick", "/skin classic", "/skin mono", "/skin contrast",
                "/busy status", "/busy queue", "/busy steer", "/busy interrupt", "/busy reject",
                "/approve list", "/approve status", "/approve all", "/approve all session",
                "/approve all always", "/approve session", "/approve always",
                "/approve clear session", "/approve clear always", "/approve clear all",
                "/deny list", "/deny status", "/deny all",
                "/security audit", "/security status", "/security policy", "/security audit-tool", "/security approvals", "/security urls",
                "/security private-urls", "/security website", "/security slash-confirm",
                "/security approval-card", "/security approval-audit", "/security mcp-reload",
                "/security lifecycle", "/security hardline", "/security terminal-guardrails", "/security tirith",
                "/security tirith-approval", "/security cron-approvals",
                "/security subagent-approvals", "/security smart-approval",
                "/security paths", "/security credentials", "/security tool-args",
                "/security skill-credentials",
                "/security mcp", "/security mcp-oauth", "/security mcp-package",
                "/security schema", "/security attachments",
                "/security terminal-paste", "/security media-cache", "/security tool-results",
                "/security patch", "/security code-execution", "/security subprocess-env",
                "/security terminal-output", "/security sudo", "/security process",
                "/cron list", "/cron status", "/cron next", "/cron upcoming", "/cron guide",
                "/cron tutorial", "/cron capabilities", "/cron policy", "/cron add", "/cron edit", "/cron pause",
                "/cron disable", "/cron stop", "/cron resume", "/cron enable", "/cron start",
                "/cron remove", "/cron delete", "/cron run", "/cron trigger", "/cron retry",
                "/cron rerun", "/cron history", "/cron inspect", "/cron show", "/cron tick",
                "/reload-mcp now", "/reload-mcp always",
                "/models", "/sessions", "/session", "/history", "/events",
                "/attachments", "/transcript", "/tips", "/skin", "/exit", "/quit",
                "/exit!", "/quit!"
            };

    public static final String[] SLASH_COMMANDS = buildSlashCommands();

    private static final List<String> SLASH_COMMAND_LIST =
            Collections.unmodifiableList(asList(SLASH_COMMANDS));

    private TerminalCommandCatalog() {}

    public static List<String> slashCommands() {
        return SLASH_COMMAND_LIST;
    }

    private static String[] buildSlashCommands() {
        List<String> commands = new ArrayList<String>(CommandRegistry.slashCommands());
        for (String command : LEGACY_SUBCOMMANDS) {
            if (!commands.contains(command)) {
                commands.add(command);
            }
        }
        return commands.toArray(new String[commands.size()]);
    }

    private static List<String> asList(String[] commands) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, commands);
        return list;
    }
}
