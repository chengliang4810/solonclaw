package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
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
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("url", SecretRedactor.maskUrl(url));
        details.put("timeoutSeconds", Integer.valueOf(normalizeTimeout(timeoutSeconds)));
        return BrowserResult.success(lease.id, "navigated", details);
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
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("path", SecretRedactor.redact(path, 500));
        details.put("fullPage", Boolean.valueOf(fullPage != null && fullPage.booleanValue()));
        return BrowserResult.success(lease.id, "screenshot", details);
    }

    public BrowserResult extract(String sessionId, String selector, String format) {
        Lease lease = findLease(sessionId);
        if (lease == null) {
            return BrowserResult.error("session_not_found", "Browser session not found");
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("selector", safe(selector));
        details.put("format", safe(StrUtil.blankToDefault(format, "text")));
        details.put("content", "");
        return BrowserResult.success(lease.id, "extracted", details);
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
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("selector", safe(selector));
        if (text != null) {
            details.put("text", SecretRedactor.redact(text, 500));
        }
        details.put("timeoutSeconds", Integer.valueOf(normalizeTimeout(timeoutSeconds)));
        return BrowserResult.success(lease.id, action, details);
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
