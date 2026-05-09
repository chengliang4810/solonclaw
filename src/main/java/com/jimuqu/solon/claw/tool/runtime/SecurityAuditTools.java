package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Jimuqu read-only security audit tool. */
public class SecurityAuditTools {
    private final SecurityPolicyService securityPolicyService;
    private final DangerousCommandApprovalService approvalService;
    private final TirithSecurityService tirithSecurityService;
    private final ToolResultStorageService toolResultStorageService;
    private final AppConfig appConfig;

    public SecurityAuditTools(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            TirithSecurityService tirithSecurityService) {
        this(securityPolicyService, approvalService, tirithSecurityService, null);
    }

    public SecurityAuditTools(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            TirithSecurityService tirithSecurityService,
            AppConfig appConfig) {
        this(securityPolicyService, approvalService, tirithSecurityService, null, appConfig);
    }

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

    @ToolMapping(
            name = "security_audit",
            description =
                    "只读安全审计。action 支持 command、url、path、tool_args、policy/status；不会执行命令或访问目标。")
    public String audit(
            @Param(name = "action", description = "command/url/path/tool_args") String action,
            @Param(name = "toolName", description = "工具名，可选", required = false)
                    String toolName,
            @Param(name = "command", description = "要审计的命令或代码", required = false)
                    String command,
            @Param(name = "url", description = "要审计的 URL", required = false) String url,
            @Param(name = "path", description = "要审计的文件路径", required = false) String path,
            @Param(name = "writeLike", description = "路径是否按写入类操作检查", required = false)
                    Boolean writeLike,
            @Param(name = "argsJson", description = "tool_args 模式下的 JSON 参数对象", required = false)
                    String argsJson) {
        String mode = StrUtil.blankToDefault(action, "command").trim().toLowerCase(Locale.ROOT);
        AuditResult result;
        if ("policy".equals(mode) || "status".equals(mode)) {
            result = auditPolicy();
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
            result.success = false;
            result.decision = "error";
            result.summary = "Unsupported security_audit action: " + StrUtil.nullToEmpty(action);
        }
        return ONode.serialize(result.toMap());
    }

