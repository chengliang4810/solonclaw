package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.ChannelInboundMessageRecord;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.ChannelInboundMessageRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunCancelledException;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.profile.ProfileRuntimeIdentity;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.GatewayMediaDeliverySupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 网关主入口服务，负责把消息分流到授权、命令和对话主链。 */
public class DefaultGatewayService {
    /** 网关日志器。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultGatewayService.class);

    /** 渠道消息去重窗口，单位毫秒。 */
    private static final long DUPLICATE_WINDOW_MILLIS = 10L * 60L * 1000L;

    /** 单次读取的入站回复恢复批量，使用稳定游标持续读取直到积压为空。 */
    private static final int INBOUND_RECOVERY_BATCH_SIZE = 100;

    /** 长时间运行进程执行入站终态清理的最短间隔。 */
    private static final long INBOUND_PRUNE_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1L);

    /** 串行化启动与渠道重连触发的 pending 恢复，保持旧消息领取顺序。 */
    private final Object pendingInboundRecoveryLock = new Object();

    /** 命令服务。 */
    private final CommandService commandService;

    /** 对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 渠道投递服务。 */
    private final DeliveryService deliveryService;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

    /** 渠道入站消息总账仓储。 */
    private final ChannelInboundMessageRepository channelInboundMessageRepository;

    /** 授权服务。 */
    private final GatewayAuthorizationService gatewayAuthorizationService;

    /** 任务后自动学习服务。 */
    private final SkillLearningService skillLearningService;

    /** 平台到渠道适配器的映射，用于触发处理状态表情回应生命周期。 */
    private final Map<PlatformType, ChannelAdapter> channelAdapters;

    /** 应用配置，用于读取消息网关运行时开关。 */
    private final AppConfig appConfig;

    /** 当前网关服务实例所属 Profile。 */
    private final String profileName;

    /** default 复用网关用于把显式命名 Profile 消息转交给独立运行时。 */
    private volatile ProfileMessageRouter profileMessageRouter;

    /** 回复文本中的 MEDIA: 指令解析器。 */
    private final GatewayMediaDeliverySupport mediaDeliverySupport;

    /** Agent 运行监督器，用于 goal 续轮抢占检查（查询是否有待处理真实用户消息）。 可选依赖：未注入时抢占检查降级跳过，由现有 interrupt 策略兜底。 */
    private AgentRunSupervisor agentRunSupervisor;

    /** 进程内最近已处理的消息键，用于抑制渠道重复投递。 */
    private final ConcurrentMap<String, Long> recentMessageKeys =
            new ConcurrentHashMap<String, Long>();

    /** 最近一次入站终态清理检查时间，避免每条消息都执行 DELETE。 */
    private final AtomicLong lastInboundPruneAt = new AtomicLong();

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService) {
        this(
                commandService,
                conversationOrchestrator,
                deliveryService,
                sessionRepository,
                null,
                gatewayAuthorizationService,
                skillLearningService,
                null,
                null,
                null);
    }

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            ChannelInboundMessageRepository channelInboundMessageRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService) {
        this(
                commandService,
                conversationOrchestrator,
                deliveryService,
                sessionRepository,
                channelInboundMessageRepository,
                gatewayAuthorizationService,
                skillLearningService,
                null,
                null,
                null);
    }

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService,
            AttachmentCacheService attachmentCacheService) {
        this(
                commandService,
                conversationOrchestrator,
                deliveryService,
                sessionRepository,
                null,
                gatewayAuthorizationService,
                skillLearningService,
                attachmentCacheService,
                null,
                null);
    }

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param channelInboundMessageRepository 渠道入站消息总账仓储。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            ChannelInboundMessageRepository channelInboundMessageRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService,
            AttachmentCacheService attachmentCacheService) {
        this(
                commandService,
                conversationOrchestrator,
                deliveryService,
                sessionRepository,
                channelInboundMessageRepository,
                gatewayAuthorizationService,
                skillLearningService,
                attachmentCacheService,
                null,
                null);
    }

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param channelAdapters 渠道Adapters参数。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService,
            AttachmentCacheService attachmentCacheService,
            Map<PlatformType, ChannelAdapter> channelAdapters) {
        this(
                commandService,
                conversationOrchestrator,
                deliveryService,
                sessionRepository,
                null,
                gatewayAuthorizationService,
                skillLearningService,
                attachmentCacheService,
                channelAdapters,
                null);
    }

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param channelAdapters 渠道Adapters参数。
     * @param appConfig 应用配置，用于读取网关运行时开关。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService,
            AttachmentCacheService attachmentCacheService,
            Map<PlatformType, ChannelAdapter> channelAdapters,
            AppConfig appConfig) {
        this(
                commandService,
                conversationOrchestrator,
                deliveryService,
                sessionRepository,
                null,
                gatewayAuthorizationService,
                skillLearningService,
                attachmentCacheService,
                channelAdapters,
                appConfig);
    }

    /**
     * 创建默认消息网关服务实例，并注入运行所需依赖。
     *
     * @param commandService 命令服务依赖。
     * @param conversationOrchestrator conversationOrchestrator 参数。
     * @param deliveryService 投递服务依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param channelInboundMessageRepository 渠道入站消息总账仓储。
     * @param gatewayAuthorizationService 网关授权服务依赖。
     * @param skillLearningService 技能Learning服务依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param channelAdapters 渠道Adapters参数。
     * @param appConfig 应用配置，用于读取网关运行时开关。
     */
    public DefaultGatewayService(
            CommandService commandService,
            ConversationOrchestrator conversationOrchestrator,
            DeliveryService deliveryService,
            SessionRepository sessionRepository,
            ChannelInboundMessageRepository channelInboundMessageRepository,
            GatewayAuthorizationService gatewayAuthorizationService,
            SkillLearningService skillLearningService,
            AttachmentCacheService attachmentCacheService,
            Map<PlatformType, ChannelAdapter> channelAdapters,
            AppConfig appConfig) {
        this.commandService = commandService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.deliveryService = deliveryService;
        this.sessionRepository = sessionRepository;
        this.channelInboundMessageRepository = channelInboundMessageRepository;
        this.gatewayAuthorizationService = gatewayAuthorizationService;
        this.skillLearningService = skillLearningService;
        this.appConfig = appConfig;
        this.profileName =
                appConfig == null ? "default" : ProfileRuntimeIdentity.resolve(appConfig);
        this.channelAdapters =
                channelAdapters == null
                        ? Collections.<PlatformType, ChannelAdapter>emptyMap()
                        : channelAdapters;
        this.mediaDeliverySupport =
                attachmentCacheService == null
                        ? null
                        : new GatewayMediaDeliverySupport(attachmentCacheService);
    }

    /**
     * 注入 Agent 运行监督器，启用 goal 续轮抢占检查。
     *
     * @param agentRunSupervisor Agent 运行监督器，可为 null（抢占检查降级跳过）。
     */
    public void setAgentRunSupervisor(AgentRunSupervisor agentRunSupervisor) {
        this.agentRunSupervisor = agentRunSupervisor;
    }

    /**
     * 为 default 复用网关注入命名 Profile 路由器。
     *
     * @param profileMessageRouter 只接受当前进程已经承载的 Profile。
     */
    public void setProfileMessageRouter(ProfileMessageRouter profileMessageRouter) {
        this.profileMessageRouter = profileMessageRouter;
    }

    /**
     * @return 当前网关服务实例所属 Profile。
     */
    public String profileName() {
        return profileName;
    }

    /**
     * 在渠道推进确认游标或进入异步队列前同步持久化原始入站消息。
     *
     * @param message 渠道刚解析完成的原始消息。
     * @return 授权消息成功写入 pending receipt 返回 true；重复、未授权或已同步回复预授权提示时返回 false。
     */
    public boolean admitInbound(GatewayMessage message) throws Exception {
        if (message == null) {
            throw new IllegalArgumentException("消息体不能为空。");
        }
        if (StrUtil.isBlank(message.getProfile())) {
            message.setProfile(profileName);
        }
        String targetProfile = targetProfile(message);
        if (!profileName.equals(targetProfile)) {
            throw new IllegalArgumentException(
                    "Profile '" + targetProfile + "' cannot be admitted by this runtime.");
        }
        if (!shouldPersistInbound(message)) {
            return true;
        }

        GatewayReply preAuth = gatewayAuthorizationService.preAuthorize(message);
        if (preAuth != null) {
            safeDeliver(message, preAuth);
            return false;
        }
        routeGroupConversation(message);
        boolean authorized =
                message.isGroupGuest() || gatewayAuthorizationService.isAuthorized(message);
        if (!authorized) {
            return false;
        }

        long now = System.currentTimeMillis();
        maybePruneTerminalInbounds(now);
        String ingressId = java.util.UUID.randomUUID().toString();
        message.setIngressId(ingressId);

        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId(ingressId);
        record.setMessageKey(inboundMessageKey(message));
        record.setProfile(message.getProfile());
        record.setPlatform(String.valueOf(message.getPlatform()));
        record.setSourceKey(message.sourceKey());
        record.setMessageJson(serializeMessage(message));
        record.setStatus(ChannelInboundMessageRecord.STATUS_PENDING);
        record.setAttempts(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        if (!channelInboundMessageRepository.saveIfAbsent(record)) {
            message.setIngressId(null);
            message.getInboundReceiptIds().clear();
            return false;
        }
        message.getInboundReceiptIds().clear();
        message.getInboundReceiptIds().add(ingressId);
        return true;
    }

    /**
     * 执行单条统一网关消息相关逻辑。
     *
     * @param message 渠道统一消息
     * @return 网关处理结果
     */
    public GatewayReply handle(GatewayMessage message) throws Exception {
        return handleInternal(message, false);
    }

    /**
     * 处理已经同步写入 pending receipt 的渠道消息。
     *
     * @param message 已准入的单条消息或合并批次。
     * @return 网关处理结果。
     */
    public GatewayReply handleAdmitted(GatewayMessage message) throws Exception {
        return handleInternal(message, true);
    }

    /** 按普通入口或两阶段准入入口执行统一网关主链。 */
    private GatewayReply handleInternal(GatewayMessage message, boolean admitted) throws Exception {
        if (message == null) {
            return GatewayReply.error("消息体不能为空。");
        }

        String targetProfile = targetProfile(message);
        if (!profileName.equals(targetProfile)) {
            ProfileMessageRouter router = profileMessageRouter;
            if (router == null) {
                return GatewayReply.error("Profile '" + targetProfile + "' is not served here.");
            }
            return router.route(targetProfile, message);
        }

        boolean persistentInbound = shouldPersistInbound(message);
        boolean admittedInbound =
                admitted
                        && persistentInbound
                        && message.getInboundReceiptIds() != null
                        && !message.getInboundReceiptIds().isEmpty();
        String messageKey =
                persistentInbound ? inboundMessageKey(message) : recentMessageKey(message);
        if (!persistentInbound) {
            pruneDuplicateKeys();
            if (isDuplicate(messageKey)) {
                log.info("Ignore duplicate gateway message: {}", messageKey);
                return null;
            }
        }

        boolean inboundClaimed = false;
        if (admittedInbound) {
            hydrateAdmittedInboundAttachments(message);
            if (!startAdmittedInboundProcessing(message)) {
                log.info("Ignore consumed admitted gateway message: {}", messageKey);
                return null;
            }
            inboundClaimed = true;
        }

        boolean authorized = false;
        boolean processingStarted = false;
        ProcessingOutcome processingOutcome = null;
        final AtomicBoolean terminalCommitAttempted = new AtomicBoolean(false);
        try {
            if (!admittedInbound) {
                GatewayReply preAuth = gatewayAuthorizationService.preAuthorize(message);
                if (preAuth != null) {
                    safeDeliver(message, preAuth);
                    return preAuth;
                }
            }

            String text = message.getText() == null ? "" : message.getText().trim();
            if (admittedInbound) {
                authorized = true;
            } else {
                routeGroupConversation(message);
                authorized =
                        message.isGroupGuest() || gatewayAuthorizationService.isAuthorized(message);
            }
            if (!authorized) {
                return null;
            }

            if (persistentInbound
                    && !admittedInbound
                    && !recordInboundProcessing(message, messageKey)) {
                log.info("Ignore persisted duplicate gateway message: {}", messageKey);
                return null;
            }
            if (persistentInbound && !admittedInbound) {
                inboundClaimed = true;
            }

            safeProcessingStart(message);
            processingStarted = true;

            final boolean commandMessage =
                    !message.isGroupGuest()
                            && text.startsWith(GatewayCommandConstants.COMMAND_PREFIX);
            message.setReplyCommitter(
                    committedReply -> {
                        terminalCommitAttempted.set(true);
                        if (committedReply != null && commandMessage) {
                            committedReply.setCommandHandled(true);
                        }
                        boolean completed = persistAndDeliverInboundReply(message, committedReply);
                        if (completed && committedReply != null) {
                            runPostDeliveryActions(message, committedReply);
                        }
                        return Boolean.valueOf(completed);
                    });
            GatewayReply reply;
            try {
                if (commandMessage) {
                    reply = commandService.handle(message, text);
                    if (reply != null) {
                        reply.setCommandHandled(true);
                    }
                } else {
                    reply = conversationOrchestrator.handleIncoming(message);
                }
            } finally {
                message.setReplyCommitter(null);
            }

            if (reply == null && !commandMessage) {
                processingOutcome =
                        terminalCommitAttempted.get()
                                ? ProcessingOutcome.FAILURE
                                : ProcessingOutcome.CANCELLED;
                convergeSuppressedInbound(
                        message,
                        terminalCommitAttempted.get()
                                ? "终态提交失败，禁止绕过写者租约补发。"
                                : "运行输出所有权已撤销，旧终态已取消。");
                return null;
            }
            processingOutcome =
                    reply != null && reply.isError()
                            ? ProcessingOutcome.FAILURE
                            : ProcessingOutcome.SUCCESS;
            if (!terminalCommitAttempted.get()) {
                boolean deliveryCompleted = persistAndDeliverInboundReply(message, reply);
                if (reply != null && deliveryCompleted) {
                    runPostDeliveryActions(message, reply);
                }
            }
            return reply;
        } catch (Exception e) {
            if (!persistentInbound && messageKey != null) {
                recentMessageKeys.remove(messageKey);
            }
            if (terminalCommitAttempted.get()) {
                processingOutcome = ProcessingOutcome.FAILURE;
                convergeSuppressedInbound(message, "终态提交异常，禁止绕过写者租约补发。");
                return null;
            }
            Throwable cause = rootCause(e);
            if (cause instanceof AgentRunCancelledException) {
                processingOutcome = ProcessingOutcome.CANCELLED;
                convergeSuppressedInbound(message, "运行已取消，禁止补发旧终态。");
                return null;
            }
            log.warn(
                    "Gateway handle failed: platform={}, chatId={}, userId={}, text={},"
                            + " errorType={}, error={}",
                    message.getPlatform(),
                    message.getChatId(),
                    message.getUserId(),
                    SecretRedactor.redact(message.getText(), 1000),
                    errorType(e),
                    safeMessage(e));
            GatewayReply errorReply = GatewayReply.error("处理消息失败：" + safeMessage(e));
            errorReply.getRuntimeMetadata().put("record_in_conversation", Boolean.TRUE);
            processingOutcome = ProcessingOutcome.FAILURE;
            if (authorized || inboundClaimed) {
                persistAndDeliverInboundReply(message, errorReply);
            }
            return errorReply;
        } finally {
            if (processingStarted) {
                safeProcessingComplete(
                        message,
                        processingOutcome == null
                                ? ProcessingOutcome.CANCELLED
                                : processingOutcome);
            }
        }
    }

    /** 把被写者租约抑制的持久化入站收敛为不可重放终态，不向渠道补发空回复或旧错误。 */
    private void convergeSuppressedInbound(GatewayMessage message, String reason) {
        if (channelInboundMessageRepository == null
                || message == null
                || StrUtil.isBlank(message.getIngressId())) {
            return;
        }
        try {
            channelInboundMessageRepository.markFailed(
                    message.getIngressId(), reason, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn(
                    "Suppressed inbound convergence failed: ingressId={}, errorType={}, error={}",
                    message.getIngressId(),
                    errorType(e),
                    safeMessage(e));
        }
    }

    /**
     * 生成安全展示用的Deliver目标Notice。
     *
     * @param message 平台消息或错误消息。
     * @param reply 回复参数。
     */
    private void safeDeliverGoalNotice(GatewayMessage message, GatewayReply reply) {
        if (reply == null
                || reply.getRuntimeMetadata() == null
                || !hasTextMetadata(reply, "goal_message")) {
            return;
        }
        GatewayReply notice = GatewayReply.ok(textMetadata(reply, "goal_message"));
        notice.getRuntimeMetadata().put("record_in_conversation", Boolean.TRUE);
        safeDeliver(message, notice);
    }

    /** 主回复完成投递并收敛总账后，统一执行通知、学习与目标续轮动作。 */
    private void runPostDeliveryActions(GatewayMessage message, GatewayReply reply) {
        safeDeliverGoalNotice(message, reply);
        safeScheduleLearning(message, reply);
        safeScheduleGoalKickoff(message, reply);
        safeScheduleGoalContinuation(message, reply);
    }

    /** 合成调度回复投递后执行既有后续动作，避免目标 kickoff 自身再次递归触发 kickoff。 */
    private void runPostScheduledDeliveryActions(GatewayMessage message, GatewayReply reply) {
        safeDeliverGoalNotice(message, reply);
        safeScheduleLearning(message, reply);
        safeScheduleGoalContinuation(message, reply);
    }

    /** 在 Agent 输出租约内提交合成调度回复；非默认编排器继续使用兼容投递路径。 */
    private GatewayReply runScheduledAndDeliver(GatewayMessage message) throws Exception {
        final AtomicBoolean terminalCommitAttempted = new AtomicBoolean(false);
        message.setReplyCommitter(
                reply -> {
                    terminalCommitAttempted.set(true);
                    boolean delivered = safeDeliver(message, reply);
                    if (delivered && reply != null) {
                        runPostScheduledDeliveryActions(message, reply);
                    }
                    return Boolean.valueOf(delivered);
                });
        GatewayReply reply;
        try {
            reply = conversationOrchestrator.runScheduled(message);
        } finally {
            message.setReplyCommitter(null);
        }
        if (reply != null && !terminalCommitAttempted.get() && safeDeliver(message, reply)) {
            runPostScheduledDeliveryActions(message, reply);
        }
        return reply;
    }

    /**
     * 生成安全展示用的调度目标Kickoff。
     *
     * @param message 平台消息或错误消息。
     * @param reply 回复参数。
     */
    private void safeScheduleGoalKickoff(final GatewayMessage message, GatewayReply reply) {
        if (reply == null
                || reply.getRuntimeMetadata() == null
                || !hasTextMetadata(reply, "goal_kickoff")) {
            return;
        }
        final String kickoff = textMetadata(reply, "goal_kickoff");
        Thread thread =
                new Thread(
                        ProfileRuntimeScope.capture(
                                new Runnable() {
                                    /** 执行异步任务主体。 */
                                    @Override
                                    public void run() {
                                        try {
                                            GatewayMessage kickoffMessage =
                                                    new GatewayMessage(
                                                            message.getPlatform(),
                                                            message.getChatId(),
                                                            message.getUserId(),
                                                            kickoff);
                                            kickoffMessage.setThreadId(message.getThreadId());
                                            kickoffMessage.setReplyToMessageId(
                                                    message.getReplyToMessageId());
                                            kickoffMessage.setChatType(message.getChatType());
                                            kickoffMessage.setChatName(message.getChatName());
                                            kickoffMessage.setUserName(message.getUserName());
                                            kickoffMessage.setProfile(message.getProfile());
                                            kickoffMessage.setSourceKeyOverride(
                                                    message.sourceKey());
                                            kickoffMessage.setGoalContinuation(true); // 标记为合成续轮
                                            runScheduledAndDeliver(kickoffMessage);
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Goal kickoff dispatch failed: sourceKey={},"
                                                            + " errorType={}, error={}",
                                                    message.sourceKey(),
                                                    errorType(e),
                                                    safeMessage(e));
                                        }
                                    }
                                }),
                        "jimuqu-goal-kickoff");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 生成安全展示用的调度目标Continuation。
     *
     * @param message 平台消息或错误消息。
     * @param reply 回复参数。
     */
    private void safeScheduleGoalContinuation(final GatewayMessage message, GatewayReply reply) {
        if (reply == null
                || reply.getRuntimeMetadata() == null
                || !Boolean.TRUE.equals(reply.getRuntimeMetadata().get("goal_should_continue"))) {
            return;
        }
        final String prompt = textMetadata(reply, "goal_continuation_prompt");
        if (StrUtil.isBlank(prompt)) {
            return;
        }
        Thread thread =
                new Thread(
                        ProfileRuntimeScope.capture(
                                new Runnable() {
                                    /** 执行异步任务主体。 */
                                    @Override
                                    public void run() {
                                        try {
                                            // 抢占检查：若有待处理真实用户消息，跳过本轮续轮，让真实消息接手
                                            if (agentRunSupervisor != null
                                                    && agentRunSupervisor.hasPendingRealMessage(
                                                            message.sourceKey())) {
                                                log.debug(
                                                        "goal continuation skipped: real user"
                                                                + " message pending for {}",
                                                        message.sourceKey());
                                                return;
                                            }
                                            GatewayMessage continuation =
                                                    new GatewayMessage(
                                                            message.getPlatform(),
                                                            message.getChatId(),
                                                            message.getUserId(),
                                                            prompt);
                                            continuation.setThreadId(message.getThreadId());
                                            continuation.setReplyToMessageId(
                                                    message.getReplyToMessageId());
                                            continuation.setChatType(message.getChatType());
                                            continuation.setChatName(message.getChatName());
                                            continuation.setUserName(message.getUserName());
                                            continuation.setProfile(message.getProfile());
                                            continuation.setSourceKeyOverride(message.sourceKey());
                                            continuation.setGoalContinuation(true); // 标记为合成续轮
                                            runScheduledAndDeliver(continuation);
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Goal continuation dispatch failed:"
                                                            + " sourceKey={}, errorType={}, error={}",
                                                    message.sourceKey(),
                                                    errorType(e),
                                                    safeMessage(e));
                                        }
                                    }
                                }),
                        "jimuqu-goal-continuation");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 判断是否存在Text元数据。
     *
     * @param reply 回复参数。
     * @param key 配置键或映射键。
     * @return 如果Text元数据满足条件则返回 true，否则返回 false。
     */
    private boolean hasTextMetadata(GatewayReply reply, String key) {
        return StrUtil.isNotBlank(textMetadata(reply, key));
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
     * 向指定渠道投递系统通知消息。
     *
     * @param platform 目标平台
     * @param chatId 目标会话 ID
     * @param threadId 目标线程 ID（可为空）
     * @param text 通知文本
     */
    public void deliverSystemNotification(
            PlatformType platform, String chatId, String threadId, String text) {
        if (platform == null || StrUtil.isBlank(chatId) || StrUtil.isBlank(text)) {
            return;
        }
        try {
            DeliveryRequest request = new DeliveryRequest();
            request.setProfile(profileName);
            request.setPlatform(platform);
            request.setChatId(chatId);
            request.setThreadId(threadId);
            request.setText(text);
            request.setRecordInConversation(true);
            deliveryService.deliver(request);
        } catch (Exception e) {
            log.warn(
                    "System notification delivery failed: platform={}, chatId={}, error={}",
                    platform,
                    chatId,
                    safeMessage(e));
        }
    }

    /** 安全投递当前回复，不让渠道发送失败打断主链。 */
    private boolean safeDeliver(GatewayMessage message, GatewayReply reply) {
        if (reply == null
                || StrUtil.isBlank(reply.getContent())
                        && (reply.getChannelExtras() == null
                                || reply.getChannelExtras().isEmpty())) {
            return true;
        }
        try {
            DeliveryRequest request = new DeliveryRequest();
            request.setProfile(message.getProfile());
            request.setPlatform(message.getPlatform());
            request.setChatId(message.getChatId());
            request.setUserId(message.getUserId());
            request.setChatType(message.getChatType());
            request.setThreadId(message.getThreadId());
            request.setReplyToMessageId(message.getReplyToMessageId());
            if (shouldRecordDeliveredReply(reply)) {
                request.setConversationSourceKey(message.sourceKey());
                request.setRecordInConversation(true);
            }
            GatewayMediaDeliverySupport.DeliveryMedia media =
                    mediaDeliverySupport == null
                            ? null
                            : mediaDeliverySupport.resolve(
                                    message.getPlatform(), reply.getContent());
            request.setText(media == null ? reply.getContent() : media.getText());
            if (media != null) {
                request.setAttachments(media.getAttachments());
            }
            if (reply.getChannelExtras() != null) {
                request.getChannelExtras().putAll(reply.getChannelExtras());
            }
            deliveryService.deliver(request);
            return true;
        } catch (Exception e) {
            log.warn(
                    "Gateway delivery failed: platform={}, chatId={}, userId={}, errorType={},"
                            + " error={}",
                    message.getPlatform(),
                    message.getChatId(),
                    message.getUserId(),
                    errorType(e),
                    safeMessage(e));
            return false;
        }
    }

    /** 命令、忙碌提示和独立通知未由模型会话保存，渠道发送后需要显式回写。 */
    private boolean shouldRecordDeliveredReply(GatewayReply reply) {
        return reply != null
                && (reply.isCommandHandled()
                        || reply.getRuntimeMetadata().containsKey("busy_status")
                        || Boolean.TRUE.equals(
                                reply.getRuntimeMetadata().get("record_in_conversation")));
    }

    /** 真实入站消息先落库并进入处理态；唯一键冲突时禁止重复执行业务主链。 */
    private boolean recordInboundProcessing(GatewayMessage message, String messageKey)
            throws Exception {
        long now = System.currentTimeMillis();
        String ingressId = java.util.UUID.randomUUID().toString();
        message.setIngressId(ingressId);

        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId(ingressId);
        record.setMessageKey(messageKey);
        record.setProfile(message.getProfile());
        record.setPlatform(String.valueOf(message.getPlatform()));
        record.setSourceKey(message.sourceKey());
        record.setMessageJson(serializeMessage(message));
        record.setStatus(ChannelInboundMessageRecord.STATUS_PROCESSING);
        record.setAttempts(1);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        if (!channelInboundMessageRepository.saveIfAbsent(record)) {
            message.setIngressId(null);
            return false;
        }
        return true;
    }

    /** 原子消费已准入的原始 receipt；首条承担逻辑处理，其余成员记录合并完成。 */
    private boolean startAdmittedInboundProcessing(GatewayMessage message) throws Exception {
        if (channelInboundMessageRepository == null
                || message == null
                || message.getInboundReceiptIds() == null
                || message.getInboundReceiptIds().isEmpty()) {
            return false;
        }
        List<String> receiptIds = new ArrayList<String>(message.getInboundReceiptIds());
        String leaderIngressId = receiptIds.get(0);
        message.setIngressId(leaderIngressId);
        return channelInboundMessageRepository.startBatch(
                leaderIngressId,
                receiptIds,
                message.sourceKey(),
                serializeMessage(message),
                System.currentTimeMillis());
    }

    /** 在领取 pending receipt 前完成附件水化，失败时让原始 receipt 保持可恢复。 */
    private void hydrateAdmittedInboundAttachments(GatewayMessage message) throws Exception {
        if (!hasUnresolvedInboundAttachments(message)) {
            return;
        }
        ChannelAdapter adapter = channelAdapter(message);
        if (adapter == null) {
            throw new IllegalStateException(
                    "No channel adapter can hydrate inbound attachments for "
                            + message.getPlatform());
        }
        adapter.hydrateInboundAttachments(message);
        if (hasUnresolvedInboundAttachments(message)) {
            throw new IllegalStateException(
                    "Channel adapter left unresolved inbound attachments for "
                            + message.getPlatform());
        }
    }

    /** 判断消息是否仍包含尚未转换成本地缓存文件的平台原始引用。 */
    private boolean hasUnresolvedInboundAttachments(GatewayMessage message) {
        if (message == null || message.getAttachments() == null) {
            return false;
        }
        for (com.jimuqu.solon.claw.core.model.MessageAttachment attachment :
                message.getAttachments()) {
            if (attachment != null && StrUtil.isNotBlank(attachment.getSourceReference())) {
                return true;
            }
        }
        return false;
    }

    /** 回复先写总账再投递；外发开始后的失败按结果未知收敛，禁止自动重放。 */
    private boolean persistAndDeliverInboundReply(GatewayMessage message, GatewayReply reply) {
        if (channelInboundMessageRepository == null
                || message == null
                || StrUtil.isBlank(message.getIngressId())) {
            return safeDeliver(message, reply);
        }
        String deliveryToken = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        boolean replyClaimed = false;
        try {
            if (!channelInboundMessageRepository.markProcessedForDelivery(
                    message.getIngressId(), serializeReply(reply), deliveryToken, now)) {
                log.warn(
                        "Inbound reply delivery skipped because persistence did not reach processed:"
                                + " ingressId={}",
                        message.getIngressId());
                return false;
            }
            replyClaimed = true;
            if (!channelInboundMessageRepository.markClaimedDeliveryStarted(
                    message.getIngressId(), deliveryToken, System.currentTimeMillis())) {
                log.warn(
                        "Inbound reply delivery skipped because owner could not enter delivering:"
                                + " ingressId={}",
                        message.getIngressId());
                releaseClaimedInboundDelivery(message.getIngressId(), deliveryToken);
                return false;
            }
        } catch (Exception e) {
            log.warn(
                    "Inbound reply owner persistence failed: ingressId={}, errorType={}, error={}",
                    message.getIngressId(),
                    errorType(e),
                    safeMessage(e));
            if (replyClaimed) {
                releaseClaimedInboundDelivery(message.getIngressId(), deliveryToken);
            }
            return false;
        }
        boolean delivered = safeDeliver(message, reply);
        if (delivered) {
            return completeInboundDelivery(message.getIngressId(), deliveryToken);
        } else {
            convergeInboundDeliveryFailure(message.getIngressId(), deliveryToken, "渠道投递失败。");
            return false;
        }
    }

    /** 按总账中的回复 JSON 还原回复对象。 */
    private GatewayReply deserializeReply(String replyJson) {
        if (StrUtil.isBlank(replyJson)) {
            return null;
        }
        Object parsed = ONode.deserialize(replyJson, Object.class);
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) parsed;
        GatewayReply reply = new GatewayReply();
        reply.setContent(stringValue(map.get("content")));
        reply.setError(Boolean.parseBoolean(stringValue(map.get("error"))));
        reply.setSessionId(stringValue(map.get("sessionId")));
        reply.setCommandHandled(Boolean.parseBoolean(stringValue(map.get("commandHandled"))));
        Object runtimeMetadata = map.get("runtimeMetadata");
        if (runtimeMetadata instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeMetadataMap = (Map<String, Object>) runtimeMetadata;
            reply.getRuntimeMetadata().putAll(runtimeMetadataMap);
        }
        Object channelExtras = map.get("channelExtras");
        if (channelExtras instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> channelExtrasMap = (Map<String, Object>) channelExtras;
            reply.getChannelExtras().putAll(channelExtrasMap);
        }
        return reply;
    }

    /** 把回复对象序列化成可恢复 JSON。 */
    private String serializeReply(GatewayReply reply) {
        Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        map.put("content", reply == null ? null : reply.getContent());
        map.put("error", Boolean.valueOf(reply != null && reply.isError()));
        map.put("sessionId", reply == null ? null : reply.getSessionId());
        map.put("commandHandled", Boolean.valueOf(reply != null && reply.isCommandHandled()));
        map.put(
                "runtimeMetadata",
                reply == null ? Collections.emptyMap() : reply.getRuntimeMetadata());
        map.put("channelExtras", reply == null ? Collections.emptyMap() : reply.getChannelExtras());
        return ONode.serialize(map);
    }

    /** 业务回复投递成功后收敛入站状态。 */
    private boolean completeInboundDelivery(String ingressId, String deliveryToken) {
        if (channelInboundMessageRepository == null || StrUtil.isBlank(ingressId)) {
            return true;
        }
        try {
            if (channelInboundMessageRepository.markClaimedCompleted(
                    ingressId, deliveryToken, System.currentTimeMillis())) {
                return true;
            }
            markInboundCompletionFailed(ingressId, deliveryToken, "完成态写入未命中 delivering owner 记录。");
        } catch (Exception e) {
            log.warn(
                    "Inbound completion persistence failed: ingressId={}, errorType={}, error={}",
                    ingressId,
                    errorType(e),
                    safeMessage(e));
            markInboundCompletionFailed(ingressId, deliveryToken, "完成态写入异常：" + safeMessage(e));
        }
        return false;
    }

    /** 投递已发生但完成态无法确认时收敛为不可自动重放，避免重启重复外发。 */
    private void markInboundCompletionFailed(String ingressId, String deliveryToken, String error) {
        try {
            channelInboundMessageRepository.markClaimedFailed(
                    ingressId,
                    deliveryToken,
                    "渠道已投递但" + error + " 禁止自动重放。",
                    System.currentTimeMillis());
        } catch (Exception persistenceError) {
            log.warn(
                    "Inbound completion failure convergence failed: ingressId={}, errorType={},"
                            + " error={}",
                    ingressId,
                    errorType(persistenceError),
                    safeMessage(persistenceError));
        }
    }

    /** 外发开始后的异常结果不可判定，按 owner 收敛为失败并禁止自动重放。 */
    private boolean convergeInboundDeliveryFailure(
            String ingressId, String deliveryToken, String error) {
        if (channelInboundMessageRepository == null
                || StrUtil.isBlank(ingressId)
                || StrUtil.isBlank(deliveryToken)) {
            return false;
        }
        try {
            return channelInboundMessageRepository.markClaimedFailed(
                    ingressId,
                    deliveryToken,
                    error + " 外发结果未知，已禁止自动重放。",
                    System.currentTimeMillis());
        } catch (Exception e) {
            log.warn(
                    "Inbound delivery failure convergence failed: ingressId={}, errorType={},"
                            + " error={}",
                    ingressId,
                    errorType(e),
                    safeMessage(e));
            return false;
        }
    }

    /** 仅释放当前 owner 尚未开始外发的单条认领。 */
    private void releaseClaimedInboundDelivery(String ingressId, String deliveryToken) {
        try {
            channelInboundMessageRepository.releaseClaimedDelivery(ingressId, deliveryToken);
        } catch (Exception e) {
            log.warn(
                    "Inbound delivery claim release failed: ingressId={}, errorType={}, error={}",
                    ingressId,
                    errorType(e),
                    safeMessage(e));
        }
    }

    /**
     * 在渠道启动前收敛前一次进程遗留的 processing 记录。
     *
     * @param profile 当前 Profile。
     * @param interruptedBefore 仅收敛不晚于该时间更新的旧记录。
     */
    public void convergeInterruptedInbounds(String profile, long interruptedBefore) {
        if (channelInboundMessageRepository == null
                || StrUtil.isBlank(profile)
                || interruptedBefore < 0L) {
            return;
        }
        try {
            channelInboundMessageRepository.markInterrupted(
                    profile, interruptedBefore, "进程重启，未完成入站消息已收敛。");
            channelInboundMessageRepository.convergeInterruptedDeliveries(
                    profile, interruptedBefore, "进程重启，渠道外发结果未知，已禁止自动重放。");
        } catch (Exception e) {
            log.warn(
                    "Interrupted inbound convergence failed: profile={}, errorType={}, error={}",
                    profile,
                    errorType(e),
                    safeMessage(e));
        }
    }

    /**
     * 按运行轨迹保留天数清理当前 Profile 足够旧的入站终态记录。
     *
     * @param profile 当前 Profile。
     * @param now 当前时间戳。
     * @return 实际删除数量；禁用、无仓储或清理失败时返回零。
     */
    public int pruneTerminalInbounds(String profile, long now) {
        int retentionDays = appConfig == null ? 0 : appConfig.getTrace().getRetentionDays();
        if (channelInboundMessageRepository == null
                || StrUtil.isBlank(profile)
                || retentionDays <= 0
                || now < 0L) {
            return 0;
        }
        try {
            int pruned =
                    channelInboundMessageRepository.pruneTerminal(
                            profile, now - TimeUnit.DAYS.toMillis(retentionDays));
            lastInboundPruneAt.set(now);
            if (pruned > 0) {
                log.info(
                        "Pruned {} expired inbound terminal record(s): profile={}",
                        pruned,
                        profile);
            }
            return pruned;
        } catch (Exception e) {
            lastInboundPruneAt.set(0L);
            log.warn(
                    "Inbound terminal pruning failed: profile={}, errorType={}, error={}",
                    profile,
                    errorType(e),
                    safeMessage(e));
            return 0;
        }
    }

    /** 入站准入时以日粒度触发终态保留期检查，覆盖长期不重启进程。 */
    private void maybePruneTerminalInbounds(long now) {
        long previous = lastInboundPruneAt.get();
        if (previous > 0L && now - previous < INBOUND_PRUNE_INTERVAL_MILLIS) {
            return;
        }
        if (lastInboundPruneAt.compareAndSet(previous, now)) {
            int pruned = pruneTerminalInbounds(profileName, now);
            if (pruned == 0 && lastInboundPruneAt.get() == now) {
                // 清理结果为空也属于成功检查；失败日志已由 pruneTerminalInbounds 隔离。
                lastInboundPruneAt.set(now);
            }
        }
    }

    /** 在渠道启动前认领当时已经保存但尚未完成投递的回复。 */
    public String claimStartupInboundDeliveries(String profile) {
        return claimInboundDeliveries(profile, null, false);
    }

    /** 在指定平台连接成功后认领并恢复该平台全部尚未完成的回复。 */
    public void recoverPlatformInboundDeliveries(String profile, PlatformType platform) {
        if (platform == null) {
            return;
        }
        String recoveryToken = claimInboundDeliveries(profile, platform, false);
        recoverInboundDeliveries(profile, recoveryToken);
    }

    /** 兼容旧调用名，实际恢复范围已包含没有写入失败摘要的未完成回复。 */
    public void recoverFailedInboundDeliveries(String profile, PlatformType platform) {
        recoverPlatformInboundDeliveries(profile, platform);
    }

    /** 认领并立即恢复当前已有的全部 processed 回复，供测试和显式恢复入口使用。 */
    public void recoverInboundDeliveries(String profile) {
        recoverInboundDeliveries(profile, claimStartupInboundDeliveries(profile));
    }

    /**
     * 恢复指定批次在渠道连接前已认领的回复。
     *
     * @param profile 当前 Profile。
     * @param recoveryToken 启动前或重连后生成的唯一恢复批次令牌。
     */
    public void recoverInboundDeliveries(String profile, String recoveryToken) {
        if (channelInboundMessageRepository == null
                || StrUtil.isBlank(profile)
                || StrUtil.isBlank(recoveryToken)) {
            return;
        }
        try {
            long afterProcessedAt = -1L;
            String afterIngressId = "";
            while (true) {
                List<ChannelInboundMessageRecord> records =
                        channelInboundMessageRepository.listClaimedDeliveries(
                                profile,
                                recoveryToken,
                                afterProcessedAt,
                                afterIngressId,
                                INBOUND_RECOVERY_BATCH_SIZE);
                if (records.isEmpty()) {
                    break;
                }
                for (ChannelInboundMessageRecord record : records) {
                    recoverInboundDelivery(record, profile, recoveryToken);
                }
                ChannelInboundMessageRecord last = records.get(records.size() - 1);
                afterProcessedAt = last.getProcessedAt();
                afterIngressId = last.getIngressId();
                if (records.size() < INBOUND_RECOVERY_BATCH_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Inbound delivery recovery failed: profile={}, errorType={}, error={}",
                    profile,
                    errorType(e),
                    safeMessage(e));
        } finally {
            releaseInboundDeliveryClaim(profile, recoveryToken);
        }
    }

    /** 按启动或单平台失败范围生成唯一令牌并原子认领 processed 回复。 */
    private String claimInboundDeliveries(
            String profile, PlatformType platform, boolean startupScope) {
        if (channelInboundMessageRepository == null || StrUtil.isBlank(profile)) {
            return null;
        }
        String recoveryToken = java.util.UUID.randomUUID().toString();
        try {
            int claimed =
                    startupScope || platform == null
                            ? channelInboundMessageRepository.claimStartupDeliveries(
                                    profile, recoveryToken)
                            : channelInboundMessageRepository.claimPlatformDeliveries(
                                    profile, String.valueOf(platform), recoveryToken);
            return claimed > 0 ? recoveryToken : null;
        } catch (Exception e) {
            log.warn(
                    "Inbound delivery claim failed: profile={}, platform={}, errorType={}, error={}",
                    profile,
                    platform,
                    errorType(e),
                    safeMessage(e));
            return null;
        }
    }

    /** 释放批次中因查询或投递异常仍未完成的记录，供下一次连接继续恢复。 */
    private void releaseInboundDeliveryClaim(String profile, String recoveryToken) {
        try {
            channelInboundMessageRepository.releaseDeliveryClaim(profile, recoveryToken);
        } catch (Exception e) {
            log.warn(
                    "Inbound delivery claim release failed: profile={}, errorType={}, error={}",
                    profile,
                    errorType(e),
                    safeMessage(e));
        }
    }

    /**
     * 捕获当前 Profile 已落库入站消息的稳定恢复水位。
     *
     * @param profile 当前 Profile。
     * @return 当前最大插入序号；未配置仓储或 Profile 为空时返回 0。
     * @throws Exception 仓储读取失败时向连接恢复屏障报告失败，由屏障稍后重试。
     */
    public long capturePendingInboundWatermark(String profile) throws Exception {
        if (channelInboundMessageRepository == null || StrUtil.isBlank(profile)) {
            return 0L;
        }
        try {
            return channelInboundMessageRepository.capturePendingWatermark(profile);
        } catch (Exception e) {
            log.warn(
                    "Pending inbound watermark capture failed: profile={}, errorType={}, error={}",
                    profile,
                    errorType(e),
                    safeMessage(e));
            throw e;
        }
    }

    /**
     * 恢复稳定插入水位内遗留的 pending 原始入站消息。
     *
     * <p>恢复时按单条消息处理，不尝试还原进程退出前的去抖边界；原始平台 ID 已逐条占位，因此不会重复执行或连带丢弃新消息。
     *
     * @param profile 当前 Profile。
     * @param maxSequence 渠道连接建立前捕获的稳定插入序号上界。
     * @throws Exception 恢复查询或单条处理失败时向连接恢复屏障报告失败，由屏障稍后重试。
     */
    public void recoverPendingInbounds(String profile, long maxSequence) throws Exception {
        if (channelInboundMessageRepository == null
                || StrUtil.isBlank(profile)
                || maxSequence <= 0L) {
            return;
        }
        synchronized (pendingInboundRecoveryLock) {
            recoverPendingInboundsLocked(profile, null, maxSequence);
        }
    }

    /**
     * 恢复指定平台稳定插入水位内遗留的 pending 原始入站消息。
     *
     * @param profile 当前 Profile。
     * @param platform 当前恢复连接的平台。
     * @param maxSequence 渠道连接建立前捕获的稳定插入序号上界。
     * @throws Exception 恢复查询或单条处理失败时向连接恢复屏障报告失败，由屏障稍后重试。
     */
    public void recoverPendingInbounds(String profile, PlatformType platform, long maxSequence)
            throws Exception {
        if (channelInboundMessageRepository == null
                || StrUtil.isBlank(profile)
                || platform == null
                || maxSequence <= 0L) {
            return;
        }
        synchronized (pendingInboundRecoveryLock) {
            recoverPendingInboundsLocked(profile, platform.name(), maxSequence);
        }
    }

    /** 在实例级恢复锁内按可选平台稳定分页并逐条消费 pending receipt。 */
    private void recoverPendingInboundsLocked(String profile, String platform, long maxSequence)
            throws Exception {
        long afterSequence = 0L;
        while (true) {
            List<ChannelInboundMessageRecord> records;
            try {
                records =
                        StrUtil.isBlank(platform)
                                ? channelInboundMessageRepository.listPending(
                                        profile,
                                        maxSequence,
                                        afterSequence,
                                        INBOUND_RECOVERY_BATCH_SIZE)
                                : channelInboundMessageRepository.listPending(
                                        profile,
                                        platform,
                                        maxSequence,
                                        afterSequence,
                                        INBOUND_RECOVERY_BATCH_SIZE);
            } catch (Exception e) {
                log.warn(
                        "Pending inbound recovery query failed: profile={}, platform={}, errorType={}, error={}",
                        profile,
                        platform,
                        errorType(e),
                        safeMessage(e));
                throw e;
            }
            if (records.isEmpty()) {
                return;
            }
            for (ChannelInboundMessageRecord record : records) {
                recoverPendingInbound(record, profile);
            }
            ChannelInboundMessageRecord last = records.get(records.size() - 1);
            afterSequence = last.getSequence();
            if (records.size() < INBOUND_RECOVERY_BATCH_SIZE) {
                return;
            }
        }
    }

    /** 恢复一条尚未进入业务主链的原始入站消息。 */
    private void recoverPendingInbound(ChannelInboundMessageRecord record, String profile)
            throws Exception {
        try {
            GatewayMessage message = deserializeMessage(record.getMessageJson());
            if (message == null || message.getPlatform() == null) {
                channelInboundMessageRepository.markFailed(
                        record.getIngressId(), "无法恢复 pending 入站消息内容。", System.currentTimeMillis());
                return;
            }
            if (StrUtil.isBlank(message.getProfile())) {
                message.setProfile(profile);
            }
            message.setIngressId(record.getIngressId());
            message.getInboundReceiptIds().clear();
            message.getInboundReceiptIds().add(record.getIngressId());
            handleAdmitted(message);
        } catch (Exception e) {
            log.warn(
                    "Pending inbound recovery failed: ingressId={}, errorType={}, error={}",
                    record == null ? null : record.getIngressId(),
                    errorType(e),
                    safeMessage(e));
            throw e;
        }
    }

    /**
     * 恢复单条已处理回复，并用 owner token 保护终态更新。
     *
     * @return 当前批次认领可以安全释放时返回 true；已投递但终态无法持久化时返回 false。
     */
    private boolean recoverInboundDelivery(
            ChannelInboundMessageRecord record, String profile, String recoveryToken) {
        long now = System.currentTimeMillis();
        boolean delivered = false;
        try {
            GatewayMessage message = deserializeMessage(record.getMessageJson());
            GatewayReply reply = deserializeReply(record.getReplyJson());
            if (message == null
                    || message.getPlatform() == null
                    || StrUtil.isBlank(message.getChatId())
                    || reply == null) {
                channelInboundMessageRepository.markClaimedFailed(
                        record.getIngressId(), recoveryToken, "无法恢复入站消息或回复内容。", now);
                return true;
            }
            if (StrUtil.isBlank(message.getProfile())) {
                message.setProfile(profile);
            }
            if (!channelInboundMessageRepository.markClaimedDeliveryStarted(
                    record.getIngressId(), recoveryToken, System.currentTimeMillis())) {
                releaseClaimedInboundDelivery(record.getIngressId(), recoveryToken);
                return true;
            }
            delivered = safeDeliver(message, reply);
            if (delivered) {
                if (channelInboundMessageRepository.markClaimedCompleted(
                        record.getIngressId(), recoveryToken, now)) {
                    runPostDeliveryActions(message, reply);
                    return true;
                }
                return markClaimedCompletionFailure(
                        record.getIngressId(), recoveryToken, "完成态 owner CAS 未命中。", now);
            } else {
                return convergeInboundDeliveryFailure(
                        record.getIngressId(), recoveryToken, "重连恢复投递失败。");
            }
        } catch (Exception e) {
            log.warn(
                    "Inbound recovery failed: ingressId={}, errorType={}, error={}",
                    record == null ? null : record.getIngressId(),
                    errorType(e),
                    safeMessage(e));
            if (delivered) {
                return markClaimedCompletionFailure(
                        record == null ? null : record.getIngressId(),
                        recoveryToken,
                        "完成态写入异常：" + safeMessage(e),
                        now);
            }
            return markClaimedCompletionFailure(
                    record == null ? null : record.getIngressId(),
                    recoveryToken,
                    "恢复状态异常：" + safeMessage(e),
                    now);
        }
    }

    /** 已发生外发但完成态无法确认时，尽力收敛为 owner 限定的不可重放失败。 */
    private boolean markClaimedCompletionFailure(
            String ingressId, String recoveryToken, String error, long now) {
        try {
            return channelInboundMessageRepository.markClaimedFailed(
                    ingressId, recoveryToken, "渠道已投递但" + error + " 禁止自动重放。", now);
        } catch (Exception persistenceError) {
            log.warn(
                    "Claimed inbound completion convergence failed: ingressId={}, errorType={},"
                            + " error={}",
                    ingressId,
                    errorType(persistenceError),
                    safeMessage(persistenceError));
            return false;
        }
    }

    /** 安全触发渠道处理开始表情回应，不让渠道状态反馈影响主处理链。 */
    private void safeProcessingStart(GatewayMessage message) {
        if (!processingReactionsEnabled()) {
            return;
        }
        ChannelAdapter adapter = channelAdapter(message);
        if (adapter == null) {
            return;
        }
        try {
            adapter.onProcessingStart(message);
        } catch (Exception e) {
            log.warn(
                    "Processing reaction start failed: platform={}, chatId={}, threadId={},"
                            + " errorType={}, error={}",
                    message.getPlatform(),
                    message.getChatId(),
                    message.getThreadId(),
                    errorType(e),
                    safeMessage(e));
        }
    }

    /** 安全触发渠道处理完成表情回应，不让渠道状态反馈影响主处理链。 */
    private void safeProcessingComplete(GatewayMessage message, ProcessingOutcome outcome) {
        if (!processingReactionsEnabled()) {
            return;
        }
        ChannelAdapter adapter = channelAdapter(message);
        if (adapter == null) {
            return;
        }
        try {
            adapter.onProcessingComplete(message, outcome);
        } catch (Exception e) {
            log.warn(
                    "Processing reaction complete failed: platform={}, chatId={}, threadId={},"
                            + " outcome={}, errorType={}, error={}",
                    message.getPlatform(),
                    message.getChatId(),
                    message.getThreadId(),
                    outcome,
                    errorType(e),
                    safeMessage(e));
        }
    }

    /** 查找入站消息所属平台的渠道适配器。 */
    private ChannelAdapter channelAdapter(GatewayMessage message) {
        if (message == null || message.getPlatform() == null) {
            return null;
        }
        return channelAdapters.get(message.getPlatform());
    }

    /** 判断是否启用渠道处理状态表情回应，未注入配置时沿用默认启用。 */
    private boolean processingReactionsEnabled() {
        return appConfig == null || appConfig.getGateway().isProcessingReactionsEnabled();
    }

    /** 安全触发后台学习，不让后台线程调度问题影响当前回复。 */
    private void safeScheduleLearning(GatewayMessage message, GatewayReply reply) {
        if (message == null
                || message.isGroupGuest()
                || reply == null
                || reply.isCommandHandled()
                || reply.isError()
                || reply.getSessionId() == null) {
            return;
        }
        try {
            SessionRecord session = sessionRepository.findById(reply.getSessionId());
            if (session != null) {
                skillLearningService.schedulePostReplyLearning(session, message, reply);
            }
        } catch (Exception e) {
            log.warn(
                    "Post-reply learning schedule failed: sessionId={}, errorType={}, error={}",
                    reply.getSessionId(),
                    errorType(e),
                    safeMessage(e));
        }
    }

    /**
     * 把群聊消息路由到主人私聊会话或访客隔离会话，回复目标仍保留原群聊。
     *
     * @param message 已通过渠道群聊与提及门禁的统一入站消息
     */
    private void routeGroupConversation(GatewayMessage message) throws Exception {
        if (message == null
                || message.getPlatform() == null
                || !GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(
                        StrUtil.nullToEmpty(message.getChatType()))) {
            return;
        }
        PlatformAdminRecord admin =
                gatewayAuthorizationService.platformAdmin(message.getPlatform());
        if (admin == null) {
            return;
        }
        if (StrUtil.equals(admin.getUserId(), message.getUserId())) {
            if (StrUtil.isNotBlank(admin.getChatId())) {
                message.setSourceKeyOverride(
                        GatewayMessage.directSourceKey(
                                message.getPlatform(), admin.getChatId(), admin.getUserId()));
            }
            return;
        }
        message.setSourceKeyOverride(
                GatewayMessage.groupGuestSourceKey(
                        message.getPlatform(), message.getChatId(), message.getUserId()));
    }

    /** 判断消息是否为需要总账保护的真实渠道入站消息。 */
    private boolean shouldPersistInbound(GatewayMessage message) {
        return channelInboundMessageRepository != null
                && message != null
                && message.getPlatform() != null
                && message.getPlatform() != PlatformType.MEMORY
                && !message.isHeartbeat()
                && !message.isGoalContinuation();
    }

    /** 生成跨进程稳定的渠道入站幂等键。 */
    private String inboundMessageKey(GatewayMessage message) {
        String platformMessageId = StrUtil.trimToNull(message.getPlatformMessageId());
        if (platformMessageId == null) {
            platformMessageId = StrUtil.trimToNull(message.getReplyToMessageId());
        }
        if (platformMessageId != null) {
            return String.valueOf(message.getPlatform())
                    + ":"
                    + StrUtil.nullToEmpty(message.getProfile())
                    + ":id:"
                    + StrUtil.nullToEmpty(message.getChatId())
                    + ":"
                    + platformMessageId;
        }
        long timestamp =
                message.getTimestamp() > 0L ? message.getTimestamp() : System.currentTimeMillis();
        String base =
                String.valueOf(message.getPlatform())
                        + ":"
                        + StrUtil.nullToEmpty(message.getProfile())
                        + ":"
                        + timestamp / DUPLICATE_WINDOW_MILLIS
                        + ":"
                        + StrUtil.nullToEmpty(message.getChatId())
                        + ":"
                        + StrUtil.nullToEmpty(message.getThreadId())
                        + ":"
                        + StrUtil.nullToEmpty(message.getUserId())
                        + ":"
                        + StrUtil.nullToEmpty(message.getText())
                        + ":"
                        + ONode.serialize(message.getAttachments());
        return String.valueOf(message.getPlatform())
                + ":"
                + StrUtil.nullToEmpty(message.getProfile())
                + ":fingerprint:"
                + sha256(base);
    }

    /** 保留原有进程内线程消息去重键，供内存与合成消息使用。 */
    private String recentMessageKey(GatewayMessage message) {
        if (message == null || StrUtil.isBlank(message.getThreadId())) {
            return null;
        }
        return String.valueOf(message.getPlatform())
                + ":"
                + StrUtil.nullToEmpty(message.getProfile())
                + ":"
                + StrUtil.nullToEmpty(message.getChatId())
                + ":"
                + message.getThreadId().trim()
                + ":"
                + StrUtil.nullToEmpty(message.getUserId())
                + ":"
                + StrUtil.nullToEmpty(message.getText()).hashCode();
    }

    /** 记录并判断是否为重复消息。 */
    private boolean isDuplicate(String messageKey) {
        if (messageKey == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long previous = recentMessageKeys.putIfAbsent(messageKey, now);
        if (previous == null) {
            return false;
        }
        if (now - previous < DUPLICATE_WINDOW_MILLIS) {
            return true;
        }
        recentMessageKeys.put(messageKey, now);
        return false;
    }

    /** 清理过期的重复消息键，避免进程内表无限增长。 */
    private void pruneDuplicateKeys() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Long> entry : recentMessageKeys.entrySet()) {
            if (now - entry.getValue() >= DUPLICATE_WINDOW_MILLIS) {
                recentMessageKeys.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 规范化目标 Profile，并在适配器未显式标记时补当前运行时身份。 */
    private String targetProfile(GatewayMessage message) {
        String raw =
                StrUtil.nullToEmpty(message.getProfile()).trim().toLowerCase(java.util.Locale.ROOT);
        String target = raw.length() == 0 ? profileName : raw;
        if (!target.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid Profile name: " + raw);
        }
        message.setProfile(target);
        return target;
    }

    /** 提炼用户可见错误信息。 */
    private String safeMessage(Exception e) {
        Throwable cause = rootCause(e);
        if (cause instanceof InterruptedException) {
            return "当前操作被中断，请重试一次。";
        }
        String message = cause == null ? null : cause.getMessage();
        if (StrUtil.isBlank(message)) {
            message = e.getMessage();
        }
        String safe = StrUtil.isBlank(message) ? e.getClass().getSimpleName() : message.trim();
        return SecretRedactor.redact(safe, 1000);
    }

    /**
     * 执行根用户Cause相关逻辑。
     *
     * @param throwable 捕获到的异常。
     * @return 返回根用户Cause结果。
     */
    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 执行错误类型相关逻辑。
     *
     * @param throwable 捕获到的异常。
     * @return 返回error类型结果。
     */
    private String errorType(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        return cause == null ? "Throwable" : cause.getClass().getSimpleName();
    }

    /** 把统一入站消息序列化为可恢复 JSON。 */
    private String serializeMessage(GatewayMessage message) {
        return message == null ? "{}" : ONode.serialize(message);
    }

    /** 把入站 JSON 还原成统一消息。 */
    private GatewayMessage deserializeMessage(String messageJson) {
        if (StrUtil.isBlank(messageJson)) {
            return null;
        }
        return ONode.deserialize(messageJson, GatewayMessage.class);
    }

    /** 统一把对象转成空安全字符串。 */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 计算稳定 SHA-256 指纹。 */
    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int value = b & 0xff;
                if (value < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate SHA-256", e);
        }
    }
}
