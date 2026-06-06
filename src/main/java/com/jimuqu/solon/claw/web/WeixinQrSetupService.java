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
    /** 默认基础URL的统一常量值。 */
    private static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    /** GET机器人二维码ENDPO整型的统一常量值。 */
    private static final String GET_BOT_QR_ENDPOINT = "ilink/bot/get_bot_qrcode?bot_type=3";

    /** GET二维码状态ENDPO整型的统一常量值。 */
    private static final String GET_QR_STATUS_ENDPOINT = "ilink/bot/get_qrcode_status?qrcode=%s";

    /** LOG整型IMEOUTMILLIS的统一常量值。 */
    private static final long LOGIN_TIMEOUT_MILLIS = 8L * 60L * 1000L;

    /** 最大刷新次数的统一常量值。 */
    private static final int MAX_REFRESH_COUNT = 3;

    /** 最大HTTPREDIRECTS的统一常量值。 */
    private static final int MAX_HTTP_REDIRECTS = 5;

    /** 注入应用配置，用于微信二维码配置引导。 */
    private final AppConfig appConfig;

    /** 注入配置服务，用于调用对应业务能力。 */
    private final DashboardConfigService configService;

    /** 注入消息网关运行时刷新服务，用于调用对应业务能力。 */
    private final com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
            gatewayRuntimeRefreshService;

    /** 记录微信二维码配置引导中的配置Resolver。 */
    private final RuntimeConfigResolver configResolver;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 保存执行器执行组件，负责调度异步或定时任务。 */
    private final ExecutorService executor = BoundedExecutorFactory.fixed("weixin-qr-setup", 2, 32);

    /** 保存tickets映射，便于按键快速查询。 */
    private final ConcurrentMap<String, TicketState> tickets =
            new ConcurrentHashMap<String, TicketState>();

    /**
     * 创建微信二维码配置引导服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param configService 配置Service配置对象。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     */
    public WeixinQrSetupService(
            AppConfig appConfig,
            DashboardConfigService configService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService) {
        this(appConfig, configService, gatewayRuntimeRefreshService, null);
    }

    /**
     * 创建微信二维码配置引导服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param configService 配置Service配置对象。
     * @param gatewayRuntimeRefreshService 网关运行时Refresh服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
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

    /**
     * 启动当前组件并准备运行资源。
     *
     * @return 返回start结果。
     */
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
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        runFlow(state);
                    }
                });
        return toMap(state);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        executor.shutdownNow();
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
            throw new IllegalStateException("Weixin QR ticket not found: " + ticket);
        }
        return toMap(state);
    }

    /**
     * 运行流程。
     *
     * @param state 状态参数。
     */
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

    /**
     * 拉取二维码。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回fetch二维码结果。
     */
    private ONode fetchQr(String baseUrl) {
        String body = executeJsonGet(normalizeBaseUrl(baseUrl) + "/" + GET_BOT_QR_ENDPOINT, 0);
        return ONode.ofJson(body);
    }

    /**
     * 拉取状态。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param qrCode qrCode 参数。
     * @return 返回fetch状态。
     */
    private ONode fetchStatus(String baseUrl, String qrCode) {
        String body =
                executeJsonGet(
                        normalizeBaseUrl(baseUrl)
                                + "/"
                                + String.format(GET_QR_STATUS_ENDPOINT, qrCode),
                        0);
        return ONode.ofJson(body);
    }

    /**
     * 执行JSON Get。
     *
     * @param url 待校验或访问的 URL。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回JSON Get结果。
     */
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

    /**
     * 更新二维码状态。
     *
     * @param state 状态参数。
     * @param qrResponse qr响应响应或执行结果。
     * @return 返回二维码状态。
     */
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

    /**
     * 执行persistConfirmed凭据相关逻辑。
     *
     * @param statusResponse 状态响应响应或执行结果。
     */
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
        result.put("status", state.status);
        result.put("message", state.message);
        result.put("error_code", state.errorCode);
        result.put("error_message", state.errorMessage);
        result.put("qrcode", state.qrCode);
        result.put("qrcode_url", state.qrImageUrl);
        result.put("created_at", isoTime(state.createdAt));
        result.put("updated_at", isoTime(state.updatedAt));
        result.put("expires_at", isoTime(state.expiresAt));
        result.put("account_id", state.accountId);
        result.put("user_id", state.userId);
        result.put("base_url", state.baseUrl);
        return result;
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

    /**
     * 规范化Base URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @return 返回Base URL结果。
     */
    private String normalizeBaseUrl(String baseUrl) {
        String value = StrUtil.nullToEmpty(baseUrl).trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 判断是否Redirect。
     *
     * @param status 状态参数。
     * @return 如果Redirect满足条件则返回 true，否则返回 false。
     */
    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 解析Redirect URL。
     *
     * @param baseUrl 待校验或访问的地址参数。
     * @param location location 参数。
     * @return 返回解析后的Redirect URL。
     */
    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            return URI.create(baseUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "微信 iLink 请求重定向地址无效：" + SecretRedactor.maskUrl(location), e);
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
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
    }

    /** 表示Ticket数据，在服务、仓储和接口之间传递。 */
    private static class TicketState {
        /** 记录Ticket中的ticket。 */
        private String ticket;

        /** 记录Ticket中的状态。 */
        private String status;

        /** 记录Ticket中的消息。 */
        private String message;

        /** 记录Ticket中的错误Code。 */
        private String errorCode;

        /** 记录Ticket中的错误消息。 */
        private String errorMessage;

        /** 记录Ticket中的二维码Code。 */
        private String qrCode;

        /** 记录Ticket中的二维码图片URL。 */
        private String qrImageUrl;

        /** 记录Ticket中的创建时间。 */
        private long createdAt;

        /** 记录Ticket中的更新时间。 */
        private long updatedAt;

        /** 记录Ticket中的expires时间。 */
        private long expiresAt;

        /** 记录Ticket中的account标识。 */
        private String accountId;

        /** 记录Ticket中的用户标识。 */
        private String userId;

        /** 记录Ticket中的基础URL。 */
        private String baseUrl;
    }
}
