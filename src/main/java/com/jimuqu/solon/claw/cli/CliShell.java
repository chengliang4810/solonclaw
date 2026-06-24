package com.jimuqu.solon.claw.cli;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 呈现Cli交互视图，封装终端侧输入输出细节。 */
public class CliShell {
    /** CLI 交互层的低敏诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(CliShell.class);

    /** 提示词的统一常量值。 */
    private static final String PROMPT = "\u001B[36mjimuqu>\u001B[0m ";

    /** COMMANDS的统一常量值。 */
    private static final String[] COMMANDS = TerminalCommandCatalog.SLASH_COMMANDS;

    /** 记录CLI中的CLI运行时。 */
    private final CliRuntime cliRuntime;

    /** 记录CLI中的模式。 */
    private final CliMode mode;

    /** 记录CLI中的附件Resolver。 */
    private final CliAttachmentResolver attachmentResolver;

    /** 注入应用配置，用于CLI。 */
    private final AppConfig appConfig;

    /** 记录CLI中的模型Picker。 */
    private final TerminalModelPicker modelPicker;

    /** 记录CLI中的 setup/config 本地命令服务。 */
    private final TerminalSetupCommands setupCommands;

    /** 记录CLI中的会话浏览器。 */
    private final TerminalSessionBrowser sessionBrowser;

    /** 记录CLI中的历史Viewer。 */
    private final TerminalHistoryViewer historyViewer;

    /** 记录CLI中的记录文本。 */
    private final LocalTerminalTranscript transcript = new LocalTerminalTranscript();

    /** 记录CLI中的最近一次事件快照。 */
    private ConsoleEventSink.EventSnapshot lastEventSnapshot;

    /** 记录CLI中的skin。 */
    private TerminalSkin skin = TerminalSkin.fromEnvironment();

    /**
     * 创建Cli Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     */
    public CliShell(CliRuntime cliRuntime, CliMode mode) {
        this(cliRuntime, mode, null, null, null);
    }

