package com.jimuqu.solon.claw.gateway.platform.qqbot;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
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
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String DEFAULT_API_DOMAIN = "https://api.sgroup.qq.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final String DELIVERY_MODE_UPDATE_PROMPT = "update_prompt";
    public static final String UPDATE_RESPONSE_FILE_NAME = ".update_response";

    private final AppConfig.ChannelConfig config;
    private final AppConfig appConfig;
    private final AttachmentCacheService attachmentCacheService;
    private final SecurityPolicyService securityPolicyService;
    private final OkHttpClient client;
    private volatile WebSocket webSocket;
    private volatile String accessToken;
    private volatile long accessTokenExpireAt;
    private ExecutorService callbackExecutor;

    public QQBotChannelAdapter(
            AppConfig.ChannelConfig config, AttachmentCacheService attachmentCacheService) {
        this(null, config, attachmentCacheService, null);
    }

    public QQBotChannelAdapter(
            AppConfig.ChannelConfig config,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        this(null, config, attachmentCacheService, securityPolicyService);
    }

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
            assertSafeUrl(gateway, "QQBot websocket URL");
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

    private ONode buildTextBody(String text, String replyTo) {
        return buildTextBody(text, replyTo, null);
    }

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

    private boolean isApprovalCardRequest(DeliveryRequest request) {
        return DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD.equalsIgnoreCase(
                stringValue(
                        request.getChannelExtras() == null
                                ? null
                                : request.getChannelExtras().get("mode")));
    }

    private boolean isUpdatePromptRequest(DeliveryRequest request) {
        return DELIVERY_MODE_UPDATE_PROMPT.equalsIgnoreCase(
                stringValue(
                        request.getChannelExtras() == null
                                ? null
                                : request.getChannelExtras().get("mode")));
    }

    private void sendDangerousApprovalKeyboard(DeliveryRequest request) throws Exception {
        if ("guild".equalsIgnoreCase(request.getChatType())) {
            throw new IllegalArgumentException("QQBot guild chats do not support inline keyboards");
        }
        postJson(resolveMessagePath(request), buildApprovalKeyboardBody(request).toJson());
    }

    private void sendUpdatePromptKeyboard(DeliveryRequest request) throws Exception {
        if ("guild".equalsIgnoreCase(request.getChatType())) {
            throw new IllegalArgumentException("QQBot guild chats do not support inline keyboards");
        }
        postJson(resolveMessagePath(request), buildUpdatePromptKeyboardBody(request).toJson());
    }

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

    private String approvalCardText(Object value, int maxLength) {
        return SecretRedactor.redact(
                TerminalAnsiSanitizer.stripAnsi(stringValue(value)), maxLength);
    }

    private String approvalCardSelector(Object value) {
        String selector = DangerousCommandApprovalService.safeApprovalSelectorToken(value);
        return selector == null ? "" : selector;
    }

    private boolean approvalCardAllowAlways(Map<String, Object> extras) {
        Object value = extras == null ? null : extras.get("approvalAllowAlways");
        return value == null || Boolean.parseBoolean(stringValue(value));
    }

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

    private String resolveMessagePath(DeliveryRequest request) {
        if (GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(request.getChatType())) {
            return "/v2/groups/" + request.getChatId() + "/messages";
        }
        if ("guild".equalsIgnoreCase(request.getChatType())) {
            return "/channels/" + request.getChatId() + "/messages";
        }
        return "/v2/users/" + request.getChatId() + "/messages";
    }

    private String resolveUploadPath(DeliveryRequest request) {
        if (GatewayBehaviorConstants.CHAT_TYPE_GROUP.equalsIgnoreCase(request.getChatType())) {
            return "/v2/groups/" + request.getChatId() + "/files";
        }
        return "/v2/users/" + request.getChatId() + "/files";
    }

    private String apiDomain() {
        String value = StrUtil.blankToDefault(config.getApiDomain(), DEFAULT_API_DOMAIN).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

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
        assertSafeUrl(TOKEN_URL, "QQBot token URL");
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

    private ONode getJson(String path) throws Exception {
        String url = apiDomain() + path;
        assertSafeUrl(url, "QQBot API URL");
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

    private ONode postJson(String path, String body) throws Exception {
        String url = apiDomain() + path;
        assertSafeUrl(url, "QQBot API URL");
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

    private ONode putJson(String path, String body) throws Exception {
        String url = apiDomain() + path;
        assertSafeUrl(url, "QQBot API URL");
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

    private String safeBody(Response response) throws Exception {
        if (response.body() == null) {
            return "";
        }
        return BoundedAttachmentIO.readOkHttpText(response, BoundedAttachmentIO.JSON_MAX_BYTES);
    }

    private String safeHttpErrorBody(String raw) {
        return SecretRedactor.redact(raw, 1000);
    }

    private String safeJson(ONode value) {
        return safeHttpErrorBody(value == null ? "" : value.toJson());
    }

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

    private class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            setConnected(true);
            setSetupState("connected");
            setDetail("websocket connected");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            dispatchInbound(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            dispatchInbound(bytes.utf8());
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            QQBotChannelAdapter.this.webSocket = null;
            setConnected(false);
            setSetupState("error");
            setLastError("qqbot_websocket_failure", safeError(t));
            setDetail("websocket disconnected");
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            QQBotChannelAdapter.this.webSocket = null;
            setConnected(false);
            setSetupState("disconnected");
            setDetail("websocket closed: " + code + " " + reason);
        }
    }

    protected void dispatchInbound(final String raw) {
        if (callbackExecutor == null || inboundMessageHandler() == null || StrUtil.isBlank(raw)) {
            return;
        }
        callbackExecutor.submit(
                new Runnable() {
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

    private String mergeQuoteInto(String text, String quoteBlock) {
        if (StrUtil.isBlank(quoteBlock)) {
            return StrUtil.nullToEmpty(text).trim();
        }
        if (StrUtil.isBlank(text)) {
            return quoteBlock.trim();
        }
        return quoteBlock.trim() + "\n\n" + text.trim();
    }

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

    protected File updateResponseFile() {
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? "runtime"
                        : StrUtil.blankToDefault(appConfig.getRuntime().getHome(), "runtime");
        return new File(home, UPDATE_RESPONSE_FILE_NAME).getAbsoluteFile();
    }

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

    private static class QuotedContext {
        private final String quoteBlock;
        private final List<MessageAttachment> attachments;

        private QuotedContext() {
            this("", new ArrayList<MessageAttachment>());
        }

        private QuotedContext(String quoteBlock, List<MessageAttachment> attachments) {
            this.quoteBlock = StrUtil.nullToEmpty(quoteBlock).trim();
            this.attachments =
                    attachments == null
                            ? new ArrayList<MessageAttachment>()
                            : new ArrayList<MessageAttachment>(attachments);
        }

        private String getQuoteBlock() {
            return quoteBlock;
        }

        private List<MessageAttachment> getAttachments() {
            return attachments;
        }
    }

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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
