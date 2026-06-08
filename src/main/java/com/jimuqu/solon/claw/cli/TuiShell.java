package com.jimuqu.solon.claw.cli;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** 呈现Tui交互视图，封装终端侧输入输出细节。 */
public class TuiShell {
    /** COMMANDS的统一常量值。 */
    private static final String[] COMMANDS = TerminalCommandCatalog.SLASH_COMMANDS;

    /** 记录TUI中的CLI运行时。 */
    private final CliRuntime cliRuntime;

    /** 记录TUI中的模式。 */
    private final CliMode mode;

    /** 记录TUI中的附件Resolver。 */
    private final CliAttachmentResolver attachmentResolver;

    /** 注入应用配置，用于TUI。 */
    private final AppConfig appConfig;

    /** 记录TUI中的模型Picker。 */
    private final TerminalModelPicker modelPicker;

    /** 记录TUI中的 setup/config 本地命令服务。 */
    private final TerminalSetupCommands setupCommands;

    /** 记录TUI中的会话浏览器。 */
    private final TerminalSessionBrowser sessionBrowser;

    /** 记录TUI中的历史Viewer。 */
    private final TerminalHistoryViewer historyViewer;

    /** 记录TUI中的记录文本。 */
    private final LocalTerminalTranscript transcript = new LocalTerminalTranscript();

    /** 记录TUI中的skin。 */
    private TerminalSkin skin = TerminalSkin.fromEnvironment();

    /** 记录TUI中的最近一次事件快照。 */
    private ConsoleEventSink.EventSnapshot lastEventSnapshot;

    /** 记录TUI中的最近一次回复。 */
    private String lastReply;

    /**
     * 创建Tui Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     */
    public TuiShell(CliRuntime cliRuntime, CliMode mode) {
        this(cliRuntime, mode, null, null, null, null, null, null);
    }

    /**
     * 创建Tui Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     * @param attachmentResolver 附件解析器参数。
     */
    public TuiShell(CliRuntime cliRuntime, CliMode mode, CliAttachmentResolver attachmentResolver) {
        this(cliRuntime, mode, attachmentResolver, null, null, null, null, null);
    }

