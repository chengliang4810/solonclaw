package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.jimuqu.solon.claw.cli.CliAttachmentResolver;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillCredentialFileService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供安全审计工具能力，供 Agent 运行时按安全策略调用。 */
public class SecurityAuditTools {
    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 注入审批服务，用于调用对应业务能力。 */
    private final DangerousCommandApprovalService approvalService;

    /** 注入tirith安全服务，用于调用对应业务能力。 */
    private final TirithSecurityService tirithSecurityService;

    /** 注入工具结果Storage服务，用于调用对应业务能力。 */
    private final ToolResultStorageService toolResultStorageService;

    /** 注入应用配置，用于安全审计。 */
    private final AppConfig appConfig;

    /**
     * 创建安全审计工具实例，并注入运行所需依赖。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     */
    public SecurityAuditTools(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            TirithSecurityService tirithSecurityService) {
        this(securityPolicyService, approvalService, tirithSecurityService, null);
    }

    /**
     * 创建安全审计工具实例，并注入运行所需依赖。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param appConfig 应用运行配置。
     */
    public SecurityAuditTools(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            TirithSecurityService tirithSecurityService,
            AppConfig appConfig) {
        this(securityPolicyService, approvalService, tirithSecurityService, null, appConfig);
    }

    /**
     * 创建安全审计工具实例，并注入运行所需依赖。
     *
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param appConfig 应用运行配置。
     */
    public SecurityAuditTools(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            AppConfig appConfig) {
        this.securityPolicyService = securityPolicyService;
        this.approvalService = approvalService;
        this.tirithSecurityService = tirithSecurityService;
        this.toolResultStorageService = toolResultStorageService;
        this.appConfig = appConfig;
    }

    /**
     * 执行审计相关逻辑。
     *
     * @param action 操作参数。
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @param url 待校验或访问的 URL。
     * @param path 文件或目录路径。
     * @param writeLike 写入Like参数。
     * @param argsJson argsJSON参数。
     * @return 返回审计结果。
     */
    @ToolMapping(
            name = "security_audit",
            description = "只读安全审计。action 支持 command、url、path、tool_args、policy/status；不会执行命令或访问目标。")
    public String audit(
            @Param(name = "action", description = "command/url/path/tool_args/policy/status")
                    String action,
            @Param(name = "toolName", description = "工具名，可选", required = false) String toolName,
            @Param(name = "command", description = "要审计的命令或代码", required = false) String command,
            @Param(name = "url", description = "要审计的 URL", required = false) String url,
            @Param(name = "path", description = "要审计的文件路径", required = false) String path,
            @Param(name = "writeLike", description = "路径是否按写入类操作检查", required = false)
                    Boolean writeLike,
            @Param(name = "argsJson", description = "tool_args 模式下的 JSON 参数对象", required = false)
                    String argsJson) {
        String mode = StrUtil.blankToDefault(action, "command").trim().toLowerCase(Locale.ROOT);
        AuditResult result;
        if ("policy".equals(mode) || "status".equals(mode)) {
            result = auditPolicy(mode);
        } else if ("url".equals(mode)) {
            result = auditUrl(url);
        } else if ("path".equals(mode)) {
            result = auditPath(path, writeLike != null && writeLike.booleanValue());
        } else if ("tool_args".equals(mode) || "tool-args".equals(mode)) {
            result = auditToolArgs(toolName, argsJson);
        } else if ("command".equals(mode)) {
            result = auditCommand(toolName, command);
        } else {
            result = new AuditResult(mode);
            result.status = "error";
            result.decision = "error";
            result.summary = "Unsupported security_audit action: " + StrUtil.nullToEmpty(action);
        }
        return ONode.serialize(result.toMap());
    }

