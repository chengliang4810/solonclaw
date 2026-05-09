package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
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

/** A lightweight terminal UI using JLine: status header, command hints, streaming body. */
public class TuiShell {
    private static final String[] COMMANDS =
            new String[] {
                "/help", "/new", "/retry", "/undo", "/branch", "/resume", "/status", "/usage",
                "/title", "/busy", "/model", "/reasoning", "/tools", "/skills", "/agent", "/cron", "/approve",
                "/deny", "/queue", "/steer", "/kanban", "/acp", "/restart", "/stop", "/compress",
                "/rollback", "/version", "/platforms", "/copy", "/models", "/sessions", "/session", "/history",
                "/events", "/tasks", "/attachments", "/transcript", "/tips", "/skin", "/exit",
                "/quit", "/exit!", "/quit!"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;
    private final CliAttachmentResolver attachmentResolver;
    private final AppConfig appConfig;
    private final TerminalModelPicker modelPicker;
    private final TerminalSessionBrowser sessionBrowser;
    private final TerminalHistoryViewer historyViewer;
    private final LocalTerminalTranscript transcript = new LocalTerminalTranscript();
    private TerminalSkin skin = TerminalSkin.fromEnvironment();
    private ConsoleEventSink.EventSnapshot lastEventSnapshot;
    private String lastReply;

    public TuiShell(CliRuntime cliRuntime, CliMode mode) {
        this(cliRuntime, mode, null, null, null, null);
    }

    public TuiShell(CliRuntime cliRuntime, CliMode mode, CliAttachmentResolver attachmentResolver) {
        this(cliRuntime, mode, attachmentResolver, null, null, null);
    }

    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig) {
        this(cliRuntime, mode, attachmentResolver, appConfig, null, null);
    }

    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker) {
        this(cliRuntime, mode, attachmentResolver, appConfig, modelPicker, null);
    }

    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker,
            TerminalSessionBrowser sessionBrowser) {
        this(cliRuntime, mode, attachmentResolver, appConfig, modelPicker, sessionBrowser, null);
    }

    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker,
            TerminalSessionBrowser sessionBrowser,
            TerminalHistoryViewer historyViewer) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
        this.attachmentResolver = attachmentResolver;
        this.appConfig = appConfig;
        this.modelPicker = modelPicker;
        this.sessionBrowser = sessionBrowser;
        this.historyViewer = historyViewer;
    }

    public int run() throws Exception {
        Terminal terminal =
                TerminalBuilder.builder()
                        .system(true)
                        .jansi(true)
                        .dumb(true)
                        .encoding(StandardCharsets.UTF_8)
                        .build();
        PrintWriter writer = terminal.writer();
        String sessionId = StrUtil.blankToDefault(mode.getSessionId(), "tui");
        if (StrUtil.isNotBlank(mode.getInput())) {
            renderHeader(writer, sessionId);
            return send(null, writer, sessionId, mode.getInput());
        }

        LineReader reader =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("jimuqu-agent-tui")
                        .completer(new StringsCompleter(COMMANDS))
                        .variable(LineReader.HISTORY_FILE, ".jimuqu-tui-history")
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
                        @Override
                        public void run() {
                            cliRuntime.stop(sessionId);
                        }
                    });
        }
        return 0;
    }

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
                    @Override
                    public Integer call() throws Exception {
                        return Integer.valueOf(send(taskRunner, writer, sessionId, input));
                    }
                });
    }

    private boolean shouldHandleInline(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (LocalTerminalHelp.isHelp(value)
                || "/copy".equalsIgnoreCase(value)
                || "/events".equalsIgnoreCase(value)
                || "/tasks".equalsIgnoreCase(value)
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

    private int send(LocalTerminalTaskRunner taskRunner, PrintWriter writer, String sessionId, String input)
            throws Exception {
        String trimmed = StrUtil.nullToEmpty(input).trim();
        if (LocalTerminalHelp.isHelp(trimmed)) {
            writer.println(LocalTerminalHelp.text());
            writer.flush();
            return 0;
        }
        if (TerminalSkin.isSkinCommand(trimmed)) {
            String next = trimmed.length() <= "/skin".length() ? "" : trimmed.substring("/skin".length()).trim();
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
        GatewayReply reply =
                cliRuntime.send(sessionId, resolved.getText(), resolved.getAttachments(), sink);
        String finalText = reply == null ? "" : StrUtil.nullToEmpty(reply.getContent());
        if (StrUtil.isBlank(finalText)) {
            finalText = sink.assistantText();
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

    private int copyLastReply(PrintWriter writer) {
        if (StrUtil.isBlank(lastReply)) {
            writer.println(skin.dim("没有可复制的上一条回复。"));
            writer.flush();
            return 1;
        }
        TerminalClipboardSupport.copy(writer, lastReply);
        writer.println(skin.dim("已复制上一条回复到终端剪贴板。"));
        writer.flush();
        return 0;
    }

    private CliAttachmentResolver.ResolvedInput resolveAttachments(String input) {
        if (attachmentResolver == null) {
            return new CliAttachmentResolver.ResolvedInput(
                    input, java.util.Collections.<MessageAttachment>emptyList());
        }
        return attachmentResolver.resolve(input);
    }

    private void renderHeader(PrintWriter writer, String sessionId) {
        writer.println(skin.bold("Jimuqu Agent TUI"));
        writer.println(skin.dim(statusLine(sessionId)));
        writer.println(
                skin.dim(
                        "tips: /help 命令  /tips 提示  /tasks 任务  /queue 排队  /steer 引导  /sessions 会话  /events 事件  /skin 皮肤  /copy 复制  /exit 退出"));
        writer.println(skin.dim("tip: " + TerminalTips.current(sessionId)));
        writer.println(skin.dim(TerminalShortcuts.helpLine()));
        writer.println(skin.dim(skin.border()));
        writer.flush();
    }

    private boolean isExitCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/exit".equalsIgnoreCase(value)
                || "/quit".equalsIgnoreCase(value)
                || "/exit!".equalsIgnoreCase(value)
                || "/quit!".equalsIgnoreCase(value);
    }

    private boolean isForceExitCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/exit!".equalsIgnoreCase(value) || "/quit!".equalsIgnoreCase(value);
    }

    private void renderFooter(PrintWriter writer, String sessionId, LocalTerminalTaskRunner taskRunner) {
        writer.println(skin.dim(footerLine(sessionId, taskRunner)));
    }

    private void renderShutdownSummary(PrintWriter writer, String sessionId, LocalTerminalTaskRunner taskRunner) {
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
                + StrUtil.blankToDefault(reasoning, "-");
    }

    private boolean isAttachmentPreviewCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return "/attachments".equalsIgnoreCase(value)
                || value.toLowerCase(java.util.Locale.ROOT).startsWith("/attachments ");
    }

    private String renderAttachmentPreview(String input) {
        if (attachmentResolver == null) {
            return "当前终端未启用附件解析。";
        }
        String value = StrUtil.nullToEmpty(input).trim();
        String rest = value.length() <= "/attachments".length() ? "" : value.substring("/attachments".length()).trim();
        return attachmentResolver.renderPreview(rest);
    }
}
