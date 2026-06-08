package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 提供浏览器运行时相关业务能力，封装调用方不需要感知的运行细节。 */
public class BrowserRuntimeService {
    /** 默认最大并发数的统一常量值。 */
    private static final int DEFAULT_MAX_CONCURRENCY = 2;

    /** 默认超时时间秒数的统一常量值。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 注入应用配置，用于浏览器运行时。 */
    private final AppConfig appConfig;

    /** 保存providers集合，维持调用顺序或去重语义。 */
    private final List<BrowserProvider> providers;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 记录浏览器运行时中的maxConcurrency。 */
    private final int maxConcurrency;

    /** 保存leases映射，便于按键快速查询。 */
    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<String, Lease>();

    /**
     * 创建浏览器运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService) {
        this(appConfig, providers, securityPolicyService, DEFAULT_MAX_CONCURRENCY);
    }

    /**
     * 创建浏览器运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param providers 能力提供方列表。
     * @param securityPolicyService 安全策略服务依赖。
     * @param maxConcurrency maxConcurrency 参数。
     */
    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService,
            int maxConcurrency) {
        this.appConfig = appConfig;
        this.providers =
                providers == null
                        ? Collections.<BrowserProvider>emptyList()
                        : new ArrayList<BrowserProvider>(providers);
        this.securityPolicyService = securityPolicyService;
        this.maxConcurrency = Math.max(0, maxConcurrency);
    }

    /**
     * 创建浏览器会话租约并初始化远程页面。
     *
     * @param taskId 任务标识。
     * @return 返回create结果。
     */
    public BrowserResult create(String taskId) {
        BrowserProvider provider = selectProvider();
        if (provider == null) {
            return BrowserResult.error("browser_unavailable", "No available browser provider");
        }
        if (!hasLeaseCapacity()) {
            return BrowserResult.error("browser_busy", "Browser concurrency limit reached");
        }
        BrowserProvider.BrowserSession providerSession;
        try {
            providerSession = provider.createSession(StrUtil.blankToDefault(taskId, newTaskId()));
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
        if (providerSession == null || StrUtil.isBlank(providerSession.getSessionId())) {
            return BrowserResult.error(
                    "provider_error", "Browser provider did not create a session");
        }
        String leaseId = "browser-" + UUID.randomUUID().toString();
        Lease lease =
                new Lease(
                        leaseId,
                        provider,
                        providerSession,
                        System.currentTimeMillis() + DEFAULT_TIMEOUT_SECONDS * 1000L);
        if (!tryRegisterLease(lease)) {
            safeClose(provider, providerSession.getSessionId());
            return BrowserResult.error("browser_busy", "Browser concurrency limit reached");
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("provider", safe(provider.name()));
        details.put("sessionId", leaseId);
        details.put("connectUrl", SecretRedactor.maskUrl(providerSession.getConnectUrl()));
        details.put("createdAt", lease.createdAt);
        details.put("timeoutSeconds", Integer.valueOf(DEFAULT_TIMEOUT_SECONDS));
        return BrowserResult.success(leaseId, "created", details);
    }

    /**
     * 导航浏览器会话到目标地址。
     *
     * @param sessionId 当前会话标识。
     * @param url 待校验或访问的 URL。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回navigate结果。
     */
    public BrowserResult navigate(String sessionId, String url, Integer timeoutSeconds) {
        SecurityPolicyService.UrlVerdict verdict = checkUrl(url);
        if (!verdict.isAllowed()) {
            return BrowserResult.error(
                    "security_blocked",
                    verdict.getMessage(),
                    Collections.<String, Object>singletonMap(
                            "url", SecretRedactor.maskUrl(verdict.getUrl())));
        }
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        LoopbackRewrite rewrite = rewriteLoopbackUrl(url);
        String browserUrl = rewrite.isRewritten() ? rewrite.rewrittenUrl : url;
        try {
            BrowserProvider.BrowserActionResult actionResult =
                    lease.provider.navigate(
                            lease.providerSession.getSessionId(),
                            browserUrl,
                            normalizeTimeout(timeoutSeconds));
            BrowserResult result =
                    toBrowserResult(lease, actionResult, "navigated", "url", browserUrl);
            if (!result.isSuccess() || !rewrite.isRewritten()) {
                return result;
            }
            Map<String, Object> details = result.getDetails();
            details.put("requestedUrl", SecretRedactor.maskUrl(url));
            details.put("urlRewrite", rewrite.toDetails());
            return BrowserResult.success(result.getSessionId(), result.getStatus(), details);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 点击浏览器页面中的目标元素。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回click结果。
     */
    public BrowserResult click(String sessionId, String selector, Integer timeoutSeconds) {
        return action(sessionId, "clicked", selector, null, timeoutSeconds);
    }

    /**
     * 向浏览器页面中的目标元素输入文本。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param text 待处理文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回类型结果。
     */
    public BrowserResult type(
            String sessionId, String selector, String text, Integer timeoutSeconds) {
        return action(sessionId, "typed", selector, text, timeoutSeconds);
    }

    /**
     * 截取浏览器页面截图。
     *
     * @param sessionId 当前会话标识。
     * @param path 文件或目录路径。
     * @param fullPage fullPage 参数。
     * @return 返回screenshot结果。
     */
    public BrowserResult screenshot(String sessionId, String path, Boolean fullPage) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        try {
            BrowserProvider.BrowserActionResult actionResult =
                    lease.provider.screenshot(
                            lease.providerSession.getSessionId(),
                            path,
                            fullPage != null && fullPage.booleanValue());
            return toBrowserResult(lease, actionResult, "screenshot", "path", path);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 从浏览器页面提取指定内容。
     *
     * @param sessionId 当前会话标识。
     * @param selector 浏览器元素选择器。
     * @param format 格式参数。
     * @return 返回extract结果。
     */
    public BrowserResult extract(String sessionId, String selector, String format) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        try {
            BrowserProvider.BrowserActionResult actionResult =
                    lease.provider.extract(
                            lease.providerSession.getSessionId(),
                            selector,
                            StrUtil.blankToDefault(format, "text"));
            return toBrowserResult(lease, actionResult, "extracted", "selector", selector);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 关闭当前组件持有的运行资源。
     *
     * @param sessionId 当前会话标识。
     * @return 返回close结果。
     */
    public BrowserResult close(String sessionId) {
        Lease lease = leases.remove(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        lease.closed.set(true);
        safeClose(lease.provider, lease.providerSession.getSessionId());
        return BrowserResult.success(sessionId, "closed", Collections.<String, Object>emptyMap());
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        for (String sessionId : new ArrayList<String>(leases.keySet())) {
            close(sessionId);
        }
    }

    /**
     * 统计当前活跃浏览器租约数量。
     *
     * @return 返回active Lease次数结果。
     */
    public int activeLeaseCount() {
        return leases.size();
    }

    /**
     * 执行action相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param action 操作参数。
     * @param selector 浏览器元素选择器。
     * @param text 待处理文本。
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回action结果。
     */
    private BrowserResult action(
            String sessionId, String action, String selector, String text, Integer timeoutSeconds) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        if (isExpired(lease)) {
            close(sessionId);
            return BrowserResult.error("session_expired", "Browser session timed out");
        }
        try {
            BrowserProvider.BrowserActionResult actionResult;
            if ("clicked".equals(action)) {
                actionResult =
                        lease.provider.click(
                                lease.providerSession.getSessionId(),
                                selector,
                                normalizeTimeout(timeoutSeconds));
            } else {
                actionResult =
                        lease.provider.type(
                                lease.providerSession.getSessionId(),
                                selector,
                                text,
                                normalizeTimeout(timeoutSeconds));
            }
            return toBrowserResult(lease, actionResult, action, "selector", selector);
        } catch (Exception e) {
            return BrowserResult.error(
                    "provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    /**
     * 选择首个可用的浏览器能力提供方。
     *
     * @return 返回select提供方结果。
     */
    private BrowserProvider selectProvider() {
        for (BrowserProvider provider : providers) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 尝试注册浏览器会话租约并检查并发上限。
     *
     * @param lease lease 参数。
     * @return 返回try Register Lease结果。
     */
    private boolean tryRegisterLease(Lease lease) {
        synchronized (leases) {
            if (leases.size() >= maxConcurrency) {
                return false;
            }
            leases.put(lease.id, lease);
            return true;
        }
    }

    /**
     * 判断是否存在Lease Capacity。
     *
     * @return 如果Lease Capacity满足条件则返回 true，否则返回 false。
     */
    private boolean hasLeaseCapacity() {
        synchronized (leases) {
            return leases.size() < maxConcurrency;
        }
    }

    /**
     * 查找Lease。
     *
     * @param sessionId 当前会话标识。
     * @return 返回Lease结果。
     */
    private Lease findLease(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return leases.get(sessionId);
    }

    /**
     * 判断是否Expired。
     *
     * @param lease lease 参数。
     * @return 如果Expired满足条件则返回 true，否则返回 false。
     */
    private boolean isExpired(Lease lease) {
        return lease.expiresAtMillis > 0L && System.currentTimeMillis() > lease.expiresAtMillis;
    }

    /**
     * 检查URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL结果。
     */
    private SecurityPolicyService.UrlVerdict checkUrl(String url) {
        if (securityPolicyService == null) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("url", url);
        return securityPolicyService.checkToolArgs(ToolNameConstants.BROWSER, args);
    }

    /**
     * 转换为浏览器结果。
     *
     * @param lease lease 参数。
     * @param actionResult action结果响应或执行结果。
     * @param defaultStatus 默认状态参数。
     * @param fallbackKey 兜底键标识或键值。
     * @param fallbackValue 兜底值参数。
     * @return 返回转换后的浏览器结果。
     */
    private BrowserResult toBrowserResult(
            Lease lease,
            BrowserProvider.BrowserActionResult actionResult,
            String defaultStatus,
            String fallbackKey,
            String fallbackValue) {
        if (actionResult == null) {
            return BrowserResult.error(
                    "provider_error", "Browser provider returned no action result");
        }
        if (!actionResult.isSuccess()) {
            return BrowserResult.error(
                    StrUtil.blankToDefault(actionResult.getErrorCode(), "provider_error"),
                    StrUtil.blankToDefault(
                            actionResult.getErrorMessage(), "Browser provider action failed"));
        }
        SecurityPolicyService.UrlVerdict verdict = checkProviderUrl(actionResult.getCurrentUrl());
        if (!verdict.isAllowed()) {
            close(lease.id);
            return BrowserResult.error(
                    "security_blocked",
                    verdict.getMessage(),
                    Collections.<String, Object>singletonMap(
                            "url", SecretRedactor.maskUrl(verdict.getUrl())));
        }
        Map<String, Object> details = sanitizeDetails(actionResult.getDetails());
        if (StrUtil.isNotBlank(actionResult.getCurrentUrl())) {
            details.put("currentUrl", SecretRedactor.maskUrl(actionResult.getCurrentUrl()));
        }
        if (StrUtil.isNotBlank(fallbackKey)
                && StrUtil.isNotBlank(fallbackValue)
                && !details.containsKey(fallbackKey)) {
            details.put(fallbackKey, sanitizeFallbackValue(fallbackKey, fallbackValue));
        }
        return BrowserResult.success(
                lease.id, StrUtil.blankToDefault(actionResult.getStatus(), defaultStatus), details);
    }

    /**
     * 检查提供方URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回提供方URL结果。
     */
    private SecurityPolicyService.UrlVerdict checkProviderUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        return checkUrl(url);
    }

    /**
     * 清理Details。
     *
     * @param rawDetails 原始Details参数。
     * @return 返回Details结果。
     */
    private Map<String, Object> sanitizeDetails(Map<String, Object> rawDetails) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        if (rawDetails == null) {
            return details;
        }
        for (Map.Entry<String, Object> entry : rawDetails.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("text".equalsIgnoreCase(key) || "input".equalsIgnoreCase(key)) {
                details.put(key, "[redacted]");
                details.put(
                        key + "Length",
                        Integer.valueOf(StrUtil.nullToEmpty(String.valueOf(value)).length()));
            } else if (value instanceof String
                    && key.toLowerCase(java.util.Locale.ROOT).contains("url")) {
                details.put(key, SecretRedactor.maskUrl(String.valueOf(value)));
            } else if (value instanceof String) {
                details.put(key, safe(String.valueOf(value)));
            } else {
                details.put(key, value);
            }
        }
        return details;
    }

    /**
     * 清理兜底Value。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回兜底Value结果。
     */
    private Object sanitizeFallbackValue(String key, String value) {
        if (StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT).contains("url")) {
            return SecretRedactor.maskUrl(value);
        }
        return safe(value);
    }

    /**
     * 规范化Timeout。
     *
     * @param timeoutSeconds 超时时间，单位为秒。
     * @return 返回Timeout结果。
     */
    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(1, Math.min(timeoutSeconds.intValue(), 300));
    }

    /**
     * 创建任务标识。
     *
     * @return 返回创建好的任务标识。
     */
    private String newTaskId() {
        return "browser-task-" + UUID.randomUUID().toString();
    }

    /**
     * 执行安全相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe结果。
     */
    private String safe(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 500);
    }

    /**
     * 执行rewriteLoopbackURL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回rewrite Loopback URL结果。
     */
    private LoopbackRewrite rewriteLoopbackUrl(String url) {
        if (appConfig == null
                || appConfig.getSecurity() == null
                || !appConfig.getSecurity().isRewriteBrowserLoopbackUrls()) {
            return LoopbackRewrite.none(url);
        }
        String alias =
                StrUtil.nullToEmpty(appConfig.getSecurity().getBrowserLoopbackHostAlias()).trim();
        if (alias.length() == 0) {
            return LoopbackRewrite.none(url);
        }
        URI uri;
        try {
            uri = URI.create(StrUtil.nullToEmpty(url).trim());
        } catch (Exception ignored) {
            return LoopbackRewrite.none(url);
        }
        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return LoopbackRewrite.none(url);
        }
        String host = uri.getHost();
        if (!isLoopbackHost(host)) {
            return LoopbackRewrite.none(url);
        }
        String rewritten = rebuildUrl(uri, alias);
        if (StrUtil.isBlank(rewritten) || rewritten.equals(url)) {
            return LoopbackRewrite.none(url);
        }
        return new LoopbackRewrite(url, rewritten, host, alias);
    }

    /**
     * 判断是否Loopback Host。
     *
     * @param host 主机参数。
     * @return 如果Loopback Host满足条件则返回 true，否则返回 false。
     */
    private boolean isLoopbackHost(String host) {
        String value = StrUtil.nullToEmpty(host).trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if ("localhost".equals(value) || "::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value)) {
            return true;
        }
        if (value.startsWith("::ffff:")) {
            value = value.substring("::ffff:".length());
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part < 0 || part > 255) {
                    return false;
                }
            }
            return first == 127;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 执行rebuildURL相关逻辑。
     *
     * @param uri 待校验或访问的地址参数。
     * @param alias 别名参数。
     * @return 返回rebuild URL结果。
     */
    private String rebuildUrl(URI uri, String alias) {
        String host = alias;
        if (host.indexOf(':') >= 0 && !host.startsWith("[") && !host.endsWith("]")) {
            host = "[" + host + "]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme()).append("://").append(host);
        if (uri.getPort() >= 0) {
            builder.append(':').append(uri.getPort());
        }
        builder.append(StrUtil.nullToEmpty(uri.getRawPath()));
        if (StrUtil.isNotBlank(uri.getRawQuery())) {
            builder.append('?').append(uri.getRawQuery());
        }
        if (StrUtil.isNotBlank(uri.getRawFragment())) {
            builder.append('#').append(uri.getRawFragment());
        }
        return builder.toString();
    }

    /**
     * 生成安全展示用的关闭。
     *
     * @param provider 模型或能力提供方。
     * @param providerSessionId 提供方会话标识。
     */
    private void safeClose(BrowserProvider provider, String providerSessionId) {
        try {
            provider.closeSession(providerSessionId);
        } catch (Exception ignored) {
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
    }

    /** 承载Lease相关状态和辅助逻辑。 */
    private static class Lease {
        /** 记录Lease中的标识。 */
        private final String id;

        /** 记录Lease中的提供方。 */
        private final BrowserProvider provider;

        /** 记录Lease中的提供方会话。 */
        private final BrowserProvider.BrowserSession providerSession;

        /** 记录Lease中的expires时间Millis。 */
        private final long expiresAtMillis;

        /** 记录Lease中的创建时间。 */
        private final String createdAt = Instant.now().toString();

        /** 记录Lease中的closed。 */
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 创建Lease实例，并注入运行所需依赖。
         *
         * @param id 标识。
         * @param provider 模型或能力提供方。
         * @param providerSession 提供方会话标识或键值。
         * @param expiresAtMillis expiresAtMillis 参数。
         */
        private Lease(
                String id,
                BrowserProvider provider,
                BrowserProvider.BrowserSession providerSession,
                long expiresAtMillis) {
            this.id = id;
            this.provider = provider;
            this.providerSession = providerSession;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    /** 承载LoopbackRewrite相关状态和辅助逻辑。 */
    private static class LoopbackRewrite {
        /** 记录LoopbackRewrite中的originalURL。 */
        private final String originalUrl;

        /** 记录LoopbackRewrite中的rewrittenURL。 */
        private final String rewrittenUrl;

        /** 记录LoopbackRewrite中的original主机。 */
        private final String originalHost;

        /** 记录LoopbackRewrite中的alias主机。 */
        private final String aliasHost;

        /**
         * 创建Loopback Rewrite实例，并注入运行所需依赖。
         *
         * @param originalUrl 待校验或访问的地址参数。
         * @param rewrittenUrl 待校验或访问的地址参数。
         * @param originalHost original主机参数。
         * @param aliasHost alias主机参数。
         */
        private LoopbackRewrite(
                String originalUrl, String rewrittenUrl, String originalHost, String aliasHost) {
            this.originalUrl = originalUrl;
            this.rewrittenUrl = rewrittenUrl;
            this.originalHost = originalHost;
            this.aliasHost = aliasHost;
        }

        /**
         * 执行none相关逻辑。
         *
         * @param url 待校验或访问的 URL。
         * @return 返回none结果。
         */
        private static LoopbackRewrite none(String url) {
            return new LoopbackRewrite(url, url, "", "");
        }

        /**
         * 判断是否Rewritten。
         *
         * @return 如果Rewritten满足条件则返回 true，否则返回 false。
         */
        private boolean isRewritten() {
            return StrUtil.isNotBlank(rewrittenUrl) && !StrUtil.equals(originalUrl, rewrittenUrl);
        }

        /**
         * 转换为Details。
         *
         * @return 返回转换后的Details。
         */
        private Map<String, Object> toDetails() {
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("from", SecretRedactor.maskUrl(StrUtil.nullToEmpty(originalHost)));
            details.put("to", SecretRedactor.maskUrl(StrUtil.nullToEmpty(aliasHost)));
            details.put("originalUrl", SecretRedactor.maskUrl(originalUrl));
            details.put("rewrittenUrl", SecretRedactor.maskUrl(rewrittenUrl));
            return details;
        }
    }

    /** 表示浏览器结果，携带调用方后续判断所需信息。 */
    public static class BrowserResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录浏览器中的会话标识。 */
        private final String sessionId;

        /** 记录浏览器中的状态。 */
        private final String status;

        /** 保存details映射，便于按键快速查询。 */
        private final Map<String, Object> details;

        /** 记录浏览器中的错误。 */
        private final BrowserError error;

        /**
         * 创建浏览器结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param sessionId 当前会话标识。
         * @param status 状态参数。
         * @param details details 参数。
         * @param error 错误参数。
         */
        private BrowserResult(
                boolean success,
                String sessionId,
                String status,
                Map<String, Object> details,
                BrowserError error) {
            this.success = success;
            this.sessionId = sessionId;
            this.status = status;
            this.details =
                    details == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(details);
            this.error = error;
        }

        /**
         * 执行success相关逻辑。
         *
         * @param sessionId 当前会话标识。
         * @param status 状态参数。
         * @param details details 参数。
         * @return 返回success结果。
         */
        public static BrowserResult success(
                String sessionId, String status, Map<String, Object> details) {
            return new BrowserResult(true, sessionId, status, details, null);
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         * @return 返回error结果。
         */
        public static BrowserResult error(String code, String message) {
            return error(code, message, Collections.<String, Object>emptyMap());
        }

        /**
         * 执行错误相关逻辑。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         * @param details details 参数。
         * @return 返回error结果。
         */
        public static BrowserResult error(
                String code, String message, Map<String, Object> details) {
            return new BrowserResult(
                    false,
                    "",
                    "error",
                    details,
                    new BrowserError(code, SecretRedactor.redact(message, 500)));
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 读取会话标识。
         *
         * @return 返回读取到的会话标识。
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        public String getStatus() {
            return status;
        }

        /**
         * 读取Details。
         *
         * @return 返回读取到的Details。
         */
        public Map<String, Object> getDetails() {
            return new LinkedHashMap<String, Object>(details);
        }

        /**
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        public BrowserError getError() {
            return error;
        }

        /**
         * 转换为String。
         *
         * @return 返回转换后的String。
         */
        @Override
        public String toString() {
            return "BrowserResult{success="
                    + success
                    + ", sessionId='"
                    + sessionId
                    + "', status='"
                    + status
                    + "', details="
                    + details
                    + ", error="
                    + error
                    + "}";
        }
    }

    /** 承载浏览器错误相关状态和辅助逻辑。 */
    public static class BrowserError {
        /** 记录浏览器错误中的code。 */
        private final String code;

        /** 记录浏览器错误中的消息。 */
        private final String message;

        /**
         * 创建浏览器Error实例，并注入运行所需依赖。
         *
         * @param code code 参数。
         * @param message 平台消息或错误消息。
         */
        private BrowserError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        /**
         * 读取Code。
         *
         * @return 返回读取到的Code。
         */
        public String getCode() {
            return code;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }

        /**
         * 转换为String。
         *
         * @return 返回转换后的String。
         */
        @Override
        public String toString() {
            return "BrowserError{code='" + code + "', message='" + message + "'}";
        }
    }
}
