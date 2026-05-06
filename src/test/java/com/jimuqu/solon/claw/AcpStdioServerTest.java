package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.acp.AcpStdioServer;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

public class AcpStdioServerTest {
    @Test
    void shouldHandleInitializeNewSessionAndPrompt() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String init =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}");
        assertThat(init)
                .contains("\"id\":1")
                .contains("\"protocol_version\":1")
                .contains("\"agent_capabilities\"")
                .contains("\"slash_commands\":true")
                .contains("context")
                .contains("reload-mcp");

        String created =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\"}}");
        assertThat(created).contains("\"id\":2").contains("\"session_id\"");
        String sessionId = extractSessionId(created);

        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello acp\"}]}}");
        assertThat(prompted)
                .contains("\"id\":3")
                .contains("\"stop_reason\":\"end_turn\"")
                .contains("echo:hello acp");
    }

    @Test
    void shouldHandleHermesLifecycleCompatibilityMethods() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String auth =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"auth-1\",\"method\":\"authenticate\",\"params\":{\"method_id\":\"default\"}}");
        assertThat(auth)
                .contains("\"id\":\"auth-1\"")
                .contains("\"authenticated\":false")
                .contains("local runtime credentials");

        String resumed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resume_session\",\"params\":{\"sessionId\":\"missing\",\"cwd\":\"D:/projects/jimuqu-agent\",\"mcp_servers\":[\"editor\"]}}");
        assertThat(resumed)
                .contains("\"id\":4")
                .contains("\"session_id\"")
                .contains("\"mcp_servers\":[\"editor\"]");
        String sessionId = extractSessionId(resumed);

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"list_sessions\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\"}}");
        assertThat(listed).contains("\"id\":5").contains("\"sessions\"").contains(sessionId);

        String forked =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"fork_session\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"cwd\":\"D:/projects/fork\"}}");
        assertThat(forked)
                .contains("\"id\":6")
                .contains("\"cwd\":\"D:/projects/fork\"")
                .contains("\"source_key\":\"MEMORY:cli:");

        String cancelled =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/cancel\",\"params\":{\"sessionId\":\""
                                + sessionId
                                + "\"}}");
        assertThat(cancelled)
                .contains("\"id\":7")
                .contains("\"ok\":true")
                .contains("\"active_run\":false");
    }

    @Test
    void shouldAcceptHermesEditorSessionModelModeAndConfigMethods() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionId = extractSessionId(newAcpSession(server, 30));

        String model =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"set_session_model\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"model_id\":\"default:gpt-test-acp\"}}");
        assertThat(model)
                .contains("\"id\":31")
                .contains("\"ok\":true")
                .contains("\"model_id\":\"default:gpt-test-acp\"")
                .contains("\"modelId\":\"default:gpt-test-acp\"");

        String mode =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"set_session_mode\",\"params\":{\"sessionId\":\""
                                + sessionId
                                + "\",\"mode_id\":\"plan\"}}");
        assertThat(mode)
                .contains("\"id\":32")
                .contains("\"ok\":true")
                .contains("\"mode_id\":\"plan\"")
                .contains("\"modeId\":\"plan\"");

        String config =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"set_config_option\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"config_id\":\"reasoning_effort\",\"value\":\"high\"}}");
        assertThat(config)
                .contains("\"id\":33")
                .contains("\"ok\":true")
                .contains("\"config_id\":\"reasoning_effort\"")
                .contains("\"reasoning_effort\":\"high\"");

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":34,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");
        assertThat(loaded)
                .contains("\"model_id\":\"default:gpt-test-acp\"")
                .contains("\"mode_id\":\"plan\"")
                .contains("\"reasoning_effort\":\"high\"");
    }

    @Test
    void shouldRegisterAcpMcpServersThroughDashboardMcpService() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardMcpService mcpService = new DashboardMcpService(env.appConfig, env.sqliteDatabase);
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        mcpService);

        String created =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"session/new\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\",\"mcp_servers\":[{\"name\":\"editor-fs\",\"command\":\"node\",\"args\":[\"server.js\"],\"tools\":[{\"name\":\"read_file\"}]}]}}");

        assertThat(created)
                .contains("\"id\":10")
                .contains("\"mcp_tool_count\":1")
                .contains("editor-fs");
        Object servers = mcpService.list().get("servers");
        assertThat(String.valueOf(servers))
                .contains("editor-fs")
                .contains("node")
                .contains("read_file");
    }

    @Test
    void shouldIsolateDangerousApprovalBetweenAcpSessions() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionAId = extractSessionId(newAcpSession(server, 20));
        String sessionBId = extractSessionId(newAcpSession(server, 21));
        SessionRecord sessionA = env.sessionRepository.findById(sessionAId);
        SessionRecord sessionB = env.sessionRepository.findById(sessionBId);
        SqliteAgentSession agentSessionA =
                new SqliteAgentSession(sessionA, env.sessionRepository);
        SqliteAgentSession agentSessionB =
                new SqliteAgentSession(sessionB, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSessionA,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        String bApproval =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionBId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"/approve session\"}]}}");

        assertThat(bApproval).contains("\"id\":22").contains("待审批的危险命令");
        assertThat(bApproval).doesNotContain("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(agentSessionA))
                .isNotNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                agentSessionB, "recursive_delete"))
                .isFalse();

        String aApproval =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionAId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"/approve session\"}]}}");
        SessionRecord updatedA = env.sessionRepository.findById(sessionAId);
        SessionRecord updatedB = env.sessionRepository.findById(sessionBId);
        SqliteAgentSession updatedAgentSessionA =
                new SqliteAgentSession(updatedA, env.sessionRepository);
        SqliteAgentSession updatedAgentSessionB =
                new SqliteAgentSession(updatedB, env.sessionRepository);

        assertThat(aApproval).contains("\"id\":23").contains("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSessionA))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSessionA, "recursive_delete"))
                .isTrue();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSessionB, "recursive_delete"))
                .isFalse();
    }

    @Test
    void shouldKeepRequestIdOnDispatchError() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String response =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"bad-1\",\"method\":\"unknown\",\"params\":{}}");
        assertThat(response)
                .contains("\"id\":\"bad-1\"")
                .contains("\"error\"")
                .contains("Unsupported ACP method");
    }

    @Test
    void shouldReadAndWriteContentLengthFrames() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        String framed = "Content-Length: " + payload.length + "\r\n\r\n" + request;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        new ByteArrayInputStream(framed.getBytes(StandardCharsets.UTF_8)),
                        output);

        assertThat(server.run()).isZero();
        String response = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertThat(response).startsWith("Content-Length: ");
        assertThat(response).contains("\"id\":1").contains("\"agent\"");
    }

    @Test
    void shouldLoadAndListPersistedRepositorySessions() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord record = env.sessionRepository.bindNewSession("MEMORY:cli:persisted-acp");
        record.setTitle("Persisted ACP Session");
        record.setNdjson(
                MessageSupport.toNdjson(
                        java.util.Arrays.asList(
                                ChatMessage.ofUser("persisted user"),
                                ChatMessage.ofAssistant("persisted assistant"))));
        record.setUpdatedAt(System.currentTimeMillis());
        env.sessionRepository.save(record);

        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String loaded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"session/load\",\"params\":{\"session_id\":\""
                                + record.getSessionId()
                                + "\",\"cwd\":\"D:/projects/jimuqu-agent\"}}");
        assertThat(loaded)
                .contains("\"id\":8")
                .contains(record.getSessionId())
                .contains("Persisted ACP Session")
                .contains("persisted user")
                .contains("persisted assistant");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session/list\",\"params\":{}}");
        assertThat(listed).contains("\"id\":9").contains(record.getSessionId());
    }

    private String newAcpSession(AcpStdioServer server, int id) {
        return server.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":"
                        + id
                        + ",\"method\":\"session/new\",\"params\":{\"cwd\":\"D:/projects/jimuqu-agent\"}}");
    }

    private String extractSessionId(String json) {
        String marker = "\"session_id\":\"";
        int start = json.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        assertThat(end).isGreaterThan(valueStart);
        return json.substring(valueStart, end);
    }
}
