package com.jimuqu.solon.claw.gateway.platform.feishu;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TerminalAnsiSanitizer;
import com.lark.oapi.Client;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.application.v6.model.GetApplicationReq;
import com.lark.oapi.service.application.v6.model.GetApplicationResp;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1;
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1Data;
import com.lark.oapi.service.im.v1.model.P2MessageReactionDeletedV1;
import com.lark.oapi.service.im.v1.model.P2MessageReactionDeletedV1Data;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;

/** FeishuChannelAdapter 实现。 */
public class FeishuChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** tokenURL的统一常量值。 */
    private static final String TOKEN_URL =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";

    /** SENDURL的统一常量值。 */
    private static final String SEND_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";

    /** 图片上传URL的统一常量值。 */
    private static final String IMAGE_UPLOAD_URL = "https://open.feishu.cn/open-apis/im/v1/images";

    /** 文件上传URL的统一常量值。 */
    private static final String FILE_UPLOAD_URL = "https://open.feishu.cn/open-apis/im/v1/files";

    /** 消息资源URL的统一常量值。 */
    private static final String MESSAGE_RESOURCE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages/%s/resources/%s?type=%s";

    /** 机器人INFOURL的统一常量值。 */
    private static final String BOT_INFO_URL = "https://open.feishu.cn/open-apis/bot/v3/info";

    /** 消息REACTIONURL的统一常量值。 */
    private static final String MESSAGE_REACTION_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages/%s/reactions";

    /** COMMENT回复TARGETPREFIX的统一常量值。 */
    private static final String COMMENT_REPLY_TARGET_PREFIX = "comment|";

    /** COMMENT回复URL的统一常量值。 */
    private static final String COMMENT_REPLY_URL =
            "https://open.feishu.cn/open-apis/drive/v1/files/%s/comments/%s/replies?file_type=%s";

    /** COMMENTADDURL的统一常量值。 */
    private static final String COMMENT_ADD_URL =
            "https://open.feishu.cn/open-apis/drive/v1/files/%s/new_comments";

    /** PROCESSINGREACTIONTYPING的统一常量值。 */
    private static final String PROCESSING_REACTION_TYPING = "Typing";

    /** PROCESSINGREACTIONFAILURE的统一常量值。 */
    private static final String PROCESSING_REACTION_FAILURE = "CrossMark";

    /** PROCESSINGREACTION缓存大小的统一常量值。 */
    private static final int PROCESSING_REACTION_CACHE_SIZE = 1024;

    /** 记录飞书渠道中的配置。 */
    private final AppConfig.ChannelConfig config;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录飞书渠道中的tenantAccesstoken。 */
    private volatile String tenantAccessToken;

    /** 记录飞书渠道中的tokenExpire时间。 */
    private volatile long tokenExpireAt;

    /** 记录飞书渠道中的wsClient。 */
    private volatile com.lark.oapi.ws.Client wsClient;

    /** 保存入站执行器执行组件，负责调度异步或定时任务。 */
    private ExecutorService inboundExecutor;

    /** 保存processingReactions映射，便于按键快速查询。 */
    private final Map<String, String> processingReactions =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, String>(64, 0.75f, true) {
                        /**
                         * 移除Eldest Entry。
                         *
                         * @param eldest eldest 参数。
                         * @return 返回Eldest Entry结果。
                         */
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > PROCESSING_REACTION_CACHE_SIZE;
                        }
                    });

    /**
     * 创建飞书渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public FeishuChannelAdapter(
            AppConfig.ChannelConfig config, AttachmentCacheService attachmentCacheService) {
        this(config, attachmentCacheService, null);
    }

    /**
     * 创建飞书渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public FeishuChannelAdapter(
            AppConfig.ChannelConfig config,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        super(PlatformType.FEISHU, config);
        this.config = config;
        this.attachmentCacheService = attachmentCacheService;
        this.securityPolicyService = securityPolicyService;
        setConnectionMode("websocket");
        setFeatures(
                "text", "attachments", "post-media", "group-mention", "card-action", "reactions");
        setSetupState(config != null && config.isEnabled() ? "configured" : "disabled");
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @return 返回connect结果。
     */
    @Override
    public boolean connect() {
        if (!isEnabled()) {
            setSetupState("disabled");
            setDetail("disabled");
            return false;
        }
        if (rejectWeakCredentials(
                "feishu_weak_credentials",
                credentialField("solonclaw.channels.feishu.appId", config.getAppId()),
                credentialField("solonclaw.channels.feishu.appSecret", config.getAppSecret()),
                credentialField("solonclaw.channels.feishu.botOpenId", config.getBotOpenId()),
                credentialField("solonclaw.channels.feishu.botUserId", config.getBotUserId()))) {
            return false;
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<String>();
        if (StrUtil.isBlank(config.getAppId())) {
            missing.add("solonclaw.channels.feishu.appId");
        }
        if (StrUtil.isBlank(config.getAppSecret())) {
            missing.add("solonclaw.channels.feishu.appSecret");
        }
        if (!missing.isEmpty()) {
            setConnected(false);
            setSetupState("missing_config");
            setMissingConfig(missing);
            setLastError("feishu_missing_credentials", "missing appId/appSecret");
            setDetail("missing appId/appSecret");
            log.warn("[FEISHU] Missing appId/appSecret");
            return false;
        }
        try {
            refreshTenantTokenIfNecessary();
            hydrateBotIdentity();
            inboundExecutor = Executors.newSingleThreadExecutor();
            EventDispatcher dispatcher =
                    EventDispatcher.newBuilder("", "")
                            .onP2MessageReceiveV1(
                                    new ImService.P2MessageReceiveV1Handler() {
                                        /**
                                         * 执行handle相关逻辑。
                                         *
                                         * @param event 事件参数。
                                         */
                                        @Override
                                        public void handle(P2MessageReceiveV1 event) {
                                            if (event == null
                                                    || event.getEvent() == null
                                                    || inboundExecutor == null) {
                                                return;
                                            }
                                            inboundExecutor.submit(
                                                    new Runnable() {
                                                        /** 执行异步任务主体。 */
                                                        @Override
                                                        public void run() {
                                                            try {
                                                                handleWebsocketEvent(
                                                                        event.getEvent());
                                                            } catch (Exception e) {
                                                                log.warn(
                                                                        "[FEISHU] websocket inbound dispatch failed: errorType={}, error={}",
                                                                        errorType(e),
                                                                        safeError(e));
                                                            }
                                                        }
                                                    });
                                        }
                                    })
                            .onP2MessageReactionCreatedV1(
                                    new ImService.P2MessageReactionCreatedV1Handler() {
                                        /**
                                         * 执行handle相关逻辑。
                                         *
                                         * @param event 事件参数。
                                         */
                                        @Override
                                        public void handle(P2MessageReactionCreatedV1 event)
                                                throws Exception {
                                            handleReactionCreatedEvent(
                                                    event == null ? null : event.getEvent());
                                        }
                                    })
                            .onP2MessageReactionDeletedV1(
                                    new ImService.P2MessageReactionDeletedV1Handler() {
                                        /**
                                         * 执行handle相关逻辑。
                                         *
                                         * @param event 事件参数。
                                         */
                                        @Override
                                        public void handle(P2MessageReactionDeletedV1 event)
                                                throws Exception {
                                            handleReactionDeletedEvent(
                                                    event == null ? null : event.getEvent());
                                        }
                                    })
                            .onP2CardActionTrigger(
                                    new P2CardActionTriggerHandler() {
                                        /**
                                         * 执行handle相关逻辑。
                                         *
                                         * @param event 事件参数。
                                         * @return 返回handle结果。
                                         */
                                        @Override
                                        public P2CardActionTriggerResponse handle(
                                                P2CardActionTrigger event) throws Exception {
                                            handleCardActionEvent(
                                                    event == null ? null : event.getEvent());
                                            return new P2CardActionTriggerResponse();
                                        }
                                    })
                            .onCustomizedEvent(
                                    "drive.notice.comment_add_v1",
                                    new CustomEventHandler() {
                                        /**
                                         * 执行handle相关逻辑。
                                         *
                                         * @param event 事件参数。
                                         */
                                        @Override
                                        public void handle(EventReq event) throws Exception {
                                            handleDriveCommentEvent(event);
                                        }
                                    })
                            .build();
            wsClient =
                    new com.lark.oapi.ws.Client.Builder(config.getAppId(), config.getAppSecret())
                            .eventHandler(dispatcher)
                            .build();
            wsClient.start();
            setConnected(true);
            setSetupState("connected");
            setMissingConfig(new String[0]);
            clearLastError();
            setDetail("websocket connected");
            return true;
        } catch (Exception e) {
            setConnected(false);
            setSetupState("error");
            setLastError("feishu_connect_failed", safeError(e));
            setDetail("connect failed: " + safeError(e));
            log.warn("[FEISHU] connect failed: errorType={}, error={}", errorType(e), safeError(e));
            return false;
        }
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public void disconnect() {
        shutdownWebsocketClient();
        if (inboundExecutor != null) {
            inboundExecutor.shutdownNow();
            inboundExecutor = null;
        }
        setConnected(false);
        setDetail("disconnected");
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param request 当前请求对象。
     */
    @Override
    public void send(DeliveryRequest request) {
        if (StrUtil.isBlank(request.getChatId())) {
            throw new IllegalArgumentException("Feishu chatId is required");
        }
        try {
            refreshTenantTokenIfNecessary();
            if (isCommentReplyTarget(request.getChatId())) {
                sendCommentReply(request.getChatId(), request.getText());
                return;
            }
            if (isApprovalCardRequest(request)) {
                sendDangerousApprovalCard(request);
                return;
            }
            if (StrUtil.isNotBlank(request.getText())) {
                sendText(request.getChatId(), request.getText());
            }
            List<MessageAttachment> attachments = request.getAttachments();
            if (attachments != null) {
                for (MessageAttachment attachment : attachments) {
                    sendAttachment(request.getChatId(), attachment);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Feishu send failed", e);
        }
    }

    /**
     * 执行WebSocket事件相关逻辑。
     *
     * @param event 事件参数。
     */
    public void handleWebsocketEvent(P2MessageReceiveV1Data event) {
        GatewayMessage message =
                toGatewayMessage(
                        event == null ? null : event.getMessage(),
                        event == null ? null : event.getSender());
        if (message != null && inboundMessageHandler() != null) {
            try {
                inboundMessageHandler().handle(message);
            } catch (Exception e) {
                throw new IllegalStateException("Feishu websocket handle failed", e);
            }
        }
    }

    /** 在飞书原消息上添加“处理中”表情回应。 */
    @Override
    public void onProcessingStart(GatewayMessage message) {
        String messageId = inboundMessageId(message);
        if (StrUtil.isBlank(messageId) || processingReactions.containsKey(messageId)) {
            return;
        }
        String reactionId = createProcessingReaction(messageId, PROCESSING_REACTION_TYPING);
        if (StrUtil.isNotBlank(reactionId)) {
            processingReactions.put(messageId, reactionId);
        }
    }

    /** 根据处理结果清理或切换飞书原消息上的表情回应。 */
    @Override
    public void onProcessingComplete(GatewayMessage message, ProcessingOutcome outcome) {
        String messageId = inboundMessageId(message);
        if (StrUtil.isBlank(messageId)) {
            return;
        }
        String reactionId = processingReactions.get(messageId);
        if (StrUtil.isNotBlank(reactionId)) {
            if (!deleteProcessingReaction(messageId, reactionId)) {
                return;
            }
            processingReactions.remove(messageId);
        }
        if (ProcessingOutcome.FAILURE.equals(outcome)) {
            createProcessingReaction(messageId, PROCESSING_REACTION_FAILURE);
        }
    }

    /** 返回渠道原始消息 ID，当前统一承载在 threadId。 */
    private String inboundMessageId(GatewayMessage message) {
        return message == null ? "" : StrUtil.nullToEmpty(message.getThreadId()).trim();
    }

    /**
     * 执行Reaction创建事件相关逻辑。
     *
     * @param event 事件参数。
     */
    private void handleReactionCreatedEvent(P2MessageReactionCreatedV1Data event) {
        if (event == null) {
            return;
        }
        GatewayMessage message =
                toReactionGatewayMessage(
                        event.getMessageId(),
                        event.getUserId(),
                        event.getReactionType() == null
                                ? ""
                                : event.getReactionType().getEmojiType(),
                        true);
        if (message != null && inboundMessageHandler() != null) {
            try {
                inboundMessageHandler().handle(message);
            } catch (Exception e) {
                throw new IllegalStateException("Feishu reaction handle failed", e);
            }
        }
    }

    /**
     * 执行Reaction删除事件相关逻辑。
     *
     * @param event 事件参数。
     */
    private void handleReactionDeletedEvent(P2MessageReactionDeletedV1Data event) {
        if (event == null) {
            return;
        }
        GatewayMessage message =
                toReactionGatewayMessage(
                        event.getMessageId(),
                        event.getUserId(),
                        event.getReactionType() == null
                                ? ""
                                : event.getReactionType().getEmojiType(),
                        false);
        if (message != null && inboundMessageHandler() != null) {
            try {
                inboundMessageHandler().handle(message);
            } catch (Exception e) {
                throw new IllegalStateException("Feishu reaction handle failed", e);
            }
        }
    }

    /**
     * 执行卡片Action事件相关逻辑。
     *
     * @param event 事件参数。
     */
    private void handleCardActionEvent(P2CardActionTriggerData event) {
        if (event == null || inboundMessageHandler() == null) {
            return;
        }
        GatewayMessage message = toCardActionGatewayMessage(event);
        if (message == null) {
            return;
        }
        try {
            inboundMessageHandler().handle(message);
        } catch (Exception e) {
            throw new IllegalStateException("Feishu card action handle failed", e);
        }
    }

    /**
     * 执行DriveComment事件相关逻辑。
     *
     * @param req req 参数。
     */
    private void handleDriveCommentEvent(final EventReq req) {
        if (!config.isCommentEnabled() || inboundMessageHandler() == null) {
            return;
        }
        if (inboundExecutor == null) {
            inboundExecutor = Executors.newSingleThreadExecutor();
        }
        inboundExecutor.submit(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        try {
                            GatewayMessage message = toCommentGatewayMessage(req);
                            if (message != null) {
                                inboundMessageHandler().handle(message);
                            }
                        } catch (Exception e) {
                            log.warn(
                                    "[FEISHU-COMMENT] dispatch failed: errorType={}, error={}",
                                    errorType(e),
                                    safeError(e));
                        }
                    }
                });
    }

    /**
     * 转换为Comment消息网关消息。
     *
     * @param req req 参数。
     * @return 返回转换后的Comment消息网关消息。
     */
    protected GatewayMessage toCommentGatewayMessage(EventReq req) {
        ONode root = ONode.ofJson(readEventBody(req));
        ONode event = root.get("event");
        if (event == null || event.isNull()) {
            event = root;
        }
        ONode notice = event.get("notice_meta");
        String noticeType = notice.get("notice_type").getString();
        if (StrUtil.isNotBlank(noticeType)
                && !"add_comment".equalsIgnoreCase(noticeType)
                && !"add_reply".equalsIgnoreCase(noticeType)) {
            return null;
        }
        String fileToken = notice.get("file_token").getString();
        String fileType = notice.get("file_type").getString();
        String commentId = event.get("comment_id").getString();
        String replyId = event.get("reply_id").getString();
        String userId =
                StrUtil.firstNonBlank(
                        notice.get("from_user_id").get("open_id").getString(),
                        notice.get("from_user_id").get("user_id").getString(),
                        event.get("operator_id").get("open_id").getString());
        if (StrUtil.isBlank(fileToken)
                || StrUtil.isBlank(fileType)
                || !allowCommentUser(userId, fileType, fileToken)) {
            return null;
        }
        String text =
                StrUtil.firstNonBlank(
                        extractCommentText(event.get("reply_content")),
                        extractCommentText(event.get("comment_content")),
                        event.get("text").getString(),
                        event.get("content").getString());
        if (StrUtil.isBlank(text)) {
            text =
                    "飞书文档评论事件：file_type="
                            + fileType
                            + " file_token="
                            + fileToken
                            + " comment_id="
                            + commentId;
        }
        boolean whole = StrUtil.isBlank(commentId);
        String chatId =
                COMMENT_REPLY_TARGET_PREFIX
                        + escapeTarget(fileType)
                        + "|"
                        + escapeTarget(fileToken)
                        + "|"
                        + escapeTarget(commentId)
                        + "|"
                        + escapeTarget(replyId)
                        + "|"
                        + String.valueOf(whole);
        GatewayMessage message = new GatewayMessage(PlatformType.FEISHU, chatId, userId, text);
        message.setChatType(GatewayBehaviorConstants.CHAT_TYPE_DM);
        message.setChatName("飞书文档评论");
        message.setUserName(userId);
        message.setThreadId(StrUtil.blankToDefault(replyId, commentId));
        message.setSourceKeyOverride(
                "FEISHU_COMMENT:"
                        + fileType
                        + ":"
                        + fileToken
                        + ":"
                        + StrUtil.blankToDefault(commentId, "whole"));
        return message;
    }

    /**
     * 读取事件Body。
     *
     * @param req req 参数。
     * @return 返回读取到的事件Body。
     */
    private String readEventBody(EventReq req) {
        if (req == null) {
            return "{}";
        }
        if (StrUtil.isNotBlank(req.getPlain())) {
            return req.getPlain();
        }
        byte[] body = req.getBody();
        return body == null ? "{}" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 提取Comment Text。
     *
     * @param content 待处理内容。
     * @return 返回Comment Text结果。
     */
    private String extractCommentText(ONode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (!content.isObject() && !content.isArray()) {
            return content.getString();
        }
        StringBuilder buffer = new StringBuilder();
        collectCommentText(content, buffer);
        return buffer.toString().trim();
    }

    /**
     * 收集Comment Text。
     *
     * @param node 节点参数。
     * @param buffer buffer 参数。
     */
    private void collectCommentText(ONode node, StringBuilder buffer) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectCommentText(node.get(i), buffer);
            }
            return;
        }
        if (!node.isObject()) {
            appendText(buffer, node.getString());
            return;
        }
        String type = StrUtil.firstNonBlank(node.get("type").getString(), node.get("tag").getString());
        if ("text_run".equalsIgnoreCase(type)) {
            appendText(buffer, node.get("text_run").get("text").getString());
        } else if ("text".equalsIgnoreCase(type)) {
            appendText(
                    buffer,
                    StrUtil.firstNonBlank(node.get("text").getString(), node.get("content").getString()));
        } else if ("docs_link".equalsIgnoreCase(type) || "link".equalsIgnoreCase(type)) {
            appendText(
                    buffer,
                    StrUtil.firstNonBlank(
                            node.get("docs_link").get("url").getString(),
                            node.get("link").get("url").getString()));
        }
        Map<?, ?> values = ONode.deserialize(node.toJson(), LinkedHashMap.class);
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("type".equals(key)
                    || "tag".equals(key)
                    || "text_run".equals(key)
                    || "docs_link".equals(key)
                    || "link".equals(key)) {
                continue;
            }
            collectCommentText(node.get(key), buffer);
        }
    }

    /**
     * 判断是否允许Comment用户。
     *
     * @param userId 用户标识。
     * @param fileType 文件或目录路径参数。
     * @param fileToken 文件或目录路径参数。
     * @return 如果Comment用户满足条件则返回 true，否则返回 false。
     */
    private boolean allowCommentUser(String userId, String fileType, String fileToken) {
        if (!config.isCommentEnabled()) {
            return false;
        }
        if (config.isAllowAllUsers() || contains(config.getAllowedUsers(), userId)) {
            return true;
        }
        Map<String, List<String>> pairings = loadCommentPairings();
        if (pairings.isEmpty()
                && (config.getAllowedUsers() == null || config.getAllowedUsers().isEmpty())) {
            return true;
        }
        String key = fileType + ":" + fileToken;
        return contains(pairings.get(key), userId) || contains(pairings.get("*"), userId);
    }

    /**
     * 加载Comment Pairings。
     *
     * @return 返回Comment Pairings结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> loadCommentPairings() {
        if (StrUtil.isBlank(config.getCommentPairingFile())) {
            return new LinkedHashMap<String, List<String>>();
        }
        try {
            File file = new File(config.getCommentPairingFile());
            if (!file.isFile()) {
                return new LinkedHashMap<String, List<String>>();
            }
            Object parsed =
                    ONode.deserialize(
                            cn.hutool.core.io.FileUtil.readUtf8String(file), Object.class);
            if (parsed instanceof Map) {
                Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                    List<String> values = new ArrayList<String>();
                    Object raw = entry.getValue();
                    if (raw instanceof List) {
                        for (Object item : (List<?>) raw) {
                            if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                                values.add(String.valueOf(item).trim());
                            }
                        }
                    }
                    result.put(String.valueOf(entry.getKey()), values);
                }
                return result;
            }
        } catch (Exception e) {
            log.debug(
                    "[FEISHU-COMMENT] pairing file load failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
        return new LinkedHashMap<String, List<String>>();
    }

    /**
     * 转义Target。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回escape Target结果。
     */
    private String escapeTarget(String value) {
        return StrUtil.nullToEmpty(value).replace("|", "%7C");
    }

    /**
     * 执行unescapeTarget相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回unescape Target结果。
     */
    private String unescapeTarget(String value) {
        return StrUtil.nullToEmpty(value).replace("%7C", "|");
    }

    /**
     * 转换为消息网关消息。
     *
     * @param messageNode 消息节点参数。
     * @param sender sender 参数。
     * @return 返回转换后的消息网关消息。
     */
    private GatewayMessage toGatewayMessage(
            EventMessage messageNode, com.lark.oapi.service.im.v1.model.EventSender sender) {
        if (messageNode == null) {
            return null;
        }
        String messageType = messageNode.getMessageType();
        String rawContent = messageNode.getContent();
        ONode content = StrUtil.isBlank(rawContent) ? new ONode() : ONode.ofJson(rawContent);
        String chatId = messageNode.getChatId();
        String chatType = "group".equalsIgnoreCase(messageNode.getChatType()) ? "group" : "dm";
        UserId senderId = sender == null ? null : sender.getSenderId();
        String userId = senderId == null ? null : senderId.getOpenId();
        if (StrUtil.isBlank(userId) && senderId != null) {
            userId = senderId.getUserId();
        }
        if (!allowInbound(chatType, chatId, userId, messageNode.getMentions(), rawContent)) {
            return null;
        }
        String text = extractInboundText(messageType, content);
        List<MessageAttachment> attachments =
                extractInboundAttachments(messageType, content, messageNode.getMessageId());
        if (StrUtil.isBlank(text) && attachments.isEmpty()) {
            return null;
        }
        GatewayMessage message = new GatewayMessage(PlatformType.FEISHU, chatId, userId, text);
        message.setChatType(chatType);
        message.setChatName(chatId);
        message.setUserName(userId);
        message.setThreadId(messageNode.getMessageId());
        message.setAttachments(attachments);
        return message;
    }

    /**
     * 提取入站Text。
     *
     * @param messageType 消息类型参数。
     * @param content 待处理内容。
     * @return 返回入站Text结果。
     */
    private String extractInboundText(String messageType, ONode content) {
        if ("text".equalsIgnoreCase(messageType)) {
            return content.get("text").getString();
        }
        if ("post".equalsIgnoreCase(messageType)) {
            return parsePostContent(content).textContent;
        }
        return "";
    }

    /**
     * 提取入站附件。
     *
     * @param messageType 消息类型参数。
     * @param content 待处理内容。
     * @param messageId 消息标识。
     * @return 返回入站附件结果。
     */
    private List<MessageAttachment> extractInboundAttachments(
            String messageType, ONode content, String messageId) {
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        if ("image".equalsIgnoreCase(messageType)) {
            MessageAttachment attachment =
                    downloadMessageResource(
                            "image", messageId, content.get("image_key").getString(), "image.jpg");
            if (attachment != null) {
                attachments.add(attachment);
            }
        } else if ("file".equalsIgnoreCase(messageType)) {
            MessageAttachment attachment =
                    downloadMessageResource(
                            "file",
                            messageId,
                            content.get("file_key").getString(),
                            content.get("file_name").getString());
            if (attachment != null) {
                attachments.add(attachment);
            }
        } else if ("audio".equalsIgnoreCase(messageType)) {
            MessageAttachment attachment =
                    downloadMessageResource(
                            "audio",
                            messageId,
                            content.get("file_key").getString(),
                            content.get("file_name").getString());
            if (attachment != null) {
                attachment.setKind("voice");
                attachments.add(attachment);
            }
        } else if ("media".equalsIgnoreCase(messageType)) {
            MessageAttachment attachment =
                    downloadMessageResource(
                            "media",
                            messageId,
                            content.get("file_key").getString(),
                            content.get("file_name").getString());
            if (attachment != null) {
                attachment.setKind("video");
                attachments.add(attachment);
            }
        } else if ("post".equalsIgnoreCase(messageType)) {
            PostParseResult post = parsePostContent(content);
            for (String imageKey : post.imageKeys) {
                MessageAttachment attachment =
                        downloadMessageResource("image", messageId, imageKey, "image.jpg");
                if (attachment != null) {
                    attachments.add(attachment);
                }
            }
            for (PostMediaRef ref : post.mediaRefs) {
                MessageAttachment attachment =
                        downloadMessageResource(
                                ref.resourceType, messageId, ref.fileKey, ref.fileName);
                if (attachment != null) {
                    attachment.setKind(
                            AttachmentCacheService.normalizeKind(
                                    ref.resourceType, ref.fileName, attachment.getMimeType()));
                    attachments.add(attachment);
                }
            }
        }
        return attachments;
    }

    /**
     * 判断是否允许入站。
     *
     * @param chatType 聊天类型参数。
     * @param chatId 聊天标识。
     * @param userId 用户标识。
     * @param mentions mentions 参数。
     * @param rawContent 原始Content参数。
     * @return 如果入站满足条件则返回 true，否则返回 false。
     */
    private boolean allowInbound(
            String chatType,
            String chatId,
            String userId,
            MentionEvent[] mentions,
            String rawContent) {
        if ("group".equalsIgnoreCase(chatType)) {
            String policy =
                    StrUtil.blankToDefault(
                                    config.getGroupPolicy(),
                                    GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST)
                            .toLowerCase();
            if (GatewayBehaviorConstants.GROUP_POLICY_DISABLED.equals(policy)) {
                return false;
            }
            if (GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST.equals(policy)
                    && !contains(config.getGroupAllowedUsers(), chatId)
                    && !contains(config.getAllowedUsers(), userId)) {
                return false;
            }
            discoverBotName(mentions);
            return isBotMentioned(mentions, rawContent);
        }
        String dmPolicy =
                StrUtil.blankToDefault(
                                config.getDmPolicy(), GatewayBehaviorConstants.DM_POLICY_OPEN)
                        .toLowerCase();
        if (GatewayBehaviorConstants.DM_POLICY_DISABLED.equals(dmPolicy)) {
            return false;
        }
        if (GatewayBehaviorConstants.DM_POLICY_ALLOWLIST.equals(dmPolicy)) {
            return contains(config.getAllowedUsers(), userId);
        }
        return true;
    }

    /**
     * 执行contains相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param target target 参数。
     * @return 返回contains结果。
     */
    private boolean contains(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        for (String value : values) {
            String normalized = StrUtil.nullToEmpty(value).trim();
            if ("*".equals(normalized) || target.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行discover机器人名称相关逻辑。
     *
     * @param mentions mentions 参数。
     */
    private void discoverBotName(MentionEvent[] mentions) {
        if (StrUtil.isNotBlank(config.getBotName()) || mentions == null) {
            return;
        }
        for (MentionEvent mention : mentions) {
            if (mention != null && StrUtil.isNotBlank(mention.getName())) {
                config.setBotName(mention.getName().trim());
                return;
            }
        }
    }

    /**
     * 判断是否机器人Mentioned。
     *
     * @param mentions mentions 参数。
     * @param rawContent 原始Content参数。
     * @return 如果机器人Mentioned满足条件则返回 true，否则返回 false。
     */
    private boolean isBotMentioned(MentionEvent[] mentions, String rawContent) {
        if (mentions == null || mentions.length == 0) {
            return false;
        }
        for (MentionEvent mention : mentions) {
            if (mention == null) {
                continue;
            }
            UserId id = mention.getId();
            if (id != null) {
                if (StrUtil.isNotBlank(config.getBotOpenId())
                        && config.getBotOpenId().equalsIgnoreCase(id.getOpenId())) {
                    return true;
                }
                if (StrUtil.isNotBlank(config.getBotUserId())
                        && config.getBotUserId().equalsIgnoreCase(id.getUserId())) {
                    return true;
                }
            }
            if (StrUtil.isNotBlank(config.getBotName())
                    && config.getBotName().equalsIgnoreCase(mention.getName())) {
                return true;
            }
        }
        return mentions.length > 0 && StrUtil.nullToEmpty(rawContent).contains("@_user_");
    }

    /**
     * 解析Post Content。
     *
     * @param content 待处理内容。
     * @return 返回解析后的Post Content。
     */
    private PostParseResult parsePostContent(ONode content) {
        PostParseResult result = new PostParseResult();
        ONode locale = content.get("zh_cn");
        if (locale == null || locale.isNull()) {
            locale = content.get("en_us");
        }
        if (locale == null || locale.isNull()) {
            locale = content;
        }
        collectPostNode(locale, result);
        result.textContent = result.text.toString().trim();
        return result;
    }

    /**
     * 收集Post Node。
     *
     * @param node 节点参数。
     * @param result 结果响应或执行结果。
     */
    private void collectPostNode(ONode node, PostParseResult result) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectPostNode(node.get(i), result);
            }
            return;
        }
        if (!node.isObject()) {
            String text = node.getString();
            if (StrUtil.isNotBlank(text)) {
                appendText(result.text, text.trim());
            }
            return;
        }

        String tag = node.get("tag").getString();
        if ("text".equalsIgnoreCase(tag)) {
            appendText(result.text, node.get("text").getString());
        } else if ("a".equalsIgnoreCase(tag)) {
            appendText(result.text, node.get("text").getString());
        } else if ("at".equalsIgnoreCase(tag)) {
            appendText(
                    result.text,
                    StrUtil.blankToDefault(
                            node.get("user_name").getString(), node.get("name").getString()));
        } else if ("img".equalsIgnoreCase(tag)) {
            String imageKey = node.get("image_key").getString();
            if (StrUtil.isNotBlank(imageKey)) {
                result.imageKeys.add(imageKey);
            }
        } else if ("media".equalsIgnoreCase(tag)) {
            String fileKey = node.get("file_key").getString();
            if (StrUtil.isNotBlank(fileKey)) {
                result.mediaRefs.add(
                        new PostMediaRef(
                                fileKey,
                                StrUtil.blankToDefault(
                                        node.get("file_name").getString(), "media.mp4"),
                                "media"));
            }
        } else if ("file".equalsIgnoreCase(tag)) {
            String fileKey = node.get("file_key").getString();
            if (StrUtil.isNotBlank(fileKey)) {
                result.mediaRefs.add(
                        new PostMediaRef(
                                fileKey,
                                StrUtil.blankToDefault(
                                        node.get("file_name").getString(), "attachment.bin"),
                                "file"));
            }
        }

        Map<?, ?> values = ONode.deserialize(node.toJson(), LinkedHashMap.class);
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("tag".equals(key)
                    || "text".equals(key)
                    || "file_key".equals(key)
                    || "file_name".equals(key)
                    || "image_key".equals(key)
                    || "user_name".equals(key)
                    || "name".equals(key)) {
                continue;
            }
            collectPostNode(node.get(key), result);
        }
    }

    /**
     * 追加Text。
     *
     * @param buffer buffer 参数。
     * @param text 待处理文本。
     */
    private void appendText(StringBuilder buffer, String text) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(text.trim());
    }

    /**
     * 转换为Card Action消息网关消息。
     *
     * @param event 事件参数。
     * @return 返回转换后的Card Action消息网关消息。
     */
    private GatewayMessage toCardActionGatewayMessage(P2CardActionTriggerData event) {
        if (event == null || event.getContext() == null) {
            return null;
        }
        CallBackOperator operator = event.getOperator();
        String userId =
                operator == null
                        ? null
                        : StrUtil.blankToDefault(operator.getOpenId(), operator.getUserId());
        String chatId = event.getContext().getOpenChatId();
        String messageId = event.getContext().getOpenMessageId();
        Object actionValue =
                event.getAction() == null
                        ? new LinkedHashMap<String, Object>()
                        : event.getAction().getValue();
        String payload = ONode.serialize(actionValue);
        String command = DangerousCommandApprovalService.commandFromCardActionPayload(actionValue);
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.FEISHU,
                        chatId,
                        userId,
                        StrUtil.blankToDefault(command, "Card action: " + payload));
        message.setChatType(GatewayBehaviorConstants.CHAT_TYPE_DM);
        message.setChatName(chatId);
        message.setUserName(userId);
        message.setThreadId(messageId);
        return message;
    }

    /**
     * 转换为Reaction消息网关消息。
     *
     * @param messageId 消息标识。
     * @param userId 用户标识。
     * @param emoji emoji 参数。
     * @param created created 参数。
     * @return 返回转换后的Reaction消息网关消息。
     */
    private GatewayMessage toReactionGatewayMessage(
            String messageId, UserId userId, String emoji, boolean created) {
        if (StrUtil.isBlank(messageId)) {
            return null;
        }
        ONode meta = fetchMessageMeta(messageId);
        ONode item = meta.get("data").get("items").get(0);
        if (item == null || item.isNull()) {
            return null;
        }
        String chatId = item.get("chat_id").getString();
        String chatType =
                "group".equalsIgnoreCase(item.get("chat_type").getString()) ? "group" : "dm";
        String actor =
                userId == null
                        ? null
                        : StrUtil.blankToDefault(userId.getOpenId(), userId.getUserId());
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.FEISHU,
                        chatId,
                        actor,
                        (created ? "Reaction added: " : "Reaction removed: ")
                                + emoji
                                + " on "
                                + messageId);
        message.setChatType(chatType);
        message.setChatName(chatId);
        message.setUserName(actor);
        message.setThreadId(messageId);
        return message;
    }

    /**
     * 拉取消息Meta。
     *
     * @param messageId 消息标识。
     * @return 返回fetch消息Meta结果。
     */
    private ONode fetchMessageMeta(String messageId) {
        refreshTenantTokenIfNecessary();
        String url = "https://open.feishu.cn/open-apis/im/v1/messages/" + messageId;
        assertSafeUrl(url, "Feishu message lookup URL");
        HttpResponse httpResponse =
                HttpRequest.get(url)
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .timeout(15000)
                        .setFollowRedirects(false)
                        .execute();
        String response;
        try {
            response = guardedResponseBody(httpResponse, "Feishu message lookup");
        } finally {
            httpResponse.close();
        }
        return ensureOk(response, "Feishu message lookup failed");
    }

    /** 执行hydrate机器人身份相关逻辑。 */
    protected void hydrateBotIdentity() {
        if (StrUtil.isNotBlank(config.getBotOpenId())
                && StrUtil.isNotBlank(config.getBotUserId())
                && StrUtil.isNotBlank(config.getBotName())) {
            return;
        }
        try {
            Map<String, String> applicationInfo = fetchApplicationInfo();
            if (applicationInfo != null && StrUtil.isBlank(config.getBotName())) {
                String appName = applicationInfo.get("app_name");
                if (StrUtil.isNotBlank(appName)) {
                    config.setBotName(appName.trim());
                }
            }
        } catch (Exception e) {
            log.debug(
                    "[FEISHU] application info discovery failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
        try {
            Map<String, String> botInfo = fetchBotInfo();
            if (botInfo != null) {
                if (StrUtil.isBlank(config.getBotName())
                        && StrUtil.isNotBlank(botInfo.get("bot_name"))) {
                    config.setBotName(botInfo.get("bot_name").trim());
                }
                if (StrUtil.isBlank(config.getBotOpenId())
                        && StrUtil.isNotBlank(botInfo.get("bot_open_id"))) {
                    config.setBotOpenId(botInfo.get("bot_open_id").trim());
                }
                if (StrUtil.isBlank(config.getBotUserId())
                        && StrUtil.isNotBlank(botInfo.get("bot_user_id"))) {
                    config.setBotUserId(botInfo.get("bot_user_id").trim());
                }
            }
        } catch (Exception e) {
            log.debug(
                    "[FEISHU] bot info discovery failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 拉取应用Info。
     *
     * @return 返回fetch Application Info结果。
     */
    protected Map<String, String> fetchApplicationInfo() throws Exception {
        Client client = Client.newBuilder(config.getAppId(), config.getAppSecret()).build();
        GetApplicationReq request =
                GetApplicationReq.newBuilder().appId(config.getAppId()).lang("en_us").build();
        GetApplicationResp response = client.application().application().get(request);
        if (response == null
                || !response.success()
                || response.getData() == null
                || response.getData().getApp() == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("app_name", response.getData().getApp().getAppName());
        return result;
    }

    /**
     * 拉取机器人Info。
     *
     * @return 返回fetch机器人Info结果。
     */
    protected Map<String, String> fetchBotInfo() {
        refreshTenantTokenIfNecessary();
        assertSafeUrl(BOT_INFO_URL, "Feishu bot info URL");
        HttpResponse httpResponse =
                HttpRequest.get(BOT_INFO_URL)
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .timeout(15000)
                        .setFollowRedirects(false)
                        .execute();
        String response;
        try {
            response = guardedResponseBody(httpResponse, "Feishu bot info");
        } finally {
            httpResponse.close();
        }
        ONode node = ONode.ofJson(response);
        if (node.get("code").getInt(-1) != 0) {
            return null;
        }
        ONode bot = node.get("bot");
        if (bot == null || bot.isNull()) {
            bot = node.get("data").get("bot");
        }
        if (bot == null || bot.isNull()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put(
                "bot_name",
                StrUtil.firstNonBlank(
                        bot.get("bot_name").getString(),
                        bot.get("botName").getString(),
                        bot.get("app_name").getString()));
        result.put(
                "bot_open_id",
                StrUtil.firstNonBlank(bot.get("open_id").getString(), bot.get("openId").getString()));
        result.put(
                "bot_user_id",
                StrUtil.firstNonBlank(bot.get("user_id").getString(), bot.get("userId").getString()));
        return result;
    }

    /**
     * 执行download消息资源相关逻辑。
     *
     * @param resourceType 资源类型参数。
     * @param messageId 消息标识。
     * @param fileKey 文件或目录路径参数。
     * @param fallbackName 兜底名称参数。
     * @return 返回download消息Resource结果。
     */
    private MessageAttachment downloadMessageResource(
            String resourceType, String messageId, String fileKey, String fallbackName) {
        if (StrUtil.isBlank(messageId) || StrUtil.isBlank(fileKey)) {
            return null;
        }
        refreshTenantTokenIfNecessary();
        String url = String.format(MESSAGE_RESOURCE_URL, messageId, fileKey, resourceType);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + tenantAccessToken);
        BoundedAttachmentIO.HutoolDownloadResult result =
                BoundedAttachmentIO.downloadHutoolResult(
                        url,
                        30000,
                        BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                        securityPolicyService,
                        headers);
        String fileName = fallbackName;
        if (StrUtil.isBlank(fileName)) {
            fileName = fileKey;
        }
        String mimeType =
                AttachmentCacheService.normalizeMimeType(result.getContentType(), fileName);
        return attachmentCacheService.cacheBytes(
                PlatformType.FEISHU,
                AttachmentCacheService.normalizeKind(resourceType, fileName, mimeType),
                fileName,
                mimeType,
                false,
                null,
                result.getData());
    }

    /**
     * 发送Text。
     *
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     */
    private void sendText(String chatId, String text) {
        for (String chunk : splitOutboundText(text, 5000)) {
            sendTextChunk(chatId, chunk);
        }
    }

    /**
     * 发送Text Chunk。
     *
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     */
    private void sendTextChunk(String chatId, String text) {
        String content = ONode.serialize(new FeishuTextMessage(text));
        String body =
                new ONode()
                        .set("receive_id", chatId)
                        .set("msg_type", "text")
                        .set("content", content)
                        .toJson();
        ensureOk(postJson(SEND_URL, body), "Feishu text send failed");
        log.info("[FEISHU:{}] {}", chatId, text);
    }

    /**
     * 拆分出站Text。
     *
     * @param text 待处理文本。
     * @param maxChars maxChars 参数。
     * @return 返回出站Text结果。
     */
    protected List<String> splitOutboundText(String text, int maxChars) {
        List<String> chunks = new ArrayList<String>();
        String remaining = StrUtil.nullToEmpty(text);
        int safeMax = Math.max(500, maxChars);
        while (remaining.length() > safeMax) {
            int split = findFenceAwareSplit(remaining, safeMax);
            String head = remaining.substring(0, split).trim();
            if (StrUtil.isNotBlank(head)) {
                chunks.add(closeFenceIfNeeded(head));
            }
            remaining = reopenFenceIfNeeded(head) + remaining.substring(split).trim();
        }
        if (StrUtil.isNotBlank(remaining)) {
            chunks.add(closeFenceIfNeeded(remaining));
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }

    /**
     * 查找Fence Aware Split。
     *
     * @param text 待处理文本。
     * @param maxChars maxChars 参数。
     * @return 返回Fence Aware Split结果。
     */
    private int findFenceAwareSplit(String text, int maxChars) {
        int split = text.lastIndexOf("\n\n", maxChars);
        if (split < maxChars / 2) {
            split = text.lastIndexOf('\n', maxChars);
        }
        if (split < maxChars / 2) {
            split = maxChars;
        }
        return Math.max(1, split);
    }

    /**
     * 关闭Fence If Needed。
     *
     * @param chunk 分片参数。
     * @return 返回Fence If Needed结果。
     */
    private String closeFenceIfNeeded(String chunk) {
        return hasUnclosedFence(chunk) ? chunk + "\n```" : chunk;
    }

    /**
     * 重新打开FenceIfNeeded。
     *
     * @param previousChunk previous分片参数。
     * @return 返回reopen Fence If Needed结果。
     */
    private String reopenFenceIfNeeded(String previousChunk) {
        return hasUnclosedFence(previousChunk) ? "```\n" : "";
    }

    /**
     * 判断是否存在Unclosed Fence。
     *
     * @param text 待处理文本。
     * @return 如果Unclosed Fence满足条件则返回 true，否则返回 false。
     */
    private boolean hasUnclosedFence(String text) {
        boolean open = false;
        String[] lines = StrUtil.nullToEmpty(text).split("\\R", -1);
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                open = !open;
            }
        }
        return open;
    }

    /**
     * 判断是否Comment Reply Target。
     *
     * @param chatId 聊天标识。
     * @return 如果Comment Reply Target满足条件则返回 true，否则返回 false。
     */
    private boolean isCommentReplyTarget(String chatId) {
        return StrUtil.startWith(chatId, COMMENT_REPLY_TARGET_PREFIX);
    }

    /**
     * 发送Comment Reply。
     *
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     */
    private void sendCommentReply(String chatId, String text) {
        String[] parts = chatId.split("\\|", -1);
        if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid Feishu comment target");
        }
        String fileType = unescapeTarget(parts[1]);
        String fileToken = unescapeTarget(parts[2]);
        String commentId = unescapeTarget(parts[3]);
        boolean whole = Boolean.parseBoolean(parts[5]) || StrUtil.isBlank(commentId);
        for (String chunk : splitOutboundText(StrUtil.blankToDefault(text, "收到。"), 1800)) {
            ONode body = buildCommentBody(chunk, whole, fileType);
            String url =
                    whole
                            ? String.format(COMMENT_ADD_URL, fileToken)
                            : String.format(COMMENT_REPLY_URL, fileToken, commentId, fileType);
            ONode response = ONode.ofJson(postJson(url, body.toJson()));
            int code = response.get("code").getInt(0);
            if (!whole && code == 1069302) {
                ensureOk(
                        postJson(
                                String.format(COMMENT_ADD_URL, fileToken),
                                buildCommentBody(chunk, true, fileType).toJson()),
                        "Feishu whole comment fallback failed");
            } else if (code != 0) {
                throw new IllegalStateException(
                        "Feishu comment reply failed: "
                                + safePlatformMessage(response.get("msg").getString()));
            }
        }
    }

    /**
     * 构建Comment Body。
     *
     * @param text 待处理文本。
     * @param whole whole 参数。
     * @param fileType 文件或目录路径参数。
     * @return 返回创建好的Comment Body。
     */
    private ONode buildCommentBody(String text, boolean whole, String fileType) {
        String sanitized =
                StrUtil.nullToEmpty(text)
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
        if (whole) {
            return new ONode()
                    .set("file_type", fileType)
                    .getOrNew("reply_elements")
                    .asArray()
                    .add(new ONode().set("type", "text").set("text", sanitized).toData())
                    .parent();
        }
        return new ONode()
                .getOrNew("content")
                .getOrNew("elements")
                .asArray()
                .add(
                        new ONode()
                                .set("type", "text_run")
                                .getOrNew("text_run")
                                .set("text", sanitized)
                                .parent()
                                .toData())
                .parent()
                .parent();
    }

    /**
     * 发送附件。
     *
     * @param chatId 聊天标识。
     * @param attachment 附件参数。
     */
    private void sendAttachment(String chatId, MessageAttachment attachment) {
        File file = new File(attachment.getLocalPath());
        if (!file.isFile()) {
            throw new IllegalStateException(
                    MessageAttachmentSupport.fileNotFoundMessage("Feishu", attachment));
        }

        String kind =
                AttachmentCacheService.normalizeKind(
                        attachment.getKind(),
                        attachment.getOriginalName(),
                        attachment.getMimeType());
        if ("image".equals(kind)) {
            String imageKey = uploadImage(file);
            String payload =
                    new ONode()
                            .set("receive_id", chatId)
                            .set("msg_type", "image")
                            .set("content", new ONode().set("image_key", imageKey).toJson())
                            .toJson();
            ensureOk(postJson(SEND_URL, payload), "Feishu image send failed");
            return;
        }

        UploadRouting routing = resolveFileRouting(attachment);
        String fileKey = uploadFile(file, routing.uploadType);
        String payload =
                new ONode()
                        .set("receive_id", chatId)
                        .set("msg_type", routing.messageType)
                        .set("content", new ONode().set("file_key", fileKey).toJson())
                        .toJson();
        ensureOk(postJson(SEND_URL, payload), "Feishu file send failed");
    }

    /**
     * 判断是否审批Card请求。
     *
     * @param request 当前请求对象。
     * @return 如果审批Card请求满足条件则返回 true，否则返回 false。
     */
    private boolean isApprovalCardRequest(DeliveryRequest request) {
        return DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD.equalsIgnoreCase(
                stringValue(
                        request.getChannelExtras() == null
                                ? null
                                : request.getChannelExtras().get("mode")));
    }

    /**
     * 发送Dangerous审批Card。
     *
     * @param request 当前请求对象。
     */
    private void sendDangerousApprovalCard(DeliveryRequest request) {
        ONode card = buildDangerousApprovalCard(request);

        String body =
                new ONode()
                        .set("receive_id", request.getChatId())
                        .set("msg_type", "interactive")
                        .set("content", card.toJson())
                        .toJson();
        ensureOk(postJson(SEND_URL, body), "Feishu approval card send failed");
    }

    /**
     * 构建Dangerous审批Card。
     *
     * @param request 当前请求对象。
     * @return 返回创建好的Dangerous审批Card。
     */
    protected ONode buildDangerousApprovalCard(DeliveryRequest request) {
        Map<String, Object> extras =
                request.getChannelExtras() == null
                        ? new LinkedHashMap<String, Object>()
                        : request.getChannelExtras();
        String command = approvalCardText(extras.get("approvalCommand"), 3000);
        String description = approvalCardText(extras.get("approvalDescription"), 1000);
        String approvalId = approvalCardSelector(extras.get("approvalId"));
        String preview = command;
        if (preview.length() > 3000) {
            preview = preview.substring(0, 3000) + "...";
        }

        List<Object> actions = new ArrayList<Object>();
        actions.add(
                cardButton(
                        "✅ 允许一次",
                        DangerousCommandApprovalService.CARD_ACTION_APPROVE,
                        approvalId,
                        "once",
                        "primary"));
        actions.add(
                cardButton(
                        "✅ 本会话允许",
                        DangerousCommandApprovalService.CARD_ACTION_APPROVE,
                        approvalId,
                        "session",
                        "default"));
        if (approvalCardAllowAlways(extras)) {
            actions.add(
                    cardButton(
                            "✅ 始终允许",
                            DangerousCommandApprovalService.CARD_ACTION_APPROVE,
                            approvalId,
                            "always",
                            "default"));
        }
        actions.add(
                cardButton(
                        "❌ 拒绝",
                        DangerousCommandApprovalService.CARD_ACTION_DENY,
                        approvalId,
                        "deny",
                        "danger"));

        List<Object> elements = new ArrayList<Object>();
        elements.add(
                new ONode()
                        .set("tag", "markdown")
                        .set(
                                "content",
                                "```\n"
                                        + preview
                                        + "\n```\n**原因：** "
                                        + StrUtil.blankToDefault(description, "危险命令"))
                        .toData());
        elements.add(new ONode().set("tag", "action").set("actions", actions).toData());

        ONode card = new ONode();
        card.getOrNew("config").set("wide_screen_mode", true);
        ONode header = card.getOrNew("header");
        header.getOrNew("title").set("content", "⚠️ 危险命令审批").set("tag", "plain_text");
        header.set("template", "orange");
        card.set("elements", elements);
        return card;
    }

    /**
     * 执行卡片Button相关逻辑。
     *
     * @param label label 参数。
     * @param action 操作参数。
     * @param approvalId 审批标识。
     * @param scope scope 参数。
     * @param type 类型参数。
     * @return 返回card Button结果。
     */
    private Object cardButton(
            String label, String action, String approvalId, String scope, String type) {
        ONode root = new ONode();
        root.set("tag", "button");
        root.getOrNew("text").set("tag", "plain_text").set("content", label);
        root.set("type", type);
        root.getOrNew("value")
                .set(DangerousCommandApprovalService.CARD_ACTION_KEY, action)
                .set(DangerousCommandApprovalService.CARD_SCOPE_KEY, scope)
                .set(DangerousCommandApprovalService.CARD_APPROVAL_ID_KEY, approvalId);
        return root.toData();
    }

    /**
     * 执行审批卡片文本相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param maxLength 最大保留字符数。
     * @return 返回审批Card Text结果。
     */
    private String approvalCardText(Object value, int maxLength) {
        return SecretRedactor.redact(
                TerminalAnsiSanitizer.stripAnsi(stringValue(value)), maxLength);
    }

    /**
     * 执行审批卡片选择器相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回审批Card Selector结果。
     */
    private String approvalCardSelector(Object value) {
        String selector = DangerousCommandApprovalService.safeApprovalSelectorToken(value);
        return selector == null ? "" : selector;
    }

    /**
     * 执行审批卡片AllowAlways相关逻辑。
     *
     * @param extras extras 参数。
     * @return 返回审批Card Allow Always结果。
     */
    private boolean approvalCardAllowAlways(Map<String, Object> extras) {
        Object value = extras == null ? null : extras.get("approvalAllowAlways");
        return value == null || Boolean.parseBoolean(stringValue(value));
    }

    /**
     * 执行upload图片相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回upload图片结果。
     */
    private String uploadImage(File file) {
        assertSafeUrl(IMAGE_UPLOAD_URL, "Feishu image upload URL");
        HttpResponse httpResponse =
                HttpRequest.post(IMAGE_UPLOAD_URL)
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .form("image_type", "message")
                        .form("image", file)
                        .timeout(30000)
                        .setFollowRedirects(false)
                        .execute();
        String response;
        try {
            response = guardedResponseBody(httpResponse, "Feishu image upload");
        } finally {
            httpResponse.close();
        }
        ONode node = ensureOk(response, "Feishu image upload failed");
        String imageKey = node.get("data").get("image_key").getString();
        if (StrUtil.isBlank(imageKey)) {
            throw new IllegalStateException("Feishu image upload missing image_key");
        }
        return imageKey;
    }

    /**
     * 执行upload文件相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @param uploadType upload类型参数。
     * @return 返回upload文件结果。
     */
    private String uploadFile(File file, String uploadType) {
        assertSafeUrl(FILE_UPLOAD_URL, "Feishu file upload URL");
        HttpResponse httpResponse =
                HttpRequest.post(FILE_UPLOAD_URL)
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .form("file_type", uploadType)
                        .form("file_name", file.getName())
                        .form("file", file)
                        .timeout(30000)
                        .setFollowRedirects(false)
                        .execute();
        String response;
        try {
            response = guardedResponseBody(httpResponse, "Feishu file upload");
        } finally {
            httpResponse.close();
        }
        ONode node = ensureOk(response, "Feishu file upload failed");
        String fileKey = node.get("data").get("file_key").getString();
        if (StrUtil.isBlank(fileKey)) {
            throw new IllegalStateException("Feishu file upload missing file_key");
        }
        return fileKey;
    }

    /**
     * 解析文件Routing。
     *
     * @param attachment 附件参数。
     * @return 返回解析后的文件Routing。
     */
    private UploadRouting resolveFileRouting(MessageAttachment attachment) {
        String name =
                StrUtil.blankToDefault(attachment.getOriginalName(), "attachment.bin")
                        .toLowerCase();
        if (name.endsWith(".ogg") || name.endsWith(".opus")) {
            return new UploadRouting("opus", "audio");
        }
        if (name.endsWith(".mp4")
                || name.endsWith(".mov")
                || name.endsWith(".avi")
                || name.endsWith(".m4v")) {
            return new UploadRouting("mp4", "media");
        }
        if (name.endsWith(".pdf")) {
            return new UploadRouting("pdf", "file");
        }
        if (name.endsWith(".doc") || name.endsWith(".docx")) {
            return new UploadRouting("doc", "file");
        }
        if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
            return new UploadRouting("xls", "file");
        }
        if (name.endsWith(".ppt") || name.endsWith(".pptx")) {
            return new UploadRouting("ppt", "file");
        }
        return new UploadRouting("stream", "file");
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 执行postJSON相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param body 请求体或消息正文内容。
     * @return 返回post JSON结果。
     */
    private String postJson(String url, String body) {
        assertSafeUrl(url, "Feishu API URL");
        HttpResponse response =
                HttpRequest.post(url)
                        .contentType(ContentType.JSON.toString())
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .body(body)
                        .timeout(15000)
                        .setFollowRedirects(false)
                        .execute();
        try {
            return guardedResponseBody(response, "Feishu API request");
        } finally {
            response.close();
        }
    }

    /** 创建飞书消息表情回应，并返回平台 reaction_id 供后续删除。 */
    protected String createProcessingReaction(String messageId, String emojiType) {
        if (StrUtil.isBlank(messageId) || StrUtil.isBlank(emojiType)) {
            return null;
        }
        try {
            refreshTenantTokenIfNecessary();
            ONode body = new ONode().set("reaction_type", new ONode().set("emoji_type", emojiType));
            String url = String.format(MESSAGE_REACTION_URL, messageId);
            ONode response =
                    ensureOk(postJson(url, body.toJson()), "Feishu reaction create failed");
            return StrUtil.firstNonBlank(
                    response.get("data").get("reaction_id").getString(),
                    response.get("data").get("reaction").get("reaction_id").getString());
        } catch (Exception e) {
            log.warn(
                    "[FEISHU] processing reaction create failed: messageId={}, emoji={}, errorType={}, error={}",
                    messageId,
                    emojiType,
                    errorType(e),
                    safeError(e));
            return null;
        }
    }

    /** 删除飞书消息表情回应，删除失败时返回 false 以避免叠加冲突状态。 */
    protected boolean deleteProcessingReaction(String messageId, String reactionId) {
        if (StrUtil.isBlank(messageId) || StrUtil.isBlank(reactionId)) {
            return false;
        }
        try {
            refreshTenantTokenIfNecessary();
            String url = String.format(MESSAGE_REACTION_URL, messageId) + "/" + reactionId;
            assertSafeUrl(url, "Feishu reaction delete URL");
            HttpResponse response =
                    HttpRequest.delete(url)
                            .header("Authorization", "Bearer " + tenantAccessToken)
                            .timeout(15000)
                            .setFollowRedirects(false)
                            .execute();
            String body;
            try {
                body = guardedResponseBody(response, "Feishu reaction delete");
            } finally {
                response.close();
            }
            ensureOk(body, "Feishu reaction delete failed");
            return true;
        } catch (Exception e) {
            log.warn(
                    "[FEISHU] processing reaction delete failed: messageId={}, reactionId={}, errorType={}, error={}",
                    messageId,
                    reactionId,
                    errorType(e),
                    safeError(e));
            return false;
        }
    }

    /**
     * 确保Ok。
     *
     * @param response 当前响应对象。
     * @param defaultMessage 默认消息参数。
     * @return 返回Ok结果。
     */
    protected ONode ensureOk(String response, String defaultMessage) {
        ONode node = ONode.ofJson(response);
        int code = node.get("code").getInt(0);
        if (code != 0) {
            throw new IllegalStateException(
                    defaultMessage + ": " + safePlatformMessage(node.get("msg").getString()));
        }
        return node;
    }

    /** 刷新Tenant token If Necessary。 */
    private synchronized void refreshTenantTokenIfNecessary() {
        long now = System.currentTimeMillis();
        if (StrUtil.isNotBlank(tenantAccessToken) && now < tokenExpireAt) {
            return;
        }
        String body =
                new ONode()
                        .set("app_id", config.getAppId())
                        .set("app_secret", config.getAppSecret())
                        .toJson();
        assertSafeUrl(TOKEN_URL, "Feishu token URL");
        HttpResponse httpResponse =
                HttpRequest.post(TOKEN_URL)
                        .contentType(ContentType.JSON.toString())
                        .body(body)
                        .timeout(15000)
                        .setFollowRedirects(false)
                        .execute();
        String response;
        try {
            response = guardedResponseBody(httpResponse, "Feishu token request");
        } finally {
            httpResponse.close();
        }
        ONode node = ONode.ofJson(response);
        int code = node.get("code").getInt(0);
        if (code != 0) {
            throw new IllegalStateException(
                    "Fetch tenant token failed: "
                            + safePlatformMessage(node.get("msg").getString()));
        }
        tenantAccessToken = node.get("tenant_access_token").getString();
        long expire = node.get("expire").getLong(7200L);
        tokenExpireAt = now + Math.max(60000L, (expire - 60L) * 1000L);
    }

    /** 关闭WebSocketClient。 */
    private void shutdownWebsocketClient() {
        if (wsClient == null) {
            return;
        }
        try {
            java.lang.reflect.Field autoReconnectField =
                    com.lark.oapi.ws.Client.class.getDeclaredField("autoReconnect");
            autoReconnectField.setAccessible(true);
            autoReconnectField.set(wsClient, Boolean.FALSE);
            java.lang.reflect.Method disconnectMethod =
                    com.lark.oapi.ws.Client.class.getDeclaredMethod("disconnect");
            disconnectMethod.setAccessible(true);
            disconnectMethod.invoke(wsClient);
            java.lang.reflect.Field executorField =
                    com.lark.oapi.ws.Client.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            Object executor = executorField.get(wsClient);
            if (executor instanceof ExecutorService) {
                ((ExecutorService) executor).shutdownNow();
            }
        } catch (Exception e) {
            log.debug(
                    "[FEISHU] websocket shutdown cleanup failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        } finally {
            wsClient = null;
        }
    }

    /**
     * 执行受控响应正文相关逻辑。
     *
     * @param response 当前响应对象。
     * @param purpose purpose 参数。
     * @return 返回guarded响应Body结果。
     */
    private String guardedResponseBody(HttpResponse response, String purpose) {
        int status = response.getStatus();
        if (status >= 300 && status < 400) {
            throw new IllegalStateException(
                    purpose
                            + " blocked redirect: HTTP "
                            + status
                            + " -> "
                            + SecretRedactor.maskUrl(response.header("Location")));
        }
        if (status >= 400) {
            throw new IllegalStateException(HutoolHttpErrorFormatter.failure(purpose, response));
        }
        return BoundedAttachmentIO.readHutoolText(response, BoundedAttachmentIO.JSON_MAX_BYTES);
    }

    /**
     * 执行assert安全URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param purpose purpose 参数。
     */
    private void assertSafeUrl(String url, String purpose) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    purpose
                            + " blocked: "
                            + SecretRedactor.maskUrl(url)
                            + "，"
                            + verdict.getMessage());
        }
    }

    /**
     * 生成安全展示用的平台消息。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe平台消息结果。
     */
    protected String safePlatformMessage(String value) {
        return SecretRedactor.redact(value, 1000);
    }

    /** 承载飞书文本消息相关状态和辅助逻辑。 */
    @RequiredArgsConstructor
    public static class FeishuTextMessage {
        /** 记录飞书文本消息中的文本。 */
        private final String text;

        /**
         * 读取Text。
         *
         * @return 返回读取到的Text。
         */
        public String getText() {
            return text;
        }
    }

    /** 承载UploadRouting相关状态和辅助逻辑。 */
    @RequiredArgsConstructor
    private static class UploadRouting {
        /** 记录UploadRouting中的upload类型。 */
        private final String uploadType;

        /** 记录UploadRouting中的消息类型。 */
        private final String messageType;
    }

    /** 承载Post媒体Ref相关状态和辅助逻辑。 */
    private static class PostMediaRef {
        /** 记录Post媒体Ref中的文件键。 */
        private final String fileKey;

        /** 记录Post媒体Ref中的文件名称。 */
        private final String fileName;

        /** 记录Post媒体Ref中的资源类型。 */
        private final String resourceType;

        /**
         * 创建Post媒体Ref实例，并注入运行所需依赖。
         *
         * @param fileKey 文件或目录路径参数。
         * @param fileName 文件或目录路径参数。
         * @param resourceType 资源类型参数。
         */
        private PostMediaRef(String fileKey, String fileName, String resourceType) {
            this.fileKey = fileKey;
            this.fileName = fileName;
            this.resourceType = resourceType;
        }
    }

    /** 表示Post解析结果，携带调用方后续判断所需信息。 */
    private static class PostParseResult {
        /** 记录Post解析中的文本。 */
        private final StringBuilder text = new StringBuilder();

        /** 记录Post解析中的文本Content。 */
        private String textContent;

        /** 保存图片Keys集合，维持调用顺序或去重语义。 */
        private final List<String> imageKeys = new ArrayList<String>();

        /** 保存媒体Refs集合，维持调用顺序或去重语义。 */
        private final List<PostMediaRef> mediaRefs = new ArrayList<PostMediaRef>();
    }
}
