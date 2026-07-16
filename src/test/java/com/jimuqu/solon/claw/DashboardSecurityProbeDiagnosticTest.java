package com.jimuqu.solon.claw;

import static com.jimuqu.solon.claw.DashboardDiagnosticTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class DashboardSecurityProbeDiagnosticTest {

    /** 创建带工作区目录的 AppConfig。 */
    private static AppConfig securityProbeConfig(String homeName) {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/" + homeName).getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(workspaceHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        return config;
    }

    /** 使用最少依赖构建安全探测专用的 DashboardDiagnosticsService。 */
    private static DashboardDiagnosticsService securityProbeDiagnosticsService(AppConfig config) {
        return DashboardDiagnosticsService.builder()
                .appConfig(config)
                .deliveryService(FixedDeliveryService.empty())
                .llmProviderService(new LlmProviderService(config))
                .toolRegistry(new FixedToolRegistry())
                .sessionRepository(null)
                .conversationOrchestrator(null)
                .approvalAuditRepository(null)
                .slashConfirmService(null)
                .commandService(null)
                .approvalService(null)
                .securityPolicyService(new SecurityPolicyService(config))
                .tirithSecurityService(null)
                .toolResultStorageService(null)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipWebsitePolicyProbeWhenWebsiteBlocklistHasNoRules() {
        AppConfig config = securityProbeConfig("dashboard-security-probes-skip");
        DashboardDiagnosticsService diagnosticsService = securityProbeDiagnosticsService(config);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> security = (Map<String, Object>) diagnostics.get("security");
        Map<String, Object> probes = (Map<String, Object>) security.get("probes");
        List<Map<String, Object>> items = (List<Map<String, Object>>) probes.get("items");
        Map<String, Object> websitePolicy = findProbe(items, "website_policy_rule");
        assertThat(websitePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicy.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(websitePolicy.get("skipped")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipPrivateUrlProbeWhenPrivateUrlsAreAllowed() {
        AppConfig config = securityProbeConfig("dashboard-private-url-probes-skip");
        config.getSecurity().setAllowPrivateUrls(true);
        DashboardDiagnosticsService diagnosticsService = securityProbeDiagnosticsService(config);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> security = (Map<String, Object>) diagnostics.get("security");
        Map<String, Object> probes = (Map<String, Object>) security.get("probes");
        List<Map<String, Object>> items = (List<Map<String, Object>>) probes.get("items");
        Map<String, Object> privateUrl = findProbe(items, "private_url");
        Map<String, Object> nestedEndpoint =
                findProbe(items, "tool_args_nested_endpoint_private_url");
        Map<String, Object> hostTarget = findProbe(items, "tool_args_host_target_private_url");
        Map<String, Object> commandPreproxy = findProbe(items, "command_preproxy_url_policy");
        Map<String, Object> commandWinhttpBypass =
                findProbe(items, "command_winhttp_bypass_policy");
        assertThat(privateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(privateUrl.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(privateUrl.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(nestedEndpoint.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(nestedEndpoint.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(nestedEndpoint.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(hostTarget.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostTarget.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(hostTarget.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(commandPreproxy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPreproxy.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(commandPreproxy.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(commandWinhttpBypass.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWinhttpBypass.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(commandWinhttpBypass.get("skipped")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRunCodeExecutionSandboxProbeWithDefaultPythonCommand() {
        AppConfig config = securityProbeConfig("dashboard-code-sandbox-default-python");
        DashboardDiagnosticsService diagnosticsService = securityProbeDiagnosticsService(config);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> security = (Map<String, Object>) diagnostics.get("security");
        Map<String, Object> probes = (Map<String, Object>) security.get("probes");
        List<Map<String, Object>> items = (List<Map<String, Object>>) probes.get("items");
        Map<String, Object> codeExecutionSandbox = findProbe(items, "code_execution_sandbox");
        assertThat(codeExecutionSandbox.get("passed"))
                .as("code execution sandbox probe: %s", codeExecutionSandbox)
                .isEqualTo(Boolean.TRUE);
        assertThat(codeExecutionSandbox.get("skipped")).isNull();
        assertThat(String.valueOf(codeExecutionSandbox))
                .doesNotContain("Cannot run program \"python\"")
                .doesNotContain("sk-dashboardcodesandboxprobe12345");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMatchSudoRewriteDiagnosticsForExplicitEmptyPassword() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("");
        DashboardDiagnosticsService diagnosticsService = securityProbeDiagnosticsService(config);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", "policy");

        Map<String, Object> result = diagnosticsService.securityAudit(body);

        Map<String, Object> policy = (Map<String, Object>) result.get("policy");
        Map<String, Object> terminal = (Map<String, Object>) policy.get("terminal");
        Map<String, Object> sudoPolicy = (Map<String, Object>) terminal.get("sudoRewritePolicy");
        assertThat(terminal.get("sudoPasswordConfigured")).isEqualTo(Boolean.TRUE);
        assertThat(sudoPolicy.get("configured")).isEqualTo(Boolean.TRUE);
        assertThat(sudoPolicy.get("stdinPasswordInjection")).isEqualTo(Boolean.TRUE);
        assertThat(sudoPolicy.get("passwordRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(ONode.serialize(result)).doesNotContain("sudoPassword\":\"\"");
    }
}
