package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
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
        this.securityPolicyService = securityPolicyService;
        this.approvalService = approvalService;
        this.tirithSecurityService = tirithSecurityService;
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
        approvals.put("mode", StrUtil.nullToEmpty(appConfig.getApprovals().getMode()));
        approvals.put("cronMode", StrUtil.nullToEmpty(appConfig.getApprovals().getCronMode()));
        approvals.put("subagentAutoApprove", Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        approvals.put("timeoutSeconds", Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        approvals.put("gatewayTimeoutSeconds", Integer.valueOf(appConfig.getApprovals().getGatewayTimeoutSeconds()));
        approvals.put("mcpReloadConfirm", Boolean.valueOf(appConfig.getApprovals().isMcpReloadConfirm()));
        approvals.put(
                "alwaysApprovalCount",
                Integer.valueOf(approvalService == null ? 0 : approvalService.listAlwaysApprovals().size()));
        result.policy.put("approvals", approvals);

        Map<String, Object> security = new LinkedHashMap<String, Object>();
        security.put("allowPrivateUrls", Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        security.put("tirithEnabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        security.put("tirithConfigured", Boolean.valueOf(StrUtil.isNotBlank(appConfig.getSecurity().getTirithPath())));
        security.put("tirithTimeoutSeconds", Integer.valueOf(appConfig.getSecurity().getTirithTimeoutSeconds()));
        security.put("tirithFailOpen", Boolean.valueOf(appConfig.getSecurity().isTirithFailOpen()));
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
        terminal.put("envPassthroughCount", Integer.valueOf(size(appConfig.getTerminal().getEnvPassthrough())));
        terminal.put("sudoPasswordConfigured", Boolean.valueOf(StrUtil.isNotBlank(appConfig.getTerminal().getSudoPassword())));
        terminal.put("writeSafeRootConfigured", Boolean.valueOf(StrUtil.isNotBlank(appConfig.getTerminal().getWriteSafeRoot())));
        terminal.put(
                "maxForegroundTimeoutSeconds",
                Integer.valueOf(appConfig.getTerminal().getMaxForegroundTimeoutSeconds()));
        terminal.put("foregroundMaxRetries", Integer.valueOf(appConfig.getTerminal().getForegroundMaxRetries()));
        terminal.put(
                "foregroundRetryBaseDelaySeconds",
                Integer.valueOf(appConfig.getTerminal().getForegroundRetryBaseDelaySeconds()));
        result.policy.put("terminal", terminal);

        result.summary = "Security policy status is available without exposing secret values.";
        result.finish();
        return result;
    }

    private AuditResult auditCommand(String toolName, String command) {
        String effectiveTool =
                StrUtil.blankToDefault(toolName, ToolNameConstants.EXECUTE_SHELL).trim();
        AuditResult result = new AuditResult("command");
        result.toolName = effectiveTool;
        result.commandPreview = StrUtil.maxLength(StrUtil.nullToEmpty(command).trim(), 400);

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
                        hardline.getDescription());
                result.escalate("block");
            }

            String backgroundGuidance =
                    approvalService.foregroundBackgroundGuidance(effectiveTool, command);
            if (StrUtil.isNotBlank(backgroundGuidance)) {
                result.addFinding("terminal_guardrail", "background_process", "high", backgroundGuidance);
                result.escalate("block");
            }

            DangerousCommandApprovalService.DetectionResult local =
                    approvalService.detect(effectiveTool, command);
            if (local != null) {
                result.addFinding(
                        "dangerous_command",
                        local.getPatternKey(),
                        "medium",
                        local.getDescription());
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
                        fileVerdict.getMessage() + ": " + fileVerdict.getPath());
                result.escalate("block");
            }

            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkCommandUrls(command);
            if (!urlVerdict.isAllowed()) {
                result.addFinding(
                        "url_policy",
                        "blocked_url",
                        "critical",
                        urlVerdict.getMessage() + ": " + urlVerdict.getUrl());
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
        result.url = StrUtil.nullToEmpty(url).trim();
        if (securityPolicyService == null) {
            result.summary = "URL policy is unavailable";
            return result;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            result.addFinding("url_policy", "blocked_url", "critical", verdict.getMessage());
            result.escalate("block");
        }
        result.finish();
        return result;
    }

    private AuditResult auditPath(String path, boolean writeLike) {
        AuditResult result = new AuditResult("path");
        result.path = StrUtil.nullToEmpty(path).trim();
        result.writeLike = Boolean.valueOf(writeLike);
        if (securityPolicyService == null) {
            result.summary = "file policy is unavailable";
            return result;
        }
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkPath(path, writeLike);
        if (!verdict.isAllowed()) {
            result.addFinding("file_policy", "blocked_path", "critical", verdict.getMessage());
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
                result.summary = "argsJson parse failed: " + e.getMessage();
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
                        fileVerdict.getMessage() + ": " + fileVerdict.getPath());
                result.escalate("block");
            }
            SecurityPolicyService.UrlVerdict urlVerdict =
                    securityPolicyService.checkToolArgs(effectiveTool, args);
            if (!urlVerdict.isAllowed()) {
                result.addFinding(
                        "url_policy",
                        "blocked_url",
                        "critical",
                        urlVerdict.getMessage() + ": " + urlVerdict.getUrl());
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
            result.addFinding("tirith", "security_scan", scan.getAction(), scan.getSummary());
        }
        for (TirithSecurityService.Finding finding : scan.getFindings()) {
            result.addFinding(
                    "tirith",
                    StrUtil.blankToDefault(finding.getRuleId(), "security_scan"),
                    StrUtil.blankToDefault(finding.getSeverity(), scan.getAction()),
                    StrUtil.blankToDefault(
                            finding.getTitle(),
                            StrUtil.blankToDefault(finding.getDescription(), scan.getSummary())));
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

        private AuditResult(String action) {
            this.action = action;
        }

        private void addFinding(String source, String ruleId, String severity, String message) {
            Map<String, Object> finding = new LinkedHashMap<String, Object>();
            finding.put("source", StrUtil.nullToEmpty(source));
            finding.put("ruleId", StrUtil.nullToEmpty(ruleId));
            finding.put("severity", StrUtil.nullToEmpty(severity));
            finding.put("message", StrUtil.nullToEmpty(message));
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
}
