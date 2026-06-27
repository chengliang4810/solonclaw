package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.RuntimeMemoryMonitorService;
import com.jimuqu.solon.claw.support.ShutdownForensicsService;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.ProcessRegistry;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsController;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class DashboardDiagnosticOutputTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeManagedProcessRuntimeDiagnosticsWithoutDrainingEvents() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-process-runtime").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(workspaceHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        FileUtil.mkdir(workspaceHome);

        ProcessRegistry processRegistry = new ProcessRegistry(config);
        List<ProcessRegistry.ManagedProcess> managedProcesses =
                new ArrayList<ProcessRegistry.ManagedProcess>();
        try {
            String longOutputMarker = "diagnostic-output-unbounded-marker";
            String longOutputCommand =
                    "printf '\\144\\151\\141\\147\\156\\157\\163\\164\\151\\143\\055\\157\\165\\164\\160\\165\\164\\055\\165\\156\\142\\157\\165\\156\\144\\145\\144\\055\\155\\141\\162\\153\\145\\162'; "
                            + "printf '%04096d' 0; "
                            + "printf 'tail-preview token=ghp_diagnosticlongsecret12345\\n'";
            ProcessRegistry.ManagedProcess completed =
                    processRegistry.start(
                            longOutputCommand, workspaceHome, true, Collections.<String>emptyList());
            processRegistry.waitFor(completed.getId(), 5000L);
            for (int i = 0; i < 7; i++) {
                managedProcesses.add(
                        processRegistry.start(
                                "sleep 30 # token=ghp_diagnosticprocess" + i, workspaceHome));
            }

            DashboardDiagnosticsService diagnosticsService =
                    new DashboardDiagnosticsService(
                            config,
                            new FixedDeliveryService(null),
                            new LlmProviderService(config),
                            new FixedToolRegistry(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            new SecurityPolicyService(config),
                            null,
                            null,
                            null,
                            null,
                            null,
                            processRegistry);

            Map<String, Object> diagnostics = diagnosticsService.diagnostics();

            Map<String, Object> runtime = (Map<String, Object>) diagnostics.get("runtime");
            assertThat(runtime).isNotNull();
            Map<String, Object> processes = (Map<String, Object>) runtime.get("managed_processes");
            assertThat(processes).isNotNull();
            assertThat(processes.get("available")).isEqualTo(Boolean.TRUE);
            assertThat(processes.get("running_count")).isEqualTo(Integer.valueOf(7));
            assertThat(processes.get("snapshot_limit")).isEqualTo(Integer.valueOf(5));
            assertThat(processes.get("lifecycle_event_limit")).isEqualTo(Integer.valueOf(10));
            assertThat((List<Map<String, Object>>) processes.get("snapshots")).hasSize(5);
            List<Map<String, Object>> snapshots =
                    (List<Map<String, Object>>) processes.get("snapshots");
            Map<String, Object> completedSnapshot = snapshots.get(0);
            assertThat(completedSnapshot).containsKey("output_preview");
            assertThat(completedSnapshot).doesNotContainKey("output");
            assertThat(String.valueOf(completedSnapshot.get("output_preview")))
                    .contains("tail-preview token=***")
                    .doesNotContain("diagnosticlongsecret12345");
            assertThat((List<Map<String, Object>>) processes.get("recent_lifecycle_events"))
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(10);

            String diagnosticsJson = ONode.serialize(diagnostics);
            assertThat(diagnosticsJson).contains("managed_processes");
            assertThat(diagnosticsJson).doesNotContain("ghp_diagnosticprocess");
            assertThat(diagnosticsJson).doesNotContain(longOutputMarker);
            assertThat(diagnosticsJson).doesNotContain("ghp_diagnosticlongsecret12345");
            assertThat(diagnosticsJson).doesNotContain("diagnosticlongsecret12345");

            List<Map<String, Object>> pendingEvents = processRegistry.drainEvents(10);
            assertThat(pendingEvents).extracting(event -> event.get("type")).contains("completion");
        } finally {
            for (ProcessRegistry.ManagedProcess managed : managedProcesses) {
                processRegistry.stop(managed.getId());
            }
        }
    }

    @Test
    void shouldRedactGatewayDoctorAndDiagnosticsOutput() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-secret-runtime").getAbsoluteFile();
        File externalState =
                new File("target/diagnostic-external-token=ghp_diagnosticexternal123/state.db")
                        .getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getRuntime().setStateDb(externalState.getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        config.getLlm().setStream(true);
        FileUtil.mkdir(workspaceHome);
        String refreshSecretPath =
                new File(workspaceHome, "secrets/refresh-token.txt").getAbsolutePath();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    note: "
                        + refreshSecretPath
                        + " token=ghp_doctorrefresh12345\n"
                        + "    broken: [\n",
                config.getRuntime().getConfigFile());

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default");
        provider.setBaseUrl("https://user:provider-pass@example.com/v1?token=provider-token");
        provider.setDefaultModel("gpt-test");
        provider.setDialect("openai");
        provider.setApiKey("sk-test-providersecret");
        config.getProviders().put("default", provider);
        AppConfig.ProviderConfig secretNamedProvider = new AppConfig.ProviderConfig();
        secretNamedProvider.setName("Provider token=ghp_providername12345");
        secretNamedProvider.setBaseUrl(
                "https://api.example.com/v1?access_token=provider-named-token");
        secretNamedProvider.setDefaultModel("model-ghp_providermodel12345");
        secretNamedProvider.setDialect("openai");
        secretNamedProvider.setApiKey("sk-test-providersecret2");
        config.getProviders().put("secret-ghp_providerkey12345", secretNamedProvider);

        ChannelStatus channelStatus =
                new ChannelStatus(
                        PlatformType.FEISHU,
                        true,
                        false,
                        "failed at "
                                + new File(workspaceHome, "secrets/token.txt").getAbsolutePath()
                                + " token=ghp_doctordetail123");
        channelStatus.setSetupState("error");
        channelStatus.setConnectionMode("websocket");
        channelStatus.setMissingConfig(Arrays.asList("channels.feishu.appSecret"));
        channelStatus.setLastErrorCode("auth_failed");
        channelStatus.setLastErrorMessage(
                "Authorization: Bearer ghp_doctorerror123 password=doctor-password");
        channelStatus.setReconnecting(true);
        channelStatus.setReconnectAttempt(2);
        channelStatus.setLastReconnectAt(1000L);
        channelStatus.setNextReconnectAt(6000L);
        channelStatus.setLastReconnectError(
                "retry token=ghp_doctorretry123 password=retry-password");

        FixedDeliveryService deliveryService = new FixedDeliveryService(channelStatus);
        GatewayRuntimeRefreshService refreshService =
                new GatewayRuntimeRefreshService(
                        config, new ChannelConnectionManager(Collections.emptyMap()));
        assertThat(refreshService.refreshConfigOnly().isSuccess()).isFalse();

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config, deliveryService, new LlmProviderService(config), refreshService);
        String doctorJson = ONode.serialize(doctorService.doctor());
        assertThat(doctorJson).contains("workspace://");
        assertThat(doctorJson).doesNotContain(workspaceHome.getAbsolutePath());
        assertThat(doctorJson).doesNotContain("ghp_doctordetail123");
        assertThat(doctorJson).doesNotContain("ghp_doctorerror123");
        assertThat(doctorJson).doesNotContain("ghp_doctorretry123");
        assertThat(doctorJson).doesNotContain("ghp_doctorrefresh12345");
        assertThat(doctorJson).doesNotContain(refreshSecretPath);
        assertThat(doctorJson).doesNotContain("doctor-password");
        assertThat(doctorJson).doesNotContain("retry-password");
        assertThat(doctorJson)
                .contains("last_refresh_failure")
                .contains("workspace/config.yml 格式错误")
                .contains("[REDACTED_PATH]");
        assertThat(doctorJson)
                .contains("\"reconnecting\":true")
                .contains("\"reconnect_attempt\":2")
                .contains("\"next_reconnect_at\":6000");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        deliveryService,
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        refreshService);
        Map<String, Object> diagnostics = diagnosticsService.diagnostics();
        Map<String, Object> streamHealth = (Map<String, Object>) diagnostics.get("stream_health");
        assertThat(streamHealth).isNotNull();
        assertThat(streamHealth.get("configured")).isEqualTo(Boolean.TRUE);
        assertThat(streamHealth.get("provider_supports_streaming")).isEqualTo(Boolean.TRUE);
        assertThat(streamHealth.get("gateway_stream_transport")).isEqualTo(Boolean.FALSE);
        assertThat(streamHealth.get("enabled_channels")).isEqualTo(Integer.valueOf(1));
        assertThat(streamHealth.get("connected_channels")).isEqualTo(Integer.valueOf(0));
        assertThat(streamHealth.get("reconnecting_channels")).isEqualTo(Integer.valueOf(1));
        assertThat(streamHealth.get("state")).isEqualTo("healthy");

        String diagnosticsJson = ONode.serialize(diagnostics);
        assertThat(diagnosticsJson).contains("path://state.db");
        assertThat(diagnosticsJson).contains("config_refresh");
        assertThat(diagnosticsJson).contains("last_failure");
        assertThat(diagnosticsJson).contains("workspace/config.yml 格式错误");
        assertThat(diagnosticsJson).contains("[REDACTED_PATH]");
        assertThat(diagnosticsJson).contains("stream_health");
        assertThat(diagnosticsJson).contains("audit_policy");
        assertThat(diagnosticsJson).contains("codeExecutionPolicy");
        assertThat(diagnosticsJson).contains("credentialMountPolicy");
        assertThat(diagnosticsJson).contains("mcpRuntimePolicy");
        assertThat(diagnosticsJson).contains("readOnlyAuditTool");
        assertThat(diagnosticsJson).contains("approval_policy");
        assertThat(diagnosticsJson).contains("hardline_policy");
        assertThat(diagnosticsJson).contains("cron_approval_policy");
        assertThat(diagnosticsJson).contains("subagent_approval_policy");
        assertThat(diagnosticsJson).contains("smart_approval_policy");
        assertThat(diagnosticsJson).contains("tirith_approval_policy");
        assertThat(diagnosticsJson).contains("terminal_guardrail_policy");
        assertThat(diagnosticsJson).contains("untrustedToolResultBoundary");
        assertThat(diagnosticsJson).contains("untrustedBoundaryAppliesToPersistedOutputBlocks");
        assertThat(diagnosticsJson).contains("untrustedToolNames");
        assertThat(diagnosticsJson).contains("mcp_");
        assertThat(diagnosticsJson).contains("approval service is unavailable");
        assertThat(diagnosticsJson).contains("\"probes\"");
        assertThat(diagnosticsJson).contains("\"metadata_url\"");
        assertThat(diagnosticsJson).contains("\"sensitive_query\"");
        assertThat(diagnosticsJson).contains("\"tool_args_url\"");
        assertThat(diagnosticsJson).contains("\"passed\":true");
        assertThat(diagnosticsJson).doesNotContain(workspaceHome.getAbsolutePath());
        assertThat(diagnosticsJson).doesNotContain(refreshSecretPath);
        assertThat(diagnosticsJson).doesNotContain("ghp_doctorrefresh12345");
        assertThat(diagnosticsJson).doesNotContain(externalState.getParentFile().getAbsolutePath());
        assertThat(diagnosticsJson).doesNotContain("ghp_diagnosticexternal123");
        assertThat(diagnosticsJson).contains("https://user:***@example.com/v1?token=***");
        assertThat(diagnosticsJson).doesNotContain("provider-pass");
        assertThat(diagnosticsJson).doesNotContain("provider-token");
        assertThat(diagnosticsJson).contains("secret-ghp_***");
        assertThat(diagnosticsJson).contains("Provider token=***");
        assertThat(diagnosticsJson).contains("model-ghp_***");
        assertThat(diagnosticsJson).doesNotContain("providerkey12345");
        assertThat(diagnosticsJson).doesNotContain("providername12345");
        assertThat(diagnosticsJson).doesNotContain("providermodel12345");
        assertThat(diagnosticsJson).doesNotContain("provider-named-token");
        assertThat(diagnosticsJson).doesNotContain("sk-test-providersecret");
        assertThat(diagnosticsJson).doesNotContain("sk-dashboard-probe-secret");
        assertThat(diagnosticsJson).doesNotContain("dashboard-probe-password");
        assertThat(diagnosticsJson).doesNotContain("ghp_doctorerror123");
        assertThat(diagnosticsJson).doesNotContain("doctor-password");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeStaticModelDoctorWithoutLeakingSecrets() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-model-runtime").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("");

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default");
        provider.setBaseUrl("https://user:provider-pass@example.com/v1?token=provider-token");
        provider.setDefaultModel("gpt-test");
        provider.setDialect("openai");
        provider.setApiKey("");
        config.getProviders().put("default", provider);

        AppConfig.ProviderConfig local = new AppConfig.ProviderConfig();
        local.setName("Local");
        local.setBaseUrl("http://127.0.0.1:11434");
        local.setDefaultModel("llama3");
        local.setDialect("ollama");
        local.setApiKey("");
        config.getProviders().put("local", local);

        AppConfig.FallbackProviderConfig missingFallback = new AppConfig.FallbackProviderConfig();
        missingFallback.setProvider("missing");
        AppConfig.FallbackProviderConfig duplicateFallback = new AppConfig.FallbackProviderConfig();
        duplicateFallback.setProvider("local");
        AppConfig.FallbackProviderConfig duplicateFallbackAgain =
                new AppConfig.FallbackProviderConfig();
        duplicateFallbackAgain.setProvider("local");
        AppConfig.FallbackProviderConfig primaryFallback = new AppConfig.FallbackProviderConfig();
        primaryFallback.setProvider("default");
        config.setFallbackProviders(
                Arrays.asList(
                        missingFallback,
                        duplicateFallback,
                        duplicateFallbackAgain,
                        primaryFallback));

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())));

        Map<String, Object> doctor = doctorService.doctor();

        Map<String, Object> model = (Map<String, Object>) doctor.get("model");
        assertThat(model).isNotNull();
        assertThat(model.get("setup_state")).isEqualTo("warning");
        assertThat(model.get("provider")).isEqualTo("default");
        assertThat(model.get("effective_model")).isEqualTo("gpt-test");
        assertThat(model.get("has_api_key")).isEqualTo(Boolean.FALSE);
        assertThat(model.get("base_url")).isEqualTo("https://user:***@example.com/v1?token=***");
        assertThat(String.valueOf(model.get("model_list_url")))
                .doesNotContain("provider-pass")
                .doesNotContain("provider-token");
        Map<String, Object> healthChecks = (Map<String, Object>) model.get("health_checks");
        assertThat(healthChecks).isNotNull();
        assertThat(healthChecks.get("mode")).isEqualTo("generic_bearer_models");
        assertThat(healthChecks.get("generic_bearer")).isEqualTo(Boolean.TRUE);
        assertThat(healthChecks.get("dedicated")).isEqualTo(Boolean.FALSE);
        assertThat(healthChecks.get("skipped")).isEqualTo(Boolean.FALSE);

        List<Map<String, Object>> checks = (List<Map<String, Object>>) model.get("checks");
        assertThat(checkCodes(checks))
                .contains(
                        "provider_present",
                        "model_present",
                        "api_key_missing",
                        "base_url_invalid",
                        "fallback_missing",
                        "fallback_duplicate",
                        "fallback_matches_primary");

        String doctorJson = ONode.serialize(doctor);
        assertThat(doctorJson)
                .doesNotContain("provider-pass")
                .doesNotContain("provider-token")
                .doesNotContain(workspaceHome.getAbsolutePath());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeDedicatedProviderHealthCheckSkipLogic() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome =
                new File("target/diagnostic-dedicated-provider-runtime").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getModel().setProviderKey("anthropic-main");
        config.getModel().setDefault("claude-sonnet-4.6");

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Anthropic Main");
        provider.setBaseUrl("https://api.anthropic.com/v1/models");
        provider.setDefaultModel("claude-sonnet-4.6");
        provider.setDialect("anthropic");
        provider.setApiKey("sk-ant-test-providersecret");
        config.getProviders().put("anthropic-main", provider);

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())));

        Map<String, Object> doctor = doctorService.doctor();

        assertThat(doctor.get("workspace_home")).isEqualTo("workspace://");
        assertThat(doctor).containsKeys("generated_at", "model", "last_shutdown", "platforms");
        Map<String, Object> shutdown = (Map<String, Object>) doctor.get("last_shutdown");
        assertThat(shutdown).isNotNull();
        assertThat(shutdown.get("available")).isEqualTo(Boolean.FALSE);

        Map<String, Object> model = (Map<String, Object>) doctor.get("model");
        assertThat(model).isNotNull();
        assertThat(model.get("setup_state")).isEqualTo("ready");
        assertThat(model.get("provider")).isEqualTo("anthropic-main");
        assertThat(model.get("provider_exists")).isEqualTo(Boolean.TRUE);
        assertThat(model.get("dialect")).isEqualTo("anthropic");
        assertThat(model.get("model_list_url")).isEqualTo("https://api.anthropic.com/v1/models");

        Map<String, Object> healthChecks = (Map<String, Object>) model.get("health_checks");
        assertThat(healthChecks).isNotNull();
        assertThat(healthChecks.get("mode")).isEqualTo("dedicated");
        assertThat(healthChecks.get("generic_bearer")).isEqualTo(Boolean.FALSE);
        assertThat(healthChecks.get("dedicated")).isEqualTo(Boolean.TRUE);
        assertThat(healthChecks.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(healthChecks.get("reason")))
                .contains("skip generic Bearer model-list check");

        List<Map<String, Object>> checks = (List<Map<String, Object>>) model.get("checks");
        assertThat(checkCodes(checks))
                .contains(
                        "provider_present",
                        "model_present",
                        "api_key_present",
                        "base_url_valid",
                        "fallback_empty");

        String doctorJson = ONode.serialize(doctor);
        assertThat(doctorJson).doesNotContain("sk-ant-test-providersecret");
        assertThat(doctorJson).doesNotContain(workspaceHome.getAbsolutePath());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldServeDoctorPayloadThroughDiagnosticsController() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-controller-runtime").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getModel().setProviderKey("anthropic-main");
        config.getModel().setDefault("claude-sonnet-4.6");

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Anthropic Main");
        provider.setBaseUrl("https://api.anthropic.com");
        provider.setDefaultModel("claude-sonnet-4.6");
        provider.setDialect("anthropic");
        provider.setApiKey("sk-ant-test-controllersecret");
        config.getProviders().put("anthropic-main", provider);

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())));
        DashboardDiagnosticsController controller =
                new DashboardDiagnosticsController(
                        new DashboardDiagnosticsService(
                                config,
                                new FixedDeliveryService(null),
                                new LlmProviderService(config),
                                new FixedToolRegistry(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                new SecurityPolicyService(config),
                                null),
                        doctorService);

        Map<String, Object> response = controller.doctor();
        assertThat(response.get("success")).isEqualTo(Boolean.TRUE);

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("workspace_home")).isEqualTo("workspace://");
        assertThat(data).containsKeys("generated_at", "model", "last_shutdown", "platforms");
        Map<String, Object> model = (Map<String, Object>) data.get("model");
        assertThat(model).isNotNull();
        assertThat(model.get("provider")).isEqualTo("anthropic-main");
        Map<String, Object> healthChecks = (Map<String, Object>) model.get("health_checks");
        assertThat(healthChecks.get("mode")).isEqualTo("dedicated");

        String json = ONode.serialize(response);
        assertThat(json).doesNotContain("sk-ant-test-controllersecret");
        assertThat(json).doesNotContain(workspaceHome.getAbsolutePath());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeQqbotAndYuanbaoInDoctorPlatforms() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-domestic-platforms-runtime").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-test");

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setDefaultModel("gpt-test");
        provider.setDialect("openai");
        provider.setApiKey("sk-test-domestic-platforms");
        config.getProviders().put("default", provider);

        ChannelStatus qqbot = new ChannelStatus(PlatformType.QQBOT, true, false, "qqbot pending");
        qqbot.setSetupState("pending");
        ChannelStatus yuanbao = new ChannelStatus(PlatformType.YUANBAO, true, true, "yuanbao ready");
        yuanbao.setSetupState("connected");

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config,
                        new FixedDeliveryService(qqbot, yuanbao),
                        new LlmProviderService(config),
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())));

        Map<String, Object> doctor = doctorService.doctor();
        List<Map<String, Object>> platforms = (List<Map<String, Object>>) doctor.get("platforms");

        assertThat(platforms)
                .extracting(item -> item.get("platform"))
                .contains("qqbot", "yuanbao");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeRedactedShutdownForensicsSummary() throws Exception {
        Path parent = Files.createTempDirectory("solonclaw-dashboard-forensics");
        Path workspaceHome =
                Files.createDirectory(parent.resolve("runtime-token=ghp_forensicshome123"));
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getRuntime().setStateDb(workspaceHome.resolve("state.db").toString());
        config.getRuntime().setCacheDir(workspaceHome.resolve("cache").toString());
        config.getRuntime().setLogsDir(workspaceHome.resolve("logs").toString());

        ShutdownForensicsService forensicsService = new ShutdownForensicsService(config);
        forensicsService.persistShutdownRecord("SIGTERM token=ghp_shutdownsecret123");

        FixedDeliveryService deliveryService = new FixedDeliveryService(null);
        GatewayRuntimeRefreshService refreshService =
                new GatewayRuntimeRefreshService(
                        config, new ChannelConnectionManager(Collections.emptyMap()));
        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config,
                        deliveryService,
                        new LlmProviderService(config),
                        refreshService,
                        forensicsService);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        deliveryService,
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null,
                        forensicsService);

        Map<String, Object> doctor = doctorService.doctor();
        Map<String, Object> shutdown = (Map<String, Object>) doctor.get("last_shutdown");
        assertThat(shutdown).isNotNull();
        assertThat(shutdown.get("available")).isEqualTo(Boolean.TRUE);
        assertThat(shutdown.get("record"))
                .isEqualTo("workspace://forensics/" + latestShutdownFile(workspaceHome));
        assertThat(shutdown.get("reason")).isEqualTo("SIGTERM token=***");
        assertThat(shutdown)
                .containsKeys(
                        "timestamp", "timestamp_iso", "uptime_ms", "pid", "memory", "threads");
        assertThat(shutdown).doesNotContainKeys("javaVersion", "osName");

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();
        Map<String, Object> runtime = (Map<String, Object>) diagnostics.get("runtime");
        Map<String, Object> diagnosticShutdown = (Map<String, Object>) runtime.get("last_shutdown");
        assertThat(diagnosticShutdown).isNotNull();
        assertThat(diagnosticShutdown.get("record")).isEqualTo(shutdown.get("record"));

        String json = ONode.serialize(diagnostics);
        assertThat(json).contains("workspace://forensics/shutdown-");
        assertThat(json).doesNotContain(workspaceHome.toString());
        assertThat(json).doesNotContain("ghp_forensicshome123");
        assertThat(json).doesNotContain("ghp_shutdownsecret123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSummarizeDoctorIssuesAndNextActionsInStableOrder() throws Exception {
        Path parent = Files.createTempDirectory("solonclaw-dashboard-doctor-summary");
        Path workspaceHome =
                Files.createDirectory(parent.resolve("runtime-token=ghp_summaryhome123"));
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getRuntime().setStateDb(workspaceHome.resolve("state.db").toString());
        config.getRuntime().setCacheDir(workspaceHome.resolve("cache").toString());
        config.getRuntime().setLogsDir(workspaceHome.resolve("logs").toString());
        config.getModel().setProviderKey("default");

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setDefaultModel("gpt-test");
        provider.setDialect("openai");
        provider.setApiKey("");
        config.getProviders().put("default", provider);

        ChannelStatus channelStatus =
                new ChannelStatus(PlatformType.FEISHU, true, false, "missing config");
        channelStatus.setSetupState("error");
        channelStatus.setMissingConfig(Arrays.asList("channels.feishu.appSecret"));
        channelStatus.setLastErrorMessage("Bearer ghp_summaryerror123 password=summary-pass");

        ShutdownForensicsService forensicsService = new ShutdownForensicsService(config);
        forensicsService.persistShutdownRecord(
                "RuntimeException: failed gateway boot token=ghp_summaryshutdown123");

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(
                        config,
                        new FixedDeliveryService(channelStatus),
                        new LlmProviderService(config),
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())),
                        forensicsService);

        Map<String, Object> doctor = doctorService.doctor();

        Map<String, Object> summary = (Map<String, Object>) doctor.get("summary");
        assertThat(summary).isNotNull();
        assertThat(summary.get("issueCount")).isEqualTo(Integer.valueOf(3));
        assertThat(summary.get("warningCount")).isEqualTo(Integer.valueOf(3));
        assertThat(summary.get("highestSeverity")).isEqualTo("warning");
        List<Map<String, Object>> issues = (List<Map<String, Object>>) summary.get("issues");
        assertThat(issues).hasSize(3);
        assertThat(issues)
                .extracting(issue -> issue.get("code"))
                .containsExactly(
                        "api_key_missing", "channel_missing_config", "last_shutdown_abnormal");
        List<String> nextActions = (List<String>) summary.get("nextActions");
        assertThat(nextActions)
                .containsExactly(
                        "为当前 provider 配置 API key，或改用本地免 key provider。",
                        "补齐 feishu 渠道缺失配置：channels.feishu.appSecret。",
                        "查看 last_shutdown.record 并排查最近一次异常退出原因。");

        String json = ONode.serialize(doctor);
        assertThat(json)
                .doesNotContain(workspaceHome.toString())
                .doesNotContain("ghp_summaryhome123")
                .doesNotContain("ghp_summaryerror123")
                .doesNotContain("summary-pass")
                .doesNotContain("ghp_summaryshutdown123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReportDegradedStreamHealthForDisconnectedStreamGateway() {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-stream-health-runtime").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(workspaceHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        config.getLlm().setStream(true);
        config.getModel().setProviderKey("default");
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setDefaultModel("gpt-test");
        provider.setDialect("openai");
        provider.setApiKey("sk-test-providersecret");
        config.getProviders().put("default", provider);

        ChannelStatus channelStatus =
                new ChannelStatus(PlatformType.DINGTALK, true, false, "stream mode connect failed");
        channelStatus.setConnectionMode("stream");
        channelStatus.setSetupState("error");
        channelStatus.setReconnecting(true);
        channelStatus.setReconnectAttempt(1);
        channelStatus.setLastErrorMessage("Bearer ghp_streamhealth123 password=stream-pass");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(channelStatus),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();
        Map<String, Object> streamHealth = (Map<String, Object>) diagnostics.get("stream_health");
        assertThat(streamHealth).isNotNull();
        assertThat(streamHealth.get("configured")).isEqualTo(Boolean.TRUE);
        assertThat(streamHealth.get("provider_supports_streaming")).isEqualTo(Boolean.TRUE);
        assertThat(streamHealth.get("gateway_stream_transport")).isEqualTo(Boolean.TRUE);
        assertThat(streamHealth.get("enabled_channels")).isEqualTo(Integer.valueOf(1));
        assertThat(streamHealth.get("connected_channels")).isEqualTo(Integer.valueOf(0));
        assertThat(streamHealth.get("reconnecting_channels")).isEqualTo(Integer.valueOf(1));
        assertThat(streamHealth.get("state")).isEqualTo("degraded");

        String json = ONode.serialize(diagnostics);
        assertThat(json).contains("stream_health");
        assertThat(json).doesNotContain("ghp_streamhealth123");
        assertThat(json).doesNotContain("stream-pass");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeRuntimeMemoryMonitorSummaryWithoutLeakingPaths() {
        Path workspaceHome =
                Paths.get("target/dashboard-memory-monitor-token=ghp_memorysecret123")
                        .toAbsolutePath();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getRuntime().setStateDb(workspaceHome.resolve("state.db").toString());
        config.getRuntime().setCacheDir(workspaceHome.resolve("cache").toString());
        config.getRuntime().setLogsDir(workspaceHome.resolve("logs").toString());

        RuntimeMemoryMonitorService monitorService = new RuntimeMemoryMonitorService();
        monitorService.start();
        monitorService.captureSnapshot("periodic");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null,
                        null,
                        monitorService);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();
        Map<String, Object> runtime = (Map<String, Object>) diagnostics.get("runtime");
        Map<String, Object> memoryMonitor = (Map<String, Object>) runtime.get("memory_monitor");
        assertThat(memoryMonitor).isNotNull();
        assertThat(memoryMonitor.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(memoryMonitor.get("running")).isEqualTo(Boolean.TRUE);
        assertThat(memoryMonitor.get("baseline")).isInstanceOf(Map.class);
        assertThat(memoryMonitor.get("latest")).isInstanceOf(Map.class);

        Map<String, Object> latest = (Map<String, Object>) memoryMonitor.get("latest");
        assertThat(latest)
                .containsKeys(
                        "tag",
                        "used_mb",
                        "max_mb",
                        "free_mb",
                        "thread_count",
                        "uptime_ms",
                        "timestamp",
                        "timestamp_iso");
        assertThat(latest.get("tag")).isEqualTo("periodic");

        String json = ONode.serialize(diagnostics);
        assertThat(json).doesNotContain(workspaceHome.toString());
        assertThat(json).doesNotContain("ghp_memorysecret123");
        assertThat(json).doesNotContain("javaVersion");
        assertThat(json).doesNotContain("osName");

        monitorService.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeRecoverableRunSummaryWithoutLeakingIdentifiersOrHints() {
        AppConfig config = new AppConfig();
        File workspaceHome = new File("target/diagnostic-recoverable-runs").getAbsoluteFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(workspaceHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        FixedAgentRunRepository repository = new FixedAgentRunRepository();
        String runSecret = "ghp_dashboardrunsecret12345";
        String sessionSecret = "ghp_dashboardsessionsecret12345";
        String sourceSecret = "ghp_dashboardsourcesecret12345";
        String hintSecret = "ghp_dashboardhintsecret12345";
        long now = 1710000000000L;
        for (int i = 0; i < 7; i++) {
            AgentRunRecord run = new AgentRunRecord();
            run.setRunId("run-" + i + "-" + runSecret);
            run.setSessionId("session-" + i + "-" + sessionSecret);
            run.setSourceKey("MEMORY:room-" + sourceSecret + "-" + i + ":user");
            run.setStatus(i == 0 ? "recoverable" : "paused");
            run.setPhase(i == 0 ? "recovery" : "tool");
            run.setBackgrounded(i % 2 == 0);
            run.setExitReason(i == 0 ? "stale_heartbeat" : "restart");
            run.setRecoverable(true);
            run.setRecoveryHint("retry with token=" + hintSecret + " password=dashboard-password");
            run.setLastActivityAt(now - i);
            repository.runs.add(run);
        }

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null,
                        null,
                        null,
                        repository);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> runs = (Map<String, Object>) diagnostics.get("runs");
        assertThat(runs).isNotNull();
        assertThat(runs.get("recoverable_count")).isEqualTo(Integer.valueOf(7));
        assertThat(runs.get("limit")).isEqualTo(Integer.valueOf(5));
        assertThat(runs.get("truncated")).isEqualTo(Boolean.TRUE);
        List<Map<String, Object>> items = (List<Map<String, Object>>) runs.get("recoverable_items");
        assertThat(items).hasSize(5);
        Map<String, Object> first = items.get(0);
        assertThat(first)
                .containsKeys(
                        "run_id",
                        "session_id",
                        "source_key",
                        "status",
                        "phase",
                        "backgrounded",
                        "exit_reason",
                        "last_activity_at",
                        "recovery_hint");
        assertThat(first.get("status")).isEqualTo("recoverable");
        assertThat(first.get("phase")).isEqualTo("recovery");
        assertThat(first.get("backgrounded")).isEqualTo(Boolean.TRUE);
        assertThat(first.get("exit_reason")).isEqualTo("stale_heartbeat");
        assertThat(first.get("last_activity_at")).isEqualTo(Long.valueOf(now));

        String json = ONode.serialize(diagnostics);
        assertThat(json)
                .contains("run-0-ghp_***")
                .contains("session-0-ghp_***")
                .contains("MEMORY:room-ghp_***")
                .contains("token=***")
                .doesNotContain(runSecret)
                .doesNotContain(sessionSecret)
                .doesNotContain(sourceSecret)
                .doesNotContain(hintSecret)
                .doesNotContain("dashboard-password");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeWebsiteSharedPolicyDiagnosticsWithoutLeakingPaths() throws Exception {
        Path parent = Files.createTempDirectory("jimuqu-dashboard-website-policy");
        Path workspaceHome =
                Files.createDirectory(parent.resolve("runtime-token=ghp_dashboardwebsecret123"));
        File shared = workspaceHome.resolve("shared-token=sk-dashboard-secret.txt").toFile();
        FileUtil.writeUtf8String(
                "blocked.example\nshared-token-sk-dashboardsecret.example\n", shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity().getWebsiteBlocklist().setDomains(Arrays.asList("inline.example"));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(
                        Arrays.asList(
                                shared.getName(), "../missing-token=sk-dashboard-secret.txt"));
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", "policy");

        Map<String, Object> result = diagnosticsService.securityAudit(body);

        Map<String, Object> policy = (Map<String, Object>) result.get("policy");
        Map<String, Object> security = (Map<String, Object>) policy.get("security");
        assertThat(security.get("websiteBlocklistSharedFileCount")).isEqualTo(Integer.valueOf(2));
        assertThat(security.get("websiteBlocklistLoadedSharedFileCount"))
                .isEqualTo(Integer.valueOf(1));
        assertThat(security.get("websiteBlocklistSkippedSharedFileCount"))
                .isEqualTo(Integer.valueOf(1));
        assertThat(security.get("websiteBlocklistSharedRuleCount")).isEqualTo(Integer.valueOf(2));
        String json = ONode.serialize(result);
        assertThat(json)
                .doesNotContain(workspaceHome.toString())
                .doesNotContain(shared.getAbsolutePath())
                .doesNotContain("ghp_dashboardwebsecret123")
                .doesNotContain("sk-dashboard-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeTirithSecurityDiagnosticsWithoutLeakingRawPathOrSecrets() throws Exception {
        String token = "sk-dashboard-tirithsecret12345";
        AppConfig config = new AppConfig();
        config.getSecurity()
                .setTirithPath("/tmp/jimuqu-dashboard-tirith/secret-" + token + "/tirith");
        config.getSecurity().setTirithFailOpen(false);
        config.getSecurity().setTirithTimeoutSeconds(9);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        new TirithSecurityService(config),
                        null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", "policy");

        Map<String, Object> result = diagnosticsService.securityAudit(body);

        Map<String, Object> policy = (Map<String, Object>) result.get("policy");
        Map<String, Object> security = (Map<String, Object>) policy.get("security");
        Map<String, Object> tirithPolicy = (Map<String, Object>) security.get("tirithPolicy");
        Map<String, Object> diagnosticSummary =
                (Map<String, Object>) tirithPolicy.get("diagnosticSummary");
        Map<String, Object> auditSurface = (Map<String, Object>) tirithPolicy.get("auditSurface");
        Map<String, Object> sampleAudit = (Map<String, Object>) tirithPolicy.get("sampleAudit");

        assertThat(security.get("tirithTimeoutSeconds")).isEqualTo(Integer.valueOf(9));
        assertThat(security.get("tirithFailOpen")).isEqualTo(Boolean.FALSE);
        assertThat(tirithPolicy.get("scannerState")).isEqualTo("configured_unavailable");
        assertThat(tirithPolicy.get("failureMode")).isEqualTo("fail-closed");
        assertThat(diagnosticSummary.get("scannerConfigured")).isEqualTo(Boolean.TRUE);
        assertThat(diagnosticSummary.get("scannerAvailable")).isEqualTo(Boolean.FALSE);
        assertThat(diagnosticSummary.get("timeoutSeconds")).isEqualTo(Integer.valueOf(9));
        assertThat(auditSurface.get("surface")).isEqualTo("tirith_command_scan");
        assertThat(auditSurface.get("rawCommandExposed")).isEqualTo(Boolean.FALSE);
        assertThat(sampleAudit.get("redactionApplied")).isEqualTo(Boolean.TRUE);
        assertThat(sampleAudit.get("rawFindingsExposed")).isEqualTo(Boolean.FALSE);
        assertThat(tirithPolicy.get("rawConfiguredPathExposed")).isEqualTo(Boolean.FALSE);
        assertThat(tirithPolicy.get("rawResolvedPathExposed")).isEqualTo(Boolean.FALSE);
        assertThat(tirithPolicy.get("rawCommandExposed")).isEqualTo(Boolean.FALSE);
        assertThat(tirithPolicy.get("sampleAuditRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(ONode.serialize(result))
                .contains("path://tirith")
                .doesNotContain("/tmp/jimuqu-dashboard-tirith")
                .doesNotContain(token);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeToolResultStoragePolicyThroughDashboardSecurityAudit() throws Exception {
        AppConfig config = new AppConfig();
        ToolResultStorageService toolResultStorageService =
                new ToolResultStorageService(
                        new File("target/dashboard-security-audit-results/token=tool-result-secret")
                                .getAbsolutePath(),
                        512,
                        768,
                        300);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        toolResultStorageService);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", "policy");

        Map<String, Object> result = diagnosticsService.securityAudit(body);

        Map<String, Object> policy = (Map<String, Object>) result.get("policy");
        Map<String, Object> coverage = (Map<String, Object>) policy.get("coverage");
        assertThat(coverage.get("privateUrlPolicy")).isEqualTo(Boolean.TRUE);
        assertThat(coverage.get("mcpPackageSecurity")).isEqualTo(Boolean.TRUE);
        assertThat(coverage.get("mcpPackageSecurityPolicy")).isInstanceOf(Map.class);
        Map<String, Object> mcpPackagePolicy =
                (Map<String, Object>) coverage.get("mcpPackageSecurityPolicy");
        assertThat(mcpPackagePolicy.get("npxPackageOptionParsed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpPackagePolicy.get("pipxRunSubcommandSkipped")).isEqualTo(Boolean.TRUE);
        assertThat(mcpPackagePolicy.get("pypiSourceOptionParsed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpPackagePolicy.get("projectEndpointOverrideEnvironment"))
                .isEqualTo("SOLONCLAW_OSV_ENDPOINT");
        assertThat(coverage.get("toolResultStorage")).isEqualTo(Boolean.TRUE);
        Map<String, Object> storagePolicy =
                (Map<String, Object>) coverage.get("toolResultStoragePolicy");
        assertThat(storagePolicy.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(storagePolicy.get("inlineLimitBytes")).isEqualTo(Integer.valueOf(512));
        assertThat(storagePolicy.get("turnBudgetBytes")).isEqualTo(Integer.valueOf(768));
        assertThat(storagePolicy.get("previewLength")).isEqualTo(Integer.valueOf(300));
        assertThat(String.valueOf(storagePolicy))
                .contains("resultRefReturned")
                .contains("previewRedacted")
                .doesNotContain("dashboard-security-audit-results")
                .doesNotContain("tool-result-secret");
        assertThat(policy.get("activeSurfaces").toString())
                .contains("privateUrlPolicy")
                .contains("mcpPackageSecurity")
                .contains("toolResultStorage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeStatusActionAsReadOnlyPolicyAuditAlias() throws Exception {
        AppConfig config = new AppConfig();
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", "status");
        body.put("command", "echo token=ghp_statusaliassecret123");
        body.put("url", "http://127.0.0.1/latest/meta-data?token=status-secret");
        body.put("path", "target/token=sk-status-alias-secret.txt");

        Map<String, Object> result = diagnosticsService.securityAudit(body);

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("action")).isEqualTo("status");
        assertThat(result.get("decision")).isEqualTo("allow");
        assertThat(result.get("approval_required")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("blocking")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("summary"))
                .isEqualTo("Security policy status is available without exposing secret values.");
        Map<String, Object> policy = (Map<String, Object>) result.get("policy");
        Map<String, Object> coverage = (Map<String, Object>) policy.get("coverage");
        Map<String, Object> readOnlyAuditPolicy =
                (Map<String, Object>) coverage.get("readOnlyAuditPolicy");
        assertThat(readOnlyAuditPolicy.get("executesCommand")).isEqualTo(Boolean.FALSE);
        assertThat(readOnlyAuditPolicy.get("opensNetworkConnection")).isEqualTo(Boolean.FALSE);
        assertThat(readOnlyAuditPolicy.get("readsTargetUrl")).isEqualTo(Boolean.FALSE);
        assertThat(readOnlyAuditPolicy.get("writesFile")).isEqualTo(Boolean.FALSE);
        assertThat(readOnlyAuditPolicy.get("storesAuditInput")).isEqualTo(Boolean.FALSE);
        String json = ONode.serialize(result);
        assertThat(json)
                .doesNotContain("ghp_statusaliassecret123")
                .doesNotContain("status-secret")
                .doesNotContain("sk-status-alias-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeSubprocessEnvironmentProbeDiagnosticsWithoutLeakingTokenLikeNames()
            throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("TENOR_API_KEY");
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null,
                        null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put(
                "names",
                Arrays.asList(
                        "PATH",
                        "TENOR_API_KEY",
                        "OPENAI_API_KEY",
                        "_SOLONCLAW_FORCE_CUSTOM_TOKEN",
                        "ghp_probe1234567890"));

        Map<String, Object> result = diagnosticsService.subprocessEnvironmentProbe(body);

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("surface")).isEqualTo("subprocess_environment");
        assertThat(result.get("requested_count")).isEqualTo(Integer.valueOf(5));
        assertThat(String.valueOf(result.get("decision_categories")))
                .contains("force")
                .contains("provider-blocked")
                .contains("high-risk");
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        assertThat(decisions).hasSize(5);
        String json = ONode.serialize(result);
        assertThat(json)
                .contains("provider-blocked")
                .contains("force")
                .contains("configured-or-skill-passthrough")
                .contains("***")
                .doesNotContain("ghp_probe1234567890");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldScanBeyondReturnedApprovalLimitForPendingApprovals() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        List<SessionRecord> records = new ArrayList<SessionRecord>();
        for (int i = 0; i < 3; i++) {
            SessionRecord empty = new SessionRecord();
            empty.setSessionId("session-empty-" + i);
            empty.setSourceKey("source-empty-" + i);
            empty.setTitle("empty " + i);
            records.add(empty);
        }
        SessionRecord pending = new SessionRecord();
        pending.setSessionId("session-older-pending");
        pending.setSourceKey("source-older-pending");
        pending.setTitle("older pending");
        SqliteAgentSession pendingSession = new SqliteAgentSession(pending);
        approvalService.storePendingApproval(
                pendingSession,
                "execute_shell",
                "recursive_delete",
                "需要确认",
                "rm -rf workspace/cache");
        records.add(pending);

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(records),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("session_scan_limit")).isEqualTo(Integer.valueOf(5));
        assertThat(result.get("scanned_sessions")).isEqualTo(Integer.valueOf(4));
        assertThat(result.get("truncated")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("session_scan_truncated")).isEqualTo(Boolean.FALSE);
        assertThat(items.get(0).get("session_id")).isEqualTo("session-older-pending");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMarkPendingApprovalsTruncatedOnlyWhenMoreItemsExist() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        List<SessionRecord> records = new ArrayList<SessionRecord>();
        for (int i = 0; i < 2; i++) {
            SessionRecord record = new SessionRecord();
            record.setSessionId("session-pending-limit-" + i);
            record.setSourceKey("source-pending-limit-" + i);
            record.setTitle("pending limit " + i);
            SqliteAgentSession session = new SqliteAgentSession(record);
            approvalService.storePendingApproval(
                    session,
                    "execute_shell",
                    "recursive_delete_" + i,
                    "需要确认",
                    "rm -rf workspace/cache-" + i);
            records.add(record);
        }

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(records),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("scanned_sessions")).isEqualTo(Integer.valueOf(2));
        assertThat(result.get("truncated")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("session_scan_truncated")).isEqualTo(Boolean.FALSE);
        assertThat(items.get(0).get("session_id")).isEqualTo("session-pending-limit-0");
    }

    @Test
    void shouldMarkPendingApprovalSessionScanTruncatedWhenRecentWindowIsExhausted()
            throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        List<SessionRecord> records = new ArrayList<SessionRecord>();
        for (int i = 0; i < 6; i++) {
            SessionRecord record = new SessionRecord();
            record.setSessionId("session-scan-window-" + i);
            record.setSourceKey("source-scan-window-" + i);
            record.setTitle("scan window " + i);
            records.add(record);
        }

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(records),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(1);

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(0));
        assertThat(result.get("session_scan_limit")).isEqualTo(Integer.valueOf(5));
        assertThat(result.get("scanned_sessions")).isEqualTo(Integer.valueOf(5));
        assertThat(result.get("truncated")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("session_scan_truncated")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnStructuredResolveApprovalFailureWhenDependenciesUnavailable()
            throws Exception {
        AppConfig config = new AppConfig();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("sessionId", "session-missing-service");
        body.put("approvalId", "approval-missing-service");
        body.put("action", "deny");

        DashboardDiagnosticsService missingSessionRepository =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new DangerousCommandApprovalService(
                                null, config, new SecurityPolicyService(config)),
                        new SecurityPolicyService(config),
                        null);
        Map<String, Object> missingSessionResult = missingSessionRepository.resolveApproval(body);
        assertThat(missingSessionResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(missingSessionResult.get("code")).isEqualTo("approval_unavailable");
        assertThat(String.valueOf(missingSessionResult.get("message"))).contains("审批服务");

        DashboardDiagnosticsService missingApprovalService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(Collections.<SessionRecord>emptyList()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);
        Map<String, Object> missingApprovalResult = missingApprovalService.resolveApproval(body);
        assertThat(missingApprovalResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(missingApprovalResult.get("code")).isEqualTo("approval_unavailable");
        assertThat(String.valueOf(missingApprovalResult.get("message"))).contains("审批服务");

        Map<String, Object> pendingResult = missingApprovalService.pendingApprovals(10);
        assertThat(pendingResult.get("count")).isEqualTo(Integer.valueOf(0));
        assertThat(pendingResult.get("available")).isEqualTo(Boolean.FALSE);
        assertThat(pendingResult.get("code")).isEqualTo("approval_unavailable");
        assertThat(String.valueOf(pendingResult.get("message"))).contains("审批服务");

        Map<String, Object> alwaysResult = missingApprovalService.alwaysApprovals(10);
        assertThat(alwaysResult.get("count")).isEqualTo(Integer.valueOf(0));
        assertThat(alwaysResult.get("available")).isEqualTo(Boolean.FALSE);
        assertThat(alwaysResult.get("code")).isEqualTo("approval_unavailable");

        Map<String, Object> revokeResult =
                missingApprovalService.revokeAlwaysApproval(
                        Collections.singletonMap("approvalId", "approval-missing-service"));
        assertThat(revokeResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(revokeResult.get("code")).isEqualTo("approval_unavailable");
    }

    @Test
    void shouldReturnStructuredApprovalHistoryFailureWhenRepositoryUnavailable() throws Exception {
        AppConfig config = new AppConfig();
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new DangerousCommandApprovalService(
                                null, config, new SecurityPolicyService(config)),
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.approvalHistory(10);

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(0));
        assertThat(result.get("available")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("code")).isEqualTo("approval_history_unavailable");
        assertThat(String.valueOf(result.get("message"))).contains("审批历史");
    }

    @Test
    void shouldReturnStructuredSlashConfirmFailureWhenServiceUnavailable() throws Exception {
        AppConfig config = new AppConfig();
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> list = diagnosticsService.pendingSlashConfirms(10);
        assertThat(list.get("count")).isEqualTo(Integer.valueOf(0));
        assertThat(list.get("available")).isEqualTo(Boolean.FALSE);
        assertThat(list.get("code")).isEqualTo("slash_confirm_unavailable");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("confirmId", "confirm-missing-service");
        body.put("action", "deny");
        Map<String, Object> result = diagnosticsService.resolveSlashConfirm(body);

        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("code")).isEqualTo("slash_confirm_unavailable");
        assertThat(String.valueOf(result.get("message"))).contains("Slash");
    }

    @Test
    void shouldRedactResolveSlashConfirmIdentifiersAndReply() throws Exception {
        AppConfig config = new AppConfig();
        SlashConfirmService slashConfirmService = new SlashConfirmService(null);
        SlashConfirmService.PendingConfirm pending =
                slashConfirmService.register(
                        "source-slash-redact",
                        "reload-mcp token=ghp_slashcommand12345",
                        "confirm token=ghp_slashprompt12345");
        pending.setConfirmId("confirm-ghp_slashconfirm12345");
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        slashConfirmService,
                        new RedactingCommandService(),
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("confirmId", pending.getConfirmId());
        body.put("action", "approve");
        Map<String, Object> result = diagnosticsService.resolveSlashConfirm(body);
        String json = ONode.serialize(result);

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(json)
                .contains("\"confirm_id\":\"confirm-ghp_***\"")
                .contains("\"session_id\":\"session-ghp_***\"")
                .contains("\"branch_name\":\"branch-token=***\"")
                .contains("slash result token=***")
                .doesNotContain("slashconfirm12345")
                .doesNotContain("slashreplysession12345")
                .doesNotContain("slashreplybranch12345")
                .doesNotContain("slashreplycontent12345");
    }

    @Test
    void shouldMarkApprovalHistoryTruncatedOnlyWhenMoreItemsExist() throws Exception {
        AppConfig config = new AppConfig();
        ApprovalAuditEvent first = new ApprovalAuditEvent();
        first.setEventId("history-truncated-1");
        first.setCreatedAt(1700000000001L);
        ApprovalAuditEvent second = new ApprovalAuditEvent();
        second.setEventId("history-truncated-2");
        second.setCreatedAt(1700000000002L);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        new FixedApprovalAuditRepository(Arrays.asList(first, second)),
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.approvalHistory(1);

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("truncated")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void shouldMarkAlwaysApprovalListTruncatedOnlyWhenMoreItemsExist() throws Exception {
        AppConfig config = new AppConfig();
        MemoryGlobalSettingRepository globalSettings = new MemoryGlobalSettingRepository();
        globalSettings.set(
                "dangerous_command_always_patterns",
                ONode.serialize(Arrays.asList("execute_shell:first", "execute_shell:second")));
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        globalSettings, config, new SecurityPolicyService(config));
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.alwaysApprovals(1);

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("truncated")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void shouldMarkSlashConfirmListTruncatedOnlyWhenMoreItemsExist() {
        AppConfig config = new AppConfig();
        SlashConfirmService slashConfirmService = new SlashConfirmService(null);
        slashConfirmService.register("source-slash-1", "/reload-mcp one", "确认一", false);
        slashConfirmService.register("source-slash-2", "/reload-mcp two", "确认二", false);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        slashConfirmService,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingSlashConfirms(1);

        assertThat(result.get("count")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("truncated")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactPendingApprovalDiagnosticOutput() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-pending\u202E");
        record.setSourceKey("source-pending");
        record.setTitle("审批标题 token=ghp_titlepending123\u202E");
        record.setBranchName("main\u202E");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell\u202E",
                "token_ghp_pendingpattern123\u202E",
                "pending password=pending-secret\u202E",
                "rm -rf workspace/cache --token ghp_pendingcommand123\u202E");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(Collections.singletonList(record)),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        String json = ONode.serialize(items.get(0));

        assertThat(json)
                .contains("\"session_id\":\"session-pending\"")
                .contains("\"branch_name\":\"main\"")
                .contains("\"tool_name\":\"execute_shell\"")
                .contains("token_ghp_***")
                .contains("password=***")
                .contains("command_preview\":\"rm -rf workspace/cache --token ***")
                .doesNotContain("\\u202E")
                .doesNotContain("\"approval_key\":")
                .doesNotContain("ghp_titlepending123")
                .doesNotContain("pendingpattern123")
                .doesNotContain("pending-secret")
                .doesNotContain("ghp_pendingcommand123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactEncodedPendingApprovalDiagnosticOutput() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-encoded-pending");
        record.setSourceKey("source-encoded-pending");
        record.setTitle("编码审批 https://example.test/callback?api%255Fkey=diagnostic-secret");
        record.setBranchName("main");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "url_policy?api%255Fkey=diagnostic-secret",
                "encoded pending https://example.test/callback?api%255Fkey=diagnostic-secret",
                "curl https://example.test/callback?api%255Fkey=diagnostic-secret");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(Collections.singletonList(record)),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        String json = ONode.serialize(items.get(0));

        assertThat(json)
                .contains("api%255Fkey=***")
                .contains("\"pattern_key\":\"url_policy?api%255Fkey=***\"")
                .contains(
                        "\"command_preview\":\"curl https://example.test/callback?api%255Fkey=***\"")
                .doesNotContain("\"approval_key\":")
                .doesNotContain("diagnostic-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactEncodedSlashConfirmDiagnosticOutput() {
        AppConfig config = new AppConfig();
        SlashConfirmService slashConfirmService = new SlashConfirmService(null);
        slashConfirmService.register(
                "source-slash-confirm",
                "/reload-mcp https://example.test/callback?api%255Fkey=slash-secret",
                "确认执行 https://example.test/callback?api%255Fkey=slash-secret",
                true);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        slashConfirmService,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingSlashConfirms(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        String json = ONode.serialize(items.get(0));

        assertThat(json)
                .contains(
                        "\"command_preview\":\"/reload-mcp https://example.test/callback?api%255Fkey=***\"")
                .contains(
                        "\"prompt_preview\":\"确认执行 https://example.test/callback?api%255Fkey=***\"")
                .doesNotContain("slash-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseOpaqueSelectorForQueuedApprovalWithoutApprovalId() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-queued-approval");
        record.setSourceKey("source-queued-approval");
        record.setTitle("队列审批会话");

        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        DangerousCommandApprovalService.PendingApproval pending =
                approvalService.getPendingApproval(session);
        String approvalKey = pending.approvalKey();
        Map<String, Object> queued = new LinkedHashMap<String, Object>();
        queued.put("toolName", pending.getToolName());
        queued.put("patternKey", pending.getPatternKey());
        queued.put("patternKeys", pending.effectivePatternKeys());
        queued.put("description", pending.getDescription());
        queued.put("command", pending.getCommand());
        queued.put("commandHash", pending.getCommandHash());
        queued.put("approvalKey", approvalKey);
        queued.put("createdAt", Long.valueOf(pending.getCreatedAt()));
        queued.put("expiresAt", Long.valueOf(pending.getExpiresAt()));
        List<Map<String, Object>> queue = new ArrayList<Map<String, Object>>();
        queue.add(queued);
        session.getContext().put("_dangerous_command_pending_queue_", queue);
        session.updateSnapshot();

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(Collections.singletonList(record)),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        Map<String, Object> item = items.get(0);
        String selector = String.valueOf(item.get("selector"));

        assertThat(item.get("approval_id")).isEqualTo(selector);
        assertThat(selector).startsWith("key_").hasSize(28);
        assertThat(selector).isNotEqualTo(approvalKey).doesNotContain("execute_shell:");
        assertThat(item).doesNotContainKey("approval_key");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("sessionId", "session-queued\u202E-approval");
        body.put("approvalId", selector.substring(0, 8) + "\u202E" + selector.substring(8));
        body.put("action", "deny");
        body.put("resume", Boolean.FALSE);
        Map<String, Object> resolve = diagnosticsService.resolveApproval(body);

        assertThat(resolve.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(approvalService.listPendingApprovals(record)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactResolveApprovalIdentifiersAndResumeReply() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-ghp_resolveapproval12345");
        record.setSourceKey("source-resolve-approval");
        record.setTitle("resolve approval");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "resolve approval",
                "rm -rf workspace/cache");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(Collections.singletonList(record)),
                        new RedactingResumeOrchestrator(),
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> pending = diagnosticsService.pendingApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) pending.get("items");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("sessionId", record.getSessionId());
        body.put("approvalId", String.valueOf(items.get(0).get("selector")));
        body.put("action", "approve");
        Map<String, Object> resolve = diagnosticsService.resolveApproval(body);
        String json = ONode.serialize(resolve);

        assertThat(resolve.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(json)
                .contains("\"session_id\":\"session-ghp_***\"")
                .contains("\"branch_name\":\"branch-token=***\"")
                .contains("\"content\":\"resumed token=***\"")
                .doesNotContain("resolveapproval12345")
                .doesNotContain("resolvereplysession12345")
                .doesNotContain("resolvebranch12345")
                .doesNotContain("resolvereplycontent12345");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactApprovalHistoryOutput() throws Exception {
        AppConfig config = new AppConfig();
        ApprovalAuditEvent event = new ApprovalAuditEvent();
        event.setEventId("audit-1\u202E");
        event.setSessionId("session-audit\u202E");
        event.setEventType("response");
        event.setChoice("once");
        event.setApprover("operator token=ghp_approversecret123");
        event.setToolName("execute_shell\u202E");
        event.setApprovalId("approval-1\u202E");
        event.setApprovalKey("execute_shell:recursive_delete:hash");
        event.setCommandHash("hash");
        event.setCommandPreview(
                "printf api_key=sk-history-secret && curl https://example.test/callback?api%255Fkey=history-encoded-secret");
        event.setDescription(
                "history password=history-secret https://example.test/callback?api%255Fkey=history-encoded-secret");
        event.setPatternKeysJson(
                "[\"recursive_delete\u202E\",\"token_ghp_historypattern123\",\"url_policy?api%255Fkey=history-encoded-secret\"]");
        event.setCreatedAt(1700000000002L);

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        new FixedApprovalAuditRepository(Collections.singletonList(event)),
                        null,
                        null,
                        null,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.approvalHistory(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        Map<String, Object> item = items.get(0);

        String json = ONode.serialize(item);
        assertThat(json).doesNotContain("ghp_approversecret123");
        assertThat(json).doesNotContain("sk-history-secret");
        assertThat(json).doesNotContain("history-encoded-secret");
        assertThat(json).doesNotContain("history-secret");
        assertThat(json).doesNotContain("execute_shell:recursive_delete:hash");
        assertThat(json).doesNotContain("\"command_hash\":\"hash\"");
        assertThat(json).doesNotContain("\\u202E");
        assertThat(json).doesNotContain("historypattern123");
        assertThat(json).doesNotContain("\"approval_id\":");
        assertThat(json).doesNotContain("\"approval_key\":");
        assertThat(json).contains("\"session_id\":\"session-audit\"");
        assertThat(json).contains("\"tool_name\":\"execute_shell\"");
        assertThat(json).contains("token_ghp_***");
        assertThat(json).contains("api%255Fkey=***");
        assertThat(json).contains("\"command_hash\":\"***\"");
        assertThat(json).contains("token=***").contains("api_key=***").contains("password=***");
    }

    @Test
    void shouldRedactAlwaysApprovalRevokeAuditApprover() throws Exception {
        AppConfig config = new AppConfig();
        FixedApprovalAuditRepository auditRepository =
                new FixedApprovalAuditRepository(Collections.<ApprovalAuditEvent>emptyList());
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        new MemoryGlobalSettingRepository(),
                        config,
                        new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-revoke");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        assertThat(
                        approvalService.approve(
                                session,
                                DangerousCommandApprovalService.ApprovalScope.ALWAYS,
                                "setup"))
                .isTrue();
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        auditRepository,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);
        Map<String, Object> list = diagnosticsService.alwaysApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("approvalId", String.valueOf(items.get(0).get("approval_id")));
        body.put("approver", "dashboard token=ghp_revokeapprover123");

        Map<String, Object> result = diagnosticsService.revokeAlwaysApproval(body);

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(auditRepository.events).hasSize(1);
        ApprovalAuditEvent event = auditRepository.events.get(0);
        assertThat(event.getChoice()).isEqualTo("revoke");
        assertThat(event.getApprover()).doesNotContain("ghp_revokeapprover123");
        assertThat(event.getApprover()).contains("token=***");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactAlwaysApprovalRevokeAuditKey() throws Exception {
        AppConfig config = new AppConfig();
        FixedApprovalAuditRepository auditRepository =
                new FixedApprovalAuditRepository(Collections.<ApprovalAuditEvent>emptyList());
        MemoryGlobalSettingRepository globalSettings = new MemoryGlobalSettingRepository();
        String approval = "execute_shell\u202E:token_ghp_revokeapprovalsecret123\u202E";
        globalSettings.set(
                AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                ONode.serialize(Collections.singletonList(approval)));
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        globalSettings, config, new SecurityPolicyService(config));
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        auditRepository,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);
        Map<String, Object> list = diagnosticsService.alwaysApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("approvalId", String.valueOf(items.get(0).get("approval_id")));
        body.put("approver", "dashboard");

        Map<String, Object> result = diagnosticsService.revokeAlwaysApproval(body);

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(auditRepository.events).hasSize(1);
        ApprovalAuditEvent event = auditRepository.events.get(0);
        assertThat(event.getApprovalKey()).isEqualTo("execute_shell:***");
        assertThat(event.getApprovalKey())
                .doesNotContain("\u202E")
                .doesNotContain("revokeapprovalsecret123");
        assertThat(event.getPatternKeysJson())
                .contains("token_ghp_***")
                .doesNotContain("\\u202E")
                .doesNotContain("revokeapprovalsecret123");
    }

    @Test
    void shouldRejectRawAlwaysApprovalRevokeInput() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        new MemoryGlobalSettingRepository(),
                        config,
                        new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-reject-raw-revoke");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf workspace/cache");
        assertThat(
                        approvalService.approve(
                                session,
                                DangerousCommandApprovalService.ApprovalScope.ALWAYS,
                                "setup"))
                .isTrue();
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("approval", "execute_shell:recursive_delete");

        Map<String, Object> result = diagnosticsService.revokeAlwaysApproval(body);

        assertThat(result.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(result.get("code")).isEqualTo("missing_approval");
        assertThat(
                        approvalService.isAlwaysApproved(
                                "execute_shell", "recursive_delete", "rm -rf workspace/cache"))
                .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactAlwaysApprovalListIdentifiers() throws Exception {
        AppConfig config = new AppConfig();
        MemoryGlobalSettingRepository globalSettings = new MemoryGlobalSettingRepository();
        globalSettings.set(
                "dangerous_command_always_patterns",
                "[\"execute_shell\\u202E:token_ghp_alwayspattern123\\u202E\"]");
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        globalSettings, config, new SecurityPolicyService(config));

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.alwaysApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        Map<String, Object> item = items.get(0);

        assertThat(item).doesNotContainKey("approval");
        assertThat(String.valueOf(item)).doesNotContain("execute_shell:token_ghp_");
        assertThat(String.valueOf(item)).doesNotContain("alwayspattern123");
        assertThat(String.valueOf(item.get("approval_id"))).isNotBlank();
        assertThat(String.valueOf(item.get("tool_name"))).isEqualTo("execute_shell");
        assertThat(String.valueOf(item.get("pattern_key")))
                .contains("token_ghp_***")
                .doesNotContain("\u202E")
                .doesNotContain("alwayspattern123");
    }

    private static Map<String, Object> findApprovalItem(
            List<Map<String, Object>> items, String sessionId) {
        for (Map<String, Object> item : items) {
            if (sessionId.equals(item.get("session_id"))) {
                return item;
            }
        }
        throw new AssertionError("approval item not found: " + sessionId);
    }

    private static class RedactingResumeOrchestrator implements ConversationOrchestrator {
        @Override
        public GatewayReply handleIncoming(GatewayMessage message) {
            return GatewayReply.ok("");
        }

        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            return GatewayReply.ok("");
        }

        @Override
        public GatewayReply resumePending(String sourceKey) {
            GatewayReply reply = GatewayReply.ok("resumed token=ghp_resolvereplycontent12345");
            reply.setSessionId("session-ghp_resolvereplysession12345");
            reply.setBranchName("branch-token=ghp_resolvebranch12345");
            return reply;
        }
    }

    private static class RedactingCommandService implements CommandService {
        @Override
        public boolean supports(String commandName) {
            return true;
        }

        @Override
        public GatewayReply handle(GatewayMessage message, String commandLine) {
            GatewayReply reply = GatewayReply.ok("slash result token=ghp_slashreplycontent12345");
            reply.setSessionId("session-ghp_slashreplysession12345");
            reply.setBranchName("branch-token=ghp_slashreplybranch12345");
            return reply;
        }
    }

    private static Map<String, Object> findProbe(List<Map<String, Object>> items, String key) {
        for (Map<String, Object> item : items) {
            if (key.equals(item.get("key"))) {
                return item;
            }
        }
        throw new AssertionError("security probe not found: " + key);
    }

    private static List<String> failedProbeKeys(List<Map<String, Object>> items) {
        List<String> failures = new ArrayList<String>();
        for (Map<String, Object> item : items) {
            if (!Boolean.TRUE.equals(item.get("passed"))) {
                failures.add(String.valueOf(item));
            }
        }
        return failures;
    }

    private static TirithSecurityService.ScanResult scanResult(
            String action, List<TirithSecurityService.Finding> findings, String summary)
            throws Exception {
        java.lang.reflect.Constructor<TirithSecurityService.ScanResult> constructor =
                TirithSecurityService.ScanResult.class.getDeclaredConstructor(
                        String.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(action, findings, summary);
    }

    private static class FixedTirithSecurityService extends TirithSecurityService {
        private final TirithSecurityService.ScanResult result;

        private FixedTirithSecurityService(TirithSecurityService.ScanResult result) {
            super(enabledTirithConfig());
            this.result = result;
        }

        @Override
        public Map<String, Object> policySummary() {
            Map<String, Object> summary = super.policySummary();
            summary.put("enabled", Boolean.TRUE);
            summary.put("configured", Boolean.TRUE);
            summary.put("available", Boolean.TRUE);
            return summary;
        }

        @Override
        public TirithSecurityService.ScanResult checkCommandSecurityForTool(
                String toolName, String command) {
            return result;
        }
    }

    private static AppConfig enabledTirithConfig() {
        AppConfig config = new AppConfig();
        config.getSecurity().setTirithEnabled(true);
        config.getSecurity().setTirithPath("target/dashboard-tirith-probe");
        return config;
    }

    private static String latestShutdownFile(Path workspaceHome) {
        File[] files = workspaceHome.resolve("forensics").toFile().listFiles();
        assertThat(files).isNotNull();
        return Arrays.stream(files)
                .filter(file -> file.getName().startsWith("shutdown-"))
                .findFirst()
                .map(File::getName)
                .orElseThrow(IllegalStateException::new);
    }

    private static class FixedDeliveryService implements DeliveryService {
        private final List<ChannelStatus> statuses;

        private FixedDeliveryService(ChannelStatus... statuses) {
            if (statuses == null || statuses.length == 0) {
                this.statuses = Collections.emptyList();
                return;
            }
            this.statuses = new ArrayList<ChannelStatus>();
            for (ChannelStatus status : statuses) {
                if (status != null) {
                    this.statuses.add(status);
                }
            }
        }

        @Override
        public void deliver(DeliveryRequest request) {}

        @Override
        public List<ChannelStatus> statuses() {
            return statuses;
        }
    }

    private static List<String> checkCodes(List<Map<String, Object>> checks) {
        List<String> codes = new ArrayList<String>();
        if (checks == null) {
            return codes;
        }
        for (Map<String, Object> check : checks) {
            if (check != null && check.get("code") != null) {
                codes.add(String.valueOf(check.get("code")));
            }
        }
        return codes;
    }

    private static class FixedSessionRepository implements SessionRepository {
        private final List<SessionRecord> records;

        private FixedSessionRepository(List<SessionRecord> records) {
            this.records =
                    records == null
                            ? Collections.<SessionRecord>emptyList()
                            : new ArrayList<SessionRecord>(records);
        }

        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            return null;
        }

        @Override
        public SessionRecord bindNewSession(String sourceKey) {
            return null;
        }

        @Override
        public void bindSource(String sourceKey, String sessionId) {}

        @Override
        public SessionRecord cloneSession(
                String sourceKey, String sourceSessionId, String branchName) {
            return null;
        }

        @Override
        public SessionRecord findById(String sessionId) {
            for (SessionRecord record : records) {
                if (record.getSessionId().equals(sessionId)) {
                    return record;
                }
            }
            return null;
        }

        @Override
        public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
            return null;
        }

        @Override
        public List<SessionRecord> findResumeCandidates(String reference, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void save(SessionRecord sessionRecord) {}

        @Override
        public List<SessionRecord> search(String keyword, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listRecent(int limit) {
            return records.subList(0, Math.min(Math.max(limit, 0), records.size()));
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            int safeOffset = Math.min(Math.max(offset, 0), records.size());
            int safeEnd = Math.min(safeOffset + Math.max(limit, 0), records.size());
            return records.subList(safeOffset, safeEnd);
        }

        @Override
        public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int countAll() {
            return records.size();
        }

        @Override
        public void delete(String sessionId) {}

        @Override
        public void setModelOverride(String sessionId, String modelOverride) {}

        @Override
        public void setServiceTierOverride(String sessionId, String serviceTierOverride) {}

        @Override
        public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {}

        @Override
        public void setActiveAgentName(String sessionId, String agentName) {}

        @Override
        public void clearActiveAgentName(String agentName) {}

        @Override
        public void setGoalState(String sessionId, String goalStateJson) {}

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {}
    }

    private static class FixedApprovalAuditRepository implements ApprovalAuditRepository {
        private final List<ApprovalAuditEvent> events;

        private FixedApprovalAuditRepository(List<ApprovalAuditEvent> events) {
            this.events =
                    events == null
                            ? Collections.<ApprovalAuditEvent>emptyList()
                            : new ArrayList<ApprovalAuditEvent>(events);
        }

        @Override
        public void append(ApprovalAuditEvent event) {
            events.add(event);
        }

        @Override
        public List<ApprovalAuditEvent> listRecent(int limit) {
            return events;
        }
    }

    private static class FixedAgentRunRepository implements AgentRunRepository {
        private final List<AgentRunRecord> runs = new ArrayList<AgentRunRecord>();

        @Override
        public void saveRun(AgentRunRecord record) {}

        @Override
        public AgentRunRecord findRun(String runId) {
            return null;
        }

        @Override
        public List<AgentRunRecord> listBySession(String sessionId, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listFinishedWithUsage(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listRecoverable(int limit) {
            return runs.subList(0, Math.min(Math.max(limit, 0), runs.size()));
        }

        @Override
        public List<AgentRunRecord> listActiveBefore(long beforeEpochMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void markStaleRuns(long beforeEpochMillis, long now) {}

        @Override
        public List<AgentRunRecord> listActiveBySource(String sourceKey, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> searchRuns(
                String sourceKey,
                String sessionId,
                String runId,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            return Collections.emptyList();
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {}

        @Override
        public List<AgentRunEventRecord> listEvents(String runId) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunEventRecord> searchEvents(
                String sourceKey,
                String sessionId,
                String runId,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            return Collections.emptyList();
        }

        @Override
        public void saveRunControlCommand(RunControlCommand command) {}

        @Override
        public List<RunControlCommand> listRunControlCommands(String runId) {
            return Collections.emptyList();
        }

        @Override
        public RunControlCommand findLatestPendingCommand(String runId, String command) {
            return null;
        }

        @Override
        public void markRunControlCommandHandled(String commandId, String status, long handledAt) {}

        @Override
        public void saveQueuedMessage(QueuedRunMessage message) {}

        @Override
        public QueuedRunMessage findNextQueuedMessage(String sourceKey, String sessionId) {
            return null;
        }

        @Override
        public int countQueuedMessages(String sourceKey, String sessionId) {
            return 0;
        }

        @Override
        public void markQueuedMessage(
                String queueId, String status, long timestamp, String error) {}

        @Override
        public void saveToolCall(ToolCallRecord record) {}

        @Override
        public List<ToolCallRecord> listToolCalls(String runId) {
            return Collections.emptyList();
        }

        @Override
        public List<ToolCallRecord> searchToolCalls(
                String sourceKey,
                String sessionId,
                String runId,
                String toolName,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            return Collections.emptyList();
        }

        @Override
        public void saveSubagentRun(SubagentRunRecord record) {}

        @Override
        public List<SubagentRunRecord> listSubagents(String parentRunId) {
            return Collections.emptyList();
        }

        @Override
        public void saveRecovery(RunRecoveryRecord record) {}

        @Override
        public List<RunRecoveryRecord> listRecoveries(String runId) {
            return Collections.emptyList();
        }

        @Override
        public void pruneBefore(long beforeEpochMillis) {}
    }

    private static class MemoryGlobalSettingRepository
            implements com.jimuqu.solon.claw.core.repository.GlobalSettingRepository {
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }

    private static class FixedToolRegistry implements ToolRegistry {
        @Override
        public List<String> listToolNames() {
            return Collections.singletonList("execute_shell");
        }

        @Override
        public List<Object> resolveEnabledTools(String sourceKey) {
            return Collections.emptyList();
        }

        @Override
        public List<Object> resolveEnabledTools(String sourceKey, AgentRuntimeScope agentScope) {
            return Collections.emptyList();
        }

        @Override
        public List<String> resolveEnabledToolNames(String sourceKey) {
            return Collections.singletonList("execute_shell");
        }

        @Override
        public List<String> resolveEnabledToolNames(
                String sourceKey, AgentRuntimeScope agentScope) {
            return Collections.singletonList("execute_shell");
        }

        @Override
        public void enableTools(String sourceKey, List<String> toolNames) {}

        @Override
        public void disableTools(String sourceKey, List<String> toolNames) {}
    }
}
