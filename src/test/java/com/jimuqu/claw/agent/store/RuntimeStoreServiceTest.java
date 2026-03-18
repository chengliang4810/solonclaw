package com.jimuqu.claw.agent.store;

import com.jimuqu.claw.agent.model.AgentRun;
import com.jimuqu.claw.agent.model.ChannelType;
import com.jimuqu.claw.agent.model.ConversationType;
import com.jimuqu.claw.agent.model.InboundEnvelope;
import com.jimuqu.claw.agent.model.ReplyTarget;
import com.jimuqu.claw.agent.model.RunEvent;
import com.jimuqu.claw.agent.model.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证运行时存储服务的持久化和恢复行为。
 */
class RuntimeStoreServiceTest {
    /** 临时测试目录。 */
    @TempDir
    Path tempDir;

    /**
     * 验证历史消息会按入站顺序重建。
     */
    @Test
    void loadConversationHistoryBeforeKeepsInboundOrder() {
        RuntimeStoreService store = new RuntimeStoreService(tempDir.toFile());

        InboundEnvelope first = inbound("session-a", "msg-1", "first");
        InboundEnvelope second = inbound("session-a", "msg-2", "second");

        long firstVersion = store.appendInboundConversationEvent(first);
        long secondVersion = store.appendInboundConversationEvent(second);
        store.appendAssistantConversationEvent("session-a", "run-1", "msg-1", firstVersion, "reply-first");
        store.appendAssistantConversationEvent("session-a", "run-2", "msg-2", secondVersion, "reply-second");

        List<ChatMessage> history = store.loadConversationHistoryBefore("session-a", 5L);

        assertEquals(4, history.size());
        assertEquals("first", history.get(0).getContent());
        assertEquals("reply-first", history.get(1).getContent());
        assertEquals("second", history.get(2).getContent());
        assertEquals("reply-second", history.get(3).getContent());
    }

    /**
     * 验证重启后未完成任务会被标记为中止。
     */
    @Test
    void marksIncompleteRunsAbortedOnStartup() {
        RuntimeStoreService store = new RuntimeStoreService(tempDir.toFile());
        AgentRun run = new AgentRun();
        run.setRunId("run-abort");
        run.setSessionKey("debug-web:test");
        run.setStatus(RunStatus.RUNNING);
        run.setCreatedAt(System.currentTimeMillis());
        store.saveRun(run);

        RuntimeStoreService restarted = new RuntimeStoreService(tempDir.toFile());

        AgentRun restored = restarted.getRun("run-abort");
        assertNotNull(restored);
        assertEquals(RunStatus.ABORTED, restored.getStatus());

        List<RunEvent> events = restarted.getRunEvents("run-abort", 0);
        assertEquals("aborted", events.get(events.size() - 1).getMessage());
    }

    /**
     * 验证最近外部路由会带上会话键一起保存。
     */
    @Test
    void remembersLatestExternalRouteWithSessionKey() {
        RuntimeStoreService store = new RuntimeStoreService(tempDir.toFile());
        ReplyTarget replyTarget = new ReplyTarget(ChannelType.DINGTALK, ConversationType.GROUP, "cid", "uid");

        store.rememberReplyTarget("dingtalk:group:cid", replyTarget);

        assertEquals("dingtalk:group:cid", store.getLatestExternalRoute().getSessionKey());
        assertEquals("cid", store.getLatestExternalRoute().getReplyTarget().getConversationId());
    }

    /**
     * 构造一条简化版入站消息。
     *
     * @param sessionKey 会话键
     * @param messageId 消息标识
     * @param content 文本内容
     * @return 入站消息
     */
    private InboundEnvelope inbound(String sessionKey, String messageId, String content) {
        InboundEnvelope envelope = new InboundEnvelope();
        envelope.setSessionKey(sessionKey);
        envelope.setMessageId(messageId);
        envelope.setChannelType(ChannelType.DEBUG_WEB);
        envelope.setConversationType(ConversationType.PRIVATE);
        envelope.setConversationId("conv");
        envelope.setSenderId("user");
        envelope.setContent(content);
        envelope.setReceivedAt(System.currentTimeMillis());
        return envelope;
    }
}
