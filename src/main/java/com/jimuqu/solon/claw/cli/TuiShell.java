package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
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
                "/busy", "/model", "/tools", "/skills", "/agent", "/cron", "/approve", "/deny",
                "/kanban", "/restart", "/stop", "/compress", "/rollback", "/version", "/copy",
                "/exit"
            };

    private final CliRuntime cliRuntime;
    private final CliMode mode;
    private final CliAttachmentResolver attachmentResolver;
    private String lastReply;

    public TuiShell(CliRuntime cliRuntime, CliMode mode) {
        this(cliRuntime, mode, null);
    }

    public TuiShell(CliRuntime cliRuntime, CliMode mode, CliAttachmentResolver attachmentResolver) {
        this.cliRuntime = cliRuntime;
        this.mode = mode;
        this.attachmentResolver = attachmentResolver;
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
        String trimmed = StrUtil.nullToEmpty(input).trim();
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
        writer.println(DIM + "session=" + sessionId + "  /help 命令  /exit 退出" + RESET);
        writer.println(DIM + BORDER + RESET);
        writer.flush();
    }
}
