package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
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
import org.noear.snack4.ONode;

/** Dashboard-first MCP server registry. */
public class DashboardMcpService {
    private static final int MAX_OAUTH_TOKEN_REDIRECTS = 5;
    private static final String TRANSPORT_STDIO = "stdio";
    private static final String TRANSPORT_HTTP = "http";
    private static final String TRANSPORT_STREAMABLE = "streamable";
    private static final String TRANSPORT_STREAMABLE_STATELESS = "streamable_stateless";
    private static final String TRANSPORT_SSE = "sse";

    private final AppConfig appConfig;
    private final SqliteDatabase database;
    private final McpPackageSecurityService packageSecurityService;
    private final McpRuntimeService mcpRuntimeService;
    private final SecurityPolicyService securityPolicyService;

    public DashboardMcpService(AppConfig appConfig, SqliteDatabase database) {
        this(appConfig, database, null, null);
    }

    public DashboardMcpService(
            AppConfig appConfig,
            SqliteDatabase database,
            McpPackageSecurityService packageSecurityService) {
        this(appConfig, database, packageSecurityService, null);
    }

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
                mcpRuntimeService == null ? new McpRuntimeService(appConfig, database) : mcpRuntimeService;
        this.securityPolicyService = new SecurityPolicyService(appConfig);
    }

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

    public Map<String, Object> list() throws Exception {
        List<Map<String, Object>> servers = new ArrayList<Map<String, Object>>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from mcp_servers order by updated_at desc");
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
                        ? checkPackageSecurity(read(body, "command"), body == null ? null : body.get("args"))
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
                    14, securityVerdict.isAllowed() ? null : safeDisplayError(securityVerdict.getMessage()));
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

    private String normalizeTransport(String rawTransport) {
        String transport = StrUtil.blankToDefault(rawTransport, TRANSPORT_STDIO)
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

    public Map<String, Object> connect(String serverId) throws Exception {
        McpRuntimeService.McpToolRefreshResult state = mcpRuntimeService.connect(serverId);
        return runtimeRefreshMap(state, "connected");
    }

    public Map<String, Object> reload(String serverId) throws Exception {
        McpRuntimeService.McpToolRefreshResult state = mcpRuntimeService.reload(serverId);
        return runtimeRefreshMap(state, "reloaded");
    }

    public Map<String, Object> refreshTools(String serverId) throws Exception {
        McpRuntimeService.McpToolRefreshResult state =
                appConfig.getMcp().isEnabled()
                        ? mcpRuntimeService.refreshLiveTools(serverId, false)
                        : mcpRuntimeService.refreshPersistedTools(
                                serverId, false, "disabled", "MCP is disabled in runtime config.");
        return runtimeRefreshMap(state, "refreshed");
    }

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
                        : Long.valueOf(Math.max(0L, (expiresAt.longValue() - System.currentTimeMillis()) / 1000L)));
        Object scopes = oauth.get("scopes");
        if (scopes != null) {
            result.put("scopes", scopes);
        }
        result.put("oauth", sanitizeOAuth(oauth));
        return result;
    }

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
            redirectUri = "http://127.0.0.1:8765/api/jimuqu/mcp/" + serverId + "/oauth/callback";
        }
        String state = randomBase64Url(32);
        String codeVerifier = randomBase64Url(32);
        String codeChallenge = codeChallenge(codeVerifier);
        String scope = scopeText(oauth.get("scopes"));
        long now = System.currentTimeMillis();
        clearOAuthSecrets(oauth);
        oauth.put("enabled", Boolean.TRUE);
        oauth.put("auth_type", StrUtil.blankToDefault(string(oauth.get("auth_type")), "oauth_pkce"));
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
        result.put("authorization_url", authorizationUrl(authorizationEndpoint, clientId, redirectUri, state, codeChallenge, scope));
        result.put("code_challenge_method", "S256");
        result.put("redirect_uri", redirectUri);
        if (StrUtil.isNotBlank(scope)) {
            result.put("scope", scope);
        }
        result.put("oauth", sanitizeOAuth(oauth));
        return result;
    }

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
                firstText(
                        body,
                        "token_endpoint",
                        "tokenEndpoint",
                        "token_url",
                        "tokenUrl");
        if (StrUtil.isBlank(tokenEndpoint)) {
            tokenEndpoint =
                    firstText(
                            oauth,
                            "token_endpoint",
                            "tokenEndpoint",
                            "token_url",
                            "tokenUrl");
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

    public Map<String, Object> refreshOAuth(String serverId) throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        if (!asBoolean(oauth.get("enabled"), false)) {
            throw new IllegalStateException("MCP OAuth is disabled for server: " + serverId);
        }
        String tokenEndpoint = firstText(oauth, "token_endpoint", "tokenEndpoint", "token_url", "tokenUrl");
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

    public Map<String, Object> handleOAuth401(String serverId) throws Exception {
        Map<String, Object> oauth = oauthMap(serverId);
        if (asBoolean(oauth.get("enabled"), false)
                && hasSecret(oauth, "refresh_token")
                && StrUtil.isNotBlank(firstText(oauth, "token_endpoint", "tokenEndpoint", "token_url", "tokenUrl"))) {
            try {
                Map<String, Object> refreshed = refreshOAuth(serverId);
                refreshed.put("recovered", Boolean.TRUE);
                refreshed.put("needs_reauth", Boolean.FALSE);
                return refreshed;
            } catch (Exception e) {
                return needsReauth(serverId, "refresh_failed", safeError(e));
            }
        }
        return needsReauth(serverId, "missing_refresh_token", "MCP server requires re-authentication.");
    }

    public McpReloadResult reloadAll() throws Exception {
        return reloadAll(true);
    }

    private McpReloadResult reloadAll(boolean baselineInitial) throws Exception {
        List<String> serverIds = new ArrayList<String>();
        List<String> changedServers = new ArrayList<String>();
        List<String> unchangedServers = new ArrayList<String>();
        int toolCount = 0;
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

    public Map<String, Object> reloadAllView() throws Exception {
        McpReloadResult result = reloadAll(false);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("enabled", Boolean.valueOf(result.isEnabled()));
        map.put("tool_count", Integer.valueOf(result.getToolCount()));
        map.put("changed_servers", result.getChangedServers());
        map.put("unchanged_servers", result.getUnchangedServers());
        map.put(
                "tool_changed_notification",
                Boolean.valueOf(!result.getChangedServers().isEmpty()));
        map.put(
                "changed_count",
                Integer.valueOf(result.getChangedServers().size()));
        map.put(
                "unchanged_count",
                Integer.valueOf(result.getUnchangedServers().size()));
        map.put(
                "server_count",
                Integer.valueOf(
                        result.getChangedServers().size() + result.getUnchangedServers().size()));
        return map;
    }

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
                    previousToolsJson = StrUtil.nullToEmpty(queryResult.getString("last_tools_json"));
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
                    toolsChanged ? difference(nextTools, previousTools) : Collections.<String>emptyList();
            List<String> removedTools =
                    toolsChanged ? difference(previousTools, nextTools) : Collections.<String>emptyList();
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
                                    : "MCP is disabled in runtime config.")
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
                                    : "MCP is disabled in runtime config.")
                            : safeDisplayError(securityVerdict.getMessage()));
        } finally {
            connection.close();
        }
    }

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

    private String read(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String json(Object value) {
        return value == null ? null : ONode.serialize(value);
    }

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

    @SuppressWarnings("unchecked")
    private Object redactParsed(Object value) {
        return redactParsed(value, false, "");
    }

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

    private boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private McpPackageSecurityService.SecurityVerdict checkPackageSecurity(
            String command, Object args) {
        if (packageSecurityService == null) {
            return McpPackageSecurityService.SecurityVerdict.allow();
        }
        return packageSecurityService.check(command, args);
    }

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

    private void updateOAuth(String serverId, Map<String, Object> oauth, long now) throws Exception {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object data = parse(json);
        if (data instanceof Map) {
            result.putAll((Map<String, Object>) data);
        }
        return result;
    }

    private Map<String, Object> sanitizeOAuth(Map<String, Object> oauth) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (oauth == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : oauth.entrySet()) {
            String key = entry.getKey();
            if (isSecretOAuthKey(key)) {
                if (entry.getValue() != null && StrUtil.isNotBlank(String.valueOf(entry.getValue()))) {
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

    private boolean isSecretOAuthKey(String key) {
        return "access_token".equals(key)
                || "refresh_token".equals(key)
                || "id_token".equals(key)
                || "token".equals(key)
                || "client_secret".equals(key)
                || "code_verifier".equals(key);
    }

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

    private HttpResponse executeOAuthTokenRequest(
            String url, String form, int redirectCount, String initialUrl) {
        assertSafeRuntimeUrl(url, "MCP OAuth token_endpoint");
        HttpRequest request =
                HttpRequest.post(url)
                        .timeout(15000)
                        .contentType("application/x-www-form-urlencoded")
                        .setFollowRedirects(false);
        if (sameOrigin(initialUrl, url)) {
            request.body(form);
        }
        HttpResponse response = request.execute();
        int status = response.getStatus();
        if (!isRedirect(status)) {
            return response;
        }
        try {
            if (redirectCount >= MAX_OAUTH_TOKEN_REDIRECTS) {
                throw new IllegalStateException("MCP OAuth token_endpoint redirect count exceeds limit");
            }
            String location = response.header("Location");
            if (StrUtil.isBlank(location)) {
                throw new IllegalStateException("MCP OAuth token_endpoint redirect missing Location");
            }
            String nextUrl = resolveRedirectUrl(url, location);
            response.close();
            return executeOAuthTokenRequest(nextUrl, form, redirectCount + 1, initialUrl);
        } catch (RuntimeException e) {
            response.close();
            throw e;
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

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

    private boolean sameOrigin(String initialUrl, String url) {
        try {
            URI initial = URI.create(initialUrl);
            URI current = URI.create(url);
            return StrUtil.equalsIgnoreCase(initial.getScheme(), current.getScheme())
                    && StrUtil.equalsIgnoreCase(initial.getHost(), current.getHost())
                    && effectivePort(initial) == effectivePort(current);
        } catch (Exception e) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }

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
            } catch (Exception ignored) {
                // Keep providers with non-standard expires_in values usable.
            }
        }
        Object expiresAt = firstPresent(tokenResponse, "expires_at", "expiresAt", "expires");
        if (expiresAt != null) {
            oauth.put("expires_at", expiresAt);
        }
    }

    private void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private void appendForm(StringBuilder form, String key, String value) throws Exception {
        if (form.length() > 0) {
            form.append('&');
        }
        form.append(urlEncode(key)).append('=').append(urlEncode(value));
    }

    private String safeTokenError(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return "";
        }
        Map<String, Object> error = sanitizeOAuth(parseMap(responseBody));
        String text = safeDisplayError(ONode.serialize(error), 500);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String safeError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        if (StrUtil.isBlank(message) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return safeDisplayError(message, 500);
    }

    private String safeDisplayError(String message) {
        return safeDisplayError(message, 1000);
    }

    private String safeDisplayError(String message, int maxLength) {
        String safe = SecretRedactor.redact(StrUtil.nullToEmpty(message), maxLength);
        if (safe.length() > maxLength) {
            return safe.substring(0, maxLength);
        }
        return safe;
    }

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
                        StrUtil.blankToDefault(
                                message, "MCP server requires re-authentication.")));
        return result;
    }

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

    private boolean hasSecret(Map<String, Object> oauth, String key) {
        Object value = oauth == null ? null : oauth.get(key);
        return value != null && StrUtil.isNotBlank(String.valueOf(value));
    }

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

    private Long expiresAt(Map<String, Object> oauth) {
        Object value = firstPresent(oauth, "expires_at", "expiresAt", "expires");
        if (value == null) {
            return null;
        }
        try {
            long parsed = value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
            if (parsed > 0L && parsed < 100000000000L) {
                parsed = parsed * 1000L;
            }
            return Long.valueOf(parsed);
        } catch (Exception e) {
            return null;
        }
    }

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

    private String firstText(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        return string(value);
    }

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

    private String codeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private String randomBase64Url(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String urlEncode(String value) throws Exception {
        return URLEncoder.encode(StrUtil.nullToEmpty(value), "UTF-8").replace("+", "%20");
    }

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
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrlAllowingPrivate(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    label
                            + " 被 URL 安全策略阻止："
                            + verdict.getMessage()
                            + " URL: "
                            + SecretRedactor.maskUrl(verdict.getUrl()));
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int countTools(String toolsJson) {
        Object parsed = parse(toolsJson);
        if (parsed instanceof List) {
            return ((List<?>) parsed).size();
        }
        return StrUtil.isBlank(toolsJson) ? 0 : 1;
    }

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

    private static class McpCheckState {
        private final String nextHash;
        private final boolean toolsChanged;
        private final int toolCount;
        private final List<String> addedTools;
        private final List<String> removedTools;
        private final McpPackageSecurityService.SecurityVerdict securityVerdict;
        private final String status;
        private final String error;

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

        private String getNextHash() {
            return nextHash;
        }

        private boolean isToolsChanged() {
            return toolsChanged;
        }

        private int getToolCount() {
            return toolCount;
        }

        private List<String> getAddedTools() {
            return addedTools;
        }

        private List<String> getRemovedTools() {
            return removedTools;
        }

        private McpPackageSecurityService.SecurityVerdict getSecurityVerdict() {
            return securityVerdict;
        }

        private String getStatus() {
            return status;
        }

        private String getError() {
            return error;
        }
    }

    public static class McpReloadResult {
        private final boolean enabled;
        private final List<String> changedServers;
        private final List<String> unchangedServers;
        private final int toolCount;

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

        public boolean isEnabled() {
            return enabled;
        }

        public List<String> getChangedServers() {
            return changedServers;
        }

        public List<String> getUnchangedServers() {
            return unchangedServers;
        }

        public int getToolCount() {
            return toolCount;
        }
    }
}
