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
                "/busy status", "/busy queue", "/busy steer", "/busy interrupt", "/busy reject",
                "/cron guide", "/cron capabilities", "/cron upcoming", "/cron trigger",
                "/kanban guide", "/kanban drawer", "/kanban pipeline", "/kanban retry",
                "/kanban history", "/kanban dispatch",
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
