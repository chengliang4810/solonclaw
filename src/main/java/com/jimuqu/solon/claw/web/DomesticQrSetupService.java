package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.noear.snack4.ONode;

/** 国内渠道扫码注册服务。 */
public class DomesticQrSetupService {
    private static final String PLATFORM_DINGTALK = "dingtalk";
    private static final String PLATFORM_FEISHU = "feishu";
    private static final String DEFAULT_DINGTALK_BASE_URL = "https://oapi.dingtalk.com";
    private static final String DEFAULT_FEISHU_ACCOUNTS_BASE_URL = "https://accounts.feishu.cn";
    private static final String DEFAULT_LARK_ACCOUNTS_BASE_URL = "https://accounts.larksuite.com";
    private static final String FEISHU_REGISTRATION_PATH = "/oauth/v1/app/registration";
    private static final long DEFAULT_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    private final AppConfig appConfig;
    private final DashboardConfigService configService;
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;
    private final RuntimeConfigResolver configResolver;
    private final SecurityPolicyService securityPolicyService;
    private final ExecutorService executor =
            BoundedExecutorFactory.fixed("domestic-qr-setup", 2, 32);
    private final ConcurrentMap<String, TicketState> tickets =
            new ConcurrentHashMap<String, TicketState>();

    public DomesticQrSetupService(
            AppConfig appConfig,
            DashboardConfigService configService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(appConfig, configService, gatewayRuntimeRefreshService, null);
    }

