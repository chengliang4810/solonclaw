package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
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
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.llm.SolonAiLlmGateway;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import com.jimuqu.solon.claw.plugin.AgentHookRegistry;
import com.jimuqu.solon.claw.plugin.HookBridgeInterceptor;
import com.jimuqu.solon.claw.plugin.ToolRegistration;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.ConversationOrchestratorHolder;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeFooterService;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.ApprovalAuditObserver;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolCallLoopGuardrailService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultTransformService;
import com.jimuqu.solon.claw.usage.UsageEventRepository;
import java.util.List;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** tool bean configuration. */
@Configuration
public class ToolConfiguration {
    @Bean
    public ProcessRegistry processRegistry(AppConfig appConfig) {
        return new ProcessRegistry(appConfig);
    }

    @Bean
    public SecurityPolicyService securityPolicyService(AppConfig appConfig) {
        return new SecurityPolicyService(appConfig);
    }

    @Bean
    public TirithSecurityService tirithSecurityService(AppConfig appConfig) {
        return new TirithSecurityService(appConfig);
    }

    @Bean
    public ToolResultTransformService toolResultTransformService() {
        return new ToolResultTransformService();
    }

    @Bean
    public ToolResultStorageService toolResultStorageService(AppConfig appConfig) {
        return new ToolResultStorageService(appConfig);
    }

    @Bean
    public ToolCallLoopGuardrailService toolCallLoopGuardrailService(AppConfig appConfig) {
        return new ToolCallLoopGuardrailService(appConfig);
    }

    @Bean
    public DangerousCommandApprovalService dangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            ApprovalAuditRepository approvalAuditRepository,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService) {
        DangerousCommandApprovalService service =
                new DangerousCommandApprovalService(
                globalSettingRepository, appConfig, securityPolicyService, tirithSecurityService);
        service.addApprovalObserver(new ApprovalAuditObserver(approvalAuditRepository));
        return service;
    }

    @Bean
    public AttachmentCacheService attachmentCacheService(AppConfig appConfig) {
        return new AttachmentCacheService(appConfig);
    }

    @Bean
    public ImageGenerationService imageGenerationService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<ImageGenProvider> imageGenProviders,
            SecurityPolicyService securityPolicyService) {
        return new ImageGenerationService(
                appConfig, attachmentCacheService, imageGenProviders, securityPolicyService);
    }

    @Bean
    public SpeechService speechService(
            AppConfig appConfig,
            AttachmentCacheService attachmentCacheService,
            List<SpeechProvider> speechProviders,
            List<TranscriptionProvider> transcriptionProviders) {
        return new SpeechService(
                appConfig, attachmentCacheService, speechProviders, transcriptionProviders);
    }

    @Bean(destroyMethod = "shutdown")
    public McpRuntimeService mcpRuntimeService(
            AppConfig appConfig,
            SqliteDatabase sqliteDatabase,
            SecurityPolicyService securityPolicyService) {
        return new McpRuntimeService(appConfig, sqliteDatabase, null, securityPolicyService);
    }

    @Bean(destroyMethod = "shutdown")
    public BrowserRuntimeService browserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> browserProviders,
            SecurityPolicyService securityPolicyService) {
        return new BrowserRuntimeService(appConfig, browserProviders, securityPolicyService);
    }

    @Bean
    public RuntimePathGuard runtimePathGuard(AppConfig appConfig) {
        return new RuntimePathGuard(appConfig);
    }

    @Bean
    public ToolRegistry toolRegistry(
            AppConfig appConfig,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentProfileService agentProfileService,
            CronJobService cronJobService,
            KanbanService kanbanService,
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
            List<ToolRegistration> pluginTools) {
        return new DefaultToolRegistry(
                appConfig,
                preferenceStore,
                sessionRepository,
                agentProfileService,
                cronJobService,
                kanbanService,
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
                pluginTools);
    }

    @Bean
    public DelegationService delegationService(
            AppConfig appConfig,
            ConversationOrchestratorHolder holder,
            SqlitePreferenceStore preferenceStore,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            AgentRunControlService agentRunControlService) {
        return new DefaultDelegationService(
                holder,
                preferenceStore,
                sessionRepository,
                agentRunRepository,
                appConfig,
                agentRunControlService);
    }

    @Bean
    public DisplaySettingsService displaySettingsService(
            AppConfig appConfig, GlobalSettingRepository globalSettingRepository) {
        return new DisplaySettingsService(appConfig, globalSettingRepository);
    }

    @Bean
    public RuntimeFooterService runtimeFooterService(AppConfig appConfig) {
        return new RuntimeFooterService(appConfig);
    }

    @Bean
    public AppVersionService appVersionService(AppConfig appConfig) {
        return new AppVersionService(appConfig);
    }

    @Bean(destroyMethod = "shutdown")
    public AppUpdateService appUpdateService(
            AppConfig appConfig,
            AppVersionService appVersionService,
            SecurityPolicyService securityPolicyService) {
        return new AppUpdateService(appConfig, appVersionService, securityPolicyService);
    }

    @Bean
    public LlmProviderService llmProviderService(AppConfig appConfig) {
        return new LlmProviderService(appConfig);
    }

    @Bean
    public ConversationOrchestratorHolder conversationOrchestratorHolder() {
        return new ConversationOrchestratorHolder();
    }

    @Bean
    public LlmGateway llmGateway(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            LlmProviderService llmProviderService,
            ToolResultTransformService toolResultTransformService,
            ToolCallLoopGuardrailService toolCallLoopGuardrailService,
            SecurityPolicyService securityPolicyService,
            HookBridgeInterceptor hookBridgeInterceptor) {
        SolonAiLlmGateway gateway = new SolonAiLlmGateway(
                appConfig,
                sessionRepository,
                dangerousCommandApprovalService,
                llmProviderService,
                toolResultTransformService,
                toolCallLoopGuardrailService,
                securityPolicyService);
        gateway.setHookBridgeInterceptor(hookBridgeInterceptor);
        return gateway;
    }

    @Bean
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
            AgentHookRegistry agentHookRegistry,
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
        orchestrator.setHookRegistry(agentHookRegistry);
        holder.set(orchestrator);
        return orchestrator;
    }
}
