package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGlobalSettingRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.support.ToolMessageStatusSupport;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tui.TerminalUiPendingAttachmentService;
import com.jimuqu.solon.claw.tui.TerminalUiRpcService;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;

class TerminalUiRpcServiceTest {

    /** 创建仅注入压缩服务的 TerminalUiRpcService。 */
    private static TerminalUiRpcService compressionRpcService(
            AppConfig config,
            SqliteSessionRepository sessions,
            RecordingCompressionService compression) {
        return new TerminalUiRpcService(
                config,
                sessions,
                null,
                null,
                null,
                null,
                null,
                null,
                compression,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** 使用 TestEnvironment 全部服务创建 TerminalUiRpcService。 */
    private static TerminalUiRpcService envRpcService(TestEnvironment env) {
        return new TerminalUiRpcService(
                env.appConfig,
                env.sessionRepository,
                env.localSkillService,
                env.skillHubService,
                env.checkpointService,
                null,
                null,
                null,
                env.contextCompressionService,
                null,
                env.processRegistry,
                null,
                env.gatewayRuntimeRefreshService,
                env.delegationService,
                env.agentRunControlService,
                env.agentRunRepository,
                env.runtimeSettingsService,
                env.globalSettingRepository);
    }

    /** 验证恢复不存在的会话会明确失败，不伪造空会话响应。 */
    @Test
    void sessionResumeRejectsMissingSession() throws Exception {
        AppConfig config = testConfig();
        SqliteSessionRepository sessions = new SqliteSessionRepository(new SqliteDatabase(config));
        TerminalUiRpcService service = new TerminalUiRpcService(config, sessions);

        IllegalArgumentException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> service.sessionResume("missing-session"));

        assertThat(error.getMessage()).isEqualTo("session not found: missing-session");
    }

    @Test
    void sessionResumeIncludesLiveRunFields() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SqliteAgentRunRepository runs = new SqliteAgentRunRepository(database);
        SessionRecord session = session("session-live", "MEMORY:terminal-ui:session-live");
        sessions.save(session);

        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-live");
        run.setSessionId(session.getSessionId());
        run.setSourceKey(session.getSourceKey());
        run.setStatus("waiting_approval");
        run.setInputPreview("等待审批的用户请求");
        run.setFinalReplyPreview("已生成的回复片段");
        run.setStartedAt(1_800_000_000_000L);
        runs.saveRun(run);

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config, sessions, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, runs, null, null);

        Map<String, Object> response = service.sessionResume(session.getSessionId());

