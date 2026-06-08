package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 承载Console事件接收端相关状态和辅助逻辑。 */
public class ConsoleEventSink implements ConversationEventSink {
    /** RESET的统一常量值。 */
    private static final String RESET = "\u001B[0m";

    /** DIM的统一常量值。 */
    private static final String DIM = "\u001B[2m";

    /** CYAN的统一常量值。 */
    private static final String CYAN = "\u001B[36m";

    /** YELLOW的统一常量值。 */
    private static final String YELLOW = "\u001B[33m";

    /** RED的统一常量值。 */
    private static final String RED = "\u001B[31m";

    /** 记录Console事件接收端中的writer。 */
    private final PrintWriter writer;

    /** 是否启用verbose。 */
    private final boolean verbose;

    /** 记录Console事件接收端中的Markdown渲染器。 */
    private final TerminalMarkdownRenderer markdownRenderer = new TerminalMarkdownRenderer();

    /** 记录Console事件接收端中的assistant。 */
    private final StringBuilder assistant = new StringBuilder();

    /** 记录Console事件接收端中的recentEvents。 */
    private final Deque<String> recentEvents = new ArrayDeque<String>();

    /** 是否启用assistantStarted。 */
    private boolean assistantStarted;

    /** 是否启用推理Started。 */
    private boolean reasoningStarted;

    /** 记录Console事件接收端中的事件次数。 */
    private int eventCount;

    /** 记录Console事件接收端中的工具次数。 */
    private int toolCount;

    /** 记录Console事件接收端中的failure次数。 */
    private int failureCount;

    /**
     * 创建Console事件接收端实例，并注入运行所需依赖。
     *
     * @param writer writer 参数。
     * @param verbose verbose 参数。
     */
    public ConsoleEventSink(PrintWriter writer, boolean verbose) {
        this.writer = writer;
        this.verbose = verbose;
    }

    /**
     * 响应运行Started事件。
     *
     * @param sessionId 当前会话标识。
     */
    @Override
    public void onRunStarted(String sessionId) {
        sidecar("run.start", "session=" + StrUtil.blankToDefault(sessionId, "-"));
        if (verbose) {
            line(DIM + "session=" + safeDisplay(sessionId, 120) + RESET);
        }
    }

