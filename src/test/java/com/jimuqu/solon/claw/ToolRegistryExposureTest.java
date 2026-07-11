package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.ApprovalQueueManageTools;
import com.jimuqu.solon.claw.tool.runtime.ConfigManageTools;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.DiagnosticsManageTools;
import com.jimuqu.solon.claw.tool.runtime.RunTools;
import com.jimuqu.solon.claw.tool.runtime.SearchManageTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SessionManageTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.ToolsetsManageTools;
import com.jimuqu.solon.claw.tool.runtime.TuiRuntimeManageTools;
import com.jimuqu.solon.claw.tool.runtime.WorkspaceConfigManageTools;
import com.jimuqu.solon.claw.tool.runtime.WorkspaceManageTools;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Param;

public class ToolRegistryExposureTest {
    @AfterEach
    void clearThreadPolicyApprovals() {
        SecurityPolicyService.clearCurrentThreadPolicyApprovals();
    }

    /** 断言工具结果为当前成功状态，避免测试重新依赖已删除的 success 布尔字段。 */
    private static void assertToolSuccess(ONode result) {
        assertThat(result.get("status").getString()).as(result.toJson()).isNotEqualTo("error");
    }

    /** 断言工具结果为当前错误状态，避免测试重新依赖已删除的 success 布尔字段。 */
    private static void assertToolError(ONode result) {
        assertThat(result.get("status").getString()).isEqualTo("error");
    }

    @Test
    void shouldExposeBuiltinSearchTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> names =
                env.gatewayService == null ? java.util.Collections.<String>emptyList() : null;
        names = env.toolRegistry.listToolNames();

        assertThat(names)
                .contains(
                        "codesearch",
                        "websearch",
                        "webfetch",
                        "web_extract",
                        "browser",
                        "file_read",
                        "file_write",
                        "read_file",
                        "write_file",
                        "search_files",
                        "security_audit",
                        "clarify",
                        "file_list",
                        "file_delete",
                        "patch",
                        "execute_shell",
                        "terminal",
                        "process",
                        "execute_code",
                        "execute_python",
                        "execute_js",
                        "get_current_time",
                        "agent_manage",
                        "mcp_manage",
                        "curator_manage",
                        "platform_toolsets_manage",
                        "provider_manage",
                        "search_manage",
                        "session_manage",
                        "analytics_manage",
                        "logs_manage",
                        "media_manage",
                        "status_manage",
                        "diagnostics_manage",
                        "doctor_manage",
                        "tui_runtime_manage",
                        "insights_manage",
                        "approval_events_manage",
                        "approval_queue_manage",
                        "workspace_manage",
                        "workspace_config_manage",
                        "config_manage",
                        "gateway_setup_manage",
                        "skills_list",
                        "skill_view",
                        "skill_files",
                        "skill_manage",
                        "toolsets_manage",
                        "skills_hub_search",
                        "skills_hub_install",
                        "skills_hub_tap",
                        "config_refresh");
        assertThat(names).contains("tool_gateway");
        assertThat(names).doesNotContain("web_search", "exists_cmd", "list_files");

