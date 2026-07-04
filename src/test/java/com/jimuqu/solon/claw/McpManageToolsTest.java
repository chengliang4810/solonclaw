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

    /** 保存一个禁用运行态也可读取持久化工具快照的 MCP 服务端。 */
    private void saveMcpServer(TestEnvironment env) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "local-docs");
        body.put("name", "Local Docs");
        body.put("transport", "stdio");
        body.put("command", "docs-mcp");
        new DashboardMcpService(env.appConfig, env.sqliteDatabase).save(body);
    }
}
