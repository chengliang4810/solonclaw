package com.jimuqu.solon.claw.plugin.provider;

/** 浏览器自动化后端接口。 */
public interface BrowserProvider {
    String name();

    boolean isAvailable();

    BrowserSession createSession(String taskId);

    void closeSession(String sessionId);

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
}
