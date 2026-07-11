package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class McpPackageSecurityServiceTest {
    /** 验证只把 OSV 的恶意软件公告作为包级阻断依据。 */
    @Test
    void shouldBlockConfirmedMalwareAdvisory() throws Exception {
        FakeOsvHttpClient http =
                new FakeOsvHttpClient(
                        "{\"vulns\":[{\"id\":\"MAL-2026-0001\",\"summary\":\"malicious package\"},{\"id\":\"GHSA-regular\",\"summary\":\"ordinary vulnerability\"}]}");
        McpPackageSecurityService service =
                new McpPackageSecurityService(http, "https://osv.test/query");

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("npx", Arrays.asList("-y", "@scope/server@1.2.3"));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getReason()).isEqualTo("malware_advisory");
        assertThat(verdict.getMessage()).contains("MAL-2026-0001").doesNotContain("GHSA-regular");
    }

    @Test
    void shouldFailClosedWhenOsvRequestFails() throws Exception {
        FakeOsvHttpClient http = new FakeOsvHttpClient(null);
        http.throwOnPost = true;
        McpPackageSecurityService service =
                new McpPackageSecurityService(http, "https://osv.test/query");

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("uvx", Arrays.asList("demo-mcp==0.1.0"));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getReason()).isEqualTo("scan_error");
        assertThat(verdict.getMessage()).contains("MCP package security check failed");
    }

    /** 验证 OSV 响应结构异常会失败关闭，不能把畸形响应当成无漏洞。 */
    @Test
    void shouldFailClosedWhenOsvResponseIsMalformed() throws Exception {
        FakeOsvHttpClient http = new FakeOsvHttpClient("[]");
        McpPackageSecurityService service =
                new McpPackageSecurityService(http, "https://osv.test/query");

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("npx", Arrays.asList("-y", "demo-mcp-server"));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getReason()).isEqualTo("scan_error");
        assertThat(verdict.getMessage()).contains("OSV response is not a JSON object");
    }

    /** 验证控制台会持久化 OSV 探测故障，避免后续运行绕过扫描。 */
    @Test
    void shouldPersistBlockedStatusWhenOsvRequestFails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FakeOsvHttpClient http = new FakeOsvHttpClient(null);
        http.throwOnPost = true;
        DashboardMcpService service =
                new DashboardMcpService(
                        env.appConfig,
                        env.sqliteDatabase,
                        new McpPackageSecurityService(http, "https://osv.test/query"));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "mcp-scan-error");
        body.put("name", "MCP Scan Error");
        body.put("transport", "stdio");
        body.put("command", "npx");
        body.put("args", Arrays.asList("-y", "scan-error-server"));
        body.put("tools", Arrays.asList(tool("scan_error_tool")));

        Map<String, Object> saved = service.save(body);
        Map<String, Object> checked = service.check("mcp-scan-error");
        Map<String, Object> listed = service.list();

        assertThat(String.valueOf(saved.get("security"))).contains("allowed=false");
        assertThat(String.valueOf(saved.get("security"))).contains("reason=scan_error");
        assertThat(checked.get("status")).isEqualTo("blocked");
        assertThat(String.valueOf(checked.get("security"))).contains("reason=scan_error");
        assertThat(String.valueOf(listed.get("servers"))).contains("blocked");
    }

    /** 验证不安全 OSV 端点在联网前被阻断。 */
    @Test
    void shouldBlockUnsafeOsvEndpointBeforeNetworkAccess() throws Exception {
        FakeOsvHttpClient http = new FakeOsvHttpClient("{\"vulns\":[]}");
        McpPackageSecurityService service =
                new McpPackageSecurityService(
                        http,
                        "http://169.254.169.254/latest/meta-data/?token=secret",
                        new SecurityPolicyService(new AppConfig()));

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("npx", Arrays.asList("-y", "safe-server"));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getReason()).isEqualTo("unsafe_endpoint");
        assertThat(verdict.getMessage()).contains("token=***").doesNotContain("token=secret");
        assertThat(http.lastBody).isNull();
    }

    /** 验证异常 OSV 端点状态会安全脱敏并持久化为阻断。 */
    @Test
    void shouldPersistBlockedStatusForUnsafeOsvEndpoint() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardMcpService service =
                new DashboardMcpService(
                        env.appConfig,
                        env.sqliteDatabase,
                        new McpPackageSecurityService(
                                new FakeOsvHttpClient("{\"vulns\":[]}"),
                                "http://169.254.169.254/latest/meta-data/?token=secret-mcp-osv",
                                new SecurityPolicyService(env.appConfig)));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "unsafe-osv-mcp");
        body.put("name", "Unsafe OSV MCP");
        body.put("transport", "stdio");
        body.put("command", "npx");
        body.put("args", Arrays.asList("-y", "safe-server"));
        body.put("tools", Arrays.asList(tool("safe_tool")));

        Map<String, Object> saved = service.save(body);
        Map<String, Object> checked = service.check("unsafe-osv-mcp");
        Map<String, Object> listed = service.list();
        assertThat(String.valueOf(saved.get("security"))).contains("reason=unsafe_endpoint");
        assertThat(checked.get("status")).isEqualTo("blocked");
        assertThat(String.valueOf(checked.get("security"))).contains("token=***");
        assertThat(String.valueOf(listed.get("servers"))).doesNotContain("secret-mcp-osv");
    }

    @Test
    void shouldDescribeMcpPackageSecurityPolicy() {
        Map<String, Object> summary =
                new McpPackageSecurityService(new FakeOsvHttpClient("{}")).policySummary();

        assertThat(summary)
                .containsEntry("malwareBlocksSaveAndCheck", Boolean.TRUE)
                .containsEntry("requestFailureFailsOpen", Boolean.FALSE)
                .containsEntry("requestFailureFailsClosed", Boolean.TRUE)
                .containsEntry("messageRedacted", Boolean.TRUE)
                .containsEntry("npxPackageOptionParsed", Boolean.TRUE)
                .containsEntry("pipxRunSubcommandSkipped", Boolean.TRUE)
                .containsEntry("pypiSourceOptionParsed", Boolean.TRUE);
        assertThat(String.valueOf(summary.get("structuredReasons")))
                .contains("scan_error")
                .contains("unsafe_endpoint");
        assertThat(summary.get("endpointOverrideEnvironment")).isEqualTo("SOLONCLAW_OSV_ENDPOINT");
        assertThat(summary.get("projectEndpointOverrideEnvironment"))
                .isEqualTo("SOLONCLAW_OSV_ENDPOINT");
        assertThat(String.valueOf(summary.get("checkedLaunchers")))
                .contains("npx")
                .contains("uvx")
                .contains("pipx");
    }

    private Map<String, Object> tool(String name) {
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("name", name);
        return tool;
    }

    private String storedLastError(TestEnvironment env, String serverId) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select last_error from mcp_servers where server_id = ?");
            statement.setString(1, serverId);
            ResultSet resultSet = statement.executeQuery();
            try {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString("last_error");
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private static class FakeOsvHttpClient implements SkillHubHttpClient {
        private final String response;
        private String lastBody;
        private boolean throwOnPost;

        private FakeOsvHttpClient(String response) {
            this.response = response;
        }

        @Override
        public String getText(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] getBytes(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String postJson(String url, Map<String, String> headers, String jsonBody)
                throws Exception {
            if (throwOnPost) {
                throw new IllegalStateException("network down");
            }
            this.lastBody = jsonBody;
            return response;
        }
    }
}
