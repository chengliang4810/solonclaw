package com.jimuqu.solon.claw.cli;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared slash command catalog for CLI, TUI, and generated shell completion. */
public final class TerminalCommandCatalog {
    public static final String[] SLASH_COMMANDS =
            new String[] {
                "/help", "/new", "/retry", "/undo", "/branch", "/resume", "/status", "/usage",
                "/title", "/busy", "/model", "/reasoning", "/tools", "/skills", "/agent",
                "/cron", "/approve", "/kanban", "/deny", "/queue", "/steer", "/acp",
                "/restart", "/stop", "/compress", "/rollback", "/version", "/platforms",
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
