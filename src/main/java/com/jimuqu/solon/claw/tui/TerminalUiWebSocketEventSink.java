package com.jimuqu.solon.claw.tui;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.net.websocket.WebSocket;

/** 将 Agent 运行事件转换为终端 UI WebSocket JSON 事件。 */
public class TerminalUiWebSocketEventSink implements ConversationEventSink {
    /** assistant 增量在终端 UI RPC 模式下的最小合并长度，避免单 token 高频刷新。 */
    private static final int ASSISTANT_DELTA_BATCH_CHARS = 96;
    /** reasoning 增量在终端 UI RPC 模式下的最小合并长度，避免思考区被短片段刷屏。 */
    private static final int REASONING_DELTA_BATCH_CHARS = 160;
    /** 后端到终端 UI 的 WebSocket 连接。 */
    private final WebSocket socket;
    /** 是否使用原 TUI 兼容的 JSON-RPC event 信封。 */
    private final boolean rpcEnvelope;
    /** JSON-RPC 模式下待发送的 assistant 小片段缓冲。 */
    private final StringBuilder assistantDeltaBuffer = new StringBuilder();
    /** JSON-RPC 模式下待发送的 reasoning 小片段缓冲。 */
    private final StringBuilder reasoningDeltaBuffer = new StringBuilder();
    /** JSON-RPC 模式下兜底拆分 assistant delta 中混入的 <think> 内容。 */
    private final ThinkTagSplitter thinkTagSplitter = new ThinkTagSplitter();
    /** 是否已经发送过模型增量，用于避免重复输出最终回复。 */
    private boolean assistantDeltaSent;
    /** JSON-RPC 模式下当前运行关联的会话 ID，用于为增量事件补齐归属。 */
    private String activeSessionId;
    /** JSON-RPC 模式下已经发送过 message.start，避免直接命令回复缺少开始事件。 */
    private boolean messageStarted;
    /** 是否已经发送完成事件，用于避免 listener 兜底逻辑在完成后追加尾巴。 */
    private boolean runCompleted;
    /** 工具名称到前端工具 ID 的映射，保证 tool.start 与 tool.complete 可以成对闭合。 */
    private final Map<String, String> activeToolIds = new HashMap<String, String>();

    /** 创建 WebSocket 事件输出器。 */
    public TerminalUiWebSocketEventSink(WebSocket socket) {
        this(socket, false);
    }

    /** 创建可选择 JSON-RPC event 信封的 WebSocket 事件输出器。 */
    public TerminalUiWebSocketEventSink(WebSocket socket, boolean rpcEnvelope) {
        this.socket = socket;
        this.rpcEnvelope = rpcEnvelope;
    }

    /** 通知终端 UI 会话运行开始。 */
    @Override
    public void onRunStarted(String sessionId) {
        activeSessionId = sessionId;
        messageStarted = true;
        if (rpcEnvelope) {
            send("message.start", pair("session_id", sessionId), sessionId);
            send("status.update", pair("text", "running..."), sessionId);
            return;
        }
        send("run.started", pair("session_id", sessionId));
    }

    /** 通知终端 UI 当前模型尝试开始，用原 TUI 的状态栏事件承载运行阶段。 */
    @Override
    public void onAttemptStarted(String runId, int attemptNo, String provider, String model) {
        if (rpcEnvelope) {
            sendStatus(
                    "model "
                            + attemptNo
                            + ": "
                            + safe(provider)
                            + "/"
                            + safe(model),
                    "status");
        }
    }

    /** 通知终端 UI 当前模型尝试结束，失败和空回复会落到活动区便于用户感知。 */
    @Override
    public void onAttemptCompleted(String runId, int attemptNo, String status, String reason) {
        if (!rpcEnvelope) {
            return;
        }
        String normalized = safe(status).toLowerCase(java.util.Locale.ROOT);
        if ("success".equals(normalized)) {
            sendStatus("model done", "status");
            return;
        }
        String text =
                "attempt "
                        + attemptNo
                        + " "
                        + safe(status)
                        + detail(reason);
        sendStatus(text, "warn");
    }

    /** 通知终端 UI 上下文压缩决策，压缩实际发生时保留一条系统提示。 */
    @Override
    public void onCompressionDecision(
            String runId,
            boolean compressed,
            String reason,
            int estimatedTokens,
            int thresholdTokens) {
        if (!rpcEnvelope) {
            return;
        }
        String text =
                compressed
                        ? "context compressed"
                        : "context ok "
                                + Math.max(0, estimatedTokens)
                                + "/"
                                + Math.max(0, thresholdTokens);
        sendStatus(text + detail(reason), compressed ? "compressing" : "status");
    }

