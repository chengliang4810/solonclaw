package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** Console renderer for streaming model and tool events. */
public class ConsoleEventSink implements ConversationEventSink {
    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final PrintWriter writer;
    private final boolean verbose;
    private final TerminalMarkdownRenderer markdownRenderer = new TerminalMarkdownRenderer();
    private final StringBuilder assistant = new StringBuilder();
    private final Deque<String> recentEvents = new ArrayDeque<String>();
    private boolean assistantStarted;
    private boolean reasoningStarted;
    private int eventCount;
    private int toolCount;
    private int failureCount;

    public ConsoleEventSink(PrintWriter writer, boolean verbose) {
        this.writer = writer;
        this.verbose = verbose;
    }

    @Override
    public void onRunStarted(String sessionId) {
        sidecar("run.start", "session=" + StrUtil.blankToDefault(sessionId, "-"));
        if (verbose) {
            line(DIM + "session=" + StrUtil.blankToDefault(sessionId, "-") + RESET);
        }
    }

    @Override
    public void onAttemptStarted(String runId, int attemptNo, String provider, String model) {
        sidecar(
                "attempt.start",
                "attempt="
                        + attemptNo
                        + " "
                        + StrUtil.blankToDefault(provider, "-")
                        + "/"
                        + StrUtil.blankToDefault(model, "-"));
        if (verbose) {
            line(
                    DIM
                            + "attempt "
                            + attemptNo
                            + ": "
                            + StrUtil.blankToDefault(provider, "-")
                            + " / "
                            + StrUtil.blankToDefault(model, "-")
                            + RESET);
        }
    }

    @Override
    public void onAttemptCompleted(String runId, int attemptNo, String status, String reason) {
        sidecar(
                "attempt.done",
                "attempt="
                        + attemptNo
                        + " status="
                        + StrUtil.blankToDefault(status, "-")
                        + detail(reason));
    }

    @Override
    public void onCompressionDecision(
            String runId,
            boolean compressed,
            String reason,
            int estimatedTokens,
            int thresholdTokens) {
        sidecar(
                "compression",
                "compressed="
                        + compressed
                        + " tokens="
                        + estimatedTokens
                        + "/"
                        + thresholdTokens
                        + detail(reason));
    }

    @Override
    public void onFallback(String runId, String fromProvider, String toProvider, String reason) {
        sidecar(
                "fallback",
                StrUtil.blankToDefault(fromProvider, "-")
                        + " -> "
                        + StrUtil.blankToDefault(toProvider, "-")
                        + detail(reason));
        line(
                YELLOW
                        + "模型切换："
                        + StrUtil.blankToDefault(fromProvider, "-")
                        + " -> "
                        + StrUtil.blankToDefault(toProvider, "-")
                        + RESET);
    }

    @Override
    public void onRecoveryStarted(String runId, String recoveryType) {
        sidecar("recovery.start", StrUtil.blankToDefault(recoveryType, "-"));
        if (verbose) {
            line(DIM + "正在恢复最终答复：" + StrUtil.blankToDefault(recoveryType, "-") + RESET);
        }
    }

    @Override
    public void onDeliveryEvent(String runId, String status, String detail) {
        sidecar(
                "delivery",
                "status=" + StrUtil.blankToDefault(status, "-") + detail(detail));
    }

    @Override
    public void onAssistantDelta(String delta) {
        if (!assistantStarted) {
            if (reasoningStarted) {
                writer.println();
            }
            writer.print(CYAN + "Assistant" + RESET + "\n");
            assistantStarted = true;
        }
        String text = StrUtil.nullToEmpty(delta);
        assistant.append(text);
        writer.print(markdownRenderer.render(text));
        writer.flush();
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (!verbose) {
            return;
        }
        if (!reasoningStarted) {
            writer.print(DIM + "Reasoning\n" + RESET);
            reasoningStarted = true;
        }
        writer.print(DIM + StrUtil.nullToEmpty(delta) + RESET);
        writer.flush();
    }

    @Override
    public void onToolStarted(String toolName, Map<String, Object> args) {
        toolCount++;
        sidecar("tool.start", StrUtil.blankToDefault(toolName, "tool"));
        if (assistantStarted) {
            writer.println();
        }
        line(DIM + "tool.start " + StrUtil.blankToDefault(toolName, "tool") + RESET);
    }

