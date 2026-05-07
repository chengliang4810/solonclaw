package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.kanban.ConversationKanbanWorkerSpawner;
import com.jimuqu.solon.claw.kanban.KanbanDispatcherService;
import com.jimuqu.solon.claw.kanban.KanbanRepository;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.kanban.KanbanWorkerSpawner;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.web.DashboardAgentService;
import com.jimuqu.solon.claw.web.DashboardAnalyticsService;
import com.jimuqu.solon.claw.web.DashboardAuthFilter;
import com.jimuqu.solon.claw.web.DashboardAuthService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCronService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import com.jimuqu.solon.claw.web.DashboardKanbanService;
import com.jimuqu.solon.claw.web.DashboardLogsService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardMediaService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRunService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import com.jimuqu.solon.claw.web.DashboardWorkspaceService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.core.handle.Filter;

/** dashboard bean configuration. */
@Configuration
public class DashboardConfiguration {
    @Bean
    public DashboardAuthService dashboardAuthService(AppConfig appConfig) {
        return new DashboardAuthService(appConfig);
    }

    @Bean
    public Filter dashboardAuthFilter(DashboardAuthService dashboardAuthService) {
        return new DashboardAuthFilter(dashboardAuthService);
    }

    @Bean
    public DashboardStatusService dashboardStatusService(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DeliveryService deliveryService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            AppVersionService appVersionService,
            AppUpdateService appUpdateService,
            LlmProviderService llmProviderService) {
        return new DashboardStatusService(
                appConfig,
                sessionRepository,
                deliveryService,
                gatewayRuntimeRefreshService,
                appVersionService,
                appUpdateService,
                llmProviderService);
    }

    @Bean
    public SessionArtifactService sessionArtifactService(AppConfig appConfig) {
        return new SessionArtifactService(appConfig);
    }

    @Bean
    public DashboardSessionService dashboardSessionService(
            SessionRepository sessionRepository,
            CheckpointService checkpointService,
            SessionArtifactService sessionArtifactService) {
        return new DashboardSessionService(
                sessionRepository, checkpointService, sessionArtifactService);
    }

    @Bean
    public DashboardRunService dashboardRunService(
            AgentRunRepository agentRunRepository,
            AgentRunControlService agentRunControlService,
            com.jimuqu.solon.claw.core.service.DelegationService delegationService) {
        return new DashboardRunService(agentRunRepository, agentRunControlService, delegationService);
    }

    @Bean
    public DashboardAgentService dashboardAgentService(
            AgentProfileService agentProfileService,
            AgentRuntimeService agentRuntimeService,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository) {
        return new DashboardAgentService(
                agentProfileService, agentRuntimeService, sessionRepository, agentRunRepository);
    }

    @Bean
    public DashboardDiagnosticsService dashboardDiagnosticsService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            LlmProviderService llmProviderService,
            ToolRegistry toolRegistry) {
        return new DashboardDiagnosticsService(
                appConfig, deliveryService, llmProviderService, toolRegistry);
    }

    @Bean
    public DashboardAnalyticsService dashboardAnalyticsService(
            SessionRepository sessionRepository) {
        return new DashboardAnalyticsService(sessionRepository);
    }

    @Bean
    public DashboardLogsService dashboardLogsService(AppConfig appConfig) {
        return new DashboardLogsService(appConfig);
    }

    @Bean
    public DashboardConfigService dashboardConfigService(
            AppConfig appConfig, GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        return new DashboardConfigService(appConfig, gatewayRuntimeRefreshService);
    }

    @Bean
    public DashboardProviderService dashboardProviderService(
            AppConfig appConfig,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService,
            LlmProviderService llmProviderService) {
        return new DashboardProviderService(
                appConfig, gatewayRuntimeRefreshService, llmProviderService);
    }

    @Bean
    public DashboardWorkspaceService dashboardWorkspaceService(
            PersonaWorkspaceService personaWorkspaceService) {
        return new DashboardWorkspaceService(personaWorkspaceService);
    }

    @Bean
    public DashboardRuntimeConfigService dashboardRuntimeConfigService(
            AppConfig appConfig, GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        return new DashboardRuntimeConfigService(appConfig, gatewayRuntimeRefreshService);
    }

    @Bean
    public DashboardGatewayDoctorService dashboardGatewayDoctorService(
            AppConfig appConfig,
            DeliveryService deliveryService,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        return new DashboardGatewayDoctorService(
                appConfig, deliveryService, gatewayRuntimeRefreshService);
    }

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

    @Bean
    public DashboardSkillsService dashboardSkillsService(
            LocalSkillService localSkillService, SqlitePreferenceStore sqlitePreferenceStore) {
        return new DashboardSkillsService(localSkillService, sqlitePreferenceStore);
    }

    @Bean
    public DashboardCronService dashboardCronService(
            CronJobService cronJobService, DefaultCronScheduler defaultCronScheduler) {
        return new DashboardCronService(cronJobService, defaultCronScheduler);
    }

    @Bean
    public KanbanService kanbanService(
            KanbanRepository kanbanRepository,
            AppConfig appConfig,
            AgentProfileService agentProfileService) {
        return new KanbanService(kanbanRepository, appConfig, agentProfileService);
    }

    @Bean
    public KanbanWorkerSpawner kanbanWorkerSpawner(
            ConversationOrchestrator conversationOrchestrator, KanbanService kanbanService) {
        return new ConversationKanbanWorkerSpawner(conversationOrchestrator, kanbanService);
    }

    @Bean(destroyMethod = "shutdown")
    public KanbanDispatcherService kanbanDispatcherService(
            KanbanRepository kanbanRepository,
            KanbanService kanbanService,
            KanbanWorkerSpawner kanbanWorkerSpawner) {
        KanbanDispatcherService dispatcherService =
                new KanbanDispatcherService(kanbanRepository, kanbanService, kanbanWorkerSpawner);
        kanbanService.setDispatcherService(dispatcherService);
        return dispatcherService;
    }

    @Bean
    public DashboardKanbanService dashboardKanbanService(KanbanService kanbanService) {
        return new DashboardKanbanService(kanbanService);
    }

    @Bean
    public DashboardMcpService dashboardMcpService(
            AppConfig appConfig,
            SqliteDatabase sqliteDatabase,
            McpRuntimeService mcpRuntimeService,
            com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService securityPolicyService) {
        return new DashboardMcpService(
                appConfig,
                sqliteDatabase,
                new McpPackageSecurityService(
                        new com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient(
                                securityPolicyService),
                        securityPolicyService),
                mcpRuntimeService);
    }

    @Bean
    public DashboardCuratorService dashboardCuratorService(
            SkillCuratorService skillCuratorService, SqliteDatabase sqliteDatabase) {
        return new DashboardCuratorService(skillCuratorService, sqliteDatabase);
    }

    @Bean
    public DashboardMediaService dashboardMediaService(
            SqliteDatabase sqliteDatabase, RuntimePathGuard runtimePathGuard) {
        return new DashboardMediaService(sqliteDatabase, runtimePathGuard);
    }
}
