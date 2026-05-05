package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import java.io.PrintWriter;
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
    private boolean assistantStarted;
    private boolean reasoningStarted;

    public ConsoleEventSink(PrintWriter writer, boolean verbose) {
        this.writer = writer;
        this.verbose = verbose;
    }

    @Override
    public void onRunStarted(String sessionId) {
        if (verbose) {
            line(DIM + "session=" + StrUtil.blankToDefault(sessionId, "-") + RESET);
        }
    }

    @Override
    public void onAttemptStarted(String runId, int attemptNo, String provider, String model) {
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
    public void onFallback(String runId, String fromProvider, String toProvider, String reason) {
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
        if (verbose) {
            line(DIM + "正在恢复最终答复：" + StrUtil.blankToDefault(recoveryType, "-") + RESET);
        }
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
        writer.print(StrUtil.nullToEmpty(delta));
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
        if (assistantStarted) {
            writer.println();
        }
        line(DIM + "tool.start " + StrUtil.blankToDefault(toolName, "tool") + RESET);
    }

    @Override
    public void onToolCompleted(String toolName, String result, long durationMs) {
        String suffix = durationMs > 0L ? " " + durationMs + "ms" : "";
        line(DIM + "tool.done  " + StrUtil.blankToDefault(toolName, "tool") + suffix + RESET);
    }

    @Override
    public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
        if (assistantStarted) {
            writer.println();
        }
        if (verbose && result != null) {
            line(
                    DIM
                            + "usage input="
                            + result.getInputTokens()
                            + " output="
                            + result.getOutputTokens()
                            + RESET);
        }
    }

    @Override
    public void onRunFailed(String sessionId, Throwable error) {
        line(RED + "运行失败：" + (error == null ? "unknown" : error.getMessage()) + RESET);
    }

    public boolean hasAssistantOutput() {
        return assistantStarted;
    }

    private void line(String text) {
        writer.println(text);
        writer.flush();
    }
}