    @Override
    public void onToolCompleted(String toolName, String result, long durationMs) {
        String suffix = durationMs > 0L ? " " + durationMs + "ms" : "";
        sidecar("tool.done", StrUtil.blankToDefault(toolName, "tool") + suffix);
        line(DIM + "tool.done  " + StrUtil.blankToDefault(toolName, "tool") + suffix + RESET);
    }

    @Override
    public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
        sidecar("run.done", "session=" + StrUtil.blankToDefault(sessionId, "-"));
        if (assistantStarted) {
            writer.print(markdownRenderer.flush());
            writer.println();
        }
        String footer = footer(result);
        if (verbose && StrUtil.isNotBlank(footer)) {
            line(DIM + footer + RESET);
        }
        if (verbose) {
            String sidecar = sidecarSummary();
            if (StrUtil.isNotBlank(sidecar)) {
                line(DIM + sidecar + RESET);
            }
        }
    }

    @Override
    public void onRunFailed(String sessionId, Throwable error) {
        failureCount++;
        sidecar("run.failed", error == null ? "unknown" : error.getMessage());
        line(RED + "运行失败：" + (error == null ? "unknown" : error.getMessage()) + RESET);
    }

    public boolean hasAssistantOutput() {
        return assistantStarted;
    }

    public String assistantText() {
        return assistant.toString();
    }

    String footer(LlmResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        String provider = StrUtil.nullToEmpty(result.getProvider()).trim();
        String model = StrUtil.nullToEmpty(result.getModel()).trim();
        if (StrUtil.isNotBlank(provider) || StrUtil.isNotBlank(model)) {
            buffer.append("model=");
            buffer.append(StrUtil.blankToDefault(provider, "-"));
            buffer.append("/");
            buffer.append(StrUtil.blankToDefault(model, "-"));
        }
        long total = result.getTotalTokens();
        if (total <= 0L) {
            total =
                    result.getInputTokens()
                            + result.getOutputTokens()
                            + result.getCacheReadTokens()
                            + result.getCacheWriteTokens();
        }
        if (total > 0L || result.getInputTokens() > 0L || result.getOutputTokens() > 0L) {
            appendSeparator(buffer);
            buffer.append("tokens total=").append(total);
            buffer.append(" input=").append(result.getInputTokens());
            buffer.append(" output=").append(result.getOutputTokens());
            if (result.getReasoningTokens() > 0L) {
                buffer.append(" reasoning=").append(result.getReasoningTokens());
            }
            if (result.getCacheReadTokens() > 0L || result.getCacheWriteTokens() > 0L) {
                buffer.append(" cache_read=").append(result.getCacheReadTokens());
                buffer.append(" cache_write=").append(result.getCacheWriteTokens());
            }
        }
        return buffer.toString();
    }

    private void appendSeparator(StringBuilder buffer) {
        if (buffer.length() > 0) {
            buffer.append("  ");
        }
    }

    private void line(String text) {
        writer.println(text);
        writer.flush();
    }

    private void sidecar(String type, String summary) {
        eventCount++;
        String event = StrUtil.blankToDefault(type, "event");
        String text = StrUtil.nullToEmpty(summary).replace('\r', ' ').replace('\n', ' ').trim();
        if (StrUtil.isNotBlank(text)) {
            event += " " + trim(text, 120);
        }
        recentEvents.addLast(event);
        while (recentEvents.size() > 4) {
            recentEvents.removeFirst();
        }
    }

    private String sidecarSummary() {
        if (eventCount <= 0) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("events total=")
                .append(eventCount)
                .append(" tools=")
                .append(toolCount)
                .append(" failures=")
                .append(failureCount);
        if (!recentEvents.isEmpty()) {
            buffer.append(" recent=");
            boolean first = true;
            for (String event : recentEvents) {
                if (!first) {
                    buffer.append(" | ");
                }
                buffer.append(event);
                first = false;
            }
        }
        return buffer.toString();
    }

    private String detail(String value) {
        String text = StrUtil.nullToEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
        return StrUtil.isBlank(text) ? "" : " " + trim(text, 120);
    }

    private String trim(String value, int maxLength) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
