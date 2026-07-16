package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.provider.ImageGenProvider;
import com.jimuqu.solon.claw.profile.ProfileBeanResolver;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.profile.ProfileGatewayStatus;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.RuntimeProcessSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.LlmConstants;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.io.File;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 状态聚合服务。 */
public class DashboardStatusService {
    /** 记录 Dashboard 状态路径规范化失败的低敏诊断日志，不输出完整路径。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardStatusService.class);

    /** Dashboard 发布日期展示格式，保持原有 yyyy-MM-dd 输出。 */
    private static final DateTimeFormatter RELEASE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Dashboard 本地 ISO 时间格式，保持原有 yyyy-MM-dd'T'HH:mm:ssXXX 输出。 */
    private static final DateTimeFormatter ISO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 注入应用配置，用于控制台状态。 */
    private final AppConfig appConfig;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入投递服务，用于调用对应业务能力。 */
    private final DeliveryService deliveryService;

    /** 注入Agent运行控制服务，用于区分近期会话和真实运行中的任务。 */
    private final AgentRunControlService agentRunControlService;

    /** 注入消息网关工作区配置刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /** 注入应用版本服务，用于调用对应业务能力。 */
    private final AppVersionService appVersionService;

    /** 注入应用更新服务，用于调用对应业务能力。 */
    private final AppUpdateService appUpdateService;

    /** 注入大模型提供方服务，用于调用对应业务能力。 */
    private final LlmProviderService llmProviderService;

    /** 注入主动协作诊断服务，用于展示主动联系状态。 */
    private final ProactiveDiagnosticsService proactiveDiagnosticsService;

    /** 注入语音运行时服务，用于报告真实可用的 TTS 与独立 STT Provider。 */
    private final SpeechService speechService;

    /** 注入图像生成 Provider，用于报告真实可用状态。 */
    private final List<ImageGenProvider> imageGenProviders;

    /** 解析 Dashboard 显式选择的 Profile；为空时保持当前状态聚合行为。 */
    private final DashboardProfileContext profileContext;

    /** 命名 Profile multiplex 子运行时管理器，用于读取真实渠道连接状态。 */
    private final ProfileMultiplexRuntimeManager profileMultiplexRuntimeManager;

