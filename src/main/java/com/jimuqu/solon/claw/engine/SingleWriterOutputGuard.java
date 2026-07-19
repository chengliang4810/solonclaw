package com.jimuqu.solon.claw.engine;

import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import java.util.Map;

/** 仅允许当前有效 Agent run 向界面和渠道写入增量事件。 */
public final class SingleWriterOutputGuard {
    /** 工具类不允许实例化。 */
    private SingleWriterOutputGuard() {}

    /**
     * 为界面事件接收器增加当前写者校验。
     *
     * @param delegate 原始事件接收器。
     * @param writeGate 当前 run 的原子写入门。
     * @return 带单写者保护的事件接收器。
     */
    public static ConversationEventSink events(
            ConversationEventSink delegate, WriteGate writeGate) {
        return new GuardedEventSink(delegate, writeGate);
    }

    /**
     * 为渠道反馈接收器增加当前写者校验。
     *
     * @param delegate 原始反馈接收器。
     * @param writeGate 当前 run 的原子写入门。
     * @return 带单写者保护的反馈接收器。
     */
    public static ConversationFeedbackSink feedback(
            ConversationFeedbackSink delegate, WriteGate writeGate) {
        return new GuardedFeedbackSink(delegate, writeGate);
    }

    /** 在写者所有权检查与实际输出之间提供原子边界。 */
    public interface WriteGate {
        /**
         * 当前写者仍有效时执行一次输出。
         *
         * @param output 待执行的界面或渠道输出。
         */
        void write(Runnable output);
    }

    /** 受单写者保护的界面事件接收器。 */
    private static final class GuardedEventSink implements ConversationEventSink {
        /** 原始事件接收器。 */
        private final ConversationEventSink delegate;

        /** 当前 run 的原子写入门。 */
        private final WriteGate writeGate;

        /** 创建受保护的界面事件接收器。 */
        private GuardedEventSink(ConversationEventSink delegate, WriteGate writeGate) {
            this.delegate = delegate == null ? ConversationEventSink.noop() : delegate;
            this.writeGate = writeGate;
        }

        /** 通过当前 run 的原子写入门执行输出。 */
        private void write(Runnable output) {
            if (writeGate == null) {
                output.run();
                return;
            }
            writeGate.write(output);
        }

        /** 转发运行开始事件。 */
        @Override
        public void onRunStarted(String sessionId) {
            write(() -> delegate.onRunStarted(sessionId));
        }

        /** 转发模型尝试开始事件。 */
        @Override
        public void onAttemptStarted(String runId, int attemptNo, String provider, String model) {
            write(() -> delegate.onAttemptStarted(runId, attemptNo, provider, model));
        }

        /** 转发模型尝试完成事件。 */
        @Override
        public void onAttemptCompleted(String runId, int attemptNo, String status, String reason) {
            write(() -> delegate.onAttemptCompleted(runId, attemptNo, status, reason));
        }

        /** 转发上下文压缩决策。 */
        @Override
        public void onCompressionDecision(
                String runId,
                boolean compressed,
                String reason,
                int estimatedTokens,
                int thresholdTokens) {
            write(
                    () ->
                            delegate.onCompressionDecision(
                                    runId, compressed, reason, estimatedTokens, thresholdTokens));
        }

        /** 转发恢复开始事件。 */
        @Override
        public void onRecoveryStarted(String runId, String recoveryType) {
            write(() -> delegate.onRecoveryStarted(runId, recoveryType));
        }

        /** 转发模型故障切换事件。 */
        @Override
        public void onFallback(
                String runId,
                String fromProvider,
                String toProvider,
                String toModel,
                String reason) {
            write(() -> delegate.onFallback(runId, fromProvider, toProvider, toModel, reason));
        }

        /** 转发投递状态事件。 */
        @Override
        public void onDeliveryEvent(String runId, String status, String detail) {
            write(() -> delegate.onDeliveryEvent(runId, status, detail));
        }

        /** 转发助手文本增量。 */
        @Override
        public void onAssistantDelta(String delta) {
            write(() -> delegate.onAssistantDelta(delta));
        }

        /** 转发语义进度说明。 */
        @Override
        public void onProgressUpdate(String text) {
            write(() -> delegate.onProgressUpdate(text));
        }

        /** 转发候选回答撤销事件。 */
        @Override
        public void onAssistantReset(String reason) {
            write(() -> delegate.onAssistantReset(reason));
        }

        /** 转发推理文本增量。 */
        @Override
        public void onReasoningDelta(String delta) {
            write(() -> delegate.onReasoningDelta(delta));
        }

        /** 转发工具开始事件。 */
        @Override
        public void onToolStarted(String toolName, Map<String, Object> args) {
            write(() -> delegate.onToolStarted(toolName, args));
        }

        /** 转发工具完成事件。 */
        @Override
        public void onToolCompleted(String toolName, String result, String error, long durationMs) {
            write(() -> delegate.onToolCompleted(toolName, result, error, durationMs));
        }

        /** 转发运行完成事件。 */
        @Override
        public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
            write(() -> delegate.onRunCompleted(sessionId, finalReply, result));
        }

        /** 转发运行失败事件。 */
        @Override
        public void onRunFailed(String sessionId, Throwable error) {
            write(() -> delegate.onRunFailed(sessionId, error));
        }
    }

    /** 受单写者保护的渠道反馈接收器。 */
    private static final class GuardedFeedbackSink implements ConversationFeedbackSink {
        /** 原始反馈接收器。 */
        private final ConversationFeedbackSink delegate;

        /** 当前 run 的原子写入门。 */
        private final WriteGate writeGate;

        /** 创建受保护的渠道反馈接收器。 */
        private GuardedFeedbackSink(ConversationFeedbackSink delegate, WriteGate writeGate) {
            this.delegate = delegate == null ? ConversationFeedbackSink.noop() : delegate;
            this.writeGate = writeGate;
        }

        /** 通过当前 run 的原子写入门执行输出。 */
        private void write(Runnable output) {
            if (writeGate == null) {
                output.run();
                return;
            }
            writeGate.write(output);
        }

        /** 转发工具开始反馈。 */
        @Override
        public void onToolStarted(String toolName, Map<String, Object> args) {
            write(() -> delegate.onToolStarted(toolName, args));
        }

        /** 转发工具完成反馈。 */
        @Override
        public void onToolFinished(String toolName, String result, long durationMs) {
            write(() -> delegate.onToolFinished(toolName, result, durationMs));
        }

        /** 转发推理反馈。 */
        @Override
        public void onReasoning(String thought) {
            write(() -> delegate.onReasoning(thought));
        }

        /** 转发语义进度反馈。 */
        @Override
        public void onProgressUpdate(String text) {
            write(() -> delegate.onProgressUpdate(text));
        }

        /** 转发最终回复反馈。 */
        @Override
        public void onFinalReply(String finalReply) {
            write(() -> delegate.onFinalReply(finalReply));
        }
    }
}
