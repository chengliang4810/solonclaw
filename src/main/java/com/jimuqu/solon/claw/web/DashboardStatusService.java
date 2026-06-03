package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Dashboard 状态聚合服务。 */
public class DashboardStatusService {
    private final AppConfig appConfig;
    private final SessionRepository sessionRepository;
    private final DeliveryService deliveryService;
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;
    private final AppVersionService appVersionService;
    private final AppUpdateService appUpdateService;
    private final LlmProviderService llmProviderService;

    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.deliveryService = deliveryService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.appVersionService = appVersionService;
        this.appUpdateService = appUpdateService;
        this.llmProviderService = llmProviderService;
    }

    public Map<String, Object> getStatus() throws Exception {
        return getStatus(true);
    }

    public Map<String, Object> getStatus(boolean detailed) throws Exception {
        RuntimeStatusSnapshot snapshot = buildRuntimeStatusSnapshot(detailed);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active_sessions", Integer.valueOf(snapshot.activeSessions));
        if (detailed) {
            result.put("config_path", runtimeReference(appConfig.getRuntime().getConfigFile()));
        }
        result.put("config_version", configVersion());
        result.put(
                "gateway_exit_reason",
                detailed && snapshot.anyFatal ? snapshot.firstFatalDetail : null);
        if (detailed) {
            result.put("gateway_pid", parsePid());
        }
        result.put("gateway_platforms", snapshot.platformStates);
        result.put("gateway_running", Boolean.valueOf(snapshot.anyConnected));
        result.put("gateway_state", snapshot.gatewayState);
        result.put("gateway_updated_at", snapshot.updatedAt);
        if (detailed) {
            result.put("runtime_capabilities", buildRuntimeCapabilitiesSnapshot());
            result.put("runtime_status", buildRuntimeStatusSnapshot(snapshot, true));
            result.put("runtime_config_refresh", runtimeConfigRefreshStatus());
            result.put("solonclaw_home", runtimeReference(appConfig.getRuntime().getHome()));
        }
        result.put("latest_config_version", configVersion());
        result.put("release_date", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        AppUpdateService.VersionStatus versionStatus = appUpdateService.getVersionStatus(false);
        result.put("version", appVersionService.currentVersion());
        result.put("version_tag", appVersionService.currentTag());
        result.put("deployment_mode", appVersionService.deploymentMode());
        result.put("latest_version", versionStatus.getLatestVersion());
        result.put("latest_tag", versionStatus.getLatestTag());
        result.put("update_available", versionStatus.isUpdateAvailable());
        if (detailed) {
            result.put("release_url", SecretRedactor.maskUrl(versionStatus.getReleaseUrl()));
            result.put(
                    "release_api_url", SecretRedactor.maskUrl(versionStatus.getReleaseApiUrl()));
            result.put(
                    "update_error_message",
                    redact(versionStatus.getUpdateErrorMessage(), 1000));
        }
        result.put(
                "update_error_at",
                versionStatus.getUpdateErrorAt() > 0 ? versionStatus.getUpdateErrorAt() : null);
        return result;
    }

    public Map<String, Object> getHealthRuntimeSnapshot() throws Exception {
        RuntimeStatusSnapshot snapshot = buildRuntimeStatusSnapshot(false);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active_sessions", Integer.valueOf(snapshot.activeSessions));
        result.put("gateway_exit_reason", snapshot.firstFatalDetail);
        result.put("gateway_platforms", snapshot.platformStates);
        result.put("gateway_running", Boolean.valueOf(snapshot.anyConnected));
        result.put("gateway_state", snapshot.gatewayState);
        result.put("gateway_updated_at", snapshot.updatedAt);
        result.put("runtime_config_refresh", runtimeConfigRefreshStatus());
        result.put("runtime_capabilities", buildRuntimeCapabilitiesSnapshot());
        result.put("runtime_status", buildRuntimeStatusSnapshot(snapshot, false));
        return result;
    }

    public Map<String, Object> getModelInfo() {
        return getModelInfo(true);
    }

    public Map<String, Object> getModelInfo(boolean detailed) {
        LlmProviderService.ResolvedProvider resolved =
                llmProviderService.resolveEffectiveProvider(null);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", safeText(resolved.getModel(), 200));
        result.put("provider", safeText(resolved.getProviderKey(), 160));
        result.put("providerKey", safeText(resolved.getProviderKey(), 160));
        result.put("providerLabel", safeText(resolved.getLabel(), 200));
        result.put("dialect", safeText(resolved.getDialect(), 80));
        if (detailed) {
            result.put("baseUrl", SecretRedactor.maskUrl(resolved.getBaseUrl()));
            result.put("fallbackProviders", safeFallbackProviders());
        }
        result.put("auto_context_length", appConfig.getLlm().getContextWindowTokens());
        result.put("config_context_length", appConfig.getLlm().getContextWindowTokens());
        result.put("effective_context_length", appConfig.getLlm().getContextWindowTokens());

        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        ModelMetadata metadata = currentModelMetadata(resolved);
        capabilities.put("supports_tools", true);
        capabilities.put("supports_vision", Boolean.valueOf(metadata.isSupportsVision()));
        capabilities.put("supports_audio", Boolean.valueOf(metadata.isSupportsAudio()));
        capabilities.put("supports_attachment", Boolean.valueOf(metadata.isSupportsAttachment()));
        capabilities.put("supports_pdf", Boolean.valueOf(metadata.isSupportsPdf()));
        capabilities.put("supports_multimodal", Boolean.valueOf(metadata.isSupportsMultimodal()));
        capabilities.put("supports_reasoning", Boolean.valueOf(metadata.isSupportsReasoning()));
        capabilities.put(
                "supports_structured_output",
                Boolean.valueOf(metadata.isSupportsStructuredOutput()));
        capabilities.put("supports_open_weights", Boolean.valueOf(metadata.isSupportsOpenWeights()));
        capabilities.put("supports_interleaved", Boolean.valueOf(metadata.isSupportsInterleaved()));
        capabilities.put("source", metadata.getSource());
        capabilities.put("context_window", appConfig.getLlm().getContextWindowTokens());
        capabilities.put("max_output_tokens", appConfig.getLlm().getMaxTokens());
        capabilities.put("model_family", safeText(resolved.getDialect(), 80));
        result.put("capabilities", capabilities);
        return result;
    }

    private RuntimeStatusSnapshot buildRuntimeStatusSnapshot(boolean detailed) throws Exception {
        gatewayRuntimeRefreshService.refreshIfNeeded();
        List<SessionRecord> recentSessions = sessionRepository.listRecent(20);
        List<ChannelStatus> statuses = deliveryService.statuses();
        int activeSessions = 0;
        long activeCutoff = System.currentTimeMillis() - 5L * 60L * 1000L;
        for (SessionRecord record : recentSessions) {
            if (record.getUpdatedAt() >= activeCutoff) {
                activeSessions++;
            }
        }

        boolean anyEnabled = false;
        boolean anyConnected = false;
        boolean anyFatal = false;
        Map<String, Object> platformStates = new LinkedHashMap<String, Object>();
        String updatedAt = isoNow();
        for (ChannelStatus status : statuses) {
            if (status.isEnabled()) {
                anyEnabled = true;
            }
            if (status.isConnected()) {
                anyConnected = true;
            }
            String detail = redact(status.getDetail(), 1000);
            boolean fatal =
                    status.isEnabled()
                            && !status.isConnected()
                            && (StrUtil.isNotBlank(status.getLastErrorCode())
                                    || StrUtil.isNotBlank(status.getLastErrorMessage()));
            if (fatal) {
                anyFatal = true;
            }

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put(
                    "state",
                    status.isEnabled()
                            ? (status.isConnected()
                                    ? "connected"
                                    : (fatal ? "fatal" : "disconnected"))
                            : "disabled");
            item.put("updated_at", updatedAt);
            item.put("detail", detailed ? detail : publicDetail(status));
            item.put("setup_state", detailed ? status.getSetupState() : null);
            item.put("connection_mode", status.getConnectionMode());
            item.put("missing_config", detailed ? status.getMissingConfig() : null);
            item.put("features", detailed ? status.getFeatures() : null);
            item.put(
                    "error_message",
                    detailed && fatal
                            ? redact(
                                    StrUtil.blankToDefault(
                                            status.getLastErrorMessage(), detail),
                                    1000)
                            : null);
            item.put(
                    "error_code",
                    fatal
                            ? StrUtil.blankToDefault(
                                    status.getLastErrorCode(), "channel_unavailable")
                            : null);
            platformStates.put(status.getPlatform().name().toLowerCase(), item);
        }

        String gatewayState;
        if (anyConnected) {
            gatewayState = "running";
        } else if (anyFatal) {
            gatewayState = "startup_failed";
        } else {
            gatewayState = anyEnabled ? "starting" : "stopped";
        }

        RuntimeStatusSnapshot snapshot = new RuntimeStatusSnapshot();
        snapshot.activeSessions = activeSessions;
        snapshot.anyConnected = anyConnected;
        snapshot.anyFatal = anyFatal;
        snapshot.firstFatalDetail = anyFatal ? redact(firstFatalDetail(statuses), 1000) : null;
        snapshot.gatewayState = gatewayState;
        snapshot.platformStates = platformStates;
        snapshot.updatedAt = updatedAt;
        return snapshot;
    }

    private Map<String, Object> buildRuntimeCapabilitiesSnapshot() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("schema_version", Integer.valueOf(1));
        capabilities.put("service", "solon-claw");
        capabilities.put("dashboard_first", Boolean.TRUE);
        capabilities.put(
                "supported_model_protocols",
                new ArrayList<String>(LlmConstants.SUPPORTED_PROVIDERS));
        capabilities.put("supported_channels", supportedChannels());
        capabilities.put("runtime_config", runtimeConfigCapabilities());
        capabilities.put("diagnostics", diagnosticsCapabilities());
        capabilities.put("cron", cronCapabilities());
        capabilities.put("skills", skillsCapabilities());
        capabilities.put("memory", memoryCapabilities());
        capabilities.put("tool_safety", toolSafetyCapabilities());
        capabilities.put("multimodal", multimodalCapabilities());
        capabilities.put("pricing", pricingCapabilities());
        return capabilities;
    }

    private Map<String, Object> buildRuntimeStatusSnapshot(
            RuntimeStatusSnapshot snapshot, boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("schema_version", Integer.valueOf(1));
        status.put("service", "solon-claw");
        status.put("status", snapshot.anyFatal ? "degraded" : "ok");
        status.put("updated_at", snapshot.updatedAt);
        status.put("active_sessions", Integer.valueOf(snapshot.activeSessions));
        status.put("gateway", gatewayRuntimeStatus(snapshot));
        status.put("runtime_config", runtimeConfigStatus(detailed));
        status.put("diagnostics", diagnosticsStatus(snapshot));
        status.put("cron", cronStatus());
        status.put("skills", skillsStatus(detailed));
        status.put("memory", memoryStatus(detailed));
        status.put("tool_safety", toolSafetyStatus());
        status.put("multimodal", multimodalStatus());
        status.put("pricing", pricingStatus());
        status.put("model", runtimeModelStatus());
        return status;
    }

    private List<String> supportedChannels() {
        List<String> channels = new ArrayList<String>();
        for (PlatformType platform : PlatformType.values()) {
            if (platform == PlatformType.MEMORY) {
                continue;
            }
            channels.add(platform.name().toLowerCase());
        }
        return channels;
    }

    private Map<String, Object> runtimeConfigCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("runtime_config_file", Boolean.TRUE);
        capabilities.put("dashboard_editable", Boolean.TRUE);
        capabilities.put("hot_refresh", Boolean.TRUE);
        capabilities.put("secret_redaction", Boolean.TRUE);
        capabilities.put("runtime_reference_scheme", "runtime://");
        return capabilities;
    }

    private Map<String, Object> diagnosticsCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("health_detailed", Boolean.TRUE);
        capabilities.put("dashboard_status", Boolean.TRUE);
        capabilities.put("gateway_runtime_snapshot", Boolean.TRUE);
        capabilities.put("runtime_refresh_failures", Boolean.TRUE);
        capabilities.put("model_metadata", Boolean.TRUE);
        capabilities.put("stream_health", Boolean.TRUE);
        return capabilities;
    }

    private Map<String, Object> cronCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("enabled", Boolean.valueOf(appConfig.getScheduler().isEnabled()));
        capabilities.put("persistent_jobs", Boolean.TRUE);
        capabilities.put("channel_delivery", Boolean.TRUE);
        capabilities.put("memory_delivery", Boolean.TRUE);
        capabilities.put(
                "approval_mode", safeText(cronApprovalMode(), 80));
        capabilities.put(
                "legacy_approval_mode",
                safeText(appConfig.getScheduler().getCronApprovalMode(), 80));
        return capabilities;
    }

    private Map<String, Object> skillsCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("runtime_dir", Boolean.TRUE);
        capabilities.put("external_dirs", Boolean.TRUE);
        capabilities.put("template_vars", Boolean.valueOf(appConfig.getSkills().isTemplateVars()));
        capabilities.put("inline_shell", Boolean.valueOf(appConfig.getSkills().isInlineShell()));
        capabilities.put("curator", Boolean.valueOf(appConfig.getCurator().isEnabled()));
        return capabilities;
    }

    private Map<String, Object> memoryCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("context_files", Boolean.TRUE);
        capabilities.put("long_term_memory", Boolean.TRUE);
        capabilities.put("session_search", Boolean.TRUE);
        capabilities.put("post_task_learning", Boolean.valueOf(appConfig.getLearning().isEnabled()));
        capabilities.put("context_compression", Boolean.valueOf(appConfig.getCompression().isEnabled()));
        return capabilities;
    }

    private Map<String, Object> toolSafetyCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("dangerous_command_approval", Boolean.TRUE);
        capabilities.put("approval_mode", safeText(guardrailMode(), 80));
        capabilities.put("cron_approval_mode", safeText(cronApprovalMode(), 80));
        capabilities.put(
                "legacy_approval_mode", safeText(appConfig.getApprovals().getMode(), 80));
        capabilities.put(
                "legacy_cron_approval_mode",
                safeText(appConfig.getApprovals().getCronMode(), 80));
        capabilities.put(
                "subagent_auto_approve",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        capabilities.put("url_private_access_policy", Boolean.TRUE);
        capabilities.put(
                "website_blocklist",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        capabilities.put("tirith_scan", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        capabilities.put("checkpoint_rollback", Boolean.valueOf(appConfig.getRollback().isEnabled()));
        return capabilities;
    }

    private Map<String, Object> multimodalCapabilities() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        ModelMetadata metadata = resolved == null ? null : currentModelMetadata(resolved);
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("model_input", multimodalModelInputCapabilities(metadata));
        capabilities.put("image_generation", Boolean.TRUE);
        capabilities.put("tts", Boolean.TRUE);
        capabilities.put("transcription", Boolean.TRUE);
        capabilities.put("attachment_cache", Boolean.TRUE);
        return capabilities;
    }

    private Map<String, Object> pricingCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("usage_events", Boolean.TRUE);
        capabilities.put("cost_calculation", Boolean.TRUE);
        capabilities.put("builtin_price_count", Integer.valueOf(PriceCatalog.builtinDefaults().size()));
        capabilities.put("configured_price_count", Integer.valueOf(configuredPriceCount()));
        capabilities.put("effective_price_count", Integer.valueOf(effectivePriceCount()));
        capabilities.put("supports_cache_pricing", Boolean.TRUE);
        capabilities.put("supports_reasoning_pricing", Boolean.TRUE);
        return capabilities;
    }

    private Map<String, Object> gatewayRuntimeStatus(RuntimeStatusSnapshot snapshot) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("state", snapshot.gatewayState);
        status.put("running", Boolean.valueOf(snapshot.anyConnected));
        status.put("platforms", snapshot.platformStates);
        status.put("supported_channels", supportedChannels());
        status.put("active_agents", Integer.valueOf(snapshot.activeSessions));
        status.put("exit_reason", snapshot.firstFatalDetail);
        status.put("updated_at", snapshot.updatedAt);
        return status;
    }

    private Map<String, Object> runtimeConfigStatus(boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("config_version", Integer.valueOf(configVersion()));
        status.put("latest_config_version", Integer.valueOf(configVersion()));
        status.put("refresh", runtimeConfigRefreshStatus());
        if (detailed) {
            status.put("config_path", runtimeReference(appConfig.getRuntime().getConfigFile()));
            status.put("runtime_home", runtimeReference(appConfig.getRuntime().getHome()));
            status.put("context_dir", runtimeReference(appConfig.getRuntime().getContextDir()));
            status.put("skills_dir", runtimeReference(appConfig.getRuntime().getSkillsDir()));
            status.put("cache_dir", runtimeReference(appConfig.getRuntime().getCacheDir()));
            status.put("logs_dir", runtimeReference(appConfig.getRuntime().getLogsDir()));
            status.put("state_db", runtimeReference(appConfig.getRuntime().getStateDb()));
        }
        return status;
    }

    private Map<String, Object> diagnosticsStatus(RuntimeStatusSnapshot snapshot) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("state", snapshot.anyFatal ? "degraded" : "ok");
        status.put("gateway_state", snapshot.gatewayState);
        status.put(
                "runtime_refresh_failed",
                Boolean.valueOf(gatewayRuntimeRefreshService.lastFailureSnapshot() != null));
        status.put("updated_at", snapshot.updatedAt);
        return status;
    }

    private Map<String, Object> cronStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("enabled", Boolean.valueOf(appConfig.getScheduler().isEnabled()));
        status.put("tick_seconds", Integer.valueOf(appConfig.getScheduler().getTickSeconds()));
        status.put(
                "script_timeout_seconds",
                Integer.valueOf(appConfig.getScheduler().getScriptTimeoutSeconds()));
        status.put(
                "inactivity_timeout_seconds",
                Integer.valueOf(appConfig.getScheduler().getInactivityTimeoutSeconds()));
        status.put("approval_mode", safeText(cronApprovalMode(), 80));
        status.put(
                "legacy_approval_mode",
                safeText(appConfig.getScheduler().getCronApprovalMode(), 80));
        return status;
    }

    private String cronApprovalMode() {
        if (appConfig.getSecurity() == null) {
            return appConfig.getScheduler().getCronApprovalMode();
        }
        return StrUtil.blankToDefault(
                appConfig.getSecurity().getGuardrailCronMode(),
                appConfig.getScheduler().getCronApprovalMode());
    }

    private String guardrailMode() {
        if (appConfig.getSecurity() == null) {
            return appConfig.getApprovals().getMode();
        }
        return StrUtil.blankToDefault(
                appConfig.getSecurity().getGuardrailMode(), appConfig.getApprovals().getMode());
    }

    private Map<String, Object> skillsStatus(boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("template_vars", Boolean.valueOf(appConfig.getSkills().isTemplateVars()));
        status.put("inline_shell", Boolean.valueOf(appConfig.getSkills().isInlineShell()));
        status.put(
                "inline_shell_timeout_seconds",
                Integer.valueOf(appConfig.getSkills().getInlineShellTimeoutSeconds()));
        status.put("curator_enabled", Boolean.valueOf(appConfig.getCurator().isEnabled()));
        status.put("external_dir_count", Integer.valueOf(appConfig.getSkills().getExternalDirs().size()));
        if (detailed) {
            status.put("runtime_dir", runtimeReference(appConfig.getRuntime().getSkillsDir()));
        }
        return status;
    }

    private Map<String, Object> memoryStatus(boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("learning_enabled", Boolean.valueOf(appConfig.getLearning().isEnabled()));
        status.put("context_compression_enabled", Boolean.valueOf(appConfig.getCompression().isEnabled()));
        status.put(
                "compression_threshold_percent",
                Double.valueOf(appConfig.getCompression().getThresholdPercent()));
        status.put(
                "heartbeat_interval_minutes",
                Integer.valueOf(appConfig.getAgent().getHeartbeat().getIntervalMinutes()));
        if (detailed) {
            status.put("context_dir", runtimeReference(appConfig.getRuntime().getContextDir()));
        }
        return status;
    }

    private Map<String, Object> toolSafetyStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("approval_mode", safeText(guardrailMode(), 80));
        status.put("cron_approval_mode", safeText(cronApprovalMode(), 80));
        status.put("legacy_approval_mode", safeText(appConfig.getApprovals().getMode(), 80));
        status.put(
                "legacy_cron_approval_mode",
                safeText(appConfig.getApprovals().getCronMode(), 80));
        status.put(
                "subagent_auto_approve",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        status.put(
                "approval_timeout_seconds",
                Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        status.put(
                "gateway_approval_timeout_seconds",
                Integer.valueOf(appConfig.getApprovals().getGatewayTimeoutSeconds()));
        status.put(
                "allow_private_urls", Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        status.put(
                "website_blocklist_enabled",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        status.put("tirith_enabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        status.put("checkpoint_rollback_enabled", Boolean.valueOf(appConfig.getRollback().isEnabled()));
        return status;
    }

    private Map<String, Object> multimodalStatus() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        ModelMetadata metadata = resolved == null ? null : currentModelMetadata(resolved);
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("provider_configured", Boolean.valueOf(resolved != null));
        status.put("provider", resolved == null ? "" : safeText(resolved.getProviderKey(), 160));
        status.put("model", resolved == null ? "" : safeText(resolved.getModel(), 200));
        status.put("dialect", resolved == null ? "" : safeText(resolved.getDialect(), 80));
        status.put("model_input", multimodalModelInputCapabilities(metadata));
        status.put("image_generation", Boolean.TRUE);
        status.put("tts", Boolean.TRUE);
        status.put("transcription", Boolean.TRUE);
        status.put(
                "media_cache_ttl_hours",
                Integer.valueOf(appConfig.getTask().getMediaCacheTtlHours()));
        return status;
    }

    private Map<String, Object> multimodalModelInputCapabilities(ModelMetadata metadata) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("vision", Boolean.valueOf(metadata != null && metadata.isSupportsVision()));
        input.put("audio", Boolean.valueOf(metadata != null && metadata.isSupportsAudio()));
        input.put("attachments", Boolean.valueOf(metadata != null && metadata.isSupportsAttachment()));
        input.put("pdf", Boolean.valueOf(metadata != null && metadata.isSupportsPdf()));
        input.put("multimodal", Boolean.valueOf(metadata != null && metadata.isSupportsMultimodal()));
        return input;
    }

    private Map<String, Object> pricingStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("builtin_price_count", Integer.valueOf(PriceCatalog.builtinDefaults().size()));
        status.put("configured_price_count", Integer.valueOf(configuredPriceCount()));
        int effectiveCount = effectivePriceCount();
        status.put("effective_price_count", Integer.valueOf(effectiveCount));
        status.put("usage_cost_calculation", Boolean.TRUE);
        status.put("currency_default", "USD");
        status.put("catalog_available", Boolean.valueOf(effectiveCount > 0));
        status.put("pricing_available", Boolean.valueOf(currentModelPrice() != null));
        return status;
    }

    private Map<String, Object> runtimeModelStatus() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("provider_configured", Boolean.valueOf(resolved != null));
        status.put("provider", resolved == null ? "" : safeText(resolved.getProviderKey(), 160));
        status.put("model", resolved == null ? "" : safeText(resolved.getModel(), 200));
        status.put("dialect", resolved == null ? "" : safeText(resolved.getDialect(), 80));
        status.put("context_window", Integer.valueOf(appConfig.getLlm().getContextWindowTokens()));
        status.put("max_output_tokens", Integer.valueOf(appConfig.getLlm().getMaxTokens()));
        status.put("stream", Boolean.valueOf(appConfig.getLlm().isStream()));
        status.put("reasoning_effort", safeText(appConfig.getLlm().getReasoningEffort(), 80));
        return status;
    }

    private int configuredPriceCount() {
        return appConfig.getPricing() == null || appConfig.getPricing().getPrices() == null
                ? 0
                : appConfig.getPricing().getPrices().size();
    }

    private int effectivePriceCount() {
        return PriceCatalog.forConfig(appConfig).size();
    }

    private com.jimuqu.solon.claw.pricing.ModelPrice currentModelPrice() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        if (resolved == null) {
            return null;
        }
        return PriceCatalog.forConfig(appConfig).find(resolved.getProviderKey(), resolved.getModel());
    }

    private LlmProviderService.ResolvedProvider safeResolvedProvider() {
        try {
            return llmProviderService.resolveEffectiveProvider(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ModelMetadata currentModelMetadata(LlmProviderService.ResolvedProvider resolved) {
        AppConfig.ProviderConfig provider =
                appConfig.getProviders().get(resolved.getProviderKey());
        AppConfig.ProviderConfig effective = new AppConfig.ProviderConfig();
        if (provider != null) {
            effective.setName(provider.getName());
            effective.setBaseUrl(provider.getBaseUrl());
            effective.setApiKey(provider.getApiKey());
            effective.setDefaultModel(provider.getDefaultModel());
            effective.setDialect(provider.getDialect());
            effective.setSupportsVision(provider.getSupportsVision());
        }
        effective.setDefaultModel(resolved.getModel());
        effective.setDialect(resolved.getDialect());
        return new ModelMetadataService(appConfig).resolve(resolved.getProviderKey(), effective);
    }

    private Map<String, Object> runtimeConfigRefreshStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("last_failure", gatewayRuntimeRefreshService.lastFailureSnapshot());
        return status;
    }

    private List<Map<String, Object>> safeFallbackProviders() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (appConfig.getFallbackProviders() == null) {
            return items;
        }
        for (AppConfig.FallbackProviderConfig fallback : appConfig.getFallbackProviders()) {
            if (fallback == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("provider", safeText(fallback.getProvider(), 160));
            item.put("model", safeText(fallback.getModel(), 200));
            items.add(item);
        }
        return items;
    }

    private String publicDetail(ChannelStatus status) {
        if (!status.isEnabled()) {
            return "disabled";
        }
        return status.isConnected() ? "connected" : "disconnected";
    }

    private Integer parsePid() {
        try {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int index = runtimeName.indexOf('@');
            String pid = index > 0 ? runtimeName.substring(0, index) : runtimeName;
            return Integer.valueOf(pid);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstFatalDetail(List<ChannelStatus> statuses) {
        for (ChannelStatus status : statuses) {
            if (status.isEnabled()
                    && !status.isConnected()
                    && (StrUtil.isNotBlank(status.getLastErrorCode())
                            || StrUtil.isNotBlank(status.getLastErrorMessage()))) {
                return StrUtil.blankToDefault(status.getLastErrorMessage(), status.getDetail());
            }
        }
        return null;
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
        return "path://" + redact(name, 200);
    }

    private String normalized(File file) {
        String path = file.getAbsolutePath();
        if (File.separatorChar == '\\') {
            return path.toLowerCase(java.util.Locale.ROOT);
        }
        return path;
    }

    private String redact(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    private String safeText(String value, int maxLength) {
        return StrUtil.nullToEmpty(SecretRedactor.redact(value, maxLength));
    }

    private int configVersion() {
        java.io.File file = new java.io.File(appConfig.getRuntime().getConfigFile());
        if (!file.exists()) {
            return 0;
        }
        return (int) (file.lastModified() / 1000L);
    }

    private String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static class RuntimeStatusSnapshot {
        private int activeSessions;
        private boolean anyConnected;
        private boolean anyFatal;
        private String firstFatalDetail;
        private String gatewayState;
        private Map<String, Object> platformStates;
        private String updatedAt;
    }
}
