package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.FileContextService;
import com.jimuqu.solon.claw.context.LocalSkillService;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ChannelInboundMessageRepository;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.core.service.SkillHubService;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.gateway.authorization.GatewayOpenPolicyStartupGuard;
import com.jimuqu.solon.claw.gateway.command.DefaultCommandService;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.gateway.delivery.AdapterBackedDeliveryService;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.qqbot.QQBotChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.weixin.WeiXinChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.yuanbao.YuanbaoChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.gateway.service.GatewayInjectionAuthService;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartCoordinator;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartNotificationService;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.goal.GoalContractDrafter;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.goal.LlmGoalJudge;
import com.jimuqu.solon.claw.profile.ProfileBeanResolver;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.DisplaySettingsService;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeSettingsService;
import com.jimuqu.solon.claw.support.SessionArtifactService;
import com.jimuqu.solon.claw.support.update.AppUpdateService;
import com.jimuqu.solon.claw.support.update.AppVersionService;
import com.jimuqu.solon.claw.tool.runtime.BrowserRuntimeService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.DashboardProviderService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 承载消息网关配置并集中创建运行组件。 */
@Configuration
public class GatewayConfiguration {
    /**
     * 执行渠道Adapters相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param channelStateRepository 渠道状态仓储依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     * @return 返回渠道Adapters结果。
     */
    @Bean
    public Map<PlatformType, ChannelAdapter> channelAdapters(
            AppConfig appConfig,
            ChannelStateRepository channelStateRepository,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(
                PlatformType.FEISHU,
                new FeishuChannelAdapter(
                        appConfig.getChannels().getFeishu(),
                        attachmentCacheService,
                        securityPolicyService));
        adapters.put(
                PlatformType.DINGTALK,
                new DingTalkChannelAdapter(
                        appConfig.getChannels().getDingtalk(),
                        channelStateRepository,
                        attachmentCacheService,
                        securityPolicyService));
        adapters.put(
                PlatformType.WECOM,
                new WeComChannelAdapter(
                        appConfig.getChannels().getWecom(),
                        attachmentCacheService,
                        securityPolicyService));
        adapters.put(
                PlatformType.WEIXIN,
                new WeiXinChannelAdapter(
                        appConfig.getChannels().getWeixin(),
                        channelStateRepository,
                        attachmentCacheService,
                        securityPolicyService));
        adapters.put(
                PlatformType.QQBOT,
                new QQBotChannelAdapter(
                        appConfig,
                        appConfig.getChannels().getQqbot(),
                        attachmentCacheService,
                        securityPolicyService));
        adapters.put(
                PlatformType.YUANBAO,
                new YuanbaoChannelAdapter(
                        appConfig.getChannels().getYuanbao(),
                        attachmentCacheService,
                        securityPolicyService));
        return adapters;
    }

    /**
     * 执行投递服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param channelAdapters 渠道Adapters参数。
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @return 返回投递服务结果。
     */
    @Bean
    public DeliveryService deliveryService(
            AppConfig appConfig,
            Map<PlatformType, ChannelAdapter> channelAdapters,
            GatewayPolicyRepository gatewayPolicyRepository,
            SessionRepository sessionRepository) {
        return new AdapterBackedDeliveryService(
                appConfig, channelAdapters, gatewayPolicyRepository, sessionRepository);
    }

    /**
     * 执行运行时设置服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param globalSettingRepository globalSetting仓储依赖。
     * @param deliveryService 投递服务依赖。
     * @param dashboardConfigService dashboard配置Service配置对象。
     * @param dashboardRuntimeConfigService dashboard工作区配置Service配置对象。
     * @param appVersionService 应用版本服务依赖。
     * @param llmProviderService LLM提供方Service标识或键值。
     * @param dashboardProviderService dashboard提供方Service标识或键值。
     * @return 返回运行时设置服务结果。
     */
    @Bean
    public RuntimeSettingsService runtimeSettingsService(
            AppConfig appConfig,
            GlobalSettingRepository globalSettingRepository,
            DeliveryService deliveryService,
            DashboardConfigService dashboardConfigService,
            DashboardRuntimeConfigService dashboardRuntimeConfigService,
            AppVersionService appVersionService,
            LlmProviderService llmProviderService,
            DashboardProviderService dashboardProviderService) {
        return new RuntimeSettingsService(
                appConfig,
                globalSettingRepository,
                deliveryService,
                dashboardConfigService,
                dashboardRuntimeConfigService,
                appVersionService,
                llmProviderService,
                dashboardProviderService);
    }

