package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.AgentTools;
import com.jimuqu.solon.claw.web.DashboardAgentService;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

public class AgentMechanismTest {
    @Test
    void shouldSwitchCurrentSessionAgentAndKeepDefaultAtRuntimeRoot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "agent-room", "agent-user");
        String sourceKey = "MEMORY:agent-room:agent-user";

        GatewayReply create = env.send("agent-room", "agent-user", "/agent create coder 你是代码助手。");
        assertThat(create.getContent()).contains("已创建 Agent：coder");
        assertThat(new File(env.appConfig.getRuntime().getHome(), "agents/coder/workspace"))
                .isDirectory();

        GatewayReply useCoder = env.send("agent-room", "agent-user", "/agent coder");
        assertThat(useCoder.getContent()).contains("已切换当前会话 Agent 为：coder");
        SessionRecord current = env.sessionRepository.getBoundSession(sourceKey);
        assertThat(current.getActiveAgentName()).isEqualTo("coder");

        GatewayReply useDefault = env.send("agent-room", "agent-user", "/agent default");
        assertThat(useDefault.getContent()).contains("已切换当前会话 Agent 为：default");
        SessionRecord reset = env.sessionRepository.getBoundSession(sourceKey);
        assertThat(reset.getActiveAgentName()).isNull();
        assertThat(new File(env.appConfig.getRuntime().getHome(), "agents/default")).doesNotExist();
    }

    @Test
    void shouldSwitchOnlyTheCurrentSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "room-a", "user-a");
        env.send("room-b", "user-a", "hello");
        env.send("room-a", "user-a", "/agent create coder 你是代码助手。");

        env.send("room-a", "user-a", "/agent coder");

        SessionRecord sessionA = env.sessionRepository.getBoundSession("MEMORY:room-a:user-a");
        SessionRecord sessionB = env.sessionRepository.getBoundSession("MEMORY:room-b:user-a");
        assertThat(sessionA.getActiveAgentName()).isEqualTo("coder");
        assertThat(sessionB.getActiveAgentName()).isNull();
    }

    @Test
    void shouldRejectDefaultMutation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "default-room", "default-user");

        GatewayReply model =
                env.send("default-room", "default-user", "/agent model default gpt-5.2");
        assertThat(model.getContent()).contains("default 是内置 Agent");

        GatewayReply delete = env.send("default-room", "default-user", "/agent delete default");
        assertThat(delete.getContent()).contains("default 是内置 Agent");
        assertThat(new File(env.appConfig.getRuntime().getHome(), "agents/default")).doesNotExist();
    }

    @Test
    void shouldUseAgentModelUnlessSessionOverrideExistsAndRecordSnapshot() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        claimAdmin(env, "model-room", "model-user");
        env.send("model-room", "model-user", "/agent create coder 你是代码助手。");
        env.send("model-room", "model-user", "/agent model coder agent-model");
        env.send("model-room", "model-user", "/agent coder");

        GatewayReply first = env.send("model-room", "model-user", "hello");
        assertThat(first.getContent()).contains("model=agent-model");
        assertThat(gateway.models).contains("agent-model");

        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:model-room:model-user");
        List<AgentRunRecord> runs =
                env.agentRunRepository.listBySession(session.getSessionId(), 10);
        assertThat(runs).isNotEmpty();
        assertThat(runs.get(0).getAgentName()).isEqualTo("coder");
        assertThat(runs.get(0).getAgentSnapshotJson()).contains("\"agent_name\":\"coder\"");
        assertThat(runs.get(0).getAgentSnapshotJson())
                .contains("\"default_model\":\"agent-model\"");
        assertThat(runs.get(0).getModel()).isEqualTo("agent-model");
        assertThat(runs.get(0).getAgentSnapshotJson()).doesNotContain("config.yml");
        assertThat(runs.get(0).getAgentSnapshotJson())
                .contains("\"workspace_dir\":\"agent://coder/workspace\"")
                .contains("\"agent_file_path\":\"agent://coder/AGENT.md\"")
                .contains("\"memory_file_path\":\"agent://coder/MEMORY.md\"")
                .doesNotContain(env.appConfig.getRuntime().getHome());

        env.send("model-room", "model-user", "/model default:session-model");
        GatewayReply second = env.send("model-room", "model-user", "again");
        assertThat(second.getContent()).contains("model=session-model");
        assertThat(gateway.models).contains("session-model");
    }

    @Test
    void shouldUseNamedAgentRoleAndWorkspace() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        TestEnvironment env = TestEnvironment.withLlm(gateway);
        claimAdmin(env, "scope-room", "scope-user");
        env.send("scope-room", "scope-user", "/agent create coder 你是严格的代码审查 Agent。");
        env.send("scope-room", "scope-user", "/agent coder");

        GatewayReply reply = env.send("scope-room", "scope-user", "check");

        String expectedWorkspace =
                FileUtil.file(env.appConfig.getRuntime().getHome(), "agents", "coder", "workspace")
                        .getAbsolutePath();
        assertThat(gateway.lastSystemPrompt).contains("你是严格的代码审查 Agent。");
        assertThat(gateway.workspaces).contains(expectedWorkspace);
        assertThat(reply.getRuntimeMetadata().get("cwd")).isEqualTo(expectedWorkspace);
    }

    @Test
    void shouldApplyAgentToolAllowlist() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.agentProfileService.createAgent("minimal", "只读 Agent");
        env.send("tools-room", "tools-user", "hello");
        env.send("tools-room", "tools-user", "/pairing claim-admin");
        env.send("tools-room", "tools-user", "/agent tools minimal read_file,skills_list");
        env.send("tools-room", "tools-user", "/agent minimal");

        AgentRuntimeScope scope =
                env.agentRuntimeService.resolve(
                        env.sessionRepository.getBoundSession("MEMORY:tools-room:tools-user"));
        List<String> names =
                env.toolRegistry.resolveEnabledToolNames("MEMORY:tools-room:tools-user", scope);
        String joinedTools =
                env.toolRegistry
                        .resolveEnabledTools("MEMORY:tools-room:tools-user", scope)
                        .toString();

        assertThat(names).containsExactly("read_file", "skills_list");
        assertThat(joinedTools).contains("SolonClawFileReadWriteSkill", "SkillsListTool");
        assertThat(joinedTools).doesNotContain("ShellTalent", "WebsearchTalent", "TodoTools");
    }

    @Test
    void shouldCleanSessionsWhenDashboardDeletesActiveAgent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        claimAdmin(env, "delete-room", "delete-user");
        env.send("delete-room", "delete-user", "/agent create coder 你是代码助手。");
        env.send("delete-room", "delete-user", "/agent coder");
        SessionRecord session =
                env.sessionRepository.getBoundSession("MEMORY:delete-room:delete-user");
        assertThat(session.getActiveAgentName()).isEqualTo("coder");

        dashboardAgentService(env).delete("coder");

        SessionRecord updated = env.sessionRepository.findById(session.getSessionId());
        assertThat(updated.getActiveAgentName()).isNull();
        assertThat(env.agentRuntimeService.resolve(updated).getEffectiveName())
                .isEqualTo("default");
    }

    @Test
    void shouldRejectDisabledDashboardActivation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.agentProfileService.createAgent("sleepy", "停用 Agent");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("enabled", Boolean.FALSE);
        dashboardAgentService(env).update("sleepy", body);

        Map<String, Object> activate = new LinkedHashMap<String, Object>();
        activate.put("session_id", "new-dashboard-session");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
                            @Override
                            public void call() throws Throwable {
                                dashboardAgentService(env).activate("sleepy", activate);
                            }
                        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent 已停用");
    }

    @Test
    void shouldActivateAgentForUnpersistedDashboardSession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.agentProfileService.createAgent("coder", "你是代码助手。");
        Map<String, Object> activate = new LinkedHashMap<String, Object>();
        activate.put("session_id", "local-empty-session");

        dashboardAgentService(env).activate("coder", activate);

        SessionRecord session = env.sessionRepository.findById("local-empty-session");
        assertThat(session).isNotNull();
        assertThat(session.getSourceKey()).isEqualTo("MEMORY:dashboard:local-empty-session");
        assertThat(session.getActiveAgentName()).isEqualTo("coder");
    }

    @Test
    void shouldRedactSecretLikeDashboardAgentRunIdentifiers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.agentProfileService.createAgent("coder", "你是代码助手。");
        SessionRecord session = new SessionRecord();
        session.setSessionId("session-ghp_agentdashsession12345");
        session.setSourceKey("MEMORY:agent-dash:user");
        session.setBranchName("main");
        session.setNdjson("");
        session.setCreatedAt(100L);
        session.setUpdatedAt(100L);
        env.sessionRepository.save(session);

        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-ghp_agentdashrun12345");
        run.setSessionId(session.getSessionId());
        run.setSourceKey(session.getSourceKey());
        run.setAgentName("coder");
        run.setStatus("running");
        run.setModel("model-token=ghp_agentdashmodel12345");
        run.setStartedAt(100L);
        env.agentRunRepository.saveRun(run);

        DashboardAgentService service = dashboardAgentService(env);
        String detailText = String.valueOf(service.get("coder", null));

        Map<String, Object> activate = new LinkedHashMap<String, Object>();
        activate.put("session_id", "session-ghp_agentactivate12345");
        String activateText = String.valueOf(service.activate("coder", activate));

        assertThat(detailText)
                .contains("run_id=run-ghp_***")
                .contains("session_id=session-ghp_***")
                .contains("model=model-token=***")
                .doesNotContain("agentdashrun12345")
                .doesNotContain("agentdashsession12345")
                .doesNotContain("agentdashmodel12345");
        assertThat(activateText)
                .contains("session_id=session-ghp_***")
                .doesNotContain("agentactivate12345");
    }

    @Test
    void dashboardListShouldHideBuiltinDefaultAgent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.agentProfileService.createAgent("coder", "你是代码助手。");

        Map<String, Object> response = dashboardAgentService(env).list(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) response.get("agents");
        assertThat(agents).extracting(agent -> agent.get("name")).containsExactly("coder");
        assertThat(response.get("active_agent_name")).isEqualTo("default");
    }

    @Test
    void shouldExposeAgentManageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("tool-agent-room", "tool-agent-user", "hello");

        String joined =
                env.toolRegistry
                        .resolveEnabledTools("MEMORY:tool-agent-room:tool-agent-user")
                        .toString();

        assertThat(env.toolRegistry.listToolNames()).contains("agent_manage");
        assertThat(joined).contains("AgentTools");
    }

    @Test
    void shouldRedactSecretsFromAgentToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AgentTools tools =
                new AgentTools(
                        env.agentProfileService,
                        env.sessionRepository,
                        "MEMORY:agent-error-room:agent-error-user");

        String response = tools.agentManage("show missing-ghp_1234567890abcdef");

        assertThat(response)
                .contains("\"status\":\"error\"")
                .contains("missing-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldRedactSecretsFromAgentToolSuccessPreviewOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AgentTools tools =
                new AgentTools(
                        env.agentProfileService,
                        env.sessionRepository,
                        "MEMORY:agent-success-room:agent-success-user");

        tools.agentManage("create operator 角色 Authorization: Bearer ghp_agentrole12345");
        String response = tools.agentManage("show operator");

        assertThat(response)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_agentrole12345");
        assertThat(env.agentProfileService.findByName("operator").getRolePrompt())
                .contains("ghp_agentrole12345");
    }

    @Test
    void shouldAllowAgentManageToolThroughAgentAllowlist() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.agentProfileService.createAgent("operator", "管理 Agent");
        env.send("allow-agent-room", "allow-agent-user", "hello");
        env.send("allow-agent-room", "allow-agent-user", "/pairing claim-admin");
        env.send("allow-agent-room", "allow-agent-user", "/agent tools operator agent");
        env.send("allow-agent-room", "allow-agent-user", "/agent operator");

        AgentRuntimeScope scope =
                env.agentRuntimeService.resolve(
                        env.sessionRepository.getBoundSession(
                                "MEMORY:allow-agent-room:allow-agent-user"));
        List<String> names =
                env.toolRegistry.resolveEnabledToolNames(
                        "MEMORY:allow-agent-room:allow-agent-user", scope);

        assertThat(names).containsExactly("agent_manage");
        assertThat(
                        env.toolRegistry.resolveEnabledTools(
                                "MEMORY:allow-agent-room:allow-agent-user", scope))
                .hasOnlyElementsOfType(AgentTools.class);
    }

    private static void claimAdmin(TestEnvironment env, String room, String user) throws Exception {
        env.send(room, user, "hello");
        env.send(room, user, "/pairing claim-admin");
    }

    private static DashboardAgentService dashboardAgentService(TestEnvironment env) {
        return new DashboardAgentService(
                env.agentProfileService,
                env.agentRuntimeService,
                env.sessionRepository,
                env.agentRunRepository);
    }

    private static class RecordingGateway extends FakeLlmGateway {
        private final java.util.List<String> models = new java.util.ArrayList<String>();
        private final java.util.List<String> workspaces = new java.util.ArrayList<String>();

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
                com.jimuqu.solon.claw.core.model.AgentRunContext runContext)
                throws Exception {
            lastSystemPrompt = systemPrompt;
            models.add(resolved.getModel());
            workspaces.add(runContext.getWorkspaceDir());

            InMemoryChatSession chatSession = new InMemoryChatSession(session.getSessionId());
            if (StrUtil.isNotBlank(session.getNdjson())) {
                chatSession.addMessage(ChatMessage.fromNdjson(session.getNdjson()));
            }
            chatSession.addMessage(ChatMessage.ofUser(userMessage));
            chatSession.addMessage(ChatMessage.ofAssistant("model=" + resolved.getModel()));

            LlmResult result = new LlmResult();
            result.setAssistantMessage(new AssistantMessage("model=" + resolved.getModel()));
            result.setNdjson(ChatMessage.toNdjson(chatSession.getMessages()));
            result.setRawResponse("fake");
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setInputTokens(1L);
            result.setOutputTokens(1L);
            result.setTotalTokens(2L);
            return result;
        }
    }
}
