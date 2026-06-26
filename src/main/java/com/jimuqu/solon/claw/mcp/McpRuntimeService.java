package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SolonClawToolSchemaSanitizer;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供MCP运行时相关业务能力，封装调用方不需要感知的运行细节。 */
public class McpRuntimeService implements Closeable {
    /** 记录MCP运行时非关键失败，日志必须避免输出密钥或完整配置内容。 */
    private static final Logger log = LoggerFactory.getLogger(McpRuntimeService.class);

    /** 默认连接超时毫秒数的统一常量值。 */
    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 60000L;

    /** 默认工具超时毫秒数的统一常量值。 */
    private static final long DEFAULT_TOOL_TIMEOUT_MILLIS = 120000L;

    /** 空对象结构的统一常量值。 */
    private static final String EMPTY_OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    /** 读取资源结构的统一常量值。 */
    private static final String READ_RESOURCE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\",\"description\":\"MCP resource URI to read.\"}},\"required\":[\"uri\"],\"additionalProperties\":false}";

    /** 获取提示词结构的统一常量值。 */
    private static final String GET_PROMPT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"MCP prompt name to fetch.\"},\"arguments\":{\"type\":\"object\",\"description\":\"Prompt arguments.\",\"additionalProperties\":true}},\"required\":[\"name\"],\"additionalProperties\":false}";

    /** 支持的传输协议的统一常量值。 */
    private static final List<String> SUPPORTED_TRANSPORTS =
            Collections.unmodifiableList(
                    java.util.Arrays.asList(
                            "stdio", "http", "streamable", "streamable_stateless", "sse"));

    /** 认证错误标记列表的统一常量值。 */
    private static final List<String> AUTH_ERROR_MARKERS =
            Collections.unmodifiableList(
                    java.util.Arrays.asList(
                            "oauth",
                            "unauthorized",
                            "401",
                            "invalid_token",
                            "token expired",
                            "requires re-auth",
                            "requires reauth"));

    /** 路径类参数键的统一常量值。 */
    private static final List<String> PATHISH_ARGUMENT_KEYS =
            Collections.unmodifiableList(
                    java.util.Arrays.asList(
                            "path",
                            "file",
                            "filename",
                            "file_name",
                            "file_path",
                            "filepath",
                            "dir",
                            "dirname",
                            "directory",
                            "output_file",
                            "destination",
                            "dest",
                            "*_path",
                            "*_file",
                            "*path"));

    /** 注入应用配置，用于MCP运行时。 */
    private final AppConfig appConfig;

    /** 记录MCP运行时中的数据库。 */
    private final SqliteDatabase database;

    /** 记录MCP运行时中的提供方工厂。 */
    private final McpClientProviderFactory providerFactory;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 保存providers映射，便于按键快速查询。 */
    private final ConcurrentMap<String, McpClientProvider> providers =
            new ConcurrentHashMap<String, McpClientProvider>();

    /** 保存discovery执行器执行组件，负责调度异步或定时任务。 */
    private final ExecutorService discoveryExecutor =
            BoundedExecutorFactory.fixed("mcp-discovery", 1, 32);

    /** 保存工具Call执行器执行组件，负责调度异步或定时任务。 */
    private final ExecutorService toolCallExecutor =
            BoundedExecutorFactory.fixed("mcp-tool-call", 4, 64);

    /**
     * 创建MCP运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param database database 参数。
     */
    public McpRuntimeService(AppConfig appConfig, SqliteDatabase database) {
        this(appConfig, database, null, null);
    }

    /**
     * 创建MCP运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param database database 参数。
     * @param providerFactory MCP 客户端提供方工厂。
     */
    public McpRuntimeService(
            AppConfig appConfig,
            SqliteDatabase database,
            McpClientProviderFactory providerFactory) {
        this(appConfig, database, providerFactory, null);
    }