        assertThat(response.get("running")).isEqualTo(Boolean.TRUE);
        assertThat(response.get("status")).isEqualTo("waiting");
        assertThat(response.get("started_at")).isEqualTo(1_800_000_000L);
        Map<String, Object> inflight = (Map<String, Object>) response.get("inflight");
        assertThat(inflight)
                .containsEntry("user", "等待审批的用户请求")
                .containsEntry("assistant", "已生成的回复片段")
                .containsEntry("streaming", Boolean.FALSE);
    }

    /** 会话恢复必须保留持久化工具失败状态和用于终端展示的错误摘要。 */
    @Test
    void sessionResumePreservesPersistedToolFailureStatus() throws Exception {
        AppConfig config = testConfig();
        SqliteSessionRepository sessions = new SqliteSessionRepository(new SqliteDatabase(config));
        SessionRecord session =
                session("session-tool-error", "MEMORY:terminal-ui:session-tool-error");
        ToolMessage failed =
                ChatMessage.ofTool(
                        "{\"status\":\"error\",\"summary\":\"command failed\",\"error\":\"command failed\"}",
                        "execute_shell",
                        "call-shell");
        ToolMessageStatusSupport.mark(failed, true);
        session.setNdjson(
                MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("run command"), failed)));
        sessions.save(session);

        Map<String, Object> response =
                new TerminalUiRpcService(config, sessions).sessionResume(session.getSessionId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");
        assertThat(messages.get(1))
                .containsEntry("role", "tool")
                .containsEntry("name", "execute_shell")
                .containsEntry("status", "error")
                .containsEntry("preview", "command failed")
                .containsEntry("error", "command failed");
    }

    /** 恢复历史消息时，助手正文中的私有思考块不能作为普通 transcript 文本再次显示。 */
    @Test
    void sessionResumeHidesAssistantThinkBlock() throws Exception {
        AppConfig config = testConfig();
        SqliteSessionRepository sessions = new SqliteSessionRepository(new SqliteDatabase(config));
        SessionRecord session = session("session-think", "MEMORY:terminal-ui:session-think");
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(
                                ChatMessage.ofUser("continue"),
                                new AssistantMessage("<think>secret</think>answer"))));
        sessions.save(session);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>)
                        new TerminalUiRpcService(config, sessions)
                                .sessionResume(session.getSessionId())
                                .get("messages");

        assertThat(messages.get(1))
                .containsEntry("role", "assistant")
                .containsEntry("text", "answer");
    }

    @Test
    void activeSessionsReturnsAllTuiLiveSessionsAndHonorsClose() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SqliteAgentRunRepository runs = new SqliteAgentRunRepository(database);
        sessions.save(session("session-a", "MEMORY:terminal-ui:session-a"));
        sessions.save(session("session-b", "MEMORY:terminal-ui:session-b"));
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-session-b");
        run.setSessionId("session-b");
        run.setSourceKey("MEMORY:terminal-ui:session-b");
        run.setStatus("waiting_approval");
        run.setStartedAt(1_800_000_000_000L);
        runs.saveRun(run);

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config, sessions, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, runs, null, null);
        service.sessionResume("session-a");
        service.sessionResume("session-b");

        List<Map<String, Object>> live = activeSessionItems(service.activeSessions("session-a"));

        assertThat(live)
                .extracting(item -> item.get("id"))
                .containsExactly("session-a", "session-b");
        assertThat(live.get(0))
                .containsEntry("current", Boolean.TRUE)
                .containsEntry("status", "idle");
        assertThat(live.get(1))
                .containsEntry("current", Boolean.FALSE)
                .containsEntry("status", "waiting");

        service.sessionClose("session-b");

        assertThat(activeSessionItems(service.activeSessions("session-a")))
                .extracting(item -> item.get("id"))
                .containsExactly("session-a");
    }

    @Test
    void sessionUsageIncludesActiveSubagentCount() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SessionRecord session = session("session-usage", "MEMORY:terminal-ui:session-usage");
        sessions.save(session);

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config,
                        sessions,
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
                        new FixedDelegationService(2),
                        null,
                        null,
                        null,
                        null);

        assertThat(service.sessionUsage(session.getSessionId()).get("active_subagents"))
                .isEqualTo(2);
    }

    /** 验证 TUI 用量面板按同一会话的多次模型运行累计 API 调用次数。 */
    @Test
    void sessionUsageCountsRunsWithUsage() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SqliteAgentRunRepository runs = new SqliteAgentRunRepository(database);
        SessionRecord session =
                session("session-usage-calls", "MEMORY:terminal-ui:session-usage-calls");
        session.setCumulativeInputTokens(76L);
        session.setCumulativeOutputTokens(12L);
        session.setCumulativeTotalTokens(88L);
        sessions.save(session);
        runs.saveRun(run("run-usage-1", session.getSessionId(), 38L, 6L));
        runs.saveRun(run("run-usage-2", session.getSessionId(), 38L, 6L));

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config, sessions, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, runs, null, null);

        Map<String, Object> usage = service.sessionUsage(session.getSessionId());

        assertThat(usage.get("calls")).isEqualTo(2L);
        assertThat(usage.get("input")).isEqualTo(76L);
        assertThat(usage.get("output")).isEqualTo(12L);
        assertThat(usage.get("total")).isEqualTo(88L);
    }

    /** 失败的真实模型请求没有 token 用量时，TUI 仍应显示已经发生过 API 调用。 */
    @Test
    void sessionUsageCountsFailedModelAttemptsWithoutTokens() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SqliteAgentRunRepository runs = new SqliteAgentRunRepository(database);
        SessionRecord session =
                session(
                        "session-usage-failed-call",
                        "MEMORY:terminal-ui:session-usage-failed-call");
        sessions.save(session);
        AgentRunRecord failed = new AgentRunRecord();
        failed.setRunId("run-usage-failed-call");
        failed.setSessionId(session.getSessionId());
        failed.setSourceKey(session.getSourceKey());
        failed.setStatus("failed");
        failed.setPhase("failed");
        failed.setProvider("default");
        failed.setModel("mimo-v2.5-pro");
        failed.setAttempts(1);
        failed.setStartedAt(1_800_000_000_000L);
        failed.setFinishedAt(1_800_000_001_000L);
        failed.setError("401 Invalid API Key");
        runs.saveRun(failed);

        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config, sessions, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, runs, null, null);

        Map<String, Object> usage = service.sessionUsage(session.getSessionId());

        assertThat(usage.get("calls")).isEqualTo(1L);
        assertThat(usage.get("input")).isEqualTo(0L);
        assertThat(usage.get("output")).isEqualTo(0L);
        assertThat(usage.get("total")).isEqualTo(0L);
    }

    @Test
    void reloadMcpRequiresExplicitConfirmationWhenPolicyRequiresIt() throws Exception {
        AppConfig config = testConfig();
        config.getApprovals().setMcpReloadConfirm(true);
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        Map<String, Object> response = service.reloadMcp();

        assertThat(response.get("status")).isEqualTo("confirm_required");
        assertThat(response.get("message")).asString().contains("/reload-mcp now");
    }

    @Test
    void reloadMcpRunsWhenConfirmedAndCanRememberAlways() throws Exception {
        AppConfig config = testConfig();
        config.getApprovals().setMcpReloadConfirm(true);
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        Map<String, Object> once = service.reloadMcp(true, false);

        assertThat(once.get("status")).isEqualTo("reloaded");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isTrue();

        Map<String, Object> always = service.reloadMcp(true, true);

        assertThat(always.get("status")).isEqualTo("reloaded");
        assertThat(config.getApprovals().isMcpReloadConfirm()).isFalse();
    }

    @Test
    void compactConfigSetRespectsExplicitOnOffValues() throws Exception {
        AppConfig config = testConfig();
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        assertThat(service.configSet("compact", "on", "").get("value")).isEqualTo("1");
        assertThat(service.configSet("compact", "on", "").get("value")).isEqualTo("1");
        assertThat(service.configSet("compact", "off", "").get("value")).isEqualTo("0");
        assertThat(service.configSet("compact", "off", "").get("value")).isEqualTo("0");
    }

    @Test
    void mouseConfigSetPersistsTrackingPresets() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config,
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
                        null,
                        null,
                        null,
                        new SqliteGlobalSettingRepository(database));

        assertThat(service.configSet("mouse", "wheel", "").get("value")).isEqualTo("wheel");
        assertThat(service.fullConfig().get("config").toString()).contains("mouse_tracking=wheel");
        assertThat(service.configSet("mouse", "buttons", "").get("value")).isEqualTo("buttons");
        assertThat(service.fullConfig().get("config").toString())
                .contains("mouse_tracking=buttons");
        assertThat(service.configSet("mouse", "off", "").get("value")).isEqualTo("off");
        assertThat(service.fullConfig().get("config").toString()).contains("mouse_tracking=off");
    }

    /** 默认模型选择只写入真实 TUI 会话，不得修改 Profile 的全局默认模型。 */
    @Test
    void modelConfigSetPersistsSessionOverrideWithoutChangingGlobalModel() throws Exception {
        AppConfig config = testConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("OpenAI Compatible");
        provider.setBaseUrl("https://example.test/v1");
        provider.setApiKey("sk-test-secret");
        provider.setDefaultModel("mimo-v2.5");
        provider.setModels(java.util.Arrays.asList("mimo-v2.5", "mimo-v2.5-pro"));
        provider.setDialect("openai");
        config.getProviders().put("openai", provider);
        config.getModel().setProviderKey("openai");
        config.getModel().setDefault("mimo-v2.5");
        SqliteSessionRepository sessions = new SqliteSessionRepository(new SqliteDatabase(config));
        sessions.save(session("session-model", "MEMORY:terminal-ui:session-model"));
        TerminalUiRpcService service = new TerminalUiRpcService(config, sessions);

        Map<String, Object> response =
                service.configSet("model", "openai:mimo-v2.5-pro", "session-model");

        assertThat(response)
                .containsEntry("value", "mimo-v2.5-pro")
                .containsEntry("provider", "openai")
                .containsEntry("global", Boolean.FALSE)
                .containsEntry("session_id", "session-model");
        assertThat((Map<String, Object>) response.get("info"))
                .containsEntry("model", "openai:mimo-v2.5-pro");
        assertThat(sessions.findById("session-model").getModelOverride())
                .isEqualTo("openai:mimo-v2.5-pro");
        assertThat(config.getModel().getDefault()).isEqualTo("mimo-v2.5");
        assertThat(service.configValue("model"))
                .containsEntry("value", "mimo-v2.5")
                .containsEntry("provider", "openai");
    }

    /** 只有显式 --global 才持久化 Profile 默认模型，无会话仓储时也应可执行。 */
    @Test
    void modelConfigSetGlobalFlagPersistsProfileDefault() throws Exception {
        AppConfig config = testConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("OpenAI Compatible");
        provider.setBaseUrl("https://example.test/v1");
        provider.setApiKey("sk-test-secret");
        provider.setDefaultModel("mimo-v2.5");
        provider.setModels(java.util.Arrays.asList("mimo-v2.5", "mimo-v2.5-pro"));
        provider.setDialect("openai");
        config.getProviders().put("openai", provider);
        config.getModel().setProviderKey("openai");
        config.getModel().setDefault("mimo-v2.5");
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        Map<String, Object> response =
                service.configSet("model", "openai:mimo-v2.5-pro --global", "");

        assertThat(response)
                .containsEntry("value", "mimo-v2.5-pro")
                .containsEntry("provider", "openai")
                .containsEntry("global", Boolean.TRUE);
        assertThat(config.getModel().getDefault()).isEqualTo("mimo-v2.5-pro");
        assertThat(FileUtil.readUtf8String(new File(config.getRuntime().getHome(), "config.yml")))
                .contains("providerKey: openai")
                .contains("default: mimo-v2.5-pro");
        assertThat(service.configValue("model"))
                .containsEntry("value", "mimo-v2.5-pro")
                .containsEntry("provider", "openai");
        @SuppressWarnings("unchecked")
        Map<String, Object> renderedConfig =
                (Map<String, Object>) service.fullConfig().get("config");
        assertThat(renderedConfig)
                .containsEntry("model", "mimo-v2.5-pro")
                .containsEntry("provider", "openai");
    }

    /** 保存其他 Provider 的 API Key 只能更新该 Provider，不得切换 Profile 默认模型。 */
    @Test
    void modelSaveKeyDoesNotChangeGlobalModel() throws Exception {
        AppConfig config = testConfig();
        AppConfig.ProviderConfig current = new AppConfig.ProviderConfig();
        current.setName("Current");
        current.setBaseUrl("https://current.example.test/v1");
        current.setApiKey("sk-current-secret-value");
        current.setDefaultModel("main-model");
        current.setModels(Collections.singletonList("main-model"));
        current.setDialect("openai");
        AppConfig.ProviderConfig secondary = new AppConfig.ProviderConfig();
        secondary.setName("Secondary");
        secondary.setBaseUrl("https://secondary.example.test/v1");
        secondary.setDefaultModel("fast-model");
        secondary.setModels(Collections.singletonList("fast-model"));
        secondary.setDialect("openai");
        config.getProviders().put("current", current);
        config.getProviders().put("secondary", secondary);
        config.getModel().setProviderKey("current");
        config.getModel().setDefault("main-model");
        File configFile = new File(config.getRuntime().getHome(), "config.yml");
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  current:\n"
                        + "    name: Current\n"
                        + "    baseUrl: https://current.example.test/v1\n"
                        + "    defaultModel: main-model\n"
                        + "    models:\n"
                        + "      - main-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: current\n"
                        + "  default: main-model\n",
                configFile);
        TerminalUiRpcService service = new TerminalUiRpcService(config);

        Map<String, Object> response =
                service.modelSaveKey(
                        "secondary", "sk-secondary-secret-value-123456", "session-model");

        assertThat(response)
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("session_id", "session-model");
        assertThat(config.getModel().getProviderKey()).isEqualTo("current");
        assertThat(config.getModel().getDefault()).isEqualTo("main-model");
        assertThat(FileUtil.readUtf8String(configFile))
                .contains("providerKey: current", "default: main-model", "secondary:");
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>) response.get("provider");
        assertThat(provider)
                .containsEntry("slug", "secondary")
                .containsEntry("is_current", Boolean.FALSE);
    }

    @Test
    void sessionCompressPassesFocusTopicToCompressionService() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SessionRecord session = session("session-compress", "MEMORY:terminal-ui:session-compress");
        session.setNdjson(
                MessageSupport.toNdjson(Arrays.asList(ChatMessage.ofUser("需要压缩的发布流程上下文"))));
        sessions.save(session);
        RecordingCompressionService compression = new RecordingCompressionService();
        TerminalUiRpcService service = compressionRpcService(config, sessions, compression);

        service.sessionCompress(session.getSessionId(), "发布流程");

        assertThat(compression.focus).isEqualTo("发布流程");
    }

    @Test
    void sessionCompressSkipsBackendCompressionForEmptySession() throws Exception {
        AppConfig config = testConfig();
        SqliteDatabase database = new SqliteDatabase(config);
        SqliteSessionRepository sessions = new SqliteSessionRepository(database);
        SessionRecord session =
                session("session-compress-empty", "MEMORY:terminal-ui:session-compress-empty");
        sessions.save(session);
        RecordingCompressionService compression = new RecordingCompressionService();
        TerminalUiRpcService service = compressionRpcService(config, sessions, compression);

        Map<String, Object> response = service.sessionCompress(session.getSessionId(), "");

        assertThat(compression.compressNowCalls).isZero();
        assertThat(response.get("removed")).isEqualTo(0);
        assertThat(response.get("summary").toString()).contains("headline=nothing to compress");
    }

    /** TUI 直连入口在会话运行中不得压缩、创建分支或恢复完整 checkpoint。 */
    @Test
    void destructiveSessionRpcRejectsActiveRun() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sessionId = "tui-busy";
        String sourceKey = "MEMORY:terminal-ui:" + sessionId;
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        session.setSessionId(sessionId);
        session.setSourceKey(sourceKey);
        env.sessionRepository.save(session);
        TerminalUiRpcService service = envRpcService(env);
        AgentRunSupervisor supervisor = (AgentRunSupervisor) env.agentRunControlService;
        supervisor.coordinateIncoming(
                sourceKey, sessionId, env.message("terminal-ui", sessionId, "run"));

        Map<String, Object> compress = service.sessionCompress(sessionId, "");
        Map<String, Object> branch = service.sessionBranch(sessionId, "blocked");
        Map<String, Object> rollback = service.rollbackRestore(sessionId, "missing", "");

        assertThat(compress)
                .containsEntry("success", Boolean.FALSE)
                .containsEntry("status", "running");
        assertThat(branch)
                .containsEntry("success", Boolean.FALSE)
                .containsEntry("status", "running");
        assertThat(rollback)
                .containsEntry("success", Boolean.FALSE)
                .containsEntry("status", "running");
        supervisor.releaseIncomingReservation(sourceKey);
    }

    /** TUI 完整 rollback 返回真实历史裁剪数量，并同步持久化会话。 */
    @Test
    void rollbackRestoreRemovesLastSessionTurn() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:terminal-ui:rollback";
        SessionRecord session = env.sessionRepository.bindNewSession(sourceKey);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("change"), new AssistantMessage("done"))));
        env.sessionRepository.save(session);
        File file = FileUtil.file(env.appConfig.getRuntime().getCacheDir(), "tui-rollback.txt");
        FileUtil.writeUtf8String("v1", file);
        String checkpointId =
                env.checkpointService
                        .createCheckpoint(
                                sourceKey, session.getSessionId(), Collections.singletonList(file))
                        .getCheckpointId();
        FileUtil.writeUtf8String("v2", file);
        TerminalUiRpcService service = envRpcService(env);

        Map<String, Object> response =
                service.rollbackRestore(session.getSessionId(), checkpointId, "");

        assertThat(response)
                .containsEntry("success", Boolean.TRUE)
                .containsEntry("history_removed", Integer.valueOf(2));
        assertThat(FileUtil.readUtf8String(file)).isEqualTo("v1");
        assertThat(
                        MessageSupport.countMessages(
                                env.sessionRepository.findById(session.getSessionId()).getNdjson()))
                .isZero();
    }

    @Test
    void completeSlashIncludesTuiLocalCommandsAndAliases() throws Exception {
        TerminalUiRpcService service = new TerminalUiRpcService(testConfig());

        assertThat(slashCompletionTexts(service.completeSlash("/de")))
                .contains("/details", "/density");
        assertThat(slashCompletionTexts(service.completeSlash("/ex"))).contains("/exit");
        assertThat(slashCompletionTexts(service.completeSlash("/sb"))).contains("/sb");
        assertThat(slashCompletionTexts(service.completeSlash("/ter"))).contains("/terminal-setup");
        assertThat(slashCompletionTexts(service.completeSlash("/rep"))).contains("/replay");
    }

    @Test
    void completeSlashIncludesRegisteredAliases() throws Exception {
        TerminalUiRpcService service = new TerminalUiRpcService(testConfig());

        assertThat(slashCompletionTexts(service.completeSlash("/ag"))).contains("/agents");
        assertThat(slashCompletionTexts(service.completeSlash("/status-"))).contains("/status-bar");
        assertThat(slashCompletionTexts(service.completeSlash("/set-"))).contains("/set-home");
    }

    /** 未真正写入附件缓存时，TUI 不能把 /image 结果渲染为成功附件。 */
    @Test
    void imageAttachReportsFailureWhenNoAttachmentResolved() throws Exception {
        TerminalUiRpcService service = new TerminalUiRpcService(testConfig());

        Map<String, Object> response = service.imageAttach("session-image", "missing.png");

        assertThat(response.get("attached")).isEqualTo(Boolean.FALSE);
        assertThat(response.get("message")).isEqualTo("image not attached");
        assertThat(response).doesNotContainKey("name");
    }

    @Test
    void imageAttachBytesQueuesOnlyTheOwningSessionAndDrainsOnce() throws Exception {
        AppConfig config = testConfig();
        AttachmentCacheService cache = new AttachmentCacheService(config);
        TerminalUiPendingAttachmentService pending = new TerminalUiPendingAttachmentService();
        TerminalUiRpcService service =
                new TerminalUiRpcService(
                        config,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new AttachmentPathResolver(cache, new SecurityPolicyService(config)),
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
                        cache,
                        pending);

        Map<String, Object> response =
                service.imageAttachBytes("session-image", "iVBORw0KGgo=", "client.png", "");

        assertThat(response)
                .containsEntry("attached", Boolean.TRUE)
                .containsEntry("count", 1)
                .containsEntry("name", "client.png")
                .containsEntry("mime_type", "image/png");
        List<MessageAttachment> attachments = service.drainPendingAttachments("session-image");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).getOriginalName()).isEqualTo("client.png");
        assertThat(service.drainPendingAttachments("session-image")).isEmpty();
        assertThat(service.drainPendingAttachments("another-session")).isEmpty();
    }

    private static SessionRecord session(String id, String sourceKey) {
        SessionRecord session = new SessionRecord();
        session.setSessionId(id);
        session.setSourceKey(sourceKey);
        session.setNdjson("");
        session.setCreatedAt(1_800_000_000_000L);
        session.setUpdatedAt(1_800_000_000_000L);
        return session;
    }

    /** 构造带用量的已完成 Agent run，用于模拟 TUI retry 后的多轮模型请求。 */
    private static AgentRunRecord run(
            String id, String sessionId, long inputTokens, long outputTokens) {
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId(id);
        run.setSessionId(sessionId);
        run.setSourceKey("MEMORY:terminal-ui:" + sessionId);
        run.setStatus("success");
        run.setStartedAt(1_800_000_000_000L);
        run.setFinishedAt(1_800_000_001_000L);
        run.setInputTokens(inputTokens);
        run.setOutputTokens(outputTokens);
        run.setTotalTokens(inputTokens + outputTokens);
        return run;
    }

    private static AppConfig testConfig() throws Exception {
        File home = Files.createTempDirectory("solonclaw-tui-rpc-test").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(home, "data"), "state.db").getAbsolutePath());
        return config;
    }

    /** 提取 slash 补全项的真实写入文本，避免测试依赖候选项的展示字段顺序。 */
    private static List<String> slashCompletionTexts(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        List<String> texts = new ArrayList<String>();
        for (Map<String, Object> item : items) {
            texts.add(String.valueOf(item.get("text")));
        }
        return texts;
    }

    /** 提取 active_list 会话项，避免每个回归重复 unchecked cast。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> activeSessionItems(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("sessions");
    }

    private static class FixedDelegationService implements DelegationService {
        private final int activeCount;

        private FixedDelegationService(int activeCount) {
            this.activeCount = activeCount;
        }

        @Override
        public DelegationResult delegateSingle(String sourceKey, String prompt, String context) {
            return null;
        }

        @Override
        public List<DelegationResult> delegateBatch(String sourceKey, List<DelegationTask> tasks) {
            return Collections.emptyList();
        }

        @Override
        public List<Map<String, Object>> activeSubagents() {
            List<Map<String, Object>> active = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < activeCount; i++) {
                active.add(Collections.<String, Object>singletonMap("subagent_id", "sub-" + i));
            }
            return active;
        }
    }

    private static class RecordingCompressionService implements ContextCompressionService {
        private int compressNowCalls;
        private String focus;

        @Override
        public SessionRecord compressIfNeeded(
                SessionRecord session, String systemPrompt, String userMessage) {
            return session;
        }

        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt) {
            return session;
        }

        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt, String focus) {
            compressNowCalls++;
            this.focus = focus;
            return session;
        }

        @Override
        public CompressionOutcome compressNowWithOutcome(
                SessionRecord session, String systemPrompt, String focus) {
            compressNowCalls++;
            this.focus = focus;
            return CompressionOutcome.skipped(session);
        }
    }
}
