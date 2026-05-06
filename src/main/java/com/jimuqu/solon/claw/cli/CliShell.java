package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** Hermes-style line-oriented CLI. */
public class CliShell {
    private static final String PROMPT = "\u001B[36mjimuqu>\u001B[0m ";
    private static final String[] COMMANDS =
            new String[] {
                "/help", "/new", "/retry", "/undo", "/branch", "/resume", "/status", "/usage",
                "/busy", "/model", "/reasoning", "/tools", "/skills", "/agent", "/cron", "/approve",
                "/kanban", "/deny", "/restart", "/stop", "/compress", "/rollback", "/version",
                "/platforms", "/exit", "/quit"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;

    public CliShell(CliRuntime cliRuntime, CliMode mode) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
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
        while (true) {
            String line;
            try {
                line = reader.readLine(PROMPT);
            } catch (UserInterruptException e) {
                writer.println("已取消当前输入。");
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
            sendOnce(writer, sessionId, line, true);
        }
        return 0;
    }

    private int sendOnce(PrintWriter writer, String sessionId, String input, boolean verbose)
            throws Exception {
        ConsoleEventSink sink = new ConsoleEventSink(writer, verbose);
        GatewayReply reply = cliRuntime.send(sessionId, input, sink);
        if (reply != null && StrUtil.isNotBlank(reply.getContent()) && !sink.hasAssistantOutput()) {
            writer.println(reply.getContent());
            writer.flush();
        }
        return reply != null && reply.isError() ? 1 : 0;
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
