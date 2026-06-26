package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.support.BaseUrlSupport;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import org.noear.snack4.ONode;

/** 国内渠道扫码注册服务。 */
public class DomesticQrSetupService {
    /** 平台钉钉的统一常量值。 */
    private static final String PLATFORM_DINGTALK = "dingtalk";

    /** 国内渠道二维码接口时间格式，保持原有本地时区偏移输出。 */
    private static final DateTimeFormatter ISO_OFFSET_SECONDS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.systemDefault());

    /** 平台飞书的统一常量值。 */
    private static final String PLATFORM_FEISHU = "feishu";

    /** 默认钉钉基础URL的统一常量值。 */
    private static final String DEFAULT_DINGTALK_BASE_URL = "https://oapi.dingtalk.com";

    /** 默认飞书ACCOUNTS基础URL的统一常量值。 */
    private static final String DEFAULT_FEISHU_ACCOUNTS_BASE_URL = "https://accounts.feishu.cn";

    /** 默认LARKACCOUNTS基础URL的统一常量值。 */
    private static final String DEFAULT_LARK_ACCOUNTS_BASE_URL = "https://accounts.larksuite.com";

    /** 飞书REGISTRATION路径的统一常量值。 */
    private static final String FEISHU_REGISTRATION_PATH = "/oauth/v1/app/registration";

    /** 默认超时毫秒数的统一常量值。 */
    private static final long DEFAULT_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    /** 注入应用配置，用于国内二维码配置引导。 */
    private final AppConfig appConfig;

    /** 注入配置服务，用于调用对应业务能力。 */
    private final DashboardConfigService configService;

    /** 注入消息网关工作区配置刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /** 记录国内二维码配置引导中的配置Resolver。 */
    private final RuntimeConfigResolver configResolver;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 保存执行器执行组件，负责调度异步或定时任务。 */
    private final ExecutorService executor =
            BoundedExecutorFactory.fixed("domestic-qr-setup", 2, 32);

    /** 保存tickets映射，便于按键快速查询。 */
    private final ConcurrentMap<String, TicketState> tickets =
            new ConcurrentHashMap<String, TicketState>();

    /**
     * 创建国内二维码配置引导服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param configService 配置Service配置对象。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public DomesticQrSetupService(
            AppConfig appConfig,
            DashboardConfigService configService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(appConfig, configService, gatewayRuntimeRefreshService, null);
    }

    /**
     * 创建国内二维码配置引导服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param configService 配置Service配置对象。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
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

    /**
     * 启动当前组件并准备运行资源。
     *
     * @param platform 平台参数。
     * @return 返回start结果。
     */
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
                    /** 执行异步任务主体。 */
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

    /**
     * 获取当前注册项或配置项。
     *
     * @param ticket ticket 参数。
     * @return 返回get结果。
     */
    public Map<String, Object> get(String ticket) {
        TicketState state = tickets.get(ticket);
        if (state == null) {
            throw new IllegalStateException("QR ticket not found: " + ticket);
        }
        return toMap(state);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 运行Ding Talk。
     *
     * @param state 状态参数。
     */
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

    /**
     * 运行飞书。
     *
     * @param state 状态参数。
     */
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

            long expireSeconds = Math.max(1L, begin.get("expires_in").getLong());
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

    /**
     * 执行飞书Accounts基础URL相关逻辑。
     *
     * @param domain domain 参数。
     * @return 返回飞书Accounts Base URL结果。
     */
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

    /**
     * 执行persist飞书相关逻辑。
     *
     * @param appId 应用标识。
     * @param appSecret 应用密钥参数。
     * @param openId open标识。
     * @param domain domain 参数。
     */
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

    /**
     * 追加飞书二维码来源。
     *
     * @param qrUrl 待校验或访问的地址参数。
     * @return 返回飞书二维码来源结果。
     */
    private String appendFeishuQrSource(String qrUrl) {
        String value = StrUtil.nullToEmpty(qrUrl).trim();
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String separator = value.contains("?") ? "&" : "?";
        return value + separator + "from=solonclaw&tp=solonclaw";
    }

    /**
     * 执行postJSON相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param payload 待签名或解析的载荷内容。
     * @return 返回post JSON结果。
     */
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

    /**
     * 执行postForm相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param payload 待签名或解析的载荷内容。
     * @return 返回post Form结果。
     */
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

    /**
     * 确保Ding Talk Ok。
     *
     * @param node 节点参数。
     * @param step step 参数。
     */
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

    /**
     * 执行waitFor二维码URL相关逻辑。
     *
     * @param state 状态参数。
     */
    private void waitForQrUrl(TicketState state) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (StrUtil.isNotBlank(state.qrUrl) || "failed".equals(state.status)) {
                return;
            }
            sleepMillis(50L);
        }
    }

    /**
     * 执行mark相关逻辑。
     *
     * @param state 状态参数。
     * @param status 状态参数。
     * @param message 平台消息或错误消息。
     */
    private void mark(TicketState state, String status, String message) {
        state.status = status;
        state.message = message;
        state.updatedAt = System.currentTimeMillis();
    }

    /**
     * 构造失败结果并携带安全错误信息。
     *
     * @param state 状态参数。
     * @param code code 参数。
     * @param message 平台消息或错误消息。
     */
    private void fail(TicketState state, String code, String message) {
        String safe = safeText(message);
        state.status = "failed";
        state.errorCode = code;
        state.errorMessage = safe;
        state.message = safe;
        state.updatedAt = System.currentTimeMillis();
    }

    /**
     * 转换为Map。
     *
     * @param state 状态参数。
     * @return 返回转换后的Map。
     */
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

    /**
     * 规范化平台。
     *
     * @param platform 平台参数。
     * @return 返回平台结果。
     */
    private String normalizePlatform(String platform) {
        String value = StrUtil.nullToEmpty(platform).trim().toLowerCase();
        if (PLATFORM_DINGTALK.equals(value) || PLATFORM_FEISHU.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("不支持的扫码平台：" + platform);
    }

    /** 规范化基础 URL，避免后续拼接路径时出现重复斜杠。 */
    private String normalizeBaseUrl(String baseUrl) {
        return BaseUrlSupport.stripTrailingSlashes(baseUrl);
    }

    /**
     * 执行mapOf相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回map Of结果。
     */
    private Map<String, String> mapOf(String... values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    /**
     * 执行formEncode相关逻辑。
     *
     * @param payload 待签名或解析的载荷内容。
     * @return 返回form Encode结果。
     */
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

    /**
     * 执行contains相关逻辑。
     *
     * @param data 数据参数。
     * @param expected expected 参数。
     * @return 返回contains结果。
     */
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

    /**
     * 执行sleepMillis相关逻辑。
     *
     * @param millis millis 参数。
     */
    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行iso时间相关逻辑。
     *
     * @param epochMillis epochMillis 参数。
     * @return 返回iso时间结果。
     */
    private String isoTime(long epochMillis) {
        if (epochMillis <= 0) {
            return null;
        }
        return ISO_OFFSET_SECONDS_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * 生成安全展示用的消息。
     *
     * @param e 捕获到的异常。
     * @return 返回safe消息结果。
     */
    private String safeMessage(Exception e) {
        String message = e.getMessage();
        String safe = StrUtil.isBlank(message) ? e.getClass().getSimpleName() : message.trim();
        return safeText(safe);
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Text结果。
     */
    private String safeText(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 1000);
    }

    /**
     * 执行assert安全基础URL相关逻辑。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param purpose purpose 参数。
     */
    private void assertSafeBaseUrl(String baseUrl, String purpose) {
        assertSafeUrl(normalizeBaseUrl(baseUrl), purpose);
    }

    /**
     * 执行assert安全URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param purpose purpose 参数。
     */
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

    /** 表示Ticket数据，在服务、仓储和接口之间传递。 */
    private static class TicketState {
        /** 记录Ticket中的ticket。 */
        private String ticket;

        /** 记录Ticket中的平台。 */
        private String platform;

        /** 记录Ticket中的状态。 */
        private String status;

        /** 记录Ticket中的消息。 */
        private String message;

        /** 记录Ticket中的错误Code。 */
        private String errorCode;

        /** 记录Ticket中的错误消息。 */
        private String errorMessage;

        /** 记录Ticket中的deviceCode。 */
        private String deviceCode;

        /** 记录Ticket中的二维码URL。 */
        private String qrUrl;

        /** 记录Ticket中的创建时间。 */
        private long createdAt;

        /** 记录Ticket中的更新时间。 */
        private long updatedAt;

        /** 记录Ticket中的expires时间。 */
        private long expiresAt;

        /** 记录Ticket中的client标识。 */
        private String clientId;

        /** 记录Ticket中的应用标识。 */
        private String appId;

        /** 记录Ticket中的open标识。 */
        private String openId;

        /** 记录Ticket中的domain。 */
        private String domain;
    }
}
