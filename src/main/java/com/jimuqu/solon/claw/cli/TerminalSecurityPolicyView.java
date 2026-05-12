package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.context.SkillCredentialFileService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
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
        if ("private-urls".equals(mode)) {
            return renderPrivateUrlPolicy(securityPolicyService.privateUrlPolicySummary());
        }
        if ("website".equals(mode)) {
            return renderWebsitePolicy(securityPolicyService.websitePolicySummary());
        }
        if ("approvals".equals(mode)) {
            return renderApprovalPolicy(approvalService.approvalPolicySummary());
        }
        if ("slash-confirm".equals(mode)) {
            return renderSlashConfirmPolicy(approvalService.slashConfirmPolicySummary());
        }
        if ("approval-card".equals(mode)) {
            return renderApprovalCardPolicy(approvalService.approvalCardPolicySummary());
        }
        if ("approval-audit".equals(mode)) {
            return renderApprovalAuditPolicy(approvalService.approvalAuditPolicySummary());
        }
        if ("mcp-reload".equals(mode)) {
            return renderMcpReloadApprovalPolicy(approvalService.mcpReloadPolicySummary());
        }
        if ("lifecycle".equals(mode)) {
            return renderApprovalLifecyclePolicy(approvalService.approvalLifecyclePolicySummary());
        }
        if ("hardline".equals(mode)) {
            return renderHardlinePolicy(approvalService.hardlinePolicySummary());
        }
        if ("terminal-guardrails".equals(mode)) {
            return renderTerminalGuardrailPolicy(approvalService.terminalGuardrailPolicySummary());
        }
        if ("tirith".equals(mode)) {
            return renderTirithPolicy(new TirithSecurityService(config).policySummary());
        }
        if ("tirith-approval".equals(mode)) {
            return renderTirithApprovalPolicy(approvalService.tirithApprovalPolicySummary());
        }
        if ("cron-approvals".equals(mode)) {
            return renderCronApprovalPolicy(approvalService.cronApprovalPolicySummary());
        }
        if ("subagent-approvals".equals(mode)) {
            return renderSubagentApprovalPolicy(approvalService.subagentApprovalPolicySummary());
        }
        if ("smart-approval".equals(mode)) {
            return renderSmartApprovalPolicy(approvalService.smartApprovalPolicySummary());
        }
        if ("paths".equals(mode)) {
            return renderPathPolicy(securityPolicyService.pathPolicySummary());
        }
        if ("credentials".equals(mode)) {
            return renderCredentialPolicy(securityPolicyService.credentialPolicySummary());
        }
        if ("skill-credentials".equals(mode)) {
            return renderSkillCredentialPolicy(new SkillCredentialFileService(config).policySummary());
        }
        if ("tool-args".equals(mode)) {
            return renderToolArgsPolicy(securityPolicyService.toolArgsPolicySummary());
        }
        if ("mcp".equals(mode)) {
            return renderMcpPolicy(McpRuntimeService.policySummary(config));
        }
        if ("mcp-oauth".equals(mode)) {
            return renderMcpOAuthPolicy(DashboardMcpService.oauthPolicySummary());
        }
        if ("mcp-package".equals(mode)) {
            return renderMcpPackagePolicy(new McpPackageSecurityService(null).policySummary());
        }
        if ("audit-tool".equals(mode)) {
            return renderAuditToolPolicy(SecurityAuditTools.readOnlyAuditPolicySummary());
        }
        if ("schema".equals(mode)) {
            return renderSchemaPolicy(SolonClawToolSchemaSanitizer.policySummary());
        }
        if ("attachments".equals(mode)) {
            return renderAttachmentPolicy(BoundedAttachmentIO.policySummary());
        }
        if ("terminal-paste".equals(mode)) {
            return renderTerminalPastePolicy(CliAttachmentResolver.policySummary());
        }
        if ("media-cache".equals(mode)) {
            return renderMediaCachePolicy(new AttachmentCacheService(config).policySummary());
        }
        if ("tool-results".equals(mode)) {
            return renderToolResultPolicy(toolResultStorageSummary(config));
        }
        if ("patch".equals(mode)) {
            return renderPatchPolicy(SolonClawPatchTools.patchParserPolicySummary());
        }
        if ("code-execution".equals(mode)) {
            return renderCodeExecutionPolicy(SolonClawCodeExecutionSkills.codeExecutionPolicySummary(config));
        }
        if ("subprocess-env".equals(mode)) {
            return renderSubprocessEnvPolicy(SubprocessEnvironmentSanitizer.policySummary(config));
        }
        if ("terminal-output".equals(mode)) {
            return renderTerminalOutputPolicy(SolonClawShellSkill.terminalOutputPolicySummary(config));
        }
        if ("sudo".equals(mode)) {
            return renderSudoPolicy(SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured(config)));
        }
        if ("process".equals(mode)) {
            return renderProcessPolicy(ProcessTools.backgroundProcessPolicySummary(config));
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
        if (rest.startsWith("private-url") || rest.startsWith("ssrf")) {
            return "private-urls";
        }
        if (rest.startsWith("website") || rest.startsWith("site")) {
            return "website";
        }
        if (rest.startsWith("slash-confirm") || rest.startsWith("confirm")) {
            return "slash-confirm";
        }
        if (rest.startsWith("approval-card") || rest.startsWith("card-approval")) {
            return "approval-card";
        }
        if (rest.startsWith("approval-audit") || rest.startsWith("audit-log")) {
            return "approval-audit";
        }
        if (rest.startsWith("mcp-reload")) {
            return "mcp-reload";
        }
        if (rest.startsWith("approval")) {
            return "approvals";
        }
        if (rest.startsWith("lifecycle") || rest.startsWith("approval-lifecycle")) {
            return "lifecycle";
        }
        if (rest.startsWith("hardline")) {
            return "hardline";
        }
        if (rest.startsWith("terminal-guard")) {
            return "terminal-guardrails";
        }
        if (rest.startsWith("tirith-approval")) {
            return "tirith-approval";
        }
        if (rest.startsWith("cron-approval") || rest.startsWith("cron-approve")) {
            return "cron-approvals";
        }
        if (rest.startsWith("subagent-approval") || rest.startsWith("subagent-approve")) {
            return "subagent-approvals";
        }
        if (rest.startsWith("smart-approval") || rest.startsWith("smart-approve")) {
            return "smart-approval";
        }
        if (rest.startsWith("tirith")) {
            return "tirith";
        }
        if (rest.startsWith("path")) {
            return "paths";
        }
        if (rest.startsWith("skill-credential") || rest.startsWith("skill-secret")) {
            return "skill-credentials";
        }
        if (rest.startsWith("credential") || rest.startsWith("secret")) {
            return "credentials";
        }
        if (rest.startsWith("tool-arg") || rest.startsWith("tools")) {
            return "tool-args";
        }
        if (rest.startsWith("mcp-oauth")) {
            return "mcp-oauth";
        }
        if (rest.startsWith("mcp-package") || rest.startsWith("mcp-osv")) {
            return "mcp-package";
        }
        if (rest.startsWith("mcp")) {
            return "mcp";
        }
        if (rest.startsWith("audit-tool") || rest.startsWith("security-audit-tool")) {
            return "audit-tool";
        }
        if (rest.startsWith("schema")) {
            return "schema";
        }
        if (rest.startsWith("terminal-paste") || rest.startsWith("paste") || rest.startsWith("local-attachment")) {
            return "terminal-paste";
        }
        if (rest.startsWith("media-cache") || rest.startsWith("cache-media")) {
            return "media-cache";
        }
        if (rest.startsWith("attachment") || rest.startsWith("download")) {
            return "attachments";
        }
        if (rest.startsWith("tool-result") || rest.startsWith("result")) {
            return "tool-results";
        }
        if (rest.startsWith("patch")) {
            return "patch";
        }
        if (rest.startsWith("code") || rest.startsWith("execute-code") || rest.startsWith("sandbox")) {
            return "code-execution";
        }
        if (rest.startsWith("subprocess") || rest.startsWith("env")) {
            return "subprocess-env";
        }
        if (rest.startsWith("terminal-output") || rest.startsWith("output")) {
            return "terminal-output";
        }
        if (rest.startsWith("sudo")) {
            return "sudo";
        }
        if (rest.startsWith("process") || rest.startsWith("background")) {
            return "process";
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
        Map<String, Object> privateUrl = securityPolicyService.privateUrlPolicySummary();
        buffer.append('\n')
                .append("- 私有 URL：allow=")
                .append(value(privateUrl, "allowPrivateUrls"))
                .append(" metadataBlocked=")
                .append(value(privateUrl, "cloudMetadataAlwaysBlocked"))
                .append(" loopbackBlocked=")
                .append(value(privateUrl, "loopbackBlocked"));
        Map<String, Object> website = securityPolicyService.websitePolicySummary();
        buffer.append('\n')
                .append("- 网站策略：enabled=")
                .append(value(website, "enabled"))
                .append(" domains=")
                .append(value(website, "configuredDomainCount"))
                .append(" sharedRules=")
                .append(value(website, "sharedRuleCount"));
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
        Map<String, Object> hardline = approvalService.hardlinePolicySummary();
        buffer.append('\n')
                .append("- 硬阻断：rules=")
                .append(value(hardline, "ruleCount"))
                .append(" bypass=")
                .append(value(hardline, "approvalBypassAllowed"));
        Map<String, Object> guardrail = approvalService.terminalGuardrailPolicySummary();
        buffer.append('\n')
                .append("- 终端护栏：longLived=")
                .append(value(guardrail, "longLivedForegroundBlocked"))
                .append(" managedRequired=")
                .append(value(guardrail, "managedBackgroundProcessRequired"));
        buffer.append('\n')
                .append(
                        "可用命令：/security audit、/security policy、/security audit-tool、/security approvals、/security slash-confirm、/security approval-card、/security approval-audit、/security mcp-reload、/security lifecycle、/security hardline、/security terminal-guardrails、/security tirith、/security tirith-approval、/security cron-approvals、/security subagent-approvals、/security smart-approval、/security urls、/security private-urls、/security website、/security paths、/security credentials、/security skill-credentials、/security tool-args、/security mcp、/security mcp-oauth、/security mcp-package、/security schema、/security attachments、/security terminal-paste、/security media-cache、/security tool-results、/security patch、/security code-execution、/security subprocess-env、/security terminal-output、/security sudo、/security process");
        return buffer.toString();
    }

    private static String renderPolicy(
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            AppConfig config) {
        StringBuilder buffer = new StringBuilder("安全策略摘要：");
        appendApprovalLine(buffer, approvalService.approvalPolicySummary());
        appendUrlLine(buffer, securityPolicyService.urlPolicySummary());
        Map<String, Object> privateUrl = securityPolicyService.privateUrlPolicySummary();
        buffer.append('\n')
                .append("- 私有 URL：allow=")
                .append(value(privateUrl, "allowPrivateUrls"))
                .append(" dnsRequired=")
                .append(value(privateUrl, "dnsResolutionRequired"))
                .append(" metadataBlocked=")
                .append(value(privateUrl, "cloudMetadataAlwaysBlocked"));
        Map<String, Object> website = securityPolicyService.websitePolicySummary();
        buffer.append('\n')
                .append("- 网站策略：enabled=")
                .append(value(website, "enabled"))
                .append(" wildcard=")
                .append(value(website, "wildcardSubdomainSupported"))
                .append(" pathSafe=")
                .append(value(website, "sharedFilePathSafetyChecked"));
        Map<String, Object> credential = securityPolicyService.credentialPolicySummary();
        buffer.append('\n')
                .append("- 凭据文件：names=")
                .append(value(credential, "fileNameCount"))
                .append(" suffixes=")
                .append(value(credential, "pathSuffixCount"))
                .append(" configured=")
                .append(value(credential, "configuredCredentialFileCount"));
        Map<String, Object> skillCredential = new SkillCredentialFileService(config).policySummary();
        buffer.append('\n')
                .append("- 技能凭据：configured=")
                .append(value(skillCredential, "configCredentialFileCount"))
                .append(" mounted=")
                .append(value(skillCredential, "configuredMountCount"))
                .append(" traversalRejected=")
                .append(value(skillCredential, "pathTraversalRejected"));
        Map<String, Object> tirith = new TirithSecurityService(config).policySummary();
        buffer.append('\n')
                .append("- Tirith：enabled=")
                .append(value(tirith, "enabled"))
                .append(" available=")
                .append(value(tirith, "available"))
                .append(" failOpen=")
                .append(value(tirith, "failOpen"));
        Map<String, Object> slash = approvalService.slashConfirmPolicySummary();
        buffer.append('\n')
                .append("- Slash 确认：approveAll=")
                .append(value(slash, "approveAllSupported"))
                .append(" denyAll=")
                .append(value(slash, "denyAllSupported"))
                .append(" scopes=")
                .append(value(slash, "scopes"));
        Map<String, Object> card = approvalService.approvalCardPolicySummary();
        buffer.append('\n')
                .append("- 审批卡：mode=")
                .append(value(card, "deliveryMode"))
                .append(" platforms=")
                .append(value(card, "supportedPlatforms"))
                .append(" redacted=")
                .append(value(card, "commandPreviewRedacted"));
        Map<String, Object> audit = approvalService.approvalAuditPolicySummary();
        buffer.append('\n')
                .append("- 审批审计：request=")
                .append(value(audit, "requestEvents"))
                .append(" response=")
                .append(value(audit, "responseEvents"))
                .append(" keyRedacted=")
                .append(value(audit, "approvalKeyRedacted"));
        Map<String, Object> mcpReload = approvalService.mcpReloadPolicySummary();
        buffer.append('\n')
                .append("- MCP 重载审批：confirm=")
                .append(value(mcpReload, "confirmRequired"))
                .append(" persistentDisable=")
                .append(value(mcpReload, "persistentDisableSupported"))
                .append(" toolNotice=")
                .append(value(mcpReload, "toolChangeNoticeInjected"));
        Map<String, Object> oauth = DashboardMcpService.oauthPolicySummary();
        buffer.append('\n')
                .append("- MCP OAuth：authUrlSafe=")
                .append(value(oauth, "authorizationEndpointUrlSafety"))
                .append(" pkce=")
                .append(value(oauth, "pkceS256Required"))
                .append(" tokenRedacted=")
                .append(value(oauth, "accessTokenRedacted"));
        Map<String, Object> mcpPackage = new McpPackageSecurityService(null).policySummary();
        buffer.append('\n')
                .append("- MCP 包安全：launchers=")
                .append(value(mcpPackage, "checkedLaunchers"))
                .append(" malwareBlocks=")
                .append(value(mcpPackage, "malwareBlocksSaveAndCheck"))
                .append(" npxPackageOption=")
                .append(value(mcpPackage, "npxPackageOptionParsed"))
                .append(" pypiSourceOption=")
                .append(value(mcpPackage, "pypiSourceOptionParsed"))
                .append(" persistedReason=")
                .append(value(mcpPackage, "persistedListReasonExposed"))
                .append(" failOpen=")
                .append(value(mcpPackage, "requestFailureFailsOpen"));
        Map<String, Object> auditTool = SecurityAuditTools.readOnlyAuditPolicySummary();
        buffer.append('\n')
                .append("- 审计工具：executesCommand=")
                .append(value(auditTool, "executesCommand"))
                .append(" writesFile=")
                .append(value(auditTool, "writesFile"))
                .append(" redacted=")
                .append(value(auditTool, "secretRedactionApplied"));
        Map<String, Object> lifecycle = approvalService.approvalLifecyclePolicySummary();
        buffer.append('\n')
                .append("- 审批生命周期：approveAll=")
                .append(value(lifecycle, "approveAllSupported"))
                .append(" clearAll=")
                .append(value(lifecycle, "clearAllSupported"))
                .append(" tirithDowngrade=")
                .append(value(lifecycle, "tirithAlwaysScopeDowngradedToSession"));
        Map<String, Object> cron = approvalService.cronApprovalPolicySummary();
        buffer.append('\n')
                .append("- Cron 审批：mode=")
                .append(value(cron, "mode"))
                .append(" default=")
                .append(value(cron, "defaultDecision"))
                .append(" scriptChecked=")
                .append(value(cron, "scriptContentChecked"));
        Map<String, Object> subagent = approvalService.subagentApprovalPolicySummary();
        buffer.append('\n')
                .append("- 子 Agent 审批：default=")
                .append(value(subagent, "defaultDecision"))
                .append(" humanPromptSuppressed=")
                .append(value(subagent, "humanApprovalPromptSuppressed"))
                .append(" pendingCreated=")
                .append(value(subagent, "pendingApprovalCreatedWhenDenied"));
        Map<String, Object> smart = approvalService.smartApprovalPolicySummary();
        buffer.append('\n')
                .append("- 智能审批：active=")
                .append(value(smart, "active"))
                .append(" escalateHuman=")
                .append(value(smart, "escalateFallsBackToHumanApproval"))
                .append(" tirithFindings=")
                .append(value(smart, "tirithFindingsIncluded"));
        Map<String, Object> hardline = approvalService.hardlinePolicySummary();
        buffer.append('\n')
                .append("- 硬阻断：rules=")
                .append(value(hardline, "ruleCount"))
                .append(" bypass=")
                .append(value(hardline, "approvalBypassAllowed"))
                .append(" decision=")
                .append(value(hardline, "blockingDecision"));
        Map<String, Object> guardrail = approvalService.terminalGuardrailPolicySummary();
        buffer.append('\n')
                .append("- 终端护栏：backgroundBlocked=")
                .append(value(guardrail, "inlineAmpersandBlocked"))
                .append(" longLived=")
                .append(value(guardrail, "longLivedForegroundBlocked"))
                .append(" managedRequired=")
                .append(value(guardrail, "managedBackgroundProcessRequired"));
        appendExtendedPolicyLines(buffer, config);
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
        buffer.append('\n')
                .append("- 凭据处理：")
                .append(value(approval, "credentialHandlingRuleSamples"));
        buffer.append('\n')
                .append("- Secret 存储：")
                .append(value(approval, "secretStoreRuleSamples"));
        return buffer.toString();
    }

    private static String renderSlashConfirmPolicy(Map<String, Object> slash) {
        StringBuilder buffer = new StringBuilder("Slash 确认策略摘要：");
        buffer.append('\n')
                .append("- 命令：commands=")
                .append(value(slash, "commands"))
                .append(" scopes=")
                .append(value(slash, "scopes"))
                .append(" default=")
                .append(value(slash, "defaultScope"));
        buffer.append('\n')
                .append("- 批量：approveAll=")
                .append(value(slash, "approveAllSupported"))
                .append(" denyAll=")
                .append(value(slash, "denyAllSupported"))
                .append(" pendingQueue=")
                .append(value(slash, "pendingQueueSupported"));
        buffer.append('\n')
                .append("- 脱敏：approvalKeyHidden=")
                .append(value(slash, "pendingListHidesApprovalKey"))
                .append(" commandRedacted=")
                .append(value(slash, "commandPreviewRedacted"))
                .append(" metadataRedacted=")
                .append(value(slash, "approvalMetadataRedacted"));
        return buffer.toString();
    }

    private static String renderApprovalCardPolicy(Map<String, Object> card) {
        StringBuilder buffer = new StringBuilder("审批卡策略摘要：");
        buffer.append('\n')
                .append("- 投递：mode=")
                .append(value(card, "deliveryMode"))
                .append(" platforms=")
                .append(value(card, "supportedPlatforms"))
                .append(" unsupportedEmpty=")
                .append(value(card, "unsupportedPlatformsReturnEmptyExtras"));
        buffer.append('\n')
                .append("- 动作：approve=")
                .append(value(card, "approveAction"))
                .append(" deny=")
                .append(value(card, "denyAction"))
                .append(" scopes=")
                .append(value(card, "scopeOptions"))
                .append(" default=")
                .append(value(card, "defaultScope"));
        buffer.append('\n')
                .append("- 选择器：idSelector=")
                .append(value(card, "approvalIdSelectorSupported"))
                .append(" unsafeRejected=")
                .append(value(card, "unsafeSelectorRejected"))
                .append(" outboundSanitized=")
                .append(value(card, "outboundApprovalIdSanitized"));
        buffer.append('\n')
                .append("- 脱敏：command=")
                .append(value(card, "commandPreviewRedacted"))
                .append(" description=")
                .append(value(card, "descriptionPreviewRedacted"))
                .append(" rawCommand=")
                .append(value(card, "rawCommandRedactedInExtras"));
        return buffer.toString();
    }

    private static String renderApprovalAuditPolicy(Map<String, Object> audit) {
        StringBuilder buffer = new StringBuilder("审批审计策略摘要：");
        buffer.append('\n')
                .append("- 事件：request=")
                .append(value(audit, "requestEvents"))
                .append(" response=")
                .append(value(audit, "responseEvents"))
                .append(" observers=")
                .append(value(audit, "observerCount"))
                .append(" isolated=")
                .append(value(audit, "observerFailureIsolated"));
        buffer.append('\n')
                .append("- 存储：repositoryBacked=")
                .append(value(audit, "repositoryBackedWhenConfigured"))
                .append(" commandHash=")
                .append(value(audit, "commandHashStored"))
                .append(" patternKeys=")
                .append(value(audit, "patternKeysStored"))
                .append(" timestamps=")
                .append(value(audit, "timestampsStored"));
        buffer.append('\n')
                .append("- 脱敏：approver=")
                .append(value(audit, "approverRedacted"))
                .append(" command=")
                .append(value(audit, "commandPreviewRedacted"))
                .append(" approvalKey=")
                .append(value(audit, "approvalKeyRedacted"));
        buffer.append('\n')
                .append("- 查询：recentDashboard=")
                .append(value(audit, "recentDashboardViewSupported"))
                .append(" revocationAudited=")
                .append(value(audit, "manualRevocationAudited"));
        return buffer.toString();
    }

    private static String renderMcpReloadApprovalPolicy(Map<String, Object> reload) {
        StringBuilder buffer = new StringBuilder("MCP 重载审批策略摘要：");
        buffer.append('\n')
                .append("- 命令：command=")
                .append(value(reload, "command"))
                .append(" confirmRequired=")
                .append(value(reload, "confirmRequired"))
                .append(" configKey=")
                .append(value(reload, "configKey"));
        buffer.append('\n')
                .append("- 确认：slashConfirm=")
                .append(value(reload, "slashConfirmBacked"))
                .append(" directAlias=")
                .append(value(reload, "directRunAlias"))
                .append(" alwaysAlias=")
                .append(value(reload, "alwaysConfirmAlias"))
                .append(" persisted=")
                .append(value(reload, "runtimeConfigPersisted"));
        buffer.append('\n')
                .append("- 变更：toolNotice=")
                .append(value(reload, "toolChangeNoticeInjected"))
                .append(" serverSummary=")
                .append(value(reload, "changedServerSummary"))
                .append(" toolCount=")
                .append(value(reload, "toolCountSummary"));
        buffer.append('\n')
                .append("- 安全：oauthUrlSafe=")
                .append(value(reload, "oauthUrlSafetyCovered"))
                .append(" encodedRedacted=")
                .append(value(reload, "encodedUrlParameterRedacted"))
                .append(" historyRedacted=")
                .append(value(reload, "reloadHistoryNoticeRedacted"));
        return buffer.toString();
    }

    private static String renderApprovalLifecyclePolicy(Map<String, Object> lifecycle) {
        StringBuilder buffer = new StringBuilder("审批生命周期策略摘要：");
        buffer.append('\n')
                .append("- 查询：list=")
                .append(value(lifecycle, "listSupported"))
                .append(" statusAlias=")
                .append(value(lifecycle, "statusAliasSupported"))
                .append(" safeSelector=")
                .append(value(lifecycle, "selectorSupported"));
        buffer.append('\n')
                .append("- 批量：approveAll=")
                .append(value(lifecycle, "approveAllSupported"))
                .append(" rejectAll=")
                .append(value(lifecycle, "rejectAllSupported"))
                .append(" bulkSafe=")
                .append(value(lifecycle, "bulkRejectUsesSafeSelector"));
        buffer.append('\n')
                .append("- 清理：clearSession=")
                .append(value(lifecycle, "clearSessionSupported"))
                .append(" clearAlways=")
                .append(value(lifecycle, "clearAlwaysSupported"))
                .append(" clearAll=")
                .append(value(lifecycle, "clearAllSupported"));
        buffer.append('\n')
                .append("- 范围：scopes=")
                .append(value(lifecycle, "scopes"))
                .append(" alwaysGlobal=")
                .append(value(lifecycle, "alwaysScopeUsesGlobalSettings"))
                .append(" tirithDowngrade=")
                .append(value(lifecycle, "tirithAlwaysScopeDowngradedToSession"));
        buffer.append('\n')
                .append("- 事件：snapshotUpdated=")
                .append(value(lifecycle, "sessionSnapshotUpdated"))
                .append(" requestObserved=")
                .append(value(lifecycle, "approvalRequestObserved"))
                .append(" responseObserved=")
                .append(value(lifecycle, "approvalResponseObserved"));
        buffer.append('\n')
                .append("- 脱敏：approver=")
                .append(value(lifecycle, "approverRedacted"))
                .append(" approvalKey=")
                .append(value(lifecycle, "approvalKeyRedacted"))
                .append(" command=")
                .append(value(lifecycle, "commandPreviewRedacted"));
        return buffer.toString();
    }

    private static String renderHardlinePolicy(Map<String, Object> hardline) {
        StringBuilder buffer = new StringBuilder("硬阻断命令策略摘要：");
        buffer.append('\n')
                .append("- 规则：count=")
                .append(value(hardline, "ruleCount"))
                .append(" categories=")
                .append(value(hardline, "blockedCategories"));
        buffer.append('\n')
                .append("- 覆盖：tools=")
                .append(value(hardline, "coveredTools"))
                .append(" metadataUrlBlocked=")
                .append(value(hardline, "metadataUrlBlocked"));
        buffer.append('\n')
                .append("- 绕过：approval=")
                .append(value(hardline, "approvalBypassAllowed"))
                .append(" slash=")
                .append(value(hardline, "slashApproveBypassAllowed"))
                .append(" yolo=")
                .append(value(hardline, "yoloBypassAllowed"));
        return buffer.toString();
    }

    private static String renderTerminalGuardrailPolicy(Map<String, Object> guardrail) {
        StringBuilder buffer = new StringBuilder("终端护栏策略摘要：");
        buffer.append('\n')
                .append("- 后台：wrappers=")
                .append(value(guardrail, "backgroundShellWrappersBlocked"))
                .append(" inlineAmpersand=")
                .append(value(guardrail, "inlineAmpersandBlocked"))
                .append(" trailingAmpersand=")
                .append(value(guardrail, "trailingAmpersandBlocked"));
        buffer.append('\n')
                .append("- 长任务：longLivedBlocked=")
                .append(value(guardrail, "longLivedForegroundBlocked"))
                .append(" patterns=")
                .append(value(guardrail, "longLivedForegroundPatternCount"))
                .append(" managedRequired=")
                .append(value(guardrail, "managedBackgroundProcessRequired"));
        buffer.append('\n')
                .append("- 预检：commandPath=")
                .append(value(guardrail, "commandPathPrechecked"))
                .append(" credentialPath=")
                .append(value(guardrail, "credentialPathPrechecked"))
                .append(" proxyUrl=")
                .append(value(guardrail, "proxyUrlPrechecked"))
                .append(" systemDns=")
                .append(value(guardrail, "systemDnsCommandPrechecked"))
                .append(" systemProxy=")
                .append(value(guardrail, "systemProxyCommandPrechecked"));
        return buffer.toString();
    }

    private static String renderTirithPolicy(Map<String, Object> tirith) {
        StringBuilder buffer = new StringBuilder("Tirith 安全策略摘要：");
        buffer.append('\n')
                .append("- 状态：enabled=")
                .append(value(tirith, "enabled"))
                .append(" configured=")
                .append(value(tirith, "configured"))
                .append(" available=")
                .append(value(tirith, "available"));
        buffer.append('\n')
                .append("- 执行：jsonMode=")
                .append(value(tirith, "jsonOutputMode"))
                .append(" envSanitized=")
                .append(value(tirith, "subprocessEnvironmentSanitized"))
                .append(" timeoutKillsProcess=")
                .append(value(tirith, "timeoutKillsProcess"));
        buffer.append('\n')
                .append("- 决策：actions=")
                .append(value(tirith, "actions"))
                .append(" failOpenMode=")
                .append(value(tirith, "failOpenMode"))
                .append(" redaction=")
                .append(value(tirith, "secretRedaction"));
        return buffer.toString();
    }

    private static String renderTirithApprovalPolicy(Map<String, Object> tirith) {
        StringBuilder buffer = new StringBuilder("Tirith 审批策略摘要：");
        buffer.append('\n')
                .append("- 扫描：configured=")
                .append(value(tirith, "scannerConfigured"))
                .append(" inApprovalMode=")
                .append(value(tirith, "scanRunsInApprovalMode"))
                .append(" prefix=")
                .append(value(tirith, "patternKeyPrefix"));
        buffer.append('\n')
                .append("- 合并：findingsAsKeys=")
                .append(value(tirith, "findingsBecomePatternKeys"))
                .append(" localRules=")
                .append(value(tirith, "combinedWithLocalDangerRules"))
                .append(" smartSessionOnly=")
                .append(value(tirith, "smartApprovalCanApproveSessionOnly"));
        buffer.append('\n')
                .append("- 范围：permanentAllowed=")
                .append(value(tirith, "permanentApprovalAllowed"))
                .append(" alwaysDowngraded=")
                .append(value(tirith, "alwaysScopeDowngradedToSession"))
                .append(" descriptionRedacted=")
                .append(value(tirith, "descriptionRedacted"));
        return buffer.toString();
    }

    private static String renderCronApprovalPolicy(Map<String, Object> cron) {
        StringBuilder buffer = new StringBuilder("Cron 审批策略摘要：");
        buffer.append('\n')
                .append("- 决策：mode=")
                .append(value(cron, "mode"))
                .append(" default=")
                .append(value(cron, "defaultDecision"))
                .append(" autoApproveDangerous=")
                .append(value(cron, "autoApproveDangerousCommands"));
        buffer.append('\n')
                .append("- 预检：dangerousChecked=")
                .append(value(cron, "dangerousPatternCheckedBeforeRun"))
                .append(" hardlineBlocked=")
                .append(value(cron, "hardlineAlwaysBlocked"))
                .append(" scriptChecked=")
                .append(value(cron, "scriptContentChecked"));
        buffer.append('\n')
                .append("- 配置：keys=")
                .append(value(cron, "configKeys"))
                .append(" approveAliases=")
                .append(value(cron, "approveAliases"))
                .append(" denyAliases=")
                .append(value(cron, "denyAliases"));
        return buffer.toString();
    }

    private static String renderSubagentApprovalPolicy(Map<String, Object> subagent) {
        StringBuilder buffer = new StringBuilder("子 Agent 审批策略摘要：");
        buffer.append('\n')
                .append("- 决策：default=")
                .append(value(subagent, "defaultDecision"))
                .append(" autoApproveDangerous=")
                .append(value(subagent, "autoApproveDangerousCommands"))
                .append(" config=")
                .append(value(subagent, "configKey"));
        buffer.append('\n')
                .append("- 预检：hardline=")
                .append(value(subagent, "hardlinePrechecked"))
                .append(" file=")
                .append(value(subagent, "filePolicyPrechecked"))
                .append(" url=")
                .append(value(subagent, "urlPolicyPrechecked"))
                .append(" terminal=")
                .append(value(subagent, "terminalGuardrailPrechecked"));
        buffer.append('\n')
                .append("- 行为：humanPromptSuppressed=")
                .append(value(subagent, "humanApprovalPromptSuppressed"))
                .append(" pendingCreated=")
                .append(value(subagent, "pendingApprovalCreatedWhenDenied"))
                .append(" smartBefore=")
                .append(value(subagent, "smartApprovalRunsBeforeSubagentPolicy"));
        return buffer.toString();
    }

    private static String renderSmartApprovalPolicy(Map<String, Object> smart) {
        StringBuilder buffer = new StringBuilder("智能审批策略摘要：");
        buffer.append('\n')
                .append("- 状态：mode=")
                .append(value(smart, "mode"))
                .append(" judgeConfigured=")
                .append(value(smart, "judgeConfigured"))
                .append(" active=")
                .append(value(smart, "active"));
        buffer.append('\n')
                .append("- 决策：types=")
                .append(value(smart, "decisionTypes"))
                .append(" escalateHuman=")
                .append(value(smart, "escalateFallsBackToHumanApproval"))
                .append(" denyBlocks=")
                .append(value(smart, "denyBlocksExecution"));
        buffer.append('\n')
                .append("- 预检：hardline=")
                .append(value(smart, "hardlinePrechecked"))
                .append(" file=")
                .append(value(smart, "filePolicyPrechecked"))
                .append(" url=")
                .append(value(smart, "urlPolicyPrechecked"))
                .append(" terminal=")
                .append(value(smart, "terminalGuardrailPrechecked"));
        buffer.append('\n')
                .append("- 输出：tirithFindings=")
                .append(value(smart, "tirithFindingsIncluded"))
                .append(" sessionApproval=")
                .append(value(smart, "approveWritesSessionApproval"))
                .append(" commandRedacted=")
                .append(value(smart, "commandPreviewRedacted"));
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

    private static String renderPrivateUrlPolicy(Map<String, Object> url) {
        StringBuilder buffer = new StringBuilder("私有 URL 安全策略摘要：");
        buffer.append('\n')
                .append("- 开关：allowPrivate=")
                .append(value(url, "allowPrivateUrls"))
                .append(" env=")
                .append(value(url, "environmentOverrideName"))
                .append(" metadataAlwaysBlocked=")
                .append(value(url, "cloudMetadataAlwaysBlocked"));
        buffer.append('\n')
                .append("- 解析：dnsRequired=")
                .append(value(url, "dnsResolutionRequired"))
                .append(" obfuscatedIpv4=")
                .append(value(url, "obfuscatedIpv4Checked"))
                .append(" mappedIpv6=")
                .append(value(url, "ipv4MappedIpv6Checked"));
        buffer.append('\n')
                .append("- 阻断：loopback=")
                .append(value(url, "loopbackBlocked"))
                .append(" linkLocal=")
                .append(value(url, "linkLocalBlocked"))
                .append(" siteLocal=")
                .append(value(url, "siteLocalBlocked"));
        return buffer.toString();
    }

    private static String renderWebsitePolicy(Map<String, Object> website) {
        StringBuilder buffer = new StringBuilder("网站策略摘要：");
        buffer.append('\n')
                .append("- 配置：enabled=")
                .append(value(website, "enabled"))
                .append(" domains=")
                .append(value(website, "configuredDomainCount"))
                .append(" sharedFiles=")
                .append(value(website, "sharedFileCount"));
        buffer.append('\n')
                .append("- 共享规则：loadedFiles=")
                .append(value(website, "loadedSharedFileCount"))
                .append(" skippedFiles=")
                .append(value(website, "skippedSharedFileCount"))
                .append(" rules=")
                .append(value(website, "sharedRuleCount"));
        buffer.append('\n')
                .append("- 匹配：normalized=")
                .append(value(website, "hostRuleNormalization"))
                .append(" wildcard=")
                .append(value(website, "wildcardSubdomainSupported"))
                .append(" pathSafe=")
                .append(value(website, "sharedFilePathSafetyChecked"));
        return buffer.toString();
    }

    private static String renderPathPolicy(Map<String, Object> path) {
        StringBuilder buffer = new StringBuilder("路径安全策略摘要：");
        buffer.append('\n')
                .append("- 基础阻断：traversal=")
                .append(value(path, "traversalBlocked"))
                .append(" controlChars=")
                .append(value(path, "controlCharactersBlocked"))
                .append(" devicePath=")
                .append(value(path, "devicePathBlocked"));
        buffer.append('\n')
                .append("- 写入边界：writeSafeRoot=")
                .append(value(path, "writeSafeRootConfigured"))
                .append(" exactDenied=")
                .append(value(path, "writeDeniedExactPathCount"))
                .append(" prefixDenied=")
                .append(value(path, "writeDeniedPrefixCount"))
                .append(" windowsPrefixDenied=")
                .append(value(path, "writeDeniedWindowsPrefixCount"));
        buffer.append('\n')
                .append("- 本地管理端点：socket=")
                .append(value(path, "localManagementSocketAccessBlocked"))
                .append(" pipe=")
                .append(value(path, "localManagementPipeAccessBlocked"));
        return buffer.toString();
    }

    private static String renderCredentialPolicy(Map<String, Object> credential) {
        StringBuilder buffer = new StringBuilder("凭据文件策略摘要：");
        buffer.append('\n')
                .append("- 文件名：count=")
                .append(value(credential, "fileNameCount"))
                .append(" samples=")
                .append(value(credential, "fileNameSamples"));
        buffer.append('\n')
                .append("- 目录和后缀：directorySegments=")
                .append(value(credential, "directorySegmentCount"))
                .append(" suffixes=")
                .append(value(credential, "pathSuffixCount"));
        buffer.append('\n')
                .append("- 配置项：configuredFiles=")
                .append(value(credential, "configuredCredentialFileCount"))
                .append(" envExamplesAllowed=")
                .append(value(credential, "envExampleFilesAllowed"));
        return buffer.toString();
    }

    private static String renderSkillCredentialPolicy(Map<String, Object> skillCredential) {
        StringBuilder buffer = new StringBuilder("技能凭据文件安全策略摘要：");
        buffer.append('\n')
                .append("- 配置：configCount=")
                .append(value(skillCredential, "configCredentialFileCount"))
                .append(" mounted=")
                .append(value(skillCredential, "configuredMountCount"))
                .append(" missing=")
                .append(value(skillCredential, "configuredMissingCount"))
                .append(" rejected=")
                .append(value(skillCredential, "configuredRejectedCount"));
        buffer.append('\n')
                .append("- 沙箱：credentialMounts=")
                .append(value(skillCredential, "sandboxCredentialMountCount"))
                .append(" skillsDirs=")
                .append(value(skillCredential, "skillsDirectoryMountCount"))
                .append(" cacheDirs=")
                .append(value(skillCredential, "cacheDirectoryMountCount"))
                .append(" base=")
                .append(value(skillCredential, "defaultContainerBase"));
        buffer.append('\n')
                .append("- 路径：relativeOnly=")
                .append(value(skillCredential, "runtimeRelativeOnly"))
                .append(" absoluteRejected=")
                .append(value(skillCredential, "absolutePathRejected"))
                .append(" traversalRejected=")
                .append(value(skillCredential, "pathTraversalRejected"))
                .append(" escapeRejected=")
                .append(value(skillCredential, "runtimeHomeEscapeRejected"));
        buffer.append('\n')
                .append("- 脱敏：hostPathsHidden=")
                .append(value(skillCredential, "hostPathsOmittedFromMetadata"))
                .append(" rejectedRedacted=")
                .append(value(skillCredential, "rejectedPathsRedacted"))
                .append(" frontmatter=")
                .append(value(skillCredential, "skillFrontmatterKey"))
                .append(" configKey=")
                .append(value(skillCredential, "configKey"));
        return buffer.toString();
    }

    private static String renderToolArgsPolicy(Map<String, Object> toolArgs) {
        StringBuilder buffer = new StringBuilder("工具参数安全策略摘要：");
        buffer.append('\n')
                .append("- URL 提取：recursive=")
                .append(value(toolArgs, "recursiveUrlExtraction"))
                .append(" returnedContent=")
                .append(value(toolArgs, "returnedContentUrlExtraction"))
                .append(" returnedSchemeless=")
                .append(value(toolArgs, "returnedSchemelessUrlChecked"))
                .append(" unsupportedScheme=")
                .append(value(toolArgs, "unsupportedNetworkSchemeChecked"));
        buffer.append('\n')
                .append("- 路径和写入：recursivePath=")
                .append(value(toolArgs, "recursivePathExtraction"))
                .append(" writeIntent=")
                .append(value(toolArgs, "writeIntentDetection"))
                .append(" patchTarget=")
                .append(value(toolArgs, "patchTargetExtraction"));
        buffer.append('\n')
                .append("- 样例：urlKeys=")
                .append(value(toolArgs, "urlKeySamples"))
                .append(" pathKeys=")
                .append(value(toolArgs, "pathKeySamples"));
        return buffer.toString();
    }

    private static String renderMcpPolicy(Map<String, Object> mcp) {
        StringBuilder buffer = new StringBuilder("MCP 安全策略摘要：");
        buffer.append('\n')
                .append("- 传输：enabled=")
                .append(value(mcp, "enabled"))
                .append(" supported=")
                .append(value(mcp, "supportedTransports"))
                .append(" boundedExecutor=")
                .append(value(mcp, "toolCallExecutorBounded"));
        buffer.append('\n')
                .append("- 远端安全：endpointUrl=")
                .append(value(mcp, "remoteEndpointUrlSafety"))
                .append(" toolArgsUrl=")
                .append(value(mcp, "remoteToolArgumentUrlSafety"))
                .append(" toolArgsPath=")
                .append(value(mcp, "remoteToolArgumentPathSafety"));
        buffer.append('\n')
                .append("- OAuth：structuredReauth=")
                .append(value(mcp, "oauthFailureStructuredReauth"))
                .append(" secretsRedacted=")
                .append(value(mcp, "oauthSecretsRedacted"));
        return buffer.toString();
    }

    private static String renderMcpOAuthPolicy(Map<String, Object> oauth) {
        StringBuilder buffer = new StringBuilder("MCP OAuth 安全策略摘要：");
        buffer.append('\n')
                .append("- URL：authorization=")
                .append(value(oauth, "authorizationEndpointUrlSafety"))
                .append(" token=")
                .append(value(oauth, "tokenEndpointUrlSafety"))
                .append(" redirect=")
                .append(value(oauth, "tokenEndpointRedirectUrlSafety"))
                .append(" maxRedirects=")
                .append(value(oauth, "tokenEndpointRedirectLimit"));
        buffer.append('\n')
                .append("- 授权：stateRequired=")
                .append(value(oauth, "stateValidationRequired"))
                .append(" pkceS256=")
                .append(value(oauth, "pkceS256Required"))
                .append(" verifierHidden=")
                .append(value(oauth, "codeVerifierHiddenFromStatus"));
        buffer.append('\n')
                .append("- 密钥：accessRedacted=")
                .append(value(oauth, "accessTokenRedacted"))
                .append(" refreshRedacted=")
                .append(value(oauth, "refreshTokenRedacted"))
                .append(" clientSecretRedacted=")
                .append(value(oauth, "clientSecretRedacted"))
                .append(" statusFields=")
                .append(value(oauth, "statusPresenceFields"));
        buffer.append('\n')
                .append("- 错误和重授权：callbackRedacted=")
                .append(value(oauth, "callbackErrorsRedacted"))
                .append(" tokenErrorsRedacted=")
                .append(value(oauth, "tokenErrorsRedacted"))
                .append(" handle401=")
                .append(value(oauth, "handle401RefreshThenReauth"));
        return buffer.toString();
    }

    private static String renderMcpPackagePolicy(Map<String, Object> mcpPackage) {
        StringBuilder buffer = new StringBuilder("MCP 包安全策略摘要：");
        buffer.append('\n')
                .append("- 范围：transport=")
                .append(value(mcpPackage, "enabledForTransport"))
                .append(" launchers=")
                .append(value(mcpPackage, "checkedLaunchers"))
                .append(" ecosystems=")
                .append(value(mcpPackage, "supportedEcosystems"));
        buffer.append('\n')
                .append("- OSV：endpointSafe=")
                .append(value(mcpPackage, "endpointUrlSafetyChecked"))
                .append(" env=")
                .append(value(mcpPackage, "endpointOverrideEnvironment"))
                .append(" unsafeBlocksBeforeNetwork=")
                .append(value(mcpPackage, "unsafeEndpointBlocksBeforeNetwork"));
        buffer.append('\n')
                .append("- 判定：malwarePrefix=")
                .append(value(mcpPackage, "malwareAdvisoryPrefix"))
                .append(" ignoreNonMalware=")
                .append(value(mcpPackage, "nonMalwareVulnerabilitiesIgnored"))
                .append(" malwareBlocks=")
                .append(value(mcpPackage, "malwareBlocksSaveAndCheck"))
                .append(" failOpen=")
                .append(value(mcpPackage, "requestFailureFailsOpen"));
        buffer.append('\n')
                .append("- 结构化原因：reasons=")
                .append(value(mcpPackage, "structuredReasons"))
                .append(" listReason=")
                .append(value(mcpPackage, "persistedListReasonExposed"));
        buffer.append('\n')
                .append("- 解析和脱敏：versionParsed=")
                .append(value(mcpPackage, "packageVersionParsed"))
                .append(" npxPackageOption=")
                .append(value(mcpPackage, "npxPackageOptionParsed"))
                .append(" pypiSourceOption=")
                .append(value(mcpPackage, "pypiSourceOptionParsed"))
                .append(" pipxRunSkipped=")
                .append(value(mcpPackage, "pipxRunSubcommandSkipped"))
                .append(" jsonArgs=")
                .append(value(mcpPackage, "jsonArgsSupported"))
                .append(" messageRedacted=")
                .append(value(mcpPackage, "messageRedacted"))
                .append(" endpointRedacted=")
                .append(value(mcpPackage, "endpointRedacted"));
        return buffer.toString();
    }

    private static String renderAuditToolPolicy(Map<String, Object> auditTool) {
        StringBuilder buffer = new StringBuilder("安全审计工具策略摘要：");
        buffer.append('\n')
                .append("- 工具：name=")
                .append(value(auditTool, "toolName"))
                .append(" actions=")
                .append(value(auditTool, "supportsActions"));
        buffer.append('\n')
                .append("- 只读：executesCommand=")
                .append(value(auditTool, "executesCommand"))
                .append(" network=")
                .append(value(auditTool, "opensNetworkConnection"))
                .append(" writesFile=")
                .append(value(auditTool, "writesFile"))
                .append(" storesInput=")
                .append(value(auditTool, "storesAuditInput"));
        buffer.append('\n')
                .append("- 继承策略：command=")
                .append(value(auditTool, "toolArgsCommandPolicyInherited"))
                .append(" url=")
                .append(value(auditTool, "toolArgsUrlPolicyInherited"))
                .append(" path=")
                .append(value(auditTool, "toolArgsPathPolicyInherited"));
        buffer.append('\n')
                .append("- 输出：secretRedaction=")
                .append(value(auditTool, "secretRedactionApplied"))
                .append(" parseErrorsRedacted=")
                .append(value(auditTool, "toolArgsJsonParseErrorsRedacted"))
                .append(" commandPreviewLimit=")
                .append(value(auditTool, "commandPreviewLimitChars"))
                .append(" findingLimit=")
                .append(value(auditTool, "findingMessageLimitChars"));
        return buffer.toString();
    }

    private static String renderSchemaPolicy(Map<String, Object> schema) {
        StringBuilder buffer = new StringBuilder("工具 schema 安全策略摘要：");
        buffer.append('\n')
                .append("- 清洗：enabled=")
                .append(value(schema, "enabled"))
                .append(" inputSchema=")
                .append(value(schema, "inputSchemaSanitized"))
                .append(" topLevelObject=")
                .append(value(schema, "topLevelObjectRequired"));
        buffer.append('\n')
                .append("- 兼容性：nullableUnionCollapsed=")
                .append(value(schema, "nullableUnionCollapsed"))
                .append(" patternAndFormatStripped=")
                .append(value(schema, "patternAndFormatStripped"))
                .append(" jsonLibrary=")
                .append(value(schema, "jsonLibrary"));
        return buffer.toString();
    }

    private static String renderAttachmentPolicy(Map<String, Object> attachment) {
        StringBuilder buffer = new StringBuilder("附件下载安全策略摘要：");
        buffer.append('\n')
                .append("- 下载边界：initialUrl=")
                .append(value(attachment, "initialUrlChecked"))
                .append(" redirectUrl=")
                .append(value(attachment, "redirectUrlCheckedBeforeFollow"))
                .append(" maxRedirects=")
                .append(value(attachment, "maxRedirects"));
        buffer.append('\n')
                .append("- 内容边界：contentLength=")
                .append(value(attachment, "contentLengthChecked"))
                .append(" streamBounded=")
                .append(value(attachment, "streamReadBounded"))
                .append(" defaultMaxBytes=")
                .append(value(attachment, "defaultMaxBytes"));
        return buffer.toString();
    }

    private static String renderToolResultPolicy(Map<String, Object> toolResults) {
        StringBuilder buffer = new StringBuilder("工具输出安全策略摘要：");
        buffer.append('\n')
                .append("- 存储：oversizedPersisted=")
                .append(value(toolResults, "oversizedResultsPersisted"))
                .append(" turnBudgetPersisted=")
                .append(value(toolResults, "turnBudgetOverflowPersisted"))
                .append(" resultRef=")
                .append(value(toolResults, "resultRefReturned"));
        buffer.append('\n')
                .append("- 脱敏：previewRedacted=")
                .append(value(toolResults, "previewRedacted"))
                .append(" pinnedPreviewRedacted=")
                .append(value(toolResults, "pinnedInlinePreviewRedacted"))
                .append(" persistedRedacted=")
                .append(value(toolResults, "persistedOutputRedacted"))
                .append(" rawSaved=")
                .append(value(toolResults, "fullOutputSavedRaw"));
        buffer.append('\n')
                .append("- 例外：pinnedRawInline=")
                .append(value(toolResults, "pinnedInlineRawObservationAllowed"))
                .append(" pinnedTools=")
                .append(value(toolResults, "pinnedInlineTools"));
        buffer.append('\n')
                .append("- 降级：previewOnlyFallback=")
                .append(value(toolResults, "storageFailureFallsBackToPreviewOnly"))
                .append(" describeBlock=")
                .append(value(toolResults, "describePersistedObservation"));
        return buffer.toString();
    }

    private static String renderTerminalPastePolicy(Map<String, Object> paste) {
        StringBuilder buffer = new StringBuilder("终端粘贴附件安全策略摘要：");
        buffer.append('\n')
                .append("- 路径识别：pastedLocalPath=")
                .append(value(paste, "pastedLocalPathDetection"))
                .append(" fileUri=")
                .append(value(paste, "fileUriDetection"))
                .append(" windowsPath=")
                .append(value(paste, "windowsPathDetection"))
                .append(" posixPath=")
                .append(value(paste, "posixPathDetection"));
        buffer.append('\n')
                .append("- 安全检查：pathPolicyBeforeCache=")
                .append(value(paste, "pathPolicyCheckedBeforeCache"))
                .append(" credentialBlocked=")
                .append(value(paste, "credentialPathBlocked"))
                .append(" rawPathHidden=")
                .append(value(paste, "rawPathHiddenInPrompt"));
        buffer.append('\n')
                .append("- 边界：maxPaths=")
                .append(value(paste, "maxAttachmentPaths"))
                .append(" maxBytes=")
                .append(value(paste, "maxAttachmentBytes"))
                .append(" previewRedacted=")
                .append(value(paste, "blockedPreviewRedacted"));
        return buffer.toString();
    }

    private static String renderMediaCachePolicy(Map<String, Object> cache) {
        StringBuilder buffer = new StringBuilder("媒体缓存安全策略摘要：");
        buffer.append('\n')
                .append("- 引用边界：prefix=")
                .append(value(cache, "mediaReferencePrefix"))
                .append(" rootRequired=")
                .append(value(cache, "mediaReferenceRequiresMediaRoot"))
                .append(" traversalBlocked=")
                .append(value(cache, "mediaReferenceTraversalBlocked"));
        buffer.append('\n')
                .append("- 缓存边界：sizeChecked=")
                .append(value(cache, "cacheBytesSizeChecked"))
                .append(" maxBytes=")
                .append(value(cache, "maxCacheBytes"))
                .append(" mimeSniffing=")
                .append(value(cache, "mimeSniffingEnabled"));
        buffer.append('\n')
                .append("- 脱敏：safeName=")
                .append(value(cache, "safeOriginalNameSanitized"))
                .append(" nameRedacted=")
                .append(value(cache, "safeOriginalNameSecretRedacted"))
                .append(" hostPathHidden=")
                .append(value(cache, "hostPathsNotReturnedInMediaReference"));
        return buffer.toString();
    }

    private static String renderPatchPolicy(Map<String, Object> patch) {
        StringBuilder buffer = new StringBuilder("补丁工具安全策略摘要：");
        buffer.append('\n')
                .append("- 格式：enabled=")
                .append(value(patch, "enabled"))
                .append(" format=")
                .append(value(patch, "patchFormat"))
                .append(" markersRequired=")
                .append(value(patch, "beginEndMarkersRequired"));
        buffer.append('\n')
                .append("- 原子性：atomicValidation=")
                .append(value(patch, "atomicValidationBeforeWrite"))
                .append(" noPartialWrites=")
                .append(value(patch, "noPartialWritesOnValidationFailure"))
                .append(" uniqueReplace=")
                .append(value(patch, "replaceRequiresUniqueMatchByDefault"));
        buffer.append('\n')
                .append("- 路径：pathTraversalBlocked=")
                .append(value(patch, "pathTraversalBlocked"))
                .append(" symlinkEscapeBlocked=")
                .append(value(patch, "symlinkEscapeBlocked"))
                .append(" credentialPrechecked=")
                .append(value(patch, "credentialPolicyPrechecked"));
        return buffer.toString();
    }

    private static String renderCodeExecutionPolicy(Map<String, Object> code) {
        StringBuilder buffer = new StringBuilder("代码执行安全策略摘要：");
        buffer.append('\n')
                .append("- 能力：executeCode=")
                .append(value(code, "executeCodeSupported"))
                .append(" python=")
                .append(value(code, "executePythonSupported"))
                .append(" js=")
                .append(value(code, "executeJsSupported"));
        buffer.append('\n')
                .append("- 预检：pathPolicy=")
                .append(value(code, "scriptPreflightPathPolicy"))
                .append(" urlPolicy=")
                .append(value(code, "scriptPreflightUrlPolicy"))
                .append(" dangerousRules=")
                .append(value(code, "dangerousCommandRulesApplied"));
        buffer.append('\n')
                .append("- 沙箱：stagingPerRun=")
                .append(value(code, "stagingDirectoryPerRun"))
                .append(" envSanitized=")
                .append(value(code, "sandboxEnvironmentSanitized"))
                .append(" timeoutKillsProcess=")
                .append(value(code, "timeoutKillsProcess"));
        return buffer.toString();
    }

    private static String renderSubprocessEnvPolicy(Map<String, Object> env) {
        StringBuilder buffer = new StringBuilder("子进程环境安全策略摘要：");
        buffer.append('\n')
                .append("- 默认：enabled=")
                .append(value(env, "enabled"))
                .append(" defaultDenyUnknown=")
                .append(value(env, "defaultDenyUnknownEnv"))
                .append(" providerBlocklist=")
                .append(value(env, "providerBlocklistCount"));
        buffer.append('\n')
                .append("- 放行：safePrefixes=")
                .append(value(env, "safePrefixCount"))
                .append(" configuredPassthrough=")
                .append(value(env, "configuredPassthroughCount"))
                .append(" skillScoped=")
                .append(value(env, "skillScopedPassthroughSupported"));
        buffer.append('\n')
                .append("- 密钥：secretNamesBlocked=")
                .append(value(env, "secretNameSubstringsBlocked"))
                .append(" runtimeTogglesBlocked=")
                .append(value(env, "runtimeSafetyTogglesBlocked"))
                .append(" forcePrefix=")
                .append(value(env, "forcePrefix"));
        return buffer.toString();
    }

    private static String renderTerminalOutputPolicy(Map<String, Object> output) {
        StringBuilder buffer = new StringBuilder("终端输出安全策略摘要：");
        buffer.append('\n')
                .append("- 输出：ansiStripped=")
                .append(value(output, "ansiStripped"))
                .append(" redacted=")
                .append(value(output, "secretRedactionApplied"))
                .append(" maxInlineChars=")
                .append(value(output, "maxInlineChars"));
        buffer.append('\n')
                .append("- 截断：headTail=")
                .append(value(output, "headTailTruncation"))
                .append(" notice=")
                .append(value(output, "truncationNoticeIncluded"))
                .append(" timeoutNotice=")
                .append(value(output, "timeoutNoticeAppended"));
        buffer.append('\n')
                .append("- 语义：sudoHint=")
                .append(value(output, "sudoFailureHintAppended"))
                .append(" emptySuccess=")
                .append(value(output, "emptySuccessMessage"))
                .append(" exitCode=")
                .append(value(output, "exitCodeSemanticsAvailable"))
                .append(" retryErrors=")
                .append(value(output, "foregroundRetryErrorsInterpreted"));
        return buffer.toString();
    }

    private static String renderSudoPolicy(Map<String, Object> sudo) {
        StringBuilder buffer = new StringBuilder("sudo 改写安全策略摘要：");
        buffer.append('\n')
                .append("- 配置：configured=")
                .append(value(sudo, "configured"))
                .append(" configKey=")
                .append(value(sudo, "configKey"))
                .append(" envKey=")
                .append(value(sudo, "envKey"));
        buffer.append('\n')
                .append("- 改写：realSudo=")
                .append(value(sudo, "rewritesRealSudoInvocations"))
                .append(" stdinPassword=")
                .append(value(sudo, "stdinPasswordInjection"))
                .append(" passwordRedacted=")
                .append(value(sudo, "passwordRedacted"));
        buffer.append('\n')
                .append("- 边界：commentsIgnored=")
                .append(value(sudo, "commentsIgnored"))
                .append(" quotedIgnored=")
                .append(value(sudo, "quotedSudoIgnored"))
                .append(" ptyDisabled=")
                .append(value(sudo, "ptyDisabledForStdinPipe"));
        return buffer.toString();
    }

    private static String renderProcessPolicy(Map<String, Object> process) {
        StringBuilder buffer = new StringBuilder("后台进程安全策略摘要：");
        buffer.append('\n')
                .append("- 管理：actions=")
                .append(value(process, "actions"))
                .append(" registry=")
                .append(value(process, "processRegistryBacked"))
                .append(" stop=")
                .append(value(process, "stopSupported"));
        buffer.append('\n')
                .append("- 启动：dangerousChecked=")
                .append(value(process, "startDangerousCommandChecked"))
                .append(" hardlineBlocked=")
                .append(value(process, "startHardlineBlocked"))
                .append(" urlChecked=")
                .append(value(process, "startUrlPolicyChecked"));
        buffer.append('\n')
                .append("- 运行：outputRedacted=")
                .append(value(process, "outputRedacted"))
                .append(" timeoutClamped=")
                .append(value(process, "waitTimeoutClamped"))
                .append(" managedRequired=")
                .append(value(process, "managedBackgroundRequiredForLongRunningCommands"));
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

    private static void appendExtendedPolicyLines(StringBuilder buffer, AppConfig config) {
        Map<String, Object> mcp = McpRuntimeService.policySummary(config);
        buffer.append('\n')
                .append("- MCP：enabled=")
                .append(value(mcp, "enabled"))
                .append(" oauthReauth=")
                .append(value(mcp, "oauthFailureStructuredReauth"))
                .append(" schemaSanitized=")
                .append(value(mcp, "inputSchemaSanitized"));
        Map<String, Object> schema = SolonClawToolSchemaSanitizer.policySummary();
        buffer.append('\n')
                .append("- Tool schema：enabled=")
                .append(value(schema, "enabled"))
                .append(" unsupportedKeywordsStripped=")
                .append(value(schema, "unsupportedKeywordsStripped"))
                .append(" jsonLibrary=")
                .append(value(schema, "jsonLibrary"));
        Map<String, Object> attachment = BoundedAttachmentIO.policySummary();
        buffer.append('\n')
                .append("- 附件下载：redirectChecked=")
                .append(value(attachment, "redirectUrlCheckedBeforeFollow"))
                .append(" maxBytes=")
                .append(value(attachment, "defaultMaxBytes"))
                .append(" streamBounded=")
                .append(value(attachment, "streamReadBounded"));
        Map<String, Object> paste = CliAttachmentResolver.policySummary();
        buffer.append('\n')
                .append("- 终端粘贴：credentialBlocked=")
                .append(value(paste, "credentialPathBlocked"))
                .append(" rawPathHidden=")
                .append(value(paste, "rawPathHiddenInPrompt"))
                .append(" maxPaths=")
                .append(value(paste, "maxAttachmentPaths"));
        Map<String, Object> cache = new AttachmentCacheService(config).policySummary();
        buffer.append('\n')
                .append("- 媒体缓存：rootRequired=")
                .append(value(cache, "mediaReferenceRequiresMediaRoot"))
                .append(" traversalBlocked=")
                .append(value(cache, "mediaReferenceTraversalBlocked"))
                .append(" hostPathHidden=")
                .append(value(cache, "hostPathsNotReturnedInMediaReference"));
        Map<String, Object> code = SolonClawCodeExecutionSkills.codeExecutionPolicySummary(config);
        buffer.append('\n')
                .append("- 代码执行：pathPolicy=")
                .append(value(code, "scriptPreflightPathPolicy"))
                .append(" envSanitized=")
                .append(value(code, "sandboxEnvironmentSanitized"))
                .append(" timeoutKillsProcess=")
                .append(value(code, "timeoutKillsProcess"));
        Map<String, Object> process = ProcessTools.backgroundProcessPolicySummary(config);
        buffer.append('\n')
                .append("- 后台进程：dangerousChecked=")
                .append(value(process, "startDangerousCommandChecked"))
                .append(" outputRedacted=")
                .append(value(process, "outputRedacted"))
                .append(" managedRequired=")
                .append(value(process, "managedBackgroundRequiredForLongRunningCommands"));
        Map<String, Object> toolResults = toolResultStorageSummary(config);
        buffer.append('\n')
                .append("- 工具输出：persistOversize=")
                .append(value(toolResults, "oversizedResultsPersisted"))
                .append(" resultRef=")
                .append(value(toolResults, "resultRefReturned"))
                .append(" redacted=")
                .append(value(toolResults, "persistedOutputRedacted"));
    }

    private static Map<String, Object> toolResultStorageSummary(AppConfig config) {
        ToolResultStorageService storage =
                new ToolResultStorageService(
                        config.getRuntime().getCacheDir(),
                        config.getTask().getToolOutputInlineLimit(),
                        config.getTask().getToolOutputTurnBudget(),
                        config.getTrace().getToolPreviewLength());
        return storage.policySummary();
    }

    private static boolean sudoPasswordConfigured(AppConfig config) {
        return config != null
                && config.getTerminal() != null
                && StrUtil.isNotBlank(config.getTerminal().getSudoPassword());
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "-" : String.valueOf(value);
    }
}
