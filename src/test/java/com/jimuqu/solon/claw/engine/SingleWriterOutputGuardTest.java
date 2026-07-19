package com.jimuqu.solon.claw.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证单写者保护在失去写入权后会阻断增量输出。 */
public class SingleWriterOutputGuardTest {
    /** 验证事件与反馈接收器都会遵循当前 owner 判断。 */
    @Test
    void shouldBlockLateEventsAndFeedbackAfterOwnershipChanges() {
        AtomicBoolean owner = new AtomicBoolean(true);
        AtomicInteger eventCount = new AtomicInteger();
        AtomicInteger feedbackCount = new AtomicInteger();
        SingleWriterOutputGuard.WriteGate writeGate =
                output -> {
                    if (owner.get()) {
                        output.run();
                    }
                };
        ConversationEventSink events =
                SingleWriterOutputGuard.events(
                        new ConversationEventSink() {
                            @Override
                            public void onAssistantDelta(String delta) {
                                eventCount.incrementAndGet();
                            }

                            @Override
                            public void onRunCompleted(
                                    String sessionId, String finalReply, LlmResult result) {
                                eventCount.incrementAndGet();
                            }

                            /** 记录当前 owner 的失败终态，包括 Error。 */
                            @Override
                            public void onRunFailed(String sessionId, Throwable error) {
                                eventCount.incrementAndGet();
                            }
                        },
                        writeGate);
        ConversationFeedbackSink feedback =
                SingleWriterOutputGuard.feedback(
                        new ConversationFeedbackSink() {
                            @Override
                            public void onFinalReply(String finalReply) {
                                feedbackCount.incrementAndGet();
                            }
                        },
                        writeGate);

        events.onAssistantDelta("first");
        events.onRunFailed("run", new AssertionError("current owner failure"));
        feedback.onFinalReply("first");
        owner.set(false);
        events.onAssistantDelta("late");
        events.onRunCompleted("run", "reply", new LlmResult());
        events.onRunFailed("run", new AssertionError("stale owner failure"));
        feedback.onFinalReply("late");

        assertThat(eventCount.get()).isEqualTo(2);
        assertThat(feedbackCount.get()).isEqualTo(1);
    }

    /** 未配置写入门时守卫应当直通，兼容没有租约上下文的调用方。 */
    @Test
    void shouldPassThroughWhenWriteGateIsAbsent() {
        AtomicInteger eventCount = new AtomicInteger();
        AtomicInteger feedbackCount = new AtomicInteger();

        ConversationEventSink events =
                SingleWriterOutputGuard.events(
                        new ConversationEventSink() {
                            @Override
                            public void onAssistantDelta(String delta) {
                                eventCount.incrementAndGet();
                            }
                        },
                        null);
        ConversationFeedbackSink feedback =
                SingleWriterOutputGuard.feedback(
                        new ConversationFeedbackSink() {
                            @Override
                            public void onFinalReply(String finalReply) {
                                feedbackCount.incrementAndGet();
                            }
                        },
                        null);

        events.onAssistantDelta("first");
        feedback.onFinalReply("first");

        assertThat(eventCount.get()).isEqualTo(1);
        assertThat(feedbackCount.get()).isEqualTo(1);
    }
}
