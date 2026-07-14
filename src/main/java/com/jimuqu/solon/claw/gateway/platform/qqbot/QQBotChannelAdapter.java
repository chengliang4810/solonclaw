package com.jimuqu.solon.claw.gateway.platform.qqbot;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
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
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.BoundedMessageDeduplicator;
import com.jimuqu.solon.claw.support.GatewayApprovalCardSupport;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TerminalAnsiSanitizer;
import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
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

/** QQBot 官方 API v2 适配器。当前覆盖文本、媒体传输与附件感知主链。 */
public class QQBotChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** 抑制 QQBot 网关重投的相同消息标识。 */
    private final BoundedMessageDeduplicator inboundMessageDeduplicator =
            new BoundedMessageDeduplicator();

    /** tokenURL的统一常量值。 */
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    /** 默认APIDOMAIN的统一常量值。 */
    private static final String DEFAULT_API_DOMAIN = "https://api.sgroup.qq.com";

    /** JSON的统一常量值。 */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** QQBot 消息、群聊、频道私信与交互事件所需的网关订阅位。 */
    private static final int GATEWAY_INTENTS = (1 << 25) | (1 << 30) | (1 << 12) | (1 << 26);

    /** QQBot 单条文本或 Markdown 消息最大字符数。 */
    private static final int MAX_MESSAGE_LENGTH = 4000;

    /** 心跳提前量，避免网络抖动时刚好超过服务端声明的超时时间。 */
    private static final double HEARTBEAT_INTERVAL_RATIO = 0.8D;

    /** 等待 Hello 或 READY/RESUMED 的最长时间，超时后主动重连。 */
    private static final long GATEWAY_HANDSHAKE_TIMEOUT_SECONDS = 30L;

    /** QQBot 网关限流关闭码要求的固定重连退避。 */
    private static final long RATE_LIMIT_RECONNECT_DELAY_SECONDS = 60L;

    /** 投递模式更新提示词的统一常量值。 */
    public static final String DELIVERY_MODE_UPDATE_PROMPT = "update_prompt";

    /** 更新响应文件名称的统一常量值。 */
    public static final String UPDATE_RESPONSE_FILE_NAME = ".update_response";

    /** 记录QQ机器人渠道中的配置。 */
    private final AppConfig.ChannelConfig config;

    /** 注入应用配置，用于QQ机器人渠道。 */
    private final AppConfig appConfig;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录QQ机器人渠道中的client。 */
    private final OkHttpClient client;

    /** 记录QQ机器人渠道中的Web套接字。 */
    private volatile WebSocket webSocket;

    /** 记录QQ机器人渠道中的access token。 */
    private volatile String accessToken;

    /** 记录QQ机器人渠道中的access tokenExpire时间。 */
    private volatile long accessTokenExpireAt;

    /** 保存服务端 READY 下发的会话标识，用于断线后恢复而不是重复创建会话。 */
    private volatile String gatewaySessionId;

    /** 保存最近接收的网关事件序号，心跳和 Resume 都必须携带该值。 */
    private volatile Long gatewaySequence;

    /** 调度 QQBot 网关心跳，连接重建时复用并替换旧周期任务。 */
    private volatile ScheduledExecutorService heartbeatExecutor;

    /** 当前连接对应的心跳任务，断线或切换连接时必须立即取消。 */
    private volatile ScheduledFuture<?> heartbeatFuture;

    /** 当前连接的 Hello 或 READY/RESUMED 握手超时任务。 */
    private volatile ScheduledFuture<?> handshakeFuture;

    /** 关闭码要求延迟重连时保存任务，主动断开可立即取消。 */
    private volatile ScheduledFuture<?> reconnectFuture;

    /** 保存callback执行器执行组件，负责调度异步或定时任务。 */
    private ExecutorService callbackExecutor;

    /**
     * 创建QQ机器人渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public QQBotChannelAdapter(
            AppConfig.ChannelConfig config, AttachmentCacheService attachmentCacheService) {
        this(null, config, attachmentCacheService, null);
    }

    /**
     * 创建QQ机器人渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public QQBotChannelAdapter(
            AppConfig.ChannelConfig config,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        this(null, config, attachmentCacheService, securityPolicyService);
    }

    /**
     * 创建QQ机器人渠道适配器实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param config 当前模块使用的配置对象。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public QQBotChannelAdapter(
            AppConfig appConfig,
            AppConfig.ChannelConfig config,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        super(PlatformType.QQBOT, config);
        this.appConfig = appConfig;
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
        setFeatures(
                "text",
                "attachments",
                "media-transfer",
                "platform-asr-text",
                "inline-keyboard",
                "approval-card",
                "update-prompt");
        setSetupState(config != null && config.isEnabled() ? "configured" : "disabled");
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @return 返回connect结果。
     */
    @Override
    public synchronized boolean connect() {
        if (!isEnabled()) {
            setSetupState("disabled");
            setDetail("disabled");
            return false;
        }
        if (rejectWeakCredentials(
                "qqbot_weak_credentials",
                credentialField("solonclaw.channels.qqbot.appId", config.getAppId()),
                credentialField(
                        "solonclaw.channels.qqbot.clientSecret", config.getClientSecret()))) {
            return false;
        }
        if (StrUtil.isBlank(config.getAppId()) || StrUtil.isBlank(config.getClientSecret())) {
            setSetupState("missing_config");
            setMissingConfig(
                    "solonclaw.channels.qqbot.appId", "solonclaw.channels.qqbot.clientSecret");
            setLastError("qqbot_missing_credentials", "missing appId/clientSecret");
            setDetail("missing appId/clientSecret");
            return false;
        }
        try {
            refreshAccessTokenIfNecessary();
            String gateway =
                    StrUtil.isNotBlank(config.getWebsocketUrl())
                            ? config.getWebsocketUrl().trim()
                            : fetchGatewayUrl();
            if (StrUtil.isBlank(gateway)) {
                setConnected(false);
                setSetupState("configured");
                setDetail("REST ready; websocket gateway unavailable");
                return true;
            }
            callbackExecutor = Executors.newSingleThreadExecutor();
            Request request =
                    new Request.Builder()
                            .url(gateway)
                            .header("Authorization", "QQBot " + accessToken)
                            .build();
            webSocket = client.newWebSocket(request, new Listener());
            setConnected(false);
            setSetupState("connecting");
            setMissingConfig(new String[0]);
            clearLastError();
            setDetail("websocket connecting");
            return true;
        } catch (Exception e) {
            setConnected(false);
            setSetupState("error");
            setLastError("qqbot_connect_failed", safeError(e));
            setDetail("connect failed: " + safeError(e));
            log.warn("[QQBOT] connect failed: errorType={}, error={}", errorType(e), safeError(e));
            return false;
        }
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public synchronized void disconnect() {
        WebSocket current = webSocket;
        webSocket = null;
        shutdownHeartbeatExecutor();
        ChannelConnectionSupport.disconnect(current, callbackExecutor);
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
    public void send(DeliveryRequest request) throws Exception {
        if (StrUtil.isBlank(request.getChatId())) {
            throw new IllegalArgumentException("QQBot chatId is required");
        }
        refreshAccessTokenIfNecessary();
        if (isApprovalCardRequest(request)) {
            sendDangerousApprovalKeyboard(request);
            return;
        }
        if (isUpdatePromptRequest(request)) {
            sendUpdatePromptKeyboard(request);
            return;
        }
        if (StrUtil.isNotBlank(request.getText())) {
            String replyTo = request.getReplyToMessageId();
            for (String chunk : splitOutboundText(request.getText())) {
                postJson(resolveMessagePath(request), buildTextBody(chunk, replyTo).toJson());
                replyTo = null;
            }
        }
        List<MessageAttachment> attachments = request.getAttachments();
        if (attachments != null) {
            for (MessageAttachment attachment : attachments) {
                sendAttachment(request, attachment);
            }
        }
    }

    /** 按自然换行或空格拆分 QQBot 长消息，避免整段因平台长度限制丢失。 */
    protected List<String> splitOutboundText(String text) {
        List<String> chunks = new ArrayList<String>();
        String remaining = StrUtil.nullToEmpty(text);
        while (remaining.length() > MAX_MESSAGE_LENGTH) {
            int split = remaining.lastIndexOf('\n', MAX_MESSAGE_LENGTH);
            if (split < MAX_MESSAGE_LENGTH / 2) {
                split = remaining.lastIndexOf(' ', MAX_MESSAGE_LENGTH);
            }
            if (split < MAX_MESSAGE_LENGTH / 2) {
                split = MAX_MESSAGE_LENGTH;
                if (Character.isHighSurrogate(remaining.charAt(split - 1))) {
                    split--;
                }
            }
            chunks.add(remaining.substring(0, split));
            remaining = remaining.substring(split);
            if (remaining.startsWith("\n") || remaining.startsWith(" ")) {
                remaining = remaining.substring(1);
            }
        }
        if (StrUtil.isNotEmpty(remaining)) {
            chunks.add(remaining);
        }
        return chunks;
    }

    /**
     * 构建Text Body。
     *
     * @param text 待处理文本。
     * @param replyTo 回复To参数。
     * @return 返回创建好的Text Body。
     */
    private ONode buildTextBody(String text, String replyTo) {
        return buildTextBody(text, replyTo, null);
    }

    /**
     * 构建Text Body。
     *
     * @param text 待处理文本。
     * @param replyTo 回复To参数。
     * @param keyboard 键盘参数。
     * @return 返回创建好的Text Body。
     */
    protected ONode buildTextBody(String text, String replyTo, ONode keyboard) {
        ONode body =
                new ONode()
                        .set("msg_type", config.isMarkdownSupport() ? 2 : 0)
                        .set("msg_seq", Integer.valueOf(nextMessageSequence()));
        if (config.isMarkdownSupport()) {
            body.getOrNew("markdown").set("content", text);
        } else {
            body.set("content", text);
        }
        if (StrUtil.isNotBlank(replyTo)) {
            body.set("msg_id", replyTo);
        }
        if (keyboard != null && !keyboard.isNull()) {
            body.set("keyboard", keyboard);
        }
        return body;
    }

    /**
     * 生成 QQBot 协议允许的 16 位消息序号。
     *
     * @return 0 到 65535 之间的消息序号。
     */
    private int nextMessageSequence() {
        return ThreadLocalRandom.current().nextInt(1 << 16);
    }

    /**
     * 判断是否审批Card请求。
     *
     * @param request 当前请求对象。
     * @return 如果审批Card请求满足条件则返回 true，否则返回 false。
     */
    private boolean isApprovalCardRequest(DeliveryRequest request) {
        return GatewayApprovalCardSupport.isApprovalCardRequest(request);
    }

    /**
     * 判断是否更新提示词请求。
     *
     * @param request 当前请求对象。
     * @return 如果更新提示词请求满足条件则返回 true，否则返回 false。
     */
    private boolean isUpdatePromptRequest(DeliveryRequest request) {
        return DELIVERY_MODE_UPDATE_PROMPT.equalsIgnoreCase(
                stringValue(
                        request.getChannelExtras() == null
                                ? null
                                : request.getChannelExtras().get("mode")));
    }

    /**
     * 发送Dangerous审批Keyboard。
     *
     * @param request 当前请求对象。
     */
    private void sendDangerousApprovalKeyboard(DeliveryRequest request) throws Exception {
        if ("guild".equalsIgnoreCase(request.getChatType())) {
            throw new IllegalArgumentException("QQBot guild chats do not support inline keyboards");
        }
        postJson(resolveMessagePath(request), buildApprovalKeyboardBody(request).toJson());
    }

    /**
     * 发送更新提示词Keyboard。
     *
     * @param request 当前请求对象。
     */
    private void sendUpdatePromptKeyboard(DeliveryRequest request) throws Exception {
        if ("guild".equalsIgnoreCase(request.getChatType())) {
            throw new IllegalArgumentException("QQBot guild chats do not support inline keyboards");
        }
        postJson(resolveMessagePath(request), buildUpdatePromptKeyboardBody(request).toJson());
    }

    /**
     * 构建审批Keyboard Body。
     *
     * @param request 当前请求对象。
     * @return 返回创建好的审批Keyboard Body。
     */
    protected ONode buildApprovalKeyboardBody(DeliveryRequest request) {
        Map<String, Object> extras =
                request.getChannelExtras() == null
                        ? new LinkedHashMap<String, Object>()
                        : request.getChannelExtras();
        String approvalId = approvalCardSelector(extras.get("approvalId"));
        String text = buildApprovalText(request, extras);
        ONode keyboard =
                QQBotKeyboardSupport.buildApprovalKeyboard(
                        approvalId, approvalCardAllowAlways(extras));
        return buildTextBody(text, request.getReplyToMessageId(), keyboard);
    }

    /**
     * 构建更新提示词Keyboard Body。
     *
     * @param request 当前请求对象。
     * @return 返回创建好的更新提示词Keyboard Body。
     */
    protected ONode buildUpdatePromptKeyboardBody(DeliveryRequest request) {
        Map<String, Object> extras =
                request.getChannelExtras() == null
                        ? new LinkedHashMap<String, Object>()
                        : request.getChannelExtras();
        String prompt =
                StrUtil.blankToDefault(request.getText(), stringValue(extras.get("updatePrompt")))
                        .trim();
        String defaultAnswer = stringValue(extras.get("updateDefault"));
        StringBuilder text = new StringBuilder("⚕ **更新需要确认**");
        if (StrUtil.isNotBlank(prompt)) {
            text.append("\n\n").append(SecretRedactor.redact(prompt, 3000));
        }
        if (StrUtil.isNotBlank(defaultAnswer)) {
            text.append("\n默认: ").append(SecretRedactor.redact(defaultAnswer, 20));
        }
        ONode keyboard = QQBotKeyboardSupport.buildUpdatePromptKeyboard();
        return buildTextBody(text.toString(), request.getReplyToMessageId(), keyboard);
    }

    /**
     * 构建审批Text。
     *
     * @param request 当前请求对象。
     * @param extras extras 参数。
     * @return 返回创建好的审批Text。
     */
    private String buildApprovalText(DeliveryRequest request, Map<String, Object> extras) {
        if (StrUtil.isNotBlank(request.getText())) {
            return approvalCardText(request.getText(), 3000);
        }
        String command = approvalCardText(extras.get("approvalCommand"), 3000);
        String description = approvalCardText(extras.get("approvalDescription"), 1000);
        String toolName = approvalCardText(extras.get("approvalToolName"), 200);
        StringBuilder buffer = new StringBuilder("🔐 **命令执行审批**");
        if (StrUtil.isNotBlank(command)) {
            buffer.append("\n\n```\n").append(command).append("\n```");
        }
        if (StrUtil.isNotBlank(description)) {
            buffer.append("\n📝 ").append(description);
        }
        if (StrUtil.isNotBlank(toolName)) {
            buffer.append("\n🔧 工具: ").append(toolName);
        }
        return buffer.toString();
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
        return GatewayApprovalCardSupport.approvalCardAllowAlways(extras);
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
                    MessageAttachmentSupport.fileNotFoundMessage("QQBot", attachment));
        }
        String kind =
                AttachmentCacheService.normalizeKind(
                        attachment.getKind(),
                        attachment.getOriginalName(),
                        attachment.getMimeType());
        int fileType =
                "image".equals(kind)
                        ? 1
                        : ("video".equals(kind) ? 2 : ("voice".equals(kind) ? 3 : 4));
        ONode uploadBody =
                new ONode()
                        .set("file_type", Integer.valueOf(fileType))
                        .set("file_data", Base64.encode(FileUtil.readBytes(file)))
                        .set(
                                "file_name",
                                StrUtil.blankToDefault(
                                        attachment.getOriginalName(), file.getName()))
                        .set("srv_send_msg", Boolean.FALSE);
        ONode uploaded = postJson(resolveUploadPath(request), uploadBody.toJson());
        String fileInfo = uploaded.get("file_info").getString();
        if (StrUtil.isBlank(fileInfo)) {
            fileInfo = uploaded.get("data").get("file_info").getString();
        }
        if (StrUtil.isBlank(fileInfo)) {
            throw new IllegalStateException(
                    "QQBot media upload missing file_info: " + safeJson(uploaded));
        }
        ONode body = buildMediaBody(fileInfo, request.getReplyToMessageId());
        postJson(resolveMessagePath(request), body.toJson());
    }

    /** 构建 QQBot 媒体消息，并沿用入站 msg_id 保持回复关联。 */
    protected ONode buildMediaBody(String fileInfo, String replyTo) {
        ONode body = new ONode();
        body.set("msg_type", Integer.valueOf(7));
        body.getOrNew("media").set("file_info", fileInfo);
        body.set("msg_seq", Integer.valueOf(nextMessageSequence()));
        if (StrUtil.isNotBlank(replyTo)) {
            body.set("msg_id", replyTo);
        }
        return body;
    }

    /**
     * 解析消息路径。
     *
     * @param request 当前请求对象。
     * @return 返回解析后的消息路径。
     */
    private String resolveMessagePath(DeliveryRequest request) {
        if (GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(request.getChatType())) {
            return "/v2/groups/" + request.getChatId() + "/messages";
        }
        if ("guild".equalsIgnoreCase(request.getChatType())) {
            return "/channels/" + request.getChatId() + "/messages";
        }
        return "/v2/users/" + request.getChatId() + "/messages";
    }

    /**
     * 解析Upload路径。
     *
     * @param request 当前请求对象。
     * @return 返回解析后的Upload路径。
     */
    private String resolveUploadPath(DeliveryRequest request) {
        if (GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(request.getChatType())) {
            return "/v2/groups/" + request.getChatId() + "/files";
        }
        return "/v2/users/" + request.getChatId() + "/files";
    }

    /**
     * 执行apiDomain相关逻辑。
     *
     * @return 返回api Domain结果。
     */
    private String apiDomain() {
        return ChannelHttpSupport.apiDomain(config.getApiDomain(), DEFAULT_API_DOMAIN);
    }

    /** 刷新access token If Necessary。 */
    private synchronized void refreshAccessTokenIfNecessary() throws Exception {
        long now = System.currentTimeMillis();
        if (StrUtil.isNotBlank(accessToken) && accessTokenExpireAt > now + 60000L) {
            return;
        }
        String body =
                new ONode()
                        .set("appId", config.getAppId())
                        .set("clientSecret", config.getClientSecret())
                        .toJson();
        Request request =
                new Request.Builder().url(TOKEN_URL).post(RequestBody.create(JSON, body)).build();
        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("QQBot token HTTP " + response.code());
            }
            String raw = safeBody(response);
            ONode node = ONode.ofJson(StrUtil.isBlank(raw) ? "{}" : raw);
            accessToken =
                    firstNonBlank(
                            node.get("access_token").getString(),
                            node.get("data").get("access_token").getString());
            if (StrUtil.isBlank(accessToken)) {
                throw new IllegalStateException(
                        "QQBot token response missing access_token: " + safeJson(node));
            }
            long expires = Math.max(60L, node.get("expires_in").getLong(7200L));
            accessTokenExpireAt = now + expires * 1000L;
        } finally {
            response.close();
        }
    }

    /**
     * 拉取消息网关URL。
     *
     * @return 返回fetch消息网关URL结果。
     */
    private String fetchGatewayUrl() {
        try {
            ONode node = getJson("/gateway");
            return firstNonBlank(
                    node.get("url").getString(), node.get("data").get("url").getString());
        } catch (Exception e) {
            log.debug(
                    "[QQBOT] gateway lookup failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
            return "";
        }
    }

    /**
     * 读取JSON。
     *
     * @param path 文件或目录路径。
     * @return 返回读取到的JSON。
     */
    private ONode getJson(String path) throws Exception {
        String url = apiDomain() + path;
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("Authorization", "QQBot " + accessToken)
                        .build();
        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                String raw = safeBody(response);
                throw new IllegalStateException(
                        "QQBot HTTP " + response.code() + ": " + safeHttpErrorBody(raw));
            }
            return ONode.ofJson(safeBody(response));
        } finally {
            response.close();
        }
    }

    /**
     * 执行postJSON相关逻辑。
     *
     * @param path 文件或目录路径。
     * @param body 请求体或消息正文内容。
     * @return 返回post JSON结果。
     */
    private ONode postJson(String path, String body) throws Exception {
        return requestJson("POST", path, body);
    }

    /**
     * 写入JSON。
     *
     * @param path 文件或目录路径。
     * @param body 请求体或消息正文内容。
     * @return 返回JSON结果。
     */
    private ONode putJson(String path, String body) throws Exception {
        return requestJson("PUT", path, body);
    }

    /**
     * 执行QQ机器人JSON请求，统一复用安全URL校验、响应限流读取与错误脱敏。
     *
     * @param method HTTP请求方法。
     * @param path API路径。
     * @param body 请求体或消息正文内容。
     * @return 返回解析后的JSON结果，空响应返回空节点。
     */
    private ONode requestJson(String method, String path, String body) throws Exception {
        String url = apiDomain() + path;
        RequestBody requestBody = RequestBody.create(JSON, body);
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("Authorization", "QQBot " + accessToken)
                        .method(method, requestBody)
                        .build();
        Response response = client.newCall(request).execute();
        try {
            String raw = safeBody(response);
            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "QQBot HTTP " + response.code() + ": " + safeHttpErrorBody(raw));
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
     * 生成安全展示用的HTTP错误正文。
     *
     * @param raw 原始输入值。
     * @return 返回safe HTTP Error Body结果。
     */
    private String safeHttpErrorBody(String raw) {
        return SecretRedactor.redact(raw, 1000);
    }

    /**
     * 生成安全展示用的JSON。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe JSON结果。
     */
    private String safeJson(ONode value) {
        return safeHttpErrorBody(value == null ? "" : value.toJson());
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
            if (QQBotChannelAdapter.this.webSocket != webSocket) {
                return;
            }
            setConnected(false);
            setSetupState("connecting");
            clearLastError();
            setDetail("websocket open; waiting for hello");
            scheduleHandshakeTimeout(webSocket, "hello timeout");
        }

        /**
         * 响应消息事件。
         *
         * @param webSocket Web套接字参数。
         * @param text 待处理文本。
         */
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleWebSocketPayload(webSocket, text);
        }

        /**
         * 响应消息事件。
         *
         * @param webSocket Web套接字参数。
         * @param bytes 字节参数。
         */
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleWebSocketPayload(webSocket, bytes.utf8());
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
            if (!detachCurrentSocket(webSocket)) {
                return;
            }
            stopHeartbeat();
            stopHandshakeTimeout();
            markWebSocketFailure("qqbot_websocket_failure", t);
            requestReconnect();
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
            if (!detachCurrentSocket(webSocket)) {
                return;
            }
            stopHeartbeat();
            stopHandshakeTimeout();
            if (code == 4004) {
                accessToken = null;
                accessTokenExpireAt = 0L;
            }
            if (code == 4006 || code == 4007 || code == 4009) {
                gatewaySessionId = null;
                gatewaySequence = null;
            }
            if (isFatalCloseCode(code)) {
                setConnected(false);
                setSetupState("error");
                setLastError("qqbot_gateway_fatal_close", closeDescription(code));
                setDetail("websocket stopped: " + closeDescription(code));
                return;
            }
            markWebSocketClosed(code, reason);
            if (code == 4008) {
                scheduleReconnect(RATE_LIMIT_RECONNECT_DELAY_SECONDS);
            } else {
                requestReconnect();
            }
        }
    }

    /**
     * 处理 QQBot 网关协议帧，并只把业务 Dispatch 事件交给统一入站链路。
     *
     * @param socket 产生该帧的 WebSocket，用于排除重连后迟到的旧连接回调。
     * @param raw 服务端原始 JSON 帧。
     */
    private void handleWebSocketPayload(WebSocket socket, String raw) {
        if (webSocket != socket || StrUtil.isBlank(raw)) {
            return;
        }
        final ONode root;
        try {
            root = ONode.ofJson(raw);
        } catch (Exception e) {
            log.warn(
                    "[QQBOT] invalid gateway payload: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
            return;
        }
        ONode sequenceNode = root.get("s");
        if (sequenceNode != null && !sequenceNode.isNull()) {
            long sequence = sequenceNode.getLong();
            Long previous = gatewaySequence;
            if (webSocket == socket && (previous == null || sequence > previous.longValue())) {
                gatewaySequence = Long.valueOf(sequence);
            }
        }
        ONode operationNode = root.get("op");
        if (operationNode == null || operationNode.isNull()) {
            // 官方业务帧都会带 op；保留直传仅供受控适配测试与平台兼容接入。
            dispatchInbound(raw);
            return;
        }
        int operation = operationNode.getInt(-1);
        if (operation == 10) {
            handleHello(socket, root.get("d"));
            return;
        }
        if (operation == 0) {
            handleDispatch(socket, root, raw);
            return;
        }
        if (operation == 7) {
            setSetupState("connecting");
            requestProtocolReconnect(socket, "server requested reconnect");
            return;
        }
        if (operation == 9) {
            ONode data = root.get("d");
            boolean resumable = data != null && !data.isNull() && data.getBoolean();
            if (!resumable) {
                gatewaySessionId = null;
                gatewaySequence = null;
            }
            setSetupState("connecting");
            requestProtocolReconnect(socket, "invalid session");
        }
        // op 11 为心跳确认，无需额外处理；未知操作码同样保持连接等待后续帧。
    }

    /**
     * 响应 Hello 帧，先鉴权再按服务端周期启动心跳。
     *
     * @param socket 当前有效 WebSocket。
     * @param data Hello 数据，包含 heartbeat_interval 毫秒数。
     */
    private void handleHello(WebSocket socket, ONode data) {
        long intervalMs =
                Math.max(
                        100L,
                        data == null || data.isNull()
                                ? 30000L
                                : data.get("heartbeat_interval").getLong(30000L));
        try {
            refreshAccessTokenIfNecessary();
            if (webSocket != socket) {
                return;
            }
            boolean sent = socket.send(buildAuthenticationPayload().toJson());
            if (!sent) {
                throw new IllegalStateException("QQBot gateway authentication was not queued");
            }
            startHeartbeat(socket, intervalMs);
            if (webSocket != socket) {
                return;
            }
            scheduleHandshakeTimeout(socket, "ready timeout");
            setSetupState("connecting");
            setDetail(
                    StrUtil.isNotBlank(gatewaySessionId) && gatewaySequence != null
                            ? "resume sent; waiting for resumed"
                            : "identify sent; waiting for ready");
        } catch (Exception e) {
            if (webSocket != socket) {
                return;
            }
            setLastError("qqbot_gateway_auth_failed", safeError(e));
            setSetupState("error");
            setDetail("gateway authentication failed");
            requestProtocolReconnect(socket, "authentication failed");
        }
    }

    /** 构造 Identify 或 Resume 帧，重连状态完整时优先恢复既有网关会话。 */
    private ONode buildAuthenticationPayload() {
        if (StrUtil.isNotBlank(gatewaySessionId) && gatewaySequence != null) {
            ONode data =
                    new ONode()
                            .set("token", "QQBot " + accessToken)
                            .set("session_id", gatewaySessionId)
                            .set("seq", gatewaySequence);
            return new ONode().set("op", Integer.valueOf(6)).set("d", data);
        }
        ONode properties =
                new ONode()
                        .set("$os", System.getProperty("os.name", "unknown"))
                        .set("$browser", "solonclaw")
                        .set("$device", "solonclaw");
        ONode data =
                new ONode()
                        .set("token", "QQBot " + accessToken)
                        .set("intents", Integer.valueOf(GATEWAY_INTENTS))
                        .set("shard", Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)))
                        .set("properties", properties);
        return new ONode().set("op", Integer.valueOf(2)).set("d", data);
    }

    /**
     * 处理 Dispatch 帧；READY/RESUMED 决定 Doctor 的真实连接状态，其余事件进入消息链路。
     *
     * @param socket 当前有效 WebSocket。
     * @param root 已解析的协议帧。
     * @param raw 原始 JSON 帧。
     */
    private void handleDispatch(WebSocket socket, ONode root, String raw) {
        if (webSocket != socket) {
            return;
        }
        String eventType = StrUtil.nullToEmpty(root.get("t").getString());
        if ("READY".equalsIgnoreCase(eventType)) {
            String sessionId = root.get("d").get("session_id").getString();
            if (StrUtil.isBlank(sessionId)) {
                setLastError("qqbot_ready_missing_session", "READY missing session_id");
                setSetupState("error");
                requestProtocolReconnect(socket, "ready missing session");
                return;
            }
            if (webSocket != socket) {
                return;
            }
            gatewaySessionId = sessionId;
            stopHandshakeTimeout();
            markGatewayReady("websocket ready");
            return;
        }
        if ("RESUMED".equalsIgnoreCase(eventType)) {
            if (webSocket != socket) {
                return;
            }
            stopHandshakeTimeout();
            markGatewayReady("websocket resumed");
            return;
        }
        dispatchInbound(raw);
    }

    /** 在鉴权成功后公开真实连接状态，并清理上一次瞬时连接错误。 */
    private void markGatewayReady(String detail) {
        setConnected(true);
        setSetupState("connected");
        clearLastError();
        setDetail(detail);
    }

    /**
     * 为当前 socket 启动单一心跳任务；重连时先取消旧任务，避免跨连接发送旧序号。
     *
     * @param socket 当前有效 WebSocket。
     * @param serverIntervalMs 服务端声明的心跳周期。
     */
    private synchronized void startHeartbeat(final WebSocket socket, long serverIntervalMs) {
        if (webSocket != socket) {
            return;
        }
        stopHeartbeat();
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
            heartbeatExecutor = BoundedExecutorFactory.scheduled("qqbot-heartbeat", 1);
        }
        long periodMs = Math.max(100L, Math.round(serverIntervalMs * HEARTBEAT_INTERVAL_RATIO));
        heartbeatFuture =
                heartbeatExecutor.scheduleAtFixedRate(
                        new Runnable() {
                            /** 向当前连接发送最近序号，发送队列拒绝时触发受控重连。 */
                            @Override
                            public void run() {
                                if (webSocket != socket) {
                                    return;
                                }
                                ONode heartbeat =
                                        new ONode()
                                                .set("op", Integer.valueOf(1))
                                                .set("d", gatewaySequence);
                                if (!socket.send(heartbeat.toJson())) {
                                    setSetupState("connecting");
                                    requestProtocolReconnect(socket, "heartbeat send failed");
                                }
                            }
                        },
                        periodMs,
                        periodMs,
                        TimeUnit.MILLISECONDS);
    }

    /** 取消当前连接的心跳任务，但保留调度器供下一次重连复用。 */
    private synchronized void stopHeartbeat() {
        ScheduledFuture<?> current = heartbeatFuture;
        heartbeatFuture = null;
        if (current != null) {
            current.cancel(true);
        }
    }

    /** 彻底停止心跳调度器，用于用户主动断开或 Profile 销毁。 */
    private synchronized void shutdownHeartbeatExecutor() {
        stopHeartbeat();
        stopHandshakeTimeout();
        ScheduledFuture<?> delayedReconnect = reconnectFuture;
        reconnectFuture = null;
        if (delayedReconnect != null) {
            delayedReconnect.cancel(true);
        }
        ScheduledExecutorService current = heartbeatExecutor;
        heartbeatExecutor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    /** 安排当前连接的协议握手超时，连接变化后任务自动失效。 */
    private synchronized void scheduleHandshakeTimeout(
            final WebSocket socket, final String reason) {
        stopHandshakeTimeout();
        if (webSocket != socket) {
            return;
        }
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
            heartbeatExecutor = BoundedExecutorFactory.scheduled("qqbot-heartbeat", 1);
        }
        handshakeFuture =
                heartbeatExecutor.schedule(
                        () -> handleHandshakeTimeout(socket, reason),
                        GATEWAY_HANDSHAKE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
    }

    /** 取消当前连接尚未触发的协议握手超时。 */
    private synchronized void stopHandshakeTimeout() {
        ScheduledFuture<?> current = handshakeFuture;
        handshakeFuture = null;
        if (current != null) {
            current.cancel(true);
        }
    }

    /** 握手超时仍属于当前连接时公开错误并进入统一重连。 */
    private void handleHandshakeTimeout(WebSocket socket, String reason) {
        if (webSocket != socket) {
            return;
        }
        setLastError("qqbot_gateway_handshake_timeout", reason);
        setSetupState("error");
        requestProtocolReconnect(socket, reason);
    }

    /** 按关闭码要求延迟通知统一连接管理器，避免限流后立即重试。 */
    private synchronized void scheduleReconnect(long delaySeconds) {
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
            heartbeatExecutor = BoundedExecutorFactory.scheduled("qqbot-heartbeat", 1);
        }
        ScheduledFuture<?> current = reconnectFuture;
        if (current != null) {
            current.cancel(true);
        }
        reconnectFuture =
                heartbeatExecutor.schedule(
                        () -> requestReconnect(), delaySeconds, TimeUnit.SECONDS);
    }

    /** 判断关闭码是否代表配置或权限错误，继续重连不会自行恢复。 */
    private boolean isFatalCloseCode(int code) {
        return code == 4001
                || code == 4002
                || code == 4010
                || code == 4011
                || code == 4012
                || code == 4013
                || code == 4014
                || code == 4914
                || code == 4915;
    }

    /** 返回可公开的致命关闭原因，不包含服务端可能携带的敏感正文。 */
    private String closeDescription(int code) {
        switch (code) {
            case 4001:
                return "invalid opcode";
            case 4002:
                return "invalid payload";
            case 4010:
                return "invalid shard";
            case 4011:
                return "sharding required";
            case 4012:
                return "invalid API version";
            case 4013:
                return "invalid intent";
            case 4014:
                return "intent not authorized";
            case 4914:
                return "bot offline or sandbox restricted";
            case 4915:
                return "bot banned";
            default:
                return "fatal gateway close " + code;
        }
    }

    /**
     * 关闭当前协议连接并请求网关统一重连，旧 socket 后续回调会因身份不匹配被忽略。
     *
     * @param socket 当前协议连接。
     * @param reason 可公开的状态原因。
     */
    private void requestProtocolReconnect(WebSocket socket, String reason) {
        if (!detachCurrentSocket(socket)) {
            return;
        }
        stopHeartbeat();
        setConnected(false);
        setDetail(reason);
        socket.close(1000, reason);
        requestReconnect();
    }

    /** 仅当参数仍为当前连接时原子摘除，防止旧回调清掉刚建立的新连接。 */
    private synchronized boolean detachCurrentSocket(WebSocket socket) {
        if (webSocket != socket) {
            return false;
        }
        webSocket = null;
        return true;
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
        // 交互回调（按钮点击）仍需先应答；无论是否控制命令都要走一次确认，避免按钮一直转圈
        acknowledgeInteractionIfNecessary(raw);
        // 先解析入站消息，便于识别控制命令；控制命令走并发执行器避免被运行中的任务阻塞而错过取消时机
        final GatewayMessage message = toGatewayMessage(raw);
        if (message == null) {
            return;
        }
        if (inboundMessageDeduplicator.isDuplicate(message.getReplyToMessageId())) {
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
                                    "[QQBOT] inbound dispatch failed: errorType={}, error={}",
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
        ONode root = ONode.ofJson(raw);
        String eventType = StrUtil.nullToEmpty(root.get("t").getString()).toLowerCase();
        if ("interaction_create".equals(eventType)) {
            return toInteractionGatewayMessage(root);
        }
        ONode data = root.get("d");
        if (data == null || data.isNull()) {
            data = root;
        }
        String chatType =
                eventType.contains("group")
                                || StrUtil.isNotBlank(data.get("group_openid").getString())
                        ? GatewayBehaviorConstants.CHAT_TYPE_GROUP
                        : (eventType.contains("guild")
                                ? "guild"
                                : GatewayBehaviorConstants.CHAT_TYPE_DM);
        String chatId =
                firstNonBlank(
                        data.get("group_openid").getString(),
                        data.get("group_id").getString(),
                        data.get("channel_id").getString(),
                        data.get("openid").getString(),
                        data.get("author").get("id").getString());
        String userId =
                firstNonBlank(
                        data.get("author").get("user_openid").getString(),
                        data.get("author").get("id").getString(),
                        data.get("user_openid").getString(),
                        data.get("openid").getString());
        String text = firstNonBlank(data.get("content").getString(), data.get("text").getString());
        String asrText = data.get("asr_refer_text").getString();
        if (StrUtil.isBlank(text)) {
            text = asrText;
        }
        List<MessageAttachment> attachments = extractAttachments(data, false, asrText);
        QuotedContext quoted = processQuotedContext(data);
        text = mergeQuoteInto(text, quoted.getQuoteBlock());
        attachments.addAll(quoted.getAttachments());
        if (!allowInbound(chatType, chatId, userId)
                || StrUtil.isBlank(chatId)
                || (StrUtil.isBlank(text) && attachments.isEmpty())) {
            return null;
        }
        GatewayMessage message =
                new GatewayMessage(PlatformType.QQBOT, chatId, userId, text.trim());
        message.setChatType(chatType);
        message.setChatName(chatId);
        message.setUserName(userId);
        message.setReplyToMessageId(data.get("id").getString());
        message.setAttachments(attachments);
        return message;
    }

    /**
     * 执行Quoted上下文相关逻辑。
     *
     * @param data 数据参数。
     * @return 返回Quoted上下文结果。
     */
    private QuotedContext processQuotedContext(ONode data) {
        QuotedContext empty = new QuotedContext();
        if (data == null || data.isNull()) {
            return empty;
        }
        try {
            if (data.get("message_type").getInt(0) != 103) {
                return empty;
            }
        } catch (Exception e) {
            return empty;
        }
        ONode elements = data.get("msg_elements");
        if (elements == null || !elements.isArray() || elements.size() == 0) {
            return empty;
        }
        List<String> lines = new ArrayList<String>();
        List<MessageAttachment> attachments = new ArrayList<MessageAttachment>();
        for (int i = 0; i < elements.size(); i++) {
            ONode element = elements.get(i);
            String text =
                    firstNonBlank(
                            element.get("content").getString(), element.get("text").getString());
            if (StrUtil.isNotBlank(text)) {
                lines.add(text);
            }
            String asrText = element.get("asr_refer_text").getString();
            List<MessageAttachment> elementAttachments = extractAttachments(element, true, asrText);
            for (MessageAttachment attachment : elementAttachments) {
                if (StrUtil.isNotBlank(attachment.getTranscribedText())) {
                    lines.add(attachment.getTranscribedText());
                }
                attachments.add(attachment);
            }
            if (StrUtil.isBlank(text)
                    && elementAttachments.isEmpty()
                    && StrUtil.isNotBlank(asrText)) {
                lines.add(asrText);
            }
        }
        if (lines.isEmpty() && attachments.isEmpty()) {
            return empty;
        }
        StringBuilder quote = new StringBuilder("[Quoted message]:");
        if (lines.isEmpty()) {
            quote.append(containsImage(attachments) ? " (image)" : " (attachment)");
        } else {
            for (String line : lines) {
                quote.append('\n').append(line);
            }
        }
        return new QuotedContext(quote.toString(), attachments);
    }

    /**
     * 提取附件。
     *
     * @param data 数据参数。
     * @param fromQuote fromQuote 参数。
     * @param transcribedText transcribed文本参数。
     * @return 返回附件结果。
     */
    private List<MessageAttachment> extractAttachments(
            ONode data, boolean fromQuote, String transcribedText) {
        List<MessageAttachment> result = new ArrayList<MessageAttachment>();
        if (data == null || data.isNull()) {
            return result;
        }
        collectAttachmentNodes(data.get("attachments"), result, fromQuote, transcribedText);
        collectAttachmentNodes(data.get("attachment"), result, fromQuote, transcribedText);
        collectAttachmentNodes(data.get("media"), result, fromQuote, transcribedText);
        collectAttachmentNodes(data.get("file"), result, fromQuote, transcribedText);
        collectAttachmentNodes(data.get("image"), result, fromQuote, transcribedText);
        return result;
    }

    /**
     * 收集附件Nodes。
     *
     * @param node 节点参数。
     * @param result 结果响应或执行结果。
     * @param fromQuote fromQuote 参数。
     * @param fallbackTranscribedText 兜底Transcribed文本参数。
     */
    private void collectAttachmentNodes(
            ONode node,
            List<MessageAttachment> result,
            boolean fromQuote,
            String fallbackTranscribedText) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectAttachmentNodes(node.get(i), result, fromQuote, fallbackTranscribedText);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String url =
                firstNonBlank(
                        node.get("url").getString(),
                        node.get("file_url").getString(),
                        node.get("download_url").getString(),
                        node.get("downloadUrl").getString());
        if (StrUtil.isBlank(url)) {
            return;
        }
        String fileName =
                firstNonBlank(
                        node.get("filename").getString(),
                        node.get("file_name").getString(),
                        node.get("name").getString());
        String mimeType =
                firstNonBlank(
                        node.get("content_type").getString(),
                        node.get("mime_type").getString(),
                        node.get("mimeType").getString());
        String kind =
                AttachmentCacheService.normalizeKind(
                        firstNonBlank(node.get("kind").getString(), node.get("type").getString()),
                        fileName,
                        mimeType);
        String transcript =
                firstNonBlank(
                        node.get("asr_refer_text").getString(),
                        node.get("transcribed_text").getString(),
                        node.get("text").getString(),
                        fallbackTranscribedText);
        try {
            result.add(cacheRemoteAttachment(url, kind, fileName, mimeType, fromQuote, transcript));
        } catch (Exception e) {
            log.warn(
                    "[QQBOT] attachment cache failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 合并Quote Into。
     *
     * @param text 待处理文本。
     * @param quote块 quote阻断参数。
     * @return 返回Quote Into结果。
     */
    private String mergeQuoteInto(String text, String quoteBlock) {
        if (StrUtil.isBlank(quoteBlock)) {
            return StrUtil.nullToEmpty(text).trim();
        }
        if (StrUtil.isBlank(text)) {
            return quoteBlock.trim();
        }
        return quoteBlock.trim() + "\n\n" + text.trim();
    }

    /**
     * 判断是否包含图片。
     *
     * @param attachments attachments 参数。
     * @return 返回contains图片结果。
     */
    private boolean containsImage(List<MessageAttachment> attachments) {
        if (attachments == null) {
            return false;
        }
        for (MessageAttachment attachment : attachments) {
            if (attachment != null && "image".equalsIgnoreCase(attachment.getKind())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行acknowledgeInteractionIfNecessary相关逻辑。
     *
     * @param raw 原始输入值。
     */
    private void acknowledgeInteractionIfNecessary(String raw) {
        try {
            ONode root = ONode.ofJson(raw);
            String eventType = StrUtil.nullToEmpty(root.get("t").getString()).toLowerCase();
            if (!"interaction_create".equals(eventType)) {
                return;
            }
            ONode data = root.get("d");
            String interactionId = data == null || data.isNull() ? "" : data.get("id").getString();
            if (StrUtil.isBlank(interactionId)) {
                return;
            }
            putJson("/interactions/" + interactionId, new ONode().set("code", 0).toJson());
        } catch (Exception e) {
            log.warn(
                    "[QQBOT] interaction ACK failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 转换为Interaction消息网关消息。
     *
     * @param root root 参数。
     * @return 返回转换后的Interaction消息网关消息。
     */
    protected GatewayMessage toInteractionGatewayMessage(ONode root) {
        ONode data = root.get("d");
        if (data == null || data.isNull()) {
            data = root;
        }
        String buttonData =
                firstNonBlank(
                        data.get("resolved").get("button_data").getString(),
                        data.get("data").get("resolved").get("button_data").getString(),
                        data.get("button_data").getString(),
                        data.get("data").get("button_data").getString());
        String command = QQBotKeyboardSupport.commandFromButtonData(buttonData);
        String updateAnswer = QQBotKeyboardSupport.updatePromptAnswerFromButtonData(buttonData);
        if (StrUtil.isBlank(command)) {
            if (StrUtil.isBlank(updateAnswer)) {
                return null;
            }
        }
        if (StrUtil.isNotBlank(command) && StrUtil.isNotBlank(updateAnswer)) {
            return null;
        }
        String chatType = resolveInteractionChatType(data);
        String chatId =
                firstNonBlank(
                        data.get("group_openid").getString(),
                        data.get("group_id").getString(),
                        data.get("channel_id").getString(),
                        data.get("openid").getString(),
                        data.get("user_openid").getString());
        String userId =
                firstNonBlank(
                        data.get("group_member_openid").getString(),
                        data.get("operator").get("openid").getString(),
                        data.get("operator").get("user_openid").getString(),
                        data.get("operator").get("id").getString(),
                        data.get("user_openid").getString(),
                        data.get("openid").getString(),
                        data.get("resolved").get("user_id").getString(),
                        data.get("data").get("resolved").get("user_id").getString());
        if (!allowInbound(chatType, chatId, userId) || StrUtil.isBlank(chatId)) {
            return null;
        }
        if (StrUtil.isNotBlank(updateAnswer)) {
            writeUpdateResponse(updateAnswer);
            return null;
        }
        GatewayMessage message = new GatewayMessage(PlatformType.QQBOT, chatId, userId, command);
        message.setChatType(chatType);
        message.setChatName(chatId);
        message.setUserName(userId);
        message.setReplyToMessageId(data.get("id").getString());
        return message;
    }

    /**
     * 解析Interaction Chat类型。
     *
     * @param data 数据参数。
     * @return 返回解析后的Interaction Chat类型。
     */
    private String resolveInteractionChatType(ONode data) {
        String scene = StrUtil.nullToEmpty(data.get("scene").getString()).toLowerCase();
        if ("group".equals(scene) || StrUtil.isNotBlank(data.get("group_openid").getString())) {
            return GatewayBehaviorConstants.CHAT_TYPE_GROUP;
        }
        if ("guild".equals(scene) || StrUtil.isNotBlank(data.get("channel_id").getString())) {
            return "guild";
        }
        int chatType = data.get("chat_type").getInt(-1);
        if (chatType == 1) {
            return GatewayBehaviorConstants.CHAT_TYPE_GROUP;
        }
        if (chatType == 0) {
            return "guild";
        }
        return GatewayBehaviorConstants.CHAT_TYPE_DM;
    }

    /**
     * 写入更新响应。
     *
     * @param answer answer 参数。
     */
    protected void writeUpdateResponse(String answer) {
        String normalized =
                QQBotKeyboardSupport.updatePromptAnswerFromButtonData(
                        "update_prompt:" + StrUtil.nullToEmpty(answer).trim());
        if (StrUtil.isBlank(normalized)) {
            return;
        }
        try {
            File responsePath = updateResponseFile();
            FileUtil.mkParentDirs(responsePath);
            File temp = new File(responsePath.getParentFile(), responsePath.getName() + ".tmp");
            FileUtil.writeUtf8String(normalized, temp);
            try {
                Files.move(
                        temp.toPath(),
                        responsePath.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temp.toPath(), responsePath.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn(
                    "[QQBOT] update prompt response write failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 更新响应文件。
     *
     * @return 返回响应文件结果。
     */
    protected File updateResponseFile() {
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? RuntimePathConstants.WORKSPACE_HOME
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getHome(),
                                RuntimePathConstants.WORKSPACE_HOME);
        return new File(home, UPDATE_RESPONSE_FILE_NAME).getAbsoluteFile();
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
     * 执行缓存Remote附件相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param kind kind 参数。
     * @param fileName 文件或目录路径参数。
     * @param mimeType MIME 类型参数。
     * @param fromQuote fromQuote 参数。
     * @param transcribedText transcribed文本参数。
     * @return 返回缓存Remote附件结果。
     */
    protected MessageAttachment cacheRemoteAttachment(
            String url,
            String kind,
            String fileName,
            String mimeType,
            boolean fromQuote,
            String transcribedText)
            throws Exception {
        BoundedAttachmentIO.OkHttpDownloadResult download =
                BoundedAttachmentIO.downloadOkHttpResult(
                        client, url, BoundedAttachmentIO.DEFAULT_MAX_BYTES, securityPolicyService);
        return attachmentCacheService.cacheBytes(
                PlatformType.QQBOT,
                AttachmentCacheService.normalizeKind(kind, fileName, mimeType),
                StrUtil.blankToDefault(fileName, "qqbot-attachment.bin"),
                AttachmentCacheService.normalizeMimeType(download.getContentType(), fileName),
                fromQuote,
                transcribedText,
                download.getData());
    }

    /** 承载Quoted上下文相关状态和辅助逻辑。 */
    private static class QuotedContext {
        /** 记录Quoted上下文中的quote阻断。 */
        private final String quoteBlock;

        /** 保存附件集合，维持调用顺序或去重语义。 */
        private final List<MessageAttachment> attachments;

        /** 创建Quoted上下文实例。 */
        private QuotedContext() {
            this("", new ArrayList<MessageAttachment>());
        }

        /**
         * 创建Quoted上下文实例，并注入运行所需依赖。
         *
         * @param quote块 quote阻断参数。
         * @param attachments attachments 参数。
         */
        private QuotedContext(String quoteBlock, List<MessageAttachment> attachments) {
            this.quoteBlock = StrUtil.nullToEmpty(quoteBlock).trim();
            this.attachments =
                    attachments == null
                            ? new ArrayList<MessageAttachment>()
                            : new ArrayList<MessageAttachment>(attachments);
        }

        /**
         * 读取Quote 块。
         *
         * @return 返回读取到的Quote 块。
         */
        private String getQuoteBlock() {
            return quoteBlock;
        }

        /**
         * 读取附件。
         *
         * @return 返回读取到的附件。
         */
        private List<MessageAttachment> getAttachments() {
            return attachments;
        }
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

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
