package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.cli.acp.AcpStdioServer;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

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
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

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
    void shouldApplyAcpSessionModelToFollowingPrompt() throws Exception {
        TestEnvironment env = TestEnvironment.withLlm(new ModelEchoGateway());
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionId = extractSessionId(newAcpSession(server, 35));
        server.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":36,\"method\":\"set_session_model\",\"params\":{\"session_id\":\""
                        + sessionId
                        + "\",\"model_id\":\"default:acp-session-model\"}}");

        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":37,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello model\"}]}}");

        assertThat(prompted)
                .contains("\"id\":37")
                .contains("model=acp-session-model");
        SessionRecord record = env.sessionRepository.findById(sessionId);
        assertThat(record.getModelOverride()).isEqualTo("default:acp-session-model");
        assertThat(record.getLastResolvedModel()).isEqualTo("acp-session-model");
    }

    @Test
    void shouldReturnHermesStylePromptUpdatesAndUsage() throws Exception {
        TestEnvironment env = TestEnvironment.withLlm(new ToolEventGateway());
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        String sessionId = extractSessionId(newAcpSession(server, 38));
        String prompted =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":39,\"method\":\"session/prompt\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"hello updates\"}]}}");

        assertThat(prompted)
                .contains("\"id\":39")
                .contains("\"session_updates\"")
                .contains("\"available_commands_update\"")
                .contains("\"tool_call_start\"")
                .contains("\"tool_call_update\"")
                .contains("\"agent_thought_chunk\"")
                .contains("\"usage_update\"")
                .contains("\"input_tokens\":13")
                .contains("\"total_tokens\":34")
                .contains("\"context_estimate_tokens\"")
                .contains("\"context_window_tokens\"")
                .contains("\"input\":{\"kind\":\"unstructured\"")
                .contains("echo:hello updates")
                .doesNotContain("[tool.start]");
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
    void shouldExposeAndResolveDangerousApprovalThroughAcpPermissionsBridge()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 24));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete with token=sk-proj-abcdefghijklmnopqrstuvwxyz",
                "OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz rm -rf runtime/cache");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"permissions/list_open\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\"}}");

        assertThat(listed)
                .contains("\"id\":25")
                .contains("\"count\":1")
                .contains("\"allow_once\"")
                .contains("\"allow_session\"")
                .contains("\"allow_always\"")
                .contains("OPENAI_API_KEY=***")
                .contains("token=***")
                .doesNotContain("sk-proj-abcdefghijklmnopqrstuvwxyz");
        String approvalId = extractJsonString(listed, "approval_id");

        String responded =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"permissions/respond\",\"params\":{\"session_id\":\""
                                + sessionId
                                + "\",\"id\":\""
                                + approvalId
                                + "\",\"outcome\":\"allow_session\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(responded)
                .contains("\"id\":26")
                .contains("\"ok\":true")
                .contains("\"outcome\":\"session\"")
                .contains("echo:resume");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
                .isTrue();
    }

    @Test
    void shouldDenyDangerousApprovalThroughAcpPermissionsBridge() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AcpStdioServer server =
                new AcpStdioServer(
                        new CliRuntime(env.commandService, env.conversationOrchestrator),
                        env.sessionRepository,
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase),
                        env.dangerousCommandApprovalService);

        String sessionId = extractSessionId(newAcpSession(server, 27));
        SessionRecord session = env.sessionRepository.findById(sessionId);
        SqliteAgentSession agentSession =
                new SqliteAgentSession(session, env.sessionRepository);
        env.dangerousCommandApprovalService.storePendingApproval(
                agentSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        String listed =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":28,\"method\":\"permissions_list_open\",\"params\":{\"sessionId\":\""
                                + sessionId
                                + "\"}}");
        String approvalId = extractJsonString(listed, "approval_id");

        String denied =
                server.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":29,\"method\":\"permissions_respond\",\"params\":{\"sessionId\":\""
                                + sessionId
                                + "\",\"permission_id\":\""
                                + approvalId
                                + "\",\"outcome\":\"reject_once\"}}");

        SessionRecord updated = env.sessionRepository.findById(sessionId);
        SqliteAgentSession updatedAgentSession =
                new SqliteAgentSession(updated, env.sessionRepository);

        assertThat(denied)
                .contains("\"id\":29")
                .contains("\"ok\":true")
                .contains("\"outcome\":\"deny\"");
        assertThat(env.dangerousCommandApprovalService.getPendingApproval(updatedAgentSession))
                .isNull();
        assertThat(
                        env.dangerousCommandApprovalService.isSessionApproved(
                                updatedAgentSession, "recursive_delete"))
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
        return extractJsonString(json, "session_id");
    }

    private String extractJsonString(String json, String key) {
        String marker = "\"session_id\":\"";
        if (!"session_id".equals(key)) {
            marker = "\"" + key + "\":\"";
        }
        int start = json.indexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        assertThat(end).isGreaterThan(valueStart);
        return json.substring(valueStart, end);
    }

    private static class ModelEchoGateway extends FakeLlmGateway {
        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            String response = "model=" + resolved.getModel();
            InMemoryChatSession chatSession = new InMemoryChatSession(session.getSessionId());
            if (session.getNdjson() != null && session.getNdjson().length() > 0) {
                chatSession.loadNdjson(session.getNdjson());
            }
            chatSession.addMessage(ChatMessage.ofUser(userMessage));
            chatSession.addMessage(ChatMessage.ofAssistant(response));

            LlmResult result = new LlmResult();
            result.setAssistantMessage(new AssistantMessage(response));
            result.setNdjson(chatSession.toNdjson());
            result.setRawResponse("fake");
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setInputTokens(1L);
            result.setOutputTokens(1L);
            result.setTotalTokens(2L);
            return result;
        }
    }

    private static class ToolEventGateway extends FakeLlmGateway {
        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                java.util.List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            java.util.Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
            args.put("command", "echo updates");
            eventSink.onReasoningDelta("thinking");
            eventSink.onToolStarted("terminal", args);
            eventSink.onToolCompleted("terminal", "{\"ok\":true}", 12L);

            LlmResult result = super.executeOnce(
                    session,
                    systemPrompt,
                    userMessage,
                    toolObjects,
                    feedbackSink,
                    eventSink,
                    resume,
                    resolved,
                    runContext);
            session.setLastInputTokens(33L);
            session.setLastOutputTokens(44L);
            session.setLastTotalTokens(77L);
            session.setLastReasoningTokens(5L);
            session.setLastCacheReadTokens(6L);
            session.setLastCacheWriteTokens(7L);
            return result;
        }
    }
}
