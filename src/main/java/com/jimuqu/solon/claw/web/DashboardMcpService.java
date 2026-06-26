package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.UrlOriginSupport;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供控制台MCP相关业务能力，封装调用方不需要感知的运行细节。 */
public class DashboardMcpService {
    /** 记录 MCP 控制台解析降级的低敏诊断日志，不输出 OAuth token 或响应正文。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardMcpService.class);

    /** 最大OAUTHtokenREDIRECTS的统一常量值。 */
    private static final int MAX_OAUTH_TOKEN_REDIRECTS = 5;

    /** TRANSPORTSTDIO的统一常量值。 */
    private static final String TRANSPORT_STDIO = "stdio";

    /** TRANSPORTHTTP的统一常量值。 */
    private static final String TRANSPORT_HTTP = "http";

    /** TRANSPORTSTREAMABLE的统一常量值。 */
    private static final String TRANSPORT_STREAMABLE = "streamable";

    /** TRANSPORTSTREAMABLESTATELESS的统一常量值。 */
    private static final String TRANSPORT_STREAMABLE_STATELESS = "streamable_stateless";

    /** TRANSPORTSSE的统一常量值。 */
    private static final String TRANSPORT_SSE = "sse";

    /** 注入应用配置，用于控制台MCP。 */
    private final AppConfig appConfig;

    /** 记录控制台MCP中的数据库。 */
    private final SqliteDatabase database;

    /** 注入包安全服务，用于调用对应业务能力。 */
    private final McpPackageSecurityService packageSecurityService;

    /** 注入MCP运行时服务，用于调用对应业务能力。 */
    private final McpRuntimeService mcpRuntimeService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /**
     * 创建控制台MCP服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param database database 参数。
     */
    public DashboardMcpService(AppConfig appConfig, SqliteDatabase database) {
        this(appConfig, database, null, null);
    }

    /**
     * 创建控制台MCP服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param database database 参数。
     * @param packageSecurityService 待校验或访问的地址参数。
     */
    public DashboardMcpService(
            AppConfig appConfig,
            SqliteDatabase database,
            McpPackageSecurityService packageSecurityService) {
        this(appConfig, database, packageSecurityService, null);
    }

    /**
     * 创建控制台MCP服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param database database 参数。
     * @param packageSecurityService 待校验或访问的地址参数。
     * @param mcpRuntimeService MCP运行时服务依赖。
     */
    public DashboardMcpService(
            AppConfig appConfig,
            SqliteDatabase database,
            McpPackageSecurityService packageSecurityService,
            McpRuntimeService mcpRuntimeService) {
        this.appConfig = appConfig;
        this.database = database;
        this.packageSecurityService =
                packageSecurityService == null
                        ? new McpPackageSecurityService(
                                new DefaultSkillHubHttpClient(new SecurityPolicyService(appConfig)),
                                new SecurityPolicyService(appConfig))
                        : packageSecurityService;
        this.mcpRuntimeService =
                mcpRuntimeService == null
                        ? new McpRuntimeService(appConfig, database)
                        : mcpRuntimeService;
        this.securityPolicyService = new SecurityPolicyService(appConfig);
    }

