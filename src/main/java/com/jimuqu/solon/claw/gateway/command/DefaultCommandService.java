package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.command.CommandDescriptor;
import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
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
import com.jimuqu.solon.claw.plugin.AgentPluginManifest;
import com.jimuqu.solon.claw.plugin.CommandHandler;
import com.jimuqu.solon.claw.plugin.PluginLoadDiagnostic;
import com.jimuqu.solon.claw.plugin.PluginLoadStatus;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.SourceKeySupport;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 提供默认命令相关业务能力，封装调用方不需要感知的运行细节。 */
public class DefaultCommandService implements CommandService {
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
        String withoutSlash = commandLine.substring(1).trim();
        String[] parts = withoutSlash.split("\\s+", 2);
        CommandDescriptor descriptor = CommandRegistry.resolve(parts[0]);
        String command = descriptor == null ? parts[0].toLowerCase() : descriptor.getName();
        String args = parts.length > 1 ? parts[1].trim() : "";
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
            GatewayReply reply = GatewayReply.ok(formatUsage(session));
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
            GatewayReply reply = handleReasoning(message, args);
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_STOP.equals(command)) {
            return handleStop(message);
        }

        if (GatewayCommandConstants.COMMAND_YOLO.equals(command)) {
            return handleYolo(message, args);
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
            if (StrUtil.isBlank(args)) {
                GatewayReply reply = GatewayReply.ok(runtimeSettingsService.describeModel(session));
                reply.setSessionId(session.getSessionId());
                reply.setBranchName(session.getBranchName());
                return reply;
            }

            ModelCommandInput input = parseModelCommand(args);
            if (input.clear) {
                sessionRepository.setModelOverride(session.getSessionId(), null);
                GatewayReply reply = GatewayReply.ok("已清除当前会话模型覆盖，下一条消息将回退到全局默认模型。");
                reply.setSessionId(session.getSessionId());
                reply.setBranchName(session.getBranchName());
                return reply;
            }
            if (StrUtil.isBlank(input.model)) {
                return GatewayReply.error(
                        "用法：/model [--global] <model> 或 /model [--global] <provider>:<model>");
            }

            if (input.global) {
                runtimeSettingsService.setGlobalModel(input.provider, input.model);
                GatewayReply reply =
                        GatewayReply.ok(
                                "已更新全局默认模型为："
                                        + (StrUtil.isNotBlank(input.provider)
                                                ? input.provider + ":"
                                                : "")
                                        + input.model
                                        + "（下一条消息生效）");
                reply.setSessionId(session.getSessionId());
                reply.setBranchName(session.getBranchName());
                return reply;
            }

            String override =
                    StrUtil.isNotBlank(input.provider)
                            ? input.provider + ":" + input.model
                            : input.model;
            sessionRepository.setModelOverride(session.getSessionId(), override);
            GatewayReply reply = GatewayReply.ok("已切换当前会话模型为：" + override + "（下一条消息生效）");
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_FAST.equals(command)) {
            SessionRecord session = requireSession(message.sourceKey());
            GatewayReply reply = handleFast(session, args);
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

        if (GatewayCommandConstants.COMMAND_ALWAYS.equals(command)) {
            return handleSlashConfirmChoice(message, args, SlashConfirmService.CHOICE_ALWAYS);
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

        if (isCompressionCommand(command)) {
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
                return GatewayReply.ok(formatCheckpointStatus(message.sourceKey()));
            }
            if ("prune".equalsIgnoreCase(args)) {
                return GatewayReply.ok(formatCheckpointPrune(message.sourceKey()));
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
                return GatewayReply.ok(formatCheckpointClear(message.sourceKey()));
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
            } catch (NumberFormatException ignored) {
                // 保留此处实现约束，避免后续维护时破坏既有行为。
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

        String withoutSlash = commandLine.substring(1).trim();
        String[] parts = withoutSlash.split("\\s+", 2);
        CommandDescriptor descriptor = CommandRegistry.resolve(parts[0]);
        String command = descriptor == null ? parts[0].toLowerCase() : descriptor.getName();
        String args = parts.length > 1 ? parts[1].trim() : "";

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
     * 执行文本元数据相关逻辑。
     *
     * @param reply 回复参数。
     * @param key 配置键或映射键。
     * @return 返回text元数据结果。
     */
    private String textMetadata(GatewayReply reply, String key) {
        if (reply == null || reply.getRuntimeMetadata() == null) {
            return "";
        }
        Object value = reply.getRuntimeMetadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
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
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        int activeRuns =
                agentRunControlService == null ? 0 : agentRunControlService.runningRunCount();
        GatewayRestartCoordinator.RestartRequest request =
                gatewayRestartCoordinator.requestRestartDrain(message, activeRuns);
        StringBuilder buffer = new StringBuilder();
        if (!request.isFirstRequest()) {
            buffer.append("网关重启已在进行中");
            if (activeRuns > 0) {
                buffer.append("，仍有 ").append(activeRuns).append(" 个任务等待 drain。");
            } else {
                buffer.append("。");
            }
        } else if (activeRuns > 0) {
            buffer.append("网关将重启，正在等待 ")
                    .append(activeRuns)
                    .append(" 个运行中任务完成；最长等待 ")
                    .append(request.getDrainTimeoutSeconds())
                    .append(" 秒。");
        } else {
            buffer.append("网关将立即重启。");
        }
        buffer.append("\n如果 60 秒内没有收到恢复通知，请在控制台检查 java -jar 或 Docker 进程。");

        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("restart_requested", Boolean.TRUE);
        reply.getRuntimeMetadata()
                .put("restart_first_request", Boolean.valueOf(request.isFirstRequest()));
        reply.getRuntimeMetadata().put("restart_active_runs", Integer.valueOf(activeRuns));
        reply.getRuntimeMetadata()
                .put(
                        "restart_drain_timeout_seconds",
                        Integer.valueOf(request.getDrainTimeoutSeconds()));
        putRestartRequesterMetadata(reply, request.getRequesterRouting());
        if (session != null) {
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
        }
        return reply;
    }

    /**
     * 写入重启Requester元数据。
     *
     * @param reply 回复参数。
     * @param routing routing 参数。
     */
    private void putRestartRequesterMetadata(
            GatewayReply reply, GatewayRestartCoordinator.RequesterRouting routing) {
        if (reply == null || routing == null || reply.getRuntimeMetadata() == null) {
            return;
        }
        if (routing.getPlatform() != null) {
            reply.getRuntimeMetadata()
                    .put("restart_requester_platform", routing.getPlatform().name());
        }
        if (StrUtil.isNotBlank(routing.getChatId())) {
            reply.getRuntimeMetadata().put("restart_requester_chat_id", routing.getChatId());
        }
        if (StrUtil.isNotBlank(routing.getUserId())) {
            reply.getRuntimeMetadata().put("restart_requester_user_id", routing.getUserId());
        }
        if (StrUtil.isNotBlank(routing.getChatType())) {
            reply.getRuntimeMetadata().put("restart_requester_chat_type", routing.getChatType());
        }
        if (StrUtil.isNotBlank(routing.getThreadId())) {
            reply.getRuntimeMetadata().put("restart_requester_thread_id", routing.getThreadId());
        }
    }

    /**
     * 执行Yolo相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Yolo结果。
     */
    private GatewayReply handleYolo(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        String action = StrUtil.nullToEmpty(args).trim().toLowerCase(java.util.Locale.ROOT);
        boolean enabled;
        if (StrUtil.isBlank(action)) {
            enabled = dangerousCommandApprovalService.toggleSessionYolo(agentSession);
        } else if ("status".equals(action) || "state".equals(action)) {
            enabled = dangerousCommandApprovalService.isSessionYoloEnabled(agentSession);
        } else if ("on".equals(action) || "enable".equals(action) || "enabled".equals(action)) {
            dangerousCommandApprovalService.enableSessionYolo(agentSession);
            enabled = true;
        } else if ("off".equals(action) || "disable".equals(action) || "disabled".equals(action)) {
            dangerousCommandApprovalService.disableSessionYolo(agentSession);
            enabled = false;
        } else {
            GatewayReply reply = GatewayReply.error("用法：/yolo [status|on|off]");
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }
        GatewayReply reply =
                GatewayReply.ok(
                        enabled
                                ? "YOLO 已开启：当前会话会自动批准可恢复的危险命令；硬阻断命令仍会被拒绝。"
                                : "YOLO 已关闭：当前会话恢复危险命令审批。");
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
        String action = firstToken(raw);
        if ("save".equalsIgnoreCase(action)) {
            String tail =
                    raw.length() <= action.length() ? "" : raw.substring(action.length()).trim();
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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
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
                || isCompressionCommand(command)
                || GatewayCommandConstants.COMMAND_ROLLBACK.equals(command);
    }

    /**
     * 判断是否压缩命令。
     *
     * @param command 待执行或解析的命令文本。
     * @return 如果压缩命令满足条件则返回 true，否则返回 false。
     */
    private boolean isCompressionCommand(String command) {
        return GatewayCommandConstants.COMMAND_COMPRESS.equals(command)
                || GatewayCommandConstants.COMMAND_COMPACT.equals(command);
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
        String action = firstToken(raw);
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
        String action = firstToken(args);
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
            return GatewayReply.error(
                    "用法：/approve [确认编号]、/approve always [确认编号]、/always 或 /cancel");
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
            return GatewayReply.ok(formatCheckpointClear(message.sourceKey()));
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
        } catch (Exception ignored) {
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
                    "\n回复 /approve [确认编号] 执行一次，/approve always [确认编号] 或 /always 执行并永久记住，/deny 或 /cancel 取消。");
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
        } catch (Exception ignored) {
            // 保留此处实现约束，避免后续维护时破坏既有行为。
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
            } catch (Exception ignored) {
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
        if (dashboardSkillsService == null) {
            return GatewayReply.error("工具集命令当前运行时未启用。");
        }
        List<Map<String, Object>> toolsets = dashboardSkillsService.getToolsets();
        GatewayReply reply = GatewayReply.ok(formatToolsets(toolsets));
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_TOOLSETS);
        reply.getRuntimeMetadata().put("toolset_count", Integer.valueOf(toolsets.size()));
        return reply;
    }

    /**
     * 格式化Toolsets。
     *
     * @param toolsets toolsets 参数。
     * @return 返回Toolsets结果。
     */
    @SuppressWarnings("unchecked")
    private String formatToolsets(List<Map<String, Object>> toolsets) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("工具集：total=").append(toolsets.size());
        for (Map<String, Object> toolset : toolsets) {
            List<Object> tools =
                    toolset.get("tools") instanceof List
                            ? (List<Object>) toolset.get("tools")
                            : Collections.<Object>emptyList();
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(toolset.get("name")), "-"))
                    .append(" enabled=")
                    .append(Boolean.TRUE.equals(toolset.get("enabled")))
                    .append(" tools=")
                    .append(tools.size())
                    .append(" - ")
                    .append(StrUtil.blankToDefault(String.valueOf(toolset.get("label")), "-"));
        }
        return buffer.toString();
    }

    /**
     * 执行浏览器相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回浏览器结果。
     */
    private GatewayReply handleBrowser(String args) {
        if (browserRuntimeService == null) {
            return GatewayReply.error("浏览器命令当前运行时未启用。");
        }
        String[] parts = StrUtil.nullToEmpty(args).trim().split("\\s+", 2);
        String action =
                parts.length == 0 || StrUtil.isBlank(parts[0])
                        ? GatewayCommandConstants.COMMAND_STATUS
                        : parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String target = parts.length > 1 ? parts[1].trim() : "";

        GatewayReply reply;
        if (GatewayCommandConstants.COMMAND_STATUS.equals(action)) {
            reply = GatewayReply.ok(formatBrowserStatus());
        } else if ("connect".equals(action)) {
            reply = browserReply(browserRuntimeService.create("slash-browser"), "浏览器会话已创建");
        } else if ("disconnect".equals(action) || "close".equals(action)) {
            if (StrUtil.isBlank(target)) {
                reply = GatewayReply.error(browserUsage());
            } else {
                reply = browserReply(browserRuntimeService.close(target), "浏览器会话已关闭");
            }
        } else {
            reply = GatewayReply.error(browserUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_BROWSER);
        reply.getRuntimeMetadata()
                .put(
                        "browser_active_sessions",
                        Integer.valueOf(browserRuntimeService.activeLeaseCount()));
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 执行浏览器回复相关逻辑。
     *
     * @param result 结果响应或执行结果。
     * @param successMessage success消息参数。
     * @return 返回浏览器Reply结果。
     */
    private GatewayReply browserReply(
            BrowserRuntimeService.BrowserResult result, String successMessage) {
        if (result == null) {
            return GatewayReply.error("浏览器运行时未返回结果。");
        }
        if (!result.isSuccess()) {
            BrowserRuntimeService.BrowserError error = result.getError();
            String code = error == null ? "browser_error" : error.getCode();
            String message = error == null ? "浏览器运行时执行失败" : error.getMessage();
            return GatewayReply.error("浏览器运行时失败：" + code + " - " + message);
        }
        StringBuilder buffer = new StringBuilder(successMessage);
        if (StrUtil.isNotBlank(result.getSessionId())) {
            buffer.append('\n').append("session_id=").append(result.getSessionId());
        }
        if (StrUtil.isNotBlank(result.getStatus())) {
            buffer.append('\n').append("status=").append(result.getStatus());
        }
        return GatewayReply.ok(buffer.toString());
    }

    /**
     * 格式化浏览器状态。
     *
     * @return 返回浏览器状态。
     */
    private String formatBrowserStatus() {
        return "浏览器运行时："
                + "\nactive_sessions="
                + browserRuntimeService.activeLeaseCount()
                + "\n"
                + browserUsage();
    }

    /**
     * 执行浏览器用量相关逻辑。
     *
     * @return 返回浏览器用量结果。
     */
    private String browserUsage() {
        return "用法："
                + GatewayCommandConstants.SLASH_BROWSER
                + " [status|connect|disconnect <session-id>]";
    }

    /**
     * 执行Debug相关逻辑。
     *
     * @return 返回Debug结果。
     */
    private GatewayReply handleDebug() throws Exception {
        DebugSummary summary = debugSummary();
        GatewayReply reply = GatewayReply.ok(formatDebugSummary(summary));
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_DEBUG);
        reply.getRuntimeMetadata()
                .put("debug_provider_count", Integer.valueOf(summary.providerCount));
        reply.getRuntimeMetadata()
                .put("debug_channel_count", Integer.valueOf(summary.channelCount));
        reply.getRuntimeMetadata().put("debug_tool_count", Integer.valueOf(summary.toolCount));
        reply.getRuntimeMetadata()
                .put("debug_session_count", Integer.valueOf(summary.sessionCount));
        reply.getRuntimeMetadata()
                .put(
                        "debug_connected_channel_count",
                        Integer.valueOf(summary.connectedChannelCount));
        return reply;
    }

    /**
     * 执行debug摘要相关逻辑。
     *
     * @return 返回debug Summary结果。
     */
    private DebugSummary debugSummary() throws Exception {
        DebugSummary summary = new DebugSummary();
        summary.runtimeHome = "runtime://";
        summary.providerCount =
                appConfig == null || appConfig.getProviders() == null
                        ? 0
                        : appConfig.getProviders().size();
        List<ChannelStatus> statuses =
                deliveryService == null
                        ? Collections.<ChannelStatus>emptyList()
                        : deliveryService.statuses();
        summary.channelCount = statuses.size();
        for (ChannelStatus status : statuses) {
            if (status == null) {
                continue;
            }
            if (status.isEnabled()) {
                summary.enabledChannelCount++;
            }
            if (status.isConnected()) {
                summary.connectedChannelCount++;
            }
        }
        summary.toolCount = toolRegistry == null ? 0 : toolRegistry.listToolNames().size();
        summary.sessionCount = sessionRepository == null ? 0 : sessionRepository.countAll();
        summary.mcpStatus =
                appConfig != null && appConfig.getMcp().isEnabled() ? "enabled" : "disabled";
        summary.approvalsMode =
                appConfig == null || appConfig.getApprovals() == null
                        ? ""
                        : StrUtil.nullToEmpty(appConfig.getApprovals().getMode());
        summary.securityProbesPassed = "not_run";
        return summary;
    }

    /**
     * 格式化Debug Summary。
     *
     * @param summary 摘要参数。
     * @return 返回Debug Summary结果。
     */
    private String formatDebugSummary(DebugSummary summary) {
        return "调试诊断："
                + "\nruntime_home="
                + summary.runtimeHome
                + "\nproviders="
                + summary.providerCount
                + "\nchannels="
                + summary.channelCount
                + " enabled="
                + summary.enabledChannelCount
                + " connected="
                + summary.connectedChannelCount
                + "\ntools="
                + summary.toolCount
                + "\nsessions="
                + summary.sessionCount
                + "\nmcp="
                + summary.mcpStatus
                + "\napprovals_mode="
                + summary.approvalsMode
                + "\nsecurity_probes_passed="
                + summary.securityProbesPassed
                + "\n"
                + debugUsage();
    }

    /**
     * 执行debug用量相关逻辑。
     *
     * @return 返回debug用量结果。
     */
    private String debugUsage() {
        return "用法：" + GatewayCommandConstants.SLASH_DEBUG + " [status]";
    }

    /** 承载Debug摘要相关状态和辅助逻辑。 */
    private static class DebugSummary {
        /** 记录Debug摘要中的运行时主渠道。 */
        private String runtimeHome;

        /** 记录Debug摘要中的提供方次数。 */
        private int providerCount;

        /** 记录Debug摘要中的渠道次数。 */
        private int channelCount;

        /** 标记是否启用渠道次数。 */
        private int enabledChannelCount;

        /** 记录Debug摘要中的connected渠道次数。 */
        private int connectedChannelCount;

        /** 记录Debug摘要中的工具次数。 */
        private int toolCount;

        /** 记录Debug摘要中的会话次数。 */
        private int sessionCount;

        /** 记录Debug摘要中的MCP状态。 */
        private String mcpStatus;

        /** 记录Debug摘要中的approvals模式。 */
        private String approvalsMode;

        /** 记录Debug摘要中的安全ProbesPassed。 */
        private String securityProbesPassed;
    }

    /** 执行技能命令相关逻辑。 */
    private GatewayReply handleSkills(GatewayMessage message, String args) throws Exception {
        String[] parts = args.split("\\s+", 2);
        String action =
                parts.length == 0 || StrUtil.isBlank(parts[0])
                        ? GatewayCommandConstants.ACTION_LIST
                        : parts[0];
        String target = parts.length > 1 ? parts[1].trim() : "";

        if (GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            return GatewayReply.ok("技能列表：" + localSkillService.listSkillNames());
        }
        if (GatewayCommandConstants.ACTION_BROWSE.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatBrowse(
                            skillHubService.browse(
                                    parseOption(target, "--source", "all"),
                                    parseIntOption(target, "--page", 1),
                                    parseIntOption(target, "--size", 20))));
        }
        if (GatewayCommandConstants.ACTION_SEARCH.equalsIgnoreCase(action)) {
            String query = stripOptions(target, "--source", "--limit");
            return GatewayReply.ok(
                    formatSearch(
                            skillHubService.search(
                                    query,
                                    parseOption(target, "--source", "all"),
                                    parseIntOption(target, "--limit", 10))));
        }
        if (GatewayCommandConstants.ACTION_INSTALL.equalsIgnoreCase(action)) {
            if (StrUtil.isBlank(target)) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_SKILLS
                                + " install <identifier> [--category <name>] [--force]");
            }
            String identifier = firstToken(target);
            String category = parseOption(target, "--category", null);
            boolean force = hasFlag(target, "--force");
            HubInstallRecord record = skillHubService.install(identifier, category, force);
            return GatewayReply.ok(
                    "已安装技能：" + record.getInstallPath() + " (" + record.getSource() + ")");
        }
        if (GatewayCommandConstants.ACTION_CHECK.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatHubInstallRecords(
                            skillHubService.check(StrUtil.blankToDefault(target, null))));
        }
        if (GatewayCommandConstants.ACTION_UPDATE.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatHubInstallRecords(
                            skillHubService.update(
                                    stripOptions(target, "--force"), hasFlag(target, "--force"))));
        }
        if (GatewayCommandConstants.ACTION_AUDIT.equalsIgnoreCase(action)) {
            return GatewayReply.ok(
                    formatAudit(skillHubService.audit(StrUtil.blankToDefault(target, null))));
        }
        if (GatewayCommandConstants.ACTION_UNINSTALL.equalsIgnoreCase(action)) {
            if (StrUtil.isBlank(target)) {
                return GatewayReply.error(
                        "用法：" + GatewayCommandConstants.SLASH_SKILLS + " uninstall <name>");
            }
            return GatewayReply.ok(skillHubService.uninstall(firstToken(target)));
        }
        if (GatewayCommandConstants.ACTION_TAP.equalsIgnoreCase(action)) {
            return GatewayReply.ok(handleTap(target));
        }
        if (GatewayCommandConstants.ACTION_ENABLE.equalsIgnoreCase(action)) {
            localSkillService.enable(message.sourceKey(), target);
            return GatewayReply.ok("已启用技能：" + target);
        }
        if (GatewayCommandConstants.ACTION_DISABLE.equalsIgnoreCase(action)) {
            localSkillService.disable(message.sourceKey(), target);
            return GatewayReply.ok("已禁用技能：" + target);
        }
        if (GatewayCommandConstants.ACTION_INSPECT.equalsIgnoreCase(action)) {
            return GatewayReply.ok(localSkillService.inspect(target));
        }
        if (GatewayCommandConstants.ACTION_RELOAD.equalsIgnoreCase(action)) {
            return handleReloadSkills();
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_SKILLS
                        + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload] ...");
    }

    /**
     * 执行Reload技能相关逻辑。
     *
     * @return 返回Reload技能结果。
     */
    private GatewayReply handleReloadSkills() throws Exception {
        List<String> names = new ArrayList<String>(localSkillService.listSkillNames());
        Collections.sort(names);
        StringBuilder buffer = new StringBuilder();
        buffer.append("已重新加载本地技能，共 ").append(names.size()).append(" 个");
        if (!names.isEmpty()) {
            buffer.append("：").append(String.join(", ", names));
        }
        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_RELOAD_SKILLS);
        reply.getRuntimeMetadata().put("skill_count", Integer.valueOf(names.size()));
        return reply;
    }

    /**
     * 执行技能维护相关逻辑。
     *
     * @param args 工具或命令参数。
     * @return 返回技能维护结果。
     */
    private GatewayReply handleCurator(String args) throws Exception {
        if (dashboardCuratorService == null) {
            return GatewayReply.error("技能后台维护命令当前运行时未启用。");
        }
        String[] parts = StrUtil.nullToEmpty(args).trim().split("\\s+", 2);
        String action =
                parts.length == 0 || StrUtil.isBlank(parts[0])
                        ? "status"
                        : parts[0].trim().toLowerCase();
        String tail = parts.length > 1 ? parts[1].trim() : "";
        GatewayReply reply;
        if ("status".equals(action)) {
            reply = GatewayReply.ok(formatCuratorStatus(dashboardCuratorService.status()));
        } else if (GatewayCommandConstants.ACTION_LIST.equals(action)) {
            reply =
                    GatewayReply.ok(
                            formatCuratorReports(
                                    dashboardCuratorService.list(parsePositiveInt(tail, 20))));
        } else if ("improvements".equals(action)) {
            reply =
                    GatewayReply.ok(
                            formatCuratorImprovements(
                                    dashboardCuratorService.improvements(
                                            parsePositiveInt(tail, 20))));
        } else if (GatewayCommandConstants.ACTION_RUN.equals(action)) {
            boolean force =
                    StrUtil.isBlank(tail)
                            || "force".equalsIgnoreCase(tail)
                            || "--force".equalsIgnoreCase(tail);
            reply = GatewayReply.ok(formatCuratorRun(dashboardCuratorService.run(force)));
        } else if (GatewayCommandConstants.ACTION_PAUSE.equals(action)) {
            reply =
                    GatewayReply.ok(
                            "技能后台维护已暂停。\n" + formatCuratorStatus(dashboardCuratorService.pause()));
        } else if (GatewayCommandConstants.ACTION_RESUME.equals(action)) {
            reply =
                    GatewayReply.ok(
                            "技能后台维护已恢复。\n" + formatCuratorStatus(dashboardCuratorService.resume()));
        } else {
            return GatewayReply.error(curatorUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_CURATOR);
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 格式化技能维护状态。
     *
     * @param status 状态参数。
     * @return 返回技能维护状态。
     */
    private String formatCuratorStatus(Map<String, Object> status) throws Exception {
        int reports = countList(dashboardCuratorService.list(20).get("reports"));
        int improvements = countList(dashboardCuratorService.improvements(20).get("improvements"));
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能后台维护状态：\n");
        buffer.append("curator_enabled=").append(bool(status.get("enabled"))).append('\n');
        buffer.append("paused=").append(bool(status.get("paused"))).append('\n');
        buffer.append("last_run_at=")
                .append(formatNullableTimestamp(status.get("lastRunAt")))
                .append('\n');
        buffer.append("tracked_skills=").append(status.get("trackedSkills")).append('\n');
        buffer.append("reports=").append(reports).append('\n');
        buffer.append("improvements=").append(improvements).append('\n');
        buffer.append("interval_hours=").append(status.get("intervalHours")).append('\n');
        buffer.append("stale_after_days=").append(status.get("staleAfterDays")).append('\n');
        buffer.append("archive_after_days=").append(status.get("archiveAfterDays")).append('\n');
        buffer.append(curatorUsage());
        return buffer.toString();
    }

    /**
     * 格式化技能维护运行。
     *
     * @param report report 参数。
     * @return 返回技能维护运行结果。
     */
    @SuppressWarnings("unchecked")
    private String formatCuratorRun(Map<String, Object> report) {
        List<Map<String, Object>> items =
                report.get("items") instanceof List
                        ? (List<Map<String, Object>>) report.get("items")
                        : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能维护运行 status=")
                .append(StrUtil.blankToDefault(String.valueOf(report.get("status")), "unknown"))
                .append(" items=")
                .append(items.size())
                .append(" state=")
                .append(StrUtil.blankToDefault(String.valueOf(report.get("stateFile")), "-"));
        appendCuratorItems(buffer, items);
        return buffer.toString();
    }

    /**
     * 格式化技能维护Reports。
     *
     * @param result 结果响应或执行结果。
     * @return 返回技能维护Reports结果。
     */
    @SuppressWarnings("unchecked")
    private String formatCuratorReports(Map<String, Object> result) {
        List<Map<String, Object>> reports =
                result.get("reports") instanceof List
                        ? (List<Map<String, Object>>) result.get("reports")
                        : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能维护报告：");
        if (reports.isEmpty()) {
            buffer.append("暂无报告");
            return buffer.toString();
        }
        for (Map<String, Object> report : reports) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(report.get("report_id")), "-"))
                    .append(" status=")
                    .append(StrUtil.blankToDefault(String.valueOf(report.get("status")), "unknown"))
                    .append(" summary=")
                    .append(StrUtil.blankToDefault(String.valueOf(report.get("summary")), "-"))
                    .append(" started=")
                    .append(formatNullableTimestamp(report.get("started_at")));
        }
        return buffer.toString();
    }

    /**
     * 格式化技能维护Improvements。
     *
     * @param result 结果响应或执行结果。
     * @return 返回技能维护Improvements结果。
     */
    @SuppressWarnings("unchecked")
    private String formatCuratorImprovements(Map<String, Object> result) {
        List<Map<String, Object>> improvements =
                result.get("improvements") instanceof List
                        ? (List<Map<String, Object>>) result.get("improvements")
                        : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder();
        buffer.append("技能改进记录：");
        if (improvements.isEmpty()) {
            buffer.append("暂无记录");
            return buffer.toString();
        }
        for (Map<String, Object> item : improvements) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("skill_name")), "-"))
                    .append(" action=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("action")), "-"))
                    .append(" review=")
                    .append(Boolean.TRUE.equals(item.get("needs_review")))
                    .append(" summary=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("summary")), "-"));
        }
        return buffer.toString();
    }

    /**
     * 追加技能维护Items。
     *
     * @param buffer buffer 参数。
     * @param items items 参数。
     */
    private void appendCuratorItems(StringBuilder buffer, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("name")), "-"))
                    .append(" status=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("status")), "-"))
                    .append(" action=")
                    .append(StrUtil.blankToDefault(String.valueOf(item.get("action")), "-"));
        }
    }

    /**
     * 执行技能维护用量相关逻辑。
     *
     * @return 返回技能维护用量结果。
     */
    private String curatorUsage() {
        return "用法："
                + GatewayCommandConstants.SLASH_CURATOR
                + " [status|list|improvements|run|pause|resume]";
    }

    /**
     * 执行次数列表相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回次数List结果。
     */
    private int countList(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    /**
     * 执行bool相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回bool结果。
     */
    private String bool(Object value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    /**
     * 格式化Nullable时间戳。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Nullable时间戳结果。
     */
    private String formatNullableTimestamp(Object value) {
        long millis = 0L;
        if (value instanceof Number) {
            millis = ((Number) value).longValue();
        } else {
            try {
                millis = Long.parseLong(String.valueOf(value));
            } catch (Exception ignored) {
                millis = 0L;
            }
        }
        return millis <= 0L ? "-" : formatTimestamp(millis);
    }

    /**
     * 执行Plugins相关逻辑。
     *
     * @return 返回Plugins结果。
     */
    private GatewayReply handlePlugins() {
        List<AgentPluginManifest> plugins =
                pluginManager == null
                        ? Collections.<AgentPluginManifest>emptyList()
                        : pluginManager.listPlugins();
        List<PluginLoadDiagnostic> diagnostics =
                pluginManager == null
                        ? Collections.<PluginLoadDiagnostic>emptyList()
                        : pluginManager.diagnostics();
        int loaded = 0;
        int skipped = 0;
        int failed = 0;
        for (PluginLoadDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.getStatus() == null) {
                continue;
            }
            if (PluginLoadStatus.LOADED == diagnostic.getStatus()) {
                loaded++;
            } else if (PluginLoadStatus.SKIPPED == diagnostic.getStatus()) {
                skipped++;
            } else if (PluginLoadStatus.FAILED == diagnostic.getStatus()) {
                failed++;
            }
        }
        if (loaded == 0 && !plugins.isEmpty()) {
            loaded = plugins.size();
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append("插件状态 loaded=")
                .append(loaded)
                .append(" skipped=")
                .append(skipped)
                .append(" failed=")
                .append(failed);
        if (plugins.isEmpty() && diagnostics.isEmpty()) {
            buffer.append('\n').append("未发现已加载插件。");
        }
        for (AgentPluginManifest manifest : plugins) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(manifest.getName(), "-"))
                    .append(" loaded");
            if (StrUtil.isNotBlank(manifest.getKind())) {
                buffer.append(" kind=").append(manifest.getKind());
            }
            if (StrUtil.isNotBlank(manifest.getVersion())) {
                buffer.append(" version=").append(manifest.getVersion());
            }
            if (StrUtil.isNotBlank(manifest.getDescription())) {
                buffer.append(" - ").append(manifest.getDescription());
            }
        }
        for (PluginLoadDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || PluginLoadStatus.LOADED == diagnostic.getStatus()) {
                continue;
            }
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(diagnostic.getPluginName(), "-"))
                    .append(' ')
                    .append(String.valueOf(diagnostic.getStatus()).toLowerCase())
                    .append(" reason=")
                    .append(StrUtil.blankToDefault(diagnostic.getReason(), "-"));
        }

        GatewayReply reply = GatewayReply.ok(buffer.toString());
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_PLUGINS);
        reply.getRuntimeMetadata().put("plugin_loaded", Integer.valueOf(loaded));
        reply.getRuntimeMetadata().put("plugin_skipped", Integer.valueOf(skipped));
        reply.getRuntimeMetadata().put("plugin_failed", Integer.valueOf(failed));
        return reply;
    }

    /** 执行人格命令相关逻辑。 */
    private GatewayReply handlePersonality(String args) throws Exception {
        Map<String, AppConfig.PersonalityConfig> personalities =
                appConfig.getAgent().getPersonalities();
        if (personalities == null || personalities.isEmpty()) {
            return GatewayReply.error("当前没有可用的人格配置。");
        }
        if (StrUtil.isBlank(args)) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("可用人格：\n");
            buffer.append("- none: 清除人格覆盖\n");
            for (Map.Entry<String, AppConfig.PersonalityConfig> entry : personalities.entrySet()) {
                String description =
                        entry.getValue() == null
                                ? ""
                                : StrUtil.blankToDefault(entry.getValue().getDescription(), "无描述");
                buffer.append("- ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(description)
                        .append('\n');
            }
            buffer.append("当前激活：").append(currentPersonalityName());
            return GatewayReply.ok(buffer.toString().trim());
        }

        if ("none".equalsIgnoreCase(args)
                || "default".equalsIgnoreCase(args)
                || "neutral".equalsIgnoreCase(args)) {
            globalSettingRepository.remove(AgentSettingConstants.ACTIVE_PERSONALITY);
            return GatewayReply.ok("已清除人格覆盖，下一条消息恢复默认行为。");
        }

        String matchedName = null;
        for (String name : personalities.keySet()) {
            if (name.equalsIgnoreCase(args)) {
                matchedName = name;
                break;
            }
        }
        if (matchedName == null) {
            return GatewayReply.error("未知人格：" + args);
        }
        globalSettingRepository.set(AgentSettingConstants.ACTIVE_PERSONALITY, matchedName);
        return GatewayReply.ok("已切换人格为：" + matchedName + "，将从下一条消息开始生效。");
    }

    /**
     * 执行主动协作命令相关逻辑；命令只改变控制面设置或候选状态，不触发调度、投递或工具执行。
     *
     * @param args 工具或命令参数。
     * @return 返回主动协作命令结果。
     */
    private GatewayReply handleProactive(String args) throws Exception {
        String[] parts = StrUtil.nullToEmpty(args).trim().split("\\s+", 2);
        String action =
                parts.length == 0 || StrUtil.isBlank(parts[0])
                        ? "status"
                        : parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String tail = parts.length > 1 ? parts[1].trim() : "";
        GatewayReply reply;
        if ("status".equals(action) || "state".equals(action)) {
            reply = GatewayReply.ok(proactiveStatusText());
        } else if (GatewayCommandConstants.ACTION_PAUSE.equals(action)
                || "off".equals(action)
                || "disable".equals(action)) {
            setProactiveSetting("proactive.enabled", "false");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(false);
            }
            reply = GatewayReply.ok("已暂停主动协作。后续不会主动联系，直到使用 /proactive resume。");
        } else if (GatewayCommandConstants.ACTION_RESUME.equals(action)
                || "on".equals(action)
                || "enable".equals(action)) {
            setProactiveSetting("proactive.enabled", "true");
            if (appConfig != null && appConfig.getProactive() != null) {
                appConfig.getProactive().setEnabled(true);
            }
            reply = GatewayReply.ok("已恢复主动协作。系统仍会遵守免打扰、冷却和每日上限。");
        } else if ("why".equals(action)) {
            reply = GatewayReply.ok(proactiveWhyText());
        } else if ("less".equals(action)) {
            int cooldown =
                    Math.min(
                            24 * 60,
                            Math.max(
                                            30,
                                            appConfig.getProactive().getCooldownMinutes())
                                    + 60);
            int dailyMax = Math.max(1, appConfig.getProactive().getDailyMaxContacts() - 1);
            setProactiveSetting("proactive.cooldownMinutes", String.valueOf(cooldown));
            setProactiveSetting("proactive.dailyMaxContacts", String.valueOf(dailyMax));
            appConfig.getProactive().setCooldownMinutes(cooldown);
            appConfig.getProactive().setDailyMaxContacts(dailyMax);
            reply =
                    GatewayReply.ok(
                            "已降低主动联系频率：冷却时间 "
                                    + cooldown
                                    + " 分钟，每日最多 "
                                    + dailyMax
                                    + " 次。");
        } else if ("more".equals(action)) {
            int cooldown = Math.max(15, appConfig.getProactive().getCooldownMinutes() - 60);
            int dailyMax = Math.min(12, appConfig.getProactive().getDailyMaxContacts() + 1);
            setProactiveSetting("proactive.cooldownMinutes", String.valueOf(cooldown));
            setProactiveSetting("proactive.dailyMaxContacts", String.valueOf(dailyMax));
            appConfig.getProactive().setCooldownMinutes(cooldown);
            appConfig.getProactive().setDailyMaxContacts(dailyMax);
            reply =
                    GatewayReply.ok(
                            "已提高主动联系频率：冷却时间 "
                                    + cooldown
                                    + " 分钟，每日最多 "
                                    + dailyMax
                                    + " 次。");
        } else if ("ignore".equals(action)) {
            reply = GatewayReply.ok(ignoreProactiveCandidate(tail));
        } else {
            reply = GatewayReply.error(proactiveUsage());
        }
        reply.getRuntimeMetadata().put("command_status", "handled");
        reply.getRuntimeMetadata().put("command", GatewayCommandConstants.COMMAND_PROACTIVE);
        reply.getRuntimeMetadata().put("action", action);
        return reply;
    }

    /**
     * 生成主动协作状态文本。
     *
     * @return 主动协作状态文本。
     */
    private String proactiveStatusText() {
        if (proactiveDiagnosticsService != null) {
            return proactiveDiagnosticsService.statusLine();
        }
        AppConfig.ProactiveConfig config = appConfig.getProactive();
        return "主动协作"
                + (config.isEnabled() ? "已启用" : "已暂停")
                + "，检查间隔 "
                + config.getIntervalMinutes()
                + " 分钟，每日最多 "
                + config.getDailyMaxContacts()
                + " 次。";
    }

    /**
     * 生成最近一次主动协作决策解释。
     *
     * @return 决策解释文本。
     */
    private String proactiveWhyText() {
        if (proactiveDiagnosticsService == null) {
            return "主动协作诊断服务尚未启用。";
        }
        com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord decision =
                proactiveDiagnosticsService.latestDecision();
        if (decision == null) {
            return "暂无主动协作决策记录。可以在 Dashboard 诊断里检查 home channel、免打扰和候选生成状态。";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("最近一次主动协作决策：")
                .append(StrUtil.blankToDefault(decision.getDecision(), "-"));
        if (StrUtil.isNotBlank(decision.getReason())) {
            buffer.append("\n原因：").append(SecretRedactor.redact(decision.getReason(), 800));
        }
        if (StrUtil.isNotBlank(decision.getDeliveryStatus())) {
            buffer.append("\n投递状态：").append(decision.getDeliveryStatus());
        }
        if (StrUtil.isNotBlank(decision.getDeliveryError())) {
            buffer.append("\n投递错误：").append(SecretRedactor.redact(decision.getDeliveryError(), 500));
        }
        buffer.append("\n时间：").append(formatTimestamp(decision.getCreatedAt()));
        return buffer.toString();
    }

    /**
     * 忽略指定主动协作候选。
     *
     * @param candidateId 候选 ID。
     * @return 用户可见结果。
     */
    private String ignoreProactiveCandidate(String candidateId) throws Exception {
        if (proactiveRepository == null) {
            return "主动协作仓储尚未启用，无法忽略候选。";
        }
        if (StrUtil.isBlank(candidateId)) {
            return proactiveUsage();
        }
        proactiveRepository.markCandidateStatus(
                candidateId.trim(), "IGNORED", "user-command", System.currentTimeMillis());
        return "已忽略主动协作候选：" + candidateId.trim();
    }

    /**
     * 写入主动协作运行时设置覆盖。
     *
     * @param key 设置键。
     * @param value 设置值。
     */
    private void setProactiveSetting(String key, String value) throws Exception {
        if (globalSettingRepository != null) {
            globalSettingRepository.set(key, value);
        }
    }

    /**
     * 生成主动协作命令用法文本。
     *
     * @return 用法文本。
     */
    private String proactiveUsage() {
        return "用法：/proactive status|pause|resume|why|less|more|ignore <candidateId>";
    }

    /** 执行定时任务命令相关逻辑。 */
    private GatewayReply handleCron(GatewayMessage message, String args) throws Exception {
        boolean overview = StrUtil.isBlank(args);
        String[] parts = args.split("\\s+", 2);
        String action =
                parts.length == 0 || StrUtil.isBlank(parts[0])
                        ? GatewayCommandConstants.ACTION_LIST
                        : parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String tail = parts.length > 1 ? parts[1] : "";
        String runTriggerType = "manual";
        if (GatewayCommandConstants.ACTION_ADD.equals(action)) {
            action = GatewayCommandConstants.ACTION_CREATE;
        }
        if ("edit".equals(action)) {
            action = GatewayCommandConstants.ACTION_UPDATE;
        }
        if ("rm".equals(action)
                || GatewayCommandConstants.ACTION_REMOVE.equals(action)
                || GatewayCommandConstants.ACTION_DELETE.equals(action)) {
            action = GatewayCommandConstants.ACTION_DELETE;
        }
        if ("disable".equals(action) || "stop".equals(action)) {
            action = GatewayCommandConstants.ACTION_PAUSE;
        }
        if ("enable".equals(action) || "start".equals(action)) {
            action = GatewayCommandConstants.ACTION_RESUME;
        }
        if ("retry".equals(action) || "rerun".equals(action)) {
            runTriggerType = "retry";
            action = GatewayCommandConstants.ACTION_RUN;
        }
        if ("trigger".equals(action)) {
            action = GatewayCommandConstants.ACTION_RUN;
        }
        if ("upcoming".equals(action)) {
            action = "next";
        }

        if (GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            List<CronJobRecord> jobs = cronJobService.listAll(options.all);
            String listText = formatCronList(jobs);
            return GatewayReply.ok(overview ? cronOverview(listText) : listText);
        }

        if ("status".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            return GatewayReply.ok(formatCronStatus(message.sourceKey(), options.all));
        }

        if ("next".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            int limit = options.limit == null ? 5 : options.limit.intValue();
            return GatewayReply.ok(formatCronNext(message.sourceKey(), options.all, limit));
        }

        if ("guide".equals(action)
                || "tutorial".equals(action)
                || "capabilities".equals(action)
                || "policy".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            Map<String, Object> guide = cronJobService.guide();
            return GatewayReply.ok(options.json ? ONode.serialize(guide) : formatCronGuide(guide));
        }

        if ("tick".equals(action)) {
            if (cronScheduler == null) {
                return GatewayReply.error("当前运行环境未启用 Cron scheduler，无法手动 tick。");
            }
            cronScheduler.tick();
            return GatewayReply.ok(formatCronStatus(message.sourceKey(), true));
        }

        if ("history".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " history <job-id> [--limit 20]");
            }
            int limit = options.limit == null ? 20 : options.limit.intValue();
            List<CronJobRunRecord> runs = cronJobService.history(options.positionals.get(0), limit);
            return GatewayReply.ok(formatCronHistory(options.positionals.get(0), runs));
        }

        if (GatewayCommandConstants.ACTION_INSPECT.equalsIgnoreCase(action)
                || "show".equals(action)
                || "detail".equals(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法：" + GatewayCommandConstants.SLASH_CRON + " inspect <job-id>");
            }
            String jobId = options.positionals.get(0);
            CronJobRecord job = cronJobService.require(jobId);
            return GatewayReply.ok(formatCronDetail(job));
        }

        if (GatewayCommandConstants.ACTION_CREATE.equalsIgnoreCase(action)) {
            Map<String, Object> body = parseCronCreate(tail);
            if (body == null) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " add <name>|<schedule>|<prompt>|[--skill a,b] 或 "
                                + GatewayCommandConstants.SLASH_CRON
                                + " add \"every 2h\" \"Check server\" [--skill blogwatcher]");
            }

            if (!body.containsKey("deliver")) {
                body.put("deliver", "origin");
            }
            body.put("origin", cronOrigin(message));
            CronJobRecord job = cronJobService.create(message.sourceKey(), body);
            return GatewayReply.ok("已创建定时任务：" + job.getJobId());
        }

        if (GatewayCommandConstants.ACTION_PAUSE.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " pause|disable|stop <job-id> [--reason 原因]");
            }
            String jobId = options.positionals.get(0);
            String reason =
                    StrUtil.blankToDefault(options.reason, joinTail(options.positionals, 1));
            cronJobService.pause(jobId, StrUtil.blankToDefault(reason, "paused by slash command"));
            return GatewayReply.ok("已暂停定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_RESUME.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " resume|enable|start <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.resume(jobId);
            return GatewayReply.ok("已恢复定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_DELETE.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法：" + GatewayCommandConstants.SLASH_CRON + " remove <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.remove(jobId);
            return GatewayReply.ok("已删除定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_UPDATE.equalsIgnoreCase(action)) {
            CronEditRequest edit = parseCronEdit(tail);
            if (edit == null) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " edit <job-id> [--schedule ...] [--prompt ...] [--skill ...|--add-skill ...|--remove-skill ...|--clear-skills] [--clear-script|--clear-workdir|--clear-context-from|--clear-toolsets]");
            }
            CronJobRecord job = cronJobService.update(edit.jobId, edit.body);
            return GatewayReply.ok("已更新定时任务：" + job.getJobId());
        }

        if (GatewayCommandConstants.ACTION_RUN.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_CRON
                                + " run|trigger|retry|rerun <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.require(jobId);
            runTriggerType = cronRunTriggerType(options, runTriggerType);
            if (cronScheduler == null) {
                cronJobService.trigger(jobId, runTriggerType);
                return GatewayReply.ok("已标记定时任务将在下一次 tick 执行：" + jobId);
            }
            cronScheduler.runNow(jobId, runTriggerType);
            return GatewayReply.ok("已执行定时任务：" + jobId);
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_CRON
                        + " [list [--all]|inspect|show|next|upcoming|guide|tutorial|capabilities|policy|add|edit|pause|disable|stop|resume|enable|start|remove|delete|run|trigger|retry|rerun|history|status|tick]");
    }

    /**
     * 执行定时任务运行Trigger类型相关逻辑。
     *
     * @param options options 参数。
     * @param fallback 兜底参数。
     * @return 返回定时任务运行Trigger类型结果。
     */
    private String cronRunTriggerType(CronFlagOptions options, String fallback) {
        String value =
                options == null
                        ? null
                        : StrUtil.blankToDefault(options.triggerType, options.reason);
        if (StrUtil.isBlank(value)) {
            return fallback;
        }
        String normalized = normalizeCronTriggerType(value, fallback);
        if ("scheduled".equals(normalized)) {
            return fallback;
        }
        if ("retry".equals(fallback) && "manual".equals(normalized)) {
            return "retry";
        }
        return normalized;
    }

    /**
     * 规范化定时任务Trigger类型。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallback 兜底参数。
     * @return 返回定时任务Trigger类型结果。
     */
    private String normalizeCronTriggerType(String value, String fallback) {
        return cronJobService.normalizeTriggerType(value, fallback);
    }

    /**
     * 格式化定时任务Guide。
     *
     * @param guide guide标识或键值。
     * @return 返回定时任务Guide结果。
     */
    private String formatCronGuide(Map<String, Object> guide) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 自动化指南");
        buffer.append('\n').append("目标：").append(guide.get("objective"));
        buffer.append('\n').append("调度类型：").append(joinGuideList(guide.get("schedule_types")));
        buffer.append('\n').append("可编辑字段：").append(joinGuideList(guide.get("editable_fields")));
        buffer.append('\n').append("动作：").append(joinGuideMapKeys(guide.get("actions")));
        buffer.append('\n').append("动作语法：");
        appendGuideMap(buffer, guide.get("action_syntax"));
        buffer.append('\n').append("别名：");
        appendGuideMap(buffer, guide.get("aliases"));
        buffer.append('\n').append("技能绑定：");
        appendGuideMap(buffer, guide.get("skill_binding"));
        buffer.append('\n').append("投递策略：");
        appendGuideMap(buffer, guide.get("delivery"));
        buffer.append('\n').append("运行模式：");
        appendGuideMap(buffer, guide.get("runtime_modes"));
        buffer.append('\n').append("历史与状态：");
        appendGuideMap(buffer, guide.get("history_and_status"));
        buffer.append('\n').append("安全策略：");
        appendGuideMap(buffer, guide.get("security"));
        buffer.append('\n').append("示例：");
        appendGuideListLines(buffer, guide.get("slash_examples"));
        return buffer.toString();
    }

    /**
     * 追加Guide Map。
     *
     * @param buffer buffer 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendGuideMap(StringBuilder buffer, Object value) {
        if (!(value instanceof Map)) {
            buffer.append(' ').append(String.valueOf(value));
            return;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            buffer.append('\n')
                    .append("- ")
                    .append(String.valueOf(entry.getKey()))
                    .append(": ")
                    .append(joinGuideValue(entry.getValue()));
        }
    }

    /**
     * 追加Guide List Lines。
     *
     * @param buffer buffer 参数。
     * @param value 待规范化或校验的原始值。
     */
    private void appendGuideListLines(StringBuilder buffer, Object value) {
        if (!(value instanceof Iterable)) {
            buffer.append('\n').append("- ").append(String.valueOf(value));
            return;
        }
        for (Object item : (Iterable<?>) value) {
            buffer.append('\n').append("- ").append(String.valueOf(item));
        }
    }

    /**
     * 执行joinGuide映射Keys相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回join Guide Map Keys结果。
     */
    private String joinGuideMapKeys(Object value) {
        if (!(value instanceof Map)) {
            return String.valueOf(value);
        }
        StringBuilder buffer = new StringBuilder();
        for (Object key : ((Map<?, ?>) value).keySet()) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(String.valueOf(key));
        }
        return buffer.toString();
    }

    /**
     * 执行joinGuide列表相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回join Guide List结果。
     */
    private String joinGuideList(Object value) {
        if (!(value instanceof Iterable)) {
            return String.valueOf(value);
        }
        StringBuilder buffer = new StringBuilder();
        for (Object item : (Iterable<?>) value) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(String.valueOf(item));
        }
        return buffer.toString();
    }

    /**
     * 执行joinGuide值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回join Guide Value结果。
     */
    private String joinGuideValue(Object value) {
        if (value instanceof Iterable) {
            return joinGuideList(value);
        }
        if (value instanceof Map) {
            return joinGuideMapKeys(value);
        }
        return String.valueOf(value);
    }

    /**
     * 执行定时任务Overview相关逻辑。
     *
     * @param listText 列表文本参数。
     * @return 返回定时任务Overview结果。
     */
    private String cronOverview(String listText) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 定时任务\n")
                .append("命令：\n")
                .append("/cron list - 查看启用中的定时任务\n")
                .append("/cron list --all - 查看全部定时任务，包括已暂停任务\n")
                .append("/cron inspect <job-id> - 查看单个任务详情\n")
                .append("/cron next [--all] [--limit 5] - 查看即将运行的任务\n")
                .append("/cron upcoming [--all] [--limit 5] - next 的别名\n")
                .append("/cron guide [--json] - 查看自动化能力、字段、别名、投递、技能绑定和安全策略\n")
                .append("/cron status [--all] - 查看任务计数、到期任务、最近失败与下次运行\n")
                .append(
                        "/cron add \"every 2h\" \"Check server status\" [--skill blogwatcher] - 创建定时任务\n")
                .append(
                        "/cron edit <job-id> --schedule \"every 4h\" --prompt \"New task\" - 编辑定时任务\n")
                .append("/cron edit <job-id> --skill blogwatcher --skill maps - 替换绑定技能\n")
                .append("/cron edit <job-id> --remove-skill blogwatcher - 移除绑定技能\n")
                .append("/cron edit <job-id> --clear-skills - 清空绑定技能\n")
                .append("/cron edit <job-id> --clear-repeat - 清空重复次数上限，恢复无限重复\n")
                .append(
                        "/cron edit <job-id> --clear-script --clear-workdir --clear-context-from --clear-toolsets - 清空脚本、工作目录、上下文链和工具集限制\n")
                .append(
                        "/cron add \"every 2h\" \"task\" --model gpt-5.4 --provider default --base-url https://api.openai.com --no-wrap-response - 固定模型与投递包装\n")
                .append(
                        "/cron add \"every 2h\" \"task\" --deliver feishu --deliver-chat-id chat --deliver-thread-id thread - 指定投递会话与线程\n")
                .append(
                        "/cron edit <job-id> --clear-deliver-chat-id --clear-deliver-thread-id - 清空投递会话与线程\n")
                .append(
                        "/cron edit <job-id> --clear-model --clear-provider --clear-base-url - 清空任务级模型/provider/base URL 固定值\n")
                .append(
                        "/cron edit <job-id> --no-agent|--agent --wrap-response|--no-wrap-response - 切换脚本直投与回复包装\n")
                .append("/cron pause|disable|stop <job-id> [--reason 原因] - 暂停定时任务\n")
                .append("/cron resume|enable|start <job-id> - 恢复定时任务\n")
                .append("/cron run <job-id> - 立即触发定时任务\n")
                .append("/cron trigger <job-id> - run 的别名\n")
                .append("/cron retry <job-id> - 重跑最近失败或需要复核的定时任务\n")
                .append("/cron tick - 立即执行一次 scheduler tick\n")
                .append("/cron history <job-id> [--limit 20] - 查看执行历史\n")
                .append("/cron remove <job-id> - 删除定时任务\n")
                .append('\n')
                .append(listText);
        return buffer.toString();
    }

    /**
     * 格式化定时任务状态。
     *
     * @param sourceKey 渠道来源键。
     * @param all all 参数。
     * @return 返回定时任务状态。
     */
    private String formatCronStatus(String sourceKey, boolean all) throws Exception {
        List<CronJobRecord> jobs = cronJobService.listAll(true);
        long now = System.currentTimeMillis();
        int active = 0;
        int paused = 0;
        int completed = 0;
        int due = 0;
        int failed = 0;
        int deliveryErrors = 0;
        long nextRunAt = 0L;
        CronJobRecord nextJob = null;
        List<CronJobRecord> recentProblems = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            String state = cronState(job);
            if ("paused".equals(state)) {
                paused++;
            } else if ("completed".equals(state)) {
                completed++;
            } else {
                active++;
                if (job.getNextRunAt() > 0L && job.getNextRunAt() <= now) {
                    due++;
                }
                if (job.getNextRunAt() > 0L
                        && (nextRunAt <= 0L || job.getNextRunAt() < nextRunAt)) {
                    nextRunAt = job.getNextRunAt();
                    nextJob = job;
                }
            }
            if (StrUtil.isNotBlank(job.getLastStatus())
                    && !"ok".equalsIgnoreCase(job.getLastStatus())) {
                failed++;
                addRecentProblem(recentProblems, job);
            }
            if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                deliveryErrors++;
                addRecentProblem(recentProblems, job);
            }
        }

        StringBuilder buffer = new StringBuilder("Cron 状态");
        buffer.append('\n').append("范围：全部任务");
        buffer.append('\n').append("总数：").append(jobs.size());
        buffer.append('\n')
                .append("状态：active=")
                .append(active)
                .append(", paused=")
                .append(paused)
                .append(", completed=")
                .append(completed);
        buffer.append('\n').append("已到期：").append(due);
        buffer.append('\n').append("最近失败：").append(failed);
        buffer.append('\n').append("最近投递错误：").append(deliveryErrors);
        if (nextJob == null) {
            buffer.append('\n').append("下次运行：N/A");
        } else {
            buffer.append('\n')
                    .append("下次运行：")
                    .append(formatTimestamp(nextRunAt))
                    .append(" ")
                    .append(nextJob.getJobId())
                    .append(" ")
                    .append(StrUtil.blankToDefault(nextJob.getName(), ""));
        }
        if (!recentProblems.isEmpty()) {
            buffer.append('\n').append("问题任务：");
            for (CronJobRecord job : recentProblems) {
                buffer.append('\n')
                        .append("- ")
                        .append(job.getJobId())
                        .append(" ")
                        .append(StrUtil.blankToDefault(job.getName(), ""))
                        .append(" status=")
                        .append(StrUtil.blankToDefault(job.getLastStatus(), "ok"));
                if (StrUtil.isNotBlank(job.getLastError())) {
                    buffer.append(" error=").append(safeCronText(job.getLastError(), 120));
                }
                if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                    buffer.append(" delivery=")
                            .append(safeCronText(job.getLastDeliveryError(), 120));
                }
            }
        }
        return buffer.toString();
    }

    /**
     * 格式化定时任务Next。
     *
     * @param sourceKey 渠道来源键。
     * @param all all 参数。
     * @param limit 最大返回数量。
     * @return 返回定时任务Next结果。
     */
    private String formatCronNext(String sourceKey, boolean all, int limit) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        List<CronJobRecord> jobs = cronJobService.listAll(true);
        List<CronJobRecord> upcoming = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : jobs) {
            String state = cronState(job);
            if (!"scheduled".equals(state) || job.getNextRunAt() <= 0L) {
                continue;
            }
            upcoming.add(job);
        }
        Collections.sort(
                upcoming,
                new Comparator<CronJobRecord>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(CronJobRecord left, CronJobRecord right) {
                        long delta = left.getNextRunAt() - right.getNextRunAt();
                        if (delta < 0L) {
                            return -1;
                        }
                        if (delta > 0L) {
                            return 1;
                        }
                        return StrUtil.blankToDefault(left.getJobId(), "")
                                .compareTo(StrUtil.blankToDefault(right.getJobId(), ""));
                    }
                });

        StringBuilder buffer = new StringBuilder("Cron 即将运行");
        buffer.append('\n').append("范围：全部任务");
        if (upcoming.isEmpty()) {
            buffer.append('\n').append("暂无即将运行的任务。");
            return buffer.toString();
        }
        int count = Math.min(safeLimit, upcoming.size());
        for (int i = 0; i < count; i++) {
            CronJobRecord job = upcoming.get(i);
            buffer.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(formatTimestamp(job.getNextRunAt()))
                    .append(" ")
                    .append(job.getJobId())
                    .append(" ")
                    .append(StrUtil.blankToDefault(job.getName(), ""));
            buffer.append('\n')
                    .append("   Schedule: ")
                    .append(StrUtil.blankToDefault(job.getCronExpr(), ""));
            buffer.append('\n')
                    .append("   Deliver: ")
                    .append(StrUtil.blankToDefault(job.getDeliverPlatform(), "local"));
            if (job.getRepeatTimes() > 0) {
                buffer.append(" Repeat: ").append(formatCronRepeat(job));
            }
        }
        if (upcoming.size() > count) {
            buffer.append('\n').append("还有 ").append(upcoming.size() - count).append(" 个任务未显示。");
        }
        return buffer.toString();
    }

    /**
     * 执行定时任务状态相关逻辑。
     *
     * @param job job 参数。
     * @return 返回定时任务状态。
     */
    private String cronState(CronJobRecord job) {
        if (job == null) {
            return "scheduled";
        }
        if ("PAUSED".equalsIgnoreCase(job.getStatus())) {
            return "paused";
        }
        if ("COMPLETED".equalsIgnoreCase(job.getStatus())) {
            return "completed";
        }
        return "scheduled";
    }

    /**
     * 追加RecentProblem。
     *
     * @param records records 参数。
     * @param job job 参数。
     */
    private void addRecentProblem(List<CronJobRecord> records, CronJobRecord job) {
        if (job == null || records.contains(job) || records.size() >= 5) {
            return;
        }
        records.add(job);
    }

    /**
     * 格式化定时任务历史。
     *
     * @param jobId job标识。
     * @param runs runs 参数。
     * @return 返回定时任务历史结果。
     */
    private String formatCronHistory(String jobId, List<CronJobRunRecord> runs) {
        if (runs == null || runs.isEmpty()) {
            return "定时任务 " + jobId + " 暂无执行历史。";
        }
        StringBuilder buffer = new StringBuilder("Cron 执行历史：").append(jobId);
        for (CronJobRunRecord run : runs) {
            buffer.append('\n')
                    .append("Run: ")
                    .append(run.getRunId())
                    .append('\n')
                    .append("Status: ")
                    .append(StrUtil.blankToDefault(run.getStatus(), "?"))
                    .append(" trigger=")
                    .append(StrUtil.blankToDefault(run.getTriggerType(), "scheduled"))
                    .append(" attempt=")
                    .append(run.getAttempt())
                    .append('\n')
                    .append("Started: ")
                    .append(run.getStartedAt())
                    .append(" Finished: ")
                    .append(run.getFinishedAt());
            if (StrUtil.isNotBlank(run.getError())) {
                buffer.append('\n').append("Error: ").append(safeCronText(run.getError(), 1000));
            }
            if (StrUtil.isNotBlank(run.getDeliveryError())) {
                buffer.append('\n')
                        .append("Delivery error: ")
                        .append(safeCronText(run.getDeliveryError(), 1000));
            }
            if (StrUtil.isNotBlank(run.getOutput())) {
                buffer.append('\n').append("Output: ").append(safeCronText(run.getOutput(), 300));
            }
        }
        return buffer.toString();
    }

    /**
     * 生成安全展示用的定时任务文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回safe定时任务Text结果。
     */
    private String safeCronText(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    /**
     * 格式化定时任务Detail。
     *
     * @param job job 参数。
     * @return 返回定时任务Detail结果。
     */
    private String formatCronDetail(CronJobRecord job) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 任务详情：").append(job.getJobId()).append('\n');
        buffer.append(formatCronList(Arrays.asList(job)));
        buffer.append('\n')
                .append("History: ")
                .append(GatewayCommandConstants.SLASH_CRON)
                .append(" history ")
                .append(job.getJobId())
                .append(" --limit 20")
                .append('\n')
                .append("Run: ")
                .append(GatewayCommandConstants.SLASH_CRON)
                .append(" run ")
                .append(job.getJobId())
                .append('\n')
                .append("Edit: ")
                .append(GatewayCommandConstants.SLASH_CRON)
                .append(" edit ")
                .append(job.getJobId())
                .append(" --schedule \"...\" --prompt \"...\"");
        return buffer.toString();
    }

    /**
     * 格式化定时任务List。
     *
     * @param jobs jobs 参数。
     * @return 返回定时任务List结果。
     */
    private String formatCronList(List<CronJobRecord> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return "当前没有定时任务。";
        }
        StringBuilder buffer = new StringBuilder("Scheduled Jobs:");
        for (CronJobRecord job : jobs) {
            Map<String, Object> view = cronJobService.toView(job);
            buffer.append('\n')
                    .append("ID: ")
                    .append(job.getJobId())
                    .append('\n')
                    .append("Name: ")
                    .append(StrUtil.blankToDefault(job.getName(), ""))
                    .append('\n')
                    .append("State: ")
                    .append(StrUtil.blankToDefault(String.valueOf(view.get("state")), "scheduled"))
                    .append('\n')
                    .append("Schedule: ")
                    .append(job.getCronExpr())
                    .append('\n')
                    .append("Repeat: ")
                    .append(formatCronRepeat(job))
                    .append('\n')
                    .append("Next run: ")
                    .append(job.getNextRunAt() <= 0 ? "N/A" : String.valueOf(job.getNextRunAt()));
            String deliver = StrUtil.blankToDefault(job.getDeliverPlatform(), "local");
            buffer.append('\n').append("Deliver: ").append(deliver);
            if (StrUtil.isNotBlank(job.getDeliverChatId())) {
                buffer.append('\n').append("Deliver chat: ").append(job.getDeliverChatId());
            }
            if (StrUtil.isNotBlank(job.getDeliverThreadId())) {
                buffer.append('\n').append("Deliver thread: ").append(job.getDeliverThreadId());
            }
            buffer.append('\n').append("Wrap response: ").append(job.isWrapResponse());
            if (StrUtil.isNotBlank(job.getPausedReason())) {
                buffer.append('\n').append("Paused reason: ").append(job.getPausedReason());
            }
            Object skills = view.get("skills");
            if (skills instanceof Iterable) {
                String text = joinIterable((Iterable<?>) skills, ", ");
                if (StrUtil.isNotBlank(text)) {
                    buffer.append('\n').append("Skills: ").append(text);
                }
            }
            if (StrUtil.isNotBlank(job.getScript())) {
                buffer.append('\n').append("Script: ").append(job.getScript());
            }
            if (job.isNoAgent()) {
                buffer.append('\n').append("Mode: no-agent (script stdout delivered directly)");
            }
            if (StrUtil.isNotBlank(job.getWorkdir())) {
                buffer.append('\n').append("Workdir: ").append(job.getWorkdir());
            }
            appendCronListIterable(buffer, "Context from", view.get("context_from"));
            appendCronListIterable(buffer, "Toolsets", view.get("enabled_toolsets"));
            if (StrUtil.isNotBlank(job.getModel())) {
                buffer.append('\n').append("Model: ").append(job.getModel());
            }
            if (StrUtil.isNotBlank(job.getProvider())) {
                buffer.append('\n').append("Provider: ").append(job.getProvider());
            }
            if (StrUtil.isNotBlank(job.getBaseUrl())) {
                buffer.append('\n').append("Base URL: ").append(job.getBaseUrl());
            }
            buffer.append('\n')
                    .append("Prompt: ")
                    .append(StrUtil.blankToDefault(String.valueOf(view.get("prompt_preview")), ""));
            if (job.getLastRunAt() > 0) {
                buffer.append('\n')
                        .append("Last run: ")
                        .append(job.getLastRunAt())
                        .append(" (")
                        .append(StrUtil.blankToDefault(job.getLastStatus(), "?"))
                        .append(")");
            }
            if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                buffer.append('\n')
                        .append("Delivery failed: ")
                        .append(safeCronText(job.getLastDeliveryError(), 1000));
            }
        }
        return buffer.toString();
    }

    /**
     * 追加定时任务List Iterable。
     *
     * @param buffer buffer 参数。
     * @param label label 参数。
     * @param values 待规范化或校验的原始值集合。
     */
    private void appendCronListIterable(StringBuilder buffer, String label, Object values) {
        if (!(values instanceof Iterable)) {
            return;
        }
        String text = joinIterable((Iterable<?>) values, ", ");
        if (StrUtil.isNotBlank(text)) {
            buffer.append('\n').append(label).append(": ").append(text);
        }
    }

    /**
     * 格式化定时任务Repeat。
     *
     * @param job job 参数。
     * @return 返回定时任务Repeat结果。
     */
    private String formatCronRepeat(CronJobRecord job) {
        if (job.getRepeatTimes() <= 0) {
            return "∞";
        }
        return job.getRepeatCompleted() + "/" + job.getRepeatTimes();
    }

    /**
     * 执行joinIterable相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param delimiter delimiter 参数。
     * @return 返回join Iterable结果。
     */
    private String joinIterable(Iterable<?> values, String delimiter) {
        StringBuilder buffer = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value).trim();
            if (StrUtil.isBlank(text)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(delimiter);
            }
            buffer.append(text);
        }
        return buffer.toString();
    }

    /**
     * 解析定时任务Create。
     *
     * @param tail tail 参数。
     * @return 返回解析后的定时任务Create。
     */
    private Map<String, Object> parseCronCreate(String tail) {
        if (StrUtil.isBlank(tail)) {
            return null;
        }
        if (tail.contains("|")) {
            String[] fields = tail.split("\\|", -1);
            if (fields.length < 3) {
                return null;
            }
            Map<String, Object> body = parseCronOptions(fields, 3);
            body.put("name", fields[0].trim());
            body.put("schedule", fields[1].trim());
            body.put("prompt", fields[2].trim());
            return body;
        }

        CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
        if (options.positionals.isEmpty()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String schedule = StrUtil.blankToDefault(options.schedule, options.positionals.get(0));
        String prompt = options.prompt;
        if (StrUtil.isBlank(prompt) && options.positionals.size() > 1) {
            prompt = join(options.positionals.subList(1, options.positionals.size()), " ");
        }
        putIfNotBlank(body, "name", options.name);
        putIfNotBlank(body, "schedule", schedule);
        putIfNotBlank(body, "prompt", prompt);
        appendCronFlagOptions(body, options);
        return body;
    }

    /**
     * 执行定时任务Origin相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回定时任务Origin结果。
     */
    private Map<String, Object> cronOrigin(GatewayMessage message) {
        String[] sourceParts = SourceKeySupport.split(message.sourceKey());
        Map<String, Object> origin = new LinkedHashMap<String, Object>();
        origin.put("platform", sourceParts[0]);
        origin.put("chat_id", sourceParts[1]);
        origin.put("user_id", sourceParts[2]);
        if (StrUtil.isNotBlank(message.getThreadId())) {
            origin.put("thread_id", message.getThreadId());
        }
        return origin;
    }

    /**
     * 解析定时任务Edit。
     *
     * @param tail tail 参数。
     * @return 返回解析后的定时任务Edit。
     */
    @SuppressWarnings("unchecked")
    private CronEditRequest parseCronEdit(String tail) throws Exception {
        if (StrUtil.isBlank(tail)) {
            return null;
        }
        if (tail.contains("|")) {
            String[] fields = tail.split("\\|", -1);
            if (fields.length < 2 || StrUtil.isBlank(fields[0])) {
                return null;
            }
            Map<String, Object> body = parseCronOptions(fields, 4);
            if (fields.length > 1 && StrUtil.isNotBlank(fields[1])) {
                body.put("name", fields[1].trim());
            }
            if (fields.length > 2 && StrUtil.isNotBlank(fields[2])) {
                body.put("schedule", fields[2].trim());
            }
            if (fields.length > 3 && StrUtil.isNotBlank(fields[3])) {
                body.put("prompt", fields[3].trim());
            }
            return new CronEditRequest(fields[0].trim(), body);
        }

        CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
        if (options.positionals.isEmpty()) {
            return null;
        }
        String jobId = options.positionals.get(0);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        putIfNotBlank(body, "name", options.name);
        putIfNotBlank(body, "schedule", options.schedule);
        putIfNotBlank(body, "prompt", options.prompt);
        appendCronFlagOptions(body, options);

        List<String> replacementSkills = normalizeList(options.skills);
        List<String> addSkills = normalizeList(options.addSkills);
        Set<String> removeSkills = new LinkedHashSet<String>(normalizeList(options.removeSkills));
        if (options.clearSkills) {
            body.put("skills", new ArrayList<String>());
        } else if (!replacementSkills.isEmpty()) {
            body.put("skills", replacementSkills);
        } else if (!addSkills.isEmpty() || !removeSkills.isEmpty()) {
            CronJobRecord existing = cronJobService.require(jobId);
            List<String> finalSkills = new ArrayList<String>();
            Object existingSkills = cronJobService.toView(existing).get("skills");
            if (existingSkills instanceof Iterable) {
                for (Object item : (Iterable<Object>) existingSkills) {
                    String skill = item == null ? "" : String.valueOf(item).trim();
                    if (StrUtil.isNotBlank(skill) && !removeSkills.contains(skill)) {
                        finalSkills.add(skill);
                    }
                }
            }
            for (String skill : addSkills) {
                if (!finalSkills.contains(skill)) {
                    finalSkills.add(skill);
                }
            }
            body.put("skills", finalSkills);
        }
        return new CronEditRequest(jobId, body);
    }

    /**
     * 解析定时任务Options。
     *
     * @param fields fields 参数。
     * @param start start 参数。
     * @return 返回解析后的定时任务Options。
     */
    private Map<String, Object> parseCronOptions(String[] fields, int start) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        for (int i = start; i < fields.length; i++) {
            String field = fields[i] == null ? "" : fields[i].trim();
            if (StrUtil.isBlank(field)) {
                continue;
            }
            if (field.startsWith("--skill ")) {
                body.put("skills", field.substring("--skill ".length()).trim());
            } else if (field.startsWith("--add-skill ")) {
                body.put("skills", field.substring("--add-skill ".length()).trim());
            } else if (field.startsWith("--add-skills ")) {
                body.put("skills", field.substring("--add-skills ".length()).trim());
            } else if ("--clear-skills".equals(field)) {
                body.put("skills", new ArrayList<String>());
            } else if (field.startsWith("--skills ")) {
                body.put("skills", field.substring("--skills ".length()).trim());
            } else if (field.startsWith("-s ")) {
                body.put("skills", field.substring("-s ".length()).trim());
            } else if (field.startsWith("--deliver ")) {
                body.put("deliver", field.substring("--deliver ".length()).trim());
            } else if (field.startsWith("--deliver-chat-id ")) {
                body.put("deliver_chat_id", field.substring("--deliver-chat-id ".length()).trim());
            } else if (field.startsWith("--deliver_chat_id ")) {
                body.put("deliver_chat_id", field.substring("--deliver_chat_id ".length()).trim());
            } else if ("--clear-deliver-chat-id".equals(field)
                    || "--clear-deliver_chat_id".equals(field)) {
                body.put("deliver_chat_id", null);
            } else if (field.startsWith("--deliver-thread-id ")) {
                body.put(
                        "deliver_thread_id",
                        field.substring("--deliver-thread-id ".length()).trim());
            } else if (field.startsWith("--deliver_thread_id ")) {
                body.put(
                        "deliver_thread_id",
                        field.substring("--deliver_thread_id ".length()).trim());
            } else if ("--clear-deliver-thread-id".equals(field)
                    || "--clear-deliver_thread_id".equals(field)) {
                body.put("deliver_thread_id", null);
            } else if (field.startsWith("--repeat ")) {
                body.put("repeat", Integer.valueOf(field.substring("--repeat ".length()).trim()));
            } else if ("--clear-repeat".equals(field)) {
                body.put("repeat", Integer.valueOf(0));
            } else if (field.startsWith("--script ")) {
                body.put("script", field.substring("--script ".length()).trim());
            } else if ("--clear-script".equals(field)) {
                body.put("script", null);
            } else if (field.startsWith("--workdir ")) {
                body.put("workdir", field.substring("--workdir ".length()).trim());
            } else if ("--clear-workdir".equals(field)) {
                body.put("workdir", null);
            } else if (field.startsWith("--context-from ")) {
                body.put("context_from", field.substring("--context-from ".length()).trim());
            } else if ("--clear-context-from".equals(field)) {
                body.put("context_from", new ArrayList<String>());
            } else if (field.startsWith("--depends-on ")) {
                body.put("depends_on", field.substring("--depends-on ".length()).trim());
            } else if ("--clear-depends-on".equals(field)) {
                body.put("depends_on", new ArrayList<String>());
            } else if (field.startsWith("--toolsets ")) {
                body.put("enabled_toolsets", field.substring("--toolsets ".length()).trim());
            } else if (field.startsWith("--enabled-toolsets ")) {
                body.put(
                        "enabled_toolsets", field.substring("--enabled-toolsets ".length()).trim());
            } else if ("--clear-toolsets".equals(field)
                    || "--clear-enabled-toolsets".equals(field)) {
                body.put("enabled_toolsets", new ArrayList<String>());
            } else if (field.startsWith("--model ")) {
                body.put("model", field.substring("--model ".length()).trim());
            } else if ("--clear-model".equals(field)) {
                body.put("model", null);
            } else if (field.startsWith("--provider ")) {
                body.put("provider", field.substring("--provider ".length()).trim());
            } else if ("--clear-provider".equals(field)) {
                body.put("provider", null);
            } else if (field.startsWith("--base-url ")) {
                body.put("base_url", field.substring("--base-url ".length()).trim());
            } else if (field.startsWith("--base_url ")) {
                body.put("base_url", field.substring("--base_url ".length()).trim());
            } else if ("--clear-base-url".equals(field) || "--clear-base_url".equals(field)) {
                body.put("base_url", null);
            } else if ("--no-agent".equals(field)) {
                body.put("no_agent", Boolean.TRUE);
            } else if ("--agent".equals(field)) {
                body.put("no_agent", Boolean.FALSE);
            } else if ("--wrap-response".equals(field) || "--wrap".equals(field)) {
                body.put("wrap_response", Boolean.TRUE);
            } else if ("--raw".equals(field) || "--no-wrap".equals(field)) {
                body.put("wrap_response", Boolean.FALSE);
            } else if ("--no-wrap-response".equals(field)) {
                body.put("wrap_response", Boolean.FALSE);
            } else if (field.startsWith("--status ")) {
                body.put("status", field.substring("--status ".length()).trim());
            } else if (field.startsWith("--state ")) {
                body.put("state", field.substring("--state ".length()).trim());
            } else if (field.startsWith("--paused-reason ")) {
                body.put("paused_reason", field.substring("--paused-reason ".length()).trim());
            } else if (field.startsWith("--paused_reason ")) {
                body.put("paused_reason", field.substring("--paused_reason ".length()).trim());
            }
        }
        return body;
    }

    /**
     * 追加定时任务Flag Options。
     *
     * @param body 请求体或消息正文内容。
     * @param options options 参数。
     */
    private void appendCronFlagOptions(Map<String, Object> body, CronFlagOptions options) {
        putIfNotBlank(body, "deliver", options.deliver);
        putCronStringOption(body, "deliver_chat_id", options.deliverChatId);
        putCronStringOption(body, "deliver_thread_id", options.deliverThreadId);
        if (options.clearDeliverChatId) {
            body.put("deliver_chat_id", null);
        }
        if (options.clearDeliverThreadId) {
            body.put("deliver_thread_id", null);
        }
        if (options.repeat != null) {
            body.put("repeat", options.repeat);
        }
        if (options.clearRepeat) {
            body.put("repeat", Integer.valueOf(0));
        }
        putCronStringOption(body, "script", options.script);
        putCronStringOption(body, "workdir", options.workdir);
        putIfNotBlank(body, "context_from", options.contextFrom);
        putIfNotBlank(body, "depends_on", options.dependsOn);
        putIfNotBlank(body, "enabled_toolsets", options.enabledToolsets);
        putIfNotBlank(body, "model", options.model);
        putIfNotBlank(body, "provider", options.provider);
        putIfNotBlank(body, "base_url", options.baseUrl);
        putIfNotBlank(body, "status", options.status);
        putIfNotBlank(body, "state", options.state);
        putIfNotBlank(body, "paused_reason", options.pausedReason);
        if (options.clearModel) {
            body.put("model", null);
        }
        if (options.clearProvider) {
            body.put("provider", null);
        }
        if (options.clearBaseUrl) {
            body.put("base_url", null);
        }
        if (options.clearScript) {
            body.put("script", null);
        }
        if (options.clearWorkdir) {
            body.put("workdir", null);
        }
        if (options.clearContextFrom) {
            body.put("context_from", new ArrayList<String>());
        }
        if (options.clearDependsOn) {
            body.put("depends_on", new ArrayList<String>());
        }
        if (options.clearToolsets) {
            body.put("enabled_toolsets", new ArrayList<String>());
        }
        if (options.noAgent) {
            body.put("no_agent", Boolean.TRUE);
        }
        if (options.agent) {
            body.put("no_agent", Boolean.FALSE);
        }
        if (options.wrapResponse) {
            body.put("wrap_response", Boolean.TRUE);
        }
        if (options.raw) {
            body.put("wrap_response", Boolean.FALSE);
        }
        if (!options.skills.isEmpty()) {
            body.put("skills", normalizeList(options.skills));
        }
    }

    /**
     * 解析定时任务Flags。
     *
     * @param tokens token参数。
     * @return 返回解析后的定时任务Flags。
     */
    private CronFlagOptions parseCronFlags(List<String> tokens) {
        CronFlagOptions options = new CronFlagOptions();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("--name".equals(token) && i + 1 < tokens.size()) {
                options.name = tokens.get(++i);
            } else if ("--deliver".equals(token) && i + 1 < tokens.size()) {
                options.deliver = tokens.get(++i);
            } else if (("--deliver-chat-id".equals(token) || "--deliver_chat_id".equals(token))
                    && i + 1 < tokens.size()) {
                options.deliverChatId = tokens.get(++i);
            } else if ("--clear-deliver-chat-id".equals(token)
                    || "--clear-deliver_chat_id".equals(token)) {
                options.clearDeliverChatId = true;
            } else if (("--deliver-thread-id".equals(token) || "--deliver_thread_id".equals(token))
                    && i + 1 < tokens.size()) {
                options.deliverThreadId = tokens.get(++i);
            } else if ("--clear-deliver-thread-id".equals(token)
                    || "--clear-deliver_thread_id".equals(token)) {
                options.clearDeliverThreadId = true;
            } else if ("--repeat".equals(token) && i + 1 < tokens.size()) {
                options.repeat = Integer.valueOf(tokens.get(++i));
            } else if ("--clear-repeat".equals(token)) {
                options.clearRepeat = true;
            } else if ("--limit".equals(token) && i + 1 < tokens.size()) {
                options.limit = Integer.valueOf(tokens.get(++i));
            } else if ("--reason".equals(token) && i + 1 < tokens.size()) {
                options.reason = tokens.get(++i);
            } else if (("--trigger-type".equals(token) || "--trigger_type".equals(token))
                    && i + 1 < tokens.size()) {
                options.triggerType = tokens.get(++i);
            } else if (("--skill".equals(token) || "-s".equals(token)) && i + 1 < tokens.size()) {
                options.skills.add(tokens.get(++i));
            } else if ("--skills".equals(token) && i + 1 < tokens.size()) {
                options.skills.add(tokens.get(++i));
            } else if (("--add-skill".equals(token) || "--add-skills".equals(token))
                    && i + 1 < tokens.size()) {
                options.addSkills.add(tokens.get(++i));
            } else if (("--remove-skill".equals(token) || "--remove-skills".equals(token))
                    && i + 1 < tokens.size()) {
                options.removeSkills.add(tokens.get(++i));
            } else if ("--clear-skills".equals(token)) {
                options.clearSkills = true;
            } else if ("--all".equals(token)) {
                options.all = true;
            } else if ("--prompt".equals(token) && i + 1 < tokens.size()) {
                options.prompt = tokens.get(++i);
            } else if ("--schedule".equals(token) && i + 1 < tokens.size()) {
                options.schedule = tokens.get(++i);
            } else if ("--script".equals(token) && i + 1 < tokens.size()) {
                options.script = tokens.get(++i);
            } else if ("--clear-script".equals(token)) {
                options.clearScript = true;
            } else if ("--workdir".equals(token) && i + 1 < tokens.size()) {
                options.workdir = tokens.get(++i);
            } else if ("--clear-workdir".equals(token)) {
                options.clearWorkdir = true;
            } else if ("--context-from".equals(token) && i + 1 < tokens.size()) {
                options.contextFrom = tokens.get(++i);
            } else if ("--clear-context-from".equals(token)) {
                options.clearContextFrom = true;
            } else if ("--depends-on".equals(token) && i + 1 < tokens.size()) {
                options.dependsOn = tokens.get(++i);
            } else if ("--clear-depends-on".equals(token)) {
                options.clearDependsOn = true;
            } else if (("--toolsets".equals(token) || "--enabled-toolsets".equals(token))
                    && i + 1 < tokens.size()) {
                options.enabledToolsets = tokens.get(++i);
            } else if ("--clear-toolsets".equals(token)
                    || "--clear-enabled-toolsets".equals(token)) {
                options.clearToolsets = true;
            } else if ("--model".equals(token) && i + 1 < tokens.size()) {
                options.model = tokens.get(++i);
            } else if ("--clear-model".equals(token)) {
                options.clearModel = true;
            } else if ("--provider".equals(token) && i + 1 < tokens.size()) {
                options.provider = tokens.get(++i);
            } else if ("--clear-provider".equals(token)) {
                options.clearProvider = true;
            } else if (("--base-url".equals(token) || "--base_url".equals(token))
                    && i + 1 < tokens.size()) {
                options.baseUrl = tokens.get(++i);
            } else if ("--clear-base-url".equals(token) || "--clear-base_url".equals(token)) {
                options.clearBaseUrl = true;
            } else if ("--status".equals(token) && i + 1 < tokens.size()) {
                options.status = tokens.get(++i);
            } else if ("--state".equals(token) && i + 1 < tokens.size()) {
                options.state = tokens.get(++i);
            } else if (("--paused-reason".equals(token) || "--paused_reason".equals(token))
                    && i + 1 < tokens.size()) {
                options.pausedReason = tokens.get(++i);
            } else if ("--no-agent".equals(token)) {
                options.noAgent = true;
            } else if ("--agent".equals(token)) {
                options.agent = true;
            } else if ("--wrap-response".equals(token) || "--wrap".equals(token)) {
                options.wrapResponse = true;
            } else if ("--raw".equals(token)
                    || "--no-wrap".equals(token)
                    || "--no-wrap-response".equals(token)) {
                options.raw = true;
            } else if ("--json".equals(token)) {
                options.json = true;
            } else {
                options.positionals.add(token);
            }
        }
        return options;
    }

    /**
     * 拆分命令Line。
     *
     * @param raw 原始输入值。
     * @return 返回命令Line结果。
     */
    private List<String> splitCommandLine(String raw) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                tokenStarted = true;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                tokenStarted = true;
                continue;
            }
            if (quoted) {
                if (ch == quote) {
                    quoted = false;
                } else {
                    current.append(ch);
                    tokenStarted = true;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quoted = true;
                quote = ch;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(ch);
                tokenStarted = true;
            }
        }
        if (escaping) {
            current.append('\\');
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 规范化List。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回List结果。
     */
    private List<String> normalizeList(List<String> values) {
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            for (String part : StrUtil.nullToEmpty(value).split(",")) {
                String text = part.trim();
                if (StrUtil.isNotBlank(text) && !result.contains(text)) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    /**
     * 执行join相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param delimiter delimiter 参数。
     * @return 返回join结果。
     */
    private String join(List<String> values, String delimiter) {
        StringBuilder buffer = new StringBuilder();
        for (String value : values) {
            if (buffer.length() > 0) {
                buffer.append(delimiter);
            }
            buffer.append(value);
        }
        return buffer.toString();
    }

    /**
     * 执行joinTail相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param start start 参数。
     * @return 返回join Tail结果。
     */
    private String joinTail(List<String> values, int start) {
        if (values == null || start >= values.size()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = start; i < values.size(); i++) {
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(values.get(i));
        }
        return buffer.toString();
    }

    /**
     * 写入If Not Blank。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void putIfNotBlank(Map<String, Object> body, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            body.put(key, value.trim());
        }
    }

    /**
     * 写入定时任务String Option。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void putCronStringOption(Map<String, Object> body, String key, String value) {
        if (value != null) {
            body.put(key, value.trim());
        }
    }

    /** 执行pairing相关命令相关逻辑。 */
    private GatewayReply handlePairing(GatewayMessage message, String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length == 0 || StrUtil.isBlank(parts[0])) {
            return GatewayReply.error(
                    "用法："
                            + GatewayCommandConstants.SLASH_PAIRING
                            + " [claim-admin|list|pending|approve|revoke|approved|clear-pending] ...");
        }
        String action = parts[0].trim().toLowerCase();

        if (GatewayCommandConstants.ACTION_CLAIM_ADMIN.equals(action)) {
            return gatewayAuthorizationService.claimAdmin(message);
        }

        PlatformType targetPlatform = message.getPlatform();
        if (parts.length >= 2) {
            targetPlatform = PlatformType.fromName(parts[1]);
        }

        if (GatewayCommandConstants.ACTION_LIST.equals(action)) {
            return gatewayAuthorizationService.pairingList(message, targetPlatform);
        }
        if (GatewayCommandConstants.ACTION_PENDING.equals(action)) {
            return gatewayAuthorizationService.pairingPending(message, targetPlatform);
        }
        if (GatewayCommandConstants.ACTION_APPROVED.equals(action)) {
            return gatewayAuthorizationService.pairingApproved(message, targetPlatform);
        }
        if (GatewayCommandConstants.ACTION_APPROVE.equals(action)) {
            if (parts.length < 3) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_PAIRING
                                + " approve <platform> <code>");
            }
            return gatewayAuthorizationService.pairingApprove(message, targetPlatform, parts[2]);
        }
        if (GatewayCommandConstants.ACTION_REVOKE.equals(action)) {
            if (parts.length < 3) {
                return GatewayReply.error(
                        "用法："
                                + GatewayCommandConstants.SLASH_PAIRING
                                + " revoke <platform> <userId>");
            }
            return gatewayAuthorizationService.pairingRevoke(message, targetPlatform, parts[2]);
        }
        if ("clear-pending".equals(action) || "clear_pending".equals(action)) {
            return gatewayAuthorizationService.pairingClearPending(message, targetPlatform);
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_PAIRING
                        + " [claim-admin|list|pending|approve|revoke|approved|clear-pending] ...");
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
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session == null) {
            return GatewayReply.error("当前没有绑定会话，也没有待审批的危险命令。请先触发需要审批的工具调用。");
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        String safeArgs = cleanApprovalCommandArgs(args);
        String normalizedArgs = safeArgs.toLowerCase();
        if ("list".equals(normalizedArgs) || "status".equals(normalizedArgs)) {
            return GatewayReply.ok(formatApprovalList(agentSession));
        }
        if (normalizedArgs.startsWith("clear")) {
            return clearApprovals(agentSession, normalizedArgs);
        }
        if (isApproveAllCommand(normalizedArgs)) {
            return approveAllDangerousCommands(message, agentSession, args);
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
        return conversationOrchestrator.resumePending(message.sourceKey());
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
        String first = firstToken(normalizedArgs);
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
        return conversationOrchestrator.resumePending(message.sourceKey());
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
                    .append(expiresInSeconds(pending.getExpiresAt()))
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
     * 执行expiresInSeconds相关逻辑。
     *
     * @param expiresAt expiresAt 参数。
     * @return 返回expires In Seconds结果。
     */
    private long expiresInSeconds(long expiresAt) {
        if (expiresAt <= 0L) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
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
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session == null) {
            return GatewayReply.error("当前没有待审批的危险命令。");
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        String safeArgs = cleanApprovalCommandArgs(args);
        String selector = firstToken(safeArgs);
        if ("list".equalsIgnoreCase(selector) || "status".equalsIgnoreCase(selector)) {
            return GatewayReply.ok(formatApprovalList(agentSession));
        }
        if ("all".equalsIgnoreCase(selector)) {
            int rejected =
                    dangerousCommandApprovalService.rejectAll(agentSession, message.getUserName());
            if (rejected <= 0) {
                return GatewayReply.error("当前没有待审批的危险命令。");
            }
            return conversationOrchestrator.resumePending(message.sourceKey());
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
        return conversationOrchestrator.resumePending(message.sourceKey());
    }

    /** 承载审批命令参数相关状态和辅助逻辑。 */
    private static class ApprovalCommandArgs {
        /** 记录审批命令参数中的选择器。 */
        private String selector;

        /** 注入范围，用于调用对应业务能力。 */
        private DangerousCommandApprovalService.ApprovalScope scope;

        /**
         * 读取Selector。
         *
         * @return 返回读取到的Selector。
         */
        public String getSelector() {
            return selector;
        }

        /**
         * 写入Selector。
         *
         * @param selector 浏览器元素选择器。
         */
        public void setSelector(String selector) {
            this.selector = selector;
        }

        /**
         * 读取范围。
         *
         * @return 返回读取到的范围。
         */
        public DangerousCommandApprovalService.ApprovalScope getScope() {
            return scope;
        }

        /**
         * 写入范围。
         *
         * @param scope scope 参数。
         */
        public void setScope(DangerousCommandApprovalService.ApprovalScope scope) {
            this.scope = scope;
        }
    }

    /**
     * 执行推理相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Reasoning结果。
     */
    private GatewayReply handleReasoning(GatewayMessage message, String args) throws Exception {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (normalized.length() == 0) {
            return GatewayReply.ok(
                    "reasoning_display="
                            + displaySettingsService.describeReasoning(
                                    message.sourceKey(), message.getPlatform())
                            + "\nreasoning_effort="
                            + effectiveReasoningEffort(session)
                            + "\nusage="
                            + GatewayCommandConstants.SLASH_REASONING
                            + " [level|reset|show|hide]");
        }
        if ("show".equals(normalized) || "on".equals(normalized)) {
            displaySettingsService.setReasoningVisible(message.sourceKey(), true);
            return GatewayReply.ok("已开启当前来源键的 reasoning 展示。");
        }
        if ("hide".equals(normalized) || "off".equals(normalized)) {
            displaySettingsService.setReasoningVisible(message.sourceKey(), false);
            return GatewayReply.ok("已关闭当前来源键的 reasoning 展示。");
        }
        if (session == null) {
            return GatewayReply.error("当前没有可设置 reasoning 的会话。");
        }
        if ("reset".equals(normalized) || "default".equals(normalized)) {
            sessionRepository.setReasoningEffortOverride(session.getSessionId(), null);
            session.setReasoningEffortOverride(null);
            return GatewayReply.ok(
                    "已清除当前会话 reasoning 覆盖。\nreasoning_effort=" + effectiveReasoningEffort(session));
        }
        if (isReasoningEffortLevel(normalized)) {
            String override = "none".equals(normalized) ? "none" : normalized;
            sessionRepository.setReasoningEffortOverride(session.getSessionId(), override);
            session.setReasoningEffortOverride(override);
            return GatewayReply.ok(
                    "已设置当前会话 reasoning 强度。\nreasoning_effort=" + effectiveReasoningEffort(session));
        }
        return GatewayReply.error(
                "用法：" + GatewayCommandConstants.SLASH_REASONING + " [level|reset|show|hide]");
    }

    /**
     * 判断是否Reasoning Effort级别。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Reasoning Effort级别满足条件则返回 true，否则返回 false。
     */
    private boolean isReasoningEffortLevel(String value) {
        return "none".equals(value)
                || "minimal".equals(value)
                || "low".equals(value)
                || "medium".equals(value)
                || "high".equals(value)
                || "xhigh".equals(value);
    }

    /**
     * 执行生效推理Effort相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回生效Reasoning Effort结果。
     */
    private String effectiveReasoningEffort(SessionRecord session) {
        String override =
                session == null
                        ? ""
                        : StrUtil.nullToEmpty(session.getReasoningEffortOverride()).trim();
        return StrUtil.blankToDefault(
                StrUtil.isNotBlank(override) ? override : appConfig.getLlm().getReasoningEffort(),
                "default");
    }

    /**
     * 执行Fast相关逻辑。
     *
     * @param session 会话参数。
     * @param args 工具或命令参数。
     * @return 返回Fast结果。
     */
    private GatewayReply handleFast(SessionRecord session, String args) throws Exception {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if (StrUtil.isBlank(normalized) || "status".equals(normalized)) {
            return GatewayReply.ok(formatFastStatus(session));
        }
        if ("fast".equals(normalized) || "on".equals(normalized) || "priority".equals(normalized)) {
            sessionRepository.setServiceTierOverride(session.getSessionId(), "priority");
            session.setServiceTierOverride("priority");
            return GatewayReply.ok("已开启当前会话快速模式。\n" + formatFastStatus(session));
        }
        if ("normal".equals(normalized)
                || "off".equals(normalized)
                || "default".equals(normalized)) {
            sessionRepository.setServiceTierOverride(session.getSessionId(), null);
            session.setServiceTierOverride(null);
            return GatewayReply.ok("已恢复当前会话普通模式。\n" + formatFastStatus(session));
        }
        return GatewayReply.error(
                "用法：" + GatewayCommandConstants.SLASH_FAST + " [fast|normal|status]");
    }

    /**
     * 格式化Fast状态。
     *
     * @param session 会话参数。
     * @return 返回Fast状态。
     */
    private String formatFastStatus(SessionRecord session) {
        return "fast_mode="
                + fastModeName(session)
                + "\nservice_tier="
                + serviceTierName(session)
                + "\nusage="
                + GatewayCommandConstants.SLASH_FAST
                + " [fast|normal|status]";
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
        return session != null
                && "priority"
                        .equalsIgnoreCase(
                                StrUtil.nullToEmpty(session.getServiceTierOverride()).trim());
    }

    /**
     * 执行Busy相关逻辑。
     *
     * @param args 工具或命令参数。
     * @param sourceKey 渠道来源键。
     * @return 返回Busy结果。
     */
    private GatewayReply handleBusy(String args, String sourceKey) {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if (StrUtil.isBlank(normalized) || "status".equals(normalized)) {
            return GatewayReply.ok(formatBusyStatus(sourceKey));
        }
        if ("queue".equals(normalized)
                || "steer".equals(normalized)
                || "interrupt".equals(normalized)
                || "reject".equals(normalized)) {
            persistBusyPolicy(normalized);
            return GatewayReply.ok(
                    "已切换运行中输入策略为 " + normalized + "。\n" + formatBusyPolicyDescription(normalized));
        }
        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_BUSY
                        + " [status|queue|steer|interrupt|reject]");
    }

    /**
     * 执行队列相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Queue结果。
     */
    private GatewayReply handleQueue(GatewayMessage message, String args) throws Exception {
        if (StrUtil.isBlank(args)) {
            return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_QUEUE + " <prompt>");
        }
        SessionRecord session = requireSession(message.sourceKey());
        GatewayMessage queuedMessage = cloneUserMessage(message, args);
        RunBusyDecision decision =
                agentRunControlService.queueIncoming(
                        message.sourceKey(), session.getSessionId(), queuedMessage);
        GatewayReply reply =
                GatewayReply.ok(StrUtil.blankToDefault(decision.getMessage(), "已加入下一轮队列。"));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        reply.getRuntimeMetadata().put("busy_policy", decision.getPolicy());
        reply.getRuntimeMetadata().put("busy_status", decision.getStatus());
        if (StrUtil.isNotBlank(decision.getRunId())) {
            reply.getRuntimeMetadata().put("run_id", decision.getRunId());
        }
        if (StrUtil.isNotBlank(decision.getQueueId())) {
            reply.getRuntimeMetadata().put("queue_id", decision.getQueueId());
        }
        return reply;
    }

    /**
     * 执行Steer相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @param args 工具或命令参数。
     * @return 返回Steer结果。
     */
    private GatewayReply handleSteer(GatewayMessage message, String args) throws Exception {
        if (StrUtil.isBlank(args)) {
            return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_STEER + " <prompt>");
        }
        SessionRecord session = requireSession(message.sourceKey());
        GatewayMessage steerMessage = cloneUserMessage(message, args);
        RunBusyDecision decision =
                agentRunControlService.steerIncoming(
                        message.sourceKey(), session.getSessionId(), steerMessage);
        if (decision.isShouldRunNow()) {
            return conversationOrchestrator.handleIncoming(steerMessage);
        }
        GatewayReply reply =
                GatewayReply.ok(
                        StrUtil.blankToDefault(decision.getMessage(), "已将 steer 指令注入当前任务。"));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        reply.getRuntimeMetadata().put("busy_policy", decision.getPolicy());
        reply.getRuntimeMetadata().put("busy_status", decision.getStatus());
        if (StrUtil.isNotBlank(decision.getRunId())) {
            reply.getRuntimeMetadata().put("run_id", decision.getRunId());
        }
        return reply;
    }

    /**
     * 克隆用户消息。
     *
     * @param source 来源参数。
     * @param text 待处理文本。
     * @return 返回clone用户消息结果。
     */
    private GatewayMessage cloneUserMessage(GatewayMessage source, String text) {
        GatewayMessage copy =
                new GatewayMessage(
                        source.getPlatform(), source.getChatId(), source.getUserId(), text);
        copy.setThreadId(source.getThreadId());
        copy.setChatType(source.getChatType());
        copy.setChatName(source.getChatName());
        copy.setUserName(source.getUserName());
        copy.setTimestamp(source.getTimestamp());
        copy.setHeartbeat(source.isHeartbeat());
        copy.setSourceKeyOverride(source.sourceKey());
        return copy;
    }

    /**
     * 执行persistBusy策略相关逻辑。
     *
     * @param policy 策略参数。
     */
    private void persistBusyPolicy(String policy) {
        if (runtimeSettingsService != null) {
            runtimeSettingsService.setConfigValue("task.busyPolicy", policy);
            return;
        }
        appConfig.getTask().setBusyPolicy(policy);
    }

    /**
     * 格式化Busy状态。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回Busy状态。
     */
    private String formatBusyStatus(String sourceKey) {
        String policy = StrUtil.blankToDefault(appConfig.getTask().getBusyPolicy(), "queue");
        SessionRecord session = findBoundSessionQuietly(sourceKey);
        Map<String, Object> activeRun =
                agentRunControlService == null
                        ? null
                        : agentRunControlService.activeRunSummary(sourceKey);
        StringBuilder buffer = new StringBuilder();
        buffer.append("busy_policy=").append(policy).append('\n');
        buffer.append("source_running=")
                .append(
                        agentRunControlService != null
                                && agentRunControlService.isRunning(sourceKey))
                .append('\n');
        buffer.append("any_running=")
                .append(agentRunControlService != null && agentRunControlService.hasRunningRuns())
                .append('\n');
        buffer.append("active_run_id=")
                .append(activeRun == null ? "-" : valueOrDash(activeRun.get("run_id")))
                .append('\n');
        if (activeRun != null) {
            buffer.append("active_run_phase=")
                    .append(valueOrDash(activeRun.get("phase")))
                    .append('\n');
            buffer.append("active_run_idle_seconds=")
                    .append(valueOrDash(activeRun.get("seconds_since_activity")))
                    .append('\n');
        }
        buffer.append("queue_pending=")
                .append(countQueuedMessagesQuietly(sourceKey, session))
                .append('\n');
        buffer.append("current_policy=").append(formatBusyPolicyDescription(policy)).append('\n');
        buffer.append("policy_options:\n").append(formatBusyPolicyOptions()).append('\n');
        buffer.append("用法：")
                .append(GatewayCommandConstants.SLASH_BUSY)
                .append(" [status|queue|steer|interrupt|reject]");
        return buffer.toString();
    }

    /**
     * 查找绑定会话Quietly。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回绑定会话Quietly结果。
     */
    private SessionRecord findBoundSessionQuietly(String sourceKey) {
        try {
            return sessionRepository.getBoundSession(sourceKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行次数排队MessagesQuietly相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param session 会话参数。
     * @return 返回次数Queued Messages Quietly结果。
     */
    private int countQueuedMessagesQuietly(String sourceKey, SessionRecord session) {
        if (agentRunRepository == null
                || session == null
                || StrUtil.isBlank(session.getSessionId())) {
            return 0;
        }
        try {
            return agentRunRepository.countQueuedMessages(sourceKey, session.getSessionId());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 执行值OrDash相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回value Or Dash结果。
     */
    private String valueOrDash(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return StrUtil.blankToDefault(text, "-");
    }

    /**
     * 格式化Busy策略Description。
     *
     * @param policy 策略参数。
     * @return 返回Busy策略Description结果。
     */
    private String formatBusyPolicyDescription(String policy) {
        if ("steer".equals(policy)) {
            return "steer：运行中收到的新消息会作为 steer 指令注入当前 run。";
        }
        if ("interrupt".equals(policy)) {
            return "interrupt：运行中收到的新消息会打断当前 run，并立即启动新 run。";
        }
        if ("reject".equals(policy)) {
            return "reject：运行中收到的新消息会被拒绝，需等待或手动停止当前 run。";
        }
        return "queue：运行中收到的新消息会进入队列，当前 run 结束后自动执行。";
    }

    /**
     * 格式化Busy策略Options。
     *
     * @return 返回Busy策略Options结果。
     */
    private String formatBusyPolicyOptions() {
        return "queue：运行中收到的新消息会进入队列，当前 run 结束后自动执行。\n"
                + "steer：运行中收到的新消息会作为 steer 指令注入当前 run。\n"
                + "interrupt：运行中收到的新消息会打断当前 run，并立即启动新 run。\n"
                + "reject：运行中收到的新消息会被拒绝，需等待或手动停止当前 run。";
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
        SessionRecord session = sessionRepository.getBoundSession(sourceKey);
        if (session == null) {
            session = sessionRepository.bindNewSession(sourceKey);
        }
        return session;
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
        try {
            String value = globalSettingRepository.get(AgentSettingConstants.ACTIVE_PERSONALITY);
            return StrUtil.blankToDefault(value, "default");
        } catch (Exception e) {
            return "default";
        }
    }

    /**
     * 解析模型命令。
     *
     * @param args 工具或命令参数。
     * @return 返回解析后的模型命令。
     */
    private ModelCommandInput parseModelCommand(String args) {
        String[] tokens = args.trim().split("\\s+");
        ModelCommandInput result = new ModelCommandInput();
        StringBuilder remainder = new StringBuilder();
        for (String token : tokens) {
            if ("--global".equalsIgnoreCase(token)) {
                result.global = true;
                continue;
            }
            if (remainder.length() > 0) {
                remainder.append(' ');
            }
            remainder.append(token);
        }
        String spec = remainder.toString().trim();
        if ("clear".equalsIgnoreCase(spec)
                || "default".equalsIgnoreCase(spec)
                || "none".equalsIgnoreCase(spec)) {
            result.clear = true;
            return result;
        }
        if (spec.contains(":")) {
            String[] parts = spec.split(":", 2);
            result.provider = parts[0].trim();
            result.model = parts[1].trim();
        } else {
            result.model = spec;
        }
        return result;
    }

    /** 承载模型命令输入相关状态和辅助逻辑。 */
    private static class ModelCommandInput {
        /** 是否启用global。 */
        private boolean global;

        /** 是否启用clear。 */
        private boolean clear;

        /** 记录模型命令输入中的提供方。 */
        private String provider;

        /** 记录模型命令输入中的模型。 */
        private String model;
    }

    /**
     * 执行来源库相关逻辑。
     *
     * @param target target 参数。
     * @return 返回Tap结果。
     */
    private String handleTap(String target) throws Exception {
        String action = firstToken(target);
        if (StrUtil.isBlank(action)
                || GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            List<TapRecord> taps = skillHubService.listTaps();
            if (taps.isEmpty()) {
                return "当前没有自定义 taps。";
            }
            StringBuilder buffer = new StringBuilder();
            for (TapRecord tap : taps) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(tap.getRepo())
                        .append(" path=")
                        .append(StrUtil.blankToDefault(tap.getPath(), ""));
            }
            return buffer.toString();
        }
        if (GatewayCommandConstants.ACTION_ADD.equalsIgnoreCase(action)) {
            String[] parts = target.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalStateException("用法：/skills tap add <owner/repo> [path]");
            }
            return skillHubService.addTap(parts[1], parts.length > 2 ? parts[2] : null);
        }
        if (GatewayCommandConstants.ACTION_REMOVE.equalsIgnoreCase(action)
                || GatewayCommandConstants.ACTION_DELETE.equalsIgnoreCase(action)) {
            String[] parts = target.split("\\s+");
            if (parts.length < 2) {
                throw new IllegalStateException("用法：/skills tap remove <owner/repo>");
            }
            return skillHubService.removeTap(parts[1]);
        }
        throw new IllegalStateException("Unsupported tap action: " + action);
    }

    /**
     * 格式化Browse。
     *
     * @param result 结果响应或执行结果。
     * @return 返回Browse结果。
     */
    private String formatBrowse(SkillBrowseResult result) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("skills hub browse page ")
                .append(result.getPage())
                .append("/")
                .append(
                        Math.max(
                                1,
                                (result.getTotal() + result.getPageSize() - 1)
                                        / result.getPageSize()))
                .append('\n');
        for (SkillMeta item : result.getItems()) {
            buffer.append("- ")
                    .append(item.getName())
                    .append(" [")
                    .append(item.getSource())
                    .append("/")
                    .append(item.getTrustLevel())
                    .append("]: ")
                    .append(item.getDescription())
                    .append('\n');
        }
        return buffer.toString().trim();
    }

    /**
     * 格式化搜索。
     *
     * @param result 结果响应或执行结果。
     * @return 返回搜索结果。
     */
    private String formatSearch(SkillBrowseResult result) {
        StringBuilder buffer = new StringBuilder();
        for (SkillMeta item : result.getItems()) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append("- ")
                    .append(item.getName())
                    .append(" [")
                    .append(item.getSource())
                    .append("/")
                    .append(item.getTrustLevel())
                    .append("]")
                    .append(" -> ")
                    .append(item.getIdentifier());
        }
        return buffer.length() == 0 ? "未找到匹配技能。" : buffer.toString();
    }

    /**
     * 格式化中心Install Records。
     *
     * @param records records 参数。
     * @return 返回中心Install Records结果。
     */
    private String formatHubInstallRecords(List<HubInstallRecord> records) {
        if (records == null || records.isEmpty()) {
            return "没有技能变更。";
        }
        StringBuilder buffer = new StringBuilder();
        for (HubInstallRecord record : records) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append("- ")
                    .append(record.getName())
                    .append(" [")
                    .append(record.getSource())
                    .append("/")
                    .append(record.getTrustLevel())
                    .append("]")
                    .append(" path=")
                    .append(record.getInstallPath());
            Object status = record.getMetadata().get("status");
            if (status != null) {
                buffer.append(" status=").append(status);
            }
        }
        return buffer.toString();
    }

    /**
     * 格式化审计。
     *
     * @param results results响应或执行结果。
     * @return 返回审计结果。
     */
    private String formatAudit(List<ScanResult> results) {
        if (results == null || results.isEmpty()) {
            return "没有可审计的 hub 技能。";
        }
        StringBuilder buffer = new StringBuilder();
        for (ScanResult result : results) {
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(result.getSkillName())
                    .append(" -> ")
                    .append(result.getVerdict())
                    .append('\n');
            buffer.append(result.getSummary());
        }
        return buffer.toString();
    }

    /**
     * 判断是否存在Flag。
     *
     * @param raw 原始输入值。
     * @param flag flag 参数。
     * @return 如果Flag满足条件则返回 true，否则返回 false。
     */
    private boolean hasFlag(String raw, String flag) {
        return (" " + StrUtil.nullToEmpty(raw) + " ").contains(" " + flag + " ");
    }

    /**
     * 解析Option。
     *
     * @param raw 原始输入值。
     * @param option 选项参数。
     * @param defaultValue 默认值参数。
     * @return 返回解析后的Option。
     */
    private String parseOption(String raw, String option, String defaultValue) {
        String[] parts = StrUtil.nullToEmpty(raw).split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (option.equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return defaultValue;
    }

    /**
     * 解析Int Option。
     *
     * @param raw 原始输入值。
     * @param option 选项参数。
     * @param defaultValue 默认值参数。
     * @return 返回解析后的Int Option。
     */
    private int parseIntOption(String raw, String option, int defaultValue) {
        try {
            return Integer.parseInt(parseOption(raw, option, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 剥离Options。
     *
     * @param raw 原始输入值。
     * @param optionNames 选项Names参数。
     * @return 返回strip Options结果。
     */
    private String stripOptions(String raw, String... optionNames) {
        String[] parts = StrUtil.nullToEmpty(raw).split("\\s+");
        List<String> kept = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            boolean skip = false;
            for (String optionName : optionNames) {
                if (optionName.equals(parts[i])) {
                    skip = true;
                    if (i + 1 < parts.length) {
                        i++;
                    }
                    break;
                }
            }
            if (!skip && i < parts.length && StrUtil.isNotBlank(parts[i])) {
                kept.add(parts[i]);
            }
        }
        return String.join(" ", kept).trim();
    }

    /**
     * 执行firsttoken相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回first token结果。
     */
    private String firstToken(String raw) {
        String[] parts = StrUtil.nullToEmpty(raw).trim().split("\\s+", 2);
        return parts.length == 0 ? "" : parts[0];
    }

    /** 生成帮助文本。 */
    private GatewayReply registeredUnimplementedReply(CommandDescriptor descriptor) {
        GatewayReply reply = GatewayReply.error("命令已登记但当前运行时未启用或不支持：" + descriptor.slashName());
        reply.getRuntimeMetadata().put("command_status", "registered_unimplemented");
        reply.getRuntimeMetadata().put("command", descriptor.getName());
        return reply;
    }

    /**
     * 执行help文本相关逻辑。
     *
     * @return 返回help Text结果。
     */
    private String helpText() {
        return String.join(
                "\n",
                Arrays.asList(
                        helpLine(GatewayCommandConstants.SLASH_NEW, "创建并切换到新会话"),
                        helpLine(GatewayCommandConstants.SLASH_RESET, "重置当前会话并重新开始"),
                        helpLine(GatewayCommandConstants.SLASH_RETRY, "重新执行上一条用户消息"),
                        helpLine(GatewayCommandConstants.SLASH_UNDO, "撤销上一轮对话"),
                        helpLine(GatewayCommandConstants.SLASH_BRANCH + " [name]", "从当前会话创建分支"),
                        helpLine(
                                GatewayCommandConstants.SLASH_RESUME + " <session-or-branch>",
                                "恢复指定会话或分支"),
                        helpLine(GatewayCommandConstants.SLASH_SESSIONS + " [query]", "浏览并搜索历史会话"),
                        helpLine(GatewayCommandConstants.SLASH_WHOAMI, "查看当前 slash 命令访问身份"),
                        helpLine(
                                GatewayCommandConstants.SLASH_COMMANDS + " [page]",
                                "浏览全部 slash 命令"),
                        helpLine(GatewayCommandConstants.SLASH_INSIGHTS, "查看使用洞察与运行摘要"),
                        helpLine(GatewayCommandConstants.SLASH_DEBUG + " [status]", "查看脱敏调试诊断摘要"),
                        helpLine(
                                GatewayCommandConstants.SLASH_TITLE + " [clear|新标题]",
                                "查看、设置或清空当前会话标题"),
                        helpLine(GatewayCommandConstants.SLASH_STATUS, "查看当前会话状态"),
                        helpLine(GatewayCommandConstants.SLASH_USAGE, "查看当前会话运行信息"),
                        helpLine(
                                GatewayCommandConstants.SLASH_GOAL
                                        + " [status|pause|resume|clear|<目标> --max-turns N|--max N]",
                                "设置跨轮长目标并由 judge 驱动自动继续"),
                        helpLine(
                                GatewayCommandConstants.SLASH_BUSY
                                        + " [status|queue|steer|interrupt|reject]",
                                "查看或切换运行中输入策略"),
                        helpLine(
                                GatewayCommandConstants.SLASH_QUEUE + " <prompt>", "将提示排到当前任务之后执行"),
                        helpLine(
                                GatewayCommandConstants.SLASH_STEER + " <prompt>",
                                "向运行中任务注入修正；空闲时按普通提示执行"),
                        helpLine(GatewayCommandConstants.SLASH_RESTART, "等待运行中任务 drain 后重启网关"),
                        helpLine(GatewayCommandConstants.SLASH_STOP, "停止当前任务和后台进程"),
                        helpLine(
                                GatewayCommandConstants.SLASH_YOLO + " [status|on|off]",
                                "查询或设置当前会话的危险命令自动批准模式"),
                        helpLine(
                                GatewayCommandConstants.SLASH_PERSONALITY + " [name|none]",
                                "查看或切换人格"),
                        helpLine(
                                GatewayCommandConstants.SLASH_VERSION + " [check|update]",
                                "查看版本或执行更新"),
                        helpLine(GatewayCommandConstants.SLASH_UPDATE, "执行应用更新"),
                        helpLine(
                                GatewayCommandConstants.SLASH_MODEL
                                        + " [--global] [provider:]<model>|clear",
                                "查看或切换模型"),
                        helpLine(
                                GatewayCommandConstants.SLASH_FAST + " [fast|normal|status]",
                                "查看或切换当前会话快速模式"),
                        helpLine(
                                GatewayCommandConstants.SLASH_REASONING
                                        + " [level|reset|show|hide]",
                                "查看或切换 reasoning 强度和展示"),
                        helpLine(
                                GatewayCommandConstants.SLASH_TOOLS
                                        + " [list|enable|disable] [name...]",
                                "查看或管理工具开关"),
                        helpLine(GatewayCommandConstants.SLASH_TOOLSETS, "列出可用工具集"),
                        helpLine(
                                GatewayCommandConstants.SLASH_BROWSER
                                        + " [status|connect|disconnect <session-id>]",
                                "管理浏览器自动化运行时"),
                        helpLine(
                                GatewayCommandConstants.SLASH_SKILLS
                                        + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload]",
                                "管理本地技能与 Skills Hub"),
                        helpLine(
                                GatewayCommandConstants.SLASH_CURATOR
                                        + " [status|list|improvements|run|pause|resume]",
                                "管理技能后台维护状态与运行"),
                        helpLine(GatewayCommandConstants.SLASH_PLUGINS, "查看插件加载状态"),
                        helpLine(GatewayCommandConstants.SLASH_RELOAD_SKILLS, "重新扫描本地技能目录"),
                        helpLine(
                                GatewayCommandConstants.SLASH_RELOAD_MCP
                                        + " [now|always]；确认：/approve [确认编号]|/always|/cancel",
                                "重新加载 MCP 工具并刷新工具变更基线"),
                        helpLine(GatewayCommandConstants.SLASH_CONFIRM, "查看当前待确认 slash 命令"),
                        helpLine(
                                GatewayCommandConstants.SLASH_AGENT
                                        + " [name|list|create|show|model|tools|skills|memory]",
                                "切换或管理当前会话 Agent"),
                        helpLine(
                                GatewayCommandConstants.SLASH_CRON
                                        + " [list [--all]|inspect|show|next|upcoming|guide|tutorial|capabilities|policy|add|edit|pause|disable|stop|resume|enable|start|remove|delete|run|trigger|retry|rerun|history|status|tick]",
                                "管理定时任务"),
                        helpLine(
                                GatewayCommandConstants.SLASH_RECAP + " [limit]", "显示恢复会话用的紧凑历史摘要"),
                        helpLine(
                                GatewayCommandConstants.SLASH_TRAJECTORY + " [user-query]",
                                "导出会话 trajectory JSON"),
                        helpLine(
                                GatewayCommandConstants.SLASH_TRAJECTORY
                                        + " save [--failed] [user-query]",
                                "追加保存 trajectory JSONL 到 runtime/artifacts"),
                        helpLine(
                                GatewayCommandConstants.SLASH_COMPACT
                                        + " [focus]（兼容 "
                                        + GatewayCommandConstants.SLASH_COMPRESS
                                        + "）",
                                "压缩当前会话上下文"),
                        helpLine(
                                GatewayCommandConstants.SLASH_ROLLBACK
                                        + " [latest|checkpoint-id|number]",
                                "回滚到指定 checkpoint"),
                        helpLine(GatewayCommandConstants.SLASH_SETHOME, "将当前聊天设为 home channel"),
                        helpLine(
                                GatewayCommandConstants.SLASH_PAIRING
                                        + " [claim-admin|pending|approve|revoke|approved]",
                                "管理渠道配对与管理员授权"),
                        helpLine(
                                GatewayCommandConstants.SLASH_APPROVE
                                        + " [#序号|审批ID|all] [session|always]",
                                "批准待审批危险命令"),
                        helpLine(
                                GatewayCommandConstants.SLASH_APPROVE
                                        + " list|status|clear session|clear always|clear all",
                                "查看或清理审批授权"),
                        helpLine(
                                GatewayCommandConstants.SLASH_DENY + " [#序号|审批ID|all]",
                                "拒绝待审批危险命令"),
                        helpLine(
                                GatewayCommandConstants.SLASH_DENY + " list|status|all",
                                "查看或批量拒绝待审批命令"),
                        helpLine(GatewayCommandConstants.SLASH_PLATFORMS, "查看平台连接与授权状态"),
                        helpLine(GatewayCommandConstants.SLASH_PLATFORM, "查看平台连接与授权状态"),
                        helpLine(GatewayCommandConstants.SLASH_HELP, "显示帮助信息"),
                        registryHelpLine("background"),
                        registryHelpLine("tasks"),
                        registryHelpLine("statusbar"),
                        registryHelpLine("footer"),
                        registryHelpLine("copy"),
                        registryHelpLine("paste"),
                        registryHelpLine("image"),
                        registryHelpLine("handoff"),
                        registryHelpLine("subgoal")));
    }

    /**
     * 执行注册表Help行相关逻辑。
     *
     * @param commandName 命令名称参数。
     * @return 返回注册表Help Line结果。
     */
    private String registryHelpLine(String commandName) {
        CommandDescriptor descriptor = CommandRegistry.get(commandName);
        return helpLine(descriptor.slashName(), descriptor.getDescription());
    }

    /**
     * 执行help行相关逻辑。
     *
     * @param usage 用量参数。
     * @param description 描述参数。
     * @return 返回help Line结果。
     */
    private String helpLine(String usage, String description) {
        return usage + " - " + description;
    }

    /**
     * 格式化用量。
     *
     * @param session 会话参数。
     * @return 返回用量结果。
     */
    private String formatUsage(SessionRecord session) {
        RuntimeSettingsService.ResolvedModel resolved =
                runtimeSettingsService.resolveEffectiveModel(session);
        StringBuilder buffer = new StringBuilder();
        buffer.append("session=").append(session.getSessionId()).append('\n');
        buffer.append("branch=").append(session.getBranchName()).append('\n');
        buffer.append("agent=")
                .append(StrUtil.blankToDefault(session.getActiveAgentName(), "default"))
                .append('\n');
        buffer.append("effective_provider=")
                .append(StrUtil.blankToDefault(resolved.getProvider(), "default"))
                .append('\n');
        buffer.append("effective_model=")
                .append(StrUtil.blankToDefault(resolved.getModel(), "default"))
                .append('\n');
        buffer.append("last_provider=")
                .append(StrUtil.blankToDefault(session.getLastResolvedProvider(), ""))
                .append('\n');
        buffer.append("last_model=")
                .append(StrUtil.blankToDefault(session.getLastResolvedModel(), ""))
                .append('\n');
        buffer.append("last_input_tokens=").append(session.getLastInputTokens()).append('\n');
        buffer.append("last_output_tokens=").append(session.getLastOutputTokens()).append('\n');
        buffer.append("last_reasoning_tokens=")
                .append(session.getLastReasoningTokens())
                .append('\n');
        buffer.append("last_cache_read_tokens=")
                .append(session.getLastCacheReadTokens())
                .append('\n');
        buffer.append("last_cache_write_tokens=")
                .append(session.getLastCacheWriteTokens())
                .append('\n');
        buffer.append("last_total_tokens=").append(session.getLastTotalTokens()).append('\n');
        buffer.append("cumulative_input_tokens=")
                .append(session.getCumulativeInputTokens())
                .append('\n');
        buffer.append("cumulative_output_tokens=")
                .append(session.getCumulativeOutputTokens())
                .append('\n');
        buffer.append("cumulative_reasoning_tokens=")
                .append(session.getCumulativeReasoningTokens())
                .append('\n');
        buffer.append("cumulative_cache_read_tokens=")
                .append(session.getCumulativeCacheReadTokens())
                .append('\n');
        buffer.append("cumulative_cache_write_tokens=")
                .append(session.getCumulativeCacheWriteTokens())
                .append('\n');
        buffer.append("cumulative_total_tokens=")
                .append(session.getCumulativeTotalTokens())
                .append('\n');
        buffer.append("last_usage_at=")
                .append(
                        session.getLastUsageAt() > 0
                                ? DateUtil.formatDateTime(
                                        new java.util.Date(session.getLastUsageAt()))
                                : "");
        return buffer.toString();
    }

    /**
     * 格式化检查点状态。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回检查点状态。
     */
    private String formatCheckpointStatus(String sourceKey) throws Exception {
        Map<String, Object> status = checkpointService.status(sourceKey);
        return "checkpoint_count="
                + status.get("checkpoint_count")
                + "\nmissing_dirs="
                + status.get("missing_dirs")
                + "\ntotal_size="
                + formatBytes(asLong(status.get("total_size_bytes")))
                + "\nmax_checkpoints_per_source="
                + status.get("max_checkpoints_per_source")
                + "\nmax_file_size_mb="
                + status.get("max_file_size_mb")
                + "\nlatest_created="
                + formatTimestamp(asLong(status.get("latest_created_at")));
    }

    /**
     * 格式化检查点Prune。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回检查点Prune结果。
     */
    private String formatCheckpointPrune(String sourceKey) throws Exception {
        Map<String, Object> result = checkpointService.prune(sourceKey);
        return "已清理 checkpoint store。"
                + "\ndeleted_missing="
                + result.get("deleted_missing")
                + "\ndeleted_overflow="
                + result.get("deleted_overflow")
                + "\nbytes_freed="
                + formatBytes(asLong(result.get("bytes_freed")))
                + "\nremaining="
                + result.get("checkpoint_count");
    }

    /**
     * 格式化检查点Clear。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回检查点Clear结果。
     */
    private String formatCheckpointClear(String sourceKey) throws Exception {
        Map<String, Object> result = checkpointService.clear(sourceKey);
        return "已删除当前来源的全部 checkpoint。"
                + "\ndeleted="
                + result.get("deleted")
                + "\nbytes_freed="
                + formatBytes(asLong(result.get("bytes_freed")))
                + "\nremaining="
                + result.get("checkpoint_count");
    }

    /**
     * 判断是否检查点Clear命令。
     *
     * @param args 工具或命令参数。
     * @return 如果检查点Clear命令满足条件则返回 true，否则返回 false。
     */
    private boolean isCheckpointClearCommand(String args) {
        String first = firstToken(args);
        return "clear".equalsIgnoreCase(first) || "clear-all".equalsIgnoreCase(first);
    }

    /**
     * 判断是否为 checkpoint 列表别名，保持 CLI、TUI 和消息网关的 rollback 语义一致。
     *
     * @param args 工具或命令参数。
     * @return list 或 ls 返回 true。
     */
    private boolean isCheckpointListCommand(String args) {
        String first = firstToken(args);
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
     * 格式化Bytes。
     *
     * @param bytes 字节参数。
     * @return 返回Bytes结果。
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = new String[] {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        while (value >= 1024D && unitIndex < units.length - 1) {
            value = value / 1024D;
            unitIndex++;
        }
        return String.format(
                java.util.Locale.ROOT, "%.1f %s", Double.valueOf(value), units[unitIndex]);
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
        } catch (Exception ignored) {
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
                } catch (Exception ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
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

    /** 承载定时任务FlagOptions相关状态和辅助逻辑。 */
    private static class CronFlagOptions {
        /** 记录定时任务FlagOptions中的名称。 */
        private String name;

        /** 记录定时任务FlagOptions中的deliver。 */
        private String deliver;

        /** 记录定时任务FlagOptions中的deliver聊天标识。 */
        private String deliverChatId;

        /** 记录定时任务FlagOptions中的deliverThread标识。 */
        private String deliverThreadId;

        /** 是否启用clearDeliver聊天标识。 */
        private boolean clearDeliverChatId;

        /** 是否启用clearDeliverThread标识。 */
        private boolean clearDeliverThreadId;

        /** 记录定时任务FlagOptions中的repeat。 */
        private Integer repeat;

        /** 是否启用clearRepeat。 */
        private boolean clearRepeat;

        /** 记录定时任务FlagOptions中的限制。 */
        private Integer limit;

        /** 记录定时任务FlagOptions中的原因。 */
        private String reason;

        /** 记录定时任务FlagOptions中的trigger类型。 */
        private String triggerType;

        /** 保存技能集合，维持调用顺序或去重语义。 */
        private final List<String> skills = new ArrayList<String>();

        /** 保存add技能集合，维持调用顺序或去重语义。 */
        private final List<String> addSkills = new ArrayList<String>();

        /** 保存remove技能集合，维持调用顺序或去重语义。 */
        private final List<String> removeSkills = new ArrayList<String>();

        /** 是否启用clear技能。 */
        private boolean clearSkills;

        /** 是否启用全部。 */
        private boolean all;

        /** 记录定时任务FlagOptions中的提示词。 */
        private String prompt;

        /** 记录定时任务FlagOptions中的调度。 */
        private String schedule;

        /** 记录定时任务FlagOptions中的script。 */
        private String script;

        /** 记录定时任务FlagOptions中的workdir。 */
        private String workdir;

        /** 记录定时任务FlagOptions中的上下文From。 */
        private String contextFrom;

        /** 记录定时任务FlagOptions中的dependsOn。 */
        private String dependsOn;

        /** 标记是否启用Toolsets。 */
        private String enabledToolsets;

        /** 记录定时任务FlagOptions中的模型。 */
        private String model;

        /** 记录定时任务FlagOptions中的提供方。 */
        private String provider;

        /** 记录定时任务FlagOptions中的基础URL。 */
        private String baseUrl;

        /** 记录定时任务FlagOptions中的状态。 */
        private String status;

        /** 记录定时任务FlagOptions中的状态。 */
        private String state;

        /** 记录定时任务FlagOptions中的paused原因。 */
        private String pausedReason;

        /** 是否启用clear模型。 */
        private boolean clearModel;

        /** 是否启用clear提供方。 */
        private boolean clearProvider;

        /** 是否启用clear基础URL。 */
        private boolean clearBaseUrl;

        /** 是否启用clearScript。 */
        private boolean clearScript;

        /** 是否启用clearWorkdir。 */
        private boolean clearWorkdir;

        /** 是否启用clear上下文From。 */
        private boolean clearContextFrom;

        /** 是否启用clearDependsOn。 */
        private boolean clearDependsOn;

        /** 是否启用clearToolsets。 */
        private boolean clearToolsets;

        /** 是否启用noAgent。 */
        private boolean noAgent;

        /** 是否启用Agent。 */
        private boolean agent;

        /** 是否启用wrap响应。 */
        private boolean wrapResponse;

        /** 是否启用原始。 */
        private boolean raw;

        /** 是否启用JSON。 */
        private boolean json;

        /** 保存positionals集合，维持调用顺序或去重语义。 */
        private final List<String> positionals = new ArrayList<String>();
    }

    /** 承载定时任务Edit请求相关状态和辅助逻辑。 */
    private static class CronEditRequest {
        /** 记录定时任务Edit请求中的任务标识。 */
        private final String jobId;

        /** 保存正文映射，便于按键快速查询。 */
        private final Map<String, Object> body;

        /**
         * 创建定时任务Edit请求实例，并注入运行所需依赖。
         *
         * @param jobId job标识。
         * @param body 请求体或消息正文内容。
         */
        private CronEditRequest(String jobId, Map<String, Object> body) {
            this.jobId = jobId;
            this.body = body;
        }
    }
}
