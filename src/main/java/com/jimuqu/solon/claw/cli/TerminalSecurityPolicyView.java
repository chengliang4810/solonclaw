package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
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
        if ("paths".equals(mode)) {
            return renderPathPolicy(securityPolicyService.pathPolicySummary());
        }
        if ("credentials".equals(mode)) {
            return renderCredentialPolicy(securityPolicyService.credentialPolicySummary());
        }
        if ("tool-args".equals(mode)) {
            return renderToolArgsPolicy(securityPolicyService.toolArgsPolicySummary());
        }
        if ("mcp".equals(mode)) {
            return renderMcpPolicy(McpRuntimeService.policySummary(config));
        }
        if ("schema".equals(mode)) {
            return renderSchemaPolicy(SolonClawToolSchemaSanitizer.policySummary());
        }
        if ("attachments".equals(mode)) {
            return renderAttachmentPolicy(BoundedAttachmentIO.policySummary());
        }
        if ("tool-results".equals(mode)) {
            return renderToolResultPolicy(toolResultStorageSummary(config));
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
        if (rest.startsWith("path")) {
            return "paths";
        }
        if (rest.startsWith("credential") || rest.startsWith("secret")) {
            return "credentials";
        }
        if (rest.startsWith("tool-arg") || rest.startsWith("tools")) {
            return "tool-args";
        }
        if (rest.startsWith("mcp")) {
            return "mcp";
        }
        if (rest.startsWith("schema")) {
            return "schema";
        }
        if (rest.startsWith("attachment") || rest.startsWith("media")) {
            return "attachments";
        }
        if (rest.startsWith("tool-result") || rest.startsWith("result")) {
            return "tool-results";
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
                .append("可用命令：/security audit、/security policy、/security approvals、/security urls、/security paths、/security credentials、/security tool-args");
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
                .append(value(path, "writeDeniedPrefixCount"));
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

    private static String renderToolArgsPolicy(Map<String, Object> toolArgs) {
        StringBuilder buffer = new StringBuilder("工具参数安全策略摘要：");
        buffer.append('\n')
                .append("- URL 提取：recursive=")
                .append(value(toolArgs, "recursiveUrlExtraction"))
                .append(" returnedContent=")
                .append(value(toolArgs, "returnedContentUrlExtraction"))
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
                .append(" persistedRedacted=")
                .append(value(toolResults, "persistedOutputRedacted"))
                .append(" rawSaved=")
                .append(value(toolResults, "fullOutputSavedRaw"));
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

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "-" : String.valueOf(value);
    }
}
