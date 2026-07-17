package com.jimuqu.solon.claw.gateway.feedback;

import java.util.Map;

/** 对话中间态反馈接收器。 */
public interface ConversationFeedbackSink {
    /** 默认空实现，供不需要渠道进度反馈的调用链复用。 */
    ConversationFeedbackSink NOOP = new ConversationFeedbackSink() {};

    /** 工具开始执行。 */
    default void onToolStarted(String toolName, Map<String, Object> args) {}

    /** 工具执行结束。 */
    default void onToolFinished(String toolName, String result, long durationMs) {}

    /** reasoning/thought 中间态。 */
    default void onReasoning(String thought) {}

    /**
     * 发送独立于最终回复的语义阶段说明。
     *
     * @param text 已完成安全过滤的单行阶段说明。
     */
    default void onProgressUpdate(String text) {}

    /** 本轮最终回复。 */
    default void onFinalReply(String finalReply) {}

    /** 返回空实现。 */
    static ConversationFeedbackSink noop() {
        return NOOP;
    }
}
