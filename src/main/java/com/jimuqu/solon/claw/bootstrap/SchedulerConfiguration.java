package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.engine.PendingSessionRecoveryService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.proactive.DefaultRepositoryProbeService;
import com.jimuqu.solon.claw.proactive.ProactiveCandidateService;
import com.jimuqu.solon.claw.proactive.ProactiveDecisionService;
import com.jimuqu.solon.claw.proactive.ProactiveDispatchService;
import com.jimuqu.solon.claw.proactive.ProactiveMessageComposer;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import com.jimuqu.solon.claw.proactive.ProactiveObservationService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.proactive.ProactiveScheduler;
import com.jimuqu.solon.claw.proactive.RepositoryProbeService;
import com.jimuqu.solon.claw.proactive.RepositoryReferenceExtractor;
import com.jimuqu.solon.claw.proactive.collector.CronFollowupCollector;
import com.jimuqu.solon.claw.proactive.collector.MemoryFollowupCollector;
import com.jimuqu.solon.claw.proactive.collector.QuietContextCollector;
import com.jimuqu.solon.claw.proactive.collector.RepositoryUpdateCollector;
import com.jimuqu.solon.claw.proactive.collector.RunStateCollector;
import com.jimuqu.solon.claw.proactive.collector.SessionContinuationCollector;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.scheduler.HeartbeatScheduler;
import com.jimuqu.solon.claw.scheduler.SkillCuratorScheduler;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.ArrayList;
import java.util.List;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 承载调度器配置并集中创建运行组件。 */
@Configuration
public class SchedulerConfiguration {
    /**
     * 执行定时任务任务服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @return 返回定时任务任务服务结果。
     */
    @Bean
    public CronJobService cronJobService(AppConfig appConfig, CronJobRepository cronJobRepository) {
        return new CronJobService(appConfig, cronJobRepository);
    }

    /**
     * 执行默认定时任务调度器相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param cronJobService 定时任务Job服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param localSkillService 本地技能服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param mcpRuntimeService MCP运行时服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @return 返回默认定时任务调度器结果。
     */
    @Bean(destroyMethod = "shutdown")
    public DefaultCronScheduler defaultCronScheduler(
            AppConfig appConfig,
            CronJobRepository cronJobRepository,
            CronJobService cronJobService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            GatewayPolicyRepository gatewayPolicyRepository,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AttachmentCacheService attachmentCacheService,
            LocalSkillService localSkillService,
            AgentRunControlService agentRunControlService,
            McpRuntimeService mcpRuntimeService,
            SessionRepository sessionRepository) {
        DefaultCronScheduler scheduler =
                new DefaultCronScheduler(
                        appConfig,
                        cronJobRepository,
                        cronJobService,
                        conversationOrchestrator,
                        deliveryService,
                        gatewayPolicyRepository,
                        dangerousCommandApprovalService,
                        attachmentCacheService,
                        localSkillService,
                        agentRunControlService,
                        mcpRuntimeService,
                        sessionRepository);
        scheduler.start();
        return scheduler;
    }

    /**
     * 执行心跳调度器相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param personaWorkspaceService persona工作区服务依赖。
     * @return 返回心跳调度器结果。
     */
    @Bean(destroyMethod = "shutdown")
    public HeartbeatScheduler heartbeatScheduler(
            AppConfig appConfig,
            GatewayPolicyRepository gatewayPolicyRepository,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            PersonaWorkspaceService personaWorkspaceService) {
        HeartbeatScheduler scheduler =
                new HeartbeatScheduler(
                        appConfig,
                        gatewayPolicyRepository,
                        conversationOrchestrator,
                        deliveryService,
                        personaWorkspaceService);
        scheduler.start();
        return scheduler;
    }

    /**
     * 执行主动协作仓库探测服务相关逻辑。
     *
     * @return 返回只读仓库探测服务。
     */
    @Bean
    public RepositoryProbeService repositoryProbeService() {
        return new DefaultRepositoryProbeService();
    }

    /**
     * 执行主动协作观测服务相关逻辑。
     *
     * @param proactiveRepository 主动协作仓储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param cronJobRepository 定时任务仓储依赖。
     * @param memoryService 记忆服务依赖。
     * @param repositoryProbeService 仓库只读探测服务依赖。
     * @return 返回主动协作观测服务。
     */
    @Bean
    public ProactiveObservationService proactiveObservationService(
            ProactiveRepository proactiveRepository,
            SessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            CronJobRepository cronJobRepository,
            MemoryService memoryService,
            RepositoryProbeService repositoryProbeService) {
        List<ProactiveObservationCollector> collectors =
                new ArrayList<ProactiveObservationCollector>();
        collectors.add(new QuietContextCollector(agentRunRepository));
        collectors.add(new SessionContinuationCollector(sessionRepository));
        collectors.add(new RunStateCollector(agentRunRepository));
        collectors.add(new CronFollowupCollector(cronJobRepository));
        collectors.add(new MemoryFollowupCollector(memoryService));
        collectors.add(
                new RepositoryUpdateCollector(
                        proactiveRepository,
                        repositoryProbeService,
                        new RepositoryReferenceExtractor(),
                        sessionRepository,
                        memoryService,
                        cronJobRepository));
        return new ProactiveObservationService(proactiveRepository, collectors);
    }

