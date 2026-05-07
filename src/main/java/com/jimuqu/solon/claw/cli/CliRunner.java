package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.web.DashboardMcpService;

/** Dispatches parsed console modes. */
public class CliRunner {
    private final CliRuntime cliRuntime;
    private final SessionRepository sessionRepository;
    private final DashboardMcpService dashboardMcpService;
    private final AppConfig appConfig;

    public CliRunner(CliRuntime cliRuntime) {
        this(cliRuntime, null, null, null);
    }

    public CliRunner(CliRuntime cliRuntime, SessionRepository sessionRepository) {
        this(cliRuntime, sessionRepository, null, null);
    }

    public CliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService dashboardMcpService) {
        this(cliRuntime, sessionRepository, dashboardMcpService, null);
    }

    public CliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            DashboardMcpService dashboardMcpService,
            AppConfig appConfig) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.dashboardMcpService = dashboardMcpService;
        this.appConfig = appConfig;
    }

    public int run(CliMode mode) throws Exception {
        if (mode.getKind() == CliMode.Kind.COMPLETION) {
            return new ShellCompletionGenerator().write(mode.getInput(), System.out, System.err);
        }
        if (mode.getKind() == CliMode.Kind.ACP) {
            return new com.jimuqu.solon.claw.cli.acp.AcpStdioServer(
                            cliRuntime, sessionRepository, dashboardMcpService, appConfig)
                    .run();
        }
        if (mode.getKind() == CliMode.Kind.TUI) {
            return new TuiShell(cliRuntime, mode).run();
        }
        return new CliShell(cliRuntime, mode).run();
    }
}
