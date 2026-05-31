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

/** Provider-backed browser automation runtime with bounded session leases. */
public class BrowserRuntimeService {
    private static final int DEFAULT_MAX_CONCURRENCY = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final AppConfig appConfig;
    private final List<BrowserProvider> providers;
    private final SecurityPolicyService securityPolicyService;
    private final int maxConcurrency;
    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<String, Lease>();

    public BrowserRuntimeService(
            AppConfig appConfig,
            List<BrowserProvider> providers,
            SecurityPolicyService securityPolicyService) {
        this(appConfig, providers, securityPolicyService, DEFAULT_MAX_CONCURRENCY);
    }

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
            return BrowserResult.error("provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
        if (providerSession == null || StrUtil.isBlank(providerSession.getSessionId())) {
            return BrowserResult.error("provider_error", "Browser provider did not create a session");
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
            return BrowserResult.error("provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    public BrowserResult click(String sessionId, String selector, Integer timeoutSeconds) {
        return action(sessionId, "clicked", selector, null, timeoutSeconds);
    }

    public BrowserResult type(String sessionId, String selector, String text, Integer timeoutSeconds) {
        return action(sessionId, "typed", selector, text, timeoutSeconds);
    }

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
            return BrowserResult.error("provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

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
            return BrowserResult.error("provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    public BrowserResult close(String sessionId) {
        Lease lease = leases.remove(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        lease.closed.set(true);
        safeClose(lease.provider, lease.providerSession.getSessionId());
        return BrowserResult.success(sessionId, "closed", Collections.<String, Object>emptyMap());
    }

    public void shutdown() {
        for (String sessionId : new ArrayList<String>(leases.keySet())) {
            close(sessionId);
        }
    }

    public int activeLeaseCount() {
        return leases.size();
    }

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
            return BrowserResult.error("provider_error", SecretRedactor.redact(e.getMessage(), 500));
        }
    }

    private BrowserProvider selectProvider() {
        for (BrowserProvider provider : providers) {
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }
        return null;
    }

    private boolean tryRegisterLease(Lease lease) {
        synchronized (leases) {
            if (leases.size() >= maxConcurrency) {
                return false;
            }
            leases.put(lease.id, lease);
            return true;
        }
    }

    private boolean hasLeaseCapacity() {
        synchronized (leases) {
            return leases.size() < maxConcurrency;
        }
    }

    private Lease findLease(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return leases.get(sessionId);
    }

    private boolean isExpired(Lease lease) {
        return lease.expiresAtMillis > 0L && System.currentTimeMillis() > lease.expiresAtMillis;
    }

    private SecurityPolicyService.UrlVerdict checkUrl(String url) {
        if (securityPolicyService == null) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("url", url);
        return securityPolicyService.checkToolArgs(ToolNameConstants.BROWSER, args);
    }

    private BrowserResult toBrowserResult(
            Lease lease,
            BrowserProvider.BrowserActionResult actionResult,
            String defaultStatus,
            String fallbackKey,
            String fallbackValue) {
        if (actionResult == null) {
            return BrowserResult.error("provider_error", "Browser provider returned no action result");
        }
        if (!actionResult.isSuccess()) {
            return BrowserResult.error(
                    StrUtil.blankToDefault(actionResult.getErrorCode(), "provider_error"),
                    StrUtil.blankToDefault(actionResult.getErrorMessage(), "Browser provider action failed"));
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
                lease.id,
                StrUtil.blankToDefault(actionResult.getStatus(), defaultStatus),
                details);
    }

    private SecurityPolicyService.UrlVerdict checkProviderUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return SecurityPolicyService.UrlVerdict.allow();
        }
        return checkUrl(url);
    }

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
                details.put(key + "Length", Integer.valueOf(StrUtil.nullToEmpty(String.valueOf(value)).length()));
            } else if (value instanceof String && key.toLowerCase(java.util.Locale.ROOT).contains("url")) {
                details.put(key, SecretRedactor.maskUrl(String.valueOf(value)));
            } else if (value instanceof String) {
                details.put(key, safe(String.valueOf(value)));
            } else {
                details.put(key, value);
            }
        }
        return details;
    }

    private Object sanitizeFallbackValue(String key, String value) {
        if (StrUtil.nullToEmpty(key).toLowerCase(java.util.Locale.ROOT).contains("url")) {
            return SecretRedactor.maskUrl(value);
        }
        return safe(value);
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(1, Math.min(timeoutSeconds.intValue(), 300));
    }

    private String newTaskId() {
        return "browser-task-" + UUID.randomUUID().toString();
    }

    private String safe(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 500);
    }

    private LoopbackRewrite rewriteLoopbackUrl(String url) {
        if (appConfig == null
                || appConfig.getSecurity() == null
                || !appConfig.getSecurity().isRewriteBrowserLoopbackUrls()) {
            return LoopbackRewrite.none(url);
        }
        String alias = StrUtil.nullToEmpty(appConfig.getSecurity().getBrowserLoopbackHostAlias()).trim();
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

    private void safeClose(BrowserProvider provider, String providerSessionId) {
        try {
            provider.closeSession(providerSessionId);
        } catch (Exception ignored) {
            // Close is best-effort during cleanup.
        }
    }

    private static class Lease {
        private final String id;
        private final BrowserProvider provider;
        private final BrowserProvider.BrowserSession providerSession;
        private final long expiresAtMillis;
        private final String createdAt = Instant.now().toString();
        private final AtomicBoolean closed = new AtomicBoolean(false);

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

    private static class LoopbackRewrite {
        private final String originalUrl;
        private final String rewrittenUrl;
        private final String originalHost;
        private final String aliasHost;

        private LoopbackRewrite(
                String originalUrl, String rewrittenUrl, String originalHost, String aliasHost) {
            this.originalUrl = originalUrl;
            this.rewrittenUrl = rewrittenUrl;
            this.originalHost = originalHost;
            this.aliasHost = aliasHost;
        }

        private static LoopbackRewrite none(String url) {
            return new LoopbackRewrite(url, url, "", "");
        }

        private boolean isRewritten() {
            return StrUtil.isNotBlank(rewrittenUrl) && !StrUtil.equals(originalUrl, rewrittenUrl);
        }

        private Map<String, Object> toDetails() {
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("from", SecretRedactor.maskUrl(StrUtil.nullToEmpty(originalHost)));
            details.put("to", SecretRedactor.maskUrl(StrUtil.nullToEmpty(aliasHost)));
            details.put("originalUrl", SecretRedactor.maskUrl(originalUrl));
            details.put("rewrittenUrl", SecretRedactor.maskUrl(rewrittenUrl));
            return details;
        }
    }

    public static class BrowserResult {
        private final boolean success;
        private final String sessionId;
        private final String status;
        private final Map<String, Object> details;
        private final BrowserError error;

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

        public static BrowserResult success(
                String sessionId, String status, Map<String, Object> details) {
            return new BrowserResult(true, sessionId, status, details, null);
        }

        public static BrowserResult error(String code, String message) {
            return error(code, message, Collections.<String, Object>emptyMap());
        }

        public static BrowserResult error(
                String code, String message, Map<String, Object> details) {
            return new BrowserResult(
                    false,
                    "",
                    "error",
                    details,
                    new BrowserError(code, SecretRedactor.redact(message, 500)));
        }

        public boolean isSuccess() {
            return success;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getStatus() {
            return status;
        }

        public Map<String, Object> getDetails() {
            return new LinkedHashMap<String, Object>(details);
        }

        public BrowserError getError() {
            return error;
        }

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

    public static class BrowserError {
        private final String code;
        private final String message;

        private BrowserError(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "BrowserError{code='" + code + "', message='" + message + "'}";
        }
    }
}
