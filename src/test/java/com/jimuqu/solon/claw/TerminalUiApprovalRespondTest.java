package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.ClarifyRequestCoordinator;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tui.TerminalUiRpcService;
import com.jimuqu.solon.claw.tui.TerminalUiWebSocketEventSink;
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
import org.noear.snack4.ONode;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.websocket.WebSocket;

class TerminalUiApprovalRespondTest {
    /** 验证 WebSocket 事件 request_id 可由同会话 clarify.respond 精确回流。 */
    @Test
    void clarifyRespondCompletesWaitingRequestForTheSameSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);
        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "clarify-flow");
        RecordingSocket socket = new RecordingSocket();
        listener.onOpen(socket);
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-status\",\"method\":\"session.status\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\"}}");
        // 普通状态 RPC 不会绑定交互桥，因此使用空 prompt.submit 完成当前 socket 会话绑定。
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-bind\",\"method\":\"prompt.submit\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"text\":\"\"}}");

        CompletableFuture<String> answer =
                CompletableFuture.supplyAsync(
                        () -> {
                            AgentRunContext.setCurrent(
                                    new AgentRunContext(
                                            null,
                                            "run-clarify-flow",
                                            session.getSessionId(),
                                            session.getSourceKey()));
                            try {
                                return ClarifyRequestCoordinator.shared()
                                        .request(
                                                session.getSessionId(),
                                                "Which branch?",
                                                java.util.Arrays.asList("dev", "main"));
                            } finally {
                                AgentRunContext.setCurrent(null);
                            }
                        });
        waitForSocketText(socket, "\"type\":\"clarify.request\"", 2_000L);
        String requestId = jsonStringValue(socket.sentText(), "request_id");

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-answer\",\"method\":\"clarify.respond\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"request_id\":\""
                        + requestId
                        + "\",\"answer\":\"main\"}}");

        assertThat(answer.get(1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("main");
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"id\":\"rpc-answer\"")
                                        && text.contains("\"status\":\"ok\""));
        listener.onClose(socket);
    }

    /** 验证另一个 WebSocket 不能抢答不属于自己的 clarify 请求。 */
    @Test
    void clarifyRespondRejectsAnotherConnection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);
        SessionRecord session =
                env.sessionRepository.bindNewSession(
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX + "clarify-owner");
        RecordingSocket owner = new RecordingSocket();
        RecordingSocket attacker = new RecordingSocket();
        listener.onOpen(owner);
        listener.onOpen(attacker);
        listener.onMessage(
                owner,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-bind-owner\",\"method\":\"prompt.submit\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"text\":\"\"}}");

        CompletableFuture<String> answer =
                CompletableFuture.supplyAsync(
                        () -> {
                            AgentRunContext.setCurrent(
                                    new AgentRunContext(
                                            null,
                                            "run-clarify-owner",
                                            session.getSessionId(),
                                            session.getSourceKey()));
                            try {
                                return ClarifyRequestCoordinator.shared()
                                        .request(session.getSessionId(), "Continue?", null);
                            } finally {
                                AgentRunContext.setCurrent(null);
                            }
                        });
        waitForSocketText(owner, "\"type\":\"clarify.request\"", 2_000L);
        String requestId = jsonStringValue(owner.sentText(), "request_id");
        listener.onMessage(
                attacker,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-attack\",\"method\":\"clarify.respond\","
                        + "\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"request_id\":\""
                        + requestId
                        + "\",\"answer\":\"yes\"}}");

        assertThat(attacker.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"id\":\"rpc-attack\"")
                                        && text.contains("another connection"));
        assertThat(answer).isNotDone();
        listener.onClose(owner);
        assertThat(answer.get(1, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        listener.onClose(attacker);
    }

    /** 校验 TUI JSON-RPC 完成事件会携带非零模型用量，避免前端统计回退成 0。 */
    @Test
    void messageCompleteIncludesNonZeroUsageInRpcEnvelope() {
        RecordingSocket socket = new RecordingSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);
        LlmResult result = new LlmResult();
        result.setRequestCount(1L);
        result.setInputTokens(19L);
        result.setOutputTokens(3L);
        result.setReasoningTokens(2L);
        result.setTotalTokens(24L);
        result.setModel("usage-model");

        sink.onRunCompleted("tui-usage-session", "ok", result);

        ONode usage = eventPayload(socket.sentText(), "message.complete").get("usage");
        assertThat(usage.get("calls").getLong()).isEqualTo(1L);
        assertThat(usage.get("input").getLong()).isEqualTo(19L);
        assertThat(usage.get("output").getLong()).isEqualTo(3L);
        assertThat(usage.get("reasoning").getLong()).isEqualTo(2L);
        assertThat(usage.get("total").getLong()).isEqualTo(24L);
        assertThat(usage.get("model").getString()).isEqualTo("usage-model");
    }

    /** fallback 状态必须携带实际激活的 provider/model，同时继续使用警告语义。 */
    @Test
    void fallbackStatusCarriesActivatedProviderAndModel() {
        RecordingSocket socket = new RecordingSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onFallback("run-1", "primary", "backup", "backup-model", "rate limited");

        ONode payload = eventPayload(socket.sentText(), "status.update");
        assertThat(payload.get("kind").getString()).isEqualTo("warn");
        assertThat(payload.get("provider").getString()).isEqualTo("backup");
        assertThat(payload.get("model").getString()).isEqualTo("backup-model");
        assertThat(payload.get("text").getString()).contains("fallback primary -> backup");
    }

    @Test
    void toolCompleteMarksExplicitErrorAsFailed() {
        RecordingSocket socket = new RecordingSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onRunStarted("tui-tool-error");
        sink.onToolStarted("terminal", java.util.Collections.<String, Object>emptyMap());
        sink.onToolCompleted(
                "terminal", "tool arguments are invalid", "tool arguments are invalid", 12L);

        ONode payload = eventPayload(socket.sentText(), "tool.complete");
        assertThat(payload.get("error").getString()).isEqualTo("tool arguments are invalid");
        assertThat(payload.get("status").getString()).isEqualTo("error");
    }

    /** 验证同一轮同名工具并发启动时，完成事件仍按启动顺序配对各自的前端 ID。 */
    @Test
    void sameNamedToolEventsPairIdsInStartOrder() {
        RecordingSocket socket = new RecordingSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onToolStarted("read_file", java.util.Collections.<String, Object>emptyMap());
        sink.onToolStarted("read_file", java.util.Collections.<String, Object>emptyMap());
        sink.onToolCompleted("read_file", "first", 1L);
        sink.onToolCompleted("read_file", "second", 1L);

        List<ONode> starts = eventPayloads(socket.sentText(), "tool.start");
        List<ONode> completes = eventPayloads(socket.sentText(), "tool.complete");
        assertThat(starts).hasSize(2);
        assertThat(completes).hasSize(2);
        assertThat(starts.get(0).get("tool_id").getString())
                .isNotEqualTo(starts.get(1).get("tool_id").getString());
        assertThat(completes.get(0).get("tool_id").getString())
                .isEqualTo(starts.get(0).get("tool_id").getString());
        assertThat(completes.get(1).get("tool_id").getString())
                .isEqualTo(starts.get(1).get("tool_id").getString());
    }

    @Test
    void sessionUndoReportsZeroRemovedForFreshTuiSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-create\",\"method\":\"session.create\",\"params\":{}}");
        String sessionId = jsonStringValue(socket.sentText(), "session_id");
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-undo\",\"method\":\"session.undo\","
                        + "\"params\":{\"session_id\":\""
                        + sessionId
                        + "\"}}");

        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"id\":\"rpc-undo\"")
                                        && text.contains("\"removed\":0"));
    }

    @Test
    void approvalRespondRemembersSecurityPolicyForSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

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
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"id\":\"rpc-policy-session\"")
                                        && text.contains("\"ok\":true"));
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                refreshedAgentSession,
                                "write_file",
                                "policy:workspace_outside_write",
                                new File(
                                                System.getProperty("java.io.tmpdir"),
                                                "another-outside.txt")
                                        .getAbsolutePath()))
                .isTrue();
    }

    @Test
    void approvalRespondStreamsResumedRunToSocket() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

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
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"type\":\"message.complete\"")
                                        && text.contains("echo:resume"));
    }

    @Test
    void approvalRespondUsesSelectorFromTuiCard() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

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
        TerminalUiWebSocketListener listener = newTuiListener(env);

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

        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"type\":\"approval.request\"")
                                        && text.contains(
                                                "\"session_id\":\""
                                                        + session.getSessionId()
                                                        + "\""));
    }

    @Test
    void approvalRespondRejectsMissingSessionId() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

        RecordingSocket socket = new RecordingSocket();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-missing-session\","
                        + "\"method\":\"approval.respond\",\"params\":{\"choice\":\"session\"}}");

        assertThat(socket.sentText())
                .anyMatch(text -> text.contains("\"id\":\"rpc-missing-session\""));
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"ok\":false")
                                        && text.contains("missing_session_id"));
    }

    @Test
    void slashExecApprovalCommandStreamsResumedRunToSocket() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

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
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"type\":\"message.complete\"")
                                        && text.contains("echo:resume"));
    }

    @Test
    void promptSubmitUsesProvidedTuiSessionBeforeCheckingPendingApprovals() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = newTuiListener(env);

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
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"id\":\"rpc-submit\"")
                                        && text.contains("\"ok\":true"));
        assertThat(socket.sentText())
                .anyMatch(
                        text ->
                                text.contains("\"type\":\"error\"")
                                        && text.contains("approve session"));
        assertThat(socket.sentText()).noneMatch(text -> text.contains("echo:继续执行"));
    }

    /** 构造默认 TUI WebSocket 监听器，集中维护测试环境到监听器的长参数装配。 */
    private static TerminalUiWebSocketListener newTuiListener(TestEnvironment env) {
        return newTuiListener(env, null);
    }

    /** 构造带安全策略拦截的 TUI WebSocket 监听器，用于直连 shell 审批链测试。 */
    private static TerminalUiWebSocketListener newTuiListenerWithSecurityPolicy(
            TestEnvironment env) {
        return newTuiListener(env, new SecurityPolicyService(env.appConfig));
    }

    /** 构造 TUI WebSocket 监听器，允许单个测试按需注入安全策略服务。 */
    private static TerminalUiWebSocketListener newTuiListener(
            TestEnvironment env, SecurityPolicyService securityPolicyService) {
        CliRuntime runtime =
                new CliRuntime(
                        env.commandService,
                        env.conversationOrchestrator,
                        env.agentRunControlService,
                        TerminalUiRpcService.TERMINAL_SOURCE_KEY_PREFIX);
        return new TerminalUiWebSocketListener(
                runtime,
                env.appConfig,
                env.sessionRepository,
                securityPolicyService,
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

    /** 从测试 WebSocket 帧中提取简单 JSON 字符串字段，避免为断言引入完整协议解析。 */
    private static String jsonStringValue(List<String> frames, String key) {
        String needle = "\"" + key + "\":\"";
        for (String text : frames) {
            int start = text.indexOf(needle);
            if (start < 0) {
                continue;
            }
            int valueStart = start + needle.length();
            int valueEnd = text.indexOf('"', valueStart);
            if (valueEnd > valueStart) {
                return text.substring(valueStart, valueEnd);
            }
        }
        return "";
    }

    /** 查找 JSON-RPC event 帧中的业务事件载荷。 */
    private static ONode eventPayload(List<String> frames, String type) {
        for (String text : frames) {
            ONode event = ONode.ofJson(text).get("params");
            if (type.equals(event.get("type").getString())) {
                return event.get("payload");
            }
        }
        throw new AssertionError("event not found: " + type);
    }

    /** 收集 JSON-RPC event 帧中指定类型的全部业务载荷，用于断言重复事件的顺序。 */
    private static List<ONode> eventPayloads(List<String> frames, String type) {
        List<ONode> payloads = new ArrayList<ONode>();
        for (String text : frames) {
            ONode event = ONode.ofJson(text).get("params");
            if (type.equals(event.get("type").getString())) {
                payloads.add(event.get("payload"));
            }
        }
        return payloads;
    }

    /** 构建跨平台测试写入命令，用于保留直接 shell 审批回放的真实执行路径。 */
    private static String writeTextCommand(String value, File target) {
        if (isWindows()) {
            return "echo " + value + ">" + target.getAbsolutePath();
        }
        return "printf '"
                + shellSingleQuoted(value)
                + "' > '"
                + shellSingleQuoted(target.getAbsolutePath())
                + "'";
    }

    /** 读取测试输出文件内容，用于兼容不同 shell 的换行行为。 */
    private static String fileText(File file) throws Exception {
        return new String(
                java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 转义 JSON 字符串中的 shell 命令，避免测试请求手写转义出错。 */
    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 转义 POSIX 单引号字符串中的内容。 */
    private static String shellSingleQuoted(String value) {
        return value.replace("'", "'\"'\"'");
    }

    /** 判断当前测试运行环境是否为 Windows。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
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
