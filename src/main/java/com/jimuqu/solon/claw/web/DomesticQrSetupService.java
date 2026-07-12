package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.gateway.service.ProfileMultiplexRuntimeManager;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.support.BaseUrlSupport;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.noear.snack4.ONode;

/** 国内渠道扫码注册服务。 */
public class DomesticQrSetupService {
    /** 平台钉钉的统一常量值。 */
    private static final String PLATFORM_DINGTALK = "dingtalk";

    /** 平台飞书的统一常量值。 */
    private static final String PLATFORM_FEISHU = "feishu";

    /** 平台企微的统一常量值。 */
    private static final String PLATFORM_WECOM = "wecom";

    /** QQBot 扫码配置的平台标识。 */
    private static final String PLATFORM_QQBOT = "qqbot";

    /** 默认钉钉基础URL的统一常量值。 */
    private static final String DEFAULT_DINGTALK_BASE_URL = "https://oapi.dingtalk.com";

    /** 默认飞书ACCOUNTS基础URL的统一常量值。 */
    private static final String DEFAULT_FEISHU_ACCOUNTS_BASE_URL = "https://accounts.feishu.cn";

    /** 默认LARKACCOUNTS基础URL的统一常量值。 */
    private static final String DEFAULT_LARK_ACCOUNTS_BASE_URL = "https://accounts.larksuite.com";

    /** 飞书REGISTRATION路径的统一常量值。 */
    private static final String FEISHU_REGISTRATION_PATH = "/oauth/v1/app/registration";

    /** 企微扫码生成接口路径。 */
    private static final String WECOM_QR_GENERATE_PATH = "/ai/qc/generate?source=solonclaw";

    /** 企微扫码结果查询接口路径。 */
    private static final String WECOM_QR_QUERY_PATH = "/ai/qc/query_result?scode=";

    /** 企微扫码服务默认基础地址。 */
    private static final String DEFAULT_WECOM_QR_BASE_URL = "https://work.weixin.qq.com";

    /** QQBot 扫码绑定服务默认基础地址。 */
    private static final String DEFAULT_QQBOT_QR_BASE_URL = "https://q.qq.com";

    /** QQBot 创建扫码绑定任务的接口路径。 */
    private static final String QQBOT_QR_CREATE_PATH = "/lite/create_bind_task";

    /** QQBot 查询扫码绑定结果的接口路径。 */
    private static final String QQBOT_QR_POLL_PATH = "/lite/poll_bind_result";

    /** QQBot 扫码绑定页面路径。 */
    private static final String QQBOT_QR_CONNECT_PATH = "/qqbot/" + "open" + "claw/connect.html";

    /** 默认超时毫秒数的统一常量值。 */
    private static final long DEFAULT_TIMEOUT_MILLIS = 10L * 60L * 1000L;

    /** 终态票据保留时间，既允许前端读取结果，也避免常驻进程无限积累。 */
    private static final long TERMINAL_RETENTION_MILLIS = 10L * 60L * 1000L;

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

    /** Dashboard Profile 解析器；未注入时只操作当前运行 Profile。 */
    private final DashboardProfileContext profileContext;

    /** 多 Profile 网关运行时，用于让非当前 Profile 立即消费扫码配置。 */
    private final ProfileMultiplexRuntimeManager profileRuntimeManager;

    /** Profile 进程管理器，用于重启目标的独立网关。 */
    private final ProfileManager profileManager;

    /** 保存执行器执行组件，负责调度异步或定时任务。 */
    private final ExecutorService executor =
            BoundedExecutorFactory.fixed("domestic-qr-setup", 4, 8);

    /** 保存tickets映射，便于按键快速查询。 */
    private final ConcurrentMap<String, TicketState> tickets =
            new ConcurrentHashMap<String, TicketState>();