    /**
     * 创建控制台状态服务实例，并兼容未接入运行控制服务的测试或轻量调用路径。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param appVersionService 应用版本服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService) {
        this(
                appConfig,
                sessionRepository,
                deliveryService,
                null,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService,
                null);
    }

    /**
     * 创建控制台状态服务实例，并兼容尚未接入主动协作诊断的运行状态统计调用路径。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param appVersionService 应用版本服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService) {
        this(
                appConfig,
                sessionRepository,
                deliveryService,
                agentRunControlService,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService,
                null);
    }

    /**
     * 创建控制台状态服务实例，并兼容未接入运行控制服务但需要主动协作诊断的测试路径。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param appVersionService 应用版本服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param proactiveDiagnosticsService 主动协作诊断服务依赖。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService,
            ProactiveDiagnosticsService proactiveDiagnosticsService) {
        this(
                appConfig,
                sessionRepository,
                deliveryService,
                null,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService,
                proactiveDiagnosticsService);
    }

    /**
     * 创建控制台状态服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param appVersionService 应用版本服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param proactiveDiagnosticsService 主动协作诊断服务依赖。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService,
            ProactiveDiagnosticsService proactiveDiagnosticsService) {
        this(
                appConfig,
                sessionRepository,
                deliveryService,
                agentRunControlService,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService,
                proactiveDiagnosticsService,
                null);
    }

    /**
     * 创建控制台状态服务实例，并注入语音运行时以计算真实可用状态。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时刷新服务依赖。
     * @param appVersionService 应用版本服务依赖。
     * @param appUpdateService 应用更新服务依赖。
     * @param llmProviderService LLM 提供方服务依赖。
     * @param proactiveDiagnosticsService 主动协作诊断服务依赖。
     * @param speechService 语音运行时服务依赖。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService,
            ProactiveDiagnosticsService proactiveDiagnosticsService,
            SpeechService speechService) {
        this(
                appConfig,
                sessionRepository,
                deliveryService,
                agentRunControlService,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService,
                proactiveDiagnosticsService,
                speechService,
                null,
                null,
                null);
    }

    /**
     * 创建支持 Profile 状态作用域的 Dashboard 状态服务。
     *
     * @param appConfig 当前 JVM 配置。
     * @param sessionRepository 当前 JVM 会话仓储。
     * @param deliveryService 当前 JVM 渠道投递服务。
     * @param agentRunControlService 当前 JVM Agent 运行控制服务。
     * @param gatewayRuntimeRefreshService 当前 JVM 网关刷新服务。
     * @param appVersionService 应用版本服务。
     * @param appUpdateService 应用更新服务。
     * @param llmProviderService 当前 JVM Provider 服务。
     * @param proactiveDiagnosticsService 主动协作诊断服务。
     * @param speechService 语音运行时服务。
     * @param imageGenProviders 图像生成 Provider 列表。
     * @param profileContext Dashboard Profile 请求上下文。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService,
            ProactiveDiagnosticsService proactiveDiagnosticsService,
            SpeechService speechService,
            List<ImageGenProvider> imageGenProviders,
            DashboardProfileContext profileContext) {
        this(
                appConfig,
                sessionRepository,
                deliveryService,
                agentRunControlService,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService,
                proactiveDiagnosticsService,
                speechService,
                imageGenProviders,
                profileContext,
                null);
    }

    /**
     * 创建支持 multiplex Profile 实时渠道状态的 Dashboard 状态服务。
     *
     * @param appConfig 当前 JVM 配置。
     * @param sessionRepository 当前 JVM 会话仓储。
     * @param deliveryService 当前 JVM 渠道投递服务。
     * @param agentRunControlService 当前 JVM Agent 运行控制服务。
     * @param gatewayRuntimeRefreshService 当前 JVM 网关刷新服务。
     * @param appVersionService 应用版本服务。
     * @param appUpdateService 应用更新服务。
     * @param llmProviderService 当前 JVM Provider 服务。
     * @param proactiveDiagnosticsService 主动协作诊断服务。
     * @param speechService 语音运行时服务。
     * @param imageGenProviders 图像生成 Provider 列表。
     * @param profileContext Dashboard Profile 请求上下文。
     * @param profileMultiplexRuntimeManager 命名 Profile multiplex 子运行时管理器。
     */
    public DashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService,
            ProactiveDiagnosticsService proactiveDiagnosticsService,
            SpeechService speechService,
            List<ImageGenProvider> imageGenProviders,
            DashboardProfileContext profileContext,
            ProfileMultiplexRuntimeManager profileMultiplexRuntimeManager) {
        this.appConfig = appConfig;
        this.sessionRepository = sessionRepository;
        this.deliveryService = deliveryService;
        this.agentRunControlService = agentRunControlService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.appVersionService = appVersionService;
        this.appUpdateService = appUpdateService;
        this.llmProviderService = llmProviderService;
        this.proactiveDiagnosticsService = proactiveDiagnosticsService;
        this.speechService = speechService;
        this.imageGenProviders = imageGenProviders;
        this.profileContext = profileContext;
        this.profileMultiplexRuntimeManager = profileMultiplexRuntimeManager;
    }

    /**
     * 读取状态。
     *
     * @return 返回读取到的状态。
     */
    public Map<String, Object> getStatus() throws Exception {
        return getStatus(true);
    }

    /**
     * 读取状态。
     *
     * @param detailed detailed 参数。
     * @return 返回读取到的状态。
     */
    public Map<String, Object> getStatus(boolean detailed) throws Exception {
        RuntimeStatusSnapshot snapshot = buildRuntimeStatusSnapshot(detailed);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active_sessions", Integer.valueOf(snapshot.activeSessions));
        result.put("running_agent_runs", Integer.valueOf(snapshot.runningAgentRuns));
        if (detailed) {
            result.put("config_path", runtimeReference(appConfig.getRuntime().getConfigFile()));
        }
        result.put("config_version", configVersion());
        result.put(
                "gateway_exit_reason",
                detailed && snapshot.anyFatal ? snapshot.firstFatalDetail : null);
        if (detailed) {
            result.put("gateway_pid", RuntimeProcessSupport.currentPid());
        }
        result.put("gateway_platforms", snapshot.platformStates);
        result.put("gateway_running", Boolean.valueOf(snapshot.anyConnected));
        result.put("gateway_state", snapshot.gatewayState);
        result.put("gateway_updated_at", snapshot.updatedAt);
        appendProfileGatewayTopology(result, detailed);
        if (detailed) {
            result.put("runtime_capabilities", buildRuntimeCapabilitiesSnapshot());
            result.put("runtime_status", buildRuntimeStatusSnapshot(snapshot, true));
            result.put("workspace_config_refresh", runtimeConfigRefreshStatus());
            result.put("solonclaw_home", runtimeReference(appConfig.getRuntime().getHome()));
        }
        if (proactiveDiagnosticsService != null) {
            result.put("proactive", proactiveDiagnosticsService.status());
        }
        result.put("latest_config_version", configVersion());
        result.put("release_date", RELEASE_DATE_FORMATTER.format(LocalDate.now()));
        AppUpdateService.VersionStatus versionStatus = appUpdateService.getVersionStatus(false);
        result.put("version", appVersionService.currentVersion());
        result.put("version_tag", appVersionService.currentTag());
        result.put("deployment_mode", appVersionService.deploymentMode());
        result.put("latest_version", versionStatus.getLatestVersion());
        result.put("latest_tag", versionStatus.getLatestTag());
        result.put("update_available", versionStatus.isUpdateAvailable());
        if (detailed) {
            result.put("release_url", SecretRedactor.maskUrl(versionStatus.getReleaseUrl()));
            result.put("release_api_url", SecretRedactor.maskUrl(versionStatus.getReleaseApiUrl()));
            result.put("update_error_message", redact(versionStatus.getUpdateErrorMessage(), 1000));
        }
        result.put(
                "update_error_at",
                versionStatus.getUpdateErrorAt() > 0 ? versionStatus.getUpdateErrorAt() : null);
        return result;
    }

    /**
     * 追加机器级 Profile 与网关拓扑；Profile 名和模式低敏，网关端口仅详细状态返回。
     *
     * <p>复用模式以 default 网关状态文件的 {@code served_profiles} 为准，避免仅凭配置误报尚未启动的拓扑。
     */
    @SuppressWarnings("unchecked")
    private void appendProfileGatewayTopology(Map<String, Object> result, boolean detailed) {
        if (profileContext == null) {
            return;
        }
        List<String> profiles = new ArrayList<String>();
        List<Map<String, Object>> gateways = new ArrayList<Map<String, Object>>();
        int runningGatewayCount = 0;
        boolean multiplex = false;
        try {
            for (com.jimuqu.solon.claw.profile.ProfileView view :
                    profileContext.profileManager().listProfileViews()) {
                profiles.add(view.getName());
                ProfileGatewayStatus gateway = view.getGateway();
                if (gateway == null || !gateway.isRunning()) {
                    continue;
                }
                runningGatewayCount++;
                Map<String, Object> state = gateway.getState();
                List<String> served = new ArrayList<String>();
                Object rawServed = state.get("served_profiles");
                if (rawServed instanceof Iterable) {
                    for (Object item : (Iterable<Object>) rawServed) {
                        String name = item == null ? "" : String.valueOf(item).trim();
                        if (name.length() > 0) {
                            served.add(name);
                        }
                    }
                }
                if ("default".equals(view.getName()) && served.size() > 1) {
                    multiplex = true;
                }
                if (detailed) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("profile", view.getName());
                    item.put("port", gateway.getPort());
                    if (!served.isEmpty()) {
                        item.put("served_profiles", served);
                    }
                    gateways.add(item);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Profile gateway topology enumeration failed: {}",
                    e.getClass().getSimpleName());
            result.put("profiles", Collections.emptyList());
            result.put("gateway_mode", "unknown");
            if (detailed) {
                result.put("gateways", Collections.emptyList());
            }
            return;
        }
        result.put("profiles", profiles);
        result.put(
                "gateway_mode",
                multiplex
                        ? "multiplex"
                        : runningGatewayCount > 1
                                ? "multiple"
                                : runningGatewayCount == 1 ? "single" : "none");
        if (detailed) {
            result.put("gateways", gateways);
        }
    }

    /**
     * 读取指定 Profile 的状态；显式非当前 Profile 从其独立 PID、状态文件和配置生成快照。
     *
     * @param detailed 是否包含详细状态。
     * @param profile Profile 名。
     * @return Profile 状态。
     */
    public Map<String, Object> getStatus(boolean detailed, String profile) throws Exception {
        if (profileContext == null) {
            return getStatus(detailed);
        }
        DashboardProfileContext.Scope scope = profileContext.resolve(profile);
        if (scope.isCurrent()) {
            return getStatus(detailed);
        }
        List<ChannelStatus> multiplexStatuses =
                multiplexRuntimeManager() == null
                        ? null
                        : multiplexRuntimeManager().deliveryStatuses(scope.getName());
        if (multiplexStatuses != null) {
            return detachedService(scope.getConfig())
                    .multiplexStatus(scope.getName(), multiplexStatuses, detailed);
        }
        ProfileGatewayStatus gateway = profileContext.gatewayStatus(scope);
        return detachedService(scope.getConfig())
                .detachedStatus(scope.getName(), gateway, detailed);
    }

    /** 延迟解析 Profile 子运行时管理器，避免 Dashboard 状态服务与工具注册表形成启动循环。 */
    private ProfileMultiplexRuntimeManager multiplexRuntimeManager() {
        return profileMultiplexRuntimeManager == null
                ? ProfileBeanResolver.getBean(ProfileMultiplexRuntimeManager.class)
                : profileMultiplexRuntimeManager;
    }

    /**
     * 读取健康检查运行时Snapshot。
     *
     * @return 返回读取到的健康检查运行时Snapshot。
     */
    public Map<String, Object> getHealthRuntimeSnapshot() throws Exception {
        RuntimeStatusSnapshot snapshot = buildRuntimeStatusSnapshot(false);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("active_sessions", Integer.valueOf(snapshot.activeSessions));
        result.put("running_agent_runs", Integer.valueOf(snapshot.runningAgentRuns));
        result.put("gateway_exit_reason", snapshot.firstFatalDetail);
        result.put("gateway_platforms", snapshot.platformStates);
        result.put("gateway_running", Boolean.valueOf(snapshot.anyConnected));
        result.put("gateway_state", snapshot.gatewayState);
        result.put("gateway_updated_at", snapshot.updatedAt);
        result.put("workspace_config_refresh", runtimeConfigRefreshStatus());
        result.put("runtime_capabilities", buildRuntimeCapabilitiesSnapshot());
        result.put("runtime_status", buildRuntimeStatusSnapshot(snapshot, false));
        if (proactiveDiagnosticsService != null) {
            result.put("proactive", proactiveDiagnosticsService.status());
        }
        return result;
    }

    /**
     * 读取模型Info。
     *
     * @return 返回读取到的模型Info。
     */
    public Map<String, Object> getModelInfo() {
        return getModelInfo(true);
    }

    /**
     * 读取模型Info。
     *
     * @param detailed detailed 参数。
     * @return 返回读取到的模型Info。
     */
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
        capabilities.put(
                "supports_open_weights", Boolean.valueOf(metadata.isSupportsOpenWeights()));
        capabilities.put("supports_interleaved", Boolean.valueOf(metadata.isSupportsInterleaved()));
        capabilities.put("source", metadata.getSource());
        capabilities.put("context_window", appConfig.getLlm().getContextWindowTokens());
        capabilities.put("max_output_tokens", appConfig.getLlm().getMaxTokens());
        capabilities.put("model_family", safeText(resolved.getDialect(), 80));
        result.put("capabilities", capabilities);
        return result;
    }

    /**
     * 读取指定 Profile 的模型信息。
     *
     * @param detailed 是否包含详细字段。
     * @param profile Profile 名。
     * @return 模型元数据。
     */
    public Map<String, Object> getModelInfo(boolean detailed, String profile) {
        if (profileContext == null) {
            return getModelInfo(detailed);
        }
        DashboardProfileContext.Scope scope = profileContext.resolve(profile);
        if (scope.isCurrent()) {
            return getModelInfo(detailed);
        }
        return detachedService(scope.getConfig()).getModelInfo(detailed);
    }

    /** 创建只读取目标 Profile 配置、不访问当前 JVM 渠道和仓储的状态服务。 */
    private DashboardStatusService detachedService(AppConfig scopedConfig) {
        return new DashboardStatusService(
                scopedConfig,
                null,
                null,
                null,
                null,
                appVersionService,
                appUpdateService,
                DashboardProfileContext.snapshotProviderService(scopedConfig),
                null,
                null,
                null,
                null,
                null);
    }

    /** 构造由 default 进程内 multiplex 子运行时承载的命名 Profile 状态。 */
    private Map<String, Object> multiplexStatus(
            String profile, List<ChannelStatus> statuses, boolean detailed) {
        RuntimeStatusSnapshot snapshot = channelRuntimeSnapshot(statuses, detailed);
        Map<String, Object> result = detachedStatusFields(profile, snapshot, detailed);
        result.put("gateway_running", Boolean.valueOf(snapshot.anyConnected));
        return result;
    }

    /** 构造非当前 Profile 的机器可观测状态。 */
    private Map<String, Object> detachedStatus(
            String profile, ProfileGatewayStatus gateway, boolean detailed) {
        RuntimeStatusSnapshot snapshot = detachedRuntimeSnapshot(gateway, detailed);
        Map<String, Object> result = detachedStatusFields(profile, snapshot, detailed);
        if (detailed) {
            result.put("gateway_pid", gateway.getPid());
            result.put("gateway_port", gateway.getPort());
        }
        result.put("gateway_running", Boolean.valueOf(gateway.isRunning()));
        return result;
    }

    /** 填充非当前 Profile 共用状态字段；进程字段由独立网关路径按需追加。 */
    private Map<String, Object> detachedStatusFields(
            String profile, RuntimeStatusSnapshot snapshot, boolean detailed) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("profile", profile);
        result.put("active_sessions", Integer.valueOf(0));
        result.put("running_agent_runs", Integer.valueOf(0));
        if (detailed) {
            result.put("config_path", runtimeReference(appConfig.getRuntime().getConfigFile()));
        }
        result.put("config_version", configVersion());
        result.put("gateway_exit_reason", snapshot.firstFatalDetail);
        result.put("gateway_platforms", snapshot.platformStates);
        result.put("gateway_state", snapshot.gatewayState);
        result.put("gateway_updated_at", snapshot.updatedAt);
        if (detailed) {
            result.put("runtime_capabilities", buildRuntimeCapabilitiesSnapshot());
            result.put("runtime_status", buildRuntimeStatusSnapshot(snapshot, true));
            result.put("workspace_config_refresh", runtimeConfigRefreshStatus());
            result.put("solonclaw_home", runtimeReference(appConfig.getRuntime().getHome()));
        }
        result.put("latest_config_version", configVersion());
        result.put("release_date", RELEASE_DATE_FORMATTER.format(LocalDate.now()));
        appendVersionStatus(result, detailed);
        return result;
    }

    /** 把 Profile 网关状态文件与配置合成为 Dashboard RuntimeStatusSnapshot。 */
    @SuppressWarnings("unchecked")
    private RuntimeStatusSnapshot detachedRuntimeSnapshot(
            ProfileGatewayStatus gateway, boolean detailed) {
        Map<String, Object> state = gateway.getState();
        Map<String, Object> platforms = new LinkedHashMap<String, Object>();
        Object recordedPlatforms = state.get("platforms");
        if (recordedPlatforms instanceof Map) {
            platforms.putAll((Map<String, Object>) recordedPlatforms);
        } else {
            appendConfiguredChannel(
                    platforms, "feishu", appConfig.getChannels().getFeishu(), gateway);
            appendConfiguredChannel(
                    platforms, "dingtalk", appConfig.getChannels().getDingtalk(), gateway);
            appendConfiguredChannel(
                    platforms, "wecom", appConfig.getChannels().getWecom(), gateway);
            appendConfiguredChannel(
                    platforms, "weixin", appConfig.getChannels().getWeixin(), gateway);
            appendConfiguredChannel(
                    platforms, "qqbot", appConfig.getChannels().getQqbot(), gateway);
            appendConfiguredChannel(
                    platforms, "yuanbao", appConfig.getChannels().getYuanbao(), gateway);
        }
        RuntimeStatusSnapshot snapshot = new RuntimeStatusSnapshot();
        snapshot.activeSessions = 0;
        snapshot.runningAgentRuns = 0;
        snapshot.anyConnected = gateway.isRunning();
        snapshot.gatewayState =
                StrUtil.blankToDefault(
                        state.get("status") == null ? null : String.valueOf(state.get("status")),
                        gateway.isRunning() ? "running" : "stopped");
        snapshot.anyFatal = "startup_failed".equals(snapshot.gatewayState);
        snapshot.firstFatalDetail =
                snapshot.anyFatal && state.get("detail") != null
                        ? redact(String.valueOf(state.get("detail")), 1000)
                        : null;
        snapshot.platformStates = platforms;
        snapshot.updatedAt = detachedUpdatedAt(state);
        return snapshot;
    }

    /** 追加未提供实时平台明细时的配置态渠道摘要。 */
    private void appendConfiguredChannel(
            Map<String, Object> platforms,
            String name,
            AppConfig.ChannelConfig channel,
            ProfileGatewayStatus gateway) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        boolean enabled = channel != null && channel.isEnabled();
        item.put(
                "state", enabled ? (gateway.isRunning() ? "running" : "disconnected") : "disabled");
        item.put("updated_at", detachedUpdatedAt(gateway.getState()));
        item.put(
                "detail",
                enabled
                        ? (gateway.isRunning() ? "gateway_running" : "gateway_stopped")
                        : "disabled");
        platforms.put(name, item);
    }

    /** 读取 Profile 状态更新时间；缺失时使用当前时间。 */
    private String detachedUpdatedAt(Map<String, Object> state) {
        Object updatedAt = state == null ? null : state.get("updatedAt");
        if (updatedAt instanceof Number) {
            try {
                return java.time.Instant.ofEpochMilli(((Number) updatedAt).longValue()).toString();
            } catch (RuntimeException ignored) {
                return isoNow();
            }
        }
        return updatedAt == null ? isoNow() : safeText(String.valueOf(updatedAt), 120);
    }

    /** 追加机器级版本状态，Profile 选择不改变已安装版本。 */
    private void appendVersionStatus(Map<String, Object> result, boolean detailed) {
        if (appVersionService == null || appUpdateService == null) {
            return;
        }
        AppUpdateService.VersionStatus versionStatus = appUpdateService.getVersionStatus(false);
        result.put("version", appVersionService.currentVersion());
        result.put("version_tag", appVersionService.currentTag());
        result.put("deployment_mode", appVersionService.deploymentMode());
        result.put("latest_version", versionStatus.getLatestVersion());
        result.put("latest_tag", versionStatus.getLatestTag());
        result.put("update_available", versionStatus.isUpdateAvailable());
        if (detailed) {
            result.put("release_url", SecretRedactor.maskUrl(versionStatus.getReleaseUrl()));
            result.put("release_api_url", SecretRedactor.maskUrl(versionStatus.getReleaseApiUrl()));
            result.put("update_error_message", redact(versionStatus.getUpdateErrorMessage(), 1000));
        }
        result.put(
                "update_error_at",
                versionStatus.getUpdateErrorAt() > 0 ? versionStatus.getUpdateErrorAt() : null);
    }

    /**
     * 构建运行时状态Snapshot。
     *
     * @param detailed detailed 参数。
     * @return 返回创建好的运行时状态Snapshot。
     */
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

        RuntimeStatusSnapshot snapshot = channelRuntimeSnapshot(statuses, detailed);
        snapshot.activeSessions = activeSessions;
        snapshot.runningAgentRuns = runningAgentRuns();
        return snapshot;
    }

    /** 将指定 DeliveryService 的渠道状态统一转换为 Dashboard 网关快照。 */
    private RuntimeStatusSnapshot channelRuntimeSnapshot(
            List<ChannelStatus> statuses, boolean detailed) {
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
            String detail = redactSensitivePaths(status.getDetail(), 1000);
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
                            ? redactSensitivePaths(
                                    StrUtil.blankToDefault(status.getLastErrorMessage(), detail),
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
        snapshot.activeSessions = 0;
        snapshot.runningAgentRuns = 0;
        snapshot.anyConnected = anyConnected;
        snapshot.anyFatal = anyFatal;
        snapshot.firstFatalDetail = anyFatal ? redact(firstFatalDetail(statuses), 1000) : null;
        snapshot.gatewayState = gatewayState;
        snapshot.platformStates = platformStates;
        snapshot.updatedAt = updatedAt;
        return snapshot;
    }

    /**
     * 构建运行时Capabilities Snapshot。
     *
     * @return 返回创建好的运行时Capabilities Snapshot。
     */
    private Map<String, Object> buildRuntimeCapabilitiesSnapshot() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("schema_version", Integer.valueOf(1));
        capabilities.put("service", "solonclaw");
        capabilities.put("dashboard_first", Boolean.TRUE);
        capabilities.put(
                "supported_model_protocols",
                new ArrayList<String>(LlmConstants.SUPPORTED_PROVIDERS));
        capabilities.put("supported_channels", supportedChannels());
        capabilities.put("workspace_config", runtimeConfigCapabilities());
        capabilities.put("diagnostics", diagnosticsCapabilities());
        capabilities.put("cron", cronCapabilities());
        capabilities.put("skills", skillsCapabilities());
        capabilities.put("memory", memoryCapabilities());
        capabilities.put("tool_safety", toolSafetyCapabilities());
        capabilities.put("multimodal", multimodalCapabilities());
        capabilities.put("pricing", pricingCapabilities());
        return capabilities;
    }

    /**
     * 构建运行时状态Snapshot。
     *
     * @param snapshot snapshot 参数。
     * @param detailed detailed 参数。
     * @return 返回创建好的运行时状态Snapshot。
     */
    private Map<String, Object> buildRuntimeStatusSnapshot(
            RuntimeStatusSnapshot snapshot, boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("schema_version", Integer.valueOf(1));
        status.put("service", "solonclaw");
        status.put("status", snapshot.anyFatal ? "degraded" : "ok");
        status.put("updated_at", snapshot.updatedAt);
        status.put("active_sessions", Integer.valueOf(snapshot.activeSessions));
        status.put("running_agent_runs", Integer.valueOf(snapshot.runningAgentRuns));
        status.put("gateway", gatewayRuntimeStatus(snapshot));
        status.put("workspace_config", runtimeConfigStatus(detailed));
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

    /**
     * 执行supportedChannels相关逻辑。
     *
     * @return 返回supported Channels结果。
     */
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

    /**
     * 执行工作区配置Capabilities相关逻辑。
     *
     * @return 返回工作区配置Capabilities结果。
     */
    private Map<String, Object> runtimeConfigCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("workspace_config_file", Boolean.TRUE);
        capabilities.put("dashboard_editable", Boolean.TRUE);
        capabilities.put("hot_refresh", Boolean.valueOf(gatewayRuntimeRefreshService != null));
        capabilities.put("secret_redaction", Boolean.TRUE);
        capabilities.put("workspace_reference_scheme", "workspace://");
        return capabilities;
    }

    /**
     * 执行诊断Capabilities相关逻辑。
     *
     * @return 返回诊断Capabilities结果。
     */
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

    /**
     * 执行定时任务Capabilities相关逻辑。
     *
     * @return 返回定时任务Capabilities结果。
     */
    private Map<String, Object> cronCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("enabled", Boolean.valueOf(appConfig.getScheduler().isEnabled()));
        capabilities.put("persistent_jobs", Boolean.TRUE);
        capabilities.put("channel_delivery", Boolean.TRUE);
        capabilities.put("memory_delivery", Boolean.TRUE);
        capabilities.put("guardrail_cron_mode", safeText(guardrailCronMode(), 80));
        return capabilities;
    }

    /**
     * 执行技能Capabilities相关逻辑。
     *
     * @return 返回技能Capabilities结果。
     */
    private Map<String, Object> skillsCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("runtime_dir", Boolean.TRUE);
        capabilities.put("external_dirs", Boolean.TRUE);
        capabilities.put("template_vars", Boolean.valueOf(appConfig.getSkills().isTemplateVars()));
        capabilities.put("inline_shell", Boolean.valueOf(appConfig.getSkills().isInlineShell()));
        capabilities.put("curator", Boolean.valueOf(appConfig.getCurator().isEnabled()));
        return capabilities;
    }

    /**
     * 执行记忆Capabilities相关逻辑。
     *
     * @return 返回记忆Capabilities结果。
     */
    private Map<String, Object> memoryCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("context_files", Boolean.TRUE);
        capabilities.put("long_term_memory", Boolean.TRUE);
        capabilities.put("session_search", Boolean.TRUE);
        capabilities.put(
                "post_task_learning", Boolean.valueOf(appConfig.getLearning().isEnabled()));
        capabilities.put(
                "context_compression", Boolean.valueOf(appConfig.getCompression().isEnabled()));
        return capabilities;
    }

    /**
     * 执行工具SafetyCapabilities相关逻辑。
     *
     * @return 返回工具Safety Capabilities结果。
     */
    private Map<String, Object> toolSafetyCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("dangerous_command_approval", Boolean.TRUE);
        capabilities.put("guardrail_mode", safeText(guardrailMode(), 80));
        capabilities.put("guardrail_cron_mode", safeText(guardrailCronMode(), 80));
        capabilities.put(
                "subagent_auto_approve",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        capabilities.put("url_private_access_policy", Boolean.TRUE);
        capabilities.put(
                "website_blocklist",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        capabilities.put("tirith_scan", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        capabilities.put(
                "checkpoint_rollback", Boolean.valueOf(appConfig.getRollback().isEnabled()));
        return capabilities;
    }

    /**
     * 执行multimodalCapabilities相关逻辑。
     *
     * @return 返回multimodal Capabilities结果。
     */
    private Map<String, Object> multimodalCapabilities() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        ModelMetadata metadata = resolved == null ? null : currentModelMetadata(resolved);
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("model_input", multimodalModelInputCapabilities(metadata));
        capabilities.put("image_generation", Boolean.valueOf(imageGenerationAvailable()));
        capabilities.put("tts", Boolean.valueOf(ttsAvailable()));
        capabilities.put("transcription", Boolean.valueOf(transcriptionAvailable()));
        capabilities.put("attachment_cache", Boolean.TRUE);
        return capabilities;
    }

    /**
     * 执行价格Capabilities相关逻辑。
     *
     * @return 返回价格Capabilities结果。
     */
    private Map<String, Object> pricingCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        capabilities.put("usage_events", Boolean.TRUE);
        capabilities.put("cost_calculation", Boolean.TRUE);
        capabilities.put(
                "builtin_price_count", Integer.valueOf(PriceCatalog.builtinDefaults().size()));
        capabilities.put("configured_price_count", Integer.valueOf(configuredPriceCount()));
        capabilities.put("effective_price_count", Integer.valueOf(effectivePriceCount()));
        capabilities.put("supports_cache_pricing", Boolean.TRUE);
        capabilities.put("supports_reasoning_pricing", Boolean.TRUE);
        return capabilities;
    }

    /**
     * 执行消息网关运行时状态相关逻辑。
     *
     * @param snapshot snapshot 参数。
     * @return 返回消息网关运行时状态。
     */
    private Map<String, Object> gatewayRuntimeStatus(RuntimeStatusSnapshot snapshot) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("state", snapshot.gatewayState);
        status.put("running", Boolean.valueOf(snapshot.anyConnected));
        status.put("platforms", snapshot.platformStates);
        status.put("supported_channels", supportedChannels());
        status.put("active_agents", Integer.valueOf(snapshot.runningAgentRuns));
        status.put("recent_active_sessions", Integer.valueOf(snapshot.activeSessions));
        status.put("exit_reason", snapshot.firstFatalDetail);
        status.put("updated_at", snapshot.updatedAt);
        return status;
    }

    /**
     * 读取真实运行中的 Agent run 数量，避免把最近更新过的会话误报为仍在执行。
     *
     * @return 返回当前运行中的 Agent run 数量。
     */
    private int runningAgentRuns() {
        if (agentRunControlService == null) {
            return 0;
        }
        return Math.max(0, agentRunControlService.runningRunCount());
    }

    /**
     * 执行工作区配置状态相关逻辑。
     *
     * @param detailed detailed 参数。
     * @return 返回工作区配置状态。
     */
    private Map<String, Object> runtimeConfigStatus(boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("config_version", Integer.valueOf(configVersion()));
        status.put("latest_config_version", Integer.valueOf(configVersion()));
        status.put("refresh", runtimeConfigRefreshStatus());
        if (detailed) {
            status.put("config_path", runtimeReference(appConfig.getRuntime().getConfigFile()));
            status.put("workspace_home", runtimeReference(appConfig.getRuntime().getHome()));
            status.put("context_dir", runtimeReference(appConfig.getRuntime().getContextDir()));
            status.put("skills_dir", runtimeReference(appConfig.getRuntime().getSkillsDir()));
            status.put("cache_dir", runtimeReference(appConfig.getRuntime().getCacheDir()));
            status.put("logs_dir", runtimeReference(appConfig.getRuntime().getLogsDir()));
            status.put("state_db", runtimeReference(appConfig.getRuntime().getStateDb()));
        }
        return status;
    }

    /**
     * 执行诊断状态相关逻辑。
     *
     * @param snapshot snapshot 参数。
     * @return 返回诊断状态。
     */
    private Map<String, Object> diagnosticsStatus(RuntimeStatusSnapshot snapshot) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("state", snapshot.anyFatal ? "degraded" : "ok");
        status.put("gateway_state", snapshot.gatewayState);
        status.put(
                "runtime_refresh_failed",
                Boolean.valueOf(
                        gatewayRuntimeRefreshService != null
                                && gatewayRuntimeRefreshService.lastFailureSnapshot() != null));
        status.put("updated_at", snapshot.updatedAt);
        return status;
    }

    /**
     * 执行定时任务状态相关逻辑。
     *
     * @return 返回定时任务状态。
     */
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
        status.put("guardrail_cron_mode", safeText(guardrailCronMode(), 80));
        return status;
    }

    /**
     * 读取定时任务安全护栏模式。
     *
     * @return 返回定时任务安全护栏模式。
     */
    private String guardrailCronMode() {
        return appConfig.getSecurity() == null
                ? "strict"
                : StrUtil.blankToDefault(appConfig.getSecurity().getGuardrailCronMode(), "strict");
    }

    /**
     * 执行防护模式相关逻辑。
     *
     * @return 返回防护模式结果。
     */
    private String guardrailMode() {
        return appConfig.getSecurity() == null
                ? "approval"
                : StrUtil.blankToDefault(appConfig.getSecurity().getGuardrailMode(), "approval");
    }

    /**
     * 执行技能状态相关逻辑。
     *
     * @param detailed detailed 参数。
     * @return 返回技能状态。
     */
    private Map<String, Object> skillsStatus(boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("template_vars", Boolean.valueOf(appConfig.getSkills().isTemplateVars()));
        status.put("inline_shell", Boolean.valueOf(appConfig.getSkills().isInlineShell()));
        status.put(
                "inline_shell_timeout_seconds",
                Integer.valueOf(appConfig.getSkills().getInlineShellTimeoutSeconds()));
        status.put("curator_enabled", Boolean.valueOf(appConfig.getCurator().isEnabled()));
        status.put(
                "external_dir_count",
                Integer.valueOf(appConfig.getSkills().getExternalDirs().size()));
        if (detailed) {
            status.put("runtime_dir", runtimeReference(appConfig.getRuntime().getSkillsDir()));
        }
        return status;
    }

    /**
     * 执行记忆状态相关逻辑。
     *
     * @param detailed detailed 参数。
     * @return 返回记忆状态。
     */
    private Map<String, Object> memoryStatus(boolean detailed) {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("learning_enabled", Boolean.valueOf(appConfig.getLearning().isEnabled()));
        status.put(
                "context_compression_enabled",
                Boolean.valueOf(appConfig.getCompression().isEnabled()));
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

    /**
     * 执行工具Safety状态相关逻辑。
     *
     * @return 返回工具Safety状态。
     */
    private Map<String, Object> toolSafetyStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("guardrail_mode", safeText(guardrailMode(), 80));
        status.put("guardrail_cron_mode", safeText(guardrailCronMode(), 80));
        status.put(
                "subagent_auto_approve",
                Boolean.valueOf(appConfig.getApprovals().isSubagentAutoApprove()));
        status.put(
                "approval_timeout_seconds",
                Integer.valueOf(appConfig.getApprovals().getTimeoutSeconds()));
        status.put(
                "allow_private_urls",
                Boolean.valueOf(appConfig.getSecurity().isAllowPrivateUrls()));
        status.put(
                "website_blocklist_enabled",
                Boolean.valueOf(appConfig.getSecurity().getWebsiteBlocklist().isEnabled()));
        status.put("tirith_enabled", Boolean.valueOf(appConfig.getSecurity().isTirithEnabled()));
        status.put(
                "checkpoint_rollback_enabled",
                Boolean.valueOf(appConfig.getRollback().isEnabled()));
        return status;
    }

    /**
     * 执行multimodal状态相关逻辑。
     *
     * @return 返回multimodal状态。
     */
    private Map<String, Object> multimodalStatus() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        ModelMetadata metadata = resolved == null ? null : currentModelMetadata(resolved);
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("provider_configured", Boolean.valueOf(resolved != null));
        status.put("provider", resolved == null ? "" : safeText(resolved.getProviderKey(), 160));
        status.put("model", resolved == null ? "" : safeText(resolved.getModel(), 200));
        status.put("dialect", resolved == null ? "" : safeText(resolved.getDialect(), 80));
        status.put("model_input", multimodalModelInputCapabilities(metadata));
        status.put("image_generation", Boolean.valueOf(imageGenerationAvailable()));
        status.put("tts", Boolean.valueOf(ttsAvailable()));
        status.put("transcription", Boolean.valueOf(transcriptionAvailable()));
        status.put(
                "media_cache_ttl_hours",
                Integer.valueOf(appConfig.getTask().getMediaCacheTtlHours()));
        return status;
    }

    /** 任一已注册图像 Provider 可用时报告图像生成为可用。 */
    private boolean imageGenerationAvailable() {
        if (imageGenProviders == null) {
            return false;
        }
        for (ImageGenProvider provider : imageGenProviders) {
            try {
                if (provider != null && provider.isAvailable()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 单个提供方异常不能中断 Dashboard 状态接口。
            }
        }
        return false;
    }

    /** 读取 TTS Provider 真实可用性，提供方异常时按不可用处理，避免健康接口失败。 */
    private boolean ttsAvailable() {
        try {
            return speechService != null && speechService.isTtsAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 读取独立 STT Provider 真实可用性，提供方异常时按不可用处理。 */
    private boolean transcriptionAvailable() {
        try {
            return speechService != null && speechService.isTranscriptionAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 执行multimodal模型输入Capabilities相关逻辑。
     *
     * @param metadata 元数据参数。
     * @return 返回multimodal模型输入Capabilities结果。
     */
    private Map<String, Object> multimodalModelInputCapabilities(ModelMetadata metadata) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("vision", Boolean.valueOf(metadata != null && metadata.isSupportsVision()));
        input.put("audio", Boolean.valueOf(metadata != null && metadata.isSupportsAudio()));
        input.put(
                "attachments",
                Boolean.valueOf(metadata != null && metadata.isSupportsAttachment()));
        input.put("pdf", Boolean.valueOf(metadata != null && metadata.isSupportsPdf()));
        input.put(
                "multimodal", Boolean.valueOf(metadata != null && metadata.isSupportsMultimodal()));
        return input;
    }

    /**
     * 执行价格状态相关逻辑。
     *
     * @return 返回价格状态。
     */
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

    /**
     * 执行运行时模型状态相关逻辑。
     *
     * @return 返回运行时模型状态。
     */
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

    /**
     * 执行已配置价格次数相关逻辑。
     *
     * @return 返回configured价格次数结果。
     */
    private int configuredPriceCount() {
        return appConfig.getPricing() == null || appConfig.getPricing().getPrices() == null
                ? 0
                : appConfig.getPricing().getPrices().size();
    }

    /**
     * 执行生效价格次数相关逻辑。
     *
     * @return 返回生效价格次数结果。
     */
    private int effectivePriceCount() {
        return PriceCatalog.forConfig(appConfig).size();
    }

    /**
     * 执行当前模型价格相关逻辑。
     *
     * @return 返回当前模型价格结果。
     */
    private com.jimuqu.solon.claw.pricing.ModelPrice currentModelPrice() {
        LlmProviderService.ResolvedProvider resolved = safeResolvedProvider();
        if (resolved == null) {
            return null;
        }
        return PriceCatalog.forConfig(appConfig)
                .find(resolved.getProviderKey(), resolved.getModel());
    }

    /**
     * 生成安全展示用的Resolved提供方。
     *
     * @return 返回safe Resolved提供方结果。
     */
    private LlmProviderService.ResolvedProvider safeResolvedProvider() {
        try {
            return llmProviderService.resolveEffectiveProvider(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行当前模型元数据相关逻辑。
     *
     * @param resolved resolved 参数。
     * @return 返回当前模型元数据结果。
     */
    private ModelMetadata currentModelMetadata(LlmProviderService.ResolvedProvider resolved) {
        AppConfig.ProviderConfig provider = appConfig.getProviders().get(resolved.getProviderKey());
        AppConfig.ProviderConfig effective = new AppConfig.ProviderConfig();
        if (provider != null) {
            effective.setName(provider.getName());
            effective.setBaseUrl(provider.getBaseUrl());
            effective.setApiKey(provider.getApiKey());
            effective.setDefaultModel(provider.getDefaultModel());
            effective.setDialect(provider.getDialect());
            effective.setSupportsVision(provider.getSupportsVision());
            effective.setCapabilities(
                    provider.getCapabilities() == null
                            ? new LinkedHashMap<String, Boolean>()
                            : new LinkedHashMap<String, Boolean>(provider.getCapabilities()));
        }
        effective.setDefaultModel(resolved.getModel());
        effective.setDialect(resolved.getDialect());
        return new ModelMetadataService(appConfig).resolve(resolved.getProviderKey(), effective);
    }

    /**
     * 执行工作区配置刷新状态相关逻辑。
     *
     * @return 返回工作区配置刷新状态。
     */
    private Map<String, Object> runtimeConfigRefreshStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put(
                "last_failure",
                gatewayRuntimeRefreshService == null
                        ? null
                        : gatewayRuntimeRefreshService.lastFailureSnapshot());
        return status;
    }

    /**
     * 生成安全展示用的兜底Providers。
     *
     * @return 返回safe兜底Providers结果。
     */
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

    /**
     * 执行公开详情相关逻辑。
     *
     * @param status 状态参数。
     * @return 返回公开Detail结果。
     */
    private String publicDetail(ChannelStatus status) {
        if (!status.isEnabled()) {
            return "disabled";
        }
        return status.isConnected() ? "connected" : "disconnected";
    }

    /**
     * 执行firstFatal详情相关逻辑。
     *
     * @param statuses statuses 参数。
     * @return 返回first Fatal Detail结果。
     */
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
        File workspaceHome = new File(appConfig.getRuntime().getHome()).getAbsoluteFile();
        File file = new File(text).getAbsoluteFile();
        try {
            workspaceHome = workspaceHome.getCanonicalFile();
            file = file.getCanonicalFile();
        } catch (Exception e) {
            log.debug("运行时路径引用规范化失败，使用绝对路径脱敏展示 error={}", e.getClass().getSimpleName());
        }
        String homePath = normalized(workspaceHome);
        String filePath = normalized(file);
        if (filePath.equals(homePath)) {
            return "workspace://";
        }
        if (filePath.startsWith(homePath + File.separator)) {
            String relative = filePath.substring(homePath.length() + 1).replace('\\', '/');
            return "workspace://" + relative;
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
        return "path://" + redact(name, 200);
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
     * 脱敏文本中的密钥、令牌和敏感路径。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回redact结果。
     */
    private String redact(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 脱敏状态输出中的敏感路径和密钥，避免 dashboard 暴露本机凭据位置。
     *
     * @param value 待展示文本。
     * @param maxLength 最大展示长度。
     * @return 返回脱敏后的文本。
     */
    private String redactSensitivePaths(String value, int maxLength) {
        return SecretRedactor.redactSensitivePaths(SecretRedactor.redact(value, maxLength));
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe Text结果。
     */
    private String safeText(String value, int maxLength) {
        return StrUtil.nullToEmpty(SecretRedactor.redact(value, maxLength));
    }

    /**
     * 执行配置版本相关逻辑。
     *
     * @return 返回配置版本结果。
     */
    private int configVersion() {
        java.io.File file = new java.io.File(appConfig.getRuntime().getConfigFile());
        if (!file.exists()) {
            return 0;
        }
        return (int) (file.lastModified() / 1000L);
    }

    /**
     * 执行isoNow相关逻辑。
     *
     * @return 返回iso Now结果。
     */
    private String isoNow() {
        return ISO_TIME_FORMATTER.format(ZonedDateTime.now());
    }

    /** 承载运行时状态快照相关状态和辅助逻辑。 */
    private static class RuntimeStatusSnapshot {
        /** 记录运行时状态快照中的activeSessions。 */
        private int activeSessions;

        /** 记录当前真实运行中的Agent run数量。 */
        private int runningAgentRuns;

        /** 是否启用anyConnected。 */
        private boolean anyConnected;

        /** 是否启用anyFatal。 */
        private boolean anyFatal;

        /** 记录运行时状态快照中的firstFatal详情。 */
        private String firstFatalDetail;

        /** 记录运行时状态快照中的消息网关状态。 */
        private String gatewayState;

        /** 保存平台States映射，便于按键快速查询。 */
        private Map<String, Object> platformStates;

        /** 记录运行时状态快照中的更新时间。 */
        private String updatedAt;
    }
}
