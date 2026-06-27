package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ContextService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartCoordinator;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.plugin.AgentPluginManager;
import com.jimuqu.solon.claw.plugin.CommandHandler;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.TimeSupport;
import com.jimuqu.solon.claw.support.constants.CompressionConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供默认命令相关业务能力，封装调用方不需要感知的运行细节。 */
public class DefaultCommandService implements CommandService {
    /** 命令服务日志，记录非关键 fallback 失败，避免 slash command 状态静默丢失。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultCommandService.class);

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** 工具注册表。 */
    private final ToolRegistry toolRegistry;

    /** 本地技能服务。 */
    private final LocalSkillService localSkillService;

    /** 定时任务仓储。 */
    private final CronJobRepository cronJobRepository;

    /** 注入定时任务任务服务，用于调用对应业务能力。 */
    private final CronJobService cronJobService;

    /** 对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 上下文服务。 */
    private final ContextService contextService;

    /** 上下文压缩服务。 */
    private final ContextCompressionService contextCompressionService;

    /** 渠道投递服务。 */
    private final DeliveryService deliveryService;

    /** 授权服务。 */
    private final GatewayAuthorizationService gatewayAuthorizationService;

    /** checkpoint 服务。 */
    private final CheckpointService checkpointService;

    /** 注入技能中心服务，用于调用对应业务能力。 */
    private final SkillHubService skillHubService;

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 全局设置仓储。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 进程注册表。 */
    private final ProcessRegistry processRegistry;

    /** 运行时设置服务。 */
    private final RuntimeSettingsService runtimeSettingsService;

    /** 保存展示设置服务集合，维持调用顺序或去重语义。 */
    private final DisplaySettingsService displaySettingsService;

    /** 注入应用更新服务，用于调用对应业务能力。 */
    private final AppUpdateService appUpdateService;

    /** 注入dangerous命令审批服务，用于调用对应业务能力。 */
    private final DangerousCommandApprovalService dangerousCommandApprovalService;

    /** 注入Agent运行控制服务，用于调用对应业务能力。 */
    private final AgentRunControlService agentRunControlService;

    /** 注入Agent角色配置服务，用于调用对应业务能力。 */
    private final AgentProfileService agentProfileService;

    /** 保存Agent运行仓储依赖，用于访问持久化数据。 */
    private final AgentRunRepository agentRunRepository;

    /** 注入控制台MCP服务，用于调用对应业务能力。 */
    private final DashboardMcpService dashboardMcpService;

    /** 注入目标服务，用于调用对应业务能力。 */
    private final GoalService goalService;

    /** 注入会话Artifact服务，用于调用对应业务能力。 */
    private final SessionArtifactService sessionArtifactService;

    /** 注入斜杠命令Confirm服务，用于调用对应业务能力。 */
    private final SlashConfirmService slashConfirmService;

    /** 保存定时任务调度器执行组件，负责调度异步或定时任务。 */
    private final DefaultCronScheduler cronScheduler;

    /** 记录默认命令中的消息网关重启Coordinator。 */
    private final GatewayRestartCoordinator gatewayRestartCoordinator;

    /** 注入控制台技能维护服务，用于调用对应业务能力。 */
    private final DashboardCuratorService dashboardCuratorService;

    /** 注入控制台技能服务，用于调用对应业务能力。 */
    private final DashboardSkillsService dashboardSkillsService;

    /** 注入浏览器运行时服务，用于调用对应业务能力。 */
    private final BrowserRuntimeService browserRuntimeService;

    /** 注入主动协作诊断服务，用于命令侧只读状态和 why 查询。 */
    private ProactiveDiagnosticsService proactiveDiagnosticsService;

    /** 注入主动协作仓储，用于命令侧忽略候选。 */
    private ProactiveRepository proactiveRepository;

    /** 保存插件Commands映射，便于按键快速查询。 */
    private final Map<String, CommandHandler> pluginCommands;

