package com.jimuqu.solon.claw.gateway.platform.dingtalk;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aliyun.dingtalkconv_file_1_0.models.GetSpaceHeaders;
import com.aliyun.dingtalkconv_file_1_0.models.GetSpaceRequest;
import com.aliyun.dingtalkconv_file_1_0.models.GetSpaceResponse;
import com.aliyun.dingtalkconv_file_1_0.models.SendHeaders;
import com.aliyun.dingtalkconv_file_1_0.models.SendRequest;
import com.aliyun.dingtalkconv_file_1_0.models.SendResponse;
import com.aliyun.dingtalkim_1_0.models.SendRobotInteractiveCardHeaders;
import com.aliyun.dingtalkim_1_0.models.SendRobotInteractiveCardRequest;
import com.aliyun.dingtalkim_1_0.models.SendRobotInteractiveCardResponse;
import com.aliyun.dingtalkim_1_0.models.UpdateRobotInteractiveCardHeaders;
import com.aliyun.dingtalkim_1_0.models.UpdateRobotInteractiveCardRequest;
import com.aliyun.dingtalkim_1_0.models.UpdateRobotInteractiveCardResponse;
import com.aliyun.dingtalkoauth2_1_0.Client;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenRequest;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponse;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponseBody;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOHeaders;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTORequest;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOResponse;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponse;
import com.aliyun.dingtalkrobot_1_0.models.RobotMessageFileDownloadHeaders;
import com.aliyun.dingtalkrobot_1_0.models.RobotMessageFileDownloadRequest;
import com.aliyun.dingtalkrobot_1_0.models.RobotMessageFileDownloadResponse;
import com.aliyun.dingtalkrobot_1_0.models.RobotRecallEmotionHeaders;
import com.aliyun.dingtalkrobot_1_0.models.RobotRecallEmotionRequest;
import com.aliyun.dingtalkrobot_1_0.models.RobotReplyEmotionHeaders;
import com.aliyun.dingtalkrobot_1_0.models.RobotReplyEmotionRequest;
import com.aliyun.dingtalkstorage_2_0.models.CommitFileHeaders;
import com.aliyun.dingtalkstorage_2_0.models.CommitFileRequest;
import com.aliyun.dingtalkstorage_2_0.models.CommitFileResponse;
import com.aliyun.dingtalkstorage_2_0.models.GetFileUploadInfoHeaders;
import com.aliyun.dingtalkstorage_2_0.models.GetFileUploadInfoRequest;
import com.aliyun.dingtalkstorage_2_0.models.GetFileUploadInfoResponse;
import com.aliyun.tea.TeaException;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.ChannelAllowListSupport;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.ThreadInterruptSupport;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.noear.snack4.ONode;

