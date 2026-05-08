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
                "/busy", "/model", "/tools", "/skills", "/agent", "/cron", "/approve", "/deny",
                "/kanban", "/restart", "/stop", "/compress", "/rollback", "/version", "/copy",
                "/models", "/sessions", "/session", "/history", "/events", "/skin", "/exit"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;
    private final CliAttachmentResolver attachmentResolver;
    private final AppConfig appConfig;
    private final TerminalModelPicker modelPicker;
    private final TerminalSessionBrowser sessionBrowser;
    private final TerminalHistoryViewer historyViewer;
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
            return send(writer, sessionId, mode.getInput());
        }

        LineReader reader =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("jimuqu-agent-tui")
                        .completer(new StringsCompleter(COMMANDS))
                        .variable(LineReader.HISTORY_FILE, ".jimuqu-tui-history")
                        .build();
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
                if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                    break;
                }
                dispatchInteractive(taskRunner, writer, sessionId, input);
                writer.println();
                writer.println(skin.dim(skin.border()));
                writer.flush();
            }
        } finally {
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
            send(writer, sessionId, input);
            return;
        }
        taskRunner.submit(
                input,
                new Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        return Integer.valueOf(send(writer, sessionId, input));
                    }
                });
    }

    private boolean shouldHandleInline(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (LocalTerminalHelp.isHelp(value)
                || "/copy".equalsIgnoreCase(value)
                || "/events".equalsIgnoreCase(value)) {
            return true;
        }
        return value.startsWith("/")
                && !"/retry".equalsIgnoreCase(value)
                && !value.toLowerCase(java.util.Locale.ROOT).startsWith("/retry ");
    }

    private int send(PrintWriter writer, String sessionId, String input) throws Exception {
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
        if ("/copy".equalsIgnoreCase(trimmed)) {
            return copyLastReply(writer);
        }
        ConsoleEventSink sink = new ConsoleEventSink(writer, true);
        CliAttachmentResolver.ResolvedInput resolved = resolveAttachments(input);
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
                        "tips: /help 命令  /sessions 浏览会话  /history 预览历史  /events 事件  /skin 皮肤  /copy 复制上一条回复  /exit 退出"));
        writer.println(skin.dim(skin.border()));
        writer.flush();
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
}
