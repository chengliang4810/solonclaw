package com.jimuqu.solon.claw.gateway.command;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.AgentRunStopResult;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.model.CompressionOutcome;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
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
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.cli.acp.AcpStdioServer;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.model.SkillBrowseResult;
import com.jimuqu.solon.claw.skillhub.model.SkillMeta;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.CronSupport;
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
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 默认 slash 命令实现，统一承接 Jimuqu 风格的会话控制命令。 */
public class DefaultCommandService implements CommandService {
    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** 工具注册表。 */
    private final ToolRegistry toolRegistry;

    /** 本地技能服务。 */
    private final LocalSkillService localSkillService;

    /** 定时任务仓储。 */
    private final CronJobRepository cronJobRepository;

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

    private final SkillHubService skillHubService;

    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 全局设置仓储。 */
    private final GlobalSettingRepository globalSettingRepository;

    /** 进程注册表。 */
    private final ProcessRegistry processRegistry;

    /** 运行时设置服务。 */
    private final RuntimeSettingsService runtimeSettingsService;

    private final DisplaySettingsService displaySettingsService;
    private final AppUpdateService appUpdateService;
    private final DangerousCommandApprovalService dangerousCommandApprovalService;
    private final AgentRunControlService agentRunControlService;
    private final AgentProfileService agentProfileService;
    private final AgentRunRepository agentRunRepository;
    private final KanbanService kanbanService;
    private final DashboardMcpService dashboardMcpService;
    private final GoalService goalService;
    private final SessionArtifactService sessionArtifactService;
    private final SlashConfirmService slashConfirmService;
    private final DefaultCronScheduler cronScheduler;
    private final GatewayRestartCoordinator gatewayRestartCoordinator;

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
            KanbanService kanbanService) {
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
                kanbanService,
                null);
    }

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
            KanbanService kanbanService,
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
                kanbanService,
                dashboardMcpService,
                null);
    }

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
            KanbanService kanbanService,
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
                kanbanService,
                dashboardMcpService,
                goalService,
                new SessionArtifactService());
    }

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
            KanbanService kanbanService,
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
                kanbanService,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                null);
    }

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
            KanbanService kanbanService,
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
                kanbanService,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                null);
    }

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
            KanbanService kanbanService,
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
                kanbanService,
                dashboardMcpService,
                goalService,
                sessionArtifactService,
                cronScheduler,
                gatewayRestartCoordinator,
                null);
    }

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
            KanbanService kanbanService,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler cronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService) {
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
        this.kanbanService = kanbanService;
        this.dashboardMcpService = dashboardMcpService;
        this.goalService = goalService == null ? new GoalService(sessionRepository) : goalService;
        this.sessionArtifactService =
                sessionArtifactService == null ? new SessionArtifactService() : sessionArtifactService;
        this.slashConfirmService =
                slashConfirmService == null
                        ? new SlashConfirmService(globalSettingRepository)
                        : slashConfirmService;
        this.cronScheduler = cronScheduler;
        this.gatewayRestartCoordinator =
                gatewayRestartCoordinator == null ? new GatewayRestartCoordinator() : gatewayRestartCoordinator;
    }

    /** 判断当前命令是否由默认命令服务承接。 */
    @Override
    public boolean supports(String commandName) {
        return Arrays.asList(
                        GatewayCommandConstants.COMMAND_NEW,
                        GatewayCommandConstants.COMMAND_RESET,
                        GatewayCommandConstants.COMMAND_RETRY,
                        GatewayCommandConstants.COMMAND_UNDO,
                        GatewayCommandConstants.COMMAND_BRANCH,
                        GatewayCommandConstants.COMMAND_RESUME,
                        GatewayCommandConstants.COMMAND_TITLE,
                        GatewayCommandConstants.COMMAND_STATUS,
                        GatewayCommandConstants.COMMAND_USAGE,
                        GatewayCommandConstants.COMMAND_BUSY,
                        GatewayCommandConstants.COMMAND_QUEUE,
                        GatewayCommandConstants.COMMAND_STEER,
                        GatewayCommandConstants.COMMAND_RESTART,
                        GatewayCommandConstants.COMMAND_REASONING,
                        GatewayCommandConstants.COMMAND_STOP,
                        GatewayCommandConstants.COMMAND_YOLO,
                        GatewayCommandConstants.COMMAND_PERSONALITY,
                        GatewayCommandConstants.COMMAND_VERSION,
                        GatewayCommandConstants.COMMAND_MODEL,
                        GatewayCommandConstants.COMMAND_TOOLS,
                        GatewayCommandConstants.COMMAND_SKILLS,
                        GatewayCommandConstants.COMMAND_RELOAD_MCP,
                        GatewayCommandConstants.COMMAND_ACP,
                        GatewayCommandConstants.COMMAND_CRON,
                        GatewayCommandConstants.COMMAND_KANBAN,
                        GatewayCommandConstants.COMMAND_GOAL,
                        GatewayCommandConstants.COMMAND_RECAP,
                        GatewayCommandConstants.COMMAND_TRAJECTORY,
                        GatewayCommandConstants.COMMAND_PLATFORMS,
                        GatewayCommandConstants.COMMAND_COMPRESS,
                        GatewayCommandConstants.COMMAND_COMPACT,
                        GatewayCommandConstants.COMMAND_ROLLBACK,
                        GatewayCommandConstants.COMMAND_SETHOME,
                        GatewayCommandConstants.COMMAND_PAIRING,
                        GatewayCommandConstants.COMMAND_APPROVE,
                        GatewayCommandConstants.COMMAND_DENY,
                        GatewayCommandConstants.COMMAND_ALWAYS,
                        GatewayCommandConstants.COMMAND_CANCEL,
                        GatewayCommandConstants.COMMAND_CONFIRM,
                        GatewayCommandConstants.COMMAND_AGENT,
                        GatewayCommandConstants.COMMAND_HELP)
                .contains(commandName);
    }

    /** 处理单条 slash 命令。 */
    @Override
    public GatewayReply handle(GatewayMessage message, String commandLine) throws Exception {
        String withoutSlash = commandLine.substring(1).trim();
        String[] parts = withoutSlash.split("\\s+", 2);
        String command = parts[0].toLowerCase();
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

        if (GatewayCommandConstants.COMMAND_TRAJECTORY.equals(command)) {
            return handleTrajectory(message, args);
        }

        if (GatewayCommandConstants.COMMAND_NEW.equals(command)
                || GatewayCommandConstants.COMMAND_RESET.equals(command)) {
            SessionRecord created = sessionRepository.bindNewSession(message.sourceKey());
            GatewayReply reply = GatewayReply.ok("已创建新会话：" + created.getSessionId());
            reply.setSessionId(created.getSessionId());
            reply.setBranchName(created.getBranchName());
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
                        "用法：" + GatewayCommandConstants.SLASH_RESUME + " <session-id|id-prefix|title|branch>");
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

        if (GatewayCommandConstants.COMMAND_TOOLS.equals(command)) {
            return handleTools(message, args);
        }

        if (GatewayCommandConstants.COMMAND_SKILLS.equals(command)) {
            return handleSkills(message, args);
        }

        if (GatewayCommandConstants.COMMAND_RELOAD_MCP.equals(command)) {
            return handleReloadMcp(message, args);
        }

        if (GatewayCommandConstants.COMMAND_ACP.equals(command)) {
            return handleAcp(args);
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

        if (GatewayCommandConstants.COMMAND_KANBAN.equals(command)) {
            if (kanbanService == null) {
                return GatewayReply.error("Kanban service is not available.");
            }
            return GatewayReply.ok(kanbanService.handleCommand(args, message.getUserName()));
        }

        if (isCompressionCommand(command)) {
            SessionRecord session = requireSession(message.sourceKey());
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
                                StrUtil.isBlank(args)
                                        ? "已完成当前会话的上下文压缩。"
                                        : "已按关注主题完成当前会话的上下文压缩。");
            } else {
                reply = GatewayReply.ok("当前会话没有足够的可压缩历史，已跳过上下文压缩。");
            }
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
            return reply;
        }

        if (GatewayCommandConstants.COMMAND_ROLLBACK.equals(command)) {
            if (StrUtil.isBlank(args)) {
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
                // fall through
            }
            return GatewayReply.ok(
                    "已回滚到指定 checkpoint：" + checkpointService.rollback(args).getCheckpointId());
        }

        if (GatewayCommandConstants.COMMAND_PLATFORMS.equals(command)) {
            return GatewayReply.ok(
                    gatewayAuthorizationService.formatPlatformStatus(deliveryService.statuses()));
        }

        return GatewayReply.ok(helpText());
    }

    @Override
    public GatewayReply handle(
            GatewayMessage message, String commandLine, ConversationEventSink eventSink)
            throws Exception {
        if (eventSink == null) {
            eventSink = ConversationEventSink.noop();
        }

        String withoutSlash = commandLine.substring(1).trim();
        String[] parts = withoutSlash.split("\\s+", 2);
        String command = parts[0].toLowerCase();
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

    private String textMetadata(GatewayReply reply, String key) {
        if (reply == null || reply.getRuntimeMetadata() == null) {
            return "";
        }
        Object value = reply.getRuntimeMetadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private GatewayReply handleStop(GatewayMessage message) throws Exception {
        AgentRunStopResult stopResult =
                agentRunControlService == null
                        ? AgentRunStopResult.none()
                        : agentRunControlService.stop(message.sourceKey());
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

    private GatewayReply handleRestart(GatewayMessage message) throws Exception {
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        int activeRuns =
                agentRunControlService == null ? 0 : agentRunControlService.runningRunCount();
        GatewayRestartCoordinator.RestartRequest request =
                gatewayRestartCoordinator.requestRestartDrain(message.sourceKey(), activeRuns);
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
        reply.getRuntimeMetadata().put("restart_first_request", Boolean.valueOf(request.isFirstRequest()));
        reply.getRuntimeMetadata().put("restart_active_runs", Integer.valueOf(activeRuns));
        reply.getRuntimeMetadata()
                .put("restart_drain_timeout_seconds", Integer.valueOf(request.getDrainTimeoutSeconds()));
        if (session != null) {
            reply.setSessionId(session.getSessionId());
            reply.setBranchName(session.getBranchName());
        }
        return reply;
    }

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
                            state == null
                                    ? "No goal set."
                                    : "⏸ Goal paused: " + state.getGoal());
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

    private GatewayReply handleRecap(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        GatewayReply reply =
                GatewayReply.ok(sessionArtifactService.recapText(session, parsePositiveInt(args, 10)));
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        return reply;
    }

    private ResumeLookup resolveResumeTarget(String sourceKey, String rawReference) throws Exception {
        String reference = normalizeResumeReference(rawReference);
        if (StrUtil.isBlank(reference)) {
            return ResumeLookup.error(
                    "用法：" + GatewayCommandConstants.SLASH_RESUME + " <session-id|id-prefix|title|branch>");
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
                buffer.append('\n')
                        .append("- ")
                        .append(candidate.getSessionId());
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

    private static class ResumeLookup {
        private final SessionRecord session;
        private final String message;

        private ResumeLookup(SessionRecord session, String message) {
            this.session = session;
            this.message = message;
        }

        static ResumeLookup found(SessionRecord session) {
            return new ResumeLookup(session, "");
        }

        static ResumeLookup error(String message) {
            return new ResumeLookup(null, message);
        }

        SessionRecord getSession() {
            return session;
        }

        String getMessage() {
            return message;
        }
    }

    private GatewayReply handleTrajectory(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        String action = firstToken(raw);
        if ("save".equalsIgnoreCase(action)) {
            String tail = raw.length() <= action.length() ? "" : raw.substring(action.length()).trim();
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

    private boolean containsTrajectoryFailedFlag(String raw) {
        String[] parts = StrUtil.nullToEmpty(raw).trim().split("\\s+");
        for (String part : parts) {
            if ("--failed".equalsIgnoreCase(part) || "--incomplete".equalsIgnoreCase(part)) {
                return true;
            }
        }
        return false;
    }

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
                            java.util.Collections.singletonMap("args", StrUtil.nullToEmpty(args))));
            event.setCreatedAt(System.currentTimeMillis());
            agentRunRepository.appendEvent(event);
        } catch (Exception ignored) {
        }
    }

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

    private boolean isCompressionCommand(String command) {
        return GatewayCommandConstants.COMMAND_COMPRESS.equals(command)
                || GatewayCommandConstants.COMMAND_COMPACT.equals(command);
    }

    private GatewayReply handleTitle(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        if (StrUtil.isBlank(raw)) {
            GatewayReply reply =
                    GatewayReply.ok(
                            "当前会话标题："
                                    + StrUtil.blankToDefault(session.getTitle(), "(未设置)"));
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

    private GatewayReply handleReloadMcp(GatewayMessage message, String args) throws Exception {
        String action = firstToken(args);
        if (StrUtil.isBlank(action)) {
            if (!appConfig.getApprovals().isMcpReloadConfirm()
                    || slashConfirmService.isAlwaysConfirmed(GatewayCommandConstants.COMMAND_RELOAD_MCP)) {
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

    private GatewayReply handleSlashConfirmChoice(
            GatewayMessage message, String args, String defaultChoice) throws Exception {
        SlashConfirmService.PendingConfirm pending = slashConfirmService.getPending(message.sourceKey());
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
        } else if (tokens.length > 1 && StrUtil.equalsIgnoreCase(tokens[1], pending.getConfirmId())) {
            confirmId = tokens[1];
        } else if (tokens.length > 1 && isSlashConfirmIdToken(tokens[1])) {
            confirmId = tokens[1];
        }
        String choice = normalizeSlashConfirmChoice(StrUtil.blankToDefault(choiceToken, defaultChoice));
        if (choice == null) {
            return GatewayReply.error("用法：/approve [确认编号]、/approve always [确认编号]、/always 或 /cancel");
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

    private String[] slashConfirmTokens(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(value)) {
            return new String[0];
        }
        return value.split("\\s+");
    }

    private boolean isSlashConfirmIdToken(String value) {
        String token = StrUtil.nullToEmpty(value).trim();
        return token.length() == 32 && token.matches("[0-9a-fA-F]+");
    }

    private boolean hasPendingSlashConfirm(GatewayMessage message) {
        return message != null && slashConfirmService.getPending(message.sourceKey()) != null;
    }

    private GatewayReply handleSlashConfirmStatus(GatewayMessage message) {
        SlashConfirmService.PendingConfirm pending =
                message == null ? null : slashConfirmService.getPending(message.sourceKey());
        if (pending == null) {
            return GatewayReply.ok("当前没有待确认的 slash 命令。");
        }
        return GatewayReply.ok("当前待确认 slash 命令：/" + pending.getCommand() + "\n" + formatSlashConfirmPrompt(pending));
    }

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

    private String normalizeSlashConfirmChoice(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim().toLowerCase();
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

    private String reloadMcpConfirmPrompt() {
        return "⚠️ /reload-mcp 会重新加载 MCP 工具并让下一轮模型请求重新发送完整工具 schema。";
    }

    private String rollbackClearConfirmPrompt() {
        return "⚠️ /rollback clear 会删除当前来源的全部 checkpoint 历史。";
    }

    private String formatSlashConfirmPrompt(SlashConfirmService.PendingConfirm confirm) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(confirm.getPrompt()).append("\n确认编号：").append(confirm.getConfirmId());
        if (confirm.isAllowAlways()) {
            buffer.append(
                    "\n回复 /approve [确认编号] 执行一次，/approve always [确认编号] 或 /always 执行并永久记住，/deny 或 /cancel 取消。");
        } else {
            buffer.append("\n回复 /approve [确认编号] 执行一次，/deny 或 /cancel 取消。");
        }
        return buffer.toString();
    }

    private GatewayReply executeReloadMcp(GatewayMessage message, boolean savedAlways) throws Exception {
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

    private GatewayReply handleAcp(String args) {
        String action = StrUtil.blankToDefault(firstToken(args), "status").toLowerCase();
        if (!"status".equals(action) && !"show".equals(action)) {
            return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_ACP + " [status]");
        }
        Map<String, Object> status = new AcpStdioServer(null, null, dashboardMcpService, appConfig).status();
        StringBuilder buffer = new StringBuilder();
        buffer.append("ACP adapter status\n");
        buffer.append("transport=").append(status.get("transport")).append('\n');
        buffer.append("command=").append(status.get("command")).append('\n');
        buffer.append("protocol_version=").append(status.get("protocol_version")).append('\n');
        Map<String, Object> capabilities = asMap(status.get("capabilities"));
        buffer.append("mcp_servers=").append(capabilities.get("mcp_servers")).append('\n');
        buffer.append("slash_commands=").append(capabilities.get("slash_commands")).append('\n');
        buffer.append("methods=").append(joinCollection(status.get("methods"))).append('\n');
        buffer.append("commands=").append(joinCommandNames(status.get("commands")));
        return GatewayReply.ok(buffer.toString());
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    private String joinCollection(Object value) {
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<String>();
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    items.add(String.valueOf(item));
                }
            }
            return String.join(", ", items);
        }
        return "";
    }

    private String joinCommandNames(Object value) {
        if (!(value instanceof Iterable)) {
            return "";
        }
        List<String> names = new ArrayList<String>();
        for (Object item : (Iterable<?>) value) {
            if (item instanceof Map) {
                Object name = ((Map<?, ?>) item).get("name");
                if (name != null) {
                    names.add("/" + String.valueOf(name));
                }
            }
        }
        return String.join(", ", names);
    }

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
            // Reload should succeed even if the best-effort history note cannot be saved.
        }
    }

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

    /** 处理工具开关命令。 */
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

    /** 处理技能命令。 */
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
            return GatewayReply.ok("已从 runtime 目录重新加载本地技能。");
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_SKILLS
                        + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload] ...");
    }

    /** 处理人格命令。 */
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

    /** 处理定时任务命令。 */
    private GatewayReply handleCron(GatewayMessage message, String args) throws Exception {
        boolean overview = StrUtil.isBlank(args);
        String[] parts = args.split("\\s+", 2);
        String action =
                parts.length == 0 || StrUtil.isBlank(parts[0])
                        ? GatewayCommandConstants.ACTION_LIST
                        : parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String tail = parts.length > 1 ? parts[1] : "";
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

        if (GatewayCommandConstants.ACTION_LIST.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            List<CronJobRecord> jobs = cronJobService.listBySource(message.sourceKey(), options.all);
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
                        "用法：" + GatewayCommandConstants.SLASH_CRON + " history <job-id> [--limit 20]");
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
                return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_CRON + " inspect <job-id>");
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
                return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_CRON + " pause <job-id> [--reason 原因]");
            }
            String jobId = options.positionals.get(0);
            String reason = StrUtil.blankToDefault(options.reason, joinTail(options.positionals, 1));
            cronJobService.pause(jobId, StrUtil.blankToDefault(reason, "paused by slash command"));
            return GatewayReply.ok("已暂停定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_RESUME.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_CRON + " resume <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.resume(jobId);
            return GatewayReply.ok("已恢复定时任务：" + jobId);
        }

        if (GatewayCommandConstants.ACTION_DELETE.equalsIgnoreCase(action)) {
            CronFlagOptions options = parseCronFlags(splitCommandLine(tail));
            if (options.positionals.isEmpty()) {
                return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_CRON + " remove <job-id>");
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
                return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_CRON + " run <job-id>");
            }
            String jobId = options.positionals.get(0);
            cronJobService.require(jobId);
            if (cronScheduler == null) {
                cronJobService.trigger(jobId);
                return GatewayReply.ok("已标记定时任务将在下一次 tick 执行：" + jobId);
            }
            cronScheduler.runNow(jobId);
            return GatewayReply.ok("已执行定时任务：" + jobId);
        }

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_CRON
                        + " [list [--all]|inspect|show|next|add|edit|pause|resume|remove|run|history|status|tick]");
    }

    private String cronOverview(String listText) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Cron 定时任务\n")
                .append("命令：\n")
                .append("/cron list - 查看启用中的定时任务\n")
                .append("/cron list --all - 查看全部定时任务，包括已暂停任务\n")
                .append("/cron inspect <job-id> - 查看单个任务详情\n")
                .append("/cron next [--all] [--limit 5] - 查看即将运行的任务\n")
                .append("/cron status [--all] - 查看任务计数、到期任务、最近失败与下次运行\n")
                .append("/cron add \"every 2h\" \"Check server status\" [--skill blogwatcher] - 创建定时任务\n")
                .append("/cron edit <job-id> --schedule \"every 4h\" --prompt \"New task\" - 编辑定时任务\n")
                .append("/cron edit <job-id> --skill blogwatcher --skill maps - 替换绑定技能\n")
                .append("/cron edit <job-id> --remove-skill blogwatcher - 移除绑定技能\n")
                .append("/cron edit <job-id> --clear-skills - 清空绑定技能\n")
                .append("/cron edit <job-id> --clear-script --clear-workdir --clear-context-from --clear-toolsets - 清空脚本、工作目录、上下文链和工具集限制\n")
                .append("/cron add \"every 2h\" \"task\" --model gpt-5.4 --provider default --base-url https://api.openai.com --no-wrap-response - 固定模型与投递包装\n")
                .append("/cron add \"every 2h\" \"task\" --deliver feishu --deliver-chat-id chat --deliver-thread-id thread - 指定投递会话与线程\n")
                .append("/cron edit <job-id> --clear-deliver-chat-id --clear-deliver-thread-id - 清空投递会话与线程\n")
                .append("/cron edit <job-id> --clear-model --clear-provider --clear-base-url - 清空任务级模型/provider/base URL 固定值\n")
                .append("/cron edit <job-id> --no-agent|--agent --wrap-response|--no-wrap-response - 切换脚本直投与回复包装\n")
                .append("/cron pause <job-id> [--reason 原因] - 暂停定时任务\n")
                .append("/cron resume <job-id> - 恢复定时任务\n")
                .append("/cron run <job-id> - 立即触发定时任务\n")
                .append("/cron tick - 立即执行一次 scheduler tick\n")
                .append("/cron history <job-id> [--limit 20] - 查看执行历史\n")
                .append("/cron remove <job-id> - 删除定时任务\n")
                .append('\n')
                .append(listText);
        return buffer.toString();
    }

    private String formatCronStatus(String sourceKey, boolean all) throws Exception {
        List<CronJobRecord> jobs = all ? cronJobService.listAll(true) : cronJobService.listBySource(sourceKey, true);
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
                if (job.getNextRunAt() > 0L && (nextRunAt <= 0L || job.getNextRunAt() < nextRunAt)) {
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
        buffer.append('\n').append("范围：").append(all ? "全部任务" : "当前会话");
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
                    buffer.append(" error=").append(StrUtil.maxLength(job.getLastError(), 120));
                }
                if (StrUtil.isNotBlank(job.getLastDeliveryError())) {
                    buffer.append(" delivery=").append(StrUtil.maxLength(job.getLastDeliveryError(), 120));
                }
            }
        }
        return buffer.toString();
    }

    private String formatCronNext(String sourceKey, boolean all, int limit) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        List<CronJobRecord> jobs = all ? cronJobService.listAll(true) : cronJobService.listBySource(sourceKey, true);
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
        buffer.append('\n').append("范围：").append(all ? "全部任务" : "当前会话");
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

    private void addRecentProblem(List<CronJobRecord> records, CronJobRecord job) {
        if (job == null || records.contains(job) || records.size() >= 5) {
            return;
        }
        records.add(job);
    }

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
                buffer.append('\n').append("Error: ").append(run.getError());
            }
            if (StrUtil.isNotBlank(run.getDeliveryError())) {
                buffer.append('\n').append("Delivery error: ").append(run.getDeliveryError());
            }
            if (StrUtil.isNotBlank(run.getOutput())) {
                buffer.append('\n').append("Output: ").append(StrUtil.maxLength(run.getOutput(), 300));
            }
        }
        return buffer.toString();
    }

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
                buffer.append('\n').append("Delivery failed: ").append(job.getLastDeliveryError());
            }
        }
        return buffer.toString();
    }

    private void appendCronListIterable(StringBuilder buffer, String label, Object values) {
        if (!(values instanceof Iterable)) {
            return;
        }
        String text = joinIterable((Iterable<?>) values, ", ");
        if (StrUtil.isNotBlank(text)) {
            buffer.append('\n').append(label).append(": ").append(text);
        }
    }

    private String formatCronRepeat(CronJobRecord job) {
        if (job.getRepeatTimes() <= 0) {
            return "∞";
        }
        return job.getRepeatCompleted() + "/" + job.getRepeatTimes();
    }

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
            } else if ("--clear-deliver-chat-id".equals(field) || "--clear-deliver_chat_id".equals(field)) {
                body.put("deliver_chat_id", null);
            } else if (field.startsWith("--deliver-thread-id ")) {
                body.put("deliver_thread_id", field.substring("--deliver-thread-id ".length()).trim());
            } else if (field.startsWith("--deliver_thread_id ")) {
                body.put("deliver_thread_id", field.substring("--deliver_thread_id ".length()).trim());
            } else if ("--clear-deliver-thread-id".equals(field) || "--clear-deliver_thread_id".equals(field)) {
                body.put("deliver_thread_id", null);
            } else if (field.startsWith("--repeat ")) {
                body.put("repeat", Integer.valueOf(field.substring("--repeat ".length()).trim()));
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
                body.put("enabled_toolsets", field.substring("--enabled-toolsets ".length()).trim());
            } else if ("--clear-toolsets".equals(field) || "--clear-enabled-toolsets".equals(field)) {
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
            }
        }
        return body;
    }

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
        putCronStringOption(body, "script", options.script);
        putCronStringOption(body, "workdir", options.workdir);
        putIfNotBlank(body, "context_from", options.contextFrom);
        putIfNotBlank(body, "depends_on", options.dependsOn);
        putIfNotBlank(body, "enabled_toolsets", options.enabledToolsets);
        putIfNotBlank(body, "model", options.model);
        putIfNotBlank(body, "provider", options.provider);
        putIfNotBlank(body, "base_url", options.baseUrl);
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
            } else if ("--clear-deliver-chat-id".equals(token) || "--clear-deliver_chat_id".equals(token)) {
                options.clearDeliverChatId = true;
            } else if (("--deliver-thread-id".equals(token) || "--deliver_thread_id".equals(token))
                    && i + 1 < tokens.size()) {
                options.deliverThreadId = tokens.get(++i);
            } else if ("--clear-deliver-thread-id".equals(token) || "--clear-deliver_thread_id".equals(token)) {
                options.clearDeliverThreadId = true;
            } else if ("--repeat".equals(token) && i + 1 < tokens.size()) {
                options.repeat = Integer.valueOf(tokens.get(++i));
            } else if ("--limit".equals(token) && i + 1 < tokens.size()) {
                options.limit = Integer.valueOf(tokens.get(++i));
            } else if ("--reason".equals(token) && i + 1 < tokens.size()) {
                options.reason = tokens.get(++i);
            } else if (("--skill".equals(token) || "-s".equals(token)) && i + 1 < tokens.size()) {
                options.skills.add(tokens.get(++i));
            } else if ("--skills".equals(token) && i + 1 < tokens.size()) {
                options.skills.add(tokens.get(++i));
            } else if (("--add-skill".equals(token) || "--add-skills".equals(token)) && i + 1 < tokens.size()) {
                options.addSkills.add(tokens.get(++i));
            } else if (("--remove-skill".equals(token) || "--remove-skills".equals(token)) && i + 1 < tokens.size()) {
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
            } else if ("--clear-toolsets".equals(token) || "--clear-enabled-toolsets".equals(token)) {
                options.clearToolsets = true;
            } else if ("--model".equals(token) && i + 1 < tokens.size()) {
                options.model = tokens.get(++i);
            } else if ("--clear-model".equals(token)) {
                options.clearModel = true;
            } else if ("--provider".equals(token) && i + 1 < tokens.size()) {
                options.provider = tokens.get(++i);
            } else if ("--clear-provider".equals(token)) {
                options.clearProvider = true;
            } else if (("--base-url".equals(token) || "--base_url".equals(token)) && i + 1 < tokens.size()) {
                options.baseUrl = tokens.get(++i);
            } else if ("--clear-base-url".equals(token) || "--clear-base_url".equals(token)) {
                options.clearBaseUrl = true;
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
            } else {
                options.positionals.add(token);
            }
        }
        return options;
    }

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

    private void putIfNotBlank(Map<String, Object> body, String key, String value) {
        if (StrUtil.isNotBlank(value)) {
            body.put(key, value.trim());
        }
    }

    private void putCronStringOption(Map<String, Object> body, String key, String value) {
        if (value != null) {
            body.put(key, value.trim());
        }
    }

    /** 处理 pairing 相关命令。 */
    private GatewayReply handlePairing(GatewayMessage message, String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length == 0 || StrUtil.isBlank(parts[0])) {
            return GatewayReply.error(
                    "用法："
                            + GatewayCommandConstants.SLASH_PAIRING
                            + " [claim-admin|pending|approve|revoke|approved] ...");
        }
        String action = parts[0].trim().toLowerCase();

        if (GatewayCommandConstants.ACTION_CLAIM_ADMIN.equals(action)) {
            return gatewayAuthorizationService.claimAdmin(message);
        }

        PlatformType targetPlatform = message.getPlatform();
        if (parts.length >= 2) {
            targetPlatform = PlatformType.fromName(parts[1]);
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

        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_PAIRING
                        + " [claim-admin|pending|approve|revoke|approved] ...");
    }

    private GatewayReply handleDangerousApprove(GatewayMessage message, String args)
            throws Exception {
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session == null) {
            return GatewayReply.error("当前没有绑定会话，也没有待审批的危险命令。请先触发需要审批的工具调用。");
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        String normalizedArgs = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if ("list".equals(normalizedArgs)) {
            return GatewayReply.ok(formatApprovalList(agentSession));
        }
        if (normalizedArgs.startsWith("clear")) {
            return clearApprovals(agentSession, normalizedArgs);
        }
        if (isApproveAllCommand(normalizedArgs)) {
            return approveAllDangerousCommands(message, agentSession, args);
        }

        ApprovalCommandArgs approvalArgs = parseApprovalCommandArgs(args);
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

    private boolean isApproveAllCommand(String normalizedArgs) {
        if (StrUtil.isBlank(normalizedArgs)) {
            return false;
        }
        String first = firstToken(normalizedArgs);
        return "all".equals(first);
    }

    private GatewayReply approveAllDangerousCommands(
            GatewayMessage message, SqliteAgentSession agentSession, String args) throws Exception {
        ApprovalCommandArgs approvalArgs = parseApprovalCommandArgs(args);
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

    private String formatApprovalList(SqliteAgentSession agentSession) {
        java.util.List<DangerousCommandApprovalService.PendingApproval> pendingApprovals =
                dangerousCommandApprovalService.listPendingApprovals(agentSession);
        StringBuilder buffer = new StringBuilder();
        buffer.append("pending=")
                .append(pendingApprovals.isEmpty() ? "none" : String.valueOf(pendingApprovals.size()))
                .append('\n');
        for (int i = 0; i < pendingApprovals.size(); i++) {
            DangerousCommandApprovalService.PendingApproval pending = pendingApprovals.get(i);
            buffer.append('#')
                    .append(i + 1)
                    .append(' ')
                    .append(StrUtil.blankToDefault(pending.getApprovalId(), pending.approvalKey()))
                    .append(" tool=")
                    .append(pending.getToolName())
                    .append(" reason=")
                    .append(SecretRedactor.redact(pending.getDescription(), 1000))
                    .append(" command_preview=")
                    .append(SecretRedactor.redact(pending.getCommand(), 800))
                    .append(" scopes=")
                    .append(approvalScopes(pending))
                    .append(" expires_in=")
                    .append(expiresInSeconds(pending.getExpiresAt()))
                    .append("s expired=")
                    .append(isExpired(pending.getExpiresAt()))
                    .append(" key=")
                    .append(SecretRedactor.redact(pending.approvalKey(), 1000))
                    .append('\n');
        }
        buffer.append("session_approvals=")
                .append(SecretRedactor.redact(
                        String.valueOf(dangerousCommandApprovalService.listSessionApprovals(agentSession)), 2000))
                .append('\n');
        buffer.append("always_approvals=")
                .append(SecretRedactor.redact(
                        String.valueOf(dangerousCommandApprovalService.listAlwaysApprovals()), 2000));
        return buffer.toString();
    }

    private String approvalScopes(DangerousCommandApprovalService.PendingApproval pending) {
        if (pending != null && pending.isPermanentApprovalAllowed()) {
            return "once,session,always";
        }
        return "once,session";
    }

    private long expiresInSeconds(long expiresAt) {
        if (expiresAt <= 0L) {
            return 0L;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    private boolean isExpired(long expiresAt) {
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

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

    private DangerousCommandApprovalService.PendingApproval selectPendingApproval(
            SqliteAgentSession agentSession, String selector) {
        java.util.List<DangerousCommandApprovalService.PendingApproval> pendingApprovals =
                dangerousCommandApprovalService.listPendingApprovals(agentSession);
        if (pendingApprovals.isEmpty()) {
            return null;
        }
        if (StrUtil.isBlank(selector)) {
            return pendingApprovals.get(0);
        }
        String value = selector.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            int index = Integer.parseInt(value);
            if (index >= 1 && index <= pendingApprovals.size()) {
                return pendingApprovals.get(index - 1);
            }
        } catch (Exception ignored) {
            // continue with id/key matching
        }
        for (DangerousCommandApprovalService.PendingApproval item : pendingApprovals) {
            if (value.equals(item.getApprovalId())
                    || value.equals(item.approvalKey())
                    || (item.getApprovalId() != null && item.getApprovalId().startsWith(value))
                    || (item.approvalKey() != null && item.approvalKey().startsWith(value))) {
                return item;
            }
        }
        return null;
    }

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

    private GatewayReply handleDangerousDeny(GatewayMessage message, String args) throws Exception {
        SessionRecord session = sessionRepository.getBoundSession(message.sourceKey());
        if (session == null) {
            return GatewayReply.error("当前没有待审批的危险命令。");
        }

        SqliteAgentSession agentSession = new SqliteAgentSession(session, sessionRepository);
        String selector = firstToken(args);
        if ("list".equalsIgnoreCase(selector) || "status".equalsIgnoreCase(selector)) {
            return GatewayReply.ok(formatApprovalList(agentSession));
        }
        if ("all".equalsIgnoreCase(selector)) {
            int rejected =
                    dangerousCommandApprovalService.rejectAll(
                            agentSession, message.getUserName());
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

        if (!dangerousCommandApprovalService.reject(agentSession, selector, message.getUserName())) {
            return GatewayReply.error("危险命令审批状态已失效，请重试。");
        }
        return conversationOrchestrator.resumePending(message.sourceKey());
    }

    private static class ApprovalCommandArgs {
        private String selector;
        private DangerousCommandApprovalService.ApprovalScope scope;

        public String getSelector() {
            return selector;
        }

        public void setSelector(String selector) {
            this.selector = selector;
        }

        public DangerousCommandApprovalService.ApprovalScope getScope() {
            return scope;
        }

        public void setScope(DangerousCommandApprovalService.ApprovalScope scope) {
            this.scope = scope;
        }
    }

    private GatewayReply handleReasoning(GatewayMessage message, String args) throws Exception {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
        if (normalized.length() == 0) {
            return GatewayReply.ok(
                    "reasoning_display="
                            + displaySettingsService.describeReasoning(
                                    message.sourceKey(), message.getPlatform())
                            + "\nreasoning_effort="
                            + StrUtil.blankToDefault(
                                    appConfig.getLlm().getReasoningEffort(), "default")
                            + "\nusage="
                            + GatewayCommandConstants.SLASH_REASONING
                            + " [show|hide]");
        }
        if ("show".equals(normalized) || "on".equals(normalized)) {
            displaySettingsService.setReasoningVisible(message.sourceKey(), true);
            return GatewayReply.ok("已开启当前来源键的 reasoning 展示。");
        }
        if ("hide".equals(normalized) || "off".equals(normalized)) {
            displaySettingsService.setReasoningVisible(message.sourceKey(), false);
            return GatewayReply.ok("已关闭当前来源键的 reasoning 展示。");
        }
        return GatewayReply.error("用法：" + GatewayCommandConstants.SLASH_REASONING + " [show|hide]");
    }

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
                    "已切换运行中输入策略为 "
                            + normalized
                            + "。\n"
                            + formatBusyPolicyDescription(normalized));
        }
        return GatewayReply.error(
                "用法："
                        + GatewayCommandConstants.SLASH_BUSY
                        + " [status|queue|steer|interrupt|reject]");
    }

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
                GatewayReply.ok(
                        StrUtil.blankToDefault(decision.getMessage(), "已加入下一轮队列。"));
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

    private void persistBusyPolicy(String policy) {
        if (runtimeSettingsService != null) {
            runtimeSettingsService.setConfigValue("task.busyPolicy", policy);
            return;
        }
        appConfig.getTask().setBusyPolicy(policy);
    }

    private String formatBusyStatus(String sourceKey) {
        String policy = StrUtil.blankToDefault(appConfig.getTask().getBusyPolicy(), "queue");
        StringBuilder buffer = new StringBuilder();
        buffer.append("busy_policy=").append(policy).append('\n');
        buffer.append("source_running=")
                .append(agentRunControlService != null && agentRunControlService.isRunning(sourceKey))
                .append('\n');
        buffer.append("any_running=")
                .append(agentRunControlService != null && agentRunControlService.hasRunningRuns())
                .append('\n');
        buffer.append(formatBusyPolicyDescription(policy)).append('\n');
        buffer.append("用法：")
                .append(GatewayCommandConstants.SLASH_BUSY)
                .append(" [status|queue|steer|interrupt|reject]");
        return buffer.toString();
    }

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

    private DangerousCommandApprovalService.ApprovalScope parseApprovalScope(String args) {
        String normalized = StrUtil.nullToEmpty(args).trim().toLowerCase();
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
                buffer.append(", session=").append(record.getSessionId());
            }
        }
        return buffer.toString();
    }

    private String currentPersonalityName() {
        try {
            String value = globalSettingRepository.get(AgentSettingConstants.ACTIVE_PERSONALITY);
            return StrUtil.blankToDefault(value, "default");
        } catch (Exception e) {
            return "default";
        }
    }

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

    private static class ModelCommandInput {
        private boolean global;
        private boolean clear;
        private String provider;
        private String model;
    }

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

    private boolean hasFlag(String raw, String flag) {
        return (" " + StrUtil.nullToEmpty(raw) + " ").contains(" " + flag + " ");
    }

    private String parseOption(String raw, String option, String defaultValue) {
        String[] parts = StrUtil.nullToEmpty(raw).split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (option.equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return defaultValue;
    }

    private int parseIntOption(String raw, String option, int defaultValue) {
        try {
            return Integer.parseInt(parseOption(raw, option, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

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

    private String firstToken(String raw) {
        String[] parts = StrUtil.nullToEmpty(raw).trim().split("\\s+", 2);
        return parts.length == 0 ? "" : parts[0];
    }

    /** 生成帮助文本。 */
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
                        helpLine(
                                GatewayCommandConstants.SLASH_TITLE + " [clear|新标题]",
                                "查看、设置或清空当前会话标题"),
                        helpLine(GatewayCommandConstants.SLASH_STATUS, "查看当前会话状态"),
                        helpLine(GatewayCommandConstants.SLASH_USAGE, "查看当前会话运行信息"),
                        helpLine(
                                GatewayCommandConstants.SLASH_GOAL
                                        + " [status|pause|resume|clear|<目标>]",
                                "设置跨轮长目标并由 judge 驱动自动继续"),
                        helpLine(
                                GatewayCommandConstants.SLASH_BUSY
                                        + " [status|queue|steer|interrupt|reject]",
                                "查看或切换运行中输入策略"),
                        helpLine(GatewayCommandConstants.SLASH_QUEUE + " <prompt>", "将提示排到当前任务之后执行"),
                        helpLine(GatewayCommandConstants.SLASH_STEER + " <prompt>", "向运行中任务注入修正；空闲时按普通提示执行"),
                        helpLine(GatewayCommandConstants.SLASH_RESTART, "等待运行中任务 drain 后重启网关"),
                        helpLine(GatewayCommandConstants.SLASH_STOP, "停止当前任务和后台进程"),
                        helpLine(GatewayCommandConstants.SLASH_YOLO + " [status|on|off]", "查询或设置当前会话的危险命令自动批准模式"),
                        helpLine(
                                GatewayCommandConstants.SLASH_PERSONALITY + " [name|none]",
                                "查看或切换人格"),
                        helpLine(
                                GatewayCommandConstants.SLASH_VERSION + " [check|update]",
                                "查看版本或执行更新"),
                        helpLine(
                                GatewayCommandConstants.SLASH_MODEL
                                        + " [--global] [provider:]<model>|clear",
                                "查看或切换模型"),
                        helpLine(
                                GatewayCommandConstants.SLASH_REASONING + " [show|hide]",
                                "查看或切换 reasoning 展示"),
                        helpLine(
                                GatewayCommandConstants.SLASH_TOOLS
                                        + " [list|enable|disable] [name...]",
                                "查看或管理工具开关"),
                        helpLine(
                                GatewayCommandConstants.SLASH_SKILLS
                                        + " [list|browse|search|install|inspect|check|update|audit|uninstall|tap|enable|disable|reload]",
                                "管理本地技能与 Skills Hub"),
                        helpLine(
                                GatewayCommandConstants.SLASH_RELOAD_MCP
                                        + " [now|always]；确认：/approve [确认编号]|/always|/cancel",
                                "重新加载 MCP 工具并刷新工具变更基线"),
                        helpLine(GatewayCommandConstants.SLASH_ACP + " [status]", "查看 ACP 本地适配器能力快照"),
                        helpLine(GatewayCommandConstants.SLASH_CONFIRM, "查看当前待确认 slash 命令"),
                        helpLine(
                                GatewayCommandConstants.SLASH_AGENT
                                        + " [name|list|create|show|model|tools|skills|memory]",
                                "切换或管理当前会话 Agent"),
                        helpLine(
                                GatewayCommandConstants.SLASH_CRON
                                        + " [list [--all]|inspect|show|next|add|edit|pause|resume|remove|run|history|status|tick]",
                                "管理定时任务"),
                        helpLine(
                                GatewayCommandConstants.SLASH_KANBAN
                                        + " [list|create|show|move|assign|comment|boards]",
                                "管理 Jimuqu 风格协作看板"),
                        helpLine(GatewayCommandConstants.SLASH_RECAP + " [limit]", "显示恢复会话用的紧凑历史摘要"),
                        helpLine(GatewayCommandConstants.SLASH_TRAJECTORY + " [user-query]", "导出会话 trajectory JSON"),
                        helpLine(GatewayCommandConstants.SLASH_TRAJECTORY + " save [--failed] [user-query]", "追加保存 trajectory JSONL 到 runtime/artifacts"),
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
                                GatewayCommandConstants.SLASH_DENY + " [#序号|审批ID|all]",
                                "拒绝待审批危险命令"),
                        helpLine(GatewayCommandConstants.SLASH_PLATFORMS, "查看平台连接与授权状态"),
                        helpLine(GatewayCommandConstants.SLASH_HELP, "显示帮助信息")));
    }

    private String helpLine(String usage, String description) {
        return usage + " - " + description;
    }

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

    private boolean isCheckpointClearCommand(String args) {
        String first = firstToken(args);
        return "clear".equalsIgnoreCase(first) || "clear-all".equalsIgnoreCase(first);
    }

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

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "never";
        }
        return DateUtil.formatDateTime(new java.util.Date(timestamp));
    }

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
        return String.format(java.util.Locale.ROOT, "%.1f %s", Double.valueOf(value), units[unitIndex]);
    }

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

    private static class CronFlagOptions {
        private String name;
        private String deliver;
        private String deliverChatId;
        private String deliverThreadId;
        private boolean clearDeliverChatId;
        private boolean clearDeliverThreadId;
        private Integer repeat;
        private Integer limit;
        private String reason;
        private final List<String> skills = new ArrayList<String>();
        private final List<String> addSkills = new ArrayList<String>();
        private final List<String> removeSkills = new ArrayList<String>();
        private boolean clearSkills;
        private boolean all;
        private String prompt;
        private String schedule;
        private String script;
        private String workdir;
        private String contextFrom;
        private String dependsOn;
        private String enabledToolsets;
        private String model;
        private String provider;
        private String baseUrl;
        private boolean clearModel;
        private boolean clearProvider;
        private boolean clearBaseUrl;
        private boolean clearScript;
        private boolean clearWorkdir;
        private boolean clearContextFrom;
        private boolean clearDependsOn;
        private boolean clearToolsets;
        private boolean noAgent;
        private boolean agent;
        private boolean wrapResponse;
        private boolean raw;
        private final List<String> positionals = new ArrayList<String>();
    }

    private static class CronEditRequest {
        private final String jobId;
        private final Map<String, Object> body;

        private CronEditRequest(String jobId, Map<String, Object> body) {
            this.jobId = jobId;
            this.body = body;
        }
    }
}
