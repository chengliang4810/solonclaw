package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ApprovalQueueManageTools;
import com.jimuqu.solon.claw.tool.runtime.ConfigManageTools;
import com.jimuqu.solon.claw.tool.runtime.DiagnosticsManageTools;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.RunTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalDecision;
import com.jimuqu.solon.claw.tool.runtime.SmartApprovalJudge;
import com.jimuqu.solon.claw.tool.runtime.SolonClawCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileReadWriteSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawFileStateTracker;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawWebTools;
import com.jimuqu.solon.claw.tool.runtime.SearchManageTools;
import com.jimuqu.solon.claw.tool.runtime.SessionManageTools;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.TuiRuntimeManageTools;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.tool.runtime.ToolsetsManageTools;
import com.jimuqu.solon.claw.tool.runtime.WorkspaceConfigManageTools;
import com.jimuqu.solon.claw.tool.runtime.WorkspaceManageTools;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.talents.web.CodeSearchTalent;
import org.noear.solon.ai.talents.web.WebfetchTalent;
import org.noear.solon.ai.talents.web.WebsearchTalent;
import org.noear.solon.annotation.Param;

public class ToolRegistryExposureTest {
    @AfterEach
    void clearThreadPolicyApprovals() {
        SecurityPolicyService.clearCurrentThreadPolicyApprovals();
    }

    /**
     * 断言工具结果为当前成功状态，避免测试重新依赖已删除的 success 布尔字段。
     */
    private static void assertToolSuccess(ONode result) {
        assertThat(result.get("status").getString()).as(result.toJson()).isNotEqualTo("error");
    }

    /**
     * 断言工具结果为当前错误状态，避免测试重新依赖已删除的 success 布尔字段。
     */
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
        assertThat(names)
                .doesNotContain(
                        "web_search",
                        "web_extract",
                        "exists_cmd",
                        "list_files");

        List<Object> tools = env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1");
        String joined = tools.toString();
        assertThat(joined).contains("SafeCodeSearchTool");
        assertThat(joined).contains("SafeWebsearchTool");
        assertThat(joined).contains("SafeWebfetchTool");
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
        assertThat(joined).contains("AgentTools");
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