    /**
     * 执行渠道连接管理器相关逻辑。
     *
     * @param channelAdapters 渠道Adapters参数。
     * @return 返回渠道Connection管理器结果。
     */
    @Bean(destroyMethod = "shutdown")
    public ChannelConnectionManager channelConnectionManager(
            Map<PlatformType, ChannelAdapter> channelAdapters) {
        return new ChannelConnectionManager(channelAdapters);
    }

    /**
     * 执行消息网关工作区配置刷新服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param channelConnectionManager 渠道连接Manager参数。
     * @return 返回消息网关工作区配置刷新服务结果。
     */
    @Bean
    public GatewayRuntimeRefreshService gatewayRuntimeRefreshService(
            AppConfig appConfig, ChannelConnectionManager channelConnectionManager) {
        return new GatewayRuntimeRefreshService(appConfig, channelConnectionManager);
    }

    /**
     * 执行消息网关授权服务相关逻辑。
     *
     * @param gatewayPolicyRepository 网关策略仓储依赖。
     * @param appConfig 应用运行配置。
     * @return 返回消息网关授权服务结果。
     */
    @Bean
    public GatewayAuthorizationService gatewayAuthorizationService(
            GatewayPolicyRepository gatewayPolicyRepository, AppConfig appConfig) {
        return new GatewayAuthorizationService(gatewayPolicyRepository, appConfig);
    }

    /**
     * 执行目标服务相关逻辑。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param llmGateway LLM 网关，供 LLM 裁决器发起 auxiliary 调用。
     * @param appConfig 应用运行配置，提供 goal 子配置。
     * @param llmProviderService provider 解析服务，供裁决器解析 judgeProvider 覆盖。
     * @return 返回goal服务结果。
     */
    @Bean
    public GoalService goalService(
            SessionRepository sessionRepository,
            LlmGateway llmGateway,
            AppConfig appConfig,
            LlmProviderService llmProviderService) {
        LlmGoalJudge llmJudge = new LlmGoalJudge(llmGateway, appConfig, llmProviderService);
        return new GoalService(sessionRepository, llmJudge, appConfig.getGoal());
    }

    /**
     * 执行目标契约起草器相关逻辑，供 /goal draft 调用辅助模型起草完成契约。
     *
     * @param llmGateway LLM 网关，供发起 auxiliary 聊天。
     * @param appConfig 应用运行配置，提供 goal 子配置。
     * @return 返回goal契约起草器结果。
     */
    @Bean
    public GoalContractDrafter goalContractDrafter(LlmGateway llmGateway, AppConfig appConfig) {
        return new GoalContractDrafter(llmGateway, appConfig.getGoal());
    }

    /**
     * 执行消息网关重启Coordinator相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param agentRunControlService Agent运行控制服务依赖。
     * @return 返回消息网关重启Coordinator结果。
     */
    @Bean(destroyMethod = "shutdown")
    public GatewayRestartCoordinator gatewayRestartCoordinator(
            AppConfig appConfig, AgentRunControlService agentRunControlService) {
        return new GatewayRestartCoordinator(appConfig, agentRunControlService);
    }

    /**
     * 执行消息网关重启Notification服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @param deliveryService 投递服务依赖。
     * @return 返回消息网关重启Notification服务结果。
     */
    @Bean
    public GatewayRestartNotificationService gatewayRestartNotificationService(
            AppConfig appConfig, DeliveryService deliveryService) {
        return new GatewayRestartNotificationService(appConfig, deliveryService);
    }

    /**
     * 执行斜杠命令Confirm服务相关逻辑。
     *
     * @param globalSettingRepository globalSetting仓储依赖。
     * @return 返回slash Confirm服务结果。
     */
    @Bean
    public SlashConfirmService slashConfirmService(
            GlobalSettingRepository globalSettingRepository) {
        return new SlashConfirmService(globalSettingRepository);
    }