    /** 通知终端 UI 当前运行进入恢复阶段，例如空回复恢复或最大步数总结。 */
    @Override
    public void onRecoveryStarted(String runId, String recoveryType) {
        if (rpcEnvelope) {
            sendStatus("recovery: " + safe(recoveryType), "warn");
        }
    }

    /** 通知终端 UI 模型提供方 fallback，保持和原 TUI 活动区的警告语义一致。 */
    @Override
    public void onFallback(String runId, String fromProvider, String toProvider, String reason) {
        if (rpcEnvelope) {
            sendStatus(
                    "fallback "
                            + safe(fromProvider)
                            + " -> "
                            + safe(toProvider)
                            + detail(reason),
                    "warn");
        }
    }

    /** 通知终端 UI 消息投递状态，失败投递展示为错误活动项。 */
    @Override
    public void onDeliveryEvent(String runId, String status, String detail) {
        if (!rpcEnvelope) {
            return;
        }
        String normalized = safe(status).toLowerCase(java.util.Locale.ROOT);
        String kind =
                normalized.contains("fail") || normalized.contains("error")
                        ? "error"
                        : "status";
        sendStatus("delivery " + safe(status) + detail(detail), kind);
    }

    /** 推送助手回复增量。 */
    @Override
    public void onAssistantDelta(String delta) {
        assistantDeltaSent = true;
        ensureMessageStarted();
        if (rpcEnvelope) {
            appendJsonRpcAssistantDelta(delta);
            return;
        }
        send("assistant.delta", pair("delta", delta));
    }

    /** 推送推理过程增量。 */
    @Override
    public void onReasoningDelta(String delta) {
        ensureMessageStarted();
        if (rpcEnvelope) {
            flushAssistantDelta();
            reasoningDeltaBuffer.append(delta);
            if (reasoningDeltaBuffer.length() >= REASONING_DELTA_BATCH_CHARS) {
                flushReasoningDelta();
            }
            return;
        }
        send("reasoning.delta", pair("delta", delta));
    }

    /** 通知终端 UI 工具开始执行。 */
    @Override
    public void onToolStarted(String toolName, Map<String, Object> args) {
        if (rpcEnvelope) {
            flushPendingDeltas();
            Map<String, Object> payload = pair("name", toolName);
            payload.put("tool_id", activeToolId(toolName));
            payload.put("context", "");
            if (CollUtil.isNotEmpty(args)) {
                payload.put("args_text", ONode.serialize(args));
            }
            send("tool.start", payload, activeSessionId);
            return;
        }
        Map<String, Object> payload = pair("tool", toolName);
        payload.put("args", args);
        send("tool.started", payload);
    }

    /** 通知终端 UI 工具执行完成。 */
    @Override
    public void onToolCompleted(String toolName, String result, long durationMs) {
        if (rpcEnvelope) {
            flushPendingDeltas();
            Map<String, Object> payload = pair("name", toolName);
            payload.put("tool_id", activeToolId(toolName));
            payload.put("result_text", result);
            payload.put("duration_s", Double.valueOf(durationMs / 1000.0D));
            send("tool.complete", payload, activeSessionId);
            activeToolIds.remove(toolName);
            return;
        }
        Map<String, Object> payload = pair("tool", toolName);
        payload.put("result", result);
        payload.put("duration_ms", Long.valueOf(durationMs));
        send("tool.completed", payload);
    }