    public DomesticQrSetupService(
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

    public Map<String, Object> start(String platform) {
        final String normalized = normalizePlatform(platform);
        final TicketState state = new TicketState();
        state.ticket = IdUtil.fastSimpleUUID();
        state.platform = normalized;
        state.status = "initializing";
        state.createdAt = System.currentTimeMillis();
        state.updatedAt = state.createdAt;
        state.expiresAt = state.createdAt + DEFAULT_TIMEOUT_MILLIS;
        tickets.put(state.ticket, state);
        executor.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        if (PLATFORM_DINGTALK.equals(normalized)) {
                            runDingTalk(state);
                        } else {
                            runFeishu(state);
                        }
                    }
                });
        waitForQrUrl(state);
        return toMap(state);
    }

    public Map<String, Object> get(String ticket) {
        TicketState state = tickets.get(ticket);
        if (state == null) {
            throw new IllegalStateException("QR ticket not found: " + ticket);
        }
        return toMap(state);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void runDingTalk(TicketState state) {
        try {
            String baseUrl =
                    normalizeBaseUrl(
                            StrUtil.blankToDefault(
                                    appConfig.getChannels().getDingtalk().getBaseUrl(),
                                    DEFAULT_DINGTALK_BASE_URL));
            assertSafeBaseUrl(baseUrl, "钉钉扫码注册地址");
            ONode init = postJson(baseUrl + "/app/registration/init", mapOf("source", "solonclaw"));
            ensureDingTalkOk(init, "init");
            String nonce = init.get("nonce").getString();
            if (StrUtil.isBlank(nonce)) {
                throw new IllegalStateException("钉钉扫码注册响应缺少 nonce");
            }

            ONode begin = postJson(baseUrl + "/app/registration/begin", mapOf("nonce", nonce));
            ensureDingTalkOk(begin, "begin");
            String deviceCode = begin.get("device_code").getString();
            String qrUrl = begin.get("verification_uri_complete").getString();
            if (StrUtil.isBlank(deviceCode) || StrUtil.isBlank(qrUrl)) {
                throw new IllegalStateException("钉钉扫码注册响应缺少二维码信息");
            }
            state.deviceCode = deviceCode;
            state.qrUrl = qrUrl;
            mark(state, "pending", "请使用钉钉扫码授权");

            long deadline =
                    Math.min(
                            state.expiresAt,
                            System.currentTimeMillis()
                                    + Math.max(1L, begin.get("expires_in").getLong()) * 1000L);
            long intervalMillis = Math.max(2L, begin.get("interval").getLong()) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                ONode poll =
                        postJson(
                                baseUrl + "/app/registration/poll",
                                mapOf("device_code", deviceCode));
                ensureDingTalkOk(poll, "poll");
                String status =
                        StrUtil.nullToEmpty(poll.get("status").getString()).trim().toUpperCase();
                if ("SUCCESS".equals(status)) {
                    String clientId = poll.get("client_id").getString();
                    String clientSecret = poll.get("client_secret").getString();
                    if (StrUtil.isBlank(clientId) || StrUtil.isBlank(clientSecret)) {
                        throw new IllegalStateException("钉钉扫码成功，但返回的凭证不完整。");
                    }
                    persistDingTalk(clientId, clientSecret);
                    state.clientId = clientId;
                    mark(state, "confirmed", "钉钉连接成功");
                    return;
                }
                if ("FAIL".equals(status) || "EXPIRED".equals(status)) {
                    fail(state, "qr_failed", "钉钉扫码授权失败：" + status);
                    return;
                }
                if (!"WAITING".equals(status)) {
                    fail(state, "qr_failed", "钉钉扫码授权返回未知状态：" + status);
                    return;
                }
                mark(state, "pending", "等待钉钉扫码授权");
                sleepMillis(intervalMillis);
            }
            fail(state, "qr_timeout", "钉钉扫码登录超时。");
        } catch (Exception e) {
            fail(state, "qr_failed", safeMessage(e));
        }
    }

    private void runFeishu(TicketState state) {
        try {
            String domain = "feishu";
            String baseUrl = feishuAccountsBaseUrl(domain);
            assertSafeBaseUrl(baseUrl, "飞书扫码注册地址");
            ONode init = postForm(baseUrl + FEISHU_REGISTRATION_PATH, mapOf("action", "init"));
            if (!contains(init.get("supported_auth_methods").toData(), "client_secret")) {
                throw new IllegalStateException("飞书扫码注册环境不支持 client_secret 授权。");
            }
            ONode begin =
                    postForm(
                            baseUrl + FEISHU_REGISTRATION_PATH,
                            mapOf(
                                    "action",
                                    "begin",
                                    "archetype",
                                    "PersonalAgent",
                                    "auth_method",
                                    "client_secret",
                                    "request_user_info",
                                    "open_id"));
            String deviceCode = begin.get("device_code").getString();
            String qrUrl = begin.get("verification_uri_complete").getString();
            if (StrUtil.isBlank(deviceCode) || StrUtil.isBlank(qrUrl)) {
                throw new IllegalStateException("飞书扫码注册响应缺少二维码信息");
            }
            state.deviceCode = deviceCode;
            state.qrUrl = appendFeishuQrSource(qrUrl);
            mark(state, "pending", "请使用飞书扫码授权");

            long expireSeconds = Math.max(1L, begin.get("expire_in").getLong());
            long deadline =
                    Math.min(state.expiresAt, System.currentTimeMillis() + expireSeconds * 1000L);
            long intervalMillis = Math.max(1L, begin.get("interval").getLong()) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                ONode poll =
                        postForm(
                                feishuAccountsBaseUrl(domain) + FEISHU_REGISTRATION_PATH,
                                mapOf("action", "poll", "device_code", deviceCode, "tp", "ob_app"));
                ONode userInfo = poll.get("user_info");
                String tenantBrand = userInfo.get("tenant_brand").getString();
                if ("lark".equalsIgnoreCase(tenantBrand)) {
                    domain = "lark";
                }
                String appId = poll.get("client_id").getString();
                String appSecret = poll.get("client_secret").getString();
                if (StrUtil.isNotBlank(appId) && StrUtil.isNotBlank(appSecret)) {
                    String openId = userInfo.get("open_id").getString();
                    persistFeishu(appId, appSecret, openId, domain);
                    state.appId = appId;
                    state.openId = openId;
                    state.domain = domain;
                    mark(state, "confirmed", "飞书连接成功");
                    return;
                }
                String error = poll.get("error").getString();
                if ("access_denied".equals(error) || "expired_token".equals(error)) {
                    fail(state, "qr_failed", "飞书扫码授权失败：" + error);
                    return;
                }
                mark(state, "pending", "等待飞书扫码授权");
                sleepMillis(intervalMillis);
            }
            fail(state, "qr_timeout", "飞书扫码登录超时。");
        } catch (Exception e) {
            fail(state, "qr_failed", safeMessage(e));
        }
    }

    private String feishuAccountsBaseUrl(String domain) {
        if ("lark".equalsIgnoreCase(domain)) {
            return DEFAULT_LARK_ACCOUNTS_BASE_URL;
        }
        return normalizeBaseUrl(
                StrUtil.blankToDefault(
                        appConfig.getChannels().getFeishu().getBaseUrl(),
                        DEFAULT_FEISHU_ACCOUNTS_BASE_URL));
    }

    /** 持久化钉钉扫码授权结果，并把机器人编码默认写为客户端 ID 以兼容钉钉扫码返回字段。 */
    private void persistDingTalk(String clientId, String clientSecret) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("channels.dingtalk.enabled", Boolean.TRUE);
        configService.savePartialFlat(updates, false);
        configResolver.setFileValue("solonclaw.channels.dingtalk.clientId", clientId);
        configResolver.setFileValue("solonclaw.channels.dingtalk.clientSecret", clientSecret);
        configResolver.setFileValue("solonclaw.channels.dingtalk.robotCode", clientId);
        gatewayRuntimeRefreshService.refreshNow();
    }

    private void persistFeishu(String appId, String appSecret, String openId, String domain) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("channels.feishu.enabled", Boolean.TRUE);
        if (StrUtil.isNotBlank(openId)) {
            updates.put("channels.feishu.groupAllowedUsers", Arrays.asList(openId.trim()));
        }
        configService.savePartialFlat(updates, false);
        configResolver.setFileValue("solonclaw.channels.feishu.appId", appId);
        configResolver.setFileValue("solonclaw.channels.feishu.appSecret", appSecret);
        configResolver.setFileValue(
                "solonclaw.channels.feishu.domain",
                "lark".equalsIgnoreCase(domain) ? "lark" : "feishu");
        gatewayRuntimeRefreshService.refreshNow();
    }

    private String appendFeishuQrSource(String qrUrl) {
        String value = StrUtil.nullToEmpty(qrUrl).trim();
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String separator = value.contains("?") ? "&" : "?";
        return value + separator + "from=solonclaw&tp=solonclaw";
    }

    private ONode postJson(String url, Map<String, String> payload) {
        assertSafeUrl(url, "扫码注册请求地址");
        HttpResponse response =
                HttpRequest.post(url)
                        .contentType(ContentType.JSON.toString())
                        .body(ONode.serialize(payload))
                        .timeout(35_000)
                        .execute();
        try {
            return ONode.ofJson(
                    BoundedAttachmentIO.readHutoolText(
                            response, BoundedAttachmentIO.JSON_MAX_BYTES));
        } finally {
            response.close();
        }
    }

    private ONode postForm(String url, Map<String, String> payload) {
        assertSafeUrl(url, "扫码注册请求地址");
        String body = formEncode(payload);
        HttpResponse response =
                HttpRequest.post(url)
                        .contentType(ContentType.FORM_URLENCODED.toString())
                        .body(body)
                        .timeout(35_000)
                        .execute();
        try {
            return ONode.ofJson(
                    BoundedAttachmentIO.readHutoolText(
                            response, BoundedAttachmentIO.JSON_MAX_BYTES));
        } finally {
            response.close();
        }
    }

    private void ensureDingTalkOk(ONode node, String step) {
        int errcode = node.get("errcode").getInt();
        if (errcode != 0) {
            throw new IllegalStateException(
                    "钉钉扫码注册 "
                            + step
                            + " 失败："
                            + StrUtil.blankToDefault(
                                    node.get("errmsg").getString(), "errcode=" + errcode));
        }
    }

    private void waitForQrUrl(TicketState state) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (StrUtil.isNotBlank(state.qrUrl) || "failed".equals(state.status)) {
                return;
            }
            sleepMillis(50L);
        }
    }

    private void mark(TicketState state, String status, String message) {
        state.status = status;
        state.message = message;
        state.updatedAt = System.currentTimeMillis();
    }

    private void fail(TicketState state, String code, String message) {
        String safe = safeText(message);
        state.status = "failed";
        state.errorCode = code;
        state.errorMessage = safe;
        state.message = safe;
        state.updatedAt = System.currentTimeMillis();
    }

    private Map<String, Object> toMap(TicketState state) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ticket", state.ticket);
        result.put("platform", state.platform);
        result.put("status", state.status);
        result.put("message", state.message);
        result.put("error_code", state.errorCode);
        result.put("error_message", state.errorMessage);
        result.put("device_code", state.deviceCode);
        result.put("qr_url", state.qrUrl);
        result.put("created_at", isoTime(state.createdAt));
        result.put("updated_at", isoTime(state.updatedAt));
        result.put("expires_at", isoTime(state.expiresAt));
        result.put("client_id", state.clientId);
        result.put("app_id", state.appId);
        result.put("open_id", state.openId);
        result.put("domain", state.domain);
        return result;
    }

    private String normalizePlatform(String platform) {
        String value = StrUtil.nullToEmpty(platform).trim().toLowerCase();
        if (PLATFORM_DINGTALK.equals(value) || PLATFORM_FEISHU.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("不支持的扫码平台：" + platform);
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = StrUtil.nullToEmpty(baseUrl).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private Map<String, String> mapOf(String... values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    private String formEncode(Map<String, String> payload) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            try {
                parts.add(
                        URLEncoder.encode(entry.getKey(), "UTF-8")
                                + "="
                                + URLEncoder.encode(
                                        StrUtil.nullToEmpty(entry.getValue()), "UTF-8"));
            } catch (Exception e) {
                throw new IllegalStateException("表单编码失败", e);
            }
        }
        return String.join("&", parts);
    }

    private boolean contains(Object data, String expected) {
        if (data instanceof Iterable) {
            for (Object item : (Iterable<?>) data) {
                if (expected.equals(String.valueOf(item))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        String safe = StrUtil.isBlank(message) ? e.getClass().getSimpleName() : message.trim();
        return safeText(safe);
    }

    private String safeText(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000);
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

    private static class TicketState {
        private String ticket;
        private String platform;
        private String status;
        private String message;
        private String errorCode;
        private String errorMessage;
        private String deviceCode;
        private String qrUrl;
        private long createdAt;
        private long updatedAt;
        private long expiresAt;
        private String clientId;
        private String appId;
        private String openId;
        private String domain;
    }
}
