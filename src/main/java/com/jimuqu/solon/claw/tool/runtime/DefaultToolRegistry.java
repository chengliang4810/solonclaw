package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.media.VisionAnalysisService;
import com.jimuqu.solon.claw.tool.runtime.ToolRegistration;
import com.jimuqu.solon.claw.provider.BrowserProvider;
import com.jimuqu.solon.claw.provider.WebSearchProvider;
import com.jimuqu.solon.claw.profile.ProfileBeanResolver;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import com.jimuqu.solon.claw.web.DashboardAnalyticsService;
import com.jimuqu.solon.claw.web.DashboardApprovalEventsService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import com.jimuqu.solon.claw.web.DashboardInsightsService;
import com.jimuqu.solon.claw.web.DashboardLogsService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardMediaService;
import com.jimuqu.solon.claw.web.DashboardPlatformToolsetsService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.talents.gateway.ToolGatewayTalent;
import org.noear.solon.ai.talents.sys.ShellTalent;
import org.noear.solon.ai.talents.sys.SystemClockTalent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认工具注册表。 */
public class DefaultToolRegistry implements ToolRegistry {
    /** 记录工具偏好写入失败的低敏诊断日志，不输出来源正文或配置内容。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultToolRegistry.class);

    /** 默认内置工具清单。 */
    private static final List<String> TOOL_NAMES = AgentRuntimePolicy.knownToolNames();

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 偏好存储。 */
    private final SqlitePreferenceStore preferenceStore;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** Agent profile 服务。 */
    private final AgentProfileService agentProfileService;

    /** 定时任务仓储。 */
    private final CronJobService cronJobService;

    /** 渠道投递服务。 */
    private final DeliveryService deliveryService;

    /** 长期记忆服务。 */
    private final MemoryService memoryService;

    /** 会话搜索服务。 */
    private final SessionSearchService sessionSearchService;

    /** 本地技能目录服务。 */
    private final LocalSkillService localSkillService;

    /** Skills Hub 服务。 */
    private final SkillHubService skillHubService;

    /** checkpoint 服务。 */
    private final CheckpointService checkpointService;

    /** 委托服务。 */
    private final DelegationService delegationService;

    /** 附件缓存服务。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 工作区配置服务。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 工作区配置刷新服务。 */
    private final GatewayRuntimeRefreshService gatewayRuntimeRefreshService;

    /** 文件/URL 安全策略。 */
    private final SecurityPolicyService securityPolicyService;

    /** 危险或外部操作审批服务。 */
    private final DangerousCommandApprovalService approvalService;

    /** MCP 运行时工具发现服务。 */
    private final McpRuntimeService mcpRuntimeService;

    /** Dashboard MCP 服务，用于给 Agent 暴露服务端管理工具。 */
    private final DashboardMcpService dashboardMcpService;

    /** Dashboard 技能维护服务，用于给 Agent 暴露维护建议管理工具。 */
    private final DashboardCuratorService dashboardCuratorService;

    /** Dashboard 平台工具集服务，用于给 Agent 暴露渠道工具集管理工具。 */
    private final DashboardPlatformToolsetsService dashboardPlatformToolsetsService;

    /** Dashboard provider 服务，用于给 Agent 暴露模型提供方管理工具。 */
    private final DashboardProviderService dashboardProviderService;

    /** Dashboard 状态服务，用于给 Agent 暴露运行状态查询工具。 */
    private final DashboardStatusService dashboardStatusService;

    /** Dashboard Doctor 服务，用于给 Agent 暴露消息网关诊断工具。 */
    private final DashboardGatewayDoctorService dashboardGatewayDoctorService;

    /** Dashboard 洞察服务，用于给 Agent 暴露概览和技能用量查询。 */
    private final DashboardInsightsService dashboardInsightsService;

    /** Dashboard 审批事件服务，用于给 Agent 暴露审批事件只读查询。 */
    private final DashboardApprovalEventsService dashboardApprovalEventsService;

    /** Dashboard 诊断服务供应器，用于给 Agent 暴露诊断与审批队列只读查询。 */
    private final Supplier<DashboardDiagnosticsService> dashboardDiagnosticsService;

    /** Dashboard 工作区服务，用于给 Agent 暴露人格工作区只读查询。 */
    private final DashboardWorkspaceService dashboardWorkspaceService;

    /** Dashboard 配置服务，用于给 Agent 暴露配置元数据只读查询。 */
    private final DashboardConfigService dashboardConfigService;

    /** Dashboard 工作区配置服务，用于给 Agent 暴露已脱敏配置项只读查询。 */
    private final DashboardRuntimeConfigService dashboardRuntimeConfigService;

    /** 微信二维码 setup 服务，用于给 Agent 暴露 Dashboard 配置引导。 */
    private final WeixinQrSetupService weixinQrSetupService;

    /** 国内渠道二维码 setup 服务，用于给 Agent 暴露 Dashboard 配置引导。 */
    private final DomesticQrSetupService domesticQrSetupService;

    /** 受管后台进程注册表。 */
    private final ProcessRegistry processRegistry;

    /** 浏览器自动化运行时。 */
    private final BrowserRuntimeService browserRuntimeService;

    /** 图片生成服务。 */
    private final ImageGenerationService imageGenerationService;

    /** 语音服务。 */
    private final SpeechService speechService;

    /** 额外注册工具。 */
    private final List<ToolRegistration> extraTools;

    /** Web 搜索附加提供方，用于按配置接管内置 websearch 后端。 */
    private List<WebSearchProvider> webSearchProviders = Collections.emptyList();

    /** Dashboard 运行服务，用于给 Agent 暴露一等运行管理工具。 */
    private final DashboardRunService dashboardRunService;

    /** 用量事件仓储，用于给 Agent 暴露与 Dashboard 一致的用量分析。 */
    private final UsageEventRepository usageEventRepository;

    /** Agent 运行仓储，用于给 Agent 暴露 Dashboard 日志结构化运行索引。 */
    private final AgentRunRepository agentRunRepository;

    /** 定时任务仓储，用于给 Agent 暴露 Dashboard 日志结构化定时任务索引。 */
    private final CronJobRepository cronJobRepository;

