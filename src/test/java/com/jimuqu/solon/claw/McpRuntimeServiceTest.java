package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.mcp.McpClientProviderFactory;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
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
import org.junit.jupiter.api.Test;
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
    void shouldFilterMcpToolsAndAllowDisablingResourcePromptUtilityTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(true);
        Map<String, Object> auth = new LinkedHashMap<String, Object>();
        Map<String, Object> tools = new LinkedHashMap<String, Object>();
        tools.put("include", Collections.singletonList("docs_search"));
        tools.put("resources", Boolean.FALSE);
        tools.put("prompts", Boolean.FALSE);
        auth.put("tools", tools);
        saveMcpServer(env.appConfig, env.sqliteDatabase, auth);

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
                .hasMessageContaining(".env");

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

    private void saveMcpServer(AppConfig appConfig, SqliteDatabase database) throws Exception {
        saveMcpServer(appConfig, database, null);
    }

    private void saveMcpServer(
            AppConfig appConfig, SqliteDatabase database, Map<String, Object> auth)
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
        new com.jimuqu.solon.claw.web.DashboardMcpService(appConfig, database).save(body);
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
}
