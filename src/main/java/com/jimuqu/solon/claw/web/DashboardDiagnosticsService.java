package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jimuqu.solon.claw.cli.CliAttachmentResolver;
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
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityAuditTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawCodeExecutionSkills;
import com.jimuqu.solon.claw.tool.runtime.SolonClawPatchTools;
import com.jimuqu.solon.claw.tool.runtime.SolonClawShellSkill;
import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import com.jimuqu.solon.claw.tool.runtime.SubprocessEnvironmentSanitizer;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
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
    private final ToolResultStorageService toolResultStorageService;

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
        this(
                appConfig,
                deliveryService,
                llmProviderService,
                toolRegistry,
                sessionRepository,
                conversationOrchestrator,
                approvalAuditRepository,
                slashConfirmService,
                commandService,
                approvalService,
                securityPolicyService,
                tirithSecurityService,
                null);
    }

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
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService) {
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
        this.toolResultStorageService = toolResultStorageService;
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
                        securityPolicyService,
                        approvalService,
                        tirithSecurityService,
                        toolResultStorageService,
                        appConfig);
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
            return safeSecurityAuditResult((Map<String, Object>) data);
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
        map.put("policies", toolPolicies());
        map.put("attachment_policies", attachmentPolicies());
        return map;
    }

    private Map<String, Object> toolPolicies() {
        Map<String, Object> policies = new LinkedHashMap<String, Object>();
        policies.put("schema_sanitizer", safeSchemaSanitizerPolicySummary());
        policies.put("patch_parser", safePatchParserPolicySummary());
        policies.put("code_execution", safeCodeExecutionPolicySummary());
        policies.put("subprocess_environment", safeSubprocessEnvironmentPolicySummary());
        return policies;
    }

    private Map<String, Object> attachmentPolicies() {
        Map<String, Object> policies = new LinkedHashMap<String, Object>();
        policies.put("download_io", safeAttachmentDownloadPolicySummary());
        policies.put("media_cache", safeAttachmentMediaCachePolicySummary());
        policies.put("terminal_paste", safeAttachmentTerminalPastePolicySummary());
        return policies;
    }

    private Map<String, Object> mcp() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", appConfig.getMcp().isEnabled());
        map.put("status", appConfig.getMcp().isEnabled() ? "enabled" : "disabled");
        map.put("runtime_policy", safeMcpRuntimePolicySummary());
        map.put("oauth_policy", safeMcpOAuthPolicySummary());
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
        approvals.put("approval_policy", safeApprovalPolicySummary());
        approvals.put("hardline_policy", safeHardlinePolicySummary());
        approvals.put("cron_approval_policy", safeCronApprovalPolicySummary());
        approvals.put("subagent_approval_policy", safeSubagentApprovalPolicySummary());
        approvals.put("smart_approval_policy", safeSmartApprovalPolicySummary());
        approvals.put("tirith_approval_policy", safeTirithApprovalPolicySummary());
        approvals.put("approval_lifecycle_policy", safeApprovalLifecyclePolicySummary());
        approvals.put("slash_confirm_policy", safeSlashConfirmPolicySummary());
        approvals.put("approval_card_policy", safeApprovalCardPolicySummary());
        approvals.put("approval_audit_policy", safeApprovalAuditPolicySummary());
        approvals.put("mcp_reload_policy", safeMcpReloadPolicySummary());
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
        policy.put("path_policy", safePathPolicySummary());
        policy.put("credential_policy", safeCredentialPolicySummary());
        policy.put("tool_args_policy", safeToolArgsPolicySummary());
        policy.put("tirith_policy", safeTirithPolicySummary());
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
                "terminal_output_policy",
                safeTerminalOutputPolicySummary());
        terminal.put(
                "tool_result_storage_policy",
                safeToolResultStoragePolicySummary());
        terminal.put(
                "sudo_rewrite_policy",
                safeSudoRewritePolicySummary());
        terminal.put(
                "background_process_policy",
                safeBackgroundProcessPolicySummary());
        terminal.put(
                "terminal_guardrail_policy",
                safeTerminalGuardrailPolicySummary());
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

    private Map<String, Object> safeApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "mode");
            copyPolicyValue(summary, safe, "cronMode");
            copyPolicyValue(summary, safe, "subagentAutoApprove");
            copyPolicyValue(summary, safe, "smartJudgeConfigured");
            copyPolicyValue(summary, safe, "dangerousRuleCount");
            copyPolicyValue(summary, safe, "hardlineRuleCount");
            copyPolicyValue(summary, safe, "dangerousRuleSamples");
            copyPolicyValue(summary, safe, "domesticCloudRuleSamples");
            copyPolicyValue(summary, safe, "cloudStorageRuleSamples");
            copyPolicyValue(summary, safe, "credentialHandlingRuleSamples");
            copyPolicyValue(summary, safe, "hardlineRuleSamples");
            copyPolicyValue(summary, safe, "terminalGuardrailCount");
            copyPolicyValue(summary, safe, "terminalGuardrails");
            copyPolicyValue(summary, safe, "sudoRewriteConfigured");
            copyPolicyValue(summary, safe, "backgroundProcessGuard");
            copyPolicyValue(summary, safe, "urlPolicyPrechecked");
            copyPolicyValue(summary, safe, "privateUrlPolicyPrechecked");
            copyPolicyValue(summary, safe, "credentialUrlPolicyPrechecked");
            copyPolicyValue(summary, safe, "websitePolicyPrechecked");
            copyPolicyValue(summary, safe, "unsafeUrlBlockedBeforeApproval");
            copyPolicyValue(summary, safe, "unsafeUrlApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "approvalTimeoutSeconds");
            copyPolicyValue(summary, safe, "gatewayTimeoutSeconds");
            copyPolicyValue(summary, safe, "alwaysApprovalCount");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeHardlinePolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.hardlinePolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "ruleCount");
            copyPolicyValue(summary, safe, "ruleSamples");
            copyPolicyValue(summary, safe, "coveredTools");
            copyPolicyValue(summary, safe, "blockedCategories");
            copyPolicyValue(summary, safe, "metadataUrlBlocked");
            copyPolicyValue(summary, safe, "codeToolShellExtractionCovered");
            copyPolicyValue(summary, safe, "pythonShellExtractionCovered");
            copyPolicyValue(summary, safe, "javascriptChildProcessExtractionCovered");
            copyPolicyValue(summary, safe, "approvalBypassAllowed");
            copyPolicyValue(summary, safe, "slashApproveBypassAllowed");
            copyPolicyValue(summary, safe, "sessionApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "alwaysApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "yoloBypassAllowed");
            copyPolicyValue(summary, safe, "smartApprovalBypassAllowed");
            copyPolicyValue(summary, safe, "blockingDecision");
            copyPolicyValue(summary, safe, "approvalRequired");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeCronApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.cronApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "mode");
            copyPolicyValue(summary, safe, "autoApproveDangerousCommands");
            copyPolicyValue(summary, safe, "defaultDecision");
            copyPolicyValue(summary, safe, "configKeys");
            copyPolicyValue(summary, safe, "approveAliases");
            copyPolicyValue(summary, safe, "denyAliases");
            copyPolicyValue(summary, safe, "runsWithoutHumanApproval");
            copyPolicyValue(summary, safe, "hardlineAlwaysBlocked");
            copyPolicyValue(summary, safe, "dangerousPatternCheckedBeforeRun");
            copyPolicyValue(summary, safe, "requiresExplicitApproveMode");
            copyPolicyValue(summary, safe, "scriptContentChecked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeSubagentApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.subagentApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "autoApproveDangerousCommands");
            copyPolicyValue(summary, safe, "defaultDecision");
            copyPolicyValue(summary, safe, "configKey");
            copyPolicyValue(summary, safe, "runKind");
            copyPolicyValue(summary, safe, "hardlinePrechecked");
            copyPolicyValue(summary, safe, "filePolicyPrechecked");
            copyPolicyValue(summary, safe, "urlPolicyPrechecked");
            copyPolicyValue(summary, safe, "terminalGuardrailPrechecked");
            copyPolicyValue(summary, safe, "smartApprovalRunsBeforeSubagentPolicy");
            copyPolicyValue(summary, safe, "humanApprovalPromptSuppressed");
            copyPolicyValue(summary, safe, "currentThreadApprovalWhenAutoApproved");
            copyPolicyValue(summary, safe, "pendingApprovalCreatedWhenDenied");
            copyPolicyValue(summary, safe, "denyMessageIncludesConfigHint");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeSmartApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.smartApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "mode");
            copyPolicyValue(summary, safe, "smartMode");
            copyPolicyValue(summary, safe, "judgeConfigured");
            copyPolicyValue(summary, safe, "active");
            copyPolicyValue(summary, safe, "decisionTypes");
            copyPolicyValue(summary, safe, "approveWritesSessionApproval");
            copyPolicyValue(summary, safe, "approveMarksCurrentThread");
            copyPolicyValue(summary, safe, "escalateFallsBackToHumanApproval");
            copyPolicyValue(summary, safe, "denyBlocksExecution");
            copyPolicyValue(summary, safe, "judgeFailureFallsBackToHumanApproval");
            copyPolicyValue(summary, safe, "hardlinePrechecked");
            copyPolicyValue(summary, safe, "filePolicyPrechecked");
            copyPolicyValue(summary, safe, "urlPolicyPrechecked");
            copyPolicyValue(summary, safe, "terminalGuardrailPrechecked");
            copyPolicyValue(summary, safe, "tirithFindingsIncluded");
            copyPolicyValue(summary, safe, "subagentPolicyRunsAfterSmartApproval");
            copyPolicyValue(summary, safe, "approvalCardFallback");
            copyPolicyValue(summary, safe, "reasonStoredInBlockMessage");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeTirithApprovalPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.tirithApprovalPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "scannerConfigured");
            copyPolicyValue(summary, safe, "scanRunsInApprovalMode");
            copyPolicyValue(summary, safe, "patternKeyPrefix");
            copyPolicyValue(summary, safe, "emptyFindingsPatternKey");
            copyPolicyValue(summary, safe, "findingsBecomePatternKeys");
            copyPolicyValue(summary, safe, "combinedWithLocalDangerRules");
            copyPolicyValue(summary, safe, "permanentApprovalAllowed");
            copyPolicyValue(summary, safe, "alwaysScopeDowngradedToSession");
            copyPolicyValue(summary, safe, "approvalCardAlwaysHidden");
            copyPolicyValue(summary, safe, "smartApprovalCanApproveSessionOnly");
            copyPolicyValue(summary, safe, "smartApprovalCanDeny");
            copyPolicyValue(summary, safe, "pendingMessageBlocksAlwaysScope");
            copyPolicyValue(summary, safe, "descriptionRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeApprovalLifecyclePolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalLifecyclePolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "pendingListPrunedBeforeRead");
            copyPolicyValue(summary, safe, "selectorSupported");
            copyPolicyValue(summary, safe, "selectorTokenPattern");
            copyPolicyValue(summary, safe, "unsafeSelectorRejected");
            copyPolicyValue(summary, safe, "listSupported");
            copyPolicyValue(summary, safe, "statusAliasSupported");
            copyPolicyValue(summary, safe, "approveAllSupported");
            copyPolicyValue(summary, safe, "rejectAllSupported");
            copyPolicyValue(summary, safe, "bulkRejectUsesSafeSelector");
            copyPolicyValue(summary, safe, "clearSessionSupported");
            copyPolicyValue(summary, safe, "clearAlwaysSupported");
            copyPolicyValue(summary, safe, "clearAllSupported");
            copyPolicyValue(summary, safe, "scopes");
            copyPolicyValue(summary, safe, "alwaysScopeUsesGlobalSettings");
            copyPolicyValue(summary, safe, "tirithAlwaysScopeDowngradedToSession");
            copyPolicyValue(summary, safe, "currentThreadApprovalTtlMillis");
            copyPolicyValue(summary, safe, "currentThreadApprovalEnabled");
            copyPolicyValue(summary, safe, "approveRemovesPendingApproval");
            copyPolicyValue(summary, safe, "rejectRemovesPendingApproval");
            copyPolicyValue(summary, safe, "sessionSnapshotUpdated");
            copyPolicyValue(summary, safe, "approvalRequestObserved");
            copyPolicyValue(summary, safe, "approvalResponseObserved");
            copyPolicyValue(summary, safe, "approverRedacted");
            copyPolicyValue(summary, safe, "approvalKeyRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeSlashConfirmPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.slashConfirmPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "commands");
            copyPolicyValue(summary, safe, "selectorSupported");
            copyPolicyValue(summary, safe, "selectorTokenPattern");
            copyPolicyValue(summary, safe, "unsafeSelectorRejected");
            copyPolicyValue(summary, safe, "listSupported");
            copyPolicyValue(summary, safe, "statusAliasSupported");
            copyPolicyValue(summary, safe, "approveAllSupported");
            copyPolicyValue(summary, safe, "denyAllSupported");
            copyPolicyValue(summary, safe, "clearSessionSupported");
            copyPolicyValue(summary, safe, "clearAlwaysSupported");
            copyPolicyValue(summary, safe, "clearAllSupported");
            copyPolicyValue(summary, safe, "scopes");
            copyPolicyValue(summary, safe, "defaultScope");
            copyPolicyValue(summary, safe, "managementCommands");
            copyPolicyValue(summary, safe, "pendingQueueSupported");
            copyPolicyValue(summary, safe, "pendingListHidesApprovalKey");
            copyPolicyValue(summary, safe, "pendingListUsesSafeSelector");
            copyPolicyValue(summary, safe, "pendingListShowsPatternKey");
            copyPolicyValue(summary, safe, "sessionApprovalListShowsCountOnly");
            copyPolicyValue(summary, safe, "alwaysApprovalListShowsCountOnly");
            copyPolicyValue(summary, safe, "approvalCardDeliveryMode");
            copyPolicyValue(summary, safe, "approvalCardPlatforms");
            copyPolicyValue(summary, safe, "permanentApprovalAllowedExceptTirith");
            copyPolicyValue(summary, safe, "tirithAlwaysDowngradedToSession");
            copyPolicyValue(summary, safe, "approverRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "approvalMetadataRedacted");
            copyPolicyValue(summary, safe, "observerEventsRedacted");
            copyPolicyValue(summary, safe, "approvalTimeoutSeconds");
            copyPolicyValue(summary, safe, "gatewayTimeoutSeconds");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeApprovalCardPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalCardPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "deliveryMode");
            copyPolicyValue(summary, safe, "supportedPlatforms");
            copyPolicyValue(summary, safe, "unsupportedPlatformsReturnEmptyExtras");
            copyPolicyValue(summary, safe, "actionKey");
            copyPolicyValue(summary, safe, "approveAction");
            copyPolicyValue(summary, safe, "denyAction");
            copyPolicyValue(summary, safe, "scopeKey");
            copyPolicyValue(summary, safe, "approvalIdKey");
            copyPolicyValue(summary, safe, "scopeOptions");
            copyPolicyValue(summary, safe, "defaultScope");
            copyPolicyValue(summary, safe, "approvalIdSelectorSupported");
            copyPolicyValue(summary, safe, "selectorTokenPattern");
            copyPolicyValue(summary, safe, "unsafeSelectorRejected");
            copyPolicyValue(summary, safe, "outboundApprovalIdSanitized");
            copyPolicyValue(summary, safe, "unsafeApprovalIdFallsBackToKeySelector");
            copyPolicyValue(summary, safe, "approveCommandGenerated");
            copyPolicyValue(summary, safe, "denyCommandGenerated");
            copyPolicyValue(summary, safe, "alwaysScopeCommandGenerated");
            copyPolicyValue(summary, safe, "sessionScopeCommandGenerated");
            copyPolicyValue(summary, safe, "tirithPermanentApprovalHidden");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "descriptionPreviewRedacted");
            copyPolicyValue(summary, safe, "toolNameRedacted");
            copyPolicyValue(summary, safe, "rawCommandRedactedInExtras");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "semicolonUrlParameterRedacted");
            copyPolicyValue(summary, safe, "fragmentUrlParameterRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeApprovalAuditPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.approvalAuditPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "observerCount");
            copyPolicyValue(summary, safe, "requestEvents");
            copyPolicyValue(summary, safe, "responseEvents");
            copyPolicyValue(summary, safe, "eventTypes");
            copyPolicyValue(summary, safe, "repositoryBackedWhenConfigured");
            copyPolicyValue(summary, safe, "observerFailureIsolated");
            copyPolicyValue(summary, safe, "approverRedacted");
            copyPolicyValue(summary, safe, "commandPreviewRedacted");
            copyPolicyValue(summary, safe, "descriptionRedacted");
            copyPolicyValue(summary, safe, "approvalKeyRedacted");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "commandHashStored");
            copyPolicyValue(summary, safe, "patternKeysStored");
            copyPolicyValue(summary, safe, "timestampsStored");
            copyPolicyValue(summary, safe, "recentDashboardViewSupported");
            copyPolicyValue(summary, safe, "manualRevocationAudited");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeMcpReloadPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.mcpReloadPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "command");
            copyPolicyValue(summary, safe, "confirmRequired");
            copyPolicyValue(summary, safe, "configKey");
            copyPolicyValue(summary, safe, "slashConfirmBacked");
            copyPolicyValue(summary, safe, "directRunAlias");
            copyPolicyValue(summary, safe, "alwaysConfirmAlias");
            copyPolicyValue(summary, safe, "persistentDisableSupported");
            copyPolicyValue(summary, safe, "runtimeConfigPersisted");
            copyPolicyValue(summary, safe, "toolChangeNoticeInjected");
            copyPolicyValue(summary, safe, "changedServerSummary");
            copyPolicyValue(summary, safe, "toolCountSummary");
            copyPolicyValue(summary, safe, "oauthUrlSafetyCovered");
            copyPolicyValue(summary, safe, "encodedUrlParameterRedacted");
            copyPolicyValue(summary, safe, "reloadHistoryNoticeRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeMcpRuntimePolicySummary() {
        try {
            Map<String, Object> summary = McpRuntimeService.policySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "supportedTransports");
            copyPolicyValue(summary, safe, "remoteEndpointUrlSafety");
            copyPolicyValue(summary, safe, "remoteEndpointAllowsPrivateByPolicy");
            copyPolicyValue(summary, safe, "stdioEndpointSkipped");
            copyPolicyValue(summary, safe, "remoteToolArgumentUrlSafety");
            copyPolicyValue(summary, safe, "remoteToolArgumentPathSafety");
            copyPolicyValue(summary, safe, "resourceUriUrlSafety");
            copyPolicyValue(summary, safe, "resourceUriPathSafety");
            copyPolicyValue(summary, safe, "nestedUrlExtraction");
            copyPolicyValue(summary, safe, "blockedUrlsMasked");
            copyPolicyValue(summary, safe, "blockedPathsRedacted");
            copyPolicyValue(summary, safe, "inputSchemaSanitized");
            copyPolicyValue(summary, safe, "toolNamesPrefixed");
            copyPolicyValue(summary, safe, "toolIncludeExcludeFilter");
            copyPolicyValue(summary, safe, "resourceUtilityToolsCapabilityGated");
            copyPolicyValue(summary, safe, "promptUtilityToolsCapabilityGated");
            copyPolicyValue(summary, safe, "blockedServersSuppressed");
            copyPolicyValue(summary, safe, "toolsChangeNotificationPersisted");
            copyPolicyValue(summary, safe, "toolChangeHashTracked");
            copyPolicyValue(summary, safe, "toolsChangeClearsProviderCache");
            copyPolicyValue(summary, safe, "oauthFailureStructuredReauth");
            copyPolicyValue(summary, safe, "oauthSecretsRedacted");
            copyPolicyValue(summary, safe, "recoverableTransportRetry");
            copyPolicyValue(summary, safe, "remoteToolTimeoutMillisDefault");
            copyPolicyValue(summary, safe, "connectTimeoutMillisDefault");
            copyPolicyValue(summary, safe, "toolCallExecutorBounded");
            copyPolicyValue(summary, safe, "toolCallExecutorMaxThreads");
            copyPolicyValue(summary, safe, "toolCallExecutorQueueCapacity");
            copyPolicyValue(summary, safe, "accessTokenHeaderOnlyForRemote");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeMcpOAuthPolicySummary() {
        try {
            Map<String, Object> summary = DashboardMcpService.oauthPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "authorizationEndpointUrlSafety");
            copyPolicyValue(summary, safe, "tokenEndpointUrlSafety");
            copyPolicyValue(summary, safe, "tokenEndpointRedirectUrlSafety");
            copyPolicyValue(summary, safe, "tokenEndpointRedirectLimit");
            copyPolicyValue(summary, safe, "crossOriginRedirectBodyForwardingBlocked");
            copyPolicyValue(summary, safe, "stateValidationRequired");
            copyPolicyValue(summary, safe, "pkceS256Required");
            copyPolicyValue(summary, safe, "codeVerifierHiddenFromStatus");
            copyPolicyValue(summary, safe, "accessTokenRedacted");
            copyPolicyValue(summary, safe, "refreshTokenRedacted");
            copyPolicyValue(summary, safe, "clientSecretRedacted");
            copyPolicyValue(summary, safe, "refreshRequiresRefreshToken");
            copyPolicyValue(summary, safe, "handle401RefreshThenReauth");
            copyPolicyValue(summary, safe, "clearRemovesSecretPresenceFlags");
            copyPolicyValue(summary, safe, "statusPresenceFields");
            copyPolicyValue(summary, safe, "callbackErrorsRedacted");
            copyPolicyValue(summary, safe, "tokenErrorsRedacted");
            copyPolicyValue(summary, safe, "tokenResponseRequiresAccessToken");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeSchemaSanitizerPolicySummary() {
        try {
            Map<String, Object> summary = SolonClawToolSchemaSanitizer.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "appliesTo");
            copyPolicyValue(summary, safe, "inputSchemaSanitized");
            copyPolicyValue(summary, safe, "outputFunctionToolSchemaSanitized");
            copyPolicyValue(summary, safe, "mcpInputSchemaSanitized");
            copyPolicyValue(summary, safe, "invalidSchemaDefaultsToObject");
            copyPolicyValue(summary, safe, "topLevelObjectRequired");
            copyPolicyValue(summary, safe, "propertiesInjectedForObject");
            copyPolicyValue(summary, safe, "requiredPrunedToKnownProperties");
            copyPolicyValue(summary, safe, "nullableUnionCollapsed");
            copyPolicyValue(summary, safe, "patternAndFormatStripped");
            copyPolicyValue(summary, safe, "schemaObjectSanitizationNonMutating");
            copyPolicyValue(summary, safe, "jsonLibrary");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safePatchParserPolicySummary() {
        try {
            Map<String, Object> summary = SolonClawPatchTools.patchParserPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "toolName");
            copyPolicyValue(summary, safe, "modes");
            copyPolicyValue(summary, safe, "patchFormat");
            copyPolicyValue(summary, safe, "beginEndMarkersRequired");
            copyPolicyValue(summary, safe, "operations");
            copyPolicyValue(summary, safe, "atomicValidationBeforeWrite");
            copyPolicyValue(summary, safe, "noPartialWritesOnValidationFailure");
            copyPolicyValue(summary, safe, "replaceRequiresUniqueMatchByDefault");
            copyPolicyValue(summary, safe, "replaceAllRequiresExplicitFlag");
            copyPolicyValue(summary, safe, "additionOnlyContextHintsSupported");
            copyPolicyValue(summary, safe, "ambiguousHunksBlocked");
            copyPolicyValue(summary, safe, "missingHunksBlocked");
            copyPolicyValue(summary, safe, "addWillNotOverwriteExistingFile");
            copyPolicyValue(summary, safe, "moveWillNotOverwriteDestination");
            copyPolicyValue(summary, safe, "deleteRequiresExistingFile");
            copyPolicyValue(summary, safe, "pathTraversalBlocked");
            copyPolicyValue(summary, safe, "nulPathBlocked");
            copyPolicyValue(summary, safe, "jarInternalPathBlocked");
            copyPolicyValue(summary, safe, "symlinkEscapeBlocked");
            copyPolicyValue(summary, safe, "credentialPolicyPrechecked");
            copyPolicyValue(summary, safe, "moveDestinationPolicyChecked");
            copyPolicyValue(summary, safe, "errorsRedacted");
            copyPolicyValue(summary, safe, "staleFileWarnings");
            copyPolicyValue(summary, safe, "diffReturned");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeCodeExecutionPolicySummary() {
        try {
            Map<String, Object> summary =
                    SolonClawCodeExecutionSkills.codeExecutionPolicySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "executeCodeSupported");
            copyPolicyValue(summary, safe, "executePythonSupported");
            copyPolicyValue(summary, safe, "executeJsSupported");
            copyPolicyValue(summary, safe, "solonAiSysSkillsWrapped");
            copyPolicyValue(summary, safe, "workdirTextValidated");
            copyPolicyValue(summary, safe, "scriptPreflightPathPolicy");
            copyPolicyValue(summary, safe, "scriptPreflightUrlPolicy");
            copyPolicyValue(summary, safe, "dangerousCommandRulesApplied");
            copyPolicyValue(summary, safe, "hardlineRulesApplied");
            copyPolicyValue(summary, safe, "foregroundBackgroundGuardrail");
            copyPolicyValue(summary, safe, "managedFileToolPathLiteralsIgnoredForPreflight");
            copyPolicyValue(summary, safe, "stagingDirectoryPerRun");
            copyPolicyValue(summary, safe, "stagingCleanup");
            copyPolicyValue(summary, safe, "sandboxEnvironmentSanitized");
            copyPolicyValue(summary, safe, "pythonPathPrependsStaging");
            copyPolicyValue(summary, safe, "pythonIoEncodingUtf8");
            copyPolicyValue(summary, safe, "pythonDontWriteBytecode");
            copyPolicyValue(summary, safe, "rpcToolBridgeEnabled");
            copyPolicyValue(summary, safe, "rpcRequestFilesSorted");
            copyPolicyValue(summary, safe, "rpcToolOutputsRedacted");
            copyPolicyValue(summary, safe, "defaultTimeoutSeconds");
            copyPolicyValue(summary, safe, "maxTimeoutClampedByTerminalConfig");
            copyPolicyValue(summary, safe, "timeoutKillsProcess");
            copyPolicyValue(summary, safe, "stdoutLimitChars");
            copyPolicyValue(summary, safe, "stderrLimitChars");
            copyPolicyValue(summary, safe, "ansiOutputStripped");
            copyPolicyValue(summary, safe, "outputRedacted");
            copyPolicyValue(summary, safe, "outputTruncated");
            copyPolicyValue(summary, safe, "stderrReturnedOnlyOnErrors");
            copyPolicyValue(summary, safe, "safeErrorTextRedacted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeSubprocessEnvironmentPolicySummary() {
        try {
            Map<String, Object> summary = SubprocessEnvironmentSanitizer.policySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "defaultDenyUnknownEnv");
            copyPolicyValue(summary, safe, "safePrefixCount");
            copyPolicyValue(summary, safe, "safeContextEnvCount");
            copyPolicyValue(summary, safe, "secretSubstringCount");
            copyPolicyValue(summary, safe, "providerBlocklistCount");
            copyPolicyValue(summary, safe, "configuredPassthroughCount");
            copyPolicyValue(summary, safe, "skillScopedPassthroughSupported");
            copyPolicyValue(summary, safe, "skillScopedPassthroughThreadLocal");
            copyPolicyValue(summary, safe, "providerBlocklistOverridesPassthrough");
            copyPolicyValue(summary, safe, "forcePrefixSupported");
            copyPolicyValue(summary, safe, "forcePrefixRequiresValidEnvName");
            copyPolicyValue(summary, safe, "secretNameSubstringsBlocked");
            copyPolicyValue(summary, safe, "runtimeSafetyTogglesBlocked");
            copyPolicyValue(summary, safe, "channelSecretsBlocked");
            copyPolicyValue(summary, safe, "toolBackendSecretsBlocked");
            copyPolicyValue(summary, safe, "gatewaySecretsBlocked");
            copyPolicyValue(summary, safe, "pathFallbackEnabledForPosix");
            copyPolicyValue(summary, safe, "windowsPathFallbackDisabled");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeAttachmentDownloadPolicySummary() {
        try {
            Map<String, Object> summary = BoundedAttachmentIO.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "hutoolDownloadGuarded");
            copyPolicyValue(summary, safe, "okHttpDownloadGuarded");
            copyPolicyValue(summary, safe, "initialUrlChecked");
            copyPolicyValue(summary, safe, "redirectUrlCheckedBeforeFollow");
            copyPolicyValue(summary, safe, "manualRedirectHandling");
            copyPolicyValue(summary, safe, "maxRedirects");
            copyPolicyValue(summary, safe, "redirectLocationRequired");
            copyPolicyValue(summary, safe, "redirectUrlResolvedAgainstCurrentUrl");
            copyPolicyValue(summary, safe, "crossHostHeaderForwardingBlocked");
            copyPolicyValue(summary, safe, "sameOriginHeadersAllowed");
            copyPolicyValue(summary, safe, "blockedUrlMasked");
            copyPolicyValue(summary, safe, "contentLengthChecked");
            copyPolicyValue(summary, safe, "streamReadBounded");
            copyPolicyValue(summary, safe, "defaultMaxBytes");
            copyPolicyValue(summary, safe, "jsonMaxBytes");
            copyPolicyValue(summary, safe, "updateJarMaxBytes");
            copyPolicyValue(summary, safe, "contentTypeCaptured");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeAttachmentMediaCachePolicySummary() {
        try {
            Map<String, Object> summary = new AttachmentCacheService(appConfig).policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "mediaReferencePrefix");
            copyPolicyValue(summary, safe, "maxCacheBytes");
            copyPolicyValue(summary, safe, "cacheBytesSizeChecked");
            copyPolicyValue(summary, safe, "safeOriginalNameSanitized");
            copyPolicyValue(summary, safe, "safeOriginalNameSecretRedacted");
            copyPolicyValue(summary, safe, "mimeSniffingEnabled");
            copyPolicyValue(summary, safe, "kindNormalized");
            copyPolicyValue(summary, safe, "fromLocalFileRequiresRuntimeCache");
            copyPolicyValue(summary, safe, "fromMediaCacheRequiresMediaRoot");
            copyPolicyValue(summary, safe, "mediaReferenceRequiresMediaRoot");
            copyPolicyValue(summary, safe, "mediaReferenceTraversalBlocked");
            copyPolicyValue(summary, safe, "generatedAttachmentSingleRuntimeLevelOnly");
            copyPolicyValue(summary, safe, "generatedAttachmentExtensionAllowlist");
            copyPolicyValue(summary, safe, "hostPathsNotReturnedInMediaReference");
            copyPolicyValue(summary, safe, "mediaRoot");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeAttachmentTerminalPastePolicySummary() {
        try {
            Map<String, Object> summary = CliAttachmentResolver.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "pastedLocalPathDetection");
            copyPolicyValue(summary, safe, "fileUriDetection");
            copyPolicyValue(summary, safe, "windowsPathDetection");
            copyPolicyValue(summary, safe, "posixPathDetection");
            copyPolicyValue(summary, safe, "pathPolicyCheckedBeforeCache");
            copyPolicyValue(summary, safe, "credentialPathBlocked");
            copyPolicyValue(summary, safe, "blockedPreviewRedacted");
            copyPolicyValue(summary, safe, "missingPreviewRedacted");
            copyPolicyValue(summary, safe, "resolvedDisplayNameRedacted");
            copyPolicyValue(summary, safe, "rawPathHiddenInPrompt");
            copyPolicyValue(summary, safe, "maxAttachmentPaths");
            copyPolicyValue(summary, safe, "maxAttachmentBytes");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
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

    private Map<String, Object> safeTerminalOutputPolicySummary() {
        try {
            Map<String, Object> summary = SolonClawShellSkill.terminalOutputPolicySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "ansiStripped");
            copyPolicyValue(summary, safe, "secretRedactionApplied");
            copyPolicyValue(summary, safe, "maxInlineChars");
            copyPolicyValue(summary, safe, "headTailTruncation");
            copyPolicyValue(summary, safe, "truncationNoticeIncluded");
            copyPolicyValue(summary, safe, "timeoutNoticeAppended");
            copyPolicyValue(summary, safe, "sudoFailureHintAppended");
            copyPolicyValue(summary, safe, "outputTransformersSupported");
            copyPolicyValue(summary, safe, "transformerFailureIsolated");
            copyPolicyValue(summary, safe, "exitCodeSemanticsAvailable");
            if (summary.get("exitCodeSemantics") instanceof Map) {
                safe.put(
                        "exitCodeSemantics",
                        safeTerminalExitCodeSemantics(
                                (Map<String, Object>) summary.get("exitCodeSemantics")));
            }
            copyPolicyValue(summary, safe, "foregroundRetryErrorsInterpreted");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeTerminalExitCodeSemantics(Map<String, Object> summary) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(summary, safe, "knownCommandCount");
        copyPolicyValue(summary, safe, "grepNoMatchExitOneInformational");
        copyPolicyValue(summary, safe, "diffExitOneInformational");
        copyPolicyValue(summary, safe, "gitDiffExitOneInformational");
        copyPolicyValue(summary, safe, "curlNetworkErrorsExplained");
        copyPolicyValue(summary, safe, "testExitOneInformational");
        copyPolicyValue(summary, safe, "findExitOnePartialResult");
        copyPolicyValue(summary, safe, "commandSamples");
        copyPolicyValue(summary, safe, "exitCodeSamples");
        return safe;
    }

    private Map<String, Object> safeToolResultStoragePolicySummary() {
        try {
            ToolResultStorageService service =
                    toolResultStorageService == null
                            ? new ToolResultStorageService(
                                    appConfig.getRuntime().getCacheDir(),
                                    appConfig.getTask().getToolOutputInlineLimit(),
                                    appConfig.getTask().getToolOutputTurnBudget(),
                                    appConfig.getTrace().getToolPreviewLength())
                            : toolResultStorageService;
            Map<String, Object> summary = service.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "interceptorBacked");
            copyPolicyValue(summary, safe, "inlineLimitBytes");
            copyPolicyValue(summary, safe, "turnBudgetBytes");
            copyPolicyValue(summary, safe, "previewLength");
            copyPolicyValue(summary, safe, "pinnedInlineTools");
            copyPolicyValue(summary, safe, "oversizedResultsPersisted");
            copyPolicyValue(summary, safe, "turnBudgetOverflowPersisted");
            copyPolicyValue(summary, safe, "persistedOutputBlock");
            copyPolicyValue(summary, safe, "resultRefReturned");
            copyPolicyValue(summary, safe, "readBackGuidanceIncluded");
            copyPolicyValue(summary, safe, "previewRedacted");
            copyPolicyValue(summary, safe, "persistedOutputRedacted");
            copyPolicyValue(summary, safe, "fullOutputSavedRaw");
            copyPolicyValue(summary, safe, "pathSegmentsSanitized");
            copyPolicyValue(summary, safe, "canonicalChildPathCheck");
            copyPolicyValue(summary, safe, "workspaceRelativeRefsPreferred");
            copyPolicyValue(summary, safe, "storageBase");
            copyPolicyValue(summary, safe, "describePersistedObservation");
            copyPolicyValue(summary, safe, "storageFailureFallsBackToPreviewOnly");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeSudoRewritePolicySummary() {
        try {
            boolean sudoPasswordConfigured =
                    appConfig != null
                            && appConfig.getTerminal() != null
                            && appConfig.getTerminal().getSudoPassword() != null;
            Map<String, Object> summary =
                    SolonClawShellSkill.sudoRewritePolicySummary(sudoPasswordConfigured);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "configured");
            copyPolicyValue(summary, safe, "configKey");
            copyPolicyValue(summary, safe, "rewritesRealSudoInvocations");
            copyPolicyValue(summary, safe, "stdinPasswordInjection");
            copyPolicyValue(summary, safe, "passwordRedacted");
            copyPolicyValue(summary, safe, "existingStdinFlagPreserved");
            copyPolicyValue(summary, safe, "commentsIgnored");
            copyPolicyValue(summary, safe, "quotedSudoIgnored");
            copyPolicyValue(summary, safe, "envAssignmentPrefixSupported");
            copyPolicyValue(summary, safe, "compoundCommandSupported");
            copyPolicyValue(summary, safe, "ptyDisabledForStdinPipe");
            copyPolicyValue(summary, safe, "missingPasswordHint");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeBackgroundProcessPolicySummary() {
        try {
            Map<String, Object> summary = ProcessTools.backgroundProcessPolicySummary(appConfig);
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "actions");
            copyPolicyValue(summary, safe, "processRegistryBacked");
            copyPolicyValue(summary, safe, "trackedSessionId");
            copyPolicyValue(summary, safe, "pidExposed");
            copyPolicyValue(summary, safe, "stdoutPreview");
            copyPolicyValue(summary, safe, "outputRedacted");
            copyPolicyValue(summary, safe, "completionEvents");
            copyPolicyValue(summary, safe, "stopSupported");
            copyPolicyValue(summary, safe, "stdinWriteSubmitCloseSupported");
            copyPolicyValue(summary, safe, "startDangerousCommandChecked");
            copyPolicyValue(summary, safe, "startHardlineBlocked");
            copyPolicyValue(summary, safe, "startPathPolicyChecked");
            copyPolicyValue(summary, safe, "startUrlPolicyChecked");
            copyPolicyValue(summary, safe, "currentThreadApprovalCanBypassStartCheck");
            copyPolicyValue(summary, safe, "stdinExecutionPayloadChecked");
            copyPolicyValue(summary, safe, "stdinExecutionTools");
            copyPolicyValue(summary, safe, "stdinPrivilegeWrapperDetection");
            copyPolicyValue(summary, safe, "waitTimeoutClamped");
            copyPolicyValue(summary, safe, "processWaitTimeoutSeconds");
            copyPolicyValue(summary, safe, "managedBackgroundRequiredForLongRunningCommands");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeTerminalGuardrailPolicySummary() {
        if (approvalService == null) {
            return unavailablePolicy("approval service is unavailable");
        }
        try {
            Map<String, Object> summary = approvalService.terminalGuardrailPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "backgroundShellWrappersBlocked");
            copyPolicyValue(summary, safe, "detachedSessionLaunchersBlocked");
            copyPolicyValue(summary, safe, "powershellBackgroundCommandsBlocked");
            copyPolicyValue(summary, safe, "inlineAmpersandBlocked");
            copyPolicyValue(summary, safe, "trailingAmpersandBlocked");
            copyPolicyValue(summary, safe, "longLivedForegroundBlocked");
            copyPolicyValue(summary, safe, "longLivedForegroundPatternCount");
            copyPolicyValue(summary, safe, "longLivedForegroundSamples");
            copyPolicyValue(summary, safe, "appliesToTools");
            copyPolicyValue(summary, safe, "commandPathPrechecked");
            copyPolicyValue(summary, safe, "credentialPathPrechecked");
            copyPolicyValue(summary, safe, "downloadOutputPathPrechecked");
            copyPolicyValue(summary, safe, "downloadOutputDetachedOptionPrechecked");
            copyPolicyValue(summary, safe, "proxyUrlPrechecked");
            copyPolicyValue(summary, safe, "preproxyUrlPrechecked");
            copyPolicyValue(summary, safe, "managedBackgroundProcessRequired");
            copyPolicyValue(summary, safe, "processRegistryBacked");
            copyPolicyValue(summary, safe, "sudoRewriteConfigured");
            copyPolicyValue(summary, safe, "sudoPasswordRedacted");
            copyPolicyValue(summary, safe, "powershellStartProcessRequiresWait");
            copyPolicyValue(summary, safe, "powershellStartProcessNoNewWindowNotEnough");
            copyPolicyValue(summary, safe, "powershellStartProcessPassThruNotEnough");
            copyPolicyValue(summary, safe, "foregroundMaxTimeoutSeconds");
            copyPolicyValue(summary, safe, "foregroundMaxRetries");
            copyPolicyValue(summary, safe, "foregroundRetryBaseDelaySeconds");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
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

    private Map<String, Object> safePathPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("security policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.pathPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "traversalBlocked");
            copyPolicyValue(summary, safe, "controlCharactersBlocked");
            copyPolicyValue(summary, safe, "rawControlCharactersBlocked");
            copyPolicyValue(summary, safe, "normalizedControlCharactersBlocked");
            copyPolicyValue(summary, safe, "devicePathBlocked");
            copyPolicyValue(summary, safe, "rawBlockDeviceWriteBlocked");
            copyPolicyValue(summary, safe, "skillsHubInternalReadBlocked");
            copyPolicyValue(summary, safe, "skillsHubInternalWriteBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketReadBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketWriteBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketAccessBlocked");
            copyPolicyValue(summary, safe, "localManagementSocketEnvironmentBlocked");
            copyPolicyValue(summary, safe, "localManagementPipeReadBlocked");
            copyPolicyValue(summary, safe, "localManagementPipeWriteBlocked");
            copyPolicyValue(summary, safe, "localManagementPipeAccessBlocked");
            copyPolicyValue(summary, safe, "writeSafeRootConfigured");
            copyPolicyValue(summary, safe, "writeDeniedExactPathCount");
            copyPolicyValue(summary, safe, "writeDeniedPrefixCount");
            copyPolicyValue(summary, safe, "writeDeniedHomeFileCount");
            copyPolicyValue(summary, safe, "blockedDevicePathCount");
            copyPolicyValue(summary, safe, "localManagementSocketPathCount");
            copyPolicyValue(summary, safe, "localManagementPipePathCount");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeCredentialPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("security policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.credentialPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "directorySegmentCount");
            copyPolicyValue(summary, safe, "fileNameCount");
            copyPolicyValue(summary, safe, "pathSuffixCount");
            copyPolicyValue(summary, safe, "keyFileExtensionCount");
            copyPolicyValue(summary, safe, "keyFileMarkerCount");
            copyPolicyValue(summary, safe, "configuredCredentialFileCount");
            copyPolicyValue(summary, safe, "envExampleFilesAllowed");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeToolArgsPolicySummary() {
        if (securityPolicyService == null) {
            return unavailablePolicy("security policy service is unavailable");
        }
        try {
            Map<String, Object> summary = securityPolicyService.toolArgsPolicySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "recursiveUrlExtraction");
            copyPolicyValue(summary, safe, "returnedContentUrlExtraction");
            copyPolicyValue(summary, safe, "returnedSchemelessUrlChecked");
            copyPolicyValue(summary, safe, "returnedDocumentContentChecked");
            copyPolicyValue(summary, safe, "recursivePathExtraction");
            copyPolicyValue(summary, safe, "encodedUrlParameterPolicyInherited");
            copyPolicyValue(summary, safe, "rawPathControlCharacterPolicyInherited");
            copyPolicyValue(summary, safe, "writeIntentDetection");
            copyPolicyValue(summary, safe, "patchTargetExtraction");
            copyPolicyValue(summary, safe, "downloadOutputPathOptionChecked");
            copyPolicyValue(summary, safe, "downloadOutputDetachedOptionChecked");
            copyPolicyValue(summary, safe, "proxyOptionUrlChecked");
            copyPolicyValue(summary, safe, "preproxyOptionUrlChecked");
            copyPolicyValue(summary, safe, "unsupportedNetworkSchemeChecked");
            return safe;
        } catch (Exception e) {
            return unavailablePolicy(e);
        }
    }

    private Map<String, Object> safeTirithPolicySummary() {
        if (tirithSecurityService == null) {
            return unavailablePolicy("tirith security service is unavailable");
        }
        try {
            Map<String, Object> summary = tirithSecurityService.policySummary();
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            copyPolicyValue(summary, safe, "enabled");
            copyPolicyValue(summary, safe, "configured");
            copyPolicyValue(summary, safe, "available");
            copyPolicyValue(summary, safe, "timeoutSeconds");
            copyPolicyValue(summary, safe, "failOpen");
            copyPolicyValue(summary, safe, "actions");
            copyPolicyValue(summary, safe, "warnRequiresApproval");
            copyPolicyValue(summary, safe, "blockRequiresApproval");
            copyPolicyValue(summary, safe, "commandPassedAsSingleArgument");
            copyPolicyValue(summary, safe, "nonInteractiveMode");
            copyPolicyValue(summary, safe, "jsonOutputMode");
            copyPolicyValue(summary, safe, "subprocessEnvironmentSanitized");
            copyPolicyValue(summary, safe, "timeoutKillsProcess");
            copyPolicyValue(summary, safe, "stdoutStderrCollectedSeparately");
            copyPolicyValue(summary, safe, "exitCodeZeroAllows");
            copyPolicyValue(summary, safe, "exitCodeOneBlocks");
            copyPolicyValue(summary, safe, "exitCodeTwoWarns");
            copyPolicyValue(summary, safe, "unexpectedExitCodeUsesFailureMode");
            copyPolicyValue(summary, safe, "parseFailureKeepsDecision");
            copyPolicyValue(summary, safe, "toolShellDetectionApplied");
            copyPolicyValue(summary, safe, "findingLimit");
            copyPolicyValue(summary, safe, "summaryLimit");
            copyPolicyValue(summary, safe, "secretRedaction");
            copyPolicyValue(summary, safe, "shellDetection");
            copyPolicyValue(summary, safe, "failOpenMode");
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
    private Map<String, Object> safeSecurityAuditResult(Map<String, Object> result) {
        if (result == null) {
            return result;
        }
        Object action = result.get("action");
        if (!("policy".equals(action) || "status".equals(action))) {
            return result;
        }
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(result, safe, "success");
        copyPolicyValue(result, safe, "action");
        copyPolicyValue(result, safe, "decision");
        copyPolicyValue(result, safe, "summary");
        copyPolicyValue(result, safe, "timestamp");
        Object policy = result.get("policy");
        if (policy instanceof Map) {
            safe.put("policy", safeSecurityAuditPolicy((Map<String, Object>) policy));
        }
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditPolicy(Map<String, Object> policy) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        Object approvals = policy.get("approvals");
        if (approvals instanceof Map) {
            safe.put("approvals", safeSecurityAuditApprovals((Map<String, Object>) approvals));
        }
        Object security = policy.get("security");
        if (security instanceof Map) {
            safe.put("security", safeSecurityAuditSecurity((Map<String, Object>) security));
        }
        Object terminal = policy.get("terminal");
        if (terminal instanceof Map) {
            safe.put("terminal", safeSecurityAuditTerminal((Map<String, Object>) terminal));
        }
        Object coverage = policy.get("coverage");
        if (coverage instanceof Map) {
            safe.put("coverage", safeSecurityAuditCoverage((Map<String, Object>) coverage));
        }
        copyPolicyValue(policy, safe, "activeSurfaces");
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditApprovals(Map<String, Object> approvals) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(approvals, safe, "mode");
        copyPolicyValue(approvals, safe, "smartMode");
        copyPolicyValue(approvals, safe, "smartJudgeConfigured");
        copyPolicyValue(approvals, safe, "smartApprovalActive");
        copyPolicyValue(approvals, safe, "smartCoversTirith");
        copyPolicyValue(approvals, safe, "cronMode");
        copyPolicyValue(approvals, safe, "cronAutoApprove");
        copyPolicyValue(approvals, safe, "subagentAutoApprove");
        copyPolicyValue(approvals, safe, "subagentApprovalDefault");
        copyPolicyValue(approvals, safe, "timeoutSeconds");
        copyPolicyValue(approvals, safe, "gatewayTimeoutSeconds");
        copyPolicyValue(approvals, safe, "mcpReloadConfirm");
        copyPolicyValue(approvals, safe, "mcpReloadConfirmationDefault");
        copyPolicyValue(approvals, safe, "alwaysApprovalCount");
        if (approvals.get("approvalPolicy") instanceof Map) {
            safe.put(
                    "approvalPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("approvalPolicy"),
                            "mode",
                            "cronMode",
                            "subagentAutoApprove",
                            "smartJudgeConfigured",
                            "dangerousRuleCount",
                            "hardlineRuleCount",
                            "urlPolicyPrechecked",
                            "privateUrlPolicyPrechecked",
                            "credentialUrlPolicyPrechecked",
                            "websitePolicyPrechecked",
                            "unsafeUrlBlockedBeforeApproval",
                            "unsafeUrlApprovalBypassAllowed",
                            "approvalTimeoutSeconds",
                            "gatewayTimeoutSeconds",
                            "alwaysApprovalCount"));
        }
        if (approvals.get("cronApprovalPolicy") instanceof Map) {
            safe.put(
                    "cronApprovalPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("cronApprovalPolicy"),
                            "mode",
                            "autoApprove",
                            "defaultDecision",
                            "dangerousCommandPrecheck",
                            "hardlineAlwaysBlocked"));
        }
        if (approvals.get("subagentApprovalPolicy") instanceof Map) {
            safe.put(
                    "subagentApprovalPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("subagentApprovalPolicy"),
                            "autoApprove",
                            "defaultDecision",
                            "sessionScoped",
                            "dangerousCommandPrecheck",
                            "hardlineAlwaysBlocked"));
        }
        if (approvals.get("smartApprovalPolicy") instanceof Map) {
            safe.put(
                    "smartApprovalPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("smartApprovalPolicy"),
                            "enabled",
                            "judgeConfigured",
                            "approvalBypassAllowed",
                            "humanApprovalPromptSuppressed",
                            "judgeFailureFallsBackToHumanApproval",
                            "commandPreviewRedacted"));
        }
        if (approvals.get("tirithApprovalPolicy") instanceof Map) {
            safe.put(
                    "tirithApprovalPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("tirithApprovalPolicy"),
                            "enabled",
                            "smartJudgeConfigured",
                            "warnRequiresApproval",
                            "blockRequiresApproval",
                            "alwaysScopeDowngradedToSession",
                            "commandPreviewRedacted"));
        }
        if (approvals.get("slashConfirmPolicy") instanceof Map) {
            safe.put(
                    "slashConfirmPolicy",
                    safeSlashConfirmPolicy((Map<String, Object>) approvals.get("slashConfirmPolicy")));
        }
        if (approvals.get("approvalCardPolicy") instanceof Map) {
            safe.put(
                    "approvalCardPolicy",
                    safeApprovalCardPolicy((Map<String, Object>) approvals.get("approvalCardPolicy")));
        }
        if (approvals.get("auditLogPolicy") instanceof Map) {
            safe.put(
                    "auditLogPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("auditLogPolicy"),
                            "requestEvents",
                            "responseEvents",
                            "observerFailureIsolated",
                            "approvalKeyRedacted",
                            "manualRevocationAudited",
                            "encodedUrlParameterRedacted"));
        }
        if (approvals.get("mcpReloadPolicy") instanceof Map) {
            safe.put(
                    "mcpReloadPolicy",
                    filterPolicyMap(
                            (Map<String, Object>) approvals.get("mcpReloadPolicy"),
                            "confirmRequired",
                            "defaultDecision",
                            "toolChangeNoticeInjected",
                            "oauthUrlSafetyCovered",
                            "unsafeSelectorRejected"));
        }
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditSecurity(Map<String, Object> security) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(security, safe, "allowPrivateUrls");
        if (security.get("urlPolicy") instanceof Map) {
            safe.put("urlPolicy", safeUrlPolicySummary());
        }
        copyPolicyValue(security, safe, "tirithEnabled");
        copyPolicyValue(security, safe, "tirithConfigured");
        copyPolicyValue(security, safe, "tirithTimeoutSeconds");
        copyPolicyValue(security, safe, "tirithFailOpen");
        copyPolicyValue(security, safe, "tirithAvailable");
        if (security.get("tirithPolicy") instanceof Map) {
            safe.put("tirithPolicy", safeTirithPolicySummary());
        }
        copyPolicyValue(security, safe, "websiteBlocklistEnabled");
        copyPolicyValue(security, safe, "websiteBlocklistDomainCount");
        copyPolicyValue(security, safe, "websiteBlocklistSharedFileCount");
        copyPolicyValue(security, safe, "websiteBlocklistSharedRuleCount");
        copyPolicyValue(security, safe, "websiteBlocklistLoadedSharedFileCount");
        copyPolicyValue(security, safe, "websiteBlocklistSkippedSharedFileCount");
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditTerminal(Map<String, Object> terminal) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        copyPolicyValue(terminal, safe, "credentialFileCount");
        if (terminal.get("credentialPolicy") instanceof Map) {
            safe.put("credentialPolicy", safeCredentialPolicySummary());
        }
        if (terminal.get("pathPolicy") instanceof Map) {
            safe.put("pathPolicy", safePathPolicySummary());
        }
        if (terminal.get("credentialMountPolicy") instanceof Map) {
            safe.put("credentialMountPolicy", safeCredentialFilePolicySummary());
        }
        copyPolicyValue(terminal, safe, "envPassthroughCount");
        copyPolicyValue(terminal, safe, "sudoPasswordConfigured");
        if (terminal.get("sudoRewritePolicy") instanceof Map) {
            safe.put("sudoRewritePolicy", safeSudoRewritePolicySummary());
        }
        if (terminal.get("terminalOutputPolicy") instanceof Map) {
            safe.put("terminalOutputPolicy", safeTerminalOutputPolicySummary());
        }
        copyPolicyValue(terminal, safe, "writeSafeRootConfigured");
        if (terminal.get("terminalGuardrailPolicy") instanceof Map) {
            safe.put("terminalGuardrailPolicy", safeTerminalGuardrailPolicySummary());
        }
        if (terminal.get("backgroundProcessPolicy") instanceof Map) {
            safe.put("backgroundProcessPolicy", safeBackgroundProcessPolicySummary());
        }
        copyPolicyValue(terminal, safe, "maxForegroundTimeoutSeconds");
        copyPolicyValue(terminal, safe, "foregroundMaxRetries");
        copyPolicyValue(terminal, safe, "foregroundRetryBaseDelaySeconds");
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeSecurityAuditCoverage(Map<String, Object> coverage) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (coverage.get("urlPolicyDetails") instanceof Map) {
            safe.put("urlPolicyDetails", safeUrlPolicySummary());
        }
        if (coverage.get("privateUrlPolicyDetails") instanceof Map) {
            safe.put("privateUrlPolicyDetails", safePrivateUrlPolicySummary());
        }
        if (coverage.get("websitePolicyDetails") instanceof Map) {
            safe.put("websitePolicyDetails", safeWebsitePolicySummary());
        }
        if (coverage.get("pathPolicyDetails") instanceof Map) {
            safe.put("pathPolicyDetails", safePathPolicySummary());
        }
        if (coverage.get("credentialPolicyDetails") instanceof Map) {
            safe.put("credentialPolicyDetails", safeCredentialPolicySummary());
        }
        if (coverage.get("toolArgsPolicy") instanceof Map) {
            safe.put("toolArgsPolicy", safeToolArgsPolicySummary());
        }
        if (coverage.get("schemaSanitizerPolicy") instanceof Map) {
            safe.put("schemaSanitizerPolicy", safeSchemaSanitizerPolicySummary());
        }
        if (coverage.get("patchParserPolicy") instanceof Map) {
            safe.put("patchParserPolicy", safePatchParserPolicySummary());
        }
        copyPolicyValue(coverage, safe, "readOnlyAuditPolicy");
        if (coverage.get("subprocessEnvironmentPolicy") instanceof Map) {
            safe.put("subprocessEnvironmentPolicy", safeSubprocessEnvironmentPolicySummary());
        }
        if (coverage.get("codeExecutionPolicy") instanceof Map) {
            safe.put("codeExecutionPolicy", safeCodeExecutionPolicySummary());
        }
        if (coverage.get("mcpRuntimePolicy") instanceof Map) {
            safe.put("mcpRuntimePolicy", safeMcpRuntimePolicySummary());
        }
        if (coverage.get("mcpOAuthPolicy") instanceof Map) {
            safe.put("mcpOAuthPolicy", safeMcpOAuthPolicySummary());
        }
        if (coverage.get("attachmentPolicy") instanceof Map) {
            safe.put(
                    "attachmentPolicy",
                    safeSecurityAuditAttachmentPolicy(
                            (Map<String, Object>) coverage.get("attachmentPolicy")));
        }
        if (coverage.get("toolResultStoragePolicy") instanceof Map) {
            safe.put("toolResultStoragePolicy", safeToolResultStoragePolicySummary());
        }
        if (coverage.get("approvalLifecyclePolicy") instanceof Map) {
            safe.put(
                    "approvalLifecyclePolicy",
                    safeApprovalLifecyclePolicy((Map<String, Object>) coverage.get("approvalLifecyclePolicy")));
        }
        if (coverage.get("slashConfirmPolicy") instanceof Map) {
            safe.put(
                    "slashConfirmPolicy",
                    safeSlashConfirmPolicy((Map<String, Object>) coverage.get("slashConfirmPolicy")));
        }
        if (coverage.get("approvalCardPolicy") instanceof Map) {
            safe.put(
                    "approvalCardPolicy",
                    safeApprovalCardPolicy((Map<String, Object>) coverage.get("approvalCardPolicy")));
        }
        if (coverage.get("approvalAuditPolicy") instanceof Map) {
            safe.put(
                    "approvalAuditPolicy",
                    safeApprovalAuditPolicy((Map<String, Object>) coverage.get("approvalAuditPolicy")));
        }
        if (coverage.get("mcpReloadPolicy") instanceof Map) {
            safe.put("mcpReloadPolicy", safeMcpReloadPolicy((Map<String, Object>) coverage.get("mcpReloadPolicy")));
        }
        copyAuditCoverageBooleans(coverage, safe);
        return safe;
    }

    private Map<String, Object> safeApprovalLifecyclePolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "pendingListPrunedBeforeRead",
                "selectorSupported",
                "selectorTokenPattern",
                "unsafeSelectorRejected",
                "listSupported",
                "statusAliasSupported",
                "approveAllSupported",
                "rejectAllSupported",
                "bulkRejectUsesSafeSelector",
                "clearSessionSupported",
                "clearAlwaysSupported",
                "clearAllSupported",
                "scopes",
                "alwaysScopeUsesGlobalSettings",
                "tirithAlwaysScopeDowngradedToSession",
                "currentThreadApprovalTtlMillis",
                "currentThreadApprovalEnabled",
                "approveRemovesPendingApproval",
                "rejectRemovesPendingApproval",
                "sessionSnapshotUpdated",
                "approvalRequestObserved",
                "approvalResponseObserved",
                "approverRedacted",
                "approvalKeyRedacted",
                "commandPreviewRedacted",
                "encodedUrlParameterRedacted");
    }

    private Map<String, Object> safeSlashConfirmPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "commands",
                "selectorSupported",
                "selectorTokenPattern",
                "unsafeSelectorRejected",
                "listSupported",
                "statusAliasSupported",
                "approveAllSupported",
                "denyAllSupported",
                "clearSessionSupported",
                "clearAlwaysSupported",
                "clearAllSupported",
                "scopes",
                "defaultScope",
                "managementCommands",
                "pendingQueueSupported",
                "pendingListHidesApprovalKey",
                "pendingListUsesSafeSelector",
                "pendingListShowsPatternKey",
                "sessionApprovalListShowsCountOnly",
                "alwaysApprovalListShowsCountOnly",
                "approvalCardDeliveryMode",
                "approvalCardPlatforms",
                "approvalCardActionKey",
                "approvalCardApproveAction",
                "approvalCardDenyAction",
                "approvalCardScopeKey",
                "approvalCardApprovalIdKey",
                "permanentApprovalAllowedExceptTirith",
                "tirithAlwaysDowngradedToSession",
                "approverRedacted",
                "commandPreviewRedacted",
                "encodedUrlParameterRedacted",
                "approvalMetadataRedacted",
                "observerEventsRedacted",
                "approvalTimeoutSeconds",
                "gatewayTimeoutSeconds");
    }

    private Map<String, Object> safeApprovalCardPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "deliveryMode",
                "supportedPlatforms",
                "unsupportedPlatformsReturnEmptyExtras",
                "actionKey",
                "approveAction",
                "denyAction",
                "scopeKey",
                "approvalIdKey",
                "scopeOptions",
                "defaultScope",
                "approvalIdSelectorSupported",
                "selectorTokenPattern",
                "unsafeSelectorRejected",
                "outboundApprovalIdSanitized",
                "unsafeApprovalIdFallsBackToKeySelector",
                "approveCommandGenerated",
                "denyCommandGenerated",
                "alwaysScopeCommandGenerated",
                "sessionScopeCommandGenerated",
                "tirithPermanentApprovalHidden",
                "commandPreviewRedacted",
                "descriptionPreviewRedacted",
                "toolNameRedacted",
                "rawCommandRedactedInExtras",
                "encodedUrlParameterRedacted",
                "semicolonUrlParameterRedacted",
                "fragmentUrlParameterRedacted");
    }

    private Map<String, Object> safeApprovalAuditPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "observerCount",
                "requestEvents",
                "responseEvents",
                "eventTypes",
                "repositoryBackedWhenConfigured",
                "observerFailureIsolated",
                "approverRedacted",
                "commandPreviewRedacted",
                "descriptionRedacted",
                "approvalKeyRedacted",
                "encodedUrlParameterRedacted",
                "commandHashStored",
                "patternKeysStored",
                "timestampsStored",
                "recentDashboardViewSupported",
                "manualRevocationAudited");
    }

    private Map<String, Object> safeMcpReloadPolicy(Map<String, Object> source) {
        return filterPolicyMap(
                source,
                "command",
                "confirmRequired",
                "configKey",
                "slashConfirmBacked",
                "directRunAlias",
                "alwaysConfirmAlias",
                "persistentDisableSupported",
                "runtimeConfigPersisted",
                "toolChangeNoticeInjected",
                "changedServerSummary",
                "toolCountSummary",
                "oauthUrlSafetyCovered",
                "encodedUrlParameterRedacted",
                "reloadHistoryNoticeRedacted");
    }

    private Map<String, Object> safeSecurityAuditAttachmentPolicy(Map<String, Object> attachment) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (attachment.get("downloadIo") instanceof Map) {
            safe.put("downloadIo", safeAttachmentDownloadPolicySummary());
        }
        if (attachment.get("mediaCache") instanceof Map) {
            safe.put("mediaCache", safeAttachmentMediaCachePolicySummary());
        }
        if (attachment.get("terminalPaste") instanceof Map) {
            safe.put("terminalPaste", safeAttachmentTerminalPastePolicySummary());
        }
        return safe;
    }

    private void copyAuditCoverageBooleans(Map<String, Object> source, Map<String, Object> target) {
        copyPolicyValue(source, target, "dangerousCommandApproval");
        copyPolicyValue(source, target, "slashApprovalConfirm");
        copyPolicyValue(source, target, "smartApproval");
        copyPolicyValue(source, target, "tirithSmartApproval");
        copyPolicyValue(source, target, "cronApprovalPolicy");
        copyPolicyValue(source, target, "subagentApprovalPolicy");
        copyPolicyValue(source, target, "approvalAuditLog");
        copyPolicyValue(source, target, "hardlineCommandBlocks");
        copyPolicyValue(source, target, "terminalGuardrails");
        copyPolicyValue(source, target, "sudoRewrite");
        copyPolicyValue(source, target, "backgroundProcessGuard");
        copyPolicyValue(source, target, "urlSafety");
        copyPolicyValue(source, target, "privateUrlPolicy");
        copyPolicyValue(source, target, "websitePolicy");
        copyPolicyValue(source, target, "credentialFilePolicy");
        copyPolicyValue(source, target, "credentialMountPolicy");
        copyPolicyValue(source, target, "pathSecurity");
        copyPolicyValue(source, target, "toolArgsSecurity");
        copyPolicyValue(source, target, "toolReturnedContentUrlSafety");
        copyPolicyValue(source, target, "schemaSanitizer");
        copyPolicyValue(source, target, "patchParser");
        copyPolicyValue(source, target, "subprocessEnvironmentSanitizer");
        copyPolicyValue(source, target, "toolResultStorage");
        copyPolicyValue(source, target, "codeExecutionGuardrails");
        copyPolicyValue(source, target, "codeExecutionPolicyAuditable");
        copyPolicyValue(source, target, "mcpUrlSafety");
        copyPolicyValue(source, target, "mcpReloadConfirmation");
        copyPolicyValue(source, target, "mcpToolChangeNotice");
        copyPolicyValue(source, target, "mcpRuntimePolicyAuditable");
        copyPolicyValue(source, target, "attachmentUrlSafety");
        copyPolicyValue(source, target, "attachmentCachePathSafety");
        copyPolicyValue(source, target, "attachmentDisplayNameRedaction");
        copyPolicyValue(source, target, "terminalAttachmentPathSafety");
        copyPolicyValue(source, target, "terminalAttachmentPreviewRedaction");
        copyPolicyValue(source, target, "terminalAttachmentResolvedNameRedaction");
        copyPolicyValue(source, target, "tirithSecurity");
        copyPolicyValue(source, target, "readOnlyAuditTool");
    }

    private Map<String, Object> filterPolicyMap(Map<String, Object> source, String... keys) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            copyPolicyValue(source, safe, key);
        }
        return safe;
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
        String selector = DangerousCommandApprovalService.approvalSelector(pending);
        item.put("approval_id", selector);
        item.put("selector", selector);
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
