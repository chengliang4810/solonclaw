package com.jimuqu.claw.agent.runtime;

import com.jimuqu.claw.agent.channel.ChannelAdapter;
import com.jimuqu.claw.agent.channel.ChannelRegistry;
import com.jimuqu.claw.agent.model.AgentRun;
import com.jimuqu.claw.agent.model.ChannelType;
import com.jimuqu.claw.agent.model.ConversationType;
import com.jimuqu.claw.agent.model.InboundEnvelope;
import com.jimuqu.claw.agent.model.OutboundEnvelope;
import com.jimuqu.claw.agent.model.ReplyTarget;
import com.jimuqu.claw.agent.model.RunStatus;
import com.jimuqu.claw.agent.store.RuntimeStoreService;
import com.jimuqu.claw.config.SolonClawProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Agent 运行时的并发调度和忙时回执行为。
 */
class AgentRuntimeServiceTest {
    /** 临时测试目录。 */
    @TempDir
    Path tempDir;

    /**
     * 验证同会话繁忙时第二条消息会收到即时回执。
     *
     * @throws Exception 执行异常
     */
    @Test
    void secondMessageGetsImmediateAckWhenConversationBusy() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();

        ConversationAgent conversationAgent = (request, progressConsumer) -> {
            int current = invocationCount.incrementAndGet();
            progressConsumer.accept("progress-" + request.getCurrentMessage());
            if (current == 1) {
                firstStarted.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            return "reply-" + request.getCurrentMessage();
        };

        RuntimeStoreService store = new RuntimeStoreService(tempDir.toFile());
        ConversationScheduler scheduler = new ConversationScheduler(1);
        ChannelRegistry registry = new ChannelRegistry();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter();
        registry.register(adapter);

        SolonClawProperties properties = new SolonClawProperties();
        properties.getAgent().getScheduler().setMaxConcurrentPerConversation(1);
        properties.getAgent().getScheduler().setAckWhenBusy(true);

        AgentRuntimeService runtimeService = new AgentRuntimeService(conversationAgent, store, scheduler, registry, properties);

        String firstRunId = runtimeService.submitInbound(inbound("msg-1", "question-1"));
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        String secondRunId = runtimeService.submitInbound(inbound("msg-2", "question-2"));
        assertNotNull(firstRunId);
        assertNotNull(secondRunId);

        assertTrue(waitUntil(() -> adapter.messages.stream().anyMatch(message -> message.contains("已收到")), 2000));

        releaseFirst.countDown();

        assertTrue(waitUntil(() -> {
            AgentRun run1 = runtimeService.getRun(firstRunId);
            AgentRun run2 = runtimeService.getRun(secondRunId);
            return run1 != null && run2 != null
                    && run1.getStatus() == RunStatus.SUCCEEDED
                    && run2.getStatus() == RunStatus.SUCCEEDED;
        }, 5000));

        assertEquals(3, adapter.outbounds.size());
        assertEquals("reply-question-1", runtimeService.getRun(firstRunId).getFinalResponse());
        assertEquals("reply-question-2", runtimeService.getRun(secondRunId).getFinalResponse());
    }

    /**
     * 构造一条测试入站消息。
     *
     * @param messageId 消息标识
     * @param content 文本内容
     * @return 入站消息
     */
    private InboundEnvelope inbound(String messageId, String content) {
        ReplyTarget replyTarget = new ReplyTarget(ChannelType.DINGTALK, ConversationType.GROUP, "group-1", "user-1");

        InboundEnvelope envelope = new InboundEnvelope();
        envelope.setMessageId(messageId);
        envelope.setChannelType(ChannelType.DINGTALK);
        envelope.setChannelInstanceId("dingtalk-default");
        envelope.setSenderId("user-1");
        envelope.setConversationId("group-1");
        envelope.setConversationType(ConversationType.GROUP);
        envelope.setContent(content);
        envelope.setReplyTarget(replyTarget);
        envelope.setReceivedAt(System.currentTimeMillis());
        envelope.setSessionKey("dingtalk:group:group-1");
        return envelope;
    }

    /**
     * 轮询等待条件成立。
     *
     * @param condition 条件判断
     * @param timeoutMs 超时时间
     * @return 若条件成立则返回 true
     * @throws InterruptedException 线程中断异常
     */
    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /**
     * 记录测试发送内容的伪渠道适配器。
     */
    private static class RecordingChannelAdapter implements ChannelAdapter {
        /** 记录发送文本。 */
        private final List<String> messages = new CopyOnWriteArrayList<>();
        /** 记录完整出站消息。 */
        private final List<OutboundEnvelope> outbounds = new CopyOnWriteArrayList<>();

        /**
         * 返回适配器渠道类型。
         *
         * @return 钉钉渠道
         */
        @Override
        public ChannelType channelType() {
            return ChannelType.DINGTALK;
        }

        /**
         * 记录一次发送请求。
         *
         * @param outboundEnvelope 出站消息
         */
        @Override
        public void send(OutboundEnvelope outboundEnvelope) {
            outbounds.add(outboundEnvelope);
            messages.add(outboundEnvelope.getContent());
        }
    }
}