    /**
     * 执行oauth策略摘要相关逻辑。
     *
     * @return 返回oauth策略Summary结果。
     */
    public static Map<String, Object> oauthPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("authorizationEndpointUrlSafety", Boolean.TRUE);
        summary.put("tokenEndpointUrlSafety", Boolean.TRUE);
        summary.put("tokenEndpointRedirectUrlSafety", Boolean.TRUE);
        summary.put("tokenEndpointRedirectLimit", Integer.valueOf(MAX_OAUTH_TOKEN_REDIRECTS));
        summary.put("crossOriginRedirectBodyForwardingBlocked", Boolean.TRUE);
        summary.put("stateValidationRequired", Boolean.TRUE);
        summary.put("pkceS256Required", Boolean.TRUE);
        summary.put("codeVerifierHiddenFromStatus", Boolean.TRUE);
        summary.put("accessTokenRedacted", Boolean.TRUE);
        summary.put("refreshTokenRedacted", Boolean.TRUE);
        summary.put("clientSecretRedacted", Boolean.TRUE);
        summary.put("refreshRequiresRefreshToken", Boolean.TRUE);
        summary.put("handle401RefreshThenReauth", Boolean.TRUE);
        summary.put("clearRemovesSecretPresenceFlags", Boolean.TRUE);
        summary.put(
                "statusPresenceFields",
                Collections.unmodifiableList(
                        java.util.Arrays.asList(
                                "has_access_token", "has_refresh_token", "has_client_secret")));
        summary.put("callbackErrorsRedacted", Boolean.TRUE);
        summary.put("tokenErrorsRedacted", Boolean.TRUE);
        summary.put("tokenResponseRequiresAccessToken", Boolean.TRUE);
        return summary;
    }

    /**
     * 执行列表相关逻辑。
     *
     * @return 返回list结果。
     */
    public Map<String, Object> list() throws Exception {
        List<Map<String, Object>> servers = new ArrayList<Map<String, Object>>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from mcp_servers order by updated_at desc");
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    servers.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", appConfig.getMcp().isEnabled());
        result.put("servers", servers);
        return result;
    }

    /**
     * 执行save，服务于控制台MCP主流程相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回save结果。
     */
    public Map<String, Object> save(Map<String, Object> body) throws Exception {
        String serverId = read(body, "serverId");
        if (StrUtil.isBlank(serverId)) {
            serverId = IdSupport.newId();
        }
        String name = StrUtil.blankToDefault(read(body, "name"), serverId);
        String transport = normalizeTransport(read(body, "transport"));
        String endpoint = read(body, "endpoint");
        validateTransportTarget(transport, endpoint, read(body, "command"));
        if (!TRANSPORT_STDIO.equals(transport)) {
            assertSafeRuntimeUrl(endpoint, "MCP endpoint");
        }
        McpPackageSecurityService.SecurityVerdict securityVerdict =
                TRANSPORT_STDIO.equals(transport)
                        ? checkPackageSecurity(
                                read(body, "command"), body == null ? null : body.get("args"))
                        : McpPackageSecurityService.SecurityVerdict.allow();
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            String previousToolsHash = "";
            String previousLastToolsJson = "";
            long createdAt = now;
            long lastCheckedAt = 0L;
            long lastToolsChangedAt = 0L;
            PreparedStatement query =
                    connection.prepareStatement(
                            "select last_tools_hash, last_tools_json, created_at, last_checked_at, last_tools_changed_at from mcp_servers where server_id = ?");
            query.setString(1, serverId);
            ResultSet queryResult = query.executeQuery();
            try {
                if (queryResult.next()) {
                    previousToolsHash =
                            StrUtil.nullToEmpty(queryResult.getString("last_tools_hash"));
                    previousLastToolsJson =
                            StrUtil.nullToEmpty(queryResult.getString("last_tools_json"));
                    createdAt = queryResult.getLong("created_at");
                    lastCheckedAt = queryResult.getLong("last_checked_at");
                    lastToolsChangedAt = queryResult.getLong("last_tools_changed_at");
                }
            } finally {
                queryResult.close();
                query.close();
            }
            String toolsJson = json(body.get("tools"));
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into mcp_servers (server_id, name, transport, endpoint, command, args_json, auth_json, oauth_json, capabilities_json, status, tools_json, last_tools_hash, last_tools_json, last_error, enabled, created_at, updated_at, last_checked_at, last_tools_changed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, serverId);
            statement.setString(2, name);
            statement.setString(3, transport);
            statement.setString(4, endpoint);
            statement.setString(5, read(body, "command"));
            statement.setString(6, json(body.get("args")));
            statement.setString(7, json(body.get("auth")));
            statement.setString(8, json(body.get("oauth")));
            statement.setString(9, json(body.get("capabilities")));
            statement.setString(10, securityVerdict.isAllowed() ? "configured" : "blocked");
            statement.setString(11, toolsJson);
            statement.setString(12, previousToolsHash);
            statement.setString(13, previousLastToolsJson);
            statement.setString(
                    14,
                    securityVerdict.isAllowed()
                            ? null
                            : safeDisplayError(securityVerdict.getMessage()));
            statement.setInt(15, asBoolean(body.get("enabled"), true) ? 1 : 0);
            statement.setLong(16, createdAt);
            statement.setLong(17, now);
            statement.setLong(18, lastCheckedAt);
            statement.setLong(19, lastToolsChangedAt);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("security", securityMap(securityVerdict));
        return result;
    }

    /**
     * 规范化Transport。
     *
     * @param rawTransport 原始Transport参数。
     * @return 返回Transport结果。
     */
    private String normalizeTransport(String rawTransport) {
        String transport =
                StrUtil.blankToDefault(rawTransport, TRANSPORT_STDIO)
                        .trim()
                        .toLowerCase()
                        .replace('-', '_');
        if (TRANSPORT_HTTP.equals(transport)) {
            return TRANSPORT_STREAMABLE;
        }
        if (TRANSPORT_STDIO.equals(transport)
                || TRANSPORT_STREAMABLE.equals(transport)
                || TRANSPORT_STREAMABLE_STATELESS.equals(transport)
                || TRANSPORT_SSE.equals(transport)) {
            return transport;
        }
        throw new IllegalArgumentException(
                "不支持的 MCP transport："
                        + StrUtil.blankToDefault(rawTransport, "")
                        + "。可选值：stdio、http、streamable、streamable_stateless、sse。");
    }

    /**
     * 校验Transport Target。
     *
     * @param transport transport 参数。
     * @param endpoint endpoint 参数。
     * @param command 待执行或解析的命令文本。
     */
    private void validateTransportTarget(String transport, String endpoint, String command) {
        if (TRANSPORT_STDIO.equals(transport)) {
            if (StrUtil.isBlank(command)) {
                throw new IllegalArgumentException("MCP stdio transport 必须提供 command。");
            }
            return;
        }
        if (StrUtil.isBlank(endpoint)) {
            throw new IllegalArgumentException("MCP " + transport + " transport 必须提供 endpoint。");
        }
    }

    /**
     * 执行check相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回check结果。
     */
    public Map<String, Object> check(String serverId) throws Exception {
        McpCheckState state = checkServer(serverId, false);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("status", state.getStatus());
        result.put("schema_sanitizer", "snack4");
        result.put("tools_hash", state.getNextHash());
        result.put("tool_changed_notification", state.isToolsChanged());
        result.put("added_tools", safeToolNames(state.getAddedTools()));
        result.put("removed_tools", safeToolNames(state.getRemovedTools()));
        result.put("security", securityMap(state.getSecurityVerdict()));
        if (StrUtil.isNotBlank(state.getError())) {
            result.put("error", safeDisplayError(state.getError()));
        }
        return result;
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回connect结果。
     */
    public Map<String, Object> connect(String serverId) throws Exception {
        McpRuntimeService.McpToolRefreshResult state = mcpRuntimeService.connect(serverId);
        return runtimeRefreshMap(state, "connected");
    }

    /**
     * 重新加载目标服务端配置与工具清单。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回reload结果。
     */
    public Map<String, Object> reload(String serverId) throws Exception {
        McpRuntimeService.McpToolRefreshResult state = mcpRuntimeService.reload(serverId);
        return runtimeRefreshMap(state, "reloaded");
    }

    /**
     * 刷新工具。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回工具结果。
     */
    public Map<String, Object> refreshTools(String serverId) throws Exception {
        McpRuntimeService.McpToolRefreshResult state =
                appConfig.getMcp().isEnabled()
                        ? mcpRuntimeService.refreshLiveTools(serverId, false)
                        : mcpRuntimeService.refreshPersistedTools(
                                serverId, false, "disabled", "MCP is disabled in workspace config.");
        return runtimeRefreshMap(state, "refreshed");
    }

    /**
     * 执行oauth状态相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回oauth状态。
     */
    public Map<String, Object> oauthStatus(String serverId) throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("enabled", asBoolean(oauth.get("enabled"), false));
        result.put("provider", string(oauth.get("provider")));
        result.put("auth_type", string(oauth.get("auth_type")));
        result.put("status", oauthStatusValue(oauth));
        result.put("authenticated", Boolean.valueOf(oauthAuthenticated(oauth)));
        result.put("has_access_token", Boolean.valueOf(hasSecret(oauth, "access_token")));
        result.put("has_refresh_token", Boolean.valueOf(hasSecret(oauth, "refresh_token")));
        result.put("has_client_secret", Boolean.valueOf(hasSecret(oauth, "client_secret")));
        Long expiresAt = expiresAt(oauth);
        result.put("expires_at", expiresAt);
        result.put(
                "expires_in_seconds",
                expiresAt == null
                        ? null
                        : Long.valueOf(
                                Math.max(
                                        0L,
                                        (expiresAt.longValue() - System.currentTimeMillis())
                                                / 1000L)));
        Object scopes = oauth.get("scopes");
        if (scopes != null) {
            result.put("scopes", scopes);
        }
        result.put("oauth", sanitizeOAuth(oauth));
        return result;
    }

    /**
     * 清理OAuth 认证。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回OAuth 认证结果。
     */
    public Map<String, Object> clearOAuth(String serverId) throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        Map<String, Object> cleared = sanitizeOAuth(oauth);
        cleared.remove("has_access_token");
        cleared.remove("has_refresh_token");
        cleared.remove("has_id_token");
        cleared.remove("has_token");
        cleared.remove("has_client_secret");
        cleared.remove("has_code_verifier");
        cleared.put("status", "cleared");
        cleared.put("cleared_at", Long.valueOf(System.currentTimeMillis()));
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update mcp_servers set oauth_json = ?, updated_at = ? where server_id = ?");
            statement.setString(1, json(cleared));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, serverId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated == 0) {
                throw new IllegalArgumentException("MCP server not found: " + serverId);
            }
        } finally {
            connection.close();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("cleared", Boolean.TRUE);
        result.put("oauth", cleared);
        return result;
    }

    /**
     * 执行beginOAuth 认证相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param body 请求体或消息正文内容。
     * @return 返回begin OAuth 认证结果。
     */
    public Map<String, Object> beginOAuth(String serverId, Map<String, Object> body)
            throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        if (body != null) {
            oauth.putAll(body);
        }
        String authorizationEndpoint =
                firstText(
                        oauth,
                        "authorization_endpoint",
                        "authorizationEndpoint",
                        "auth_url",
                        "authorizationUrl");
        String clientId = firstText(oauth, "client_id", "clientId");
        String redirectUri = firstText(oauth, "redirect_uri", "redirectUri");
        if (StrUtil.isBlank(authorizationEndpoint)) {
            throw new IllegalStateException("authorization_endpoint is required for MCP OAuth");
        }
        assertSafeRuntimeUrl(authorizationEndpoint, "MCP OAuth authorization_endpoint");
        if (StrUtil.isBlank(clientId)) {
            throw new IllegalStateException("client_id is required for MCP OAuth");
        }
        if (StrUtil.isBlank(redirectUri)) {
            redirectUri = "http://127.0.0.1:8765/api/mcp/" + serverId + "/oauth/callback";
        }
        String state = randomBase64Url(32);
        String codeVerifier = randomBase64Url(32);
        String codeChallenge = codeChallenge(codeVerifier);
        String scope = scopeText(oauth.get("scopes"));
        long now = System.currentTimeMillis();
        clearOAuthSecrets(oauth);
        oauth.put("enabled", Boolean.TRUE);
        oauth.put(
                "auth_type", StrUtil.blankToDefault(string(oauth.get("auth_type")), "oauth_pkce"));
        oauth.put("status", "pending");
        oauth.put("authorization_endpoint", authorizationEndpoint);
        oauth.put("client_id", clientId);
        oauth.put("redirect_uri", redirectUri);
        oauth.put("state", state);
        oauth.put("code_verifier", codeVerifier);
        oauth.put("code_challenge", codeChallenge);
        oauth.put("code_challenge_method", "S256");
        oauth.put("started_at", Long.valueOf(now));
        updateOAuth(serverId, oauth, now);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("status", "pending");
        result.put("state", state);
        result.put(
                "authorization_url",
                authorizationUrl(
                        authorizationEndpoint, clientId, redirectUri, state, codeChallenge, scope));
        result.put("code_challenge_method", "S256");
        result.put("redirect_uri", redirectUri);
        if (StrUtil.isNotBlank(scope)) {
            result.put("scope", scope);
        }
        result.put("oauth", sanitizeOAuth(oauth));
        return result;
    }

    /**
     * 执行completeOAuth 认证相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param body 请求体或消息正文内容。
     * @return 返回complete OAuth 认证结果。
     */
    public Map<String, Object> completeOAuth(String serverId, Map<String, Object> body)
            throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        String expectedState = string(oauth.get("state"));
        String actualState = firstText(body, "state");
        String code = firstText(body, "code");
        String error = firstText(body, "error");
        if (StrUtil.isNotBlank(error)) {
            throw new IllegalStateException(
                    "MCP OAuth authorization failed: " + safeDisplayError(error));
        }
        if (StrUtil.isBlank(code)) {
            throw new IllegalStateException("code is required for MCP OAuth callback");
        }
        if (StrUtil.isBlank(expectedState)) {
            throw new IllegalStateException("MCP OAuth flow is not pending");
        }
        if (!expectedState.equals(actualState)) {
            throw new IllegalStateException("MCP OAuth state mismatch");
        }
        String tokenEndpoint =
                firstText(body, "token_endpoint", "tokenEndpoint", "token_url", "tokenUrl");
        if (StrUtil.isBlank(tokenEndpoint)) {
            tokenEndpoint =
                    firstText(oauth, "token_endpoint", "tokenEndpoint", "token_url", "tokenUrl");
        }
        String clientId = firstText(oauth, "client_id", "clientId");
        String redirectUri = firstText(oauth, "redirect_uri", "redirectUri");
        String codeVerifier = string(oauth.get("code_verifier"));
        if (StrUtil.isBlank(tokenEndpoint)) {
            throw new IllegalStateException("token_endpoint is required for MCP OAuth callback");
        }
        assertSafeRuntimeUrl(tokenEndpoint, "MCP OAuth token_endpoint");
        if (StrUtil.isBlank(clientId)) {
            throw new IllegalStateException("client_id is required for MCP OAuth callback");
        }
        if (StrUtil.isBlank(codeVerifier)) {
            throw new IllegalStateException("code_verifier is missing for MCP OAuth callback");
        }
        Map<String, Object> tokenResponse =
                exchangeOAuthCode(tokenEndpoint, clientId, redirectUri, code, codeVerifier, oauth);
        mergeTokenResponse(oauth, tokenResponse);
        oauth.put("enabled", Boolean.TRUE);
        oauth.put("status", "authenticated");
        oauth.put("token_endpoint", tokenEndpoint);
        oauth.put("authenticated_at", Long.valueOf(System.currentTimeMillis()));
        oauth.remove("state");
        oauth.remove("code_verifier");
        oauth.remove("code_challenge");
        oauth.remove("code_challenge_method");
        updateOAuth(serverId, oauth, System.currentTimeMillis());

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("status", oauthStatusValue(oauth));
        result.put("authenticated", Boolean.valueOf(oauthAuthenticated(oauth)));
        result.put("has_access_token", Boolean.valueOf(hasSecret(oauth, "access_token")));
        result.put("has_refresh_token", Boolean.valueOf(hasSecret(oauth, "refresh_token")));
        result.put("oauth", sanitizeOAuth(oauth));
        return result;
    }

    /**
     * 刷新OAuth 认证。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回OAuth 认证结果。
     */
    public Map<String, Object> refreshOAuth(String serverId) throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        if (!asBoolean(oauth.get("enabled"), false)) {
            throw new IllegalStateException("MCP OAuth is disabled for server: " + serverId);
        }
        String tokenEndpoint =
                firstText(oauth, "token_endpoint", "tokenEndpoint", "token_url", "tokenUrl");
        String clientId = firstText(oauth, "client_id", "clientId");
        String refreshToken = string(oauth.get("refresh_token"));
        if (StrUtil.isBlank(tokenEndpoint)) {
            throw new IllegalStateException("token_endpoint is required for MCP OAuth refresh");
        }
        assertSafeRuntimeUrl(tokenEndpoint, "MCP OAuth token_endpoint");
        if (StrUtil.isBlank(clientId)) {
            throw new IllegalStateException("client_id is required for MCP OAuth refresh");
        }
        if (StrUtil.isBlank(refreshToken)) {
            throw new IllegalStateException("refresh_token is required for MCP OAuth refresh");
        }
        Map<String, Object> tokenResponse =
                exchangeRefreshToken(tokenEndpoint, clientId, refreshToken, oauth);
        mergeTokenResponse(oauth, tokenResponse);
        if (!tokenResponse.containsKey("refresh_token")) {
            oauth.put("refresh_token", refreshToken);
        }
        oauth.put("enabled", Boolean.TRUE);
        oauth.put("status", "authenticated");
        oauth.put("refreshed_at", Long.valueOf(System.currentTimeMillis()));
        updateOAuth(serverId, oauth, System.currentTimeMillis());
        Map<String, Object> result = oauthStatus(serverId);
        result.put("refreshed", Boolean.TRUE);
        result.put("reconnect_required", Boolean.TRUE);
        return result;
    }

    /**
     * 执行OAuth401相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回O Auth401结果。
     */
    public Map<String, Object> handleOAuth401(String serverId) throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        if (asBoolean(oauth.get("enabled"), false)
                && hasSecret(oauth, "refresh_token")
                && StrUtil.isNotBlank(
                        firstText(
                                oauth,
                                "token_endpoint",
                                "tokenEndpoint",
                                "token_url",
                                "tokenUrl"))) {
            try {
                Map<String, Object> refreshed = refreshOAuth(serverId);
                refreshed.put("recovered", Boolean.TRUE);
                refreshed.put("needs_reauth", Boolean.FALSE);
                return refreshed;
            } catch (Exception e) {
                return needsReauth(serverId, "refresh_failed", safeError(e));
            }
        }
        return needsReauth(
                serverId, "missing_refresh_token", "MCP server requires re-authentication.");
    }

    /**
     * 执行reload全部相关逻辑。
     *
     * @return 返回reload全部结果。
     */
    public McpReloadResult reloadAll() throws Exception {
        return reloadAll(true);
    }

    /**
     * 执行reload全部相关逻辑。
     *
     * @param baselineInitial baselineInitial 参数。
     * @return 返回reload全部结果。
     */
    private McpReloadResult reloadAll(boolean baselineInitial) throws Exception {
        List<String> serverIds = enabledServerIds();
        List<String> changedServers = new ArrayList<String>();
        List<String> unchangedServers = new ArrayList<String>();
        int toolCount = 0;
        for (String serverId : serverIds) {
            McpCheckState state = checkServer(serverId, baselineInitial);
            toolCount += state.getToolCount();
            if (state.isToolsChanged()) {
                changedServers.add(serverId);
            } else {
                unchangedServers.add(serverId);
            }
        }
        return new McpReloadResult(
                appConfig.getMcp().isEnabled(), changedServers, unchangedServers, toolCount);
    }

    /**
     * 执行reload全部视图相关逻辑。
     *
     * @return 返回reload全部视图。
     */
    public Map<String, Object> reloadAllView() throws Exception {
        McpReloadResult result = reloadAll(false);
        return reloadResultMap(result);
    }

    /**
     * 执行reload全部异步视图相关逻辑。
     *
     * @return 返回reload全部Async视图。
     */
    public Map<String, Object> reloadAllAsyncView() throws Exception {
        List<String> serverIds = enabledServerIds();
        CompletableFuture<List<McpRuntimeService.McpToolRefreshResult>> future =
                mcpRuntimeService.refreshAllEnabledLiveToolsAsync(false);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", Boolean.valueOf(appConfig.getMcp().isEnabled()));
        map.put("status", future.isCompletedExceptionally() ? "failed" : "queued");
        map.put("async", Boolean.TRUE);
        if (future.isCompletedExceptionally()) {
            map.put("error", asyncFailureMessage(future));
        }
        map.put("server_count", Integer.valueOf(serverIds.size()));
        map.put("server_ids", serverIds);
        return map;
    }

    /**
     * 执行异步Failure消息相关逻辑。
     *
     * @param future future 参数。
     * @return 返回async Failure消息结果。
     */
    private String asyncFailureMessage(CompletableFuture<?> future) {
        try {
            future.get();
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SecretRedactor.redact(e.getMessage());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = StrUtil.blankToDefault(cause.getMessage(), cause.toString());
            return SecretRedactor.redact(message);
        }
    }

    /**
     * 执行启用状态服务端标识相关逻辑。
     *
     * @return 返回enabled Server标识。
     */
    private List<String> enabledServerIds() throws Exception {
        List<String> serverIds = new ArrayList<String>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select server_id from mcp_servers where enabled != 0 order by name asc");
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    serverIds.add(resultSet.getString("server_id"));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return serverIds;
    }

    /**
     * 执行reload结果映射相关逻辑。
     *
     * @param result 结果响应或执行结果。
     * @return 返回reload结果Map结果。
     */
    private Map<String, Object> reloadResultMap(McpReloadResult result) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", Boolean.valueOf(result.isEnabled()));
        map.put("tool_count", Integer.valueOf(result.getToolCount()));
        map.put("changed_servers", result.getChangedServers());
        map.put("unchanged_servers", result.getUnchangedServers());
        map.put(
                "tool_changed_notification",
                Boolean.valueOf(!result.getChangedServers().isEmpty()));
        map.put("changed_count", Integer.valueOf(result.getChangedServers().size()));
        map.put("unchanged_count", Integer.valueOf(result.getUnchangedServers().size()));
        map.put(
                "server_count",
                Integer.valueOf(
                        result.getChangedServers().size() + result.getUnchangedServers().size()));
        return map;
    }

    /**
     * 检查Server。
     *
     * @param serverId MCP 服务端标识。
     * @param baselineInitial baselineInitial 参数。
     * @return 返回Server结果。
     */
    private McpCheckState checkServer(String serverId, boolean baselineInitial) throws Exception {
        String previousHash = "";
        String previousToolsJson = "";
        String toolsJson = null;
        String command = "";
        Object args = null;
        boolean found = false;
        Connection connection = database.openConnection();
        try {
            PreparedStatement query =
                    connection.prepareStatement(
                            "select command, args_json, tools_json, last_tools_hash, last_tools_json from mcp_servers where server_id = ?");
            query.setString(1, serverId);
            ResultSet queryResult = query.executeQuery();
            try {
                if (queryResult.next()) {
                    found = true;
                    command = queryResult.getString("command");
                    args = parse(queryResult.getString("args_json"));
                    toolsJson = queryResult.getString("tools_json");
                    previousHash = StrUtil.nullToEmpty(queryResult.getString("last_tools_hash"));
                    previousToolsJson =
                            StrUtil.nullToEmpty(queryResult.getString("last_tools_json"));
                }
            } finally {
                queryResult.close();
                query.close();
            }
            if (!found) {
                throw new IllegalArgumentException("MCP server not found: " + serverId);
            }
            McpPackageSecurityService.SecurityVerdict securityVerdict =
                    checkPackageSecurity(command, args);
            if (securityVerdict.isAllowed() && appConfig.getMcp().isEnabled()) {
                try {
                    McpRuntimeService.McpToolRefreshResult refresh =
                            mcpRuntimeService.refreshLiveTools(serverId, baselineInitial);
                    return new McpCheckState(
                            refresh.getNextHash(),
                            refresh.isToolsChanged(),
                            refresh.getToolCount(),
                            refresh.getAddedTools(),
                            refresh.getRemovedTools(),
                            securityVerdict,
                            refresh.getStatus(),
                            refresh.getError());
                } catch (Exception e) {
                    String error = safeError(e);
                    McpRuntimeService.McpToolRefreshResult refresh =
                            mcpRuntimeService.refreshPersistedTools(
                                    serverId, baselineInitial, "error", error);
                    return new McpCheckState(
                            refresh.getNextHash(),
                            refresh.isToolsChanged(),
                            refresh.getToolCount(),
                            refresh.getAddedTools(),
                            refresh.getRemovedTools(),
                            securityVerdict,
                            "error",
                            error);
                }
            }
            String nextHash = hash(toolsJson);
            boolean initialBaseline =
                    securityVerdict.isAllowed() && baselineInitial && StrUtil.isBlank(previousHash);
            boolean toolsChanged =
                    securityVerdict.isAllowed()
                            && StrUtil.isNotBlank(nextHash)
                            && !nextHash.equals(previousHash)
                            && !initialBaseline;
            List<String> previousTools = toolNames(previousToolsJson);
            List<String> nextTools = toolNames(toolsJson);
            List<String> addedTools =
                    toolsChanged
                            ? difference(nextTools, previousTools)
                            : Collections.<String>emptyList();
            List<String> removedTools =
                    toolsChanged
                            ? difference(previousTools, nextTools)
                            : Collections.<String>emptyList();
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update mcp_servers set status = ?, last_error = ?, last_checked_at = ?, updated_at = ?, last_tools_hash = ?, last_tools_json = ?, last_tools_changed_at = case when ? then ? else last_tools_changed_at end where server_id = ?");
            statement.setString(
                    1,
                    securityVerdict.isAllowed()
                            ? (appConfig.getMcp().isEnabled() ? "ready" : "disabled")
                            : "blocked");
            statement.setString(
                    2,
                    securityVerdict.isAllowed()
                            ? (appConfig.getMcp().isEnabled()
                                    ? null
                                    : "MCP is disabled in workspace config.")
                            : safeDisplayError(securityVerdict.getMessage()));
            long now = System.currentTimeMillis();
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.setString(5, nextHash);
            statement.setString(6, toolsJson);
            statement.setInt(7, toolsChanged ? 1 : 0);
            statement.setLong(8, now);
            statement.setString(9, serverId);
            statement.executeUpdate();
            statement.close();
            return new McpCheckState(
                    nextHash,
                    toolsChanged,
                    countTools(toolsJson),
                    addedTools,
                    removedTools,
                    securityVerdict,
                    securityVerdict.isAllowed()
                            ? (appConfig.getMcp().isEnabled() ? "ready" : "disabled")
                            : "blocked",
                    securityVerdict.isAllowed()
                            ? (appConfig.getMcp().isEnabled()
                                    ? null
                                    : "MCP is disabled in workspace config.")
                            : safeDisplayError(securityVerdict.getMessage()));
        } finally {
            connection.close();
        }
    }

    /**
     * 执行delete，服务于控制台MCP主流程相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回delete结果。
     */
    public Map<String, Object> delete(String serverId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("delete from mcp_servers where server_id = ?");
            statement.setString(1, serverId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        return Collections.singletonMap("ok", true);
    }

    /**
     * 执行map相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map结果。
     */
    private Map<String, Object> map(ResultSet resultSet) throws Exception {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("server_id", resultSet.getString("server_id"));
        map.put("name", resultSet.getString("name"));
        map.put("transport", resultSet.getString("transport"));
        map.put("endpoint", SecretRedactor.maskUrl(resultSet.getString("endpoint")));
        map.put("command", SecretRedactor.redact(resultSet.getString("command"), 800));
        map.put("args", redactParsed(parse(resultSet.getString("args_json"))));
        map.put("auth", redactParsed(parse(resultSet.getString("auth_json"))));
        map.put("oauth", sanitizeOAuth(parseMap(resultSet.getString("oauth_json"))));
        map.put("capabilities", parse(resultSet.getString("capabilities_json")));
        map.put("status", resultSet.getString("status"));
        map.put("tools", redactParsed(parse(resultSet.getString("tools_json"))));
        map.put("last_tools_hash", resultSet.getString("last_tools_hash"));
        String lastError = SecretRedactor.redact(resultSet.getString("last_error"), 1000);
        map.put("last_error", lastError);
        map.put("security", persistedSecurityMap(resultSet.getString("status"), lastError));
        map.put("enabled", resultSet.getInt("enabled") != 0);
        map.put("created_at", resultSet.getLong("created_at"));
        map.put("updated_at", resultSet.getLong("updated_at"));
        map.put("last_checked_at", resultSet.getLong("last_checked_at"));
        map.put("last_tools_changed_at", resultSet.getLong("last_tools_changed_at"));
        return map;
    }

    /**
     * 执行persisted安全映射相关逻辑。
     *
     * @param status 状态参数。
     * @param lastError last错误参数。
     * @return 返回persisted安全Map结果。
     */
    private Map<String, Object> persistedSecurityMap(String status, String lastError) {
        Map<String, Object> security = new LinkedHashMap<String, Object>();
        boolean blocked = "blocked".equals(StrUtil.nullToEmpty(status));
        security.put("allowed", Boolean.valueOf(!blocked));
        security.put("reason", blocked ? inferPersistedSecurityReason(lastError) : "allow");
        if (blocked && StrUtil.isNotBlank(lastError)) {
            security.put("message", lastError);
        }
        return security;
    }

    /**
     * 执行inferPersisted安全原因相关逻辑。
     *
     * @param lastError last错误参数。
     * @return 返回infer Persisted安全Reason结果。
     */
    private String inferPersistedSecurityReason(String lastError) {
        String value = StrUtil.nullToEmpty(lastError);
        if (value.contains("OSV endpoint is unsafe")) {
            return "unsafe_endpoint";
        }
        if (value.contains("known malware advisories")) {
            return "malware_advisory";
        }
        return "blocked";
    }

    /**
     * 执行read相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @return 返回read结果。
     */
    private String read(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 执行JSON相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回JSON结果。
     */
    private String json(Object value) {
        return value == null ? null : ONode.serialize(value);
    }

    /**
     * 执行解析相关逻辑。
     *
     * @param json JSON参数。
     * @return 返回parse结果。
     */
    private Object parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return ONode.deserialize(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 脱敏Parsed。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Parsed结果。
     */
    @SuppressWarnings("unchecked")
    private Object redactParsed(Object value) {
        return redactParsed(value, false, "");
    }

    /**
     * 脱敏Parsed。
     *
     * @param value 待规范化或校验的原始值。
     * @param sensitiveContext sensitive上下文上下文。
     * @param key 配置键或映射键。
     * @return 返回Parsed结果。
     */
    @SuppressWarnings("unchecked")
    private Object redactParsed(Object value, boolean sensitiveContext, String key) {
        if (value instanceof String) {
            if (sensitiveContext && isSensitiveDisplayKey(key)) {
                return "***";
            }
            return SecretRedactor.redact((String) value, 800);
        }
        if (value instanceof Map) {
            Map<String, Object> redacted = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                redacted.put(
                        childKey,
                        redactParsed(
                                entry.getValue(),
                                sensitiveContext || isSensitiveKey(childKey),
                                childKey));
            }
            return redacted;
        }
        if (value instanceof List) {
            List<Object> redacted = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                redacted.add(redactParsed(item, sensitiveContext, key));
            }
            return redacted;
        }
        return value;
    }

    /**
     * 判断是否Sensitive键。
     *
     * @param key 配置键或映射键。
     * @return 如果Sensitive键满足条件则返回 true，否则返回 false。
     */
    private boolean isSensitiveKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase();
        return normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("access_token")
                || normalized.contains("refresh_token")
                || normalized.contains("auth_token")
                || normalized.contains("client_secret")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("credential")
                || normalized.contains("private_key")
                || normalized.contains("authorization");
    }

    /**
     * 判断是否Sensitive展示键。
     *
     * @param key 配置键或映射键。
     * @return 如果Sensitive展示键满足条件则返回 true，否则返回 false。
     */
    private boolean isSensitiveDisplayKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase();
        return normalized.length() == 0
                || "description".equals(normalized)
                || "title".equals(normalized)
                || "default".equals(normalized)
                || "example".equals(normalized)
                || "examples".equals(normalized)
                || "const".equals(normalized)
                || "enum".equals(normalized)
                || isSensitiveKey(normalized);
    }

    /**
     * 执行as布尔值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallback 兜底参数。
     * @return 返回as Boolean结果。
     */
    private boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    /**
     * 检查Package安全。
     *
     * @param command 待执行或解析的命令文本。
     * @param args 工具或命令参数。
     * @return 返回Package安全结果。
     */
    private McpPackageSecurityService.SecurityVerdict checkPackageSecurity(
            String command, Object args) {
        if (packageSecurityService == null) {
            return McpPackageSecurityService.SecurityVerdict.allow();
        }
        return packageSecurityService.check(command, args);
    }

    /**
     * 执行安全映射相关逻辑。
     *
     * @param verdict 判定参数。
     * @return 返回安全Map结果。
     */
    private Map<String, Object> securityMap(McpPackageSecurityService.SecurityVerdict verdict) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("allowed", verdict == null || verdict.isAllowed());
        if (verdict != null) {
            map.put("reason", verdict.getReason());
        }
        if (verdict != null && StrUtil.isNotBlank(verdict.getMessage())) {
            map.put("message", safeDisplayError(verdict.getMessage()));
        }
        return map;
    }

    /**
     * 执行哈希相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回hash结果。
     */
    private String hash(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder buffer = new StringBuilder();
            for (byte item : bytes) {
                buffer.append(String.format("%02x", item & 0xff));
            }
            return buffer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash MCP tools", e);
        }
    }

    /**
     * 执行oauth映射相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回oauth Map结果。
     */
    private Map<String, Object> oauthMap(String serverId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select oauth_json from mcp_servers where server_id = ?");
            statement.setString(1, serverId);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("MCP server not found: " + serverId);
                }
                return parseMap(resultSet.getString("oauth_json"));
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 更新OAuth 认证。
     *
     * @param serverId MCP 服务端标识。
     * @param oauth oauth 参数。
     * @param now 当前时间戳。
     */
    private void updateOAuth(String serverId, Map<String, Object> oauth, long now)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update mcp_servers set oauth_json = ?, updated_at = ? where server_id = ?");
            statement.setString(1, json(oauth));
            statement.setLong(2, now);
            statement.setString(3, serverId);
            int updated = statement.executeUpdate();
            statement.close();
            if (updated == 0) {
                throw new IllegalArgumentException("MCP server not found: " + serverId);
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 解析Map。
     *
     * @param json JSON参数。
     * @return 返回解析后的Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object data = parse(json);
        if (data instanceof Map) {
            result.putAll((Map<String, Object>) data);
        }
        return result;
    }

    /**
     * 清理OAuth 认证。
     *
     * @param oauth oauth 参数。
     * @return 返回OAuth 认证结果。
     */
    private Map<String, Object> sanitizeOAuth(Map<String, Object> oauth) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (oauth == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : oauth.entrySet()) {
            String key = entry.getKey();
            if (isSecretOAuthKey(key)) {
                if (entry.getValue() != null
                        && StrUtil.isNotBlank(String.valueOf(entry.getValue()))) {
                    result.put("has_" + key, Boolean.TRUE);
                }
            } else if (isOAuthDisplayErrorKey(key)) {
                result.put(key, safeDisplayError(string(entry.getValue())));
            } else {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    /**
     * 判断是否密钥OAuth 认证键。
     *
     * @param key 配置键或映射键。
     * @return 如果密钥OAuth 认证键满足条件则返回 true，否则返回 false。
     */
    private boolean isSecretOAuthKey(String key) {
        return "access_token".equals(key)
                || "refresh_token".equals(key)
                || "id_token".equals(key)
                || "token".equals(key)
                || "client_secret".equals(key)
                || "code_verifier".equals(key);
    }

    /**
     * 判断是否OAuth 认证展示Error键。
     *
     * @param key 配置键或映射键。
     * @return 如果OAuth 认证展示Error键满足条件则返回 true，否则返回 false。
     */
    private boolean isOAuthDisplayErrorKey(String key) {
        return "error".equals(key)
                || "error_description".equals(key)
                || "errorDescription".equals(key)
                || "message".equals(key)
                || "last_error".equals(key)
                || "lastError".equals(key)
                || "authorization_error".equals(key)
                || "authorizationError".equals(key);
    }

    /**
     * 清理OAuth 认证Secrets。
     *
     * @param oauth oauth 参数。
     */
    private void clearOAuthSecrets(Map<String, Object> oauth) {
        oauth.remove("access_token");
        oauth.remove("refresh_token");
        oauth.remove("id_token");
        oauth.remove("token");
        oauth.remove("client_secret");
        oauth.remove("expires_at");
        oauth.remove("expiresAt");
        oauth.remove("expires");
    }

    /**
     * 执行exchangeOAuth 认证Code相关逻辑。
     *
     * @param tokenEndpoint tokenEndpoint 参数。
     * @param clientId client标识。
     * @param redirectUri 文件或目录路径参数。
     * @param code code 参数。
     * @param codeVerifier codeVerifier 参数。
     * @param oauth oauth 参数。
     * @return 返回exchange OAuth 认证Code结果。
     */
    private Map<String, Object> exchangeOAuthCode(
            String tokenEndpoint,
            String clientId,
            String redirectUri,
            String code,
            String codeVerifier,
            Map<String, Object> oauth)
            throws Exception {
        assertSafeRuntimeUrl(tokenEndpoint, "MCP OAuth token_endpoint");
        StringBuilder form = new StringBuilder();
        appendForm(form, "grant_type", "authorization_code");
        appendForm(form, "code", code);
        appendForm(form, "client_id", clientId);
        if (StrUtil.isNotBlank(redirectUri)) {
            appendForm(form, "redirect_uri", redirectUri);
        }
        appendForm(form, "code_verifier", codeVerifier);

        String clientSecret = string(oauth.get("client_secret"));
        if (StrUtil.isNotBlank(clientSecret)) {
            appendForm(form, "client_secret", clientSecret);
        }
        HttpResponse response =
                executeOAuthTokenRequest(tokenEndpoint, form.toString(), 0, tokenEndpoint);
        try {
            String responseBody = response.body();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IllegalStateException(
                        "MCP OAuth token exchange failed: HTTP "
                                + response.getStatus()
                                + " "
                                + safeTokenError(responseBody));
            }
            Map<String, Object> tokenResponse = parseMap(responseBody);
            if (StrUtil.isBlank(string(tokenResponse.get("access_token")))) {
                throw new IllegalStateException(
                        "MCP OAuth token response missing access_token: "
                                + safeTokenError(responseBody));
            }
            return tokenResponse;
        } finally {
            response.close();
        }
    }

    /**
     * 执行exchange刷新token相关逻辑。
     *
     * @param tokenEndpoint tokenEndpoint 参数。
     * @param clientId client标识。
     * @param refreshToken refreshtoken参数。
     * @param oauth oauth 参数。
     * @return 返回exchange刷新token结果。
     */
    private Map<String, Object> exchangeRefreshToken(
            String tokenEndpoint, String clientId, String refreshToken, Map<String, Object> oauth)
            throws Exception {
        assertSafeRuntimeUrl(tokenEndpoint, "MCP OAuth token_endpoint");
        StringBuilder form = new StringBuilder();
        appendForm(form, "grant_type", "refresh_token");
        appendForm(form, "refresh_token", refreshToken);
        appendForm(form, "client_id", clientId);

        String clientSecret = string(oauth.get("client_secret"));
        if (StrUtil.isNotBlank(clientSecret)) {
            appendForm(form, "client_secret", clientSecret);
        }
        HttpResponse response =
                executeOAuthTokenRequest(tokenEndpoint, form.toString(), 0, tokenEndpoint);
        try {
            String responseBody = response.body();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                throw new IllegalStateException(
                        "MCP OAuth refresh failed: HTTP "
                                + response.getStatus()
                                + " "
                                + safeTokenError(responseBody));
            }
            Map<String, Object> tokenResponse = parseMap(responseBody);
            if (StrUtil.isBlank(string(tokenResponse.get("access_token")))) {
                throw new IllegalStateException(
                        "MCP OAuth refresh response missing access_token: "
                                + safeTokenError(responseBody));
            }
            return tokenResponse;
        } finally {
            response.close();
        }
    }

    /**
     * 执行OAuth 认证token请求。
     *
     * @param url 待校验或访问的 URL。
     * @param form form 参数。
     * @param redirectCount 文件或目录路径参数。
     * @param initialUrl 待校验或访问的地址参数。
     * @return 返回OAuth 认证token请求结果。
     */
    private HttpResponse executeOAuthTokenRequest(
            String url, String form, int redirectCount, String initialUrl) {
        assertSafeRuntimeUrl(url, "MCP OAuth token_endpoint");
        HttpRequest request =
                HttpRequest.post(url)
                        .timeout(15000)
                        .contentType("application/x-www-form-urlencoded")
                        .setFollowRedirects(false);
        if (UrlOriginSupport.sameOrigin(initialUrl, url)) {
            request.body(form);
        }
        HttpResponse response = request.execute();
        int status = response.getStatus();
        if (!isRedirect(status)) {
            return response;
        }
        try {
            if (redirectCount >= MAX_OAUTH_TOKEN_REDIRECTS) {
                throw new IllegalStateException(
                        "MCP OAuth token_endpoint redirect count exceeds limit");
            }
            String location = response.header("Location");
            if (StrUtil.isBlank(location)) {
                throw new IllegalStateException(
                        "MCP OAuth token_endpoint redirect missing Location");
            }
            String nextUrl = resolveRedirectUrl(url, location);
            response.close();
            return executeOAuthTokenRequest(nextUrl, form, redirectCount + 1, initialUrl);
        } catch (RuntimeException e) {
            response.close();
            throw e;
        }
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
                    "MCP OAuth token_endpoint redirect URL is invalid: "
                            + SecretRedactor.maskUrl(location),
                    e);
        }
    }

    /**
     * 合并token响应。
     *
     * @param oauth oauth 参数。
     * @param tokenResponse token响应响应或执行结果。
     */
    private void mergeTokenResponse(Map<String, Object> oauth, Map<String, Object> tokenResponse) {
        oauth.put("access_token", tokenResponse.get("access_token"));
        copyIfPresent(tokenResponse, oauth, "refresh_token");
        copyIfPresent(tokenResponse, oauth, "id_token");
        copyIfPresent(tokenResponse, oauth, "token_type");
        copyIfPresent(tokenResponse, oauth, "scope");
        copyIfPresent(tokenResponse, oauth, "scopes");
        Object expiresIn = tokenResponse.get("expires_in");
        if (expiresIn != null) {
            try {
                long seconds =
                        expiresIn instanceof Number
                                ? ((Number) expiresIn).longValue()
                                : Long.parseLong(String.valueOf(expiresIn));
                oauth.put(
                        "expires_at",
                        Long.valueOf(System.currentTimeMillis() + Math.max(0L, seconds) * 1000L));
            } catch (Exception e) {
                log.debug("MCP OAuth expires_in解析失败，保留显式expires_at优先兜底 error={}",
                        e.getClass().getSimpleName());
            }
        }
        Object expiresAt = firstPresent(tokenResponse, "expires_at", "expiresAt", "expires");
        if (expiresAt != null) {
            oauth.put("expires_at", expiresAt);
        }
    }

    /**
     * 复制If Present。
     *
     * @param source 来源参数。
     * @param target target 参数。
     * @param key 配置键或映射键。
     */
    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    /**
     * 追加Form。
     *
     * @param form form 参数。
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     */
    private void appendForm(StringBuilder form, String key, String value) throws Exception {
        if (form.length() > 0) {
            form.append('&');
        }
        form.append(urlEncode(key)).append('=').append(urlEncode(value));
    }

    /**
     * 生成安全展示用的token错误。
     *
     * @param responseBody 响应正文响应或执行结果。
     * @return 返回safe token Error结果。
     */
    private String safeTokenError(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return "";
        }
        Map<String, Object> error = sanitizeOAuth(parseMap(responseBody));
        String text = safeDisplayError(ONode.serialize(error), 500);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        if (StrUtil.isBlank(message) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return safeDisplayError(message, 500);
    }

    /**
     * 生成安全展示用的展示错误。
     *
     * @param message 平台消息或错误消息。
     * @return 返回safe展示Error结果。
     */
    private String safeDisplayError(String message) {
        return safeDisplayError(message, 1000);
    }

    /**
     * 生成安全展示用的展示错误。
     *
     * @param message 平台消息或错误消息。
     * @param maxLength 最大保留字符数。
     * @return 返回safe展示Error结果。
     */
    private String safeDisplayError(String message, int maxLength) {
        String safe = SecretRedactor.redact(StrUtil.nullToEmpty(message), maxLength);
        if (safe.length() > maxLength) {
            return safe.substring(0, maxLength);
        }
        return safe;
    }

    /**
     * 执行工作区配置刷新映射相关逻辑。
     *
     * @param state 状态参数。
     * @param action 操作参数。
     * @return 返回工作区配置刷新Map结果。
     */
    private Map<String, Object> runtimeRefreshMap(
            McpRuntimeService.McpToolRefreshResult state, String action) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", state.getServerId());
        result.put("action", action);
        result.put("status", state.getStatus());
        result.put("tools_hash", state.getNextHash());
        result.put("previous_tool_count", Integer.valueOf(state.getPreviousToolCount()));
        result.put("current_tool_count", Integer.valueOf(state.getToolCount()));
        result.put("tool_count", Integer.valueOf(state.getToolCount()));
        result.put("tool_changed_notification", Boolean.valueOf(state.isToolsChanged()));
        result.put("added_tools", safeToolNames(state.getAddedTools()));
        result.put("removed_tools", safeToolNames(state.getRemovedTools()));
        result.put("schema_sanitizer", "snack4");
        if (StrUtil.isNotBlank(state.getError())) {
            result.put("error", safeDisplayError(state.getError()));
        }
        return result;
    }

    /**
     * 执行needsReauth相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param reason 原因参数。
     * @param message 平台消息或错误消息。
     * @return 返回needs Reauth结果。
     */
    private Map<String, Object> needsReauth(String serverId, String reason, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("server_id", serverId);
        result.put("recovered", Boolean.FALSE);
        result.put("reconnect_required", Boolean.FALSE);
        result.put("needs_reauth", Boolean.TRUE);
        result.put("reason", reason);
        result.put(
                "error",
                safeDisplayError(
                        StrUtil.blankToDefault(message, "MCP server requires re-authentication.")));
        return result;
    }

    /**
     * 生成安全展示用的工具Names。
     *
     * @param toolNames 工具Names参数。
     * @return 返回safe工具Names结果。
     */
    private List<String> safeToolNames(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String toolName : toolNames) {
            result.add(SecretRedactor.redact(StrUtil.nullToEmpty(toolName), 400));
        }
        return result;
    }

    /**
     * 判断是否存在密钥。
     *
     * @param oauth oauth 参数。
     * @param key 配置键或映射键。
     * @return 如果密钥满足条件则返回 true，否则返回 false。
     */
    private boolean hasSecret(Map<String, Object> oauth, String key) {
        Object value = oauth == null ? null : oauth.get(key);
        return value != null && StrUtil.isNotBlank(String.valueOf(value));
    }

    /**
     * 执行oauthAuthenticated相关逻辑。
     *
     * @param oauth oauth 参数。
     * @return 返回oauth Authenticated结果。
     */
    private boolean oauthAuthenticated(Map<String, Object> oauth) {
        if (!asBoolean(oauth.get("enabled"), false)) {
            return false;
        }
        if (!hasSecret(oauth, "access_token")) {
            return false;
        }
        Long expiresAt = expiresAt(oauth);
        return expiresAt == null || expiresAt.longValue() > System.currentTimeMillis();
    }

    /**
     * 执行oauth状态值相关逻辑。
     *
     * @param oauth oauth 参数。
     * @return 返回oauth状态Value结果。
     */
    private String oauthStatusValue(Map<String, Object> oauth) {
        if (oauth == null || oauth.isEmpty()) {
            return "not_configured";
        }
        if (!asBoolean(oauth.get("enabled"), false)) {
            return "disabled";
        }
        if (!hasSecret(oauth, "access_token")) {
            return StrUtil.blankToDefault(string(oauth.get("status")), "pending");
        }
        Long expiresAt = expiresAt(oauth);
        if (expiresAt != null && expiresAt.longValue() <= System.currentTimeMillis()) {
            return "expired";
        }
        return "authenticated";
    }

    /**
     * 执行expires时间相关逻辑。
     *
     * @param oauth oauth 参数。
     * @return 返回expires时间结果。
     */
    private Long expiresAt(Map<String, Object> oauth) {
        Object value = firstPresent(oauth, "expires_at", "expiresAt", "expires");
        if (value == null) {
            return null;
        }
        try {
            long parsed =
                    value instanceof Number
                            ? ((Number) value).longValue()
                            : Long.parseLong(String.valueOf(value));
            if (parsed > 0L && parsed < 100000000000L) {
                parsed = parsed * 1000L;
            }
            return Long.valueOf(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行firstPresent相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first Present结果。
     */
    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    /**
     * 执行first文本相关逻辑。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first Text结果。
     */
    private String firstText(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        return string(value);
    }

    /**
     * 执行范围文本相关逻辑。
     *
     * @param scopes scopes 参数。
     * @return 返回范围Text结果。
     */
    private String scopeText(Object scopes) {
        if (scopes == null) {
            return "";
        }
        if (scopes instanceof Iterable) {
            StringBuilder buffer = new StringBuilder();
            for (Object scope : (Iterable<?>) scopes) {
                String text = string(scope);
                if (StrUtil.isBlank(text)) {
                    continue;
                }
                if (buffer.length() > 0) {
                    buffer.append(' ');
                }
                buffer.append(text);
            }
            return buffer.toString();
        }
        return String.valueOf(scopes).replace(',', ' ').trim();
    }

    /**
     * 执行授权URL相关逻辑。
     *
     * @param endpoint endpoint 参数。
     * @param clientId client标识。
     * @param redirectUri 文件或目录路径参数。
     * @param state 状态参数。
     * @param codeChallenge codeChallenge 参数。
     * @param scope scope 参数。
     * @return 返回授权URL结果。
     */
    private String authorizationUrl(
            String endpoint,
            String clientId,
            String redirectUri,
            String state,
            String codeChallenge,
            String scope)
            throws Exception {
        StringBuilder buffer = new StringBuilder(endpoint);
        buffer.append(endpoint.contains("?") ? "&" : "?");
        buffer.append("response_type=code");
        buffer.append("&client_id=").append(urlEncode(clientId));
        buffer.append("&redirect_uri=").append(urlEncode(redirectUri));
        buffer.append("&state=").append(urlEncode(state));
        buffer.append("&code_challenge=").append(urlEncode(codeChallenge));
        buffer.append("&code_challenge_method=S256");
        if (StrUtil.isNotBlank(scope)) {
            buffer.append("&scope=").append(urlEncode(scope));
        }
        return buffer.toString();
    }

    /**
     * 执行codeChallenge相关逻辑。
     *
     * @param verifier verifier 参数。
     * @return 返回code Challenge结果。
     */
    private String codeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * 执行randomBase64URL相关逻辑。
     *
     * @param bytes 字节参数。
     * @return 返回random Base64 URL结果。
     */
    private String randomBase64Url(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * 执行URLEncode相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回URL Encode结果。
     */
    private String urlEncode(String value) throws Exception {
        return URLEncoder.encode(StrUtil.nullToEmpty(value), "UTF-8").replace("+", "%20");
    }

    /**
     * 执行assert安全运行时URL相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @param label label 参数。
     */
    private void assertSafeRuntimeUrl(String url, String label) {
        if (StrUtil.isBlank(url)) {
            return;
        }
        SecurityPolicyService.UrlVerdict floorVerdict =
                securityPolicyService.checkAlwaysBlockedUrl(url);
        if (!floorVerdict.isAllowed()) {
            throw new IllegalArgumentException(
                    label
                            + " 被 URL 安全底线阻止："
                            + floorVerdict.getMessage()
                            + " URL: "
                            + SecretRedactor.maskUrl(floorVerdict.getUrl()));
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrlAllowingPrivate(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    label
                            + " 被 URL 安全策略阻止："
                            + verdict.getMessage()
                            + " URL: "
                            + SecretRedactor.maskUrl(verdict.getUrl()));
        }
    }

    /**
     * 执行string相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string结果。
     */
    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 执行次数工具相关逻辑。
     *
     * @param toolsJson toolsJSON参数。
     * @return 返回次数工具结果。
     */
    private int countTools(String toolsJson) {
        Object parsed = parse(toolsJson);
        if (parsed instanceof List) {
            return ((List<?>) parsed).size();
        }
        return StrUtil.isBlank(toolsJson) ? 0 : 1;
    }

    /**
     * 执行工具Names相关逻辑。
     *
     * @param toolsJson toolsJSON参数。
     * @return 返回工具Names结果。
     */
    @SuppressWarnings("unchecked")
    private List<String> toolNames(String toolsJson) {
        Object parsed = parse(toolsJson);
        List<String> result = new ArrayList<String>();
        if (!(parsed instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) item;
            String name = firstText(map, "prefixed_name", "name");
            if (StrUtil.isNotBlank(name) && !result.contains(name)) {
                result.add(name);
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 执行difference相关逻辑。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 返回difference结果。
     */
    private List<String> difference(List<String> left, List<String> right) {
        List<String> result = new ArrayList<String>();
        List<String> safeRight = right == null ? Collections.<String>emptyList() : right;
        if (left == null) {
            return result;
        }
        for (String item : left) {
            if (StrUtil.isNotBlank(item) && !safeRight.contains(item) && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /** 表示MCPCheck数据，在服务、仓储和接口之间传递。 */
    private static class McpCheckState {
        /** 记录MCPCheck中的next哈希。 */
        private final String nextHash;

        /** 是否启用工具Changed。 */
        private final boolean toolsChanged;

        /** 记录MCPCheck中的工具次数。 */
        private final int toolCount;

        /** 保存added工具集合，维持调用顺序或去重语义。 */
        private final List<String> addedTools;

        /** 保存removed工具集合，维持调用顺序或去重语义。 */
        private final List<String> removedTools;

        /** 注入安全判定，用于调用对应业务能力。 */
        private final McpPackageSecurityService.SecurityVerdict securityVerdict;

        /** 记录MCPCheck中的状态。 */
        private final String status;

        /** 记录MCPCheck中的错误。 */
        private final String error;

        /**
         * 创建MCP Check状态实例，并注入运行所需依赖。
         *
         * @param nextHash next哈希参数。
         * @param toolsChanged toolsChanged 参数。
         * @param toolCount 工具Count参数。
         * @param addedTools addedTools 参数。
         * @param removedTools removedTools 参数。
         * @param securityVerdict 待校验或访问的地址参数。
         * @param status 状态参数。
         * @param error 错误参数。
         */
        private McpCheckState(
                String nextHash,
                boolean toolsChanged,
                int toolCount,
                List<String> addedTools,
                List<String> removedTools,
                McpPackageSecurityService.SecurityVerdict securityVerdict,
                String status,
                String error) {
            this.nextHash = nextHash;
            this.toolsChanged = toolsChanged;
            this.toolCount = toolCount;
            this.addedTools =
                    addedTools == null
                            ? Collections.<String>emptyList()
                            : Collections.unmodifiableList(new ArrayList<String>(addedTools));
            this.removedTools =
                    removedTools == null
                            ? Collections.<String>emptyList()
                            : Collections.unmodifiableList(new ArrayList<String>(removedTools));
            this.securityVerdict = securityVerdict;
            this.status = status;
            this.error = error;
        }

        /**
         * 读取Next Hash。
         *
         * @return 返回读取到的Next Hash。
         */
        private String getNextHash() {
            return nextHash;
        }

        /**
         * 判断是否工具Changed。
         *
         * @return 如果工具Changed满足条件则返回 true，否则返回 false。
         */
        private boolean isToolsChanged() {
            return toolsChanged;
        }

        /**
         * 读取工具次数。
         *
         * @return 返回读取到的工具次数。
         */
        private int getToolCount() {
            return toolCount;
        }

        /**
         * 读取Added工具。
         *
         * @return 返回读取到的Added工具。
         */
        private List<String> getAddedTools() {
            return addedTools;
        }

        /**
         * 读取Removed工具。
         *
         * @return 返回读取到的Removed工具。
         */
        private List<String> getRemovedTools() {
            return removedTools;
        }

        /**
         * 读取安全Verdict。
         *
         * @return 返回读取到的安全Verdict。
         */
        private McpPackageSecurityService.SecurityVerdict getSecurityVerdict() {
            return securityVerdict;
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        private String getStatus() {
            return status;
        }

        /**
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        private String getError() {
            return error;
        }
    }

    /** 表示MCPReload结果，携带调用方后续判断所需信息。 */
    public static class McpReloadResult {
        /** 标记该配置项或记录是否处于启用状态。 */
        private final boolean enabled;

        /** 保存changed服务端集合，维持调用顺序或去重语义。 */
        private final List<String> changedServers;

        /** 保存unchanged服务端集合，维持调用顺序或去重语义。 */
        private final List<String> unchangedServers;

        /** 记录MCPReload中的工具次数。 */
        private final int toolCount;

        /**
         * 创建MCP Reload结果实例，并注入运行所需依赖。
         *
         * @param enabled 启用状态开关值。
         * @param changed服务端 changed服务端 参数。
         * @param unchanged服务端 unchanged服务端 参数。
         * @param toolCount 工具Count参数。
         */
        private McpReloadResult(
                boolean enabled,
                List<String> changedServers,
                List<String> unchangedServers,
                int toolCount) {
            this.enabled = enabled;
            this.changedServers = changedServers;
            this.unchangedServers = unchangedServers;
            this.toolCount = toolCount;
        }

        /**
         * 判断是否启用。
         *
         * @return 如果启用满足条件则返回 true，否则返回 false。
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 读取Changed 服务端。
         *
         * @return 返回读取到的Changed 服务端。
         */
        public List<String> getChangedServers() {
            return changedServers;
        }

        /**
         * 读取Unchanged 服务端。
         *
         * @return 返回读取到的Unchanged 服务端。
         */
        public List<String> getUnchangedServers() {
            return unchangedServers;
        }

        /**
         * 读取工具次数。
         *
         * @return 返回读取到的工具次数。
         */
        public int getToolCount() {
            return toolCount;
        }
    }
}
