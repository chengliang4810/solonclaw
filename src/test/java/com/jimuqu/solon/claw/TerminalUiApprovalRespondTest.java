package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tui.TerminalUiRpcService;
import com.jimuqu.solon.claw.tui.TerminalUiWebSocketListener;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.websocket.WebSocket;

class TerminalUiApprovalRespondTest {
    @Test
    void approvalRespondRemembersSecurityPolicyForSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "tui-policy-approval");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        File outsideFile =
                new File(System.getProperty("java.io.tmpdir"), "solonclaw-tui-policy-outside.txt");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "write_file",
                "policy:workspace_outside_write",
                "工作区外写入需要审批",
                outsideFile.getAbsolutePath());
        DangerousCommandApprovalService.PendingApproval pending =
                env.dangerousCommandApprovalService.listPendingApprovals(agentSession).get(0);
        String selector = DangerousCommandApprovalService.approvalSelector(pending);

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-policy-session\",\"method\":\"approval.respond\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"approval_id\":\""
                        + selector
                        + "\",\"choice\":\"session\"}}");

        SessionRecord refreshed = env.sessionRepository.findById(session.getSessionId());
        SqliteAgentSession refreshedAgentSession =
                new SqliteAgentSession(refreshed, env.sessionRepository);
        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"id\":\"rpc-policy-session\"")
                && text.contains("\"ok\":true"));
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                refreshedAgentSession,
                                "write_file",
                                "policy:workspace_outside_write",
                                new File(System.getProperty("java.io.tmpdir"), "another-outside.txt")
                                        .getAbsolutePath()))
                .isTrue();
    }

    @Test
    void approvalRespondStreamsResumedRunToSocket() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "tui-approval");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"method\":\"approval.respond\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"choice\":\"session\"}}");

        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"id\":\"rpc-1\""));
        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"type\":\"message.complete\"")
                && text.contains("echo:resume"));
    }

    @Test
    void approvalRespondUsesSelectorFromTuiCard() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "tui-approval-selector");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "first_delete",
                "first delete",
                "rm -rf workspace/first");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "second_delete",
                "second delete",
                "rm -rf workspace/second");

        String secondSelector =
                DangerousCommandApprovalService.approvalSelector(
                        env.dangerousCommandApprovalService
                                .listPendingApprovals(agentSession)
                                .get(1));

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-2\",\"method\":\"approval.respond\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"approval_id\":\""
                        + secondSelector
                        + "\",\"choice\":\"session\"}}");

        SessionRecord refreshed = env.sessionRepository.findById(session.getSessionId());
        SqliteAgentSession refreshedAgentSession =
                new SqliteAgentSession(refreshed, env.sessionRepository);
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(refreshedAgentSession))
                .extracting("description")
                .containsExactly("first delete");
    }

    @Test
    void sessionResumeBindsApprovalObserverForResumedRunRequests() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "tui-resume-approval");

        RecordingSocket socket = new RecordingSocket();
        listener.onOpen(socket);
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-resume\",\"method\":\"session.resume\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\"}}");

        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"type\":\"approval.request\"")
                && text.contains("\"session_id\":\"" + session.getSessionId() + "\""));
    }

    @Test
    void approvalRespondRejectsMissingSessionId() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-missing-session\","
                        + "\"method\":\"approval.respond\",\"params\":{\"choice\":\"session\"}}");

        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"id\":\"rpc-missing-session\""));
        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"ok\":false")
                && text.contains("missing_session_id"));
    }

    @Test
    void slashExecApprovalCommandStreamsResumedRunToSocket() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "tui-slash-approval");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-3\",\"method\":\"slash.exec\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"command\":\"approve session\"}}");

        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"id\":\"rpc-3\""));
        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"type\":\"message.complete\"")
                && text.contains("echo:resume"));
    }

    @Test
    void promptSubmitUsesProvidedTuiSessionBeforeCheckingPendingApprovals() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(
                        runtime,
                        env.appConfig,
                        env.sessionRepository,
                        null,
                        null,
                        env.dangerousCommandApprovalService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.runtimeSettingsService,
                        env.globalSettingRepository);

        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        "MEMORY:terminal-ui:imported-without-active-binding");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");

        RecordingSocket socket = new RecordingSocket();
        listener.onOpen(socket);
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-submit\",\"method\":\"prompt.submit\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"text\":\"继续执行\"}}");

        waitForSocketText(socket, "\"type\":\"error\"", 2000L);
        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"id\":\"rpc-submit\"")
                && text.contains("\"ok\":true"));
        assertThat(socket.sentText()).anyMatch(text -> text.contains("\"type\":\"error\"")
                && text.contains("approve session"));
        assertThat(socket.sentText()).noneMatch(text -> text.contains("echo:继续执行"));
    }

    /** 等待后台 prompt.submit 线程把预期帧写入测试 socket。 */
    private static void waitForSocketText(RecordingSocket socket, String expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String text : socket.sentText()) {
                if (text.contains(expected)) {
                    return;
                }
            }
            Thread.sleep(20L);
        }
    }

    /** 收集 WebSocket 文本帧，避免测试依赖真实网络连接。 */
    private static final class RecordingSocket implements WebSocket {
        /** 已发送文本帧。 */
        private final List<String> sentText = new ArrayList<String>();
        /** WebSocket 属性。 */
        private final Map<String, Object> attrs = new LinkedHashMap<String, Object>();

        private List<String> sentText() {
            return sentText;
        }

        @Override
        public String id() {
            return "test-socket";
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void nameAs(String name) {}

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String url() {
            return "ws://127.0.0.1/ws/tui";
        }

        @Override
        public String path() {
            return "/ws/tui";
        }

        @Override
        public void pathNew(String path) {}

        @Override
        public MultiMap<String> paramMap() {
            return new MultiMap<String>();
        }

        @Override
        public String param(String name) {
            return null;
        }

        @Override
        public String paramOrDefault(String name, String def) {
            return def;
        }

        @Override
        public void param(String name, String value) {}

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public InetSocketAddress localAddress() {
            return null;
        }

        @Override
        public Map<String, Object> attrMap() {
            return attrs;
        }

        @Override
        public boolean attrHas(String name) {
            return attrs.containsKey(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T attr(String name) {
            return (T) attrs.get(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T attrOrDefault(String name, T def) {
            Object value = attrs.get(name);
            return value == null ? def : (T) value;
        }

        @Override
        public <T> void attr(String name, T value) {
            attrs.put(name, value);
        }

        @Override
        public long getIdleTimeout() {
            return 0L;
        }

        @Override
        public void setIdleTimeout(long timeout) {}

        @Override
        public Future<Void> send(String text) {
            sentText.add(text);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Future<Void> send(ByteBuffer bytes) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {}

        @Override
        public void close(int code, String reason) {}
    }
}
