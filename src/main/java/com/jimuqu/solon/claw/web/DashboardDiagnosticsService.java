package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.support.LlmProviderService;
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
    private final DangerousCommandApprovalService approvalService;
    private final SecurityPolicyService securityPolicyService;
    private final TirithSecurityService tirithSecurityService;

    public DashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            DangerousCommandApprovalService approvalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
        this.llmProviderService = llmProviderService;
        this.toolRegistry = toolRegistry;
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

    private Map<String, Object> runtime() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("home", appConfig.getRuntime().getHome());
        map.put("state_db", appConfig.getRuntime().getStateDb());
        map.put("cache_dir", appConfig.getRuntime().getCacheDir());
        map.put("logs_dir", appConfig.getRuntime().getLogsDir());
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
            item.put("base_url", entry.getValue().getBaseUrl());
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
            item.put("last_error_message", status.getLastErrorMessage());
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
                "max_foreground_timeout_seconds",
                Integer.valueOf(appConfig.getTerminal().getMaxForegroundTimeoutSeconds()));
        terminal.put(
                "foreground_max_retries",
                Integer.valueOf(appConfig.getTerminal().getForegroundMaxRetries()));
        terminal.put(
                "foreground_retry_base_delay_seconds",
                Integer.valueOf(appConfig.getTerminal().getForegroundRetryBaseDelaySeconds()));
        map.put("terminal", terminal);
        return map;
    }

    private boolean canWriteParent(String path) {
        if (path == null) {
            return false;
        }
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent != null && parent.exists() && parent.canWrite();
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
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
