package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.provider.WebSearchProvider;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import com.jimuqu.solon.claw.web.DashboardApprovalEventsService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import com.jimuqu.solon.claw.web.DashboardInsightsService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardPlatformToolsetsService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import java.util.List;
import java.util.function.Supplier;

/**
 * DefaultToolRegistry 的 Builder 类。
 * <p>
 * 使用 Builder 模式简化 DefaultToolRegistry 的创建，避免伸缩构造函数。
 *
 * @author jimuqu
 * @since 1.0.0
 */
public class DefaultToolRegistryBuilder {

    /** 应用运行配置。 */
    private AppConfig appConfig;
    /** 工具开关偏好存储。 */
    private SqlitePreferenceStore preferenceStore;
    /** 会话仓储。 */
    private SessionRepository sessionRepository;
    /** Agent 配置服务。 */
    private AgentProfileService agentProfileService;
    /** 定时任务服务。 */
    private CronJobService cronJobService;
    /** 渠道投递服务。 */
    private DeliveryService deliveryService;
    /** 长期记忆服务。 */
    private MemoryService memoryService;
    /** 会话搜索服务。 */
    private SessionSearchService sessionSearchService;
    /** 本地技能服务。 */
    private LocalSkillService localSkillService;
    /** 技能中心服务。 */
    private SkillHubService skillHubService;
    /** 会话检查点服务。 */
    private CheckpointService checkpointService;
    /** 子 Agent 委托服务。 */
    private DelegationService delegationService;
    /** 附件缓存服务。 */
    private AttachmentCacheService attachmentCacheService;
    /** 运行时设置服务。 */
    private RuntimeSettingsService runtimeSettingsService;
    /** 网关运行时刷新服务。 */
    private GatewayRuntimeRefreshService gatewayRuntimeRefreshService;
    /** 文件与网络安全策略服务。 */
    private SecurityPolicyService securityPolicyService;
    /** 危险操作审批服务。 */
    private DangerousCommandApprovalService approvalService;
    /** 受管进程注册表。 */
    private ProcessRegistry processRegistry;
    /** MCP 运行时服务。 */
    private McpRuntimeService mcpRuntimeService;
    /** Dashboard MCP 管理服务。 */
    private DashboardMcpService dashboardMcpService;
    /** Dashboard 技能维护服务。 */
    private DashboardCuratorService dashboardCuratorService;
    /** Dashboard 平台工具集服务。 */
    private DashboardPlatformToolsetsService dashboardPlatformToolsetsService;
    /** Dashboard 模型提供方服务。 */
    private DashboardProviderService dashboardProviderService;
    /** Dashboard 状态服务。 */
    private DashboardStatusService dashboardStatusService;
    /** Dashboard 网关诊断服务。 */
    private DashboardGatewayDoctorService dashboardGatewayDoctorService;
    /** Dashboard 洞察服务。 */
    private DashboardInsightsService dashboardInsightsService;
    /** Dashboard 审批事件服务。 */
    private DashboardApprovalEventsService dashboardApprovalEventsService;
    /** Dashboard 诊断服务的延迟解析器。 */
    private Supplier<DashboardDiagnosticsService> dashboardDiagnosticsService;
    /** Dashboard 工作区服务。 */
    private DashboardWorkspaceService dashboardWorkspaceService;
    /** Dashboard 配置元数据服务。 */
    private DashboardConfigService dashboardConfigService;
    /** Dashboard 运行配置服务。 */
    private DashboardRuntimeConfigService dashboardRuntimeConfigService;
    /** 微信二维码配置服务。 */
    private WeixinQrSetupService weixinQrSetupService;
    /** 国内渠道二维码配置服务。 */
    private DomesticQrSetupService domesticQrSetupService;
    /** 浏览器自动化运行时。 */
    private BrowserRuntimeService browserRuntimeService;
    /** 图片生成服务。 */
    private ImageGenerationService imageGenerationService;
    /** 语音处理服务。 */
    private SpeechService speechService;
    /** Dashboard Agent 运行服务。 */
    private DashboardRunService dashboardRunService;
    /** Dashboard 查询使用的 SQLite 数据库。 */
    private SqliteDatabase sqliteDatabase;
    /** Agent 运行记录仓储。 */
    private AgentRunRepository agentRunRepository;
    /** 定时任务记录仓储。 */
    private CronJobRepository cronJobRepository;
    /** 用量事件仓储。 */
    private UsageEventRepository usageEventRepository;
    /** 插件附加工具。 */
    private List<ToolRegistration> pluginTools;
    /** Web 搜索提供方。 */
    private List<WebSearchProvider> webSearchProviders;

    /** 设置工具注册表使用的应用运行配置。 */
    public DefaultToolRegistryBuilder appConfig(AppConfig appConfig) {
        this.appConfig = appConfig;
        return this;
    }

