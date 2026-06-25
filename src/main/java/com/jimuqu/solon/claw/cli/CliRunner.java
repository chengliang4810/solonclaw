package com.jimuqu.solon.claw.cli;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.support.LlmProviderService;

/** 驱动Cli运行流程，连接命令入口与业务服务。 */
public class CliRunner {
    /** 记录CLI中的CLI运行时。 */
    private final CliRuntime cliRuntime;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入应用配置，用于CLI。 */
    private final AppConfig appConfig;

    /** 记录CLI中的附件Resolver。 */
    private final AttachmentPathResolver attachmentResolver;

    /** 记录CLI中的模型Picker。 */
    private final TerminalModelPicker modelPicker;

    /** 记录CLI中的会话浏览器。 */
    private final TerminalSessionBrowser sessionBrowser;

    /** 记录CLI中的历史Viewer。 */
    private final TerminalHistoryViewer historyViewer;

    /** 记录CLI中的 setup/config 本地命令服务。 */
    private final TerminalSetupCommands setupCommands;

    /**
     * 创建Cli Runner实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param sessionRepository 会话仓储依赖。
     * @param appConfig 应用运行配置。
     * @param attachmentResolver 附件解析器参数。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public CliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            AppConfig appConfig,
            AttachmentPathResolver attachmentResolver,
            LlmProviderService llmProviderService) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.appConfig = appConfig;
        this.attachmentResolver = attachmentResolver;
        this.modelPicker =
                appConfig == null || llmProviderService == null
                        ? null
                        : new TerminalModelPicker(appConfig, llmProviderService);
        this.setupCommands =
                appConfig == null ? null : new TerminalSetupCommands(appConfig, modelPicker);
        this.sessionBrowser =
                sessionRepository == null ? null : new TerminalSessionBrowser(sessionRepository);
        this.historyViewer =
                sessionRepository == null
                        ? null
                        : new TerminalHistoryViewer(sessionRepository, cliRuntime);
    }

    /**
     * 执行异步任务主体。
     *
     * @param mode 模式参数。
     * @return 返回运行结果。
     */
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
                            setupCommands,
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
                        setupCommands,
                        sessionBrowser,
                        historyViewer)
                .run();
    }
}
