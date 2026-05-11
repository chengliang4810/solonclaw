package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.support.SkillHubHttpClient;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import com.jimuqu.solon.claw.web.McpPackageSecurityService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class McpPackageSecurityServiceTest {
    @Test
    void shouldBlockOnlyMalwareAdvisoriesFromOsv() throws Exception {
        FakeOsvHttpClient http =
                new FakeOsvHttpClient(
                        "{\"vulns\":[{\"id\":\"MAL-2026-0001\",\"summary\":\"malicious package\"},{\"id\":\"GHSA-regular\",\"summary\":\"ordinary vuln\"}]}");
        McpPackageSecurityService service =
                new McpPackageSecurityService(http, "https://osv.test/query");

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("npx", Arrays.asList("-y", "@scope/server@1.2.3", "--stdio"));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage()).contains("MAL-2026-0001");
        assertThat(verdict.getMessage()).doesNotContain("GHSA-regular");
        assertThat(http.lastBody).contains("\"name\":\"@scope/server\"");
        assertThat(http.lastBody).contains("\"ecosystem\":\"npm\"");
        assertThat(http.lastBody).contains("\"version\":\"1.2.3\"");
    }

    @Test
    void shouldRedactMcpMalwareAdvisoryMessages() throws Exception {
        FakeOsvHttpClient http =
                new FakeOsvHttpClient(
                        "{\"vulns\":[{\"id\":\"MAL-2026-ghp_mcpadvisory12345\",\"summary\":\"token=secret-mcp-summary\"}]}");
        McpPackageSecurityService service =
                new McpPackageSecurityService(http, "https://osv.test/query");

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("npx", Arrays.asList("-y", "bad-ghp_mcppackage12345"));

        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.getMessage())
                .contains("ghp_***")
                .contains("token=***")
                .doesNotContain("ghp_mcpadvisory12345")
                .doesNotContain("secret-mcp-summary")
                .doesNotContain("ghp_mcppackage12345");
    }

    @Test
    void shouldFailOpenWhenOsvRequestFails() throws Exception {
        FakeOsvHttpClient http = new FakeOsvHttpClient(null);
        http.throwOnPost = true;
        McpPackageSecurityService service =
                new McpPackageSecurityService(http, "https://osv.test/query");

        McpPackageSecurityService.SecurityVerdict verdict =
                service.check("uvx", Arrays.asList("demo-mcp==0.1.0"));

        assertThat(verdict.isAllowed()).isTrue();
    }

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
        assertThat(verdict.getMessage()).contains("OSV endpoint is unsafe");
        assertThat(verdict.getMessage()).contains("169.254.169.254");
        assertThat(verdict.getMessage()).contains("token=***");
        assertThat(http.lastBody).isNull();
    }

    @Test
    void shouldPersistBlockedMcpPackageStatus() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        FakeOsvHttpClient http =
                new FakeOsvHttpClient(
                        "{\"vulns\":[{\"id\":\"MAL-2026-9999\",\"summary\":\"known malware\"}]}");
        DashboardMcpService service =
                new DashboardMcpService(
                        env.appConfig,
                        env.sqliteDatabase,
                        new McpPackageSecurityService(http, "https://osv.test/query"));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverId", "bad-mcp");
        body.put("name", "Bad MCP");
        body.put("transport", "stdio");
        body.put("command", "npx");
        body.put("args", Arrays.asList("-y", "bad-mcp-server"));
        body.put("tools", Arrays.asList(tool("bad_tool")));

        Map<String, Object> saved = service.save(body);
        Map<String, Object> checked = service.check("bad-mcp");
        Map<String, Object> listed = service.list();

        assertThat(String.valueOf(saved.get("security"))).contains("allowed=false");
        assertThat(checked.get("status")).isEqualTo("blocked");
        assertThat(String.valueOf(checked.get("security"))).contains("MAL-2026-9999");
        assertThat(String.valueOf(listed.get("servers"))).contains("blocked");
        assertThat(String.valueOf(listed.get("servers"))).contains("MAL-2026-9999");
    }

    @Test
    void shouldRedactBlockedMcpPackageStatusMessages() throws Exception {
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

        assertThat(String.valueOf(saved.get("security"))).contains("token=***");
        assertThat(String.valueOf(checked.get("security"))).contains("token=***");
        assertThat(String.valueOf(listed.get("servers"))).contains("token=***");
        assertThat(String.valueOf(saved)).doesNotContain("secret-mcp-osv");
        assertThat(String.valueOf(checked)).doesNotContain("secret-mcp-osv");
        assertThat(String.valueOf(listed)).doesNotContain("secret-mcp-osv");
    }

    private Map<String, Object> tool(String name) {
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("name", name);
        return tool;
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
