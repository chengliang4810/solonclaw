package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DangerousCommandApprovalCommandTest {
    /**
     * 创建显式开启人工审批护栏的命令测试环境，用于验证 pending 会话阻塞新请求等审批链路。
     *
     * @return 返回已开启 approval 模式的测试环境。
     */
    private static TestEnvironment approvalEnvironment() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("approval");
        return env;
    }

    /** 通过可信测试控制面设置平台管理员，使审批命令穿过 pairing 门禁。 */
    private static void authorize(TestEnvironment env, String chatId, String userId)
            throws Exception {
        env.gatewayAuthorizationService.setPlatformAdmin(
                PlatformType.MEMORY, userId, userId, chatId);
    }

    @Test
    void shouldApproveDangerousCommandForSessionAndResume() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-1", "user-1", "hello"));
        authorize(env, "room-1", "user-1");

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-1:user-1");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

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
                                "rm -rf workspace/cache"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession,
                                "execute_shell",
                                "recursive_delete",
                                "rm -rf workspace/logs"))
                .isTrue();
    }

    @Test
    void shouldStreamResumedRunAfterApprovalCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-stream", "user-stream", "hello"));
        authorize(env, "room-stream", "user-stream");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-stream:user-stream");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        RecordingEventSink sink = new RecordingEventSink();
        GatewayReply reply =
                env.commandService.handle(
                        env.message("room-stream", "user-stream", "/approve session"),
                        "/approve session",
                        sink);

        assertThat(reply.getContent()).isEqualTo("echo:resume");
        assertThat(sink.completedText).isEqualTo("echo:resume");
        assertThat(sink.completedSessionId).isEqualTo(session.getSessionId());
    }

    @Test
    void shouldPersistAlwaysApprovalPattern() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-2", "user-2", "hello"));
        authorize(env, "room-2", "user-2");

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-2:user-2");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        agentSession.pending(false, null);
        agentSession.updateSnapshot();

        GatewayReply reply = env.send("room-2", "user-2", "/approve always");

        assertThat(reply.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.isAlwaysApproved("recursive_delete"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isAlwaysApproved(
                                "execute_shell", "recursive_delete", "rm -rf workspace/cache"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isAlwaysApproved(
                                "execute_shell", "recursive_delete", "rm -rf workspace/logs"))
                .isTrue();
    }

    @Test
    void shouldListAndApproveSelectedPendingDangerousCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-queue", "user-queue", "hello"));
        authorize(env, "room-queue", "user-queue");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-queue:user-queue");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "temporary_session_pattern",
                "temporary session approval",
                "echo temp");
        env.dangerousCommandApprovalService.approve(
                agentSession,
                "",
                com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService.ApprovalScope
                        .SESSION,
                "tester");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply list = env.send("room-queue", "user-queue", "/approve list");
        DangerousCommandApprovalService.PendingApproval secondPending =
                env.dangerousCommandApprovalService.listPendingApprovals(agentSession).get(1);
        String secondApprovalKey = secondPending.approvalKey();
        String secondSelector = DangerousCommandApprovalService.approvalSelector(secondPending);
        assertThat(list.getContent())
                .contains("待审批：2 项")
                .contains("#1")
                .contains("#2")
                .contains("#2 确认编号：" + secondSelector)
                .contains("风险类型：recursive_delete")
                .contains("命令预览：rm -rf workspace/cache")
                .contains("可用审批范围：单次、当前会话、永久")
                .contains("剩余有效期：")
                .contains("当前会话已授权：1 项")
                .contains("永久授权：")
                .doesNotContain("session_approvals=[")
                .doesNotContain("always_approvals=[")
                .doesNotContain(" key=")
                .doesNotContain(secondApprovalKey);

        GatewayReply rawKeyRejected =
                env.send("room-queue", "user-queue", "/approve " + secondApprovalKey + " session");
        GatewayReply rawKeyPrefixRejected =
                env.send(
                        "room-queue",
                        "user-queue",
                        "/approve " + secondApprovalKey.substring(0, 16) + " session");
        GatewayReply shortIdPrefixRejected =
                env.send(
                        "room-queue",
                        "user-queue",
                        "/approve " + secondSelector.substring(0, 7) + " session");
        GatewayReply longIdPrefixApproved =
                env.send(
                        "room-queue",
                        "user-queue",
                        "/approve " + secondSelector.substring(0, 8) + " session");
        GatewayReply approved =
                env.send("room-queue", "user-queue", "/approve " + secondSelector + " session");
        SessionRecord updated =
                env.sessionRepository.getBoundSession("MEMORY:room-queue:user-queue");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(rawKeyRejected.isError()).isTrue();
        assertThat(rawKeyPrefixRejected.isError()).isTrue();
        assertThat(shortIdPrefixRejected.isError()).isTrue();
        assertThat(longIdPrefixApproved.getContent()).isEqualTo("echo:resume");
        assertThat(approved.isError()).isTrue();
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(updatedAgentSession))
                .hasSize(1);
        assertThat(
                        env.dangerousCommandApprovalService
                                .getPendingApproval(updatedAgentSession)
                                .getPatternKey())
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
        authorize(env, "room-deny-list", "user-deny-list");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-deny-list:user-deny-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "git_reset_hard",
                "git reset --hard (destroys uncommitted changes)",
                "git reset --hard origin/main");

        GatewayReply list = env.send("room-deny-list", "user-deny-list", "/deny list");
        GatewayReply status = env.send("room-deny-list", "user-deny-list", "/deny status");

        assertThat(list.getContent())
                .contains("待审批：2 项")
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
        authorize(env, "room-redact-list", "user-redact-list");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-redact-list:user-redact-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "Security scan token=ghp_reasonsecret123",
                "rm -rf workspace/cache --token ghp_commandsecret123");

        GatewayReply list = env.send("room-redact-list", "user-redact-list", "/approve list");

        assertThat(list.getContent())
                .contains("token=***")
                .contains("命令预览：rm -rf workspace/cache --token ***")
                .doesNotContain("ghp_reasonsecret123")
                .doesNotContain("ghp_commandsecret123");
    }

    @Test
    void shouldRedactApproveListEncodedSensitiveText() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(
                env.message("room-redact-encoded-list", "user-redact-encoded-list", "hello"));
        authorize(env, "room-redact-encoded-list", "user-redact-encoded-list");

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        "MEMORY:room-redact-encoded-list:user-redact-encoded-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "url_policy?api%255Fkey=list-secret",
                "encoded list https://example.test/callback?api%255Fkey=list-secret",
                "curl https://example.test/callback?api%255Fkey=list-secret");

        GatewayReply list =
                env.send("room-redact-encoded-list", "user-redact-encoded-list", "/approve list");

        assertThat(list.getContent())
                .contains("风险类型：url_policy?api%255Fkey=***")
                .contains("原因：encoded list https://example.test/callback?api%255Fkey=***")
                .contains("命令预览：curl https://example.test/callback?api%255Fkey=***")
                .doesNotContain("list-secret");
    }

    @Test
    void shouldStripDisplayControlsFromApprovalListAndSelector() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-control-list", "user-control-list", "hello"));
        authorize(env, "room-control-list", "user-control-list");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-control-list:user-control-list");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell\u202E",
                "recursive_delete",
                "Security scan\u202E reason",
                "rm -rf workspace/cache\u202E --token ghp_commandsecret123");

        GatewayReply list = env.send("room-control-list", "user-control-list", "/approve list");
        String approvalId =
                env.dangerousCommandApprovalService
                        .getPendingApproval(agentSession)
                        .getApprovalId();
        String disguisedApprovalId =
                approvalId.substring(0, 8) + "\u202E" + approvalId.substring(8);
        GatewayReply approved =
                env.send(
                        "room-control-list",
                        "user-control-list",
                        "/approve " + disguisedApprovalId);

        assertThat(list.getContent())
                .contains("工具：execute_shell")
                .contains("原因：Security scan reason")
                .contains("命令预览：rm -rf workspace/cache --token ***")
                .doesNotContain("\u202E")
                .doesNotContain("ghp_commandsecret123");
        assertThat(approved.getContent()).isEqualTo("echo:resume");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldShowSafeSelectorForUnsafeApprovalIdInApprovalList() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-list-selector", "user-list-selector", "hello"));
        authorize(env, "room-list-selector", "user-list-selector");

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        "MEMORY:room-list-selector:user-list-selector");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        List<Map<String, Object>> queue =
                (List<Map<String, Object>>)
                        agentSession.getContext().get("_dangerous_command_pending_queue_");
        queue.get(0).put("approvalId", "approval-unsafe always");
        agentSession.updateSnapshot();

        SqliteAgentSession restoredAgentSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession(
                                "MEMORY:room-list-selector:user-list-selector"),
                        env.sessionRepository);
        String safeSelector =
                DangerousCommandApprovalService.approvalSelector(
                        env.dangerousCommandApprovalService.getPendingApproval(
                                restoredAgentSession));
        GatewayReply list = env.send("room-list-selector", "user-list-selector", "/approve list");

        assertThat(safeSelector).startsWith("key_");
        assertThat(list.getContent()).contains("#1 确认编号：" + safeSelector);
        assertThat(list.getContent()).doesNotContain("approval-unsafe always");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldApproveUnsafeApprovalIdThroughSafeKeySelector() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-key-selector", "user-key-selector", "hello"));
        authorize(env, "room-key-selector", "user-key-selector");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-key-selector:user-key-selector");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        List<Map<String, Object>> queue =
                (List<Map<String, Object>>)
                        agentSession.getContext().get("_dangerous_command_pending_queue_");
        queue.get(0).put("approvalId", "approval-unsafe always");
        agentSession.updateSnapshot();

        SqliteAgentSession restoredAgentSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession(
                                "MEMORY:room-key-selector:user-key-selector"),
                        env.sessionRepository);
        DangerousCommandApprovalService.PendingApproval pending =
                env.dangerousCommandApprovalService.getPendingApproval(restoredAgentSession);
        String safeSelector = DangerousCommandApprovalService.approvalSelector(pending);

        assertThat(safeSelector).startsWith("key_");
        GatewayReply approved =
                env.send(
                        "room-key-selector",
                        "user-key-selector",
                        "/approve " + safeSelector + " session");
        SessionRecord updated =
                env.sessionRepository.getBoundSession("MEMORY:room-key-selector:user-key-selector");
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(approved.getContent()).isEqualTo("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHideSecretLikeApprovalIdInApprovalListAndApproveThroughSafeKeySelector()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(
                env.message("room-secret-selector", "user-secret-selector", "hello"));
        authorize(env, "room-secret-selector", "user-secret-selector");

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        "MEMORY:room-secret-selector:user-secret-selector");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        List<Map<String, Object>> queue =
                (List<Map<String, Object>>)
                        agentSession.getContext().get("_dangerous_command_pending_queue_");
        queue.get(0).put("approvalId", "approval-ghp_selectorsecret123456");
        agentSession.updateSnapshot();

        SqliteAgentSession restoredAgentSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession(
                                "MEMORY:room-secret-selector:user-secret-selector"),
                        env.sessionRepository);
        String safeSelector =
                DangerousCommandApprovalService.approvalSelector(
                        env.dangerousCommandApprovalService.getPendingApproval(
                                restoredAgentSession));

        GatewayReply list =
                env.send("room-secret-selector", "user-secret-selector", "/approve list");
        GatewayReply rawRejected =
                env.send(
                        "room-secret-selector",
                        "user-secret-selector",
                        "/approve approval-ghp_selectorsecret123456 session");
        GatewayReply prefixRejected =
                env.send(
                        "room-secret-selector",
                        "user-secret-selector",
                        "/approve approval-ghp_select session");
        GatewayReply approved =
                env.send(
                        "room-secret-selector",
                        "user-secret-selector",
                        "/approve " + safeSelector + " session");

        assertThat(safeSelector).startsWith("key_");
        assertThat(list.getContent())
                .contains("#1 确认编号：" + safeSelector)
                .doesNotContain("approval-ghp_selectorsecret123456")
                .doesNotContain("selectorsecret123456");
        assertThat(rawRejected.isError()).isTrue();
        assertThat(prefixRejected.isError()).isTrue();
        assertThat(approved.getContent()).isEqualTo("echo:resume");
        assertThat(
                        env.dangerousCommandApprovalService.getPendingApproval(
                                new SqliteAgentSession(
                                        env.sessionRepository.getBoundSession(
                                                "MEMORY:room-secret-selector:user-secret-selector"),
                                        env.sessionRepository)))
                .isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectShortKeySelectorPrefix() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(
                env.message("room-short-selector", "user-short-selector", "hello"));
        authorize(env, "room-short-selector", "user-short-selector");

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        "MEMORY:room-short-selector:user-short-selector");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        List<Map<String, Object>> queue =
                (List<Map<String, Object>>)
                        agentSession.getContext().get("_dangerous_command_pending_queue_");
        queue.get(0).put("approvalId", "approval-unsafe always");
        agentSession.updateSnapshot();

        SqliteAgentSession restoredAgentSession =
                new SqliteAgentSession(
                        env.sessionRepository.getBoundSession(
                                "MEMORY:room-short-selector:user-short-selector"),
                        env.sessionRepository);
        String safeSelector =
                DangerousCommandApprovalService.approvalSelector(
                        env.dangerousCommandApprovalService.getPendingApproval(
                                restoredAgentSession));

        GatewayReply rejected =
                env.send(
                        "room-short-selector",
                        "user-short-selector",
                        "/approve " + safeSelector.substring(0, 7) + " session");

        assertThat(rejected.isError()).isTrue();
        assertThat(rejected.getContent()).contains("没有匹配的待审批命令");
        assertThat(
                        env.dangerousCommandApprovalService.getPendingApproval(
                                new SqliteAgentSession(
                                        env.sessionRepository.getBoundSession(
                                                "MEMORY:room-short-selector:user-short-selector"),
                                        env.sessionRepository)))
                .isNotNull();
    }

    @Test
    void shouldListTirithPendingApprovalWithoutAlwaysScope() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-tirith-list", "user-tirith-list", "hello"));
        authorize(env, "room-tirith-list", "user-tirith-list");

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
                .contains("待审批：1 项")
                .contains("tirith:shortened_url")
                .contains("可用审批范围：单次、当前会话")
                .contains("剩余有效期：")
                .doesNotContain("可用审批范围：单次、当前会话、永久");
    }

    @Test
    void shouldApproveAllPendingDangerousCommandsAndResumeOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-all", "user-all", "hello"));
        authorize(env, "room-all", "user-all");

        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room-all:user-all");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
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
        authorize(env, "room-deny-all", "user-deny-all");

        SessionRecord session =
                env.sessionRepository.bindNewSession("MEMORY:room-deny-all:user-deny-all");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
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
        TestEnvironment env = approvalEnvironment();
        env.gatewayService.handle(env.message("room-4", "user-4", "hello"));
        authorize(env, "room-4", "user-4");

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
        authorize(env, "room-3", "user-3");

        GatewayReply reply = env.send("room-3", "user-3", "/approve always");

        assertThat(reply.getContent()).contains("待审批的危险命令");
        assertThat(reply.getContent()).doesNotContain("???");
    }

    @Test
    void shouldToggleSessionAutoApprovalOnlyForCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(
                env.message("room-auto-approval-a", "user-auto-approval", "hello"));
        env.gatewayService.handle(
                env.message("room-auto-approval-b", "user-auto-approval", "hello"));
        authorize(env, "room-auto-approval-a", "user-auto-approval");

        SessionRecord sessionA =
                env.sessionRepository.bindNewSession(
                        "MEMORY:room-auto-approval-a:user-auto-approval");
        SessionRecord sessionB =
                env.sessionRepository.bindNewSession(
                        "MEMORY:room-auto-approval-b:user-auto-approval");

        GatewayReply enabled =
                env.send("room-auto-approval-a", "user-auto-approval", "/approve auto");

        SessionRecord updatedA =
                env.sessionRepository.getBoundSession(
                        "MEMORY:room-auto-approval-a:user-auto-approval");
        SessionRecord updatedB =
                env.sessionRepository.getBoundSession(
                        "MEMORY:room-auto-approval-b:user-auto-approval");
        SqliteAgentSession agentSessionA = new SqliteAgentSession(updatedA, env.sessionRepository);
        SqliteAgentSession agentSessionB = new SqliteAgentSession(updatedB, env.sessionRepository);

        assertThat(enabled.getContent()).contains("会话自动审批已开启");
        assertThat(updatedA.getSessionId()).isEqualTo(sessionA.getSessionId());
        assertThat(updatedB.getSessionId()).isEqualTo(sessionB.getSessionId());
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(agentSessionA))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(agentSessionB))
                .isFalse();

        GatewayReply disabled =
                env.send("room-auto-approval-a", "user-auto-approval", "/approve auto");
        SessionRecord disabledA =
                env.sessionRepository.getBoundSession(
                        "MEMORY:room-auto-approval-a:user-auto-approval");
        assertThat(disabled.getContent()).contains("会话自动审批已关闭");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(
                                new SqliteAgentSession(disabledA, env.sessionRepository)))
                .isFalse();
    }

    @Test
    void shouldSupportExplicitSessionAutoApprovalStatusOnOffForCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayService.handle(env.message("room-auto-explicit-a", "user-auto", "hello"));
        env.gatewayService.handle(env.message("room-auto-explicit-b", "user-auto", "hello"));
        authorize(env, "room-auto-explicit-a", "user-auto");

        SessionRecord sessionA =
                env.sessionRepository.bindNewSession("MEMORY:room-auto-explicit-a:user-auto");
        SessionRecord sessionB =
                env.sessionRepository.bindNewSession("MEMORY:room-auto-explicit-b:user-auto");
        SqliteAgentSession agentSessionA = new SqliteAgentSession(sessionA, env.sessionRepository);
        SqliteAgentSession agentSessionB = new SqliteAgentSession(sessionB, env.sessionRepository);

        GatewayReply initialStatus =
                env.send("room-auto-explicit-a", "user-auto", "/approve auto status");
        assertThat(initialStatus.getContent()).contains("会话自动审批已关闭");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(
                                sessionAutoApprovalSession(env, "room-auto-explicit-a")))
                .isFalse();

        GatewayReply enabled = env.send("room-auto-explicit-a", "user-auto", "/approve auto on");
        GatewayReply enabledAgain =
                env.send("room-auto-explicit-a", "user-auto", "/approve auto enable");
        assertThat(enabled.getContent()).contains("会话自动审批已开启");
        assertThat(enabledAgain.getContent()).contains("会话自动审批已开启");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(
                                sessionAutoApprovalSession(env, "room-auto-explicit-a")))
                .isTrue();
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(agentSessionB))
                .isFalse();

        GatewayReply status = env.send("room-auto-explicit-a", "user-auto", "/approve auto status");
        assertThat(status.getContent()).contains("会话自动审批已开启");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(
                                sessionAutoApprovalSession(env, "room-auto-explicit-a")))
                .isTrue();

        GatewayReply disabled = env.send("room-auto-explicit-a", "user-auto", "/approve auto off");
        GatewayReply disabledAgain =
                env.send("room-auto-explicit-a", "user-auto", "/approve auto disable");
        assertThat(disabled.getContent()).contains("会话自动审批已关闭");
        assertThat(disabledAgain.getContent()).contains("会话自动审批已关闭");
        assertThat(
                        env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(
                                sessionAutoApprovalSession(env, "room-auto-explicit-a")))
                .isFalse();
        assertThat(env.dangerousCommandApprovalService.isSessionAutoApprovalEnabled(agentSessionB))
                .isFalse();

        GatewayReply invalid = env.send("room-auto-explicit-a", "user-auto", "/approve auto maybe");
        assertThat(invalid.isError()).isTrue();
        assertThat(invalid.getContent()).contains("用法：/approve auto [status|on|off]");
    }

    private SqliteAgentSession sessionAutoApprovalSession(TestEnvironment env, String chatId)
            throws Exception {
        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:" + chatId + ":user-auto");
        return new SqliteAgentSession(session, env.sessionRepository);
    }

    /** 记录审批恢复事件，验证 TUI/事件流路径不会只返回 RPC 而丢掉最终回复。 */
    private static class RecordingEventSink implements ConversationEventSink {
        /** 恢复完成时收到的会话编号。 */
        private String completedSessionId;

        /** 恢复完成时收到的最终文本。 */
        private String completedText;

        @Override
        public void onRunCompleted(String sessionId, String finalReply, LlmResult result) {
            completedSessionId = sessionId;
            completedText = finalReply;
        }
    }
}