    /** 每个 Profile 与平台当前唯一活动任务。 */
    private final ConcurrentMap<String, TicketState> activeTickets =
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
        this(appConfig, configService, gatewayRuntimeRefreshService, null, null, null, null);
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
        this(
                appConfig,
                configService,
                gatewayRuntimeRefreshService,
                securityPolicyService,
                null,
                null,
                null);
    }

    /** 创建支持显式 Profile 绑定的国内渠道扫码服务。 */
    public DomesticQrSetupService(
            AppConfig appConfig,
            DashboardConfigService configService,
            com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService
                    gatewayRuntimeRefreshService,
            SecurityPolicyService securityPolicyService,
            DashboardProfileContext profileContext,
            ProfileMultiplexRuntimeManager profileRuntimeManager,
            ProfileManager profileManager) {
        this.appConfig = appConfig;
        this.configService = configService;
        this.gatewayRuntimeRefreshService = gatewayRuntimeRefreshService;
        this.configResolver = RuntimeConfigResolver.initialize(appConfig.getRuntime().getHome());
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
        this.profileContext = profileContext;
        this.profileRuntimeManager = profileRuntimeManager;
        this.profileManager = profileManager;
    }

    /**
     * 启动当前组件并准备运行资源。
     *
     * @param platform 平台参数。
     * @return 返回start结果。
     */
    public Map<String, Object> start(String platform) {
        return start(platform, null);
    }

    /** 启动并绑定到明确的 Profile，避免扫码完成后写入当前选择之外的配置。 */
    public Map<String, Object> start(String platform, String profile) {
        final String normalized = normalizePlatform(platform);
        cleanupTickets();
        final TicketState state = new TicketState(DEFAULT_TIMEOUT_MILLIS);
        state.platform = normalized;
        bindProfile(state, profile);
        String activeKey = state.profile + ":" + normalized;
        TicketState previous = activeTickets.put(activeKey, state);
        if (previous != null) {
            previous.cancel();
            ((ThreadPoolExecutor) executor).purge();
        }
        tickets.put(state.ticket, state);
        state.future =
                executor.submit(
                        new Runnable() {
                            /** 执行异步任务主体。 */
                            @Override
                            public void run() {
                                if (PLATFORM_DINGTALK.equals(normalized)) {
                                    runDingTalk(state);
                                } else if (PLATFORM_WECOM.equals(normalized)) {
                                    runWecom(state);
                                } else if (PLATFORM_QQBOT.equals(normalized)) {
                                    runQqbot(state);
                                } else {
                                    runFeishu(state);
                                }
                                activeTickets.remove(activeKey, state);
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
        return get(ticket, null);
    }

    /** 查询时校验 Profile 与任务创建作用域一致，避免切换管理目标后串读旧状态。 */
    public Map<String, Object> get(String ticket, String profile) {
        cleanupTickets();
        TicketState state = tickets.get(ticket);
        if (state == null) {
            throw new IllegalStateException("QR ticket not found: " + ticket);
        }
        if (profileContext != null
                && !state.profile.equals(profileContext.resolve(profile).getName())) {
            throw new IllegalStateException("QR ticket does not belong to the selected profile.");
        }
        return toMap(state);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        for (TicketState state : activeTickets.values()) {
            state.cancel();
        }
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
                                    state.config.getChannels().getDingtalk().getBaseUrl(),
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
            state.mark("pending", "请使用钉钉扫码授权");

            long deadline =
                    Math.min(
                            state.expiresAt,
                            System.currentTimeMillis()
                                    + Math.max(1L, begin.get("expires_in").getLong()) * 1000L);
            long intervalMillis = Math.max(2L, begin.get("interval").getLong()) * 1000L;
            while (state.active() && System.currentTimeMillis() < deadline) {
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
                    if (!state.active()) {
                        return;
                    }
                    persistDingTalk(state, clientId, clientSecret);
                    state.clientId = clientId;
                    state.mark("confirmed", "钉钉连接成功");
                    return;
                }
                if ("FAIL".equals(status) || "EXPIRED".equals(status)) {
                    state.fail("qr_failed", "钉钉扫码授权失败：" + status);
                    return;
                }
                if (!"WAITING".equals(status)) {
                    state.fail("qr_failed", "钉钉扫码授权返回未知状态：" + status);
                    return;
                }
                state.mark("pending", "等待钉钉扫码授权");
                sleepMillis(intervalMillis);
            }
            if (!state.active()) {
                return;
            }
            state.fail("qr_timeout", "钉钉扫码登录超时。");
        } catch (Exception e) {
            if (!state.active()) {
                return;
            }
            state.fail("qr_failed", ErrorTextSupport.safeError(e));
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
            String baseUrl = feishuAccountsBaseUrl(state, domain);
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
            state.mark("pending", "请使用飞书扫码授权");

            long expireSeconds = Math.max(1L, begin.get("expires_in").getLong());
            long deadline =
                    Math.min(state.expiresAt, System.currentTimeMillis() + expireSeconds * 1000L);
            long intervalMillis = Math.max(1L, begin.get("interval").getLong()) * 1000L;
            while (state.active() && System.currentTimeMillis() < deadline) {
                ONode poll =
                        postForm(
                                feishuAccountsBaseUrl(state, domain) + FEISHU_REGISTRATION_PATH,
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
                    if (!state.active()) {
                        return;
                    }
                    persistFeishu(state, appId, appSecret, openId, domain);
                    state.appId = appId;
                    state.openId = openId;
                    state.domain = domain;
                    state.mark("confirmed", "飞书连接成功");
                    return;
                }
                String error = poll.get("error").getString();
                if ("access_denied".equals(error) || "expired_token".equals(error)) {
                    state.fail("qr_failed", "飞书扫码授权失败：" + error);
                    return;
                }
                state.mark("pending", "等待飞书扫码授权");
                sleepMillis(intervalMillis);
            }
            if (!state.active()) {
                return;
            }
            state.fail("qr_timeout", "飞书扫码登录超时。");
        } catch (Exception e) {
            if (!state.active()) {
                return;
            }
            state.fail("qr_failed", ErrorTextSupport.safeError(e));
        }
    }

    /** 运行企微扫码注册，成功后持久化机器人 ID 与密钥。 */
    private void runWecom(TicketState state) {
        try {
            String baseUrl =
                    normalizeBaseUrl(
                            StrUtil.blankToDefault(
                                    state.config.getChannels().getWecom().getBaseUrl(),
                                    DEFAULT_WECOM_QR_BASE_URL));
            ONode generated = getJson(baseUrl + WECOM_QR_GENERATE_PATH);
            ONode data = generated.get("data");
            String scode = data.get("scode").getString();
            String authUrl = data.get("auth_url").getString();
            if (StrUtil.isBlank(scode) || StrUtil.isBlank(authUrl)) {
                throw new IllegalStateException("企微扫码注册响应缺少二维码信息");
            }
            state.deviceCode = scode;
            state.qrUrl = authUrl;
            state.mark("pending", "请使用企业微信扫码授权");

            while (state.active() && System.currentTimeMillis() < state.expiresAt) {
                ONode result = getJson(baseUrl + WECOM_QR_QUERY_PATH + urlEncode(scode));
                ONode resultData = result.get("data");
                String status = StrUtil.nullToEmpty(resultData.get("status").getString()).trim();
                if ("success".equalsIgnoreCase(status)) {
                    ONode botInfo = resultData.get("bot_info");
                    String botId =
                            StrUtil.blankToDefault(
                                    botInfo.get("botid").getString(),
                                    botInfo.get("bot_id").getString());
                    String secret = botInfo.get("secret").getString();
                    if (StrUtil.isBlank(botId) || StrUtil.isBlank(secret)) {
                        throw new IllegalStateException("企微扫码成功，但返回的机器人凭证不完整。");
                    }
                    if (!state.active()) {
                        return;
                    }
                    persistWecom(state, botId, secret);
                    state.botId = botId;
                    state.mark("confirmed", "企业微信连接成功");
                    return;
                }
                state.mark("pending", "等待企业微信扫码授权");
                sleepMillis(3000L);
            }
            if (!state.active()) {
                return;
            }
            state.fail("qr_timeout", "企业微信扫码登录超时。");
        } catch (Exception e) {
            if (!state.active()) {
                return;
            }
            state.fail("qr_failed", ErrorTextSupport.safeError(e));
        }
    }

    /** 运行 QQBot 扫码创建、轮询与本地密钥解密流程。 */
    private void runQqbot(TicketState state) {
        try {
            String baseUrl =
                    normalizeBaseUrl(
                            StrUtil.blankToDefault(
                                    state.config.getChannels().getQqbot().getBaseUrl(),
                                    DEFAULT_QQBOT_QR_BASE_URL));
            assertSafeBaseUrl(baseUrl, "QQBot 扫码绑定地址");
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String encodedKey = Base64.getEncoder().encodeToString(key);
            ONode created = postJson(baseUrl + QQBOT_QR_CREATE_PATH, mapOf("key", encodedKey));
            ensureQqbotOk(created, "create");
            String taskId = created.get("data").get("task_id").getString();
            if (StrUtil.isBlank(taskId)) {
                throw new IllegalStateException("QQBot 扫码绑定响应缺少 task_id");
            }
            state.deviceCode = taskId;
            state.qrUrl =
                    DEFAULT_QQBOT_QR_BASE_URL
                            + QQBOT_QR_CONNECT_PATH
                            + "?task_id="
                            + urlEncode(taskId)
                            + "&_wv=2&source=solonclaw";
            state.mark("pending", "请使用 QQ 扫码添加机器人");

            while (state.active() && System.currentTimeMillis() < state.expiresAt) {
                ONode polled = postJson(baseUrl + QQBOT_QR_POLL_PATH, mapOf("task_id", taskId));
                ensureQqbotOk(polled, "poll");
                ONode data = polled.get("data");
                int status = data.get("status").getInt();
                if (status == 2) {
                    String appId = data.get("bot_appid").getString();
                    String encryptedSecret = data.get("bot_encrypt_secret").getString();
                    String userOpenId = data.get("user_openid").getString();
                    if (StrUtil.isBlank(appId) || StrUtil.isBlank(encryptedSecret)) {
                        throw new IllegalStateException("QQBot 扫码成功，但返回的机器人凭证不完整。");
                    }
                    String clientSecret = decryptQqbotSecret(encryptedSecret, key);
                    if (!state.active()) {
                        return;
                    }
                    persistQqbot(state, appId, clientSecret, userOpenId);
                    state.appId = appId;
                    state.userOpenId = userOpenId;
                    state.mark("confirmed", "QQBot 连接成功");
                    return;
                }
                if (status == 3) {
                    state.fail("qr_expired", "QQBot 二维码已过期，请重新获取。");
                    return;
                }
                if (status != 0 && status != 1) {
                    state.fail("qr_failed", "QQBot 扫码绑定返回未知状态：" + status);
                    return;
                }
                state.mark("pending", "等待 QQ 扫码添加机器人");
                sleepMillis(2000L);
            }
            if (!state.active()) {
                return;
            }
            state.fail("qr_timeout", "QQBot 扫码绑定超时。");
        } catch (Exception e) {
            if (!state.active()) {
                return;
            }
            state.fail("qr_failed", ErrorTextSupport.safeError(e));
        }
    }

    /**
     * 执行飞书Accounts基础URL相关逻辑。
     *
     * @param domain domain 参数。
     * @return 返回飞书Accounts Base URL结果。
     */
    private String feishuAccountsBaseUrl(TicketState state, String domain) {
        if ("lark".equalsIgnoreCase(domain)) {
            return DEFAULT_LARK_ACCOUNTS_BASE_URL;
        }
        return normalizeBaseUrl(
                StrUtil.blankToDefault(
                        state.config.getChannels().getFeishu().getBaseUrl(),
                        DEFAULT_FEISHU_ACCOUNTS_BASE_URL));
    }

    /** 持久化钉钉扫码授权结果，并把机器人编码默认写为客户端 ID 以兼容钉钉扫码返回字段。 */
    private void persistDingTalk(TicketState state, String clientId, String clientSecret) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("channels.dingtalk.enabled", Boolean.TRUE);
        persist(
                state,
                updates,
                "solonclaw.channels.dingtalk.clientId",
                clientId,
                "solonclaw.channels.dingtalk.clientSecret",
                clientSecret,
                "solonclaw.channels.dingtalk.robotCode",
                clientId);
    }

    /**
     * 执行persist飞书相关逻辑。
     *
     * @param appId 应用标识。
     * @param appSecret 应用密钥参数。
     * @param openId open标识。
     * @param domain domain 参数。
     */
    private void persistFeishu(
            TicketState state, String appId, String appSecret, String openId, String domain) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("channels.feishu.enabled", Boolean.TRUE);
        if (StrUtil.isNotBlank(openId)) {
            updates.put("channels.feishu.groupAllowedUsers", Arrays.asList(openId.trim()));
        }
        persist(
                state,
                updates,
                "solonclaw.channels.feishu.appId",
                appId,
                "solonclaw.channels.feishu.appSecret",
                appSecret,
                "solonclaw.channels.feishu.domain",
                "lark".equalsIgnoreCase(domain) ? "lark" : "feishu");
    }

    /** 持久化企微扫码授权结果并立即刷新该渠道连接。 */
    private void persistWecom(TicketState state, String botId, String secret) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("channels.wecom.enabled", Boolean.TRUE);
        persist(
                state,
                updates,
                "solonclaw.channels.wecom.botId",
                botId,
                "solonclaw.channels.wecom.secret",
                secret);
    }

    /** 持久化 QQBot 凭证，并让扫码者在默认配对策略下可直接访问。 */
    private void persistQqbot(
            TicketState state, String appId, String clientSecret, String userOpenId) {
        Map<String, Object> updates = new LinkedHashMap<String, Object>();
        updates.put("channels.qqbot.enabled", Boolean.TRUE);
        updates.put("channels.qqbot.allowAllUsers", Boolean.FALSE);
        updates.put("channels.qqbot.dmPolicy", "pairing");
        if (StrUtil.isNotBlank(userOpenId)) {
            updates.put("channels.qqbot.allowedUsers", Arrays.asList(userOpenId.trim()));
        }
        persist(
                state,
                updates,
                "solonclaw.channels.qqbot.appId",
                appId,
                "solonclaw.channels.qqbot.clientSecret",
                clientSecret);
    }

    /** 一次性写入目标 Profile 的普通项和凭据，再刷新承载该 Profile 的网关。 */
    private void persist(TicketState state, Map<String, Object> updates, String... secrets) {
        synchronized (state) {
            if (!state.active()) {
                return;
            }
            configService.savePartialFlat(updates, false, state.profile);
            RuntimeConfigResolver resolver =
                    state.current ? configResolver : RuntimeConfigResolver.open(state.home);
            for (int i = 0; i + 1 < secrets.length; i += 2) {
                resolver.setFileValue(secrets[i], secrets[i + 1]);
            }
            if (state.current) {
                gatewayRuntimeRefreshService.refreshNow();
            } else {
                refreshProfileGateway(state.profile);
            }
        }
    }

    /** 刷新目标 Profile 实际使用的独立进程或复用运行时。 */
    private void refreshProfileGateway(String profile) {
        try {
            if (profileManager != null && profileManager.gatewayStatus(profile).isRunning()) {
                profileManager.stopGateway(profile);
                profileManager.startGateway(profile, java.util.Collections.<String>emptyList());
            } else if (profileRuntimeManager != null) {
                profileRuntimeManager.reload();
            }
        } catch (Exception e) {
            throw new IllegalStateException("目标 Profile 网关刷新失败", e);
        }
    }

    /** 使用扫码任务本地密钥解密 QQBot 返回的 AES-256-GCM 密文。 */
    private String decryptQqbotSecret(String encryptedBase64, byte[] key) {
        try {
            byte[] raw = Base64.getDecoder().decode(encryptedBase64);
            if (key == null || key.length != 32 || raw.length < 29) {
                throw new IllegalArgumentException("QQBot 加密凭证格式无效");
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, 12);
            byte[] ciphertextAndTag = Arrays.copyOfRange(raw, 12, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertextAndTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("QQBot 客户端密钥解密失败", e);
        }
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
                        .header("Accept", ContentType.JSON.toString())
                        .header("User-Agent", "SolonClaw-QQBot/1.0")
                        .body(ONode.serialize(payload))
                        .timeout(35_000)
                        .setFollowRedirects(false)
                        .execute();
        try {
            ensureHttpSuccess(response, url);
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
                        .setFollowRedirects(false)
                        .execute();
        try {
            ensureHttpSuccess(response, url);
            return ONode.ofJson(
                    BoundedAttachmentIO.readHutoolText(
                            response, BoundedAttachmentIO.JSON_MAX_BYTES));
        } finally {
            response.close();
        }
    }

    /** 执行扫码服务 GET 请求并限制响应体大小。 */
    private ONode getJson(String url) {
        assertSafeUrl(url, "扫码注册请求地址");
        HttpResponse response =
                HttpRequest.get(url).timeout(35_000).setFollowRedirects(false).execute();
        try {
            ensureHttpSuccess(response, url);
            return ONode.ofJson(
                    BoundedAttachmentIO.readHutoolText(
                            response, BoundedAttachmentIO.JSON_MAX_BYTES));
        } finally {
            response.close();
        }
    }

    /** 校验扫码服务 HTTP 状态，不把可能包含凭证的响应正文带入异常。 */
    private void ensureHttpSuccess(HttpResponse response, String url) {
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(
                    "扫码服务 HTTP " + status + "：" + SecretRedactor.maskUrl(url));
        }
    }

    /** 校验 QQBot 扫码接口返回码，错误信息经过脱敏后再进入 ticket。 */
    private void ensureQqbotOk(ONode node, String step) {
        int retcode = node.get("retcode").getInt();
        if (retcode != 0) {
            String message =
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(
                                    node.get("msg").getString(), "retcode=" + retcode),
                            500);
            throw new IllegalStateException("QQBot 扫码绑定 " + step + " 失败：" + message);
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

    /** 在任务创建时冻结 Profile 作用域，后续 Dashboard 切换不会改变写入目标。 */
    private void bindProfile(TicketState state, String requestedProfile) {
        if (profileContext == null) {
            state.profile =
                    StrUtil.blankToDefault(System.getProperty("solonclaw.profile.name"), "default")
                            .trim();
            state.home = appConfig.getRuntime().getHome();
            state.config = appConfig;
            state.current = true;
            return;
        }
        DashboardProfileContext.Scope scope = profileContext.resolve(requestedProfile);
        state.profile = scope.getName();
        state.home = scope.getHome().toString();
        state.config = scope.getConfig();
        state.current = scope.isCurrent();
    }

    /** 清理过期活动任务和已保留足够时间的终态票据。 */
    private void cleanupTickets() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TicketState> entry : tickets.entrySet()) {
            TicketState state = entry.getValue();
            if (state.active() && now >= state.expiresAt) {
                state.cancel("qr_timeout", "扫码绑定已过期，请重新获取。");
            }
            if (!state.active() && now - state.updatedAt >= TERMINAL_RETENTION_MILLIS) {
                tickets.remove(entry.getKey(), state);
                activeTickets.remove(state.profile + ":" + state.platform, state);
            }
        }
    }

    /**
     * 转换为Map。
     *
     * @param state 状态参数。
     * @return 返回转换后的Map。
     */
    private Map<String, Object> toMap(TicketState state) {
        Map<String, Object> result = state.baseMap();
        result.put("platform", state.platform);
        result.put("profile", state.profile);
        result.put("device_code", state.deviceCode);
        result.put("qr_url", state.qrUrl);
        result.put("client_id", state.clientId);
        result.put("app_id", state.appId);
        result.put("open_id", state.openId);
        result.put("domain", state.domain);
        result.put("bot_id", state.botId);
        result.put("user_openid", state.userOpenId);
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
        if (PLATFORM_DINGTALK.equals(value)
                || PLATFORM_FEISHU.equals(value)
                || PLATFORM_WECOM.equals(value)
                || PLATFORM_QQBOT.equals(value)) {
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

    /** 编码查询参数，避免 scode 中的保留字符改变请求语义。 */
    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(StrUtil.nullToEmpty(value), "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("查询参数编码失败", e);
        }
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
     * 扫码注册请求地址不再经 URL 安全校验，对齐外部对标仓库中渠道出站不经安全策略的行为。
     *
     * @param baseUrl 待访问的地址参数。
     * @param purpose purpose 参数。
     */
    private void assertSafeBaseUrl(String baseUrl, String purpose) {
        assertSafeUrl(baseUrl, purpose);
    }

    /** 扫码注册请求地址不再经 URL 安全校验，保留方法以维持调用点稳定。 */
    private void assertSafeUrl(String url, String purpose) {
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    purpose
                            + "被安全策略阻断："
                            + SecretRedactor.maskUrl(verdict.getUrl())
                            + "（"
                            + verdict.getMessage()
                            + "）");
        }
    }

    /** 表示Ticket数据，在服务、仓储和接口之间传递。 */
    private static class TicketState extends QrSetupTicketState {
        /** 记录Ticket中的平台。 */
        private String platform;

        /** 记录Ticket中的deviceCode。 */
        private String deviceCode;

        /** 记录Ticket中的二维码URL。 */
        private String qrUrl;

        /** 记录Ticket中的client标识。 */
        private String clientId;

        /** 记录Ticket中的应用标识。 */
        private String appId;

        /** 记录Ticket中的open标识。 */
        private String openId;

        /** 记录Ticket中的domain。 */
        private String domain;

        /** 记录Ticket中的企微机器人标识。 */
        private String botId;

        /** 记录完成 QQBot 扫码的用户 OpenID，用于建立默认访问策略。 */
        private String userOpenId;

        /** 任务创建时冻结的 Profile 名称。 */
        private String profile;

        /** 任务创建时冻结的 Profile 工作区。 */
        private String home;

        /** 任务创建时冻结的 Profile 配置。 */
        private AppConfig config;

        /** 标记目标是否为当前 JVM Profile。 */
        private boolean current;

        /** 取消标志，确保被替换任务不能继续写入凭据。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 后台任务句柄，用于新任务立即中断旧轮询。 */
        private volatile Future<?> future;

        /**
         * 创建国内扫码 setup ticket 状态。
         *
         * @param timeoutMillis ticket 生命周期毫秒数。
         */
        private TicketState(long timeoutMillis) {
            super(timeoutMillis);
        }

        /** 返回任务是否仍允许发请求和落配置。 */
        private boolean active() {
            return !cancelled.get() && !"confirmed".equals(status) && !"failed".equals(status);
        }

        /** 新任务替换当前任务时保留明确终态，供旧页面停止轮询。 */
        private void cancel() {
            cancel("qr_replaced", "已有新的扫码任务，本次任务已取消。");
        }

        /** 取消任务并中断其阻塞轮询。 */
        private synchronized void cancel(String code, String message) {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            fail(code, message);
            Future<?> task = future;
            if (task != null) {
                task.cancel(true);
            }
        }
    }
}