    /**
     * 执行主动协作候选生成服务相关逻辑。
     *
     * @param proactiveRepository 主动协作仓储依赖。
     * @return 返回主动协作候选生成服务。
     */
    @Bean
    public ProactiveCandidateService proactiveCandidateService(
            ProactiveRepository proactiveRepository) {
        return new ProactiveCandidateService(proactiveRepository);
    }

    /**
     * 执行主动协作决策服务相关逻辑。
     *
     * @param proactiveRepository 主动协作仓储依赖。
     * @param llmGateway 大模型网关依赖。
     * @return 返回主动协作决策服务。
     */
    @Bean
    public ProactiveDecisionService proactiveDecisionService(
            ProactiveRepository proactiveRepository, LlmGateway llmGateway) {
        return new ProactiveDecisionService(
                proactiveRepository, new ProactiveDecisionService.GatewayLlmDecisionClient(llmGateway));
    }

    /**
     * 执行主动协作文案生成服务相关逻辑。
     *
     * @param llmGateway 大模型网关依赖。
     * @return 返回主动协作文案生成服务。
     */
    @Bean
    public ProactiveMessageComposer proactiveMessageComposer(LlmGateway llmGateway) {
        return new ProactiveMessageComposer(
                new ProactiveMessageComposer.GatewayLlmPolishClient(llmGateway));
    }

    /**
     * 执行主动协作投递服务相关逻辑。
     *
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param proactiveRepository 主动协作仓储依赖。
     * @return 返回主动协作投递服务。
     */
    @Bean
    public ProactiveDispatchService proactiveDispatchService(
            GatewayPolicyRepository gatewayPolicyRepository,
            DeliveryService deliveryService,
            ProactiveRepository proactiveRepository) {
        return new ProactiveDispatchService(
                gatewayPolicyRepository, deliveryService, proactiveRepository);
    }

    /**
     * 执行主动协作调度器相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param observationService 主动协作观测服务依赖。
     * @param candidateService 主动协作候选服务依赖。
     * @param decisionService 主动协作决策服务依赖。
     * @param messageComposer 主动协作文案服务依赖。
     * @param dispatchService 主动协作投递服务依赖。
     * @param proactiveRepository 主动协作仓储依赖。
     * @return 返回主动协作调度器。
     */
    @Bean(destroyMethod = "shutdown")
    public ProactiveScheduler proactiveScheduler(
            AppConfig appConfig,
            GatewayPolicyRepository gatewayPolicyRepository,
            ProactiveObservationService observationService,
            ProactiveCandidateService candidateService,
            ProactiveDecisionService decisionService,
            ProactiveMessageComposer messageComposer,
            ProactiveDispatchService dispatchService,
            ProactiveRepository proactiveRepository) {
        ProactiveScheduler scheduler =
                new ProactiveScheduler(
                        appConfig,
                        gatewayPolicyRepository,
                        observationService,
                        candidateService,
                        decisionService,
                        messageComposer,
                        dispatchService,
                        proactiveRepository);
        scheduler.start();
        return scheduler;
    }

    /**
     * 执行技能技能维护调度器相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param skillCuratorService 技能Curator服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @return 返回技能技能维护调度器结果。
     */
    @Bean(destroyMethod = "shutdown")
    public SkillCuratorScheduler skillCuratorScheduler(
            AppConfig appConfig,
            SkillCuratorService skillCuratorService,
            AgentRunControlService agentRunControlService) {
        SkillCuratorScheduler scheduler =
                new SkillCuratorScheduler(appConfig, skillCuratorService, agentRunControlService);
        scheduler.start();
        return scheduler;
    }

    /**
     * 执行stale运行恢复Bootstrap相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunSupervisor Agent运行Supervisor参数。
     * @param pendingSessionRecoveryService 待恢复会话恢复服务依赖。
     * @return 返回stale运行Recovery Bootstrap结果。
     */
    @Bean
    public Object staleRunRecoveryBootstrap(
            AppConfig appConfig,
            AgentRunSupervisor agentRunSupervisor,
            PendingSessionRecoveryService pendingSessionRecoveryService) {
        long staleAfterMinutes = Math.max(1, appConfig.getTask().getStaleAfterMinutes());
        agentRunSupervisor.recoverStaleRuns(staleAfterMinutes * 60L * 1000L);
        pendingSessionRecoveryService.recoverRecentPendingSessions();
        return new Object();
    }

    /**
     * 执行待恢复会话恢复服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @return 返回pending会话Recovery服务结果。
     */
    @Bean
    public PendingSessionRecoveryService pendingSessionRecoveryService(
            AppConfig appConfig,
            com.jimuqu.solon.claw.core.repository.SessionRepository sessionRepository,
            ConversationOrchestrator conversationOrchestrator) {
        return new PendingSessionRecoveryService(
                appConfig, sessionRepository, conversationOrchestrator);
    }
}
