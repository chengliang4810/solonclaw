package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
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

/** Jimuqu line-oriented CLI. */
public class CliShell {
    private static final String PROMPT = "\u001B[36mjimuqu>\u001B[0m ";
    private static final String[] COMMANDS =
            new String[] {
                "/help", "/new", "/retry", "/undo", "/branch", "/resume", "/status", "/usage",
                "/busy", "/model", "/reasoning", "/tools", "/skills", "/agent", "/cron", "/approve",
                "/kanban", "/deny", "/restart", "/stop", "/compress", "/rollback", "/version",
                "/platforms", "/models", "/sessions", "/session", "/copy", "/exit", "/quit"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;
    private final CliAttachmentResolver attachmentResolver;
    private final TerminalModelPicker modelPicker;
    private final TerminalSessionBrowser sessionBrowser;

    public CliShell(CliRuntime cliRuntime, CliMode mode) {
        this(cliRuntime, mode, null, null, null);
    }

    public CliShell(CliRuntime cliRuntime, CliMode mode, CliAttachmentResolver attachmentResolver) {
        this(cliRuntime, mode, attachmentResolver, null, null);
    }

    public CliShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            TerminalModelPicker modelPicker) {
        this(cliRuntime, mode, attachmentResolver, modelPicker, null);
    }

    public CliShell(
            CliRuntime cliRuntime,
            CliMode mode,
            CliAttachmentResolver attachmentResolver,
            TerminalModelPicker modelPicker,
            TerminalSessionBrowser sessionBrowser) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
        this.attachmentResolver = attachmentResolver;
        this.modelPicker = modelPicker;
        this.sessionBrowser = sessionBrowser;
    }

    public int run() throws Exception {
        configureWindowsUtf8();
        Terminal terminal = newTerminal();
        PrintWriter writer = terminal.writer();
        String sessionId = StrUtil.blankToDefault(mode.getSessionId(), "cli");
        if (StrUtil.isNotBlank(mode.getInput())) {
            return sendOnce(writer, sessionId, mode.getInput(), true);
        }

        LineReader reader =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("jimuqu-agent")
                        .completer(new StringsCompleter(COMMANDS))
                        .variable(LineReader.HISTORY_FILE, ".jimuqu-cli-history")
                        .build();
        writer.println("Jimuqu Agent CLI。输入 /help 查看命令，/exit 退出。");
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
                if ("/exit".equalsIgnoreCase(line) || "/quit".equalsIgnoreCase(line)) {
                    break;
                }
                dispatchInteractive(taskRunner, writer, sessionId, line);
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
            final String line)
            throws Exception {
        if (shouldHandleInline(line)) {
            sendOnce(writer, sessionId, line, true);
            return;
        }
        taskRunner.submit(
                line,
                new Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        return Integer.valueOf(sendOnce(writer, sessionId, line, true));
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

    private int sendOnce(PrintWriter writer, String sessionId, String input, boolean verbose)
            throws Exception {
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
        if ("/copy".equalsIgnoreCase(trimmed)) {
            return copyLastReply(writer);
        }
        ConsoleEventSink sink = new ConsoleEventSink(writer, verbose);
        CliAttachmentResolver.ResolvedInput resolved = resolveAttachments(input);
        if (!resolved.getAttachments().isEmpty()) {
            writer.println("已附加本地文件：" + resolved.getAttachments().size());
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
            writer.println(reply.getContent());
            writer.flush();
        }
        return reply != null && reply.isError() ? 1 : 0;
    }

    private String lastReply;

    private int copyLastReply(PrintWriter writer) {
        if (StrUtil.isBlank(lastReply)) {
            writer.println("没有可复制的上一条回复。");
            writer.flush();
            return 1;
        }
        TerminalClipboardSupport.copy(writer, lastReply);
        writer.println("已复制上一条回复到终端剪贴板。");
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

    private Terminal newTerminal() throws Exception {
        return TerminalBuilder.builder()
                .system(true)
                .jansi(true)
                .dumb(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
    }

    private void configureWindowsUtf8() {
        String os = StrUtil.nullToEmpty(System.getProperty("os.name")).toLowerCase();
        if (!os.contains("win")) {
            return;
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "chcp", "65001").start();
            process.waitFor();
        } catch (Exception ignored) {
            // best effort only
        }
    }
}
