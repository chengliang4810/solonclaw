package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class AgentRunStopCommandTest {
    @Test
    void shouldStopActiveAgentRunForCurrentSource() throws Exception {
        SlowLlmGateway slowLlmGateway = new SlowLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        bootstrapAdmin(env);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(() -> env.send("admin-chat", "admin-user", "执行一个长任务"));

        assertThat(slowLlmGateway.started.await(2, TimeUnit.SECONDS)).isTrue();

        GatewayReply stopReply = env.send("admin-chat", "admin-user", "/stop");

        assertThat(stopReply.getContent()).contains("已请求停止当前任务");
        assertThat(stopReply.getContent()).contains("已停止后台进程");
        GatewayReply cancelledReply = running.get(3, TimeUnit.SECONDS);
        assertThat(cancelledReply.getContent()).contains("当前任务已停止");
        assertThat(slowLlmGateway.interrupted).isTrue();
        assertThat(env.agentRunControlService.isRunning("MEMORY:admin-chat:admin-user")).isFalse();

        executorService.shutdownNow();
    }

    @Test
    void shouldStopSiblingParticipantRunInSameThreadWhenAuthorized() throws Exception {
        SlowLlmGateway slowLlmGateway = new SlowLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        env.appConfig.getGateway().setAllowAllUsers(true);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(
                        () ->
                                env.gatewayService.handle(
                                        threadMessage(
                                                "thread-chat",
                                                "participant-a",
                                                "thr1",
                                                "执行一个长任务")));

        assertThat(slowLlmGateway.started.await(2, TimeUnit.SECONDS)).isTrue();

        GatewayReply stopReply =
                env.gatewayService.handle(
                        threadMessage("thread-chat", "admin-user", "thr1", "/stop"));

        assertThat(stopReply.getContent()).contains("已请求停止当前任务");
        GatewayReply cancelledReply = running.get(3, TimeUnit.SECONDS);
        assertThat(cancelledReply.getContent()).contains("当前任务已停止");
        assertThat(slowLlmGateway.interrupted).isTrue();
        assertThat(env.agentRunControlService.isRunning("MEMORY:thread-chat:thr1:participant-a"))
                .isFalse();

        executorService.shutdownNow();
    }

    @Test
    void shouldNotStopSiblingParticipantRunOutsideThread() throws Exception {
        SlowLlmGateway slowLlmGateway = new SlowLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        env.appConfig.getGateway().setAllowAllUsers(true);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(
                        () -> env.gatewayService.handle(groupMessage("group-chat", "participant-a", "执行一个长任务")));

        assertThat(slowLlmGateway.started.await(2, TimeUnit.SECONDS)).isTrue();

        GatewayReply stopReply =
                env.gatewayService.handle(groupMessage("group-chat", "admin-user", "/stop"));

        assertThat(stopReply.getContent()).contains("当前聊天没有正在执行的任务");
        assertThat(slowLlmGateway.interrupted).isFalse();
        assertThat(env.agentRunControlService.isRunning("MEMORY:group-chat:participant-a"))
                .isTrue();

        env.gatewayService.handle(groupMessage("group-chat", "participant-a", "/stop"));
        running.get(3, TimeUnit.SECONDS);
        executorService.shutdownNow();
    }

    @Test
    void shouldNotStopThreadWhenThreadIdOnlySharesPrefix() throws Exception {
        SlowLlmGateway slowLlmGateway = new SlowLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(slowLlmGateway);
        env.appConfig.getGateway().setAllowAllUsers(true);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<GatewayReply> running =
                executorService.submit(
                        () ->
                                env.gatewayService.handle(
                                        threadMessage(
                                                "thread-chat",
                                                "participant-a",
                                                "thr11",
                                                "执行一个长任务")));

        assertThat(slowLlmGateway.started.await(2, TimeUnit.SECONDS)).isTrue();

        GatewayReply stopReply =
                env.gatewayService.handle(
                        threadMessage("thread-chat", "admin-user", "thr1", "/stop"));

        assertThat(stopReply.getContent()).contains("当前聊天没有正在执行的任务");
        assertThat(slowLlmGateway.interrupted).isFalse();
        assertThat(env.agentRunControlService.isRunning("MEMORY:thread-chat:thr11:participant-a"))
                .isTrue();

        env.gatewayService.handle(threadMessage("thread-chat", "participant-a", "thr11", "/stop"));
        running.get(3, TimeUnit.SECONDS);
        executorService.shutdownNow();
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
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(agentSession)).isNotNull();

        GatewayReply stopReply = env.send("admin-chat", "admin-user", "/stop");
        assertThat(stopReply.getContent()).contains("已停止后台进程");

        SqliteAgentSession reloadedSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession("MEMORY:admin-chat:admin-user"),
                        env.sessionRepository);
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(reloadedSession)).isNull();

        GatewayReply approveReply = env.send("admin-chat", "admin-user", "/approve always");
        assertThat(approveReply.isError()).isTrue();
        assertThat(approveReply.getContent()).contains("当前没有待审批的危险命令");
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

    private com.jimuqu.solon.claw.core.model.GatewayMessage groupMessage(
            String chatId, String userId, String text) {
        com.jimuqu.solon.claw.core.model.GatewayMessage message =
                new com.jimuqu.solon.claw.core.model.GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY, chatId, userId, text);
        message.setChatType("group");
        return message;
    }

    private static class SlowLlmGateway implements LlmGateway {
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile boolean interrupted;

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            }
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects)
                throws Exception {
            return chat(session, systemPrompt, null, toolObjects);
        }
    }
}
