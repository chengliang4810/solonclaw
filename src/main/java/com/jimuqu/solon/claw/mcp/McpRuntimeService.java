package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.content.ResourceBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.FunctionPrompt;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.resource.FunctionResource;
import org.noear.solon.ai.chat.resource.ResourcePack;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.util.ParamDesc;

/** Runtime MCP connector and dynamic tool discovery service. */
public class McpRuntimeService implements Closeable {
    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 60000L;
    private static final long DEFAULT_TOOL_TIMEOUT_MILLIS = 120000L;
    private static final String EMPTY_OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final String READ_RESOURCE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\",\"description\":\"MCP resource URI to read.\"}},\"required\":[\"uri\"],\"additionalProperties\":false}";
    private static final String GET_PROMPT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"MCP prompt name to fetch.\"},\"arguments\":{\"type\":\"object\",\"description\":\"Prompt arguments.\",\"additionalProperties\":true}},\"required\":[\"name\"],\"additionalProperties\":false}";

    private final AppConfig appConfig;
    private final SqliteDatabase database;
    private final McpClientProviderFactory providerFactory;
    private final SecurityPolicyService securityPolicyService;
    private final ConcurrentMap<String, McpClientProvider> providers =
            new ConcurrentHashMap<String, McpClientProvider>();
    private final ExecutorService toolCallExecutor =
            BoundedExecutorFactory.fixed("mcp-tool-call", 4, 64);

    public McpRuntimeService(AppConfig appConfig, SqliteDatabase database) {
        this(appConfig, database, null, null);
    }

    public McpRuntimeService(
            AppConfig appConfig,
            SqliteDatabase database,
            McpClientProviderFactory providerFactory) {
        this(appConfig, database, providerFactory, null);
    }

    public McpRuntimeService(
            AppConfig appConfig,
            SqliteDatabase database,
            McpClientProviderFactory providerFactory,
            SecurityPolicyService securityPolicyService) {
        this.appConfig = appConfig;
        this.database = database;
        this.securityPolicyService =
                securityPolicyService == null
                        ? new SecurityPolicyService(appConfig)
                        : securityPolicyService;
        this.providerFactory =
                providerFactory == null ? new SolonAiMcpClientProviderFactory(this) : providerFactory;
    }

    public List<ToolProvider> resolveEnabledToolProviders() {
        if (!appConfig.getMcp().isEnabled()) {
            return Collections.emptyList();
        }
        List<McpServerConfig> configs = enabledServers();
        List<ToolProvider> result = new ArrayList<ToolProvider>();
        for (McpServerConfig config : configs) {
            try {
                result.add(new PrefixedMcpToolProvider(config, providerFor(config)));
            } catch (Exception e) {
                updateStatus(config.getServerId(), "error", safeError(e), null, false);
            }
        }
        return result;
    }

    public McpToolRefreshResult connect(String serverId) throws Exception {
        McpServerConfig config = loadServer(serverId);
        McpClientProvider provider = providerFor(config);
        provider.getClient();
        return refreshLiveTools(serverId, false);
    }

    public McpToolRefreshResult reload(String serverId) throws Exception {
        closeProvider(serverId);
        return refreshLiveTools(serverId, false);
    }

    public McpToolRefreshResult refreshLiveTools(String serverId, boolean baselineInitial)
            throws Exception {
        McpServerConfig config = loadServer(serverId);
        McpClientProvider provider = providerFor(config);
        Collection<FunctionTool> tools = filteredTools(config, provider.getTools());
        String toolsJson = json(toolsSnapshot(config.getServerId(), tools));
        return persistToolSnapshot(serverId, toolsJson, baselineInitial, "ready", null);
    }

    public McpToolRefreshResult refreshPersistedTools(
            String serverId, boolean baselineInitial, String status, String lastError)
            throws Exception {
        McpServerConfig config = loadServer(serverId);
        return persistToolSnapshot(
                serverId,
                config.getToolsJson(),
                baselineInitial,
                StrUtil.blankToDefault(status, "ready"),
                lastError);
    }

    public void persistToolsChanged(String serverId, List<McpSchema.Tool> tools) {
        try {
            McpServerConfig config = loadServer(serverId);
            String toolsJson = json(mcpToolsSnapshot(config, tools));
            persistToolSnapshot(serverId, toolsJson, false, "ready", null);
            McpClientProvider provider = providers.get(serverId);
            if (provider != null) {
                provider.clearCache();
            }
        } catch (Exception e) {
            updateStatus(serverId, "error", safeError(e), null, false);
        }
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        for (McpClientProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (Exception ignored) {
                // Best effort shutdown.
            }
        }
        providers.clear();
        toolCallExecutor.shutdownNow();
    }

    private McpClientProvider providerFor(McpServerConfig config) {
        McpClientProvider current = providers.get(config.getServerId());
        if (current != null) {
            return current;
        }
        assertSafeProviderEndpoint(config);
        McpClientProvider created = providerFactory.create(config);
        McpClientProvider existing = providers.putIfAbsent(config.getServerId(), created);
        if (existing != null) {
            created.close();
            return existing;
        }
        return created;
    }

