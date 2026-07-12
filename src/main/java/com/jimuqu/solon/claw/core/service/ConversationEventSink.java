package com.jimuqu.solon.claw.core.service;

import com.jimuqu.solon.claw.core.model.LlmResult;
import java.util.Map;

/** 面向 dashboard chat 的运行事件接收器。 */
public interface ConversationEventSink {
    /** NOOP的统一常量值。 */
    ConversationEventSink NOOP = new ConversationEventSink() {};

    /** 运行开始。 */
    default void onRunStarted(String sessionId) {}

    /**
     * 响应AttemptStarted事件。
     *
     * @param runId 运行标识。
     * @param attemptNo attemptNo 参数。
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     */
    default void onAttemptStarted(String runId, int attemptNo, String provider, String model) {}

    /**
     * 响应AttemptCompleted事件。
     *
     * @param runId 运行标识。
     * @param attemptNo attemptNo 参数。
     * @param status 状态参数。
     * @param reason 原因参数。
     */
    default void onAttemptCompleted(String runId, int attemptNo, String status, String reason) {}

    /**
     * 响应压缩决策事件。
     *
     * @param runId 运行标识。
     * @param compressed compressed 参数。
     * @param reason 原因参数。
     * @param estimatedTokens estimatedtoken参数。
     * @param thresholdTokens thresholdtoken参数。
     */
    default void onCompressionDecision(
            String runId,
            boolean compressed,
            String reason,
            int estimatedTokens,
            int thresholdTokens) {}

    /**
     * 响应恢复Started事件。
     *
     * @param runId 运行标识。
     * @param recoveryType 恢复类型参数。
     */
    default void onRecoveryStarted(String runId, String recoveryType) {}

    /**
     * 响应兜底事件。
     *
     * @param runId 运行标识。
     * @param fromProvider from提供方标识或键值。
     * @param toProvider to提供方标识或键值。
     * @param reason 原因参数。
     */
    default void onFallback(String runId, String fromProvider, String toProvider, String reason) {}

    /**
     * 响应投递事件事件。
     *
     * @param runId 运行标识。
     * @param status 状态参数。
     * @param detail 详情参数。
     */
    default void onDeliveryEvent(String runId, String status, String detail) {}

    /** assistant 文本增量。 */
    default void onAssistantDelta(String delta) {}

    /** assistant reasoning 文本增量。 */
    default void onReasoningDelta(String delta) {}

    /** 工具开始。 */
    default void onToolStarted(String toolName, Map<String, Object> args) {}

    /** 工具结束。 */
    default void onToolCompleted(String toolName, String result, long durationMs) {}

    /**
     * 工具结束并携带结构化失败原因，避免展示层从自由文本推断执行结果。
     *
     * @param toolName 工具名称。
     * @param result 工具返回内容。
     * @param error 明确的失败原因；成功时为 null。
     * @param durationMs 工具执行耗时，单位毫秒。
     */
    default void onToolCompleted(String toolName, String result, String error, long durationMs) {
        onToolCompleted(toolName, result, durationMs);
    }

    /** 运行成功完成。 */
    default void onRunCompleted(String sessionId, String finalReply, LlmResult result) {}

    /** 运行失败。 */
    default void onRunFailed(String sessionId, Throwable error) {}

    /** 返回空实现。 */
    static ConversationEventSink noop() {
        return NOOP;
    }
}
