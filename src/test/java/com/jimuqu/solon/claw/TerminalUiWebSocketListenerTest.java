package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tui.TerminalUiWebSocketEventSink;
import com.jimuqu.solon.claw.tui.TerminalUiWebSocketListener;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.websocket.WebSocket;

public class TerminalUiWebSocketListenerTest {
    @Test
    void shouldReplyToClientHelloWithProtocolVersion() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();

        listener.onMessage(socket, "{\"type\":\"client.hello\",\"payload\":{}}");

        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains("server.hello"));
        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains("protocol_version"));
    }

    @Test
    void shouldAllowLocalWebSocketWithoutDashboardToken() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getDashboard().setAccessToken("tui-secret");
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        listener.onOpen(socket);

        assertThat(socket.closed).isFalse();
        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains("server.ready"));
    }

    @Test
    void shouldCloseRemoteWebSocketWhenDashboardTokenIsMissing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getDashboard().setAccessToken("tui-secret");
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = FakeSocket.remote("203.0.113.10");

        listener.onOpen(socket);

        assertThat(socket.closed).isTrue();
        assertThat(socket.closeCode).isEqualTo(1008);
        assertThat(socket.sent).isEmpty();
    }

    @Test
    void shouldAllowRemoteWebSocketWhenDashboardTokenMatchesQuery() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getDashboard().setAccessToken("tui-secret");
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = FakeSocket.remote("203.0.113.10");
        socket.param("token", "tui-secret");

        listener.onOpen(socket);

        assertThat(socket.closed).isFalse();
        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains("server.ready"));
    }

    @Test
    void shouldAllowRemoteWebSocketWhenAuthorizationHeaderMatchesDashboardToken()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getDashboard().setAccessToken("tui-secret");
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = FakeSocket.remote("203.0.113.10");
        socket.param("Authorization", "Bearer tui-secret");

        listener.onOpen(socket);

        assertThat(socket.closed).isFalse();
        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains("server.ready"));
    }

    @Test
    void shouldSendChatEventsOverWebSocket() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"type\":\"chat.send\",\"payload\":{\"session_id\":\"ws-test\",\"input\":\"hello\"}}");

        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains("run.started"));
        assertThat(socket.sent)
                .anySatisfy(text -> assertThat(text).contains("run.completed").contains("echo:hello"));
    }

    @Test
    void shouldReplyToSessionCreateJsonRpc() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r1\",\"method\":\"session.create\",\"params\":{\"cols\":80}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"r1\"")
                                        .contains("\"result\"")
                                        .contains("\"session_id\"")
                                        .contains("\"info\""));
        assertThat(env.sessionRepository.listRecent(10)).hasSize(1);
    }

    @Test
    void shouldReturnPersistedSessionsToJsonRpcList() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r1\",\"method\":\"session.create\",\"params\":{\"cols\":80}}");
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r2\",\"method\":\"session.list\",\"params\":{\"limit\":20}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"r2\"")
                                        .contains("\"sessions\"")
                                        .contains("\"message_count\""));
    }

    @Test
    void shouldReturnSlashCompletionJsonRpc() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r2\",\"method\":\"complete.slash\",\"params\":{\"text\":\"/sta\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"r2\"")
                                        .contains("\"items\"")
                                        .contains("/status"));
    }

    @Test
    void shouldReturnTerminalSetupCommandsInSlashCompletionJsonRpc() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"doctor-complete\",\"method\":\"complete.slash\",\"params\":{\"text\":\"/doc\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"doctor-complete\"")
                                        .contains("\"items\"")
                                        .contains("/doctor"));
    }

    @Test
    void shouldReturnPathCompletionJsonRpc() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();
        String prefix = new File("pom").getAbsolutePath().replace("\\", "\\\\");

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r4\",\"method\":\"complete.path\",\"params\":{\"word\":\""
                        + prefix
                        + "\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"r4\"")
                                        .contains("\"items\"")
                                        .contains("pom.xml"));
    }

    @Test
    void shouldExecuteSlashRpcThroughBackendCommandService() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r5\",\"method\":\"session.create\",\"params\":{\"cols\":80}}");
        String sessionId = env.sessionRepository.listRecent(1).get(0).getSessionId();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r6\",\"method\":\"slash.exec\",\"params\":{\"session_id\":\""
                        + sessionId
                        + "\",\"command\":\"usage\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"r6\"")
                                        .contains("\"output\"")
                                        .contains("session=")
                                        .contains("cumulative_input_tokens=")
                                        .contains("cumulative_output_tokens="));
    }

    @Test
    void shouldRouteSetupConfigAndDoctorSlashRpcToLocalTerminalSetupCommands()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"session\",\"method\":\"session.create\",\"params\":{\"cols\":80}}");
        String sessionId = env.sessionRepository.listRecent(1).get(0).getSessionId();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"config\",\"method\":\"slash.exec\",\"params\":{\"session_id\":\""
                        + sessionId
                        + "\",\"command\":\"config path\"}}");
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"doctor\",\"method\":\"slash.exec\",\"params\":{\"session_id\":\""
                        + sessionId
                        + "\",\"command\":\"doctor\"}}");

        assertThat(rpcResponse(socket.sent, "config", 0))
                .contains("\"output\"")
                .contains("config.yml");
        assertThat(rpcResponse(socket.sent, "doctor", 0))
                .contains("\"output\"")
                .contains("doctor");
    }

    @Test
    void shouldSendPromptSubmitJsonRpcEventsAndResult() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"r3\",\"method\":\"prompt.submit\",\"params\":{\"session_id\":\"rpc-test\",\"text\":\"hello\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"r3\"")
                                        .contains("\"result\"")
                                        .contains("\"ok\":true"));
        waitForSent(socket, "\"type\":\"message.complete\"");
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"method\":\"event\"")
                                        .contains("\"type\":\"message.start\""));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"method\":\"event\"")
                                        .contains("\"type\":\"message.complete\"")
                                        .contains("echo:hello"));
    }

    @Test
    void shouldKeepRpcResponsiveWhilePromptSubmitRunsInBackground() throws Exception {
        BlockingCliRuntime runtime = new BlockingCliRuntime();
        TerminalUiWebSocketListener listener = new TerminalUiWebSocketListener(runtime);
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"prompt\",\"method\":\"prompt.submit\",\"params\":{\"session_id\":\"slow\",\"text\":\"hello\"}}");
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"cfg\",\"method\":\"config.get\",\"params\":{\"key\":\"mtime\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"prompt\"")
                                        .contains("\"result\"")
                                        .contains("\"ok\":true"));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"cfg\"")
                                        .contains("\"mtime\""));
        assertThat(runtime.started).isTrue();
        assertThat(runtime.release.isDone()).isFalse();
        runtime.release.complete(null);
        waitForSent(socket, "\"type\":\"message.complete\"");
    }

    @Test
    void shouldReportPromptSubmitThrowableWithoutClosingWebSocketWorker() throws Exception {
        TerminalUiWebSocketListener listener =
                new TerminalUiWebSocketListener(new FailingCliRuntime());
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"boom\",\"method\":\"prompt.submit\",\"params\":{\"session_id\":\"boom-session\",\"text\":\"hello\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"boom\"")
                                        .contains("\"result\""));
        waitForSent(socket, "\"type\":\"error\"");
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"type\":\"error\"")
                                        .contains("LinkageError"));
    }

    @Test
    void shouldBatchShortAssistantDeltasBeforeSendingToJsonRpcTui() {
        FakeSocket socket = new FakeSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onRunStarted("rpc-batch");
        sink.onAssistantDelta("你");
        sink.onAssistantDelta("好");

        long deltaBeforeComplete = socket.sent.stream()
                .filter(text -> text.contains("\"type\":\"message.delta\""))
                .count();
        assertThat(deltaBeforeComplete).isZero();

        sink.onRunCompleted("rpc-batch", "你好", null);

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"type\":\"message.delta\"")
                                        .contains("你好"));
        assertThat(socket.sent)
                .anySatisfy(text -> assertThat(text).contains("\"type\":\"message.complete\""));
    }

    @Test
    void shouldAttachTokenUsageToCopiedTerminalUiMessageComplete() {
        FakeSocket socket = new FakeSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);
        LlmResult result = new LlmResult();
        result.setModel("mimo-v2.5-pro");
        result.setInputTokens(10L);
        result.setOutputTokens(5L);
        result.setReasoningTokens(2L);
        result.setCacheReadTokens(3L);
        result.setCacheWriteTokens(4L);
        result.setRequestCount(2L);

        sink.onRunStarted("rpc-usage");
        sink.onRunCompleted("rpc-usage", "done", result);

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"type\":\"message.complete\"")
                                        .contains("\"input\":10")
                                        .contains("\"output\":5")
                                        .contains("\"reasoning\":2")
                                        .contains("\"cache_read\":3")
                                        .contains("\"cache_write\":4")
                                        .contains("\"total\":24")
                                        .contains("\"calls\":2")
                                        .contains("\"model\":\"mimo-v2.5-pro\""));
    }

    @Test
    void shouldFlushReasoningBeforeAssistantDeltaInJsonRpcTui() {
        FakeSocket socket = new FakeSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onRunStarted("rpc-reasoning");
        sink.onReasoningDelta("思考");
        sink.onAssistantDelta("回复");
        sink.onRunCompleted("rpc-reasoning", "回复", null);

        int reasoningIndex = indexOf(socket.sent, "\"type\":\"reasoning.delta\"");
        int messageDeltaIndex = indexOf(socket.sent, "\"type\":\"message.delta\"");

        assertThat(reasoningIndex).isGreaterThanOrEqualTo(0);
        assertThat(messageDeltaIndex).isGreaterThan(reasoningIndex);
    }

    @Test
    void shouldStripThinkTagsFromJsonRpcAssistantDeltas() {
        FakeSocket socket = new FakeSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onRunStarted("rpc-think");
        sink.onAssistantDelta("<think>内部推理</think>\n\n最终答复");
        sink.onRunCompleted("rpc-think", "最终答复", null);

        assertThat(socket.sent)
                .filteredOn(text -> text.contains("\"type\":\"message.delta\""))
                .anySatisfy(text -> assertThat(text).contains("最终答复").doesNotContain("<think>").doesNotContain("</think>"));
        assertThat(socket.sent)
                .filteredOn(text -> text.contains("\"type\":\"reasoning.delta\""))
                .anySatisfy(text -> assertThat(text).contains("内部推理"));
    }

    @Test
    void shouldMapBackendRunStatusEventsToCopiedTerminalUiStatusUpdates() {
        FakeSocket socket = new FakeSocket();
        TerminalUiWebSocketEventSink sink = new TerminalUiWebSocketEventSink(socket, true);

        sink.onRunStarted("rpc-status");
        sink.onAttemptStarted("run-1", 1, "openai", "mimo-v2.5-pro");
        sink.onCompressionDecision("run-1", false, "below threshold", 1200, 8000);
        sink.onFallback("run-1", "openai", "ollama", "upstream timeout");
        sink.onRecoveryStarted("run-1", "empty_reply");
        sink.onDeliveryEvent("run-1", "failed", "network down");
        sink.onAttemptCompleted("run-1", 1, "error", "upstream timeout");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"type\":\"status.update\"")
                                        .contains("model 1: openai/mimo-v2.5-pro"));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"kind\":\"status\"")
                                        .contains("context ok 1200/8000"));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"kind\":\"warn\"")
                                        .contains("fallback openai -> ollama"));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"kind\":\"warn\"")
                                        .contains("recovery: empty_reply"));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"kind\":\"error\"")
                                        .contains("delivery failed"));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"kind\":\"warn\"")
                                        .contains("attempt 1 error"));
    }

    @Test
    void shouldBridgeDangerousCommandApprovalRequestAndResponseToCopiedTerminalUi()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:terminal-ui:approval");
        SqliteAgentSession agentSession = new SqliteAgentSession(session, env.sessionRepository);

        listener.onOpen(socket);
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"bind\",\"method\":\"prompt.submit\",\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"text\":\"\"}}");
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "terminal",
                "dangerous_rm",
                "recursive delete",
                "rm -rf /tmp/demo");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"type\":\"approval.request\"")
                                        .contains("recursive delete")
                                        .contains("rm -rf /tmp/demo"));

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"deny\",\"method\":\"approval.respond\",\"params\":{\"session_id\":\""
                        + session.getSessionId()
                        + "\",\"choice\":\"deny\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"deny\"")
                                        .contains("\"ok\":true"));
        SessionRecord updated = env.sessionRepository.findById(session.getSessionId());
        assertThat(env.dangerousCommandApprovalService.listPendingApprovals(updated)).isEmpty();
        listener.onClose(socket);
    }

    @Test
    void shouldReturnResultForFrontendRpcMethodCatalog() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"seed\",\"method\":\"session.create\",\"params\":{\"cols\":80}}");
        String sessionId = env.sessionRepository.listRecent(1).get(0).getSessionId();
        List<RpcCase> cases = frontendRpcCases(sessionId);

        for (int i = 0; i < cases.size(); i++) {
            RpcCase rpc = cases.get(i);
            String id = "rpc-" + i;
            int before = socket.sent.size();
            listener.onMessage(
                    socket,
                    "{\"jsonrpc\":\"2.0\",\"id\":\""
                            + id
                            + "\",\"method\":\""
                            + rpc.method
                            + "\",\"params\":"
                            + rpc.params
                            + "}");

            String response = rpcResponse(socket.sent, id, before);
            assertThat(response)
                    .describedAs(rpc.method)
                    .contains("\"result\"")
                    .doesNotContain("\"error\"")
                    .doesNotContain("not available")
                    .doesNotContain("method unavailable");
        }
    }

    @Test
    void shouldReturnExpectedShapesForInteractiveRpcMethods() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode modelOptions = rpcResult(listener, socket, "model.options", "{}");
        assertThat(modelOptions.get("providers").isArray()).isTrue();
        assertThat(modelOptions.get("model").getString()).isNotBlank();

        ONode sessionSave =
                rpcResult(
                        listener,
                        socket,
                        "session.save",
                        "{\"session_id\":\"shape-session\"}");
        assertThat(sessionSave.get("file").getString()).contains("state.db");

        ONode clipboard = rpcResult(listener, socket, "clipboard.paste", "{}");
        assertThat(clipboard.get("attached").getBoolean()).isFalse();

        ONode skills =
                rpcResult(listener, socket, "skills.manage", "{\"action\":\"list\"}");
        assertThat(skills.get("skills").isObject()).isTrue();

        ONode tools =
                rpcResult(
                        listener,
                        socket,
                        "tools.configure",
                        "{\"action\":\"enable\",\"names\":[\"terminal\"]}");
        assertThat(tools.get("unknown").isArray()).isTrue();
        assertThat(tools.get("info").isObject()).isTrue();

        ONode voice = rpcResult(listener, socket, "voice.toggle", "{}");
        assertThat(voice.get("available").getBoolean()).isFalse();

        ONode rollback = rpcResult(listener, socket, "rollback.list", "{}");
        assertThat(rollback.get("checkpoints").isArray()).isTrue();

        ONode shell =
                rpcResult(
                        listener,
                        socket,
                        "shell.exec",
                        "{\"command\":\"printf tui-ok\"}");
        assertThat(shell.get("code").getInt()).isEqualTo(0);
        assertThat(shell.get("stdout").getString()).contains("tui-ok");
    }

    @Test
    void shouldExposeBackendSkillsToCopiedTerminalUi() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.localSkillService.createSkill(
                "deploy",
                "ops",
                "---\nname: deploy\ndescription: deploy skill\n---\n\n# Deploy\nrun deployment");
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode list = rpcResult(listener, socket, "skills.manage", "{\"action\":\"list\"}");
        ONode inspect =
                rpcResult(
                        listener,
                        socket,
                        "skills.manage",
                        "{\"action\":\"inspect\",\"query\":\"ops/deploy\"}");

        assertThat(list.get("skills").get("ops").toJson()).contains("ops/deploy");
        assertThat(inspect.get("info").get("name").getString()).isEqualTo("ops/deploy");
        assertThat(inspect.get("info").get("description").getString()).contains("deploy skill");
    }

    @Test
    void shouldExposeBackendCheckpointsToCopiedTerminalUiRollback() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:terminal-ui:rollback";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "tui-rollback.txt");
        FileUtil.writeUtf8String("v1", file);
        String checkpointId =
                env.checkpointService
                        .createCheckpoint(sourceKey, session.getSessionId(), java.util.Collections.singletonList(file))
                        .getCheckpointId();
        FileUtil.writeUtf8String("v2", file);
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode list =
                rpcResult(
                        listener,
                        socket,
                        "rollback.list",
                        "{\"session_id\":\"" + session.getSessionId() + "\"}");
        ONode diff =
                rpcResult(
                        listener,
                        socket,
                        "rollback.diff",
                        "{\"hash\":\"" + checkpointId + "\",\"session_id\":\"" + session.getSessionId() + "\"}");
        ONode restore =
                rpcResult(
                        listener,
                        socket,
                        "rollback.restore",
                        "{\"hash\":\"" + checkpointId + "\",\"session_id\":\"" + session.getSessionId() + "\"}");

        assertThat(list.get("enabled").getBoolean()).isTrue();
        assertThat(list.get("checkpoints").toJson()).contains(checkpointId);
        assertThat(diff.get("rendered").getString()).contains("tui-rollback.txt");
        assertThat(restore.get("success").getBoolean()).isTrue();
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v1");
    }

    @Test
    void shouldWireOperationalRpcMethodsToBackendServices() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File attachment = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "tui-image.png");
        FileUtil.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, attachment);
        Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        env.processRegistry.add(process);
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode image =
                rpcResult(
                        listener,
                        socket,
                        "image.attach",
                        "{\"path\":\"" + jsonEscape(attachment.getAbsolutePath()) + "\"}");
        ONode stop = rpcResult(listener, socket, "process.stop", "{}");
        ONode reload = rpcResult(listener, socket, "reload.env", "{}");

        assertThat(image.get("attached").getBoolean()).isTrue();
        assertThat(image.get("name").getString()).contains("tui-image.png");
        assertThat(stop.get("killed").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(reload.get("updated").getInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldWireDelegationRpcMethodsToBackendService() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        RecordingDelegationService delegationService = new RecordingDelegationService();
        Map<String, Object> active = new LinkedHashMap<String, Object>();
        active.put("subagent_id", "sa-live");
        active.put("parent_run_id", "run-parent");
        active.put("status", "running");
        active.put("depth", Integer.valueOf(1));
        active.put("goal", "inspect files");
        active.put("started_at", Long.valueOf(System.currentTimeMillis()));
        delegationService.active.add(active);
        delegationService.interruptible.add("sa-live");
        TerminalUiWebSocketListener listener = listener(env, delegationService);
        FakeSocket socket = new FakeSocket();

        ONode pause = rpcResult(listener, socket, "delegation.pause", "{\"paused\":true}");
        ONode status = rpcResult(listener, socket, "delegation.status", "{}");
        ONode interrupt =
                rpcResult(
                        listener,
                        socket,
                        "subagent.interrupt",
                        "{\"subagent_id\":\"sa-live\"}");

        assertThat(pause.get("paused").getBoolean()).isTrue();
        assertThat(status.get("active").toJson()).contains("sa-live").contains("inspect files");
        assertThat(status.get("max_concurrent_children").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(interrupt.get("found").getBoolean()).isTrue();
    }

    @Test
    void shouldLoadSpawnTreeFromBackendRunRepository() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:terminal-ui:spawn");
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-spawn");
        run.setSessionId(session.getSessionId());
        run.setSourceKey(session.getSourceKey());
        run.setStatus("success");
        run.setInputPreview("parent prompt");
        run.setStartedAt(System.currentTimeMillis() - 2000L);
        run.setFinishedAt(System.currentTimeMillis());
        env.agentRunRepository.saveRun(run);
        SubagentRunRecord child = new SubagentRunRecord();
        child.setSubagentId("sa-spawn");
        child.setParentRunId(run.getRunId());
        child.setName("delegate");
        child.setGoalPreview("child task");
        child.setStatus("success");
        child.setDepth(1);
        child.setStartedAt(System.currentTimeMillis() - 1000L);
        child.setFinishedAt(System.currentTimeMillis());
        child.setOutputTailJson("[{\"preview\":\"done\",\"is_error\":false}]");
        env.agentRunRepository.saveSubagentRun(child);
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode list =
                rpcResult(
                        listener,
                        socket,
                        "spawn_tree.list",
                        "{\"session_id\":\"" + session.getSessionId() + "\",\"limit\":10}");
        ONode load = rpcResult(listener, socket, "spawn_tree.load", "{\"path\":\"run:run-spawn\"}");

        assertThat(list.get("entries").toJson()).contains("run:run-spawn").contains("parent prompt");
        assertThat(load.get("subagents").toJson())
                .contains("sa-spawn")
                .contains("child task")
                .contains("completed")
                .contains("done");
    }

    @Test
    void shouldPersistCopiedTerminalUiConfigCommandsThroughBackend() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode busySet =
                rpcResult(
                        listener,
                        socket,
                        "config.set",
                        "{\"key\":\"busy\",\"value\":\"steer\"}");
        ONode indicatorSet =
                rpcResult(
                        listener,
                        socket,
                        "config.set",
                        "{\"key\":\"indicator\",\"value\":\"ascii\"}");
        ONode full = rpcResult(listener, socket, "config.get", "{\"key\":\"full\"}");
        ONode busyGet = rpcResult(listener, socket, "config.get", "{\"key\":\"busy\"}");
        ONode indicatorGet = rpcResult(listener, socket, "config.get", "{\"key\":\"indicator\"}");

        assertThat(busySet.get("value").getString()).isEqualTo("steer");
        assertThat(env.appConfig.getTask().getBusyPolicy()).isEqualTo("steer");
        assertThat(busyGet.get("value").getString()).isEqualTo("steer");
        assertThat(indicatorSet.get("value").getString()).isEqualTo("ascii");
        assertThat(indicatorGet.get("value").getString()).isEqualTo("ascii");
        assertThat(full.get("config").get("display").get("busy_input_mode").getString())
                .isEqualTo("steer");
        assertThat(full.get("config").get("display").get("tui_status_indicator").getString())
                .isEqualTo("ascii");
    }

    @Test
    void shouldExposePersistedUsageInCopiedTerminalUiSessionInfo() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:terminal-ui:usage");
        session.setCumulativeInputTokens(10L);
        session.setCumulativeOutputTokens(5L);
        session.setCumulativeReasoningTokens(2L);
        session.setCumulativeCacheReadTokens(3L);
        session.setCumulativeCacheWriteTokens(4L);
        session.setCumulativeTotalTokens(24L);
        env.sessionRepository.save(session);
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode resumed =
                rpcResult(
                        listener,
                        socket,
                        "session.resume",
                        "{\"session_id\":\"" + session.getSessionId() + "\"}");
        ONode usage = resumed.get("info").get("usage");

        assertThat(usage.get("input").getLong()).isEqualTo(10L);
        assertThat(usage.get("output").getLong()).isEqualTo(5L);
        assertThat(usage.get("reasoning").getLong()).isEqualTo(2L);
        assertThat(usage.get("cache_read").getLong()).isEqualTo(3L);
        assertThat(usage.get("cache_write").getLong()).isEqualTo(4L);
        assertThat(usage.get("total").getLong()).isEqualTo(24L);
    }

    @Test
    void shouldEmitSkinChangedEventForCopiedTerminalUiSkinCommand() throws Exception {
        TerminalUiWebSocketListener listener = listener();
        FakeSocket socket = new FakeSocket();

        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\"skin-1\",\"method\":\"config.set\",\"params\":{\"key\":\"skin\",\"value\":\"default\"}}");

        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"id\":\"skin-1\"")
                                        .contains("\"result\"")
                                        .contains("\"value\":\"default\""));
        assertThat(socket.sent)
                .anySatisfy(
                        text ->
                                assertThat(text)
                                        .contains("\"type\":\"skin.changed\"")
                                        .contains("\"agent_name\":\"SolonClaw Agent\"")
                                        .contains("\"colors\":{}"));
    }

    @Test
    void shouldToggleModelProviderAuthenticationFromCopiedTerminalUiPicker() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode before = rpcResult(listener, socket, "model.options", "{}");
        ONode saved =
                rpcResult(
                        listener,
                        socket,
                        "model.save_key",
                        "{\"slug\":\"default\",\"api_key\":\"sk-solonclaw-test-key\"}");

        assertThat(before.get("providers").get(0).get("authenticated").getBoolean()).isFalse();
        assertThat(saved.get("provider").get("authenticated").getBoolean()).isTrue();
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("apiKey: sk-solonclaw-test-key");
        ONode disconnected =
                rpcResult(listener, socket, "model.disconnect", "{\"slug\":\"default\"}");

        assertThat(disconnected.get("disconnected").getBoolean()).isTrue();
        assertThat(disconnected.get("provider").get("authenticated").getBoolean()).isFalse();
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .doesNotContain("sk-solonclaw-test-key")
                .contains("apiKey: ''");
        assertThat(env.appConfig.getProviders().get("default").getApiKey()).isEmpty();
    }

    @Test
    void shouldPersistModelSwitchFromCopiedTerminalUiOverWebSocket() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode result =
                rpcResult(
                        listener,
                        socket,
                        "config.set",
                        "{\"key\":\"model\",\"value\":\"mimo-v2.5-pro --provider default\",\"session_id\":\"sid-model\"}");
        ONode model = rpcResult(listener, socket, "config.get", "{\"key\":\"model\"}");
        ONode full = rpcResult(listener, socket, "config.get", "{\"key\":\"full\"}");

        assertThat(result.get("value").getString()).isEqualTo("mimo-v2.5-pro");
        assertThat(result.get("info").get("model").getString()).isEqualTo("mimo-v2.5-pro");
        assertThat(model.get("value").getString()).isEqualTo("mimo-v2.5-pro");
        assertThat(model.get("provider").getString()).isEqualTo("default");
        assertThat(full.get("config").get("model").getString()).isEqualTo("mimo-v2.5-pro");
        assertThat(full.get("config").get("provider").getString()).isEqualTo("default");
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("providerKey: default")
                .contains("default: mimo-v2.5-pro");
    }

    @Test
    void shouldPersistRuntimeProviderConfigKeysFromCopiedTerminalUi() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        rpcResult(
                listener,
                socket,
                "config.set",
                "{\"key\":\"providers.default.baseUrl\",\"value\":\"https://api.example.test/v1\"}");
        rpcResult(
                listener,
                socket,
                "config.set",
                "{\"key\":\"providers.default.defaultModel\",\"value\":\"mimo-v2.5-pro\"}");
        rpcResult(
                listener,
                socket,
                "config.set",
                "{\"key\":\"providers.default.dialect\",\"value\":\"openai\"}");
        ONode full = rpcResult(listener, socket, "config.get", "{\"key\":\"full\"}");

        assertThat(full.get("config").get("providers").get("default").get("default_model").getString())
                .isEqualTo("mimo-v2.5-pro");
        assertThat(full.get("config").get("providers").get("default").get("dialect").getString())
                .isEqualTo("openai");
        assertThat(FileUtil.readUtf8String(env.appConfig.getRuntime().getConfigFile()))
                .contains("baseUrl: https://api.example.test/v1")
                .contains("defaultModel: mimo-v2.5-pro")
                .contains("dialect: openai");
    }

    @Test
    void shouldExposeDomesticChannelSetupRpcToCopiedTerminalUi() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        TerminalUiWebSocketListener listener = listener(env);
        FakeSocket socket = new FakeSocket();

        ONode options = rpcResult(listener, socket, "channel.options", "{}");
        assertThat(options.get("channels").size()).isGreaterThanOrEqualTo(6);
        assertThat(options.toJson()).contains("feishu").contains("dingtalk").contains("yuanbao");
        assertThat(options.get("channels").get(0).get("key").getString()).isEqualTo("feishu");
        assertThat(options.get("channels").get(0).get("label").getString()).isNotBlank();
        assertThat(options.get("channels").get(0).get("fields").size()).isGreaterThan(0);

        ONode saved =
                rpcResult(
                        listener,
                        socket,
                        "channel.save",
                        "{\"channel\":\"feishu\",\"values\":{\"enabled\":\"true\",\"appId\":\"cli-app\",\"appSecret\":\"cli-secret\"}}");

        assertThat(saved.get("ok").getBoolean()).isTrue();
        assertThat(saved.get("saved").getBoolean()).isTrue();
        assertThat(saved.get("channel").getString()).isEqualTo("feishu");
        assertThat(saved.get("status").getString()).isEqualTo("configured");
        assertThat(saved.get("values").get("appSecret").getString()).isEqualTo("***");
    }

    private TerminalUiWebSocketListener listener() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        return listener(env);
    }

    private TerminalUiWebSocketListener listener(TestEnvironment env) {
        return listener(env, env.delegationService);
    }

    private TerminalUiWebSocketListener listener(
            TestEnvironment env, DelegationService delegationService) {
        com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService securityPolicyService =
                new com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService(env.appConfig);
        com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore preferenceStore =
                new com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore(env.sqliteDatabase);
        return new TerminalUiWebSocketListener(
                new CliRuntime(env.commandService, env.conversationOrchestrator),
                env.appConfig,
                env.sessionRepository,
                securityPolicyService,
                env.processRegistry,
                env.dangerousCommandApprovalService,
                env.localSkillService,
                env.skillHubService,
                env.checkpointService,
                new com.jimuqu.solon.claw.web.DashboardSkillsService(
                        env.localSkillService, preferenceStore),
                preferenceStore,
                new com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService(
                        env.appConfig,
                        java.util.Collections
                                .<com.jimuqu.solon.claw.plugin.provider.BrowserProvider>emptyList(),
                        securityPolicyService),
                env.contextCompressionService,
                new com.jimuqu.solon.claw.cli.CliAttachmentResolver(
                        new com.jimuqu.solon.claw.support.AttachmentCacheService(env.appConfig),
                        securityPolicyService),
                new com.jimuqu.solon.claw.mcp.McpRuntimeService(env.appConfig, env.sqliteDatabase),
                env.gatewayRuntimeRefreshService,
                delegationService,
                env.agentRunControlService,
                env.agentRunRepository,
                env.runtimeSettingsService,
                env.globalSettingRepository);
    }

    private static int indexOf(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static List<RpcCase> frontendRpcCases(String sessionId) {
        List<RpcCase> cases = new ArrayList<RpcCase>();
        String sid = jsonString(sessionId);
        cases.add(rpc("approval.respond", "{\"approved\":true}"));
        cases.add(rpc("browser.manage", "{\"action\":\"status\"}"));
        cases.add(rpc("clarify.respond", "{\"text\":\"ok\"}"));
        cases.add(rpc("clipboard.paste", "{}"));
        cases.add(rpc("command.dispatch", "{\"session_id\":" + sid + ",\"name\":\"status\",\"arg\":\"\"}"));
        cases.add(rpc("commands.catalog", "{}"));
        cases.add(rpc("complete.path", "{\"word\":\"pom\"}"));
        cases.add(rpc("complete.slash", "{\"text\":\"/sta\"}"));
        cases.add(rpc("config.get", "{\"key\":\"full\"}"));
        cases.add(rpc("config.set", "{\"key\":\"display.details_mode\",\"value\":\"collapsed\"}"));
        cases.add(rpc("delegation.pause", "{\"paused\":true}"));
        cases.add(rpc("delegation.status", "{}"));
        cases.add(rpc("image.attach", "{\"path\":\"sample.png\"}"));
        cases.add(rpc("input.detect_drop", "{\"text\":\"plain text\"}"));
        cases.add(rpc("model.disconnect", "{\"slug\":\"default\"}"));
        cases.add(rpc("model.options", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("model.save_key", "{\"slug\":\"default\",\"api_key\":\"test\"}"));
        cases.add(rpc("paste.collapse", "{\"text\":\"hello\"}"));
        cases.add(rpc("process.stop", "{}"));
        cases.add(rpc("prompt.background", "{\"session_id\":" + sid + ",\"text\":\"hello\"}"));
        cases.add(rpc("prompt.submit", "{\"session_id\":" + sid + ",\"text\":\"\"}"));
        cases.add(rpc("reload.env", "{}"));
        cases.add(rpc("reload.mcp", "{}"));
        cases.add(rpc("rollback.diff", "{\"session_id\":" + sid + ",\"checkpoint_id\":\"cp\"}"));
        cases.add(rpc("rollback.list", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("rollback.restore", "{\"session_id\":" + sid + ",\"checkpoint_id\":\"cp\"}"));
        cases.add(rpc("secret.respond", "{\"value\":\"secret\"}"));
        cases.add(rpc("session.activate", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.active_list", "{\"current_session_id\":" + sid + "}"));
        cases.add(rpc("session.branch", "{\"session_id\":" + sid + ",\"name\":\"branch\"}"));
        cases.add(rpc("session.close", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.compress", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.create", "{\"cols\":80}"));
        cases.add(rpc("session.delete", "{\"session_id\":\"missing-delete\"}"));
        cases.add(rpc("session.interrupt", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.list", "{\"limit\":20}"));
        cases.add(rpc("session.most_recent", "{}"));
        cases.add(rpc("session.resume", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.save", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.status", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.steer", "{\"session_id\":" + sid + ",\"text\":\"stop\"}"));
        cases.add(rpc("session.title", "{\"session_id\":" + sid + ",\"title\":\"TUI\"}"));
        cases.add(rpc("session.undo", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("session.usage", "{\"session_id\":" + sid + "}"));
        cases.add(rpc("setup.status", "{}"));
        cases.add(rpc("shell.exec", "{\"command\":\"printf tui-rpc\"}"));
        cases.add(rpc("skills.manage", "{\"action\":\"list\"}"));
        cases.add(rpc("skills.reload", "{}"));
        cases.add(rpc("slash.exec", "{\"session_id\":" + sid + ",\"command\":\"status\"}"));
        cases.add(rpc("spawn_tree.list", "{}"));
        cases.add(rpc("spawn_tree.load", "{\"id\":\"tree\"}"));
        cases.add(rpc("spawn_tree.save", "{}"));
        cases.add(rpc("subagent.interrupt", "{\"subagent_id\":\"child\"}"));
        cases.add(rpc("sudo.respond", "{\"approved\":true}"));
        cases.add(rpc("terminal.resize", "{\"cols\":100,\"rows\":30}"));
        cases.add(rpc("tools.configure", "{\"action\":\"status\",\"names\":[]}"));
        cases.add(rpc("voice.record", "{\"action\":\"stop\"}"));
        cases.add(rpc("voice.toggle", "{\"action\":\"status\"}"));
        return cases;
    }

    private static RpcCase rpc(String method, String params) {
        return new RpcCase(method, params);
    }

    private static ONode rpcResult(
            TerminalUiWebSocketListener listener, FakeSocket socket, String method, String params)
            throws Exception {
        String id = "shape-" + socket.sent.size();
        int before = socket.sent.size();
        listener.onMessage(
                socket,
                "{\"jsonrpc\":\"2.0\",\"id\":\""
                        + id
                        + "\",\"method\":\""
                        + method
                        + "\",\"params\":"
                        + params
                        + "}");
        return ONode.ofJson(rpcResponse(socket.sent, id, before)).get("result");
    }

    private static String rpcResponse(List<String> sent, String id, int fromIndex) {
        for (int i = fromIndex; i < sent.size(); i++) {
            String text = sent.get(i);
            ONode node = ONode.ofJson(text);
            if (id.equals(node.get("id").getString())) {
                return text;
            }
        }
        throw new AssertionError("missing RPC response for " + id);
    }

    /** 等待异步 WebSocket 事件写入测试 socket，避免后台 prompt worker 与断言竞态。 */
    private static void waitForSent(FakeSocket socket, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            synchronized (socket.sent) {
                for (String text : socket.sent) {
                    if (text.contains(expected)) {
                        return;
                    }
                }
            }
            Thread.sleep(25L);
        }
        assertThat(socket.sent).anySatisfy(text -> assertThat(text).contains(expected));
    }

    private static String jsonString(String value) {
        return "\"" + jsonEscape(value) + "\"";
    }

    private static String jsonEscape(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class RpcCase {
        private final String method;
        private final String params;

        private RpcCase(String method, String params) {
            this.method = method;
            this.params = params;
        }
    }

    private static class RecordingDelegationService implements DelegationService {
        private final List<Map<String, Object>> active = new ArrayList<Map<String, Object>>();
        private final List<String> interruptible = new ArrayList<String>();
        private boolean paused;

        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context) {
            return null;
        }

        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks) {
            return Collections.emptyList();
        }

        @Override
        public void setSpawnPaused(boolean paused) {
            this.paused = paused;
        }

        @Override
        public boolean isSpawnPaused() {
            return paused;
        }

        @Override
        public boolean interruptSubagent(String subagentId) {
            return interruptible.contains(subagentId);
        }

        @Override
        public List<Map<String, Object>> activeSubagents() {
            return active;
        }
    }

    /** 测试用运行时，用阻塞模型请求证明 config.get 不会被 prompt.submit 卡住。 */
    private static class BlockingCliRuntime extends CliRuntime {
        /** 释放后台 prompt worker 的信号。 */
        private final CompletableFuture<Void> release = new CompletableFuture<Void>();
        /** 标记后台 prompt worker 已经开始执行。 */
        private volatile boolean started;

        /** 创建阻塞型测试运行时。 */
        private BlockingCliRuntime() {
            super(null, null);
        }

        /** 模拟一个长时间运行的模型请求，直到测试主动释放。 */
        @Override
        public GatewayReply send(String sessionId, String input, ConversationEventSink eventSink)
                throws Exception {
            started = true;
            eventSink.onRunStarted(sessionId);
            release.get(5L, TimeUnit.SECONDS);
            eventSink.onRunCompleted(sessionId, "echo:" + input, null);
            GatewayReply reply = GatewayReply.ok("echo:" + input);
            reply.setSessionId(sessionId);
            return reply;
        }
    }

    /** 测试用运行时，用 Error 验证 WebSocket worker 不会被后端异常击穿。 */
    private static class FailingCliRuntime extends CliRuntime {
        /** 创建失败型测试运行时。 */
        private FailingCliRuntime() {
            super(null, null);
        }

        /** 模拟非 Exception 的严重错误，覆盖 WebSocket worker 的 Throwable 保护。 */
        @Override
        public GatewayReply send(String sessionId, String input, ConversationEventSink eventSink) {
            throw new LinkageError("simulated linkage failure");
        }
    }

    private static class FakeSocket implements WebSocket {
        private final List<String> sent =
                Collections.synchronizedList(new ArrayList<String>());
        private final MultiMap<String> params = new MultiMap<String>(true);
        private final Map<String, Object> attrs = new java.util.LinkedHashMap<String, Object>();
        private final InetSocketAddress remoteAddress;
        private boolean closed;
        private int closeCode;
        private String closeReason;

        private FakeSocket() {
            this(new InetSocketAddress("127.0.0.1", 10000));
        }

        private FakeSocket(InetSocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
        }

        private static FakeSocket remote(String host) {
            return new FakeSocket(new InetSocketAddress(host, 10000));
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public void nameAs(String name) {}

        @Override
        public boolean isValid() {
            return !closed;
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public String url() {
            String token = params.get("token");
            return token == null ? "ws://127.0.0.1/ws/tui" : "ws://127.0.0.1/ws/tui?token=" + token;
        }

        @Override
        public String path() {
            return "/ws/tui";
        }

        @Override
        public void pathNew(String path) {}

        @Override
        public MultiMap<String> paramMap() {
            return params;
        }

        @Override
        public String param(String name) {
            return params.get(name);
        }

        @Override
        public String paramOrDefault(String name, String def) {
            return params.getOrDefault(name, def);
        }

        @Override
        public void param(String name, String value) {
            params.add(name, value);
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return remoteAddress;
        }

        @Override
        public InetSocketAddress localAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
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
        public <T> T attr(String name) {
            return (T) attrs.get(name);
        }

        @Override
        public <T> T attrOrDefault(String name, T def) {
            return (T) attrs.getOrDefault(name, def);
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
            sent.add(text);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Future<Void> send(ByteBuffer bytes) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void close(int code, String reason) {
            closed = true;
            closeCode = code;
            closeReason = reason;
        }
    }
}