    /**
     * 创建Tui Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     * @param attachmentResolver 附件解析器参数。
     * @param appConfig 应用运行配置。
     */
    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig) {
        this(cliRuntime, mode, attachmentResolver, appConfig, null, null, null, null);
    }

    /**
     * 创建Tui Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     * @param attachmentResolver 附件解析器参数。
     * @param appConfig 应用运行配置。
     * @param modelPicker 模型选择器参数。
     */
    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker) {
        this(cliRuntime, mode, attachmentResolver, appConfig, modelPicker, null, null, null);
    }

    /**
     * 创建Tui Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     * @param attachmentResolver 附件解析器参数。
     * @param appConfig 应用运行配置。
     * @param modelPicker 模型选择器参数。
     * @param sessionBrowser 会话浏览器参数。
     */
    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker,
            TerminalSessionBrowser sessionBrowser) {
        this(
                cliRuntime,
                mode,
                attachmentResolver,
                appConfig,
                modelPicker,
                null,
                sessionBrowser,
                null);
    }

    /**
     * 创建Tui Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     * @param attachmentResolver 附件解析器参数。
     * @param appConfig 应用运行配置。
     * @param modelPicker 模型选择器参数。
     * @param setupCommands setup/config 本地命令服务。
     * @param sessionBrowser 会话浏览器参数。
     * @param historyViewer 历史查看器参数。
     */
    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker,
            TerminalSetupCommands setupCommands,
            TerminalSessionBrowser sessionBrowser,
            TerminalHistoryViewer historyViewer) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
        this.attachmentResolver = attachmentResolver;
        this.appConfig = appConfig;
        this.modelPicker = modelPicker;
        this.setupCommands = setupCommands;
        this.sessionBrowser = sessionBrowser;
        this.historyViewer = historyViewer;
    }

    /**
     * 执行异步任务主体。
     *
     * @return 返回运行结果。
     */
    public int run() throws Exception {
        Terminal terminal =
                TerminalBuilder.builder()
                        .system(true)
                        .jansi(true)
                        .dumb(true)
                        .encoding(StandardCharsets.UTF_8)
                        .build();
        TerminalDimensionSupport.sanitize(terminal);
        PrintWriter writer = terminal.writer();
        String sessionId = StrUtil.blankToDefault(mode.getSessionId(), "tui");
        if (StrUtil.isNotBlank(mode.getInput())) {
            renderHeader(writer, sessionId);
            return send(null, writer, sessionId, mode.getInput());
        }

        LineReader reader =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("solon-claw-tui")
                        .completer(new StringsCompleter(COMMANDS))
                        .variable(LineReader.HISTORY_FILE, historyFile().toPath())
                        .build();
        TerminalShortcuts.install(reader);
        renderHeader(writer, sessionId);
        LocalTerminalTaskRunner taskRunner = new LocalTerminalTaskRunner(writer);
        try {
            while (true) {
                String input;
                try {
                    input = reader.readLine(skin.prompt("你"));
                } catch (UserInterruptException e) {
                    writer.println(skin.dim("输入已取消。运行中的任务可用 /stop 停止，或 /exit 退出。"));
                    writer.flush();
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                input = StrUtil.nullToEmpty(input).trim();
                if (StrUtil.isBlank(input)) {
                    continue;
                }
                if (isExitCommand(input)) {
                    if (taskRunner.hasRunning() && !isForceExitCommand(input)) {
                        writer.println(skin.dim(taskRunner.renderExitGuard(input)));
                        writer.flush();
                        continue;
                    }
                    break;
                }
                dispatchInteractive(taskRunner, writer, sessionId, input);
                writer.println();
                renderFooter(writer, sessionId, taskRunner);
                writer.println(skin.dim(skin.border()));
                writer.flush();
            }
        } finally {
            renderShutdownSummary(writer, sessionId, taskRunner);
            taskRunner.cancelAndClose(
                    new Runnable() {
                        /** 执行异步任务主体。 */
                        @Override
                        public void run() {
                            cliRuntime.stop(sessionId);
                        }
                    });
        }
        return 0;
    }

    /** 解析 TUI 输入历史文件，避免真实输入落到仓库工作目录。 */
    private File historyFile() {
        String runtimeHome =
                appConfig == null || appConfig.getRuntime() == null
                        ? RuntimePathConstants.RUNTIME_HOME
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getHome(), RuntimePathConstants.RUNTIME_HOME);
        File historyDir = FileUtil.file(runtimeHome, "history");
        FileUtil.mkdir(historyDir);
        return FileUtil.file(historyDir, "tui.history");
    }

    /**
     * 分发Interactive。
     *
     * @param taskRunner 任务Runner参数。
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     */
    private void dispatchInteractive(
            LocalTerminalTaskRunner taskRunner,
            final PrintWriter writer,
            final String sessionId,
            final String input)
            throws Exception {
        if (shouldHandleInline(input)) {
            send(taskRunner, writer, sessionId, input);
            return;
        }
        taskRunner.submit(
                input,
                new Callable<Integer>() {
                    /**
                     * 执行回调调用并返回结果。
                     *
                     * @return 返回call结果。
                     */
                    @Override
                    public Integer call() throws Exception {
                        return Integer.valueOf(send(taskRunner, writer, sessionId, input));
                    }
                });
    }

    /**
     * 判断是否需要Handle Inline。
     *
     * @param input 输入参数。
     * @return 如果Handle Inline满足条件则返回 true，否则返回 false。
     */
    private boolean shouldHandleInline(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (LocalTerminalHelp.isHelp(value)
                || "/copy".equalsIgnoreCase(value)
                || "/events".equalsIgnoreCase(value)
                || "/tasks".equalsIgnoreCase(value)
                || TerminalSecurityPolicyView.isSecurityCommand(value)
                || (setupCommands != null && setupCommands.isSetupCommand(value))
                || transcript.isTranscriptCommand(value)
                || value.equalsIgnoreCase("/attachments")
                || value.toLowerCase(java.util.Locale.ROOT).startsWith("/attachments ")
                || TerminalTips.isTipsCommand(value)) {
            return true;
        }
        return value.startsWith("/")
                && !"/retry".equalsIgnoreCase(value)
                && !value.toLowerCase(java.util.Locale.ROOT).startsWith("/retry ");
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param taskRunner 任务Runner参数。
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     * @return 返回send结果。
     */
    private int send(
            LocalTerminalTaskRunner taskRunner, PrintWriter writer, String sessionId, String input)
            throws Exception {
        input = TerminalInputSanitizer.stripLeakedTerminalResponses(input);
        String trimmed = StrUtil.nullToEmpty(input).trim();
        if (LocalTerminalHelp.isHelp(trimmed)) {
            writer.println(LocalTerminalHelp.text());
            writer.flush();
            return 0;
        }
        if (setupCommands != null && setupCommands.isSetupCommand(trimmed)) {
            writer.println(setupCommands.render(trimmed));
            writer.flush();
            return 0;
        }
        if (TerminalSkin.isSkinCommand(trimmed)) {
            String next =
                    trimmed.length() <= "/skin".length()
                            ? ""
                            : trimmed.substring("/skin".length()).trim();
            if (StrUtil.isNotBlank(next)) {
                skin = TerminalSkin.resolve(next);
            }
            writer.println(skin.renderHelp());
            writer.flush();
            return 0;
        }
        if (TerminalTips.isTipsCommand(trimmed)) {
            writer.println(TerminalTips.render());
            writer.flush();
            return 0;
        }
        if (TerminalSecurityPolicyView.isSecurityCommand(trimmed)) {
            writer.println(TerminalSecurityPolicyView.render(appConfig, trimmed));
            writer.flush();
            return 0;
        }
        if (modelPicker != null && modelPicker.isPickerCommand(trimmed)) {
            String command = modelPicker.resolveCommand(trimmed);
            if (StrUtil.isBlank(command)) {
                writer.println(modelPicker.render());
                writer.flush();
                return 0;
            }
            trimmed = command;
            input = command;
        }
        if (sessionBrowser != null && sessionBrowser.isBrowserCommand(trimmed)) {
            String command = sessionBrowser.resolveCommand(trimmed);
            if (StrUtil.isBlank(command)) {
                writer.println(sessionBrowser.render(trimmed));
                writer.flush();
                return 0;
            }
            trimmed = command;
            input = command;
        }
        if (historyViewer != null && historyViewer.isHistoryCommand(trimmed)) {
            writer.println(historyViewer.render(sessionId, trimmed));
            writer.flush();
            return 0;
        }
        if ("/events".equalsIgnoreCase(trimmed)) {
            writer.println(renderEvents());
            writer.flush();
            return 0;
        }
        if ("/tasks".equalsIgnoreCase(trimmed)) {
            writer.println(taskRunner == null ? "暂无终端后台任务。" : taskRunner.renderTasks());
            writer.flush();
            return 0;
        }
        if (transcript.isTranscriptCommand(trimmed)) {
            writer.println(transcript.render(trimmed));
            writer.flush();
            return 0;
        }
        if (isAttachmentPreviewCommand(trimmed)) {
            writer.println(renderAttachmentPreview(trimmed));
            writer.flush();
            return 0;
        }
        if ("/copy".equalsIgnoreCase(trimmed)) {
            return copyLastReply(writer);
        }
        ConsoleEventSink sink = new ConsoleEventSink(writer, true);
        CliAttachmentResolver.ResolvedInput resolved = resolveAttachments(input);
        transcript.user(resolved.getText());
        if (!resolved.getAttachments().isEmpty()) {
            writer.println(skin.dim("已附加本地文件：" + resolved.getAttachments().size()));
            writer.flush();
        }
        GatewayReply reply;
        try {
            reply = cliRuntime.send(sessionId, resolved.getText(), resolved.getAttachments(), sink);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.println(skin.dim("执行已中断。"));
            writer.flush();
            return 130;
        } catch (Exception e) {
            writer.println(
                    skin.dim(
                            "执行失败："
                                    + SecretRedactor.redact(
                                            StrUtil.blankToDefault(
                                                    e.getMessage(), e.getClass().getSimpleName()),
                                            1000)));
            writer.flush();
            return 1;
        }
        String finalText = reply == null ? "" : StrUtil.nullToEmpty(reply.getContent());
        if (StrUtil.isBlank(finalText)) {
            finalText = sink.assistantText();
        }
        if (StrUtil.isBlank(finalText)) {
            writer.println(skin.dim("未产生最终回复，已按失败处理。"));
            writer.flush();
            return 1;
        }
        if (StrUtil.isNotBlank(finalText)) {
            lastReply = finalText;
            transcript.assistant(finalText);
        }
        lastEventSnapshot = sink.eventSnapshot();
        if (reply != null && StrUtil.isNotBlank(reply.getContent()) && !sink.hasAssistantOutput()) {
            writer.println(skin.bold(skin.accent("系统")));
            writer.println(reply.getContent());
            writer.flush();
        }
        return reply != null && reply.isError() ? 1 : 0;
    }

    /**
     * 渲染Events。
     *
     * @return 返回render Events结果。
     */
    private String renderEvents() {
        if (lastEventSnapshot == null || lastEventSnapshot.getEventCount() <= 0) {
            return "暂无终端事件。";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("最近一次运行事件：total=")
                .append(lastEventSnapshot.getEventCount())
                .append(" tools=")
                .append(lastEventSnapshot.getToolCount())
                .append(" failures=")
                .append(lastEventSnapshot.getFailureCount());
        List<String> events = lastEventSnapshot.getRecentEvents();
        if (!events.isEmpty()) {
            int index = 1;
            for (String event : events) {
                buffer.append('\n').append(index).append(". ").append(event);
                index++;
            }
        }
        return buffer.toString();
    }

    /**
     * 复制Last Reply。
     *
     * @param writer writer 参数。
     * @return 返回Last Reply结果。
     */
    private int copyLastReply(PrintWriter writer) {
        if (StrUtil.isBlank(lastReply)) {
            writer.println(skin.dim("没有可复制的上一条回复。"));
            writer.flush();
            return 0;
        }
        TerminalClipboardSupport.copy(writer, lastReply);
        writer.println(skin.dim("已复制上一条回复到终端剪贴板。"));
        writer.flush();
        return 0;
    }

    /**
     * 解析附件。
     *
     * @param input 输入参数。
     * @return 返回解析后的附件。
     */
    private CliAttachmentResolver.ResolvedInput resolveAttachments(String input) {
        if (attachmentResolver == null) {
            return new CliAttachmentResolver.ResolvedInput(
                    input, java.util.Collections.<MessageAttachment>emptyList());
        }
        return attachmentResolver.resolve(input);
    }

    /**
     * 渲染Header。
     *
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     */
    private void renderHeader(PrintWriter writer, String sessionId) {
        writer.println(skin.bold("Solon Claw TUI"));
        writer.println(skin.dim(statusLine(sessionId)));
        writer.println(
                skin.dim(
                        "tips: /help 命令  /tips 提示  /tasks 任务  /queue 排队  /steer 引导  /sessions 会话  /goal 目标  /cron 自动化  /security 安全  /recap 摘要  /reload-mcp MCP  /events 事件  /skin 皮肤  /copy 复制  /exit 退出"));
        writer.println(skin.dim("tip: " + TerminalTips.current(sessionId)));
        writer.println(skin.dim(TerminalShortcuts.helpLine()));
        writer.println(skin.dim(skin.border()));
        writer.flush();
    }

    /**
     * 判断是否Exit命令。
     *
     * @param input 输入参数。
     * @return 如果Exit命令满足条件则返回 true，否则返回 false。
     */
    private boolean isExitCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/exit".equalsIgnoreCase(value)
                || "/quit".equalsIgnoreCase(value)
                || "/exit!".equalsIgnoreCase(value)
                || "/quit!".equalsIgnoreCase(value);
    }

    /**
     * 判断是否Force Exit命令。
     *
     * @param input 输入参数。
     * @return 如果Force Exit命令满足条件则返回 true，否则返回 false。
     */
    private boolean isForceExitCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/exit!".equalsIgnoreCase(value) || "/quit!".equalsIgnoreCase(value);
    }

    /**
     * 渲染Footer。
     *
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param taskRunner 任务Runner参数。
     */
    private void renderFooter(
            PrintWriter writer, String sessionId, LocalTerminalTaskRunner taskRunner) {
        writer.println(skin.dim(footerLine(sessionId, taskRunner)));
    }

    /**
     * 渲染关闭摘要。
     *
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param taskRunner 任务Runner参数。
     */
    private void renderShutdownSummary(
            PrintWriter writer, String sessionId, LocalTerminalTaskRunner taskRunner) {
        writer.println(
                skin.dim(
                        TerminalLifecycleSummary.render(
                                sessionId,
                                taskRunner,
                                transcript,
                                lastEventSnapshot,
                                StrUtil.isNotBlank(lastReply))));
        writer.flush();
    }

    /**
     * 执行footer行相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param taskRunner 任务Runner参数。
     * @return 返回footer Line结果。
     */
    private String footerLine(String sessionId, LocalTerminalTaskRunner taskRunner) {
        int running = taskRunner == null ? 0 : taskRunner.runningCount();
        int recentTasks = taskRunner == null ? 0 : taskRunner.snapshots().size();
        int events = lastEventSnapshot == null ? 0 : lastEventSnapshot.getEventCount();
        int tools = lastEventSnapshot == null ? 0 : lastEventSnapshot.getToolCount();
        int failures = lastEventSnapshot == null ? 0 : lastEventSnapshot.getFailureCount();
        String copy = StrUtil.isBlank(lastReply) ? "empty" : "ready";
        return "footer: "
                + statusLine(sessionId)
                + "  tasks="
                + running
                + "/"
                + recentTasks
                + "  events="
                + events
                + " tools="
                + tools
                + " failures="
                + failures
                + "  copy="
                + copy;
    }

    /**
     * 执行状态行相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回状态Line结果。
     */
    private String statusLine(String sessionId) {
        String provider = "";
        String model = "";
        String reasoning = "";
        if (appConfig != null) {
            if (appConfig.getModel() != null) {
                provider = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
                model = StrUtil.nullToEmpty(appConfig.getModel().getDefaultModel()).trim();
            }
            if (appConfig.getLlm() != null) {
                reasoning = StrUtil.nullToEmpty(appConfig.getLlm().getReasoningEffort()).trim();
            }
        }
        return "session="
                + sessionId
                + "  provider="
                + StrUtil.blankToDefault(provider, "default")
                + "  model="
                + StrUtil.blankToDefault(model, "-")
                + "  reasoning="
                + StrUtil.blankToDefault(reasoning, "-")
                + busyStatusSuffix(sessionId);
    }

    /**
     * 执行busy状态Suffix相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @return 返回busy状态Suffix结果。
     */
    private String busyStatusSuffix(String sessionId) {
        if (cliRuntime == null) {
            return "";
        }
        try {
            GatewayReply reply = cliRuntime.send(sessionId, "/busy status", null);
            if (reply == null || StrUtil.isBlank(reply.getContent())) {
                return "";
            }
            String content = reply.getContent();
            String policy = statusValue(content, "busy_policy");
            String activeRunId = statusValue(content, "active_run_id");
            String queuePending = statusValue(content, "queue_pending");
            if (StrUtil.isBlank(policy)
                    && StrUtil.isBlank(activeRunId)
                    && StrUtil.isBlank(queuePending)) {
                return "";
            }
            return "  busy="
                    + StrUtil.blankToDefault(policy, "-")
                    + " run="
                    + StrUtil.blankToDefault(activeRunId, "-")
                    + " queue="
                    + StrUtil.blankToDefault(queuePending, "0");
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 执行状态值相关逻辑。
     *
     * @param content 待处理内容。
     * @param key 配置键或映射键。
     * @return 返回状态Value结果。
     */
    private String statusValue(String content, String key) {
        String prefix = key + "=";
        String[] lines = StrUtil.nullToEmpty(content).split("\\r?\\n");
        for (String line : lines) {
            String value = StrUtil.nullToEmpty(line).trim();
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    /**
     * 判断是否附件Preview命令。
     *
     * @param input 输入参数。
     * @return 如果附件Preview命令满足条件则返回 true，否则返回 false。
     */
    private boolean isAttachmentPreviewCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/attachments".equalsIgnoreCase(value)
                || value.toLowerCase(java.util.Locale.ROOT).startsWith("/attachments ");
    }

    /**
     * 渲染附件预览。
     *
     * @param input 输入参数。
     * @return 返回render附件Preview结果。
     */
    private String renderAttachmentPreview(String input) {
        if (attachmentResolver == null) {
            return "当前终端未启用附件解析。";
        }
        String value = StrUtil.nullToEmpty(input).trim();
        String rest =
                value.length() <= "/attachments".length()
                        ? ""
                        : value.substring("/attachments".length()).trim();
        return attachmentResolver.renderPreview(rest);
    }
}