    @Test
    void shouldAuditSecurityInputsWithoutExecutingThem() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository, env.appConfig, policy, null);
        approvalService.setSmartApprovalJudge(
                new SmartApprovalJudge() {
                    @Override
                    public SmartApprovalDecision judge(
                            String toolName, String command, String description) {
                        return SmartApprovalDecision.escalate("audit only");
                    }
                });
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        approvalService,
                        new TirithSecurityService(env.appConfig),
                        new ToolResultStorageService(
                                env.appConfig.getRuntime().getCacheDir(),
                                env.appConfig.getRuntime().getHome(),
                                env.appConfig.getTask().getToolOutputInlineLimit(),
                                env.appConfig.getTask().getToolOutputTurnBudget(),
                                env.appConfig.getTrace().getToolPreviewLength()),
                        env.appConfig);
        env.appConfig.getSecurity().setGuardrailMode("smart");
        env.appConfig.getSecurity().setGuardrailCronMode("approve");
        env.appConfig.getSecurity().setFileGuardrailMode("strict");
        env.appConfig.getSecurity().setUrlGuardrailMode("strict");
        env.appConfig.getApprovals().setSubagentAutoApprove(false);
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig
                .getSecurity()
                .setTirithPath(
                        Files.createTempDirectory("jimuqu-audit-tirith")
                                .resolve("missing-tirith")
                                .toString());
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        env.appConfig.getTerminal().setCredentialFiles(Arrays.asList("credentials/oauth.json"));
        env.appConfig.getTerminal().setEnvPassthrough(Arrays.asList("TENOR_API_KEY"));
        env.appConfig.getTerminal().setSudoPassword("secret-sudo");

        ONode hardline =
                ONode.ofJson(
                        tools.audit(
                                "command",
                                "execute_shell",
                                "blkdiscard /dev/sdb",
                                null,
                                null,
                                null,
                                null));
        ONode path =
                ONode.ofJson(tools.audit("path", null, null, null, ".env", Boolean.FALSE, null));
        String externalPath =
                new java.io.File(
                                new java.io.File(env.appConfig.getRuntime().getHome())
                                        .getParentFile(),
                                "audit-token=ghp_auditpath12345.txt")
                        .getAbsolutePath();
        ONode externalPathAudit =
                ONode.ofJson(
                        tools.audit("path", null, null, null, externalPath, Boolean.FALSE, null));
        ONode toolArgs =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "write_file",
                                null,
                                null,
                                null,
                                null,
                                "{\"path\":\"../outside.txt\"}"));
        ONode policyStatus =
                ONode.ofJson(tools.audit("policy", null, null, null, null, null, null));

        assertToolSuccess(hardline);
        assertThat(hardline.get("decision").getString()).isEqualTo("block");
        assertThat(hardline.get("blocking").getBoolean()).isTrue();
        assertThat(hardline.get("approval_required").getBoolean()).isFalse();
        assertThat(hardline.get("commandPreview").getString()).contains("blkdiscard /dev/sdb");
        assertThat(String.valueOf(hardline.get("findings")))
                .contains("hardline")
                .contains("destroy raw disk partition table")
                .contains("change_command")
                .contains("blocking")
                .contains("approval_required");
        assertThat(path.get("decision").getString()).isEqualTo("block");
        assertThat(path.get("blocking").getBoolean()).isTrue();
        assertThat(path.get("approval_required").getBoolean()).isFalse();
        assertThat(path.get("path").getString()).isEqualTo("path://.env");
        assertThat(String.valueOf(path.get("findings")))
                .contains("file_policy")
                .contains("凭据")
                .contains("change_path");
        assertThat(externalPathAudit.get("path").getString())
                .startsWith("path://")
                .contains(new java.io.File(externalPath).getParentFile().getAbsolutePath())
                .contains("audit-token=***")
                .doesNotContain("ghp_auditpath12345");
        assertThat(externalPathAudit.toJson())
                .doesNotContain("ghp_auditpath12345");
        assertThat(toolArgs.get("decision").getString()).isEqualTo("block");
        assertThat(toolArgs.get("blocking").getBoolean()).isTrue();
        assertThat(String.valueOf(toolArgs.get("findings")))
                .contains("路径遍历")
                .contains("change_path");

        ONode dangerous =
                ONode.ofJson(
                        tools.audit(
                                "command",
                                "execute_shell",
                                "rm -rf workspace/cache",
                                null,
                                null,
                                null,
                                null));
        assertThat(dangerous.get("decision").getString()).isEqualTo("warn");
        assertThat(dangerous.get("blocking").getBoolean()).isFalse();
        assertThat(dangerous.get("approval_required").getBoolean()).isTrue();
        assertThat(String.valueOf(dangerous.get("findings")))
                .contains("recursive_delete")
                .contains("request_approval");
        ONode structuredCommandArgs =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "execute_shell",
                                null,
                                null,
                                null,
                                null,
                                "{\"command\":[\"rm\",\"-rf\",\"workspace/cache\"]}"));
        assertThat(structuredCommandArgs.get("decision").getString()).isEqualTo("warn");
        assertThat(structuredCommandArgs.get("blocking").getBoolean()).isFalse();
        assertThat(structuredCommandArgs.get("approval_required").getBoolean()).isTrue();
        assertThat(String.valueOf(structuredCommandArgs.get("findings")))
                .contains("recursive_delete")
                .contains("request_approval");
        ONode nestedStructuredCommandArgs =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "execute_shell",
                                null,
                                null,
                                null,
                                null,
                                "{\"command\":[\"echo ready\",{\"cmd\":\"rm -rf workspace/cache\"}]}"));
        assertThat(nestedStructuredCommandArgs.get("decision").getString()).isEqualTo("warn");
        assertThat(nestedStructuredCommandArgs.get("blocking").getBoolean()).isFalse();
        assertThat(nestedStructuredCommandArgs.get("approval_required").getBoolean()).isTrue();
        assertThat(String.valueOf(nestedStructuredCommandArgs.get("findings")))
                .contains("recursive_delete")
                .contains("request_approval");

        assertToolSuccess(policyStatus);
        assertThat(policyStatus.get("summary").getString())
                .contains("without exposing secret values");
        assertThat(policyStatus.get("policy").get("security").get("allowPrivateUrls").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("urlPolicy")
                                .get("allowPrivateUrls")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("urlPolicy")
                                .get("alwaysBlockedIpCount")
                                .getInt())
                .isGreaterThan(0);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("urlPolicy")
                                .get("sensitiveQueryBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(policyStatus.get("policy").get("security").get("urlPolicy")))
                .contains("169.254")
                .contains("blocked.example")
                .contains("access_token");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("websiteBlocklistDomainCount")
                                .getInt())
                .isEqualTo(1);
        assertThat(policyStatus.get("policy").get("security").get("tirithAvailable").getBoolean())
                .isFalse();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("tirithDiagnostic")
                                .get("enabled")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("tirithDiagnostic")
                                .get("summary")
                                .getString())
                .contains("unavailable");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("tirithPolicy")
                                .get("warnRequiresApproval")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("security")
                                .get("tirithPolicy")
                                .get("secretRedaction")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(policyStatus.get("policy").get("security").get("tirithPolicy")))
                .contains("failOpenMode")
                .contains("powershell")
                .contains("cmd");
        ONode backgroundProcessPolicy =
                policyStatus.get("policy").get("terminal").get("backgroundProcessPolicy");
        assertThat(backgroundProcessPolicy.get("processRegistryBacked").getBoolean()).isTrue();
        assertThat(backgroundProcessPolicy.get("startHardlineBlocked").getBoolean()).isTrue();
        assertThat(backgroundProcessPolicy.get("startDangerousCommandChecked").getBoolean())
                .isTrue();
        assertThat(backgroundProcessPolicy.get("stdinExecutionPayloadChecked").getBoolean())
                .isTrue();
        assertThat(backgroundProcessPolicy.get("stdinPrivilegeWrapperDetection").getBoolean())
                .isTrue();
        assertThat(backgroundProcessPolicy.get("waitTimeoutClamped").getBoolean()).isTrue();
        assertThat(backgroundProcessPolicy.get("processWaitTimeoutSeconds").getInt())
                .isGreaterThan(0);
        assertThat(String.valueOf(backgroundProcessPolicy))
                .contains("start")
                .contains("submit")
                .contains("close")
                .contains("execute_python")
                .contains("sudo")
                .contains("nohup")
                .doesNotContain("secret-sudo");
        assertThat(policyStatus.get("policy").get("approvals").get("guardrailMode").getString())
                .isEqualTo("smart");
        assertThat(policyStatus.get("policy").get("approvals").get("smartMode").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("smartJudgeConfigured")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("smartApprovalActive")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("smartCoversTirith")
                                .getBoolean())
                .isTrue();
        assertThat(policyStatus.get("policy").get("approvals").get("guardrailCronMode").getString())
                .isEqualTo("approve");
        assertThat(policyStatus.get("policy").get("approvals").get("cronAutoApprove").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("subagentApprovalDefault")
                                .getString())
                .isEqualTo("deny");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("cronApprovalPolicy")
                                .get("autoApproveDangerousCommands")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("cronApprovalPolicy")
                                .get("hardlineAlwaysBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("approvals")
                                        .get("cronApprovalPolicy")))
                .contains("security.guardrailCronMode")
                .contains("guardrailApprovalCanPauseCron");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("subagentApprovalPolicy")
                                .get("autoApproveDangerousCommands")
                                .getBoolean())
                .isFalse();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("subagentApprovalPolicy")
                                .get("humanApprovalPromptSuppressed")
                                .getBoolean())
                .isTrue();
        assertThat(policyStatus.get("policy").get("approvals").get("mcpReloadConfirm").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("mcpReloadConfirmationDefault")
                                .getString())
                .isEqualTo("confirm");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("guardrailMode")
                                .getString())
                .isEqualTo("smart");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("smartApprovalPolicy")
                                .get("active")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("smartApprovalPolicy")
                                .get("escalateFallsBackToHumanApproval")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("approvals")
                                        .get("smartApprovalPolicy")))
                .contains("approve")
                .contains("deny")
                .contains("tirithFindingsIncluded")
                .contains("terminalGuardrailPrechecked");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("tirithApprovalPolicy")
                                .get("alwaysScopeDowngradedToSession")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("tirithApprovalPolicy")
                                .get("permanentApprovalAllowed")
                                .getBoolean())
                .isFalse();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("approvals")
                                        .get("tirithApprovalPolicy")))
                .contains("tirith:")
                .contains("tirith:security_scan");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("dangerousRuleCount")
                                .getInt())
                .isGreaterThan(50);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("hardlineRuleCount")
                                .getInt())
                .isGreaterThan(10);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("hardlinePolicy")
                                .get("approvalBypassAllowed")
                                .getBoolean())
                .isFalse();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("sudoRewriteConfigured")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("terminalGuardrailPolicy")
                                .get("longLivedForegroundBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("smartApprovalPolicy")
                                .get("judgeFailureFallsBackToHumanApproval")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("approvalPolicy")
                                .get("backgroundProcessGuard")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus.get("policy").get("approvals").get("approvalPolicy")))
                .contains("recursive_delete")
                .contains("hardlinePolicy")
                .contains("hardline_windows")
                .contains("metadataUrlBlocked")
                .contains("long_lived_foreground")
                .contains("terminalGuardrailPolicy")
                .contains("slashConfirmPolicy")
                .contains("/approve")
                .contains("/deny")
                .contains("dangerous_command_approval_card")
                .contains("auditLogPolicy")
                .contains("mcpReloadPolicy")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("slashConfirmPolicy")
                                .get("selectorSupported")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("slashConfirmPolicy")
                                .get("approveAllSupported")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("slashConfirmPolicy")
                                .get("denyAllSupported")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("slashConfirmPolicy")
                                .get("tirithAlwaysDowngradedToSession")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("slashConfirmPolicy")
                                .get("approverRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("approvals")
                                        .get("slashConfirmPolicy")))
                .contains("once")
                .contains("session")
                .contains("always")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("auditLogPolicy")
                                .get("requestEvents")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("auditLogPolicy")
                                .get("responseEvents")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("auditLogPolicy")
                                .get("observerFailureIsolated")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("auditLogPolicy")
                                .get("approverRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus.get("policy").get("approvals").get("auditLogPolicy")))
                .contains("commandHashStored")
                .contains("patternKeysStored")
                .contains("manualRevocationAudited")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("mcpReloadPolicy")
                                .get("confirmRequired")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("mcpReloadPolicy")
                                .get("toolChangeNoticeInjected")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("mcpReloadPolicy")
                                .get("oauthUrlSafetyCovered")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("approvals")
                                .get("mcpReloadPolicy")
                                .get("encodedUrlParameterRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus.get("policy").get("approvals").get("mcpReloadPolicy")))
                .contains("/reload-mcp")
                .contains("approvals.mcpReloadConfirm");
        assertThat(policyStatus.get("policy").get("terminal").get("credentialFileCount").getInt())
                .isEqualTo(1);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("credentialPolicy")
                                .get("fileNameCount")
                                .getInt())
                .isGreaterThan(20);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("credentialPolicy")
                                .get("configuredCredentialFileCount")
                                .getInt())
                .isEqualTo(1);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("credentialPolicy")
                                .get("envExampleFilesAllowed")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus.get("policy").get("terminal").get("credentialPolicy")))
                .contains(".ssh")
                .contains("credentials/oauth.json")
                .doesNotContain("secret");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("credentialMountPolicy")
                                .get("configCredentialFileCount")
                                .getInt())
                .isEqualTo(1);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("credentialMountPolicy")
                                .get("workspaceRelativeOnly")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("credentialMountPolicy")
                                .get("hostPathsOmittedFromMetadata")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("terminal")
                                        .get("credentialMountPolicy")))
                .contains("required_credential_files")
                .contains("terminal.credentialFiles")
                .doesNotContain("credentials/oauth.json");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("pathPolicy")
                                .get("traversalBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("pathPolicy")
                                .get("workspaceWriteFree")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("pathPolicy")
                                .get("outsideWorkspaceReadFree")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("pathPolicy")
                                .get("outsideWorkspaceWriteFree")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(policyStatus.get("policy").get("terminal").get("pathPolicy")))
                .contains("/etc/passwd")
                .contains("c:/windows/")
                .contains("/dev/zero");
        assertThat(policyStatus.get("policy").get("terminal").get("envPassthroughCount").getInt())
                .isEqualTo(1);
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("sudoPasswordConfigured")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("sudoRewritePolicy")
                                .get("configured")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("sudoRewritePolicy")
                                .get("stdinPasswordInjection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("terminal")
                                        .get("sudoRewritePolicy")))
                .contains("SUDO_PASSWORD")
                .contains("terminal.sudoPassword")
                .contains("passwordRedacted")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("terminalGuardrailPolicy")
                                .get("sudoRewriteConfigured")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("terminalGuardrailPolicy")
                                .get("codeToolShellExtractionCovered")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("terminal")
                                .get("terminalGuardrailPolicy")
                                .get("downloadOutputPathPrechecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("terminal")
                                        .get("terminalGuardrailPolicy")))
                .contains("nohup")
                .contains("npm run dev")
                .contains("execute_python")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("dangerousCommandApproval")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("configuredCredentialCommandPathApproval")
                                .getBoolean())
                .isTrue();
        ONode dangerousCommandApprovalPolicy =
                policyStatus.get("policy").get("coverage").get("dangerousCommandApprovalPolicy");
        assertThat(dangerousCommandApprovalPolicy.get("dangerousRuleCount").getInt())
                .isGreaterThan(0);
        assertThat(dangerousCommandApprovalPolicy.get("hardlineRuleCount").getInt())
                .isGreaterThan(0);
        assertThat(dangerousCommandApprovalPolicy.get("backgroundProcessGuard").getBoolean())
                .isTrue();
        assertThat(dangerousCommandApprovalPolicy.get("approvalTimeoutSeconds").getInt())
                .isGreaterThan(0);
        assertThat(
                        dangerousCommandApprovalPolicy
                                .get("configuredCredentialCommandPathDetection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        dangerousCommandApprovalPolicy
                                .get("recursiveStructuredToolArgsDetection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        dangerousCommandApprovalPolicy
                                .get("nestedArrayCommandArgumentDetection")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(dangerousCommandApprovalPolicy))
                .contains("rm")
                .contains("hardlinePolicy")
                .contains("slashConfirmPolicy")
                .doesNotContain("secret-sudo");
        ONode approvalLifecyclePolicy =
                policyStatus.get("policy").get("coverage").get("approvalLifecyclePolicy");
        assertThat(approvalLifecyclePolicy.get("pendingListPrunedBeforeRead").getBoolean())
                .isTrue();
        assertThat(approvalLifecyclePolicy.get("listSupported").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("approveAllSupported").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("rejectAllSupported").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("bulkRejectUsesSafeSelector").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("clearSessionSupported").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("clearAlwaysSupported").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("clearAllSupported").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("alwaysScopeUsesGlobalSettings").getBoolean())
                .isTrue();
        assertThat(approvalLifecyclePolicy.get("tirithAlwaysScopeDowngradedToSession").getBoolean())
                .isTrue();
        assertThat(approvalLifecyclePolicy.get("currentThreadApprovalTtlMillis").getLong())
                .isEqualTo(30000L);
        assertThat(approvalLifecyclePolicy.get("sessionSnapshotUpdated").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("approvalRequestObserved").getBoolean()).isTrue();
        assertThat(approvalLifecyclePolicy.get("approvalResponseObserved").getBoolean()).isTrue();
        assertThat(String.valueOf(approvalLifecyclePolicy))
                .contains("once")
                .contains("session")
                .contains("always")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("slashApprovalConfirm")
                                .getBoolean())
                .isTrue();
        ONode slashConfirmPolicy =
                policyStatus.get("policy").get("coverage").get("slashConfirmPolicy");
        assertThat(slashConfirmPolicy.get("selectorSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("listSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("approveAllSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("denyAllSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("clearSessionSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("clearAlwaysSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("clearAllSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("pendingQueueSupported").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("pendingListUsesSafeSelector").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("commandPreviewRedacted").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("encodedUrlParameterRedacted").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("approvalMetadataRedacted").getBoolean()).isTrue();
        assertThat(slashConfirmPolicy.get("unsafeSelectorRejected").getBoolean()).isTrue();
        assertThat(String.valueOf(slashConfirmPolicy))
                .contains("/approve")
                .contains("/deny")
                .contains("/approve clear all")
                .contains("/deny status")
                .contains("once")
                .contains("session")
                .contains("always")
                .contains("[A-Za-z0-9_.-]{1,128}")
                .doesNotContain("secret-sudo");
        ONode approvalCardPolicy =
                policyStatus.get("policy").get("coverage").get("approvalCardPolicy");
        assertThat(approvalCardPolicy.get("deliveryMode").getString())
                .isEqualTo("dangerous_command_approval_card");
        assertThat(approvalCardPolicy.get("unsupportedPlatformsReturnEmptyExtras").getBoolean())
                .isTrue();
        assertThat(approvalCardPolicy.get("approvalIdSelectorSupported").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("unsafeSelectorRejected").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("outboundApprovalIdSanitized").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("unsafeApprovalIdFallsBackToKeySelector").getBoolean())
                .isTrue();
        assertThat(approvalCardPolicy.get("approveCommandGenerated").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("denyCommandGenerated").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("alwaysScopeCommandGenerated").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("sessionScopeCommandGenerated").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("domesticCardLabelsLocalized").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("feishuChineseCardLabels").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("qqbotSessionActionSupported").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("tirithPermanentApprovalHidden").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("commandPreviewRedacted").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("descriptionPreviewRedacted").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("encodedUrlParameterRedacted").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("semicolonUrlParameterRedacted").getBoolean()).isTrue();
        assertThat(approvalCardPolicy.get("fragmentUrlParameterRedacted").getBoolean()).isTrue();
        assertThat(String.valueOf(approvalCardPolicy))
                .contains("FEISHU")
                .contains("QQBOT")
                .contains("dangerous_approve")
                .contains("dangerous_deny")
                .doesNotContain("secret-sudo");
        ONode approvalAuditPolicy =
                policyStatus.get("policy").get("coverage").get("approvalAuditPolicy");
        assertThat(approvalAuditPolicy.get("requestEvents").getBoolean()).isTrue();
        assertThat(approvalAuditPolicy.get("responseEvents").getBoolean()).isTrue();
        assertThat(approvalAuditPolicy.get("observerFailureIsolated").getBoolean()).isTrue();
        assertThat(approvalAuditPolicy.get("approvalKeyRedacted").getBoolean()).isTrue();
        assertThat(approvalAuditPolicy.get("encodedUrlParameterRedacted").getBoolean()).isTrue();
        assertThat(approvalAuditPolicy.get("manualRevocationAudited").getBoolean()).isTrue();
        ONode mcpReloadPolicy = policyStatus.get("policy").get("coverage").get("mcpReloadPolicy");
        assertThat(mcpReloadPolicy.get("confirmRequired").getBoolean()).isTrue();
        assertThat(mcpReloadPolicy.get("slashConfirmBacked").getBoolean()).isTrue();
        assertThat(mcpReloadPolicy.get("persistentDisableSupported").getBoolean()).isTrue();
        assertThat(mcpReloadPolicy.get("toolChangeNoticeInjected").getBoolean()).isTrue();
        assertThat(mcpReloadPolicy.get("oauthUrlSafetyCovered").getBoolean()).isTrue();
        assertThat(mcpReloadPolicy.get("encodedUrlParameterRedacted").getBoolean()).isTrue();
        assertThat(policyStatus.get("policy").get("coverage").get("smartApproval").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("smartApprovalPolicy")
                                .get("active")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("tirithSmartApproval")
                                .getBoolean())
                .isTrue();
        ONode tirithApprovalPolicy =
                policyStatus.get("policy").get("coverage").get("tirithApprovalPolicy");
        assertThat(tirithApprovalPolicy.get("scanRunsInApprovalMode").getBoolean()).isTrue();
        assertThat(tirithApprovalPolicy.get("combinedWithLocalDangerRules").getBoolean()).isTrue();
        assertThat(tirithApprovalPolicy.get("permanentApprovalAllowed").getBoolean()).isFalse();
        assertThat(tirithApprovalPolicy.get("alwaysScopeDowngradedToSession").getBoolean())
                .isTrue();
        assertThat(tirithApprovalPolicy.get("descriptionRedacted").getBoolean()).isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("tirithPolicy")
                                .get("blockRequiresApproval")
                                .getBoolean())
                .isTrue();
        ONode tirithPolicy = policyStatus.get("policy").get("coverage").get("tirithPolicy");
        assertThat(tirithPolicy.get("commandPassedAsSingleArgument").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("nonInteractiveMode").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("jsonOutputMode").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("subprocessEnvironmentSanitized").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("timeoutKillsProcess").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("stdoutStderrCollectedSeparately").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("exitCodeZeroAllows").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("exitCodeOneBlocks").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("exitCodeTwoWarns").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("unexpectedExitCodeUsesFailureMode").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("parseFailureKeepsDecision").getBoolean()).isTrue();
        assertThat(tirithPolicy.get("toolShellDetectionApplied").getBoolean()).isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("cronApprovalPolicy")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("cronApprovalPolicyDetails")
                                .get("scriptContentChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("subagentApprovalPolicy")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("subagentApprovalPolicyDetails")
                                .get("pendingApprovalCreatedWhenDenied")
                                .getBoolean())
                .isFalse();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("hardlineCommandBlocks")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("hardlinePolicy")
                                .get("slashApproveBypassAllowed")
                                .getBoolean())
                .isFalse();
        assertThat(String.valueOf(policyStatus.get("policy").get("coverage").get("hardlinePolicy")))
                .contains("execute_python")
                .contains("metadata_url_access")
                .contains("approvalRequired")
                .contains("false");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("terminalGuardrails")
                                .getBoolean())
                .isTrue();
        ONode coverageTerminalGuardrailPolicy =
                policyStatus.get("policy").get("coverage").get("terminalGuardrailPolicy");
        assertThat(coverageTerminalGuardrailPolicy.get("inlineAmpersandBlocked").getBoolean())
                .isTrue();
        assertThat(coverageTerminalGuardrailPolicy.get("trailingAmpersandBlocked").getBoolean())
                .isTrue();
        assertThat(coverageTerminalGuardrailPolicy.get("longLivedForegroundBlocked").getBoolean())
                .isTrue();
        assertThat(
                        coverageTerminalGuardrailPolicy
                                .get("managedBackgroundProcessRequired")
                                .getBoolean())
                .isTrue();
        assertThat(coverageTerminalGuardrailPolicy.get("credentialPathPrechecked").getBoolean())
                .isTrue();
        assertThat(
                        coverageTerminalGuardrailPolicy
                                .get("downloadOutputDetachedOptionPrechecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        coverageTerminalGuardrailPolicy
                                .get("networkUploadSourcePathPrechecked")
                                .getBoolean())
                .isTrue();
        assertThat(coverageTerminalGuardrailPolicy.get("preproxyUrlPrechecked").getBoolean())
                .isTrue();
        assertThat(coverageTerminalGuardrailPolicy.get("systemDnsCommandPrechecked").getBoolean())
                .isTrue();
        assertThat(coverageTerminalGuardrailPolicy.get("systemProxyCommandPrechecked").getBoolean())
                .isTrue();
        assertThat(
                        coverageTerminalGuardrailPolicy
                                .get("windowsRegistryProxyCommandPrechecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        coverageTerminalGuardrailPolicy
                                .get("hostsAndResolverPathPrechecked")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(coverageTerminalGuardrailPolicy))
                .contains("nohup")
                .contains("docker compose up")
                .contains("execute_js")
                .doesNotContain("secret-sudo");
        ONode terminalOutputPolicy =
                policyStatus.get("policy").get("coverage").get("terminalOutputPolicy");
        assertThat(terminalOutputPolicy.get("ansiStripped").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("oscSequencesStripped").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("bidiControlsStripped").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("secretRedactionApplied").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("maxInlineChars").getInt()).isEqualTo(50000);
        assertThat(terminalOutputPolicy.get("headTailTruncation").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("truncationNoticeIncluded").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("emptySuccessMessage").getString()).isEqualTo("执行成功");
        assertThat(terminalOutputPolicy.get("timeoutNoticeAppended").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("sudoFailureHintAppended").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("outputTransformersSupported").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("transformerFailureIsolated").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("exitCodeSemanticsAvailable").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("exitCodeMeaningReturned").getBoolean()).isTrue();
        assertThat(terminalOutputPolicy.get("executeShellExitMeaningNotice").getBoolean()).isTrue();
        assertThat(String.valueOf(terminalOutputPolicy))
                .contains("执行成功")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("backgroundProcessPolicy")
                                .get("stdinExecutionPayloadChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("coverage")
                                        .get("backgroundProcessPolicy")))
                .contains("execute_js")
                .contains("waitTimeoutClamped")
                .doesNotContain("secret-sudo");
        assertThat(policyStatus.get("policy").get("coverage").get("urlSafety").getBoolean())
                .isTrue();
        ONode urlPolicyDetails = policyStatus.get("policy").get("coverage").get("urlPolicyDetails");
        assertThat(urlPolicyDetails.get("allowPrivateUrls").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("alwaysBlockedHostCount").getInt()).isGreaterThan(0);
        assertThat(urlPolicyDetails.get("alwaysBlockedIpCount").getInt()).isGreaterThan(0);
        assertThat(urlPolicyDetails.get("websiteBlocklistEnabled").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("websiteBlocklistDomainCount").getInt()).isEqualTo(1);
        assertThat(urlPolicyDetails.get("userinfoBlocked").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("sensitiveQueryBlocked").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("encodedSensitiveQueryBlocked").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("repeatedEncodedSensitiveQueryBlocked").getBoolean())
                .isTrue();
        assertThat(urlPolicyDetails.get("semicolonSensitiveQueryBlocked").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("fragmentSensitiveQueryBlocked").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("sensitivePathCredentialBlocked").getBoolean()).isTrue();
        assertThat(urlPolicyDetails.get("cloudMetadataBlocked").getBoolean()).isTrue();
        assertThat(String.valueOf(urlPolicyDetails))
                .contains("169.254")
                .contains("blocked.example")
                .contains("access_token")
                .doesNotContain("secret-sudo");
        ONode privateUrlPolicyDetails =
                policyStatus.get("policy").get("coverage").get("privateUrlPolicyDetails");
        assertThat(privateUrlPolicyDetails.get("allowPrivateUrls").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("cloudMetadataAlwaysBlocked").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("dnsResolutionRequired").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("obfuscatedIpv4Checked").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("ipv4MappedIpv6Checked").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("loopbackBlocked").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("linkLocalBlocked").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("siteLocalBlocked").getBoolean()).isTrue();
        assertThat(privateUrlPolicyDetails.get("reservedDocumentationRangesBlocked").getBoolean())
                .isTrue();
        assertThat(String.valueOf(privateUrlPolicyDetails))
                .contains("SOLONCLAW_ALLOW_PRIVATE_URLS")
                .contains("metadata.google.internal")
                .doesNotContain("secret-sudo");
        ONode websitePolicyDetails =
                policyStatus.get("policy").get("coverage").get("websitePolicyDetails");
        assertThat(websitePolicyDetails.get("enabled").getBoolean()).isTrue();
        assertThat(websitePolicyDetails.get("configuredDomainCount").getInt()).isEqualTo(1);
        assertThat(websitePolicyDetails.get("hostRuleNormalization").getBoolean()).isTrue();
        assertThat(websitePolicyDetails.get("wildcardSubdomainSupported").getBoolean()).isTrue();
        assertThat(websitePolicyDetails.get("schemeAndPathIgnoredForRules").getBoolean()).isTrue();
        assertThat(websitePolicyDetails.get("wwwPrefixIgnored").getBoolean()).isTrue();
        assertThat(websitePolicyDetails.get("sharedFilePathSafetyChecked").getBoolean()).isTrue();
        assertThat(String.valueOf(websitePolicyDetails))
                .contains("blocked.example")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("credentialFilePolicy")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("credentialMountPolicy")
                                .getBoolean())
                .isTrue();
        ONode credentialMountPolicyDetails =
                policyStatus.get("policy").get("coverage").get("credentialMountPolicyDetails");
        assertThat(credentialMountPolicyDetails.get("configCredentialFileCount").getInt())
                .isEqualTo(1);
        assertThat(credentialMountPolicyDetails.get("workspaceRelativeOnly").getBoolean())
                .isTrue();
        assertThat(credentialMountPolicyDetails.get("absolutePathRejected").getBoolean()).isTrue();
        assertThat(credentialMountPolicyDetails.get("pathTraversalRejected").getBoolean()).isTrue();
        assertThat(credentialMountPolicyDetails.get("hostPathsOmittedFromMetadata").getBoolean())
                .isTrue();
        assertThat(String.valueOf(credentialMountPolicyDetails))
                .contains("required_credential_files")
                .contains("terminal.credentialFiles")
                .contains("tool-results")
                .doesNotContain("credentials/oauth.json");
        ONode pathPolicyDetails =
                policyStatus.get("policy").get("coverage").get("pathPolicyDetails");
        assertThat(pathPolicyDetails.get("traversalBlocked").getBoolean()).isTrue();
        assertThat(pathPolicyDetails.get("controlCharactersBlocked").getBoolean()).isTrue();
        assertThat(pathPolicyDetails.get("rawControlCharactersBlocked").getBoolean()).isTrue();
        assertThat(pathPolicyDetails.get("normalizedControlCharactersBlocked").getBoolean())
                .isTrue();
        assertThat(pathPolicyDetails.get("devicePathBlocked").getBoolean()).isTrue();
        assertThat(pathPolicyDetails.get("rawBlockDeviceWriteBlocked").getBoolean()).isTrue();
        assertThat(pathPolicyDetails.get("skillsHubInternalReadBlocked").getBoolean()).isTrue();
        assertThat(pathPolicyDetails.get("writeDeniedExactPathCount").getInt()).isGreaterThan(0);
        assertThat(pathPolicyDetails.get("writeDeniedPrefixCount").getInt()).isGreaterThan(0);
        assertThat(pathPolicyDetails.get("writeDeniedWindowsPrefixCount").getInt())
                .isGreaterThan(0);
        assertThat(String.valueOf(pathPolicyDetails))
                .contains("/etc/passwd")
                .contains("c:/windows/")
                .contains("/dev/zero")
                .contains("A-Za-z0-9")
                .doesNotContain("secret-sudo");
        ONode credentialPolicyDetails =
                policyStatus.get("policy").get("coverage").get("credentialPolicyDetails");
        assertThat(credentialPolicyDetails.get("directorySegmentCount").getInt()).isGreaterThan(0);
        assertThat(credentialPolicyDetails.get("fileNameCount").getInt()).isGreaterThan(0);
        assertThat(credentialPolicyDetails.get("configuredCredentialFileCount").getInt())
                .isEqualTo(1);
        assertThat(credentialPolicyDetails.get("envExampleFilesAllowed").getBoolean()).isTrue();
        assertThat(String.valueOf(credentialPolicyDetails))
                .contains(".ssh")
                .contains(".env")
                .contains("credentials/oauth.json")
                .doesNotContain("secret-sudo");
        assertThat(policyStatus.get("policy").get("coverage").get("toolArgsSecurity").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolReturnedContentUrlSafety")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("recursiveUrlExtraction")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("returnedContentUrlExtraction")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("returnedSchemelessUrlChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("returnedDocumentContentChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("returnedDocumentMetadataUrlChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("returnedPojoUrlChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("encodedUrlParameterPolicyInherited")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("rawPathControlCharacterPolicyInherited")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("writeIntentDetection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("downloadOutputPathOptionChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("downloadOutputDetachedOptionChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("networkUploadSourcePathChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("networkUploadCredentialOnlyBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("preproxyOptionUrlChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("systemDnsCommandChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("setxProxyEnvironmentChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("systemProxyCommandChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("windowsRegistryProxyCommandChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("gitPersistentProxyConfigChecked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolArgsPolicy")
                                .get("unsupportedNetworkSchemeChecked")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(policyStatus.get("policy").get("coverage").get("toolArgsPolicy")))
                .contains("file_path")
                .contains("endpoint")
                .contains("browser_download_url")
                .contains("networkUploadSourcePathChecked")
                .contains("patch");
        assertThat(policyStatus.get("policy").get("coverage").get("schemaSanitizer").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("schemaSanitizerPolicy")
                                .get("mcpInputSchemaSanitized")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("coverage")
                                        .get("schemaSanitizerPolicy")))
                .contains("localFunctionTools")
                .contains("toolProviders")
                .contains("patternAndFormatStripped")
                .contains("unsupportedKeywordsStripped")
                .contains("snack4");
        assertThat(policyStatus.get("policy").get("coverage").get("patchParser").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("patchParserPolicy")
                                .get("atomicValidationBeforeWrite")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("coverage")
                                        .get("patchParserPolicy")))
                .contains("V4A")
                .contains("replaceRequiresUniqueMatchByDefault")
                .contains("moveWillNotOverwriteDestination")
                .contains("symlinkEscapeBlocked")
                .contains("credentialPolicyPrechecked");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("readOnlyAuditPolicy")
                                .get("executesCommand")
                                .getBoolean())
                .isFalse();
        ONode readOnlyAuditPolicy =
                policyStatus.get("policy").get("coverage").get("readOnlyAuditPolicy");
        assertThat(readOnlyAuditPolicy.get("opensNetworkConnection").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("readsTargetUrl").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("writesFile").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("storesAuditInput").getBoolean()).isFalse();
        assertThat(readOnlyAuditPolicy.get("secretRedactionApplied").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsCommandPolicyInherited").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("structuredCommandArgumentsJoined").getBoolean())
                .isTrue();
        assertThat(
                        readOnlyAuditPolicy
                                .get("nestedStructuredCommandArgumentsExtracted")
                                .getBoolean())
                .isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsUrlPolicyInherited").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsPathPolicyInherited").getBoolean()).isTrue();
        assertThat(readOnlyAuditPolicy.get("toolArgsJsonParseErrorsRedacted").getBoolean())
                .isTrue();
        assertThat(readOnlyAuditPolicy.get("commandPreviewLimitChars").getInt()).isEqualTo(400);
        assertThat(readOnlyAuditPolicy.get("findingMessageLimitChars").getInt()).isEqualTo(1000);
        assertThat(String.valueOf(readOnlyAuditPolicy))
                .contains("security_audit")
                .contains("tool_args")
                .contains("policy")
                .doesNotContain("secret-sudo");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("subprocessEnvironmentSanitizer")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("subprocessEnvironmentPolicy")
                                .get("providerBlocklistOverridesPassthrough")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("coverage")
                                        .get("subprocessEnvironmentPolicy")))
                .contains("skillScopedPassthroughSupported")
                .contains("toolBackendSecretsBlocked")
                .contains("pathFallbackEnabledForPosix")
                .doesNotContain("OPENAI_API_KEY")
                .doesNotContain("TENOR_API_KEY");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("codeExecutionPolicyAuditable")
                                .getBoolean())
                .isTrue();
        ONode codeExecutionPolicy =
                policyStatus.get("policy").get("coverage").get("codeExecutionPolicy");
        assertThat(codeExecutionPolicy.get("executeCodeSupported").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("executePythonSupported").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("executeJsSupported").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("scriptPreflightPathPolicy").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("scriptPreflightUrlPolicy").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("dangerousCommandRulesApplied").getBoolean()).isTrue();
        assertThat(
                        codeExecutionPolicy
                                .get("managedFileToolPathLiteralsIgnoredForPreflight")
                                .getBoolean())
                .isTrue();
        assertThat(codeExecutionPolicy.get("sandboxEnvironmentSanitized").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("rpcToolBridgeEnabled").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("defaultTimeoutSeconds").getInt()).isEqualTo(300);
        assertThat(codeExecutionPolicy.get("stderrLimitChars").getInt()).isEqualTo(10000);
        assertThat(codeExecutionPolicy.get("timeoutKillsProcess").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("stagingCleanup").getBoolean()).isTrue();
        assertThat(codeExecutionPolicy.get("outputRedacted").getBoolean()).isTrue();
        assertThat(String.valueOf(codeExecutionPolicy))
                .contains("websearch")
                .contains("webfetch")
                .contains("read_file")
                .contains("write_file")
                .contains("search_files")
                .contains("terminal")
                .contains("providerBlocklistOverridesPassthrough")
                .doesNotContain("TENOR_API_KEY");
        assertThat(policyStatus.get("policy").get("coverage").get("toolResultStorage").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("resultRefReturned")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("persistedOutputRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("fullOutputSavedRaw")
                                .getBoolean())
                .isFalse();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("pinnedInlineRawObservationAllowed")
                                .getBoolean())
                .isFalse();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("pinnedInlineObservationRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("pinnedInlinePreviewRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("toolResultStoragePolicy")
                                .get("storageFailureFallsBackToPreviewOnly")
                                .getBoolean())
                .isTrue();
        assertThat(
                        String.valueOf(
                                policyStatus
                                        .get("policy")
                                        .get("coverage")
                                        .get("toolResultStoragePolicy")))
                .contains("read_file")
                .contains("previewRedacted")
                .contains("describedPreviewRedacted")
                .contains("persistedOutputRedacted")
                .contains("pathSegmentsSanitized")
                .contains(".jimuqu/tool-results")
                .doesNotContain(env.appConfig.getRuntime().getHome());
        assertThat(policyStatus.get("policy").get("coverage").get("mcpUrlSafety").getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("mcpRuntimePolicyAuditable")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("mcpPackageSecurity")
                                .getBoolean())
                .isTrue();
        ONode mcpRuntimePolicy = policyStatus.get("policy").get("coverage").get("mcpRuntimePolicy");
        assertThat(mcpRuntimePolicy.get("remoteEndpointUrlSafety").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("remoteToolArgumentUrlSafety").getBoolean()).isTrue();
        assertThat(
                        mcpRuntimePolicy
                                .get("remoteToolStructuredCredentialArgumentBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(mcpRuntimePolicy.get("remoteToolArgumentPathSafety").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("resourceUriUrlSafety").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("toolsChangeNotificationPersisted").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("oauthFailureStructuredReauth").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("recoverableTransportRetry").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("accessTokenHeaderOnlyForRemote").getBoolean()).isTrue();
        assertThat(mcpRuntimePolicy.get("authorizationHeaderCaseInsensitive").getBoolean())
                .isTrue();
        assertThat(String.valueOf(mcpRuntimePolicy))
                .contains("streamable_stateless")
                .contains("file_path")
                .contains("invalid_token")
                .doesNotContain("secret-sudo");
        ONode mcpOAuthPolicy = policyStatus.get("policy").get("coverage").get("mcpOAuthPolicy");
        assertThat(mcpOAuthPolicy.get("authorizationEndpointUrlSafety").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("tokenEndpointUrlSafety").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("tokenEndpointRedirectUrlSafety").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("tokenEndpointRedirectLimit").getInt()).isEqualTo(5);
        assertThat(mcpOAuthPolicy.get("stateValidationRequired").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("pkceS256Required").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("codeVerifierHiddenFromStatus").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("accessTokenRedacted").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("refreshTokenRedacted").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("clientSecretRedacted").getBoolean()).isTrue();
        assertThat(mcpOAuthPolicy.get("handle401RefreshThenReauth").getBoolean()).isTrue();
        assertThat(String.valueOf(mcpOAuthPolicy))
                .contains("has_access_token")
                .contains("has_refresh_token")
                .contains("has_client_secret")
                .doesNotContain("secret-sudo");
        ONode mcpPackagePolicy =
                policyStatus.get("policy").get("coverage").get("mcpPackageSecurityPolicy");
        assertThat(mcpPackagePolicy.get("malwareBlocksSaveAndCheck").getBoolean()).isTrue();
        assertThat(mcpPackagePolicy.get("requestFailureFailsOpen").getBoolean()).isFalse();
        assertThat(mcpPackagePolicy.get("unsafeEndpointBlocksBeforeNetwork").getBoolean()).isTrue();
        assertThat(mcpPackagePolicy.get("messageRedacted").getBoolean()).isTrue();
        assertThat(mcpPackagePolicy.get("persistedListReasonExposed").getBoolean()).isTrue();
        assertThat(mcpPackagePolicy.get("npxPackageOptionParsed").getBoolean()).isTrue();
        assertThat(mcpPackagePolicy.get("pipxRunSubcommandSkipped").getBoolean()).isTrue();
        assertThat(mcpPackagePolicy.get("pypiSourceOptionParsed").getBoolean()).isTrue();
        assertThat(String.valueOf(mcpPackagePolicy))
                .contains("npx")
                .contains("uvx")
                .contains("pipx")
                .contains("malware_advisory")
                .contains("unsafe_endpoint");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("attachmentUrlSafety")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("attachmentCachePathSafety")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("attachmentDisplayNameRedaction")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("terminalAttachmentPathSafety")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("terminalAttachmentPreviewRedaction")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("terminalAttachmentResolvedNameRedaction")
                                .getBoolean())
                .isTrue();
        ONode attachmentPolicy = policyStatus.get("policy").get("coverage").get("attachmentPolicy");
        assertThat(
                        attachmentPolicy
                                .get("downloadIo")
                                .get("redirectUrlCheckedBeforeFollow")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("downloadIo")
                                .get("crossHostHeaderForwardingBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("mediaCache")
                                .get("mediaReferenceTraversalBlocked")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("mediaCache")
                                .get("hostPathsNotReturnedInMediaReference")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("mediaCache")
                                .get("safeOriginalNameSecretRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("terminalPaste")
                                .get("pathPolicyCheckedBeforeCache")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("terminalPaste")
                                .get("canonicalPathResolvedBeforePolicy")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("terminalPaste")
                                .get("cacheWriteAfterPolicyOnly")
                                .getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("terminalPaste")
                                .get("duplicatePathDeduplicated")
                                .getBoolean())
                .isTrue();
        assertThat(attachmentPolicy.get("terminalPaste").get("credentialPathBlocked").getBoolean())
                .isTrue();
        assertThat(attachmentPolicy.get("terminalPaste").get("blockedPreviewRedacted").getBoolean())
                .isTrue();
        assertThat(attachmentPolicy.get("terminalPaste").get("missingPreviewRedacted").getBoolean())
                .isTrue();
        assertThat(
                        attachmentPolicy
                                .get("terminalPaste")
                                .get("resolvedDisplayNameRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(attachmentPolicy))
                .contains("workspace://cache/media")
                .doesNotContain(env.appConfig.getRuntime().getHome());
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("sudoRewritePolicy")
                                .get("passwordRedacted")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("mcpReloadConfirmation")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("mcpToolChangeNotice")
                                .getBoolean())
                .isTrue();
        assertThat(String.valueOf(policyStatus.get("policy").get("activeSurfaces")))
                .contains("approval")
                .contains("approvalLifecycle")
                .contains("approvalAuditLog")
                .contains("smartApproval")
                .contains("tirithSmartApproval")
                .contains("cronApprovalPolicy")
                .contains("subagentApprovalPolicy")
                .contains("hardlineCommand")
                .contains("terminalGuardrails")
                .contains("urlSafety")
                .contains("privateUrlPolicy")
                .contains("websitePolicy")
                .contains("credentialFilePolicy")
                .contains("credentialMountPolicy")
                .contains("pathSecurity")
                .contains("toolArgsSecurity")
                .contains("schemaSanitizer")
                .contains("patchParser")
                .contains("subprocessEnvironmentSanitizer")
                .contains("toolResultStorage")
                .contains("codeExecution")
                .contains("mcpRuntimePolicy")
                .contains("mcpOauthUrlSafety")
                .contains("mcpOauthPolicy")
                .contains("mcpPackageSecurity")
                .contains("mcpReloadConfirmation")
                .contains("mcpToolChangeNotice")
                .contains("attachmentPolicy")
                .contains("terminalAttachmentPathSafety")
                .contains("readOnlyAuditTool");
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("dangerousCommandApprovalPolicy")
                                .get("pythonCredentialFileClipboardExportDetection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("dangerousCommandApprovalPolicy")
                                .get("javascriptCredentialFileClipboardExportDetection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("dangerousCommandApprovalPolicy")
                                .get("pythonCredentialFileLogWriteDetection")
                                .getBoolean())
                .isTrue();
        assertThat(
                        policyStatus
                                .get("policy")
                                .get("coverage")
                                .get("dangerousCommandApprovalPolicy")
                                .get("javascriptCredentialFileLogWriteDetection")
                                .getBoolean())
                .isTrue();
        assertThat(policyStatus.toJson())
                .doesNotContain("secret-sudo")
                .doesNotContain("TENOR_API_KEY");
    }

    @Test
    void shouldRedactSecretsInSecurityAuditFindingsWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setUrlGuardrailMode("strict");
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));

        ONode command =
                ONode.ofJson(
                        tools.audit(
                                "command",
                                "execute_shell",
                                "curl https://blocked.example/docs?token=secret123",
                                null,
                                null,
                                null,
                                null));
        ONode url =
                ONode.ofJson(
                        tools.audit(
                                "url",
                                null,
                                null,
                                "https://blocked.example/docs?token=secret123#access%255Ftoken=fragment-secret",
                                null,
                                null,
                                null));
        ONode toolArgs =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "webfetch",
                                null,
                                null,
                                null,
                                null,
                                "{\"url\":\"https://blocked.example/docs?api%255Fkey=encoded-secret\"}"));

        assertThat(command.get("decision").getString()).isEqualTo("block");
        assertThat(command.toJson()).contains("token=***").doesNotContain("secret123");
        assertThat(url.get("decision").getString()).isEqualTo("block");
        assertThat(url.toJson())
                .contains("token=***")
                .contains("access%255Ftoken=***")
                .doesNotContain("secret123")
                .doesNotContain("fragment-secret");
        assertThat(toolArgs.get("decision").getString()).isEqualTo("block");
        assertThat(toolArgs.toJson()).contains("api%255Fkey=***").doesNotContain("encoded-secret");
    }

    @Test
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

    @Test
    void shouldAuditToolArgWorkingDirectoriesAsPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode terminal =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "terminal",
                                null,
                                null,
                                null,
                                null,
                                "{\"command\":\"echo ok\",\"workdir\":\".ssh\"}"));
        ONode process =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "process",
                                null,
                                null,
                                null,
                                null,
                                "{\"action\":\"start\",\"command\":\"echo ok\",\"cwd\":\"credentials.json\"}"));

        assertThat(terminal.get("decision").getString()).isEqualTo("block");
        assertThat(terminal.get("blocking").getBoolean()).isTrue();
        assertThat(terminal.toJson())
                .contains("file_policy")
                .contains("敏感系统/凭据文件")
                .doesNotContain(".ssh");
        assertThat(process.get("decision").getString()).isEqualTo("block");
        assertThat(process.get("blocking").getBoolean()).isTrue();
        assertThat(process.toJson())
                .contains("file_policy")
                .contains("敏感系统/凭据文件")
                .doesNotContain("credentials.json");
    }

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
    void shouldNormalizeWrappedAndEscapedUrlsBeforeSecurityAudit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().setUrlGuardrailMode("strict");
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode command =
                ONode.ofJson(
                        tools.audit(
                                "command",
                                "execute_shell",
                                "curl '&lt;https://blocked.example/docs?token=secret123&amp;page=1&gt;'",
                                null,
                                null,
                                null,
                                null));
        ONode toolArgs =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "webfetch",
                                null,
                                null,
                                null,
                                null,
                                "{\"url\":\"<https://blocked.example/docs?token=secret123&amp;page=1>\"}"));

        assertThat(command.get("decision").getString()).isEqualTo("block");
        assertThat(command.toJson())
                .contains("blocked.example")
                .contains("token=***")
                .doesNotContain("secret123")
                .doesNotContain("&amp;");
        assertThat(toolArgs.get("decision").getString()).isEqualTo("block");
        assertThat(toolArgs.toJson())
                .contains("blocked.example")
                .contains("token=***")
                .doesNotContain("secret123")
                .doesNotContain("&amp;");
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
    void shouldExposeProviderManagementToolForNaturalLanguageModelConfiguration()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("provider_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("ProviderManageTools");
    }

    @Test
    void shouldExposeSessionManagementToolForNaturalLanguageSessionInspection()
            throws Exception {
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
                env.toolRegistry.resolveEnabledTools("MEMORY:trajectory-tool-room:trajectory-tool-user")
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
                env.toolRegistry
                        .resolveEnabledTools("MEMORY:session-title-tool-room:session-title-tool-user")
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
    void shouldExposeAnalyticsManagementToolForNaturalLanguageUsageInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("analytics_manage");
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
    void shouldProbeSubprocessEnvironmentThroughNaturalLanguageDiagnosticsTool()
            throws Exception {
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
    void shouldExposeTuiRuntimeManagementToolForNaturalLanguageSetupInspection()
            throws Exception {
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
                                        "setup_status",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null));

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
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        3));

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

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("workspace_manage");
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
                                () ->
                                        new AssertionError(
                                                "workspace config manage tool missing"));

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
                                () ->
                                        new AssertionError(
                                                "workspace config manage tool missing"));

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
    void shouldExposeToolsetsManagementToolForNaturalLanguageToolsetInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey))
                .contains("toolsets_manage");
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