        List<Object> tools = env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1");
        String joined = tools.toString();
        assertThat(joined).contains("SafeCodeSearchTool");
        assertThat(joined).contains("SafeWebsearchTool");
        assertThat(joined).contains("SafeWebfetchTool");
        assertThat(joined).contains("SafeWebExtractTool");
        assertThat(joined).contains("BrowserTools");
        assertThat(joined).contains("SecurityAuditTools");
        assertThat(joined).contains("ClarifyTools");
        assertThat(joined).contains("SolonClawFileReadWriteSkill");
        assertThat(joined).contains("SolonClawPatchTools");
        assertThat(joined).contains("SolonClawShellSkill");
        assertThat(joined).contains("ProcessTools");
        assertThat(joined).contains("SafeExecuteCodeTool");
        assertThat(joined).contains("SafePythonSkill");
        assertThat(joined).contains("SafeNodejsSkill");
        assertThat(joined).contains("SystemClockTalent");
        assertThat(joined).contains("TodoTools");
        assertThat(joined).doesNotContain("AgentTools");
        assertThat(joined).contains("SkillsListTool");
        assertThat(joined).contains("SkillFilesTool");
        assertThat(joined).contains("ConfigRefreshTool");
        assertThat(joined).doesNotContain("ToolGatewayTalent");
    }

    @Test
    void shouldExposeSkillFilesInSkillsSelector() {
        assertThat(AgentRuntimePolicy.expandToolSelectors(Arrays.asList("skills")))
                .contains("skills_list", "skill_view", "skill_files", "skill_manage");
    }

    @Test
    void shouldSearchFilesWithCanonicalToolResultEnvelope() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("canonical-search.txt"),
                Arrays.asList("alpha", "needle", "omega"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result =
                ONode.ofJson(
                        fileSkill.searchFiles(
                                "needle", "content", ".", "*.txt", 10, 0, "content", 0));

        assertToolSuccess(result);
        assertThat(result.get("matches").size()).isEqualTo(1);
        assertThat(result.get("matches").get(0).get("path").getString())
                .contains("canonical-search.txt");
        assertThat(result.get("preview").getString()).contains("needle");
    }

    @Test
    void shouldDescribeSecurityAuditStatusActionInToolParameters() throws Exception {
        Method method =
                SecurityAuditTools.class.getMethod(
                        "audit",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        Boolean.class,
                        String.class);

        assertThat(paramDescription(method, "action"))
                .contains("command")
                .contains("tool_args")
                .contains("policy")
                .contains("status");
    }

    void shouldApplyTerminalGuardrailsWhenAuditingToolArgs() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "terminal",
                                null,
                                null,
                                null,
                                null,
                                "{\"command\":\"python -m http.server 8000\"}"));

        assertThat(result.get("action").getString()).isEqualTo("tool_args");
        assertThat(result.get("decision").getString()).isEqualTo("block");
        assertThat(result.get("blocking").getBoolean()).isTrue();
        assertThat(result.get("approval_required").getBoolean()).isFalse();
        assertThat(result.get("commandPreview").getString()).contains("python -m http.server 8000");
        assertThat(result.toJson())
                .contains("terminal_guardrail")
                .contains("use_managed_background_process")
                .doesNotContain("secret-sudo");
    }

    // shouldAuditToolArgWorkingDirectoriesAsPaths 已删除：terminal/process 的 workdir/cwd 走读路径
    // 审计（非 write-like 工具），凭据目录读已放宽（对齐 外部对标仓库"读非安全边界"），现在放行。

    @Test
    void shouldAuditNestedAndArrayCommandToolArgs() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode nested =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "execute_shell",
                                null,
                                null,
                                null,
                                null,
                                "{\"payload\":{\"shell_command\":\"git reset --hard\"}}"));
        ONode array =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "execute_shell",
                                null,
                                null,
                                null,
                                null,
                                "{\"commands\":[\"echo ready\",\"terraform destroy -auto-approve\"]}"));
        ONode safeNote =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "terminal",
                                null,
                                null,
                                null,
                                null,
                                "{\"note\":\"git reset --hard appears in docs, not a command key\"}"));

        assertThat(nested.get("decision").getString()).isEqualTo("warn");
        assertThat(nested.get("approval_required").getBoolean()).isTrue();
        assertThat(nested.toJson()).contains("git_reset_hard");
        assertThat(array.get("decision").getString()).isEqualTo("warn");
        assertThat(array.get("approval_required").getBoolean()).isTrue();
        assertThat(array.toJson()).contains("terraform_destroy");
        assertThat(safeNote.get("decision").getString()).isEqualTo("allow");
        assertThat(safeNote.toJson()).doesNotContain("git_reset_hard");
    }

    @Test
    void shouldNormalizeCommandToolArgKeysWhenAuditingToolArgs() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode mixedCase =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "execute_shell",
                                null,
                                null,
                                null,
                                null,
                                "{\"payload\":{\" Command \":\"git reset --hard\"}}"));
        ONode spaced =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "execute_shell",
                                null,
                                null,
                                null,
                                null,
                                "{\"payload\":{\" shell_command \":\"terraform destroy -auto-approve\"}}"));

        assertThat(mixedCase.get("decision").getString()).isEqualTo("warn");
        assertThat(mixedCase.get("approval_required").getBoolean()).isTrue();
        assertThat(mixedCase.toJson()).contains("git_reset_hard");
        assertThat(spaced.get("decision").getString()).isEqualTo("warn");
        assertThat(spaced.get("approval_required").getBoolean()).isTrue();
        assertThat(spaced.toJson()).contains("terraform_destroy");
    }

    @Test
    void shouldRedactSecurityAuditArgsJsonParseErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "webfetch",
                                null,
                                null,
                                null,
                                null,
                                "{\"url\":\"https://example.test/?token=secret123"));

        assertToolError(result);
        assertThat(result.get("summary").getString()).contains("argsJson parse failed");
        assertThat(result.toJson()).contains("token=***").doesNotContain("secret123");
    }

    @Test
    void shouldRedactSecurityAuditTopLevelToolOutput() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        String unsupported =
                tools.audit("unknown-ghp_auditaction12345", null, null, null, null, null, null);
        String command =
                tools.audit(
                        "command",
                        "execute_shell-ghp_audittool12345",
                        "echo token=ghp_auditcommand12345",
                        null,
                        null,
                        null,
                        null);

        assertThat(unsupported).contains("unknown-ghp_***").doesNotContain("ghp_auditaction12345");
        assertThat(command)
                .contains("execute_shell-ghp_***")
                .contains("token=***")
                .doesNotContain("ghp_audittool12345")
                .doesNotContain("ghp_auditcommand12345");
    }

    @Test
    void shouldDropFileSkillWhenAllFileToolsAreDisabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.toolRegistry.disableTools(
                "MEMORY:room-1:user-1",
                java.util.Arrays.asList(
                        "file_read",
                        "file_write",
                        "read_file",
                        "write_file",
                        "search_files",
                        "file_list",
                        "file_delete"));

        String joined = env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").toString();

        assertThat(joined).doesNotContain("FileReadWriteSkill");
    }

    @Test
    void shouldDropBrowserToolsWhenDisabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        env.toolRegistry.disableTools(sourceKey, java.util.Collections.singletonList("browser"));

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).doesNotContain("browser");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .doesNotContain("BrowserTools");
    }

    @Test
    void shouldExposeManagedToolGatewayWhenExplicitlyEnabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        env.toolRegistry.enableTools(
                sourceKey, java.util.Collections.singletonList("tool_gateway"));

        String joined = env.toolRegistry.resolveEnabledTools(sourceKey).toString();

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("tool_gateway");
        assertThat(joined).contains("ToolGatewayTalent");
    }

    @Test
    void shouldExposeRunManagementToolForNaturalLanguageRunControl() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("run_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString()).contains("RunTools");
    }

    @Test
    void shouldInspectSessionRunsThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        com.jimuqu.solon.claw.core.model.AgentRunRecord run =
                new com.jimuqu.solon.claw.core.model.AgentRunRecord();
        run.setRunId("run-session-tool");
        run.setSessionId("session-run-tool");
        run.setSourceKey("MEMORY:run-tool-room:run-tool-user");
        run.setRunKind("chat");
        run.setStatus("completed");
        run.setInputPreview("run input");
        run.setStartedAt(System.currentTimeMillis());
        env.agentRunRepository.saveRun(run);
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:run-tool-room:run-tool-user").stream()
                        .filter(candidate -> candidate instanceof RunTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("run manage tool missing"));

        ONode sessionRuns =
                ONode.ofJson(
                        ((RunTools) tool)
                                .runManage(
                                        "session_runs",
                                        null,
                                        null,
                                        null,
                                        "session-run-tool",
                                        null,
                                        20));
        ONode summary =
                ONode.ofJson(
                        ((RunTools) tool)
                                .runManage("run", "run-session-tool", null, null, null, 20));

        assertToolSuccess(sessionRuns);
        assertThat(sessionRuns.get("result").get("runs").get(0).get("run_id").getString())
                .isEqualTo("run-session-tool");
        assertToolSuccess(summary);
        assertThat(summary.get("result").get("run_id").getString()).isEqualTo("run-session-tool");
    }

    @Test
    void shouldExposeMcpManagementToolForNaturalLanguageServerControl() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("mcp_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("McpManageTools");
    }

    @Test
    void shouldExposeCuratorManagementToolForNaturalLanguageSkillMaintenance() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("curator_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("CuratorManageTools");
    }

    @Test
    void shouldExposePlatformToolsetsManagementToolForNaturalLanguageChannelPolicy()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("platform_toolsets_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("PlatformToolsetsManageTools");
    }

    @Test
    void shouldExposeProviderManagementToolForNaturalLanguageModelConfiguration() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("provider_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("ProviderManageTools");
    }

    @Test
    void shouldExposeSessionManagementToolForNaturalLanguageSessionInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("session_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("SessionManageTools");
    }

    @Test
    void shouldSaveSessionTrajectoryThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("trajectory-tool-room", "trajectory-tool-user", "hello");
        env.send("trajectory-tool-room", "trajectory-tool-user", "/pairing claim-admin");
        env.send("trajectory-tool-room", "trajectory-tool-user", "start");
        com.jimuqu.solon.claw.core.model.SessionRecord session =
                env.sessionRepository.getBoundSession(
                        "MEMORY:trajectory-tool-room:trajectory-tool-user");
        Object tool =
                env
                        .toolRegistry
                        .resolveEnabledTools("MEMORY:trajectory-tool-room:trajectory-tool-user")
                        .stream()
                        .filter(candidate -> candidate instanceof SessionManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("session manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((SessionManageTools) tool)
                                .sessionManage(
                                        "save_trajectory",
                                        session.getSessionId(),
                                        null,
                                        null,
                                        Boolean.TRUE,
                                        20,
                                        0,
                                        20));

        assertToolSuccess(result);
        assertThat(result.get("result").get("saved").getBoolean()).isTrue();
        assertThat(result.get("result").get("path").getString())
                .isEqualTo("workspace://artifacts/trajectory_samples.jsonl");
    }

    @Test
    void shouldUpdateSessionTitleThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.send("session-title-tool-room", "session-title-tool-user", "hello");
        env.send("session-title-tool-room", "session-title-tool-user", "/pairing claim-admin");
        env.send("session-title-tool-room", "session-title-tool-user", "start");
        com.jimuqu.solon.claw.core.model.SessionRecord session =
                env.sessionRepository.getBoundSession(
                        "MEMORY:session-title-tool-room:session-title-tool-user");
        Object tool =
                env
                        .toolRegistry
                        .resolveEnabledTools(
                                "MEMORY:session-title-tool-room:session-title-tool-user")
                        .stream()
                        .filter(candidate -> candidate instanceof SessionManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("session manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((SessionManageTools) tool)
                                .sessionManage(
                                        "update_title",
                                        session.getSessionId(),
                                        null,
                                        null,
                                        Boolean.FALSE,
                                        20,
                                        0,
                                        20,
                                        "新的会话标题"));

        assertToolSuccess(result);
        assertThat(result.get("result").get("title").getString()).isEqualTo("新的会话标题");
        assertThat(env.sessionRepository.findById(session.getSessionId()).getTitle())
                .isEqualTo("新的会话标题");
    }

    @Test
    void shouldExposeAnalyticsManagementToolForNaturalLanguageUsageInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("analytics_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("AnalyticsManageTools");
    }

    @Test
    void shouldExposeLogsManagementToolForNaturalLanguageLogInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("logs_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("LogsManageTools");
    }

    @Test
    void shouldExposeMediaManagementToolForNaturalLanguageMediaInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("media_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("MediaManageTools");
    }

    @Test
    void shouldExposeStatusManagementToolForNaturalLanguageRuntimeInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("status_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("StatusManageTools");
    }

    @Test
    void shouldExposeDoctorManagementToolForNaturalLanguageGatewayDiagnostics() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("doctor_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("DoctorManageTools");
    }

    @Test
    void shouldExposeInsightsManagementToolForNaturalLanguageInsightInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("insights_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("InsightsManageTools");
    }

    @Test
    void shouldExposeDiagnosticsManagementToolForNaturalLanguageDiagnosticsInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("diagnostics_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("DiagnosticsManageTools");
    }

    @Test
    void shouldInspectDashboardDiagnosticsThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof DiagnosticsManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("diagnostics manage tool missing"));

        ONode result = ONode.ofJson(((DiagnosticsManageTools) tool).diagnosticsManage());

        assertToolSuccess(result);
        assertThat(result.get("result").get("runtime").isNull()).isFalse();
        assertThat(result.get("result").get("tools").isNull()).isFalse();
        assertThat(result.get("result").get("security").isNull()).isFalse();
    }

    @Test
    void shouldProbeSubprocessEnvironmentThroughNaturalLanguageDiagnosticsTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof DiagnosticsManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("diagnostics manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((DiagnosticsManageTools) tool)
                                .diagnosticsManage(
                                        "subprocess_environment",
                                        "[\"PATH\",\"OPENAI_API_KEY\",\"ghp_diagprobe12345\"]"));

        assertToolSuccess(result);
        assertThat(result.get("result").get("surface").getString())
                .isEqualTo("subprocess_environment");
        assertThat(result.get("result").get("requested_count").getInt()).isEqualTo(3);
        assertThat(result.toJson())
                .contains("provider-blocked")
                .contains("***")
                .doesNotContain("ghp_diagprobe12345");
    }

    @Test
    void shouldExposeApprovalEventsManagementToolForNaturalLanguageApprovalInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("approval_events_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("ApprovalEventsManageTools");
    }

    @Test
    void shouldExposeApprovalQueueManagementToolForNaturalLanguageApprovalInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("approval_queue_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("ApprovalQueueManageTools");
    }

    @Test
    void shouldInspectApprovalQueuesThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof ApprovalQueueManageTools)
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("approval queue manage tool missing"));

        ONode result =
                ONode.ofJson(((ApprovalQueueManageTools) tool).approvalQueueManage("summary", 5));

        assertToolSuccess(result);
        assertThat(result.get("result").get("pending").isNull()).isFalse();
        assertThat(result.get("result").get("history").isNull()).isFalse();
        assertThat(result.get("result").get("always").isNull()).isFalse();
        assertThat(result.get("result").get("slash_confirms").isNull()).isFalse();
    }

    @Test
    void shouldExposeDashboardSearchManagementToolForNaturalLanguageSearch() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("search_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("SearchManageTools");
    }

    @Test
    void shouldExposeTuiRuntimeManagementToolForNaturalLanguageSetupInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("tui_runtime_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("TuiRuntimeManageTools");
    }

    @Test
    void shouldInspectTuiRuntimeSetupThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof TuiRuntimeManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("tui runtime manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((TuiRuntimeManageTools) tool)
                                .tuiRuntimeManage(
                                        "setup_status", null, null, null, null, null, null));

        assertToolSuccess(result);
        assertThat(result.get("result").get("provider_configured").isNull()).isFalse();
    }

    @Test
    void shouldOperateTuiRuntimeModelSetupThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof TuiRuntimeManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("tui runtime manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((TuiRuntimeManageTools) tool)
                                .tuiRuntimeManage(
                                        "model_save_key",
                                        null,
                                        "default",
                                        "tp-test-realistic-secret",
                                        null,
                                        null,
                                        "session-tui"));

        assertToolSuccess(result);
        ONode provider = result.get("result").get("provider");
        assertThat(provider.get("slug").getString()).isEqualTo("default");
        assertThat(provider.get("authenticated").getBoolean()).isTrue();
        assertThat(result.toJson()).doesNotContain("tp-test-realistic-secret");
    }

    @Test
    void shouldSearchDashboardResultsThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof SearchManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("search manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((SearchManageTools) tool)
                                .searchManage(
                                        null, null, null, null, null, null, null, null, false, 3));

        assertToolSuccess(result);
        assertThat(result.get("result").get("tokenizer").getString())
                .isEqualTo("fts5/cjk-ngram-fallback");
        assertThat(result.get("result").get("results").isArray()).isTrue();
    }

    @Test
    void shouldExposeWorkspaceManagementToolForNaturalLanguageWorkspaceInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("workspace_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("WorkspaceManageTools");
    }

    @Test
    void shouldSaveAndRestoreWorkspaceFileThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof WorkspaceManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("workspace manage tool missing"));

        ONode saved =
                ONode.ofJson(
                        ((WorkspaceManageTools) tool)
                                .workspaceManage(
                                        "save_file",
                                        "agents",
                                        null,
                                        "# Test Agents\n\n- temporary test content"));
        ONode restored =
                ONode.ofJson(
                        ((WorkspaceManageTools) tool)
                                .workspaceManage("restore_file", "agents", null, null));

        assertToolSuccess(saved);
        assertThat(saved.get("result").get("ok").getBoolean()).isTrue();
        assertThat(saved.get("result").get("file").get("content").getString())
                .contains("temporary test content");
        assertToolSuccess(restored);
        assertThat(restored.get("result").get("ok").getBoolean()).isTrue();
        assertThat(restored.get("result").get("file").get("content").getString())
                .doesNotContain("temporary test content");
    }

    @Test
    void shouldExposeConfigManagementToolForNaturalLanguageConfigInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("config_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("ConfigManageTools");
    }

    @Test
    void shouldInspectCurrentConfigThroughNaturalLanguageToolWithoutRevealingSecrets()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        File configFile = new File(env.appConfig.getRuntime().getConfigFile());
        Files.write(
                configFile.toPath(),
                Arrays.asList(
                        "solonclaw:",
                        "  gateway:",
                        "    injectionSecret: natural-language-secret",
                        "  terminal:",
                        "    sudoPassword: natural-language-sudo"),
                StandardCharsets.UTF_8);
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof ConfigManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("config manage tool missing"));

        ONode result = ONode.ofJson(((ConfigManageTools) tool).configManage("current"));

        assertToolSuccess(result);
        assertThat(
                        result.get("result")
                                .get("config")
                                .get("gateway")
                                .get("injectionSecret")
                                .getString())
                .isEqualTo("********");
        assertThat(
                        result.get("result")
                                .get("config")
                                .get("terminal")
                                .get("sudoPassword")
                                .getString())
                .isEqualTo("********");
        assertThat(result.toJson()).doesNotContain("natural-language-secret");
        assertThat(result.toJson()).doesNotContain("natural-language-sudo");
    }

    @Test
    void shouldExposeWorkspaceConfigManagementToolForNaturalLanguageConfigInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("workspace_config_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("WorkspaceConfigManageTools");
    }

    @Test
    void shouldInspectWorkspaceConfigItemsThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof WorkspaceConfigManageTools)
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("workspace config manage tool missing"));

        ONode result =
                ONode.ofJson(
                        ((WorkspaceConfigManageTools) tool)
                                .workspaceConfigManage("items", null, null));

        assertToolSuccess(result);
        assertThat(result.get("result").get("items").get("providers.default.apiKey").isNull())
                .isFalse();
        assertThat(
                        result.get("result")
                                .get("items")
                                .get("providers.default.apiKey")
                                .get("is_password")
                                .getBoolean())
                .isTrue();
    }

    @Test
    void shouldSetAndRemoveWorkspaceConfigThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof WorkspaceConfigManageTools)
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("workspace config manage tool missing"));

        ONode saved =
                ONode.ofJson(
                        ((WorkspaceConfigManageTools) tool)
                                .workspaceConfigManage(
                                        "set",
                                        "providers.default.defaultModel",
                                        "workspace-config-test-model"));
        ONode items =
                ONode.ofJson(
                        ((WorkspaceConfigManageTools) tool)
                                .workspaceConfigManage("items", null, null));
        ONode removed =
                ONode.ofJson(
                        ((WorkspaceConfigManageTools) tool)
                                .workspaceConfigManage(
                                        "remove", "providers.default.defaultModel", null));
        ONode secretWrite =
                ONode.ofJson(
                        ((WorkspaceConfigManageTools) tool)
                                .workspaceConfigManage(
                                        "set", "providers.default.apiKey", "sk-test-secret"));

        assertToolSuccess(saved);
        assertThat(saved.get("result").get("ok").getBoolean()).isTrue();
        assertThat(
                        items.get("result")
                                .get("items")
                                .get("providers.default.defaultModel")
                                .get("is_set")
                                .getBoolean())
                .isTrue();
        assertThat(
                        items.get("result")
                                .get("items")
                                .get("providers.default.defaultModel")
                                .get("redacted_value")
                                .getString())
                .isEqualTo("work...odel");
        assertToolSuccess(removed);
        assertThat(removed.get("result").get("ok").getBoolean()).isTrue();
        assertToolError(secretWrite);
        assertThat(secretWrite.get("error").getString()).contains("密钥配置");
    }

    @Test
    void shouldExposeToolsetsManagementToolForNaturalLanguageToolsetInspection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("toolsets_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("ToolsetsManageTools");
    }

    @Test
    void shouldInspectDashboardToolsetsThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Object tool =
                env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1").stream()
                        .filter(candidate -> candidate instanceof ToolsetsManageTools)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("toolsets manage tool missing"));

        ONode result = ONode.ofJson(((ToolsetsManageTools) tool).toolsetsManage());

        assertToolSuccess(result);
        assertThat(result.get("result").get("toolsets").isArray()).isTrue();
        assertThat(result.get("result").get("toolsets").toJson()).contains("\"code\"");
        assertThat(result.get("result").get("toolsets").toJson()).contains("\"skills\"");
    }

    @Test
    void shouldExposeGatewaySetupManagementToolForNaturalLanguageQrSetup() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("gateway_setup_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("GatewaySetupManageTools");
    }

    private String paramDescription(Method method, String name) {
        for (Parameter parameter : method.getParameters()) {
            Param annotation = parameter.getAnnotation(Param.class);
            if (annotation != null && name.equals(annotation.name())) {
                return annotation.description();
            }
        }
        throw new IllegalStateException("parameter not found: " + name);
    }
}
