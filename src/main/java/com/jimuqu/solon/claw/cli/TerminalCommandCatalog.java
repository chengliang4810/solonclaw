package com.jimuqu.solon.claw.cli;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared slash command catalog for CLI, TUI, and generated shell completion. */
public final class TerminalCommandCatalog {
    public static final String[] SLASH_COMMANDS =
            new String[] {
                "/help", "/new", "/retry", "/undo", "/branch", "/resume", "/status", "/usage",
                "/title", "/goal", "/busy", "/model", "/reasoning", "/tools", "/skills", "/agent",
                "/cron", "/approve", "/kanban", "/deny", "/queue", "/steer", "/acp",
                "/model pick", "/skin classic", "/skin mono", "/skin contrast",
                "/busy status", "/busy queue", "/busy steer", "/busy interrupt", "/busy reject",
                "/approve list", "/approve status", "/approve all", "/approve all session",
                "/approve all always", "/approve session", "/approve always",
                "/approve clear session", "/approve clear always", "/approve clear all",
                "/deny list", "/deny status", "/deny all", "/always", "/cancel",
                "/cron list", "/cron status", "/cron next", "/cron upcoming", "/cron guide",
                "/cron tutorial", "/cron capabilities", "/cron add", "/cron edit", "/cron pause",
                "/cron disable", "/cron stop", "/cron resume", "/cron enable", "/cron start",
                "/cron remove", "/cron delete", "/cron run", "/cron trigger", "/cron retry",
                "/cron rerun", "/cron history", "/cron inspect", "/cron show", "/cron tick",
                "/kanban list", "/kanban create", "/kanban schema", "/kanban show",
                "/kanban drawer", "/kanban inspect", "/kanban boards", "/kanban move",
                "/kanban assign", "/kanban step", "/kanban pipeline", "/kanban link",
                "/kanban unlink", "/kanban retry", "/kanban reclaim", "/kanban reassign",
                "/kanban unblock", "/kanban edit", "/kanban assignees", "/kanban history",
                "/kanban runs", "/kanban events", "/kanban tail", "/kanban context",
                "/kanban diagnostics", "/kanban guide", "/kanban tutorial", "/kanban stats",
                "/kanban watch", "/kanban notify-subscribe", "/kanban notify-list",
                "/kanban notify-unsubscribe", "/kanban log", "/kanban gc", "/kanban claim",
                "/kanban next", "/kanban heartbeat", "/kanban release-stale",
                "/kanban reclaim-timeouts", "/kanban dispatch", "/kanban daemon",
                "/kanban comment", "/kanban block", "/kanban done", "/kanban archive",
                "/restart", "/stop", "/compact", "/compress", "/rollback", "/recap",
                "/trajectory", "/confirm", "/yolo", "/version", "/platforms",
                "/models", "/sessions", "/session", "/history", "/events", "/tasks",
                "/attachments", "/transcript", "/tips", "/skin", "/copy", "/exit", "/quit",
                "/exit!", "/quit!"
            };

    private static final List<String> SLASH_COMMAND_LIST =
            Collections.unmodifiableList(Arrays.asList(SLASH_COMMANDS));

    private TerminalCommandCatalog() {}

    public static List<String> slashCommands() {
        return SLASH_COMMAND_LIST;
    }
}