    /**
     * 创建Cli Shell实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param mode 模式参数。
     * @param attachmentResolver 附件解析器参数。
     * @param modelPicker 模型选择器参数。
     * @param sessionBrowser 会话浏览器参数。
     */
    public CliShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            TerminalModelPicker modelPicker,
            TerminalSessionBrowser sessionBrowser) {
        this(cliRuntime, mode, attachmentResolver, null, modelPicker, null, sessionBrowser, null);
    }

    /**
     * 创建Cli Shell实例，并注入运行所需依赖。
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
    public CliShell(
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
        configureWindowsUtf8();
        Terminal terminal = newTerminal();
        PrintWriter writer = terminal.writer();
        String sessionId = StrUtil.blankToDefault(mode.getSessionId(), "cli");
        if (StrUtil.isNotBlank(mode.getInput())) {
            return sendOnce(null, writer, sessionId, mode.getInput(), true);
        }

        LineReader reader =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("solon-claw")
                        .completer(new StringsCompleter(COMMANDS))
                        .variable(LineReader.HISTORY_FILE, historyFile().toPath())
                        .build();
        TerminalShortcuts.install(reader);
        writer.println("Solon Claw CLI。输入 /help 查看命令，/exit 退出。");
        writer.flush();
        LocalTerminalTaskRunner taskRunner = new LocalTerminalTaskRunner(writer);
        try {
            while (true) {
                String line;
                try {
                    line = reader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    writer.println("已取消当前输入。运行中的任务可用 /stop 停止。");
                    writer.flush();
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                line = StrUtil.nullToEmpty(line).trim();
                if (StrUtil.isBlank(line)) {
                    continue;
                }
                if (isExitCommand(line)) {
                    if (taskRunner.hasRunning() && !isForceExitCommand(line)) {
                        writer.println(taskRunner.renderExitGuard(line));
                        writer.flush();
                        continue;
                    }
                    break;
                }
                dispatchInteractive(taskRunner, writer, sessionId, line);
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

    /** 解析 CLI 历史文件路径，避免输入历史直接落到仓库工作目录。 */
    private File historyFile() {
        String runtimeHome =
                appConfig == null || appConfig.getRuntime() == null
                        ? RuntimePathConstants.RUNTIME_HOME
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getHome(), RuntimePathConstants.RUNTIME_HOME);
        File historyDir = FileUtil.file(runtimeHome, "history");
        FileUtil.mkdir(historyDir);
        return FileUtil.file(historyDir, "cli.history");
    }

    /**
     * 分发Interactive。
     *
     * @param taskRunner 任务Runner参数。
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param line 行参数。
     */
    private void dispatchInteractive(
            LocalTerminalTaskRunner taskRunner,
            final PrintWriter writer,
            final String sessionId,
            final String line)
            throws Exception {
        if (shouldHandleInline(line)) {
            sendOnce(taskRunner, writer, sessionId, line, true);
            return;
        }
        taskRunner.submit(
                line,
                new Callable<Integer>() {
                    /**
                     * 执行回调调用并返回结果。
                     *
                     * @return 返回call结果。
                     */
                    @Override
                    public Integer call() throws Exception {
                        return Integer.valueOf(sendOnce(taskRunner, writer, sessionId, line, true));
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
                || isTerminalOnlyLocalCommand(value, null)
                || TerminalTips.isTipsCommand(value)
                || TerminalSkin.isSkinCommand(value)
                || TerminalSecurityPolicyView.isSecurityCommand(value)
                || (setupCommands != null && setupCommands.isSetupCommand(value))
                || "/tasks".equalsIgnoreCase(value)
                || transcript.isTranscriptCommand(value)
                || value.equalsIgnoreCase("/attachments")
                || value.toLowerCase(java.util.Locale.ROOT).startsWith("/attachments ")) {
            return true;
        }
        return value.startsWith("/")
                && !"/retry".equalsIgnoreCase(value)
                && !value.toLowerCase(java.util.Locale.ROOT).startsWith("/retry ");
    }

    /**
     * 发送Once。
     *
     * @param taskRunner 任务Runner参数。
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param input 输入参数。
     * @param verbose verbose 参数。
     * @return 返回Once结果。
     */
    private int sendOnce(
            LocalTerminalTaskRunner taskRunner,
            PrintWriter writer,
            String sessionId,
            String input,
            boolean verbose)
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
        if ("/copy".equalsIgnoreCase(trimmed)) {
            return copyLastReply(writer);
        }
        if ("/events".equalsIgnoreCase(trimmed)) {
            writer.println(renderEvents());
            writer.flush();
            return 0;
        }
        if (isTerminalOnlyLocalCommand(trimmed, taskRunner)) {
            writer.println(renderTerminalOnlyCommandGuidance(trimmed));
            writer.flush();
            return 0;
        }
        if (TerminalTips.isTipsCommand(trimmed)) {
            writer.println(TerminalTips.render());
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
        if (TerminalSecurityPolicyView.isSecurityCommand(trimmed)) {
            writer.println(TerminalSecurityPolicyView.render(appConfig, trimmed));
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
        ConsoleEventSink sink = new ConsoleEventSink(writer, verbose);
        CliAttachmentResolver.ResolvedInput resolved = resolveAttachments(input);
        transcript.user(resolved.getText());
        if (!resolved.getAttachments().isEmpty()) {
            writer.println("已附加本地文件：" + resolved.getAttachments().size());
            writer.flush();
        }
        GatewayReply reply;
        try {
            reply = cliRuntime.send(sessionId, resolved.getText(), resolved.getAttachments(), sink);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.println("执行已中断。");
            writer.flush();
            return 130;
        } catch (Exception e) {
            writer.println(
                    "执行失败："
                            + SecretRedactor.redact(
                                    StrUtil.blankToDefault(
                                            e.getMessage(), e.getClass().getSimpleName()),
                                    1000));
            writer.flush();
            return 1;
        }
        String finalText = reply == null ? "" : StrUtil.nullToEmpty(reply.getContent());
        if (StrUtil.isBlank(finalText)) {
            finalText = sink.assistantText();
        }
        if (StrUtil.isBlank(finalText)) {
            writer.println("未产生最终回复，已按失败处理。");
            writer.flush();
            return 1;
        }
        if (StrUtil.isNotBlank(finalText)) {
            lastReply = finalText;
            transcript.assistant(finalText);
        }
        lastEventSnapshot = sink.eventSnapshot();
        if (reply != null && StrUtil.isNotBlank(reply.getContent()) && !sink.hasAssistantOutput()) {
            writer.println(reply.getContent());
            writer.flush();
        }
        return reply != null && reply.isError() ? 1 : 0;
    }

    /** 记录CLI中的最近一次回复。 */
    private String lastReply;

    /**
     * 判断当前命令是否只能由交互式 TUI/终端状态直接处理。
     *
     * @param input 用户输入。
     * @param taskRunner 终端后台任务状态；为空表示 one-shot 或路由判断阶段。
     * @return 需要本地提示时返回 true。
     */
    private boolean isTerminalOnlyLocalCommand(String input, LocalTerminalTaskRunner taskRunner) {
        CommandDescriptor descriptor = CommandRegistry.resolve(firstToken(input));
        if (descriptor == null) {
            return false;
        }
        String name = descriptor.getName();
        if ("steer".equals(name)) {
            return taskRunner == null || !taskRunner.hasRunning();
        }
        if (!"terminal".equals(descriptor.getCategory())) {
            return false;
        }
        if ("tasks".equals(name)
                || "skin".equals(name)
                || "copy".equals(name)
                || "history".equals(name)
                || "quit".equals(name)) {
            return false;
        }
        return true;
    }

    /**
     * 渲染 TUI 专属交互命令在 CLI one-shot 下的本地说明，避免误触发模型调用。
     *
     * @param input 用户输入。
     * @return 面向终端用户的说明文本。
     */
    private String renderTerminalOnlyCommandGuidance(String input) {
        CommandDescriptor descriptor = CommandRegistry.resolve(firstToken(input));
        String command = descriptor == null ? firstToken(input) : descriptor.slashName();
        String description = descriptor == null ? "" : descriptor.getDescription();
        StringBuilder buffer = new StringBuilder();
        buffer.append("此命令是 TUI 交互命令：")
                .append(command)
                .append(StrUtil.isBlank(description) ? "" : " - " + description)
                .append('\n');
        if ("/steer".equals(command)) {
            buffer.append("当前没有运行中的终端任务可注入；请在 solonclaw 交互界面里先发起任务，或直接发送普通提示。");
        } else if ("/background".equals(command)) {
            buffer.append("后台提示需要 TUI 会话的任务队列；请启动 solonclaw 后在界面内使用该命令。");
        } else if ("/paste".equals(command) || "/image".equals(command)) {
            buffer.append("剪贴板和图片附加依赖 TUI 输入框状态；请启动 solonclaw 后在界面内操作。");
        } else {
            buffer.append("请启动 solonclaw 进入 TUI 后使用该命令。");
        }
        buffer.append('\n').append("可用替代：solonclaw --cli -p /help 或 solonclaw --tui -p /help 查看终端命令。");
        return buffer.toString();
    }

    /**
     * 读取输入首个 token。
     *
     * @param input 用户输入。
     * @return 首个 token。
     */
    private String firstToken(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.split("\\s+", 2)[0];
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
        java.util.List<String> events = lastEventSnapshot.getRecentEvents();
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
            writer.println("没有可复制的上一条回复。");
            writer.flush();
            return 0;
        }
        TerminalClipboardSupport.copy(writer, lastReply);
        writer.println("已复制上一条回复到终端剪贴板。");
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
     * 创建终端。
     *
     * @return 返回创建好的终端。
     */
    private Terminal newTerminal() throws Exception {
        Terminal terminal =
                TerminalBuilder.builder()
                        .system(true)
                        .jansi(true)
                        .dumb(true)
                        .encoding(StandardCharsets.UTF_8)
                        .build();
        TerminalDimensionSupport.sanitize(terminal);
        return terminal;
    }

    /** 执行configureWindowsUtf8相关逻辑。 */
    private void configureWindowsUtf8() {
        String os = StrUtil.nullToEmpty(System.getProperty("os.name")).toLowerCase();
        if (!os.contains("win")) {
            return;
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "chcp", "65001").start();
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Windows 终端代码页切换被中断，继续使用当前代码页");
        } catch (Exception e) {
            log.debug(
                    "Windows 终端代码页切换失败，继续使用当前代码页 error={}",
                    e.getClass().getSimpleName());
        }
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
     * 渲染关闭摘要。
     *
     * @param writer writer 参数。
     * @param sessionId 当前会话标识。
     * @param taskRunner 任务Runner参数。
     */
    private void renderShutdownSummary(
            PrintWriter writer, String sessionId, LocalTerminalTaskRunner taskRunner) {
        writer.println(
                TerminalLifecycleSummary.render(
                        sessionId,
                        taskRunner,
                        transcript,
                        lastEventSnapshot,
                        StrUtil.isNotBlank(lastReply)));
        writer.flush();
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
