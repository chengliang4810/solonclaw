package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
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

/** A lightweight terminal UI using JLine: status header, command hints, streaming body. */
public class TuiShell {
    private static final String BOLD = "\u001B[1m";
    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String BORDER = "────────────────────────────────────────────────────────";
    private static final String[] COMMANDS =
            new String[] {
                "/help", "/new", "/retry", "/undo", "/branch", "/resume", "/status", "/usage",
                "/busy", "/model", "/tools", "/skills", "/agent", "/cron", "/approve", "/deny",
                "/kanban", "/restart", "/stop", "/compress", "/rollback", "/version", "/copy",
                "/models", "/exit"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;
    private final CliAttachmentResolver attachmentResolver;
    private final AppConfig appConfig;
    private final TerminalModelPicker modelPicker;
    private String lastReply;

    public TuiShell(CliRuntime cliRuntime, CliMode mode) {
        this(cliRuntime, mode, null, null, null);
    }

    public TuiShell(CliRuntime cliRuntime, CliMode mode, CliAttachmentResolver attachmentResolver) {
        this(cliRuntime, mode, attachmentResolver, null, null);
    }

    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig) {
        this(cliRuntime, mode, attachmentResolver, appConfig, null);
    }

    public TuiShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            AppConfig appConfig,
            TerminalModelPicker modelPicker) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
        this.attachmentResolver = attachmentResolver;
        this.appConfig = appConfig;
        this.modelPicker = modelPicker;
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
                    input = reader.readLine(BOLD + CYAN + "你" + RESET + " > ");
                } catch (UserInterruptException e) {
                    writer.println(DIM + "输入已取消。运行中的任务可用 /stop 停止，或 /exit 退出。" + RESET);
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
                writer.println(DIM + BORDER + RESET);
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
        if (LocalTerminalHelp.isHelp(value) || "/copy".equalsIgnoreCase(value)) {
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
        if ("/copy".equalsIgnoreCase(trimmed)) {
            return copyLastReply(writer);
        }
        ConsoleEventSink sink = new ConsoleEventSink(writer, true);
        CliAttachmentResolver.ResolvedInput resolved = resolveAttachments(input);
        if (!resolved.getAttachments().isEmpty()) {
            writer.println(DIM + "已附加本地文件：" + resolved.getAttachments().size() + RESET);
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
        if (reply != null && StrUtil.isNotBlank(reply.getContent()) && !sink.hasAssistantOutput()) {
            writer.println(BOLD + CYAN + "系统" + RESET);
            writer.println(reply.getContent());
            writer.flush();
        }
        return reply != null && reply.isError() ? 1 : 0;
    }

    private int copyLastReply(PrintWriter writer) {
        if (StrUtil.isBlank(lastReply)) {
            writer.println(DIM + "没有可复制的上一条回复。" + RESET);
            writer.flush();
            return 1;
        }
        TerminalClipboardSupport.copy(writer, lastReply);
        writer.println(DIM + "已复制上一条回复到终端剪贴板。" + RESET);
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
        writer.println(BOLD + "Jimuqu Agent TUI" + RESET);
        writer.println(DIM + statusLine(sessionId) + RESET);
        writer.println(DIM + "tips: /help 命令  /copy 复制上一条回复  粘贴本地文件路径可作为附件  /exit 退出" + RESET);
        writer.println(DIM + BORDER + RESET);
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
