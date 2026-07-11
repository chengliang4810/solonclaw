package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;

/** 承载工具Arg凭据判定相关状态和辅助逻辑。 */
final class ToolArgCredentialVerdict {
    /** 是否启用allowed。 */
    boolean allowed = true;

    /** 记录工具Arg凭据判定中的引用。 */
    String reference = "";

    /**
     * 执行阻断相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param normalizedKey normalized键标识或键值。
     */
    void block(String key, String normalizedKey) {
        this.allowed = false;
        String safeKey = canonicalStructuredCredentialKey(normalizedKey);
        if (safeKey.length() == 0) {
            safeKey = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(key)).trim();
        }
        safeKey = safeKey.replaceAll("\\s+", "_");
        safeKey = SecretRedactor.redact(safeKey, 200);
        this.reference = safeKey.length() == 0 ? "tool_arg://credential" : "tool_arg://" + safeKey;
    }

    /**
     * 执行规范Structured凭据键相关逻辑。
     *
     * @param normalizedKey normalized键标识或键值。
     * @return 返回规范Structured凭据键结果。
     */
    private static String canonicalStructuredCredentialKey(String normalizedKey) {
        String key = StrUtil.nullToEmpty(normalizedKey).trim();
        if (key.startsWith("proxy_authorization")) {
            return "Proxy-Authorization";
        }
        if (key.startsWith("authorization")) {
            return "Authorization";
        }
        if (key.startsWith("x_api_key")) {
            return "x-api-key";
        }
        if (key.startsWith("x_api_token")) {
            return "x-api-token";
        }
        if (key.startsWith("x_auth_token")) {
            return "x-auth-token";
        }
        if (key.startsWith("api_key") || key.startsWith("apikey")) {
            return "apiKey";
        }
        if (key.startsWith("access_token")) {
            return "access_token";
        }
        if (key.startsWith("refresh_token")) {
            return "refresh_token";
        }
        if (key.startsWith("id_token")) {
            return "id_token";
        }
        if (key.startsWith("auth_token")) {
            return "auth_token";
        }
        if (key.startsWith("oauth_token")) {
            return "oauth_token";
        }
        if (key.startsWith("bearer_token")) {
            return "bearer_token";
        }
        if (key.startsWith("client_secret")) {
            return "client_secret";
        }
        if (key.startsWith("private_key")) {
            return "private_key";
        }
        if (key.startsWith("secret_key")) {
            return "secret_key";
        }
        if (key.startsWith("session_token")) {
            return "session_token";
        }
        if (key.startsWith("security_token")) {
            return "security_token";
        }
        if (key.startsWith("credentials")) {
            return "credentials";
        }
        if (key.startsWith("credential")) {
            return "credential";
        }
        if (key.startsWith("cookie")) {
            return "cookie";
        }
        if (key.startsWith("secret")) {
            return "secret";
        }
        if (key.startsWith("token")) {
            return "token";
        }
        if ("auth".equals(key)) {
            return "auth";
        }
        return "";
    }
}