    /**
     * 响应AttemptStarted事件。
     *
     * @param runId 运行标识。
     * @param attemptNo attemptNo 参数。
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     */
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
                            + safeDisplay(provider, 120)
                            + " / "
                            + safeDisplay(model, 120)
                            + RESET);
        }
    }

    /**
     * 响应AttemptCompleted事件。
     *
     * @param runId 运行标识。
     * @param attemptNo attemptNo 参数。
     * @param status 状态参数。
     * @param reason 原因参数。
     */
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

    /**
     * 响应压缩决策事件。
     *
     * @param runId 运行标识。
     * @param compressed compressed 参数。
     * @param reason 原因参数。
     * @param estimatedTokens estimatedtoken参数。
     * @param thresholdTokens thresholdtoken参数。
     */
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

    /**
     * 响应兜底事件。
     *
     * @param runId 运行标识。
     * @param fromProvider from提供方标识或键值。
     * @param toProvider to提供方标识或键值。
     * @param reason 原因参数。
     */
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
                        + safeDisplay(fromProvider, 120)
                        + " -> "
                        + safeDisplay(toProvider, 120)
                        + RESET);
    }

    /**
     * 响应恢复Started事件。
     *
     * @param runId 运行标识。
     * @param recoveryType 恢复类型参数。
     */
    @Override
    public void onRecoveryStarted(String runId, String recoveryType) {
        sidecar("recovery.start", StrUtil.blankToDefault(recoveryType, "-"));
        if (verbose) {
            line(DIM + "正在恢复最终答复：" + safeDisplay(recoveryType, 120) + RESET);
        }
    }

    /**
     * 响应投递事件事件。
     *
     * @param runId 运行标识。
     * @param status 状态参数。
     * @param detail 详情参数。
     */
    @Override
    public void onDeliveryEvent(String runId, String status, String detail) {
        sidecar("delivery", "status=" + StrUtil.blankToDefault(status, "-") + detail(detail));
    }

    /**
     * 响应AssistantDelta事件。
     *
     * @param delta delta 参数。
     */
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

    /**
     * 响应推理Delta事件。
     *
     * @param delta delta 参数。
     */
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

    /**
     * 响应工具Started事件。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     */
    @Override
    public void onToolStarted(String toolName, Map<String, Object> args) {
        toolCount++;
        sidecar("tool.start", StrUtil.blankToDefault(toolName, "tool"));
        if (assistantStarted) {
            writer.println();
        }
        line(DIM + "tool.start " + StrUtil.blankToDefault(toolName, "tool") + RESET);
    }

    /**
     * 响应工具Completed事件。
     *
     * @param toolName 工具名称。
     * @param result 结果响应或执行结果。
     * @param durationMs durationMs 参数。
     */
    @Override
    public void onToolCompleted(String toolName, String result, long durationMs) {
        String suffix = durationMs > 0L ? " " + durationMs + "ms" : "";
        sidecar("tool.done", StrUtil.blankToDefault(toolName, "tool") + suffix);
        line(DIM + "tool.done  " + StrUtil.blankToDefault(toolName, "tool") + suffix + RESET);
    }

    /**
     * 响应运行Completed事件。
     *
     * @param sessionId 当前会话标识。
     * @param finalReply 最终回复参数。
     * @param result 结果响应或执行结果。
     */
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

    /**
     * 响应运行Failed事件。
     *
     * @param sessionId 当前会话标识。
     * @param error 错误参数。
     */
    @Override
    public void onRunFailed(String sessionId, Throwable error) {
        failureCount++;
        sidecar("run.failed", error == null ? "unknown" : error.getMessage());
        line(
                RED
                        + "运行失败："
                        + safeDisplay(error == null ? "unknown" : error.getMessage(), 600)
                        + RESET);
    }

    /**
     * 判断是否存在Assistant输出。
     *
     * @return 如果Assistant输出满足条件则返回 true，否则返回 false。
     */
    public boolean hasAssistantOutput() {
        return assistantStarted;
    }

    /**
     * 执行assistant文本相关逻辑。
     *
     * @return 返回assistant Text结果。
     */
    public String assistantText() {
        return assistant.toString();
    }

    /**
     * 执行事件Snapshot相关逻辑。
     *
     * @return 返回事件Snapshot结果。
     */
    public EventSnapshot eventSnapshot() {
        return new EventSnapshot(
                eventCount, toolCount, failureCount, new ArrayList<String>(recentEvents));
    }

    /**
     * 执行footer相关逻辑。
     *
     * @param result 结果响应或执行结果。
     * @return 返回footer结果。
     */
    String footer(LlmResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        String provider = safeDisplay(result.getProvider(), 120);
        String model = safeDisplay(result.getModel(), 120);
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

    /**
     * 追加Separator。
     *
     * @param buffer buffer 参数。
     */
    private void appendSeparator(StringBuilder buffer) {
        if (buffer.length() > 0) {
            buffer.append("  ");
        }
    }

    /**
     * 执行行相关逻辑。
     *
     * @param text 待处理文本。
     */
    private void line(String text) {
        writer.println(text);
        writer.flush();
    }

    /**
     * 执行sidecar相关逻辑。
     *
     * @param type 类型参数。
     * @param summary 摘要参数。
     */
    private void sidecar(String type, String summary) {
        eventCount++;
        String event = StrUtil.blankToDefault(type, "event");
        String text = safeDisplay(summary, 1200);
        if (StrUtil.isNotBlank(text)) {
            event += " " + trim(text, 120);
        }
        recentEvents.addLast(event);
        while (recentEvents.size() > 4) {
            recentEvents.removeFirst();
        }
    }

    /**
     * 执行sidecar摘要相关逻辑。
     *
     * @return 返回sidecar Summary结果。
     */
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

    /**
     * 执行详情相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回detail结果。
     */
    private String detail(String value) {
        String text = safeDisplay(value, 1200);
        return StrUtil.isBlank(text) ? "" : " " + trim(text, 120);
    }

    /**
     * 生成安全展示用的展示。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe展示结果。
     */
    private String safeDisplay(String value, int maxLength) {
        String text = StrUtil.nullToEmpty(value).replace('\r', ' ').replace('\n', ' ').trim();
        return SecretRedactor.redact(text, maxLength);
    }

    /**
     * 执行trim相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回trim结果。
     */
    private String trim(String value, int maxLength) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /** 承载事件快照相关状态和辅助逻辑。 */
    public static final class EventSnapshot {
        /** 记录事件快照中的事件次数。 */
        private final int eventCount;

        /** 记录事件快照中的工具次数。 */
        private final int toolCount;

        /** 记录事件快照中的failure次数。 */
        private final int failureCount;

        /** 保存recentEvents集合，维持调用顺序或去重语义。 */
        private final List<String> recentEvents;

        /**
         * 创建事件Snapshot实例，并注入运行所需依赖。
         *
         * @param eventCount 事件Count参数。
         * @param toolCount 工具Count参数。
         * @param failureCount failureCount 参数。
         * @param recentEvents recentEvents 参数。
         */
        private EventSnapshot(
                int eventCount, int toolCount, int failureCount, List<String> recentEvents) {
            this.eventCount = eventCount;
            this.toolCount = toolCount;
            this.failureCount = failureCount;
            this.recentEvents =
                    Collections.unmodifiableList(
                            recentEvents == null
                                    ? new ArrayList<String>()
                                    : new ArrayList<String>(recentEvents));
        }

        /**
         * 读取事件次数。
         *
         * @return 返回读取到的事件次数。
         */
        public int getEventCount() {
            return eventCount;
        }

        /**
         * 读取工具次数。
         *
         * @return 返回读取到的工具次数。
         */
        public int getToolCount() {
            return toolCount;
        }

        /**
         * 读取Failure次数。
         *
         * @return 返回读取到的Failure次数。
         */
        public int getFailureCount() {
            return failureCount;
        }

        /**
         * 读取Recent Events。
         *
         * @return 返回读取到的Recent Events。
         */
        public List<String> getRecentEvents() {
            return recentEvents;
        }
    }
}
