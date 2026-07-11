package com.jimuqu.solon.claw.gateway.platform.yuanbao;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.gateway.platform.ChannelConnectionSupport;
import com.jimuqu.solon.claw.gateway.platform.ChannelHttpSupport;
import com.jimuqu.solon.claw.gateway.platform.ChannelInboundPolicySupport;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.noear.snack4.ONode;

/** 腾讯元宝 Bot 渠道适配器。协议层保留 JSON/REST 可测边界，媒体只做传输与附件感知。 */
public class YuanbaoChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** 默认WSURL的统一常量值。 */
    private static final String DEFAULT_WS_URL = "wss://bot-wss.yuanbao.tencent.com/wss/connection";

    /** 默认APIDOMAIN的统一常量值。 */
    private static final String DEFAULT_API_DOMAIN = "https://bot.yuanbao.tencent.com";

    /** JSON的统一常量值。 */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 记录元宝渠道中的配置。 */
    private final AppConfig.ChannelConfig config;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 将入站媒体落入统一缓存，供多模态和后续转写链消费。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 记录元宝渠道中的client。 */
    private final OkHttpClient client;

    /** 记录元宝渠道中的Web套接字。 */
    private volatile WebSocket webSocket;

    /** 保存callback执行器执行组件，负责调度异步或定时任务。 */
    private ExecutorService callbackExecutor;

    /**
     * 创建元宝渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     */
    public YuanbaoChannelAdapter(AppConfig.ChannelConfig config) {
        this(config, null, null);
    }

    /**
     * 创建元宝渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public YuanbaoChannelAdapter(
            AppConfig.ChannelConfig config, SecurityPolicyService securityPolicyService) {
        this(config, null, securityPolicyService);
    }

    /**
     * 创建带统一附件缓存的元宝渠道适配器。
     *
     * @param config 渠道配置。
     * @param attachmentCacheService 附件缓存服务。
     * @param securityPolicyService URL 安全策略。
     */
    public YuanbaoChannelAdapter(
            AppConfig.ChannelConfig config,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        super(PlatformType.YUANBAO, config);
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
        setFeatures("text", "attachments", "media-transfer", "platform-asr-text");
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
                "yuanbao_weak_credentials",
                credentialField("solonclaw.channels.yuanbao.appId", config.getAppId()),
                credentialField("solonclaw.channels.yuanbao.appSecret", config.getAppSecret()),
                credentialField("solonclaw.channels.yuanbao.botId", config.getBotId()))) {
            return false;
        }
        if (StrUtil.isBlank(config.getAppId()) || StrUtil.isBlank(config.getAppSecret())) {
            setSetupState("missing_config");
            setMissingConfig(
                    "solonclaw.channels.yuanbao.appId", "solonclaw.channels.yuanbao.appSecret");
            setLastError("yuanbao_missing_credentials", "missing appId/appSecret");
            setDetail("missing appId/appSecret");
            return false;
        }
        try {
            String wsUrl = StrUtil.blankToDefault(config.getWebsocketUrl(), DEFAULT_WS_URL);
            callbackExecutor = Executors.newSingleThreadExecutor();
            Request request =
                    new Request.Builder()
                            .url(wsUrl)
                            .header("X-App-Id", config.getAppId())
                            .header("X-Signature", sign(String.valueOf(System.currentTimeMillis())))
                            .build();
            webSocket = client.newWebSocket(request, new Listener());
            setConnected(true);
            setSetupState("connected");
            setMissingConfig(new String[0]);
            clearLastError();
            setDetail("websocket connecting");
            return true;
        } catch (Exception e) {
            setConnected(false);
            setSetupState("error");
            setLastError("yuanbao_connect_failed", safeError(e));
            setDetail("connect failed: " + safeError(e));
            log.warn(
                    "[YUANBAO] connect failed: errorType={}, error={}", errorType(e), safeError(e));
            return false;
        }
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public void disconnect() {
        ChannelConnectionSupport.disconnect(webSocket, callbackExecutor);
        webSocket = null;
        callbackExecutor = null;
        setConnected(false);
        setDetail("disconnected");
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param request 当前请求对象。
     */
    @Override
    public void send(DeliveryRequest request) throws Exception {
        if (StrUtil.isBlank(request.getChatId())) {
            throw new IllegalArgumentException("Yuanbao chatId is required");
        }
        if (StrUtil.isNotBlank(request.getText())) {
            ONode body =
                    baseSendBody(request)
                            .set("msg_type", "text")
                            .getOrNew("text")
                            .set("content", request.getText())
                            .parent();
            sendPayload(body);
        }
        if (request.getAttachments() != null) {
            for (MessageAttachment attachment : request.getAttachments()) {
                sendAttachment(request, attachment);
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
        File file = new File(attachment.getLocalPath());
        if (!file.isFile()) {
            throw new IllegalStateException(
                    MessageAttachmentSupport.fileNotFoundMessage("Yuanbao", attachment));
        }
        String kind =
                AttachmentCacheService.normalizeKind(
                        attachment.getKind(),
                        attachment.getOriginalName(),
                        attachment.getMimeType());
        ONode body =
                baseSendBody(request)
                        .set("msg_type", kind)
                        .getOrNew(kind)
                        .set(
                                "file_name",
                                StrUtil.blankToDefault(
                                        attachment.getOriginalName(), file.getName()))
                        .set(
                                "mime_type",
                                AttachmentCacheService.normalizeMimeType(
                                        attachment.getMimeType(), file.getName()))
                        .set("file_data", Base64.encode(FileUtil.readBytes(file)))
                        .parent();
        sendPayload(body);
    }

    /**
     * 执行基础Send正文相关逻辑。
     *
     * @param request 当前请求对象。
     * @return 返回base Send Body结果。
     */
    private ONode baseSendBody(DeliveryRequest request) {
        return new ONode()
                .set("request_id", UUID.randomUUID().toString())
                .set("bot_id", config.getBotId())
                .set("chat_id", request.getChatId())
                .set(
                        "chat_type",
                        StrUtil.blankToDefault(
                                request.getChatType(), GatewayBehaviorConstants.CHAT_TYPE_DM))
                .set("reply_to", request.getThreadId());
    }

    /**
     * 发送Payload。
     *
     * @param body 请求体或消息正文内容。
     */
    private void sendPayload(ONode body) throws Exception {
        String payload = body.toJson();
        if (webSocket != null && isConnected()) {
            if (!webSocket.send(payload)) {
                throw new IllegalStateException("Yuanbao websocket send failed");
            }
            return;
        }
        postJson("/openapi/bot/messages", payload);
    }

    /**
     * 执行postJSON相关逻辑。
     *
     * @param path 文件或目录路径。
     * @param body 请求体或消息正文内容。
     * @return 返回post JSON结果。
     */
    private ONode postJson(String path, String body) throws Exception {
        String url = apiDomain() + path;
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("X-App-Id", config.getAppId())
                        .header("X-Signature", sign(body))
                        .post(RequestBody.create(JSON, body))
                        .build();
        Response response = client.newCall(request).execute();
        try {
            String raw = safeBody(response);
            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "Yuanbao HTTP "
                                + response.code()
                                + ": "
                                + SecretRedactor.redact(raw, 1000));
            }
            return StrUtil.isBlank(raw) ? new ONode() : ONode.ofJson(raw);
        } finally {
            response.close();
        }
    }

    /**
     * 生成安全展示用的正文。
     *
     * @param response 当前响应对象。
     * @return 返回safe Body结果。
     */
    private String safeBody(Response response) throws Exception {
        return ChannelHttpSupport.safeBody(response);
    }

    /**
     * 执行apiDomain相关逻辑。
     *
     * @return 返回api Domain结果。
     */
    private String apiDomain() {
        return ChannelHttpSupport.apiDomain(config.getApiDomain(), DEFAULT_API_DOMAIN);
    }

    /**
     * 执行sign相关逻辑。
     *
     * @param payload 待签名或解析的载荷内容。
     * @return 返回sign结果。
     */
    private String sign(String payload) {
        HMac hmac =
                new HMac(
                        HmacAlgorithm.HmacSHA256,
                        StrUtil.nullToEmpty(config.getAppSecret())
                                .getBytes(StandardCharsets.UTF_8));
        return hmac.digestHex(StrUtil.nullToEmpty(payload));
    }

    /** 承载列表ener相关状态和辅助逻辑。 */
    private class Listener extends WebSocketListener {
        /**
         * 响应Open事件。
         *
         * @param webSocket Web套接字参数。
         * @param response 当前响应对象。
         */
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            markWebSocketConnected();
        }

        /**
         * 响应消息事件。
         *
         * @param webSocket Web套接字参数。
         * @param text 待处理文本。
         */
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            dispatchInbound(text);
        }

        /**
         * 响应消息事件。
         *
         * @param webSocket Web套接字参数。
         * @param bytes 字节参数。
         */
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            dispatchInbound(bytes.utf8());
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
            YuanbaoChannelAdapter.this.webSocket = null;
            markWebSocketFailure("yuanbao_websocket_failure", t);
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
            YuanbaoChannelAdapter.this.webSocket = null;
            markWebSocketClosed(code, reason);
        }
    }

    /**
     * 分发入站。
     *
     * @param raw 原始输入值。
     */
    protected void dispatchInbound(final String raw) {
        if (callbackExecutor == null || inboundMessageHandler() == null || StrUtil.isBlank(raw)) {
            return;
        }
        callbackExecutor.submit(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        GatewayMessage message = toGatewayMessage(raw);
                        if (message == null) {
                            return;
                        }
                        try {
                            inboundMessageHandler().handle(message);
                        } catch (Exception e) {
                            log.warn(
                                    "[YUANBAO] inbound dispatch failed: errorType={}, error={}",
                                    errorType(e),
                                    safeError(e));
                        }
                    }
                });
    }

    /**
     * 转换为消息网关消息。
     *
     * @param raw 原始输入值。
     * @return 返回转换后的消息网关消息。
     */
    protected GatewayMessage toGatewayMessage(String raw) {
        ONode node = ONode.ofJson(raw);
        ONode body = node.get("body");
        if (body == null || body.isNull()) {
            body = node;
        }
        String chatId =
                firstNonBlank(
                        body.get("chat_id").getString(),
                        body.get("group_id").getString(),
                        body.get("openid").getString());
        String userId =
                firstNonBlank(
                        body.get("user_id").getString(),
                        body.get("from_openid").getString(),
                        body.get("from").getString());
        String chatType =
                StrUtil.blankToDefault(
                        body.get("chat_type").getString(), GatewayBehaviorConstants.CHAT_TYPE_DM);
        String text =
                firstNonBlank(
                        body.get("text").get("content").getString(),
                        body.get("content").getString(),
                        body.get("voice").get("text").getString(),
                        body.get("asr_text").getString());
        List<MessageAttachment> attachments = extractAttachments(body);
        if (StrUtil.isBlank(chatId)
                || (StrUtil.isBlank(text) && attachments.isEmpty())
                || !allowInbound(chatType, chatId, userId)) {
            return null;
        }
        GatewayMessage message =
                new GatewayMessage(
                        PlatformType.YUANBAO, chatId, userId, StrUtil.nullToEmpty(text).trim());
        message.setChatType(chatType);
        message.setChatName(chatId);
        message.setUserName(userId);
        message.setThreadId(
                firstNonBlank(body.get("message_id").getString(), body.get("msg_id").getString()));
        message.setAttachments(attachments);
        return message;
    }

    /** 提取元宝入站消息中的图片、文件、视频和语音附件。 */
    private List<MessageAttachment> extractAttachments(ONode body) {
        List<MessageAttachment> result = new ArrayList<MessageAttachment>();
        String transcript =
                firstNonBlank(
                        body.get("voice").get("text").getString(),
                        body.get("asr_text").getString());
        collectAttachmentNodes(body.get("attachments"), result, transcript);
        collectAttachmentNodes(body.get("attachment"), result, transcript);
        collectAttachmentNodes(body.get("media"), result, transcript);
        collectAttachmentNodes(body.get("image"), result, transcript);
        collectAttachmentNodes(body.get("file"), result, transcript);
        collectAttachmentNodes(body.get("video"), result, transcript);
        collectAttachmentNodes(body.get("voice"), result, transcript);
        return result;
    }

    /** 递归收集单个或数组形式的媒体节点。 */
    private void collectAttachmentNodes(
            ONode node, List<MessageAttachment> result, String fallbackTranscript) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectAttachmentNodes(node.get(i), result, fallbackTranscript);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String fileName =
                firstNonBlank(
                        node.get("file_name").getString(),
                        node.get("filename").getString(),
                        node.get("name").getString());
        String mimeType =
                firstNonBlank(
                        node.get("mime_type").getString(),
                        node.get("content_type").getString(),
                        node.get("mimeType").getString());
        String kind =
                AttachmentCacheService.normalizeKind(
                        firstNonBlank(node.get("kind").getString(), node.get("type").getString()),
                        fileName,
                        mimeType);
        String transcript =
                firstNonBlank(
                        node.get("asr_text").getString(),
                        node.get("transcribed_text").getString(),
                        node.get("text").getString(),
                        fallbackTranscript);
        String inlineData =
                firstNonBlank(node.get("file_data").getString(), node.get("data").getString());
        String url =
                firstNonBlank(
                        node.get("url").getString(),
                        node.get("file_url").getString(),
                        node.get("download_url").getString(),
                        node.get("downloadUrl").getString());
        if (StrUtil.isBlank(inlineData) && StrUtil.isBlank(url)) {
            return;
        }
        try {
            result.add(
                    cacheInboundAttachment(kind, fileName, mimeType, transcript, inlineData, url));
        } catch (Exception e) {
            log.warn(
                    "[YUANBAO] attachment cache failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /** 下载或解码入站附件；无缓存服务的测试构造仅保留受限 URL 元数据。 */
    private MessageAttachment cacheInboundAttachment(
            String kind,
            String fileName,
            String mimeType,
            String transcript,
            String inlineData,
            String url)
            throws Exception {
        if (attachmentCacheService != null) {
            byte[] data;
            String effectiveMime = mimeType;
            if (StrUtil.isNotBlank(inlineData)) {
                data = Base64.decode(inlineData);
            } else {
                BoundedAttachmentIO.OkHttpDownloadResult download =
                        BoundedAttachmentIO.downloadOkHttpResult(
                                client,
                                url,
                                BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                securityPolicyService);
                data = download.getData();
                effectiveMime =
                        AttachmentCacheService.normalizeMimeType(
                                download.getContentType(), fileName);
            }
            return attachmentCacheService.cacheBytes(
                    PlatformType.YUANBAO,
                    kind,
                    StrUtil.blankToDefault(fileName, "yuanbao-attachment.bin"),
                    effectiveMime,
                    false,
                    transcript,
                    data);
        }
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind(kind);
        attachment.setOriginalName(StrUtil.blankToDefault(fileName, "yuanbao-attachment.bin"));
        attachment.setMimeType(AttachmentCacheService.normalizeMimeType(mimeType, fileName));
        attachment.setData(inlineData);
        attachment.setUrl(url);
        attachment.setTranscribedText(StrUtil.nullToEmpty(transcript).trim());
        return attachment;
    }

    /**
     * 判断是否允许入站。
     *
     * @param chatType 聊天类型参数。
     * @param chatId 聊天标识。
     * @param userId 用户标识。
     * @return 如果入站满足条件则返回 true，否则返回 false。
     */
    private boolean allowInbound(String chatType, String chatId, String userId) {
        return ChannelInboundPolicySupport.allowInbound(config, chatType, chatId, userId);
    }

    /**
     * 执行firstNon空白值相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first Non Blank结果。
     */
    private String firstNonBlank(String... values) {
        return ChannelHttpSupport.firstNonBlank(values);
    }
}
