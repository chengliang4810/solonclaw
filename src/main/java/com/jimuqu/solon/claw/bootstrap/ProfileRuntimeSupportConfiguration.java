package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.MemoryArchiveService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SessionSearchService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.pricing.PriceCatalog;
import com.jimuqu.solon.claw.profile.ProfileChildRuntimeMarker;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import com.jimuqu.solon.claw.provider.WebSearchProvider;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardProfileScope;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
import java.util.List;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;

/** 为命名 Profile 子容器提供 Agent 与网关需要的最小 Dashboard 支撑，不装配 HTTP 或主机级服务。 */
@Configuration
@Condition(onBean = ProfileChildRuntimeMarker.class)
public class ProfileRuntimeSupportConfiguration {
    /** 创建当前 Profile 的配置管理支撑，不启用跨 Profile 请求路由。 */
    @Bean
    public DashboardConfigService dashboardConfigService(
            AppConfig appConfig, GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        return new DashboardConfigService(appConfig, gatewayRuntimeRefreshService);
    }

    /** 创建当前 Profile 的工作区配置支撑，不读取主机 Dashboard 的 Profile 选择。 */
    @Bean
    public DashboardRuntimeConfigService dashboardRuntimeConfigService(
            AppConfig appConfig, GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        return new DashboardRuntimeConfigService(appConfig, gatewayRuntimeRefreshService);
    }

    /** 创建当前 Profile 的模型提供方管理支撑。 */
    @Bean
    public DashboardProviderService dashboardProviderService(
            AppConfig appConfig,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService,
            SecurityPolicyService securityPolicyService,
            PriceCatalog priceCatalog) {
        return new DashboardProviderService(
                appConfig,
                gatewayRuntimeRefreshService,
                llmProviderService,
                securityPolicyService,
                null,
                null,
                priceCatalog);
    }

    /** 创建当前 Profile 的 MCP 管理支撑，并复用子容器自己的 MCP 运行时。 */
    @Bean(destroyMethod = "shutdown")
    public DashboardMcpService dashboardMcpService(
            AppConfig appConfig,
            SqliteDatabase sqliteDatabase,
            McpRuntimeService mcpRuntimeService,
            SecurityPolicyService securityPolicyService) {
        return new DashboardMcpService(
                appConfig,
                sqliteDatabase,
                new McpPackageSecurityService(
                        new com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient(
                                securityPolicyService),
                        securityPolicyService),
                mcpRuntimeService,
                profileScope(appConfig));
    }

    /** 创建当前 Profile 的技能管理支撑。 */
    @Bean
    public DashboardSkillsService dashboardSkillsService(
            LocalSkillService localSkillService,
            SqlitePreferenceStore sqlitePreferenceStore,
            AppConfig appConfig,
            SkillHubService skillHubService) {
        return new DashboardSkillsService(
                localSkillService, sqlitePreferenceStore, profileScope(appConfig), skillHubService);
    }

    /** 创建当前 Profile 的技能维护支撑。 */
    @Bean
    public DashboardCuratorService dashboardCuratorService(
            SkillCuratorService skillCuratorService, SqliteDatabase sqliteDatabase) {
        return new DashboardCuratorService(skillCuratorService, sqliteDatabase);
    }

    /** 创建当前 Profile 的会话产物支撑。 */
    @Bean
    public SessionArtifactService sessionArtifactService(AppConfig appConfig) {
        return new SessionArtifactService(appConfig);
    }

    /** 创建绑定当前命名 Profile 工作区与归档服务的 Agent 工具支撑。 */
    @Bean
    public DashboardWorkspaceService dashboardWorkspaceService(
            AppConfig appConfig,
            PersonaWorkspaceService personaWorkspaceService,
            MemoryArchiveService memoryArchiveService) {
        return new DashboardWorkspaceService(
                personaWorkspaceService, profileScope(appConfig), memoryArchiveService);
    }

    /** 创建绑定当前子运行时身份与配置的解析器，不向 Bean 容器暴露主机级 ProfileManager。 */
    private DashboardProfileScope profileScope(AppConfig appConfig) {
        return new DashboardProfileScope(
                ProfileManager.current(), ProfileRuntimeIdentity.resolve(appConfig), appConfig);
    }

    /** 创建命名 Profile 的工具注册表；主机状态、二维码 setup 和 Dashboard 诊断工具保留稳定的 unavailable 返回，但不创建对应后台服务。 */
    @Bean
    public ToolRegistry toolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
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
            DashboardProviderService dashboardProviderService,
            DashboardConfigService dashboardConfigService,
            DashboardRuntimeConfigService dashboardRuntimeConfigService,
            DashboardWorkspaceService dashboardWorkspaceService,
            BrowserRuntimeService browserRuntimeService,
            ImageGenerationService imageGenerationService,
            SpeechService speechService,
            SqliteDatabase sqliteDatabase,
            AgentRunRepository agentRunRepository,
            CronJobRepository cronJobRepository,
            UsageEventRepository usageEventRepository,
            List<WebSearchProvider> webSearchProviders) {
        return ToolConfiguration.applyCommonToolRegistrySettings(
                        DefaultToolRegistry.builder(),
                        appConfig,
                        preferenceStore,
                        sessionRepository,
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
                .dashboardProviderService(dashboardProviderService)
                .dashboardConfigService(dashboardConfigService)
                .dashboardRuntimeConfigService(dashboardRuntimeConfigService)
                .dashboardWorkspaceService(dashboardWorkspaceService)
                .browserRuntimeService(browserRuntimeService)
                .imageGenerationService(imageGenerationService)
                .speechService(speechService)
                .sqliteDatabase(sqliteDatabase)
                .agentRunRepository(agentRunRepository)
                .cronJobRepository(cronJobRepository)
                .usageEventRepository(usageEventRepository)
                .webSearchProviders(webSearchProviders)
                .build();
    }
}
