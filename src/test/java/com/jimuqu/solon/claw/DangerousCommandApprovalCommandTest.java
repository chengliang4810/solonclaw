package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class DangerousCommandApprovalCommandTest {
    @Test
    void shouldApproveDangerousCommandForSessionAndResume() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-1", "user-1", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-1", "user-1", "/pairing claim-admin"));

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-1:user-1");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        GatewayReply reply = env.send("room-1", "user-1", "/approve session");
        SessionRecord updated = env.sessionRepository.getBoundSession("MEMORY:room-1:user-1");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(reply.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession,
                                "execute_shell",
                                "recursive_delete",
                                "rm -rf runtime/cache"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession,
                                "execute_shell",
                                "recursive_delete",
                                "rm -rf runtime/logs"))
                .isTrue();
    }

    @Test
    void shouldPersistAlwaysApprovalPattern() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-2", "user-2", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-2", "user-2", "/pairing claim-admin"));

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-2:user-2");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        GatewayReply reply = env.send("room-2", "user-2", "/approve always");

        assertThat(reply.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.isAlwaysApproved("recursive_delete"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isAlwaysApproved(
                                "execute_shell", "recursive_delete", "rm -rf runtime/cache"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isAlwaysApproved(
                                "execute_shell", "recursive_delete", "rm -rf runtime/logs"))
                .isTrue();
    }

    @Test
    void shouldListAndApproveSelectedPendingDangerousCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-queue", "user-queue", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-queue", "user-queue", "/pairing claim-admin"));

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-queue:user-queue");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply list = env.send("room-queue", "user-queue", "/approve list");
        assertThat(list.getContent())
                .contains("pending=2")
                .contains("#1")
                .contains("#2")
                .contains("command_preview=rm -rf runtime/cache")
                .contains("scopes=once,session,always")
                .contains("expires_in=")
                .contains("expired=false");

        GatewayReply approved = env.send("room-queue", "user-queue", "/approve #2 session");
        SessionRecord updated = env.sessionRepository.getBoundSession("MEMORY:room-queue:user-queue");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(approved.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(updatedAgentSession))
                .hasSize(1);
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession).getPatternKey())
                .isEqualTo("recursive_delete");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "git_reset_hard"))
                .isTrue();
    }

    @Test
    void shouldListPendingDangerousCommandsThroughDenyCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-deny-list", "user-deny-list", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-deny-list", "user-deny-list", "/pairing claim-admin"));

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-deny-list:user-deny-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply list = env.send("room-deny-list", "user-deny-list", "/deny list");
        GatewayReply status = env.send("room-deny-list", "user-deny-list", "/deny status");

        assertThat(list.getContent())
                .contains("pending=2")
                .contains("#1")
                .contains("#2")
                .contains("recursive_delete")
                .contains("git_reset_hard");
        assertThat(status.getContent()).isEqualTo(list.getContent());
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(agentSession))
                .hasSize(2);
    }

    @Test
    void shouldRedactApproveListSensitiveText() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-redact-list", "user-redact-list", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-redact-list", "user-redact-list", "/pairing claim-admin"));

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-redact-list:user-redact-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "Security scan token=ghp_reasonsecret123",
                "rm -rf runtime/cache --token ghp_commandsecret123");

        GatewayReply list = env.send("room-redact-list", "user-redact-list", "/approve list");

        assertThat(list.getContent())
                .contains("token=***")
                .contains("command_preview=rm -rf runtime/cache --token ***")
                .doesNotContain("ghp_reasonsecret123")
                .doesNotContain("ghp_commandsecret123");
    }

    @Test
    void shouldListTirithPendingApprovalWithoutAlwaysScope() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-tirith-list", "user-tirith-list", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-tirith-list", "user-tirith-list", "/pairing claim-admin"));

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-tirith-list:user-tirith-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "tirith:shortened_url",
                "Security scan: shortened URL",
                "echo hello");

        GatewayReply list = env.send("room-tirith-list", "user-tirith-list", "/approve list");

        assertThat(list.getContent())
                .contains("pending=1")
                .contains("tirith:shortened_url")
                .contains("scopes=once,session")
                .contains("expires_in=")
                .contains("expired=false")
                .doesNotContain("scopes=once,session,always");
    }

    @Test
    void shouldApproveAllPendingDangerousCommandsAndResumeOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-all", "user-all", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-all", "user-all", "/pairing claim-admin"));

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-all:user-all");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply approved = env.send("room-all", "user-all", "/approve all session");
        SessionRecord updated = env.sessionRepository.getBoundSession("MEMORY:room-all:user-all");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(approved.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(updatedAgentSession))
                .isEmpty();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "git_reset_hard"))
                .isTrue();
    }

    @Test
    void shouldDenyAllPendingDangerousCommandsAndResumeOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-deny-all", "user-deny-all", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-deny-all", "user-deny-all", "/pairing claim-admin"));

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-deny-all:user-deny-all");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply denied = env.send("room-deny-all", "user-deny-all", "/deny all");
        SessionRecord updated =
                env.sessionRepository.getBoundSession("MEMORY:room-deny-all:user-deny-all");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(denied.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(updatedAgentSession))
                .isEmpty();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isFalse();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "git_reset_hard"))
                .isFalse();
    }

    @Test
    void shouldSkipNewRunWhenDangerousApprovalIsPending() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-4", "user-4", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-4", "user-4", "/pairing claim-admin"));

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-4:user-4");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply blocked =
                env.conversationOrchestrator.runScheduled(
                        env.message("room-4", "user-4", "执行日志巡检"));
        SessionRecord afterBlockedRun =
                env.sessionRepository.getBoundSession("MEMORY:room-4:user-4");
        SqliteAgentSession afterBlockedAgentSession =
                new SqliteAgentSession(afterBlockedRun, env.sessionRepository);

        assertThat(blocked.getContent()).contains("待审批的危险命令");
        assertThat(blocked.getContent()).contains("本次请求已跳过");
        assertThat(afterBlockedRun.getNdjson()).doesNotContain("执行日志巡检");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(afterBlockedAgentSession))
                .isNotNull();

        GatewayReply approved = env.send("room-4", "user-4", "/approve always");
        assertThat(approved.getContent()).isEqualTo("echo:resume");
        assertThat(
                        env.dangerousCommandApprovalService.isAlwaysApproved(
                                "execute_shell", "git_reset_hard", "git reset --hard origin/main"))
                .isTrue();
    }

    @Test
    void shouldReturnChineseMessageWhenApproveHasNoPendingCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-3", "user-3", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-3", "user-3", "/pairing claim-admin"));

        GatewayReply reply = env.send("room-3", "user-3", "/approve always");

        assertThat(reply.getContent()).contains("待审批的危险命令");
        assertThat(reply.getContent()).doesNotContain("???");
    }

    @Test
    void shouldToggleYoloOnlyForCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-yolo-a", "user-yolo", "hello"));
        env.gatewayService.handle(env.message("room-yolo-b", "user-yolo", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-yolo-a", "user-yolo", "/pairing claim-admin"));

        SessionRecord sessionA = env.sessionRepository.bindNewSession("MEMORY:room-yolo-a:user-yolo");
        SessionRecord sessionB = env.sessionRepository.bindNewSession("MEMORY:room-yolo-b:user-yolo");

        GatewayReply enabled = env.send("room-yolo-a", "user-yolo", "/yolo");

        SessionRecord updatedA =
                env.sessionRepository.getBoundSession("MEMORY:room-yolo-a:user-yolo");
        SessionRecord updatedB =
                env.sessionRepository.getBoundSession("MEMORY:room-yolo-b:user-yolo");
        SqliteAgentSession agentSessionA =
                new SqliteAgentSession(updatedA, env.sessionRepository);
        SqliteAgentSession agentSessionB =
                new SqliteAgentSession(updatedB, env.sessionRepository);

        assertThat(enabled.getContent()).contains("YOLO 已开启");
        assertThat(updatedA.getSessionId()).isEqualTo(sessionA.getSessionId());
        assertThat(updatedB.getSessionId()).isEqualTo(sessionB.getSessionId());
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(agentSessionA))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(agentSessionB))
                .isFalse();

        GatewayReply disabled = env.send("room-yolo-a", "user-yolo", "/yolo");
        SessionRecord disabledA =
                env.sessionRepository.getBoundSession("MEMORY:room-yolo-a:user-yolo");
        assertThat(disabled.getContent()).contains("YOLO 已关闭");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionYoloEnabled(
                                new SqliteAgentSession(disabledA, env.sessionRepository)))
                .isFalse();
    }

    @Test
    void shouldSupportExplicitYoloStatusOnOffForCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-yolo-explicit-a", "user-yolo", "hello"));
        env.gatewayService.handle(env.message("room-yolo-explicit-b", "user-yolo", "hello"));
        env.gatewayAuthorizationService.claimAdmin(
                env.message("room-yolo-explicit-a", "user-yolo", "/pairing claim-admin"));

        SessionRecord sessionA =
                env.sessionRepository.bindNewSession("MEMORY:room-yolo-explicit-a:user-yolo");
        SessionRecord sessionB =
                env.sessionRepository.bindNewSession("MEMORY:room-yolo-explicit-b:user-yolo");
        SqliteAgentSession agentSessionA =
                new SqliteAgentSession(sessionA, env.sessionRepository);
        SqliteAgentSession agentSessionB =
                new SqliteAgentSession(sessionB, env.sessionRepository);

        GatewayReply initialStatus = env.send("room-yolo-explicit-a", "user-yolo", "/yolo status");
        assertThat(initialStatus.getContent()).contains("YOLO 已关闭");
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(
                        yoloSession(env, "room-yolo-explicit-a")))
                .isFalse();

        GatewayReply enabled = env.send("room-yolo-explicit-a", "user-yolo", "/yolo on");
        GatewayReply enabledAgain = env.send("room-yolo-explicit-a", "user-yolo", "/yolo enable");
        assertThat(enabled.getContent()).contains("YOLO 已开启");
        assertThat(enabledAgain.getContent()).contains("YOLO 已开启");
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(
                        yoloSession(env, "room-yolo-explicit-a")))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(agentSessionB))
                .isFalse();

        GatewayReply status = env.send("room-yolo-explicit-a", "user-yolo", "/yolo status");
        assertThat(status.getContent()).contains("YOLO 已开启");
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(
                        yoloSession(env, "room-yolo-explicit-a")))
                .isTrue();

        GatewayReply disabled = env.send("room-yolo-explicit-a", "user-yolo", "/yolo off");
        GatewayReply disabledAgain = env.send("room-yolo-explicit-a", "user-yolo", "/yolo disable");
        assertThat(disabled.getContent()).contains("YOLO 已关闭");
        assertThat(disabledAgain.getContent()).contains("YOLO 已关闭");
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(
                        yoloSession(env, "room-yolo-explicit-a")))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.isSessionYoloEnabled(agentSessionB))
                .isFalse();

        GatewayReply invalid = env.send("room-yolo-explicit-a", "user-yolo", "/yolo maybe");
        assertThat(invalid.isError()).isTrue();
        assertThat(invalid.getContent()).contains("用法：/yolo [status|on|off]");
    }

    private SqliteAgentSession yoloSession(TestEnvironment env, String chatId) throws Exception {
        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:" + chatId + ":user-yolo");
        return new SqliteAgentSession(session, env.sessionRepository);
    }
}
