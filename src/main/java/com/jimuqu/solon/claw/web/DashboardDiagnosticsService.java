package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.context.SkillCredentialFileService;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Dashboard 统一诊断服务。 */
public class DashboardDiagnosticsService {
    private final AppConfig appConfig;
    private final DeliveryService deliveryService;
    private final LlmProviderService llmProviderService;
    private final ToolRegistry toolRegistry;
    private final SessionRepository sessionRepository;
    private final ConversationOrchestrator conversationOrchestrator;
    private final ApprovalAuditRepository approvalAuditRepository;
    private final SlashConfirmService slashConfirmService;
    private final CommandService commandService;
    private final DangerousCommandApprovalService approvalService;
    private final SecurityPolicyService securityPolicyService;
    private final TirithSecurityService tirithSecurityService;

    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
        this.llmProviderService = llmProviderService;
        this.toolRegistry = toolRegistry;
        this.sessionRepository = sessionRepository;
        this.conversationOrchestrator = conversationOrchestrator;
        this.approvalAuditRepository = approvalAuditRepository;
        this.slashConfirmService = slashConfirmService;
        this.commandService = commandService;
        this.approvalService = approvalService;
        this.securityPolicyService = securityPolicyService;
        this.tirithSecurityService = tirithSecurityService;
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runtime", runtime());
        result.put("providers", providers());
        result.put("channels", channels());
        result.put("tools", tools());
        result.put("mcp", mcp());
        result.put("security", security());
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> securityAudit(Map<String, Object> body) {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        SecurityAuditTools tools =
                new SecurityAuditTools(
                        securityPolicyService, approvalService, tirithSecurityService, appConfig);
        String result =
                tools.audit(
                        text(input, "action"),
                        text(input, "toolName"),
                        text(input, "command"),
                        text(input, "url"),
                        text(input, "path"),
                        bool(input, "writeLike"),
                        text(input, "argsJson"));
        Object data = ONode.ofJson(result).toData();
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("success", Boolean.FALSE);
        fallback.put("decision", "error");
        fallback.put("summary", "security audit result was not a JSON object");
        return fallback;
    }

