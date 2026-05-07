package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpRequest;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.noear.snack4.ONode;

/** 微信 iLink QR 登录服务。 */
public class WeixinQrSetupService {
    private static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    private static final String GET_BOT_QR_ENDPOINT = "ilink/bot/get_bot_qrcode?bot_type=3";
    private static final String GET_QR_STATUS_ENDPOINT = "ilink/bot/get_qrcode_status?qrcode=%s";
    private static final long LOGIN_TIMEOUT_MILLIS = 8L * 60L * 1000L;
    private static final int MAX_REFRESH_COUNT = 3;
    private static final int MAX_HTTP_REDIRECTS = 5;

    private final AppConfig appConfig;
    private final DashboardConfigService configService;
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;
    private final RuntimeConfigResolver configResolver;
    private final SecurityPolicyService securityPolicyService;
    private final ExecutorService executor = BoundedExecutorFactory.fixed("weixin-qr-setup", 2, 32);
    private final ConcurrentMap<String, TicketState> tickets =
            new ConcurrentHashMap<String, TicketState>();

    public WeixinQrSetupService(
            AppConfig appConfig,
            DashboardConfigService configService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(appConfig, configService, gatewayRuntimeRefreshService, null);
    }

    public WeixinQrSetupService(
            AppConfig appConfig,
            DashboardConfigService configService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.configResolver = RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome());
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
    }

    public Map<String, Object> start() {
        final TicketState state = new TicketState();
        state.ticket = IdUtil.fastSimpleUUID();
        state.status = "initializing";
        state.createdAt = System.currentTimeMillis();
        state.updatedAt = state.createdAt;
        state.expiresAt = state.createdAt + LOGIN_TIMEOUT_MILLIS;
        tickets.put(state.ticket, state);
        executor.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        runFlow(state);
                    }
                });
        return toMap(state);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public Map<String, Object> get(String ticket) {
        TicketState state = tickets.get(ticket);
        if (state == null) {
            throw new IllegalStateException("Weixin QR ticket not found: " + ticket);
        }
        return toMap(state);
    }

    private void runFlow(TicketState state) {
        String baseUrl =
                StrUtil.blankToDefault(
                        appConfig.getChannels().getWeixin().getBaseUrl(), DEFAULT_BASE_URL);
        String currentBaseUrl = baseUrl;
        String qrCode = null;
        int refreshCount = 0;
        try {
            assertSafeBaseUrl(baseUrl, "微信 iLink baseUrl");
            ONode qrResponse = fetchQr(baseUrl);
            qrCode = updateQrState(state, qrResponse);
            while (System.currentTimeMillis() < state.expiresAt) {
                ONode statusResponse = fetchStatus(currentBaseUrl, qrCode);
                String status =
                        StrUtil.nullToDefault(statusResponse.get("status").getString(), "wait");
                if ("wait".equals(status)) {
                    mark(state, "pending", "等待扫码");
                } else if ("scaned".equals(status)) {
                    mark(state, "scanned", "已扫码，等待确认");
                } else if ("scaned_but_redirect".equals(status)) {
                    String redirectHost = statusResponse.get("redirect_host").getString();
                    if (StrUtil.isNotBlank(redirectHost)) {
                        currentBaseUrl = normalizeBaseUrl("https://" + redirectHost.trim());
                        assertSafeBaseUrl(currentBaseUrl, "微信 iLink redirect_host");
                    }
                    mark(state, "scanned", "已扫码，等待跳转确认");
                } else if ("expired".equals(status)) {
                    refreshCount++;
                    if (refreshCount > MAX_REFRESH_COUNT) {
                        fail(state, "qr_expired", "二维码已过期，请重新发起扫码。");
                        return;
                    }
                    qrResponse = fetchQr(baseUrl);
                    currentBaseUrl = baseUrl;
                    qrCode = updateQrState(state, qrResponse);
                    mark(state, "pending", "二维码已刷新，请重新扫码");
                } else if ("confirmed".equals(status)) {
                    persistConfirmedCredentials(statusResponse);
                    state.accountId = statusResponse.get("ilink_bot_id").getString();
                    state.userId = statusResponse.get("ilink_user_id").getString();
                    state.baseUrl =
                            StrUtil.blankToDefault(
                                    statusResponse.get("baseurl").getString(), baseUrl);
                    mark(state, "confirmed", "微信连接成功");
                    return;
                } else {
                    fail(state, "qr_status_unknown", "未知二维码状态：" + status);
                    return;
                }
                sleepMillis(1000L);
            }
            fail(state, "qr_timeout", "微信扫码登录超时。");
        } catch (Exception e) {
            fail(state, "qr_failed", safeMessage(e));
        }
    }

    private ONode fetchQr(String baseUrl) {
        String body = executeJsonGet(normalizeBaseUrl(baseUrl) + "/" + GET_BOT_QR_ENDPOINT, 0);
        return ONode.ofJson(body);
    }

    private ONode fetchStatus(String baseUrl, String qrCode) {
        String body =
                executeJsonGet(
                        normalizeBaseUrl(baseUrl)
                                + "/"
                                + String.format(GET_QR_STATUS_ENDPOINT, qrCode),
                        0);
        return ONode.ofJson(body);
    }

    private String executeJsonGet(String url, int redirectCount) {
        assertSafeUrl(url, "微信 iLink 请求地址");
        HttpResponse response =
                HttpRequest.get(url)
                        .header("iLink-App-Id", "bot")
                        .header("iLink-App-ClientVersion", String.valueOf((2 << 16) | (2 << 8)))
                        .contentType(ContentType.JSON.toString())
                        .timeout(35_000)
                        .setFollowRedirects(false)
                        .execute();
        try {
            int status = response.getStatus();
            if (isRedirect(status)) {
                if (redirectCount >= MAX_HTTP_REDIRECTS) {
                    throw new IllegalStateException("微信 iLink 请求重定向次数超过限制");
                }
                String location = response.header("Location");
                if (StrUtil.isBlank(location)) {
                    throw new IllegalStateException("微信 iLink 请求重定向缺少 Location");
                }
                String nextUrl = resolveRedirectUrl(url, location);
                response.close();
                return executeJsonGet(nextUrl, redirectCount + 1);
            }
            return BoundedAttachmentIO.readHutoolText(response, BoundedAttachmentIO.JSON_MAX_BYTES);
        } finally {
            response.close();
        }
    }

    private String updateQrState(TicketState state, ONode qrResponse) {
        String qrCode = qrResponse.get("qrcode").getString();
        if (StrUtil.isBlank(qrCode)) {
            throw new IllegalStateException("微信二维码响应缺少 qrcode");
        }
        state.qrCode = qrCode;
        state.qrImageUrl = qrResponse.get("qrcode_img_content").getString();
        state.updatedAt = System.currentTimeMillis();
        state.status = "pending";
        state.message = "请使用微信扫码";
        return qrCode;
    }

    private void persistConfirmedCredentials(ONode statusResponse) {
        String accountId = statusResponse.get("ilink_bot_id").getString();
        String token = statusResponse.get("bot_token").getString();
        String baseUrl =
                StrUtil.blankToDefault(statusResponse.get("baseurl").getString(), DEFAULT_BASE_URL);
        if (StrUtil.isBlank(accountId) || StrUtil.isBlank(token)) {
            throw new IllegalStateException("微信扫码成功，但返回的账号信息不完整。");
        }
        baseUrl = normalizeBaseUrl(baseUrl);
        assertSafeBaseUrl(baseUrl, "微信 iLink baseurl");
        configResolver.setFileValue("solonclaw.channels.weixin.accountId", accountId);
        configResolver.setFileValue("solonclaw.channels.weixin.token", token);

        if (!DEFAULT_BASE_URL.equals(baseUrl)) {
            Map<String, Object> updates = new LinkedHashMap<String, Object>();
            updates.put("channels.weixin.baseUrl", baseUrl);
            configService.savePartialFlat(updates);
        }
        gatewayRuntimeRefreshService.refreshNow();
    }

    private void mark(TicketState state, String status, String message) {
        state.status = status;
        state.message = message;
        state.updatedAt = System.currentTimeMillis();
    }

    private void fail(TicketState state, String code, String message) {
        state.status = "failed";
        state.errorCode = code;
        state.errorMessage = message;
        state.message = message;
        state.updatedAt = System.currentTimeMillis();
    }

    private Map<String, Object> toMap(TicketState state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ticket", state.ticket);
        result.put("status", state.status);
        result.put("message", state.message);
        result.put("error_code", state.errorCode);
        result.put("error_message", state.errorMessage);
        result.put("qr_code", state.qrCode);
        result.put("qr_image_url", state.qrImageUrl);
        result.put("created_at", isoTime(state.createdAt));
        result.put("updated_at", isoTime(state.updatedAt));
        result.put("expires_at", isoTime(state.expiresAt));
        result.put("account_id", state.accountId);
        result.put("user_id", state.userId);
        result.put("base_url", state.baseUrl);
        return result;
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return StrUtil.isBlank(message) ? e.getClass().getSimpleName() : message.trim();
    }

    private void assertSafeBaseUrl(String baseUrl, String purpose) {
        assertSafeUrl(normalizeBaseUrl(baseUrl), purpose);
    }

    private void assertSafeUrl(String url, String purpose) {
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    purpose
                            + " 被安全策略阻断："
                            + SecretRedactor.maskUrl(url)
                            + "，"
                            + verdict.getMessage());
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = StrUtil.nullToEmpty(baseUrl).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "微信 iLink 请求重定向地址无效：" + SecretRedactor.maskUrl(location), e);
        }
    }

    private String isoTime(long epochMillis) {
        if (epochMillis <= 0) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
    }

    private static class TicketState {
        private String ticket;
        private String status;
        private String message;
        private String errorCode;
        private String errorMessage;
        private String qrCode;
        private String qrImageUrl;
        private long createdAt;
        private long updatedAt;
        private long expiresAt;
        private String accountId;
        private String userId;
        private String baseUrl;
    }
}
