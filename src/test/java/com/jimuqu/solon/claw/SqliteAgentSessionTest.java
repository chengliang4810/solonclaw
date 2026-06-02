package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.engine.PendingSessionRecoveryService;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;

public class SqliteAgentSessionTest {
    @Test
    void shouldPersistMessagesAndFlowSnapshotIntoSqlite() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-a:user-a");

        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.addMessage(
                Arrays.asList(ChatMessage.ofUser("你好"), ChatMessage.ofAssistant("收到")));
        agentSession.getContext().put("flag", "demo");
        agentSession.pending(true, "need-review");
        agentSession.updateSnapshot();

        SessionRecord reloaded = env.sessionRepository.findById(session.getSessionId());
        assertThat(reloaded.getNdjson()).contains("你好");
        assertThat(reloaded.getNdjson()).contains("收到");
        assertThat(reloaded.getAgentSnapshotJson()).isNotBlank();

        SqliteAgentSession restored = new SqliteAgentSession(reloaded);
        assertThat(restored.getMessages()).hasSize(2);
        assertThat(restored.getContext().<String>getAs("flag")).isEqualTo("demo");
        assertThat(restored.isPending()).isTrue();
        assertThat(restored.getPendingReason()).isEqualTo("need-review");
        assertThat(restored.getPendingMarkedAt()).isGreaterThan(0L);
        assertThat(restored.getPendingClearedAt()).isZero();
        assertThat(restored.getPendingLastReason()).isEqualTo("need-review");
    }

    @Test
    void shouldPersistPendingClearMetadataForResumeVisibility() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-clear:user");

        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.pending(true, "restart_timeout");
        agentSession.updateSnapshot();
        long markedAt = agentSession.getPendingMarkedAt();

        SqliteAgentSession restored =
                new SqliteAgentSession(
                        env.sessionRepository.findById(session.getSessionId()), env.sessionRepository);
        restored.pending(false, null);
        restored.updateSnapshot();

        SqliteAgentSession cleared =
                new SqliteAgentSession(env.sessionRepository.findById(session.getSessionId()));
        assertThat(cleared.isPending()).isFalse();
        assertThat(cleared.getPendingReason()).isNull();
        assertThat(cleared.getPendingMarkedAt()).isEqualTo(markedAt);
        assertThat(cleared.getPendingClearedAt()).isGreaterThanOrEqualTo(markedAt);
        assertThat(cleared.getPendingLastReason()).isEqualTo("restart_timeout");
    }

    @Test
    void shouldListAndAutoResumeFreshPendingSessionsOnStartup() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord fresh =
                env.sessionRepository.bindNewSession("MEMORY:fresh-pending-room:user");
        SqliteAgentSession freshAgentSession =
                new SqliteAgentSession(fresh, env.sessionRepository);
        freshAgentSession.addMessage(Arrays.asList(ChatMessage.ofUser("启动前中断的审批任务")));
        freshAgentSession.pending(true, "restart_interrupted");
        freshAgentSession.updateSnapshot();

        SessionRecord stale =
                env.sessionRepository.bindNewSession("MEMORY:stale-pending-room:user");
        SqliteAgentSession staleAgentSession =
                new SqliteAgentSession(stale, env.sessionRepository);
        staleAgentSession.addMessage(Arrays.asList(ChatMessage.ofUser("过期的审批任务")));
        staleAgentSession.pending(true, "restart_interrupted");
        staleAgentSession.updateSnapshot();
        stale = env.sessionRepository.findById(stale.getSessionId());
        stale.setUpdatedAt(System.currentTimeMillis() - 120_000L);
        env.sessionRepository.save(stale);

        SessionRecord approval =
                env.sessionRepository.bindNewSession("MEMORY:approval-pending-room:user");
        SqliteAgentSession approvalAgentSession =
                new SqliteAgentSession(approval, env.sessionRepository);
        approvalAgentSession.addMessage(Arrays.asList(ChatMessage.ofUser("等待人工审批的任务")));
        approvalAgentSession.pending(true, "need-review");
        approvalAgentSession.updateSnapshot();

        env.appConfig.getTask().setStaleAfterMinutes(1);
        fresh = env.sessionRepository.findById(fresh.getSessionId());
        approval = env.sessionRepository.findById(approval.getSessionId());

        assertThat(env.sessionRepository.listPendingAgentSessions(System.currentTimeMillis() - 60_000L, 10))
                .extracting(SessionRecord::getSessionId)
                .contains(fresh.getSessionId())
                .contains(approval.getSessionId())
                .doesNotContain(stale.getSessionId());

        PendingSessionRecoveryService recoveryService =
                new PendingSessionRecoveryService(
                        env.appConfig, env.sessionRepository, env.conversationOrchestrator);
        assertThat(recoveryService.recoverRecentPendingSessions()).isEqualTo(1);

        SessionRecord recovered = env.sessionRepository.findById(fresh.getSessionId());
        SqliteAgentSession recoveredAgentSession = new SqliteAgentSession(recovered);
        assertThat(recoveredAgentSession.isPending()).isFalse();
        assertThat(recovered.getNdjson()).contains("echo:resume");

        SessionRecord stillStale = env.sessionRepository.findById(stale.getSessionId());
        assertThat(new SqliteAgentSession(stillStale).isPending()).isTrue();

        SessionRecord stillApproval = env.sessionRepository.findById(approval.getSessionId());
        assertThat(new SqliteAgentSession(stillApproval).isPending()).isTrue();
        assertThat(stillApproval.getNdjson()).doesNotContain("echo:resume");
    }

    @Test
    void shouldAddGatewayInterruptionNoteWhenResumingRestartPendingSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:restart-note-room:user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.addMessage(Arrays.asList(ChatMessage.ofUser("继续启动前的任务")));
        agentSession.pending(true, "restart_timeout");
        agentSession.updateSnapshot();

        GatewayReply reply =
                env.conversationOrchestrator.resumePending("MEMORY:restart-note-room:user");

        assertThat(reply.getContent()).contains("echo:resume");
        assertThat(((FakeLlmGateway) env.llmGateway).lastSystemPrompt)
                .contains("上一轮执行被网关重启打断")
                .contains("尚未处理完的工具结果");
        assertThat(new SqliteAgentSession(env.sessionRepository.findById(session.getSessionId()))
                        .isPending())
                .isFalse();
    }

    @Test
    void shouldResumeSpecifiedPendingSessionEvenWhenCurrentBindingMoved() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:moved-pending-room:user";
        SessionRecord pending = env.sessionRepository.bindNewSession(sourceKey);
        SqliteAgentSession pendingAgentSession =
                new SqliteAgentSession(pending, env.sessionRepository);
        pendingAgentSession.addMessage(Arrays.asList(ChatMessage.ofUser("恢复旧的 pending 会话")));
        pendingAgentSession.pending(true, "restart_interrupted");
        pendingAgentSession.updateSnapshot();

        SessionRecord rebound = env.sessionRepository.bindNewSession(sourceKey);
        rebound.setTitle("current-session");
        env.sessionRepository.save(rebound);

        GatewayReply reply =
                env.conversationOrchestrator.resumePending(sourceKey, pending.getSessionId());

        assertThat(reply.getSessionId()).isEqualTo(pending.getSessionId());
        assertThat(reply.getContent()).contains("echo:resume");
        assertThat(new SqliteAgentSession(env.sessionRepository.findById(pending.getSessionId()))
                        .isPending())
                .isFalse();
        assertThat(new SqliteAgentSession(env.sessionRepository.findById(rebound.getSessionId()))
                        .isPending())
                .isFalse();
        assertThat(env.sessionRepository.getBoundSession(sourceKey).getSessionId())
                .isEqualTo(rebound.getSessionId());
    }

    @Test
    void shouldNotAddGatewayInterruptionNoteWhenResumingApprovalPendingSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:approval-note-room:user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.addMessage(Arrays.asList(ChatMessage.ofUser("等待审批后继续")));
        agentSession.pending(true, "need-review");
        agentSession.updateSnapshot();

        GatewayReply reply =
                env.conversationOrchestrator.resumePending("MEMORY:approval-note-room:user");

        assertThat(reply.getContent()).contains("echo:resume");
        assertThat(((FakeLlmGateway) env.llmGateway).lastSystemPrompt)
                .doesNotContain("上一轮执行被网关");
    }

    @Test
    void shouldRejectPendingResumeForDifferentSourceBinding() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:source-a:user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        agentSession.addMessage(Arrays.asList(ChatMessage.ofUser("只允许原 source 恢复")));
        agentSession.pending(true, "restart_timeout");
        agentSession.updateSnapshot();

        GatewayReply reply =
                env.conversationOrchestrator.resumePending(
                        "MEMORY:source-b:user", session.getSessionId());

        assertThat(reply.isError()).isTrue();
        assertThat(reply.getContent()).contains("不是当前来源键下可恢复的 pending 会话");
        assertThat(new SqliteAgentSession(env.sessionRepository.findById(session.getSessionId()))
                        .isPending())
                .isTrue();
    }

    @Test
    void shouldRestoreStopLoopHistoryAsLinkedList() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-b:user-b");

        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        ReActTrace trace = new ReActTrace();
        trace.setExtra("stoploop_history", new ArrayList<String>(Arrays.asList("first", "second")));
        agentSession.getContext().put("trace-1", trace);
        agentSession.updateSnapshot();

        SessionRecord reloaded = env.sessionRepository.findById(session.getSessionId());
        SqliteAgentSession restored = new SqliteAgentSession(reloaded);
        ReActTrace restoredTrace = restored.getContext().getAs("trace-1");

        assertThat(restoredTrace.getExtra("stoploop_history")).isInstanceOf(LinkedList.class);
    }
}