/** DingTalkChannelAdapter 实现。 */
public class DingTalkChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** 媒体上传URL的统一常量值。 */
    private static final String MEDIA_UPLOAD_URL = "https://oapi.dingtalk.com/media/upload";

    /** 状态LAST用户标识的统一常量值。 */
    private static final String STATE_LAST_USER_ID = "last_user_id";

    /** 状态LASTUNION标识的统一常量值。 */
    private static final String STATE_LAST_UNION_ID = "last_union_id";

    /** 状态LASTSPACE标识的统一常量值。 */
    private static final String STATE_LAST_SPACE_ID = "last_space_id";

    /** 状态会话WEBHOOK的统一常量值。 */
    private static final String STATE_SESSION_WEBHOOK = "session_webhook";

    /** 状态会话WEBHOOKEXPIRES时间的统一常量值。 */
    private static final String STATE_SESSION_WEBHOOK_EXPIRES_AT = "session_webhook_expires_at";

    /** PROCESSINGEMOTION思考的统一常量值。 */
    private static final String PROCESSING_EMOTION_THINKING = "🤔Thinking";

    /** PROCESSINGEMOTIONDONE的统一常量值。 */
    private static final String PROCESSING_EMOTION_DONE = "🥳Done";

    /** PROCESSINGEMOTION类型的统一常量值。 */
    private static final int PROCESSING_EMOTION_TYPE = 2;

    /** PROCESSING文本EMOTION标识的统一常量值。 */
    private static final String PROCESSING_TEXT_EMOTION_ID = "2659900";

    /** PROCESSING文本EMOTIONBACKGROUND标识的统一常量值。 */
    private static final String PROCESSING_TEXT_EMOTION_BACKGROUND_ID = "im_bg_1";

    /** PROCESSINGEMOTION缓存大小的统一常量值。 */
    private static final int PROCESSING_EMOTION_CACHE_SIZE = 1024;

    /** 记录DingTalk渠道中的配置。 */
    private final AppConfig.ChannelConfig config;

    /** 保存渠道状态仓储依赖，用于访问持久化数据。 */
    private final ChannelStateRepository channelStateRepository;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录DingTalk渠道中的oauthClient。 */
    private final Client oauthClient;

    /** 记录DingTalk渠道中的robotClient。 */
    private final com.aliyun.dingtalkrobot_1_0.Client robotClient;

    /** 记录DingTalk渠道中的conv文件Client。 */
    private final com.aliyun.dingtalkconv_file_1_0.Client convFileClient;

    /** 记录DingTalk渠道中的storageClient。 */
    private final com.aliyun.dingtalkstorage_2_0.Client storageClient;

    /** 记录DingTalk渠道中的imClient。 */
    private final com.aliyun.dingtalkim_1_0.Client imClient;

    /** 记录DingTalk渠道中的access token。 */
    private volatile String accessToken;

    /** 记录DingTalk渠道中的access tokenExpire时间。 */
    private volatile long accessTokenExpireAt;

    /** 记录DingTalk渠道中的流Client。 */
    private volatile OpenDingTalkClient streamClient;

    /** 保存callback执行器执行组件，负责调度异步或定时任务。 */
    private ExecutorService callbackExecutor;

    /** 保存对话群组Flags映射，便于按键快速查询。 */
    private final Map<String, Boolean> conversationGroupFlags =
            new ConcurrentHashMap<String, Boolean>();

    /** 保存卡片InstanceBindings映射，便于按键快速查询。 */
    private final Map<String, String> cardInstanceBindings =
            new ConcurrentHashMap<String, String>();

    /** 保存processingEmotionStarts映射，便于按键快速查询。 */
    private final Map<String, Boolean> processingEmotionStarts = newProcessingEmotionCache();

    /** 保存processingEmotionCompletions映射，便于按键快速查询。 */
    private final Map<String, Boolean> processingEmotionCompletions = newProcessingEmotionCache();

    /**
     * 创建Ding Talk渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param channelStateRepository 渠道状态仓储依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DingTalkChannelAdapter(
            AppConfig.ChannelConfig config,
            ChannelStateRepository channelStateRepository,
            AttachmentCacheService attachmentCacheService) {
        this(config, channelStateRepository, attachmentCacheService, null);
    }

    /**
     * 创建Ding Talk渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param channelStateRepository 渠道状态仓储依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public DingTalkChannelAdapter(
            AppConfig.ChannelConfig config,
            ChannelStateRepository channelStateRepository,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        super(PlatformType.DINGTALK, config);
        this.config = config;
        this.channelStateRepository = channelStateRepository;
        this.attachmentCacheService = attachmentCacheService;
        this.securityPolicyService = securityPolicyService;
        try {
            com.aliyun.teaopenapi.models.Config teaConfig =
                    new com.aliyun.teaopenapi.models.Config();
            teaConfig.protocol = "https";
            teaConfig.regionId = "central";
            this.oauthClient = new Client(teaConfig);
            this.robotClient = new com.aliyun.dingtalkrobot_1_0.Client(teaConfig);
            this.convFileClient = new com.aliyun.dingtalkconv_file_1_0.Client(teaConfig);
            this.storageClient = new com.aliyun.dingtalkstorage_2_0.Client(teaConfig);
            this.imClient = new com.aliyun.dingtalkim_1_0.Client(teaConfig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize DingTalk SDK clients", e);
        }
        setConnectionMode("stream");
        setFeatures(
                "text",
                "attachments",
                "group-mention",
                "stream",
                "ai-card",
                "card-callback",
                "emoji-reaction");
        setSetupState(config != null && config.isEnabled() ? "configured" : "disabled");
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @return 返回connect结果。
     */
    @Override
    public boolean connect() {
        log.info(
                "[DINGTALK] connect called: enabled={}, hasClientId={}, hasClientSecret={}, hasEffectiveRobotCode={}",
                isEnabled(),
                !isBlank(config.getClientId()),
                !isBlank(config.getClientSecret()),
                !isBlank(effectiveRobotCode()));
        if (!isEnabled()) {
            setSetupState("disabled");
            return false;
        }
        if (rejectWeakCredentials(
                "dingtalk_weak_credentials",
                credentialField("solonclaw.channels.dingtalk.clientId", config.getClientId()),
                credentialField(
                        "solonclaw.channels.dingtalk.clientSecret", config.getClientSecret()),
                credentialField("solonclaw.channels.dingtalk.robotCode", effectiveRobotCode()))) {
            return false;
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<String>();
        if (isBlank(config.getClientId())) {
            missing.add("solonclaw.channels.dingtalk.clientId");
        }
        if (isBlank(config.getClientSecret())) {
            missing.add("solonclaw.channels.dingtalk.clientSecret");
        }
        if (isBlank(config.getRobotCode())) {
            missing.add("solonclaw.channels.dingtalk.robotCode");
        }
        if (!missing.isEmpty()) {
            setSetupState("missing_config");
            setMissingConfig(missing);
            setLastError("dingtalk_missing_credentials", "missing clientId/clientSecret/robotCode");
        }
        if (isBlank(config.getClientId())
                || isBlank(config.getClientSecret())
                || isBlank(config.getRobotCode())) {
            setDetail("missing clientId/clientSecret/robotCode");
            log.warn("[DINGTALK] connect aborted: {}", detail());
            return false;
        }

        try {
            refreshAccessTokenIfNecessary();
            callbackExecutor = Executors.newSingleThreadExecutor();
            streamClient =
                    OpenDingTalkStreamClientBuilder.custom()
                            .credential(
                                    new AuthClientCredential(
                                            config.getClientId(), config.getClientSecret()))
                            .registerCallbackListener(
                                    DingTalkStreamTopics.BOT_MESSAGE_TOPIC,
                                    new OpenDingTalkCallbackListener<
                                            ChatbotMessage, Map<String, Object>>() {
                                        /**
                                         * 执行当前回调或工具调用。
                                         *
                                         * @param message 平台消息或错误消息。
                                         * @return 返回执行结果。
                                         */
                                        public Map<String, Object> execute(ChatbotMessage message) {
                                            handleInbound(message);
                                            return new HashMap<String, Object>();
                                        }
                                    })
                            .registerCallbackListener(
                                    DingTalkStreamTopics.CARD_CALLBACK_TOPIC,
                                    new OpenDingTalkCallbackListener<
                                            Map<String, Object>, Map<String, Object>>() {
                                        /**
                                         * 执行当前回调或工具调用。
                                         *
                                         * @param payload 待签名或解析的载荷内容。
                                         * @return 返回执行结果。
                                         */
                                        @Override
                                        public Map<String, Object> execute(
                                                Map<String, Object> payload) {
                                            handleCardCallback(payload);
                                            return new HashMap<String, Object>();
                                        }
                                    })
                            .build();
            streamClient.start();
            setConnected(true);
            setSetupState("connected");
            setMissingConfig(new String[0]);
            clearLastError();
            setDetail("stream mode connected");
            log.info("[DINGTALK] stream mode connected");
            return true;
        } catch (Exception e) {
            setConnected(false);
            setSetupState("error");
            setLastError("dingtalk_stream_connect_failed", safeError(e));
            setDetail("stream mode connect failed: " + safeError(e));
            log.warn(
                    "[DINGTALK] Stream mode connect failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
            return false;
        }
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public void disconnect() {
        try {
            if (streamClient != null) {
                streamClient.stop();
            }
        } catch (Exception e) {
            log.warn(
                    "[DINGTALK] Stream mode disconnect failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        } finally {
            if (callbackExecutor != null) {
                callbackExecutor.shutdownNow();
            }
            // 关闭控制命令并发执行器，避免断开连接后线程泄漏
            shutdownControlExecutor();
            setConnected(false);
            setDetail("stream mode disconnected");
        }
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param request 当前请求对象。
     */
    @Override
    public void send(DeliveryRequest request) throws Exception {
        if (isBlank(request.getChatId())) {
            throw new IllegalArgumentException("DingTalk openConversationId is required");
        }
        refreshAccessTokenIfNecessary();
        if (isAiCardRequest(request)) {
            sendAiCard(request);
            return;
        }
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            if (notBlank(request.getText())) {
                sendText(request);
            }
            for (MessageAttachment attachment : request.getAttachments()) {
                sendAttachment(request, attachment);
            }
            return;
        }
        if (notBlank(request.getText())) {
            sendText(request);
        }
    }

    /** 在钉钉原消息上添加“处理中”自定义表情回应。 */
    @Override
    public void onProcessingStart(GatewayMessage message) {
        String conversationId = inboundConversationId(message);
        String messageId = inboundMessageId(message);
        String key = processingEmotionKey(conversationId, messageId);
        if (isBlank(key) || processingEmotionStarts.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        sendProcessingEmotion(conversationId, messageId, PROCESSING_EMOTION_THINKING, false);
    }

    /** 根据处理结果撤回或切换钉钉原消息上的自定义表情回应。 */
    @Override
    public void onProcessingComplete(GatewayMessage message, ProcessingOutcome outcome) {
        String conversationId = inboundConversationId(message);
        String messageId = inboundMessageId(message);
        String key = processingEmotionKey(conversationId, messageId);
        if (isBlank(key) || processingEmotionCompletions.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        sendProcessingEmotion(conversationId, messageId, PROCESSING_EMOTION_THINKING, true);
        if (ProcessingOutcome.SUCCESS.equals(outcome)) {
            sendProcessingEmotion(conversationId, messageId, PROCESSING_EMOTION_DONE, false);
        }
        processingEmotionStarts.remove(key);
    }

    /** 返回入站原始会话 ID，钉钉 emotion API 要求 openConversationId。 */
    private String inboundConversationId(GatewayMessage message) {
        return message == null ? "" : StrUtil.nullToEmpty(message.getChatId()).trim();
    }

    /** 返回入站原始消息 ID，当前统一承载在 threadId。 */
    private String inboundMessageId(GatewayMessage message) {
        return message == null ? "" : StrUtil.nullToEmpty(message.getThreadId()).trim();
    }

    /** 生成处理状态缓存键，避免同一条原消息重复添加或重复完成。 */
    private String processingEmotionKey(String conversationId, String messageId) {
        if (isBlank(conversationId) || isBlank(messageId)) {
            return "";
        }
        return conversationId + "\n" + messageId;
    }

    /** 创建有界处理状态缓存，避免长时间运行后积累历史消息 ID。 */
    private Map<String, Boolean> newProcessingEmotionCache() {
        return Collections.synchronizedMap(
                new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                    /**
                     * 移除Eldest Entry。
                     *
                     * @param eldest eldest 参数。
                     * @return 返回Eldest Entry结果。
                     */
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > PROCESSING_EMOTION_CACHE_SIZE;
                    }
                });
    }

    /**
     * 执行入站相关逻辑。
     *
     * @param message 平台消息或错误消息。
     */
    private void handleInbound(final ChatbotMessage message) {
        if (callbackExecutor == null || inboundMessageHandler() == null || message == null) {
            return;
        }
        // 控制命令（/stop、/cancel）走并发执行器，避免被串行回调队列中运行中的任务阻塞而错过取消时机
        if (isControlCommand(extractText(message))) {
            // 复用现有转换逻辑构造网关消息后再投递到控制执行器
            final String text = extractText(message);
            String conversationId =
                    notBlank(message.getConversationId())
                            ? message.getConversationId()
                            : message.getSenderId();
            String chatType =
                    "2".equals(String.valueOf(message.getConversationType())) ? "group" : "dm";
            String userId =
                    notBlank(message.getSenderStaffId())
                            ? message.getSenderStaffId()
                            : message.getSenderId();
            GatewayMessage gatewayMessage =
                    new GatewayMessage(PlatformType.DINGTALK, conversationId, userId, text);
            gatewayMessage.setChatType(chatType);
            gatewayMessage.setChatName(
                    notBlank(message.getConversationTitle())
                            ? message.getConversationTitle()
                            : conversationId);
            gatewayMessage.setUserName(
                    notBlank(message.getSenderNick()) ? message.getSenderNick() : userId);
            gatewayMessage.setThreadId(message.getMsgId());
            dispatchInboundControl(gatewayMessage);
            return;
        }
        callbackExecutor.submit(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    public void run() {
                        try {
                            String text = extractText(message);
                            String conversationId =
                                    notBlank(message.getConversationId())
                                            ? message.getConversationId()
                                            : message.getSenderId();
                            String chatType =
                                    "2".equals(String.valueOf(message.getConversationType()))
                                            ? "group"
                                            : "dm";
                            String userId =
                                    notBlank(message.getSenderStaffId())
                                            ? message.getSenderStaffId()
                                            : message.getSenderId();
                            if (!allowInbound(message, conversationId, chatType, userId)) {
                                return;
                            }
                            List<MessageAttachment> attachments = extractAttachments(message);
                            if (isBlank(text) && attachments.isEmpty()) {
                                return;
                            }
                            conversationGroupFlags.put(
                                    conversationId,
                                    "2".equals(String.valueOf(message.getConversationType())));
                            rememberSessionWebhook(
                                    conversationId,
                                    message.getSessionWebhook(),
                                    message.getSessionWebhookExpiredTime());
                            try {
                                channelStateRepository.put(
                                        PlatformType.DINGTALK,
                                        conversationId,
                                        STATE_LAST_USER_ID,
                                        userId);
                                channelStateRepository.put(
                                        PlatformType.DINGTALK,
                                        conversationId,
                                        STATE_LAST_UNION_ID,
                                        StrUtil.nullToEmpty(message.getSenderId()));
                            } catch (Exception e) {
                                logRecoverableChannelFailure("remember_inbound_sender", e);
                            }
                            log.info(
                                    "[DINGTALK-INBOUND] conversationId={}, senderId={}, senderStaffId={}, type={}, text={}",
                                    conversationId,
                                    message.getSenderId(),
                                    message.getSenderStaffId(),
                                    message.getConversationType(),
                                    text);
                            GatewayMessage gatewayMessage =
                                    new GatewayMessage(
                                            PlatformType.DINGTALK, conversationId, userId, text);
                            gatewayMessage.setChatType(chatType);
                            gatewayMessage.setChatName(
                                    notBlank(message.getConversationTitle())
                                            ? message.getConversationTitle()
                                            : conversationId);
                            gatewayMessage.setUserName(
                                    notBlank(message.getSenderNick())
                                            ? message.getSenderNick()
                                            : userId);
                            gatewayMessage.setThreadId(message.getMsgId());
                            gatewayMessage.setAttachments(attachments);
                            inboundMessageHandler().handle(gatewayMessage);
                        } catch (Exception e) {
                            log.warn(
                                    "[DINGTALK] inbound dispatch failed: errorType={}, error={}",
                                    errorType(e),
                                    safeError(e));
                        }
                    }
                });
    }

    /**
     * 执行卡片回调相关逻辑。
     *
     * @param payload 待签名或解析的载荷内容。
     */
    private void handleCardCallback(final Map<String, Object> payload) {
        if (callbackExecutor == null || inboundMessageHandler() == null || payload == null) {
            return;
        }
        // 先解析卡片回调消息，便于识别控制命令；控制命令走并发执行器避免被运行中的任务阻塞
        final GatewayMessage message = toCardCallbackMessage(payload);
        if (message == null) {
            return;
        }
        if (isControlCommand(message.getText())) {
            dispatchInboundControl(message);
            return;
        }
        callbackExecutor.submit(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        try {
                            inboundMessageHandler().handle(message);
                        } catch (Exception e) {
                            log.warn(
                                    "[DINGTALK] card callback dispatch failed: errorType={}, error={}",
                                    errorType(e),
                                    safeError(e));
                        }
                    }
                });
    }

    /** 刷新access token If Necessary。 */
    protected synchronized void refreshAccessTokenIfNecessary() throws Exception {
        long now = System.currentTimeMillis();
        if (!isBlank(accessToken) && accessTokenExpireAt > now + 60000L) {
            return;
        }

        GetAccessTokenRequest request =
                new GetAccessTokenRequest()
                        .setAppKey(config.getClientId())
                        .setAppSecret(config.getClientSecret());
        GetAccessTokenResponse response = oauthClient.getAccessToken(request);
        if (response == null || response.getBody() == null) {
            throw new IllegalStateException("DingTalk access token response is empty");
        }

        GetAccessTokenResponseBody body = response.getBody();
        if (isBlank(body.getAccessToken()) || body.getExpireIn() == null) {
            throw new IllegalStateException("DingTalk access token is missing");
        }

        accessToken = body.getAccessToken();
        accessTokenExpireAt = now + (body.getExpireIn() * 1000L);
    }

    /** 返回钉钉 SDK 调用实际使用的当前机器人编码。 */
    protected String effectiveRobotCode() {
        return StrUtil.nullToEmpty(config.getRobotCode()).trim();
    }

    /** 发送或撤回钉钉处理状态自定义表情，失败只记录日志，不阻断主消息回复。 */
    protected void sendProcessingEmotion(
            String conversationId, String messageId, String emotionName, boolean recall) {
        if (isBlank(conversationId) || isBlank(messageId) || isBlank(emotionName)) {
            return;
        }
        try {
            refreshAccessTokenIfNecessary();
            if (recall) {
                RobotRecallEmotionHeaders headers = new RobotRecallEmotionHeaders();
                headers.setXAcsDingtalkAccessToken(accessToken);
                robotClient.robotRecallEmotionWithOptions(
                        buildRecallEmotionRequest(conversationId, messageId, emotionName),
                        headers,
                        new com.aliyun.teautil.models.RuntimeOptions());
            } else {
                RobotReplyEmotionHeaders headers = new RobotReplyEmotionHeaders();
                headers.setXAcsDingtalkAccessToken(accessToken);
                robotClient.robotReplyEmotionWithOptions(
                        buildReplyEmotionRequest(conversationId, messageId, emotionName),
                        headers,
                        new com.aliyun.teautil.models.RuntimeOptions());
            }
        } catch (Exception e) {
            log.warn(
                    "[DINGTALK] processing emotion {} failed: conversationId={}, messageId={}, emotion={}, errorType={}, error={}",
                    recall ? "recall" : "reply",
                    conversationId,
                    messageId,
                    emotionName,
                    errorType(e),
                    safeError(e));
        }
    }

    /** 构造钉钉添加自定义表情请求，字段与平台 emotion API 保持一一对应。 */
    private RobotReplyEmotionRequest buildReplyEmotionRequest(
            String conversationId, String messageId, String emotionName) {
        return new RobotReplyEmotionRequest()
                .setRobotCode(effectiveRobotCode())
                .setOpenConversationId(conversationId)
                .setOpenMsgId(messageId)
                .setEmotionType(Integer.valueOf(PROCESSING_EMOTION_TYPE))
                .setEmotionName(emotionName)
                .setTextEmotion(
                        new RobotReplyEmotionRequest.RobotReplyEmotionRequestTextEmotion()
                                .setEmotionId(PROCESSING_TEXT_EMOTION_ID)
                                .setEmotionName(emotionName)
                                .setText(emotionName)
                                .setBackgroundId(PROCESSING_TEXT_EMOTION_BACKGROUND_ID));
    }

    /** 构造钉钉撤回自定义表情请求，用于清理“处理中”状态。 */
    private RobotRecallEmotionRequest buildRecallEmotionRequest(
            String conversationId, String messageId, String emotionName) {
        return new RobotRecallEmotionRequest()
                .setRobotCode(effectiveRobotCode())
                .setOpenConversationId(conversationId)
                .setOpenMsgId(messageId)
                .setEmotionType(Integer.valueOf(PROCESSING_EMOTION_TYPE))
                .setEmotionName(emotionName)
                .setTextEmotion(
                        new RobotRecallEmotionRequest.RobotRecallEmotionRequestTextEmotion()
                                .setEmotionId(PROCESSING_TEXT_EMOTION_ID)
                                .setEmotionName(emotionName)
                                .setText(emotionName)
                                .setBackgroundId(PROCESSING_TEXT_EMOTION_BACKGROUND_ID));
    }

    /**
     * 构建Markdown Param。
     *
     * @param text 待处理文本。
     * @return 返回创建好的Markdown Param。
     */
    private String buildMarkdownParam(String text) {
        return new ONode().set("title", resolveMarkdownTitle(text)).set("text", text).toJson();
    }

    /**
     * 判断是否群组对话。
     *
     * @param request 当前请求对象。
     * @return 如果群组对话满足条件则返回 true，否则返回 false。
     */
    private boolean isGroupConversation(DeliveryRequest request) {
        if ("group".equalsIgnoreCase(request.getChatType())) {
            return true;
        }
        if ("dm".equalsIgnoreCase(request.getChatType())) {
            return false;
        }
        Boolean value = conversationGroupFlags.get(request.getChatId());
        return value == null || value.booleanValue();
    }

    /**
     * 提取Text。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Text结果。
     */
    private String extractText(ChatbotMessage message) {
        String messageType = message.getMsgtype();
        if ("reaction".equalsIgnoreCase(messageType) || "emoji".equalsIgnoreCase(messageType)) {
            String contentText =
                    message.getContent() == null ? null : message.getContent().getContent();
            return StrUtil.blankToDefault(contentText, "reaction");
        }
        if ("audio".equalsIgnoreCase(messageType)
                && message.getContent() != null
                && notBlank(message.getContent().getRecognition())) {
            return message.getContent().getRecognition().trim();
        }
        MessageContent text = message.getText();
        if (text != null && !isBlank(text.getContent())) {
            return text.getContent().trim();
        }
        MessageContent content = message.getContent();
        if (content != null && !isBlank(content.getContent())) {
            return content.getContent().trim();
        }
        return "";
    }

    /**
     * 提取附件。
     *
     * @param message 平台消息或错误消息。
     * @return 返回附件结果。
     */
    private List<MessageAttachment> extractAttachments(ChatbotMessage message) {
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        MessageContent content = message.getContent();
        String msgType = StrUtil.nullToEmpty(message.getMsgtype()).toLowerCase();
        if ("picture".equals(msgType) && content != null) {
            addAttachment(
                    attachments,
                    "image",
                    content.getPictureDownloadCode(),
                    "image.jpg",
                    "image/jpeg",
                    null);
        } else if ("file".equals(msgType) && content != null) {
            addAttachment(
                    attachments,
                    "file",
                    content.getDownloadCode(),
                    content.getFileName(),
                    AttachmentCacheService.normalizeMimeType(null, content.getFileName()),
                    null);
        } else if ("video".equals(msgType) && content != null) {
            addAttachment(
                    attachments,
                    "video",
                    content.getDownloadCode(),
                    "video.mp4",
                    "video/mp4",
                    null);
        } else if ("audio".equals(msgType) && content != null) {
            addAttachment(
                    attachments,
                    "voice",
                    content.getDownloadCode(),
                    "voice.silk",
                    "audio/silk",
                    content.getRecognition());
        }
        if (content != null && content.getRichText() != null) {
            for (MessageContent item : content.getRichText()) {
                String itemType = StrUtil.nullToEmpty(item.getType()).toLowerCase();
                if ("picture".equals(itemType) || "image".equals(itemType)) {
                    addAttachment(
                            attachments,
                            "image",
                            notBlank(item.getPictureDownloadCode())
                                    ? item.getPictureDownloadCode()
                                    : item.getDownloadCode(),
                            "image.jpg",
                            "image/jpeg",
                            null);
                } else if ("file".equals(itemType)) {
                    addAttachment(
                            attachments,
                            "file",
                            item.getDownloadCode(),
                            item.getFileName(),
                            AttachmentCacheService.normalizeMimeType(null, item.getFileName()),
                            null);
                } else if ("video".equals(itemType)) {
                    addAttachment(
                            attachments,
                            "video",
                            item.getDownloadCode(),
                            "video.mp4",
                            "video/mp4",
                            null);
                } else if ("audio".equals(itemType) || "voice".equals(itemType)) {
                    addAttachment(
                            attachments,
                            "voice",
                            item.getDownloadCode(),
                            "voice.silk",
                            "audio/silk",
                            item.getRecognition());
                }
            }
        }
        return attachments;
    }

    /**
     * 追加附件。
     *
     * @param attachments attachments 参数。
     * @param kind kind 参数。
     * @param downloadCode downloadCode 参数。
     * @param fileName 文件或目录路径参数。
     * @param mimeType MIME 类型参数。
     * @param transcribedText transcribed文本参数。
     */
    private void addAttachment(
            List<MessageAttachment> attachments,
            String kind,
            String downloadCode,
            String fileName,
            String mimeType,
            String transcribedText) {
        if (isBlank(downloadCode)) {
            return;
        }
        try {
            String downloadUrl = resolveDownloadUrl(downloadCode);
            byte[] data =
                    BoundedAttachmentIO.downloadHutool(
                            downloadUrl,
                            30000,
                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                            securityPolicyService);
            attachments.add(
                    attachmentCacheService.cacheBytes(
                            PlatformType.DINGTALK,
                            kind,
                            fileName,
                            mimeType,
                            false,
                            transcribedText,
                            data));
        } catch (Exception e) {
            log.warn(
                    "[DINGTALK] attachment download failed: kind={}, code={}, message={}",
                    kind,
                    downloadCode,
                    e.getMessage());
        }
    }

    /**
     * 解析Download URL。
     *
     * @param downloadCode downloadCode 参数。
     * @return 返回解析后的Download URL。
     */
    private String resolveDownloadUrl(String downloadCode) throws Exception {
        RobotMessageFileDownloadHeaders headers = new RobotMessageFileDownloadHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        RobotMessageFileDownloadRequest request =
                new RobotMessageFileDownloadRequest()
                        .setDownloadCode(downloadCode)
                        .setRobotCode(effectiveRobotCode());
        RobotMessageFileDownloadResponse response =
                robotClient.robotMessageFileDownloadWithOptions(
                        request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null
                || response.getBody() == null
                || isBlank(response.getBody().getDownloadUrl())) {
            throw new IllegalStateException("DingTalk download url missing");
        }
        return response.getBody().getDownloadUrl();
    }

    /**
     * 发送Text。
     *
     * @param request 当前请求对象。
     */
    private void sendText(DeliveryRequest request) throws Exception {
        boolean isGroup = isGroupConversation(request);
        if (isGroup) {
            try {
                OrgGroupSendHeaders headers = new OrgGroupSendHeaders();
                headers.setXAcsDingtalkAccessToken(accessToken);

                OrgGroupSendRequest sendRequest = new OrgGroupSendRequest();
                sendRequest.setRobotCode(effectiveRobotCode());
                sendRequest.setOpenConversationId(request.getChatId());
                sendRequest.setMsgKey("sampleMarkdown");
                sendRequest.setMsgParam(buildMarkdownParam(request.getText()));
                if (!isBlank(config.getCoolAppCode())) {
                    sendRequest.setCoolAppCode(config.getCoolAppCode());
                }

                OrgGroupSendResponse response =
                        robotClient.orgGroupSendWithOptions(
                                sendRequest,
                                headers,
                                new com.aliyun.teautil.models.RuntimeOptions());
                if (response == null || response.getBody() == null) {
                    throw new IllegalStateException("DingTalk group send returned empty response");
                }
                log.info(
                        "[DINGTALK:{}] sent processKey={}",
                        request.getChatId(),
                        response.getBody().getProcessQueryKey());
            } catch (TeaException e) {
                log.warn(
                        "[DINGTALK] group send failed: code={}, message={}, data={}, error={}",
                        e.getCode(),
                        SecretRedactor.redact(e.getMessage(), 1000),
                        SecretRedactor.redact(String.valueOf(e.getData()), 1000),
                        safeError(e));
                throw e;
            }
        } else {
            String privateUserId = request.getUserId();
            if (isBlank(privateUserId)) {
                privateUserId =
                        channelStateRepository.get(
                                PlatformType.DINGTALK, request.getChatId(), STATE_LAST_USER_ID);
            }
            if (isBlank(privateUserId)) {
                throw new IllegalStateException(
                        "DingTalk private chat send requires userId from inbound context.");
            }
            try {
                BatchSendOTOHeaders headers = new BatchSendOTOHeaders();
                headers.setXAcsDingtalkAccessToken(accessToken);

                BatchSendOTORequest sendRequest = new BatchSendOTORequest();
                sendRequest.setRobotCode(effectiveRobotCode());
                sendRequest.setUserIds(java.util.Collections.singletonList(privateUserId));
                sendRequest.setMsgKey("sampleMarkdown");
                sendRequest.setMsgParam(buildMarkdownParam(request.getText()));

                BatchSendOTOResponse response =
                        robotClient.batchSendOTOWithOptions(
                                sendRequest,
                                headers,
                                new com.aliyun.teautil.models.RuntimeOptions());
                if (response == null || response.getBody() == null) {
                    throw new IllegalStateException(
                            "DingTalk private send returned empty response");
                }
                log.info(
                        "[DINGTALK:{}] sent private batch response={}",
                        request.getUserId(),
                        response.getBody().toMap());
            } catch (TeaException e) {
                log.warn(
                        "[DINGTALK] private send failed: code={}, message={}, data={}, error={}",
                        e.getCode(),
                        SecretRedactor.redact(e.getMessage(), 1000),
                        SecretRedactor.redact(String.valueOf(e.getData()), 1000),
                        safeError(e));
                throw e;
            }
        }
    }

    /**
     * 发送附件。
     *
     * @param request 当前请求对象。
     * @param attachment 附件参数。
     */
    private void sendAttachment(DeliveryRequest request, MessageAttachment attachment)
            throws Exception {
        DeliveryContext context = resolveDeliveryContext(request);
        String fileName =
                StrUtil.blankToDefault(
                        attachment.getOriginalName(),
                        new File(attachment.getLocalPath()).getName());
        byte[] data = cn.hutool.core.io.FileUtil.readBytes(new File(attachment.getLocalPath()));
        String spaceId = resolveSpaceId(context);
        String uploadKey = getUploadInfo(context.unionId, fileName, data.length, spaceId);
        uploadFileBytes(uploadKey, data, context.unionId, spaceId, fileName);
        String dentryId =
                commitUploadedFile(uploadKey, context.unionId, spaceId, fileName, data.length);
        sendConversationFile(context, spaceId, dentryId);
        log.info("[DINGTALK:{}] native attachment sent {}", request.getChatId(), fileName);
    }

    /**
     * 判断是否Ai Card请求。
     *
     * @param request 当前请求对象。
     * @return 如果Ai Card请求满足条件则返回 true，否则返回 false。
     */
    protected boolean isAiCardRequest(DeliveryRequest request) {
        Map<String, Object> extras = request.getChannelExtras();
        if (extras == null || extras.isEmpty()) {
            return false;
        }
        if (!config.isAiCardStreamingEnabled() && isAiCardUpdateRequest(extras)) {
            return false;
        }
        String mode = stringValue(extras.get("mode"));
        return "ai_card".equalsIgnoreCase(mode)
                || "dingtalk_ai_card".equalsIgnoreCase(mode)
                || StrUtil.isNotBlank(stringValue(extras.get("cardTemplateId")));
    }

    /**
     * 发送Ai Card。
     *
     * @param request 当前请求对象。
     */
    protected void sendAiCard(DeliveryRequest request) throws Exception {
        Map<String, Object> extras =
                request.getChannelExtras() == null
                        ? Collections.<String, Object>emptyMap()
                        : request.getChannelExtras();
        if (isAiCardUpdateRequest(extras)) {
            updateAiCard(extras);
            return;
        }
        String cardTemplateId = stringValue(extras.get("cardTemplateId"));
        String cardData = jsonString(extras.get("cardData"));
        if (isBlank(cardTemplateId) || isBlank(cardData)) {
            throw new IllegalStateException(
                    "DingTalk AI card send requires channelExtras.cardTemplateId and channelExtras.cardData");
        }

        SendRobotInteractiveCardHeaders headers = new SendRobotInteractiveCardHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        SendRobotInteractiveCardRequest sendRequest = new SendRobotInteractiveCardRequest();
        sendRequest.setRobotCode(effectiveRobotCode());
        sendRequest.setCardTemplateId(cardTemplateId);
        sendRequest.setCardData(cardData);
        sendRequest.setCardBizId(
                StrUtil.blankToDefault(
                        stringValue(extras.get("cardBizId")),
                        "jimuqu-card-" + System.currentTimeMillis()));

        SendRobotInteractiveCardRequest.SendRobotInteractiveCardRequestSendOptions sendOptions =
                new SendRobotInteractiveCardRequest.SendRobotInteractiveCardRequestSendOptions();
        if (extras.containsKey("atAll")) {
            sendOptions.setAtAll(
                    Boolean.valueOf(Boolean.parseBoolean(String.valueOf(extras.get("atAll")))));
        }
        if (extras.containsKey("cardPropertyJson")) {
            sendOptions.setCardPropertyJson(stringValue(extras.get("cardPropertyJson")));
        }
        if (extras.containsKey("atUserListJson")) {
            sendOptions.setAtUserListJson(stringValue(extras.get("atUserListJson")));
        }
        if (extras.containsKey("receiverListJson")) {
            sendOptions.setReceiverListJson(stringValue(extras.get("receiverListJson")));
        }
        if (hasAnySendOptions(sendOptions)) {
            sendRequest.setSendOptions(sendOptions);
        }

        if (isGroupConversation(request)) {
            sendRequest.setOpenConversationId(request.getChatId());
        } else {
            String receiver = request.getUserId();
            if (isBlank(receiver)) {
                receiver =
                        channelStateRepository.get(
                                PlatformType.DINGTALK, request.getChatId(), STATE_LAST_USER_ID);
            }
            if (isBlank(receiver)) {
                throw new IllegalStateException(
                        "DingTalk AI card send requires userId from inbound context for private chat");
            }
            sendRequest.setSingleChatReceiver(receiver);
        }

        if (extras.containsKey("callbackUrl")) {
            sendRequest.setCallbackUrl(stringValue(extras.get("callbackUrl")));
        }

        SendRobotInteractiveCardResponse response =
                imClient.sendRobotInteractiveCardWithOptions(
                        sendRequest, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null
                || response.getBody() == null
                || isBlank(response.getBody().getProcessQueryKey())) {
            throw new IllegalStateException("DingTalk AI card send failed");
        }
        if (StrUtil.isNotBlank(request.getThreadId())) {
            cardInstanceBindings.put(
                    response.getBody().getProcessQueryKey(), request.getThreadId().trim());
        }
        log.info(
                "[DINGTALK:{}] ai card sent processKey={}",
                request.getChatId(),
                response.getBody().getProcessQueryKey());
    }

    /**
     * 判断是否Ai Card更新请求。
     *
     * @param extras extras 参数。
     * @return 如果Ai Card更新请求满足条件则返回 true，否则返回 false。
     */
    private boolean isAiCardUpdateRequest(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return false;
        }
        String mode = stringValue(extras.get("mode"));
        if ("ai_card_update".equalsIgnoreCase(mode)
                || "dingtalk_ai_card_update".equalsIgnoreCase(mode)) {
            return true;
        }
        return Boolean.parseBoolean(String.valueOf(extras.get("updateExisting")));
    }

    /**
     * 更新Ai Card。
     *
     * @param extras extras 参数。
     */
    private void updateAiCard(Map<String, Object> extras) throws Exception {
        String cardBizId = stringValue(extras.get("cardBizId"));
        String cardData = jsonString(extras.get("cardData"));
        if (isBlank(cardBizId) || isBlank(cardData)) {
            throw new IllegalStateException(
                    "DingTalk AI card update requires channelExtras.cardBizId and channelExtras.cardData");
        }

        UpdateRobotInteractiveCardHeaders headers = new UpdateRobotInteractiveCardHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        UpdateRobotInteractiveCardRequest request = new UpdateRobotInteractiveCardRequest();
        request.setCardBizId(cardBizId);
        request.setCardData(cardData);
        UpdateRobotInteractiveCardRequest.UpdateRobotInteractiveCardRequestUpdateOptions options =
                new UpdateRobotInteractiveCardRequest
                        .UpdateRobotInteractiveCardRequestUpdateOptions();
        options.setUpdateCardDataByKey(Boolean.TRUE);
        request.setUpdateOptions(options);

        UpdateRobotInteractiveCardResponse response =
                imClient.updateRobotInteractiveCardWithOptions(
                        request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null || response.getBody() == null) {
            throw new IllegalStateException("DingTalk AI card update failed");
        }
        log.info("[DINGTALK] ai card updated cardBizId={}", cardBizId);
    }

    /**
     * 执行upload媒体相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回upload媒体结果。
     */
    private String uploadMedia(MessageAttachment attachment) {
        File file = new File(attachment.getLocalPath());
        if (!file.isFile()) {
            throw new IllegalStateException(
                    MessageAttachmentSupport.fileNotFoundMessage("DingTalk", attachment));
        }
        String kind =
                AttachmentCacheService.normalizeKind(
                        attachment.getKind(),
                        attachment.getOriginalName(),
                        attachment.getMimeType());
        String type = "image".equals(kind) ? "image" : ("voice".equals(kind) ? "voice" : "file");
        String uploadUrl = MEDIA_UPLOAD_URL + "?access_token=" + accessToken + "&type=" + type;
        HttpResponse uploadResponse =
                HttpRequest.post(uploadUrl)
                        .form("media", file)
                        .timeout(30000)
                        .setFollowRedirects(false)
                        .execute();
        String response;
        try {
            response = guardedResponseBody(uploadResponse, "DingTalk media upload");
        } finally {
            uploadResponse.close();
        }
        ONode node = ONode.ofJson(response);
        int errCode = node.get("errcode").getInt(0);
        if (errCode != 0) {
            throw new IllegalStateException("DingTalk media upload failed: " + response);
        }
        String mediaId = node.get("media_id").getString();
        if (isBlank(mediaId)) {
            throw new IllegalStateException("DingTalk media upload missing media_id");
        }
        return mediaId;
    }

    /**
     * 构建Webhook媒体Payload。
     *
     * @param kind kind 参数。
     * @param mediaId 媒体标识。
     * @param attachment 附件参数。
     * @return 返回创建好的Webhook媒体Payload。
     */
    private ONode buildWebhookMediaPayload(
            String kind, String mediaId, MessageAttachment attachment) {
        if ("image".equals(kind)) {
            return new ONode()
                    .set("msgtype", "image")
                    .getOrNew("image")
                    .set("media_id", mediaId)
                    .parent()
                    .asObject();
        }
        if ("voice".equals(kind)
                && (StrUtil.endWithIgnoreCase(attachment.getOriginalName(), ".ogg")
                        || StrUtil.endWithIgnoreCase(attachment.getOriginalName(), ".amr"))) {
            return new ONode()
                    .set("msgtype", "voice")
                    .getOrNew("voice")
                    .set("media_id", mediaId)
                    .set("duration", "1")
                    .parent()
                    .asObject();
        }
        return new ONode()
                .set("msgtype", "file")
                .getOrNew("file")
                .set("media_id", mediaId)
                .parent()
                .asObject();
    }

    /**
     * 执行remember会话Webhook相关逻辑。
     *
     * @param chatId 聊天标识。
     * @param sessionWebhook 会话Webhook参数。
     * @param expiredTime expired时间参数。
     */
    private void rememberSessionWebhook(String chatId, String sessionWebhook, Long expiredTime) {
        if (isBlank(chatId) || isBlank(sessionWebhook)) {
            return;
        }
        long expiresAt = expiredTime == null ? 0L : expiredTime.longValue();
        try {
            channelStateRepository.put(
                    PlatformType.DINGTALK, chatId, STATE_SESSION_WEBHOOK, sessionWebhook);
            channelStateRepository.put(
                    PlatformType.DINGTALK,
                    chatId,
                    STATE_SESSION_WEBHOOK_EXPIRES_AT,
                    String.valueOf(expiresAt));
        } catch (Exception e) {
            logRecoverableChannelFailure("remember_session_webhook", e);
        }
    }

    /**
     * 记录钉钉渠道可降级失败，只输出平台、阶段和异常类型，避免泄露消息正文或凭据。
     *
     * @param stage 失败发生的内部阶段。
     * @param error 捕获到的异常。
     */
    private void logRecoverableChannelFailure(String stage, Exception error) {
        ThreadInterruptSupport.restoreIfCausedByInterrupted(error);
        log.debug(
                "[DINGTALK] recoverable channel failure: platform={}, stage={}, errorType={}",
                PlatformType.DINGTALK,
                stage,
                errorType(error));
    }

    /**
     * 判断是否允许入站。
     *
     * @param message 平台消息或错误消息。
     * @param conversationId conversation标识。
     * @param chatType 聊天类型参数。
     * @param userId 用户标识。
     * @return 如果入站满足条件则返回 true，否则返回 false。
     */
    private boolean allowInbound(
            ChatbotMessage message, String conversationId, String chatType, String userId) {
        if ("group".equals(chatType)) {
            if (!config.getAllowedChats().isEmpty()
                    && !ChannelAllowListSupport.contains(
                            config.getAllowedChats(), conversationId)) {
                return false;
            }
            String groupPolicy =
                    StrUtil.blankToDefault(
                                    config.getGroupPolicy(),
                                    GatewayBehaviorConstants.GROUP_POLICY_OPEN)
                            .toLowerCase();
            if (GatewayBehaviorConstants.GROUP_POLICY_DISABLED.equals(groupPolicy)) {
                return false;
            }
            if (GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST.equals(groupPolicy)
                    && !ChannelAllowListSupport.contains(
                            config.getGroupAllowedUsers(), conversationId)) {
                return false;
            }
            return !config.isRequireMention()
                    || ChannelAllowListSupport.contains(
                            config.getFreeResponseChats(), conversationId)
                    || Boolean.TRUE.equals(message.getInAtList());
        }
        String dmPolicy =
                StrUtil.blankToDefault(
                                config.getDmPolicy(), GatewayBehaviorConstants.DM_POLICY_OPEN)
                        .toLowerCase();
        if (GatewayBehaviorConstants.DM_POLICY_DISABLED.equals(dmPolicy)) {
            return false;
        }
        if (GatewayBehaviorConstants.DM_POLICY_ALLOWLIST.equals(dmPolicy)) {
            return ChannelAllowListSupport.contains(config.getAllowedUsers(), userId);
        }
        return true;
    }

    /**
     * 转换为Card Callback消息。
     *
     * @param payload 待签名或解析的载荷内容。
     * @return 返回转换后的Card Callback消息。
     */
    private GatewayMessage toCardCallbackMessage(Map<String, Object> payload) {
        ONode node = ONode.ofJson(ONode.serialize(payload));
        String processKey =
                StrUtil.firstNonBlank(
                        node.get("processQueryKey").getString(),
                        node.get("process_query_key").getString(),
                        node.get("outTrackId").getString(),
                        node.get("out_track_id").getString(),
                        node.get("cardBizId").getString(),
                        node.get("card_biz_id").getString());
        String chatId =
                StrUtil.firstNonBlank(
                        node.get("openConversationId").getString(),
                        node.get("open_conversation_id").getString(),
                        findNested(node, "conversation", "openConversationId"),
                        cardInstanceBindings.get(processKey));
        String userId =
                StrUtil.firstNonBlank(
                        node.get("userId").getString(),
                        node.get("staffId").getString(),
                        node.get("unionId").getString(),
                        findNested(node, "operator", "staffId"),
                        findNested(node, "operator", "userId"));
        if (isBlank(chatId)) {
            chatId = "dingtalk-card";
        }
        String text = "Card action: " + node.toJson();
        GatewayMessage message = new GatewayMessage(PlatformType.DINGTALK, chatId, userId, text);
        message.setChatType(
                conversationGroupFlags.containsKey(chatId)
                                && Boolean.TRUE.equals(conversationGroupFlags.get(chatId))
                        ? GatewayBehaviorConstants.CHAT_TYPE_GROUP
                        : GatewayBehaviorConstants.CHAT_TYPE_DM);
        message.setChatName(chatId);
        message.setUserName(StrUtil.blankToDefault(userId, "dingtalk-card"));
        message.setThreadId(
                StrUtil.blankToDefault(processKey, "card-callback-" + System.currentTimeMillis()));
        return message;
    }

    /**
     * 查找Nested。
     *
     * @param node 节点参数。
     * @param parentKey parent键标识或键值。
     * @param childKey child键标识或键值。
     * @return 返回Nested结果。
     */
    private String findNested(ONode node, String parentKey, String childKey) {
        ONode parent = node.get(parentKey);
        if (parent == null || parent.isNull()) {
            return null;
        }
        return parent.get(childKey).getString();
    }

    /**
     * 判断是否存在Any Send Options。
     *
     * @param options options 参数。
     * @return 如果Any Send Options满足条件则返回 true，否则返回 false。
     */
    private boolean hasAnySendOptions(
            SendRobotInteractiveCardRequest.SendRobotInteractiveCardRequestSendOptions options) {
        return options != null
                && (options.getAtAll() != null
                        || notBlank(options.getAtUserListJson())
                        || notBlank(options.getCardPropertyJson())
                        || notBlank(options.getReceiverListJson()));
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 执行JSON字符串相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回JSON String结果。
     */
    private String jsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return ((String) value).trim();
        }
        return ONode.serialize(value);
    }

    /**
     * 解析投递上下文。
     *
     * @param request 当前请求对象。
     * @return 返回解析后的投递上下文。
     */
    private DeliveryContext resolveDeliveryContext(DeliveryRequest request) throws Exception {
        DeliveryContext context = new DeliveryContext();
        context.chatId = request.getChatId();
        context.group = isGroupConversation(request);
        context.unionId =
                channelStateRepository.get(
                        PlatformType.DINGTALK, request.getChatId(), STATE_LAST_UNION_ID);
        if (isBlank(context.unionId)) {
            throw new IllegalStateException(
                    "DingTalk attachment send requires a recent inbound conversation context to resolve unionId");
        }
        return context;
    }

    /**
     * 解析Space标识。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回解析后的Space标识。
     */
    private String resolveSpaceId(DeliveryContext context) throws Exception {
        String cached =
                channelStateRepository.get(
                        PlatformType.DINGTALK, context.chatId, STATE_LAST_SPACE_ID);
        if (notBlank(cached)) {
            return cached;
        }
        GetSpaceHeaders headers = new GetSpaceHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        GetSpaceRequest request =
                new GetSpaceRequest()
                        .setOpenConversationId(context.chatId)
                        .setUnionId(context.unionId);
        GetSpaceResponse response =
                convFileClient.getSpaceWithOptions(
                        request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null
                || response.getBody() == null
                || response.getBody().getSpace() == null
                || isBlank(response.getBody().getSpace().getSpaceId())) {
            throw new IllegalStateException("DingTalk conversation space lookup failed");
        }
        String spaceId = response.getBody().getSpace().getSpaceId();
        channelStateRepository.put(
                PlatformType.DINGTALK, context.chatId, STATE_LAST_SPACE_ID, spaceId);
        return spaceId;
    }

    /**
     * 读取Upload Info。
     *
     * @param unionId union标识。
     * @param fileName 文件或目录路径参数。
     * @param size size 参数。
     * @param spaceId space标识。
     * @return 返回读取到的Upload Info。
     */
    private String getUploadInfo(String unionId, String fileName, int size, String spaceId)
            throws Exception {
        GetFileUploadInfoHeaders headers = new GetFileUploadInfoHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        GetFileUploadInfoRequest.GetFileUploadInfoRequestOption option =
                new GetFileUploadInfoRequest.GetFileUploadInfoRequestOption()
                        .setStorageDriver("DINGTALK")
                        .setPreCheckParam(
                                new GetFileUploadInfoRequest
                                                .GetFileUploadInfoRequestOptionPreCheckParam()
                                        .setName(fileName)
                                        .setSize(Long.valueOf(size)));
        GetFileUploadInfoRequest request =
                new GetFileUploadInfoRequest()
                        .setProtocol("HEADER_SIGNATURE")
                        .setUnionId(unionId)
                        .setOption(option);
        GetFileUploadInfoResponse response =
                storageClient.getFileUploadInfoWithOptions(
                        spaceId, request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null
                || response.getBody() == null
                || isBlank(response.getBody().getUploadKey())) {
            throw new IllegalStateException("DingTalk upload init failed");
        }
        DeliveryUploadState.current.set(response.getBody());
        return response.getBody().getUploadKey();
    }

    /**
     * 执行upload文件字节相关逻辑。
     *
     * @param uploadKey upload键标识或键值。
     * @param data 数据参数。
     * @param unionId union标识。
     * @param spaceId space标识。
     * @param fileName 文件或目录路径参数。
     */
    private void uploadFileBytes(
            String uploadKey, byte[] data, String unionId, String spaceId, String fileName) {
        com.aliyun.dingtalkstorage_2_0.models.GetFileUploadInfoResponseBody body =
                DeliveryUploadState.current.get();
        if (body == null
                || body.getHeaderSignatureInfo() == null
                || body.getHeaderSignatureInfo().getResourceUrls() == null
                || body.getHeaderSignatureInfo().getResourceUrls().isEmpty()) {
            throw new IllegalStateException("DingTalk upload info missing signed resource url");
        }
        String uploadUrl = body.getHeaderSignatureInfo().getResourceUrls().get(0);
        HttpRequest request =
                HttpRequest.put(uploadUrl).timeout(120000).setFollowRedirects(false).body(data);
        Map<String, String> headers = body.getHeaderSignatureInfo().getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
        }
        HttpResponse response = request.execute();
        try {
            if (response.getStatus() >= 300 && response.getStatus() < 400) {
                throw new IllegalStateException(
                        "DingTalk upload bytes blocked redirect: HTTP "
                                + response.getStatus()
                                + " -> "
                                + SecretRedactor.maskUrl(response.header("Location")));
            }
            if (response.getStatus() >= 400) {
                throw new IllegalStateException(
                        HutoolHttpErrorFormatter.failure("DingTalk upload bytes", response));
            }
        } finally {
            response.close();
        }
    }

    /**
     * 执行commitUploaded文件相关逻辑。
     *
     * @param uploadKey upload键标识或键值。
     * @param unionId union标识。
     * @param spaceId space标识。
     * @param fileName 文件或目录路径参数。
     * @param size size 参数。
     * @return 返回commit Uploaded文件结果。
     */
    private String commitUploadedFile(
            String uploadKey, String unionId, String spaceId, String fileName, int size)
            throws Exception {
        CommitFileHeaders headers = new CommitFileHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        CommitFileRequest request =
                new CommitFileRequest()
                        .setName(fileName)
                        .setUploadKey(uploadKey)
                        .setUnionId(unionId)
                        .setOption(
                                new CommitFileRequest.CommitFileRequestOption()
                                        .setSize(Long.valueOf(size))
                                        .setConflictStrategy("AUTO_RENAME"));
        CommitFileResponse response =
                storageClient.commitFileWithOptions(
                        spaceId, request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null
                || response.getBody() == null
                || response.getBody().getDentry() == null
                || isBlank(response.getBody().getDentry().getId())) {
            throw new IllegalStateException("DingTalk commit file failed");
        }
        return response.getBody().getDentry().getId();
    }

    /**
     * 发送对话文件。
     *
     * @param context 当前请求或运行上下文。
     * @param spaceId space标识。
     * @param dentryId dentry标识。
     */
    private void sendConversationFile(DeliveryContext context, String spaceId, String dentryId)
            throws Exception {
        SendHeaders headers = new SendHeaders();
        headers.setXAcsDingtalkAccessToken(accessToken);
        SendRequest request =
                new SendRequest()
                        .setOpenConversationId(context.chatId)
                        .setSpaceId(spaceId)
                        .setDentryId(dentryId)
                        .setUnionId(context.unionId);
        SendResponse response =
                convFileClient.sendWithOptions(
                        request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response == null
                || response.getBody() == null
                || response.getBody().getFile() == null
                || isBlank(response.getBody().getFile().getId())) {
            throw new IllegalStateException("DingTalk conversation file send failed");
        }
    }

    /**
     * 判断是否Blank。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Blank满足条件则返回 true，否则返回 false。
     */
    private boolean isBlank(String value) {
        return StrUtil.isBlank(value);
    }

    /**
     * 判断文本是否包含非空白内容。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回not Blank结果。
     */
    private boolean notBlank(String value) {
        return !isBlank(value);
    }

    /**
     * 执行受控响应正文相关逻辑。
     *
     * @param response 当前响应对象。
     * @param purpose purpose 参数。
     * @return 返回guarded响应Body结果。
     */
    private String guardedResponseBody(HttpResponse response, String purpose) {
        return HutoolHttpErrorFormatter.guardedBody(purpose, response);
    }


    /**
     * 解析Markdown标题。
     *
     * @param content 待处理内容。
     * @return 返回解析后的Markdown标题。
     */
    private String resolveMarkdownTitle(String content) {
        if (isBlank(content)) {
            return "solonclaw";
        }
        String[] lines = content.split("\\R");
        for (String line : lines) {
            String normalized = line.replaceFirst("^[#>*`\\-\\s]+", "").trim();
            if (!isBlank(normalized)) {
                return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
            }
        }
        return "solonclaw";
    }

    /** 承载投递上下文相关状态和辅助逻辑。 */
    private static class DeliveryContext {
        /** 记录投递上下文中的聊天标识。 */
        private String chatId;

        /** 记录投递上下文中的union标识。 */
        private String unionId;

        /** 是否启用群组。 */
        private boolean group;
    }

    /** 表示投递Upload数据，在服务、仓储和接口之间传递。 */
    private static class DeliveryUploadState {
        /** 当前的统一常量值。 */
        private static final ThreadLocal<
                        com.aliyun.dingtalkstorage_2_0.models.GetFileUploadInfoResponseBody>
                current =
                        new ThreadLocal<
                                com.aliyun.dingtalkstorage_2_0.models
                                        .GetFileUploadInfoResponseBody>();
    }
}