    /** 设置工具开关偏好存储。 */
    public DefaultToolRegistryBuilder preferenceStore(SqlitePreferenceStore preferenceStore) {
        this.preferenceStore = preferenceStore;
        return this;
    }

    /** 设置会话仓储。 */
    public DefaultToolRegistryBuilder sessionRepository(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
        return this;
    }

    /** 设置 Agent 配置服务。 */
    public DefaultToolRegistryBuilder agentProfileService(AgentProfileService agentProfileService) {
        this.agentProfileService = agentProfileService;
        return this;
    }

    /** 设置定时任务服务。 */
    public DefaultToolRegistryBuilder cronJobService(CronJobService cronJobService) {
        this.cronJobService = cronJobService;
        return this;
    }

    /** 设置渠道投递服务。 */
    public DefaultToolRegistryBuilder deliveryService(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
        return this;
    }

    /** 设置长期记忆服务。 */
    public DefaultToolRegistryBuilder memoryService(MemoryService memoryService) {
        this.memoryService = memoryService;
        return this;
    }

    /** 设置会话搜索服务。 */
    public DefaultToolRegistryBuilder sessionSearchService(SessionSearchService sessionSearchService) {
        this.sessionSearchService = sessionSearchService;
        return this;
    }

    /** 设置本地技能服务。 */
    public DefaultToolRegistryBuilder localSkillService(LocalSkillService localSkillService) {
        this.localSkillService = localSkillService;
        return this;
    }

    /** 设置技能中心服务。 */
    public DefaultToolRegistryBuilder skillHubService(SkillHubService skillHubService) {
        this.skillHubService = skillHubService;
        return this;
    }