    private void assertSafeProviderEndpoint(McpServerConfig config) {
        String transport = StrUtil.nullToEmpty(config.getTransport()).trim();
        if ("stdio".equalsIgnoreCase(transport) || StrUtil.isBlank(config.getEndpoint())) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkUrlAllowingPrivate(config.getEndpoint());
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "MCP endpoint 被 URL 安全策略阻止："
                            + verdict.getMessage()
                            + " URL: "
                            + SecretRedactor.maskUrl(verdict.getUrl()));
        }
    }

    private void closeProvider(String serverId) {
        McpClientProvider provider = providers.remove(serverId);
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception ignored) {
                // Best effort reconnect.
            }
        }
    }

    private List<McpServerConfig> enabledServers() {
        List<McpServerConfig> result = new ArrayList<McpServerConfig>();
        Connection connection = null;
        try {
            connection = database.openConnection();
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from mcp_servers where enabled != 0 and coalesce(status, '') != 'blocked' order by name asc");
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    result.add(mapConfig(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        } finally {
            close(connection);
        }
        return result;
    }

    private McpServerConfig loadServer(String serverId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from mcp_servers where server_id = ?");
            statement.setString(1, serverId);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("MCP server not found: " + serverId);
                }
                return mapConfig(resultSet);
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private McpServerConfig mapConfig(ResultSet resultSet) throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setServerId(resultSet.getString("server_id"));
        config.setName(resultSet.getString("name"));
        config.setTransport(resultSet.getString("transport"));
        config.setEndpoint(resultSet.getString("endpoint"));
        config.setCommand(resultSet.getString("command"));
        config.setArgs(parseStringList(resultSet.getString("args_json")));
        config.setAuth(parseMap(resultSet.getString("auth_json")));
        config.setOauth(parseMap(resultSet.getString("oauth_json")));
        config.setToolOptions(resolveToolOptions(config.getAuth()));
        config.setToolsJson(resultSet.getString("tools_json"));
        config.setLastToolsHash(StrUtil.nullToEmpty(resultSet.getString("last_tools_hash")));
        config.setEnabled(resultSet.getInt("enabled") != 0);
        config.setHeaders(resolveHeaders(config));
        config.setEnv(resolveEnv(config.getAuth()));
        config.setAccessToken(firstText(config.getOauth(), "access_token", "token"));
        config.setConnectTimeoutMillis(
                readLong(
                        firstPresent(config.getAuth(), "connect_timeout_ms", "connectTimeoutMs"),
                        readSeconds(
                                firstPresent(config.getAuth(), "connect_timeout", "connectTimeout"),
                                DEFAULT_CONNECT_TIMEOUT_MILLIS)));
        config.setToolTimeoutMillis(
                readLong(
                        firstPresent(config.getAuth(), "tool_timeout_ms", "toolTimeoutMs"),
                        readSeconds(
                                firstPresent(config.getAuth(), "tool_timeout", "toolTimeout"),
                                DEFAULT_TOOL_TIMEOUT_MILLIS)));
        return config;
    }

    private McpToolRefreshResult persistToolSnapshot(
            String serverId,
            String toolsJson,
            boolean baselineInitial,
            String status,
            String lastError)
            throws Exception {
        McpServerConfig config = loadServer(serverId);
        String nextHash = hash(toolsJson);
        boolean initialBaseline = baselineInitial && StrUtil.isBlank(config.getLastToolsHash());
        boolean toolsChanged =
                StrUtil.isNotBlank(nextHash)
                        && !nextHash.equals(config.getLastToolsHash())
                        && !initialBaseline;
        List<String> previousTools = toolNames(config.getToolsJson());
        List<String> nextTools = toolNames(toolsJson);
        updateStatus(serverId, status, lastError, toolsJson, toolsChanged);
        return new McpToolRefreshResult(
                serverId,
                nextHash,
                toolsChanged,
                countTools(toolsJson),
                difference(nextTools, previousTools),
                difference(previousTools, nextTools),
                StrUtil.blankToDefault(status, "ready"),
                lastError);
    }

    private void updateStatus(
            String serverId,
            String status,
            String lastError,
            String toolsJson,
            boolean toolsChanged) {
        Connection connection = null;
        try {
            connection = database.openConnection();
            StringBuilder sql =
                    new StringBuilder(
                            "update mcp_servers set status = ?, last_error = ?, last_checked_at = ?, updated_at = ?, last_tools_changed_at = case when ? then ? else last_tools_changed_at end");
            if (toolsJson != null) {
                sql.append(", tools_json = ?, last_tools_hash = ?");
            } else {
                sql.append(", last_tools_hash = coalesce(last_tools_hash, '')");
            }
            sql.append(" where server_id = ?");
            PreparedStatement statement = connection.prepareStatement(sql.toString());
            long now = System.currentTimeMillis();
            statement.setString(1, StrUtil.blankToDefault(status, "ready"));
            statement.setString(2, lastError);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.setInt(5, toolsChanged ? 1 : 0);
            statement.setLong(6, now);
            int index = 7;
            if (toolsJson != null) {
                statement.setString(index++, toolsJson);
                statement.setString(index++, hash(toolsJson));
            }
            statement.setString(index, serverId);
            statement.executeUpdate();
            statement.close();
        } catch (Exception ignored) {
            // Status updates are diagnostic; tool execution should surface its own error.
        } finally {
            close(connection);
        }
    }

    private List<Map<String, Object>> toolsSnapshot(String serverId, Collection<FunctionTool> tools) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (tools == null) {
            return result;
        }
        for (FunctionTool tool : tools) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("name", tool.name());
            map.put("prefixed_name", prefixedName(serverId, tool.name()));
            map.put("title", tool.title());
            map.put("description", tool.description());
            map.put("input_schema", parse(sanitizeInputSchema(tool.inputSchema())));
            map.put("output_schema", parse(tool.outputSchema()));
            map.put("return_direct", Boolean.valueOf(tool.returnDirect()));
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> mcpToolsSnapshot(McpServerConfig config, List<McpSchema.Tool> tools) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (tools == null) {
            return result;
        }
        for (McpSchema.Tool tool : tools) {
            if (!shouldRegisterTool(config, tool.name())) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("name", tool.name());
            map.put("prefixed_name", prefixedName(config.getServerId(), tool.name()));
            map.put("title", tool.title());
            map.put("description", tool.description());
            map.put("input_schema", parse(sanitizeInputSchema(json(tool.inputSchema()))));
            map.put("output_schema", tool.outputSchema());
            result.add(map);
        }
        return result;
    }

    private List<FunctionTool> filteredTools(
            McpServerConfig config, Collection<FunctionTool> remoteTools) {
        List<FunctionTool> result = new ArrayList<FunctionTool>();
        if (remoteTools == null) {
            return result;
        }
        for (FunctionTool remote : remoteTools) {
            if (shouldRegisterTool(config, remote.name())) {
                result.add(remote);
            }
        }
        return result;
    }

    private boolean shouldRegisterTool(McpServerConfig config, String toolName) {
        McpToolOptions options = config.getToolOptions();
        if (!options.getInclude().isEmpty()) {
            return options.getInclude().contains(toolName);
        }
        if (!options.getExclude().isEmpty()) {
            return !options.getExclude().contains(toolName);
        }
        return true;
    }

    private Map<String, String> resolveHeaders(McpServerConfig config) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Map<String, Object> auth = config.getAuth();
        Object headers = firstPresent(auth, "headers", "header");
        if (headers instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) headers).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        String bearer = firstText(auth, "bearer_token", "bearerToken", "access_token", "token");
        if (StrUtil.isNotBlank(bearer) && !result.containsKey("Authorization")) {
            result.put("Authorization", "Bearer " + bearer);
        }
        return result;
    }

    private Map<String, String> resolveEnv(Map<String, Object> auth) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Object env = firstPresent(auth, "env", "environment");
        if (env instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) env).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private McpToolOptions resolveToolOptions(Map<String, Object> auth) {
        McpToolOptions options = new McpToolOptions();
        Object tools = firstPresent(auth, "tools", "tool_options", "toolOptions");
        if (tools instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) tools;
            options.setInclude(parseNameSet(firstPresent(map, "include", "allow")));
            options.setExclude(parseNameSet(firstPresent(map, "exclude", "deny")));
            options.setResourcesEnabled(asBoolean(firstPresent(map, "resources"), true));
            options.setPromptsEnabled(asBoolean(firstPresent(map, "prompts"), true));
        }
        Object include = firstPresent(auth, "tools_include", "toolsInclude");
        if (include != null) {
            options.setInclude(parseNameSet(include));
        }
        Object exclude = firstPresent(auth, "tools_exclude", "toolsExclude");
        if (exclude != null) {
            options.setExclude(parseNameSet(exclude));
        }
        Object resources = firstPresent(auth, "resources", "mcp_resources");
        if (resources != null) {
            options.setResourcesEnabled(asBoolean(resources, true));
        }
        Object prompts = firstPresent(auth, "prompts", "mcp_prompts");
        if (prompts != null) {
            options.setPromptsEnabled(asBoolean(prompts, true));
        }
        return options;
    }

    private Set<String> parseNameSet(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (value == null) {
            return result;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                addName(result, item);
            }
            return result;
        }
        if (value instanceof String) {
            String text = String.valueOf(value).trim();
            if (StrUtil.isBlank(text)) {
                return result;
            }
            if (text.indexOf(',') >= 0) {
                for (String item : text.split("\\s*,\\s*")) {
                    addName(result, item);
                }
            } else {
                addName(result, text);
            }
            return result;
        }
        addName(result, value);
        return result;
    }

    private void addName(Set<String> result, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (StrUtil.isNotBlank(text)) {
            result.add(text);
        }
    }

    private boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = String.valueOf(value).trim();
        if (StrUtil.isBlank(text)) {
            return fallback;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String json) {
        Object parsed = parse(json);
        List<String> result = new ArrayList<String>();
        if (parsed instanceof List) {
            for (Object item : (List<Object>) parsed) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
        } else if (parsed instanceof String && StrUtil.isNotBlank(String.valueOf(parsed))) {
            result.add(String.valueOf(parsed));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        Object parsed = parse(json);
        if (parsed instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
        }
        return new LinkedHashMap<String, Object>();
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

    private String json(Object value) {
        return value == null ? null : ONode.serialize(value);
    }

    private String sanitizeInputSchema(String inputSchema) {
        return SolonClawToolSchemaSanitizer.sanitizeSchemaJson(inputSchema);
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
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long readSeconds(Object value, long fallbackMillis) {
        if (value == null) {
            return fallbackMillis;
        }
        try {
            return Math.max(1L, Long.parseLong(String.valueOf(value))) * 1000L;
        } catch (Exception e) {
            return fallbackMillis;
        }
    }

    private long readLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(1L, Long.parseLong(String.valueOf(value)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String hash(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder buffer = new StringBuilder();
            for (byte item : bytes) {
                buffer.append(String.format("%02x", item & 0xff));
            }
            return buffer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash MCP tools", e);
        }
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

    private String safeError(Throwable e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        if (StrUtil.isBlank(message) && e != null) {
            message = e.getClass().getSimpleName();
        }
        return SecretRedactor.redact(message, 500);
    }

    private static String prefixedName(String serverId, String toolName) {
        return "mcp_" + sanitizeName(serverId) + "_" + sanitizeName(toolName);
    }

    private static String sanitizeName(String value) {
        String text = StrUtil.nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        String sanitized = text.replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_");
        while (sanitized.startsWith("_")) {
            sanitized = sanitized.substring(1);
        }
        while (sanitized.endsWith("_")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return StrUtil.blankToDefault(sanitized, "tool");
    }

    private void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // Ignore close failures.
            }
        }
    }

    public static class McpServerConfig {
        private String serverId;
        private String name;
        private String transport;
        private String endpoint;
        private String command;
        private List<String> args = new ArrayList<String>();
        private Map<String, Object> auth = new LinkedHashMap<String, Object>();
        private Map<String, Object> oauth = new LinkedHashMap<String, Object>();
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        private Map<String, String> env = new LinkedHashMap<String, String>();
        private McpToolOptions toolOptions = new McpToolOptions();
        private String accessToken;
        private String toolsJson;
        private String lastToolsHash;
        private boolean enabled;
        private long connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private long toolTimeoutMillis = DEFAULT_TOOL_TIMEOUT_MILLIS;

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? new ArrayList<String>() : args;
        }

        public Map<String, Object> getAuth() {
            return auth;
        }

        public void setAuth(Map<String, Object> auth) {
            this.auth = auth == null ? new LinkedHashMap<String, Object>() : auth;
        }

        public Map<String, Object> getOauth() {
            return oauth;
        }

        public void setOauth(Map<String, Object> oauth) {
            this.oauth = oauth == null ? new LinkedHashMap<String, Object>() : oauth;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env == null ? new LinkedHashMap<String, String>() : env;
        }

        public McpToolOptions getToolOptions() {
            return toolOptions;
        }

        public void setToolOptions(McpToolOptions toolOptions) {
            this.toolOptions = toolOptions == null ? new McpToolOptions() : toolOptions;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getToolsJson() {
            return toolsJson;
        }

        public void setToolsJson(String toolsJson) {
            this.toolsJson = toolsJson;
        }

        public String getLastToolsHash() {
            return lastToolsHash;
        }

        public void setLastToolsHash(String lastToolsHash) {
            this.lastToolsHash = lastToolsHash;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public long getToolTimeoutMillis() {
            return toolTimeoutMillis;
        }

        public void setToolTimeoutMillis(long toolTimeoutMillis) {
            this.toolTimeoutMillis = toolTimeoutMillis;
        }
    }

    public static class McpToolOptions {
        private Set<String> include = new LinkedHashSet<String>();
        private Set<String> exclude = new LinkedHashSet<String>();
        private boolean resourcesEnabled = true;
        private boolean promptsEnabled = true;

        public Set<String> getInclude() {
            return include;
        }

        public void setInclude(Set<String> include) {
            this.include = include == null ? new LinkedHashSet<String>() : include;
        }

        public Set<String> getExclude() {
            return exclude;
        }

        public void setExclude(Set<String> exclude) {
            this.exclude = exclude == null ? new LinkedHashSet<String>() : exclude;
        }

        public boolean isResourcesEnabled() {
            return resourcesEnabled;
        }

        public void setResourcesEnabled(boolean resourcesEnabled) {
            this.resourcesEnabled = resourcesEnabled;
        }

        public boolean isPromptsEnabled() {
            return promptsEnabled;
        }

        public void setPromptsEnabled(boolean promptsEnabled) {
            this.promptsEnabled = promptsEnabled;
        }
    }

    public static class McpToolRefreshResult {
        private final String serverId;
        private final String nextHash;
        private final boolean toolsChanged;
        private final int toolCount;
        private final List<String> addedTools;
        private final List<String> removedTools;
        private final String status;
        private final String error;

        public McpToolRefreshResult(
                String serverId,
                String nextHash,
                boolean toolsChanged,
                int toolCount,
                List<String> addedTools,
                List<String> removedTools,
                String status,
                String error) {
            this.serverId = serverId;
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
            this.status = status;
            this.error = error;
        }

        public String getServerId() {
            return serverId;
        }

        public String getNextHash() {
            return nextHash;
        }

        public boolean isToolsChanged() {
            return toolsChanged;
        }

        public int getToolCount() {
            return toolCount;
        }

        public List<String> getAddedTools() {
            return addedTools;
        }

        public List<String> getRemovedTools() {
            return removedTools;
        }

        public String getStatus() {
            return status;
        }

        public String getError() {
            return error;
        }
    }

    private class PrefixedMcpToolProvider implements ToolProvider {
        private final McpServerConfig config;
        private McpClientProvider provider;

        private PrefixedMcpToolProvider(McpServerConfig config, McpClientProvider provider) {
            this.config = config;
            this.provider = provider;
        }

        @Override
        public Collection<FunctionTool> getTools() {
            Collection<FunctionTool> remoteTools = filteredTools(config, provider.getTools());
            List<FunctionTool> result = new ArrayList<FunctionTool>();
            for (final FunctionTool remote : remoteTools) {
                FunctionToolDesc desc = new FunctionToolDesc(prefixedName(config.getServerId(), remote.name()));
                desc.title(remote.title());
                desc.description(remote.description());
                desc.returnDirect(remote.returnDirect());
                desc.inputSchema(sanitizeInputSchema(remote.inputSchema()));
                desc.outputSchema(remote.outputSchema());
                desc.metaPut("mcp_server_id", config.getServerId());
                desc.metaPut("mcp_server_name", config.getName());
                desc.metaPut("mcp_tool_name", remote.name());
                desc.doHandle(
                        args -> {
                            assertSafeRemoteTool(remote.name(), args);
                            return callRemoteToolWithRecovery(remote.name(), args);
                        });
                result.add(desc);
            }
            if (config.getToolOptions().isResourcesEnabled()) {
                result.add(listResourcesTool());
                result.add(readResourceTool());
            }
            if (config.getToolOptions().isPromptsEnabled()) {
                result.add(listPromptsTool());
                result.add(getPromptTool());
            }
            return result;
        }

        private FunctionTool listResourcesTool() {
            FunctionToolDesc desc =
                    new FunctionToolDesc(prefixedName(config.getServerId(), "list_resources"));
            desc.title("MCP List Resources");
            desc.description("List resources and resource templates exposed by MCP server " + config.getName() + ".");
            desc.inputSchema(EMPTY_OBJECT_SCHEMA);
            desc.metaPut("mcp_server_id", config.getServerId());
            desc.metaPut("mcp_server_name", config.getName());
            desc.metaPut("mcp_utility", "resources");
            desc.doHandle(args -> listResourcesJson());
            return desc;
        }

        private FunctionTool readResourceTool() {
            FunctionToolDesc desc =
                    new FunctionToolDesc(prefixedName(config.getServerId(), "read_resource"));
            desc.title("MCP Read Resource");
            desc.description("Read a resource from MCP server " + config.getName() + " by URI.");
            desc.inputSchema(READ_RESOURCE_SCHEMA);
            desc.metaPut("mcp_server_id", config.getServerId());
            desc.metaPut("mcp_server_name", config.getName());
            desc.metaPut("mcp_utility", "resources");
            desc.doHandle(args -> readResourceJson(firstArgText(args, "uri")));
            return desc;
        }

        private FunctionTool listPromptsTool() {
            FunctionToolDesc desc =
                    new FunctionToolDesc(prefixedName(config.getServerId(), "list_prompts"));
            desc.title("MCP List Prompts");
            desc.description("List prompts exposed by MCP server " + config.getName() + ".");
            desc.inputSchema(EMPTY_OBJECT_SCHEMA);
            desc.metaPut("mcp_server_id", config.getServerId());
            desc.metaPut("mcp_server_name", config.getName());
            desc.metaPut("mcp_utility", "prompts");
            desc.doHandle(args -> listPromptsJson());
            return desc;
        }

        private FunctionTool getPromptTool() {
            FunctionToolDesc desc =
                    new FunctionToolDesc(prefixedName(config.getServerId(), "get_prompt"));
            desc.title("MCP Get Prompt");
            desc.description("Fetch a prompt from MCP server " + config.getName() + " by name.");
            desc.inputSchema(GET_PROMPT_SCHEMA);
            desc.metaPut("mcp_server_id", config.getServerId());
            desc.metaPut("mcp_server_name", config.getName());
            desc.metaPut("mcp_utility", "prompts");
            desc.doHandle(
                    args ->
                            getPromptJson(
                                    firstArgText(args, "name"),
                                    firstArgMap(args, "arguments", "args")));
            return desc;
        }

        private String listResourcesJson() {
            return callWithRecovery(
                    "list_resources",
                    new RecoverableCall<String>() {
                        @Override
                        public String call(McpClientProvider activeProvider) {
                            List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
                            appendResourceMaps(resources, activeProvider.getResources(), false);
                            appendResourceMaps(resources, activeProvider.getResourceTemplates(), true);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            result.put("resources", resources);
                            return json(result);
                        }
                    });
        }

        private void appendResourceMaps(
                List<Map<String, Object>> result,
                Collection<FunctionResource> resources,
                boolean template) {
            if (resources == null) {
                return;
            }
            for (FunctionResource resource : resources) {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("name", resource.name());
                map.put("title", resource.title());
                map.put(template ? "uri_template" : "uri", resource.uri());
                map.put("description", resource.description());
                map.put("mime_type", resource.mimeType());
                map.put("template", Boolean.valueOf(template));
                if (resource.meta() != null && !resource.meta().isEmpty()) {
                    map.put("meta", resource.meta());
                }
                result.add(map);
            }
        }

        private String readResourceJson(String uri) throws Throwable {
            if (StrUtil.isBlank(uri)) {
                throw new IllegalArgumentException("MCP resource uri is required.");
            }
            assertSafeResourceUri(uri);
            return callWithRecovery(
                    "read_resource",
                    new RecoverableCall<String>() {
                        @Override
                        public String call(McpClientProvider activeProvider) throws Throwable {
                            ResourcePack pack = activeProvider.readResource(uri);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
                            if (pack != null) {
                                for (ResourceBlock block : pack.getResources()) {
                                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                                    map.put("content", block.getContent());
                                    map.put("mime_type", block.getMimeType());
                                    if (block.metas() != null && !block.metas().isEmpty()) {
                                        map.put("meta", block.metas());
                                    }
                                    resources.add(map);
                                }
                                if (pack.metas() != null && !pack.metas().isEmpty()) {
                                    result.put("meta", pack.metas());
                                }
                            }
                            result.put("result", pack == null ? null : pack.getContent());
                            result.put("resources", resources);
                            return json(result);
                        }
                    });
        }

        private String listPromptsJson() {
            return callWithRecovery(
                    "list_prompts",
                    new RecoverableCall<String>() {
                        @Override
                        public String call(McpClientProvider activeProvider) {
                            Collection<FunctionPrompt> prompts = activeProvider.getPrompts();
                            List<Map<String, Object>> promptMaps = new ArrayList<Map<String, Object>>();
                            if (prompts != null) {
                                for (FunctionPrompt prompt : prompts) {
                                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                                    map.put("name", prompt.name());
                                    map.put("title", prompt.title());
                                    map.put("description", prompt.description());
                                    map.put("arguments", promptArguments(prompt.params()));
                                    if (prompt.meta() != null && !prompt.meta().isEmpty()) {
                                        map.put("meta", prompt.meta());
                                    }
                                    promptMaps.add(map);
                                }
                            }
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            result.put("prompts", promptMaps);
                            return json(result);
                        }
                    });
        }

        private String getPromptJson(final String name, final Map<String, Object> args) {
            if (StrUtil.isBlank(name)) {
                throw new IllegalArgumentException("MCP prompt name is required.");
            }
            return callWithRecovery(
                    "get_prompt",
                    new RecoverableCall<String>() {
                        @Override
                        public String call(McpClientProvider activeProvider) throws Throwable {
                            Prompt prompt = activeProvider.getPrompt(name, args);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
                            if (prompt != null) {
                                for (ChatMessage message : prompt.getMessages()) {
                                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                                    map.put("role", message.getRole() == null ? null : message.getRole().name());
                                    map.put("content", message.getContent());
                                    if (message.getMetadata() != null && !message.getMetadata().isEmpty()) {
                                        map.put("metadata", message.getMetadata());
                                    }
                                    messages.add(map);
                                }
                                if (prompt.attrs() != null && !prompt.attrs().isEmpty()) {
                                    result.put("meta", prompt.attrs());
                                }
                            }
                            result.put("messages", messages);
                            return json(result);
                        }
                    });
        }

        private Object callRemoteToolWithRecovery(final String remoteToolName, final Map<String, Object> args) {
            return callWithRecovery(
                    remoteToolName,
                    new RecoverableCall<Object>() {
                        @Override
                        public Object call(McpClientProvider activeProvider) throws Throwable {
                            FunctionTool tool = findRemoteTool(activeProvider, remoteToolName);
                            if (tool == null) {
                                throw new IllegalStateException(
                                        "MCP tool not found after reconnect: " + remoteToolName);
                            }
                            return tool.call(args);
                        }
                    });
        }

        private FunctionTool findRemoteTool(McpClientProvider activeProvider, String remoteToolName) {
            Collection<FunctionTool> tools = filteredTools(config, activeProvider.getTools());
            if (tools == null) {
                return null;
            }
            for (FunctionTool tool : tools) {
                if (tool != null && remoteToolName.equals(tool.name())) {
                    return tool;
                }
            }
            return null;
        }

        private <T> T callWithRecovery(String operation, RecoverableCall<T> call) {
            try {
                return callWithTimeout(operation, call, provider);
            } catch (Throwable first) {
                if (isAuthError(first)) {
                    @SuppressWarnings("unchecked")
                    T value = (T) authFailureJson(operation, first);
                    return value;
                }
                if (!isRecoverableTransportError(first)) {
                    throwUnchecked(first);
                }
                McpClientProvider reconnected = reconnectProvider();
                try {
                    return callWithTimeout(operation, call, reconnected);
                } catch (Throwable second) {
                    if (isAuthError(second)) {
                        @SuppressWarnings("unchecked")
                        T value = (T) authFailureJson(operation, second);
                        return value;
                    }
                    throwUnchecked(second);
                }
            }
            throw new IllegalStateException("unreachable");
        }

        private <T> T callWithTimeout(
                final String operation,
                final RecoverableCall<T> call,
                final McpClientProvider activeProvider)
                throws Throwable {
            final long timeoutMillis = Math.max(1L, config.getToolTimeoutMillis());
            final long startNanos = System.nanoTime();
            Future<T> future =
                    toolCallExecutor.submit(
                            new Callable<T>() {
                                @Override
                                public T call() throws Exception {
                                    try {
                                        return call.call(activeProvider);
                                    } catch (RuntimeException e) {
                                        throw e;
                                    } catch (Error e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw e;
                                    } catch (Throwable e) {
                                        throw new McpToolCallException(e);
                                    }
                                }
                            });
            try {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                long elapsedMillis =
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                throw new IllegalStateException(
                        "MCP call timed out after "
                                + formatSeconds(elapsedMillis)
                                + "s (configured timeout: "
                                + formatSeconds(timeoutMillis)
                                + "s, server: "
                                + config.getServerId()
                                + ", operation: "
                                + operation
                                + ")");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof McpToolCallException
                        && cause.getCause() != null) {
                    throw cause.getCause();
                }
                throw cause == null ? e : cause;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        private String formatSeconds(long millis) {
            return String.format(Locale.ROOT, "%.1f", Double.valueOf(millis / 1000.0D));
        }

        private McpClientProvider reconnectProvider() {
            closeProvider(config.getServerId());
            McpServerConfig latest;
            try {
                latest = loadServer(config.getServerId());
            } catch (Exception e) {
                latest = config;
            }
            provider = providerFor(latest);
            return provider;
        }

        private boolean isAuthError(Throwable error) {
            Throwable current = error;
            while (current != null) {
                String type = current.getClass().getName().toLowerCase(Locale.ROOT);
                String message = StrUtil.nullToEmpty(current.getMessage()).toLowerCase(Locale.ROOT);
                if (type.contains("oauth")
                        || type.contains("unauthorized")
                        || type.contains("auth")
                        || message.contains("401")
                        || message.contains("unauthorized")
                        || message.contains("invalid_token")
                        || message.contains("token expired")
                        || message.contains("requires re-auth")
                        || message.contains("requires reauth")) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private boolean isRecoverableTransportError(Throwable error) {
            Throwable current = error;
            while (current != null) {
                String message = StrUtil.nullToEmpty(current.getMessage()).toLowerCase(Locale.ROOT);
                String type = current.getClass().getName().toLowerCase(Locale.ROOT);
                if (message.contains("session terminated")
                        || message.contains("session expired")
                        || message.contains("session not found")
                        || message.contains("unknown session")
                        || message.contains("closed resource")
                        || message.contains("closedresourceerror")
                        || message.contains("transport")
                        || message.contains("transport is closed")
                        || message.contains("connection reset")
                        || message.contains("connection closed")
                        || message.contains("broken pipe")
                        || message.contains("end of file")
                        || message.contains("eof")
                        || message.contains("stream closed")
                        || type.contains("transport")) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private String authFailureJson(String operation, Throwable error) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("success", Boolean.FALSE);
            result.put("status", "error");
            result.put("needs_reauth", Boolean.TRUE);
            result.put("server", config.getServerId());
            result.put("server_name", config.getName());
            result.put("operation", operation);
            result.put(
                    "error",
                    "MCP server requires re-authentication. Use the dashboard MCP OAuth flow or /reload-mcp after credentials are refreshed.");
            String detail = safeError(error);
            if (StrUtil.isNotBlank(detail)) {
                result.put("detail", detail);
            }
            return json(result);
        }

        private void throwUnchecked(Throwable error) {
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            throw new IllegalStateException(error);
        }

        private List<Map<String, Object>> promptArguments(Collection<ParamDesc> params) {
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            if (params == null) {
                return result;
            }
            for (ParamDesc param : params) {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("name", param.name());
                map.put("description", param.description());
                map.put("required", Boolean.valueOf(param.required()));
                map.put("default", param.defaultValue());
                map.put("format", param.format());
                result.add(map);
            }
            return result;
        }

        private String firstArgText(Map<String, Object> args, String key) {
            Object value = args == null ? null : args.get(key);
            return value == null ? "" : String.valueOf(value).trim();
        }

        private void assertSafeRemoteTool(String remoteToolName, Map<String, Object> args) {
            assertSafeUrls(remoteToolName, args);
            assertSafePaths(remoteToolName, args);
        }

        private void assertSafeResourceUri(String uri) {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("uri", uri);
            args.put("url", uri);
            assertSafeUrls("read_resource", args);
            assertSafePaths("read_resource", args);
        }

        private void assertSafeUrls(String remoteToolName, Map<String, Object> args) {
            if (securityPolicyService == null || args == null || args.isEmpty()) {
                return;
            }
            List<String> urls = new ArrayList<String>();
            collectUrlish(args, urls);
            for (String url : urls) {
                SecurityPolicyService.UrlVerdict verdict =
                        securityPolicyService.checkUrl(cleanToken(url));
                if (!verdict.isAllowed()) {
                    throw new IllegalArgumentException(
                            "BLOCKED: MCP tool "
                                    + config.getName()
                                    + "/"
                                    + remoteToolName
                                    + " URL 安全策略阻止访问："
                                    + verdict.getMessage()
                                    + "\nURL: "
                                    + com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(
                                            verdict.getUrl()));
                }
            }
        }

        private void assertSafePaths(String remoteToolName, Map<String, Object> args) {
            if (securityPolicyService == null || args == null || args.isEmpty()) {
                return;
            }
            List<String> paths = new ArrayList<String>();
            collectPathish(args, paths);
            for (String path : paths) {
                SecurityPolicyService.FileVerdict verdict =
                        securityPolicyService.checkPath(path, true);
                if (!verdict.isAllowed()) {
                    throw new IllegalArgumentException(
                            "BLOCKED: MCP tool "
                                    + config.getName()
                                    + "/"
                                    + remoteToolName
                                    + " 文件安全策略阻止访问："
                                    + verdict.getMessage()
                                    + "\n路径："
                                    + verdict.getPath());
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void collectUrlish(Object raw, List<String> urls) {
            if (raw == null) {
                return;
            }
            if (raw instanceof Map) {
                for (Object value : ((Map<?, ?>) raw).values()) {
                    collectUrlish(value, urls);
                }
                return;
            }
            if (raw instanceof Iterable) {
                for (Object value : (Iterable<?>) raw) {
                    collectUrlish(value, urls);
                }
                return;
            }
            String text = String.valueOf(raw);
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("https?://[^\\s)>'\"]+").matcher(text);
            while (matcher.find()) {
                urls.add(matcher.group());
            }
        }

        @SuppressWarnings("unchecked")
        private void collectPathish(Object raw, List<String> paths) {
            if (raw == null) {
                return;
            }
            if (raw instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                    String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                    Object value = entry.getValue();
                    if (looksLikePathKey(key)) {
                        addPathValue(value, paths);
                    } else {
                        collectPathish(value, paths);
                    }
                }
                return;
            }
            if (raw instanceof Iterable) {
                for (Object value : (Iterable<?>) raw) {
                    collectPathish(value, paths);
                }
            }
        }

        private boolean looksLikePathKey(String key) {
            String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
            return "path".equals(normalized)
                    || "file".equals(normalized)
                    || "filename".equals(normalized)
                    || "file_name".equals(normalized)
                    || "file_path".equals(normalized)
                    || "filepath".equals(normalized)
                    || "dir".equals(normalized)
                    || "dirname".equals(normalized)
                    || "directory".equals(normalized)
                    || normalized.endsWith("_path")
                    || normalized.endsWith("path");
        }

        @SuppressWarnings("unchecked")
        private void addPathValue(Object raw, List<String> paths) {
            if (raw == null) {
                return;
            }
            if (raw instanceof Iterable) {
                for (Object item : (Iterable<?>) raw) {
                    addPathValue(item, paths);
                }
                return;
            }
            String value = String.valueOf(raw).trim();
            if (StrUtil.isNotBlank(value)) {
                paths.add(value);
            }
        }

        private String cleanToken(String raw) {
            String value = StrUtil.nullToEmpty(raw).trim();
            while (value.endsWith(",")
                    || value.endsWith(".")
                    || value.endsWith(";")
                    || value.endsWith(":")
                    || value.endsWith("]")
                    || value.endsWith("}")) {
                value = value.substring(0, value.length() - 1).trim();
            }
            return value;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> firstArgMap(Map<String, Object> args, String... keys) {
            if (args == null) {
                return Collections.emptyMap();
            }
            for (String key : keys) {
                Object value = args.get(key);
                if (value instanceof Map) {
                    return new LinkedHashMap<String, Object>((Map<String, Object>) value);
                }
            }
            return Collections.emptyMap();
        }

        @Override
        public String toString() {
            return "McpToolProvider(" + config.getServerId() + ")";
        }
    }

    private interface RecoverableCall<T> {
        T call(McpClientProvider provider) throws Throwable;
    }

    private static class McpToolCallException extends Exception {
        private McpToolCallException(Throwable cause) {
            super(cause);
        }
    }
}
