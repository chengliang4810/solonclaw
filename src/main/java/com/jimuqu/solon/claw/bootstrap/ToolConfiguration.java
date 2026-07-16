package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.engine.DefaultConversationOrchestrator;
import com.jimuqu.solon.claw.engine.DefaultDelegationService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.media.VisionAnalysisService;
import com.jimuqu.solon.claw.provider.BrowserProvider;
import com.jimuqu.solon.claw.provider.ImageGenProvider;
import com.jimuqu.solon.claw.provider.SpeechProvider;
import com.jimuqu.solon.claw.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.provider.WebSearchProvider;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import com.jimuqu.solon.claw.profile.ProfileChildRuntimeMarker;
import com.jimuqu.solon.claw.scheduler.CronApprovalResumeObserver;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.ConversationOrchestratorHolder;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.RuntimeFooterService;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.ApprovalAuditObserver;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistryBuilder;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultTransformService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import com.jimuqu.solon.claw.web.DashboardApprovalEventsService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
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
import java.util.Collections;
import java.util.List;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;

/** 承载工具配置并集中创建运行组件。 */
@Configuration
public class ToolConfiguration {
    /**
     * 执行注册表相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回注册表结果。
     */
    @Bean
    public ProcessRegistry processRegistry(AppConfig appConfig) {
        return new ProcessRegistry(appConfig);
    }

    /**
     * 执行安全策略服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回安全策略服务结果。
     */
    @Bean
    public SecurityPolicyService securityPolicyService(AppConfig appConfig) {
        return new SecurityPolicyService(appConfig);
    }

    /**
     * 执行tirith安全服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回tirith安全服务结果。
     */
    @Bean
    public TirithSecurityService tirithSecurityService(AppConfig appConfig) {
        return new TirithSecurityService(appConfig);
    }

    /**
     * 执行工具结果转换服务相关逻辑。
     *
     * @return 返回工具结果Transform服务结果。
     */
    @Bean
    public ToolResultTransformService toolResultTransformService() {
        return new ToolResultTransformService();
    }

    /**
     * 执行工具结果Storage服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回工具结果Storage服务结果。
     */
    @Bean
    public ToolResultStorageService toolResultStorageService(AppConfig appConfig) {
        return new ToolResultStorageService(appConfig);
    }

    /**
     * 执行工具Call循环防护服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回工具Call循环防护服务结果。
     */
    @Bean
    public ToolCallLoopGuardrailService toolCallLoopGuardrailService(AppConfig appConfig) {
        return new ToolCallLoopGuardrailService(appConfig);
    }

