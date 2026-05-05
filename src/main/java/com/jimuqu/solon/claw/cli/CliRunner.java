package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.web.DashboardMcpService;

/** Dispatches parsed console modes. */
public class CliRunner {
    private final CliRuntime cliRuntime;
    private final SessionRepository sessionRepository;
    private final DashboardMcpService dashboardMcpService;

    public CliRunner(CliRuntime cliRuntime) {
        this(cliRuntime, null, null);
    }

    public CliRunner(CliRuntime cliRuntime, SessionRepository sessionRepository) {
        this(cliRuntime, sessionRepository, null);
    }

    public CliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService dashboardMcpService) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.dashboardMcpService = dashboardMcpService;
    }

    public int run(CliMode mode) throws Exception {
        if (mode.getKind() == CliMode.Kind.ACP) {
            return new com.jimuqu.solon.claw.cli.acp.AcpStdioServer(
                            cliRuntime, sessionRepository, dashboardMcpService)
                    .run();
        }
        if (mode.getKind() == CliMode.Kind.TUI) {
            return new TuiShell(cliRuntime, mode).run();
        }
        return new CliShell(cliRuntime, mode).run();
    }
}