    public Map<String, Object> pendingApprovals(int limit) throws Exception {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (sessionRepository == null || approvalService == null) {
            Map<String, Object> disabled = new LinkedHashMap<String, Object>();
            disabled.put("count", Integer.valueOf(0));
            disabled.put("items", items);
            return disabled;
        }

        for (SessionRecord session : sessionRepository.listRecent(effectiveLimit)) {
            List<DangerousCommandApprovalService.PendingApproval> pending =
                    approvalService.listPendingApprovals(session);
            for (DangerousCommandApprovalService.PendingApproval approval : pending) {
                items.add(pendingApprovalItem(session, approval));
                if (items.size() >= effectiveLimit) {
                    break;
                }
            }
            if (items.size() >= effectiveLimit) {
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> resolveApproval(Map<String, Object> body) throws Exception {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        String sessionId = text(input, "sessionId");
        String selector = StrUtil.blankToDefault(text(input, "approvalId"), text(input, "selector"));
        String action = StrUtil.nullToEmpty(text(input, "action")).trim().toLowerCase();
        boolean resume = !Boolean.FALSE.equals(bool(input, "resume"));
        String approver = StrUtil.blankToDefault(text(input, "approver"), "dashboard");
        DangerousCommandApprovalService.ApprovalScope scope = parseApprovalScope(text(input, "scope"));

        if (StrUtil.isBlank(sessionId)) {
            return resolveResult(false, "missing_session", "缺少会话 ID。", null);
        }
        if (!"approve".equals(action) && !"deny".equals(action)) {
            return resolveResult(false, "invalid_action", "审批动作必须是 approve 或 deny。", null);
        }

        SessionRecord session = sessionRepository.findById(sessionId);
        if (session == null) {
            return resolveResult(false, "session_not_found", "会话不存在或已删除。", null);
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        boolean changed;
        if ("approve".equals(action)) {
            changed = approvalService.approve(agentSession, selector, scope, approver);
        } else {
            changed = approvalService.reject(agentSession, selector, approver);
        }
        if (!changed) {
            return resolveResult(false, "approval_not_found", "待审批项不存在或已过期。", null);
        }

        GatewayReply reply = null;
        if (resume && StrUtil.isNotBlank(session.getSourceKey()) && conversationOrchestrator != null) {
            reply = conversationOrchestrator.resumePending(session.getSourceKey());
        }

        Map<String, Object> result =
                resolveResult(true, "ok", "审批状态已更新。", reply == null ? null : replyMap(reply));
        result.put("action", action);
        result.put("session_id", session.getSessionId());
        result.put("resumed", Boolean.valueOf(reply != null));
        return result;
    }

    public Map<String, Object> approvalHistory(int limit) throws Exception {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (approvalAuditRepository != null) {
            for (ApprovalAuditEvent event : approvalAuditRepository.listRecent(effectiveLimit)) {
                items.add(approvalAuditItem(event));
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> alwaysApprovals(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (approvalService != null) {
            for (String approval : approvalService.listAlwaysApprovals()) {
                items.add(alwaysApprovalItem(approval));
                if (items.size() >= effectiveLimit) {
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> revokeAlwaysApproval(Map<String, Object> body) throws Exception {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        String approval =
                resolveAlwaysApproval(StrUtil.blankToDefault(text(input, "approvalId"), text(input, "approval_id")));
        String approver = StrUtil.blankToDefault(text(input, "approver"), "dashboard");
        if (StrUtil.isBlank(approval)) {
            return resolveResult(false, "missing_approval", "缺少长期授权项。", null);
        }
        boolean changed = approvalService != null && approvalService.revokeAlwaysApproval(approval);
        if (!changed) {
            return resolveResult(false, "approval_not_found", "长期授权项不存在或已撤销。", null);
        }
        appendAlwaysApprovalRevokedAudit(approval, approver);
        return resolveResult(true, "ok", "长期授权已撤销。", null);
    }

    public Map<String, Object> pendingSlashConfirms(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (slashConfirmService != null) {
            for (SlashConfirmService.PendingConfirm pending : slashConfirmService.listPending()) {
                items.add(slashConfirmItem(pending));
                if (items.size() >= effectiveLimit) {
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(items.size()));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> resolveSlashConfirm(Map<String, Object> body) throws Exception {
        Map<String, Object> input = body == null ? Collections.<String, Object>emptyMap() : body;
        String sourceKey = text(input, "sourceKey");
        String confirmId = text(input, "confirmId");
        String action = StrUtil.nullToEmpty(text(input, "action")).trim().toLowerCase();
        if (StrUtil.isBlank(confirmId)) {
            return resolveResult(false, "missing_confirm_id", "缺少确认编号。", null);
        }
        if (!"approve".equals(action) && !"deny".equals(action) && !"always".equals(action)) {
            return resolveResult(false, "invalid_action", "确认动作必须是 approve、always 或 deny。", null);
        }
        SlashConfirmService.PendingConfirm pending = findPendingSlashConfirm(confirmId, sourceKey);
        if (pending == null) {
            return resolveResult(false, "confirm_not_found", "待确认 slash 命令不存在或已过期。", null);
        }
        if ("always".equals(action) && !pending.isAllowAlways()) {
            return resolveResult(false, "always_not_allowed", "该 Slash 命令不允许永久确认。", null);
        }

        sourceKey = pending.getSourceKey();
        String commandLine = slashConfirmCommandLine(action, pending.getConfirmId());
        GatewayReply reply = commandService.handle(dashboardMessage(sourceKey, commandLine), commandLine);
        Map<String, Object> result =
                resolveResult(!reply.isError(), reply.isError() ? "error" : "ok", reply.getContent(), replyMap(reply));
        result.put("action", action);
        result.put("confirm_id", pending.getConfirmId());
        result.put("confirm_ref", shortId(pending.getConfirmId()));
        return result;
    }

    private Map<String, Object> runtime() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("home", runtimeReference(appConfig.getRuntime().getHome()));
        map.put("state_db", runtimeReference(appConfig.getRuntime().getStateDb()));
        map.put("cache_dir", runtimeReference(appConfig.getRuntime().getCacheDir()));
        map.put("logs_dir", runtimeReference(appConfig.getRuntime().getLogsDir()));
        map.put("home_exists", new File(appConfig.getRuntime().getHome()).exists());
        map.put("state_parent_writable", canWriteParent(appConfig.getRuntime().getStateDb()));
        return map;
    }

    private List<Map<String, Object>> providers() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AppConfig.ProviderConfig> entry :
                llmProviderService.providers().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", entry.getKey());
            item.put("name", entry.getValue().getName());
            item.put("dialect", entry.getValue().getDialect());
            item.put("base_url", SecretRedactor.maskUrl(entry.getValue().getBaseUrl()));
            item.put("default_model", entry.getValue().getDefaultModel());
            item.put("has_api_key", StrUtil.isNotBlank(entry.getValue().getApiKey()));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> channels() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ChannelStatus status : deliveryService.statuses()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put(
                    "platform",
                    status.getPlatform() == null
                            ? null
                            : status.getPlatform().name().toLowerCase());
            item.put("enabled", status.isEnabled());
            item.put("connected", status.isConnected());
            item.put("setup_state", status.getSetupState());
            item.put("connection_mode", status.getConnectionMode());
            item.put(
                    "last_error_message",
                    SecretRedactor.redact(status.getLastErrorMessage(), 1000));
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> tools() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("count", toolRegistry.listToolNames().size());
        map.put("names", toolRegistry.listToolNames());
        return map;
    }

    private Map<String, Object> mcp() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", appConfig.getMcp().isEnabled());
        map.put("status", appConfig.getMcp().isEnabled() ? "enabled" : "disabled");
        return map;
    }

    private Map<String, Object> security() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        Map<String, Object> approvals = new LinkedHashMap<String, Object>();
        approvals.put("mode", StrUtil.nullToEmpty(appConfig.getApprovals().getMode()));
        approvals.put("cron_mode", StrUtil.nullToEmpty(appConfig.getApprovals().getCronMode()));
        approvals.put(
                "subagent_auto_approve",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        approvals.put(
                "timeout_seconds",
                Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        approvals.put(
                "gateway_timeout_seconds",
                Integer.valueOf(appConfig.getApprovals().getGatewayTimeoutSeconds()));
        approvals.put(
                "mcp_reload_confirm",
                Boolean.valueOf(appConfig.getApprovals().isMcpReloadConfirm()));
        approvals.put(
                "always_approval_count",
                Integer.valueOf(
                        approvalService == null
                                ? 0
                                : approvalService.listAlwaysApprovals().size()));
        map.put("approvals", approvals);

        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put(
                "allow_private_urls",
                Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        policy.put("tirith_enabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        policy.put(
                "tirith_configured",
                Boolean.valueOf(StrUtil.isNotBlank(appConfig.getSecurity().getTirithPath())));
        policy.put(
                "tirith_timeout_seconds",
                Integer.valueOf(appConfig.getSecurity().getTirithTimeoutSeconds()));
        policy.put(
                "tirith_fail_open",
                Boolean.valueOf(appConfig.getSecurity().isTirithFailOpen()));
        policy.put(
                "website_blocklist_enabled",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        policy.put(
                "website_blocklist_domain_count",
                Integer.valueOf(size(appConfig.getSecurity().getWebsiteBlocklist().getDomains())));
        policy.put(
                "website_blocklist_shared_file_count",
                Integer.valueOf(size(appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles())));
        policy.put("url_policy", safeUrlPolicySummary());
        policy.put("private_url_policy", safePrivateUrlPolicySummary());
        policy.put("website_policy", safeWebsitePolicySummary());
        map.put("policy", policy);

        Map<String, Object> terminal = new LinkedHashMap<String, Object>();
        terminal.put(
                "credential_file_count",
                Integer.valueOf(size(appConfig.getTerminal().getCredentialFiles())));
        terminal.put(
                "env_passthrough_count",
                Integer.valueOf(size(appConfig.getTerminal().getEnvPassthrough())));
        terminal.put(
                "sudo_password_configured",
                Boolean.valueOf(StrUtil.isNotBlank(appConfig.getTerminal().getSudoPassword())));
        terminal.put(
                "write_safe_root_configured",
                Boolean.valueOf(StrUtil.isNotBlank(appConfig.getTerminal().getWriteSafeRoot())));
        terminal.put(
                "credential_file_policy",
                safeCredentialFilePolicySummary());
        terminal.put(
                "max_foreground_timeout_seconds",
                Integer.valueOf(appConfig.getTerminal().getMaxForegroundTimeoutSeconds()));
        terminal.put(
                "foreground_max_retries",
                Integer.valueOf(appConfig.getTerminal().getForegroundMaxRetries()));
        terminal.put(
                "foreground_retry_base_delay_seconds",
                Integer.valueOf(appConfig.getTerminal().getForegroundRetryBaseDelaySeconds()));
        map.put("terminal", terminal);
        map.put("audit_policy", securityAuditPolicy());
        return map;
    }

    private Map<String, Object> safeCredentialFilePolicySummary() {
        try {
            Map<String, Object> summary =
                    new SkillCredentialFileService(appConfig).policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "configCredentialFileCount");
            copyPolicyValue(summary, safe, "configuredMountCount");
            copyPolicyValue(summary, safe, "configuredMissingCount");
            copyPolicyValue(summary, safe, "configuredRejectedCount");
            copyPolicyValue(summary, safe, "sandboxCredentialMountCount");
            copyPolicyValue(summary, safe, "runtimeRelativeOnly");
            copyPolicyValue(summary, safe, "absolutePathRejected");
            copyPolicyValue(summary, safe, "pathTraversalRejected");
            copyPolicyValue(summary, safe, "controlCharacterRejected");
            copyPolicyValue(summary, safe, "runtimeHomeEscapeRejected");
            copyPolicyValue(summary, safe, "missingFilesNotMounted");
            copyPolicyValue(summary, safe, "hostPathsOmittedFromMetadata");
            copyPolicyValue(summary, safe, "rejectedPathsRedacted");
            copyPolicyValue(summary, safe, "skillFrontmatterKey");
            copyPolicyValue(summary, safe, "configKey");
            return safe;
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("available", Boolean.FALSE);
            fallback.put(
                    "summary",
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            1000));
            return fallback;
        }
    }

    private Map<String, Object> safeUrlPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("url policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.urlPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "allowPrivateUrls");
            copyPolicyValue(summary, safe, "alwaysBlockedHostCount");
            copyPolicyValue(summary, safe, "alwaysBlockedIpCount");
            copyPolicyValue(summary, safe, "trustedPrivateIpHostCount");
            copyPolicyValue(summary, safe, "sensitiveQueryNameCount");
            copyPolicyValue(summary, safe, "websiteBlocklistEnabled");
            copyPolicyValue(summary, safe, "websiteBlocklistDomainCount");
            copyPolicyValue(summary, safe, "websiteBlocklistSharedFileCount");
            copyPolicyValue(summary, safe, "websiteBlocklistSharedRuleCount");
            copyPolicyValue(summary, safe, "websiteBlocklistLoadedSharedFileCount");
            copyPolicyValue(summary, safe, "websiteBlocklistSkippedSharedFileCount");
            copyPolicyValue(summary, safe, "userinfoBlocked");
            copyPolicyValue(summary, safe, "sensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "encodedSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "repeatedEncodedSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "semicolonSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "fragmentSensitiveQueryBlocked");
            copyPolicyValue(summary, safe, "sensitivePathCredentialBlocked");
            copyPolicyValue(summary, safe, "cloudMetadataBlocked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safePrivateUrlPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("url policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.privateUrlPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "allowPrivateUrls");
            copyPolicyValue(summary, safe, "environmentOverrideName");
            copyPolicyValue(summary, safe, "cloudMetadataAlwaysBlocked");
            copyPolicyValue(summary, safe, "dnsResolutionRequired");
            copyPolicyValue(summary, safe, "obfuscatedIpv4Checked");
            copyPolicyValue(summary, safe, "ipv4MappedIpv6Checked");
            copyPolicyValue(summary, safe, "loopbackBlocked");
            copyPolicyValue(summary, safe, "linkLocalBlocked");
            copyPolicyValue(summary, safe, "siteLocalBlocked");
            copyPolicyValue(summary, safe, "multicastBlocked");
            copyPolicyValue(summary, safe, "reservedDocumentationRangesBlocked");
            copyPolicyValue(summary, safe, "trustedPrivateIpHostCount");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeWebsitePolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("website policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.websitePolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "configuredDomainCount");
            copyPolicyValue(summary, safe, "sharedFileCount");
            copyPolicyValue(summary, safe, "loadedSharedFileCount");
            copyPolicyValue(summary, safe, "skippedSharedFileCount");
            copyPolicyValue(summary, safe, "sharedRuleCount");
            copyPolicyValue(summary, safe, "hostRuleNormalization");
            copyPolicyValue(summary, safe, "wildcardSubdomainSupported");
            copyPolicyValue(summary, safe, "schemeAndPathIgnoredForRules");
            copyPolicyValue(summary, safe, "wwwPrefixIgnored");
            copyPolicyValue(summary, safe, "sharedFilePathSafetyChecked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> unavailablePolicy(Exception e) {
        return unavailablePolicy(
                StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
    }

    private Map<String, Object> unavailablePolicy(String message) {
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("available", Boolean.FALSE);
        fallback.put("summary", SecretRedactor.redact(message, 1000));
        return fallback;
    }

    private static void copyPolicyValue(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> securityAuditPolicy() {
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        try {
            Map<String, Object> result = securityAudit(Collections.singletonMap("action", "policy"));
            Object policy = result.get("policy");
            if (policy instanceof Map) {
                return (Map<String, Object>) policy;
            }
            fallback.put("available", Boolean.FALSE);
            fallback.put("summary", SecretRedactor.redact(String.valueOf(result.get("summary")), 1000));
        } catch (Exception e) {
            fallback.put("available", Boolean.FALSE);
            fallback.put(
                    "summary",
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()),
                            1000));
        }
        return fallback;
    }

    private Map<String, Object> pendingApprovalItem(
            SessionRecord session, DangerousCommandApprovalService.PendingApproval pending) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("session_id", safeAuditPreview(session.getSessionId(), 240));
        item.put("source_ref", sourceRef(session.getSourceKey()));
        item.put("title", safeAuditPreview(
                StrUtil.blankToDefault(session.getTitle(), session.getSessionId()), 240));
        item.put("branch_name", safeAuditPreview(session.getBranchName(), 160));
        item.put("updated_at", Long.valueOf(session.getUpdatedAt()));
        item.put("approval_id", safeAuditPreview(pending.getApprovalId(), 160));
        item.put("selector", DangerousCommandApprovalService.approvalSelector(pending));
        item.put("tool_name", safeAuditPreview(pending.getToolName(), 160));
        item.put("description", safeAuditPreview(pending.getDescription(), 1000));
        item.put("pattern_key", safeAuditPreview(pending.getPatternKey(), 400));
        item.put("pattern_keys", redactedTextList(pending.effectivePatternKeys(), 400));
        item.put("rule_sources", approvalRuleSources(pending));
        item.put("command_preview", safeAuditPreview(pending.getCommand(), 800));
        item.put("command_hash", redactedIdentifier(pending.getCommandHash()));
        item.put("created_at", Long.valueOf(pending.getCreatedAt()));
        item.put("expires_at", Long.valueOf(pending.getExpiresAt()));
        item.put("expires_in_seconds", Long.valueOf(expiresInSeconds(pending.getExpiresAt())));
        item.put("expired", Boolean.valueOf(isExpired(pending.getExpiresAt())));
        item.put("scopes", pending.isPermanentApprovalAllowed() ? "once,session,always" : "once,session");
        item.put("scope_options", approvalScopeOptions(pending));
        item.put("permanent_allowed", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        item.put("permanent_disabled_reason", permanentDisabledReason(pending));
        return item;
    }

    private List<Object> redactedTextList(List<String> source, int maxLength) {
        List<Object> values = new ArrayList<Object>();
        if (source == null) {
            return values;
        }
        for (String item : source) {
            if (StrUtil.isNotBlank(item)) {
                values.add(safeAuditPreview(item, maxLength));
            }
        }
        return values;
    }

    private List<String> approvalRuleSources(
            DangerousCommandApprovalService.PendingApproval pending) {
        List<String> sources = new ArrayList<String>();
        if (pending == null) {
            return sources;
        }
        for (String patternKey : pending.effectivePatternKeys()) {
            String source =
                    StrUtil.nullToEmpty(patternKey).startsWith("tirith:")
                            ? "security_scan"
                            : "local_policy";
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }
        return sources;
    }

    private String permanentDisabledReason(
            DangerousCommandApprovalService.PendingApproval pending) {
        if (pending == null || pending.isPermanentApprovalAllowed()) {
            return "";
        }
        for (String patternKey : pending.effectivePatternKeys()) {
            if (StrUtil.nullToEmpty(patternKey).startsWith("tirith:")) {
                return "安全扫描命中项只能按本次或本会话审批，不能写入长期授权。";
            }
        }
        return "该审批项不允许长期授权。";
    }

    private List<String> approvalScopeOptions(
            DangerousCommandApprovalService.PendingApproval pending) {
        List<String> scopes = new ArrayList<String>();
        scopes.add("once");
        scopes.add("session");
        if (pending != null && pending.isPermanentApprovalAllowed()) {
            scopes.add("always");
        }
        return scopes;
    }

    private long expiresInSeconds(long expiresAt) {
        if (expiresAt <= 0L) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    private boolean isExpired(long expiresAt) {
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

    private DangerousCommandApprovalService.ApprovalScope parseApprovalScope(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase();
        if ("always".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.ALWAYS;
        }
        if ("session".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.SESSION;
        }
        return DangerousCommandApprovalService.ApprovalScope.ONCE;
    }

    private Map<String, Object> resolveResult(
            boolean success, String code, String message, Map<String, Object> reply) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.valueOf(success));
        result.put("code", code);
        result.put("message", message);
        if (reply != null) {
            result.put("reply", reply);
        }
        return result;
    }

    private Map<String, Object> replyMap(GatewayReply reply) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("session_id", reply.getSessionId());
        map.put("branch_name", reply.getBranchName());
        map.put("content", SecretRedactor.redact(reply.getContent(), 1200));
        map.put("error", Boolean.valueOf(reply.isError()));
        return map;
    }

    private Map<String, Object> approvalAuditItem(ApprovalAuditEvent event) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("event_id", safeAuditPreview(event.getEventId(), 120));
        item.put("session_id", safeAuditPreview(event.getSessionId(), 240));
        item.put("event_type", safeAuditPreview(event.getEventType(), 80));
        item.put("choice", safeAuditPreview(event.getChoice(), 80));
        item.put("approver", SecretRedactor.redact(event.getApprover(), 200));
        item.put("tool_name", safeAuditPreview(event.getToolName(), 160));
        item.put("command_hash", redactedIdentifier(event.getCommandHash()));
        item.put("command_preview", safeAuditPreview(event.getCommandPreview(), 800));
        item.put("description", safeAuditPreview(event.getDescription(), 1000));
        item.put("pattern_keys", redactedJsonList(event.getPatternKeysJson(), 400));
        item.put("created_at", Long.valueOf(event.getCreatedAt()));
        item.put("approval_created_at", Long.valueOf(event.getApprovalCreatedAt()));
        item.put("approval_expires_at", Long.valueOf(event.getApprovalExpiresAt()));
        return item;
    }

    private String safeAuditPreview(String value, int maxLength) {
        return StrUtil.nullToEmpty(SecretRedactor.redact(value, maxLength));
    }

    private String redactedApprovalKey(String approvalKey) {
        String value = SecretRedactor.redact(StrUtil.nullToEmpty(approvalKey), 1000);
        int split = value.lastIndexOf(':');
        if (split >= 0 && split < value.length() - 1) {
            return value.substring(0, split + 1) + "***";
        }
        return redactedIdentifier(value);
    }

    private String redactedIdentifier(String value) {
        return StrUtil.isBlank(value) ? "" : "***";
    }

    private Map<String, Object> alwaysApprovalItem(String approval) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String value = StrUtil.nullToEmpty(approval);
        String toolName = "";
        String patternKey = "";
        int colon = value.indexOf(':');
        if (colon >= 0) {
            toolName = value.substring(0, colon);
            patternKey = value.substring(colon + 1);
        }
        item.put("approval_id", alwaysApprovalId(value));
        item.put("tool_name", safeAuditPreview(toolName, 160));
        item.put("pattern_key", safeAuditPreview(patternKey, 400));
        return item;
    }

    private String resolveAlwaysApproval(String approvalId) {
        if (StrUtil.isNotBlank(approvalId) && approvalService != null) {
            for (String approval : approvalService.listAlwaysApprovals()) {
                if (alwaysApprovalId(approval).equals(approvalId.trim())) {
                    return approval;
                }
            }
        }
        return "";
    }

    private String alwaysApprovalId(String approval) {
        String value = StrUtil.nullToEmpty(approval);
        return value.isEmpty() ? "" : SecureUtil.sha256(value).substring(0, 24);
    }

    private void appendAlwaysApprovalRevokedAudit(String approval, String approver) {
        if (approvalAuditRepository == null) {
            return;
        }
        Map<String, Object> item = alwaysApprovalItem(approval);
        ApprovalAuditEvent audit = new ApprovalAuditEvent();
        audit.setEventId(IdSupport.newId());
        audit.setSessionId("");
        audit.setEventType("response");
        audit.setChoice("revoke");
        audit.setApprover(SecretRedactor.redact(approver, 200));
        audit.setToolName(StrUtil.nullToEmpty(String.valueOf(item.get("tool_name"))));
        audit.setApprovalId("");
        audit.setApprovalKey(redactedApprovalKey(approval));
        audit.setCommandHash("");
        audit.setCommandPreview("");
        audit.setDescription("撤销长期审批授权");
        audit.setPatternKeysJson(ONode.serialize(Collections.singletonList(item.get("pattern_key"))));
        audit.setCreatedAt(System.currentTimeMillis());
        audit.setApprovalCreatedAt(0L);
        audit.setApprovalExpiresAt(0L);
        try {
            approvalAuditRepository.append(audit);
        } catch (Exception ignored) {
            // Audit persistence must not affect safety-critical approval handling.
        }
    }

    private Map<String, Object> slashConfirmItem(SlashConfirmService.PendingConfirm pending) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis();
        long expiresAt = pending.getCreatedAt() + SlashConfirmService.DEFAULT_TIMEOUT_MS;
        long remainingMillis = expiresAt - now;
        boolean expired = remainingMillis <= 0L;
        List<String> actionOptions = new ArrayList<String>();
        if (!expired) {
            actionOptions.add("approve");
            actionOptions.add("deny");
            if (pending.isAllowAlways()) {
                actionOptions.add("always");
            }
        }
        item.put("confirm_id", safeAuditPreview(pending.getConfirmId(), 160));
        item.put("confirm_ref", shortId(pending.getConfirmId()));
        item.put("source_ref", sourceRef(pending.getSourceKey()));
        item.put("command_preview", safeAuditPreview(pending.getCommand(), 1000));
        item.put("prompt_preview", safeAuditPreview(pending.getPrompt(), 1000));
        item.put("allow_always", Boolean.valueOf(pending.isAllowAlways()));
        item.put("action_options", actionOptions);
        item.put("created_at", Long.valueOf(pending.getCreatedAt()));
        item.put("expires_at", Long.valueOf(expiresAt));
        item.put("expires_in_seconds", Long.valueOf(Math.max(0L, remainingMillis / 1000L)));
        item.put("expired", Boolean.valueOf(expired));
        return item;
    }

    private SlashConfirmService.PendingConfirm findPendingSlashConfirm(
            String confirmId, String fallbackSourceKey) {
        if (slashConfirmService == null) {
            return null;
        }
        String expected =
                SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(confirmId)).trim();
        for (SlashConfirmService.PendingConfirm pending : slashConfirmService.listPending()) {
            if (StrUtil.equals(expected, pending.getConfirmId())) {
                return pending;
            }
        }
        if (StrUtil.isNotBlank(fallbackSourceKey)) {
            SlashConfirmService.PendingConfirm pending = slashConfirmService.getPending(fallbackSourceKey);
            if (pending != null && StrUtil.equals(expected, pending.getConfirmId())) {
                return pending;
            }
        }
        return null;
    }

    private String shortId(String value) {
        String safe = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        return safe.length() <= 8 ? safe : safe.substring(0, 8);
    }

    private String sourceRef(String value) {
        String safe = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        return safe.isEmpty() ? "" : SecureUtil.sha256(safe).substring(0, 12);
    }

    private String slashConfirmCommandLine(String action, String confirmId) {
        if ("deny".equals(action)) {
            return "/deny " + StrUtil.nullToEmpty(confirmId);
        }
        if ("always".equals(action)) {
            return "/approve always " + StrUtil.nullToEmpty(confirmId);
        }
        return "/approve " + StrUtil.nullToEmpty(confirmId);
    }

    private GatewayMessage dashboardMessage(String sourceKey, String text) {
        GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "dashboard", "dashboard", text);
        message.setSourceKeyOverride(sourceKey);
        message.setUserName("dashboard");
        return message;
    }

    @SuppressWarnings("unchecked")
    private List<Object> parseJsonList(String json) {
        if (StrUtil.isBlank(json)) {
            return new ArrayList<Object>();
        }
        try {
            Object data = ONode.ofJson(json).toData();
            if (data instanceof List) {
                return (List<Object>) data;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<Object>();
    }

    private List<Object> redactedJsonList(String json, int maxLength) {
        List<Object> source = parseJsonList(json);
        List<Object> values = new ArrayList<Object>();
        for (Object item : source) {
            if (item instanceof String) {
                values.add(safeAuditPreview((String) item, maxLength));
            } else if (item != null) {
                values.add(SecretRedactor.redact(String.valueOf(item), maxLength));
            }
        }
        return values;
    }

    private boolean canWriteParent(String path) {
        if (path == null) {
            return false;
        }
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent != null && parent.exists() && parent.canWrite();
    }

    private String runtimeReference(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (StrUtil.isBlank(text)) {
            return text;
        }
        File runtimeHome = new File(appConfig.getRuntime().getHome()).getAbsoluteFile();
        File file = new File(text).getAbsoluteFile();
        try {
            runtimeHome = runtimeHome.getCanonicalFile();
            file = file.getCanonicalFile();
        } catch (Exception ignored) {
        }
        String homePath = normalized(runtimeHome);
        String filePath = normalized(file);
        if (filePath.equals(homePath)) {
            return "runtime://";
        }
        if (filePath.startsWith(homePath + File.separator)) {
            String relative = filePath.substring(homePath.length() + 1).replace('\\', '/');
            return "runtime://" + relative;
        }
        return externalPathReference(text);
    }

    private String externalPathReference(String value) {
        String name = new File(StrUtil.nullToEmpty(value)).getName();
        if (StrUtil.isBlank(name)) {
            name = "external";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    private String normalized(File file) {
        String path = file.getAbsolutePath();
        if (File.separatorChar == '\\') {
            return path.toLowerCase(java.util.Locale.ROOT);
        }
        return path;
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : SecretRedactor.stripDisplayControls(String.valueOf(value));
    }

    private Boolean bool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        return Boolean.valueOf(String.valueOf(value));
    }
}