    /**
     * 执行dangerous命令审批服务相关逻辑。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param appConfig 应用运行配置。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param cronJobService 定时任务Job服务依赖。
     * @return 返回dangerous命令审批服务结果。
     */
    @Bean
    public DangerousCommandApprovalService dangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            ApprovalAuditRepository approvalAuditRepository,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            CronJobService cronJobService) {
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                        globalSettingRepository,
                        appConfig,
                        securityPolicyService,
                        tirithSecurityService);
        service.addApprovalObserver(new ApprovalAuditObserver(approvalAuditRepository));
        service.addApprovalObserver(new CronApprovalResumeObserver(cronJobService));
        return service;
    }

    /**
     * 执行附件缓存服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回附件缓存服务结果。
     */
    @Bean
    public AttachmentCacheService attachmentCacheService(AppConfig appConfig) {
        return new AttachmentCacheService(appConfig);
    }

    /**
     * 执行图片Generation服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param imageGenProviders 图片GenProviders标识或键值。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回图片Generation服务结果。
     */
    @Bean
    public ImageGenerationService imageGenerationService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<ImageGenProvider> imageGenProviders,
            SecurityPolicyService securityPolicyService) {
        return new ImageGenerationService(
                appConfig, attachmentCacheService, imageGenProviders, securityPolicyService);
    }

    /**
     * 执行语音服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param speechProviders 语音Providers标识或键值。
     * @param transcriptionProviders 转写Providers标识或键值。
     * @return 返回语音服务结果。
     */
    @Bean
    public SpeechService speechService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<SpeechProvider> speechProviders,
            List<TranscriptionProvider> transcriptionProviders) {
        return new SpeechService(
                appConfig, attachmentCacheService, speechProviders, transcriptionProviders);
    }

    /**
     * 执行MCP运行时服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sqliteDatabase SQLiteDatabase参数。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回MCP运行时服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public McpRuntimeService mcpRuntimeService(
            AppConfig appConfig,
            SqliteDatabase sqliteDatabase,
            SecurityPolicyService securityPolicyService) {
        McpRuntimeService service =
                new McpRuntimeService(appConfig, sqliteDatabase, null, securityPolicyService);
        service.startInitialDiscoveryAsync();
        return service;
    }

    /**
     * 执行浏览器运行时服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param browserProviders 浏览器Providers标识或键值。
     * @param securityPolicyService 安全策略服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param llmProviderService LLM 提供方解析服务。
     * @param llmGateway LLM 网关。
     * @return 返回浏览器运行时服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public BrowserRuntimeService browserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> browserProviders,
            SecurityPolicyService securityPolicyService,
            AttachmentCacheService attachmentCacheService,
            LlmProviderService llmProviderService,
            LlmGateway llmGateway) {
        VisionAnalysisService visionAnalysisService =
                new VisionAnalysisService(
                        appConfig,
                        attachmentCacheService,
                        securityPolicyService,
                        llmProviderService,
                        () -> llmGateway);
        return new BrowserRuntimeService(
                appConfig, browserProviders, securityPolicyService, visionAnalysisService::analyze);
    }

    /**
     * 执行运行时路径保护相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回运行时路径保护结果。
     */
    @Bean
    public RuntimePathGuard runtimePathGuard(AppConfig appConfig) {
        return new RuntimePathGuard(appConfig);
    }

    /**
     * 执行工具注册表相关逻辑。
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
     * @param dangerousCommandApprovalService 危险或外部操作审批服务依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param dashboardMcpService Dashboard MCP服务依赖。
     * @param dashboardCuratorService Dashboard技能维护服务依赖。
     * @param dashboardPlatformToolsetsService Dashboard平台工具集服务依赖。
     * @param dashboardProviderService Dashboard provider服务依赖。
     * @param dashboardStatusService Dashboard 状态服务依赖。
     * @param dashboardGatewayDoctorService Dashboard Doctor 服务依赖。
     * @param dashboardInsightsService Dashboard 洞察服务依赖。
     * @param dashboardApprovalEventsService Dashboard 审批事件服务依赖。
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
     * @param webSearchProviders Web 搜索附加提供方列表。
     * @return 返回工具注册表结果。
     */
    @Bean
    @Condition(onMissingBean = ProfileChildRuntimeMarker.class)
    public ToolRegistry toolRegistry(
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
            DangerousCommandApprovalService dangerousCommandApprovalService,
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
            List<WebSearchProvider> webSearchProviders) {
        return applyCommonToolRegistrySettings(
                        DefaultToolRegistry.builder(),
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
                        dangerousCommandApprovalService,
                        processRegistry,
                        mcpRuntimeService,
                        dashboardMcpService,
                        dashboardCuratorService)
                .dashboardPlatformToolsetsService(dashboardPlatformToolsetsService)
                .dashboardProviderService(dashboardProviderService)
                .dashboardStatusService(dashboardStatusService)
                .dashboardGatewayDoctorService(dashboardGatewayDoctorService)
                .dashboardInsightsService(dashboardInsightsService)
                .dashboardApprovalEventsService(dashboardApprovalEventsService)
                .dashboardWorkspaceService(dashboardWorkspaceService)
                .dashboardConfigService(dashboardConfigService)
                .dashboardRuntimeConfigService(dashboardRuntimeConfigService)
                .weixinQrSetupService(weixinQrSetupService)
                .domesticQrSetupService(domesticQrSetupService)
                .browserRuntimeService(browserRuntimeService)
                .imageGenerationService(imageGenerationService)
                .speechService(speechService)
                .dashboardRunService(dashboardRunService)
                .sqliteDatabase(sqliteDatabase)
                .agentRunRepository(agentRunRepository)
                .cronJobRepository(cronJobRepository)
                .usageEventRepository(usageEventRepository)
                .webSearchProviders(webSearchProviders)
                .build();
    }

    /**
     * 将公共的工具注册表 Builder 设置应用到传入的 builder 上，供主机级和 Profile 子运行时复用。
     */
    static DefaultToolRegistryBuilder applyCommonToolRegistrySettings(
            DefaultToolRegistryBuilder builder,
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
            DangerousCommandApprovalService dangerousCommandApprovalService,
            ProcessRegistry processRegistry,
            McpRuntimeService mcpRuntimeService,
            DashboardMcpService dashboardMcpService,
            DashboardCuratorService dashboardCuratorService) {
        return builder
                .appConfig(appConfig)
                .preferenceStore(preferenceStore)
                .sessionRepository(sessionRepository)
                .agentProfileService(agentProfileService)
                .cronJobService(cronJobService)
                .deliveryService(deliveryService)
                .memoryService(memoryService)
                .sessionSearchService(sessionSearchService)
                .localSkillService(localSkillService)
                .skillHubService(skillHubService)
                .checkpointService(checkpointService)
                .delegationService(delegationService)
                .attachmentCacheService(attachmentCacheService)
                .runtimeSettingsService(runtimeSettingsService)
                .gatewayRuntimeRefreshService(gatewayRuntimeRefreshService)
                .securityPolicyService(securityPolicyService)
                .approvalService(dangerousCommandApprovalService)
                .processRegistry(processRegistry)
                .mcpRuntimeService(mcpRuntimeService)
                .dashboardMcpService(dashboardMcpService)
                .dashboardCuratorService(dashboardCuratorService);
    }

    /**
     * 执行委托服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param holder holder 参数。
     * @param preferenceStore 本地偏好存储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @return 返回委托服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public DelegationService delegationService(
            AppConfig appConfig,
            ConversationOrchestratorHolder holder,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            AgentRunControlService agentRunControlService,
            DeliveryService deliveryService) {
        DefaultDelegationService service =
                new DefaultDelegationService(
                        holder,
                        preferenceStore,
                        sessionRepository,
                        agentRunRepository,
                        appConfig,
                        agentRunControlService,
                        deliveryService);
        service.reconcileStaleSubagents();
        return service;
    }

    /**
     * 执行展示设置服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @return 返回展示设置服务结果。
     */
    @Bean
    public DisplaySettingsService displaySettingsService(
            AppConfig appConfig, GlobalSettingRepository globalSettingRepository) {
        return new DisplaySettingsService(appConfig, globalSettingRepository);
    }

    /**
     * 执行运行时Footer服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回运行时Footer服务结果。
     */
    @Bean
    public RuntimeFooterService runtimeFooterService(AppConfig appConfig) {
        return new RuntimeFooterService(appConfig);
    }

    /**
     * 执行应用版本服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回app版本服务结果。
     */
    @Bean
    public AppVersionService appVersionService(AppConfig appConfig) {
        return new AppVersionService(appConfig);
    }

    /**
     * 执行应用更新服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param appVersionService 应用版本服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回app更新服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public AppUpdateService appUpdateService(
            AppConfig appConfig,
            AppVersionService appVersionService,
            SecurityPolicyService securityPolicyService) {
        return new AppUpdateService(appConfig, appVersionService, securityPolicyService);
    }

    /**
     * 执行大模型提供方服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param modelMetadataService 完整模型元数据解析服务。
     * @return 返回大模型提供方服务结果。
     */
    @Bean
    public LlmProviderService llmProviderService(
            AppConfig appConfig, ModelMetadataService modelMetadataService) {
        return new LlmProviderService(appConfig, modelMetadataService);
    }

    /**
     * 执行对话编排器Holder相关逻辑。
     *
     * @return 返回对话编排器Holder结果。
     */
    @Bean
    public ConversationOrchestratorHolder conversationOrchestratorHolder() {
        return new ConversationOrchestratorHolder();
    }

    /**
     * 执行大模型消息网关相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolResultTransformService 工具结果转换Service响应或执行结果。
     * @param toolCallLoopGuardrailService 工具CallLoop护栏服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回大模型消息网关结果。
     */
    @Bean
    public LlmGateway llmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            LlmProviderService llmProviderService,
            ToolResultTransformService toolResultTransformService,
            ToolCallLoopGuardrailService toolCallLoopGuardrailService,
            SecurityPolicyService securityPolicyService) {
        SolonAiLlmGateway gateway =
                new SolonAiLlmGateway(
                        appConfig,
                        sessionRepository,
                        dangerousCommandApprovalService,
                        llmProviderService,
                        toolResultTransformService,
                        toolCallLoopGuardrailService,
                        securityPolicyService);
        return gateway;
    }

    /**
     * 执行Agent运行Supervisor相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param contextBudgetService 上下文预算Service上下文。
     * @param llmGateway LLM网关参数。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param usageEventRepository 用量事件仓储依赖。
     * @param usageCostCalculator 用量成本Calculator参数。
     * @return 返回Agent运行Supervisor结果。
     */
    @Bean(destroyMethod = "shutdown")
    public AgentRunSupervisor agentRunSupervisor(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            ContextCompressionService contextCompressionService,
            ContextBudgetService contextBudgetService,
            LlmGateway llmGateway,
            LlmProviderService llmProviderService,
            UsageEventRepository usageEventRepository,
            UsageCostCalculator usageCostCalculator) {
        return new AgentRunSupervisor(
                appConfig,
                sessionRepository,
                agentRunRepository,
                contextCompressionService,
                contextBudgetService,
                llmGateway,
                llmProviderService,
                usageEventRepository,
                usageCostCalculator);
    }

    /**
     * 执行对话编排器相关逻辑。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param llmGateway LLM网关参数。
     * @param toolRegistry 工具注册表依赖组件。
     * @param deliveryService 投递服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param holder holder 参数。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param runtimeFooterService 运行时Footer服务依赖。
     * @param agentRuntimeService Agent运行时服务依赖。
     * @param memoryManager 记忆Manager参数。
     * @param goalService 目标服务依赖。
     * @param speechService 语音服务依赖。
     * @return 返回对话编排器结果。
     */
    @Bean
    public ConversationOrchestrator conversationOrchestrator(
            SessionRepository sessionRepository,
            FileContextService contextService,
            ContextCompressionService contextCompressionService,
            LlmGateway llmGateway,
            ToolRegistry toolRegistry,
            DeliveryService deliveryService,
            DisplaySettingsService displaySettingsService,
            ConversationOrchestratorHolder holder,
            RuntimeSettingsService runtimeSettingsService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunSupervisor agentRunSupervisor,
            RuntimeFooterService runtimeFooterService,
            AgentRuntimeService agentRuntimeService,
            MemoryManager memoryManager,
            GoalService goalService,
            SpeechService speechService) {
        DefaultConversationOrchestrator orchestrator =
                new DefaultConversationOrchestrator(
                        sessionRepository,
                        contextService,
                        contextCompressionService,
                        llmGateway,
                        toolRegistry,
                        deliveryService,
                        displaySettingsService,
                        runtimeSettingsService,
                        dangerousCommandApprovalService,
                        agentRunSupervisor,
                        runtimeFooterService,
                        agentRuntimeService,
                        memoryManager,
                        goalService,
                        speechService);
        holder.set(orchestrator);
        return orchestrator;
    }
}
