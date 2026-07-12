package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.context.SkillUsageTracker;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.plugin.AgentPluginManager;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import com.jimuqu.solon.claw.web.DashboardAnalyticsService;
import com.jimuqu.solon.claw.web.DashboardApprovalEventsService;
import com.jimuqu.solon.claw.web.DashboardAuthFilter;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCronService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import com.jimuqu.solon.claw.web.DashboardInsightsService;
import com.jimuqu.solon.claw.web.DashboardLogsService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardMediaService;
import com.jimuqu.solon.claw.web.DashboardPairingService;
import com.jimuqu.solon.claw.web.DashboardPlatformToolsetsService;
import com.jimuqu.solon.claw.web.DashboardPluginStatusService;
import com.jimuqu.solon.claw.web.DashboardProfileService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.util.List;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.core.handle.Filter;

/** 承载控制台配置并集中创建运行组件。 */
@Configuration
public class DashboardConfiguration {
    /**
     * 创建机器级 Profile 管理器，供 Dashboard 和后续 Profile 作用域服务复用。
     *
     * @return 基于当前启动工作区的 Profile 管理器。
     */
    @Bean
    public ProfileManager profileManager() {
        return ProfileManager.current();
    }

    /**
     * 创建 Dashboard Profile 管理服务。
     *
     * @param profileManager Profile 核心管理器。
     * @param dashboardMcpService 跨 Profile MCP 配置服务。
     * @param dashboardSkillsService 跨 Profile 技能服务。
     * @return Dashboard Profile 服务。
     */
    @Bean
    public DashboardProfileService dashboardProfileService(
            ProfileManager profileManager,
            DashboardMcpService dashboardMcpService,
            DashboardSkillsService dashboardSkillsService,
            ProfileMultiplexRuntimeManager profileMultiplexRuntimeManager) {
        return new DashboardProfileService(
                profileManager,
                dashboardMcpService,
                dashboardSkillsService,
                profileMultiplexRuntimeManager);
    }

    /**
     * 创建不修改 JVM 全局状态的 Dashboard Profile 请求上下文。
     *
     * @param profileManager 机器级 Profile 管理器。
     * @param appConfig 当前 Dashboard JVM 配置。
     * @return Profile 请求上下文。
     */
    @Bean
    public DashboardProfileContext dashboardProfileContext(
            ProfileManager profileManager, AppConfig appConfig) {
        return new DashboardProfileContext(profileManager, appConfig);
    }

    /**
     * 执行控制台认证服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回控制台认证服务结果。
     */
    @Bean
    public DashboardAuthService dashboardAuthService(AppConfig appConfig) {
        return new DashboardAuthService(appConfig);
    }

    /**
     * 执行控制台认证过滤器相关逻辑。
     *
     * @param dashboardAuthService dashboard鉴权服务依赖。
     * @return 返回控制台认证Filter结果。
     */
    @Bean
    public Filter dashboardAuthFilter(DashboardAuthService dashboardAuthService) {
        return new DashboardAuthFilter(dashboardAuthService);
    }

    /**
     * 创建已认证 Dashboard 的 pairing 管理服务。
     *
     * @param gatewayAuthorizationService 当前 Profile 授权服务。
     * @param dashboardProfileContext Dashboard Profile 请求上下文。
     * @return pairing 管理服务。
     */
    @Bean
    public DashboardPairingService dashboardPairingService(
            GatewayAuthorizationService gatewayAuthorizationService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardPairingService(gatewayAuthorizationService, dashboardProfileContext);
    }

