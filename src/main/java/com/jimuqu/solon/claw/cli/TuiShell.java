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
                "/model", "/tools", "/skills", "/agent", "/cron", "/approve", "/deny",
                "/kanban", "/stop", "/compress", "/rollback", "/version", "/exit"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;

    public TuiShell(CliRuntime cliRuntime, CliMode mode) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
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
        while (true) {
            String input;
            try {
                input = reader.readLine(BOLD + CYAN + "你" + RESET + " > ");
            } catch (UserInterruptException e) {
                writer.println(DIM + "输入已取消。继续输入，或 /exit 退出。" + RESET);
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
            send(writer, sessionId, input);
            writer.println();
            writer.println(DIM + BORDER + RESET);
            writer.flush();
        }
        return 0;
    }

    private int send(PrintWriter writer, String sessionId, String input) throws Exception {
        ConsoleEventSink sink = new ConsoleEventSink(writer, true);
        GatewayReply reply = cliRuntime.send(sessionId, input, sink);
        if (reply != null && StrUtil.isNotBlank(reply.getContent()) && !sink.hasAssistantOutput()) {
            writer.println(BOLD + CYAN + "系统" + RESET);
            writer.println(reply.getContent());
            writer.flush();
        }
        return reply != null && reply.isError() ? 1 : 0;
    }

    private void renderHeader(PrintWriter writer, String sessionId) {
        writer.println(BOLD + "Jimuqu Agent TUI" + RESET);
        writer.println(DIM + "session=" + sessionId + "  /help 命令  /exit 退出" + RESET);
        writer.println(DIM + BORDER + RESET);
        writer.flush();
    }
}
