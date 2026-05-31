package com.jimuqu.solon.claw.plugin.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 浏览器自动化后端接口。 */
public interface BrowserProvider {
    String name();

    boolean isAvailable();

    BrowserSession createSession(String taskId);

    void closeSession(String sessionId);

    default BrowserActionResult navigate(String sessionId, String url, int timeoutSeconds) {
        return BrowserActionResult.fail("unsupported_action", "Browser provider does not support navigate");
    }

    default BrowserActionResult click(String sessionId, String selector, int timeoutSeconds) {
        return BrowserActionResult.fail("unsupported_action", "Browser provider does not support click");
    }

    default BrowserActionResult type(
            String sessionId, String selector, String text, int timeoutSeconds) {
        return BrowserActionResult.fail("unsupported_action", "Browser provider does not support type");
    }

    default BrowserActionResult screenshot(String sessionId, String path, boolean fullPage) {
        return BrowserActionResult.fail("unsupported_action", "Browser provider does not support screenshot");
    }

    default BrowserActionResult extract(String sessionId, String selector, String format) {
        return BrowserActionResult.fail("unsupported_action", "Browser provider does not support extract");
    }

    class BrowserSession {
        private final String sessionId;
        private final String connectUrl;

        public BrowserSession(String sessionId, String connectUrl) {
            this.sessionId = sessionId;
            this.connectUrl = connectUrl;
        }

        public String getSessionId() { return sessionId; }
        public String getConnectUrl() { return connectUrl; }
    }

    class BrowserActionResult {
        private final boolean success;
        private final String status;
        private final String currentUrl;
        private final Map<String, Object> details;
        private final String errorCode;
        private final String errorMessage;

        private BrowserActionResult(
                boolean success,
                String status,
                String currentUrl,
                Map<String, Object> details,
                String errorCode,
                String errorMessage) {
            this.success = success;
            this.status = status;
            this.currentUrl = currentUrl;
            this.details =
                    details == null
                            ? Collections.<String, Object>emptyMap()
                            : new LinkedHashMap<String, Object>(details);
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static BrowserActionResult ok(String status, String currentUrl) {
            return ok(status, currentUrl, Collections.<String, Object>emptyMap());
        }

        public static BrowserActionResult ok(
                String status, String currentUrl, Map<String, Object> details) {
            return new BrowserActionResult(true, status, currentUrl, details, null, null);
        }

        public static BrowserActionResult fail(String errorCode, String errorMessage) {
            return new BrowserActionResult(
                    false, "error", null, Collections.<String, Object>emptyMap(), errorCode, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getStatus() {
            return status;
        }

        public String getCurrentUrl() {
            return currentUrl;
        }

        public Map<String, Object> getDetails() {
            return new LinkedHashMap<String, Object>(details);
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