    /**
     * 执行命令服务相关逻辑。
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
     * @param agentRunRepository Agent运行仓储依赖。
     * @param dashboardMcpService dashboardMCP服务依赖。
     * @param goalService 目标服务依赖。
     * @param goalContractDrafter 目标契约起草器依赖，用于 /goal draft。
     * @param sessionArtifactService 会话Artifact服务依赖。
     * @param defaultCronScheduler 默认定时任务调度器参数。
     * @param gatewayRestartCoordinator 网关RestartCoordinator参数。
     * @param slashConfirmService 斜杠命令Confirm服务依赖。
     * @param dashboardCuratorService dashboardCurator服务依赖。
     * @param dashboardSkillsService dashboard技能服务依赖。
     * @param browserRuntimeService 浏览器运行时服务依赖。
     * @param memoryService 长期记忆服务，供 /memory 与工具共用审批队列。
     * @return 返回命令服务结果。
     */
    @Bean
    public CommandService commandService(
            SessionRepository sessionRepository,
            ToolRegistry toolRegistry,
            LocalSkillService localSkillService,
            CronJobRepository cronJobRepository,
            ConversationOrchestrator conversationOrchestrator,
            FileContextService contextService,
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
            AgentRunRepository agentRunRepository,
            DashboardMcpService dashboardMcpService,
            GoalService goalService,
            GoalContractDrafter goalContractDrafter,
            SessionArtifactService sessionArtifactService,
            DefaultCronScheduler defaultCronScheduler,
            GatewayRestartCoordinator gatewayRestartCoordinator,
            SlashConfirmService slashConfirmService,
            DashboardCuratorService dashboardCuratorService,
            DashboardSkillsService dashboardSkillsService,
            BrowserRuntimeService browserRuntimeService,
            MemoryService memoryService,
            DelegationService delegationService) {
        DefaultCommandService service =
                new DefaultCommandService(
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
                        agentRunRepository,
                        dashboardMcpService,
                        goalService,
                        goalContractDrafter,
                        sessionArtifactService,
                        defaultCronScheduler,
                        gatewayRestartCoordinator,
                        slashConfirmService,
                        dashboardCuratorService,
                        dashboardSkillsService,
                        browserRuntimeService,
                        memoryService);
        service.setDelegationService(delegationService);
        return service;
    }

