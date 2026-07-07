package com.jimuqu.solon.claw.gateway.platform.wecom;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.gateway.platform.ChannelConnectionSupport;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.noear.snack4.ONode;

/** WeComChannelAdapter 实现。 */
public class WeComChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** 默认WSURL的统一常量值。 */
    private static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    /** 应用CMD回调的统一常量值。 */
    private static final String APP_CMD_CALLBACK = "aibot_msg_callback";

    /** 应用CMDSEND的统一常量值。 */
    private static final String APP_CMD_SEND = "aibot_send_msg";

    /** 应用CMD响应的统一常量值。 */
    private static final String APP_CMD_RESPONSE = "aibot_respond_msg";

    /** 应用CMD上传媒体INIT的统一常量值。 */
    private static final String APP_CMD_UPLOAD_MEDIA_INIT = "aibot_upload_media_init";

    /** 应用CMD上传媒体分片的统一常量值。 */
    private static final String APP_CMD_UPLOAD_MEDIA_CHUNK = "aibot_upload_media_chunk";

    /** 应用CMD上传媒体FINISH的统一常量值。 */
    private static final String APP_CMD_UPLOAD_MEDIA_FINISH = "aibot_upload_media_finish";

    /** 上传分片大小的统一常量值。 */
    private static final int UPLOAD_CHUNK_SIZE = 512 * 1024;

    /** 图片最大字节的统一常量值。 */
    private static final int IMAGE_MAX_BYTES = 10 * 1024 * 1024;

    /** VIDEO最大字节的统一常量值。 */
    private static final int VIDEO_MAX_BYTES = 10 * 1024 * 1024;

    /** 语音最大字节的统一常量值。 */
    private static final int VOICE_MAX_BYTES = 2 * 1024 * 1024;

    /** 文件最大字节的统一常量值。 */
    private static final int FILE_MAX_BYTES = 20 * 1024 * 1024;

    /** 回复REQ标识TTLMILLIS的统一常量值。 */
    private static final long REPLY_REQ_ID_TTL_MILLIS = 5L * 60L * 1000L;

    /** 记录WeCom渠道中的配置。 */
    private final AppConfig.ChannelConfig config;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录WeCom渠道中的client。 */
    private final OkHttpClient client;

    /** 保存待恢复Responses映射，便于按键快速查询。 */
    private final ConcurrentMap<String, CompletableFuture<ONode>> pendingResponses =
            new ConcurrentHashMap<String, CompletableFuture<ONode>>();

    /** 保存回复Req标识映射，便于按键快速查询。 */
    private final ConcurrentMap<String, TimedReqId> replyReqIds =
            new ConcurrentHashMap<String, TimedReqId>();

    /** 记录WeCom渠道中的Web套接字。 */
    private volatile WebSocket webSocket;

    /** 保存callback执行器执行组件，负责调度异步或定时任务。 */
    private ExecutorService callbackExecutor;

    /**
     * 创建We Com渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public WeComChannelAdapter(
            AppConfig.ChannelConfig config, AttachmentCacheService attachmentCacheService) {
        this(config, attachmentCacheService, null);
    }

    /**
     * 创建We Com渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public WeComChannelAdapter(
            AppConfig.ChannelConfig config,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        super(PlatformType.WECOM, config);
        this.config = config;
        this.attachmentCacheService = attachmentCacheService;
        this.securityPolicyService = securityPolicyService;
        this.client =
                new OkHttpClient.Builder()
                        .readTimeout(0, TimeUnit.MILLISECONDS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build();
        setConnectionMode("websocket");
        setFeatures("text", "attachments", "quoted-media", "reply-mode", "aes-media");
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
                "wecom_weak_credentials",
                credentialField("solonclaw.channels.wecom.botId", config.getBotId()),
                credentialField("solonclaw.channels.wecom.secret", config.getSecret()))) {
            return false;
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<String>();
        if (StrUtil.isBlank(config.getBotId())) {
            missing.add("solonclaw.channels.wecom.botId");
        }
        if (StrUtil.isBlank(config.getSecret())) {
            missing.add("solonclaw.channels.wecom.secret");
        }
        if (!missing.isEmpty()) {
            setConnected(false);
            setSetupState("missing_config");
            setMissingConfig(missing);
            setLastError("wecom_missing_credentials", "missing botId/secret");
            setDetail("missing botId/secret");
            log.warn("[WECOM] Missing botId/secret");
            return false;
        }

        try {
            String wsUrl = StrUtil.blankToDefault(config.getWebsocketUrl(), DEFAULT_WS_URL).trim();
            callbackExecutor = Executors.newSingleThreadExecutor();
            CountDownLatch latch = new CountDownLatch(1);
            Request request = new Request.Builder().url(wsUrl).build();
            webSocket = client.newWebSocket(request, new Listener(latch));
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("WeCom websocket open timeout");
            }
            ONode auth =
                    request(
                            "aibot_subscribe",
                            new ONode()
                                    .set("bot_id", config.getBotId())
                                    .set("secret", config.getSecret())
                                    .asObject(),
                            15);
            int ret = auth.get("ret").getInt(0);
            if (ret != 0) {
                throw new IllegalStateException("WeCom subscribe failed: " + safeJson(auth));
            }
            setConnected(true);
            setSetupState("connected");
            setMissingConfig(new String[0]);
            clearLastError();
            setDetail("websocket subscribed");
            return true;
        } catch (Exception e) {
            if (webSocket != null) {
                webSocket.cancel();
            }
            webSocket = null;
            setConnected(false);
            setSetupState("error");
            setLastError("wecom_connect_failed", safeError(e));
            setDetail("connect failed: " + safeError(e));
            throw new IllegalStateException("WeCom connect failed", e);
        }
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public void disconnect() {
        ChannelConnectionSupport.disconnect(webSocket, callbackExecutor);
        webSocket = null;
        callbackExecutor = null;
        // 关闭控制命令并发执行器，避免断开连接后线程泄漏
        shutdownControlExecutor();
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
            throw new IllegalArgumentException("WeCom chatId is required");
        }
        if (StrUtil.isNotBlank(request.getText())) {
            ONode response =
                    sendTextMessage(request.getChatId(), request.getText(), request.getThreadId());
            int ret = response.get("ret").getInt(0);
            if (ret != 0) {
                throw new IllegalStateException("WeCom send failed: " + safeJson(response));
            }
        }
        if (request.getAttachments() != null) {
            for (MessageAttachment attachment : request.getAttachments()) {
                sendAttachment(request.getChatId(), attachment, request.getThreadId());
            }
        }
    }

    /**
     * 执行请求相关逻辑。
     *
     * @param cmd cmd 参数。
     * @param body 请求体或消息正文内容。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回请求结果。
     */
    private ONode request(String cmd, ONode body, int timeoutSeconds) {
        if (webSocket == null) {
            throw new IllegalStateException("WeCom websocket is not connected");
        }

        String reqId = UUID.randomUUID().toString();
        CompletableFuture<ONode> future = new CompletableFuture<ONode>();
        pendingResponses.put(reqId, future);
        String payload =
                new ONode()
                        .set("cmd", cmd)
                        .getOrNew("headers")
                        .set("req_id", reqId)
                        .parent()
                        .set("body", body)
                        .toJson();

        if (!webSocket.send(payload)) {
            pendingResponses.remove(reqId);
            throw new IllegalStateException("WeCom websocket send failed");
        }

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingResponses.remove(reqId);
            throw new IllegalStateException("WeCom request timeout", e);
        }
    }

    /** 承载列表ener相关状态和辅助逻辑。 */
    @RequiredArgsConstructor
    private class Listener extends WebSocketListener {
        /** 记录列表ener中的openLatch。 */
        private final CountDownLatch openLatch;

        /**
         * 响应Open事件。
         *
         * @param webSocket Web套接字参数。
         * @param response 当前响应对象。
         */
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            openLatch.countDown();
        }

        /**
         * 响应消息事件。
         *
         * @param webSocket Web套接字参数。
         * @param text 待处理文本。
         */
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            ONode node = ONode.ofJson(text);
            String reqId = node.get("headers").get("req_id").getString();
            if (StrUtil.isBlank(reqId)) {
                reqId = node.get("payload").get("headers").get("req_id").getString();
            }
            CompletableFuture<ONode> future = pendingResponses.remove(reqId);
            if (future != null) {
                future.complete(node);
                return;
            }
            String cmd = node.get("cmd").getString();
            if (APP_CMD_CALLBACK.equals(cmd)) {
                rememberReplyReqId(node.get("body").get("msgid").getString(), payloadReqId(node));
                handleInbound(node);
            }
        }

        /**
         * 响应Failure事件。
         *
         * @param webSocket Web套接字参数。
         * @param t t 参数。
         * @param response 当前响应对象。
         */
        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            failPending(t == null ? new IllegalStateException("WeCom websocket failure") : t);
            WeComChannelAdapter.this.webSocket = null;
            markWebSocketFailure("wecom_websocket_failure", t);
            openLatch.countDown();
            log.warn(
                    "[WECOM] websocket failure: errorType={}, error={}",
                    errorType(t),
                    safeError(t));
        }

        /**
         * 响应Closed事件。
         *
         * @param webSocket Web套接字参数。
         * @param code code 参数。
         * @param reason 原因参数。
         */
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            failPending(
                    new IllegalStateException("WeCom websocket closed: " + code + " " + reason));
            WeComChannelAdapter.this.webSocket = null;
            markWebSocketClosed(code, reason);
        }
    }

    /**
     * 执行入站相关逻辑。
     *
     * @param payload 待签名或解析的载荷内容。
     */
    private void handleInbound(final ONode payload) {
        if (callbackExecutor == null || inboundMessageHandler() == null) {
            return;
        }
        // 先解析入站消息，便于识别控制命令；控制命令走并发执行器避免被运行中的任务阻塞而错过取消时机
        final GatewayMessage message;
        try {
            message = toGatewayMessage(payload);
        } catch (Exception e) {
            log.warn(
                    "[WECOM] inbound parse failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
            return;
        }
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
                    public void run() {
                        try {
                            inboundMessageHandler().handle(message);
                        } catch (Exception e) {
                            log.warn(
                                    "[WECOM] inbound dispatch failed: errorType={}, error={}",
                                    errorType(e),
                                    safeError(e));
                        }
                    }
                });
    }

    /**
     * 转换为消息网关消息。
     *
     * @param payload 待签名或解析的载荷内容。
     * @return 返回转换后的消息网关消息。
     */
    private GatewayMessage toGatewayMessage(ONode payload) throws Exception {
        ONode body = payload.get("body");
        String msgId = body.get("msgid").getString();
        String chatId = body.get("chatid").getString();
        String userId = body.get("from").get("userid").getString();
        String chatType =
                "group".equalsIgnoreCase(body.get("chattype").getString()) ? "group" : "dm";
        if (!allowChat(chatType, chatId, userId)) {
            return null;
        }
        String text = extractText(body);
        List<MessageAttachment> attachments = extractAttachments(body, false);
        ONode quote = findQuoteNode(body);
        if (quote != null && quote.isObject()) {
            attachments.addAll(extractAttachments(quote, true));
        }
        if (StrUtil.isBlank(text) && attachments.isEmpty()) {
            return null;
        }

        GatewayMessage message = new GatewayMessage(PlatformType.WECOM, chatId, userId, text);
        message.setChatType(chatType);
        message.setChatName(chatId);
        message.setUserName(userId);
        message.setThreadId(msgId);
        message.setAttachments(attachments);
        return message;
    }

    /**
     * 提取Text。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回Text结果。
     */
    private String extractText(ONode body) {
        String msgType = body.get("msgtype").getString();
        if ("text".equalsIgnoreCase(msgType)) {
            return StrUtil.nullToEmpty(body.get("text").get("content").getString()).trim();
        }
        if ("voice".equalsIgnoreCase(msgType)) {
            return StrUtil.nullToEmpty(body.get("voice").get("content").getString()).trim();
        }
        if ("mixed".equalsIgnoreCase(msgType)) {
            StringBuilder buffer = new StringBuilder();
            ONode items = body.get("mixed").get("msg_item");
            for (int i = 0; i < items.size(); i++) {
                ONode item = items.get(i);
                if ("text".equalsIgnoreCase(item.get("msgtype").getString())) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(
                            StrUtil.nullToEmpty(item.get("text").get("content").getString())
                                    .trim());
                }
            }
            return buffer.toString().trim();
        }
        return "";
    }

    /**
     * 提取附件。
     *
     * @param body 请求体或消息正文内容。
     * @param fromQuote fromQuote 参数。
     * @return 返回附件结果。
     */
    private List<MessageAttachment> extractAttachments(ONode body, boolean fromQuote)
            throws Exception {
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        String msgType = body.get("msgtype").getString();
        if ("image".equalsIgnoreCase(msgType)) {
            addAttachment(attachments, "image", body.get("image"), fromQuote);
        } else if ("file".equalsIgnoreCase(msgType)) {
            addAttachment(attachments, "file", body.get("file"), fromQuote);
        } else if ("video".equalsIgnoreCase(msgType)) {
            addAttachment(attachments, "video", body.get("video"), fromQuote);
        } else if ("voice".equalsIgnoreCase(msgType)) {
            addAttachment(attachments, "voice", body.get("voice"), fromQuote);
        } else if ("mixed".equalsIgnoreCase(msgType)) {
            ONode items = body.get("mixed").get("msg_item");
            for (int i = 0; i < items.size(); i++) {
                ONode item = items.get(i);
                String itemType = item.get("msgtype").getString();
                if ("image".equalsIgnoreCase(itemType)) {
                    addAttachment(attachments, "image", item.get("image"), fromQuote);
                } else if ("file".equalsIgnoreCase(itemType)) {
                    addAttachment(attachments, "file", item.get("file"), fromQuote);
                } else if ("video".equalsIgnoreCase(itemType)) {
                    addAttachment(attachments, "video", item.get("video"), fromQuote);
                } else if ("voice".equalsIgnoreCase(itemType)) {
                    addAttachment(attachments, "voice", item.get("voice"), fromQuote);
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
     * @param payload 待签名或解析的载荷内容。
     * @param fromQuote fromQuote 参数。
     */
    private void addAttachment(
            List<MessageAttachment> attachments, String kind, ONode payload, boolean fromQuote)
            throws Exception {
        String url = payload.get("url").getString();
        if (StrUtil.isBlank(url)) {
            return;
        }
        byte[] data = downloadBytes(url, FILE_MAX_BYTES);
        String aesKey = payload.get("aeskey").getString();
        if (StrUtil.isNotBlank(aesKey)) {
            data = decryptFileBytes(data, aesKey);
        }
        String fileName = payload.get("filename").getString();
        if (StrUtil.isBlank(fileName)) {
            fileName = payload.get("name").getString();
        }
        if (StrUtil.isBlank(fileName)) {
            fileName = kind + ".bin";
        }
        String mimeType =
                AttachmentCacheService.normalizeMimeType(
                        payload.get("content_type").getString(), fileName);
        attachments.add(
                attachmentCacheService.cacheBytes(
                        PlatformType.WECOM,
                        kind,
                        fileName,
                        mimeType,
                        fromQuote,
                        payload.get("content").getString(),
                        data));
    }

    /**
     * 执行download字节相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param maxBytes max字节参数。
     * @return 返回download Bytes结果。
     */
    private byte[] downloadBytes(String url, long maxBytes) throws Exception {
        return BoundedAttachmentIO.downloadOkHttp(client, url, maxBytes, securityPolicyService);
    }


    /**
     * 发送附件。
     *
     * @param chatId 聊天标识。
     * @param attachment 附件参数。
     * @param replyToMessageId 回复To消息标识。
     */
    private void sendAttachment(
            String chatId, MessageAttachment attachment, String replyToMessageId) {
        File file = new File(attachment.getLocalPath());
        if (!file.isFile()) {
            throw new IllegalStateException(
                    MessageAttachmentSupport.fileNotFoundMessage("WeCom", attachment));
        }

        byte[] bytes = cn.hutool.core.io.FileUtil.readBytes(file);
        String mediaType = resolveOutboundMediaType(attachment, bytes.length);
        String mediaId = uploadMediaBytes(bytes, mediaType, file.getName());
        ONode body =
                new ONode()
                        .set("chatid", chatId)
                        .set("msgtype", mediaType)
                        .getOrNew(mediaType)
                        .set("media_id", mediaId)
                        .parent()
                        .asObject();
        ONode response = sendByMode(body, chatId, replyToMessageId, mediaType, 30);
        int errCode = response.get("errcode").getInt(0);
        if (errCode != 0) {
            throw new IllegalStateException("WeCom media send failed: " + safeJson(response));
        }
    }

    /**
     * 发送Text消息。
     *
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     * @param replyToMessageId 回复To消息标识。
     * @return 返回Text消息结果。
     */
    private ONode sendTextMessage(String chatId, String text, String replyToMessageId) {
        ONode body =
                new ONode()
                        .set("chatid", chatId)
                        .set("msgtype", "markdown")
                        .getOrNew("markdown")
                        .set("content", text)
                        .parent()
                        .asObject();
        return sendByMode(body, chatId, replyToMessageId, "markdown", 15);
    }

    /**
     * 发送根据模式。
     *
     * @param body 请求体或消息正文内容。
     * @param chatId 聊天标识。
     * @param replyToMessageId 回复To消息标识。
     * @param description 描述参数。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回根据模式结果。
     */
    private ONode sendByMode(
            ONode body,
            String chatId,
            String replyToMessageId,
            String description,
            int timeoutSeconds) {
        String replyReqId = replyReqIdForMessage(replyToMessageId);
        try {
            if (StrUtil.isNotBlank(replyReqId)) {
                return requestWithReplyReqId(APP_CMD_RESPONSE, replyReqId, body, timeoutSeconds);
            }
        } catch (Exception e) {
            log.warn(
                    "[WECOM] reply-mode {} failed, fallback to proactive send: {}",
                    description,
                    e.getMessage());
        }
        return request(APP_CMD_SEND, body, timeoutSeconds);
    }

    /**
     * 执行请求With回复Req标识相关逻辑。
     *
     * @param cmd cmd 参数。
     * @param replyReqId 回复Req标识。
     * @param body 请求体或消息正文内容。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回请求With Reply Req标识。
     */
    private ONode requestWithReplyReqId(
            String cmd, String replyReqId, ONode body, int timeoutSeconds) {
        if (webSocket == null) {
            throw new IllegalStateException("WeCom websocket is not connected");
        }
        CompletableFuture<ONode> future = new CompletableFuture<ONode>();
        pendingResponses.put(replyReqId, future);
        String payload =
                new ONode()
                        .set("cmd", cmd)
                        .getOrNew("headers")
                        .set("req_id", replyReqId)
                        .parent()
                        .set("body", body)
                        .toJson();
        if (!webSocket.send(payload)) {
            pendingResponses.remove(replyReqId);
            throw new IllegalStateException("WeCom websocket send failed");
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingResponses.remove(replyReqId);
            throw new IllegalStateException("WeCom reply request timeout", e);
        }
    }

    /**
     * 执行载荷Req标识相关逻辑。
     *
     * @param payload 待签名或解析的载荷内容。
     * @return 返回payload Req标识。
     */
    private String payloadReqId(ONode payload) {
        String reqId = payload.get("headers").get("req_id").getString();
        if (StrUtil.isBlank(reqId)) {
            reqId = payload.get("payload").get("headers").get("req_id").getString();
        }
        return reqId;
    }

    /**
     * 执行remember回复Req标识相关逻辑。
     *
     * @param messageId 消息标识。
     * @param reqId req标识。
     */
    private void rememberReplyReqId(String messageId, String reqId) {
        cleanupReplyReqIds();
        String normalizedMessageId = StrUtil.nullToEmpty(messageId).trim();
        String normalizedReqId = StrUtil.nullToEmpty(reqId).trim();
        if (normalizedMessageId.length() == 0 || normalizedReqId.length() == 0) {
            return;
        }
        replyReqIds.put(
                normalizedMessageId,
                new TimedReqId(
                        normalizedReqId, System.currentTimeMillis() + REPLY_REQ_ID_TTL_MILLIS));
        while (replyReqIds.size() > 1000) {
            String firstKey = replyReqIds.keySet().iterator().next();
            replyReqIds.remove(firstKey);
        }
    }

    /**
     * 执行回复Req标识For消息相关逻辑。
     *
     * @param messageId 消息标识。
     * @return 返回reply Req标识For消息结果。
     */
    private String replyReqIdForMessage(String messageId) {
        cleanupReplyReqIds();
        String normalized = StrUtil.nullToEmpty(messageId).trim();
        if (normalized.length() == 0) {
            return null;
        }
        TimedReqId value = replyReqIds.get(normalized);
        if (value == null || value.isExpired()) {
            replyReqIds.remove(normalized);
            return null;
        }
        return value.reqId;
    }

    /** 执行cleanup回复Req标识相关逻辑。 */
    private void cleanupReplyReqIds() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TimedReqId> entry : replyReqIds.entrySet()) {
            TimedReqId value = entry.getValue();
            if (value == null || value.expiresAt <= now) {
                replyReqIds.remove(entry.getKey(), value);
            }
        }
    }

    /**
     * 执行fail待恢复相关逻辑。
     *
     * @param throwable 捕获到的异常。
     */
    private void failPending(Throwable throwable) {
        for (CompletableFuture<ONode> future : pendingResponses.values()) {
            future.completeExceptionally(throwable);
        }
        pendingResponses.clear();
    }

    /**
     * 判断是否允许Chat。
     *
     * @param chatType 聊天类型参数。
     * @param chatId 聊天标识。
     * @param userId 用户标识。
     * @return 如果Chat满足条件则返回 true，否则返回 false。
     */
    private boolean allowChat(String chatType, String chatId, String userId) {
        if (config.isAllowAllUsers()) {
            return true;
        }
        if ("group".equalsIgnoreCase(chatType)) {
            String policy =
                    StrUtil.blankToDefault(
                                    config.getGroupPolicy(),
                                    GatewayBehaviorConstants.GROUP_POLICY_OPEN)
                            .toLowerCase();
            if (GatewayBehaviorConstants.GROUP_POLICY_DISABLED.equals(policy)) {
                return false;
            }
            if (GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST.equals(policy)
                    && !contains(config.getGroupAllowedUsers(), chatId)) {
                return false;
            }
            return allowGroupSender(chatId, userId);
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
     * 判断是否允许群组Sender。
     *
     * @param chatId 聊天标识。
     * @param userId 用户标识。
     * @return 如果群组Sender满足条件则返回 true，否则返回 false。
     */
    private boolean allowGroupSender(String chatId, String userId) {
        Map<String, List<String>> allowMap = config.getGroupMemberAllowedUsers();
        if (allowMap == null || allowMap.isEmpty()) {
            return true;
        }
        List<String> entries = allowMap.get(chatId);
        if ((entries == null || entries.isEmpty()) && allowMap.containsKey("*")) {
            entries = allowMap.get("*");
        }
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        return contains(entries, userId);
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
            if ("*".equals(normalized)
                    || target.equalsIgnoreCase(normalized)
                    || target.equalsIgnoreCase(
                            normalized
                                    .replaceFirst("(?i)^wecom:", "")
                                    .replaceFirst("(?i)^(user|group):", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查找Quote Node。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回Quote Node结果。
     */
    private ONode findQuoteNode(ONode body) {
        for (String key : new String[] {"quote", "quoted", "quote_msg", "reference"}) {
            ONode node = body.get(key);
            if (node != null && node.isObject()) {
                return node;
            }
        }
        return null;
    }

    /**
     * 解析出站媒体类型。
     *
     * @param attachment 附件参数。
     * @param sizeBytes size字节参数。
     * @return 返回解析后的出站媒体类型。
     */
    private String resolveOutboundMediaType(MessageAttachment attachment, int sizeBytes) {
        String kind =
                AttachmentCacheService.normalizeKind(
                        attachment.getKind(),
                        attachment.getOriginalName(),
                        attachment.getMimeType());
        String mime = StrUtil.nullToEmpty(attachment.getMimeType()).toLowerCase();
        if ("image".equals(kind)) {
            return sizeBytes > IMAGE_MAX_BYTES ? "file" : "image";
        }
        if ("video".equals(kind)) {
            return sizeBytes > VIDEO_MAX_BYTES ? "file" : "video";
        }
        if ("voice".equals(kind)) {
            if (sizeBytes > VOICE_MAX_BYTES) {
                return "file";
            }
            return "audio/amr".equals(mime)
                            || StrUtil.endWithIgnoreCase(attachment.getOriginalName(), ".amr")
                    ? "voice"
                    : "file";
        }
        if (sizeBytes > FILE_MAX_BYTES) {
            throw new IllegalStateException("WeCom attachment exceeds 20MB limit");
        }
        return "file";
    }

    /**
     * 执行upload媒体字节相关逻辑。
     *
     * @param data 数据参数。
     * @param mediaType 媒体类型参数。
     * @param fileName 文件或目录路径参数。
     * @return 返回upload媒体Bytes结果。
     */
    private String uploadMediaBytes(byte[] data, String mediaType, String fileName) {
        if (data.length == 0) {
            throw new IllegalArgumentException("WeCom attachment is empty");
        }
        int totalChunks = (data.length + UPLOAD_CHUNK_SIZE - 1) / UPLOAD_CHUNK_SIZE;
        ONode init =
                request(
                        APP_CMD_UPLOAD_MEDIA_INIT,
                        new ONode()
                                .set("type", mediaType)
                                .set("filename", fileName)
                                .set("total_size", data.length)
                                .set("total_chunks", totalChunks)
                                .set("md5", cn.hutool.crypto.digest.DigestUtil.md5Hex(data))
                                .asObject(),
                        30);
        String uploadId = init.get("body").get("upload_id").getString();
        if (StrUtil.isBlank(uploadId)) {
            throw new IllegalStateException(
                    "WeCom media upload init missing upload_id: " + safeJson(init));
        }

        for (int index = 0; index < totalChunks; index++) {
            int start = index * UPLOAD_CHUNK_SIZE;
            int end = Math.min(start + UPLOAD_CHUNK_SIZE, data.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(data, start, chunk, 0, chunk.length);
            request(
                    APP_CMD_UPLOAD_MEDIA_CHUNK,
                    new ONode()
                            .set("upload_id", uploadId)
                            .set("chunk_index", index)
                            .set("base64_data", Base64.getEncoder().encodeToString(chunk))
                            .asObject(),
                    30);
        }

        ONode finish =
                request(
                        APP_CMD_UPLOAD_MEDIA_FINISH,
                        new ONode().set("upload_id", uploadId).asObject(),
                        30);
        String mediaId = finish.get("body").get("media_id").getString();
        if (StrUtil.isBlank(mediaId)) {
            throw new IllegalStateException(
                    "WeCom media upload finish missing media_id: " + safeJson(finish));
        }
        return mediaId;
    }

    /**
     * 生成安全展示用的JSON。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe JSON结果。
     */
    private String safeJson(ONode value) {
        return SecretRedactor.redact(value == null ? "" : value.toJson(), 1000);
    }

    /**
     * 执行decrypt文件字节相关逻辑。
     *
     * @param encryptedData encrypted数据参数。
     * @param aesKeyBase64 aes键Base64参数。
     * @return 返回decrypt文件Bytes结果。
     */
    private byte[] decryptFileBytes(byte[] encryptedData, String aesKeyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(aesKeyBase64);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec iv = new IvParameterSpec(key, 0, 16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, iv);
        return cipher.doFinal(encryptedData);
    }

    /** 承载TimedReq标识相关状态和辅助逻辑。 */
    private static class TimedReqId {
        /** 记录TimedReq标识中的req标识。 */
        private final String reqId;

        /** 记录TimedReq标识中的expires时间。 */
        private final long expiresAt;

        /**
         * 创建Timed Req标识实例，并注入运行所需依赖。
         *
         * @param reqId req标识。
         * @param expiresAt expiresAt 参数。
         */
        private TimedReqId(String reqId, long expiresAt) {
            this.reqId = reqId;
            this.expiresAt = expiresAt;
        }

        /**
         * 判断是否Expired。
         *
         * @return 如果Expired满足条件则返回 true，否则返回 false。
         */
        private boolean isExpired() {
            return expiresAt <= System.currentTimeMillis();
        }
    }
}
