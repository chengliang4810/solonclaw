package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
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
        gatewayRuntimeRefreshService.refreshIfNeeded();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
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
            item.put("updated_at", isoNow());
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

        result.put("active_sessions", activeSessions);
        if (detailed) {
            result.put("config_path", runtimeReference(appConfig.getRuntime().getConfigFile()));
        }
        result.put("config_version", configVersion());
        result.put(
                "gateway_exit_reason",
                detailed && anyFatal ? redact(firstFatalDetail(statuses), 1000) : null);
        if (detailed) {
            result.put("gateway_pid", parsePid());
        }
        result.put("gateway_platforms", platformStates);
        result.put("gateway_running", anyConnected);
        result.put("gateway_state", gatewayState);
        result.put("gateway_updated_at", isoNow());
        if (detailed) {
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
        capabilities.put("supports_reasoning", true);
        capabilities.put("context_window", appConfig.getLlm().getContextWindowTokens());
        capabilities.put("max_output_tokens", appConfig.getLlm().getMaxTokens());
        capabilities.put("model_family", safeText(resolved.getDialect(), 80));
        result.put("capabilities", capabilities);
        return result;
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
}
