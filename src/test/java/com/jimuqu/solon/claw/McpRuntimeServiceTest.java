package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpClientProviderFactory;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.mcp.SolonAiMcpClientProviderFactory;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqlitePreferenceStore;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.DefaultToolRegistry;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                        env.kanbanService,
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
        assertThat(tools.toString()).contains("McpToolProvider(local-docs)");
        ToolProvider provider = null;
        for (Object tool : tools) {
            if (tool instanceof ToolProvider && tool.toString().contains("McpToolProvider")) {
                provider = (ToolProvider) tool;
            }
        }
        assertThat(provider).isNotNull();
        assertThat(provider.getTools())
                .extracting(FunctionTool::name)
                .contains(
                        "mcp_local-docs_docs_search",
                        "mcp_local-docs_docs_fetch",
                        "mcp_local-docs_list_resources",
                        "mcp_local-docs_read_resource",
                        "mcp_local-docs_list_prompts",
                        "mcp_local-docs_get_prompt");
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
        assertThat(second.getAddedTools()).containsExactly("mcp_local-docs_docs_new");
        assertThat(second.getRemovedTools()).containsExactly("mcp_local-docs_docs_search");
    }

    @Test
    void shouldSanitizeMcpToolSchemasBeforeExposingThemToModel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        SchemaEdgeMcpFactory factory = new SchemaEdgeMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        McpRuntimeService.McpToolRefreshResult refresh =
                mcpRuntimeService.refreshLiveTools("local-docs", false);
        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool tool = toolByName(provider, "mcp_local-docs_schema_edge");
        ONode exposed = ONode.ofJson(tool.inputSchema());
        ONode persisted =
                ONode.ofJson(readToolsJson(env.sqliteDatabase))
                        .get(0)
                        .get("input_schema");

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
    void shouldKeepLegacyMcpUtilityToolsWhenCapabilitiesAreUnknown() throws Exception {
        assertUtilityToolsForCapabilities(
                null,
                "mcp_local-docs_docs_search",
                "mcp_local-docs_docs_fetch",
                "mcp_local-docs_list_resources",
                "mcp_local-docs_read_resource",
                "mcp_local-docs_list_prompts",
                "mcp_local-docs_get_prompt");
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
        env.appConfig.getSecurity().setAllowPrivateUrls(true);
        saveMcpServer(env.appConfig, env.sqliteDatabase);
        FakeMcpFactory factory = new FakeMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        ToolProvider provider = mcpRuntimeService.resolveEnabledToolProviders().get(0);
        FunctionTool docsFetch = toolByName(provider, "mcp_local-docs_docs_fetch");
        FunctionTool readResource = toolByName(provider, "mcp_local-docs_read_resource");

        Map<String, Object> unsafeUrl = new LinkedHashMap<String, Object>();
        unsafeUrl.put("uri", "http://169.254.169.254/latest/meta-data/?token=secret123");
        assertThatThrownBy(() -> docsFetch.handle(unsafeUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP tool")
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret123");

        Map<String, Object> unsafePath = new LinkedHashMap<String, Object>();
        unsafePath.put("file_path", ".env");
        assertThatThrownBy(() -> docsFetch.handle(unsafePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件安全策略")
                .hasMessageContaining("[REDACTED_PATH]")
                .hasMessageNotContaining(".env");

        Map<String, Object> nestedUnsafeUrl = new LinkedHashMap<String, Object>();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(
                "callbacks",
                Arrays.asList(
                        "http://169.254.169.254/latest/meta-data/?token=secret456",
                        "https://example.com/status"));
        nestedUnsafeUrl.put("metadata", metadata);
        assertThatThrownBy(() -> docsFetch.handle(nestedUnsafeUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP tool")
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret456");

        Map<String, Object> unsafeResource = new LinkedHashMap<String, Object>();
        unsafeResource.put("uri", "http://169.254.169.254/latest/meta-data/");
        assertThatThrownBy(() -> readResource.handle(unsafeResource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 安全策略");
        assertThat(factory.provider.remoteCallCount).isEqualTo(0);
    }

    @Test
    void shouldGuardRemoteMcpEndpointBeforeProviderCreation() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        saveRemoteMcpServerDirectly(
                env.sqliteDatabase,
                "http://169.254.169.254/latest/meta-data/?token=secret-endpoint");
        CountingMcpFactory factory = new CountingMcpFactory();
        McpRuntimeService mcpRuntimeService =
                new McpRuntimeService(env.appConfig, env.sqliteDatabase, factory);

        assertThatThrownBy(() -> mcpRuntimeService.connect("remote-docs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP endpoint")
                .hasMessageContaining("URL 安全策略")
                .hasMessageNotContaining("secret-endpoint");
        assertThat(factory.createCount).isEqualTo(0);
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

            Throwable thrown = catchThrowable(() -> docsFetch.handle(Collections.<String, Object>emptyMap()));

            assertThat(thrown).as(mode).isInstanceOf(IllegalStateException.class);
            assertThat(thrown.getMessage())
                    .contains("MCP call failed")
                    .contains(
                            "blank-error".equals(mode)
                                    ? "BlankMcpError"
                                    : "WhitespaceMcpError")
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

        assertThatThrownBy(
                        () -> new SolonAiMcpClientProviderFactory(null).create(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 MCP transport");
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
                            "insert or replace into mcp_servers (server_id, name, transport, endpoint, command, args_json, auth_json, oauth_json, capabilities_json, status, tools_json, last_tools_hash, last_error, enabled, created_at, updated_at, last_checked_at, last_tools_changed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
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
            statement.setString(13, null);
            statement.setInt(14, 1);
            statement.setLong(15, now);
            statement.setLong(16, now);
            statement.setLong(17, 0L);
            statement.setLong(18, 0L);
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
            search.inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
            search.doHandle(args -> Collections.singletonMap("ok", Boolean.TRUE));

            FunctionToolDesc fetch = new FunctionToolDesc("docs_fetch");
            fetch.title("Docs Fetch");
            fetch.description("Fetch docs");
            fetch.inputSchema("{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\"}}}");
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
            prompt.doHandle(args -> Prompt.of(ChatMessage.ofUser("summarize " + args.get("topic"))));
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
                        if (mode != null
                                && mode.startsWith("transport:")
                                && generation == 1) {
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
}