    /** 设置会话检查点服务。 */
    public DefaultToolRegistryBuilder checkpointService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
        return this;
    }

    /** 设置子 Agent 委托服务。 */
    public DefaultToolRegistryBuilder delegationService(DelegationService delegationService) {
        this.delegationService = delegationService;
        return this;
    }

    /** 设置附件缓存服务。 */
    public DefaultToolRegistryBuilder attachmentCacheService(AttachmentCacheService attachmentCacheService) {
        this.attachmentCacheService = attachmentCacheService;
        return this;
    }

    /** 设置运行时配置服务。 */
    public DefaultToolRegistryBuilder runtimeSettingsService(RuntimeSettingsService runtimeSettingsService) {
        this.runtimeSettingsService = runtimeSettingsService;
        return this;
    }

    /** 设置网关运行时刷新服务。 */
    public DefaultToolRegistryBuilder gatewayRuntimeRefreshService(GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        return this;
    }

    /** 设置文件与网络安全策略服务。 */
    public DefaultToolRegistryBuilder securityPolicyService(SecurityPolicyService securityPolicyService) {
        this.securityPolicyService = securityPolicyService;
        return this;
    }

    /** 设置危险操作审批服务。 */
    public DefaultToolRegistryBuilder approvalService(DangerousCommandApprovalService approvalService) {
        this.approvalService = approvalService;
        return this;
    }

    /** 设置受管进程注册表。 */
    public DefaultToolRegistryBuilder processRegistry(ProcessRegistry processRegistry) {
        this.processRegistry = processRegistry;
        return this;
    }

    /** 设置 MCP 运行时服务。 */
    public DefaultToolRegistryBuilder mcpRuntimeService(McpRuntimeService mcpRuntimeService) {
        this.mcpRuntimeService = mcpRuntimeService;
        return this;
    }

    /** 设置 Dashboard MCP 管理服务。 */
    public DefaultToolRegistryBuilder dashboardMcpService(DashboardMcpService dashboardMcpService) {
        this.dashboardMcpService = dashboardMcpService;
        return this;
    }

    /** 设置 Dashboard 技能维护服务。 */
    public DefaultToolRegistryBuilder dashboardCuratorService(DashboardCuratorService dashboardCuratorService) {
        this.dashboardCuratorService = dashboardCuratorService;
        return this;
    }

    /** 设置 Dashboard 平台工具集服务。 */
    public DefaultToolRegistryBuilder dashboardPlatformToolsetsService(DashboardPlatformToolsetsService dashboardPlatformToolsetsService) {
        this.dashboardPlatformToolsetsService = dashboardPlatformToolsetsService;
        return this;
    }

    /** 设置 Dashboard 模型提供方服务。 */
    public DefaultToolRegistryBuilder dashboardProviderService(DashboardProviderService dashboardProviderService) {
        this.dashboardProviderService = dashboardProviderService;
        return this;
    }

    /** 设置 Dashboard 状态服务。 */
    public DefaultToolRegistryBuilder dashboardStatusService(DashboardStatusService dashboardStatusService) {
        this.dashboardStatusService = dashboardStatusService;
        return this;
    }

    /** 设置 Dashboard 网关诊断服务。 */
    public DefaultToolRegistryBuilder dashboardGatewayDoctorService(DashboardGatewayDoctorService dashboardGatewayDoctorService) {
        this.dashboardGatewayDoctorService = dashboardGatewayDoctorService;
        return this;
    }

    /** 设置 Dashboard 洞察服务。 */
    public DefaultToolRegistryBuilder dashboardInsightsService(DashboardInsightsService dashboardInsightsService) {
        this.dashboardInsightsService = dashboardInsightsService;
        return this;
    }

    /** 设置 Dashboard 审批事件服务。 */
    public DefaultToolRegistryBuilder dashboardApprovalEventsService(DashboardApprovalEventsService dashboardApprovalEventsService) {
        this.dashboardApprovalEventsService = dashboardApprovalEventsService;
        return this;
    }

    /** 设置 Dashboard 诊断服务的延迟解析器。 */
    public DefaultToolRegistryBuilder dashboardDiagnosticsService(Supplier<DashboardDiagnosticsService> dashboardDiagnosticsService) {
        this.dashboardDiagnosticsService = dashboardDiagnosticsService;
        return this;
    }

    /** 设置 Dashboard 工作区服务。 */
    public DefaultToolRegistryBuilder dashboardWorkspaceService(DashboardWorkspaceService dashboardWorkspaceService) {
        this.dashboardWorkspaceService = dashboardWorkspaceService;
        return this;
    }

    /** 设置 Dashboard 配置元数据服务。 */
    public DefaultToolRegistryBuilder dashboardConfigService(DashboardConfigService dashboardConfigService) {
        this.dashboardConfigService = dashboardConfigService;
        return this;
    }

    /** 设置 Dashboard 运行配置服务。 */
    public DefaultToolRegistryBuilder dashboardRuntimeConfigService(DashboardRuntimeConfigService dashboardRuntimeConfigService) {
        this.dashboardRuntimeConfigService = dashboardRuntimeConfigService;
        return this;
    }

    /** 设置微信二维码配置服务。 */
    public DefaultToolRegistryBuilder weixinQrSetupService(WeixinQrSetupService weixinQrSetupService) {
        this.weixinQrSetupService = weixinQrSetupService;
        return this;
    }

    /** 设置国内渠道二维码配置服务。 */
    public DefaultToolRegistryBuilder domesticQrSetupService(DomesticQrSetupService domesticQrSetupService) {
        this.domesticQrSetupService = domesticQrSetupService;
        return this;
    }

    /** 设置浏览器自动化运行时。 */
    public DefaultToolRegistryBuilder browserRuntimeService(BrowserRuntimeService browserRuntimeService) {
        this.browserRuntimeService = browserRuntimeService;
        return this;
    }

    /** 设置图片生成服务。 */
    public DefaultToolRegistryBuilder imageGenerationService(ImageGenerationService imageGenerationService) {
        this.imageGenerationService = imageGenerationService;
        return this;
    }

    /** 设置语音处理服务。 */
    public DefaultToolRegistryBuilder speechService(SpeechService speechService) {
        this.speechService = speechService;
        return this;
    }

    /** 设置 Dashboard Agent 运行服务。 */
    public DefaultToolRegistryBuilder dashboardRunService(DashboardRunService dashboardRunService) {
        this.dashboardRunService = dashboardRunService;
        return this;
    }

    /** 设置 Dashboard 查询使用的 SQLite 数据库。 */
    public DefaultToolRegistryBuilder sqliteDatabase(SqliteDatabase sqliteDatabase) {
        this.sqliteDatabase = sqliteDatabase;
        return this;
    }

    /** 设置 Agent 运行记录仓储。 */
    public DefaultToolRegistryBuilder agentRunRepository(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
        return this;
    }

    /** 设置定时任务记录仓储。 */
    public DefaultToolRegistryBuilder cronJobRepository(CronJobRepository cronJobRepository) {
        this.cronJobRepository = cronJobRepository;
        return this;
    }

    /** 设置用量事件仓储。 */
    public DefaultToolRegistryBuilder usageEventRepository(UsageEventRepository usageEventRepository) {
        this.usageEventRepository = usageEventRepository;
        return this;
    }

    /** 设置插件附加工具。 */
    public DefaultToolRegistryBuilder pluginTools(List<ToolRegistration> pluginTools) {
        this.pluginTools = pluginTools;
        return this;
    }

    /** 设置 Web 搜索提供方。 */
    public DefaultToolRegistryBuilder webSearchProviders(List<WebSearchProvider> webSearchProviders) {
        this.webSearchProviders = webSearchProviders;
        return this;
    }

    /**
     * 构建 DefaultToolRegistry 实例。
     *
     * @return 返回构建的 DefaultToolRegistry 实例。
     */
    public DefaultToolRegistry build() {
        return new DefaultToolRegistry(
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
                pluginTools,
                webSearchProviders);
    }
}