    private AuditResult auditPolicy() {
        AuditResult result = new AuditResult("policy");
        if (appConfig == null) {
            result.success = false;
            result.decision = "error";
            result.summary = "security policy config is unavailable";
            return result;
        }

        result.policy = new LinkedHashMap<String, Object>();
        Map<String, Object> approvals = new LinkedHashMap<String, Object>();
        String approvalMode =
                approvalService == null
                        ? normalizeApprovalMode(appConfig.getApprovals().getMode())
                        : approvalService.approvalMode();
        String cronApprovalMode =
                approvalService == null
                        ? normalizeCronApprovalMode(appConfig.getApprovals().getCronMode())
                        : approvalService.cronApprovalMode();
        boolean smartMode = "smart".equals(approvalMode);
        boolean smartJudgeConfigured = approvalService != null && approvalService.hasSmartApprovalJudge();
        approvals.put("mode", approvalMode);
        approvals.put("smartMode", Boolean.valueOf(smartMode));
        approvals.put("smartJudgeConfigured", Boolean.valueOf(smartJudgeConfigured));
        approvals.put("smartApprovalActive", Boolean.valueOf(smartMode && smartJudgeConfigured));
        approvals.put("smartCoversTirith", Boolean.valueOf(smartMode && smartJudgeConfigured && tirithSecurityService != null));
        approvals.put("cronMode", cronApprovalMode);
        approvals.put("cronAutoApprove", Boolean.valueOf("approve".equals(cronApprovalMode)));
        approvals.put("subagentAutoApprove", Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        approvals.put(
                "subagentApprovalDefault",
                appConfig.getApprovals().isSubagentAutoApprove() ? "approve" : "deny");
        approvals.put("timeoutSeconds", Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        approvals.put("gatewayTimeoutSeconds", Integer.valueOf(appConfig.getApprovals().getGatewayTimeoutSeconds()));
        approvals.put("mcpReloadConfirm", Boolean.valueOf(appConfig.getApprovals().isMcpReloadConfirm()));
        approvals.put(
                "mcpReloadConfirmationDefault",
                appConfig.getApprovals().isMcpReloadConfirm() ? "confirm" : "direct");
        approvals.put(
                "alwaysApprovalCount",
                Integer.valueOf(approvalService == null ? 0 : approvalService.listAlwaysApprovals().size()));
        if (approvalService != null) {
            approvals.put("approvalPolicy", approvalService.approvalPolicySummary());
            approvals.put("cronApprovalPolicy", approvalService.cronApprovalPolicySummary());
            approvals.put("subagentApprovalPolicy", approvalService.subagentApprovalPolicySummary());
            approvals.put("smartApprovalPolicy", approvalService.smartApprovalPolicySummary());
            approvals.put("tirithApprovalPolicy", approvalService.tirithApprovalPolicySummary());
            approvals.put("slashConfirmPolicy", approvalService.slashConfirmPolicySummary());
            approvals.put("auditLogPolicy", approvalService.approvalAuditPolicySummary());
            approvals.put("mcpReloadPolicy", approvalService.mcpReloadPolicySummary());
        }
        result.policy.put("approvals", approvals);

        Map<String, Object> security = new LinkedHashMap<String, Object>();
        security.put("allowPrivateUrls", Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        if (securityPolicyService != null) {
            security.put("urlPolicy", securityPolicyService.urlPolicySummary());
        }
        security.put("tirithEnabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        security.put("tirithConfigured", Boolean.valueOf(StrUtil.isNotBlank(appConfig.getSecurity().getTirithPath())));
        security.put("tirithTimeoutSeconds", Integer.valueOf(appConfig.getSecurity().getTirithTimeoutSeconds()));
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
                Integer.valueOf(size(appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles())));
        result.policy.put("security", security);

        Map<String, Object> terminal = new LinkedHashMap<String, Object>();
        terminal.put("credentialFileCount", Integer.valueOf(size(appConfig.getTerminal().getCredentialFiles())));
        if (securityPolicyService != null) {
            terminal.put("credentialPolicy", securityPolicyService.credentialPolicySummary());
            terminal.put("pathPolicy", securityPolicyService.pathPolicySummary());
        }
        terminal.put("envPassthroughCount", Integer.valueOf(size(appConfig.getTerminal().getEnvPassthrough())));
        boolean sudoPasswordConfigured = appConfig.getTerminal().getSudoPassword() != null;
        terminal.put("sudoPasswordConfigured", Boolean.valueOf(sudoPasswordConfigured));
        terminal.put(
                "sudoRewritePolicy",
                SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured));
        terminal.put("writeSafeRootConfigured", Boolean.valueOf(StrUtil.isNotBlank(appConfig.getTerminal().getWriteSafeRoot())));
        if (approvalService != null) {
            terminal.put("terminalGuardrailPolicy", approvalService.terminalGuardrailPolicySummary());
        }
        Map<String, Object> backgroundProcessPolicy =
                ProcessTools.backgroundProcessPolicySummary(appConfig);
        terminal.put("backgroundProcessPolicy", backgroundProcessPolicy);
        terminal.put(
                "maxForegroundTimeoutSeconds",
                Integer.valueOf(appConfig.getTerminal().getMaxForegroundTimeoutSeconds()));
        terminal.put("foregroundMaxRetries", Integer.valueOf(appConfig.getTerminal().getForegroundMaxRetries()));
        terminal.put(
                "foregroundRetryBaseDelaySeconds",
                Integer.valueOf(appConfig.getTerminal().getForegroundRetryBaseDelaySeconds()));
        result.policy.put("terminal", terminal);

        Map<String, Object> coverage = new LinkedHashMap<String, Object>();
        if (securityPolicyService != null) {
            coverage.put("toolArgsPolicy", securityPolicyService.toolArgsPolicySummary());
        }
        coverage.put("schemaSanitizerPolicy", SolonClawToolSchemaSanitizer.policySummary());
        coverage.put("patchParserPolicy", SolonClawPatchTools.patchParserPolicySummary());
        if (toolResultStorageService != null) {
            coverage.put("toolResultStoragePolicy", toolResultStorageService.policySummary());
        }
        coverage.put("dangerousCommandApproval", Boolean.TRUE);
        coverage.put("slashApprovalConfirm", Boolean.valueOf(approvalService != null));
        coverage.put("smartApproval", Boolean.valueOf(smartMode && smartJudgeConfigured));
        if (approvalService != null) {
            coverage.put("smartApprovalPolicy", approvalService.smartApprovalPolicySummary());
        }
        coverage.put("tirithSmartApproval", Boolean.valueOf(smartMode && smartJudgeConfigured && tirithSecurityService != null));
        coverage.put("cronApprovalPolicy", Boolean.TRUE);
        if (approvalService != null) {
            coverage.put("cronApprovalPolicyDetails", approvalService.cronApprovalPolicySummary());
            coverage.put("subagentApprovalPolicyDetails", approvalService.subagentApprovalPolicySummary());
        }
        coverage.put("subagentApprovalPolicy", Boolean.TRUE);
        coverage.put("approvalAuditLog", Boolean.valueOf(approvalService != null));
        coverage.put("hardlineCommandBlocks", Boolean.TRUE);
        if (approvalService != null) {
            coverage.put("hardlinePolicy", approvalService.hardlinePolicySummary());
        }
        coverage.put("terminalGuardrails", Boolean.TRUE);
        coverage.put("sudoRewrite", Boolean.TRUE);
        coverage.put(
                "sudoRewritePolicy",
                SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured));
        coverage.put("backgroundProcessGuard", Boolean.TRUE);
        coverage.put("backgroundProcessPolicy", backgroundProcessPolicy);
        coverage.put("urlSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("privateUrlPolicy", Boolean.valueOf(securityPolicyService != null));
        coverage.put("websitePolicy", Boolean.valueOf(securityPolicyService != null));
        coverage.put("credentialFilePolicy", Boolean.valueOf(securityPolicyService != null));
        coverage.put("pathSecurity", Boolean.valueOf(securityPolicyService != null));
        coverage.put("toolArgsSecurity", Boolean.valueOf(securityPolicyService != null));
        coverage.put("schemaSanitizer", Boolean.TRUE);
        coverage.put("patchParser", Boolean.TRUE);
        coverage.put("toolResultStorage", Boolean.valueOf(toolResultStorageService != null));
        coverage.put("codeExecutionGuardrails", Boolean.valueOf(approvalService != null || securityPolicyService != null));
        coverage.put("mcpUrlSafety", Boolean.valueOf(securityPolicyService != null));
        coverage.put("mcpReloadConfirmation", Boolean.valueOf(approvalService != null));
        coverage.put("mcpToolChangeNotice", Boolean.TRUE);
        coverage.put("tirithSecurity", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        if (tirithSecurityService != null) {
            coverage.put("tirithPolicy", tirithSecurityService.policySummary());
        }
        coverage.put("readOnlyAuditTool", Boolean.TRUE);
        result.policy.put("coverage", coverage);

        List<String> activeSurfaces = new ArrayList<String>();
        addSurface(activeSurfaces, "approval", approvalService != null);
        addSurface(activeSurfaces, "slashConfirm", approvalService != null);
        addSurface(activeSurfaces, "smartApproval", smartMode && smartJudgeConfigured);
        addSurface(activeSurfaces, "tirithSmartApproval", smartMode && smartJudgeConfigured && tirithSecurityService != null);
        addSurface(activeSurfaces, "cronApprovalPolicy", true);
        addSurface(activeSurfaces, "subagentApprovalPolicy", true);
        addSurface(activeSurfaces, "hardlineCommand", true);
        addSurface(activeSurfaces, "terminalGuardrails", true);
        addSurface(activeSurfaces, "sudoRewrite", true);
        addSurface(activeSurfaces, "backgroundProcess", true);
        addSurface(activeSurfaces, "urlSafety", securityPolicyService != null);
        addSurface(activeSurfaces, "websitePolicy", securityPolicyService != null);
        addSurface(activeSurfaces, "credentialFilePolicy", securityPolicyService != null);
        addSurface(activeSurfaces, "pathSecurity", securityPolicyService != null);
        addSurface(activeSurfaces, "toolArgsSecurity", securityPolicyService != null);
        addSurface(activeSurfaces, "codeExecution", approvalService != null || securityPolicyService != null);
        addSurface(activeSurfaces, "mcpOauthUrlSafety", securityPolicyService != null);
        addSurface(activeSurfaces, "mcpReloadConfirmation", approvalService != null);
        addSurface(activeSurfaces, "mcpToolChangeNotice", true);
        addSurface(activeSurfaces, "tirithSecurity", appConfig.getSecurity().isTirithEnabled());
        result.policy.put("activeSurfaces", activeSurfaces);

        result.summary = "Security policy status is available without exposing secret values.";
        result.finish();
        return result;
    }

    private AuditResult auditCommand(String toolName, String command) {
        String effectiveTool =
                StrUtil.blankToDefault(toolName, ToolNameConstants.EXECUTE_SHELL).trim();
        AuditResult result = new AuditResult("command");
        result.toolName = effectiveTool;
        result.commandPreview = SecretRedactor.redact(StrUtil.nullToEmpty(command).trim(), 400);

        if (StrUtil.isBlank(command)) {
            result.success = false;
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
            SecurityPolicyService.FileVerdict fileVerdict =
                    securityPolicyService.checkCommandPaths(command);
            if (!fileVerdict.isAllowed()) {
                result.addFinding(
                        "file_policy",
                        "blocked_path",
                        "critical",
                        fileVerdict.getMessage()
                                + ": "
                                + SecretRedactor.redact(fileVerdict.getPath(), 400),
                        "block",
                        true,
                        false,
                        "change_path");
                result.escalate("block");
            }

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

        if (tirithSecurityService != null) {
            TirithSecurityService.ScanResult scan =
                    tirithSecurityService.checkCommandSecurityForTool(effectiveTool, command);
            addTirith(result, scan);
        }

        result.finish();
        return result;
    }

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

    private AuditResult auditPath(String path, boolean writeLike) {
        AuditResult result = new AuditResult("path");
        result.path = SecretRedactor.redact(StrUtil.nullToEmpty(path).trim(), 400);
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
                    result.success = false;
                    result.decision = "error";
                    result.summary = "argsJson must be a JSON object";
                    return result;
                }
            } catch (Exception e) {
                result.success = false;
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
        if (securityPolicyService != null) {
            SecurityPolicyService.FileVerdict fileVerdict =
                    securityPolicyService.checkFileToolArgs(effectiveTool, args);
            if (!fileVerdict.isAllowed()) {
                result.addFinding(
                        "file_policy",
                        "blocked_path",
                        "critical",
                        fileVerdict.getMessage()
                                + ": "
                                + SecretRedactor.redact(fileVerdict.getPath(), 400),
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

    private static class AuditResult {
        private final String action;
        private boolean success = true;
        private String decision = "allow";
        private String summary = "";
        private String toolName;
        private String commandPreview;
        private String url;
        private String path;
        private Boolean writeLike;
        private String tirithAction;
        private Map<String, Object> policy;
        private final List<Map<String, Object>> findings = new ArrayList<Map<String, Object>>();
        private boolean blocking;
        private boolean approvalRequired;

        private AuditResult(String action) {
            this.action = action;
        }

        private void addFinding(String source, String ruleId, String severity, String message) {
            addFinding(source, ruleId, severity, message, severity, false, false, "");
        }

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

        private void escalate(String candidate) {
            if ("block".equals(candidate)) {
                decision = "block";
            } else if ("warn".equals(candidate) && !"block".equals(decision)) {
                decision = "warn";
            }
        }

        private void finish() {
            if (StrUtil.isBlank(summary)) {
                if (findings.isEmpty()) {
                    summary = "No security issues detected.";
                } else {
                    summary = decision + ": " + findings.size() + " security finding(s).";
                }
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("success", Boolean.valueOf(success));
            map.put("action", action);
            map.put("decision", decision);
            map.put("blocking", Boolean.valueOf(blocking));
            map.put("approval_required", Boolean.valueOf(approvalRequired));
            map.put("summary", summary);
            if (StrUtil.isNotBlank(toolName)) {
                map.put("toolName", toolName);
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

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static void addSurface(List<String> surfaces, String name, boolean enabled) {
        if (enabled) {
            surfaces.add(name);
        }
    }

    private static String normalizeApprovalMode(String value) {
        String mode = StrUtil.blankToDefault(value, "on").trim().toLowerCase(Locale.ROOT);
        if ("false".equals(mode)) {
            return "off";
        }
        if ("true".equals(mode)) {
            return "on";
        }
        if ("off".equals(mode) || "smart".equals(mode)) {
            return mode;
        }
        return "on";
    }

    private static String normalizeCronApprovalMode(String value) {
        String mode = StrUtil.blankToDefault(value, "deny").trim().toLowerCase(Locale.ROOT);
        return "approve".equals(mode)
                        || "off".equals(mode)
                        || "allow".equals(mode)
                        || "yes".equals(mode)
                ? "approve"
                : "deny";
    }
}