    /**
     * 执行控制台状态服务相关逻辑。
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
     * @param speechService 语音运行时服务依赖。
     * @param imageGenProviders 图像生成 Provider 列表。
     * @param dashboardProfileContext Dashboard Profile 请求上下文。
     * @return 返回控制台状态服务结果。
     */
    @Bean
    public DashboardStatusService dashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            AgentRunControlService agentRunControlService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService,
            ProactiveDiagnosticsService proactiveDiagnosticsService,
            SpeechService speechService,
            List<ImageGenProvider> imageGenProviders,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardStatusService(
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
                dashboardProfileContext);
    }

    /**
     * 执行主动协作诊断服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param proactiveRepository 主动协作仓储依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @return 返回主动协作诊断服务结果。
     */
    @Bean
    public ProactiveDiagnosticsService proactiveDiagnosticsService(
            AppConfig appConfig,
            ProactiveRepository proactiveRepository,
            com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository gatewayPolicyRepository) {
        return new ProactiveDiagnosticsService(
                appConfig, proactiveRepository, gatewayPolicyRepository);
    }

    /**
     * 执行会话Artifact服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回会话Artifact服务结果。
     */
    @Bean
    public SessionArtifactService sessionArtifactService(AppConfig appConfig) {
        return new SessionArtifactService(appConfig);
    }

    /**
     * 执行控制台会话服务相关逻辑。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @return 返回控制台会话服务结果。
     */
    @Bean
    public DashboardSessionService dashboardSessionService(
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            CheckpointService checkpointService,
            SessionArtifactService sessionArtifactService) {
        return new DashboardSessionService(
                sessionRepository, checkpointService, sessionArtifactService, agentRunRepository);
    }

    /**
     * 执行控制台运行服务相关逻辑。
     *
     * @param agentRunRepository Agent运行仓储依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param delegationService delegation服务依赖。
     * @return 返回控制台运行服务结果。
     */
    @Bean
    public DashboardRunService dashboardRunService(
            AgentRunRepository agentRunRepository,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.core.service.DelegationService delegationService) {
        return new DashboardRunService(
                agentRunRepository, agentRunControlService, delegationService);
    }

    /**
     * 执行控制台插件状态服务相关逻辑。
     *
     * @param pluginManager 插件生命周期管理器。
     * @return 返回控制台插件状态服务。
     */
    @Bean
    public DashboardPluginStatusService dashboardPluginStatusService(
            AgentPluginManager pluginManager) {
        return new DashboardPluginStatusService(pluginManager);
    }

    /**
     * 执行控制台诊断服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param toolRegistry 工具注册表依赖组件。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param approvalAuditRepository 审批Audit仓储依赖。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param commandService 命令服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @param tirithSecurityService 待校验或访问的地址参数。
     * @param toolResultStorageService 工具结果StorageService响应或执行结果。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @param runtimeMemoryMonitorService 运行时记忆Monitor服务依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param dashboardProfileContext Dashboard Profile 请求上下文。
     * @param proactiveDiagnosticsService 主动协作诊断服务依赖。
     * @return 返回控制台诊断服务结果。
     */
    @Bean
    public DashboardDiagnosticsService dashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry,
            SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator,
            ApprovalAuditRepository approvalAuditRepository,
            SlashConfirmService slashConfirmService,
            CommandService commandService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService,
            ToolResultStorageService toolResultStorageService,
            ShutdownForensicsService shutdownForensicsService,
            RuntimeMemoryMonitorService runtimeMemoryMonitorService,
            AgentRunRepository agentRunRepository,
            ProcessRegistry processRegistry,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            ProactiveDiagnosticsService proactiveDiagnosticsService) {
        return new DashboardDiagnosticsService(
                appConfig,
                deliveryService,
                llmProviderService,
                toolRegistry,
                sessionRepository,
                conversationOrchestrator,
                approvalAuditRepository,
                slashConfirmService,
                commandService,
                dangerousCommandApprovalService,
                securityPolicyService,
                tirithSecurityService,
                toolResultStorageService,
                shutdownForensicsService,
                runtimeMemoryMonitorService,
                agentRunRepository,
                processRegistry,
                gatewayRuntimeRefreshService,
                proactiveDiagnosticsService);
    }

    /**
     * 执行控制台分析服务相关逻辑。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param usageEventRepository 用量事件仓储依赖。
     * @return 返回控制台分析服务结果。
     */
    @Bean
    public DashboardAnalyticsService dashboardAnalyticsService(
            SessionRepository sessionRepository, UsageEventRepository usageEventRepository) {
        return new DashboardAnalyticsService(sessionRepository, usageEventRepository);
    }

    /**
     * 执行控制台Logs服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunRepository Agent 运行仓储依赖。
     * @param cronJobRepository 定时任务仓储依赖。
     * @return 返回控制台Logs服务结果。
     */
    @Bean
    public DashboardLogsService dashboardLogsService(
            AppConfig appConfig,
            AgentRunRepository agentRunRepository,
            CronJobRepository cronJobRepository) {
        return new DashboardLogsService(appConfig, agentRunRepository, cronJobRepository);
    }

    /**
     * 执行控制台配置服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @return 返回控制台配置服务结果。
     */
    @Bean
    public DashboardConfigService dashboardConfigService(
            AppConfig appConfig,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardConfigService(
                appConfig, gatewayRuntimeRefreshService, dashboardProfileContext);
    }

    /**
     * 执行控制台提供方服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param modelMetadataService 模型元数据服务依赖。
     * @param dashboardProfileContext Dashboard Profile 请求上下文。
     * @return 返回控制台提供方服务结果。
     */
    @Bean
    public DashboardProviderService dashboardProviderService(
            AppConfig appConfig,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService,
            ModelMetadataService modelMetadataService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardProviderService(
                appConfig,
                gatewayRuntimeRefreshService,
                llmProviderService,
                null,
                modelMetadataService,
                dashboardProfileContext);
    }

    /**
     * 创建模型上下文长度在线目录服务，负责 models.dev 和 OpenRouter 数据获取与缓存。
     *
     * @param appConfig 应用运行配置。
     * @return 返回模型上下文目录服务。
     */
    @Bean
    public com.jimuqu.solon.claw.support.ModelContextCatalogService modelContextCatalogService(
            AppConfig appConfig) {
        return new com.jimuqu.solon.claw.support.ModelContextCatalogService(appConfig);
    }

    /**
     * 创建模型上下文长度持久化缓存，存储在线探测得到的 model@baseUrl 条目。
     *
     * @param appConfig 应用运行配置。
     * @return 返回模型上下文缓存存储。
     */
    @Bean
    public com.jimuqu.solon.claw.support.ModelContextCacheStore modelContextCacheStore(
            AppConfig appConfig) {
        java.io.File cacheDir =
                appConfig.getRuntime() != null
                                && cn.hutool.core.util.StrUtil.isNotBlank(
                                        appConfig.getRuntime().getCacheDir())
                        ? new java.io.File(appConfig.getRuntime().getCacheDir())
                        : null;
        return new com.jimuqu.solon.claw.support.ModelContextCacheStore(cacheDir);
    }

    /**
     * 创建模型元数据解析服务，注入在线目录和持久化缓存。
     *
     * @param appConfig 应用运行配置。
     * @param modelContextCatalogService 模型上下文在线目录服务。
     * @param modelContextCacheStore 模型上下文持久化缓存。
     * @return 返回模型元数据服务。
     */
    @Bean
    public com.jimuqu.solon.claw.support.ModelMetadataService modelMetadataService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.support.ModelContextCatalogService modelContextCatalogService,
            com.jimuqu.solon.claw.support.ModelContextCacheStore modelContextCacheStore) {
        return new com.jimuqu.solon.claw.support.ModelMetadataService(
                appConfig, modelContextCatalogService, modelContextCacheStore);
    }

    /**
     * 执行控制台工作区服务相关逻辑。
     *
     * @param personaWorkspaceService persona工作区服务依赖。
     * @return 返回控制台工作区服务结果。
     */
    @Bean
    public DashboardWorkspaceService dashboardWorkspaceService(
            PersonaWorkspaceService personaWorkspaceService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardWorkspaceService(
                personaWorkspaceService,
                new com.jimuqu.solon.claw.web.DashboardProfileScope(dashboardProfileContext));
    }

    /**
     * 执行控制台工作区配置服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param dashboardProfileContext Dashboard Profile 请求上下文。
     * @return 返回控制台工作区配置服务结果。
     */
    @Bean
    public DashboardRuntimeConfigService dashboardRuntimeConfigService(
            AppConfig appConfig,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardRuntimeConfigService(
                appConfig, gatewayRuntimeRefreshService, dashboardProfileContext);
    }

    /**
     * 执行控制台平台Toolsets服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param dashboardConfigService dashboard配置Service配置对象。
     * @param dashboardProfileContext Dashboard Profile 请求上下文。
     * @return 返回控制台平台Toolsets服务结果。
     */
    @Bean
    public DashboardPlatformToolsetsService dashboardPlatformToolsetsService(
            AppConfig appConfig,
            DashboardConfigService dashboardConfigService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardPlatformToolsetsService(
                appConfig, dashboardConfigService, dashboardProfileContext);
    }

    /**
     * 执行控制台消息网关诊断服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param shutdownForensicsService 关闭Forensics服务依赖。
     * @return 返回控制台消息网关诊断服务结果。
     */
    @Bean
    public DashboardGatewayDoctorService dashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            ShutdownForensicsService shutdownForensicsService) {
        return new DashboardGatewayDoctorService(
                appConfig,
                deliveryService,
                llmProviderService,
                gatewayRuntimeRefreshService,
                shutdownForensicsService);
    }

    /**
     * 执行微信二维码配置引导服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param dashboardConfigService dashboard配置Service配置对象。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回微信二维码配置引导服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public WeixinQrSetupService weixinQrSetupService(
            AppConfig appConfig,
            DashboardConfigService dashboardConfigService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService securityPolicyService) {
        return new WeixinQrSetupService(
                appConfig,
                dashboardConfigService,
                gatewayRuntimeRefreshService,
                securityPolicyService);
    }

    /**
     * 执行国内二维码配置引导服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param dashboardConfigService dashboard配置Service配置对象。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回国内二维码配置引导服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public com.jimuqu.solon.claw.web.DomesticQrSetupService domesticQrSetupService(
            AppConfig appConfig,
            DashboardConfigService dashboardConfigService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService securityPolicyService,
            DashboardProfileContext dashboardProfileContext,
            ProfileManager profileManager) {
        return new com.jimuqu.solon.claw.web.DomesticQrSetupService(
                appConfig,
                dashboardConfigService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                dashboardProfileContext,
                profileManager);
    }

    /**
     * 执行控制台技能服务相关逻辑。
     *
     * @param localSkillService 本地技能服务依赖。
     * @param sqlitePreferenceStore SQLitePreferenceStore参数。
     * @return 返回控制台技能服务结果。
     */
    @Bean
    public DashboardSkillsService dashboardSkillsService(
            LocalSkillService localSkillService,
            SqlitePreferenceStore sqlitePreferenceStore,
            DashboardProfileContext dashboardProfileContext,
            SkillHubService skillHubService) {
        return new DashboardSkillsService(
                localSkillService,
                sqlitePreferenceStore,
                new com.jimuqu.solon.claw.web.DashboardProfileScope(dashboardProfileContext),
                skillHubService);
    }

    /**
     * 执行控制台定时任务服务相关逻辑。
     *
     * @param cronJobService 定时任务Job服务依赖。
     * @param defaultCronScheduler 默认定时任务调度器参数。
     * @return 返回控制台定时任务服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public DashboardCronService dashboardCronService(
            CronJobService cronJobService,
            DefaultCronScheduler defaultCronScheduler,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardCronService(
                cronJobService,
                defaultCronScheduler,
                new com.jimuqu.solon.claw.web.DashboardProfileScope(dashboardProfileContext));
    }

    /**
     * 执行控制台MCP服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sqliteDatabase SQLiteDatabase参数。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回控制台MCP服务结果。
     */
    @Bean(destroyMethod = "shutdown")
    public DashboardMcpService dashboardMcpService(
            AppConfig appConfig,
            SqliteDatabase sqliteDatabase,
            McpRuntimeService mcpRuntimeService,
            com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService securityPolicyService,
            DashboardProfileContext dashboardProfileContext) {
        return new DashboardMcpService(
                appConfig,
                sqliteDatabase,
                new McpPackageSecurityService(
                        new com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient(
                                securityPolicyService),
                        securityPolicyService),
                mcpRuntimeService,
                new com.jimuqu.solon.claw.web.DashboardProfileScope(dashboardProfileContext));
    }

    /**
     * 执行控制台技能维护服务相关逻辑。
     *
     * @param skillCuratorService 技能Curator服务依赖。
     * @param sqliteDatabase SQLiteDatabase参数。
     * @return 返回控制台技能维护服务结果。
     */
    @Bean
    public DashboardCuratorService dashboardCuratorService(
            SkillCuratorService skillCuratorService, SqliteDatabase sqliteDatabase) {
        return new DashboardCuratorService(skillCuratorService, sqliteDatabase);
    }

    /**
     * 执行控制台媒体服务相关逻辑。
     *
     * @param sqliteDatabase SQLiteDatabase参数。
     * @param runtimePathGuard 文件或目录路径参数。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @return 返回控制台媒体服务结果。
     */
    @Bean
    public DashboardMediaService dashboardMediaService(
            SqliteDatabase sqliteDatabase,
            RuntimePathGuard runtimePathGuard,
            AttachmentCacheService attachmentCacheService) {
        return new DashboardMediaService(sqliteDatabase, runtimePathGuard, attachmentCacheService);
    }

    /**
     * 执行技能用量Tracker相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回技能用量Tracker结果。
     */
    @Bean
    public SkillUsageTracker skillUsageTracker(AppConfig appConfig) {
        return new SkillUsageTracker(appConfig);
    }

    /**
     * 执行控制台洞察服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param skillUsageTracker 技能用量Tracker参数。
     * @param sessionRepository 会话仓储依赖。
     * @return 返回控制台洞察服务结果。
     */
    @Bean
    public DashboardInsightsService dashboardInsightsService(
            AppConfig appConfig,
            SkillUsageTracker skillUsageTracker,
            SessionRepository sessionRepository) {
        return new DashboardInsightsService(appConfig, skillUsageTracker, sessionRepository);
    }

    /**
     * 执行控制台审批Events服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回控制台审批Events服务结果。
     */
    @Bean
    public DashboardApprovalEventsService dashboardApprovalEventsService(AppConfig appConfig) {
        return new DashboardApprovalEventsService(appConfig);
    }
}