    /**
     * 执行消息网关服务相关逻辑。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param channelAdapters 渠道Adapters参数。
     * @param channelConnectionManager 渠道连接Manager参数。
     * @param gatewayRestartNotificationService 网关RestartNotification服务依赖。
     * @param agentRunSupervisor Agent 运行监督器，启用 goal 续轮抢占检查。
     * @param appConfig 应用配置，用于解析当前 Profile 身份。
     * @return 返回消息网关服务结果。
     */
    @Bean
    public DefaultGatewayService gatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            ChannelInboundMessageRepository channelInboundMessageRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService,
            AttachmentCacheService attachmentCacheService,
            Map<PlatformType, ChannelAdapter> channelAdapters,
            ChannelConnectionManager channelConnectionManager,
            GatewayRestartNotificationService gatewayRestartNotificationService,
            AgentRunSupervisor agentRunSupervisor,
            AppConfig appConfig) {
        final DefaultGatewayService service =
                new DefaultGatewayService(
                        commandService,
                        conversationOrchestrator,
                        deliveryService,
                        sessionRepository,
                        channelInboundMessageRepository,
                        gatewayAuthorizationService,
                        skillLearningService,
                        attachmentCacheService,
                        channelAdapters,
                        appConfig);
        service.setAgentRunSupervisor(agentRunSupervisor);

        final ProfileRuntimeScope.Context profileScope = ProfileRuntimeScope.current();

        channelConnectionManager.bindInboundHandler(
                new InboundMessageHandler() {
                    /** 在渠道异步排队前同步写入原始入站 receipt。 */
                    @Override
                    public boolean admit(GatewayMessage message) throws Exception {
                        assignProfile(message);
                        if (profileScope == null) {
                            return service.admitInbound(message);
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            return service.admitInbound(message);
                        }
                    }

                    /**
                     * 执行handle相关逻辑。
                     *
                     * @param message 平台消息或错误消息。
                     */
                    @Override
                    public void handle(GatewayMessage message) throws Exception {
                        assignProfile(message);
                        if (profileScope == null) {
                            service.handle(message);
                            return;
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            service.handle(message);
                        }
                    }

                    /** 在异步队列中处理已经同步准入的消息。 */
                    @Override
                    public void handleAdmitted(GatewayMessage message) throws Exception {
                        assignProfile(message);
                        if (profileScope == null) {
                            service.handleAdmitted(message);
                            return;
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            service.handleAdmitted(message);
                        }
                    }

                    /** 为每次渠道连接捕获连接前 pending receipt 的稳定水位。 */
                    @Override
                    public long capturePendingRecoveryWatermark() throws Exception {
                        if (profileScope == null) {
                            return service.capturePendingInboundWatermark(service.profileName());
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            return service.capturePendingInboundWatermark(service.profileName());
                        }
                    }

                    /** 每次渠道连接后恢复连接前水位内遗留的 pending receipt。 */
                    @Override
                    public void recoverPendingThrough(long maxSequence) throws Exception {
                        if (profileScope == null) {
                            service.recoverPendingInbounds(service.profileName(), maxSequence);
                            return;
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            service.recoverPendingInbounds(service.profileName(), maxSequence);
                        }
                    }

                    /** 每次渠道连接后只恢复当前平台在连接前遗留的 pending receipt。 */
                    @Override
                    public void recoverPendingThrough(PlatformType platform, long maxSequence)
                            throws Exception {
                        if (profileScope == null) {
                            service.recoverPendingInbounds(
                                    service.profileName(), platform, maxSequence);
                            return;
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            service.recoverPendingInbounds(
                                    service.profileName(), platform, maxSequence);
                        }
                    }

                    /** 为渠道消息补齐当前运行时 Profile。 */
                    private void assignProfile(GatewayMessage message) {
                        if (message != null
                                && (message.getProfile() == null
                                        || message.getProfile().trim().length() == 0)) {
                            message.setProfile(service.profileName());
                        }
                    }

                    /** 打开与当前 Bean 创建上下文一致的 Profile 作用域。 */
                    private ProfileRuntimeScope.Scope openProfileScope() {
                        return ProfileRuntimeScope.open(
                                profileScope.getProfile(),
                                profileScope.getHome(),
                                profileScope.getEnvironment(),
                                profileScope.getAppContext());
                    }
                });
        channelConnectionManager.bindConnectedHandler(
                new java.util.function.Consumer<PlatformType>() {
                    /** 渠道真正可用后按平台补投尚未完成的持久化回复。 */
                    @Override
                    public void accept(PlatformType platform) {
                        if (profileScope == null) {
                            recoverReadyPlatform(platform);
                            return;
                        }
                        try (ProfileRuntimeScope.Scope ignored = openProfileScope()) {
                            recoverReadyPlatform(platform);
                        }
                    }

                    /** 只补投当前已启用且已 ready 平台尚未完成的回复。 */
                    private void recoverReadyPlatform(PlatformType platform) {
                        service.recoverPlatformInboundDeliveries(service.profileName(), platform);
                    }

                    /** 打开与当前 Bean 创建上下文一致的 Profile 作用域。 */
                    private ProfileRuntimeScope.Scope openProfileScope() {
                        return ProfileRuntimeScope.open(
                                profileScope.getProfile(),
                                profileScope.getHome(),
                                profileScope.getEnvironment(),
                                profileScope.getAppContext());
                    }
                });
        GatewayOpenPolicyStartupGuard.requireAllowed(
                appConfig, ProfileRuntimeIdentity.resolve(appConfig));
        long startupRecoveryCutoff = System.currentTimeMillis();
        service.convergeInterruptedInbounds(service.profileName(), startupRecoveryCutoff);
        service.pruneTerminalInbounds(service.profileName(), startupRecoveryCutoff);
        channelConnectionManager.startAll();
        gatewayRestartNotificationService.deliverPendingRestartOnlineNotification();
        return service;
    }

    /**
     * 创建 default 进程内的 Profile 复用运行时；命名 Profile 配置会被强制关闭递归复用。
     *
     * @param appConfig 当前 Profile 配置。
     * @param gatewayService 当前 Profile 网关服务。
     * @param channelAdapters 当前 Profile 渠道适配器。
     * @param gatewayRuntimeRefreshService 当前 Profile 配置刷新服务。
     * @return 当前 Bean 容器对应的 Profile 复用运行时管理器。
     */
    @Bean(destroyMethod = "close")
    public ProfileMultiplexRuntimeManager profileMultiplexRuntimeManager(
            AppConfig appConfig,
            DefaultGatewayService gatewayService,
            Map<PlatformType, ChannelAdapter> channelAdapters,
            GatewayRuntimeRefreshService gatewayRuntimeRefreshService) {
        ProfileMultiplexRuntimeManager manager =
                new ProfileMultiplexRuntimeManager(
                        appConfig,
                        ProfileBeanResolver.currentContext(),
                        gatewayService,
                        channelAdapters);
        gatewayRuntimeRefreshService.setProfileMultiplexRuntimeManager(manager);
        manager.start();
        return manager;
    }

    /**
     * 执行消息网关Injection认证服务相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回消息网关Injection认证服务结果。
     */
    @Bean
    public GatewayInjectionAuthService gatewayInjectionAuthService(AppConfig appConfig) {
        return new GatewayInjectionAuthService(appConfig);
    }
}
