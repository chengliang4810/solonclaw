package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
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
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
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
     * 为测试中的一次外部网络工具调用模拟用户已完成单次审批。
     */
    private static void approveNetworkOperationForTest(String target) {
        SecurityPolicyService.approveUrlPolicyForCurrentThread("network_external_operation", target);
    }

    /**
     * 断言工具结果为当前成功状态，避免测试重新依赖已删除的 success 布尔字段。
     */
    private static void assertToolSuccess(ONode result) {
        assertThat(result.get("status").getString()).isNotEqualTo("error");
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
                        "session_manage",
                        "analytics_manage",
                        "logs_manage",
                        "media_manage",
                        "status_manage",
                        "doctor_manage",
                        "insights_manage",
                        "approval_events_manage",
                        "workspace_manage",
                        "skills_list",
                        "skill_view",
                        "skill_manage",
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
        assertThat(joined).contains("ConfigRefreshTool");
        assertThat(joined).doesNotContain("ToolGatewayTalent");
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
        assertThat(dangerous.get("decision").getString()).isEqualTo("block");
        assertThat(dangerous.get("blocking").getBoolean()).isTrue();
        assertThat(dangerous.get("approval_required").getBoolean()).isTrue();
        assertThat(String.valueOf(dangerous.get("findings")))
                .contains("request_approval")
                .contains("change_command");
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
        assertThat(structuredCommandArgs.get("decision").getString()).isEqualTo("block");
        assertThat(structuredCommandArgs.get("blocking").getBoolean()).isTrue();
        assertThat(structuredCommandArgs.get("approval_required").getBoolean()).isTrue();
        assertThat(String.valueOf(structuredCommandArgs.get("findings")))
                .contains("recursive_delete")
                .contains("request_approval")
                .contains("change_command");
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
        assertThat(nestedStructuredCommandArgs.get("decision").getString()).isEqualTo("block");
        assertThat(nestedStructuredCommandArgs.get("blocking").getBoolean()).isTrue();
        assertThat(nestedStructuredCommandArgs.get("approval_required").getBoolean()).isTrue();
        assertThat(String.valueOf(nestedStructuredCommandArgs.get("findings")))
                .contains("recursive_delete")
                .contains("request_approval")
                .contains("change_command");

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
                                .get("outsideWorkspaceWriteApprovalRequired")
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
    void shouldAuditStructuredCredentialToolArgsBeforeNetworkTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        policy,
                        new DangerousCommandApprovalService(
                                env.globalSettingRepository, env.appConfig, policy, null),
                        null,
                        env.appConfig);

        ONode header =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "webfetch",
                                null,
                                null,
                                null,
                                null,
                                "{\"url\":\"https://example.com/docs\",\"headers\":{\"Authorization\":\"Bearer ghp_toolargheader12345\"}}"));
        ONode body =
                ONode.ofJson(
                        tools.audit(
                                "tool_args",
                                "websearch",
                                null,
                                null,
                                null,
                                null,
                                "{\"query\":\"docs\",\"payload\":{\"apiKey\":\"sk-toolargbody12345\"}}"));

        assertThat(header.get("decision").getString()).isEqualTo("block");
        assertThat(header.get("blocking").getBoolean()).isTrue();
        assertThat(header.toJson())
                .contains("工具参数包含敏感凭据字段")
                .contains("Authorization")
                .doesNotContain("ghp_toolargheader12345");
        assertThat(body.get("decision").getString()).isEqualTo("block");
        assertThat(body.get("blocking").getBoolean()).isTrue();
        assertThat(body.toJson())
                .contains("工具参数包含敏感凭据字段")
                .contains("apiKey")
                .doesNotContain("sk-toolargbody12345");
    }

    @Test
    void shouldRedactStructuredCredentialToolArgFieldReference() throws Exception {
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
                                "{\"url\":\"https://example.com/docs\",\"headers\":{\"Authorization\\nBearer ghp_toolargfield12345\":\"secret-value\"}}"));

        assertThat(result.get("decision").getString()).isEqualTo("block");
        assertThat(result.toJson())
                .contains("tool_arg://Authorization")
                .doesNotContain("ghp_toolargfield12345")
                .doesNotContain("Authorization\\nBearer");
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
    void shouldManageJimuquStyleBackgroundProcesses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "echo jimuqu-process-ok",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(started);
        String sessionId = started.get("session_id").getString();
        assertThat(sessionId).startsWith("proc_");

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        assertToolSuccess(waited);
        assertThat(waited.get("process_status").getString()).isEqualTo("exited");
        assertThat(waited.get("exited").getBoolean()).isTrue();
        assertThat(waited.get("output").getString()).contains("jimuqu-process-ok");

        ONode polled =
                ONode.ofJson(tools.process("poll", null, sessionId, null, null, null, null, null));
        assertThat(polled.get("process_status").getString()).isEqualTo("exited");
        assertThat(polled.get("output_preview").getString()).contains("jimuqu-process-ok");
        assertThat(polled.get("uptime_seconds").getLong()).isGreaterThanOrEqualTo(0L);

        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        assertThat(listed.get("count").getInt()).isGreaterThanOrEqualTo(1);
        assertThat(String.valueOf(listed.get("processes")))
                .contains("output_preview")
                .contains("uptime_seconds");
    }

    @Test
    void shouldRedactSecretsFromProcessToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode unsupported =
                ONode.ofJson(
                        tools.process(
                                "inspect --token=ghp_processaction12345",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        ONode missing =
                ONode.ofJson(
                        tools.process(
                                "poll",
                                null,
                                "proc_token=ghp_processsession12345",
                                null,
                                null,
                                null,
                                null,
                                null));

        assertThat(unsupported.get("error").getString())
                .contains("token=***")
                .doesNotContain("ghp_processaction12345");
        assertThat(missing.get("error").getString())
                .contains("token=***")
                .doesNotContain("ghp_processsession12345");
    }

    @Test
    void shouldExposeTerminalNotificationMetadataThroughProcessTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry.ManagedProcess managed =
                env.processRegistry.start(
                        javaSleepCommand(), new File(env.appConfig.getRuntime().getHome()));
        managed.setNotifyOnComplete(true);
        managed.setWatchPatterns(java.util.Collections.singletonList("ready"));
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode polled =
                ONode.ofJson(
                        tools.process("poll", null, managed.getId(), null, null, null, null, null));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));

        assertThat(polled.get("notify_on_complete").getBoolean()).isTrue();
        assertThat(polled.get("watch_patterns").get(0).getString()).isEqualTo("ready");
        assertThat(String.valueOf(listed.get("processes")))
                .contains("notify_on_complete")
                .contains("watch_patterns")
                .contains("ready");

        assertThat(env.processRegistry.stop(managed.getId())).isTrue();
    }

    @Test
    void shouldRedactSensitiveWatchPatternsThroughProcessToolWithCanonicalConfig()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry.ManagedProcess managed =
                env.processRegistry.start(
                        javaSleepCommand(), new File(env.appConfig.getRuntime().getHome()));
        managed.setWatchPatterns(java.util.Collections.singletonList("token=secret123"));
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode polled =
                ONode.ofJson(
                        tools.process("poll", null, managed.getId(), null, null, null, null, null));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));

        assertThat(polled.get("watch_patterns").get(0).getString()).isEqualTo("token=***");
        assertThat(polled.toJson()).doesNotContain("secret123");
        assertThat(listed.toJson()).contains("token=***").doesNotContain("secret123");
        assertThat(managed.getWatchPatterns()).containsExactly("token=secret123");

        assertThat(env.processRegistry.stop(managed.getId())).isTrue();
    }

    @Test
    void shouldExposeManagedProcessEventsThroughProcessTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessRegistry.ManagedProcess managed =
                registry.start(
                        shortEchoCommand(),
                        new File(env.appConfig.getRuntime().getHome()),
                        true,
                        java.util.Collections.<String>emptyList());
        ProcessTools tools =
                new ProcessTools(
                        registry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        assertThat(registry.waitFor(managed.getId(), 5000L)).isTrue();
        ONode events =
                ONode.ofJson(
                        tools.process(
                                "events", null, null, null, null, null, null, Integer.valueOf(10)));

        assertToolSuccess(events);
        assertThat(events.get("count").getInt()).isEqualTo(1);
        assertThat(events.get("events").get(0).get("type").getString()).isEqualTo("completion");
        assertThat(events.get("events").get(0).get("session_id").getString())
                .isEqualTo(managed.getId());
    }

    @Test
    void shouldExposeManagedProcessLifecycleSnapshotsThroughProcessTool() throws Exception {
        assumeTrue(
                !System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessTools tools =
                new ProcessTools(
                        registry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode completedStart =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "printf 'ok token=secret123\\n'",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String completedId = completedStart.get("session_id").getString();
        assertThat(registry.waitFor(completedId, 5000L)).isTrue();

        ONode failedStart =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "printf 'bad token=secret123\\n'; exit 7",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String failedId = failedStart.get("session_id").getString();
        assertThat(registry.waitFor(failedId, 5000L)).isTrue();

        ONode lifecycle =
                ONode.ofJson(
                        tools.process(
                                "lifecycle",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Integer.valueOf(10)));
        ONode lifecycleAgain =
                ONode.ofJson(
                        tools.process(
                                "lifecycle",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Integer.valueOf(10)));
        ONode polled =
                ONode.ofJson(tools.process("detail", null, failedId, null, null, null, null, null));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        String lifecycleJson = lifecycle.toJson();

        assertToolSuccess(lifecycle);
        assertThat(lifecycle.get("count").getInt()).isEqualTo(4);
        assertThat(lifecycleJson)
                .contains("\"type\":\"started\"")
                .contains("\"type\":\"completed\"")
                .contains("\"type\":\"failed\"")
                .contains("\"exitCode\":7")
                .contains(completedId)
                .contains(failedId)
                .contains("token=***")
                .doesNotContain("secret123");
        assertThat(lifecycleAgain.get("count").getInt()).isEqualTo(4);
        assertThat(polled.get("lifecycle").toJson())
                .contains("\"type\":\"started\"")
                .contains("\"type\":\"failed\"");
        assertThat(listed.toJson()).contains("lifecycle_last_event");
    }

    @Test
    void shouldExposeKilledManagedProcessLifecycleSnapshot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessTools tools =
                new ProcessTools(
                        registry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();
        ONode killed =
                ONode.ofJson(
                        tools.process(
                                "kill",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        ONode detail =
                ONode.ofJson(
                        tools.process("status", null, sessionId, null, null, null, null, null));

        assertThat(killed.get("process_status").getString()).isEqualTo("killed");
        assertThat(detail.get("lifecycle").toJson())
                .contains("\"type\":\"started\"")
                .contains("\"type\":\"killed\"")
                .doesNotContain("\"type\":\"failed\"");
    }

    @Test
    void shouldAttachJimuquExitCodeMeaningToManagedProcessResults() throws Exception {
        assumeTrue(
                !System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                "test -f /definitely-not-a-jimuqu-file",
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        assertThat(waited.get("exit_code").getInt()).isEqualTo(1);
        assertThat(waited.get("exit_code_meaning").getString())
                .isEqualTo("Condition evaluated to false (expected, not an error)");

        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        assertThat(String.valueOf(listed.get("processes")))
                .contains("exit_code_meaning")
                .contains("Condition evaluated to false");
    }

    @Test
    void shouldRedactSecretsFromManagedProcessOutputsAndMetadata() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setUrlGuardrailMode("bypass");
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                secretEchoCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(started.get("status").getString())
                .as("process start result: %s", started.toString())
                .isNotEqualTo("error");
        String sessionId = started.get("session_id").getString();
        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        ONode polled =
                ONode.ofJson(tools.process("poll", null, sessionId, null, null, null, null, null));
        ONode logged =
                ONode.ofJson(
                        tools.process(
                                "log",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                Integer.valueOf(0),
                                Integer.valueOf(10)));
        ONode listed =
                ONode.ofJson(tools.process("list", null, null, null, null, null, null, null));
        String combined =
                waited.toString() + polled.toString() + logged.toString() + listed.toString();

        assertThat(combined)
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123");
    }

    @Test
    void shouldRedactSecretsFromManagedProcessEvents() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessRegistry registry = new ProcessRegistry(null, 1000L, 3, 100, 1000L, 1000L);
        ProcessRegistry.ManagedProcess managed =
                registry.start(
                        secretEventEchoCommand(),
                        new File(env.appConfig.getRuntime().getHome()),
                        true,
                        java.util.Collections.<String>emptyList());
        ProcessTools tools =
                new ProcessTools(
                        registry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        assertThat(registry.waitFor(managed.getId(), 5000L)).isTrue();
        ONode events =
                ONode.ofJson(
                        tools.process(
                                "events", null, null, null, null, null, null, Integer.valueOf(10)));
        String json = events.toJson();

        assertToolSuccess(events);
        assertThat(events.get("events").get(0).get("type").getString()).isEqualTo("completion");
        assertThat(json)
                .contains("api_key=***")
                .contains("token=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123");
    }

    @Test
    void shouldReturnCleanErrorsForInvalidTerminalCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(),
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        env.processRegistry);

        ONode executeNull = ONode.ofJson(shell.execute(null, Integer.valueOf(1000)));
        ONode terminalNull =
                ONode.ofJson(
                        shell.terminal(
                                null,
                                Boolean.FALSE,
                                Integer.valueOf(1),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.FALSE));
        ONode backgroundNull =
                ONode.ofJson(
                        shell.terminal(
                                null,
                                Boolean.TRUE,
                                Integer.valueOf(1),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.TRUE));

        assertToolError(executeNull);
        assertThat(executeNull.get("status").getString()).isEqualTo("error");
        assertThat(executeNull.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(executeNull.get("error").getString())
                .contains("expected string")
                .contains("null");
        assertToolError(terminalNull);
        assertThat(terminalNull.get("error").getString())
                .contains("expected string")
                .contains("null");
        assertToolError(backgroundNull);
        assertThat(backgroundNull.get("background").getBoolean()).isTrue();
        assertThat(env.processRegistry.runningCount()).isZero();
    }

    @Test
    void shouldRejectForegroundTerminalTimeoutsAboveConfiguredCap() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(),
                        env.appConfig,
                        new SecurityPolicyService(env.appConfig),
                        env.processRegistry);

        ONode executeTooLong =
                ONode.ofJson(shell.execute(shortEchoCommand(), Integer.valueOf(600001)));
        ONode terminalTooLong =
                ONode.ofJson(
                        shell.terminal(
                                shortEchoCommand(),
                                Boolean.FALSE,
                                Integer.valueOf(601),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.FALSE));
        ONode backgroundLong =
                ONode.ofJson(
                        shell.terminal(
                                javaSleepCommand(),
                                Boolean.TRUE,
                                Integer.valueOf(9999),
                                env.appConfig.getRuntime().getHome(),
                                Boolean.FALSE));
        String backgroundSessionId = backgroundLong.get("session_id").getString();

        assertToolError(executeTooLong);
        assertThat(executeTooLong.get("status").getString()).isEqualTo("error");
        assertThat(executeTooLong.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(executeTooLong.get("error").getString())
                .contains("600001ms")
                .contains("600000ms")
                .contains("background=true");

        assertToolError(terminalTooLong);
        assertThat(terminalTooLong.get("status").getString()).isEqualTo("error");
        assertThat(terminalTooLong.get("exit_code").getInt()).isEqualTo(-1);
        assertThat(terminalTooLong.get("error").getString())
                .contains("601000ms")
                .contains("600000ms")
                .contains("background=true");

        assertToolSuccess(backgroundLong);
        assertThat(backgroundLong.get("background").getBoolean()).isTrue();
        assertThat(backgroundSessionId).isNotBlank();
        env.processRegistry.stop(backgroundSessionId);
    }

    @Test
    void shouldPageManagedProcessLogs() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                multiLineEchoCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();
        tools.process("wait", null, sessionId, null, null, Integer.valueOf(5), null, null);

        ONode lastTwo =
                ONode.ofJson(
                        tools.process(
                                "log",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                Integer.valueOf(0),
                                Integer.valueOf(2)));
        assertToolSuccess(lastTwo);
        assertThat(lastTwo.get("total_lines").getInt()).isGreaterThanOrEqualTo(4);
        assertThat(lastTwo.get("showing").getString()).isEqualTo("2 lines");
        assertThat(lastTwo.get("output").getString()).contains("line-3").contains("line-4");
        assertThat(lastTwo.get("output").getString()).doesNotContain("line-1");

        ONode middle =
                ONode.ofJson(
                        tools.process(
                                "log",
                                null,
                                sessionId,
                                null,
                                null,
                                null,
                                Integer.valueOf(1),
                                Integer.valueOf(2)));
        assertThat(middle.get("output").getString()).contains("line-2").contains("line-3");
        assertThat(middle.get("output").getString()).doesNotContain("line-4");
    }

    @Test
    void shouldStopManagedBackgroundProcessesThroughStopCommandRegistry() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));

        assertThat(env.processRegistry.runningCount()).isEqualTo(1);
        assertThat(env.processRegistry.stop(started.get("session_id").getString())).isTrue();
        assertThat(env.processRegistry.runningCount()).isZero();
    }

    @Test
    void shouldReturnJimuquKillStatusesForManagedProcesses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode killed =
                ONode.ofJson(
                        tools.process(
                                "kill",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(killed);
        assertThat(killed.get("process_status").getString()).isEqualTo("killed");
        assertThat(killed.get("stopped").getBoolean()).isTrue();

        ONode killedAgain =
                ONode.ofJson(
                        tools.process(
                                "kill",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(killedAgain.get("process_status").getString()).isEqualTo("already_exited");
        assertThat(killedAgain.get("exit_code").isNull()).isFalse();
    }

    @Test
    void shouldReturnTimeoutWhenWaitingForRunningManagedProcess() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(0),
                                null,
                                null));

        assertToolSuccess(waited);
        assertThat(waited.get("process_status").getString()).isEqualTo("timeout");
        assertThat(waited.get("running").getBoolean()).isTrue();
        assertThat(waited.get("timeout_note").getString()).contains("still running");

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldClampManagedProcessWaitTimeoutToConfiguredLimit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setProcessWaitTimeoutSeconds(1);
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                javaSleepCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();
        long startedAt = System.currentTimeMillis();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(60),
                                null,
                                null));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertToolSuccess(waited);
        assertThat(waited.get("process_status").getString()).isEqualTo("timeout");
        assertThat(waited.get("timeout_note").getString())
                .contains("Requested wait of 60s was clamped to configured limit of 1s");
        assertThat(elapsed).isLessThan(5000L);

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldWriteSubmitAndCloseManagedProcessStdin() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                stdinEchoCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode write =
                ONode.ofJson(
                        tools.process(
                                "write",
                                null,
                                sessionId,
                                null,
                                "alpha",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(write);

        ONode submit =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "-beta",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(submit);

        ONode close =
                ONode.ofJson(
                        tools.process(
                                "close",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolSuccess(close);
        assertThat(close.get("stdin_closed").getBoolean()).isTrue();

        ONode waited =
                ONode.ofJson(
                        tools.process(
                                "wait",
                                null,
                                sessionId,
                                null,
                                null,
                                Integer.valueOf(5),
                                null,
                                null));
        assertToolSuccess(waited);
        assertThat(waited.get("output").getString()).contains("alpha-beta");

        ONode writeAfterExit =
                ONode.ofJson(
                        tools.process(
                                "write",
                                null,
                                sessionId,
                                null,
                                "late",
                                Integer.valueOf(1),
                                null,
                                null));
        assertThat(writeAfterExit.get("process_status").getString()).isEqualTo("already_exited");
    }

    @Test
    void shouldApplyTerminalGuardrailsToManagedProcessStdinForShells() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("approval");
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode started =
                ONode.ofJson(
                        tools.process(
                                "start",
                                interactiveShellCommand(),
                                null,
                                env.appConfig.getRuntime().getHome(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));
        String sessionId = started.get("session_id").getString();

        ONode blocked =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "curl http://169.254.169.254/latest/meta-data/?token=secret",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolError(blocked);
        assertThat(blocked.get("error").getString())
                .contains("process stdin")
                .contains("169.254.169.254")
                .doesNotContain("token=secret");

        ONode dangerous =
                ONode.ofJson(
                        tools.process(
                                "submit",
                                null,
                                sessionId,
                                null,
                                "rm -rf workspace/cache",
                                Integer.valueOf(1),
                                null,
                                null));
        assertToolError(dangerous);
        assertThat(dangerous.get("error").getString())
                .contains("process stdin")
                .contains("危险命令安全规则");

        assertThat(env.processRegistry.stop(sessionId)).isTrue();
    }

    @Test
    void shouldRecognizeWrappedManagedProcessStdinInterpretersForGuardrails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        assertThat(resolveStdinExecutionToolName(tools, "TOKEN=1 python3"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "/usr/bin/env FOO=bar python3 -i"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "env -i -u TOKEN node"))
                .isEqualTo("execute_js");
        assertThat(resolveStdinExecutionToolName(tools, "sudo -S -p '' python3"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "sudo --user root -- bash"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "doas python3"))
                .isEqualTo("execute_python");
        assertThat(resolveStdinExecutionToolName(tools, "pkexec node")).isEqualTo("execute_js");
        assertThat(resolveStdinExecutionToolName(tools, "runas /user:Administrator powershell"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "command -p sh"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "exec /bin/bash"))
                .isEqualTo("execute_shell");
        assertThat(resolveStdinExecutionToolName(tools, "nohup node")).isEqualTo("execute_js");
        assertThat(resolveStdinExecutionToolName(tools, "cat")).isEqualTo("");
    }

    @Test
    void shouldRedactManagedProcessInvalidCwdErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));
        File workspaceHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File missing =
                new File(workspaceHome.getParentFile(), "process-token=ghp_processcwd12345-missing");

        ONode result =
                ONode.ofJson(
                        tools.process(
                                "start",
                                shortEchoCommand(),
                                null,
                                missing.getAbsolutePath(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));

        assertToolError(result);
        assertThat(result.get("error").getString()).contains("cwd is not a directory");
        assertThat(result.get("error").getString()).doesNotContain(workspaceHome.getParent());
        assertThat(result.get("error").getString()).doesNotContain("ghp_processcwd12345");
    }

    @Test
    void shouldRejectManagedProcessCredentialCwd() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProcessTools tools =
                new ProcessTools(
                        env.processRegistry,
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));
        File workspaceHome = new File(env.appConfig.getRuntime().getHome()).getCanonicalFile();
        File credentialDir = new File(workspaceHome, ".ssh");
        assertThat(credentialDir.mkdirs() || credentialDir.isDirectory()).isTrue();

        ONode result =
                ONode.ofJson(
                        tools.process(
                                "start",
                                shortEchoCommand(),
                                null,
                                credentialDir.getAbsolutePath(),
                                null,
                                Integer.valueOf(1),
                                null,
                                null));

        assertToolError(result);
        assertThat(result.get("error").getString())
                .contains("workdir path")
                .contains("敏感系统/凭据文件")
                .doesNotContain(".ssh")
                .doesNotContain(workspaceHome.getAbsolutePath());
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
    void shouldExposeWorkspaceManagementToolForNaturalLanguageWorkspaceInspection()
            throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        String sourceKey = "MEMORY:room-1:user-1";

        assertThat(env.toolRegistry.resolveEnabledToolNames(sourceKey)).contains("workspace_manage");
        assertThat(env.toolRegistry.resolveEnabledTools(sourceKey).toString())
                .contains("WorkspaceManageTools");
    }

    @Test
    void shouldGuardWebToolsBeforeDelegatingToSolonAiTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);

        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(policy);
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy);
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(policy);

        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "http://169.254.169.254/latest/meta-data/?token=secret123",
                                        "text",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("元数据")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "read https://blocked.example/docs?token=secret123",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                codesearch.codesearch(
                                        "read https://blocked.example/docs?token=secret123",
                                        Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://example.com/docs?next=sk-proj-abcdefghijklmnop",
                                        "text",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("疑似 API key")
                .hasMessageNotContaining("sk-proj-abcdefghijklmnop");
    }

    @Test
    void shouldGuardWebfetchFinalDocumentUrlAfterRedirect() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        policy,
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "secret redirected content https://blocked.example/final?token=secret123";
                            }
                        });

        approveNetworkOperationForTest("https://allowed.example/start");
        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://allowed.example/start",
                                        "markdown",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardWebfetchNestedMetadataUrlsAfterProviderResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        policy,
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                Map<String, Object> nested =
                                        new java.util.LinkedHashMap<String, Object>();
                                nested.put(
                                        "redirect_uri",
                                        "https://blocked.example/oauth/callback?client_secret=secret123");
                                return "allowed body " + ONode.serialize(nested);
                            }
                        });

        approveNetworkOperationForTest("https://allowed.example/page");
        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://allowed.example/page",
                                        "markdown",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardWebfetchReturnedDocumentContentUrlsAfterProviderResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        policy,
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "{\"download\":\"https://blocked.example/files/app.jar?token=secret123\"}";
                            }
                        });

        approveNetworkOperationForTest("https://allowed.example/page");
        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://allowed.example/page",
                                        "markdown",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardWebfetchReturnedSchemelessDocumentUrlsAfterProviderResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        policy,
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "{\"mirror\":\"//blocked.example/files/app.jar?token=secret123\",\"source_url\":\"blocked.example/download?client_secret=secret123\"}";
                            }
                        });

        approveNetworkOperationForTest("https://allowed.example/page");
        assertThatThrownBy(
                        () ->
                                webfetch.webfetch(
                                        "https://allowed.example/page",
                                        "markdown",
                                        Integer.valueOf(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldRedactSecretsFromWebfetchSuccessDocument() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeWebfetchTool webfetch =
                new SolonClawWebTools.SafeWebfetchTool(
                        new SecurityPolicyService(env.appConfig),
                        new WebfetchTalent() {
                            @Override
                            public String webfetch(
                                    String url, String format, Integer timeoutSeconds) {
                                return "Fetched api_key=sk-webfetch-secret token=ghp_webfetchcontent12345 doc-ghp_webfetchid12345 title ghp_webfetchtitle12345 https://example.com/docs api_key=sk-webfetch-meta";
                            }
                        });

        approveNetworkOperationForTest("https://example.com/docs");
        Document document =
                webfetch.webfetch("https://example.com/docs", "markdown", Integer.valueOf(1));
        String text = document.toString();

        assertThat(text)
                .contains("api_key=***")
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("sk-webfetch-secret")
                .doesNotContain("ghp_webfetchcontent12345")
                .doesNotContain("ghp_webfetchid12345")
                .doesNotContain("ghp_webfetchtitle12345")
                .doesNotContain("sk-webfetch-meta");
    }

    @Test
    void shouldGuardWebsearchReturnedDocumentUrlsAfterProviderResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        policy,
                        new WebsearchTalent() {
                            @Override
                            public String websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return "secret search content https://blocked.example/result?token=secret123";
                            }
                        });

        approveNetworkOperationForTest("tool://websearch");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "allowed search",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldRedactSecretsFromWebsearchSuccessDocument() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig),
                        new WebsearchTalent() {
                            @Override
                            public String websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return "Search api_key=sk-websearch-secret token=ghp_websearchcontent12345 doc-ghp_websearchid12345 title ghp_websearchtitle12345 https://example.com/search api_key=sk-websearch-meta";
                            }
                        });

        approveNetworkOperationForTest("tool://websearch");
        Document document =
                websearch.websearch(
                        "allowed search",
                        Integer.valueOf(1),
                        "fallback",
                        "auto",
                        Integer.valueOf(1000));
        String text = document.toString();

        assertThat(text)
                .contains("api_key=***")
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("sk-websearch-secret")
                .doesNotContain("ghp_websearchcontent12345")
                .doesNotContain("ghp_websearchid12345")
                .doesNotContain("ghp_websearchtitle12345")
                .doesNotContain("sk-websearch-meta");
    }

    @Test
    void shouldGuardWebsearchReturnedDocumentContentUrlsAfterProviderResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        policy,
                        new WebsearchTalent() {
                            @Override
                            public String websearch(
                                    String query,
                                    Integer numResults,
                                    String livecrawl,
                                    String type,
                                    Integer contextMaxCharacters) {
                                return "{\"assets\":[{\"browser_download_url\":\"https://blocked.example/releases/app.jar?token=secret123\"}]}";
                            }
                        });

        approveNetworkOperationForTest("tool://websearch");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "allowed search",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldRedactSecretsFromCodesearchSuccessContainers() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        new SecurityPolicyService(env.appConfig),
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                Map<String, Object> hit =
                                        new java.util.LinkedHashMap<String, Object>();
                                hit.put("title", "code ghp_codesearchtitle12345");
                                hit.put(
                                        "document",
                                        new Document("code api_key=sk-codesearch-secret")
                                                .id("doc-ghp_codesearchid12345")
                                                .metadata("note", "token=ghp_codesearchnote12345"));
                                Map<String, Object> result =
                                        new java.util.LinkedHashMap<String, Object>();
                                result.put("results", Arrays.asList(hit));
                                return ONode.serialize(result);
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        Object result = codesearch.codesearch("allowed code query", Integer.valueOf(5000));
        String text = String.valueOf(result);

        assertThat(text)
                .contains("api_key=***")
                .contains("token=***")
                .contains("ghp_***")
                .doesNotContain("sk-codesearch-secret")
                .doesNotContain("ghp_codesearchtitle12345")
                .doesNotContain("ghp_codesearchid12345")
                .doesNotContain("ghp_codesearchnote12345");
    }

    @Test
    void shouldUseBraveFreeSearchBackendWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("brave-free");
        env.appConfig.getWeb().setBraveSearchApiKey("brv-test-secret");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig), null, env.appConfig) {
                    @Override
                    protected String executeBraveSearchRequest(
                            String query, int limit, String apiKey) {
                        assertThat(query).isEqualTo("solon ai");
                        assertThat(limit).isEqualTo(2);
                        assertThat(apiKey).isEqualTo("brv-test-secret");
                        return "{\"web\":{\"results\":[{\"title\":\"Solon AI\",\"url\":\"https://example.com/solon\",\"description\":\"Java agent\"},{\"title\":\"Jimuqu\",\"url\":\"https://example.com/jimuqu\",\"description\":\"Agent\"}]}}";
                    }
                };

        approveNetworkOperationForTest("https://api.search.brave.com/res/v1/web/search");
        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        ONode result = ONode.ofJson(document.getContent());

        assertThat(result.get("provider").getString()).isEqualTo("brave-free");
        assertThat(((List<?>) result.get("data").get("web").toData()).size()).isEqualTo(2);
        assertThat(result.get("data").get("web").get(0).get("url").getString())
                .isEqualTo("https://example.com/solon");
    }

    @Test
    void shouldRequireBraveFreeApiKeyWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("brave_free");
        env.appConfig.getWeb().setBraveSearchApiKey("");
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(
                        new SecurityPolicyService(env.appConfig), null, env.appConfig);

        approveNetworkOperationForTest("https://api.search.brave.com/res/v1/web/search");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "solon ai",
                                        Integer.valueOf(2),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BRAVE_SEARCH_API_KEY");
    }

    @Test
    void shouldGuardBraveFreeReturnedUrls() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        env.appConfig.getWeb().setSearchBackend("brave-free");
        env.appConfig.getWeb().setBraveSearchApiKey("brv-test-secret");
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy, null, env.appConfig) {
                    @Override
                    protected String executeBraveSearchRequest(
                            String query, int limit, String apiKey) {
                        return "{\"web\":{\"results\":[{\"title\":\"Blocked\",\"url\":\"https://blocked.example/docs?token=secret123\",\"description\":\"bad\"}]}}";
                    }
                };

        approveNetworkOperationForTest("https://api.search.brave.com/res/v1/web/search");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "blocked",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardBraveFreeSearchEndpointBeforeNetworkAccess() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("brave-free");
        env.appConfig.getWeb().setBraveSearchApiKey("brv-test-secret");
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("api.search.brave.com"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy, null, env.appConfig) {
                    @Override
                    protected String executeBraveSearchRequest(
                            String query, int limit, String apiKey) {
                        throw new AssertionError("Brave backend should not be called");
                    }
                };

        approveNetworkOperationForTest("https://api.search.brave.com/res/v1/web/search");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "blocked",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api.search.brave.com");
    }

    @Test
    void shouldUseDdgsSearchBackendWhenConfigured() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("ddgs");
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy, null, env.appConfig) {
                    @Override
                    protected String executeDdgsSearchRequest(String query, int limit) {
                        assertThat(query).isEqualTo("solon ai");
                        assertThat(limit).isEqualTo(2);
                        return "<html><body>"
                                + "<a rel=\"nofollow\" class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsolon&amp;rut=x\">Solon <b>AI</b></a>"
                                + "<a class=\"result__snippet\">Java&nbsp;agent framework</a>"
                                + "<a class=\"result__a\" href=\"https://example.com/jimuqu\">Jimuqu Agent</a>"
                                + "<div class=\"result__snippet\">Local agent</div>"
                                + "</body></html>";
                    }
                };

        approveNetworkOperationForTest("https://html.duckduckgo.com/html/");
        Document document =
                websearch.websearch(
                        "solon ai", Integer.valueOf(2), "fallback", "auto", Integer.valueOf(1000));
        ONode result = ONode.ofJson(document.getContent());

        assertThat(result.get("provider").getString()).isEqualTo("ddgs");
        assertThat(((List<?>) result.get("data").get("web").toData()).size()).isEqualTo(2);
        assertThat(result.get("data").get("web").get(0).get("title").getString())
                .isEqualTo("Solon AI");
        assertThat(result.get("data").get("web").get(0).get("url").getString())
                .isEqualTo("https://example.com/solon");
        assertThat(result.get("data").get("web").get(0).get("description").getString())
                .isEqualTo("Java agent framework");
    }

    @Test
    void shouldGuardDdgsReturnedUrls() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        env.appConfig.getWeb().setSearchBackend("ddgs");
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy, null, env.appConfig) {
                    @Override
                    protected String executeDdgsSearchRequest(String query, int limit) {
                        return "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fblocked.example%2Fdocs%3Ftoken%3Dsecret123\">Blocked</a>";
                    }
                };

        approveNetworkOperationForTest("https://html.duckduckgo.com/html/");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "blocked",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardDdgsSearchEndpointBeforeNetworkAccess() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getWeb().setSearchBackend("ddgs");
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("html.duckduckgo.com"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeWebsearchTool websearch =
                new SolonClawWebTools.SafeWebsearchTool(policy, null, env.appConfig) {
                    @Override
                    protected String executeDdgsSearchRequest(String query, int limit) {
                        throw new AssertionError("DDGS backend should not be called");
                    }
                };

        approveNetworkOperationForTest("https://html.duckduckgo.com/html/");
        assertThatThrownBy(
                        () ->
                                websearch.websearch(
                                        "blocked",
                                        Integer.valueOf(1),
                                        "fallback",
                                        "auto",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("html.duckduckgo.com");
    }

    @Test
    void shouldGuardCodesearchReturnedDocumentUrlsInsideContainers() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                Map<String, Object> result =
                                        new java.util.LinkedHashMap<String, Object>();
                                result.put(
                                        "documents",
                                        Arrays.asList(
                                                new Document("secret code content")
                                                        .title("code")
                                                        .metadata(
                                                                "source_url",
                                                                "https://blocked.example/code?token=secret123")));
                                return ONode.serialize(result);
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        assertThatThrownBy(() -> codesearch.codesearch("allowed code query", Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardCodesearchReturnedDocumentContentUrlsInsideContainers() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                Map<String, Object> result =
                                        new java.util.LinkedHashMap<String, Object>();
                                result.put(
                                        "documents",
                                        Arrays.asList(
                                                new Document(
                                                                "{\"download\":\"https://blocked.example/code.zip?token=secret123\"}")
                                                        .title("code")));
                                return ONode.serialize(result);
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        assertThatThrownBy(() -> codesearch.codesearch("allowed code query", Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardCodesearchReturnedStringUrlsInsideContainers() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                Map<String, Object> hit =
                                        new java.util.LinkedHashMap<String, Object>();
                                hit.put("finalUrl", "https://blocked.example/code?token=secret123");
                                hit.put("title", "blocked code result");
                                Map<String, Object> result =
                                        new java.util.LinkedHashMap<String, Object>();
                                result.put("results", Arrays.asList(hit));
                                return ONode.serialize(result);
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        assertThatThrownBy(() -> codesearch.codesearch("allowed code query", Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldGuardCodesearchReturnedPojoUrlFields() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                return "blocked code result https://blocked.example/code?token=secret123";
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        assertThatThrownBy(() -> codesearch.codesearch("allowed code query", Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldRedactCodesearchReturnedPojoFields() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                return "token=ghp_pojoresult12345 https://example.com/code";
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        Object result = codesearch.codesearch("allowed code query", Integer.valueOf(5000));

        assertThat(result).isInstanceOf(String.class);
        assertThat(String.valueOf(result))
                .contains("example.com")
                .doesNotContain("ghp_pojoresult12345");
    }

    @Test
    void shouldGuardCodesearchUnstructuredObjectStringUrls() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().getWebsiteBlocklist().setEnabled(true);
        env.appConfig
                .getSecurity()
                .getWebsiteBlocklist()
                .setDomains(Arrays.asList("blocked.example"));
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                return new BlockedStringReturnedObject().toString();
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        assertThatThrownBy(() -> codesearch.codesearch("allowed code query", Integer.valueOf(5000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageContaining("blocked.example")
                .hasMessageNotContaining("secret123");
    }

    @Test
    void shouldRedactCodesearchUnstructuredObjectStringFields() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy =
                new SecurityPolicyService(env.appConfig) {
                    @Override
                    protected InetAddress[] resolveHost(String host) throws Exception {
                        return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                    }
                };
        SolonClawWebTools.SafeCodeSearchTool codesearch =
                new SolonClawWebTools.SafeCodeSearchTool(
                        policy,
                        new CodeSearchTalent() {
                            @Override
                            public String codesearch(String query, Integer tokensNum) throws Throwable {
                                return new SecretStringReturnedObject().toString();
                            }
                        });

        approveNetworkOperationForTest("tool://codesearch");
        Object result = codesearch.codesearch("allowed code query", Integer.valueOf(5000));

        assertThat(result).isInstanceOf(String.class);
        assertThat(String.valueOf(result))
                .contains("example.com")
                .doesNotContain("ghp_unstructured12345");
    }

    @Test
    void shouldGuardCodeExecutionToolsBeforeDelegatingToSolonAiSkills() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        env.appConfig.getSecurity().setGuardrailMode("approval");
        env.appConfig.getSecurity().setFileGuardrailMode("strict");
        env.appConfig.getSecurity().setUrlGuardrailMode("strict");
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);

        SolonClawCodeExecutionSkills.SafePythonSkill python =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        env.appConfig.getRuntime().getHome(), "python", policy);
        SolonClawCodeExecutionSkills.SafeNodejsSkill nodejs =
                new SolonClawCodeExecutionSkills.SafeNodejsSkill(
                        env.appConfig.getRuntime().getHome(), policy);

        assertThatThrownBy(() -> python.execute("open('.env').read()", Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining(".env");
        assertThatThrownBy(
                        () ->
                                nodejs.execute(
                                        "fetch('http://169.254.169.254/latest/meta-data/?token=secret123')",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret123");
        assertThatThrownBy(
                        () ->
                                nodejs.execute(
                                        "require('child_process').execSync('whoami')",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("危险命令安全规则")
                .hasMessageContaining("child");
    }

    @Test
    void shouldRedactDirectCodeExecutionSkillOutputs() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawCodeExecutionSkills.SafePythonSkill python =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        env.appConfig.getRuntime().getHome(), "python", policy);

        String output =
                python.execute(
                        "print('Authorization: Bearer ghp_directpython12345')\n",
                        Integer.valueOf(1000));

        assertThat(output)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_directpython12345");
    }

    @Test
    void shouldExposeJimuquStyleExecuteCodeResultEnvelope() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputInlineLimit(200);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import json_parse, shell_quote\n"
                                        + "print('\\u001b[31mapi_key=sk-test-secret\\u001b[0m')\n"
                                        + "print(json_parse('{\"ok\": true}')['ok'])\n"
                                        + "print(shell_quote('a b'))\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(0);
        assertThat(result.get("duration_seconds").getDouble()).isGreaterThanOrEqualTo(0.0d);
        assertThat(result.get("output").getString())
                .contains("api_key=***")
                .contains("True")
                .contains("'a b'")
                .doesNotContain("sk-test-secret")
                .doesNotContain("\u001b");
    }

    @Test
    void shouldReturnErrorWhenExecuteCodeContainsShellHardlineCommand() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import os\nos.system('rm -rf /')\nprint('after')\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("硬阻断安全规则").contains("root filesystem");
        assertThat(result.get("output").getString()).doesNotContain("after");
    }

    @Test
    void shouldReturnErrorWhenExecuteCodeSubprocessArgvContainsHardlineCommand() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import subprocess\nsubprocess.run(['rm', '-rf', '/'])\nprint('after')\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("硬阻断安全规则").contains("root filesystem");
        assertThat(result.get("output").getString()).doesNotContain("after");
    }

    @Test
    void shouldRejectNodeChildProcessHardlineBeforeRunning() throws Exception {
        assumeTrue(commandExists("node"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeNodejsSkill nodejs =
                new SolonClawCodeExecutionSkills.SafeNodejsSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        assertThatThrownBy(
                        () ->
                                nodejs.execute(
                                        "require('child_process').execSync('rm -rf /')\nconsole.log('after')",
                                        Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("硬阻断安全规则")
                .hasMessageContaining("root filesystem")
                .hasMessageNotContaining("after");
    }

    @Test
    void shouldReturnErrorWhenExecuteCodeReadsCredentialFilesBeforeRunning() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve(".env"), Arrays.asList("TOKEN=secret"), StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("credentials.json"),
                Arrays.asList("{\"token\":\"secret\"}"),
                StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode envResult =
                ONode.ofJson(
                        executeCode.executeCode(
                                "print(open('.env').read())\nprint('after')\n",
                                Integer.valueOf(5)));
        ONode credentialsResult =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from pathlib import Path\nprint(Path('credentials.json').read_text())\nprint('after')\n",
                                Integer.valueOf(5)));

        assertThat(envResult.get("status").getString()).isEqualTo("error");
        assertThat(envResult.get("error").getString())
                .contains("文件安全策略")
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env")
                .doesNotContain("TOKEN=secret");
        assertThat(envResult.get("output").getString()).doesNotContain("after");
        assertThat(credentialsResult.get("status").getString()).isEqualTo("error");
        assertThat(credentialsResult.get("error").getString())
                .contains("文件安全策略")
                .contains("[REDACTED_PATH]")
                .doesNotContain("credentials.json")
                .doesNotContain("secret");
        assertThat(credentialsResult.get("output").getString()).doesNotContain("after");
    }

    @Test
    void shouldReturnErrorWhenExecuteCodeTouchesHomeSshKeyBeforeRunning() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "print(open('~/.ssh/id_rsa').read())\nprint('after')\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString())
                .contains("文件安全策略")
                .contains("[REDACTED_PATH]")
                .doesNotContain("id_rsa");
        assertThat(result.get("output").getString()).doesNotContain("after");
    }

    @Test
    void shouldReturnJimuquStyleExecuteCodeErrorsWithStderr() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import sys\nprint('before')\nsys.stderr.write('token=secret123\\n')\nraise RuntimeError('boom')\n",
                                Integer.valueOf(5)));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("token=***").contains("RuntimeError");
        assertThat(result.get("output").getString())
                .contains("before")
                .contains("--- stderr ---")
                .contains("token=***")
                .doesNotContain("secret123");
    }

    @Test
    void shouldRedactExecuteCodeTimeoutOutput() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "import time\n"
                                        + "print('token=ghp_timeoutsecret12345', flush=True)\n"
                                        + "time.sleep(3)\n",
                                Integer.valueOf(1)));

        assertThat(result.get("process_status").getString()).isEqualTo("timeout");
        assertThat(result.get("error").getString()).contains("timed out");
        assertThat(result.get("output").getString())
                .contains("token=***")
                .contains("timed out")
                .doesNotContain("ghp_timeoutsecret12345");
    }

    @Test
    void shouldAllowExecuteCodeToCallJimuquFileAndTerminalToolsThroughRpc() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("rpc-source.txt"),
                Arrays.asList("alpha", "needle"),
                StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        String terminalCommand =
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "echo rpc-terminal"
                        : "printf 'rpc-terminal\\n'";
        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file, write_file, patch, search_files, terminal\n"
                                        + "print(read_file('rpc-source.txt')['content'].splitlines()[0])\n"
                                        + "print(search_files('needle', path='.', limit=5)['matches'][0]['path'])\n"
                                        + "print(write_file('rpc-output.txt', 'before\\n')['output'])\n"
                                        + "print(patch(path='rpc-output.txt', old_string='before', new_string='after')['status'])\n"
                                        + "print(read_file('rpc-output.txt')['content'])\n"
                                        + "print(terminal(\""
                                        + terminalCommand
                                                .replace("\\", "\\\\")
                                                .replace("\"", "\\\"")
                                        + "\")['output'].strip())\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(6);
        assertThat(result.get("output").getString())
                .contains("alpha")
                .contains("rpc-source.txt")
                .contains("success")
                .contains("after")
                .contains("rpc-terminal");
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("rpc-output.txt")),
                                StandardCharsets.UTF_8))
                .contains("after");
    }

    @Test
    void shouldResetExecuteCodeRpcReadDedupAfterOtherToolCall() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("rpc-repeat.txt"),
                Arrays.asList("alpha", "needle"),
                StandardCharsets.UTF_8);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file, search_files\n"
                                        + "print(read_file('rpc-repeat.txt').get('success'))\n"
                                        + "print(read_file('rpc-repeat.txt').get('dedup'))\n"
                                        + "print(search_files('needle', path='.', limit=5)['matches'][0]['path'])\n"
                                        + "third = read_file('rpc-repeat.txt')\n"
                                        + "print(third.get('dedup'))\n"
                                        + "print(third.get('error'))\n"
                                        + "print(read_file('rpc-repeat.txt').get('error'))\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(5);
        assertThat(result.get("output").getString())
                .contains("True")
                .contains("rpc-repeat.txt")
                .contains("None")
                .contains("BLOCKED");
    }

    @Test
    void shouldReturnExecuteCodeRpcToolErrorsWithoutBypassingSafety() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(),
                        "python",
                        new SecurityPolicyService(env.appConfig),
                        env.appConfig);

        ONode result =
                ONode.ofJson(
                        executeCode.executeCode(
                                "from solonclaw_tools import read_file\n"
                                        + "print(read_file('.env')['error'])\n",
                                Integer.valueOf(10)));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("tool_calls_made").getInt()).isEqualTo(1);
        assertThat(result.get("output").getString())
                .contains("文件安全策略")
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env");
    }

    @Test
    void shouldLetApprovedExecuteCodeScriptPassToolFallbackOnce() throws Exception {
        assumeTrue(commandExists("python"));
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCode =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        env.appConfig.getRuntime().getHome(), "python", policy, env.appConfig);
        String code =
                "import shutil\n"
                        + "import os\n"
                        + "target = 'approved-delete-target'\n"
                        + "os.makedirs(target, exist_ok=True)\n"
                        + "shutil.rmtree(target)\n"
                        + "print('deleted')\n";

        ONode blockedBefore = ONode.ofJson(executeCode.executeCode(code, Integer.valueOf(5)));
        assertThat(blockedBefore.get("status").getString()).isEqualTo("error");
        assertThat(blockedBefore.get("error").getString())
                .contains("危险命令安全规则")
                .contains("Python recursive delete");

        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository, env.appConfig, policy);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        args.put("code", code);
        service.buildInterceptor().onAction(trace, exchange("execute_code", args));
        assertThat(
                        service.approve(
                                trace.getSession(),
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();
        TestTrace resumed = new TestTrace(trace.getSession());
        service.buildInterceptor().onAction(resumed, exchange("execute_code", args));

        ONode allowed = ONode.ofJson(executeCode.executeCode(code, Integer.valueOf(5)));
        assertThat(allowed.get("status").getString()).isEqualTo("success");
        assertThat(allowed.get("output").getString()).contains("deleted");

        ONode blockedAfter = ONode.ofJson(executeCode.executeCode(code, Integer.valueOf(5)));
        assertThat(blockedAfter.get("status").getString()).isEqualTo("error");
        assertThat(blockedAfter.get("error").getString())
                .contains("危险命令安全规则")
                .contains("Python recursive delete");
    }

    @Test
    void shouldLetApprovedExecuteShellCommandPassToolFallbackOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("approval");
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(), env.appConfig, policy);
        String command = "git reset --hard";

        assertThatThrownBy(() -> shell.execute(command, Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("危险命令安全规则")
                .hasMessageContaining("git reset --hard");

        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository, env.appConfig, policy);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        args.put("code", command);
        service.buildInterceptor().onAction(trace, exchange("execute_shell", args));
        assertThat(
                        service.approve(
                                trace.getSession(),
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();
        TestTrace resumed = new TestTrace(trace.getSession());
        service.buildInterceptor().onAction(resumed, exchange("execute_shell", args));

        assertThat(shell.execute(command, Integer.valueOf(1000))).doesNotContain("危险命令安全规则");

        assertThatThrownBy(() -> shell.execute(command, Integer.valueOf(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("危险命令安全规则")
                .hasMessageContaining("git reset --hard");
    }

    @Test
    void shouldLetApprovedTerminalCommandPassToolFallbackOnce() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getSecurity().setGuardrailMode("approval");
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawShellSkill shell =
                new SolonClawShellSkill(
                        env.appConfig.getRuntime().getHome(), env.appConfig, policy);
        String command = "git reset --hard";

        ONode blockedBefore =
                ONode.ofJson(
                        shell.terminal(
                                command, Boolean.FALSE, Integer.valueOf(1), null, Boolean.FALSE));
        assertThat(blockedBefore.get("status").getString()).isEqualTo("error");
        assertThat(blockedBefore.get("error").getString())
                .contains("危险命令安全规则")
                .contains("git reset --hard");

        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        env.globalSettingRepository, env.appConfig, policy);
        TestTrace trace = new TestTrace();
        Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        args.put("command", command);
        service.buildInterceptor().onAction(trace, exchange("terminal", args));
        assertThat(
                        service.approve(
                                trace.getSession(),
                                DangerousCommandApprovalService.ApprovalScope.ONCE,
                                "test"))
                .isTrue();
        TestTrace resumed = new TestTrace(trace.getSession());
        service.buildInterceptor().onAction(resumed, exchange("terminal", args));

        ONode allowed =
                ONode.ofJson(
                        shell.terminal(
                                command, Boolean.FALSE, Integer.valueOf(1), null, Boolean.FALSE));
        assertThat(allowed.toJson()).doesNotContain("危险命令安全规则");

        ONode blockedAfter =
                ONode.ofJson(
                        shell.terminal(
                                command, Boolean.FALSE, Integer.valueOf(1), null, Boolean.FALSE));
        assertThat(blockedAfter.get("status").getString()).isEqualTo("error");
        assertThat(blockedAfter.get("error").getString())
                .contains("危险命令安全规则")
                .contains("git reset --hard");
    }

    @Test
    void shouldGuardFileToolsBeforeDelegatingToSolonAiSkill() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(env.appConfig.getRuntime().getHome(), policy);

        assertThatThrownBy(() -> fileSkill.read(".env"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining(".env");
        assertThatThrownBy(() -> fileSkill.read("credentials.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining("credentials.json");
        assertThatThrownBy(() -> fileSkill.write("credentials", "token=secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining("credentials");
        assertThatThrownBy(() -> fileSkill.write("../outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径遍历");
        assertThatThrownBy(() -> fileSkill.delete("~/.ssh/id_rsa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining(".ssh")
                .hasMessageNotContaining("id_rsa");
        assertThatThrownBy(() -> fileSkill.list("~/.ssh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining(".ssh");
        assertThatThrownBy(() -> fileSkill.list("skills/.hub/index-cache"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("Skills Hub")
                .hasMessageNotContaining("index-cache");
    }

    @Test
    void shouldRedactSecretsFromFileReadContent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("project-config.txt"),
                Arrays.asList(
                        "public=true",
                        "api_key=sk-test-secret",
                        "callback=https://user:pass@example.com/?token=secret123"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("project-config.txt"));
        String content = result.get("content").getString();

        assertToolSuccess(result);
        assertThat(content)
                .contains("public=true")
                .contains("api_key=***")
                .doesNotContain("sk-test-secret")
                .doesNotContain("secret123")
                .doesNotContain("user:pass");
    }

    @Test
    void shouldRedactSecretsFromFileReadErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        String result = fileSkill.read("logs/token=ghp_filereaderror12345.txt");

        assertThat(result).contains("token=***").doesNotContain("ghp_filereaderror12345");
    }

    @Test
    void shouldRedactSecretsFromFileReadSuccessPath() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.createDirectories(workspace.resolve("logs"));
        Files.write(
                workspace.resolve("logs/token-ghp_filereadsuccess12345.txt"),
                Arrays.asList("public=true"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result =
                ONode.ofJson(fileSkill.read("logs/token-ghp_filereadsuccess12345.txt", 1, 5));

        assertToolSuccess(result);
        assertThat(result.get("summary").getString())
                .contains("token-ghp_***")
                .doesNotContain("ghp_filereadsuccess12345");
        assertThat(result.get("path").getString())
                .contains("token-ghp_***")
                .doesNotContain("ghp_filereadsuccess12345");
        assertThat(result.toJson()).doesNotContain("ghp_filereadsuccess12345");
    }

    @Test
    void shouldRedactSecretsFromFileWriteListAndDeleteResultsOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        String write =
                fileSkill.write(
                        "logs/token-ghp_filewrite12345.txt",
                        "Authorization: Bearer ghp_filecontent12345");
        String list = fileSkill.list("logs");
        assertThat(
                        new String(
                                Files.readAllBytes(
                                        new java.io.File(env.appConfig.getRuntime().getHome())
                                                .toPath()
                                                .resolve("logs/token-ghp_filewrite12345.txt")),
                                StandardCharsets.UTF_8))
                .contains("ghp_filecontent12345");
        String delete = fileSkill.delete("logs/token-ghp_filewrite12345.txt");

        assertThat(write)
                .contains("token-ghp_***")
                .doesNotContain("ghp_filewrite12345")
                .doesNotContain("ghp_filecontent12345");
        assertThat(list).contains("token-ghp_***").doesNotContain("ghp_filewrite12345");
        assertThat(delete).contains("token-ghp_***").doesNotContain("ghp_filewrite12345");
    }

    @Test
    void shouldReportResolvedAbsolutePathForFileWrite() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.write("notes/out.txt", "hello\n"));
        String expected = workspace.resolve("notes/out.txt").toRealPath().toString();

        assertToolSuccess(result);
        assertThat(result.get("resolved_path").getString()).isEqualTo(expected);
        Object filesModified = result.get("files_modified").toData();
        assertThat(String.valueOf(filesModified)).isEqualTo("[" + expected + "]");
        assertThat(result.get("path").getString()).isEqualTo("notes/out.txt");
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("notes/out.txt")),
                                StandardCharsets.UTF_8))
                .isEqualTo("hello\n");
    }

    @Test
    void shouldReportResolvedAbsolutePathForFileRead() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("notes/input.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, Arrays.asList("alpha", "beta"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("notes/input.txt", 1, 2));
        String expected = file.toRealPath().toString();

        assertToolSuccess(result);
        assertThat(result.get("resolved_path").getString()).isEqualTo(expected);
        assertThat(result.get("path").getString()).isEqualTo("notes/input.txt");
        assertThat(result.get("content").getString()).contains("1|alpha").contains("2|beta");
    }

    @Test
    void shouldReadAndWriteRuntimeReferencesWithinToolRoot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path input = workspace.resolve("tool-results/run-1/call-1.txt");
        Files.createDirectories(input.getParent());
        Files.write(input, Arrays.asList("runtime ref"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read =
                ONode.ofJson(fileSkill.read("workspace://tool-results/run-1/call-1.txt", 1, 2));
        ONode write = ONode.ofJson(fileSkill.write("workspace://notes/out.txt", "saved\n"));

        assertToolSuccess(read);
        assertThat(read.get("content").getString()).contains("1|runtime ref");
        assertThat(read.get("resolved_path").getString()).isEqualTo(input.toRealPath().toString());
        assertToolSuccess(write);
        assertThat(write.get("resolved_path").getString())
                .isEqualTo(workspace.resolve("notes/out.txt").toRealPath().toString());
        assertThat(new String(Files.readAllBytes(workspace.resolve("notes/out.txt")), StandardCharsets.UTF_8))
                .isEqualTo("saved\n");
    }

    @Test
    void shouldSuggestSimilarFilesWhenFileReadPathIsMissing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.createDirectories(workspace.resolve("config"));
        Files.write(
                workspace.resolve("config/app.yaml"),
                Arrays.asList("app: true"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("config/app.yml"),
                Arrays.asList("app: short"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("config/application.yaml"),
                Arrays.asList("app: long"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("config/readme.txt"),
                Arrays.asList("ignore"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("config/app.json", 1, 5));
        Object suggestionsData = result.get("similar_files").toData();
        String suggestions = String.valueOf(suggestionsData);

        assertToolError(result);
        assertThat(result.get("error").getString()).contains("config/app.json");
        assertThat(suggestionsData).isNotNull();
        assertThat(result.get("path").getString()).isEqualTo("config/app.json");
        assertThat(result.get("resolved_path").getString())
                .isEqualTo(
                        workspace
                                .resolve("config/app.json")
                                .toAbsolutePath()
                                .normalize()
                                .toString());
        assertThat(suggestions)
                .contains("config/app.yaml")
                .contains("config/app.yml")
                .contains("config/application.yaml")
                .doesNotContain("config/readme.txt");
    }

    @Test
    void shouldNotSuggestSensitiveFilesWhenFileReadPathIsMissing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("config.yml"),
                Arrays.asList("public: true"),
                StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("credentials.json"),
                Arrays.asList("TOKEN=secret"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode result = ONode.ofJson(fileSkill.read("config.json", 1, 5));
        Object suggestionsData = result.get("similar_files").toData();
        String suggestions = String.valueOf(suggestionsData);

        assertToolError(result);
        assertThat(suggestions)
                .contains("config.yml")
                .doesNotContain("credentials.json")
                .doesNotContain("TOKEN=secret");
        assertThat(result.toJson())
                .doesNotContain("credentials.json")
                .doesNotContain("TOKEN=secret");
    }

    @Test
    void shouldHideUtf8BomOnFileReadAndPreserveItAcrossEdits() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path readTarget = workspace.resolve("bom-read.txt");
        Path writeTarget = workspace.resolve("bom-write.txt");
        Path patchTarget = workspace.resolve("bom-patch.txt");
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        Files.write(readTarget, concat(bom, "alpha\nbravo\n".getBytes(StandardCharsets.UTF_8)));
        Files.write(writeTarget, concat(bom, "old\n".getBytes(StandardCharsets.UTF_8)));
        Files.write(patchTarget, concat(bom, "first\nsecond\n".getBytes(StandardCharsets.UTF_8)));
        SecurityPolicyService policy = new SecurityPolicyService(env.appConfig);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(env.appConfig.getRuntime().getHome(), policy);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(env.appConfig.getRuntime().getHome(), policy);

        ONode read = ONode.ofJson(fileSkill.read("bom-read.txt", 1, 2));
        ONode write = ONode.ofJson(fileSkill.write("bom-write.txt", "new\n"));
        ONode patch =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "bom-patch.txt",
                                "first",
                                "first updated",
                                Boolean.FALSE,
                                null));

        assertToolSuccess(read);
        assertThat(read.get("content").getString())
                .contains("1|alpha")
                .doesNotContain("     1|")
                .doesNotContain("\ufeff");
        assertToolSuccess(write);
        assertThat(Files.readAllBytes(writeTarget))
                .startsWith(bom)
                .isEqualTo(concat(bom, "new\n".getBytes(StandardCharsets.UTF_8)));
        assertToolSuccess(patch);
        assertThat(Files.readAllBytes(patchTarget))
                .startsWith(bom)
                .isEqualTo(concat(bom, "first updated\nsecond\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectJarInternalFileToolPathsBeforeDelegating() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));
        String jarPath = "app.jar!/org/noear/solon/core/USER.md";

        ONode readResult = ONode.ofJson(fileSkill.read(jarPath));

        assertToolError(readResult);
        assertThat(readResult.get("error").getString())
                .contains("jar-internal paths are not disk files");
        assertThatThrownBy(() -> fileSkill.write(jarPath, "content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jar-internal paths are not disk files");
        assertThatThrownBy(() -> fileSkill.delete(jarPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jar-internal paths are not disk files");
        assertThatThrownBy(() -> fileSkill.list(jarPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jar-internal paths are not disk files");
    }

    @Test
    void shouldGuardFileToolsAgainstSymlinkEscapesBeforeDelegating() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path outside = Files.createTempDirectory("jimuqu-file-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, Arrays.asList("TOKEN=old"), StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode readResult = ONode.ofJson(fileSkill.read("linked/secret.txt"));
        assertToolError(readResult);
        assertThat(readResult.get("error").getString()).contains("符号链接").contains("沙箱外部");
        assertThatThrownBy(() -> fileSkill.write("linked/secret.txt", "TOKEN=new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVAL_REQUIRED")
                .hasMessageContaining("工作区外")
                .hasMessageContaining("linked/secret.txt");
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .contains("TOKEN=old");
    }

    @Test
    void shouldGuardPatchToolsAgainstSymlinkEscapesBeforeWriting() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path outside = Files.createTempDirectory("jimuqu-patch-outside");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, Arrays.asList("TOKEN=old"), StandardCharsets.UTF_8);
        Files.write(
                workspace.resolve("inside.txt"), Arrays.asList("inside"), StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked");
        assumeTrue(createDirectoryLink(link, outside));
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode replaceResult =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "linked/secret.txt",
                                "TOKEN=old",
                                "TOKEN=new",
                                Boolean.FALSE,
                                null));
        ONode updateResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Update File: linked/secret.txt\n"
                                        + "@@ TOKEN @@\n"
                                        + "-TOKEN=old\n"
                                        + "+TOKEN=new\n"
                                        + "*** End Patch"));
        ONode addResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Add File: linked/new.txt\n"
                                        + "+TOKEN=new\n"
                                        + "*** End Patch"));
        ONode moveResult =
                ONode.ofJson(
                        patchTools.patch(
                                "patch",
                                null,
                                null,
                                null,
                                null,
                                "*** Begin Patch\n"
                                        + "*** Move File: inside.txt -> linked/moved.txt\n"
                                        + "*** End Patch"));

        assertPatchSymlinkEscapeBlocked(replaceResult);
        assertPatchSymlinkEscapeBlocked(updateResult);
        assertPatchSymlinkEscapeBlocked(addResult);
        assertPatchSymlinkEscapeBlocked(moveResult);
        assertThat(new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8))
                .contains("TOKEN=old");
        assertThat(Files.exists(outside.resolve("new.txt"))).isFalse();
        assertThat(Files.exists(outside.resolve("moved.txt"))).isFalse();
        assertThat(
                        new String(
                                Files.readAllBytes(workspace.resolve("inside.txt")),
                                StandardCharsets.UTF_8))
                .contains("inside");
    }

    @Test
    void shouldGuardFileAndPatchToolsAgainstConfiguredCredentialFiles() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTerminal().setCredentialFiles(Arrays.asList("credentials/oauth.json"));
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path credentialDir = workspace.resolve("credentials");
        Files.createDirectories(credentialDir);
        Path credentialFile = credentialDir.resolve("oauth.json");
        Files.write(credentialFile, Arrays.asList("{\"token\":\"old\"}"), StandardCharsets.UTF_8);
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(env.appConfig);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(), securityPolicyService);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(), securityPolicyService);

        ONode patchResult =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "credentials/oauth.json",
                                "old",
                                "new",
                                Boolean.FALSE,
                                null));

        assertThatThrownBy(() -> fileSkill.read("credentials/oauth.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining("credentials/oauth.json");
        assertThatThrownBy(() -> fileSkill.write("credentials/oauth.json", "{\"token\":\"new\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining("credentials/oauth.json");
        assertToolError(patchResult);
        assertThat(patchResult.get("error").getString())
                .contains("凭据")
                .contains("[REDACTED_PATH]")
                .doesNotContain("credentials/oauth.json");
        assertThat(new String(Files.readAllBytes(credentialFile), StandardCharsets.UTF_8))
                .contains("old")
                .doesNotContain("new");
    }

    @Test
    void shouldApplyJimuquToolOutputLimitsToFileReads() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("long-lines.txt"),
                Arrays.asList("alpha", "0123456789ABCDEFGHIJ", "charlie", "delta"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        2,
                        10);

        ONode firstPage = ONode.ofJson(fileSkill.read("long-lines.txt", 1, 99));

        assertToolSuccess(firstPage);
        assertThat(firstPage.get("limit").getInt()).isEqualTo(2);
        assertThat(firstPage.get("total_lines").getInt()).isEqualTo(4);
        assertThat(firstPage.get("truncated").getBoolean()).isTrue();
        assertThat(firstPage.get("hint").getString()).contains("offset=3");
        assertThat(firstPage.get("content").getString())
                .contains("1|alpha")
                .contains("2|0123456789... [truncated]")
                .doesNotContain("     1|")
                .doesNotContain("charlie");

        ONode secondPage = ONode.ofJson(fileSkill.read("long-lines.txt", 3, 2));

        assertThat(secondPage.get("truncated").getBoolean()).isFalse();
        assertThat(secondPage.get("content").getString())
                .contains("3|charlie")
                .contains("4|delta")
                .doesNotContain("     3|");
    }

    @Test
    void shouldApplyUpdatedToolOutputLimitsToExistingFileReadTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputMaxLines(2);
        env.appConfig.getTask().setToolOutputMaxLineLength(10);
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("runtime-limits.txt"),
                Arrays.asList("alpha", "0123456789ABCDEFGHIJ", "charlie", "delta"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1")) {
            if (tool instanceof SolonClawFileReadWriteSkill) {
                fileSkill = (SolonClawFileReadWriteSkill) tool;
                break;
            }
        }
        assertThat(fileSkill).isNotNull();

        ONode firstPage = ONode.ofJson(fileSkill.read("runtime-limits.txt", 1, 99));
        env.appConfig.getTask().setToolOutputMaxLines(3);
        env.appConfig.getTask().setToolOutputMaxLineLength(20);
        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("test_config_refresh");
        ONode updatedPage = ONode.ofJson(fileSkill.read("runtime-limits.txt", 1, 99));

        assertThat(firstPage.get("limit").getInt()).isEqualTo(2);
        assertThat(firstPage.get("content").getString())
                .contains("2|0123456789... [truncated]")
                .doesNotContain("3|charlie");
        assertThat(updatedPage.get("limit").getInt()).isEqualTo(3);
        assertThat(updatedPage.get("content").getString())
                .contains("2|0123456789ABCDEFGHIJ")
                .contains("3|charlie")
                .doesNotContain("... [truncated]");
    }

    @Test
    void shouldApplyUpdatedLineLengthToExistingFileReadToolForSamePage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getTask().setToolOutputMaxLines(3);
        env.appConfig.getTask().setToolOutputMaxLineLength(10);
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("runtime-line-length.txt"),
                Arrays.asList("0123456789ABCDEFGHIJ", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill = null;
        for (Object tool : env.toolRegistry.resolveEnabledTools("MEMORY:room-1:user-1")) {
            if (tool instanceof SolonClawFileReadWriteSkill) {
                fileSkill = (SolonClawFileReadWriteSkill) tool;
                break;
            }
        }
        assertThat(fileSkill).isNotNull();

        ONode firstPage = ONode.ofJson(fileSkill.read("runtime-line-length.txt", 1, 2));
        env.appConfig.getTask().setToolOutputMaxLineLength(20);
        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("test_config_refresh");
        ONode updatedPage = ONode.ofJson(fileSkill.read("runtime-line-length.txt", 1, 2));

        assertThat(firstPage.get("content").getString())
                .contains("1|0123456789... [truncated]")
                .contains("2|bravo");
        assertToolSuccess(updatedPage);
        assertThat(updatedPage.get("dedup").getBoolean()).isFalse();
        assertThat(updatedPage.get("content").getString())
                .contains("1|0123456789ABCDEFGHIJ")
                .contains("2|bravo")
                .doesNotContain("... [truncated]");
    }

    @Test
    void shouldDeduplicateUnchangedRepeatedFileReadsWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("repeat.txt"),
                Arrays.asList("alpha", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode first = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        ONode second = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        ONode third = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));
        String expected = workspace.resolve("repeat.txt").toRealPath().toString();

        assertToolSuccess(first);
        assertThat(first.get("content").getString()).contains("alpha").contains("bravo");
        assertThat(first.get("resolved_path").getString()).isEqualTo(expected);
        assertToolSuccess(second);
        assertThat(second.get("dedup").getBoolean()).isTrue();
        assertThat(second.get("resolved_path").getString()).isEqualTo(expected);
        assertThat(second.get("content_returned").getBoolean()).isFalse();
        assertThat(second.get("content").getString()).isNull();
        assertToolError(third);
        assertThat(third.get("error").getString()).contains("BLOCKED").contains("重复");
        assertThat(third.get("resolved_path").getString()).isEqualTo(expected);

        fileSkill.write("repeat.txt", "delta\n");
        ONode changed = ONode.ofJson(fileSkill.read("repeat.txt", 1, 2));

        assertToolSuccess(changed);
        assertThat(changed.get("content").getString()).contains("delta");
    }

    @Test
    void shouldRedactRepeatedFileReadStatusPaths() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path dir = workspace.resolve("token-ghp_filereadstatus12345");
        Files.createDirectories(dir);
        Files.write(
                dir.resolve("repeat.txt"), Arrays.asList("alpha", "bravo"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));
        String path = "token-ghp_filereadstatus12345/repeat.txt";

        fileSkill.read(path, 1, 2);
        ONode second = ONode.ofJson(fileSkill.read(path, 1, 2));
        ONode third = ONode.ofJson(fileSkill.read(path, 1, 2));

        assertThat(second.get("path").getString())
                .contains("repeat.txt")
                .doesNotContain("ghp_filereadstatus12345");
        assertThat(third.get("error").getString())
                .contains("BLOCKED")
                .contains("repeat.txt")
                .doesNotContain("ghp_filereadstatus12345");
        assertThat(third.get("path").getString())
                .contains("repeat.txt")
                .doesNotContain("ghp_filereadstatus12345");
    }

    @Test
    void shouldResetFileReadDedupHitsAfterOtherToolCall() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("repeat-after-tool.txt"),
                Arrays.asList("alpha", "bravo", "charlie"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode first = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));
        ONode second = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));
        ToolCallLoopGuardrailService.notifyFileReadDedupIfOtherTool("search_files");
        ONode third = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));
        ONode fourth = ONode.ofJson(fileSkill.read("repeat-after-tool.txt", 1, 2));

        assertToolSuccess(first);
        assertToolSuccess(second);
        assertThat(second.get("dedup").getBoolean()).isTrue();
        assertToolSuccess(third);
        assertThat(third.get("dedup").getBoolean()).isTrue();
        assertThat(third.get("error").getString()).isNull();
        assertToolError(fourth);
        assertThat(fourth.get("error").getString()).contains("BLOCKED").contains("重复");
    }

    @Test
    void shouldWarnButNotBlockWhenWritingStaleReadFileWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-write.txt");
        Files.write(file, Arrays.asList("alpha"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read = ONode.ofJson(fileSkill.read("stale-write.txt", 1, 2));
        Files.write(file, Arrays.asList("external"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode staleWrite = ONode.ofJson(fileSkill.write("stale-write.txt", "agent\n"));
        String plainWrite = fileSkill.write("stale-write.txt", "agent2\n");

        assertToolSuccess(read);
        assertToolSuccess(staleWrite);
        assertThat(staleWrite.get("_warning").getString())
                .contains("was modified since you last read")
                .contains("stale-write.txt");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("agent2");
        assertThat(plainWrite).contains("文件保存成功").doesNotContain("_warning");
    }

    @Test
    void shouldRedactSecretsFromFileWriteStaleWarningResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-token-ghp_stalewrite12345.txt");
        Files.write(file, Arrays.asList("alpha"), StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        ONode read = ONode.ofJson(fileSkill.read("stale-token-ghp_stalewrite12345.txt", 1, 2));
        Files.write(file, Arrays.asList("external"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode staleWrite =
                ONode.ofJson(fileSkill.write("stale-token-ghp_stalewrite12345.txt", "agent\n"));

        assertToolSuccess(read);
        assertToolSuccess(staleWrite);
        assertThat(staleWrite.toJson())
                .contains("stale-token-ghp_***")
                .doesNotContain("ghp_stalewrite12345");
    }

    @Test
    void shouldWarnWhenPatchingStaleReadFileWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Path file = workspace.resolve("stale-patch.txt");
        Files.write(file, Arrays.asList("alpha", "bravo"), StandardCharsets.UTF_8);
        SolonClawFileStateTracker tracker = new SolonClawFileStateTracker();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        2000,
                        2000,
                        tracker);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig),
                        tracker);

        ONode read = ONode.ofJson(fileSkill.read("stale-patch.txt", 1, 2));
        Files.write(file, Arrays.asList("external", "bravo"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        ONode patched =
                ONode.ofJson(
                        patchTools.patch(
                                "replace",
                                "stale-patch.txt",
                                "external",
                                "agent",
                                Boolean.FALSE,
                                null));

        assertToolSuccess(read);
        assertToolSuccess(patched);
        assertThat(patched.get("_warning").getString())
                .contains("was modified since you last read")
                .contains("stale-patch.txt");
        assertThat(patched.get("warnings").size()).isEqualTo(1);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("agent");
    }

    @Test
    void shouldRefuseWritingInternalReadDedupStatusTextWithCanonicalConfig() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Path workspace = new java.io.File(env.appConfig.getRuntime().getHome()).toPath();
        Files.write(
                workspace.resolve("dedup-source.txt"),
                Arrays.asList("alpha", "bravo"),
                StandardCharsets.UTF_8);
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        env.appConfig.getRuntime().getHome(),
                        new SecurityPolicyService(env.appConfig));

        fileSkill.read("dedup-source.txt", 1, 2);
        ONode dedup = ONode.ofJson(fileSkill.read("dedup-source.txt", 1, 2));
        String status = dedup.get("summary").getString();

        assertThatThrownBy(() -> fileSkill.write("bad.txt", status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal read_file status");
        assertThatThrownBy(() -> fileSkill.write("bad.txt", "Note:\n" + status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal read_file status");

        StringBuilder longDoc = new StringBuilder();
        longDoc.append("This document quotes a tool status for tests:\n").append(status);
        while (longDoc.length() <= status.length() * 2) {
            longDoc.append("\nmore ordinary documentation");
        }
        fileSkill.write("quoted-status.txt", longDoc.toString());
        assertThat(Files.readAllBytes(workspace.resolve("quoted-status.txt")).length)
                .isGreaterThan(0);
    }

    private void assertPatchSymlinkEscapeBlocked(ONode result) {
        assertToolError(result);
        assertThat(result.get("error").getString()).contains("APPROVAL_REQUIRED").contains("工作区外");
    }

    private String javaSleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "ping -n 30 127.0.0.1 > nul";
        }
        return "sleep 30";
    }

    private byte[] concat(byte[] prefix, byte[] suffix) {
        byte[] result = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win")) {
                return false;
            }
            try {
                Process process =
                        new ProcessBuilder(
                                        "cmd",
                                        "/c",
                                        "mklink",
                                        "/J",
                                        link.toString(),
                                        target.toString())
                                .redirectErrorStream(true)
                                .start();
                return process.waitFor() == 0 && Files.exists(link);
            } catch (Exception ignoredAgain) {
                return false;
            }
        }
    }

    private String stdinEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "findstr /n .*";
        }
        return "cat";
    }

    private String interactiveShellCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "cmd";
        }
        return "sh";
    }

    private String multiLineEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo line-1 & echo line-2 & echo line-3 & echo line-4";
        }
        return "printf 'line-1\\nline-2\\nline-3\\nline-4\\n'";
    }

    private String shortEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo ok";
        }
        return "printf 'ok\\n'";
    }

    private String secretEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo api_key=sk-test-secret token=secret123 https://example.com/public";
        }
        return "printf '%s\\n' 'api_key=sk-test-secret token=secret123 https://example.com/public'";
    }

    private String secretEventEchoCommand() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return "echo api_key=sk-test-secret token=secret123 credentials.json";
        }
        return "printf '%s\\n' 'api_key=sk-test-secret token=secret123 credentials.json'";
    }

    private boolean commandExists(String command) {
        try {
            Process process =
                    new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
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

    private String resolveStdinExecutionToolName(ProcessTools tools, String command)
            throws Exception {
        Method method =
                ProcessTools.class.getDeclaredMethod("stdinExecutionToolName", String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(tools, command));
    }

    private ToolExchanger exchange(String toolName, Map<String, Object> args) {
        return new ToolExchanger(toolName, args);
    }

    /** 提供审批拦截器测试需要的最小 ReActTrace 实现。 */
    static class TestTrace extends org.noear.solon.ai.agent.react.ReActTrace {
        /** 承载测试中的会话上下文和一次性审批状态。 */
        private final InMemoryAgentSession session;

        /** 记录拦截器写入的路由标识，便于断言流程是否被终止。 */
        private String route;

        /** 创建默认测试 Trace。 */
        TestTrace() {
            this(new InMemoryAgentSession("tool-registry-exposure-test"));
        }

        /**
         * 用已有会话创建 Trace，用于模拟审批后的恢复执行。
         *
         * @param session 已经保存审批状态的测试会话。
         */
        TestTrace(InMemoryAgentSession session) {
            this.session = session;
        }

        @Override
        public InMemoryAgentSession getSession() {
            return session;
        }

        @Override
        public org.noear.solon.flow.FlowContext getContext() {
            return session.getContext();
        }

        @Override
        public void setRoute(String route) {
            this.route = route;
        }

        @Override
        public String getRoute() {
            return route;
        }
    }

    public static class ReturnedPojo {
        private final String title;
        private final String finalUrl;

        public ReturnedPojo(String title, String finalUrl) {
            this.title = title;
            this.finalUrl = finalUrl;
        }

        public String getTitle() {
            return title;
        }

        public String getFinalUrl() {
            return finalUrl;
        }
    }

    public static class BlockedStringReturnedObject {
        @Override
        public String toString() {
            return "https://blocked.example/code?token=secret123";
        }
    }

    public static class SecretStringReturnedObject {
        @Override
        public String toString() {
            return "token=ghp_unstructured12345 https://example.com/code";
        }
    }
}
