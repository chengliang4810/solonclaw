package com.jimuqu.solon.claw.gateway.platform.qqbot;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.gateway.platform.ChannelUrlPolicyGuard;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** QQBot 官方 API v2 适配器。当前覆盖文本、媒体传输与附件感知主链。 */
public class QQBotChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** tokenURL的统一常量值。 */
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    /** 默认APIDOMAIN的统一常量值。 */
    private static final String DEFAULT_API_DOMAIN = "https://api.sgroup.qq.com";

    /** JSON的统一常量值。 */
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

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
    public boolean connect() {
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
                setConnected(true);
                setSetupState("configured");
                setDetail("REST ready; websocket gateway unavailable");
                return true;
            }
            ChannelUrlPolicyGuard.assertSafeUrl(securityPolicyService, gateway, "QQBot websocket URL");
            callbackExecutor = Executors.newSingleThreadExecutor();
            Request request =
                    new Request.Builder()
                            .url(gateway)
                            .header("Authorization", "QQBot " + accessToken)
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
            setLastError("qqbot_connect_failed", safeError(e));
            setDetail("connect failed: " + safeError(e));
            log.warn("[QQBOT] connect failed: errorType={}, error={}", errorType(e), safeError(e));
            return false;
        }
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "normal");
            webSocket = null;
        }
        if (callbackExecutor != null) {
            callbackExecutor.shutdownNow();
            callbackExecutor = null;
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
            postJson(
                    resolveMessagePath(request),
                    buildTextBody(request.getText(), request.getThreadId()).toJson());
        }
        List<MessageAttachment> attachments = request.getAttachments();
        if (attachments != null) {
            for (MessageAttachment attachment : attachments) {
                sendAttachment(request, attachment);
            }
        }
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
                        .set("msg_seq", Long.valueOf(System.currentTimeMillis()));
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
        return buildTextBody(text, request.getThreadId(), keyboard);
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
        return buildTextBody(text.toString(), request.getThreadId(), keyboard);
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
        Object value = extras == null ? null : extras.get("approvalAllowAlways");
        return value == null || Boolean.parseBoolean(stringValue(value));
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
        ONode body =
                new ONode()
                        .set("msg_type", Integer.valueOf(7))
                        .getOrNew("media")
                        .set("file_info", fileInfo)
                        .parent()
                        .set("msg_seq", Long.valueOf(System.currentTimeMillis()));
        postJson(resolveMessagePath(request), body.toJson());
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
        String value = StrUtil.blankToDefault(config.getApiDomain(), DEFAULT_API_DOMAIN).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
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
        ChannelUrlPolicyGuard.assertSafeUrl(securityPolicyService, TOKEN_URL, "QQBot token URL");
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
        ChannelUrlPolicyGuard.assertSafeUrl(securityPolicyService, url, "QQBot API URL");
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
        String url = apiDomain() + path;
        ChannelUrlPolicyGuard.assertSafeUrl(securityPolicyService, url, "QQBot API URL");
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("Authorization", "QQBot " + accessToken)
                        .post(RequestBody.create(JSON, body))
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
     * 写入JSON。
     *
     * @param path 文件或目录路径。
     * @param body 请求体或消息正文内容。
     * @return 返回JSON结果。
     */
    private ONode putJson(String path, String body) throws Exception {
        String url = apiDomain() + path;
        ChannelUrlPolicyGuard.assertSafeUrl(securityPolicyService, url, "QQBot API URL");
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("Authorization", "QQBot " + accessToken)
                        .put(RequestBody.create(JSON, body))
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
        if (response.body() == null) {
            return "";
        }
        return BoundedAttachmentIO.readOkHttpText(response, BoundedAttachmentIO.JSON_MAX_BYTES);
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
            setConnected(true);
            setSetupState("connected");
            setDetail("websocket connected");
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
            QQBotChannelAdapter.this.webSocket = null;
            setConnected(false);
            setSetupState("error");
            setLastError("qqbot_websocket_failure", safeError(t));
            setDetail("websocket disconnected");
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
            QQBotChannelAdapter.this.webSocket = null;
            setConnected(false);
            setSetupState("disconnected");
            setDetail("websocket closed: " + code + " " + reason);
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
                        acknowledgeInteractionIfNecessary(raw);
                        GatewayMessage message = toGatewayMessage(raw);
                        if (message == null) {
                            return;
                        }
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
        message.setThreadId(data.get("id").getString());
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
        message.setThreadId(data.get("id").getString());
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
        if (config.isAllowAllUsers()) {
            return true;
        }
        if (GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(chatType)) {
            String policy =
                    StrUtil.blankToDefault(
                                    config.getGroupPolicy(),
                                    GatewayBehaviorConstants.GROUP_POLICY_OPEN)
                            .toLowerCase();
            if (GatewayBehaviorConstants.GROUP_POLICY_DISABLED.equals(policy)) {
                return false;
            }
            return !GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST.equals(policy)
                    || contains(config.getGroupAllowedUsers(), chatId);
        }
        String policy =
                StrUtil.blankToDefault(
                                config.getDmPolicy(), GatewayBehaviorConstants.DM_POLICY_OPEN)
                        .toLowerCase();
        if (GatewayBehaviorConstants.DM_POLICY_DISABLED.equals(policy)) {
            return false;
        }
        return !GatewayBehaviorConstants.DM_POLICY_ALLOWLIST.equals(policy)
                || contains(config.getAllowedUsers(), userId);
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
     * 执行firstNon空白值相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回first Non Blank结果。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
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
