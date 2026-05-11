package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.util.Map;

/** Read-only security policy summary for local terminal commands. */
public final class TerminalSecurityPolicyView {
    private TerminalSecurityPolicyView() {}

    public static boolean isSecurityCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(java.util.Locale.ROOT);
        return "/security".equals(value) || value.startsWith("/security ");
    }

    public static String render(AppConfig appConfig, String input) {
        AppConfig config = appConfig == null ? new AppConfig() : appConfig;
        SecurityPolicyService securityPolicyService = new SecurityPolicyService(config);
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(null, config, securityPolicyService);
        String mode = mode(input);
        if ("urls".equals(mode)) {
            return renderUrlPolicy(securityPolicyService.urlPolicySummary());
        }
        if ("approvals".equals(mode)) {
            return renderApprovalPolicy(approvalService.approvalPolicySummary());
        }
        if ("policy".equals(mode)) {
            return renderPolicy(securityPolicyService, approvalService, config);
        }
        return renderAudit(securityPolicyService, approvalService, config);
    }

    private static String mode(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(java.util.Locale.ROOT);
        if (value.length() <= "/security".length()) {
            return "audit";
        }
        String rest = value.substring("/security".length()).trim();
        if (rest.startsWith("url")) {
            return "urls";
        }
        if (rest.startsWith("approval")) {
            return "approvals";
        }
        if (rest.startsWith("policy")) {
            return "policy";
        }
        return "audit";
    }

    private static String renderAudit(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            AppConfig config) {
        StringBuilder buffer = new StringBuilder("安全审计摘要：");
        appendApprovalLine(buffer, approvalService.approvalPolicySummary());
        appendUrlLine(buffer, securityPolicyService.urlPolicySummary());
        Map<String, Object> path = securityPolicyService.pathPolicySummary();
        buffer.append('\n')
                .append("- 路径策略：traversal=")
                .append(value(path, "traversalBlocked"))
                .append(" devicePath=")
                .append(value(path, "devicePathBlocked"))
                .append(" writeSafeRoot=")
                .append(value(path, "writeSafeRootConfigured"));
        Map<String, Object> toolArgs = securityPolicyService.toolArgsPolicySummary();
        buffer.append('\n')
                .append("- 工具参数：recursiveUrl=")
                .append(value(toolArgs, "recursiveUrlExtraction"))
                .append(" patchTarget=")
                .append(value(toolArgs, "patchTargetExtraction"))
                .append(" unsupportedScheme=")
                .append(value(toolArgs, "unsupportedNetworkSchemeChecked"));
        Map<String, Object> tirith = new TirithSecurityService(config).policySummary();
        buffer.append('\n')
                .append("- 安全拦截：enabled=")
                .append(value(tirith, "enabled"))
                .append(" promptGuard=")
                .append(value(tirith, "promptInjectionGuardEnabled"));
        buffer.append('\n')
                .append("可用命令：/security audit、/security policy、/security approvals、/security urls");
        return buffer.toString();
    }

    private static String renderPolicy(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            AppConfig config) {
        StringBuilder buffer = new StringBuilder("安全策略摘要：");
        appendApprovalLine(buffer, approvalService.approvalPolicySummary());
        appendUrlLine(buffer, securityPolicyService.urlPolicySummary());
        Map<String, Object> credential = securityPolicyService.credentialPolicySummary();
        buffer.append('\n')
                .append("- 凭据文件：names=")
                .append(value(credential, "fileNameCount"))
                .append(" suffixes=")
                .append(value(credential, "pathSuffixCount"))
                .append(" configured=")
                .append(value(credential, "configuredCredentialFileCount"));
        Map<String, Object> tirith = new TirithSecurityService(config).policySummary();
        buffer.append('\n')
                .append("- Tirith：enabled=")
                .append(value(tirith, "enabled"))
                .append(" blockedPatternCount=")
                .append(value(tirith, "blockedPatternCount"));
        return buffer.toString();
    }

    private static String renderApprovalPolicy(Map<String, Object> approval) {
        StringBuilder buffer = new StringBuilder("审批策略摘要：");
        appendApprovalLine(buffer, approval);
        buffer.append('\n')
                .append("- 云存储规则：")
                .append(value(approval, "cloudStorageRuleSamples"));
        buffer.append('\n')
                .append("- 终端护栏：")
                .append(value(approval, "terminalGuardrails"));
        return buffer.toString();
    }

    private static String renderUrlPolicy(Map<String, Object> url) {
        StringBuilder buffer = new StringBuilder("URL 安全策略摘要：");
        appendUrlLine(buffer, url);
        buffer.append('\n')
                .append("- 允许 scheme：")
                .append(value(url, "allowedNetworkSchemes"));
        buffer.append('\n')
                .append("- 敏感参数：encoded=")
                .append(value(url, "encodedSensitiveQueryBlocked"))
                .append(" repeated=")
                .append(value(url, "repeatedEncodedSensitiveQueryBlocked"))
                .append(" semicolon=")
                .append(value(url, "semicolonSensitiveQueryBlocked"));
        return buffer.toString();
    }

    private static void appendApprovalLine(StringBuilder buffer, Map<String, Object> approval) {
        buffer.append('\n')
                .append("- 审批：mode=")
                .append(value(approval, "mode"))
                .append(" cron=")
                .append(value(approval, "cronMode"))
                .append(" dangerousRules=")
                .append(value(approval, "dangerousRuleCount"))
                .append(" hardlineRules=")
                .append(value(approval, "hardlineRuleCount"));
    }

    private static void appendUrlLine(StringBuilder buffer, Map<String, Object> url) {
        buffer.append('\n')
                .append("- URL：privateAllowed=")
                .append(value(url, "allowPrivateUrls"))
                .append(" metadataBlocked=")
                .append(value(url, "cloudMetadataBlocked"))
                .append(" unsupportedSchemeBlocked=")
                .append(value(url, "unsupportedNetworkSchemeBlocked"))
                .append(" dnsRequired=")
                .append(value(url, "dnsResolutionRequired"));
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "-" : String.valueOf(value);
    }
}
