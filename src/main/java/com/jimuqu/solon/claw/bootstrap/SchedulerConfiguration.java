package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.CrossSessionReflectionService;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.context.PersonaWorkspaceService;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.engine.PendingSessionRecoveryService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.proactive.ProactiveReminderScheduler;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.scheduler.HeartbeatScheduler;
import com.jimuqu.solon.claw.scheduler.ReflectionScheduler;
import com.jimuqu.solon.claw.scheduler.SkillCuratorScheduler;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
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
     * 创建并启动由上下文文件控制的主动提醒调度器。
     *
     * @param appConfig 应用运行配置。
     * @param sessionRepository 会话仓储，用于定位最近的国内渠道主对话。
     * @param gatewayPolicyRepository 网关策略仓储，用于读取显式主对话绑定。
     * @param memoryService 记忆服务，用于向主动提醒模型提供三层记忆。
     * @param llmGateway 模型网关，用于分析活跃度并生成提醒内容。
     * @param deliveryService 渠道投递服务，用于把提醒发送到主对话。
     * @param personaWorkspaceService 人格工作区服务，用于读取主动提醒上下文文件。
     * @param globalSettingRepository 全局设置仓储，用于持久化提醒活跃度与冷却状态。
     * @return 返回已启动的主动提醒调度器。
     */
    @Bean(destroyMethod = "shutdown")
    public ProactiveReminderScheduler proactiveReminderScheduler(
            AppConfig appConfig,
            SessionRepository sessionRepository,
            GatewayPolicyRepository gatewayPolicyRepository,
            MemoryService memoryService,
            LlmGateway llmGateway,
            DeliveryService deliveryService,
            PersonaWorkspaceService personaWorkspaceService,
            GlobalSettingRepository globalSettingRepository) {
        ProactiveReminderScheduler scheduler =
                new ProactiveReminderScheduler(
                        appConfig,
                        sessionRepository,
                        gatewayPolicyRepository,
                        memoryService,
                        llmGateway,
                        deliveryService,
                        personaWorkspaceService,
                        globalSettingRepository);
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
     * 创建并启动跨会话反思调度器。
     *
     * @param appConfig 应用配置。
     * @param reflectionService 跨会话反思服务。
     * @param agentRunControlService Agent 运行控制服务。
     * @return 已启动的跨会话反思调度器。
     */
    @Bean(destroyMethod = "shutdown")
    public ReflectionScheduler reflectionScheduler(
            AppConfig appConfig,
            CrossSessionReflectionService reflectionService,
            AgentRunControlService agentRunControlService) {
        ReflectionScheduler scheduler =
                new ReflectionScheduler(appConfig, reflectionService, agentRunControlService);
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
        agentRunSupervisor.setPendingSessionResumer(
                pendingSessionRecoveryService::resumeInterruptedSession);
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
