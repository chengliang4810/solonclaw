package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Dashboard 网关 doctor 聚合服务。 */
public class DashboardGatewayDoctorService {
    private final AppConfig appConfig;
    private final DeliveryService deliveryService;
    private final LlmProviderService llmProviderService;
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;
    private final ShutdownForensicsService shutdownForensicsService;

    public DashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(
                appConfig,
                deliveryService,
                new LlmProviderService(appConfig),
                gatewayRuntimeRefreshService,
                null);
    }

    public DashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(appConfig, deliveryService, llmProviderService, gatewayRuntimeRefreshService, null);
    }

    public DashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            ShutdownForensicsService shutdownForensicsService) {
        this(
                appConfig,
                deliveryService,
                new LlmProviderService(appConfig),
                gatewayRuntimeRefreshService,
                shutdownForensicsService);
    }

    public DashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            ShutdownForensicsService shutdownForensicsService) {
        this.appConfig = appConfig;
        this.deliveryService = deliveryService;
        this.llmProviderService =
                llmProviderService == null ? new LlmProviderService(appConfig) : llmProviderService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.shutdownForensicsService = shutdownForensicsService;
    }

    public Map<String, Object> doctor() throws Exception {
        gatewayRuntimeRefreshService.refreshIfNeeded();
        List<Map<String, Object>> platforms = new ArrayList<Map<String, Object>>();
        List<ChannelStatus> statuses = deliveryService.statuses();
        for (String platform : Arrays.asList("feishu", "dingtalk", "wecom", "weixin")) {
            ChannelStatus status = findStatus(statuses, platform);
            if (status != null) {
                platforms.add(toDoctorItem(status));
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("generated_at", isoNow());
        result.put("runtime_home", runtimeReference(appConfig.getRuntime().getHome()));
        result.put("model", modelDoctor());
        result.put("last_shutdown", shutdownSummary());
        result.put("platforms", platforms);
        return result;
    }

    private Map<String, Object> shutdownSummary() {
        if (shutdownForensicsService == null) {
            return unavailableShutdownSummary();
        }
        Map<String, Object> record = shutdownForensicsService.lastShutdownRecord();
        File file = shutdownForensicsService.lastShutdownRecordFile();
        if (record == null || file == null) {
            return unavailableShutdownSummary();
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.TRUE);
        summary.put("record", runtimeReference(file.getAbsolutePath()));
        summary.put("timestamp", record.get("timestamp"));
        summary.put("timestamp_iso", safeObjectText(record.get("timestampIso"), 80));
        summary.put("reason", safeObjectText(record.get("reason"), 200));
        summary.put("uptime_ms", record.get("uptimeMs"));
        summary.put("pid", safeObjectText(record.get("pid"), 80));
        summary.put("memory", record.get("memory"));
        summary.put("threads", record.get("threads"));
        return summary;
    }

    private Map<String, Object> unavailableShutdownSummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.FALSE);
        return summary;
    }

    private String safeObjectText(Object value, int maxLength) {
        return SecretRedactor.redact(
                StrUtil.nullToEmpty(value == null ? null : String.valueOf(value)), maxLength);
    }

    private ChannelStatus findStatus(List<ChannelStatus> statuses, String platformName) {
        for (ChannelStatus status : statuses) {
            if (status.getPlatform() != null
                    && platformName.equalsIgnoreCase(status.getPlatform().name())) {
                return status;
            }
        }
        return null;
    }

    private Map<String, Object> toDoctorItem(ChannelStatus status) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put(
                "platform",
                status.getPlatform() == null ? null : status.getPlatform().name().toLowerCase());
        item.put("enabled", status.isEnabled());
        item.put("connected", status.isConnected());
        item.put("detail", SecretRedactor.redact(status.getDetail(), 1000));
        item.put("setup_state", status.getSetupState());
        item.put("connection_mode", status.getConnectionMode());
        item.put("missing_config", status.getMissingConfig());
        item.put("features", status.getFeatures());
        item.put("last_error_code", status.getLastErrorCode());
        item.put("last_error_message", SecretRedactor.redact(status.getLastErrorMessage(), 1000));
        item.put("reconnecting", Boolean.valueOf(status.isReconnecting()));
        item.put("reconnect_attempt", Integer.valueOf(status.getReconnectAttempt()));
        item.put("last_reconnect_at", Long.valueOf(status.getLastReconnectAt()));
        item.put("next_reconnect_at", Long.valueOf(status.getNextReconnectAt()));
        item.put("last_reconnect_error", SecretRedactor.redact(status.getLastReconnectError(), 1000));
        item.put("next_step", nextStep(status));
        return item;
    }

    private String nextStep(ChannelStatus status) {
        if (!status.isEnabled()) {
            return "在配置中启用该渠道。";
        }
        if (status.getMissingConfig() != null && !status.getMissingConfig().isEmpty()) {
            return "补齐缺失配置：" + join(status.getMissingConfig());
        }
        if ("connected".equalsIgnoreCase(status.getSetupState()) || status.isConnected()) {
            return "渠道已连通，可直接开始使用。";
        }
        if (status.isReconnecting()) {
            return "渠道连接失败，已排队自动重连。";
        }
        if (StrUtil.isNotBlank(status.getLastErrorMessage())) {
            return "修复最近一次连接错误后重试。";
        }
        if ("disabled".equalsIgnoreCase(status.getSetupState())) {
            return "渠道当前未启用。";
        }
        return "配置已就绪，等待网关连接。";
    }

    private String join(List<String> values) {
        StringBuilder buffer = new StringBuilder();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(value.trim());
        }
        return buffer.length() == 0 ? GatewayBehaviorConstants.NONE : buffer.toString();
    }

    private Map<String, Object> modelDoctor() {
        List<Map<String, Object>> checks = new ArrayList<Map<String, Object>>();
        String primaryKey = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
        Map<String, AppConfig.ProviderConfig> providers = llmProviderService.providers();
        AppConfig.ProviderConfig primary =
                StrUtil.isBlank(primaryKey) ? null : providers.get(primaryKey);

        addCheck(
                checks,
                primary != null,
                "provider_present",
                "当前 provider 存在。",
                StrUtil.isBlank(primaryKey)
                        ? "model.providerKey 为空。"
                        : "model.providerKey 未命中 providers。");

        String effectiveModel = "";
        if (primary != null) {
            effectiveModel =
                    StrUtil.isNotBlank(appConfig.getModel().getDefault())
                            ? appConfig.getModel().getDefault().trim()
                            : StrUtil.nullToEmpty(primary.getDefaultModel()).trim();
        }
        addCheck(
                checks,
                StrUtil.isNotBlank(effectiveModel),
                "model_present",
                "默认模型已配置。",
                "model.default 与 provider.defaultModel 均为空。");

        if (primary != null) {
            boolean requiresApiKey = requiresApiKey(primary);
            boolean hasApiKey = StrUtil.isNotBlank(primary.getApiKey());
            addCheck(
                    checks,
                    !requiresApiKey || hasApiKey,
                    hasApiKey ? "api_key_present" : "api_key_missing",
                    hasApiKey ? "API key 已配置。" : "该 provider 当前不要求 API key。",
                    "API key 缺失。");

            String baseUrlIssue = baseUrlIssue(primary.getBaseUrl());
            addCheck(
                    checks,
                    StrUtil.isBlank(baseUrlIssue),
                    StrUtil.isBlank(baseUrlIssue) ? "base_url_valid" : "base_url_invalid",
                    "baseUrl 通过静态格式检查。",
                    baseUrlIssue);
        }

        addFallbackChecks(checks, primaryKey, providers);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("setup_state", setupState(checks));
        result.put("provider", safeText(primaryKey, 160));
        result.put("provider_exists", Boolean.valueOf(primary != null));
        result.put("provider_name", primary == null ? "" : safeText(primary.getName(), 200));
        result.put("dialect", primary == null ? "" : safeText(primary.getDialect(), 80));
        result.put("base_url", primary == null ? "" : SecretRedactor.maskUrl(primary.getBaseUrl()));
        result.put(
                "has_api_key",
                Boolean.valueOf(primary != null && StrUtil.isNotBlank(primary.getApiKey())));
        result.put("effective_model", safeText(effectiveModel, 200));
        result.put("checks", checks);
        return result;
    }

    private void addFallbackChecks(
            List<Map<String, Object>> checks,
            String primaryKey,
            Map<String, AppConfig.ProviderConfig> providers) {
        List<AppConfig.FallbackProviderConfig> fallbackProviders = appConfig.getFallbackProviders();
        if (fallbackProviders == null || fallbackProviders.isEmpty()) {
            addCheck(checks, true, "fallback_empty", "未配置 fallback provider。", "");
            return;
        }
        Set<String> seen = new HashSet<String>();
        int index = 0;
        for (AppConfig.FallbackProviderConfig fallback : fallbackProviders) {
            String provider =
                    fallback == null ? "" : StrUtil.nullToEmpty(fallback.getProvider()).trim();
            String label = "fallbackProviders[" + index + "]";
            if (StrUtil.isBlank(provider)) {
                checks.add(checkItem(false, "fallback_missing", label + " provider 为空。"));
                index++;
                continue;
            }
            if (!providers.containsKey(provider)) {
                checks.add(
                        checkItem(
                                false,
                                "fallback_missing",
                                label + " 引用了不存在的 provider。"));
            }
            if (!seen.add(provider)) {
                checks.add(
                        checkItem(
                                false,
                                "fallback_duplicate",
                                label + " 与前序 fallback provider 重复。"));
            }
            if (StrUtil.equals(provider, primaryKey)) {
                checks.add(
                        checkItem(
                                false,
                                "fallback_matches_primary",
                                label + " 与当前主 provider 相同。"));
            }
            if (providers.containsKey(provider)
                    && seen.contains(provider)
                    && !StrUtil.equals(provider, primaryKey)) {
                String model =
                        StrUtil.isNotBlank(fallback.getModel())
                                ? fallback.getModel().trim()
                                : StrUtil.nullToEmpty(providers.get(provider).getDefaultModel()).trim();
                if (StrUtil.isBlank(model)) {
                    model = StrUtil.nullToEmpty(appConfig.getModel().getDefault()).trim();
                }
                checks.add(
                        checkItem(
                                StrUtil.isNotBlank(model),
                                "fallback_model_present",
                                label + " 模型可解析。"));
            }
            index++;
        }
    }

    private boolean requiresApiKey(AppConfig.ProviderConfig provider) {
        String dialect = LlmProviderSupport.normalizeDialect(provider.getDialect());
        if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
            return false;
        }
        String baseUrl = StrUtil.nullToEmpty(provider.getBaseUrl()).toLowerCase(Locale.ROOT);
        return !(baseUrl.contains("127.0.0.1")
                || baseUrl.contains("localhost")
                || baseUrl.contains("::1"));
    }

    private String baseUrlIssue(String baseUrl) {
        if (StrUtil.isBlank(baseUrl)) {
            return "baseUrl 为空。";
        }
        try {
            LlmProviderSupport.validateBaseUrl(baseUrl);
            return "";
        } catch (IllegalArgumentException e) {
            return "baseUrl 不安全或格式无效：" + SecretRedactor.redact(e.getMessage(), 400);
        }
    }

    private void addCheck(
            List<Map<String, Object>> checks,
            boolean passed,
            String code,
            String passMessage,
            String failMessage) {
        checks.add(checkItem(passed, code, passed ? passMessage : failMessage));
    }

    private Map<String, Object> checkItem(boolean passed, String code, String message) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("passed", Boolean.valueOf(passed));
        item.put("code", code);
        item.put("message", SecretRedactor.redact(message, 800));
        return item;
    }

    private String setupState(List<Map<String, Object>> checks) {
        for (Map<String, Object> check : checks) {
            if (Boolean.FALSE.equals(check.get("passed"))) {
                return "warning";
            }
        }
        return "ready";
    }

    private String safeText(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value).trim(), maxLength);
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

    private String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
}
