package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.McpManageTools;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 验证 MCP 自然语言管理工具与控制台页面动作保持一致。 */
public class McpManageToolsTest {
    /** 页面 tools/refresh 动作应能通过自然语言工具的同名语义别名执行。 */
    @Test
    void shouldRefreshToolsWithPageActionAlias() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.appConfig.getMcp().setEnabled(false);
        saveMcpServer(env);
        McpManageTools tools =
                new McpManageTools(
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        ONode result = ONode.ofJson(tools.mcpManage("tools_refresh", "local-docs", null));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("result").get("action").getString()).isEqualTo("refreshed");
        assertThat(result.get("result").get("server_id").getString()).isEqualTo("local-docs");
    }

    /** 页面 oauth/handle-401 动作应能通过自然语言工具返回重新授权提示。 */
    @Test
    void shouldHandleOAuth401ThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Map<String, Object> oauth = new LinkedHashMap<String, Object>();
        oauth.put("enabled", Boolean.TRUE);
        saveMcpServer(env, oauth);
        McpManageTools tools =
                new McpManageTools(
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        ONode result = ONode.ofJson(tools.mcpManage("oauth_handle_401", "local-docs", null));

        assertThat(result.get("status").getString()).isEqualTo("success");
        assertThat(result.get("result").get("server_id").getString()).isEqualTo("local-docs");
        assertThat(result.get("result").get("needs_reauth").getBoolean()).isTrue();
        assertThat(result.get("result").get("reason").getString())
                .isEqualTo("missing_refresh_token");
    }

    /** 页面 oauth/refresh 动作应能通过自然语言工具进入刷新校验。 */
    @Test
    void shouldRefreshOAuthThroughNaturalLanguageTool() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Map<String, Object> oauth = new LinkedHashMap<String, Object>();
        oauth.put("enabled", Boolean.TRUE);
        oauth.put("client_id", "local-client");
        oauth.put("refresh_token", "refresh-token-for-test");
        saveMcpServer(env, oauth);
        McpManageTools tools =
                new McpManageTools(
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        ONode result = ONode.ofJson(tools.mcpManage("oauth_refresh", "local-docs", null));

        assertThat(result.get("status").getString()).isEqualTo("error");
        assertThat(result.get("error").getString()).contains("token_endpoint is required");
        assertThat(result.toJson()).doesNotContain("refresh-token-for-test");
    }

    /** 页面 oauth/begin 动作应能通过自然语言工具生成安全授权链接。 */
    @Test
    void shouldBeginOAuthThroughNaturalLanguageToolAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Map<String, Object> oauth = new LinkedHashMap<String, Object>();
        oauth.put("enabled", Boolean.TRUE);
        saveMcpServer(env, oauth);
        McpManageTools tools =
                new McpManageTools(
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        for (String action : new String[] {"oauth_begin", "start_oauth", "generate_link"}) {
            ONode result =
                    ONode.ofJson(
                            tools.mcpManage(
                                    action,
                                    "local-docs",
                                    "{\"authorization_endpoint\":\"http://127.0.0.1:8765/oauth/authorize\",\"client_id\":\"local-client\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\",\"scopes\":[\"repo\",\"read:user\"]}"));

            assertThat(result.get("status").getString()).isEqualTo("success");
            assertThat(result.get("result").get("status").getString()).isEqualTo("pending");
            assertThat(result.get("result").get("authorization_url").getString())
                    .contains("code_challenge_method=S256")
                    .contains("scope=repo%20read%3Auser");
            assertThat(result.get("result").get("oauth").get("has_code_verifier").getBoolean())
                    .isTrue();
            assertThat(result.toJson()).doesNotContain("\"code_verifier\":\"");
        }
    }

    /** 页面 oauth/callback 动作应能通过自然语言工具进入回调校验。 */
    @Test
    void shouldCompleteOAuthThroughNaturalLanguageToolAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        Map<String, Object> oauth = new LinkedHashMap<String, Object>();
        oauth.put("enabled", Boolean.TRUE);
        saveMcpServer(env, oauth);
        McpManageTools tools =
                new McpManageTools(
                        new DashboardMcpService(env.appConfig, env.sqliteDatabase));

        ONode begin =
                ONode.ofJson(
                        tools.mcpManage(
                                "oauth_begin",
                                "local-docs",
                                "{\"authorization_endpoint\":\"http://127.0.0.1:8765/oauth/authorize\",\"client_id\":\"local-client\",\"redirect_uri\":\"http://127.0.0.1:8765/callback\"}"));
        String state = begin.get("result").get("state").getString();

        for (String action : new String[] {"oauth_complete", "submit_callback"}) {
            ONode result =
                    ONode.ofJson(
                            tools.mcpManage(
                                    action,
                                    "local-docs",
                                    "{\"code\":\"auth-code\",\"state\":\"" + state + "\"}"));

            assertThat(result.get("status").getString()).isEqualTo("error");
            assertThat(result.get("error").getString())
                    .contains("token_endpoint is required for MCP OAuth callback");
        }
    }

    /** 保存一个禁用运行态也可读取持久化工具快照的 MCP 服务端。 */
    private void saveMcpServer(TestEnvironment env) throws Exception {
        saveMcpServer(env, null);
    }

    /** 保存一个可带 OAuth 配置的 MCP 服务端，用于复用 Dashboard 持久化逻辑。 */
    private void saveMcpServer(TestEnvironment env, Map<String, Object> oauth) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "local-docs");
        body.put("name", "Local Docs");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        if (oauth != null) {
            body.put("oauth", oauth);
        }
        new DashboardMcpService(env.appConfig, env.sqliteDatabase).save(body);
    }
}