    /** SQLite 数据库，用于给 Agent 暴露 Dashboard 媒体索引查询。 */
    private final SqliteDatabase sqliteDatabase;

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                (SecurityPolicyService) null);
    }

    /**
     * 创建默认工具注册表实例，并注入 MCP 管理和浏览器运行时依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService Agent profile 服务依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param dashboardMcpService Dashboard MCP服务依赖。
     * @param dashboardCuratorService Dashboard 技能维护服务依赖。
     * @param dashboardPlatformToolsetsService Dashboard 平台工具集服务依赖。
     * @param dashboardProviderService Dashboard provider 服务依赖。
     * @param dashboardStatusService Dashboard 状态服务依赖。
     * @param dashboardGatewayDoctorService Dashboard Doctor 服务依赖。
     * @param dashboardInsightsService Dashboard 洞察服务依赖。
     * @param dashboardApprovalEventsService Dashboard 审批事件服务依赖。
     * @param dashboardWorkspaceService Dashboard 工作区服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            DashboardMcpService dashboardMcpService,
            DashboardCuratorService dashboardCuratorService,
            DashboardPlatformToolsetsService dashboardPlatformToolsetsService,
            DashboardProviderService dashboardProviderService,
            BrowserRuntimeService browserRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                dashboardMcpService,
                dashboardCuratorService,
                dashboardPlatformToolsetsService,
                dashboardProviderService,
                (DashboardStatusService) null,
                (DashboardGatewayDoctorService) null,
                (DashboardInsightsService) null,
                (DashboardApprovalEventsService) null,
                (Supplier<DashboardDiagnosticsService>) null,
                (DashboardWorkspaceService) null,
                (DashboardConfigService) null,
                (DashboardRuntimeConfigService) null,
                (WeixinQrSetupService) null,
                (DomesticQrSetupService) null,
                browserRuntimeService,
                (ImageGenerationService) null,
                (SpeechService) null,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.storage.repository.SqliteDatabase) null,
                (com.jimuqu.solon.claw.core.repository.AgentRunRepository) null,
                (com.jimuqu.solon.claw.core.repository.CronJobRepository) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (McpRuntimeService) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param mcpRuntimeService MCP运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            McpRuntimeService mcpRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (ProcessRegistry) null,
                mcpRuntimeService);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                processRegistry,
                mcpRuntimeService,
                (ImageGenerationService) null,
                (SpeechService) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                processRegistry,
                mcpRuntimeService,
                (BrowserRuntimeService) null,
                imageGenerationService,
                speechService);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                (ImageGenerationService) null,
                (SpeechService) null);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param extraTools 额外工具参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            List<ToolRegistration> extraTools) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                (BrowserRuntimeService) null,
                (ImageGenerationService) null,
                (SpeechService) null,
                extraTools);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入审批、浏览器和媒体能力依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService Agent profile 服务依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                approvalService,
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                (List<ToolRegistration>) null);
    }

    /**
     * 创建默认工具注册表实例，并注入浏览器、媒体和额外工具依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService Agent profile 服务依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param extraTools 额外工具参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            List<ToolRegistration> extraTools) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                (DangerousCommandApprovalService) null,
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                extraTools);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param extraTools 额外工具参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            List<ToolRegistration> extraTools) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                approvalService,
                processRegistry,
                mcpRuntimeService,
                (DashboardMcpService) null,
                (DashboardCuratorService) null,
                (DashboardPlatformToolsetsService) null,
                (DashboardProviderService) null,
                (DashboardStatusService) null,
                (DashboardGatewayDoctorService) null,
                (DashboardInsightsService) null,
                (DashboardApprovalEventsService) null,
                (Supplier<DashboardDiagnosticsService>) null,
                (DashboardWorkspaceService) null,
                (DashboardConfigService) null,
                (DashboardRuntimeConfigService) null,
                (WeixinQrSetupService) null,
                (DomesticQrSetupService) null,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                (DashboardRunService) null,
                (com.jimuqu.solon.claw.storage.repository.SqliteDatabase) null,
                (com.jimuqu.solon.claw.core.repository.AgentRunRepository) null,
                (com.jimuqu.solon.claw.core.repository.CronJobRepository) null,
                (com.jimuqu.solon.claw.usage.UsageEventRepository) null,
                extraTools);
    }

    /**
     * 创建默认工具注册表实例，并额外注入 Web 搜索附加提供方。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService Agent profile 服务依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param extraTools 额外工具列表。
     * @param webSearchProviders Web 搜索附加提供方列表。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            List<ToolRegistration> extraTools,
            List<WebSearchProvider> webSearchProviders) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                processRegistry,
                mcpRuntimeService,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                extraTools);
        setWebSearchProviders(webSearchProviders);
    }

    /**
     * 创建默认工具注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param dashboardCuratorService Dashboard 技能维护服务依赖。
     * @param dashboardPlatformToolsetsService Dashboard 平台工具集服务依赖。
     * @param dashboardProviderService Dashboard provider 服务依赖。
     * @param dashboardStatusService Dashboard 状态服务依赖。
     * @param dashboardGatewayDoctorService Dashboard Doctor 服务依赖。
     * @param dashboardInsightsService Dashboard 洞察服务依赖。
     * @param dashboardApprovalEventsService Dashboard 审批事件服务依赖。
     * @param dashboardDiagnosticsService Dashboard 诊断服务供应器。
     * @param dashboardWorkspaceService Dashboard 工作区服务依赖。
     * @param dashboardConfigService Dashboard 配置服务依赖。
     * @param dashboardRuntimeConfigService Dashboard 工作区配置服务依赖。
     * @param weixinQrSetupService 微信二维码 setup 服务依赖。
     * @param domesticQrSetupService 国内二维码 setup 服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param dashboardRunService Dashboard运行服务依赖。
     * @param sqliteDatabase SQLite数据库依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param cronJobRepository 定时任务仓储依赖。
     * @param usageEventRepository 用量事件仓储依赖。
     * @param extraTools 额外工具参数。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            DashboardMcpService dashboardMcpService,
            DashboardCuratorService dashboardCuratorService,
            DashboardPlatformToolsetsService dashboardPlatformToolsetsService,
            DashboardProviderService dashboardProviderService,
            DashboardStatusService dashboardStatusService,
            DashboardGatewayDoctorService dashboardGatewayDoctorService,
            DashboardInsightsService dashboardInsightsService,
            DashboardApprovalEventsService dashboardApprovalEventsService,
            Supplier<DashboardDiagnosticsService> dashboardDiagnosticsService,
            DashboardWorkspaceService dashboardWorkspaceService,
            DashboardConfigService dashboardConfigService,
            DashboardRuntimeConfigService dashboardRuntimeConfigService,
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            DashboardRunService dashboardRunService,
            SqliteDatabase sqliteDatabase,
            AgentRunRepository agentRunRepository,
            CronJobRepository cronJobRepository,
            UsageEventRepository usageEventRepository,
            List<ToolRegistration> extraTools) {
        this(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                deliveryService,
                memoryService,
                sessionSearchService,
                localSkillService,
                skillHubService,
                checkpointService,
                delegationService,
                attachmentCacheService,
                runtimeSettingsService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                approvalService,
                processRegistry,
                mcpRuntimeService,
                dashboardMcpService,
                dashboardCuratorService,
                dashboardPlatformToolsetsService,
                dashboardProviderService,
                dashboardStatusService,
                dashboardGatewayDoctorService,
                dashboardInsightsService,
                dashboardApprovalEventsService,
                dashboardDiagnosticsService,
                dashboardWorkspaceService,
                dashboardConfigService,
                dashboardRuntimeConfigService,
                weixinQrSetupService,
                domesticQrSetupService,
                browserRuntimeService,
                imageGenerationService,
                speechService,
                dashboardRunService,
                sqliteDatabase,
                agentRunRepository,
                cronJobRepository,
                usageEventRepository,
                extraTools,
                null);
    }

    /**
     * 创建默认工具注册表实例，并注入完整运行依赖与 Web 搜索附加提供方。
     *
     * @param appConfig 应用运行配置。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentProfileService Agent profile 服务依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param deliveryService 投递服务依赖。
     * @param memoryService 记忆服务依赖。
     * @param sessionSearchService 会话搜索服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param delegationService delegation服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param approvalService 审批服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param dashboardMcpService Dashboard MCP服务依赖。
     * @param dashboardCuratorService Dashboard 技能维护服务依赖。
     * @param dashboardPlatformToolsetsService Dashboard 平台工具集服务依赖。
     * @param dashboardProviderService Dashboard provider 服务依赖。
     * @param dashboardStatusService Dashboard 状态服务依赖。
     * @param dashboardGatewayDoctorService Dashboard Doctor 服务依赖。
     * @param dashboardInsightsService Dashboard 洞察服务依赖。
     * @param dashboardApprovalEventsService Dashboard 审批事件服务依赖。
     * @param dashboardDiagnosticsService Dashboard 诊断服务供应器。
     * @param dashboardWorkspaceService Dashboard 工作区服务依赖。
     * @param dashboardConfigService Dashboard 配置服务依赖。
     * @param dashboardRuntimeConfigService Dashboard 工作区配置服务依赖。
     * @param weixinQrSetupService 微信二维码 setup 服务依赖。
     * @param domesticQrSetupService 国内二维码 setup 服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param imageGenerationService 图片Generation服务依赖。
     * @param speechService 语音服务依赖。
     * @param dashboardRunService Dashboard运行服务依赖。
     * @param sqliteDatabase SQLite数据库依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param cronJobRepository 定时任务仓储依赖。
     * @param usageEventRepository 用量事件仓储依赖。
     * @param extraTools 额外工具列表。
     * @param webSearchProviders Web 搜索附加提供方列表。
     */
    public DefaultToolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            DeliveryService deliveryService,
            MemoryService memoryService,
            SessionSearchService sessionSearchService,
            LocalSkillService localSkillService,
            SkillHubService skillHubService,
            CheckpointService checkpointService,
            DelegationService delegationService,
            AttachmentCacheService attachmentCacheService,
            RuntimeSettingsService runtimeSettingsService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DangerousCommandApprovalService approvalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            DashboardMcpService dashboardMcpService,
            DashboardCuratorService dashboardCuratorService,
            DashboardPlatformToolsetsService dashboardPlatformToolsetsService,
            DashboardProviderService dashboardProviderService,
            DashboardStatusService dashboardStatusService,
            DashboardGatewayDoctorService dashboardGatewayDoctorService,
            DashboardInsightsService dashboardInsightsService,
            DashboardApprovalEventsService dashboardApprovalEventsService,
            Supplier<DashboardDiagnosticsService> dashboardDiagnosticsService,
            DashboardWorkspaceService dashboardWorkspaceService,
            DashboardConfigService dashboardConfigService,
            DashboardRuntimeConfigService dashboardRuntimeConfigService,
            WeixinQrSetupService weixinQrSetupService,
            DomesticQrSetupService domesticQrSetupService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            DashboardRunService dashboardRunService,
            SqliteDatabase sqliteDatabase,
            AgentRunRepository agentRunRepository,
            CronJobRepository cronJobRepository,
            UsageEventRepository usageEventRepository,
            List<ToolRegistration> extraTools,
            List<WebSearchProvider> webSearchProviders) {
        this.appConfig = appConfig;
        this.preferenceStore = preferenceStore;
        this.sessionRepository = sessionRepository;
        this.agentProfileService = agentProfileService;
        this.cronJobService = cronJobService;
        this.deliveryService = deliveryService;
        this.memoryService = memoryService;
        this.sessionSearchService = sessionSearchService;
        this.localSkillService = localSkillService;
        this.skillHubService = skillHubService;
        this.checkpointService = checkpointService;
        this.delegationService = delegationService;
        this.attachmentCacheService = attachmentCacheService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.securityPolicyService = securityPolicyService;
        this.approvalService = approvalService;
        this.mcpRuntimeService = mcpRuntimeService;
        this.dashboardMcpService = dashboardMcpService;
        this.dashboardCuratorService = dashboardCuratorService;
        this.dashboardPlatformToolsetsService = dashboardPlatformToolsetsService;
        this.dashboardProviderService = dashboardProviderService;
        this.dashboardStatusService = dashboardStatusService;
        this.dashboardGatewayDoctorService = dashboardGatewayDoctorService;
        this.dashboardInsightsService = dashboardInsightsService;
        this.dashboardApprovalEventsService = dashboardApprovalEventsService;
        this.dashboardDiagnosticsService =
                dashboardDiagnosticsService == null
                        ? this::resolveDashboardDiagnosticsService
                        : dashboardDiagnosticsService;
        this.dashboardWorkspaceService = dashboardWorkspaceService;
        this.dashboardConfigService = dashboardConfigService;
        this.dashboardRuntimeConfigService = dashboardRuntimeConfigService;
        this.weixinQrSetupService = weixinQrSetupService;
        this.domesticQrSetupService = domesticQrSetupService;
        this.processRegistry = processRegistry;
        this.browserRuntimeService =
                browserRuntimeService == null
                        ? new BrowserRuntimeService(
                                appConfig,
                                Collections.<BrowserProvider>emptyList(),
                                securityPolicyService)
                        : browserRuntimeService;
        this.imageGenerationService = imageGenerationService;
        this.speechService = speechService;
        this.dashboardRunService = dashboardRunService;
        this.sqliteDatabase = sqliteDatabase;
        this.agentRunRepository = agentRunRepository;
        this.cronJobRepository = cronJobRepository;
        this.usageEventRepository = usageEventRepository;
        this.extraTools =
                extraTools == null
                        ? Collections.<ToolRegistration>emptyList()
                        : new ArrayList<ToolRegistration>(extraTools);
        setWebSearchProviders(webSearchProviders);
    }

    /**
     * 设置 Web 搜索附加提供方。
     *
     * @param providers Web 搜索附加提供方列表。
     */
    private void setWebSearchProviders(List<WebSearchProvider> providers) {
        this.webSearchProviders =
                providers == null ? Collections.<WebSearchProvider>emptyList() : providers;
    }

    /**
     * 列出工具Names。
     *
     * @return 返回工具Names列表。
     */
    @Override
    public List<String> listToolNames() {
        List<String> result = new ArrayList<String>(TOOL_NAMES);
        Set<String> seen = new LinkedHashSet<String>(TOOL_NAMES);
        for (ToolRegistration registration : extraTools) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isNotBlank(name) && seen.add(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具对象。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回解析后的启用工具。
     */
    @Override
    public List<Object> resolveEnabledTools(String sourceKey) {
        return resolveEnabledTools(sourceKey, null);
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具对象。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的启用工具。
     */
    @Override
    public List<Object> resolveEnabledTools(String sourceKey, AgentRuntimeScope agentScope) {
        List<Object> tools = new ArrayList<Object>();

        MemoryTools memoryTools = new MemoryTools(memoryService);
        SessionSearchTools sessionSearchTools =
                new SessionSearchTools(sessionSearchService, sourceKey);
        SearchManageTools searchManageTools = new SearchManageTools(sessionSearchService);
        SessionManageTools sessionManageTools =
                new SessionManageTools(
                        new DashboardSessionService(
                                sessionRepository,
                                checkpointService,
                                new SessionArtifactService(),
                                agentRunRepository));
        AnalyticsManageTools analyticsManageTools =
                new AnalyticsManageTools(
                        new DashboardAnalyticsService(sessionRepository, usageEventRepository));
        LogsManageTools logsManageTools =
                new LogsManageTools(
                        new DashboardLogsService(appConfig, agentRunRepository, cronJobRepository));
        MediaManageTools mediaManageTools =
                new MediaManageTools(
                        sqliteDatabase == null
                                ? null
                                : new DashboardMediaService(
                                        sqliteDatabase,
                                        new com.jimuqu.solon.claw.support.RuntimePathGuard(
                                                appConfig),
                                        attachmentCacheService));
        SkillTools skillTools =
                new SkillTools(
                        localSkillService,
                        checkpointService,
                        sessionRepository,
                        sourceKey,
                        agentScope,
                        cronJobService);
        SkillHubTools skillHubTools = new SkillHubTools(skillHubService);
        ToolsetsManageTools toolsetsManageTools =
                new ToolsetsManageTools(
                        new com.jimuqu.solon.claw.web.DashboardSkillsService(
                                localSkillService, preferenceStore));
        MessagingTools messagingTools =
                new MessagingTools(deliveryService, sourceKey, attachmentCacheService, appConfig);
        CronjobTools cronjobTools = new CronjobTools(cronJobService, sourceKey);
        TodoTools todoTools = new TodoTools(appConfig, sourceKey);
        RunTools runTools = new RunTools(dashboardRunService);
        McpManageTools mcpManageTools = new McpManageTools(dashboardMcpService);
        CuratorManageTools curatorManageTools = new CuratorManageTools(dashboardCuratorService);
        PlatformToolsetsManageTools platformToolsetsManageTools =
                new PlatformToolsetsManageTools(dashboardPlatformToolsetsService);
        ProviderManageTools providerManageTools = new ProviderManageTools(dashboardProviderService);
        StatusManageTools statusManageTools = new StatusManageTools(dashboardStatusService);
        DiagnosticsManageTools diagnosticsManageTools =
                new DiagnosticsManageTools(dashboardDiagnosticsService);
        DoctorManageTools doctorManageTools = new DoctorManageTools(dashboardGatewayDoctorService);
        TuiRuntimeManageTools tuiRuntimeManageTools =
                new TuiRuntimeManageTools(appConfig, weixinQrSetupService, domesticQrSetupService);
        InsightsManageTools insightsManageTools = new InsightsManageTools(dashboardInsightsService);
        ApprovalEventsManageTools approvalEventsManageTools =
                new ApprovalEventsManageTools(dashboardApprovalEventsService);
        ApprovalQueueManageTools approvalQueueManageTools =
                new ApprovalQueueManageTools(dashboardDiagnosticsService);
        WorkspaceManageTools workspaceManageTools =
                new WorkspaceManageTools(dashboardWorkspaceService);
        WorkspaceConfigManageTools workspaceConfigManageTools =
                new WorkspaceConfigManageTools(dashboardRuntimeConfigService);
        ConfigManageTools configManageTools = new ConfigManageTools(dashboardConfigService);
        GatewaySetupManageTools gatewaySetupManageTools =
                new GatewaySetupManageTools(weixinQrSetupService, domesticQrSetupService);
        DelegateTools delegateTools = new DelegateTools(delegationService, sourceKey);
        ConfigTools configTools =
                new ConfigTools(runtimeSettingsService, gatewayRuntimeRefreshService, appConfig);
        String sysWorkDir = resolveWorkDir(agentScope);
        List<String> enabledToolNames = new ArrayList<String>();
        LinkedHashSet<String> enabledFileFunctionNames = new LinkedHashSet<String>();
        LinkedHashSet<String> enabledShellFunctionNames = new LinkedHashSet<String>();
        LinkedHashSet<String> enabledMediaFunctionNames = new LinkedHashSet<String>();
        for (String toolName : AgentRuntimePolicy.resolveAllowedTools(agentScope, TOOL_NAMES)) {
            if (!isEnabled(sourceKey, toolName)) {
                continue;
            }
            enabledToolNames.add(toolName);
            if (ToolNameConstants.isFileTool(toolName)) {
                enabledFileFunctionNames.add(toolName);
            } else if (ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                    || ToolNameConstants.TERMINAL.equals(toolName)) {
                enabledShellFunctionNames.add(toolName);
            } else if (ToolNameConstants.IMAGE_GENERATE.equals(toolName)
                    || ToolNameConstants.TEXT_TO_SPEECH.equals(toolName)
                    || ToolNameConstants.SPEECH_TRANSCRIBE.equals(toolName)) {
                enabledMediaFunctionNames.add(toolName);
            }
        }
        SolonClawFileStateTracker fileStateTracker = new SolonClawFileStateTracker();
        SolonClawFileReadWriteSkill fileSkill =
                new SolonClawFileReadWriteSkill(
                        sysWorkDir, securityPolicyService, appConfig, fileStateTracker);
        SolonClawPatchTools patchTools =
                new SolonClawPatchTools(sysWorkDir, securityPolicyService, fileStateTracker);
        ProcessRegistry activeProcessRegistry = resolveProcessRegistry();
        ShellTalent shellSkill =
                new SolonClawShellSkill(
                        sysWorkDir, appConfig, securityPolicyService, activeProcessRegistry);
        ProcessTools processTools =
                new ProcessTools(
                        activeProcessRegistry, sysWorkDir, securityPolicyService, appConfig);
        SolonClawCodeExecutionSkills.SafePythonSkill pythonSkill =
                new SolonClawCodeExecutionSkills.SafePythonSkill(
                        sysWorkDir, defaultPythonCommand(), securityPolicyService);
        SolonClawCodeExecutionSkills.SafeNodejsSkill nodejsSkill =
                new SolonClawCodeExecutionSkills.SafeNodejsSkill(sysWorkDir, securityPolicyService);
        SystemClockTalent systemClockSkill = new SystemClockTalent();
        SolonClawWebTools.SafeWebsearchTool websearchTool =
                new SolonClawWebTools.SafeWebsearchTool(securityPolicyService, null, appConfig);
        websearchTool.setWebSearchProviders(webSearchProviders);
        SolonClawWebTools.SafeWebfetchTool webfetchTool =
                new SolonClawWebTools.SafeWebfetchTool(securityPolicyService);
        SolonClawCodeExecutionSkills.SafeExecuteCodeTool executeCodeTool =
                new SolonClawCodeExecutionSkills.SafeExecuteCodeTool(
                        sysWorkDir,
                        defaultPythonCommand(),
                        securityPolicyService,
                        appConfig,
                        websearchTool,
                        webfetchTool,
                        enabledToolNames);
        SolonClawWebTools.SafeWebExtractTool webExtractTool =
                new SolonClawWebTools.SafeWebExtractTool(webfetchTool, sysWorkDir);
        SolonClawWebTools.SafeCodeSearchTool codeSearchTool =
                new SolonClawWebTools.SafeCodeSearchTool(securityPolicyService);
        BrowserTools browserTools = new BrowserTools(browserRuntimeService);
        SecurityAuditTools securityAuditTools =
                new SecurityAuditTools(
                        securityPolicyService,
                        new DangerousCommandApprovalService(null, appConfig, securityPolicyService),
                        new TirithSecurityService(appConfig),
                        appConfig);
        MediaSpeechTools mediaSpeechTools =
                imageGenerationService == null || speechService == null
                        ? null
                        : new MediaSpeechTools(imageGenerationService, speechService);
        VisionAnalyzeTools visionAnalyzeTools =
                new VisionAnalyzeTools(
                        new VisionAnalysisService(
                                appConfig,
                                attachmentCacheService,
                                securityPolicyService,
                                new LlmProviderService(appConfig),
                                () -> ProfileBeanResolver.getBean(LlmGateway.class)));
        boolean fileSkillAdded = false;
        boolean shellSkillAdded = false;
        boolean clockSkillAdded = false;
        boolean websearchToolAdded = false;
        boolean webfetchToolAdded = false;
        boolean webExtractToolAdded = false;
        boolean mediaSpeechToolsAdded = false;
        List<FunctionTool> dynamicTools = new ArrayList<FunctionTool>();
        List<Object> gatewayCandidates = new ArrayList<Object>();
        for (String toolName : enabledToolNames) {
            if (ToolNameConstants.isFileTool(toolName)) {
                if (!fileSkillAdded) {
                    tools.add(filteredTalent(fileSkill, enabledFileFunctionNames));
                    fileSkillAdded = true;
                }
            } else if (ToolNameConstants.PATCH.equals(toolName)) {
                tools.add(patchTools);
            } else if (ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                    || ToolNameConstants.TERMINAL.equals(toolName)) {
                if (!shellSkillAdded) {
                    tools.add(filteredTalent(shellSkill, enabledShellFunctionNames));
                    shellSkillAdded = true;
                }
            } else if (ToolNameConstants.PROCESS.equals(toolName)) {
                tools.add(processTools);
            } else if (ToolNameConstants.EXECUTE_CODE.equals(toolName)) {
                tools.add(executeCodeTool);
            } else if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
                tools.add(pythonSkill);
            } else if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
                tools.add(nodejsSkill);
            } else if (ToolNameConstants.GET_CURRENT_TIME.equals(toolName)) {
                if (!clockSkillAdded) {
                    tools.add(systemClockSkill);
                    clockSkillAdded = true;
                }
            } else if (ToolNameConstants.CONFIG_GET.equals(toolName)) {
                tools.add(new ConfigTools.ConfigGetTool(configTools));
            } else if (ToolNameConstants.CONFIG_SET.equals(toolName)) {
                tools.add(new ConfigTools.ConfigSetTool(configTools));
            } else if (ToolNameConstants.CONFIG_SET_SECRET.equals(toolName)) {
                tools.add(new ConfigTools.ConfigSetSecretTool(configTools));
            } else if (ToolNameConstants.CONFIG_REFRESH.equals(toolName)) {
                tools.add(new ConfigTools.ConfigRefreshTool(configTools));
            } else if (ToolNameConstants.TOOL_GATEWAY.equals(toolName)) {
                // 在直接工具收集完成后再加入，避免递归包装自身。
            } else if (ToolNameConstants.MCP.equals(toolName)) {
                if (mcpRuntimeService != null) {
                    for (ToolProvider provider : mcpRuntimeService.resolveEnabledToolProviders()) {
                        if (provider != null) {
                            java.util.Collection<FunctionTool> providedTools = provider.getTools();
                            if (providedTools != null) {
                                dynamicTools.addAll(providedTools);
                            }
                        }
                    }
                }
            } else if (ToolNameConstants.MCP_MANAGE.equals(toolName)) {
                tools.add(mcpManageTools);
            } else if (ToolNameConstants.CURATOR_MANAGE.equals(toolName)) {
                tools.add(curatorManageTools);
            } else if (ToolNameConstants.PLATFORM_TOOLSETS_MANAGE.equals(toolName)) {
                tools.add(platformToolsetsManageTools);
            } else if (ToolNameConstants.PROVIDER_MANAGE.equals(toolName)) {
                tools.add(providerManageTools);
            } else if (ToolNameConstants.MEMORY.equals(toolName)
                    || ToolNameConstants.MEMORY_SEARCH.equals(toolName)
                    || ToolNameConstants.MEMORY_GET.equals(toolName)) {
                if (!tools.contains(memoryTools)) {
                    tools.add(memoryTools);
                }
            } else if (ToolNameConstants.SESSION_SEARCH.equals(toolName)) {
                tools.add(sessionSearchTools);
            } else if (ToolNameConstants.SEARCH_MANAGE.equals(toolName)) {
                tools.add(searchManageTools);
            } else if (ToolNameConstants.SESSION_MANAGE.equals(toolName)) {
                tools.add(sessionManageTools);
            } else if (ToolNameConstants.ANALYTICS_MANAGE.equals(toolName)) {
                tools.add(analyticsManageTools);
            } else if (ToolNameConstants.LOGS_MANAGE.equals(toolName)) {
                tools.add(logsManageTools);
            } else if (ToolNameConstants.MEDIA_MANAGE.equals(toolName)) {
                tools.add(mediaManageTools);
            } else if (ToolNameConstants.STATUS_MANAGE.equals(toolName)) {
                tools.add(statusManageTools);
            } else if (ToolNameConstants.DIAGNOSTICS_MANAGE.equals(toolName)) {
                tools.add(diagnosticsManageTools);
            } else if (ToolNameConstants.DOCTOR_MANAGE.equals(toolName)) {
                tools.add(doctorManageTools);
            } else if (ToolNameConstants.TUI_RUNTIME_MANAGE.equals(toolName)) {
                tools.add(tuiRuntimeManageTools);
            } else if (ToolNameConstants.INSIGHTS_MANAGE.equals(toolName)) {
                tools.add(insightsManageTools);
            } else if (ToolNameConstants.APPROVAL_EVENTS_MANAGE.equals(toolName)) {
                tools.add(approvalEventsManageTools);
            } else if (ToolNameConstants.APPROVAL_QUEUE_MANAGE.equals(toolName)) {
                tools.add(approvalQueueManageTools);
            } else if (ToolNameConstants.WORKSPACE_MANAGE.equals(toolName)) {
                tools.add(workspaceManageTools);
            } else if (ToolNameConstants.WORKSPACE_CONFIG_MANAGE.equals(toolName)) {
                tools.add(workspaceConfigManageTools);
            } else if (ToolNameConstants.CONFIG_MANAGE.equals(toolName)) {
                tools.add(configManageTools);
            } else if (ToolNameConstants.GATEWAY_SETUP_MANAGE.equals(toolName)) {
                tools.add(gatewaySetupManageTools);
            } else if (ToolNameConstants.SKILLS_LIST.equals(toolName)) {
                tools.add(new SkillTools.SkillsListTool(skillTools));
            } else if (ToolNameConstants.SKILL_VIEW.equals(toolName)) {
                tools.add(new SkillTools.SkillViewTool(skillTools));
            } else if (ToolNameConstants.SKILL_FILES.equals(toolName)) {
                tools.add(new SkillTools.SkillFilesTool(skillTools));
            } else if (ToolNameConstants.SKILL_MANAGE.equals(toolName)) {
                tools.add(new SkillTools.SkillManageTool(skillTools));
            } else if (ToolNameConstants.TOOLSETS_MANAGE.equals(toolName)) {
                tools.add(toolsetsManageTools);
            } else if (ToolNameConstants.SKILLS_HUB_SEARCH.equals(toolName)) {
                tools.add(new SkillHubTools.SearchTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_INSPECT.equals(toolName)) {
                tools.add(new SkillHubTools.InspectTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_INSTALL.equals(toolName)) {
                tools.add(new SkillHubTools.InstallTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_LIST.equals(toolName)) {
                tools.add(new SkillHubTools.ListTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_CHECK.equals(toolName)) {
                tools.add(new SkillHubTools.CheckTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_UPDATE.equals(toolName)) {
                tools.add(new SkillHubTools.UpdateTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_AUDIT.equals(toolName)) {
                tools.add(new SkillHubTools.AuditTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_UNINSTALL.equals(toolName)) {
                tools.add(new SkillHubTools.UninstallTool(skillHubTools));
            } else if (ToolNameConstants.SKILLS_HUB_TAP.equals(toolName)) {
                tools.add(new SkillHubTools.TapTool(skillHubTools));
            } else if (ToolNameConstants.SEND_MESSAGE.equals(toolName)) {
                tools.add(messagingTools);
            } else if (ToolNameConstants.CRONJOB.equals(toolName)) {
                tools.add(cronjobTools);
            } else if (ToolNameConstants.TODO.equals(toolName)) {
                tools.add(todoTools);
            } else if (ToolNameConstants.AGENT_MANAGE.equals(toolName)) {
            } else if (ToolNameConstants.RUN_MANAGE.equals(toolName)) {
                tools.add(runTools);
            } else if (ToolNameConstants.DELEGATE_TASK.equals(toolName)) {
                tools.add(delegateTools);
            } else if (ToolNameConstants.WEBSEARCH.equals(toolName)) {
                if (!websearchToolAdded) {
                    tools.add(websearchTool);
                    websearchToolAdded = true;
                }
            } else if (ToolNameConstants.WEBFETCH.equals(toolName)) {
                if (!webfetchToolAdded) {
                    tools.add(webfetchTool);
                    webfetchToolAdded = true;
                }
            } else if (ToolNameConstants.WEB_EXTRACT.equals(toolName)) {
                if (!webExtractToolAdded) {
                    tools.add(webExtractTool);
                    webExtractToolAdded = true;
                }
            } else if (ToolNameConstants.CODESEARCH.equals(toolName)) {
                tools.add(codeSearchTool);
            } else if (ToolNameConstants.IMAGE_GENERATE.equals(toolName)
                    || ToolNameConstants.TEXT_TO_SPEECH.equals(toolName)
                    || ToolNameConstants.SPEECH_TRANSCRIBE.equals(toolName)) {
                if (mediaSpeechTools != null && !mediaSpeechToolsAdded) {
                    tools.add(
                            filteredMethodToolProvider(
                                    mediaSpeechTools, enabledMediaFunctionNames));
                    mediaSpeechToolsAdded = true;
                }
            } else if (ToolNameConstants.VISION_ANALYZE.equals(toolName)) {
                tools.add(visionAnalyzeTools);
            } else if (ToolNameConstants.BROWSER.equals(toolName)) {
                tools.add(browserTools);
            } else if (ToolNameConstants.SECURITY_AUDIT.equals(toolName)) {
                tools.add(securityAuditTools);
            } else if (ToolNameConstants.CLARIFY.equals(toolName)) {
                tools.add(new ClarifyTools());
            }
        }
        LinkedHashSet<String> occupiedToolNames = collectFunctionToolNames(tools);
        occupiedToolNames.add("call_tool");
        occupiedToolNames.add("search_tools");
        occupiedToolNames.add("get_tool_detail");
        addUniqueDynamicTools(tools, dynamicTools, occupiedToolNames);
        addUniqueDynamicTools(tools, resolveExtraTools(sourceKey, agentScope), occupiedToolNames);
        if (isGatewayEnabled(sourceKey, agentScope)) {
            gatewayCandidates.addAll(tools);
            ToolGatewayTalent gatewaySkill = buildToolGateway(gatewayCandidates);
            if (gatewaySkill != null) {
                tools.add(gatewaySkill);
            }
        }
        return tools;
    }

    /**
     * 保留 Talent 的指令、支持判断和生命周期，只按选择器过滤模型可见函数。
     *
     * @param delegate 原始 Talent。
     * @param allowedToolNames 本轮明确启用的函数名。
     * @return 仅暴露允许函数的 Talent 包装。
     */
    static Talent filteredTalent(Talent delegate, Iterable<String> allowedToolNames) {
        return new FilteredTalent(delegate, normalizedToolNames(allowedToolNames));
    }

    /**
     * 将普通多函数 Bean 展开后按选择器过滤，避免启用一个函数时连带暴露同 Bean 其他函数。
     *
     * @param bean 包含 ToolMapping 方法的 Bean。
     * @param allowedToolNames 本轮明确启用的函数名。
     * @return 仅返回允许函数的工具提供器。
     */
    static ToolProvider filteredMethodToolProvider(Object bean, Iterable<String> allowedToolNames) {
        LinkedHashSet<String> allowedNames = normalizedToolNames(allowedToolNames);
        Collection<FunctionTool> candidates =
                bean instanceof ToolProvider
                        ? ((ToolProvider) bean).getTools()
                        : new MethodToolProvider(bean).getTools();
        List<FunctionTool> filteredTools = filterFunctionTools(candidates, allowedNames);
        return new FilteredMethodToolProvider(bean, filteredTools);
    }

    /**
     * 规范化选择器函数名，工具名匹配不区分大小写。
     *
     * @param toolNames 原始函数名集合。
     * @return 去空白、转小写且保持顺序的名称集合。
     */
    private static LinkedHashSet<String> normalizedToolNames(Iterable<String> toolNames) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        if (toolNames == null) {
            return names;
        }
        for (String toolName : toolNames) {
            addNormalizedToolName(names, toolName);
        }
        return names;
    }

    /**
     * 从候选函数中保留选择器明确允许的函数。
     *
     * @param tools 候选函数集合。
     * @param allowedNames 已规范化的允许名称。
     * @return 不可变且保持原声明顺序的函数列表。
     */
    private static List<FunctionTool> filterFunctionTools(
            Iterable<FunctionTool> tools, Set<String> allowedNames) {
        List<FunctionTool> filtered = new ArrayList<FunctionTool>();
        if (tools != null && allowedNames != null) {
            for (FunctionTool tool : tools) {
                String name = tool == null ? null : tool.name();
                if (StrUtil.isNotBlank(name)
                        && allowedNames.contains(name.trim().toLowerCase(Locale.ROOT))) {
                    filtered.add(tool);
                }
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    /** 保留 Talent 行为、仅过滤其模型函数集合的包装器。 */
    private static final class FilteredTalent implements Talent {
        /** 原始 Talent，负责真实指令、支持判断和工具执行。 */
        private final Talent delegate;

        /** 当前选择器允许暴露的规范化函数名。 */
        private final Set<String> allowedToolNames;

        /**
         * 创建 Talent 过滤包装。
         *
         * @param delegate 原始 Talent。
         * @param allowedToolNames 允许暴露的规范化函数名。
         */
        private FilteredTalent(Talent delegate, Set<String> allowedToolNames) {
            this.delegate = delegate;
            this.allowedToolNames = allowedToolNames;
        }

        /**
         * @return 返回原始 Talent 的启用状态。
         */
        @Override
        public boolean isEnabled() {
            return delegate.isEnabled();
        }

        /**
         * @param enabled 设置原始 Talent 的启用状态。
         */
        @Override
        public void setEnabled(Boolean enabled) {
            delegate.setEnabled(enabled);
        }

        /**
         * @return 返回原始 Talent 名称。
         */
        @Override
        public String name() {
            return delegate.name();
        }

        /**
         * @return 返回原始 Talent 描述。
         */
        @Override
        public String description() {
            return delegate.description();
        }

        /**
         * @return 返回原始 Talent 元数据。
         */
        @Override
        public org.noear.solon.ai.chat.talent.TalentMetadata metadata() {
            return delegate.metadata();
        }

        /**
         * @param prompt 当前提示词。
         * @return 返回原始 Talent 的支持判断。
         */
        @Override
        public boolean isSupported(Prompt prompt) {
            return delegate.isSupported(prompt);
        }

        /**
         * @param prompt 当前提示词。
         */
        @Override
        public void onAttach(Prompt prompt) {
            delegate.onAttach(prompt);
        }

        /**
         * @param prompt 当前提示词。
         * @return 返回原始 Talent 指令。
         */
        @Override
        public String getInstruction(Prompt prompt) {
            return delegate.getInstruction(prompt);
        }

        /**
         * @param prompt 当前提示词。
         * @return 仅包含选择器明确启用函数的集合。
         */
        @Override
        public Collection<FunctionTool> getTools(Prompt prompt) {
            return filterFunctionTools(delegate.getTools(prompt), allowedToolNames);
        }

        /**
         * @return 返回原始 Talent 的诊断文本。
         */
        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /** 普通 Bean 展开后的不可变函数过滤提供器。 */
    private static final class FilteredMethodToolProvider implements ToolProvider {
        /** 原始 Bean，仅用于保留诊断可读性。 */
        private final Object bean;

        /** 已按选择器过滤的不可变函数列表。 */
        private final List<FunctionTool> tools;

        /**
         * 创建普通 Bean 工具过滤提供器。
         *
         * @param bean 原始 Bean。
         * @param tools 已过滤函数列表。
         */
        private FilteredMethodToolProvider(Object bean, List<FunctionTool> tools) {
            this.bean = bean;
            this.tools = tools;
        }

        /**
         * @return 返回已过滤的模型函数集合。
         */
        @Override
        public Collection<FunctionTool> getTools() {
            return tools;
        }

        /**
         * @return 返回原始 Bean 的诊断文本。
         */
        @Override
        public String toString() {
            return bean.toString();
        }
    }

    /**
     * 收集已注册对象实际暴露给模型的函数名，用于阻止动态工具覆盖内置实现。
     *
     * @param toolObjects 已注册工具对象。
     * @return 返回小写规范化且保持声明顺序的函数名集合。
     */
    private static LinkedHashSet<String> collectFunctionToolNames(List<Object> toolObjects) {
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (Object toolObject : toolObjects) {
            if (toolObject == null) {
                continue;
            }
            if (toolObject instanceof FunctionTool) {
                addNormalizedToolName(names, ((FunctionTool) toolObject).name());
            } else if (toolObject instanceof ToolProvider) {
                addFunctionToolNames(names, ((ToolProvider) toolObject).getTools());
            } else if (toolObject instanceof Talent) {
                addFunctionToolNames(names, ((Talent) toolObject).getTools(Prompt.of("")));
            } else {
                addFunctionToolNames(names, new MethodToolProvider(toolObject).getTools());
            }
        }
        return names;
    }

    /**
     * 将一组函数工具名加入占名集合。
     *
     * @param names 已占用的规范化名称。
     * @param tools 函数工具集合。
     */
    private static void addFunctionToolNames(Set<String> names, Iterable<FunctionTool> tools) {
        if (tools == null) {
            return;
        }
        for (FunctionTool tool : tools) {
            if (tool != null) {
                addNormalizedToolName(names, tool.name());
            }
        }
    }

    /**
     * 规范化并记录一个模型函数名。
     *
     * @param names 已占用名称集合。
     * @param name 原始函数名。
     */
    private static void addNormalizedToolName(Set<String> names, String name) {
        if (StrUtil.isNotBlank(name)) {
            names.add(name.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 按声明顺序加入动态工具；名称大小写等价时保留首次注册实现并记录告警。
     *
     * @param target 最终工具对象列表。
     * @param dynamicTools 待加入的 MCP 或额外函数工具。
     * @param occupiedNames 已被内置工具或先前动态工具占用的名称集合。
     */
    static void addUniqueDynamicTools(
            List<Object> target,
            List<? extends FunctionTool> dynamicTools,
            LinkedHashSet<String> occupiedNames) {
        if (target == null || dynamicTools == null || occupiedNames == null) {
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String occupiedName : occupiedNames) {
            addNormalizedToolName(normalized, occupiedName);
        }
        occupiedNames.clear();
        occupiedNames.addAll(normalized);
        for (FunctionTool tool : dynamicTools) {
            String name = tool == null ? null : tool.name();
            String normalizedName =
                    StrUtil.isBlank(name) ? null : name.trim().toLowerCase(Locale.ROOT);
            if (normalizedName == null) {
                log.warn("跳过名称为空的动态工具注册");
                continue;
            }
            if (!occupiedNames.add(normalizedName)) {
                log.warn("跳过名称冲突的动态工具注册: {}", name);
                continue;
            }
            target.add(tool);
        }
    }

    /**
     * 判断是否消息网关启用。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 如果消息网关启用满足条件则返回 true，否则返回 false。
     */
    private boolean isGatewayEnabled(String sourceKey, AgentRuntimeScope agentScope) {
        if (!AgentRuntimePolicy.isToolAllowed(agentScope, ToolNameConstants.TOOL_GATEWAY)) {
            return false;
        }
        try {
            return preferenceStore.isToolEnabled(sourceKey, ToolNameConstants.TOOL_GATEWAY, false);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 构建工具消息网关。
     *
     * @param candidates candidates标识或键值。
     * @return 返回创建好的工具消息网关。
     */
    private ToolGatewayTalent buildToolGateway(List<Object> candidates) {
        ToolGatewayTalent gateway =
                new ToolGatewayTalent().dynamicThreshold(0).listThreshold(40).searchThreshold(100);
        boolean added = false;
        for (Object candidate : candidates) {
            if (candidate == null || candidate instanceof ToolGatewayTalent) {
                continue;
            }
            if (candidate instanceof ToolProvider) {
                gateway.addTool((ToolProvider) candidate);
                added = true;
            } else if (candidate instanceof Talent) {
                for (FunctionTool tool : ((Talent) candidate).getTools(Prompt.of(""))) {
                    gateway.addTool(tool);
                    added = true;
                }
            } else if (candidate instanceof FunctionTool) {
                gateway.addTool((FunctionTool) candidate);
                added = true;
            }
        }
        return added ? gateway : null;
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具名称。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回解析后的启用工具Names。
     */
    @Override
    public List<String> resolveEnabledToolNames(String sourceKey) {
        return resolveEnabledToolNames(sourceKey, null);
    }

    /**
     * 解析来源键在当前 Agent 范围下启用的工具名称。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的启用工具Names。
     */
    @Override
    public List<String> resolveEnabledToolNames(String sourceKey, AgentRuntimeScope agentScope) {
        List<String> result = new ArrayList<String>();
        for (String toolName : AgentRuntimePolicy.resolveAllowedTools(agentScope, TOOL_NAMES)) {
            if (isEnabled(sourceKey, toolName)) {
                result.add(toolName);
            }
        }
        for (String toolName : extraToolNames()) {
            if (isExtraToolAllowed(agentScope, sourceKey, toolName)
                    && isEnabled(sourceKey, toolName)) {
                result.add(toolName);
            }
        }
        return result;
    }

    /**
     * 启用工具。
     *
     * @param sourceKey 渠道来源键。
     * @param toolNames 工具Names参数。
     */
    @Override
    public void enableTools(String sourceKey, List<String> toolNames) {
        for (String toolName : toolNames) {
            if (TOOL_NAMES.contains(toolName)) {
                setToolEnabled(sourceKey, toolName, true);
            } else if (extraToolNames().contains(toolName)) {
                setToolEnabled(sourceKey, toolName, true);
            }
        }
    }

    /**
     * 禁用工具。
     *
     * @param sourceKey 渠道来源键。
     * @param toolNames 工具Names参数。
     */
    @Override
    public void disableTools(String sourceKey, List<String> toolNames) {
        for (String toolName : toolNames) {
            if (TOOL_NAMES.contains(toolName)) {
                setToolEnabled(sourceKey, toolName, false);
            } else if (extraToolNames().contains(toolName)) {
                setToolEnabled(sourceKey, toolName, false);
            }
        }
    }

    /** 读取工具启用状态。 */
    private boolean isEnabled(String sourceKey, String toolName) {
        try {
            if (ToolNameConstants.isFileTool(toolName) && areCoreFileToolsDisabled(sourceKey)) {
                return false;
            }
            if (ToolNameConstants.TOOL_GATEWAY.equals(toolName)) {
                return preferenceStore.isToolEnabled(sourceKey, toolName, false);
            }
            return preferenceStore.isToolEnabled(sourceKey, toolName);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 判断文件工具组是否已整体禁用。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果文件工具组被整体禁用则返回 true。
     */
    private boolean areCoreFileToolsDisabled(String sourceKey) throws SQLException {
        for (String fileToolName : coreFileToolNames()) {
            if (!preferenceStore.hasScopedToolToggle(sourceKey, fileToolName)
                    || preferenceStore.isToolEnabled(sourceKey, fileToolName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 文件工具名集合。
     *
     * @return 返回文件工具名集合。
     */
    private List<String> coreFileToolNames() {
        return Arrays.asList(
                ToolNameConstants.FILE_READ,
                ToolNameConstants.FILE_WRITE,
                ToolNameConstants.READ_FILE,
                ToolNameConstants.WRITE_FILE,
                ToolNameConstants.SEARCH_FILES,
                ToolNameConstants.FILE_LIST,
                ToolNameConstants.FILE_DELETE);
    }

    /** 设置工具启用状态。 */
    private void setToolEnabled(String sourceKey, String toolName, boolean enabled) {
        try {
            preferenceStore.setToolEnabled(sourceKey, toolName, enabled);
        } catch (SQLException e) {
            log.warn(
                    "工具启用偏好写入失败 source={} tool={} enabled={} error={}",
                    SecretRedactor.redact(sourceKey, 120),
                    SecretRedactor.redact(toolName, 120),
                    Boolean.valueOf(enabled),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 执行默认Python命令相关逻辑。
     *
     * @return 返回默认Python命令结果。
     */
    private String defaultPythonCommand() {
        return isWindows() ? "python" : "python3";
    }

    /**
     * 解析Work Dir。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的Work Dir。
     */
    private String resolveWorkDir(AgentRuntimeScope agentScope) {
        if (agentScope != null && StrUtil.isNotBlank(agentScope.getWorkspaceDir())) {
            return agentScope.getWorkspaceDir();
        }
        return appConfig.getWorkspace().getDir();
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 解析进程注册表。
     *
     * @return 返回解析后的进程注册表。
     */
    private ProcessRegistry resolveProcessRegistry() {
        return processRegistry == null ? new ProcessRegistry(appConfig) : processRegistry;
    }

    /**
     * 解析额外工具。
     *
     * @param sourceKey 渠道来源键。
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @return 返回解析后的额外工具。
     */
    private List<FunctionTool> resolveExtraTools(String sourceKey, AgentRuntimeScope agentScope) {
        List<FunctionTool> result = new ArrayList<FunctionTool>();
        Set<String> builtinNames = new LinkedHashSet<String>(TOOL_NAMES);
        Set<String> seen = new LinkedHashSet<String>();
        for (final ToolRegistration registration : extraTools) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isBlank(name)
                    || builtinNames.contains(name)
                    || !seen.add(name)
                    || !isExtraToolAllowed(agentScope, sourceKey, name)
                    || !isEnabled(sourceKey, name)) {
                continue;
            }
            FunctionToolDesc tool = new FunctionToolDesc(name);
            tool.description(StrUtil.blankToDefault(registration.getDescription(), "Additional tool"));
            if (registration.getSchema() != null && !registration.getSchema().isEmpty()) {
                tool.inputSchema(org.noear.snack4.ONode.serialize(registration.getSchema()));
            }
            tool.doHandle(
                    args -> {
                        return registration.getHandler() == null
                                ? ""
                                : registration.getHandler().apply(args);
                    });
            result.add(tool);
        }
        return result;
    }

    /**
     * 执行额外工具Names相关逻辑。
     *
     * @return 返回额外工具Names结果。
     */
    private List<String> extraToolNames() {
        List<String> result = new ArrayList<String>();
        Set<String> builtinNames = new LinkedHashSet<String>(TOOL_NAMES);
        Set<String> seen = new LinkedHashSet<String>();
        for (ToolRegistration registration : extraTools) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isNotBlank(name) && !builtinNames.contains(name) && seen.add(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * 判断是否额外工具Allowed。
     *
     * @param agentScope 当前运行冻结后的 Agent 范围。
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果额外工具Allowed满足条件则返回 true，否则返回 false。
     */
    private boolean isExtraToolAllowed(
            AgentRuntimeScope agentScope, String sourceKey, String toolName) {
        if (isDelegateSourceKey(sourceKey) && !hasExplicitScopedToolToggle(sourceKey, toolName)) {
            return false;
        }
        return AgentRuntimePolicy.resolveAllowedTools(agentScope, listToolNames())
                .contains(toolName);
    }

    /**
     * 判断是否委托来源键。
     *
     * @param sourceKey 渠道来源键。
     * @return 如果委托来源键满足条件则返回 true，否则返回 false。
     */
    private boolean isDelegateSourceKey(String sourceKey) {
        return StrUtil.nullToEmpty(sourceKey).contains(":delegate:");
    }

    /**
     * 按需解析 Dashboard 诊断服务，避免工具注册表创建时要求诊断服务先完成注入。
     *
     * @return 返回 Dashboard 诊断服务，容器尚未就绪时返回 null。
     */
    private DashboardDiagnosticsService resolveDashboardDiagnosticsService() {
        return ProfileBeanResolver.getBean(DashboardDiagnosticsService.class);
    }

    /**
     * 判断是否存在Explicit Scoped工具Toggle。
     *
     * @param sourceKey 渠道来源键。
     * @param toolName 工具名称。
     * @return 如果Explicit Scoped工具Toggle满足条件则返回 true，否则返回 false。
     */
    private boolean hasExplicitScopedToolToggle(String sourceKey, String toolName) {
        try {
            return preferenceStore.hasScopedToolToggle(sourceKey, toolName);
        } catch (SQLException e) {
            return false;
        }
    }
}
