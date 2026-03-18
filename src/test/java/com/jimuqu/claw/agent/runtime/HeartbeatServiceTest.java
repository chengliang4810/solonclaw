package com.jimuqu.claw.agent.runtime;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.claw.agent.channel.ChannelAdapter;
import com.jimuqu.claw.agent.channel.ChannelRegistry;
import com.jimuqu.claw.agent.model.ChannelType;
import com.jimuqu.claw.agent.model.ConversationType;
import com.jimuqu.claw.agent.model.OutboundEnvelope;
import com.jimuqu.claw.agent.model.ReplyTarget;
import com.jimuqu.claw.agent.store.RuntimeStoreService;
import com.jimuqu.claw.config.SolonClawProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证心跳服务能够把消息投递到最近外部路由。
 */
class HeartbeatServiceTest {
    /** 临时测试目录。 */
    @TempDir
    Path tempDir;

    /**
     * 验证一次心跳轮询会触发系统消息发送。
     *
     * @throws Exception 执行异常
     */
    @Test
    void tickSubmitsHeartbeatIntoLatestExternalRoute() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        FileUtil.mkdir(workspace.toFile());
        FileUtil.writeUtf8String("请汇报当前状态", workspace.resolve("HEARTBEAT.md").toFile());

        RuntimeStoreService store = new RuntimeStoreService(tempDir.resolve("runtime").toFile());
        ReplyTarget replyTarget = new ReplyTarget(ChannelType.DINGTALK, ConversationType.GROUP, "group-9", "user-9");
        store.rememberReplyTarget("dingtalk:group:group-9", replyTarget);

        ConversationAgent conversationAgent = (request, progressConsumer) -> "heartbeat:" + request.getCurrentMessage();
        ConversationScheduler scheduler = new ConversationScheduler(1);
        ChannelRegistry registry = new ChannelRegistry();
        RecordingChannelAdapter adapter = new RecordingChannelAdapter();
        registry.register(adapter);

        SolonClawProperties properties = new SolonClawProperties();
        properties.setWorkspace(workspace.toString());

        AgentRuntimeService runtimeService = new AgentRuntimeService(conversationAgent, store, scheduler, registry, properties);
        HeartbeatService heartbeatService = new HeartbeatService(runtimeService, store, properties);

        heartbeatService.tick();

        assertTrue(waitUntil(() -> adapter.messages.stream().anyMatch(message -> message.contains("heartbeat:请汇报当前状态")), 3000));
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
     * 记录测试发送消息的伪渠道适配器。
     */
    private static class RecordingChannelAdapter implements ChannelAdapter {
        /** 收到的消息列表。 */
        private final List<String> messages = new CopyOnWriteArrayList<>();

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
         * 记录发送内容。
         *
         * @param outboundEnvelope 出站消息
         */
        @Override
        public void send(OutboundEnvelope outboundEnvelope) {
            messages.add(outboundEnvelope.getContent());
        }
    }
}
