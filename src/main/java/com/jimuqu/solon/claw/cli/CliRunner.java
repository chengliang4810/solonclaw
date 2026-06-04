package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.LlmProviderService;

/** Dispatches parsed console modes. */
public class CliRunner {
    private final CliRuntime cliRuntime;
    private final SessionRepository sessionRepository;
    private final AppConfig appConfig;
    private final CliAttachmentResolver attachmentResolver;
    private final TerminalModelPicker modelPicker;
    private final TerminalSessionBrowser sessionBrowser;
    private final TerminalHistoryViewer historyViewer;

    public CliRunner(CliRuntime cliRuntime) {
        this(cliRuntime, null, null, null, null);
    }

    public CliRunner(CliRuntime cliRuntime, SessionRepository sessionRepository) {
        this(cliRuntime, sessionRepository, null, null, null);
    }

    public CliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            AppConfig appConfig,
            CliAttachmentResolver attachmentResolver,
            LlmProviderService llmProviderService) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.appConfig = appConfig;
        this.attachmentResolver = attachmentResolver;
        this.modelPicker =
                appConfig == null || llmProviderService == null
                        ? null
                        : new TerminalModelPicker(appConfig, llmProviderService);
        this.sessionBrowser =
                sessionRepository == null ? null : new TerminalSessionBrowser(sessionRepository);
        this.historyViewer =
                sessionRepository == null
                        ? null
                        : new TerminalHistoryViewer(sessionRepository, cliRuntime);
    }

    public int run(CliMode mode) throws Exception {
        if (mode.getKind() == CliMode.Kind.COMPLETION) {
            return new ShellCompletionGenerator().write(mode.getInput(), System.out, System.err);
        }
        if (mode.getKind() == CliMode.Kind.TUI) {
            return new TuiShell(
                            cliRuntime,
                            mode,
                            attachmentResolver,
                            appConfig,
                            modelPicker,
                            sessionBrowser,
                            historyViewer)
                    .run();
        }
        return new CliShell(
                        cliRuntime,
                        mode,
                        attachmentResolver,
                        appConfig,
                        modelPicker,
                        sessionBrowser,
                        historyViewer)
                .run();
    }
}