    /** 通知终端 UI 会话运行完成。 */
    @Override
    public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
        activeSessionId = sessionId;
        ensureMessageStarted();
        runCompleted = true;
        Map<String, Object> payload = pair("session_id", sessionId);
        if (rpcEnvelope) {
            flushPendingDeltas();
            payload.put("text", finalReply);
            payload.put("usage", usage(result));
            send("message.complete", payload, sessionId);
            return;
        }
        payload.put("final_reply", finalReply);
        send("run.completed", payload);
    }

    /** 通知终端 UI 会话运行失败。 */
    @Override
    public void onRunFailed(String sessionId, Throwable error) {
        activeSessionId = sessionId;
        if (rpcEnvelope) {
            flushPendingDeltas();
            send("error", pair("message", error == null ? "unknown" : error.getMessage()), sessionId);
            return;
        }
        Map<String, Object> payload = pair("session_id", sessionId);
        payload.put("error", error == null ? "unknown" : error.getMessage());
        send("run.failed", payload);
    }

    /** 发送一条终端 UI JSON 事件。 */
    void send(String type, Map<String, Object> payload) {
        send(type, payload, null);
    }

    /** 发送一条终端 UI JSON 事件，可在 JSON-RPC 模式中附带会话编号。 */
    void send(String type, Map<String, Object> payload, String sessionId) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("payload", payload == null ? new LinkedHashMap<String, Object>() : payload);
        if (ObjectUtil.isNotNull(sessionId)) {
            event.put("session_id", sessionId);
        }
        if (!rpcEnvelope) {
            socket.send(ONode.serialize(event));
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("jsonrpc", "2.0");
        frame.put("method", "event");
        frame.put("params", event);
        socket.send(ONode.serialize(frame));
    }

    /** 构造单字段事件载荷。 */
    private Map<String, Object> pair(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }

    /** 是否已经向终端 UI 发送过 assistant 增量。 */
    public boolean hasAssistantDeltaSent() {
        return assistantDeltaSent;
    }

    /** 是否已经向终端 UI 发送过当前运行的完成事件。 */
    public boolean hasRunCompleted() {
        return runCompleted;
    }

    /** 为当前工具事件生成稳定可读的前端工具编号。 */
    private String toolId(String toolName) {
        return safeToolName(toolName) + "-" + System.nanoTime();
    }

    /** 读取或创建当前工具的稳定前端 ID。 */
    private String activeToolId(String toolName) {
        String key = safeToolName(toolName);
        if (!activeToolIds.containsKey(key)) {
            activeToolIds.put(key, toolId(key));
        }
        return activeToolIds.get(key);
    }

    /** JSON-RPC 增量事件可能来自直接命令回复，这里自动补齐 message.start。 */
    private void ensureMessageStarted() {
        if (!rpcEnvelope || messageStarted) {
            return;
        }
        messageStarted = true;
        send("message.start", pair("session_id", activeSessionId), activeSessionId);
    }

    /** 发送所有尚未刷出的短增量，保证工具、完成和错误事件前文本顺序完整。 */
    private void flushPendingDeltas() {
        appendJsonRpcAssistantDelta(thinkTagSplitter.flushPending());
        flushReasoningDelta();
        flushAssistantDelta();
    }

    /** 将 JSON-RPC assistant delta 拆成 reasoning 与可见文本，避免 TUI 正文出现 <think> 标签。 */
    private void appendJsonRpcAssistantDelta(String delta) {
        if (StrUtil.isEmpty(delta)) {
            return;
        }
        ThinkTagSplitter.Delta split = thinkTagSplitter.accept(delta);
        if (split.reasoning.length() > 0) {
            reasoningDeltaBuffer.append(split.reasoning);
            if (reasoningDeltaBuffer.length() >= REASONING_DELTA_BATCH_CHARS) {
                flushReasoningDelta();
            }
        }
        if (split.visible.length() > 0) {
            flushReasoningDelta();
            assistantDeltaBuffer.append(split.visible);
            if (assistantDeltaBuffer.length() >= ASSISTANT_DELTA_BATCH_CHARS) {
                flushAssistantDelta();
            }
        }
    }

    /** 刷出 assistant 增量缓冲，终端 UI 会继续按原逻辑合并为当前回复。 */
    private void flushAssistantDelta() {
        if (assistantDeltaBuffer.length() == 0) {
            return;
        }
        String text = assistantDeltaBuffer.toString();
        assistantDeltaBuffer.setLength(0);
        send("message.delta", pair("text", text), activeSessionId);
    }

    /** 刷出 reasoning 增量缓冲，并保留 verbose 标记让前端按思考区渲染。 */
    private void flushReasoningDelta() {
        if (reasoningDeltaBuffer.length() == 0) {
            return;
        }
        String text = reasoningDeltaBuffer.toString();
        reasoningDeltaBuffer.setLength(0);
        Map<String, Object> payload = pair("text", text);
        payload.put("verbose", Boolean.TRUE);
        send("reasoning.delta", payload, activeSessionId);
    }

    /** 发送原 TUI 识别的状态栏事件，并按 kind 决定是否进入活动区。 */
    private void sendStatus(String text, String kind) {
        Map<String, Object> payload = pair("text", text);
        payload.put("kind", kind);
        send("status.update", payload, activeSessionId);
    }

    /** 构造可展示的非空短文本，避免状态栏出现 null。 */
    private String safe(String value) {
        return StrUtil.isBlank(value) ? "-" : value.trim();
    }

    /** 构造状态补充说明，空原因不展示尾随分隔符。 */
    private String detail(String value) {
        String safe = safe(value);
        return "-".equals(safe) ? "" : ": " + safe;
    }

    /** 将模型用量转换为原 TUI 可识别的 usage 结构。 */
    private Map<String, Object> usage(LlmResult result) {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("calls", Long.valueOf(result == null ? 0L : Math.max(0L, result.getRequestCount())));
        usage.put("input", Long.valueOf(result == null ? 0L : Math.max(0L, result.getInputTokens())));
        usage.put("output", Long.valueOf(result == null ? 0L : Math.max(0L, result.getOutputTokens())));
        usage.put("reasoning", Long.valueOf(result == null ? 0L : Math.max(0L, result.getReasoningTokens())));
        usage.put("cache_read", Long.valueOf(result == null ? 0L : Math.max(0L, result.getCacheReadTokens())));
        usage.put("cache_write", Long.valueOf(result == null ? 0L : Math.max(0L, result.getCacheWriteTokens())));
        long total =
                result == null
                        ? 0L
                        : Math.max(
                                Math.max(0L, result.getTotalTokens()),
                                Math.max(0L, result.getInputTokens())
                                        + Math.max(0L, result.getOutputTokens())
                                        + Math.max(0L, result.getReasoningTokens())
                                        + Math.max(0L, result.getCacheReadTokens())
                                        + Math.max(0L, result.getCacheWriteTokens()));
        usage.put("total", Long.valueOf(total));
        if (result != null) {
            usage.put("model", result.getModel());
        }
        return usage;
    }

    /**
     * 生成工具事件索引用的非空名称。
     *
     * @param toolName 原始工具名称，可能来自工具注册表或模型返回。
     * @return 可用于前端工具事件配对的非空名称。
     */
    private String safeToolName(String toolName) {
        return ObjectUtil.defaultIfNull(toolName, "tool");
    }

    /** 面向 JSON-RPC 事件的轻量 think 标签拆分器，仅处理模型输出开头或流中完整出现的 think 标签。 */
    private static class ThinkTagSplitter {
        /** 思考开始标签。 */
        private static final String THINK_OPEN = "<think>";
        /** 思考结束标签。 */
        private static final String THINK_CLOSE = "</think>";
        /** 等待判定是否为标签的短缓冲。 */
        private final StringBuilder pendingTag = new StringBuilder();
        /** 当前是否处于思考块内。 */
        private boolean thinking;

        /**
         * 接收一段 assistant delta。
         *
         * @param text 原始 assistant 增量。
         * @return 拆分后的可见文本与 reasoning 文本。
         */
        private Delta accept(String text) {
            StringBuilder visible = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (pendingTag.length() > 0 || ch == '<') {
                    pendingTag.append(ch);
                    String pending = pendingTag.toString();
                    if (THINK_OPEN.equalsIgnoreCase(pending)) {
                        thinking = true;
                        pendingTag.setLength(0);
                        continue;
                    }
                    if (THINK_CLOSE.equalsIgnoreCase(pending)) {
                        thinking = false;
                        pendingTag.setLength(0);
                        continue;
                    }
                    String lower = pending.toLowerCase(java.util.Locale.ROOT);
                    if (THINK_OPEN.startsWith(lower) || THINK_CLOSE.startsWith(lower)) {
                        continue;
                    }
                    appendCurrent(pending, visible, reasoning);
                    pendingTag.setLength(0);
                    continue;
                }
                appendCurrent(String.valueOf(ch), visible, reasoning);
            }
            return new Delta(visible.toString(), reasoning.toString());
        }

        /** 刷出无法构成 think 标签的尾部文本。 */
        private String flushPending() {
            if (pendingTag.length() == 0) {
                return "";
            }
            String text = pendingTag.toString();
            pendingTag.setLength(0);
            return text;
        }

        /** 按当前状态追加文本。 */
        private void appendCurrent(String value, StringBuilder visible, StringBuilder reasoning) {
            if (thinking) {
                reasoning.append(value);
            } else {
                visible.append(value);
            }
        }

        /** 拆分结果。 */
        private static class Delta {
            /** 可见文本。 */
            private final String visible;
            /** reasoning 文本。 */
            private final String reasoning;

            /** 创建拆分结果。 */
            private Delta(String visible, String reasoning) {
                this.visible = visible;
                this.reasoning = reasoning;
            }
        }
    }
}
