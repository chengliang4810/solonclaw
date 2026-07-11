package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpClientProviderFactory;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.mcp.SolonAiMcpClientProviderFactory;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.FunctionPrompt;
import org.noear.solon.ai.chat.prompt.FunctionPromptDesc;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.resource.FunctionResource;
import org.noear.solon.ai.chat.resource.FunctionResourceDesc;
import org.noear.solon.ai.chat.resource.ResourcePack;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.client.McpClientProvider;

public class McpRuntimeServiceTest {
    @AfterEach
    void clearPolicyApprovals() {
        SecurityPolicyService.clearCurrentThreadPolicyApprovals();
    }

    @Test
    void shouldExposeMcpRuntimePolicySummaryWithoutSecrets() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);

        Map<String, Object> summary = McpRuntimeService.policySummary(env.appConfig);

        assertThat(summary.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("remoteEndpointUrlSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("remoteToolArgumentUrlSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("remoteToolStructuredCredentialArgumentBlocked"))
                .isEqualTo(Boolean.TRUE);
        assertThat(summary.get("remoteToolArgumentPathSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("resourceUriUrlSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("resourceUriPathSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("nestedUrlExtraction")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("inputSchemaSanitized")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("toolsChangeNotificationPersisted")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("oauthFailureStructuredReauth")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("recoverableTransportRetry")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("remoteToolTimeoutMillisDefault")).isEqualTo(Long.valueOf(120000L));
        assertThat(summary.get("connectTimeoutMillisDefault")).isEqualTo(Long.valueOf(60000L));
        assertThat(summary.get("startupDiscoveryAsync")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("reloadDiscoveryAsync")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("discoveryExecutorBounded")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("toolCallExecutorBounded")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("accessTokenHeaderOnlyForRemote")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("authorizationHeaderCaseInsensitive")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary))
                .contains("streamable_stateless")
                .contains("invalid_token")
                .contains("token expired")
                .doesNotContain("secret");
    }

    @Test
    void shouldExposeMcpOAuthPolicySummaryWithoutSecrets() {
        Map<String, Object> summary = DashboardMcpService.oauthPolicySummary();

        assertThat(summary.get("authorizationEndpointUrlSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("tokenEndpointUrlSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("tokenEndpointRedirectUrlSafety")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("tokenEndpointRedirectLimit")).isEqualTo(Integer.valueOf(5));
        assertThat(summary.get("crossOriginRedirectBodyForwardingBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("stateValidationRequired")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("pkceS256Required")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("codeVerifierHiddenFromStatus")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("accessTokenRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("refreshTokenRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("clientSecretRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("refreshRequiresRefreshToken")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("handle401RefreshThenReauth")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("clearRemovesSecretPresenceFlags")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(summary))
                .contains("has_access_token")
                .contains("has_refresh_token")
                .contains("has_client_secret")
                .doesNotContain("secret-sudo")
                .doesNotContain("access-token-value")
                .doesNotContain("refresh-token-value");
    }

    @Test
    void shouldRefreshLiveToolsAndExposePrefixedMcpProvider() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);

        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, new FakeMcpFactory());
        McpRuntimeService.McpToolRefreshResult refresh =
                mcpRuntimeService.refreshLiveTools("local-docs", false);

        assertThat(refresh.getToolCount()).isEqualTo(2);
        assertThat(refresh.isToolsChanged()).isTrue();
        assertThat(refresh.getAddedTools())
                .containsExactly("mcp_local-docs_docs_fetch", "mcp_local-docs_docs_search");
        assertThat(refresh.getRemovedTools()).isEmpty();
        assertThat(readToolsJson(env.sqliteDatabase))
                .contains("mcp_local-docs_docs_search")
                .contains("mcp_local-docs_docs_fetch");

        DefaultToolRegistry registry =
                new DefaultToolRegistry(
                        env.appConfig,
                        new SqlitePreferenceStore(env.sqliteDatabase),
                        env.sessionRepository,
                        null,
                        new com.jimuqu.solon.claw.scheduler.CronJobService(
                                env.appConfig, env.cronJobRepository),
                        env.deliveryService,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        env.gatewayRuntimeRefreshService,
                        null,
                        env.processRegistry,
                        mcpRuntimeService);
        List<Object> tools = registry.resolveEnabledTools("MEMORY:room-1:user-1");
        assertThat(tools)
                .filteredOn(FunctionTool.class::isInstance)
                .map(FunctionTool.class::cast)
                .extracting(FunctionTool::name)
                .contains("mcp_local-docs_docs_search", "mcp_local-docs_docs_fetch");
    }

    @Test
    void shouldReportAddedAndRemovedMcpToolsWhenLiveToolsChange() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        MutableMcpFactory factory = new MutableMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        McpRuntimeService.McpToolRefreshResult first =
                mcpRuntimeService.refreshLiveTools("local-docs", false);
        factory.provider.setToolNames(Arrays.asList("docs_fetch", "docs_new"));
        McpRuntimeService.McpToolRefreshResult second =
                mcpRuntimeService.refreshLiveTools("local-docs", false);

        assertThat(first.getAddedTools())
                .containsExactly("mcp_local-docs_docs_fetch", "mcp_local-docs_docs_search");
        assertThat(first.getRemovedTools()).isEmpty();
        assertThat(second.isToolsChanged()).isTrue();
        assertThat(second.getPreviousToolCount()).isEqualTo(2);
        assertThat(second.getToolCount()).isEqualTo(2);
        assertThat(second.getAddedTools()).containsExactly("mcp_local-docs_docs_new");
        assertThat(second.getRemovedTools()).containsExactly("mcp_local-docs_docs_search");
    }

    @Test
    void shouldSanitizeMcpToolSchemasBeforeExposingThemToModel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase, null, fullMcpCapabilities());
        SchemaEdgeMcpFactory factory = new SchemaEdgeMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        McpRuntimeService.McpToolRefreshResult refresh =
                mcpRuntimeService.refreshLiveTools("local-docs", false);
        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool tool = toolByName(provider, "mcp_local-docs_schema_edge");
        ONode exposed = ONode.ofJson(tool.inputSchema());
        ONode persisted =
                ONode.ofJson(readToolsJson(env.sqliteDatabase)).get(0).get("input_schema");

        assertThat(refresh.getToolCount()).isEqualTo(1);
        assertThat(exposed.get("properties").get("payload").get("properties").isObject()).isTrue();
        assertThat(exposed.get("properties").get("bare").get("type").getString())
                .isEqualTo("object");
        assertThat(exposed.get("properties").get("maybe").get("type").getString())
                .isEqualTo("string");
        assertThat(exposed.get("properties").get("maybe").get("nullable").getBoolean()).isTrue();
        assertThat(exposed.get("required").size()).isEqualTo(1);
        assertThat(exposed.get("required").get(0).getString()).isEqualTo("payload");
        assertThat(persisted.get("properties").get("payload").get("properties").isObject())
                .isTrue();
    }

    @Test
    void shouldFilterMcpToolsAndAllowDisablingResourcePromptUtilityTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        Map<String, Object> tools = new LinkedHashMap<String, Object>();
        tools.put("include", Collections.singletonList("docs_search"));
        tools.put("resources", Boolean.FALSE);
        tools.put("prompts", Boolean.FALSE);
        auth.put("tools", tools);
        saveMcpServer(env.appConfig, env.sqliteDatabase, auth, fullMcpCapabilities());

        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, new FakeMcpFactory());
        McpRuntimeService.McpToolRefreshResult refresh =
                mcpRuntimeService.refreshLiveTools("local-docs", false);

        assertThat(refresh.getToolCount()).isEqualTo(1);
        assertThat(readToolsJson(env.sqliteDatabase))
                .contains("mcp_local-docs_docs_search")
                .doesNotContain("mcp_local-docs_docs_fetch");

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        assertThat(provider.getTools())
                .extracting(FunctionTool::name)
                .containsExactly("mcp_local-docs_docs_search");
    }

    @Test
    void shouldDeferMcpProviderCreationUntilToolsAreRequested() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        CountingMcpFactory factory = new CountingMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        List<ToolProvider> providers = mcpRuntimeService.resolveEnabledToolProviders();

        assertThat(providers).hasSize(1);
        assertThat(factory.createCount).isEqualTo(0);
        assertThat(providers.get(0).toString()).contains("McpToolProvider(local-docs)");

        assertThat(providers.get(0).getTools())
                .extracting(FunctionTool::name)
                .contains("mcp_local-docs_docs_search");
        assertThat(factory.createCount).isEqualTo(1);
    }

    @Test
    void shouldGateMcpUtilityToolsByAdvertisedCapabilities() throws Exception {
        assertUtilityToolsForCapabilities(
                capabilities(Boolean.FALSE, Boolean.FALSE),
                "mcp_local-docs_docs_search",
                "mcp_local-docs_docs_fetch");
        assertUtilityToolsForCapabilities(
                capabilities(Boolean.TRUE, Boolean.FALSE),
                "mcp_local-docs_docs_search",
                "mcp_local-docs_docs_fetch",
                "mcp_local-docs_list_resources",
                "mcp_local-docs_read_resource");
        assertUtilityToolsForCapabilities(
                capabilities(Boolean.FALSE, Boolean.TRUE),
                "mcp_local-docs_docs_search",
                "mcp_local-docs_docs_fetch",
                "mcp_local-docs_list_prompts",
                "mcp_local-docs_get_prompt");
        assertUtilityToolsForCapabilities(
                fullMcpCapabilities(),
                "mcp_local-docs_docs_search",
                "mcp_local-docs_docs_fetch",
                "mcp_local-docs_list_resources",
                "mcp_local-docs_read_resource",
                "mcp_local-docs_list_prompts",
                "mcp_local-docs_get_prompt");
    }

    @Test
    void shouldNotAddMcpUtilityToolsWhenCapabilitiesAreUnknown() throws Exception {
        assertUtilityToolsForCapabilities(
                null, "mcp_local-docs_docs_search", "mcp_local-docs_docs_fetch");
    }

    @Test
    void shouldNotExposeBlockedMcpServersAtRuntime() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        updateServerStatus(env.sqliteDatabase, "blocked");

        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, new FakeMcpFactory());

        assertThat(mcpRuntimeService.resolveEnabledToolProviders()).isEmpty();
    }

    @Test
    void shouldGuardMcpToolAndResourceArgumentsBeforeRemoteCall() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase, null, fullMcpCapabilities());
        FakeMcpFactory factory = new FakeMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool docsFetch = toolByName(provider, "mcp_local-docs_docs_fetch");
        FunctionTool readResource = toolByName(provider, "mcp_local-docs_read_resource");

        Map<String, Object> remoteArgs = new LinkedHashMap<String, Object>();
        remoteArgs.put("uri", "http://169.254.169.254/latest/meta-data/?token=secret123");
        assertThatThrownBy(() -> docsFetch.handle(remoteArgs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP tool")
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret123");

        Map<String, Object> credentialArgs = new LinkedHashMap<String, Object>();
        credentialArgs.put(
                "headers", Collections.singletonMap("Authorization", "Bearer secret-token"));
        assertThatThrownBy(() -> docsFetch.handle(credentialArgs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret-token");

        Map<String, Object> resourceArgs = new LinkedHashMap<String, Object>();
        resourceArgs.put("uri", "http://169.254.169.254/latest/meta-data/");
        assertThatThrownBy(() -> readResource.handle(resourceArgs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略");
        assertThat(factory.provider.remoteCallCount).isEqualTo(0);
    }

    @Test
    void shouldRedactMcpResourceAndPromptUtilityToolResults() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase, null, fullMcpCapabilities());
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, new SecretMcpFactory());

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool remoteTool = toolByName(provider, "mcp_local-docs_docs_fetch");
        FunctionTool listResources = toolByName(provider, "mcp_local-docs_list_resources");
        FunctionTool readResource = toolByName(provider, "mcp_local-docs_read_resource");
        FunctionTool listPrompts = toolByName(provider, "mcp_local-docs_list_prompts");
        FunctionTool getPrompt = toolByName(provider, "mcp_local-docs_get_prompt");
        Map<String, Object> resourceArgs = new LinkedHashMap<String, Object>();
        resourceArgs.put("uri", "https://example.com/guide");
        Map<String, Object> promptArgs = new LinkedHashMap<String, Object>();
        promptArgs.put("name", "summarize");
        promptArgs.put("arguments", Collections.singletonMap("topic", "release"));

        String remote = String.valueOf(remoteTool.handle(Collections.<String, Object>emptyMap()));
        String resources =
                String.valueOf(listResources.handle(Collections.<String, Object>emptyMap()));
        String resource = String.valueOf(readResource.handle(resourceArgs));
        String prompts = String.valueOf(listPrompts.handle(Collections.<String, Object>emptyMap()));
        String prompt = String.valueOf(getPrompt.handle(promptArgs));

        assertThat(remote)
                .contains("Authorization: Bearer ***")
                .contains("access_token=***")
                .doesNotContain("ghp_remotetool12345")
                .doesNotContain("secret-remote-token");
        assertThat(resources)
                .contains("token=***")
                .contains("Authorization: Bearer ***")
                .doesNotContain("secret-resource-title")
                .doesNotContain("ghp_resource12345");
        assertThat(resource)
                .contains("api_key=***")
                .contains("\"access_token\":\"***\"")
                .doesNotContain("sk-test-resource12345")
                .doesNotContain("secret-pack-token");
        assertThat(prompts)
                .contains("OPENAI_API_KEY=***")
                .contains("bearer ***")
                .doesNotContain("sk-test-prompt12345")
                .doesNotContain("ghp_promptmeta12345");
        assertThat(prompt)
                .contains("token=***")
                .contains("bearer ***")
                .doesNotContain("secret-prompt-message")
                .doesNotContain("ghp_promptresult12345");
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldReturnStructuredReauthResultWhenMcpToolReportsAuthFailure() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        RecoveringMcpFactory factory = new RecoveringMcpFactory("auth");
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool docsFetch = toolByName(provider, "mcp_local-docs_docs_fetch");

        Object raw = docsFetch.handle(Collections.<String, Object>emptyMap());
        ONode result = ONode.ofJson(String.valueOf(raw));

        assertThat(result.get("needs_reauth").getBoolean()).isTrue();
        assertThat(result.get("server").getString()).isEqualTo("local-docs");
        assertThat(result.get("operation").getString()).isEqualTo("docs_fetch");
        assertThat(factory.createCount).isEqualTo(1);
    }

    @Test
    void shouldReconnectAndRetryMcpToolAfterTransportSessionFailure() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        RecoveringMcpFactory factory = new RecoveringMcpFactory("transport");
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool docsFetch = toolByName(provider, "mcp_local-docs_docs_fetch");

        Object raw = docsFetch.handle(Collections.<String, Object>emptyMap());

        assertThat(String.valueOf(raw)).contains("recovered");
        assertThat(factory.createCount).isEqualTo(2);
        assertThat(factory.first.closed).isTrue();
        assertThat(factory.second.remoteCallCount).isEqualTo(1);
    }

    @Test
    void shouldReconnectAndRetryMcpToolAfterStalePipeFailures() throws Throwable {
        for (String marker :
                Arrays.asList(
                        "ClosedResourceError",
                        "transport is closed",
                        "end of file",
                        "unknown session")) {
            TestEnvironment env = TestEnvironment.withFakeLlm();
            env.appConfig.getMcp().setEnabled(true);
            saveMcpServer(env.appConfig, env.sqliteDatabase);
            RecoveringMcpFactory factory = new RecoveringMcpFactory("transport:" + marker);
            McpRuntimeService mcpRuntimeService =
                    new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

            ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
            FunctionTool docsFetch = toolByName(provider, "mcp_local-docs_docs_fetch");

            Object raw = docsFetch.handle(Collections.<String, Object>emptyMap());

            assertThat(String.valueOf(raw)).contains("recovered");
            assertThat(factory.createCount).isEqualTo(2);
            assertThat(factory.first.closed).as(marker).isTrue();
            assertThat(factory.second.remoteCallCount).as(marker).isEqualTo(1);
            mcpRuntimeService.shutdown();
        }
    }

    @Test
    void shouldCoerceNumericMcpToolArgumentsBeforeRemoteCall() throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        NumericMcpFactory factory = new NumericMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool tool = toolByName(provider, "mcp_local-docs_numeric_tool");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("limit", "999");
        args.put("ratio", "0.25");
        args.put("label", "keep");

        Object raw = tool.handle(args);

        assertThat(String.valueOf(raw)).contains("ok");
        assertThat(factory.provider.lastArgs.get("limit")).isEqualTo(Integer.valueOf(200));
        assertThat(factory.provider.lastArgs.get("ratio")).isEqualTo(Double.valueOf(0.25D));
        assertThat(factory.provider.lastArgs.get("label")).isEqualTo("keep");
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldBlockUnsafeMcpToolArgumentsBeforeRemoteCall() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        NumericMcpFactory factory = new NumericMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool tool = toolByName(provider, "mcp_local-docs_numeric_tool");
        Map<String, Object> unsafeUrl = new LinkedHashMap<String, Object>();
        unsafeUrl.put("url", "http://169.254.169.254/latest/meta-data/");
        assertThatThrownBy(() -> tool.handle(unsafeUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略");

        Map<String, Object> unsafePath = new LinkedHashMap<String, Object>();
        unsafePath.put("path", "../../remote-owned-path");
        assertThatThrownBy(() -> tool.handle(unsafePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略");
        assertThat(factory.provider.lastArgs).isEmpty();
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldFallbackAndClampInvalidNumericMcpToolArgumentsWhenSchemaProvidesDefault()
            throws Throwable {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        NumericMcpFactory factory = new NumericMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool tool = toolByName(provider, "mcp_local-docs_numeric_tool");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("limit", "bad");
        args.put("ratio", Double.valueOf(Double.POSITIVE_INFINITY));
        args.put("page", "-5");
        args.put("label", "keep");

        Object raw = tool.handle(args);

        assertThat(String.valueOf(raw)).contains("ok");
        assertThat(factory.provider.lastArgs.get("limit")).isEqualTo(Integer.valueOf(50));
        assertThat(factory.provider.lastArgs.get("ratio")).isEqualTo(Double.valueOf(1D));
        assertThat(factory.provider.lastArgs.get("page")).isEqualTo(Integer.valueOf(1));
        assertThat(factory.provider.lastArgs.get("label")).isEqualTo("keep");
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldReportConfiguredMcpToolTimeoutAndCancelCall() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        auth.put("tool_timeout_ms", Integer.valueOf(200));
        saveMcpServer(env.appConfig, env.sqliteDatabase, auth);
        BlockingMcpFactory factory = new BlockingMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool slowTool = toolByName(provider, "mcp_local-docs_slow_tool");

        assertThatThrownBy(() -> slowTool.handle(Collections.<String, Object>emptyMap()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP call timed out after")
                .hasMessageContaining("configured timeout: 0.2s")
                .hasMessageContaining("server: local-docs")
                .hasMessageContaining("operation: slow_tool");
        assertThat(factory.provider.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldQueueSlowMcpDiscoveryWithoutBlockingCaller() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        SlowDiscoveryMcpFactory factory = new SlowDiscoveryMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        long start = System.nanoTime();
        CompletableFuture<McpRuntimeService.McpToolRefreshResult> future =
                mcpRuntimeService.refreshLiveToolsAsync("local-docs", false);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis).isLessThan(200L);
        SlowDiscoveryMcpClientProvider provider = awaitSlowDiscoveryProvider(factory);
        assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isFalse();
        provider.release.countDown();
        assertThat(future.get(2, TimeUnit.SECONDS).getStatus()).isEqualTo("ready");
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldRecordLastErrorForFailedDiscoveryAndRecoverOnLaterReload() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        FlakyDiscoveryMcpFactory factory = new FlakyDiscoveryMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);
        DashboardMcpService dashboardMcpService =
                new DashboardMcpService(env.appConfig, env.sqliteDatabase, null, mcpRuntimeService);

        CompletableFuture<McpRuntimeService.McpToolRefreshResult> failed =
                mcpRuntimeService.refreshLiveToolsAsync("local-docs", false);

        assertThatThrownBy(() -> failed.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(readMcpStatus(env.sqliteDatabase, "local-docs"))
                .containsEntry("status", "error");
        assertThat(readMcpStatus(env.sqliteDatabase, "local-docs").get("last_error"))
                .asString()
                .contains("discovery failed")
                .doesNotContain("secret-failure-token");
        assertThat(String.valueOf(dashboardMcpService.list().get("servers")))
                .contains("discovery failed")
                .doesNotContain("secret-failure-token");

        factory.fail = false;
        McpRuntimeService.McpToolRefreshResult recovered = mcpRuntimeService.reload("local-docs");

        assertThat(recovered.getStatus()).isEqualTo("ready");
        assertThat(readMcpStatus(env.sqliteDatabase, "local-docs"))
                .containsEntry("status", "ready")
                .containsEntry("last_error", null);
        assertThat(readToolsJson(env.sqliteDatabase)).contains("mcp_local-docs_docs_recovered");
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldReportAsyncReloadQueueFailureWhenDiscoveryExecutorIsClosed() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, new FakeMcpFactory());
        DashboardMcpService dashboardMcpService =
                new DashboardMcpService(env.appConfig, env.sqliteDatabase, null, mcpRuntimeService);
        mcpRuntimeService.shutdown();

        Map<String, Object> queued = dashboardMcpService.reloadAllAsyncView();

        assertThat(queued)
                .containsEntry("status", "failed")
                .containsEntry("async", Boolean.TRUE)
                .containsEntry("server_count", Integer.valueOf(1));
        assertThat(String.valueOf(queued.get("error"))).contains("rejected");
    }

    @Test
    void shouldReturnQuicklyWhenDashboardQueuesAsyncReload() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        SlowDiscoveryMcpFactory factory = new SlowDiscoveryMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);
        DashboardMcpService dashboardMcpService =
                new DashboardMcpService(env.appConfig, env.sqliteDatabase, null, mcpRuntimeService);

        long start = System.nanoTime();
        Map<String, Object> queued = dashboardMcpService.reloadAllAsyncView();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis).isLessThan(200L);
        assertThat(queued)
                .containsEntry("status", "queued")
                .containsEntry("async", Boolean.TRUE)
                .containsEntry("server_count", Integer.valueOf(1));
        SlowDiscoveryMcpClientProvider provider = awaitSlowDiscoveryProvider(factory);
        assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
        provider.release.countDown();
        assertThat(readEventuallyToolsJson(env.sqliteDatabase, "mcp_local-docs_slow_tool"))
                .contains("mcp_local-docs_slow_tool");
        mcpRuntimeService.shutdown();
    }

    @Test
    void shouldIncludeExceptionTypeWhenMcpToolErrorMessageIsBlank() throws Throwable {
        for (String mode : Arrays.asList("blank-error", "space-error")) {
            TestEnvironment env = TestEnvironment.withFakeLlm();
            env.appConfig.getMcp().setEnabled(true);
            saveMcpServer(env.appConfig, env.sqliteDatabase);
            RecoveringMcpFactory factory = new RecoveringMcpFactory(mode);
            McpRuntimeService mcpRuntimeService =
                    new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

            ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
            FunctionTool docsFetch = toolByName(provider, "mcp_local-docs_docs_fetch");

            Throwable thrown =
                    catchThrowable(() -> docsFetch.handle(Collections.<String, Object>emptyMap()));

            assertThat(thrown).as(mode).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getMessage())
                    .contains("MCP call failed")
                    .contains("blank-error".equals(mode) ? "BlankMcpError" : "WhitespaceMcpError")
                    .doesNotEndWith(": ");
            mcpRuntimeService.shutdown();
        }
    }

    private void saveMcpServer(AppConfig appConfig, SqliteDatabase database) throws Exception {
        saveMcpServer(appConfig, database, null);
    }

    private void saveMcpServer(
            AppConfig appConfig, SqliteDatabase database, Map<String, Object> auth)
            throws Exception {
        saveMcpServer(appConfig, database, auth, null);
    }

    private void saveMcpServer(
            AppConfig appConfig,
            SqliteDatabase database,
            Map<String, Object> auth,
            Map<String, Object> capabilities)
            throws Exception {
        appConfig.getMcp().setEnabled(true);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "local-docs");
        body.put("name", "Local Docs");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        if (auth != null) {
            body.put("auth", auth);
        }
        if (capabilities != null) {
            body.put("capabilities", capabilities);
        }
        new com.jimuqu.solon.claw.web.DashboardMcpService(appConfig, database).save(body);
    }

    private void assertUtilityToolsForCapabilities(
            Map<String, Object> capabilities, String... expectedNames) throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase, null, capabilities);
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, new FakeMcpFactory());
        try {
            ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
            assertThat(provider.getTools())
                    .extracting(FunctionTool::name)
                    .containsExactly(expectedNames);
        } finally {
            mcpRuntimeService.shutdown();
        }
    }

    private Map<String, Object> fullMcpCapabilities() {
        return capabilities(Boolean.TRUE, Boolean.TRUE);
    }

    private Map<String, Object> capabilities(Boolean resources, Boolean prompts) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tools", Boolean.TRUE);
        result.put("resources", resources);
        result.put("prompts", prompts);
        return result;
    }

    private HttpServer secretTokenServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void shouldRedactMcpOauthMissingAccessTokenResponses() throws Throwable {
        HttpServer server =
                secretTokenServer(
                        "{\"refresh_token\":\"ghp_mcpoauthrefresh12345\",\"client_secret\":\"sk-mcp-oauth-secret\"}");
        try {
            TestEnvironment env = TestEnvironment.withFakeLlm();
            DashboardMcpService service =
                    new DashboardMcpService(env.appConfig, env.sqliteDatabase);
            Method exchangeCode =
                    DashboardMcpService.class.getDeclaredMethod(
                            "exchangeOAuthCode",
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            Map.class);
            Method exchangeRefresh =
                    DashboardMcpService.class.getDeclaredMethod(
                            "exchangeRefreshToken",
                            String.class,
                            String.class,
                            String.class,
                            Map.class);
            exchangeCode.setAccessible(true);
            exchangeRefresh.setAccessible(true);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
            Map<String, Object> oauth = new LinkedHashMap<String, Object>();
            oauth.put("client_secret", "sk-local-client-secret");

            assertThatThrownBy(
                            () ->
                                    invoke(
                                            exchangeCode,
                                            service,
                                            endpoint,
                                            "client-1",
                                            "http://localhost/callback",
                                            "code-1",
                                            "verifier-1",
                                            oauth))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing access_token")
                    .hasMessageContaining("\"has_refresh_token\":true")
                    .hasMessageContaining("\"has_client_secret\":true")
                    .hasMessageNotContaining("ghp_mcpoauthrefresh12345")
                    .hasMessageNotContaining("sk-mcp-oauth-secret");

            assertThatThrownBy(
                            () ->
                                    invoke(
                                            exchangeRefresh,
                                            service,
                                            endpoint,
                                            "client-1",
                                            "refresh-1",
                                            oauth))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing access_token")
                    .hasMessageContaining("\"has_refresh_token\":true")
                    .hasMessageContaining("\"has_client_secret\":true")
                    .hasMessageNotContaining("ghp_mcpoauthrefresh12345")
                    .hasMessageNotContaining("sk-mcp-oauth-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSaveSseMcpServerAndRejectInvalidTransports() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        com.jimuqu.solon.claw.web.DashboardMcpService service =
                new com.jimuqu.solon.claw.web.DashboardMcpService(
                        env.appConfig, env.sqliteDatabase);

        Map<String, Object> sse = new LinkedHashMap<String, Object>();
        sse.put("serverId", "sse-docs");
        sse.put("name", "SSE Docs");
        sse.put("transport", "SSE");
        sse.put("endpoint", "https://example.com/sse");
        service.save(sse);

        assertThat(readMcpTransport(env.sqliteDatabase, "sse-docs")).isEqualTo("sse");

        Map<String, Object> hyphenated = new LinkedHashMap<String, Object>();
        hyphenated.put("serverId", "stateless-docs");
        hyphenated.put("name", "Stateless Docs");
        hyphenated.put("transport", "streamable-stateless");
        hyphenated.put("endpoint", "https://example.com/mcp");
        service.save(hyphenated);

        assertThat(readMcpTransport(env.sqliteDatabase, "stateless-docs"))
                .isEqualTo("streamable_stateless");

        Map<String, Object> httpAlias = new LinkedHashMap<String, Object>();
        httpAlias.put("serverId", "http-docs");
        httpAlias.put("name", "HTTP Docs");
        httpAlias.put("transport", "http");
        httpAlias.put("endpoint", "https://example.com/http-mcp");
        service.save(httpAlias);

        assertThat(readMcpTransport(env.sqliteDatabase, "http-docs")).isEqualTo("streamable");

        Map<String, Object> invalid = new LinkedHashMap<String, Object>();
        invalid.put("serverId", "bad-docs");
        invalid.put("transport", "websocket");
        invalid.put("endpoint", "https://example.com/ws");
        assertThatThrownBy(() -> service.save(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 MCP transport");

        Map<String, Object> missingEndpoint = new LinkedHashMap<String, Object>();
        missingEndpoint.put("serverId", "missing-endpoint");
        missingEndpoint.put("transport", "sse");
        assertThatThrownBy(() -> service.save(missingEndpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 endpoint");
    }

    @Test
    void shouldRejectUnknownTransportWhenCreatingSolonAiMcpProvider() {
        McpRuntimeService.McpServerConfig config = new McpRuntimeService.McpServerConfig();
        config.setServerId("bad-transport");
        config.setTransport("websocket");
        config.setEndpoint("https://example.com/mcp");

        assertThatThrownBy(() -> new SolonAiMcpClientProviderFactory(null).create(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 MCP transport");
    }

    @Test
    void shouldOnlyAttachMcpAccessTokenHeaderForRemoteTransports() throws Exception {
        McpRuntimeService.McpServerConfig stdio = new McpRuntimeService.McpServerConfig();
        stdio.setServerId("stdio-docs");
        stdio.setTransport("stdio");
        stdio.setCommand("docs-mcp");
        stdio.setAccessToken("stdio-token-for-header-test");

        McpRuntimeService.McpServerConfig remote = new McpRuntimeService.McpServerConfig();
        remote.setServerId("remote-docs");
        remote.setTransport("streamable");
        remote.setEndpoint("https://example.com/mcp");
        remote.setAccessToken("remote-token-for-header-test");

        McpClientProvider stdioProvider = null;
        McpClientProvider remoteProvider = null;
        try {
            SolonAiMcpClientProviderFactory factory = new SolonAiMcpClientProviderFactory(null);
            stdioProvider = factory.create(stdio);
            remoteProvider = factory.create(remote);

            assertThat(clientProps(stdioProvider).getChannel()).isEqualTo(McpChannel.STDIO);
            assertThat(clientProps(stdioProvider).getHeaders()).doesNotContainKey("Authorization");
            assertThat(clientProps(remoteProvider).getChannel()).isEqualTo(McpChannel.STREAMABLE);
            assertThat(clientProps(remoteProvider).getHeaders())
                    .containsEntry("Authorization", "Bearer remote-token-for-header-test");
        } finally {
            if (stdioProvider != null) {
                stdioProvider.close();
            }
            if (remoteProvider != null) {
                remoteProvider.close();
            }
        }
    }

    @Test
    void shouldTreatMcpAuthorizationHeaderCaseInsensitively() throws Exception {
        McpRuntimeService.McpServerConfig remote = new McpRuntimeService.McpServerConfig();
        remote.setServerId("remote-docs");
        remote.setTransport("sse");
        remote.setEndpoint("https://example.com/mcp");
        remote.setAccessToken("oauth-token-for-header-test");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("authorization", "Bearer configured-token-for-header-test");
        remote.setHeaders(headers);

        McpClientProvider provider = null;
        try {
            provider = new SolonAiMcpClientProviderFactory(null).create(remote);

            assertThat(clientProps(provider).getHeaders())
                    .containsEntry("authorization", "Bearer configured-token-for-header-test")
                    .doesNotContainKey("Authorization");
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @Test
    void shouldNotDuplicateMcpBearerHeaderWhenConfiguredHeaderUsesDifferentCase() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Map<String, Object> configuredHeaders = new LinkedHashMap<String, Object>();
        configuredHeaders.put("authorization", "Bearer configured-token-for-header-test");
        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        auth.put("headers", configuredHeaders);
        auth.put("bearer_token", "runtime-token-for-header-test");
        saveMcpServer(env.appConfig, env.sqliteDatabase, auth);

        CapturingMcpFactory factory = new CapturingMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);
        try {
            mcpRuntimeService.resolveEnabledToolProviders().get(0).getTools();

            assertThat(factory.config).isNotNull();
            assertThat(factory.config.getHeaders())
                    .containsEntry("authorization", "Bearer configured-token-for-header-test")
                    .doesNotContainKey("Authorization");
        } finally {
            mcpRuntimeService.shutdown();
        }
    }

    private String readToolsJson(SqliteDatabase database) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select tools_json from mcp_servers where server_id = ?");
            statement.setString(1, "local-docs");
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getString("tools_json") : "";
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private String readEventuallyToolsJson(SqliteDatabase database, String expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        String toolsJson = "";
        while (System.currentTimeMillis() < deadline) {
            toolsJson = readToolsJson(database);
            if (toolsJson != null && toolsJson.contains(expected)) {
                return toolsJson;
            }
            Thread.sleep(25L);
        }
        return toolsJson;
    }

    private SlowDiscoveryMcpClientProvider awaitSlowDiscoveryProvider(
            SlowDiscoveryMcpFactory factory) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (factory.provider != null) {
                return factory.provider;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("slow discovery provider was not created");
    }

    private Map<String, Object> readMcpStatus(SqliteDatabase database, String serverId)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select status, last_error from mcp_servers where server_id = ?");
            statement.setString(1, serverId);
            ResultSet resultSet = statement.executeQuery();
            try {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                if (resultSet.next()) {
                    result.put("status", resultSet.getString("status"));
                    result.put("last_error", resultSet.getString("last_error"));
                }
                return result;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private String readMcpTransport(SqliteDatabase database, String serverId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select transport from mcp_servers where server_id = ?");
            statement.setString(1, serverId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getString("transport") : "";
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private McpClientProperties clientProps(McpClientProvider provider) throws Exception {
        Field field = McpClientProvider.class.getDeclaredField("clientProps");
        field.setAccessible(true);
        return (McpClientProperties) field.get(provider);
    }

    private void updateServerStatus(SqliteDatabase database, String status) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update mcp_servers set status = ? where server_id = ?");
            statement.setString(1, status);
            statement.setString(2, "local-docs");
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private void saveRemoteMcpServerDirectly(SqliteDatabase database, String endpoint)
            throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into mcp_servers (server_id, name, transport, endpoint, command, args_json, auth_json, oauth_json, capabilities_json, status, tools_json, last_tools_hash, last_tools_json, last_error, enabled, created_at, updated_at, last_checked_at, last_tools_changed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, "remote-docs");
            statement.setString(2, "Remote Docs");
            statement.setString(3, "sse");
            statement.setString(4, endpoint);
            statement.setString(5, "");
            statement.setString(6, "[]");
            statement.setString(7, "{}");
            statement.setString(8, "{}");
            statement.setString(9, "{}");
            statement.setString(10, "configured");
            statement.setString(11, "[]");
            statement.setString(12, "");
            statement.setString(13, "");
            statement.setString(14, null);
            statement.setInt(15, 1);
            statement.setLong(16, now);
            statement.setLong(17, now);
            statement.setLong(18, 0L);
            statement.setLong(19, 0L);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private FunctionTool toolByName(ToolProvider provider, String name) {
        for (FunctionTool tool : provider.getTools()) {
            if (name.equals(tool.name())) {
                return tool;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    private static class FakeMcpFactory implements McpClientProviderFactory {
        private FakeMcpClientProvider provider;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            provider = new FakeMcpClientProvider();
            return provider;
        }
    }

    private static class CountingMcpFactory implements McpClientProviderFactory {
        private int createCount;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            createCount++;
            return new FakeMcpClientProvider();
        }
    }

    private static class CapturingMcpFactory implements McpClientProviderFactory {
        private McpRuntimeService.McpServerConfig config;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            this.config = config;
            return new FakeMcpClientProvider();
        }
    }

    private static class FakeMcpClientProvider extends McpClientProvider {
        private int remoteCallCount;

        FakeMcpClientProvider() {
            super(properties());
        }

        @Override
        public Collection<FunctionTool> getTools() {
            FunctionToolDesc search = new FunctionToolDesc("docs_search");
            search.title("Docs Search");
            search.description("Search docs");
            search.inputSchema(
                    "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
            search.doHandle(args -> Collections.singletonMap("ok", Boolean.TRUE));

            FunctionToolDesc fetch = new FunctionToolDesc("docs_fetch");
            fetch.title("Docs Fetch");
            fetch.description("Fetch docs");
            fetch.inputSchema(
                    "{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\"}}}");
            fetch.doHandle(
                    args -> {
                        remoteCallCount++;
                        return Collections.singletonMap("content", "doc");
                    });
            return Arrays.<FunctionTool>asList(search, fetch);
        }

        @Override
        public Collection<FunctionResource> getResources() {
            FunctionResourceDesc resource = new FunctionResourceDesc("guide");
            resource.title("Guide");
            resource.description("Project guide");
            resource.uri("docs://guide");
            resource.mimeType("text/plain");
            resource.doHandle(uri -> new ResourcePack().addText("guide content"));
            return Collections.<FunctionResource>singletonList(resource);
        }

        @Override
        public Collection<FunctionResource> getResourceTemplates() {
            FunctionResourceDesc resource = new FunctionResourceDesc("topic");
            resource.title("Topic");
            resource.description("Topic template");
            resource.uri("docs://topic/{name}");
            resource.mimeType("text/plain");
            resource.doHandle(uri -> new ResourcePack().addText("topic content"));
            return Collections.<FunctionResource>singletonList(resource);
        }

        @Override
        public ResourcePack readResource(String uri) {
            return new ResourcePack().addText("read " + uri);
        }

        @Override
        public Collection<FunctionPrompt> getPrompts() {
            FunctionPromptDesc prompt = new FunctionPromptDesc("summarize");
            prompt.title("Summarize");
            prompt.description("Summarize docs");
            prompt.paramAdd("topic", true, "Topic to summarize");
            prompt.doHandle(
                    args -> Prompt.of(ChatMessage.ofUser("summarize " + args.get("topic"))));
            return Collections.<FunctionPrompt>singletonList(prompt);
        }

        @Override
        public Prompt getPrompt(String name, Map<String, Object> args) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.ofUser("prompt " + name + " " + args.get("topic")));
            return Prompt.of(messages);
        }

        @Override
        public void close() {}

        private static McpClientProperties properties() {
            McpClientProperties properties = new McpClientProperties();
            properties.setChannel(McpChannel.STDIO);
            properties.setCommand("fake-mcp");
            return properties;
        }
    }

    private static class SecretMcpFactory implements McpClientProviderFactory {
        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            return new SecretMcpClientProvider();
        }
    }

    private static class SecretMcpClientProvider extends FakeMcpClientProvider {
        @Override
        public Collection<FunctionTool> getTools() {
            FunctionToolDesc fetch = new FunctionToolDesc("docs_fetch");
            fetch.title("Secret Docs Fetch");
            fetch.description("Fetch docs");
            fetch.inputSchema("{\"type\":\"object\",\"properties\":{}}");
            fetch.doHandle(
                    args -> {
                        Map<String, Object> result = new LinkedHashMap<String, Object>();
                        result.put("content", "Authorization: Bearer ghp_remotetool12345");
                        result.put("access_token", "secret-remote-token");
                        return result;
                    });
            return Collections.<FunctionTool>singletonList(fetch);
        }

        @Override
        public Collection<FunctionResource> getResources() {
            FunctionResourceDesc resource = new FunctionResourceDesc("guide");
            resource.title("Guide token=secret-resource-title");
            resource.description("Authorization: Bearer ghp_resource12345");
            resource.uri("docs://guide?token=secret-resource-uri");
            resource.mimeType("text/plain");
            resource.metaPut("access_token", "secret-resource-meta");
            resource.doHandle(uri -> new ResourcePack().addText("guide content"));
            return Collections.<FunctionResource>singletonList(resource);
        }

        @Override
        public ResourcePack readResource(String uri) {
            ResourcePack pack = new ResourcePack().addText("read api_key=sk-test-resource12345");
            pack.metas().put("access_token", "secret-pack-token");
            return pack;
        }

        @Override
        public Collection<FunctionPrompt> getPrompts() {
            FunctionPromptDesc prompt = new FunctionPromptDesc("summarize");
            prompt.title("Prompt OPENAI_API_KEY=sk-test-prompt12345");
            prompt.description("Prompt bearer ghp_promptmeta12345");
            prompt.paramAdd("topic", true, "Topic token=secret-param-token");
            prompt.metaPut("client_secret", "secret-prompt-meta");
            prompt.doHandle(
                    args -> Prompt.of(ChatMessage.ofUser("summarize " + args.get("topic"))));
            return Collections.<FunctionPrompt>singletonList(prompt);
        }

        @Override
        public Prompt getPrompt(String name, Map<String, Object> args) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.ofUser("prompt token=secret-prompt-message"));
            messages.add(ChatMessage.ofAssistant("prompt bearer ghp_promptresult12345"));
            return Prompt.of(messages);
        }
    }

    private static class MutableMcpFactory implements McpClientProviderFactory {
        private MutableMcpClientProvider provider;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            provider = new MutableMcpClientProvider();
            return provider;
        }
    }

    private static class MutableMcpClientProvider extends McpClientProvider {
        private List<String> toolNames = Arrays.asList("docs_search", "docs_fetch");

        MutableMcpClientProvider() {
            super(FakeMcpClientProvider.properties());
        }

        private void setToolNames(List<String> toolNames) {
            this.toolNames = new ArrayList<String>(toolNames);
        }

        @Override
        public Collection<FunctionTool> getTools() {
            List<FunctionTool> result = new ArrayList<FunctionTool>();
            for (String name : toolNames) {
                FunctionToolDesc tool = new FunctionToolDesc(name);
                tool.title(name);
                tool.description(name);
                tool.inputSchema("{\"type\":\"object\",\"properties\":{}}");
                tool.doHandle(args -> Collections.singletonMap("ok", Boolean.TRUE));
                result.add(tool);
            }
            return result;
        }

        @Override
        public void close() {}
    }

    private static class SchemaEdgeMcpFactory implements McpClientProviderFactory {
        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            return new SchemaEdgeMcpClientProvider();
        }
    }

    private static class SchemaEdgeMcpClientProvider extends McpClientProvider {
        SchemaEdgeMcpClientProvider() {
            super(FakeMcpClientProvider.properties());
        }

        @Override
        public Collection<FunctionTool> getTools() {
            FunctionToolDesc edge = new FunctionToolDesc("schema_edge");
            edge.title("Schema Edge");
            edge.description("Tool with MCP schema edge cases");
            edge.inputSchema(
                    "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"object\"},\"bare\":\"object\",\"maybe\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],\"description\":\"Maybe text\"}},\"required\":[\"payload\",\"missing\"]}");
            edge.doHandle(args -> Collections.singletonMap("ok", Boolean.TRUE));
            return Collections.<FunctionTool>singletonList(edge);
        }

        @Override
        public void close() {}
    }

    private static class RecoveringMcpFactory implements McpClientProviderFactory {
        private final String mode;
        private int createCount;
        private RecoveringMcpClientProvider first;
        private RecoveringMcpClientProvider second;

        private RecoveringMcpFactory(String mode) {
            this.mode = mode;
        }

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            createCount++;
            RecoveringMcpClientProvider provider =
                    new RecoveringMcpClientProvider(mode, createCount);
            if (createCount == 1) {
                first = provider;
            } else if (createCount == 2) {
                second = provider;
            }
            return provider;
        }
    }

    private static class RecoveringMcpClientProvider extends McpClientProvider {
        private final String mode;
        private final int generation;
        private int remoteCallCount;
        private boolean closed;

        private RecoveringMcpClientProvider(String mode, int generation) {
            super(FakeMcpClientProvider.properties());
            this.mode = mode;
            this.generation = generation;
        }

        @Override
        public Collection<FunctionTool> getTools() {
            FunctionToolDesc fetch = new FunctionToolDesc("docs_fetch");
            fetch.title("Docs Fetch");
            fetch.description("Fetch docs");
            fetch.inputSchema("{\"type\":\"object\",\"properties\":{}}");
            fetch.doHandle(
                    args -> {
                        remoteCallCount++;
                        if ("auth".equals(mode)) {
                            throw new IllegalStateException("HTTP 401 unauthorized");
                        }
                        if ("transport".equals(mode) && generation == 1) {
                            throw new IllegalStateException("Session terminated");
                        }
                        if (mode != null && mode.startsWith("transport:") && generation == 1) {
                            throw new IllegalStateException(mode.substring("transport:".length()));
                        }
                        if ("blank-error".equals(mode)) {
                            throw new BlankMcpError();
                        }
                        if ("space-error".equals(mode)) {
                            throw new WhitespaceMcpError();
                        }
                        return Collections.singletonMap("content", "recovered");
                    });
            return Collections.<FunctionTool>singletonList(fetch);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static class NumericMcpFactory implements McpClientProviderFactory {
        private NumericMcpClientProvider provider;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            provider = new NumericMcpClientProvider();
            return provider;
        }
    }

    private static class NumericMcpClientProvider extends McpClientProvider {
        private Map<String, Object> lastArgs = Collections.emptyMap();

        private NumericMcpClientProvider() {
            super(FakeMcpClientProvider.properties());
        }

        @Override
        public Collection<FunctionTool> getTools() {
            FunctionToolDesc tool = new FunctionToolDesc("numeric_tool");
            tool.title("Numeric Tool");
            tool.description("Checks numeric argument coercion.");
            tool.inputSchema(
                    "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200,\"default\":50},\"ratio\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1,\"default\":2},\"page\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200},\"label\":{\"type\":\"string\"}}}");
            tool.doHandle(
                    args -> {
                        lastArgs = new LinkedHashMap<String, Object>(args);
                        return Collections.singletonMap("ok", Boolean.TRUE);
                    });
            return Collections.<FunctionTool>singletonList(tool);
        }

        @Override
        public void close() {}
    }

    private static class BlankMcpError extends RuntimeException {
        @Override
        public String getMessage() {
            return "";
        }

        @Override
        public String toString() {
            return "";
        }
    }

    private static class WhitespaceMcpError extends RuntimeException {
        private WhitespaceMcpError() {
            super("   ");
        }
    }

    private static class BlockingMcpFactory implements McpClientProviderFactory {
        private BlockingMcpClientProvider provider;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            provider = new BlockingMcpClientProvider();
            return provider;
        }
    }

    private static class BlockingMcpClientProvider extends McpClientProvider {
        private final CountDownLatch interrupted = new CountDownLatch(1);

        private BlockingMcpClientProvider() {
            super(FakeMcpClientProvider.properties());
        }

        @Override
        public Collection<FunctionTool> getTools() {
            FunctionToolDesc slow = new FunctionToolDesc("slow_tool");
            slow.title("Slow Tool");
            slow.description("Blocks until interrupted");
            slow.inputSchema("{\"type\":\"object\",\"properties\":{}}");
            slow.doHandle(
                    args -> {
                        try {
                            TimeUnit.SECONDS.sleep(30);
                        } catch (InterruptedException e) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted", e);
                        }
                        return Collections.singletonMap("ok", Boolean.TRUE);
                    });
            return Collections.<FunctionTool>singletonList(slow);
        }

        @Override
        public void close() {}
    }

    private static class SlowDiscoveryMcpFactory implements McpClientProviderFactory {
        private SlowDiscoveryMcpClientProvider provider;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            provider = new SlowDiscoveryMcpClientProvider();
            return provider;
        }
    }

    private static class SlowDiscoveryMcpClientProvider extends McpClientProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private SlowDiscoveryMcpClientProvider() {
            super(FakeMcpClientProvider.properties());
        }

        @Override
        public Collection<FunctionTool> getTools() {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            FunctionToolDesc slow = new FunctionToolDesc("slow_tool");
            slow.title("Slow Tool");
            slow.description("Slow discovery tool");
            slow.inputSchema("{\"type\":\"object\",\"properties\":{}}");
            slow.doHandle(args -> Collections.singletonMap("ok", Boolean.TRUE));
            return Collections.<FunctionTool>singletonList(slow);
        }

        @Override
        public void close() {}
    }

    private static class FlakyDiscoveryMcpFactory implements McpClientProviderFactory {
        private boolean fail = true;

        @Override
        public McpClientProvider create(McpRuntimeService.McpServerConfig config) {
            return new FlakyDiscoveryMcpClientProvider(this);
        }
    }

    private static class FlakyDiscoveryMcpClientProvider extends McpClientProvider {
        private final FlakyDiscoveryMcpFactory factory;

        private FlakyDiscoveryMcpClientProvider(FlakyDiscoveryMcpFactory factory) {
            super(FakeMcpClientProvider.properties());
            this.factory = factory;
        }

        @Override
        public Collection<FunctionTool> getTools() {
            if (factory.fail) {
                throw new IllegalStateException("discovery failed token=secret-failure-token");
            }
            FunctionToolDesc recovered = new FunctionToolDesc("docs_recovered");
            recovered.title("Recovered Docs");
            recovered.description("Recovered discovery tool");
            recovered.inputSchema("{\"type\":\"object\",\"properties\":{}}");
            recovered.doHandle(args -> Collections.singletonMap("ok", Boolean.TRUE));
            return Collections.<FunctionTool>singletonList(recovered);
        }

        @Override
        public void close() {}
    }
}
