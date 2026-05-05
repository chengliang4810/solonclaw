package com.jimuqu.solon.claw.cli;

/** Dispatches parsed console modes. */
public class CliRunner {
    private final CliRuntime cliRuntime;

    public CliRunner(CliRuntime cliRuntime) {
        this.cliRuntime = cliRuntime;
    }

    public int run(CliMode mode) throws Exception {
        if (mode.getKind() == CliMode.Kind.TUI) {
            return new TuiShell(cliRuntime, mode).run();
        }
        return new CliShell(cliRuntime, mode).run();
    }
}
