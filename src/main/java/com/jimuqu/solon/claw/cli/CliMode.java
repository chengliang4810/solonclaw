package com.jimuqu.solon.claw.cli;

/** Parsed command-line mode for the application entry point. */
public class CliMode {
    public enum Kind {
        SERVER,
        CLI,
        TUI,
        COMPLETION
    }

    private final Kind kind;
    private final String input;
    private final String sessionId;

    public CliMode(Kind kind, String input, String sessionId) {
        this.kind = kind == null ? Kind.SERVER : kind;
        this.input = input;
        this.sessionId = sessionId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getInput() {
        return input;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isConsoleMode() {
        return kind == Kind.CLI || kind == Kind.TUI || kind == Kind.COMPLETION;
    }
}
