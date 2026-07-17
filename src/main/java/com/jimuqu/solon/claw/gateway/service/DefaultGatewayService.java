package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
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
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 网关主入口服务，负责把消息分流到授权、命令和对话主链。 */
public class DefaultGatewayService {
    /** 网关日志器。 */
    private static final Logger log = LoggerFactory.getLogger(DefaultGatewayService.class);

    /** 渠道消息去重窗口，单位毫秒。 */
    private static final long DUPLICATE_WINDOW_MILLIS = 10L * 60L * 1000L;

    /** 命令服务。 */
    private final CommandService commandService;

    /** 对话编排器。 */
    private final ConversationOrchestrator conversationOrchestrator;

    /** 渠道投递服务。 */
    private final DeliveryService deliveryService;

    /** 会话仓储。 */
    private final SessionRepository sessionRepository;

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
        this.commandService = commandService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.deliveryService = deliveryService;
        this.sessionRepository = sessionRepository;
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
     * 执行单条统一网关消息相关逻辑。
     *
     * @param message 渠道统一消息
     * @return 网关处理结果
     */
    public GatewayReply handle(GatewayMessage message) throws Exception {
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

        pruneDuplicateKeys();
        String messageKey = messageKey(message);
        if (isDuplicate(messageKey)) {
            log.info("Ignore duplicate gateway message: {}", messageKey);
            return null;
        }

        boolean authorized = false;
        boolean processingStarted = false;
        ProcessingOutcome processingOutcome = null;
        try {
            GatewayReply preAuth = gatewayAuthorizationService.preAuthorize(message);
            if (preAuth != null) {
                safeDeliver(message, preAuth);
                return preAuth;
            }

            String text = message.getText() == null ? "" : message.getText().trim();
            routeGroupConversation(message);
            authorized =
                    message.isGroupGuest() || gatewayAuthorizationService.isAuthorized(message);
            if (!authorized) {
                return null;
            }

            safeProcessingStart(message);
            processingStarted = true;

            GatewayReply reply;
            if (!message.isGroupGuest()
                    && text.startsWith(GatewayCommandConstants.COMMAND_PREFIX)) {
                reply = commandService.handle(message, text);
                if (reply != null) {
                    reply.setCommandHandled(true);
                }
            } else {
                reply = conversationOrchestrator.handleIncoming(message);
            }

            processingOutcome =
                    reply != null && reply.isError()
                            ? ProcessingOutcome.FAILURE
                            : ProcessingOutcome.SUCCESS;
            if (reply != null) {
                safeDeliver(message, reply);
                safeDeliverGoalNotice(message, reply);
                safeScheduleLearning(message, reply);
                safeScheduleGoalKickoff(message, reply);
                safeScheduleGoalContinuation(message, reply);
            }
            return reply;
        } catch (Exception e) {
            if (messageKey != null) {
                recentMessageKeys.remove(messageKey);
            }
            Throwable cause = rootCause(e);
            if (cause instanceof AgentRunCancelledException) {
                GatewayReply cancelledReply = GatewayReply.ok(cause.getMessage());
                processingOutcome = ProcessingOutcome.CANCELLED;
                if (authorized) {
                    safeDeliver(message, cancelledReply);
                }
                return cancelledReply;
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
            processingOutcome = ProcessingOutcome.FAILURE;
            if (authorized) {
                safeDeliver(message, errorReply);
            }
            return errorReply;
        } finally {
            if (processingStarted) {
                safeProcessingComplete(
                        message,
                        processingOutcome == null ? ProcessingOutcome.SUCCESS : processingOutcome);
            }
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
        safeDeliver(message, notice);
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
                                            GatewayReply next =
                                                    conversationOrchestrator.runScheduled(
                                                            kickoffMessage);
                                            if (next != null) {
                                                safeDeliver(kickoffMessage, next);
                                                safeDeliverGoalNotice(kickoffMessage, next);
                                                safeScheduleLearning(kickoffMessage, next);
                                                safeScheduleGoalContinuation(kickoffMessage, next);
                                            }
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
                                            GatewayReply next =
                                                    conversationOrchestrator.runScheduled(
                                                            continuation);
                                            if (next != null) {
                                                safeDeliver(continuation, next);
                                                safeDeliverGoalNotice(continuation, next);
                                                safeScheduleLearning(continuation, next);
                                                safeScheduleGoalContinuation(continuation, next);
                                            }
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
    private void safeDeliver(GatewayMessage message, GatewayReply reply) {
        if (reply == null
                || StrUtil.isBlank(reply.getContent())
                        && (reply.getChannelExtras() == null
                                || reply.getChannelExtras().isEmpty())) {
            return;
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
        } catch (Exception e) {
            log.warn(
                    "Gateway delivery failed: platform={}, chatId={}, userId={}, errorType={},"
                            + " error={}",
                    message.getPlatform(),
                    message.getChatId(),
                    message.getUserId(),
                    errorType(e),
                    safeMessage(e));
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

    /** 生成用于重复消息抑制的键。 */
    private String messageKey(GatewayMessage message) {
        if (StrUtil.isBlank(message.getThreadId())) {
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
}