    /** 记录默认命令中的插件管理器。 */
    private final AgentPluginManager pluginManager;

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                new SessionArtifactService());
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                slashConfirmService,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param pluginCommands 插件Commands参数。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            Map<String, CommandHandler> pluginCommands) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                slashConfirmService,
                pluginCommands,
                null,
                null,
                null,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param pluginCommands 插件Commands参数。
     * @param pluginManager 插件Manager参数。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            Map<String, CommandHandler> pluginCommands,
            AgentPluginManager pluginManager) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                slashConfirmService,
                pluginCommands,
                pluginManager,
                null,
                null,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param pluginCommands 插件Commands参数。
     * @param pluginManager 插件Manager参数。
     * @param dashboardCuratorService dashboardCurator服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            Map<String, CommandHandler> pluginCommands,
            AgentPluginManager pluginManager,
            DashboardCuratorService dashboardCuratorService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                slashConfirmService,
                pluginCommands,
                pluginManager,
                dashboardCuratorService,
                null,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param pluginCommands 插件Commands参数。
     * @param pluginManager 插件Manager参数。
     * @param dashboardCuratorService dashboardCurator服务依赖。
     * @param dashboardSkillsService dashboard技能服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            Map<String, CommandHandler> pluginCommands,
            AgentPluginManager pluginManager,
            DashboardCuratorService dashboardCuratorService,
            DashboardSkillsService dashboardSkillsService) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                slashConfirmService,
                pluginCommands,
                pluginManager,
                dashboardCuratorService,
                dashboardSkillsService,
                null);
    }

    /**
     * 创建默认命令服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param pluginCommands 插件Commands参数。
     * @param pluginManager 插件Manager参数。
     * @param dashboardCuratorService dashboardCurator服务依赖。
     * @param dashboardSkillsService dashboard技能服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            Map<String, CommandHandler> pluginCommands,
            AgentPluginManager pluginManager,
            DashboardCuratorService dashboardCuratorService,
            DashboardSkillsService dashboardSkillsService,
            BrowserRuntimeService browserRuntimeService) {
        this.sessionRepository = sessionRepository;
        this.toolRegistry = toolRegistry;
        this.localSkillService = localSkillService;
        this.cronJobRepository = cronJobRepository;
        this.cronJobService = new CronJobService(appConfig, cronJobRepository);
        this.conversationOrchestrator = conversationOrchestrator;
        this.contextService = contextService;
        this.contextCompressionService = contextCompressionService;
        this.deliveryService = deliveryService;
        this.gatewayAuthorizationService = gatewayAuthorizationService;
        this.checkpointService = checkpointService;
        this.skillHubService = skillHubService;
        this.appConfig = appConfig;
        this.globalSettingRepository = globalSettingRepository;
        this.processRegistry = processRegistry;
        this.runtimeSettingsService = runtimeSettingsService;
        this.displaySettingsService = displaySettingsService;
        this.appUpdateService = appUpdateService;
        this.dangerousCommandApprovalService = dangerousCommandApprovalService;
        this.agentRunControlService = agentRunControlService;
        this.agentProfileService = agentProfileService;
        this.agentRunRepository = agentRunRepository;
        this.dashboardMcpService = dashboardMcpService;
        this.goalService = goalService == null ? new GoalService(sessionRepository) : goalService;
        this.sessionArtifactService =
                sessionArtifactService == null
                        ? new SessionArtifactService()
                        : sessionArtifactService;
        this.slashConfirmService =
                slashConfirmService == null
                        ? new SlashConfirmService(globalSettingRepository)
                        : slashConfirmService;
        this.cronScheduler = cronScheduler;
        this.gatewayRestartCoordinator =
                gatewayRestartCoordinator == null
                        ? new GatewayRestartCoordinator()
                        : gatewayRestartCoordinator;
        this.dashboardCuratorService = dashboardCuratorService;
        this.dashboardSkillsService = dashboardSkillsService;
        this.browserRuntimeService = browserRuntimeService;
        this.pluginCommands =
                pluginCommands == null
                        ? Collections.<String, CommandHandler>emptyMap()
                        : new LinkedHashMap<String, CommandHandler>(pluginCommands);
        this.pluginManager = pluginManager;
    }

    /**
     * 创建带主动协作控制面的默认命令服务实例。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param toolRegistry 工具注册表依赖组件。
     * @param localSkillService 本地技能服务依赖。
     * @param cronJobRepository 定时任务Job仓储依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param contextService 上下文Service上下文。
     * @param contextCompressionService 上下文CompressionService上下文。
     * @param deliveryService 投递服务依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param checkpointService checkpoint服务依赖。
     * @param skillHubService 技能Hub服务依赖。
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param processRegistry 进程注册表依赖组件。
     * @param runtimeSettingsService 运行时Settings服务依赖。
     * @param displaySettingsService 展示Settings服务依赖。
     * @param appUpdateService 应用Update服务依赖。
     * @param dangerousCommandApprovalService dangerous命令审批服务依赖。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @param agentProfileService 文件或目录路径参数。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param pluginCommands 插件Commands参数。
     * @param pluginManager 插件Manager参数。
     * @param dashboardCuratorService dashboardCurator服务依赖。
     * @param dashboardSkillsService dashboard技能服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param proactiveDiagnosticsService 主动协作诊断服务依赖。
     * @param proactiveRepository 主动协作仓储依赖。
     */
    public DefaultCommandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            ContextService contextService,
            ContextCompressionService contextCompressionService,
            DeliveryService deliveryService,
            GatewayAuthorizationService gatewayAuthorizationService,
            CheckpointService checkpointService,
            SkillHubService skillHubService,
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            ProcessRegistry processRegistry,
            RuntimeSettingsService runtimeSettingsService,
            DisplaySettingsService displaySettingsService,
            AppUpdateService appUpdateService,
            DangerousCommandApprovalService dangerousCommandApprovalService,
            AgentRunControlService agentRunControlService,
            AgentProfileService agentProfileService,
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            Map<String, CommandHandler> pluginCommands,
            AgentPluginManager pluginManager,
            DashboardCuratorService dashboardCuratorService,
            DashboardSkillsService dashboardSkillsService,
            BrowserRuntimeService browserRuntimeService,
            ProactiveDiagnosticsService proactiveDiagnosticsService,
            ProactiveRepository proactiveRepository) {
        this(
                sessionRepository,
                toolRegistry,
                localSkillService,
                cronJobRepository,
                conversationOrchestrator,
                contextService,
                contextCompressionService,
                deliveryService,
                gatewayAuthorizationService,
                checkpointService,
                skillHubService,
                appConfig,
                globalSettingRepository,
                processRegistry,
                runtimeSettingsService,
                displaySettingsService,
                appUpdateService,
                dangerousCommandApprovalService,
                agentRunControlService,
                agentProfileService,
                agentRunRepository,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                slashConfirmService,
                pluginCommands,
                pluginManager,
                dashboardCuratorService,
                dashboardSkillsService,
                browserRuntimeService);
        this.proactiveDiagnosticsService = proactiveDiagnosticsService;
        this.proactiveRepository = proactiveRepository;
    }

    /** 判断当前命令是否由默认命令服务承接。 */
    @Override
    public boolean supports(String commandName) {
        return CommandRegistry.resolve(commandName) != null
                || pluginCommands.containsKey(
                        StrUtil.nullToEmpty(commandName).trim().toLowerCase());
    }

    /** 执行单条斜杠命令命令相关逻辑。 */
    @Override
    public GatewayReply handle(GatewayMessage message, String commandLine) throws Exception {
        SlashCommandLine parsed = SlashCommandLine.parse(commandLine);
        CommandDescriptor descriptor = parsed.getDescriptor();
        String command = parsed.getCommand();
        String args = parsed.getArgs();
        recordSlashCommand(message, command, args);

        if (GatewayCommandConstants.COMMAND_AGENT.equals(command)) {
            return GatewayReply.ok(
                    agentProfileService.handleCommand(
                            args, sessionRepository, message.sourceKey()));
        }

        if (GatewayCommandConstants.COMMAND_GOAL.equals(command)) {
            return handleGoal(message, args);
        }

        if (GatewayCommandConstants.COMMAND_RECAP.equals(command)) {
            return handleRecap(message, args);
        }

        if (GatewayCommandConstants.COMMAND_SESSIONS.equals(command)) {
            return handleSessions(args);
        }

        if (GatewayCommandConstants.COMMAND_WHOAMI.equals(command)) {
            return handleWhoami(message);
        }

        if (GatewayCommandConstants.COMMAND_COMMANDS.equals(command)) {
            return handleCommands(args);
        }

        if (GatewayCommandConstants.COMMAND_INSIGHTS.equals(command)) {
            return handleInsights();
        }

        if (GatewayCommandConstants.COMMAND_DEBUG.equals(command)) {
            return handleDebug();
        }

        if (GatewayCommandConstants.COMMAND_TRAJECTORY.equals(command)) {
            return handleTrajectory(message, args);
        }

        if (GatewayCommandConstants.COMMAND_NEW.equals(command)
                || GatewayCommandConstants.COMMAND_RESET.equals(command)) {
            SessionRecord created = sessionRepository.bindNewSession(message.sourceKey());
            String title = normalizeSessionTitle(args);
            String content = "已创建新会话：" + created.getSessionId();
            if (StrUtil.isNotBlank(title)) {
                created.setTitle(title);
                created.setUpdatedAt(System.currentTimeMillis());
                sessionRepository.save(created);
                content = "已创建新会话：" + title + "（" + created.getSessionId() + "）";
            }
            GatewayReply reply = GatewayReply.ok(content);
            reply.setSessionId(created.getSessionId());
            reply.setBranchName(created.getBranchName());
            if (StrUtil.isNotBlank(title)) {
                reply.getRuntimeMetadata().put("title", title);
            }
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_RETRY.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            String lastUser = MessageSupport.getLastUserMessage(session.getNdjson());
            if (StrUtil.isBlank(lastUser)) {
                return GatewayReply.error("没有可重试的上一条用户消息。");
            }
            session.setNdjson(MessageSupport.removeLastTurn(session.getNdjson()));
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);

            GatewayMessage retryMessage =
                    new GatewayMessage(
                            message.getPlatform(),
                            message.getChatId(),
                            message.getUserId(),
                            lastUser);
            retryMessage.setThreadId(message.getThreadId());
            retryMessage.setChatType(message.getChatType());
            retryMessage.setChatName(message.getChatName());
            retryMessage.setUserName(message.getUserName());
            return conversationOrchestrator.handleIncoming(retryMessage);
        }

        if (GatewayCommandConstants.COMMAND_UNDO.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            session.setNdjson(MessageSupport.removeLastTurn(session.getNdjson()));
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
            GatewayReply reply = GatewayReply.ok("已从会话中移除上一轮对话：" + session.getSessionId());
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_BRANCH.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            String branchName =
                    StrUtil.isBlank(args) ? "branch-" + System.currentTimeMillis() : args;
            SessionRecord clone =
                    sessionRepository.cloneSession(
                            message.sourceKey(), session.getSessionId(), branchName);
            GatewayReply reply =
                    GatewayReply.ok("已创建分支 " + branchName + " -> " + clone.getSessionId());
            reply.setSessionId(clone.getSessionId());
            reply.setBranchName(clone.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_RESUME.equals(command)) {
            if (StrUtil.isBlank(args)) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_RESUME
                                + " <session-id|id-prefix|title|branch>");
            }
            ResumeLookup lookup = resolveResumeTarget(message.sourceKey(), args);
            SessionRecord session = lookup.getSession();
            if (session == null) {
                return GatewayReply.error(lookup.getMessage());
            }
            dangerousCommandApprovalService.clearSessionApprovals(
                    new SqliteAgentSession(session, sessionRepository));
            sessionRepository.bindSource(message.sourceKey(), session.getSessionId());
            GatewayReply reply = GatewayReply.ok(formatResumeReply(session));
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_TITLE.equals(command)) {
            return handleTitle(message, args);
        }

        if (GatewayCommandConstants.COMMAND_STATUS.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            int count = MessageSupport.countMessages(session.getNdjson());
            GatewayReply reply =
                    GatewayReply.ok(
                            "session="
                                    + session.getSessionId()
                                    + ", branch="
                                    + session.getBranchName()
                                    + ", messages="
                                    + count
                                    + ", model="
                                    + StrUtil.nullToDefault(session.getModelOverride(), "default")
                                    + ", fast_mode="
                                    + fastModeName(session)
                                    + ", agent="
                                    + StrUtil.blankToDefault(
                                            session.getActiveAgentName(), "default")
                                    + ", personality="
                                    + currentPersonalityName());
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_USAGE.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply =
                    GatewayReply.ok(
                            SlashCommandStatusRenderer.usage(session, runtimeSettingsService));
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_BUSY.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply = handleBusy(args, message.sourceKey());
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_QUEUE.equals(command)) {
            return handleQueue(message, args);
        }

        if (GatewayCommandConstants.COMMAND_STEER.equals(command)) {
            return handleSteer(message, args);
        }

        if (GatewayCommandConstants.COMMAND_RESTART.equals(command)) {
            return handleRestart(message);
        }

        if (GatewayCommandConstants.COMMAND_REASONING.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply = newSessionSettingsCommandHandler().handleReasoning(message, session, args);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_STOP.equals(command)) {
            return handleStop(message);
        }

        if (GatewayCommandConstants.COMMAND_PERSONALITY.equals(command)) {
            return handlePersonality(args);
        }

        if (GatewayCommandConstants.COMMAND_UPDATE.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            AppUpdateService.UpdateResult result = appUpdateService.startUpdate();
            GatewayReply reply =
                    result.isError()
                            ? GatewayReply.error(result.getMessage())
                            : GatewayReply.ok(result.getMessage());
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_VERSION.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply;
            if (StrUtil.isBlank(args)) {
                reply = GatewayReply.ok(appUpdateService.formatVersionReport(false));
            } else if ("check".equalsIgnoreCase(args) || "status".equalsIgnoreCase(args)) {
                reply = GatewayReply.ok(appUpdateService.formatVersionReport(true));
            } else if ("update".equalsIgnoreCase(args)
                    || "upgrade".equalsIgnoreCase(args)
                    || "run".equalsIgnoreCase(args)) {
                AppUpdateService.UpdateResult result = appUpdateService.startUpdate();
                reply =
                        result.isError()
                                ? GatewayReply.error(result.getMessage())
                                : GatewayReply.ok(result.getMessage());
            } else {
                reply = GatewayReply.error("用法：/version [check|update]");
            }
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_MODEL.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply = newSessionSettingsCommandHandler().handleModel(session, args);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_FAST.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply = newSessionSettingsCommandHandler().handleFast(session, args);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_TOOLS.equals(command)) {
            return handleTools(message, args);
        }

        if (GatewayCommandConstants.COMMAND_TOOLSETS.equals(command)) {
            return handleToolsets();
        }

        if (GatewayCommandConstants.COMMAND_BROWSER.equals(command)) {
            return handleBrowser(args);
        }

        if (GatewayCommandConstants.COMMAND_SKILLS.equals(command)) {
            return handleSkills(message, args);
        }

        if (GatewayCommandConstants.COMMAND_CURATOR.equals(command)) {
            return handleCurator(args);
        }

        if (GatewayCommandConstants.COMMAND_PLUGINS.equals(command)) {
            return handlePlugins();
        }

        if (GatewayCommandConstants.COMMAND_RELOAD_SKILLS.equals(command)) {
            return handleReloadSkills();
        }

        if (GatewayCommandConstants.COMMAND_RELOAD_MCP.equals(command)) {
            return handleReloadMcp(message, args);
        }

        if (GatewayCommandConstants.COMMAND_SETHOME.equals(command)) {
            return gatewayAuthorizationService.setHome(message);
        }

        if (GatewayCommandConstants.COMMAND_PAIRING.equals(command)) {
            return handlePairing(message, args);
        }

        if (GatewayCommandConstants.COMMAND_APPROVE.equals(command)) {
            if (hasPendingDangerousApproval(message)) {
                return handleDangerousApprove(message, args);
            }
            return hasPendingSlashConfirm(message)
                    ? handleSlashConfirmChoice(message, args, SlashConfirmService.CHOICE_ONCE)
                    : handleDangerousApprove(message, args);
        }

        if (GatewayCommandConstants.COMMAND_DENY.equals(command)) {
            if (hasPendingDangerousApproval(message)) {
                return handleDangerousDeny(message, args);
            }
            return hasPendingSlashConfirm(message)
                    ? handleSlashConfirmChoice(message, args, SlashConfirmService.CHOICE_CANCEL)
                    : handleDangerousDeny(message, args);
        }

        if (GatewayCommandConstants.COMMAND_CANCEL.equals(command)) {
            if (hasPendingDangerousApproval(message)) {
                return handleDangerousDeny(message, args);
            }
            return handleSlashConfirmChoice(message, args, SlashConfirmService.CHOICE_CANCEL);
        }

        if (GatewayCommandConstants.COMMAND_CONFIRM.equals(command)) {
            return handleSlashConfirmStatus(message);
        }

        if (GatewayCommandConstants.COMMAND_CRON.equals(command)) {
            return handleCron(message, args);
        }

        if (GatewayCommandConstants.COMMAND_PROACTIVE.equals(command)) {
            return handleProactive(args);
        }

        if (GatewayCommandConstants.COMMAND_COMPACT.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            if (agentRunControlService != null
                    && agentRunControlService.isRunning(message.sourceKey())) {
                GatewayReply reply =
                        GatewayReply.error(
                                "当前会话正在运行任务，已跳过上下文压缩，避免覆盖运行中的上下文。请等待任务完成后重试，或先使用 /stop 停止当前任务。");
                reply.setSessionId(session.getSessionId());
                reply.setBranchName(session.getBranchName());
                Map<String, Object> activeRun =
                        agentRunControlService.activeRunSummary(message.sourceKey());
                if (activeRun != null) {
                    reply.getRuntimeMetadata().put("busy_status", "running");
                    Object runId = activeRun.get("run_id");
                    if (runId != null) {
                        reply.getRuntimeMetadata().put("run_id", String.valueOf(runId));
                    }
                }
                return reply;
            }
            String systemPrompt = contextService.buildSystemPrompt(message.sourceKey());
            session.setSystemPromptSnapshot(systemPrompt);
            CompressionOutcome outcome =
                    contextCompressionService.compressNowWithOutcome(session, systemPrompt, args);
            session = outcome.getSession();
            sessionRepository.save(session);
            GatewayReply reply;
            if (outcome.isFailed()) {
                reply =
                        GatewayReply.error(
                                "上下文压缩失败："
                                        + StrUtil.blankToDefault(
                                                outcome.getErrorMessage(), "摘要生成异常"));
            } else if (outcome.isCompressed()) {
                reply =
                        GatewayReply.ok(
                                StrUtil.isBlank(args) ? "已完成当前会话的上下文压缩。" : "已按关注主题完成当前会话的上下文压缩。");
            } else {
                reply = GatewayReply.ok("当前会话没有足够的可压缩历史，已跳过上下文压缩。");
            }
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_ROLLBACK.equals(command)) {
            if (StrUtil.isBlank(args) || isCheckpointListCommand(args)) {
                return GatewayReply.ok(formatCheckpointList(message.sourceKey()));
            }
            if ("status".equalsIgnoreCase(args)) {
                return GatewayReply.ok(
                        SlashCommandStatusRenderer.checkpointStatus(
                                checkpointService, message.sourceKey()));
            }
            if ("prune".equalsIgnoreCase(args)) {
                return GatewayReply.ok(
                        SlashCommandStatusRenderer.checkpointPrune(
                                checkpointService, message.sourceKey()));
            }
            if (isCheckpointClearCommand(args)) {
                if (!hasClearCheckpointConfirmation(args)) {
                    SlashConfirmService.PendingConfirm confirm =
                            slashConfirmService.register(
                                    message.sourceKey(),
                                    GatewayCommandConstants.COMMAND_ROLLBACK,
                                    rollbackClearConfirmPrompt(),
                                    false);
                    return GatewayReply.ok(formatSlashConfirmPrompt(confirm));
                }
                return GatewayReply.ok(
                        SlashCommandStatusRenderer.checkpointClear(
                                checkpointService, message.sourceKey()));
            }
            if ("latest".equalsIgnoreCase(args)) {
                return GatewayReply.ok(
                        "已回滚到最近一次 checkpoint："
                                + checkpointService
                                        .rollbackLatest(message.sourceKey())
                                        .getCheckpointId());
            }
            try {
                int index = Integer.parseInt(args);
                List<CheckpointRecord> recent =
                        checkpointService.listRecent(message.sourceKey(), 10);
                if (index < 1 || index > recent.size()) {
                    return GatewayReply.error("checkpoint 序号无效，应在 1-" + recent.size() + " 之间。");
                }
                CheckpointRecord restored =
                        checkpointService.rollback(recent.get(index - 1).getCheckpointId());
                return GatewayReply.ok("已按列表序号回滚到 checkpoint：" + restored.getCheckpointId());
            } catch (NumberFormatException e) {
                log.debug(
                        "Rollback checkpoint argument is not a list index; treating it as checkpoint id: {}",
                        exceptionSummary(e));
            }
            return GatewayReply.ok(
                    "已回滚到指定 checkpoint：" + checkpointService.rollback(args).getCheckpointId());
        }

        if (GatewayCommandConstants.COMMAND_PLATFORMS.equals(command)
                || GatewayCommandConstants.COMMAND_PLATFORM.equals(command)) {
            return GatewayReply.ok(
                    gatewayAuthorizationService.formatPlatformStatus(deliveryService.statuses()));
        }

        if (GatewayCommandConstants.COMMAND_HELP.equals(command)) {
            return GatewayReply.ok(helpText());
        }

        if (descriptor == null && pluginCommands.containsKey(command)) {
            GatewayReply reply = GatewayReply.ok(pluginCommands.get(command).handle(args));
            reply.setCommandHandled(true);
            return reply;
        }

        CommandDescriptor unresolvedRegistered = CommandRegistry.get(command);
        if (unresolvedRegistered != null) {
            return registeredUnimplementedReply(unresolvedRegistered);
        }
        return GatewayReply.ok(helpText());
    }

    /**
     * 执行handle相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param commandLine 命令行参数。
     * @param eventSink 事件Sink参数。
     * @return 返回handle结果。
     */
    @Override
    public GatewayReply handle(
            GatewayMessage message, String commandLine, ConversationEventSink eventSink)
            throws Exception {
        if (eventSink == null) {
            eventSink = ConversationEventSink.noop();
        }

        SlashCommandLine parsed = SlashCommandLine.parse(commandLine);
        String command = parsed.getCommand();
        String args = parsed.getArgs();

        if (GatewayCommandConstants.COMMAND_APPROVE.equals(command)
                && hasPendingDangerousApproval(message)) {
            recordSlashCommand(message, command, args);
            GatewayReply reply = handleDangerousApprove(message, args, eventSink);
            emitDirectReplyIfNeeded(reply, eventSink, message.sourceKey());
            return reply;
        }

        if ((GatewayCommandConstants.COMMAND_DENY.equals(command)
                        || GatewayCommandConstants.COMMAND_CANCEL.equals(command))
                && hasPendingDangerousApproval(message)) {
            recordSlashCommand(message, command, args);
            GatewayReply reply = handleDangerousDeny(message, args, eventSink);
            emitDirectReplyIfNeeded(reply, eventSink, message.sourceKey());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_RETRY.equals(command)) {
            recordSlashCommand(message, command, args);
            SessionRecord session = requireSession(message.sourceKey());
            String lastUser = MessageSupport.getLastUserMessage(session.getNdjson());
            if (StrUtil.isBlank(lastUser)) {
                GatewayReply reply = GatewayReply.error("没有可重试的上一条用户消息。");
                emitDirectReply(reply, eventSink, session.getSessionId());
                return reply;
            }

            session.setNdjson(MessageSupport.removeLastTurn(session.getNdjson()));
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);

            GatewayMessage retryMessage =
                    new GatewayMessage(
                            message.getPlatform(),
                            message.getChatId(),
                            message.getUserId(),
                            lastUser);
            retryMessage.setThreadId(message.getThreadId());
            retryMessage.setChatType(message.getChatType());
            retryMessage.setChatName(message.getChatName());
            retryMessage.setUserName(message.getUserName());
            retryMessage.setSourceKeyOverride(message.sourceKey());
            return conversationOrchestrator.handleIncoming(retryMessage, eventSink);
        }

        GatewayReply reply = handle(message, commandLine);
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        emitDirectReply(reply, eventSink, session == null ? null : session.getSessionId());
        String goalKickoff = textMetadata(reply, "goal_kickoff");
        if (StrUtil.isNotBlank(goalKickoff)) {
            GatewayMessage kickoffMessage =
                    new GatewayMessage(
                            message.getPlatform(),
                            message.getChatId(),
                            message.getUserId(),
                            goalKickoff);
            kickoffMessage.setThreadId(message.getThreadId());
            kickoffMessage.setChatType(message.getChatType());
            kickoffMessage.setChatName(message.getChatName());
            kickoffMessage.setUserName(message.getUserName());
            kickoffMessage.setSourceKeyOverride(message.sourceKey());
            return conversationOrchestrator.handleIncoming(kickoffMessage, eventSink);
        }
        return reply;
    }

    /**
     * 对没有启动模型恢复的审批类命令补发直接回复；已经恢复的运行会自行发 message.complete。
     *
     * @param reply 回复参数。
     * @param eventSink 事件Sink参数。
     * @param sourceKey 渠道来源键。
     */
    private void emitDirectReplyIfNeeded(
            GatewayReply reply, ConversationEventSink eventSink, String sourceKey)
            throws Exception {
        if (reply == null || reply.getRuntimeMetadata().containsKey("resumed_pending_run")) {
            return;
        }
        SessionRecord session = sessionRepository.getBoundSession(sourceKey);
        emitDirectReply(reply, eventSink, session == null ? null : session.getSessionId());
    }

    /**
     * 执行文本元数据相关逻辑。
     *
     * @param reply 回复参数。
     * @param key 配置键或映射键。
     * @return 返回text元数据结果。
     */
    private String textMetadata(GatewayReply reply, String key) {
        return reply == null ? "" : reply.textRuntimeMetadata(key);
    }

    /**
     * 停止当前组件并释放运行状态。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Stop结果。
     */
    private GatewayReply handleStop(GatewayMessage message) throws Exception {
        AgentRunStopResult stopResult =
                agentRunControlService == null
                        ? AgentRunStopResult.none()
                        : agentRunControlService.stop(message.sourceKey());
        if (!stopResult.isActiveRun()
                && agentRunControlService != null
                && StrUtil.isNotBlank(message.getThreadId())
                && gatewayAuthorizationService.isAuthorized(message)) {
            stopResult = agentRunControlService.stopSiblingThreadRun(message, message.sourceKey());
        }
        int stoppedProcesses = processRegistry.stopAll();
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session != null && dangerousCommandApprovalService != null) {
            dangerousCommandApprovalService.clearSessionApprovals(
                    new SqliteAgentSession(session, sessionRepository));
        }

        StringBuilder buffer = new StringBuilder();
        if (stopResult.isActiveRun()) {
            buffer.append("已请求停止当前任务：").append(stopResult.getRunId());
        } else {
            buffer.append("当前聊天没有正在执行的任务。");
        }
        buffer.append("\n已停止后台进程：").append(stoppedProcesses).append(" 个。");

        GatewayReply reply = GatewayReply.ok(buffer.toString());
        if (session != null) {
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
        }
        return reply;
    }

    /**
     * 执行重启相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回重启结果。
     */
    private GatewayReply handleRestart(GatewayMessage message) throws Exception {
        return new DefaultRestartCommandHandler(
                        sessionRepository, agentRunControlService, gatewayRestartCoordinator)
                .handle(message);
    }

    /**
     * 执行会话自动审批设置相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回会话自动审批设置结果。
     */
    private GatewayReply handleSessionAutoApproval(GatewayMessage message, String args)
            throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        String action = StrUtil.nullToEmpty(args).trim().toLowerCase(java.util.Locale.ROOT);
        boolean enabled;
        if (StrUtil.isBlank(action)) {
            enabled = dangerousCommandApprovalService.toggleSessionAutoApproval(agentSession);
        } else if ("status".equals(action) || "state".equals(action)) {
            enabled = dangerousCommandApprovalService.isSessionAutoApprovalEnabled(agentSession);
        } else if ("on".equals(action) || "enable".equals(action) || "enabled".equals(action)) {
            dangerousCommandApprovalService.enableSessionAutoApproval(agentSession);
            enabled = true;
        } else if ("off".equals(action) || "disable".equals(action) || "disabled".equals(action)) {
            dangerousCommandApprovalService.disableSessionAutoApproval(agentSession);
            enabled = false;
        } else {
            GatewayReply reply = GatewayReply.error("用法：/approve auto [status|on|off]");
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        GatewayReply reply =
                GatewayReply.ok(
                        enabled
                                ? "会话自动审批已开启：当前会话会自动批准可恢复的危险命令；硬阻断命令仍会被拒绝。"
                                : "会话自动审批已关闭：当前会话恢复危险命令审批。");
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        return reply;
    }

    /**
     * 执行目标相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Goal结果。
     */
    private GatewayReply handleGoal(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        if (StrUtil.isBlank(raw) || "status".equalsIgnoreCase(raw)) {
            GatewayReply reply = GatewayReply.ok(goalService.statusLine(session));
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        if ("pause".equalsIgnoreCase(raw)) {
            GoalState state = goalService.pause(session, "user-paused");
            GatewayReply reply =
                    GatewayReply.ok(
                            state == null ? "No goal set." : "⏸ Goal paused: " + state.getGoal());
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        if ("resume".equalsIgnoreCase(raw)) {
            GoalState state = goalService.resume(session, true);
            String continuationPrompt = goalService.nextContinuationPrompt(state);
            GatewayReply reply =
                    GatewayReply.ok(
                            state == null
                                    ? "No goal to resume."
                                    : "▶ Goal resumed: "
                                            + state.getGoal()
                                            + "\n"
                                            + continuationPrompt);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            if (StrUtil.isNotBlank(continuationPrompt)) {
                reply.getRuntimeMetadata().put("goal_kickoff", continuationPrompt);
            }
            return reply;
        }
        if ("clear".equalsIgnoreCase(raw)) {
            boolean had = goalService.clear(session);
            GatewayReply reply = GatewayReply.ok(had ? "✓ Goal cleared." : "No active goal.");
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        int maxTurns = parseGoalMaxTurns(raw, GoalState.DEFAULT_MAX_TURNS);
        String goal = stripGoalOptions(raw);
        GoalState state = goalService.set(session, goal, maxTurns);
        GatewayReply reply =
                GatewayReply.ok(
                        "⊙ Goal set ("
                                + state.getMaxTurns()
                                + "-turn budget): "
                                + state.getGoal()
                                + "\nI'll keep working until the goal is done, you pause/clear it, or the budget is exhausted.\n"
                                + "Controls: /goal status · /goal pause · /goal resume · /goal clear");
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        reply.getRuntimeMetadata().put("goal_kickoff", state.getGoal());
        return reply;
    }

    /**
     * 执行Recap相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Recap结果。
     */
    private GatewayReply handleRecap(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        GatewayReply reply =
                GatewayReply.ok(
                        sessionArtifactService.recapText(session, parsePositiveInt(args, 10)));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        return reply;
    }

    /**
     * 执行Sessions相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回Sessions结果。
     */
    private GatewayReply handleSessions(String args) throws Exception {
        String query = StrUtil.nullToEmpty(args).trim();
        List<SessionRecord> records =
                StrUtil.isBlank(query)
                        ? sessionRepository.listRecent(10)
                        : sessionRepository.search(query, 10);
        if (StrUtil.isNotBlank(query) && (records == null || records.isEmpty())) {
            records = filterRecentSessions(query, 10);
        }
        GatewayReply reply = GatewayReply.ok(formatSessions(records, query));
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_SESSIONS);
        if (StrUtil.isNotBlank(query)) {
            reply.getRuntimeMetadata().put("query", query);
        }
        return reply;
    }

    /**
     * 执行过滤器RecentSessions相关逻辑。
     *
     * @param query 查询参数。
     * @param limit 最大返回数量。
     * @return 返回filter Recent Sessions结果。
     */
    private List<SessionRecord> filterRecentSessions(String query, int limit) throws Exception {
        List<SessionRecord> records = sessionRepository.listRecent(50);
        List<SessionRecord> result = new ArrayList<SessionRecord>();
        if (records == null) {
            return result;
        }
        for (SessionRecord record : records) {
            if (sessionMatches(record, query)) {
                result.add(record);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 执行会话Matches相关逻辑。
     *
     * @param record 记录参数。
     * @param query 查询参数。
     * @return 返回会话Matches结果。
     */
    private boolean sessionMatches(SessionRecord record, String query) {
        if (record == null || StrUtil.isBlank(query)) {
            return false;
        }
        return containsIgnoreCase(record.getSessionId(), query)
                || containsIgnoreCase(record.getTitle(), query)
                || containsIgnoreCase(record.getBranchName(), query)
                || containsIgnoreCase(record.getSourceKey(), query);
    }

    /**
     * 判断是否包含忽略Case。
     *
     * @param text 待处理文本。
     * @param query 查询参数。
     * @return 返回contains忽略Case结果。
     */
    private boolean containsIgnoreCase(String text, String query) {
        return StrUtil.nullToEmpty(text)
                .toLowerCase(java.util.Locale.ROOT)
                .contains(StrUtil.nullToEmpty(query).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 格式化Sessions。
     *
     * @param records records 参数。
     * @param query 查询参数。
     * @return 返回Sessions结果。
     */
    private String formatSessions(List<SessionRecord> records, String query) {
        if (records == null || records.isEmpty()) {
            return StrUtil.isBlank(query) ? "没有找到可浏览的会话。" : "没有找到匹配的会话：" + query;
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append(StrUtil.isBlank(query) ? "最近会话：" : "最近会话（搜索：").append(query).append("）：");
        for (int i = 0; i < records.size(); i++) {
            SessionRecord record = records.get(i);
            if (record == null || StrUtil.isBlank(record.getSessionId())) {
                continue;
            }
            buffer.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(record.getSessionId())
                    .append("  ")
                    .append(StrUtil.blankToDefault(record.getTitle(), "(未命名会话)"))
                    .append("  branch=")
                    .append(StrUtil.blankToDefault(record.getBranchName(), "-"))
                    .append("  updated=")
                    .append(formatTimestamp(record.getUpdatedAt()))
                    .append("  tokens=")
                    .append(record.getCumulativeTotalTokens());
        }
        buffer.append('\n').append("使用：/resume <session-id|title> 恢复会话。");
        return buffer.toString();
    }

    /**
     * 执行Whoami相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Whoami结果。
     */
    private GatewayReply handleWhoami(GatewayMessage message) throws Exception {
        boolean admin = gatewayAuthorizationService.isAdmin(message);
        boolean authorized = gatewayAuthorizationService.isAuthorized(message);
        String role = admin ? "admin" : authorized ? "user" : "unauthorized";
        StringBuilder buffer = new StringBuilder();
        buffer.append("platform=").append(message.getPlatform()).append('\n');
        buffer.append("user=")
                .append(StrUtil.blankToDefault(message.getUserId(), "-"))
                .append('\n');
        buffer.append("chat=")
                .append(StrUtil.blankToDefault(message.getChatId(), "-"))
                .append('\n');
        buffer.append("chat_type=")
                .append(StrUtil.blankToDefault(message.getChatType(), "-"))
                .append('\n');
        buffer.append("role=").append(role).append('\n');
        buffer.append("authorized=").append(authorized);
        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_WHOAMI);
        reply.getRuntimeMetadata().put("role", role);
        reply.getRuntimeMetadata().put("authorized", Boolean.valueOf(authorized));
        return reply;
    }

    /**
     * 执行Commands相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回Commands结果。
     */
    private GatewayReply handleCommands(String args) {
        int page = Math.max(1, parsePositiveInt(args, 1));
        int pageSize = 30;
        List<CommandDescriptor> descriptors =
                new ArrayList<CommandDescriptor>(CommandRegistry.all());
        int total = descriptors.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (page > totalPages) {
            page = totalPages;
        }
        int from = (page - 1) * pageSize;
        int to = Math.min(total, from + pageSize);
        StringBuilder buffer = new StringBuilder();
        buffer.append("命令目录 page=")
                .append(page)
                .append("/")
                .append(totalPages)
                .append(" total=")
                .append(total);
        for (int i = from; i < to; i++) {
            CommandDescriptor descriptor = descriptors.get(i);
            buffer.append('\n')
                    .append(descriptor.slashName())
                    .append(" [")
                    .append(descriptor.getCategory())
                    .append("] - ")
                    .append(descriptor.getDescription());
        }
        if (page < totalPages) {
            buffer.append('\n')
                    .append("下一页：")
                    .append(GatewayCommandConstants.SLASH_COMMANDS)
                    .append(' ')
                    .append(page + 1);
        }
        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_COMMANDS);
        reply.getRuntimeMetadata().put("page", Integer.valueOf(page));
        reply.getRuntimeMetadata().put("total", Integer.valueOf(total));
        return reply;
    }

    /**
     * 执行洞察相关逻辑。
     *
     * @return 返回洞察结果。
     */
    private GatewayReply handleInsights() throws Exception {
        int sessionTotal = sessionRepository == null ? 0 : sessionRepository.countAll();
        List<String> skillNames =
                localSkillService == null
                        ? Collections.<String>emptyList()
                        : localSkillService.listSkillNames();
        int skillAvailable = skillNames.size();
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemoryMb = runtime.maxMemory() / (1024 * 1024);
        int processors = runtime.availableProcessors();

        StringBuilder buffer = new StringBuilder();
        buffer.append("使用洞察\n");
        buffer.append("sessions.total=").append(sessionTotal).append('\n');
        buffer.append("skills.available=").append(skillAvailable).append('\n');
        buffer.append("runtime.memory=")
                .append(usedMemoryMb)
                .append("MB/")
                .append(maxMemoryMb)
                .append("MB")
                .append('\n');
        buffer.append("runtime.processors=").append(processors);

        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_INSIGHTS);
        reply.getRuntimeMetadata().put("session_total", Integer.valueOf(sessionTotal));
        reply.getRuntimeMetadata().put("skill_available", Integer.valueOf(skillAvailable));
        reply.getRuntimeMetadata().put("runtime_used_memory_mb", Long.valueOf(usedMemoryMb));
        reply.getRuntimeMetadata().put("runtime_max_memory_mb", Long.valueOf(maxMemoryMb));
        return reply;
    }

    /**
     * 解析Resume Target。
     *
     * @param sourceKey 渠道来源键。
     * @param rawReference 原始引用参数。
     * @return 返回解析后的Resume Target。
     */
    private ResumeLookup resolveResumeTarget(String sourceKey, String rawReference)
            throws Exception {
        String reference = normalizeResumeReference(rawReference);
        if (StrUtil.isBlank(reference)) {
            return ResumeLookup.error(
                    "用法："
                            + GatewayCommandConstants.SLASH_RESUME
                            + " <session-id|id-prefix|title|branch>");
        }
        SessionRecord session = sessionRepository.findById(reference);
        if (session != null) {
            return ResumeLookup.found(session);
        }
        session = sessionRepository.findBySourceAndBranch(sourceKey, reference);
        if (session != null) {
            return ResumeLookup.found(session);
        }
        List<SessionRecord> candidates = sessionRepository.findResumeCandidates(reference, 3);
        if (candidates.size() == 1) {
            return ResumeLookup.found(candidates.get(0));
        }
        if (candidates.size() > 1) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("匹配到多个会话，请使用完整 session id：");
            for (SessionRecord candidate : candidates) {
                buffer.append('\n').append("- ").append(candidate.getSessionId());
                if (StrUtil.isNotBlank(candidate.getTitle())) {
                    buffer.append(" \"").append(candidate.getTitle()).append("\"");
                }
                if (StrUtil.isNotBlank(candidate.getBranchName())) {
                    buffer.append(" branch=").append(candidate.getBranchName());
                }
            }
            return ResumeLookup.error(buffer.toString());
        }
        return ResumeLookup.error("未找到对应会话、分支或标题：" + reference);
    }

    /**
     * 规范化Resume Reference。
     *
     * @param rawReference 原始引用参数。
     * @return 返回Resume Reference结果。
     */
    private String normalizeResumeReference(String rawReference) {
        String value = StrUtil.nullToEmpty(rawReference).trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    /**
     * 格式化Resume Reply。
     *
     * @param session 会话参数。
     * @return 返回Resume Reply结果。
     */
    private String formatResumeReply(SessionRecord session) throws Exception {
        StringBuilder buffer = new StringBuilder();
        buffer.append("已恢复会话：").append(session.getSessionId());
        if (StrUtil.isNotBlank(session.getTitle())) {
            buffer.append(" \"").append(session.getTitle()).append("\"");
        }
        buffer.append(" (messages=")
                .append(MessageSupport.countMessages(session.getNdjson()))
                .append(")");

        String resumeDisplay =
                appConfig == null || appConfig.getDisplay() == null
                        ? "full"
                        : StrUtil.blankToDefault(appConfig.getDisplay().getResumeDisplay(), "full");
        if ("minimal".equalsIgnoreCase(resumeDisplay)) {
            return buffer.toString();
        }
        String recap = sessionArtifactService.recapText(session, 10);
        if (StrUtil.isNotBlank(recap)) {
            buffer.append("\n\n历史摘要：\n").append(recap);
        }
        return buffer.toString();
    }

    /** 承载ResumeLookup相关状态和辅助逻辑。 */
    private static class ResumeLookup {
        /** 记录ResumeLookup中的会话。 */
        private final SessionRecord session;

        /** 记录ResumeLookup中的消息。 */
        private final String message;

        /**
         * 创建Resume Lookup实例，并注入运行所需依赖。
         *
         * @param session 会话参数。
         * @param message 平台消息或错误消息。
         */
        private ResumeLookup(SessionRecord session, String message) {
            this.session = session;
            this.message = message;
        }

        /**
         * 执行found相关逻辑。
         *
         * @param session 会话参数。
         * @return 返回found结果。
         */
        static ResumeLookup found(SessionRecord session) {
            return new ResumeLookup(session, "");
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param message 平台消息或错误消息。
         * @return 返回error结果。
         */
        static ResumeLookup error(String message) {
            return new ResumeLookup(null, message);
        }

        /**
         * 读取会话。
         *
         * @return 返回读取到的会话。
         */
        SessionRecord getSession() {
            return session;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        String getMessage() {
            return message;
        }
    }

    /**
     * 执行Trajectory相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Trajectory结果。
     */
    private GatewayReply handleTrajectory(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        SlashCommandLine.ActionTail parsed = SlashCommandLine.parseActionTail(raw, "");
        String action = parsed.getAction();
        if ("save".equalsIgnoreCase(action)) {
            String tail = parsed.getTail();
            boolean completed = !containsTrajectoryFailedFlag(tail);
            String userQuery = stripTrajectorySaveFlags(tail);
            Map<String, Object> saved =
                    sessionArtifactService.saveTrajectory(
                            session, StrUtil.blankToDefault(userQuery, null), completed);
            GatewayReply reply =
                    GatewayReply.ok(
                            "已保存 trajectory："
                                    + saved.get("path")
                                    + "\nformat=jsonl, completed="
                                    + completed);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        String userQuery = StrUtil.blankToDefault(raw, null);
        GatewayReply reply =
                GatewayReply.ok(sessionArtifactService.trajectoryJson(session, userQuery, true));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        return reply;
    }

    /**
     * 判断是否包含TrajectoryFailedFlag。
     *
     * @param raw 原始输入值。
     * @return 返回contains Trajectory Failed Flag结果。
     */
    private boolean containsTrajectoryFailedFlag(String raw) {
        String[] parts = StrUtil.nullToEmpty(raw).trim().split("\\s+");
        for (String part : parts) {
            if ("--failed".equalsIgnoreCase(part) || "--incomplete".equalsIgnoreCase(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥离TrajectorySaveFlags。
     *
     * @param raw 原始输入值。
     * @return 返回strip Trajectory Save Flags结果。
     */
    private String stripTrajectorySaveFlags(String raw) {
        String[] parts = StrUtil.nullToEmpty(raw).trim().split("\\s+");
        List<String> kept = new ArrayList<String>();
        for (String part : parts) {
            if (StrUtil.isBlank(part)
                    || "--failed".equalsIgnoreCase(part)
                    || "--incomplete".equalsIgnoreCase(part)
                    || "--completed".equalsIgnoreCase(part)) {
                continue;
            }
            kept.add(part);
        }
        return String.join(" ", kept).trim();
    }

    /**
     * 解析Positive Int。
     *
     * @param raw 原始输入值。
     * @param defaultValue 默认值参数。
     * @return 返回解析后的Positive Int。
     */
    private int parsePositiveInt(String raw, int defaultValue) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(text.split("\\s+", 2)[0]));
        } catch (Exception e) {
            log.debug("Positive integer option parsing failed; using default value: {}", exceptionSummary(e));
            return defaultValue;
        }
    }

    /**
     * 记录Slash命令。
     *
     * @param message 平台消息或错误消息。
     * @param command 待执行或解析的命令文本。
     * @param args 工具或命令参数。
     */
    private void recordSlashCommand(GatewayMessage message, String command, String args) {
        if (agentRunRepository == null || message == null || !isTrackedSlash(command)) {
            return;
        }
        try {
            SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
            if (session == null) {
                return;
            }
            List<AgentRunRecord> runs = agentRunRepository.listBySession(session.getSessionId(), 1);
            if (runs.isEmpty()) {
                return;
            }
            AgentRunRecord run = runs.get(0);
            AgentRunEventRecord event = new AgentRunEventRecord();
            event.setEventId(IdSupport.newId());
            event.setRunId(run.getRunId());
            event.setSessionId(session.getSessionId());
            event.setSourceKey(message.sourceKey());
            event.setEventType("slash.command");
            event.setPhase(run.getPhase());
            event.setSeverity("info");
            event.setSummary("/" + command);
            event.setMetadataJson(
                    org.noear.snack4.ONode.serialize(
                            java.util.Collections.singletonMap(
                                    "args", SecretRedactor.redact(args, 4000))));
            event.setCreatedAt(System.currentTimeMillis());
            agentRunRepository.appendEvent(event);
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            log.debug("Slash command event recording failed; command history remains best-effort: {}", exceptionSummary(e));
        }
    }

    /**
     * 判断是否Tracked Slash。
     *
     * @param command 待执行或解析的命令文本。
     * @return 如果Tracked Slash满足条件则返回 true，否则返回 false。
     */
    private boolean isTrackedSlash(String command) {
        return GatewayCommandConstants.COMMAND_NEW.equals(command)
                || GatewayCommandConstants.COMMAND_RETRY.equals(command)
                || GatewayCommandConstants.COMMAND_UNDO.equals(command)
                || GatewayCommandConstants.COMMAND_BRANCH.equals(command)
                || GatewayCommandConstants.COMMAND_RESUME.equals(command)
                || GatewayCommandConstants.COMMAND_STOP.equals(command)
                || GatewayCommandConstants.COMMAND_RELOAD_MCP.equals(command)
                || GatewayCommandConstants.COMMAND_COMPACT.equals(command)
                || GatewayCommandConstants.COMMAND_ROLLBACK.equals(command);
    }

    /**
     * 执行标题相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回标题结果。
     */
    private GatewayReply handleTitle(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        if (StrUtil.isBlank(raw)) {
            GatewayReply reply =
                    GatewayReply.ok(
                            "当前会话标题：" + StrUtil.blankToDefault(session.getTitle(), "(未设置)"));
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        String action = SlashCommandLine.firstToken(raw);
        if ("clear".equalsIgnoreCase(action) || "reset".equalsIgnoreCase(action)) {
            session.setTitle("");
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
            GatewayReply reply = GatewayReply.ok("已清空当前会话标题：" + session.getSessionId());
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        String title = normalizeSessionTitle(raw);
        if (StrUtil.isBlank(title)) {
            return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_TITLE + " [clear|新标题]");
        }
        session.setTitle(title);
        session.setUpdatedAt(System.currentTimeMillis());
        sessionRepository.save(session);
        GatewayReply reply = GatewayReply.ok("已更新当前会话标题：" + title);
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        reply.getRuntimeMetadata().put("title", title);
        return reply;
    }

    /**
     * 规范化会话标题。
     *
     * @param raw 原始输入值。
     * @return 返回会话标题结果。
     */
    private String normalizeSessionTitle(String raw) {
        String title = normalizeResumeReference(raw);
        title = title.replace('\r', ' ').replace('\n', ' ').trim();
        while (title.contains("  ")) {
            title = title.replace("  ", " ");
        }
        if (title.length() <= CompressionConstants.MAX_TITLE_LENGTH) {
            return title;
        }
        return title.substring(0, CompressionConstants.MAX_TITLE_LENGTH) + "...";
    }

    /**
     * 执行ReloadMCP相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Reload MCP结果。
     */
    private GatewayReply handleReloadMcp(GatewayMessage message, String args) throws Exception {
        String action = SlashCommandLine.firstToken(args);
        if (StrUtil.isBlank(action)) {
            if (!appConfig.getApprovals().isMcpReloadConfirm()
                    || slashConfirmService.isAlwaysConfirmed(
                            GatewayCommandConstants.COMMAND_RELOAD_MCP)) {
                return executeReloadMcp(message, false);
            }
            SlashConfirmService.PendingConfirm confirm =
                    slashConfirmService.register(
                            message.sourceKey(),
                            GatewayCommandConstants.COMMAND_RELOAD_MCP,
                            reloadMcpConfirmPrompt());
            return GatewayReply.ok(formatSlashConfirmPrompt(confirm));
        }
        if (!"now".equalsIgnoreCase(action) && !"always".equalsIgnoreCase(action)) {
            return GatewayReply.error(
                    "用法：" + GatewayCommandConstants.SLASH_RELOAD_MCP + " [now|always]");
        }
        if ("always".equalsIgnoreCase(action)) {
            slashConfirmService.addAlwaysConfirmed(GatewayCommandConstants.COMMAND_RELOAD_MCP);
            persistMcpReloadConfirm(false);
        }
        return executeReloadMcp(message, "always".equalsIgnoreCase(action));
    }

    /**
     * 执行斜杠命令ConfirmChoice相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @param defaultChoice 默认Choice参数。
     * @return 返回Slash Confirm Choice结果。
     */
    private GatewayReply handleSlashConfirmChoice(
            GatewayMessage message, String args, String defaultChoice) throws Exception {
        SlashConfirmService.PendingConfirm pending =
                slashConfirmService.getPending(message.sourceKey());
        if (pending == null) {
            return GatewayReply.error("当前没有待确认的 slash 命令。");
        }
        String[] tokens = slashConfirmTokens(args);
        String confirmId = null;
        String choiceToken = tokens.length > 0 ? tokens[0] : defaultChoice;
        if (tokens.length > 0 && StrUtil.equalsIgnoreCase(tokens[0], pending.getConfirmId())) {
            confirmId = tokens[0];
            choiceToken = tokens.length > 1 ? tokens[1] : defaultChoice;
        } else if (tokens.length > 0 && isSlashConfirmIdToken(tokens[0])) {
            confirmId = tokens[0];
            choiceToken = tokens.length > 1 ? tokens[1] : defaultChoice;
        } else if (tokens.length > 1
                && StrUtil.equalsIgnoreCase(tokens[1], pending.getConfirmId())) {
            confirmId = tokens[1];
        } else if (tokens.length > 1 && isSlashConfirmIdToken(tokens[1])) {
            confirmId = tokens[1];
        }
        String choice =
                normalizeSlashConfirmChoice(StrUtil.blankToDefault(choiceToken, defaultChoice));
        if (choice == null) {
            return GatewayReply.error("用法：/approve [确认编号]、/approve always [确认编号] 或 /cancel");
        }
        if (SlashConfirmService.CHOICE_ALWAYS.equals(choice) && !pending.isAllowAlways()) {
            return GatewayReply.error("/" + pending.getCommand() + " 不支持永久确认，请使用 /approve 执行一次。");
        }
        pending =
                StrUtil.isBlank(confirmId)
                        ? slashConfirmService.resolve(message.sourceKey())
                        : slashConfirmService.resolve(message.sourceKey(), confirmId);
        if (pending == null) {
            return GatewayReply.error("待确认的 slash 命令已过期或确认编号不匹配，请重新发起。");
        }
        if (SlashConfirmService.CHOICE_CANCEL.equals(choice)) {
            return GatewayReply.ok("已取消 /" + pending.getCommand() + "。");
        }
        if (SlashConfirmService.CHOICE_ALWAYS.equals(choice)) {
            slashConfirmService.addAlwaysConfirmed(pending.getCommand());
            if (GatewayCommandConstants.COMMAND_RELOAD_MCP.equals(pending.getCommand())) {
                persistMcpReloadConfirm(false);
            }
        }
        if (GatewayCommandConstants.COMMAND_RELOAD_MCP.equals(pending.getCommand())) {
            return executeReloadMcp(message, SlashConfirmService.CHOICE_ALWAYS.equals(choice));
        }
        if (GatewayCommandConstants.COMMAND_ROLLBACK.equals(pending.getCommand())) {
            return GatewayReply.ok(
                    SlashCommandStatusRenderer.checkpointClear(
                            checkpointService, message.sourceKey()));
        }
        return GatewayReply.error("Unsupported slash confirm command: /" + pending.getCommand());
    }

    /**
     * 执行斜杠命令Confirmtoken相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回slash Confirm token结果。
     */
    private String[] slashConfirmTokens(String raw) {
        String value = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(raw)).trim();
        if (StrUtil.isBlank(value)) {
            return new String[0];
        }
        return value.split("\\s+");
    }

    /**
     * 判断是否Slash Confirm标识token。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Slash Confirm标识token满足条件则返回 true，否则返回 false。
     */
    private boolean isSlashConfirmIdToken(String value) {
        String token = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        return token.length() == 32 && token.matches("[0-9a-fA-F]+");
    }

    /**
     * 判断是否存在Pending Slash Confirm。
     *
     * @param message 平台消息或错误消息。
     * @return 如果Pending Slash Confirm满足条件则返回 true，否则返回 false。
     */
    private boolean hasPendingSlashConfirm(GatewayMessage message) {
        return message != null && slashConfirmService.getPending(message.sourceKey()) != null;
    }

    /**
     * 执行斜杠命令Confirm状态相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Slash Confirm状态。
     */
    private GatewayReply handleSlashConfirmStatus(GatewayMessage message) {
        SlashConfirmService.PendingConfirm pending =
                message == null ? null : slashConfirmService.getPending(message.sourceKey());
        if (pending == null) {
            return GatewayReply.ok("当前没有待确认的 slash 命令。");
        }
        return GatewayReply.ok(
                "当前待确认 slash 命令：/"
                        + SecretRedactor.redact(pending.getCommand(), 400)
                        + "\n"
                        + formatSlashConfirmPrompt(pending));
    }

    /**
     * 判断是否存在Pending Dangerous审批。
     *
     * @param message 平台消息或错误消息。
     * @return 如果Pending Dangerous审批满足条件则返回 true，否则返回 false。
     */
    private boolean hasPendingDangerousApproval(GatewayMessage message) {
        if (message == null) {
            return false;
        }
        try {
            SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
            if (session == null) {
                return false;
            }
            return !dangerousCommandApprovalService
                    .listPendingApprovals(new SqliteAgentSession(session, sessionRepository))
                    .isEmpty();
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            log.debug("Pending dangerous approval lookup failed; treating as no pending approval: {}", exceptionSummary(e));
            return false;
        }
    }

    /**
     * 规范化Slash Confirm Choice。
     *
     * @param raw 原始输入值。
     * @return 返回Slash Confirm Choice结果。
     */
    private String normalizeSlashConfirmChoice(String raw) {
        String value =
                SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(raw)).trim().toLowerCase();
        if (StrUtil.isBlank(value)
                || "once".equals(value)
                || "now".equals(value)
                || "yes".equals(value)
                || "ok".equals(value)
                || "confirm".equals(value)) {
            return SlashConfirmService.CHOICE_ONCE;
        }
        if ("always".equals(value) || "永久".equals(value)) {
            return SlashConfirmService.CHOICE_ALWAYS;
        }
        if ("cancel".equals(value) || "deny".equals(value) || "no".equals(value)) {
            return SlashConfirmService.CHOICE_CANCEL;
        }
        return null;
    }

    /**
     * 执行reloadMCPConfirm提示词相关逻辑。
     *
     * @return 返回reload MCP Confirm提示词结果。
     */
    private String reloadMcpConfirmPrompt() {
        return "⚠️ /reload-mcp 会重新加载 MCP 工具并让下一轮模型请求重新发送完整工具 schema。";
    }

    /**
     * 执行回滚ClearConfirm提示词相关逻辑。
     *
     * @return 返回回滚Clear Confirm提示词结果。
     */
    private String rollbackClearConfirmPrompt() {
        return "⚠️ /rollback clear 会删除当前来源的全部 checkpoint 历史。";
    }

    /**
     * 格式化Slash Confirm提示词。
     *
     * @param confirm confirm 参数。
     * @return 返回Slash Confirm提示词结果。
     */
    private String formatSlashConfirmPrompt(SlashConfirmService.PendingConfirm confirm) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(SecretRedactor.redact(confirm.getPrompt(), 1000))
                .append("\n确认编号：")
                .append(confirm.getConfirmId());
        if (confirm.isAllowAlways()) {
            buffer.append(
                    "\n回复 /approve [确认编号] 执行一次，/approve always [确认编号] 执行并永久记住，/deny 或 /cancel 取消。");
        } else {
            buffer.append("\n回复 /approve [确认编号] 执行一次，/deny 或 /cancel 取消。");
        }
        return buffer.toString();
    }

    /**
     * 执行Reload MCP。
     *
     * @param message 平台消息或错误消息。
     * @param savedAlways savedAlways 参数。
     * @return 返回Reload MCP结果。
     */
    private GatewayReply executeReloadMcp(GatewayMessage message, boolean savedAlways)
            throws Exception {
        if (dashboardMcpService == null) {
            return GatewayReply.error("MCP registry is not available in this runtime.");
        }
        DashboardMcpService.McpReloadResult result = dashboardMcpService.reloadAll();
        appendReloadMcpHistoryNotice(message, result);
        StringBuilder buffer = new StringBuilder();
        buffer.append("MCP reload completed: ");
        buffer.append(result.isEnabled() ? "enabled" : "disabled");
        buffer.append(", tools=").append(result.getToolCount());
        buffer.append(", changed_servers=").append(result.getChangedServers());
        buffer.append(", unchanged_servers=").append(result.getUnchangedServers());
        if (savedAlways) {
            buffer.append("\n已永久确认 /reload-mcp，后续将直接执行。");
        }
        return GatewayReply.ok(buffer.toString());
    }

    /**
     * 追加Reload MCP历史Notice。
     *
     * @param message 平台消息或错误消息。
     * @param result 结果响应或执行结果。
     */
    private void appendReloadMcpHistoryNotice(
            GatewayMessage message, DashboardMcpService.McpReloadResult result) {
        if (message == null || result == null) {
            return;
        }
        try {
            SessionRecord session = requireSession(message.sourceKey());
            if (session == null) {
                return;
            }
            List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
            messages.add(
                    ChatMessage.ofUser(
                            reloadMcpHistoryNotice(
                                    result.getChangedServers(),
                                    result.getUnchangedServers(),
                                    result.getToolCount())));
            session.setNdjson(MessageSupport.toNdjson(messages));
            session.setUpdatedAt(System.currentTimeMillis());
            sessionRepository.save(session);
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            log.debug("MCP reload history notice append failed; continuing without history notice: {}", exceptionSummary(e));
        }
    }

    /**
     * 执行reloadMCP历史Notice相关逻辑。
     *
     * @param changed服务端 changed服务端 参数。
     * @param unchanged服务端 unchanged服务端 参数。
     * @param toolCount 工具Count参数。
     * @return 返回reload MCP历史Notice结果。
     */
    private String reloadMcpHistoryNotice(
            List<String> changedServers, List<String> unchangedServers, int toolCount) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[IMPORTANT: MCP servers have been reloaded. ");
        if (changedServers != null && !changedServers.isEmpty()) {
            buffer.append("Changed servers: ").append(changedServers).append(". ");
        }
        if (unchangedServers != null && !unchangedServers.isEmpty()) {
            buffer.append("Reconnected servers: ").append(unchangedServers).append(". ");
        }
        if (toolCount > 0) {
            buffer.append(toolCount).append(" MCP tool(s) now available. ");
        } else {
            buffer.append("No MCP tools available. ");
        }
        buffer.append("The tool list for this conversation has been updated accordingly.]");
        return buffer.toString();
    }

    /**
     * 执行persistMCPReloadConfirm相关逻辑。
     *
     * @param confirmRequired confirmRequired 参数。
     */
    private void persistMcpReloadConfirm(boolean confirmRequired) {
        appConfig.getApprovals().setMcpReloadConfirm(confirmRequired);
        if (runtimeSettingsService != null) {
            try {
                runtimeSettingsService.setConfigValue(
                        "approvals.mcpReloadConfirm", String.valueOf(confirmRequired));
            } catch (Exception e) {
                restoreInterruptIfNeeded(e);
                log.warn("MCP reload confirmation setting persistence failed; in-memory setting remains active: error={}", exceptionSummary(e));
            }
        }
    }

    /** 执行工具开关命令相关逻辑。 */
    private GatewayReply handleTools(GatewayMessage message, String args) {
        String[] parts = args.split("\\s+");
        if (parts.length == 0
                || StrUtil.isBlank(parts[0])
                || GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(parts[0])) {
            return GatewayReply.ok("工具列表：" + toolRegistry.listToolNames());
        }

        List<String> names = new ArrayList<String>();
        for (int i = 1; i < parts.length; i++) {
            if (StrUtil.isNotBlank(parts[i])) {
                names.add(parts[i]);
            }
        }

        if (GatewayCommandConstants.ACTION_ENABLE.equalsIgnoreCase(parts[0])) {
            toolRegistry.enableTools(message.sourceKey(), names);
            return GatewayReply.ok("已启用工具：" + names);
        }

        if (GatewayCommandConstants.ACTION_DISABLE.equalsIgnoreCase(parts[0])) {
            toolRegistry.disableTools(message.sourceKey(), names);
            return GatewayReply.ok("已禁用工具：" + names);
        }

        return GatewayReply.error(
                "用法：" + GatewayCommandConstants.SLASH_TOOLS + " [list|enable|disable] [name...]");
    }

    /**
     * 执行Toolsets相关逻辑。
     *
     * @return 返回Toolsets结果。
     */
    private GatewayReply handleToolsets() {
        return newDiagnosticsCommandHandler().handleToolsets();
    }

    /**
     * 执行浏览器相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回浏览器结果。
     */
    private GatewayReply handleBrowser(String args) {
        return newDiagnosticsCommandHandler().handleBrowser(args);
    }

    /**
     * 执行Debug相关逻辑。
     *
     * @return 返回Debug结果。
     */
    private GatewayReply handleDebug() throws Exception {
        return newDiagnosticsCommandHandler().handleDebug();
    }

    /** 创建诊断与运行时工具命令处理器。 */
    private DefaultDiagnosticsCommandHandler newDiagnosticsCommandHandler() {
        return new DefaultDiagnosticsCommandHandler(
                appConfig,
                toolRegistry,
                sessionRepository,
                deliveryService,
                dashboardSkillsService,
                browserRuntimeService);
    }

    /** 执行技能命令相关逻辑。 */
    private GatewayReply handleSkills(GatewayMessage message, String args) throws Exception {
        return newSkillCommandHandler().handleSkills(message, args);
    }

    /** 创建技能命令处理器，集中承接 skills、tap 与 curator 命令族。 */
    private DefaultSkillCommandHandler newSkillCommandHandler() {
        return new DefaultSkillCommandHandler(localSkillService, skillHubService, dashboardCuratorService);
    }

    /**
     * 执行Reload技能相关逻辑。
     *
     * @return 返回Reload技能结果。
     */
    private GatewayReply handleReloadSkills() throws Exception {
        return newSkillCommandHandler().handleReloadSkills();
    }

    /**
     * 执行技能维护相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回技能维护结果。
     */
    private GatewayReply handleCurator(String args) throws Exception {
        return newSkillCommandHandler().handleCurator(args);
    }

    /**
     * 执行Plugins相关逻辑。
     *
     * @return 返回Plugins结果。
     */
    private GatewayReply handlePlugins() {
        return newRuntimeCommandHandler().handlePlugins();
    }

    /** 执行人格命令相关逻辑。 */
    private GatewayReply handlePersonality(String args) throws Exception {
        return newRuntimeCommandHandler().handlePersonality(args);
    }

    /**
     * 执行主动协作命令相关逻辑；命令只改变控制面设置或候选状态，不触发调度、投递或工具执行。
     *
     * @param args 工具或命令参数。
     * @return 返回主动协作命令结果。
     */
    private GatewayReply handleProactive(String args) throws Exception {
        return newRuntimeCommandHandler().handleProactive(args);
    }

    /** 创建运行时控制面命令处理器。 */
    private DefaultRuntimeCommandHandler newRuntimeCommandHandler() {
        return new DefaultRuntimeCommandHandler(
                appConfig,
                globalSettingRepository,
                pluginManager,
                proactiveDiagnosticsService,
                proactiveRepository);
    }

    /** 执行定时任务命令相关逻辑。 */
    private GatewayReply handleCron(GatewayMessage message, String args) throws Exception {
        return new DefaultCronCommandHandler(cronJobService, cronScheduler).handle(message, args);
    }

    /**
     * 拆分命令Line。
     *
     * @param raw 原始输入值。
     * @return 返回命令Line结果。
     */
    private List<String> splitCommandLine(String raw) {
        return SlashCommandTextSupport.splitCommandLine(raw);
    }

    /** 执行pairing相关命令相关逻辑。 */
    private GatewayReply handlePairing(GatewayMessage message, String args) throws Exception {
        return new DefaultPairingCommandHandler(gatewayAuthorizationService).handle(message, args);
    }

    /**
     * 执行DangerousApprove相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Dangerous Approve结果。
     */
    private GatewayReply handleDangerousApprove(GatewayMessage message, String args)
            throws Exception {
        return handleDangerousApprove(message, args, ConversationEventSink.noop());
    }

    /**
     * 执行DangerousApprove相关逻辑，并把审批后的恢复运行事件输出给调用方。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @param eventSink 事件Sink参数。
     * @return 返回Dangerous Approve结果。
     */
    private GatewayReply handleDangerousApprove(
            GatewayMessage message, String args, ConversationEventSink eventSink)
            throws Exception {
        String safeArgs = cleanApprovalCommandArgs(args);
        String normalizedArgs = safeArgs.toLowerCase();
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session == null) {
            if ("list".equals(normalizedArgs) || "status".equals(normalizedArgs)) {
                return GatewayReply.ok(formatEmptyApprovalList());
            }
            return GatewayReply.error("当前没有绑定会话，也没有待审批的危险命令。请先触发需要审批的工具调用。");
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        if (isSessionAutoApprovalCommand(normalizedArgs)) {
            return handleSessionAutoApproval(message, SlashCommandLine.remainingTokens(safeArgs));
        }
        if ("list".equals(normalizedArgs) || "status".equals(normalizedArgs)) {
            return GatewayReply.ok(formatApprovalList(agentSession));
        }
        if (normalizedArgs.startsWith("clear")) {
            return clearApprovals(agentSession, normalizedArgs);
        }
        if (isApproveAllCommand(normalizedArgs)) {
            return approveAllDangerousCommands(message, agentSession, args, eventSink);
        }

        ApprovalCommandArgs approvalArgs = parseApprovalCommandArgs(safeArgs);
        DangerousCommandApprovalService.PendingApproval pending =
                selectPendingApproval(agentSession, approvalArgs.getSelector());
        if (pending == null) {
            return GatewayReply.error("当前没有待审批的危险命令。若刚刚收到审批提示，请重试原始请求；也可以使用 /approve list 查看审批状态。");
        }

        if (!dangerousCommandApprovalService.approve(
                agentSession,
                approvalArgs.getSelector(),
                approvalArgs.getScope(),
                message.getUserName())) {
            return GatewayReply.error("危险命令审批状态已失效，请重试原始请求。");
        }
        GatewayReply reply =
                conversationOrchestrator.resumePending(message.sourceKey(), eventSink);
        reply.getRuntimeMetadata().put("resumed_pending_run", Boolean.TRUE);
        return reply;
    }

    /**
     * 判断审批命令是否请求当前会话自动审批设置。
     *
     * @param normalizedArgs 已标准化为小写的命令参数。
     * @return 如果首个参数为 auto 则返回 true。
     */
    private boolean isSessionAutoApprovalCommand(String normalizedArgs) {
        return "auto".equals(SlashCommandLine.firstToken(normalizedArgs));
    }

    /**
     * 判断是否Approve全部命令。
     *
     * @param normalizedArgs normalizedArgs 参数。
     * @return 如果Approve全部命令满足条件则返回 true，否则返回 false。
     */
    private boolean isApproveAllCommand(String normalizedArgs) {
        if (StrUtil.isBlank(normalizedArgs)) {
            return false;
        }
        String first = SlashCommandLine.firstToken(normalizedArgs);
        return "all".equals(first);
    }

    /**
     * 执行approve全部DangerousCommands相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param agentSession Agent会话参数。
     * @param args 工具或命令参数。
     * @return 返回approve全部Dangerous Commands结果。
     */
    private GatewayReply approveAllDangerousCommands(
            GatewayMessage message, SqliteAgentSession agentSession, String args) throws Exception {
        return approveAllDangerousCommands(
                message, agentSession, args, ConversationEventSink.noop());
    }

    /**
     * 执行approve全部DangerousCommands相关逻辑，并输出审批后的恢复运行事件。
     *
     * @param message 平台消息或错误消息。
     * @param agentSession Agent会话参数。
     * @param args 工具或命令参数。
     * @param eventSink 事件Sink参数。
     * @return 返回approve全部Dangerous Commands结果。
     */
    private GatewayReply approveAllDangerousCommands(
            GatewayMessage message,
            SqliteAgentSession agentSession,
            String args,
            ConversationEventSink eventSink)
            throws Exception {
        ApprovalCommandArgs approvalArgs = parseApprovalCommandArgs(cleanApprovalCommandArgs(args));
        DangerousCommandApprovalService.ApprovalScope scope =
                approvalArgs.getScope() == null
                        ? DangerousCommandApprovalService.ApprovalScope.ONCE
                        : approvalArgs.getScope();
        int approved =
                dangerousCommandApprovalService.approveAll(
                        agentSession, scope, message.getUserName());
        if (approved <= 0) {
            return GatewayReply.error("当前没有待审批的危险命令。若刚刚收到审批提示，请重试原始请求；也可以使用 /approve list 查看审批状态。");
        }
        GatewayReply reply =
                conversationOrchestrator.resumePending(message.sourceKey(), eventSink);
        reply.getRuntimeMetadata().put("resumed_pending_run", Boolean.TRUE);
        return reply;
    }

    /**
     * 格式化审批List。
     *
     * @param agentSession Agent会话参数。
     * @return 返回审批List结果。
     */
    private String formatApprovalList(SqliteAgentSession agentSession) {
        java.util.List<DangerousCommandApprovalService.PendingApproval> pendingApprovals =
                dangerousCommandApprovalService.listPendingApprovals(agentSession);
        StringBuilder buffer = new StringBuilder();
        buffer.append("pending=")
                .append(
                        pendingApprovals.isEmpty()
                                ? "none"
                                : String.valueOf(pendingApprovals.size()))
                .append('\n');
        for (int i = 0; i < pendingApprovals.size(); i++) {
            DangerousCommandApprovalService.PendingApproval pending = pendingApprovals.get(i);
            buffer.append('#')
                    .append(i + 1)
                    .append(' ')
                    .append(
                            safeApprovalPreview(
                                    DangerousCommandApprovalService.approvalSelector(pending), 120))
                    .append(" tool=")
                    .append(safeApprovalPreview(pending.getToolName(), 120))
                    .append(" pattern=")
                    .append(safeApprovalPreview(pending.getPatternKey(), 240))
                    .append(" reason=")
                    .append(safeApprovalPreview(pending.getDescription(), 1000))
                    .append(" command_preview=")
                    .append(safeApprovalPreview(pending.getCommand(), 800))
                    .append(" scopes=")
                    .append(approvalScopes(pending))
                    .append(" expires_in=")
                    .append(TimeSupport.expiresInSeconds(pending.getExpiresAt()))
                    .append("s expired=")
                    .append(isExpired(pending.getExpiresAt()))
                    .append('\n');
        }
        buffer.append("session_approvals_count=")
                .append(dangerousCommandApprovalService.listSessionApprovals(agentSession).size())
                .append('\n');
        buffer.append("always_approvals_count=")
                .append(dangerousCommandApprovalService.listAlwaysApprovals().size());
        return buffer.toString();
    }

    /**
     * 生成未绑定会话时的只读审批状态，避免查询型命令被误判为执行失败。
     *
     * @return 返回空审批列表展示文本。
     */
    private String formatEmptyApprovalList() {
        return "pending=none\nsession_approvals_count=0\nalways_approvals_count="
                + dangerousCommandApprovalService.listAlwaysApprovals().size();
    }

    /**
     * 生成安全展示用的审批预览。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe审批Preview结果。
     */
    private String safeApprovalPreview(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 执行审批Scopes相关逻辑。
     *
     * @param pending 待恢复参数。
     * @return 返回审批Scopes结果。
     */
    private String approvalScopes(DangerousCommandApprovalService.PendingApproval pending) {
        if (pending != null && pending.isPermanentApprovalAllowed()) {
            return "once,session,always";
        }
        return "once,session";
    }

    /**
     * 判断是否Expired。
     *
     * @param expiresAt expiresAt 参数。
     * @return 如果Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isExpired(long expiresAt) {
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

    /**
     * 清理Approvals。
     *
     * @param agentSession Agent会话参数。
     * @param normalizedArgs normalizedArgs 参数。
     * @return 返回Approvals结果。
     */
    private GatewayReply clearApprovals(SqliteAgentSession agentSession, String normalizedArgs)
            throws Exception {
        String[] parts = normalizedArgs.split("\\s+", 3);
        String scope = parts.length >= 2 ? parts[1] : "session";
        if ("session".equals(scope)) {
            dangerousCommandApprovalService.clearSessionApprovals(agentSession);
            return GatewayReply.ok("cleared session approvals");
        }
        if ("always".equals(scope)) {
            dangerousCommandApprovalService.clearAlwaysApprovals();
            return GatewayReply.ok("cleared always approvals");
        }
        if ("all".equals(scope)) {
            dangerousCommandApprovalService.clearSessionApprovals(agentSession);
            dangerousCommandApprovalService.clearAlwaysApprovals();
            return GatewayReply.ok("cleared all approvals");
        }
        return GatewayReply.error("用法：/approve clear session|always|all");
    }

    /**
     * 执行select待恢复审批相关逻辑。
     *
     * @param agentSession Agent会话参数。
     * @param selector 浏览器元素选择器。
     * @return 返回select Pending审批结果。
     */
    private DangerousCommandApprovalService.PendingApproval selectPendingApproval(
            SqliteAgentSession agentSession, String selector) {
        return dangerousCommandApprovalService.selectPendingApproval(agentSession, selector);
    }

    /**
     * 解析审批命令参数。
     *
     * @param args 工具或命令参数。
     * @return 返回解析后的审批命令参数。
     */
    private ApprovalCommandArgs parseApprovalCommandArgs(String args) {
        String[] parts = StrUtil.nullToEmpty(args).trim().split("\\s+");
        ApprovalCommandArgs parsed = new ApprovalCommandArgs();
        parsed.setScope(DangerousCommandApprovalService.ApprovalScope.ONCE);
        if (parts.length == 1 && parts[0].length() == 0) {
            return parsed;
        }
        for (String part : parts) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            DangerousCommandApprovalService.ApprovalScope scope = parseApprovalScope(part);
            if (scope != DangerousCommandApprovalService.ApprovalScope.ONCE
                    || "once".equalsIgnoreCase(part)
                    || "session".equalsIgnoreCase(part)
                    || "always".equalsIgnoreCase(part)) {
                parsed.setScope(scope);
            } else if (StrUtil.isBlank(parsed.getSelector())) {
                parsed.setSelector(part);
            }
        }
        return parsed;
    }

    /**
     * 执行DangerousDeny相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Dangerous Deny结果。
     */
    private GatewayReply handleDangerousDeny(GatewayMessage message, String args) throws Exception {
        return handleDangerousDeny(message, args, ConversationEventSink.noop());
    }

    /**
     * 执行DangerousDeny相关逻辑，并把拒绝后的恢复运行事件输出给调用方。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @param eventSink 事件Sink参数。
     * @return 返回Dangerous Deny结果。
     */
    private GatewayReply handleDangerousDeny(
            GatewayMessage message, String args, ConversationEventSink eventSink)
            throws Exception {
        String safeArgs = cleanApprovalCommandArgs(args);
        String selector = SlashCommandLine.firstToken(safeArgs);
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session == null) {
            if ("list".equalsIgnoreCase(selector) || "status".equalsIgnoreCase(selector)) {
                return GatewayReply.ok(formatEmptyApprovalList());
            }
            return GatewayReply.error("当前没有待审批的危险命令。");
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        if ("list".equalsIgnoreCase(selector) || "status".equalsIgnoreCase(selector)) {
            return GatewayReply.ok(formatApprovalList(agentSession));
        }
        if ("all".equalsIgnoreCase(selector)) {
            int rejected =
                    dangerousCommandApprovalService.rejectAll(agentSession, message.getUserName());
            if (rejected <= 0) {
                return GatewayReply.error("当前没有待审批的危险命令。");
            }
            GatewayReply reply =
                    conversationOrchestrator.resumePending(message.sourceKey(), eventSink);
            reply.getRuntimeMetadata().put("resumed_pending_run", Boolean.TRUE);
            return reply;
        }
        DangerousCommandApprovalService.PendingApproval pending =
                selectPendingApproval(agentSession, selector);
        if (pending == null) {
            return GatewayReply.error("当前没有待审批的危险命令。");
        }

        if (!dangerousCommandApprovalService.reject(
                agentSession, selector, message.getUserName())) {
            return GatewayReply.error("危险命令审批状态已失效，请重试。");
        }
        GatewayReply reply =
                conversationOrchestrator.resumePending(message.sourceKey(), eventSink);
        reply.getRuntimeMetadata().put("resumed_pending_run", Boolean.TRUE);
        return reply;
    }

    /**
     * 执行fast模式名称相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回fast模式名称结果。
     */
    private String fastModeName(SessionRecord session) {
        return isPriorityServiceTier(session) ? "fast" : "normal";
    }

    /**
     * 执行服务Tier名称相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回服务Tier名称结果。
     */
    private String serviceTierName(SessionRecord session) {
        return isPriorityServiceTier(session) ? "priority" : "default";
    }

    /**
     * 判断是否Priority服务Tier。
     *
     * @param session 会话参数。
     * @return 如果Priority服务Tier满足条件则返回 true，否则返回 false。
     */
    private boolean isPriorityServiceTier(SessionRecord session) {
        return GatewayCommandSessionSupport.isPriorityServiceTier(session);
    }

    /**
     * 执行Busy相关逻辑。
     *
     * @param args 工具或命令参数。
     * @param sourceKey 渠道来源键。
     * @return 返回Busy结果。
     */
    private GatewayReply handleBusy(String args, String sourceKey) {
        return newBusyCommandHandler().handleBusy(args, sourceKey);
    }

    /**
     * 执行队列相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Queue结果。
     */
    private GatewayReply handleQueue(GatewayMessage message, String args) throws Exception {
        return newBusyCommandHandler().handleQueue(message, args);
    }

    /**
     * 执行Steer相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Steer结果。
     */
    private GatewayReply handleSteer(GatewayMessage message, String args) throws Exception {
        return newBusyCommandHandler().handleSteer(message, args);
    }

    /** 创建运行中输入策略命令处理器。 */
    private DefaultBusyCommandHandler newBusyCommandHandler() {
        return new DefaultBusyCommandHandler(
                appConfig,
                runtimeSettingsService,
                sessionRepository,
                agentRunRepository,
                agentRunControlService,
                conversationOrchestrator);
    }

    /**
     * 清理审批命令参数。
     *
     * @param args 工具或命令参数。
     * @return 返回clean审批命令参数结果。
     */
    private String cleanApprovalCommandArgs(String args) {
        return SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(args)).trim();
    }

    /**
     * 解析审批范围。
     *
     * @param args 工具或命令参数。
     * @return 返回解析后的审批范围。
     */
    private DangerousCommandApprovalService.ApprovalScope parseApprovalScope(String args) {
        String normalized = cleanApprovalCommandArgs(args).toLowerCase();
        if ("always".equals(normalized)
                || "permanent".equals(normalized)
                || "permanently".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.ALWAYS;
        }
        if ("session".equals(normalized) || "ses".equals(normalized)) {
            return DangerousCommandApprovalService.ApprovalScope.SESSION;
        }
        return DangerousCommandApprovalService.ApprovalScope.ONCE;
    }

    /** 获取当前来源键的会话；若不存在则立即创建。 */
    private SessionRecord requireSession(String sourceKey) throws Exception {
        return GatewayCommandSessionSupport.requireSession(sessionRepository, sourceKey);
    }

    /**
     * 发送Direct回复。
     *
     * @param reply 回复参数。
     * @param eventSink 事件Sink参数。
     * @param fallbackSessionId 兜底会话标识。
     */
    private void emitDirectReply(
            GatewayReply reply, ConversationEventSink eventSink, String fallbackSessionId) {
        if (eventSink == null || eventSink == ConversationEventSink.noop() || reply == null) {
            return;
        }

        String sessionId = StrUtil.blankToDefault(reply.getSessionId(), fallbackSessionId);
        if (reply.isError()) {
            eventSink.onRunFailed(sessionId, new IllegalStateException(reply.getContent()));
            return;
        }

        if (StrUtil.isNotBlank(reply.getContent())) {
            eventSink.onAssistantDelta(reply.getContent());
        }
        eventSink.onRunCompleted(sessionId, "", null);
    }

    /**
     * 格式化检查点List。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回检查点List结果。
     */
    private String formatCheckpointList(String sourceKey) throws Exception {
        List<CheckpointRecord> checkpoints = checkpointService.listRecent(sourceKey, 10);
        if (checkpoints.isEmpty()) {
            return "当前来源键没有可回滚的 checkpoint。";
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < checkpoints.size(); i++) {
            CheckpointRecord record = checkpoints.get(i);
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(i + 1)
                    .append(". ")
                    .append(record.getCheckpointId())
                    .append(" created=")
                    .append(DateUtil.formatDateTime(new java.util.Date(record.getCreatedAt())))
                    .append(", restored=")
                    .append(
                            record.getRestoredAt() > 0
                                    ? DateUtil.formatDateTime(
                                            new java.util.Date(record.getRestoredAt()))
                                    : "never");
            if (StrUtil.isNotBlank(record.getSessionId())) {
                buffer.append(", session=").append(safeIdentifier(record.getSessionId()));
            }
        }
        return buffer.toString();
    }

    /**
     * 执行当前Personality名称相关逻辑。
     *
     * @return 返回当前Personality名称结果。
     */
    private String currentPersonalityName() {
        return newRuntimeCommandHandler().currentPersonalityName();
    }

    /** 创建会话设置命令处理器。 */
    private DefaultSessionSettingsCommandHandler newSessionSettingsCommandHandler() {
        return new DefaultSessionSettingsCommandHandler(
                appConfig, sessionRepository, runtimeSettingsService, displaySettingsService);
    }

    /** 生成帮助文本。 */
    private GatewayReply registeredUnimplementedReply(CommandDescriptor descriptor) {
        GatewayReply reply = GatewayReply.error("命令已登记但当前运行时未启用或不支持：" + descriptor.slashName());
        reply.getRuntimeMetadata().put("command_status", "registered_unimplemented");
        reply.getRuntimeMetadata().put("command", descriptor.getName());
        return reply;
    }

    /**
     * 委托专用渲染器生成帮助文本，避免命令服务继续承载长文本维护职责。
     *
     * @return 返回help Text结果。
     */
    private String helpText() {
        return SlashCommandHelpRenderer.render();
    }

    /**
     * 判断是否检查点Clear命令。
     *
     * @param args 工具或命令参数。
     * @return 如果检查点Clear命令满足条件则返回 true，否则返回 false。
     */
    private boolean isCheckpointClearCommand(String args) {
        String first = SlashCommandLine.firstToken(args);
        return "clear".equalsIgnoreCase(first) || "clear-all".equalsIgnoreCase(first);
    }

    /**
     * 判断是否为 checkpoint 列表别名，保持 CLI、TUI 和消息网关的 rollback 语义一致。
     *
     * @param args 工具或命令参数。
     * @return list 或 ls 返回 true。
     */
    private boolean isCheckpointListCommand(String args) {
        String first = SlashCommandLine.firstToken(args);
        return "list".equalsIgnoreCase(first) || "ls".equalsIgnoreCase(first);
    }

    /**
     * 判断是否存在Clear检查点Confirmation。
     *
     * @param args 工具或命令参数。
     * @return 如果Clear检查点Confirmation满足条件则返回 true，否则返回 false。
     */
    private boolean hasClearCheckpointConfirmation(String args) {
        List<String> tokens = splitCommandLine(args);
        for (String token : tokens) {
            if ("--confirm".equalsIgnoreCase(token)
                    || "--force".equalsIgnoreCase(token)
                    || "-f".equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 格式化时间戳。
     *
     * @param timestamp 请求携带的时间戳。
     * @return 返回时间戳结果。
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "never";
        }
        return DateUtil.formatDateTime(new java.util.Date(timestamp));
    }

    /**
     * 执行as长整型相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Long结果。
     */
    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            log.debug("Long value parsing failed; using 0 fallback: {}", exceptionSummary(e));
            return 0L;
        }
    }

    /**
     * 生成安全展示用的Identifier。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Identifier结果。
     */
    private String safeIdentifier(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 400);
    }

    /**
     * 解析Goal Max Turns。
     *
     * @param raw 原始输入值。
     * @param defaultValue 默认值参数。
     * @return 返回解析后的Goal Max Turns。
     */
    private int parseGoalMaxTurns(String raw, int defaultValue) {
        String[] tokens = StrUtil.nullToEmpty(raw).split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (("--max-turns".equals(token) || "--max".equals(token)) && i + 1 < tokens.length) {
                try {
                    return Math.max(1, Integer.parseInt(tokens[i + 1]));
                } catch (Exception e) {
                    log.debug("Goal max turns option parsing failed; using default value: {}", exceptionSummary(e));
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * 在命令辅助逻辑捕获中断异常时恢复中断标记，避免吞掉上层调度信号。
     *
     * @param error 捕获到的命令处理异常。
     */
    private static void restoreInterruptIfNeeded(Exception error) {
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 生成命令服务异常摘要；只记录异常类型，避免命令参数或配置值进入日志。
     *
     * @param error 命令处理过程中捕获到的异常。
     * @return 可写入日志的异常摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    /**
     * 剥离目标Options。
     *
     * @param raw 原始输入值。
     * @return 返回strip Goal Options结果。
     */
    private String stripGoalOptions(String raw) {
        String[] tokens = StrUtil.nullToEmpty(raw).trim().split("\\s+");
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (("--max-turns".equals(token) || "--max".equals(token)) && i + 1 < tokens.length) {
                i++;
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(token);
        }
        return buffer.toString().trim();
    }

}