    /**
     * 执行审计策略相关逻辑。
     *
     * @param mode 模式参数。
     * @return 返回审计策略结果。
     */
    private AuditResult auditPolicy(String mode) {
        String action = "status".equals(mode) ? "status" : "policy";
        AuditResult result = new AuditResult(action);
        if (appConfig == null) {
            result.status = "error";
            result.decision = "error";
            result.summary = "security policy config is unavailable";
            return result;
        }

        result.policy = new LinkedHashMap<String, Object>();
        Map<String, Object> approvals = new LinkedHashMap<String, Object>();
        String guardrailMode = normalizeGuardrailMode(appConfig.getSecurity().getGuardrailMode());
        String guardrailCronMode =
                approvalService == null
                        ? normalizeCronApprovalMode(appConfig.getSecurity().getGuardrailCronMode())
                        : approvalService.guardrailCronMode();
        boolean smartMode = "smart".equals(guardrailMode);
        boolean smartJudgeConfigured =
                approvalService != null && approvalService.hasSmartApprovalJudge();
        approvals.put("guardrailMode", guardrailMode);
        approvals.put("smartMode", Boolean.valueOf(smartMode));
        approvals.put("smartJudgeConfigured", Boolean.valueOf(smartJudgeConfigured));
        approvals.put("smartApprovalActive", Boolean.valueOf(smartMode && smartJudgeConfigured));
        approvals.put(
                "smartCoversTirith",
                Boolean.valueOf(
                        smartMode && smartJudgeConfigured && tirithSecurityService != null));
        approvals.put("guardrailCronMode", guardrailCronMode);
        approvals.put("cronAutoApprove", Boolean.valueOf("approve".equals(guardrailCronMode)));
        approvals.put(
                "subagentAutoApprove",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        approvals.put(
                "subagentApprovalDefault",
                appConfig.getApprovals().isSubagentAutoApprove() ? "approve" : "deny");
        approvals.put(
                "timeoutSeconds", Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        approvals.put(
                "gatewayTimeoutSeconds",
                Integer.valueOf(appConfig.getApprovals().getGatewayTimeoutSeconds()));
        approvals.put(
                "mcpReloadConfirm", Boolean.valueOf(appConfig.getApprovals().isMcpReloadConfirm()));
        approvals.put(
                "mcpReloadConfirmationDefault",
                appConfig.getApprovals().isMcpReloadConfirm() ? "confirm" : "direct");
        approvals.put(
                "alwaysApprovalCount",
                Integer.valueOf(
                        approvalService == null
                                ? 0
                                : approvalService.listAlwaysApprovals().size()));
        if (approvalService != null) {
            approvals.put("approvalPolicy", approvalService.approvalPolicySummary());
            approvals.put("cronApprovalPolicy", approvalService.cronApprovalPolicySummary());
            approvals.put(
                    "subagentApprovalPolicy", approvalService.subagentApprovalPolicySummary());
            approvals.put("smartApprovalPolicy", approvalService.smartApprovalPolicySummary());
            approvals.put("tirithApprovalPolicy", approvalService.tirithApprovalPolicySummary());
            approvals.put("slashConfirmPolicy", approvalService.slashConfirmPolicySummary());
            approvals.put("approvalCardPolicy", approvalService.approvalCardPolicySummary());
            approvals.put("auditLogPolicy", approvalService.approvalAuditPolicySummary());
            approvals.put("mcpReloadPolicy", approvalService.mcpReloadPolicySummary());
        }
        result.policy.put("approvals", approvals);

        Map<String, Object> security = new LinkedHashMap<String, Object>();
        security.put(
                "allowPrivateUrls", Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        if (securityPolicyService != null) {
            security.put("urlPolicy", securityPolicyService.urlPolicySummary());
        }
        security.put("tirithEnabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        security.put(
                "tirithConfigured",
                Boolean.valueOf(StrUtil.isNotBlank(appConfig.getSecurity().getTirithPath())));
        security.put(
                "tirithTimeoutSeconds",
                Integer.valueOf(appConfig.getSecurity().getTirithTimeoutSeconds()));
        security.put("tirithFailOpen", Boolean.valueOf(appConfig.getSecurity().isTirithFailOpen()));
        TirithSecurityService.Diagnostic tirithDiagnostic =
                tirithSecurityService == null ? null : tirithSecurityService.diagnose();
        if (tirithDiagnostic != null) {
            security.put("tirithAvailable", Boolean.valueOf(tirithDiagnostic.isAvailable()));
            security.put("tirithDiagnostic", tirithDiagnostic.toMap());
        }
        if (tirithSecurityService != null) {
            security.put("tirithPolicy", tirithSecurityService.policySummary());
        }
        security.put(
                "websiteBlocklistEnabled",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        security.put(
                "websiteBlocklistDomainCount",
                Integer.valueOf(size(appConfig.getSecurity().getWebsiteBlocklist().getDomains())));
        security.put(
                "websiteBlocklistSharedFileCount",
                Integer.valueOf(
                        size(appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles())));
        if (securityPolicyService != null) {
            Map<String, Object> urlPolicy = securityPolicyService.urlPolicySummary();
            security.put(
                    "websiteBlocklistSharedRuleCount",
                    urlPolicy.get("websiteBlocklistSharedRuleCount"));
            security.put(
                    "websiteBlocklistLoadedSharedFileCount",
                    urlPolicy.get("websiteBlocklistLoadedSharedFileCount"));
            security.put(
                    "websiteBlocklistSkippedSharedFileCount",
                    urlPolicy.get("websiteBlocklistSkippedSharedFileCount"));
        }
        result.policy.put("security", security);

        Map<String, Object> terminal = new LinkedHashMap<String, Object>();
        terminal.put(
                "credentialFileCount",
                Integer.valueOf(size(appConfig.getTerminal().getCredentialFiles())));
        if (securityPolicyService != null) {
            terminal.put("credentialPolicy", securityPolicyService.credentialPolicySummary());
            terminal.put("pathPolicy", securityPolicyService.pathPolicySummary());
        }
        terminal.put(
                "credentialMountPolicy", new SkillCredentialFileService(appConfig).policySummary());
        terminal.put(
                "envPassthroughCount",
                Integer.valueOf(size(appConfig.getTerminal().getEnvPassthrough())));
        boolean sudoPasswordConfigured = appConfig.getTerminal().getSudoPassword() != null;
        terminal.put("sudoPasswordConfigured", Boolean.valueOf(sudoPasswordConfigured));
        terminal.put(
                "sudoRewritePolicy",
                SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured));
        terminal.put(
                "terminalOutputPolicy", SolonClawShellSkill.terminalOutputPolicySummary(appConfig));
        if (approvalService != null) {
            terminal.put(
                    "terminalGuardrailPolicy", approvalService.terminalGuardrailPolicySummary());
        }
        Map<String, Object> backgroundProcessPolicy =
                ProcessTools.backgroundProcessPolicySummary(appConfig);
        terminal.put("backgroundProcessPolicy", backgroundProcessPolicy);
        terminal.put(
                "maxForegroundTimeoutSeconds",
                Integer.valueOf(appConfig.getTerminal().getMaxForegroundTimeoutSeconds()));
        terminal.put(
                "foregroundMaxRetries",
                Integer.valueOf(appConfig.getTerminal().getForegroundMaxRetries()));
        terminal.put(
                "foregroundRetryBaseDelaySeconds",
                Integer.valueOf(appConfig.getTerminal().getForegroundRetryBaseDelaySeconds()));
        result.policy.put("terminal", terminal);

        Map<String, Object> coverage = new LinkedHashMap<String, Object>();
        if (securityPolicyService != null) {
            coverage.put("urlPolicyDetails", securityPolicyService.urlPolicySummary());
            coverage.put(
                    "privateUrlPolicyDetails", securityPolicyService.privateUrlPolicySummary());
            coverage.put("websitePolicyDetails", securityPolicyService.websitePolicySummary());
            coverage.put("pathPolicyDetails", securityPolicyService.pathPolicySummary());
            coverage.put(
                    "credentialPolicyDetails", securityPolicyService.credentialPolicySummary());
            coverage.put("toolArgsPolicy", securityPolicyService.toolArgsPolicySummary());
        }
        coverage.put("schemaSanitizerPolicy", SolonClawToolSchemaSanitizer.policySummary());
        coverage.put("patchParserPolicy", SolonClawPatchTools.patchParserPolicySummary());
        coverage.put("readOnlyAuditPolicy", readOnlyAuditPolicySummary());
        coverage.put(
                "subprocessEnvironmentPolicy",
                SubprocessEnvironmentSanitizer.policySummary(appConfig));
        coverage.put(
                "codeExecutionPolicy",
                SolonClawCodeExecutionSkills.codeExecutionPolicySummary(appConfig));
        coverage.put("mcpRuntimePolicy", McpRuntimeService.policySummary(appConfig));
        coverage.put("mcpOAuthPolicy", DashboardMcpService.oauthPolicySummary());
        coverage.put(
                "mcpPackageSecurityPolicy", new McpPackageSecurityService(null).policySummary());
        Map<String, Object> attachmentPolicy = new LinkedHashMap<String, Object>();
        attachmentPolicy.put("downloadIo", BoundedAttachmentIO.policySummary());
        attachmentPolicy.put("mediaCache", new AttachmentCacheService(appConfig).policySummary());
        attachmentPolicy.put("terminalPaste", CliAttachmentResolver.policySummary());
        coverage.put("attachmentPolicy", attachmentPolicy);
        if (toolResultStorageService != null) {
            coverage.put("toolResultStoragePolicy", toolResultStorageService.policySummary());
        }
        coverage.put("dangerousCommandApproval", Boolean.TRUE);
        coverage.put(
                "configuredCredentialCommandPathApproval",
                Boolean.valueOf(approvalService != null));
        coverage.put("slashApprovalConfirm", Boolean.valueOf(approvalService != null));
        if (approvalService != null) {
            coverage.put("dangerousCommandApprovalPolicy", approvalService.approvalPolicySummary());
            coverage.put(
                    "approvalLifecyclePolicy", approvalService.approvalLifecyclePolicySummary());
            coverage.put("slashConfirmPolicy", approvalService.slashConfirmPolicySummary());
            coverage.put("approvalCardPolicy", approvalService.approvalCardPolicySummary());
            coverage.put("approvalAuditPolicy", approvalService.approvalAuditPolicySummary());
            coverage.put("mcpReloadPolicy", approvalService.mcpReloadPolicySummary());
        }
        coverage.put("smartApproval", Boolean.valueOf(smartMode && smartJudgeConfigured));
        if (approvalService != null) {
            coverage.put("smartApprovalPolicy", approvalService.smartApprovalPolicySummary());
        }
        coverage.put(
                "tirithSmartApproval",
                Boolean.valueOf(
                        smartMode && smartJudgeConfigured && tirithSecurityService != null));
        if (approvalService != null) {
            coverage.put("tirithApprovalPolicy", approvalService.tirithApprovalPolicySummary());
        }
        coverage.put("cronApprovalPolicy", Boolean.TRUE);
        if (approvalService != null) {
            coverage.put("cronApprovalPolicyDetails", approvalService.cronApprovalPolicySummary());
            coverage.put(
                    "subagentApprovalPolicyDetails",
                    approvalService.subagentApprovalPolicySummary());
        }
        coverage.put("subagentApprovalPolicy", Boolean.TRUE);
        coverage.put("approvalAuditLog", Boolean.valueOf(approvalService != null));
        coverage.put("hardlineCommandBlocks", Boolean.TRUE);
        if (approvalService != null) {
            coverage.put("hardlinePolicy", approvalService.hardlinePolicySummary());
        }
        coverage.put("terminalGuardrails", Boolean.TRUE);
        if (approvalService != null) {
            coverage.put(
                    "terminalGuardrailPolicy", approvalService.terminalGuardrailPolicySummary());
        }
        coverage.put("sudoRewrite", Boolean.TRUE);
        coverage.put(
                "sudoRewritePolicy",
                SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured));
        coverage.put(
                "terminalOutputPolicy", SolonClawShellSkill.terminalOutputPolicySummary(appConfig));
        coverage.put("backgroundProcessGuard", Boolean.TRUE);
        coverage.put("backgroundProcessPolicy", backgroundProcessPolicy);
        coverage.put("urlSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("privateUrlPolicy", Boolean.valueOf(securityPolicyService != null));
        coverage.put("websitePolicy", Boolean.valueOf(securityPolicyService != null));
        coverage.put("credentialFilePolicy", Boolean.valueOf(securityPolicyService != null));
        coverage.put("credentialMountPolicy", Boolean.TRUE);
        coverage.put(
                "credentialMountPolicyDetails",
                new SkillCredentialFileService(appConfig).policySummary());
        coverage.put("pathSecurity", Boolean.valueOf(securityPolicyService != null));
        coverage.put("toolArgsSecurity", Boolean.valueOf(securityPolicyService != null));
        coverage.put(
                "toolReturnedContentUrlSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("schemaSanitizer", Boolean.TRUE);
        coverage.put("patchParser", Boolean.TRUE);
        coverage.put("subprocessEnvironmentSanitizer", Boolean.TRUE);
        coverage.put("toolResultStorage", Boolean.valueOf(toolResultStorageService != null));
        coverage.put(
                "codeExecutionGuardrails",
                Boolean.valueOf(approvalService != null || securityPolicyService != null));
        coverage.put("codeExecutionPolicyAuditable", Boolean.TRUE);
        coverage.put("mcpUrlSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("mcpReloadConfirmation", Boolean.valueOf(approvalService != null));
        coverage.put("mcpToolChangeNotice", Boolean.TRUE);
        coverage.put("mcpRuntimePolicyAuditable", Boolean.TRUE);
        coverage.put("mcpPackageSecurity", Boolean.TRUE);
        coverage.put("attachmentUrlSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("attachmentCachePathSafety", Boolean.TRUE);
        coverage.put("attachmentDisplayNameRedaction", Boolean.TRUE);
        coverage.put(
                "terminalAttachmentPathSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("terminalAttachmentPreviewRedaction", Boolean.TRUE);
        coverage.put("terminalAttachmentResolvedNameRedaction", Boolean.TRUE);
        coverage.put("tirithSecurity", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        if (tirithSecurityService != null) {
            coverage.put("tirithPolicy", tirithSecurityService.policySummary());
        }
        coverage.put("readOnlyAuditTool", Boolean.TRUE);
        result.policy.put("coverage", coverage);

        List<String> activeSurfaces = new ArrayList<String>();
        addSurface(activeSurfaces, "approval", approvalService != null);
        addSurface(
                activeSurfaces, "configuredCredentialCommandPathApproval", approvalService != null);
        addSurface(activeSurfaces, "approvalLifecycle", approvalService != null);
        addSurface(activeSurfaces, "approvalAuditLog", approvalService != null);
        addSurface(activeSurfaces, "slashConfirm", approvalService != null);
        addSurface(activeSurfaces, "smartApproval", smartMode && smartJudgeConfigured);
        addSurface(
                activeSurfaces,
                "tirithSmartApproval",
                smartMode && smartJudgeConfigured && tirithSecurityService != null);
        addSurface(activeSurfaces, "cronApprovalPolicy", true);
        addSurface(activeSurfaces, "subagentApprovalPolicy", true);
        addSurface(activeSurfaces, "hardlineCommand", true);
        addSurface(activeSurfaces, "terminalGuardrails", true);
        addSurface(activeSurfaces, "sudoRewrite", true);
        addSurface(activeSurfaces, "backgroundProcess", true);
        addSurface(activeSurfaces, "urlSafety", securityPolicyService != null);
        addSurface(activeSurfaces, "privateUrlPolicy", securityPolicyService != null);
        addSurface(activeSurfaces, "websitePolicy", securityPolicyService != null);
        addSurface(activeSurfaces, "credentialFilePolicy", securityPolicyService != null);
        addSurface(activeSurfaces, "credentialMountPolicy", true);
        addSurface(activeSurfaces, "pathSecurity", securityPolicyService != null);
        addSurface(activeSurfaces, "toolArgsSecurity", securityPolicyService != null);
        addSurface(activeSurfaces, "toolReturnedContentUrlSafety", securityPolicyService != null);
        addSurface(activeSurfaces, "schemaSanitizer", true);
        addSurface(activeSurfaces, "patchParser", true);
        addSurface(activeSurfaces, "subprocessEnvironmentSanitizer", true);
        addSurface(activeSurfaces, "toolResultStorage", toolResultStorageService != null);
        addSurface(
                activeSurfaces,
                "codeExecution",
                approvalService != null || securityPolicyService != null);
        addSurface(activeSurfaces, "mcpRuntimePolicy", true);
        addSurface(activeSurfaces, "mcpOauthUrlSafety", securityPolicyService != null);
        addSurface(activeSurfaces, "mcpOauthPolicy", true);
        addSurface(activeSurfaces, "mcpPackageSecurity", true);
        addSurface(activeSurfaces, "mcpReloadConfirmation", approvalService != null);
        addSurface(activeSurfaces, "mcpToolChangeNotice", true);
        addSurface(activeSurfaces, "attachmentPolicy", true);
        addSurface(activeSurfaces, "terminalAttachmentPathSafety", securityPolicyService != null);
        addSurface(activeSurfaces, "tirithSecurity", appConfig.getSecurity().isTirithEnabled());
        addSurface(activeSurfaces, "readOnlyAuditTool", true);
        result.policy.put("activeSurfaces", activeSurfaces);

        result.summary = "Security policy status is available without exposing secret values.";
        result.finish();
        return result;
    }

    /**
     * 执行审计命令相关逻辑。
     *
     * @param toolName 工具名称。
     * @param command 待执行或解析的命令文本。
     * @return 返回审计命令结果。
     */
    private AuditResult auditCommand(String toolName, String command) {
        String effectiveTool = canonicalCommandAuditTool(toolName);
        AuditResult result = new AuditResult("command");
        result.toolName = effectiveTool;
        result.commandPreview =
                SecretRedactor.redact(HtmlUtil.unescape(StrUtil.nullToEmpty(command).trim()), 400);

        if (StrUtil.isBlank(command)) {
            result.status = "error";
            result.decision = "error";
            result.summary = "command is required";
            return result;
        }

        if (approvalService != null) {
            DangerousCommandApprovalService.DetectionResult hardline =
                    approvalService.detectHardline(effectiveTool, command);
            if (hardline != null) {
                result.addFinding(
                        "hardline",
                        hardline.getPatternKey(),
                        "critical",
                        hardline.getDescription(),
                        "block",
                        true,
                        false,
                        "change_command");
                result.escalate("block");
            }

            String backgroundGuidance =
                    approvalService.foregroundBackgroundGuidance(effectiveTool, command);
            if (StrUtil.isNotBlank(backgroundGuidance)) {
                result.addFinding(
                        "terminal_guardrail",
                        "background_process",
                        "high",
                        backgroundGuidance,
                        "block",
                        true,
                        false,
                        "use_managed_background_process");
                result.escalate("block");
            }

            DangerousCommandApprovalService.DetectionResult local =
                    approvalService.detect(effectiveTool, command);
            if (local != null) {
                result.addFinding(
                        "dangerous_command",
                        local.getPatternKey(),
                        "medium",
                        local.getDescription(),
                        "warn",
                        false,
                        true,
                        "request_approval");
                result.escalate("warn");
            }
        }

        if (securityPolicyService != null) {
            if (SolonClawCodeExecutionSkills.isFileGuardrailEnabled(appConfig)) {
                SecurityPolicyService.FileVerdict fileVerdict =
                        securityPolicyService.checkCommandPaths(command);
                if (!fileVerdict.isAllowed()) {
                    result.addFinding(
                            "file_policy",
                            "blocked_path",
                            "critical",
                            filePolicyFindingMessage(fileVerdict),
                            "block",
                            true,
                            false,
                            "change_path");
                    result.escalate("block");
                }
            }

            if (SolonClawCodeExecutionSkills.isUrlGuardrailEnabled(appConfig)) {
                SecurityPolicyService.UrlVerdict urlVerdict =
                        securityPolicyService.checkCommandUrls(command);
                if (!urlVerdict.isAllowed()) {
                    result.addFinding(
                            "url_policy",
                            "blocked_url",
                            "critical",
                            urlVerdict.getMessage()
                                    + ": "
                                    + SecretRedactor.maskUrl(urlVerdict.getUrl()),
                            "block",
                            true,
                            false,
                            "change_url_or_policy");
                    result.escalate("block");
                }
            }
        }

        if (tirithSecurityService != null) {
            TirithSecurityService.ScanResult scan =
                    tirithSecurityService.checkCommandSecurityForTool(effectiveTool, command);
            addTirith(result, scan);
        }

        result.finish();
        return result;
    }

    /**
     * 执行审计URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回审计URL结果。
     */
    private AuditResult auditUrl(String url) {
        AuditResult result = new AuditResult("url");
        result.url = SecretRedactor.maskUrl(StrUtil.nullToEmpty(url).trim());
        if (securityPolicyService == null) {
            result.summary = "URL policy is unavailable";
            return result;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            result.addFinding(
                    "url_policy",
                    "blocked_url",
                    "critical",
                    verdict.getMessage(),
                    "block",
                    true,
                    false,
                    "change_url_or_policy");
            result.escalate("block");
        }
        result.finish();
        return result;
    }

    /**
     * 执行审计路径相关逻辑。
     *
     * @param path 文件或目录路径。
     * @param writeLike 写入Like参数。
     * @return 返回审计路径。
     */
    private AuditResult auditPath(String path, boolean writeLike) {
        AuditResult result = new AuditResult("path");
        result.path = pathReference(path);
        result.writeLike = Boolean.valueOf(writeLike);
        if (securityPolicyService == null) {
            result.summary = "file policy is unavailable";
            return result;
        }
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkPath(path, writeLike);
        if (!verdict.isAllowed()) {
            result.addFinding(
                    "file_policy",
                    "blocked_path",
                    "critical",
                    verdict.getMessage(),
                    "block",
                    true,
                    false,
                    "change_path");
            result.escalate("block");
        }
        result.finish();
        return result;
    }

    /**
     * 执行路径引用相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回路径Reference结果。
     */
    private String pathReference(String path) {
        String text = StrUtil.nullToEmpty(path).trim();
        if (StrUtil.isBlank(text)) {
            return "";
        }
        return "path://" + SecretRedactor.redact(text, 400);
    }

    /**
     * 执行审计工具参数相关逻辑。
     *
     * @param toolName 工具名称。
     * @param argsJson argsJSON参数。
     * @return 返回审计工具参数结果。
     */
    @SuppressWarnings("unchecked")
    private AuditResult auditToolArgs(String toolName, String argsJson) {
        String effectiveTool =
                StrUtil.blankToDefault(toolName, ToolNameConstants.EXECUTE_SHELL).trim();
        AuditResult result = new AuditResult("tool_args");
        result.toolName = effectiveTool;
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        if (StrUtil.isNotBlank(argsJson)) {
            try {
                Object parsed = ONode.deserialize(argsJson, Object.class);
                if (parsed instanceof Map) {
                    args.putAll((Map<String, Object>) parsed);
                } else {
                    result.status = "error";
                    result.decision = "error";
                    result.summary = "argsJson must be a JSON object";
                    return result;
                }
            } catch (Exception e) {
                result.status = "error";
                result.decision = "error";
                result.summary =
                        "argsJson parse failed: "
                                + SecretRedactor.redact(
                                        StrUtil.blankToDefault(
                                                e.getMessage(), e.getClass().getSimpleName()),
                                        1000)
                                + "; input="
                                + SecretRedactor.redact(StrUtil.nullToEmpty(argsJson), 1000);
                return result;
            }
        }
        List<String> commands = commandLikeArguments(args);
        for (String command : commands) {
            applyCommandPolicies(result, effectiveTool, command);
        }
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict fileVerdict =
                    securityPolicyService.checkFileToolArgs(effectiveTool, args);
            if (!fileVerdict.isAllowed()) {
                result.addFinding(
                        "file_policy",
                        "blocked_path",
                        "critical",
                        filePolicyFindingMessage(fileVerdict),
                        "block",
                        true,
                        false,
                        "change_path");
                result.escalate("block");
            }
            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkToolArgs(effectiveTool, args);
            if (!urlVerdict.isAllowed()) {
                result.addFinding(
                        "url_policy",
                        "blocked_url",
                        "critical",
                        urlVerdict.getMessage()
                                + ": "
                                + SecretRedactor.maskUrl(urlVerdict.getUrl()),
                        "block",
                        true,
                        false,
                        "change_url_or_policy");
                result.escalate("block");
            }
        }
        result.finish();
        return result;
    }

    /**
     * 执行文件策略Finding消息相关逻辑。
     *
     * @param fileVerdict 文件或目录路径参数。
     * @return 返回文件策略Finding消息结果。
     */
    private String filePolicyFindingMessage(SecurityPolicyService.FileVerdict fileVerdict) {
        if (fileVerdict == null) {
            return "文件安全策略阻断";
        }
        String message = StrUtil.blankToDefault(fileVerdict.getMessage(), "文件安全策略阻断");
        String rawPath = StrUtil.nullToEmpty(fileVerdict.getPath());
        String path =
                shouldRedactSensitivePathReference(rawPath)
                        ? "[REDACTED_PATH]"
                        : SecretRedactor.redact(rawPath, 400);
        return StrUtil.isBlank(path) ? message : message + ": " + path;
    }

    /**
     * 判断审计 finding 中的路径是否属于凭据载体；命中时只展示通用占位，避免泄露密钥文件位置。
     *
     * @param rawPath 文件或目录路径。
     * @return 如果路径指向凭据或密钥文件返回 true。
     */
    private boolean shouldRedactSensitivePathReference(String rawPath) {
        String path = StrUtil.nullToEmpty(rawPath).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(path)) {
            return false;
        }
        return ".env".equals(path)
                || path.startsWith(".env.")
                || path.contains("/.env")
                || path.contains("\\.env")
                || ".ssh".equals(path)
                || path.contains("/.ssh")
                || path.contains("\\.ssh")
                || path.contains("credential")
                || path.contains("secret")
                || path.contains("token")
                || path.contains("password")
                || path.contains("passwd")
                || path.contains("private-key")
                || path.contains("private_key")
                || path.contains("id_rsa")
                || path.contains("id_ed25519")
                || path.endsWith(".pem")
                || path.endsWith(".key");
    }

    /**
     * 应用命令Policies。
     *
     * @param result 结果响应或执行结果。
     * @param effectiveTool effective工具参数。
     * @param command 待执行或解析的命令文本。
     */
    private void applyCommandPolicies(AuditResult result, String effectiveTool, String command) {
        AuditResult commandResult = auditCommand(effectiveTool, command);
        result.commandPreview = commandResult.commandPreview;
        result.tirithAction = commandResult.tirithAction;
        for (Map<String, Object> finding : commandResult.findings) {
            result.findings.add(finding);
        }
        if (commandResult.blocking) {
            result.blocking = true;
        }
        if (commandResult.approvalRequired) {
            result.approvalRequired = true;
        }
        result.escalate(commandResult.decision);
    }

    /**
     * 执行命令Like参数相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回命令Like参数结果。
     */
    private List<String> commandLikeArguments(Map<String, Object> args) {
        List<String> commands = new ArrayList<String>();
        collectCommandLikeArguments(args, commands, false);
        return commands;
    }

    /**
     * 收集命令Like参数。
     *
     * @param value 待规范化或校验的原始值。
     * @param commands commands 参数。
     * @param commandValue 命令值参数。
     */
    @SuppressWarnings("unchecked")
    private void collectCommandLikeArguments(
            Object value, List<String> commands, boolean commandValue) {
        if (value == null) {
            return;
        }
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                String key =
                        entry.getKey() == null
                                ? ""
                                : String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
                collectCommandLikeArguments(
                        entry.getValue(), commands, COMMAND_ARGUMENT_KEYS.contains(key));
            }
            return;
        }
        if (value instanceof Iterable) {
            if (commandValue) {
                String command = commandValueToString(value);
                if (StrUtil.isNotBlank(command)) {
                    commands.add(command);
                }
            } else {
                for (Object item : (Iterable<?>) value) {
                    collectCommandLikeArguments(item, commands, false);
                }
            }
            return;
        }
        if (value.getClass().isArray()) {
            if (commandValue) {
                String command = commandValueToString(value);
                if (StrUtil.isNotBlank(command)) {
                    commands.add(command);
                }
            } else {
                int length = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    collectCommandLikeArguments(
                            java.lang.reflect.Array.get(value, i), commands, false);
                }
            }
            return;
        }
        if (commandValue) {
            String text = StrUtil.nullToEmpty(String.valueOf(value)).trim();
            if (StrUtil.isNotBlank(text)) {
                commands.add(text);
            }
        }
    }

    /**
     * 执行命令值To字符串相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回命令Value To String结果。
     */
    private String commandValueToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        StringBuilder buffer = new StringBuilder();
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key =
                        entry.getKey() == null
                                ? ""
                                : String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
                if (COMMAND_ARGUMENT_KEYS.contains(key)) {
                    appendCommandPart(buffer, entry.getValue());
                }
            }
            if (buffer.length() == 0) {
                for (Object nested : map.values()) {
                    appendCommandPart(buffer, nested);
                }
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                appendCommandPart(buffer, item);
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendCommandPart(buffer, java.lang.reflect.Array.get(value, i));
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        return null;
    }

    /**
     * 追加命令Part。
     *
     * @param buffer buffer 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendCommandPart(StringBuilder buffer, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            String part = String.valueOf(value).trim();
            if (StrUtil.isNotBlank(part)) {
                if (buffer.length() > 0) {
                    buffer.append(' ');
                }
                buffer.append(part);
            }
            return;
        }
        if (value instanceof Map || value instanceof Iterable || value.getClass().isArray()) {
            String nested = commandValueToString(value);
            if (StrUtil.isNotBlank(nested)) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(nested);
            }
        }
    }

    /**
     * 执行规范命令审计工具相关逻辑。
     *
     * @param toolName 工具名称。
     * @return 返回规范命令审计工具结果。
     */
    private String canonicalCommandAuditTool(String toolName) {
        String normalized =
                StrUtil.blankToDefault(toolName, ToolNameConstants.EXECUTE_SHELL).trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (ToolNameConstants.TERMINAL.equals(lower)) {
            return ToolNameConstants.EXECUTE_SHELL;
        }
        return normalized;
    }

    /**
     * 追加Tirith。
     *
     * @param result 结果响应或执行结果。
     * @param scan scan 参数。
     */
    private void addTirith(AuditResult result, TirithSecurityService.ScanResult scan) {
        if (scan == null) {
            return;
        }
        result.tirithAction = scan.getAction();
        if ("block".equals(scan.getAction())) {
            result.escalate("block");
        } else if ("warn".equals(scan.getAction())) {
            result.escalate("warn");
        }
        if (StrUtil.isNotBlank(scan.getSummary())) {
            result.addFinding(
                    "tirith",
                    "security_scan",
                    scan.getAction(),
                    scan.getSummary(),
                    scan.getAction(),
                    "block".equals(scan.getAction()),
                    "warn".equals(scan.getAction()),
                    "warn".equals(scan.getAction()) ? "request_approval" : "change_command");
        }
        for (TirithSecurityService.Finding finding : scan.getFindings()) {
            String action = StrUtil.blankToDefault(scan.getAction(), "warn");
            result.addFinding(
                    "tirith",
                    StrUtil.blankToDefault(finding.getRuleId(), "security_scan"),
                    StrUtil.blankToDefault(finding.getSeverity(), action),
                    StrUtil.blankToDefault(
                            finding.getTitle(),
                            StrUtil.blankToDefault(finding.getDescription(), scan.getSummary())),
                    action,
                    "block".equals(action),
                    "warn".equals(action),
                    "warn".equals(action) ? "request_approval" : "change_command");
        }
    }

    /** 表示审计结果，携带调用方后续判断所需信息。 */
    private static class AuditResult {
        /** 记录审计中的action。 */
        private final String action;

        /** 记录安全审计工具对外返回的当前执行状态。 */
        private String status = "success";

        /** 记录审计中的决策。 */
        private String decision = "allow";

        /** 记录审计中的摘要。 */
        private String summary = "";

        /** 记录审计中的工具名称。 */
        private String toolName;

        /** 记录审计中的命令预览。 */
        private String commandPreview;

        /** 记录审计中的URL。 */
        private String url;

        /** 记录审计中的路径。 */
        private String path;

        /** 是否启用写入Like。 */
        private Boolean writeLike;

        /** 记录审计中的tirithAction。 */
        private String tirithAction;

        /** 保存策略映射，便于按键快速查询。 */
        private Map<String, Object> policy;

        /** 保存findings映射，便于按键快速查询。 */
        private final List<Map<String, Object>> findings = new ArrayList<Map<String, Object>>();

        /** 是否启用blocking。 */
        private boolean blocking;

        /** 是否启用审批Required。 */
        private boolean approvalRequired;

        /**
         * 创建审计结果实例，并注入运行所需依赖。
         *
         * @param action 操作参数。
         */
        private AuditResult(String action) {
            this.action = action;
        }

        /**
         * 追加Finding。
         *
         * @param source 来源参数。
         * @param ruleId rule标识。
         * @param severity severity 参数。
         * @param message 平台消息或错误消息。
         */
        private void addFinding(String source, String ruleId, String severity, String message) {
            addFinding(source, ruleId, severity, message, severity, false, false, "");
        }

        /**
         * 追加Finding。
         *
         * @param source 来源参数。
         * @param ruleId rule标识。
         * @param severity severity 参数。
         * @param message 平台消息或错误消息。
         * @param findingDecision finding决策参数。
         * @param finding块ing finding块ing 参数。
         * @param findingApprovalRequired finding审批Required参数。
         * @param suggestedAction suggestedAction 参数。
         */
        private void addFinding(
                String source,
                String ruleId,
                String severity,
                String message,
                String findingDecision,
                boolean findingBlocking,
                boolean findingApprovalRequired,
                String suggestedAction) {
            Map<String, Object> finding = new LinkedHashMap<String, Object>();
            finding.put("source", StrUtil.nullToEmpty(source));
            finding.put("ruleId", StrUtil.nullToEmpty(ruleId));
            finding.put("severity", StrUtil.nullToEmpty(severity));
            finding.put("message", SecretRedactor.redact(StrUtil.nullToEmpty(message), 1000));
            finding.put("decision", StrUtil.blankToDefault(findingDecision, severity));
            finding.put("blocking", Boolean.valueOf(findingBlocking));
            finding.put("approval_required", Boolean.valueOf(findingApprovalRequired));
            if (StrUtil.isNotBlank(suggestedAction)) {
                finding.put("suggested_action", suggestedAction);
            }
            if (findingBlocking) {
                blocking = true;
            }
            if (findingApprovalRequired) {
                approvalRequired = true;
            }
            findings.add(finding);
        }

        /**
         * 创建需要人工升级处理的审批决策。
         *
         * @param candidate candidate标识或键值。
         */
        private void escalate(String candidate) {
            if ("block".equals(candidate)) {
                decision = "block";
            } else if ("warn".equals(candidate) && !"block".equals(decision)) {
                decision = "warn";
            }
        }

        /** 执行finish相关逻辑。 */
        private void finish() {
            if (StrUtil.isBlank(summary)) {
                if (findings.isEmpty()) {
                    summary = "No security issues detected.";
                } else {
                    summary = decision + ": " + findings.size() + " security finding(s).";
                }
            }
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("status", status);
            map.put("action", SecretRedactor.redact(action, 200));
            map.put("decision", SecretRedactor.redact(decision, 100));
            map.put("blocking", Boolean.valueOf(blocking));
            map.put("approval_required", Boolean.valueOf(approvalRequired));
            map.put("summary", SecretRedactor.redact(summary, 1000));
            if (StrUtil.isNotBlank(toolName)) {
                map.put("toolName", SecretRedactor.redact(toolName, 200));
            }
            if (StrUtil.isNotBlank(commandPreview)) {
                map.put("commandPreview", commandPreview);
            }
            if (StrUtil.isNotBlank(url)) {
                map.put("url", url);
            }
            if (StrUtil.isNotBlank(path)) {
                map.put("path", path);
            }
            if (writeLike != null) {
                map.put("writeLike", writeLike);
            }
            if (StrUtil.isNotBlank(tirithAction)) {
                map.put("tirithAction", tirithAction);
            }
            if (policy != null) {
                map.put("policy", policy);
            }
            map.put("findings", findings);
            return map;
        }
    }

    /**
     * 执行大小相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回大小结果。
     */
    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    /**
     * 追加Surface。
     *
     * @param surfaces surfaces 参数。
     * @param name 名称参数。
     * @param enabled 启用状态开关值。
     */
    private static void addSurface(List<String> surfaces, String name, boolean enabled) {
        if (enabled) {
            surfaces.add(name);
        }
    }

    /**
     * 读取Only审计策略Summary。
     *
     * @return 返回读取到的Only审计策略Summary。
     */
    public static Map<String, Object> readOnlyAuditPolicySummary() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("toolName", "security_audit");
        policy.put("executesCommand", Boolean.FALSE);
        policy.put("opensNetworkConnection", Boolean.FALSE);
        policy.put("readsTargetUrl", Boolean.FALSE);
        policy.put("writesFile", Boolean.FALSE);
        policy.put("storesAuditInput", Boolean.FALSE);
        policy.put("secretRedactionApplied", Boolean.TRUE);
        policy.put("toolArgsCommandPolicyInherited", Boolean.TRUE);
        policy.put("structuredCommandArgumentsJoined", Boolean.TRUE);
        policy.put("nestedStructuredCommandArgumentsExtracted", Boolean.TRUE);
        policy.put("toolArgsUrlPolicyInherited", Boolean.TRUE);
        policy.put("toolArgsPathPolicyInherited", Boolean.TRUE);
        policy.put("toolArgsJsonParseErrorsRedacted", Boolean.TRUE);
        policy.put("commandPreviewLimitChars", Integer.valueOf(400));
        policy.put("findingMessageLimitChars", Integer.valueOf(1000));
        policy.put("supportsActions", "command,url,path,tool_args,policy,status");
        return policy;
    }

    /** 命令参数KEYS的统一常量值。 */
    private static final Set<String> COMMAND_ARGUMENT_KEYS =
            Collections.unmodifiableSet(
                    new HashSet<String>(
                            Arrays.asList(
                                    "code",
                                    "command",
                                    "commands",
                                    "cmd",
                                    "script",
                                    "shell",
                                    "shell_command")));

    /**
     * 规范化审批模式。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回审批模式结果。
     */
    private static String normalizeGuardrailMode(String value) {
        String mode = StrUtil.blankToDefault(value, "bypass").trim().toLowerCase(Locale.ROOT);
        if ("bypass".equals(mode)
                || "approval".equals(mode)
                || "strict".equals(mode)
                || "smart".equals(mode)) {
            return mode;
        }
        throw new IllegalStateException(
                "security.guardrailMode 只支持 approval、strict、bypass、smart，当前值：" + value);
    }

    /**
     * 规范化定时任务审批模式。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回定时任务审批模式结果。
     */
    private static String normalizeCronApprovalMode(String value) {
        String mode = StrUtil.blankToDefault(value, "bypass").trim().toLowerCase(Locale.ROOT);
        if ("approval".equals(mode)
                || "strict".equals(mode)
                || "bypass".equals(mode)
                || "approve".equals(mode)) {
            return mode;
        }
        throw new IllegalStateException(
                "security.guardrailCronMode 只支持 approval、strict、bypass、approve，当前值：" + value);
    }

}
