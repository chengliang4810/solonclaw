package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
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
    /** GENERICAPI键健康检查CHECKDIALECTS的统一常量值。 */
    private static final Set<String> GENERIC_API_KEY_HEALTH_CHECK_DIALECTS =
            new HashSet<String>(
                    Arrays.asList(
                            LlmConstants.PROVIDER_OPENAI, LlmConstants.PROVIDER_OPENAI_RESPONSES));

    /** DEDICATED健康检查CHECKDIALECTS的统一常量值。 */
    private static final Set<String> DEDICATED_HEALTH_CHECK_DIALECTS =
            new HashSet<String>(
                    Arrays.asList(LlmConstants.PROVIDER_GEMINI, LlmConstants.PROVIDER_ANTHROPIC));

    /** 注入应用配置，用于控制台消息网关诊断。 */
    private final AppConfig appConfig;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 注入消息网关运行时刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /** 注入关闭Forensics服务，用于调用对应业务能力。 */
    private final ShutdownForensicsService shutdownForensicsService;

    /**
     * 创建控制台消息网关诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
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

    /**
     * 创建控制台消息网关诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(appConfig, deliveryService, llmProviderService, gatewayRuntimeRefreshService, null);
    }

    /**
     * 创建控制台消息网关诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     */
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

    /**
     * 创建控制台消息网关诊断服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     */
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

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    public Map<String, Object> doctor() throws Exception {
        if (gatewayRuntimeRefreshService != null) {
            gatewayRuntimeRefreshService.refreshIfNeeded();
        }
        List<Map<String, Object>> platforms = new ArrayList<Map<String, Object>>();
        List<ChannelStatus> statuses = deliveryService.statuses();
        for (String platform : Arrays.asList("feishu", "dingtalk", "wecom", "weixin")) {
            ChannelStatus status = findStatus(statuses, platform);
            if (status != null) {
                platforms.add(toDoctorItem(status));
            }
        }

        Map<String, Object> model = modelDoctor();
        Map<String, Object> shutdown = shutdownSummary();
        Map<String, Object> config = configDoctor();

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("generated_at", isoNow());
        result.put("runtime_home", runtimeReference(appConfig.getRuntime().getHome()));
        result.put("model", model);
        result.put("last_shutdown", shutdown);
        result.put("config", config);
        result.put("platforms", platforms);
        result.put("summary", doctorSummary(model, platforms, shutdown, config));
        return result;
    }

    /**
     * 执行诊断摘要相关逻辑。
     *
     * @param model 模型名称。
     * @param platforms platforms 参数。
     * @param shutdown 关闭参数。
     * @param config 当前模块使用的配置对象。
     * @return 返回诊断Summary结果。
     */
    private Map<String, Object> doctorSummary(
            Map<String, Object> model,
            List<Map<String, Object>> platforms,
            Map<String, Object> shutdown,
            Map<String, Object> config) {
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        addModelIssues(issues, model);
        addPlatformIssues(issues, platforms);
        addShutdownIssues(issues, shutdown);
        addConfigIssues(issues, config);

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("issueCount", Integer.valueOf(issues.size()));
        summary.put("warningCount", Integer.valueOf(countSeverity(issues, "warning")));
        summary.put("highestSeverity", highestSeverity(issues));
        summary.put("issues", issues);
        summary.put("nextActions", nextActions(issues));
        return summary;
    }

    /**
     * 追加模型Issues。
     *
     * @param issues issues 参数。
     * @param model 模型名称。
     */
    @SuppressWarnings("unchecked")
    private void addModelIssues(List<Map<String, Object>> issues, Map<String, Object> model) {
        if (model == null || !(model.get("checks") instanceof List)) {
            return;
        }
        List<Map<String, Object>> checks = (List<Map<String, Object>>) model.get("checks");
        for (Map<String, Object> check : checks) {
            if (check == null || !Boolean.FALSE.equals(check.get("passed"))) {
                continue;
            }
            String code = safeObjectText(check.get("code"), 120);
            String message = safeObjectText(check.get("message"), 800);
            addIssue(issues, "warning", "model", code, "model", message, modelNextAction(code));
        }
    }

    /**
     * 追加平台Issues。
     *
     * @param issues issues 参数。
     * @param platforms platforms 参数。
     */
    private void addPlatformIssues(
            List<Map<String, Object>> issues, List<Map<String, Object>> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return;
        }
        for (Map<String, Object> platform : platforms) {
            if (platform == null || !Boolean.TRUE.equals(platform.get("enabled"))) {
                continue;
            }
            String platformName = safeObjectText(platform.get("platform"), 80);
            String target = StrUtil.isBlank(platformName) ? "platform" : "platform:" + platformName;
            List<String> missingConfig = safeStringList(platform.get("missing_config"), 240);
            if (!missingConfig.isEmpty()) {
                String joinedMissingConfig = join(missingConfig);
                addIssue(
                        issues,
                        "warning",
                        "platform",
                        "channel_missing_config",
                        target,
                        platformName + " 渠道缺失配置：" + joinedMissingConfig,
                        "补齐 " + platformName + " 渠道缺失配置：" + joinedMissingConfig + "。");
                continue;
            }
            String setupState = safeObjectText(platform.get("setup_state"), 120);
            String lastError = safeObjectText(platform.get("last_error_message"), 800);
            String lastReconnectError = safeObjectText(platform.get("last_reconnect_error"), 800);
            if ("error".equalsIgnoreCase(setupState)
                    || StrUtil.isNotBlank(lastError)
                    || StrUtil.isNotBlank(lastReconnectError)) {
                addIssue(
                        issues,
                        "warning",
                        "platform",
                        "channel_connection_failed",
                        target,
                        platformMessage(platformName, lastError, lastReconnectError),
                        "修复 " + platformName + " 渠道最近一次连接错误后重试。");
                continue;
            }
            if (Boolean.TRUE.equals(platform.get("reconnecting"))) {
                addIssue(
                        issues,
                        "warning",
                        "platform",
                        "channel_reconnecting",
                        target,
                        platformName + " 渠道连接失败，正在等待自动重连。",
                        "等待 " + platformName + " 自动重连，若持续失败请查看最近连接错误。");
                continue;
            }
            if (!Boolean.TRUE.equals(platform.get("connected"))
                    && !"connected".equalsIgnoreCase(setupState)) {
                addIssue(
                        issues,
                        "warning",
                        "platform",
                        "channel_disconnected",
                        target,
                        platformName + " 渠道已启用但尚未连通。",
                        "启动或重连 " + platformName + " 网关。");
            }
        }
    }

    /**
     * 追加关闭Issues。
     *
     * @param issues issues 参数。
     * @param shutdown 关闭参数。
     */
    private void addShutdownIssues(List<Map<String, Object>> issues, Map<String, Object> shutdown) {
        if (shutdown == null || !Boolean.TRUE.equals(shutdown.get("available"))) {
            return;
        }
        String reason = safeObjectText(shutdown.get("reason"), 240);
        if (!isAbnormalShutdownReason(reason)) {
            return;
        }
        addIssue(
                issues,
                "warning",
                "runtime",
                "last_shutdown_abnormal",
                "last_shutdown",
                "最近一次 shutdown 记录显示异常退出：" + reason,
                "查看 last_shutdown.record 并排查最近一次异常退出原因。");
    }

    /**
     * 追加配置Issues。
     *
     * @param issues issues 参数。
     * @param config 当前模块使用的配置对象。
     */
    private void addConfigIssues(List<Map<String, Object>> issues, Map<String, Object> config) {
        if (config == null || !Boolean.TRUE.equals(config.get("has_issues"))) {
            return;
        }
        int unknown = intValue(config.get("unknown_count"));
        int legacy = intValue(config.get("legacy_count"));
        int diffs = intValue(config.get("effective_diff_count"));
        if (unknown > 0) {
            addIssue(
                    issues,
                    "warning",
                    "config",
                    "config_unknown_keys",
                    "runtime_config",
                    "runtime/config.yml 存在未知配置键：" + unknown + " 个。",
                    "查看 config.unknown_keys，移除或迁移未生效的配置键。");
        }
        if (legacy > 0) {
            addIssue(
                    issues,
                    "warning",
                    "config",
                    "config_legacy_keys",
                    "runtime_config",
                    "runtime/config.yml 存在遗留配置键：" + legacy + " 个。",
                    "查看 config.legacy_keys，将遗留配置迁移到当前配置路径。");
        }
        if (diffs > 0) {
            addIssue(
                    issues,
                    "info",
                    "config",
                    "config_effective_drift",
                    "runtime_config",
                    "runtime/config.yml 与当前生效配置存在差异：" + diffs + " 个。",
                    "查看 config.effective_diffs，确认类型归一化、别名或默认值覆盖是否符合预期。");
        }
    }

    /**
     * 执行模型NextAction相关逻辑。
     *
     * @param code code 参数。
     * @return 返回模型Next Action结果。
     */
    private String modelNextAction(String code) {
        if ("provider_present".equals(code)) {
            return "修正 model.providerKey，确保它命中 providers 中的 provider。";
        }
        if ("model_present".equals(code)) {
            return "配置 model.default 或当前 provider.defaultModel。";
        }
        if ("api_key_missing".equals(code)) {
            return "为当前 provider 配置 API key，或改用本地免 key provider。";
        }
        if ("base_url_invalid".equals(code)) {
            return "修正当前 provider.baseUrl，确保使用安全且有效的 HTTP(S) 地址。";
        }
        if ("fallback_missing".equals(code)) {
            return "修正 fallbackProviders 中为空或不存在的 provider。";
        }
        if ("fallback_duplicate".equals(code)) {
            return "移除 fallbackProviders 中重复的 provider。";
        }
        if ("fallback_matches_primary".equals(code)) {
            return "从 fallbackProviders 中移除与 model.providerKey 相同的 provider。";
        }
        if ("fallback_model_present".equals(code)) {
            return "为 fallback provider 配置可解析的模型。";
        }
        return "修复模型 doctor 检查失败项：" + code + "。";
    }

    /**
     * 执行平台消息相关逻辑。
     *
     * @param platformName 平台名称参数。
     * @param lastError last错误参数。
     * @param lastReconnectError lastReconnect错误参数。
     * @return 返回平台消息结果。
     */
    private String platformMessage(
            String platformName, String lastError, String lastReconnectError) {
        String detail = StrUtil.isNotBlank(lastError) ? lastError : lastReconnectError;
        if (StrUtil.isBlank(detail)) {
            return platformName + " 渠道最近一次连接失败。";
        }
        return platformName + " 渠道最近一次连接失败：" + detail;
    }

    /**
     * 生成安全展示用的字符串列表。
     *
     * @param raw 原始输入值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe String List结果。
     */
    private List<String> safeStringList(Object raw, int maxLength) {
        List<String> values = new ArrayList<String>();
        if (!(raw instanceof List)) {
            return values;
        }
        for (Object value : (List<?>) raw) {
            String text = safeObjectText(value, maxLength);
            if (StrUtil.isNotBlank(text)) {
                values.add(text);
            }
        }
        return values;
    }

    /**
     * 追加Issue。
     *
     * @param issues issues 参数。
     * @param severity severity 参数。
     * @param section section 参数。
     * @param code code 参数。
     * @param target target 参数。
     * @param message 平台消息或错误消息。
     * @param nextAction nextAction 参数。
     */
    private void addIssue(
            List<Map<String, Object>> issues,
            String severity,
            String section,
            String code,
            String target,
            String message,
            String nextAction) {
        Map<String, Object> issue = new LinkedHashMap<String, Object>();
        issue.put("severity", safeText(severity, 40));
        issue.put("section", safeText(section, 80));
        issue.put("code", safeText(code, 120));
        issue.put("target", safeText(target, 160));
        issue.put("message", safeText(message, 800));
        issue.put("nextAction", safeText(nextAction, 800));
        issues.add(issue);
    }

    /**
     * 执行nextActions相关逻辑。
     *
     * @param issues issues 参数。
     * @return 返回next Actions结果。
     */
    private List<String> nextActions(List<Map<String, Object>> issues) {
        List<String> actions = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (Map<String, Object> issue : issues) {
            String action = safeObjectText(issue == null ? null : issue.get("nextAction"), 800);
            if (StrUtil.isNotBlank(action) && seen.add(action)) {
                actions.add(action);
            }
        }
        return actions;
    }

    /**
     * 执行次数Severity相关逻辑。
     *
     * @param issues issues 参数。
     * @param severity severity 参数。
     * @return 返回次数Severity结果。
     */
    private int countSeverity(List<Map<String, Object>> issues, String severity) {
        int count = 0;
        for (Map<String, Object> issue : issues) {
            if (issue != null && severity.equals(issue.get("severity"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 执行int值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回int Value结果。
     */
    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * 执行highestSeverity相关逻辑。
     *
     * @param issues issues 参数。
     * @return 返回highest Severity结果。
     */
    private String highestSeverity(List<Map<String, Object>> issues) {
        String highest = "none";
        int rank = 0;
        for (Map<String, Object> issue : issues) {
            String severity = issue == null ? "" : String.valueOf(issue.get("severity"));
            int currentRank = severityRank(severity);
            if (currentRank > rank) {
                highest = severity;
                rank = currentRank;
            }
        }
        return highest;
    }

    /**
     * 执行severityRank相关逻辑。
     *
     * @param severity severity 参数。
     * @return 返回severity Rank结果。
     */
    private int severityRank(String severity) {
        if ("error".equalsIgnoreCase(severity)) {
            return 3;
        }
        if ("warning".equalsIgnoreCase(severity)) {
            return 2;
        }
        if ("info".equalsIgnoreCase(severity)) {
            return 1;
        }
        return 0;
    }

    /**
     * 判断是否Abnormal Shutdown Reason。
     *
     * @param reason 原因参数。
     * @return 如果Abnormal Shutdown Reason满足条件则返回 true，否则返回 false。
     */
    private boolean isAbnormalShutdownReason(String reason) {
        String text = StrUtil.nullToEmpty(reason).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(text)) {
            return false;
        }
        if ("lifecycle_shutdown".equals(text)
                || "shutdown".equals(text)
                || "normal".equals(text)
                || text.startsWith("sigterm")
                || text.startsWith("interrupt")) {
            return false;
        }
        return text.contains("exception")
                || text.contains("error")
                || text.contains("failed")
                || text.contains("crash")
                || text.contains("panic")
                || text.contains("oom")
                || text.contains("sigkill");
    }

    /**
     * 执行配置诊断相关逻辑。
     *
     * @return 返回配置诊断结果。
     */
    private Map<String, Object> configDoctor() {
        Map<String, Object> config =
                RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome())
                        .diagnostics(appConfig);
        if (gatewayRuntimeRefreshService != null) {
            config.put("last_refresh_failure", gatewayRuntimeRefreshService.lastFailureSnapshot());
        }
        return config;
    }

    /**
     * 关闭摘要。
     *
     * @return 返回shutdown Summary结果。
     */
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

    /**
     * 执行unavailable关闭摘要相关逻辑。
     *
     * @return 返回unavailable Shutdown Summary结果。
     */
    private Map<String, Object> unavailableShutdownSummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("available", Boolean.FALSE);
        return summary;
    }

    /**
     * 生成安全展示用的Object文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe Object Text结果。
     */
    private String safeObjectText(Object value, int maxLength) {
        return SecretRedactor.redact(
                StrUtil.nullToEmpty(value == null ? null : String.valueOf(value)), maxLength);
    }

    /**
     * 查找状态。
     *
     * @param statuses statuses 参数。
     * @param platformName 平台名称参数。
     * @return 返回状态。
     */
    private ChannelStatus findStatus(List<ChannelStatus> statuses, String platformName) {
        for (ChannelStatus status : statuses) {
            if (status.getPlatform() != null
                    && platformName.equalsIgnoreCase(status.getPlatform().name())) {
                return status;
            }
        }
        return null;
    }

    /**
     * 转换为诊断Item。
     *
     * @param status 状态参数。
     * @return 返回转换后的诊断Item。
     */
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
        item.put(
                "last_reconnect_error",
                SecretRedactor.redact(status.getLastReconnectError(), 1000));
        item.put("next_step", nextStep(status));
        return item;
    }

    /**
     * 执行nextStep相关逻辑。
     *
     * @param status 状态参数。
     * @return 返回next Step结果。
     */
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

    /**
     * 执行join相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回join结果。
     */
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

    /**
     * 执行模型诊断相关逻辑。
     *
     * @return 返回模型诊断结果。
     */
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
        result.put("model_list_url", primary == null ? "" : modelListUrl(primaryKey, primary));
        result.put(
                "has_api_key",
                Boolean.valueOf(primary != null && StrUtil.isNotBlank(primary.getApiKey())));
        result.put("effective_model", safeText(effectiveModel, 200));
        result.put("health_checks", healthCheckSummary(primary));
        result.put("checks", checks);
        return result;
    }

    /**
     * 执行健康检查Check摘要相关逻辑。
     *
     * @param provider 模型或能力提供方。
     * @return 返回健康检查Check Summary结果。
     */
    private Map<String, Object> healthCheckSummary(AppConfig.ProviderConfig provider) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        String dialect =
                provider == null ? "" : LlmProviderSupport.normalizeDialect(provider.getDialect());
        String mode = "unavailable";
        String reason = "provider is missing";
        boolean genericBearer = false;
        boolean dedicated = false;
        boolean skipped = true;
        if (provider != null) {
            if (GENERIC_API_KEY_HEALTH_CHECK_DIALECTS.contains(dialect)) {
                mode = "generic_bearer_models";
                reason = "generic API-key model-list check is supported";
                genericBearer = true;
                skipped = false;
            } else if (DEDICATED_HEALTH_CHECK_DIALECTS.contains(dialect)) {
                mode = "dedicated";
                reason =
                        "dedicated provider protocol is used; skip generic Bearer model-list check";
                dedicated = true;
            } else if (LlmConstants.PROVIDER_OLLAMA.equals(dialect)) {
                mode = "local_runtime";
                reason = "local Ollama runtime does not require API-key health check";
            } else {
                mode = "static_only";
                reason = "provider dialect is not in the generic API-key health-check allowlist";
            }
        }
        summary.put("mode", mode);
        summary.put("generic_bearer", Boolean.valueOf(genericBearer));
        summary.put("dedicated", Boolean.valueOf(dedicated));
        summary.put("skipped", Boolean.valueOf(skipped));
        summary.put("reason", reason);
        return summary;
    }

    /**
     * 执行模型列表URL相关逻辑。
     *
     * @param providerKey 提供方键标识或键值。
     * @param provider 模型或能力提供方。
     * @return 返回模型List URL结果。
     */
    private String modelListUrl(String providerKey, AppConfig.ProviderConfig provider) {
        if (provider == null) {
            return "";
        }
        return SecretRedactor.maskUrl(
                LlmProviderSupport.buildModelListUrl(
                        providerKey, provider.getBaseUrl(), provider.getDialect()));
    }

    /**
     * 追加兜底Checks。
     *
     * @param checks checks 参数。
     * @param primaryKey primary键标识或键值。
     * @param providers 能力提供方列表。
     */
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
                checks.add(checkItem(false, "fallback_missing", label + " 引用了不存在的 provider。"));
            }
            if (!seen.add(provider)) {
                checks.add(
                        checkItem(
                                false, "fallback_duplicate", label + " 与前序 fallback provider 重复。"));
            }
            if (StrUtil.equals(provider, primaryKey)) {
                checks.add(
                        checkItem(false, "fallback_matches_primary", label + " 与当前主 provider 相同。"));
            }
            if (providers.containsKey(provider)
                    && seen.contains(provider)
                    && !StrUtil.equals(provider, primaryKey)) {
                String model =
                        StrUtil.isNotBlank(fallback.getModel())
                                ? fallback.getModel().trim()
                                : StrUtil.nullToEmpty(providers.get(provider).getDefaultModel())
                                        .trim();
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

    /**
     * 执行requiresApi键相关逻辑。
     *
     * @param provider 模型或能力提供方。
     * @return 返回requires Api键结果。
     */
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

    /**
     * 执行基础URLIssue相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回base URL Issue结果。
     */
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

    /**
     * 追加Check。
     *
     * @param checks checks 参数。
     * @param passed passed 参数。
     * @param code code 参数。
     * @param passMessage pass消息参数。
     * @param failMessage fail消息参数。
     */
    private void addCheck(
            List<Map<String, Object>> checks,
            boolean passed,
            String code,
            String passMessage,
            String failMessage) {
        checks.add(checkItem(passed, code, passed ? passMessage : failMessage));
    }

    /**
     * 检查Item。
     *
     * @param passed passed 参数。
     * @param code code 参数。
     * @param message 平台消息或错误消息。
     * @return 返回Item结果。
     */
    private Map<String, Object> checkItem(boolean passed, String code, String message) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("passed", Boolean.valueOf(passed));
        item.put("code", code);
        item.put("message", SecretRedactor.redact(message, 800));
        return item;
    }

    /**
     * 执行配置引导状态相关逻辑。
     *
     * @param checks checks 参数。
     * @return 返回配置引导状态。
     */
    private String setupState(List<Map<String, Object>> checks) {
        for (Map<String, Object> check : checks) {
            if (Boolean.FALSE.equals(check.get("passed"))) {
                return "warning";
            }
        }
        return "ready";
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe Text结果。
     */
    private String safeText(String value, int maxLength) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value).trim(), maxLength);
    }

    /**
     * 执行运行时引用相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回运行时Reference结果。
     */
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

    /**
     * 执行外部路径引用相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回外部路径Reference结果。
     */
    private String externalPathReference(String value) {
        String name = new File(StrUtil.nullToEmpty(value)).getName();
        if (StrUtil.isBlank(name)) {
            name = "external";
        }
        return "path://" + SecretRedactor.redact(name, 200);
    }

    /**
     * 执行normalized相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回normalized结果。
     */
    private String normalized(File file) {
        String path = file.getAbsolutePath();
        if (File.separatorChar == '\\') {
            return path.toLowerCase(java.util.Locale.ROOT);
        }
        return path;
    }

    /**
     * 执行isoNow相关逻辑。
     *
     * @return 返回iso Now结果。
     */
    private String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
}
