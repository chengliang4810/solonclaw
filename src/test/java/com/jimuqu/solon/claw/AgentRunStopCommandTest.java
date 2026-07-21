package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.AgentRunCancelledException;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.BlockingLlmGateway;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class AgentRunStopCommandTest {
    @Test
    void shouldStopActiveAgentRunForCurrentSource() throws Exception {
        BlockingLlmGateway slowLlmGateway = new BlockingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        bootstrapAdmin(env);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(() -> env.send("admin-chat", "admin-user", "执行一个长任务"));

        assertThat(slowLlmGateway.awaitStarted(2, TimeUnit.SECONDS)).isTrue();

        GatewayReply stopReply = env.send("admin-chat", "admin-user", "/stop");

        assertThat(stopReply.getContent()).contains("已请求停止当前任务");
        assertThat(stopReply.getContent()).contains("已停止后台进程");
        GatewayReply cancelledReply = running.get(3, TimeUnit.SECONDS);
        assertThat(cancelledReply).as("被取消旧 run 不得绕过单写者租约补发终态，停止确认只由 /stop 新写者输出").isNull();
        assertThat(slowLlmGateway.isInterrupted()).isTrue();
        assertThat(env.agentRunControlService.isRunning("MEMORY:admin-chat:admin-user")).isFalse();

        executorService.shutdownNow();
    }

    /** 内部取消只结束旧运行，不得直接向用户发送含糊的“当前任务已停止”终态。 */
    @Test
    void shouldSuppressAmbiguousInternalCancellationReply() throws Exception {
        FakeLlmGateway cancellingGateway =
                new FakeLlmGateway() {
                    /** 仅对指定测试消息模拟内部取消，管理员初始化仍走正常模型回复。 */
                    @Override
                    public LlmResult chat(
                            SessionRecord session,
                            String systemPrompt,
                            String userMessage,
                            List<Object> toolObjects)
                            throws Exception {
                        if ("触发内部取消".equals(userMessage)) {
                            throw new AgentRunCancelledException();
                        }
                        return super.chat(session, systemPrompt, userMessage, toolObjects);
                    }
                };
        TestEnvironment env = TestEnvironment.withLlm(cancellingGateway);
        bootstrapAdmin(env);

        GatewayReply reply = env.send("admin-chat", "admin-user", "触发内部取消");

        assertThat(reply).isNull();
        assertThat(env.agentRunControlService.isRunning("MEMORY:admin-chat:admin-user")).isFalse();
    }

    /** 旧 run 失去输出权后抛 Error 不得补发失败终态，当前 run 的 Error 必须只输出一次。 */
    @Test
    void shouldIsolateStaleErrorTerminalFromCurrentWriter() throws Exception {
        ErrorSwitchingLlmGateway gateway = new ErrorSwitchingLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        env.appConfig.getTask().setBusyPolicy("interrupt");
        RecordingEventSink staleSink = new RecordingEventSink();
        RecordingEventSink successSink = new RecordingEventSink();
        RecordingEventSink currentFailureSink = new RecordingEventSink();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GatewayMessage first =
                new GatewayMessage(PlatformType.MEMORY, "single-writer", "user", "first request");
        GatewayMessage second =
                new GatewayMessage(PlatformType.MEMORY, "single-writer", "user", "second request");
        GatewayMessage third =
                new GatewayMessage(PlatformType.MEMORY, "single-writer", "user", "third request");

        Future<GatewayReply> stale =
                executor.submit(
                        () -> env.conversationOrchestrator.handleIncoming(first, staleSink));
        try {
            assertThat(gateway.firstStarted.await(2L, TimeUnit.SECONDS)).isTrue();

            GatewayReply success = env.conversationOrchestrator.handleIncoming(second, successSink);
            GatewayReply staleReply = stale.get(3L, TimeUnit.SECONDS);
            GatewayReply currentFailure =
                    env.conversationOrchestrator.handleIncoming(third, currentFailureSink);

            assertThat(success.getContent()).contains("echo:second request");
            assertThat(staleReply).isNull();
            assertThat(staleSink.failed.get()).isZero();
            assertThat(successSink.completed.get()).isEqualTo(1);
            assertThat(successSink.failed.get()).isZero();
            assertThat(currentFailure.isError()).isTrue();
            assertThat(currentFailureSink.failed.get()).isEqualTo(1);
            assertThat(currentFailureSink.completed.get()).isZero();
            assertThat(currentFailureSink.lastFailure.get())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("current owner failure");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sourceKeyShouldIsolateThreadParticipantsAndExposeDeliveryParts() {
        assertThat(threadMessage("chat-a", "user-a", "thr1", "hello").sourceKey())
                .isEqualTo("MEMORY:chat-a:thr1:user-a");
        assertThat(threadMessage("chat-a", "user-b", "thr1", "hello").sourceKey())
                .isEqualTo("MEMORY:chat-a:thr1:user-b");

        String[] parts = SourceKeySupport.split("MEMORY:chat-a:thr1:user-a");
        assertThat(parts[0]).isEqualTo("MEMORY");
        assertThat(parts[1]).isEqualTo("chat-a");
        assertThat(parts[2]).isEqualTo("user-a");
        assertThat(parts[3]).isEqualTo("thr1");
    }

    @Test
    void shouldClearDangerousPendingApprovalWhenStoppingSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        bootstrapAdmin(env);
        env.send("admin-chat", "admin-user", "准备执行危险命令");

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(agentSession))
                .isNotNull();

        GatewayReply stopReply = env.send("admin-chat", "admin-user", "/stop");
        assertThat(stopReply.getContent()).contains("已停止后台进程");

        SqliteAgentSession reloadedSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user"),
                        env.sessionRepository);
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(reloadedSession))
                .isNull();

        GatewayReply approveReply = env.send("admin-chat", "admin-user", "/approve always");
        assertThat(approveReply.isError()).isTrue();
        assertThat(approveReply.getContent()).contains("没有匹配的待审批命令");
        assertThat(env.dangerousCommandApprovalService.listAlwaysApprovals()).isEmpty();
        assertThat(env.dangerousCommandApprovalService.listSessionApprovals(reloadedSession))
                .isEmpty();
    }

    private void bootstrapAdmin(TestEnvironment env) throws Exception {
        env.send("admin-chat", "admin-user", "hello");
        env.send("admin-chat", "admin-user", "/pairing claim-admin");
    }

    private com.jimuqu.solon.claw.core.model.GatewayMessage threadMessage(
            String chatId, String userId, String threadId, String text) {
        com.jimuqu.solon.claw.core.model.GatewayMessage message =
                new com.jimuqu.solon.claw.core.model.GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY, chatId, userId, text);
        message.setChatType("group");
        message.setThreadId(threadId);
        return message;
    }

    /** 首次调用被中断后抛 Error，第二次成功，第三次由当前 owner 抛 Error 的测试网关。 */
    private static final class ErrorSwitchingLlmGateway extends FakeLlmGateway {
        /** 记录模型调用次序。 */
        private final AtomicInteger calls = new AtomicInteger();

        /** 标记首次调用已经进入阻塞区。 */
        private final CountDownLatch firstStarted = new CountDownLatch(1);

        /** 按调用次序模拟旧 owner 与当前 owner 的 Error。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    throw new AssertionError("stale owner failure", e);
                }
            }
            if (call == 2) {
                return super.chat(session, systemPrompt, userMessage, toolObjects);
            }
            throw new AssertionError("current owner failure");
        }
    }

    /** 记录可由 Dashboard 与 TUI 共用的运行完成和失败事件。 */
    private static final class RecordingEventSink implements ConversationEventSink {
        /** 完成终态次数。 */
        private final AtomicInteger completed = new AtomicInteger();

        /** 失败终态次数。 */
        private final AtomicInteger failed = new AtomicInteger();

        /** 最近一次失败原因。 */
        private final AtomicReference<Throwable> lastFailure = new AtomicReference<Throwable>();

        /** 记录一次完成终态。 */
        @Override
        public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
            completed.incrementAndGet();
        }

        /** 记录一次失败终态及其 Throwable。 */
        @Override
        public void onRunFailed(String sessionId, Throwable error) {
            failed.incrementAndGet();
            lastFailure.set(error);
        }
    }
}