    /**
     * 创建MCP运行时服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param database database 参数。
     * @param providerFactory MCP 客户端提供方工厂。
     * @param securityPolicyService 安全策略服务依赖。
     */
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
                providerFactory == null
                        ? new SolonAiMcpClientProviderFactory(this)
                        : providerFactory;
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @param appConfig 应用运行配置。
     * @return 返回策略Summary结果。
     */
    public static Map<String, Object> policySummary(AppConfig appConfig) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put(
                "enabled",
                Boolean.valueOf(
                        appConfig != null
                                && appConfig.getMcp() != null
                                && appConfig.getMcp().isEnabled()));
        summary.put("supportedTransports", SUPPORTED_TRANSPORTS);
        summary.put("remoteEndpointUrlSafety", Boolean.TRUE);
        summary.put("remoteEndpointAllowsPrivateByPolicy", Boolean.TRUE);
        summary.put("stdioEndpointSkipped", Boolean.TRUE);
        summary.put("remoteToolArgumentUrlSafety", Boolean.TRUE);
        summary.put("remoteToolStructuredCredentialArgumentBlocked", Boolean.TRUE);
        summary.put("remoteToolArgumentPathSafety", Boolean.TRUE);
        summary.put("resourceUriUrlSafety", Boolean.TRUE);
        summary.put("resourceUriPathSafety", Boolean.TRUE);
        summary.put("nestedUrlExtraction", Boolean.TRUE);
        summary.put("pathishArgumentKeys", PATHISH_ARGUMENT_KEYS);
        summary.put("blockedUrlsMasked", Boolean.TRUE);
        summary.put("blockedPathsRedacted", Boolean.TRUE);
        summary.put("inputSchemaSanitized", Boolean.TRUE);
        summary.put("toolNamesPrefixed", Boolean.TRUE);
        summary.put("toolIncludeExcludeFilter", Boolean.TRUE);
        summary.put("resourceUtilityToolsCapabilityGated", Boolean.TRUE);
        summary.put("promptUtilityToolsCapabilityGated", Boolean.TRUE);
        summary.put("blockedServersSuppressed", Boolean.TRUE);
        summary.put("toolsChangeNotificationPersisted", Boolean.TRUE);
        summary.put("toolChangeHashTracked", Boolean.TRUE);
        summary.put("toolsChangeClearsProviderCache", Boolean.TRUE);
        summary.put("oauthFailureStructuredReauth", Boolean.TRUE);
        summary.put("oauthFailureMarkers", AUTH_ERROR_MARKERS);
        summary.put("oauthSecretsRedacted", Boolean.TRUE);
        summary.put("recoverableTransportRetry", Boolean.TRUE);
        summary.put("remoteToolTimeoutMillisDefault", Long.valueOf(DEFAULT_TOOL_TIMEOUT_MILLIS));
        summary.put("connectTimeoutMillisDefault", Long.valueOf(DEFAULT_CONNECT_TIMEOUT_MILLIS));
        summary.put("startupDiscoveryAsync", Boolean.TRUE);
        summary.put("reloadDiscoveryAsync", Boolean.TRUE);
        summary.put("discoveryExecutorBounded", Boolean.TRUE);
        summary.put("discoveryExecutorMaxThreads", Integer.valueOf(1));
        summary.put("discoveryExecutorQueueCapacity", Integer.valueOf(32));
        summary.put("toolCallExecutorBounded", Boolean.TRUE);
        summary.put("toolCallExecutorMaxThreads", Integer.valueOf(4));
        summary.put("toolCallExecutorQueueCapacity", Integer.valueOf(64));
        summary.put("accessTokenHeaderOnlyForRemote", Boolean.TRUE);
        summary.put("authorizationHeaderCaseInsensitive", Boolean.TRUE);
        return summary;
    }

    /**
     * 解析启用工具Providers。
     *
     * @return 返回解析后的启用工具Providers。
     */
    public List<ToolProvider> resolveEnabledToolProviders() {
        if (!appConfig.getMcp().isEnabled()) {
            return Collections.emptyList();
        }
        List<McpServerConfig> configs = enabledServers();
        List<ToolProvider> result = new ArrayList<ToolProvider>();
        for (McpServerConfig config : configs) {
            try {
                result.add(new PrefixedMcpToolProvider(config));
            } catch (Exception e) {
                updateStatus(config.getServerId(), "error", safeError(e), null, false);
            }
        }
        return result;
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回connect结果。
     */
    public McpToolRefreshResult connect(String serverId) throws Exception {
        try {
            McpServerConfig config = loadServer(serverId);
            McpClientProvider provider = providerFor(config);
            provider.getClient();
            return refreshLiveTools(serverId, false);
        } catch (Exception e) {
            recordDiscoveryError(serverId, e);
            throw e;
        }
    }

    /**
     * 重新加载目标服务端配置与工具清单。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回reload结果。
     */
    public McpToolRefreshResult reload(String serverId) throws Exception {
        closeProvider(serverId);
        try {
            return refreshLiveTools(serverId, false);
        } catch (Exception e) {
            recordDiscoveryError(serverId, e);
            throw e;
        }
    }

    /**
     * 连接异步。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回connect Async结果。
     */
    public CompletableFuture<McpToolRefreshResult> connectAsync(String serverId) {
        return submitDiscovery(
                serverId,
                new DiscoveryCallable() {
                    /**
                     * 执行回调调用并返回结果。
                     *
                     * @param targetServerId target服务端标识。
                     * @return 返回call结果。
                     */
                    @Override
                    public McpToolRefreshResult call(String targetServerId) throws Exception {
                        return connect(targetServerId);
                    }
                });
    }

    /**
     * 异步重新加载目标服务端配置与工具清单。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回reload Async结果。
     */
    public CompletableFuture<McpToolRefreshResult> reloadAsync(String serverId) {
        return submitDiscovery(
                serverId,
                new DiscoveryCallable() {
                    /**
                     * 执行回调调用并返回结果。
                     *
                     * @param targetServerId target服务端标识。
                     * @return 返回call结果。
                     */
                    @Override
                    public McpToolRefreshResult call(String targetServerId) throws Exception {
                        return reload(targetServerId);
                    }
                });
    }

    /**
     * 刷新在线工具Async。
     *
     * @param serverId MCP 服务端标识。
     * @param baselineInitial baselineInitial 参数。
     * @return 返回在线工具Async结果。
     */
    public CompletableFuture<McpToolRefreshResult> refreshLiveToolsAsync(
            String serverId, final boolean baselineInitial) {
        return submitDiscovery(
                serverId,
                new DiscoveryCallable() {
                    /**
                     * 执行回调调用并返回结果。
                     *
                     * @param targetServerId target服务端标识。
                     * @return 返回call结果。
                     */
                    @Override
                    public McpToolRefreshResult call(String targetServerId) throws Exception {
                        return refreshLiveTools(targetServerId, baselineInitial);
                    }
                });
    }

    /**
     * 刷新全部启用 在线工具Async。
     *
     * @param baselineInitial baselineInitial 参数。
     * @return 返回全部启用 在线工具Async结果。
     */
    public CompletableFuture<List<McpToolRefreshResult>> refreshAllEnabledLiveToolsAsync(
            final boolean baselineInitial) {
        final List<McpServerConfig> configs = enabledServers();
        final List<String> serverIds = new ArrayList<String>();
        for (McpServerConfig config : configs) {
            if (config != null && StrUtil.isNotBlank(config.getServerId())) {
                serverIds.add(config.getServerId());
            }
        }
        final CompletableFuture<List<McpToolRefreshResult>> future =
                new CompletableFuture<List<McpToolRefreshResult>>();
        try {
            discoveryExecutor.submit(
                    new Runnable() {
                        /** 执行异步任务主体。 */
                        @Override
                        public void run() {
                            List<McpToolRefreshResult> result =
                                    new ArrayList<McpToolRefreshResult>();
                            try {
                                for (String serverId : serverIds) {
                                    try {
                                        result.add(refreshLiveTools(serverId, baselineInitial));
                                    } catch (Exception e) {
                                        recordDiscoveryError(serverId, e);
                                    }
                                }
                                future.complete(result);
                            } catch (Throwable e) {
                                future.completeExceptionally(e);
                            }
                        }
                    });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 刷新在线工具。
     *
     * @param serverId MCP 服务端标识。
     * @param baselineInitial baselineInitial 参数。
     * @return 返回在线工具结果。
     */
    public McpToolRefreshResult refreshLiveTools(String serverId, boolean baselineInitial)
            throws Exception {
        try {
            McpServerConfig config = loadServer(serverId);
            McpClientProvider provider = providerFor(config);
            Collection<FunctionTool> tools = filteredTools(config, provider.getTools());
            String toolsJson = json(toolsSnapshot(config.getServerId(), tools));
            return persistToolSnapshot(serverId, toolsJson, baselineInitial, "ready", null);
        } catch (Exception e) {
            recordDiscoveryError(serverId, e);
            throw e;
        }
    }

    /**
     * 刷新Persisted工具。
     *
     * @param serverId MCP 服务端标识。
     * @param baselineInitial baselineInitial 参数。
     * @param status 状态参数。
     * @param lastError last错误参数。
     * @return 返回Persisted工具结果。
     */
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

    /**
     * 执行persist工具Changed相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param tools tools 参数。
     */
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
            recordDiscoveryError(serverId, e);
        }
    }

    /** 启动Initial Discovery Async。 */
    public void startInitialDiscoveryAsync() {
        if (appConfig == null || appConfig.getMcp() == null || !appConfig.getMcp().isEnabled()) {
            return;
        }
        refreshAllEnabledLiveToolsAsync(true);
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        close();
    }

    /** 关闭当前组件持有的运行资源。 */
    @Override
    public void close() {
        for (McpClientProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (Exception e) {
                log.debug("MCP提供方关闭失败，继续释放其他运行资源: {}", exceptionSummary(e));
            }
        }
        providers.clear();
        discoveryExecutor.shutdownNow();
        toolCallExecutor.shutdownNow();
    }

    /**
     * 提交Discovery。
     *
     * @param serverId MCP 服务端标识。
     * @param callable callable 参数。
     * @return 返回submit Discovery结果。
     */
    private CompletableFuture<McpToolRefreshResult> submitDiscovery(
            final String serverId, final DiscoveryCallable callable) {
        final CompletableFuture<McpToolRefreshResult> future =
                new CompletableFuture<McpToolRefreshResult>();
        try {
            discoveryExecutor.submit(
                    new Runnable() {
                        /** 执行异步任务主体。 */
                        @Override
                        public void run() {
                            try {
                                future.complete(callable.call(serverId));
                            } catch (Exception e) {
                                recordDiscoveryError(serverId, e);
                                future.completeExceptionally(e);
                            }
                        }
                    });
        } catch (RejectedExecutionException e) {
            recordDiscoveryError(serverId, e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 记录Discovery Error。
     *
     * @param serverId MCP 服务端标识。
     * @param error 错误参数。
     */
    private void recordDiscoveryError(String serverId, Throwable error) {
        updateStatus(serverId, "error", safeError(error), null, false);
    }

    /**
     * 执行提供方For相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @return 返回提供方For结果。
     */
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

    /**
     * 执行assert安全提供方Endpoint相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     */
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

    /**
     * 关闭提供方。
     *
     * @param serverId MCP 服务端标识。
     */
    private void closeProvider(String serverId) {
        McpClientProvider provider = providers.remove(serverId);
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                log.debug(
                        "MCP提供方关闭失败，后续会按需重新建立连接: serverId={}, error={}",
                        sanitizeLogToken(serverId),
                        exceptionSummary(e));
            }
        }
    }

    /**
     * 执行启用状态服务端相关逻辑。
     *
     * @return 返回enabled 服务端结果。
     */
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
        } catch (Exception e) {
            log.warn("MCP启用服务端列表读取失败，回退为空列表: error={}", exceptionSummary(e));
            return Collections.emptyList();
        } finally {
            close(connection);
        }
        return result;
    }

    /**
     * 加载Server。
     *
     * @param serverId MCP 服务端标识。
     * @return 返回Server结果。
     */
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

    /**
     * 执行map配置相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map配置。
     */
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
        config.setCapabilities(parseMap(resultSet.getString("capabilities_json")));
        config.setToolOptions(resolveToolOptions(config.getAuth()));
        config.setToolsJson(resultSet.getString("tools_json"));
        config.setLastToolsHash(StrUtil.nullToEmpty(resultSet.getString("last_tools_hash")));
        config.setEnabled(resultSet.getInt("enabled") != 0);
        config.setHeaders(resolveHeaders(config));
        config.setEnv(resolveEnv(config.getAuth()));
        config.setAccessToken(
                McpToolListSupport.firstText(config.getOauth(), "access_token", "token"));
        config.setConnectTimeoutMillis(
                readLong(
                        McpToolListSupport.firstPresent(
                                config.getAuth(), "connect_timeout_ms", "connectTimeoutMs"),
                        readSeconds(
                                McpToolListSupport.firstPresent(
                                        config.getAuth(), "connect_timeout", "connectTimeout"),
                                DEFAULT_CONNECT_TIMEOUT_MILLIS)));
        config.setToolTimeoutMillis(
                readLong(
                        McpToolListSupport.firstPresent(
                                config.getAuth(), "tool_timeout_ms", "toolTimeoutMs"),
                        readSeconds(
                                McpToolListSupport.firstPresent(
                                        config.getAuth(), "tool_timeout", "toolTimeout"),
                                DEFAULT_TOOL_TIMEOUT_MILLIS)));
        return config;
    }

    /**
     * 执行persist工具Snapshot相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param toolsJson toolsJSON参数。
     * @param baselineInitial baselineInitial 参数。
     * @param status 状态参数。
     * @param lastError last错误参数。
     * @return 返回persist工具Snapshot结果。
     */
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
        List<String> previousTools = McpToolListSupport.toolNames(config.getToolsJson());
        List<String> nextTools = McpToolListSupport.toolNames(toolsJson);
        updateStatus(serverId, status, lastError, toolsJson, toolsChanged);
        return new McpToolRefreshResult(
                serverId,
                nextHash,
                toolsChanged,
                previousTools.size(),
                McpToolListSupport.countTools(toolsJson),
                McpToolListSupport.difference(nextTools, previousTools),
                McpToolListSupport.difference(previousTools, nextTools),
                StrUtil.blankToDefault(status, "ready"),
                lastError);
    }

    /**
     * 更新状态。
     *
     * @param serverId MCP 服务端标识。
     * @param status 状态参数。
     * @param lastError last错误参数。
     * @param toolsJson toolsJSON参数。
     * @param toolsChanged toolsChanged 参数。
     */
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
                sql.append(", tools_json = ?, last_tools_hash = ?, last_tools_json = ?");
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
                statement.setString(index++, toolsJson);
            }
            statement.setString(index, serverId);
            statement.executeUpdate();
            statement.close();
        } catch (Exception e) {
            log.warn(
                    "MCP服务端状态写回失败，保留原状态继续运行: serverId={}, status={}, error={}",
                    sanitizeLogToken(serverId),
                    StrUtil.blankToDefault(status, "ready"),
                    exceptionSummary(e));
        } finally {
            close(connection);
        }
    }

    /**
     * 执行工具Snapshot相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param tools tools 参数。
     * @return 返回工具Snapshot结果。
     */
    private List<Map<String, Object>> toolsSnapshot(
            String serverId, Collection<FunctionTool> tools) {
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

    /**
     * 执行MCP工具Snapshot相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @param tools tools 参数。
     * @return 返回MCP工具Snapshot结果。
     */
    private List<Map<String, Object>> mcpToolsSnapshot(
            McpServerConfig config, List<McpSchema.Tool> tools) {
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

    /**
     * 执行filtered工具相关逻辑。
     *
     * @param config 当前模块使用的配置对象。
     * @param remoteTools remoteTools 参数。
     * @return 返回filtered工具结果。
     */
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

    /**
     * 判断是否需要Register工具。
     *
     * @param config 当前模块使用的配置对象。
     * @param toolName 工具名称。
     * @return 如果Register工具满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 解析Headers。
     *
     * @param config 当前模块使用的配置对象。
     * @return 返回解析后的Headers。
     */
    private Map<String, String> resolveHeaders(McpServerConfig config) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Map<String, Object> auth = config.getAuth();
        Object headers = McpToolListSupport.firstPresent(auth, "headers", "header");
        if (headers instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) headers).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        String bearer =
                McpToolListSupport.firstText(
                        auth, "bearer_token", "bearerToken", "access_token", "token");
        if (StrUtil.isNotBlank(bearer) && !hasHeader(result, "Authorization")) {
            result.put("Authorization", "Bearer " + bearer);
        }
        return result;
    }

    /**
     * 判断是否存在Header。
     *
     * @param headers headers 参数。
     * @param name 名称参数。
     * @return 如果Header满足条件则返回 true，否则返回 false。
     */
    private boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析Env。
     *
     * @param auth 鉴权参数。
     * @return 返回解析后的Env。
     */
    private Map<String, String> resolveEnv(Map<String, Object> auth) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Object env = McpToolListSupport.firstPresent(auth, "env", "environment");
        if (env instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) env).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }

    /**
     * 解析工具Options。
     *
     * @param auth 鉴权参数。
     * @return 返回解析后的工具Options。
     */
    @SuppressWarnings("unchecked")
    private McpToolOptions resolveToolOptions(Map<String, Object> auth) {
        McpToolOptions options = new McpToolOptions();
        Object tools = McpToolListSupport.firstPresent(auth, "tools", "tool_options", "toolOptions");
        if (tools instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) tools;
            options.setInclude(
                    parseNameSet(McpToolListSupport.firstPresent(map, "include", "allow")));
            options.setExclude(
                    parseNameSet(McpToolListSupport.firstPresent(map, "exclude", "deny")));
            options.setResourcesEnabled(
                    asBoolean(McpToolListSupport.firstPresent(map, "resources"), true));
            options.setPromptsEnabled(
                    asBoolean(McpToolListSupport.firstPresent(map, "prompts"), true));
        }
        Object include = McpToolListSupport.firstPresent(auth, "tools_include", "toolsInclude");
        if (include != null) {
            options.setInclude(parseNameSet(include));
        }
        Object exclude = McpToolListSupport.firstPresent(auth, "tools_exclude", "toolsExclude");
        if (exclude != null) {
            options.setExclude(parseNameSet(exclude));
        }
        Object resources = McpToolListSupport.firstPresent(auth, "resources", "mcp_resources");
        if (resources != null) {
            options.setResourcesEnabled(asBoolean(resources, true));
        }
        Object prompts = McpToolListSupport.firstPresent(auth, "prompts", "mcp_prompts");
        if (prompts != null) {
            options.setPromptsEnabled(asBoolean(prompts, true));
        }
        return options;
    }

    /**
     * 解析名称Set。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回解析后的名称Set。
     */
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

    /**
     * 追加名称。
     *
     * @param result 结果响应或执行结果。
     * @param value 待规范化或校验的原始值。
     */
    private void addName(Set<String> result, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (StrUtil.isNotBlank(text)) {
            result.add(text);
        }
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
        String text = String.valueOf(value).trim();
        if (StrUtil.isBlank(text)) {
            return fallback;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    /**
     * 解析String List。
     *
     * @param json JSON参数。
     * @return 返回解析后的String List。
     */
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

    /**
     * 解析Map。
     *
     * @param json JSON参数。
     * @return 返回解析后的Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        Object parsed = parse(json);
        if (parsed instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
        }
        return new LinkedHashMap<String, Object>();
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
     * 执行JSON相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回JSON结果。
     */
    private String json(Object value) {
        return value == null ? null : ONode.serialize(value);
    }

    /**
     * 脱敏For工具结果。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回For工具结果。
     */
    @SuppressWarnings("unchecked")
    private Object redactForToolResult(Object value) {
        if (value instanceof String) {
            return SecretRedactor.redact((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> redacted = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveResultKey(key)) {
                    redacted.put(key, redactedSensitiveValue(entry.getValue()));
                } else {
                    redacted.put(key, redactForToolResult(entry.getValue()));
                }
            }
            return redacted;
        }
        if (value instanceof Collection) {
            List<Object> redacted = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) {
                redacted.add(redactForToolResult(item));
            }
            return redacted;
        }
        return value;
    }

    /**
     * 为敏感键构造脱敏后的展示值。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回redacted Sensitive Value结果。
     */
    private Object redactedSensitiveValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection || value instanceof Map) {
            return redactForToolResult(value);
        }
        return "***";
    }

    /**
     * 判断是否Sensitive结果键。
     *
     * @param key 配置键或映射键。
     * @return 如果Sensitive结果键满足条件则返回 true，否则返回 false。
     */
    private boolean isSensitiveResultKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return "access_token".equals(normalized)
                || "refresh_token".equals(normalized)
                || "id_token".equals(normalized)
                || "auth_token".equals(normalized)
                || "api_key".equals(normalized)
                || "apikey".equals(normalized)
                || "client_secret".equals(normalized)
                || "secret".equals(normalized)
                || "password".equals(normalized)
                || "passwd".equals(normalized)
                || "authorization".equals(normalized)
                || "bearer".equals(normalized)
                || normalized.endsWith("_token")
                || normalized.endsWith("_secret")
                || normalized.endsWith("_password")
                || normalized.endsWith("_api_key");
    }

    /**
     * 生成安全展示用的工具结果JSON。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe工具结果JSON结果。
     */
    private String safeToolResultJson(Object value) {
        return json(redactForToolResult(value));
    }

    /**
     * 清理输入结构。
     *
     * @param inputSchema 输入Schema参数。
     * @return 返回输入结构结果。
     */
    private String sanitizeInputSchema(String inputSchema) {
        return SolonClawToolSchemaSanitizer.sanitizeSchemaJson(inputSchema);
    }

    /**
     * 读取Seconds。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallbackMillis 兜底Millis参数。
     * @return 返回读取到的Seconds。
     */
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

    /**
     * 读取Long。
     *
     * @param value 待规范化或校验的原始值。
     * @param fallback 兜底参数。
     * @return 返回读取到的Long。
     */
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

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Throwable e) {
        return SecretRedactor.redact(diagnosticError(e), 500);
    }

    /**
     * 生成单行脱敏异常摘要，避免MCP日志输出密钥、Token或完整配置值。
     *
     * @param error 捕获到的异常。
     * @return 可写入日志的异常类型与脱敏消息摘要。
     */
    private static String exceptionSummary(Throwable error) {
        restoreInterruptIfNeeded(error);
        if (error == null) {
            return "unknown";
        }
        String message = SecretRedactor.redact(StrUtil.nullToEmpty(error.getMessage()), 300);
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (StrUtil.isBlank(message)) {
            return error.getClass().getName();
        }
        return error.getClass().getName() + ": " + message;
    }

    /**
     * 捕获链中出现中断异常时恢复当前线程中断标记，避免上层并发控制丢失取消信号。
     *
     * @param error 捕获到的异常。
     */
    private static void restoreInterruptIfNeeded(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 16) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            current = current.getCause();
            depth++;
        }
    }

    /**
     * 生成适合日志展示的短标识，避免换行或疑似敏感片段进入日志字段。
     *
     * @param value 服务端标识或状态字段。
     * @return 脱敏后的单行短文本。
     */
    private static String sanitizeLogToken(String value) {
        String text = SecretRedactor.redact(StrUtil.nullToEmpty(value), 120);
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * 执行诊断错误相关逻辑。
     *
     * @param e 捕获到的异常。
     * @return 返回诊断Error结果。
     */
    private static String diagnosticError(Throwable e) {
        if (e == null) {
            return "";
        }
        String message = String.valueOf(e.getMessage());
        if (StrUtil.isNotBlank(message)) {
            return message;
        }
        return e.getClass().getSimpleName();
    }

    /**
     * 执行prefixed名称相关逻辑。
     *
     * @param serverId MCP 服务端标识。
     * @param toolName 工具名称。
     * @return 返回prefixed名称结果。
     */
    private static String prefixedName(String serverId, String toolName) {
        return "mcp_" + sanitizeName(serverId) + "_" + sanitizeName(toolName);
    }

    /**
     * 清理名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回名称结果。
     */
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

    /**
     * 关闭当前组件持有的运行资源。
     *
     * @param connection 连接参数。
     */
    private void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.debug("MCP数据库连接关闭失败，连接池或驱动将继续处理回收: {}", exceptionSummary(e));
            }
        }
    }

    /** 承载MCP服务端配置并集中创建运行组件。 */
    public static class McpServerConfig {
        /** 记录MCP服务端中的服务端标识。 */
        private String serverId;

        /** 记录MCP服务端中的名称。 */
        private String name;

        /** 记录MCP服务端中的transport。 */
        private String transport;

        /** 记录MCP服务端中的endpoint。 */
        private String endpoint;

        /** 记录MCP服务端中的命令。 */
        private String command;

        /** 保存参数集合，维持调用顺序或去重语义。 */
        private List<String> args = new ArrayList<String>();

        /** 保存认证映射，便于按键快速查询。 */
        private Map<String, Object> auth = new LinkedHashMap<String, Object>();

        /** 保存oauth映射，便于按键快速查询。 */
        private Map<String, Object> oauth = new LinkedHashMap<String, Object>();

        /** 保存capabilities映射，便于按键快速查询。 */
        private Map<String, Object> capabilities = new LinkedHashMap<String, Object>();

        /** 保存headers映射，便于按键快速查询。 */
        private Map<String, String> headers = new LinkedHashMap<String, String>();

        /** 保存环境变量映射，便于按键快速查询。 */
        private Map<String, String> env = new LinkedHashMap<String, String>();

        /** 记录MCP服务端中的工具Options。 */
        private McpToolOptions toolOptions = new McpToolOptions();

        /** 记录MCP服务端中的access token。 */
        private String accessToken;

        /** 记录MCP服务端中的工具JSON。 */
        private String toolsJson;

        /** 记录MCP服务端中的最近一次工具哈希。 */
        private String lastToolsHash;

        /** 标记该配置项或记录是否处于启用状态。 */
        private boolean enabled;

        /** 记录MCP服务端中的connectTimeoutMillis。 */
        private long connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;

        /** 记录MCP服务端中的工具TimeoutMillis。 */
        private long toolTimeoutMillis = DEFAULT_TOOL_TIMEOUT_MILLIS;

        /**
         * 读取Server标识。
         *
         * @return 返回读取到的Server标识。
         */
        public String getServerId() {
            return serverId;
        }

        /**
         * 写入Server标识。
         *
         * @param serverId MCP 服务端标识。
         */
        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        /**
         * 读取名称。
         *
         * @return 返回读取到的名称。
         */
        public String getName() {
            return name;
        }

        /**
         * 写入名称。
         *
         * @param name 名称参数。
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 读取Transport。
         *
         * @return 返回读取到的Transport。
         */
        public String getTransport() {
            return transport;
        }

        /**
         * 写入Transport。
         *
         * @param transport transport 参数。
         */
        public void setTransport(String transport) {
            this.transport = transport;
        }

        /**
         * 读取Endpoint。
         *
         * @return 返回读取到的Endpoint。
         */
        public String getEndpoint() {
            return endpoint;
        }

        /**
         * 写入Endpoint。
         *
         * @param endpoint endpoint 参数。
         */
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        /**
         * 读取命令。
         *
         * @return 返回读取到的命令。
         */
        public String getCommand() {
            return command;
        }

        /**
         * 写入命令。
         *
         * @param command 待执行或解析的命令文本。
         */
        public void setCommand(String command) {
            this.command = command;
        }

        /**
         * 读取参数。
         *
         * @return 返回读取到的参数。
         */
        public List<String> getArgs() {
            return args;
        }

        /**
         * 写入参数。
         *
         * @param args 工具或命令参数。
         */
        public void setArgs(List<String> args) {
            this.args = args == null ? new ArrayList<String>() : args;
        }

        /**
         * 读取认证。
         *
         * @return 返回读取到的认证。
         */
        public Map<String, Object> getAuth() {
            return auth;
        }

        /**
         * 写入认证。
         *
         * @param auth 鉴权参数。
         */
        public void setAuth(Map<String, Object> auth) {
            this.auth = auth == null ? new LinkedHashMap<String, Object>() : auth;
        }

        /**
         * 读取Oauth。
         *
         * @return 返回读取到的Oauth。
         */
        public Map<String, Object> getOauth() {
            return oauth;
        }

        /**
         * 写入Oauth。
         *
         * @param oauth oauth 参数。
         */
        public void setOauth(Map<String, Object> oauth) {
            this.oauth = oauth == null ? new LinkedHashMap<String, Object>() : oauth;
        }

        /**
         * 读取Capabilities。
         *
         * @return 返回读取到的Capabilities。
         */
        public Map<String, Object> getCapabilities() {
            return capabilities;
        }

        /**
         * 写入Capabilities。
         *
         * @param capabilities capabilities 参数。
         */
        public void setCapabilities(Map<String, Object> capabilities) {
            this.capabilities =
                    capabilities == null ? new LinkedHashMap<String, Object>() : capabilities;
        }

        /**
         * 读取Headers。
         *
         * @return 返回读取到的Headers。
         */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * 写入Headers。
         *
         * @param headers headers 参数。
         */
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        }

        /**
         * 读取Env。
         *
         * @return 返回读取到的Env。
         */
        public Map<String, String> getEnv() {
            return env;
        }

        /**
         * 写入Env。
         *
         * @param env 环境变量参数。
         */
        public void setEnv(Map<String, String> env) {
            this.env = env == null ? new LinkedHashMap<String, String>() : env;
        }

        /**
         * 读取工具Options。
         *
         * @return 返回读取到的工具Options。
         */
        public McpToolOptions getToolOptions() {
            return toolOptions;
        }

        /**
         * 写入工具Options。
         *
         * @param toolOptions 工具Options参数。
         */
        public void setToolOptions(McpToolOptions toolOptions) {
            this.toolOptions = toolOptions == null ? new McpToolOptions() : toolOptions;
        }

        /**
         * 读取access token。
         *
         * @return 返回读取到的access token。
         */
        public String getAccessToken() {
            return accessToken;
        }

        /**
         * 写入access token。
         *
         * @param accessToken access token参数。
         */
        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        /**
         * 读取工具JSON。
         *
         * @return 返回读取到的工具JSON。
         */
        public String getToolsJson() {
            return toolsJson;
        }

        /**
         * 写入工具JSON。
         *
         * @param toolsJson toolsJSON参数。
         */
        public void setToolsJson(String toolsJson) {
            this.toolsJson = toolsJson;
        }

        /**
         * 读取Last工具Hash。
         *
         * @return 返回读取到的Last工具Hash。
         */
        public String getLastToolsHash() {
            return lastToolsHash;
        }

        /**
         * 写入Last工具Hash。
         *
         * @param lastToolsHash lastTools哈希参数。
         */
        public void setLastToolsHash(String lastToolsHash) {
            this.lastToolsHash = lastToolsHash;
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
         * 写入启用。
         *
         * @param enabled 启用状态开关值。
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 读取Connect Timeout Millis。
         *
         * @return 返回读取到的Connect Timeout Millis。
         */
        public long getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        /**
         * 写入Connect Timeout Millis。
         *
         * @param connectTimeoutMillis connectTimeoutMillis 参数。
         */
        public void setConnectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        /**
         * 读取工具Timeout Millis。
         *
         * @return 返回读取到的工具Timeout Millis。
         */
        public long getToolTimeoutMillis() {
            return toolTimeoutMillis;
        }

        /**
         * 写入工具Timeout Millis。
         *
         * @param toolTimeoutMillis 工具TimeoutMillis参数。
         */
        public void setToolTimeoutMillis(long toolTimeoutMillis) {
            this.toolTimeoutMillis = toolTimeoutMillis;
        }
    }

    /** 承载MCP工具Options相关状态和辅助逻辑。 */
    public static class McpToolOptions {
        /** 保存include集合，维持调用顺序或去重语义。 */
        private Set<String> include = new LinkedHashSet<String>();

        /** 保存exclude集合，维持调用顺序或去重语义。 */
        private Set<String> exclude = new LinkedHashSet<String>();

        /** 是否启用resources启用状态。 */
        private boolean resourcesEnabled = true;

        /** 是否启用prompts启用状态。 */
        private boolean promptsEnabled = true;

        /**
         * 读取Include。
         *
         * @return 返回读取到的Include。
         */
        public Set<String> getInclude() {
            return include;
        }

        /**
         * 写入Include。
         *
         * @param include include 参数。
         */
        public void setInclude(Set<String> include) {
            this.include = include == null ? new LinkedHashSet<String>() : include;
        }

        /**
         * 读取Exclude。
         *
         * @return 返回读取到的Exclude。
         */
        public Set<String> getExclude() {
            return exclude;
        }

        /**
         * 写入Exclude。
         *
         * @param exclude exclude 参数。
         */
        public void setExclude(Set<String> exclude) {
            this.exclude = exclude == null ? new LinkedHashSet<String>() : exclude;
        }

        /**
         * 判断是否Resources 启用。
         *
         * @return 如果Resources 启用满足条件则返回 true，否则返回 false。
         */
        public boolean isResourcesEnabled() {
            return resourcesEnabled;
        }

        /**
         * 写入Resources 启用。
         *
         * @param resources启用 resources启用状态开关值。
         */
        public void setResourcesEnabled(boolean resourcesEnabled) {
            this.resourcesEnabled = resourcesEnabled;
        }

        /**
         * 判断是否Prompts 启用。
         *
         * @return 如果Prompts 启用满足条件则返回 true，否则返回 false。
         */
        public boolean isPromptsEnabled() {
            return promptsEnabled;
        }

        /**
         * 写入Prompts 启用。
         *
         * @param prompts启用 prompts启用状态开关值。
         */
        public void setPromptsEnabled(boolean promptsEnabled) {
            this.promptsEnabled = promptsEnabled;
        }
    }

    /** 表示MCP工具刷新结果，携带调用方后续判断所需信息。 */
    public static class McpToolRefreshResult {
        /** 记录MCP工具刷新中的服务端标识。 */
        private final String serverId;

        /** 记录MCP工具刷新中的next哈希。 */
        private final String nextHash;

        /** 是否启用工具Changed。 */
        private final boolean toolsChanged;

        /** 记录MCP工具刷新中的previous工具次数。 */
        private final int previousToolCount;

        /** 记录MCP工具刷新中的工具次数。 */
        private final int toolCount;

        /** 保存added工具集合，维持调用顺序或去重语义。 */
        private final List<String> addedTools;

        /** 保存removed工具集合，维持调用顺序或去重语义。 */
        private final List<String> removedTools;

        /** 记录MCP工具刷新中的状态。 */
        private final String status;

        /** 记录MCP工具刷新中的错误。 */
        private final String error;

        /**
         * 创建MCP工具刷新结果实例，并注入运行所需依赖。
         *
         * @param serverId MCP 服务端标识。
         * @param nextHash next哈希参数。
         * @param toolsChanged toolsChanged 参数。
         * @param previousToolCount previous工具Count参数。
         * @param toolCount 工具Count参数。
         * @param addedTools addedTools 参数。
         * @param removedTools removedTools 参数。
         * @param status 状态参数。
         * @param error 错误参数。
         */
        public McpToolRefreshResult(
                String serverId,
                String nextHash,
                boolean toolsChanged,
                int previousToolCount,
                int toolCount,
                List<String> addedTools,
                List<String> removedTools,
                String status,
                String error) {
            this.serverId = serverId;
            this.nextHash = nextHash;
            this.toolsChanged = toolsChanged;
            this.previousToolCount = previousToolCount;
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

        /**
         * 读取Server标识。
         *
         * @return 返回读取到的Server标识。
         */
        public String getServerId() {
            return serverId;
        }

        /**
         * 读取Next Hash。
         *
         * @return 返回读取到的Next Hash。
         */
        public String getNextHash() {
            return nextHash;
        }

        /**
         * 判断是否工具Changed。
         *
         * @return 如果工具Changed满足条件则返回 true，否则返回 false。
         */
        public boolean isToolsChanged() {
            return toolsChanged;
        }

        /**
         * 读取Previous工具次数。
         *
         * @return 返回读取到的Previous工具次数。
         */
        public int getPreviousToolCount() {
            return previousToolCount;
        }

        /**
         * 读取工具次数。
         *
         * @return 返回读取到的工具次数。
         */
        public int getToolCount() {
            return toolCount;
        }

        /**
         * 读取Added工具。
         *
         * @return 返回读取到的Added工具。
         */
        public List<String> getAddedTools() {
            return addedTools;
        }

        /**
         * 读取Removed工具。
         *
         * @return 返回读取到的Removed工具。
         */
        public List<String> getRemovedTools() {
            return removedTools;
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
         * 读取Error。
         *
         * @return 返回读取到的Error。
         */
        public String getError() {
            return error;
        }
    }

    /** 提供Prefixed MCP工具能力的扩展入口，屏蔽具体实现差异。 */
    private class PrefixedMcpToolProvider implements ToolProvider {
        /** 记录PrefixedMCP工具中的配置。 */
        private final McpServerConfig config;

        /** 记录PrefixedMCP工具中的提供方。 */
        private McpClientProvider provider;

        /**
         * 创建Prefixed MCP工具提供方实例，并注入运行所需依赖。
         *
         * @param config 当前模块使用的配置对象。
         */
        private PrefixedMcpToolProvider(McpServerConfig config) {
            this.config = config;
        }

        /**
         * 读取工具。
         *
         * @return 返回读取到的工具。
         */
        @Override
        public Collection<FunctionTool> getTools() {
            McpClientProvider activeProvider = ensureProvider();
            Collection<FunctionTool> remoteTools = filteredTools(config, activeProvider.getTools());
            List<FunctionTool> result = new ArrayList<FunctionTool>();
            for (final FunctionTool remote : remoteTools) {
                FunctionToolDesc desc =
                        new FunctionToolDesc(prefixedName(config.getServerId(), remote.name()));
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
                            Map<String, Object> normalizedArgs =
                                    coerceNumericArgs(args, remote.inputSchema());
                            assertSafeRemoteTool(remote.name(), normalizedArgs);
                            return callRemoteToolWithRecovery(remote.name(), normalizedArgs);
                        });
                result.add(desc);
            }
            if (supportsResourcesUtility()) {
                result.add(listResourcesTool());
                result.add(readResourceTool());
            }
            if (supportsPromptsUtility()) {
                result.add(listPromptsTool());
                result.add(getPromptTool());
            }
            return result;
        }

        /**
         * 确保提供方。
         *
         * @return 返回提供方结果。
         */
        private McpClientProvider ensureProvider() {
            if (provider == null) {
                provider = providerFor(config);
            }
            return provider;
        }

        /**
         * 判断是否支持ResourcesUtility。
         *
         * @return 返回supports Resources Utility结果。
         */
        private boolean supportsResourcesUtility() {
            return config.getToolOptions().isResourcesEnabled()
                    && advertisedCapabilityEnabled("resources");
        }

        /**
         * 判断是否支持PromptsUtility。
         *
         * @return 返回supports Prompts Utility结果。
         */
        private boolean supportsPromptsUtility() {
            return config.getToolOptions().isPromptsEnabled()
                    && advertisedCapabilityEnabled("prompts");
        }

        /**
         * 执行advertisedCapability启用状态相关逻辑。
         *
         * @param name 名称参数。
         * @return 返回advertised Capability 启用结果。
         */
        private boolean advertisedCapabilityEnabled(String name) {
            Map<String, Object> capabilities = config.getCapabilities();
            if (capabilities == null || capabilities.isEmpty()) {
                return false;
            }
            Object capability;
            if (capabilities.containsKey(name)) {
                capability = capabilities.get(name);
            } else {
                Object nested = capabilities.get("capabilities");
                if (nested instanceof Map) {
                    Map<?, ?> nestedCapabilities = (Map<?, ?>) nested;
                    if (!nestedCapabilities.containsKey(name)) {
                        return false;
                    }
                    capability = nestedCapabilities.get(name);
                } else {
                    return false;
                }
            }
            if (capability == null) {
                return false;
            }
            if (capability instanceof Boolean) {
                return ((Boolean) capability).booleanValue();
            }
            if (capability instanceof Map) {
                return true;
            }
            if (capability instanceof Collection) {
                return !((Collection<?>) capability).isEmpty();
            }
            return asBoolean(capability, false);
        }

        /**
         * 列出Resources工具。
         *
         * @return 返回Resources工具列表。
         */
        private FunctionTool listResourcesTool() {
            FunctionToolDesc desc =
                    new FunctionToolDesc(prefixedName(config.getServerId(), "list_resources"));
            desc.title("MCP List Resources");
            desc.description(
                    "List resources and resource templates exposed by MCP server "
                            + config.getName()
                            + ".");
            desc.inputSchema(EMPTY_OBJECT_SCHEMA);
            desc.metaPut("mcp_server_id", config.getServerId());
            desc.metaPut("mcp_server_name", config.getName());
            desc.metaPut("mcp_utility", "resources");
            desc.doHandle(args -> listResourcesJson());
            return desc;
        }

        /**
         * 读取Resource工具。
         *
         * @return 返回读取到的Resource工具。
         */
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

        /**
         * 列出Prompts工具。
         *
         * @return 返回Prompts工具列表。
         */
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

        /**
         * 读取提示词工具。
         *
         * @return 返回读取到的提示词工具。
         */
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

        /**
         * 列出Resources JSON。
         *
         * @return 返回Resources JSON列表。
         */
        private String listResourcesJson() {
            return callWithRecovery(
                    "list_resources",
                    new RecoverableCall<String>() {
                        /**
                         * 执行回调调用并返回结果。
                         *
                         * @param activeProvider active提供方标识或键值。
                         * @return 返回call结果。
                         */
                        @Override
                        public String call(McpClientProvider activeProvider) {
                            List<Map<String, Object>> resources =
                                    new ArrayList<Map<String, Object>>();
                            appendResourceMaps(resources, activeProvider.getResources(), false);
                            appendResourceMaps(
                                    resources, activeProvider.getResourceTemplates(), true);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            result.put("resources", resources);
                            return safeToolResultJson(result);
                        }
                    });
        }

        /**
         * 追加Resource Maps。
         *
         * @param result 结果响应或执行结果。
         * @param resources resources 参数。
         * @param template template 参数。
         */
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

        /**
         * 读取Resource JSON。
         *
         * @param uri 待校验或访问的地址参数。
         * @return 返回读取到的Resource JSON。
         */
        private String readResourceJson(String uri) throws Throwable {
            if (StrUtil.isBlank(uri)) {
                throw new IllegalArgumentException("MCP resource uri is required.");
            }
            assertSafeResourceUri(uri);
            return callWithRecovery(
                    "read_resource",
                    new RecoverableCall<String>() {
                        /**
                         * 执行回调调用并返回结果。
                         *
                         * @param activeProvider active提供方标识或键值。
                         * @return 返回call结果。
                         */
                        @Override
                        public String call(McpClientProvider activeProvider) throws Throwable {
                            ResourcePack pack = activeProvider.readResource(uri);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            List<Map<String, Object>> resources =
                                    new ArrayList<Map<String, Object>>();
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
                            return safeToolResultJson(result);
                        }
                    });
        }

        /**
         * 列出Prompts JSON。
         *
         * @return 返回Prompts JSON列表。
         */
        private String listPromptsJson() {
            return callWithRecovery(
                    "list_prompts",
                    new RecoverableCall<String>() {
                        /**
                         * 执行回调调用并返回结果。
                         *
                         * @param activeProvider active提供方标识或键值。
                         * @return 返回call结果。
                         */
                        @Override
                        public String call(McpClientProvider activeProvider) {
                            Collection<FunctionPrompt> prompts = activeProvider.getPrompts();
                            List<Map<String, Object>> promptMaps =
                                    new ArrayList<Map<String, Object>>();
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
                            return safeToolResultJson(result);
                        }
                    });
        }

        /**
         * 读取提示词JSON。
         *
         * @param name 名称参数。
         * @param args 工具或命令参数。
         * @return 返回读取到的提示词JSON。
         */
        private String getPromptJson(final String name, final Map<String, Object> args) {
            if (StrUtil.isBlank(name)) {
                throw new IllegalArgumentException("MCP prompt name is required.");
            }
            return callWithRecovery(
                    "get_prompt",
                    new RecoverableCall<String>() {
                        /**
                         * 执行回调调用并返回结果。
                         *
                         * @param activeProvider active提供方标识或键值。
                         * @return 返回call结果。
                         */
                        @Override
                        public String call(McpClientProvider activeProvider) throws Throwable {
                            Prompt prompt = activeProvider.getPrompt(name, args);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            List<Map<String, Object>> messages =
                                    new ArrayList<Map<String, Object>>();
                            if (prompt != null) {
                                for (ChatMessage message : prompt.getMessages()) {
                                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                                    map.put(
                                            "role",
                                            message.getRole() == null
                                                    ? null
                                                    : message.getRole().name());
                                    map.put("content", message.getContent());
                                    if (message.getMetadata() != null
                                            && !message.getMetadata().isEmpty()) {
                                        map.put("metadata", message.getMetadata());
                                    }
                                    messages.add(map);
                                }
                                if (prompt.attrs() != null && !prompt.attrs().isEmpty()) {
                                    result.put("meta", prompt.attrs());
                                }
                            }
                            result.put("messages", messages);
                            return safeToolResultJson(result);
                        }
                    });
        }

        /**
         * 调用Remote工具With恢复。
         *
         * @param remoteToolName remote工具名称参数。
         * @param args 工具或命令参数。
         * @return 返回call Remote工具With Recovery结果。
         */
        private Object callRemoteToolWithRecovery(
                final String remoteToolName, final Map<String, Object> args) {
            return callWithRecovery(
                    remoteToolName,
                    new RecoverableCall<Object>() {
                        /**
                         * 执行回调调用并返回结果。
                         *
                         * @param activeProvider active提供方标识或键值。
                         * @return 返回call结果。
                         */
                        @Override
                        public Object call(McpClientProvider activeProvider) throws Throwable {
                            FunctionTool tool = findRemoteTool(activeProvider, remoteToolName);
                            if (tool == null) {
                                throw new IllegalStateException(
                                        "MCP tool not found after reconnect: " + remoteToolName);
                            }
                            return redactForToolResult(tool.handle(args));
                        }
                    });
        }

        /**
         * 查找Remote工具。
         *
         * @param activeProvider active提供方标识或键值。
         * @param remoteToolName remote工具名称参数。
         * @return 返回Remote工具结果。
         */
        private FunctionTool findRemoteTool(
                McpClientProvider activeProvider, String remoteToolName) {
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

        /**
         * 执行coerceNumeric参数相关逻辑。
         *
         * @param args 工具或命令参数。
         * @param inputSchema 输入Schema参数。
         * @return 返回coerce Numeric参数结果。
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> coerceNumericArgs(
                Map<String, Object> args, String inputSchema) {
            Map<String, Object> result =
                    args == null
                            ? new LinkedHashMap<String, Object>()
                            : new LinkedHashMap<String, Object>(args);
            if (result.isEmpty() || StrUtil.isBlank(inputSchema)) {
                return result;
            }
            Object parsed = parse(inputSchema);
            if (!(parsed instanceof Map)) {
                return result;
            }
            Object properties = ((Map<?, ?>) parsed).get("properties");
            if (!(properties instanceof Map)) {
                return result;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) properties).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                if (StrUtil.isBlank(key) || !result.containsKey(key)) {
                    continue;
                }
                if (!(entry.getValue() instanceof Map)) {
                    continue;
                }
                Map<String, Object> property = (Map<String, Object>) entry.getValue();
                Object current = result.get(key);
                Object coerced = coerceNumber(current, property);
                if (coerced != current) {
                    result.put(key, coerced);
                }
            }
            return result;
        }

        /**
         * 执行coerceNumber相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @param property property 参数。
         * @return 返回coerce Number结果。
         */
        private Object coerceNumber(Object value, Map<String, Object> property) {
            if (value == null || value instanceof Boolean || property == null) {
                return value;
            }
            boolean integer = isNumericSchema(property, "integer");
            boolean number = integer || isNumericSchema(property, "number");
            if (!number) {
                return value;
            }
            Double parsed = parseDouble(value);
            if (parsed == null) {
                parsed = parseDouble(property.get("default"));
                if (parsed == null) {
                    return value;
                }
            }
            double bounded = clamp(parsed.doubleValue(), property);
            if (integer) {
                long rounded = (long) bounded;
                if (rounded <= Integer.MAX_VALUE && rounded >= Integer.MIN_VALUE) {
                    return Integer.valueOf((int) rounded);
                }
                return Long.valueOf(rounded);
            }
            return Double.valueOf(bounded);
        }

        /**
         * 判断是否Numeric结构。
         *
         * @param property property 参数。
         * @param expectedType expected类型参数。
         * @return 如果Numeric结构满足条件则返回 true，否则返回 false。
         */
        private boolean isNumericSchema(Map<String, Object> property, String expectedType) {
            Object type = property.get("type");
            if (type instanceof Iterable) {
                for (Object item : (Iterable<?>) type) {
                    if (expectedType.equals(String.valueOf(item))) {
                        return true;
                    }
                }
                return false;
            }
            return expectedType.equals(String.valueOf(type));
        }

        /**
         * 解析Double。
         *
         * @param value 待规范化或校验的原始值。
         * @return 返回解析后的Double。
         */
        private Double parseDouble(Object value) {
            if (value instanceof Number) {
                double parsed = ((Number) value).doubleValue();
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    return null;
                }
                return Double.valueOf(parsed);
            }
            String text = String.valueOf(value).trim();
            if (StrUtil.isBlank(text)) {
                return null;
            }
            try {
                double parsed = Double.parseDouble(text);
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    return null;
                }
                return Double.valueOf(parsed);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 执行clamp相关逻辑。
         *
         * @param value 待规范化或校验的原始值。
         * @param property property 参数。
         * @return 返回clamp结果。
         */
        private double clamp(double value, Map<String, Object> property) {
            Double minimum = parseDouble(property.get("minimum"));
            Double maximum = parseDouble(property.get("maximum"));
            double result = value;
            if (minimum != null) {
                result = Math.max(minimum.doubleValue(), result);
            }
            if (maximum != null) {
                result = Math.min(maximum.doubleValue(), result);
            }
            return result;
        }

        /**
         * 调用With恢复。
         *
         * @param operation operation 参数。
         * @param call call 参数。
         * @return 返回call With Recovery结果。
         */
        private <T> T callWithRecovery(String operation, RecoverableCall<T> call) {
            McpClientProvider activeProvider = ensureProvider();
            try {
                return callWithTimeout(operation, call, activeProvider);
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

        /**
         * 调用WithTimeout。
         *
         * @param operation operation 参数。
         * @param call call 参数。
         * @param activeProvider active提供方标识或键值。
         * @return 返回call With Timeout结果。
         */
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
                                /**
                                 * 执行回调调用并返回结果。
                                 *
                                 * @return 返回call结果。
                                 */
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
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
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
                if (cause instanceof McpToolCallException && cause.getCause() != null) {
                    throw cause.getCause();
                }
                throw cause == null ? e : cause;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        /**
         * 格式化Seconds。
         *
         * @param millis millis 参数。
         * @return 返回Seconds结果。
         */
        private String formatSeconds(long millis) {
            return String.format(Locale.ROOT, "%.1f", Double.valueOf(millis / 1000.0D));
        }

        /**
         * 执行reconnect提供方相关逻辑。
         *
         * @return 返回reconnect提供方结果。
         */
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

        /**
         * 判断是否认证Error。
         *
         * @param error 错误参数。
         * @return 如果认证Error满足条件则返回 true，否则返回 false。
         */
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

        /**
         * 判断是否Recoverable Transport Error。
         *
         * @param error 错误参数。
         * @return 如果Recoverable Transport Error满足条件则返回 true，否则返回 false。
         */
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

        /**
         * 执行认证FailureJSON相关逻辑。
         *
         * @param operation operation 参数。
         * @param error 错误参数。
         * @return 返回认证Failure JSON结果。
         */
        private String authFailureJson(String operation, Throwable error) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
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

        /**
         * 执行throwUnchecked相关逻辑。
         *
         * @param error 错误参数。
         */
        private void throwUnchecked(Throwable error) {
            if (error instanceof RuntimeException) {
                if (StrUtil.isBlank(error.getMessage())) {
                    throw new IllegalStateException(
                            "MCP call failed: " + diagnosticError(error), error);
                }
                throw (RuntimeException) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            throw new IllegalStateException(error);
        }

        /**
         * 执行提示词参数相关逻辑。
         *
         * @param params params 参数。
         * @return 返回提示词参数结果。
         */
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

        /**
         * 执行firstArg文本相关逻辑。
         *
         * @param args 工具或命令参数。
         * @param key 配置键或映射键。
         * @return 返回first Arg Text结果。
         */
        private String firstArgText(Map<String, Object> args, String key) {
            Object value = args == null ? null : args.get(key);
            return value == null ? "" : String.valueOf(value).trim();
        }

        /**
         * 执行assert安全Remote工具相关逻辑。
         *
         * @param remoteToolName remote工具名称参数。
         * @param args 工具或命令参数。
         */
        private void assertSafeRemoteTool(String remoteToolName, Map<String, Object> args) {
            assertSafeUrls(remoteToolName, args);
            assertSafePaths(remoteToolName, args);
        }

        /**
         * 执行assert安全资源URI相关逻辑。
         *
         * @param uri 待校验或访问的地址参数。
         */
        private void assertSafeResourceUri(String uri) {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("uri", uri);
            args.put("url", uri);
            assertSafeUrls("read_resource", args);
            assertSafePaths("read_resource", args);
        }

        /**
         * 执行assert安全Urls相关逻辑。
         *
         * @param remoteToolName remote工具名称参数。
         * @param args 工具或命令参数。
         */
        private void assertSafeUrls(String remoteToolName, Map<String, Object> args) {
            if (securityPolicyService == null || args == null || args.isEmpty()) {
                return;
            }
            SecurityPolicyService.UrlVerdict verdict =
                    securityPolicyService.checkToolArgs(remoteToolName, args);
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

        /**
         * 执行assert安全Paths相关逻辑。
         *
         * @param remoteToolName remote工具名称参数。
         * @param args 工具或命令参数。
         */
        private void assertSafePaths(String remoteToolName, Map<String, Object> args) {
            if (securityPolicyService == null || args == null || args.isEmpty()) {
                return;
            }
            SecurityPolicyService.FileVerdict verdict =
                    securityPolicyService.checkFileToolArgs("mcp_remote_tool", args);
            if (!verdict.isAllowed()) {
                throw new IllegalArgumentException(
                        "BLOCKED: MCP tool "
                                + config.getName()
                                + "/"
                                + remoteToolName
                                + " 文件安全策略阻止访问："
                                + verdict.getMessage()
                                + "\n路径："
                                + SecretRedactor.redact(verdict.getPath(), 400));
            }
        }

        /**
         * 执行firstArg映射相关逻辑。
         *
         * @param args 工具或命令参数。
         * @param keys 候选键列表。
         * @return 返回first Arg Map结果。
         */
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

        /**
         * 转换为String。
         *
         * @return 返回转换后的String。
         */
        @Override
        public String toString() {
            return "McpToolProvider(" + config.getServerId() + ")";
        }
    }

    /** 定义Recoverable Call的抽象契约，供不同运行时实现保持一致行为。 */
    private interface RecoverableCall<T> {
        /**
         * 执行回调调用并返回结果。
         *
         * @param provider 模型或能力提供方。
         * @return 返回call结果。
         */
        T call(McpClientProvider provider) throws Throwable;
    }

    /** 定义Discovery Callable的抽象契约，供不同运行时实现保持一致行为。 */
    private interface DiscoveryCallable {
        /**
         * 执行回调调用并返回结果。
         *
         * @param serverId MCP 服务端标识。
         * @return 返回call结果。
         */
        McpToolRefreshResult call(String serverId) throws Exception;
    }

    /** 表示MCP工具Call异常，用于向上层传递可识别的失败原因。 */
    private static class McpToolCallException extends Exception {
        /**
         * 创建MCP工具Call Exception实例，并注入运行所需依赖。
         *
         * @param cause cause 参数。
         */
        private McpToolCallException(Throwable cause) {
            super(cause);
        }
    }
}
