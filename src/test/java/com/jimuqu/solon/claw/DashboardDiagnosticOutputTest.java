package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.ToolRegistry;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import com.jimuqu.solon.claw.tool.runtime.ToolResultStorageService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import cn.hutool.core.io.FileUtil;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void shouldRedactGatewayDoctorAndDiagnosticsOutput() throws Exception {
        AppConfig config = new AppConfig();
        File runtimeHome = new File("target/diagnostic-secret-runtime").getAbsoluteFile();
        File externalState =
                new File(
                        "target/diagnostic-external-token=ghp_diagnosticexternal123/state.db")
                        .getAbsoluteFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setStateDb(externalState.getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(runtimeHome, "logs").getAbsolutePath());

        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default");
        provider.setBaseUrl("https://user:provider-pass@example.com/v1?token=provider-token");
        provider.setDefaultModel("gpt-test");
        provider.setDialect("openai");
        provider.setApiKey("sk-test-providersecret");
        config.getProviders().put("default", provider);

        ChannelStatus channelStatus =
                new ChannelStatus(
                        PlatformType.FEISHU,
                        true,
                        false,
                        "failed at "
                                + new File(runtimeHome, "secrets/token.txt").getAbsolutePath()
                                + " token=ghp_doctordetail123");
        channelStatus.setSetupState("error");
        channelStatus.setConnectionMode("websocket");
        channelStatus.setMissingConfig(Arrays.asList("channels.feishu.appSecret"));
        channelStatus.setLastErrorCode("auth_failed");
        channelStatus.setLastErrorMessage(
                "Authorization: Bearer ghp_doctorerror123 password=doctor-password");

        FixedDeliveryService deliveryService = new FixedDeliveryService(channelStatus);
        GatewayRuntimeRefreshService refreshService =
                new GatewayRuntimeRefreshService(
                        config, new ChannelConnectionManager(Collections.emptyMap()));

        DashboardGatewayDoctorService doctorService =
                new DashboardGatewayDoctorService(config, deliveryService, refreshService);
        String doctorJson = ONode.serialize(doctorService.doctor());
        assertThat(doctorJson).contains("runtime://");
        assertThat(doctorJson).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(doctorJson).doesNotContain("ghp_doctordetail123");
        assertThat(doctorJson).doesNotContain("ghp_doctorerror123");
        assertThat(doctorJson).doesNotContain("doctor-password");

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
                        null);
        String diagnosticsJson = ONode.serialize(diagnosticsService.diagnostics());
        assertThat(diagnosticsJson).contains("path://state.db");
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
        assertThat(diagnosticsJson).contains("approval service is unavailable");
        assertThat(diagnosticsJson).contains("\"probes\"");
        assertThat(diagnosticsJson).contains("\"metadata_url\"");
        assertThat(diagnosticsJson).contains("\"sensitive_query\"");
        assertThat(diagnosticsJson).contains("\"tool_args_url\"");
        assertThat(diagnosticsJson).contains("\"passed\":true");
        assertThat(diagnosticsJson).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(diagnosticsJson).doesNotContain(externalState.getParentFile().getAbsolutePath());
        assertThat(diagnosticsJson).doesNotContain("ghp_diagnosticexternal123");
        assertThat(diagnosticsJson).contains("https://user:***@example.com/v1?token=***");
        assertThat(diagnosticsJson).doesNotContain("provider-pass");
        assertThat(diagnosticsJson).doesNotContain("provider-token");
        assertThat(diagnosticsJson).doesNotContain("sk-test-providersecret");
        assertThat(diagnosticsJson).doesNotContain("sk-dashboard-probe-secret");
        assertThat(diagnosticsJson).doesNotContain("dashboard-probe-password");
        assertThat(diagnosticsJson).doesNotContain("ghp_doctorerror123");
        assertThat(diagnosticsJson).doesNotContain("doctor-password");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeWebsiteSharedPolicyDiagnosticsWithoutLeakingPaths() throws Exception {
        Path parent = Files.createTempDirectory("jimuqu-dashboard-website-policy");
        Path runtimeHome = Files.createDirectory(parent.resolve("runtime-token=ghp_dashboardwebsecret123"));
        File shared = runtimeHome.resolve("shared-token=sk-dashboard-secret.txt").toFile();
        FileUtil.writeUtf8String("blocked.example\nshared-token-sk-dashboardsecret.example\n", shared);
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity().getWebsiteBlocklist().setDomains(Arrays.asList("inline.example"));
        config.getSecurity()
                .getWebsiteBlocklist()
                .setSharedFiles(Arrays.asList(shared.getName(), "../missing-token=sk-dashboard-secret.txt"));
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
        assertThat(security.get("websiteBlocklistLoadedSharedFileCount")).isEqualTo(Integer.valueOf(1));
        assertThat(security.get("websiteBlocklistSkippedSharedFileCount")).isEqualTo(Integer.valueOf(1));
        assertThat(security.get("websiteBlocklistSharedRuleCount")).isEqualTo(Integer.valueOf(2));
        String json = ONode.serialize(result);
        assertThat(json)
                .doesNotContain(runtimeHome.toString())
                .doesNotContain(shared.getAbsolutePath())
                .doesNotContain("ghp_dashboardwebsecret123")
                .doesNotContain("sk-dashboard-secret");
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
                .isEqualTo("JIMUQU_OSV_ENDPOINT");
        assertThat(mcpPackagePolicy.get("legacyEndpointOverrideEnvironment")).isEqualTo("OSV_ENDPOINT");
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
    void shouldExposeApprovalSecurityProbesWhenApprovalServiceIsAvailable() throws Exception {
        AppConfig config = new AppConfig();
        File runtimeHome = new File("target/dashboard-security-probes").getAbsoluteFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(runtimeHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(runtimeHome, "logs").getAbsolutePath());
        config.getSecurity().getWebsiteBlocklist().setEnabled(true);
        config.getSecurity().getWebsiteBlocklist().setDomains(Arrays.asList("blocked.example"));
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        TirithSecurityService tirithSecurityService =
                new FixedTirithSecurityService(
                        scanResult("warn", Collections.<TirithSecurityService.Finding>emptyList(), "probe warning"));
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        null,
                        null,
                        null,
                        new SlashConfirmService(null),
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        tirithSecurityService,
                        null);

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> security = (Map<String, Object>) diagnostics.get("security");
        Map<String, Object> probes = (Map<String, Object>) security.get("probes");
        assertThat(probes.get("available")).isEqualTo(Boolean.TRUE);
        assertThat(probes.get("passed")).isEqualTo(Boolean.TRUE);
        List<Map<String, Object>> items = (List<Map<String, Object>>) probes.get("items");
        Map<String, Object> hardline = findProbe(items, "hardline_command");
        Map<String, Object> sudoRewrite = findProbe(items, "sudo_rewrite");
        Map<String, Object> terminal = findProbe(items, "terminal_guardrail");
        Map<String, Object> terminalOutput = findProbe(items, "terminal_output");
        Map<String, Object> backgroundProcessGuard = findProbe(items, "background_process_guard");
        Map<String, Object> privateUrl = findProbe(items, "private_url");
        Map<String, Object> loopbackUrl = findProbe(items, "loopback_url");
        Map<String, Object> ipv6LoopbackUrl = findProbe(items, "ipv6_loopback_url");
        Map<String, Object> numericLoopbackUrl = findProbe(items, "numeric_loopback_url");
        Map<String, Object> ipv4MappedLoopbackUrl =
                findProbe(items, "ipv4_mapped_loopback_url");
        Map<String, Object> protocolRelativePrivateUrl =
                findProbe(items, "protocol_relative_private_url");
        Map<String, Object> encodedPrivateHostUrl =
                findProbe(items, "encoded_private_host_url");
        Map<String, Object> unsupportedNetworkScheme =
                findProbe(items, "unsupported_network_scheme");
        Map<String, Object> unsupportedSftpScheme =
                findProbe(items, "unsupported_sftp_scheme");
        Map<String, Object> unsupportedScpScheme =
                findProbe(items, "unsupported_scp_scheme");
        Map<String, Object> sensitiveFragment = findProbe(items, "sensitive_fragment");
        Map<String, Object> encodedSensitiveQuery = findProbe(items, "encoded_sensitive_query");
        Map<String, Object> repeatedEncodedSensitiveQuery =
                findProbe(items, "repeated_encoded_sensitive_query");
        Map<String, Object> semicolonSensitiveQuery =
                findProbe(items, "semicolon_sensitive_query");
        Map<String, Object> sensitiveQueryAlias = findProbe(items, "sensitive_query_alias");
        Map<String, Object> signedUrl = findProbe(items, "signed_url");
        Map<String, Object> nestedSignedUrl = findProbe(items, "nested_signed_url");
        Map<String, Object> encodedUserinfoUrl = findProbe(items, "encoded_userinfo_url");
        Map<String, Object> schemelessUserinfoUrl =
                findProbe(items, "schemeless_userinfo_url");
        Map<String, Object> sensitivePathSegmentUrl =
                findProbe(items, "sensitive_path_segment_url");
        Map<String, Object> schemelessSensitiveQuery =
                findProbe(items, "schemeless_sensitive_query");
        Map<String, Object> schemelessSensitivePath =
                findProbe(items, "schemeless_sensitive_path");
        Map<String, Object> encodedSeparatorSensitiveQuery =
                findProbe(items, "encoded_separator_sensitive_query");
        Map<String, Object> htmlEntitySensitiveQuery =
                findProbe(items, "html_entity_sensitive_query");
        Map<String, Object> credentialPath = findProbe(items, "credential_path");
        Map<String, Object> credentialFileName = findProbe(items, "credential_file_name");
        Map<String, Object> credentialPathSuffix = findProbe(items, "credential_path_suffix");
        Map<String, Object> encodedPathTraversal = findProbe(items, "encoded_path_traversal");
        Map<String, Object> pathControlCharacter = findProbe(items, "path_control_character");
        Map<String, Object> devicePathRead = findProbe(items, "device_path_read");
        Map<String, Object> rawBlockDeviceWrite = findProbe(items, "raw_block_device_write");
        Map<String, Object> skillsHubInternalPath =
                findProbe(items, "skills_hub_internal_path");
        Map<String, Object> commandUrlPolicy = findProbe(items, "command_url_policy");
        Map<String, Object> workdirTextPolicy = findProbe(items, "workdir_text_policy");
        Map<String, Object> toolArgsRepeatedEncodedSensitiveUrl =
                findProbe(items, "tool_args_repeated_encoded_sensitive_url");
        Map<String, Object> toolArgsSemicolonSensitiveUrl =
                findProbe(items, "tool_args_semicolon_sensitive_url");
        Map<String, Object> toolArgsSensitiveQueryAlias =
                findProbe(items, "tool_args_sensitive_query_alias");
        Map<String, Object> toolArgsEndpointPrivateUrl =
                findProbe(items, "tool_args_endpoint_private_url");
        Map<String, Object> toolArgsNestedEndpointPrivateUrl =
                findProbe(items, "tool_args_nested_endpoint_private_url");
        Map<String, Object> toolArgsHostTargetPrivateUrl =
                findProbe(items, "tool_args_host_target_private_url");
        Map<String, Object> toolResultRedirectTarget =
                findProbe(items, "tool_result_redirect_target");
        Map<String, Object> commandWebsocketUrlPolicy =
                findProbe(items, "command_websocket_url_policy");
        Map<String, Object> commandUnsupportedFtpUrlPolicy =
                findProbe(items, "command_unsupported_ftp_url_policy");
        Map<String, Object> commandUnsupportedSftpUrlPolicy =
                findProbe(items, "command_unsupported_sftp_url_policy");
        Map<String, Object> commandUnsupportedScpUrlPolicy =
                findProbe(items, "command_unsupported_scp_url_policy");
        Map<String, Object> commandUserinfoUrlPolicy =
                findProbe(items, "command_userinfo_url_policy");
        Map<String, Object> commandSchemelessUserinfoUrlPolicy =
                findProbe(items, "command_schemeless_userinfo_url_policy");
        Map<String, Object> commandProtocolRelativeUrlPolicy =
                findProbe(items, "command_protocol_relative_url_policy");
        Map<String, Object> commandEncodedHostUrlPolicy =
                findProbe(items, "command_encoded_host_url_policy");
        Map<String, Object> commandSchemelessSensitiveUrlPolicy =
                findProbe(items, "command_schemeless_sensitive_url_policy");
        Map<String, Object> commandRepeatedEncodedSensitiveUrlPolicy =
                findProbe(items, "command_repeated_encoded_sensitive_url_policy");
        Map<String, Object> commandSemicolonSensitiveUrlPolicy =
                findProbe(items, "command_semicolon_sensitive_url_policy");
        Map<String, Object> commandSensitiveQueryAliasPolicy =
                findProbe(items, "command_sensitive_query_alias_policy");
        Map<String, Object> commandCurlConnectToPolicy =
                findProbe(items, "command_curl_connect_to_policy");
        Map<String, Object> commandCurlResolvePolicy =
                findProbe(items, "command_curl_resolve_policy");
        Map<String, Object> commandCurlDohPolicy = findProbe(items, "command_curl_doh_policy");
        Map<String, Object> commandCurlDnsServersPolicy =
                findProbe(items, "command_curl_dns_servers_policy");
        Map<String, Object> commandPreproxyUrlPolicy =
                findProbe(items, "command_preproxy_url_policy");
        Map<String, Object> commandProxyOptionUrlPolicy =
                findProbe(items, "command_proxy_option_url_policy");
        Map<String, Object> commandProxyServerUrlPolicy =
                findProbe(items, "command_proxy_server_url_policy");
        Map<String, Object> commandJavaProxyPropertyPolicy =
                findProbe(items, "command_java_proxy_property_policy");
        Map<String, Object> commandJavaProxyOptionsPolicy =
                findProbe(items, "command_java_proxy_options_policy");
        Map<String, Object> commandProxyEnvPolicy =
                findProbe(items, "command_proxy_env_policy");
        Map<String, Object> commandProxyEnvSetitemPolicy =
                findProbe(items, "command_proxy_env_setitem_policy");
        Map<String, Object> commandProxyEnvSetenvironmentPolicy =
                findProbe(items, "command_proxy_env_setenvironment_policy");
        Map<String, Object> commandProxyEnvSetxPolicy =
                findProbe(items, "command_proxy_env_setx_policy");
        Map<String, Object> commandProxyBypassPolicy =
                findProbe(items, "command_proxy_bypass_policy");
        Map<String, Object> commandProxyBypassSetenvironmentPolicy =
                findProbe(items, "command_proxy_bypass_setenvironment_policy");
        Map<String, Object> commandProxyBypassSetxPolicy =
                findProbe(items, "command_proxy_bypass_setx_policy");
        Map<String, Object> commandPersistentProxyPolicy =
                findProbe(items, "command_persistent_proxy_policy");
        Map<String, Object> commandPersistentProxyAssignmentPolicy =
                findProbe(items, "command_persistent_proxy_assignment_policy");
        Map<String, Object> commandPersistentNoProxyAddPolicy =
                findProbe(items, "command_persistent_no_proxy_add_policy");
        Map<String, Object> commandPersistentProxyReplacePolicy =
                findProbe(items, "command_persistent_proxy_replace_policy");
        Map<String, Object> commandWinhttpProxyPolicy =
                findProbe(items, "command_winhttp_proxy_policy");
        Map<String, Object> commandWinhttpBypassPolicy =
                findProbe(items, "command_winhttp_bypass_policy");
        Map<String, Object> commandMacosWebProxyPolicy =
                findProbe(items, "command_macos_web_proxy_policy");
        Map<String, Object> commandMacosSocksProxyPolicy =
                findProbe(items, "command_macos_socks_proxy_policy");
        Map<String, Object> commandPackageProxyBypassPolicy =
                findProbe(items, "command_package_proxy_bypass_policy");
        Map<String, Object> commandPackageProxyBypassPowershellPolicy =
                findProbe(items, "command_package_proxy_bypass_powershell_policy");
        Map<String, Object> commandPackagePersistentProxyPolicy =
                findProbe(items, "command_package_persistent_proxy_policy");
        Map<String, Object> commandSystemDnsPolicy =
                findProbe(items, "command_system_dns_policy");
        Map<String, Object> commandRegistryProxyPolicy =
                findProbe(items, "command_registry_proxy_policy");
        Map<String, Object> commandRegistrySplitProxyPolicy =
                findProbe(items, "command_registry_split_proxy_policy");
        Map<String, Object> commandRegistryProxyOverridePolicy =
                findProbe(items, "command_registry_proxy_override_policy");
        Map<String, Object> commandRegistryInlineProxyPolicy =
                findProbe(items, "command_registry_inline_proxy_policy");
        Map<String, Object> commandLocalManagementSocket =
                findProbe(items, "command_local_management_socket");
        Map<String, Object> commandLocalManagementPipe =
                findProbe(items, "command_local_management_pipe");
        Map<String, Object> commandLocalManagementEncodedPipe =
                findProbe(items, "command_local_management_encoded_pipe");
        Map<String, Object> commandLocalManagementEntityPipe =
                findProbe(items, "command_local_management_entity_pipe");
        Map<String, Object> commandLocalManagementPowershellPipe =
                findProbe(items, "command_local_management_powershell_pipe");
        Map<String, Object> commandLocalManagementPowershellSocket =
                findProbe(items, "command_local_management_powershell_socket");
        Map<String, Object> commandLocalManagementPodmanSocket =
                findProbe(items, "command_local_management_podman_socket");
        Map<String, Object> commandLocalManagementContainerdSocket =
                findProbe(items, "command_local_management_containerd_socket");
        Map<String, Object> commandLocalManagementCriDockerdSocket =
                findProbe(items, "command_local_management_cri_dockerd_socket");
        Map<String, Object> commandLocalManagementCrioSocket =
                findProbe(items, "command_local_management_crio_socket");
        Map<String, Object> fileToolCredentialPath = findProbe(items, "file_tool_credential_path");
        Map<String, Object> fileToolEntityCredentialPath =
                findProbe(items, "file_tool_entity_credential_path");
        Map<String, Object> patchToolCredentialPath =
                findProbe(items, "patch_tool_credential_path");
        Map<String, Object> patchToolUnifiedCredentialPath =
                findProbe(items, "patch_tool_unified_credential_path");
        Map<String, Object> patchToolMoveCredentialPath =
                findProbe(items, "patch_tool_move_credential_path");
        Map<String, Object> patchToolUnifiedAddCredentialPath =
                findProbe(items, "patch_tool_unified_add_credential_path");
        Map<String, Object> commandDownloadOutputPath =
                findProbe(items, "command_download_output_path");
        Map<String, Object> commandUploadSourcePath =
                findProbe(items, "command_upload_source_path");
        Map<String, Object> commandArchiveCredentialPath =
                findProbe(items, "command_archive_credential_path");
        Map<String, Object> commandCredentialOptionPath =
                findProbe(items, "command_credential_option_path");
        Map<String, Object> commandCurlConfigCredentialPath =
                findProbe(items, "command_curl_config_credential_path");
        Map<String, Object> commandCurlCookieCredentialPath =
                findProbe(items, "command_curl_cookie_credential_path");
        Map<String, Object> commandWgetCookieCredentialPath =
                findProbe(items, "command_wget_cookie_credential_path");
        Map<String, Object> commandKubectlKubeconfigPath =
                findProbe(items, "command_kubectl_kubeconfig_path");
        Map<String, Object> commandGcloudKeyFilePath =
                findProbe(items, "command_gcloud_key_file_path");
        Map<String, Object> commandEncodedPathTraversal =
                findProbe(items, "command_encoded_path_traversal");
        Map<String, Object> commandHostsFileWrite = findProbe(items, "command_hosts_file_write");
        Map<String, Object> commandResolverFileWrite =
                findProbe(items, "command_resolver_file_write");
        Map<String, Object> commandPasswdFileWrite =
                findProbe(items, "command_passwd_file_write");
        Map<String, Object> commandShadowFileWrite =
                findProbe(items, "command_shadow_file_write");
        Map<String, Object> commandSudoersFileWrite =
                findProbe(items, "command_sudoers_file_write");
        Map<String, Object> commandSudoersDropinWrite =
                findProbe(items, "command_sudoers_dropin_write");
        Map<String, Object> commandDockerSocketWrite =
                findProbe(items, "command_docker_socket_write");
        Map<String, Object> commandRuntimeDockerSocketWrite =
                findProbe(items, "command_runtime_docker_socket_write");
        Map<String, Object> commandHomeProfileWrite =
                findProbe(items, "command_home_profile_write");
        Map<String, Object> commandSystemdUnitWrite =
                findProbe(items, "command_systemd_unit_write");
        Map<String, Object> commandBootLoaderWrite =
                findProbe(items, "command_boot_loader_write");
        Map<String, Object> commandSbinWrite =
                findProbe(items, "command_sbin_write");
        Map<String, Object> commandUsrSbinWrite =
                findProbe(items, "command_usr_sbin_write");
        Map<String, Object> commandBinWrite =
                findProbe(items, "command_bin_write");
        Map<String, Object> commandUsrBinWrite =
                findProbe(items, "command_usr_bin_write");
        Map<String, Object> commandUsrLocalBinWrite =
                findProbe(items, "command_usr_local_bin_write");
        Map<String, Object> commandUsrLocalSbinWrite =
                findProbe(items, "command_usr_local_sbin_write");
        Map<String, Object> commandPrivateEtcWrite =
                findProbe(items, "command_private_etc_write");
        Map<String, Object> commandPrivateVarWrite =
                findProbe(items, "command_private_var_write");
        Map<String, Object> commandWindowsSystemWrite =
                findProbe(items, "command_windows_system_write");
        Map<String, Object> commandWindowsProgramFilesWrite =
                findProbe(items, "command_windows_program_files_write");
        Map<String, Object> commandWindowsProgramFilesX86Write =
                findProbe(items, "command_windows_program_files_x86_write");
        Map<String, Object> commandWindowsEnvWindirWrite =
                findProbe(items, "command_windows_env_windir_write");
        Map<String, Object> commandWindowsPercentWindirWrite =
                findProbe(items, "command_windows_percent_windir_write");
        Map<String, Object> commandWindowsEnvProgramFilesWrite =
                findProbe(items, "command_windows_env_program_files_write");
        Map<String, Object> commandWindowsPercentProgramFilesWrite =
                findProbe(items, "command_windows_percent_program_files_write");
        Map<String, Object> commandWindowsBracedWindirWrite =
                findProbe(items, "command_windows_braced_windir_write");
        Map<String, Object> commandWindowsBracedProgramFilesWrite =
                findProbe(items, "command_windows_braced_program_files_write");
        Map<String, Object> commandWindowsPercentProgramFilesX86Write =
                findProbe(items, "command_windows_percent_program_files_x86_write");
        Map<String, Object> commandDevicePathRead =
                findProbe(items, "command_device_path_read");
        Map<String, Object> commandRawBlockDeviceWrite =
                findProbe(items, "command_raw_block_device_write");
        Map<String, Object> commandBarePackedIpv4Metadata =
                findProbe(items, "command_bare_packed_ipv4_metadata");
        Map<String, Object> commandBareHexIpv4Metadata =
                findProbe(items, "command_bare_hex_ipv4_metadata");
        Map<String, Object> commandBareIpv6MappedMetadata =
                findProbe(items, "command_bare_ipv6_mapped_metadata");
        Map<String, Object> commandBareIpv6ExpandedMetadata =
                findProbe(items, "command_bare_ipv6_expanded_metadata");
        Map<String, Object> commandBitsPackedIpv4Metadata =
                findProbe(items, "command_bits_packed_ipv4_metadata");
        Map<String, Object> commandCertutilPackedIpv4Metadata =
                findProbe(items, "command_certutil_packed_ipv4_metadata");
        Map<String, Object> commandNetcatMetadata = findProbe(items, "command_netcat_metadata");
        Map<String, Object> commandOpensslConnectMetadata =
                findProbe(items, "command_openssl_connect_metadata");
        Map<String, Object> schemaSanitizer = findProbe(items, "schema_sanitizer");
        Map<String, Object> mcpOAuthPolicy = findProbe(items, "mcp_oauth_policy");
        Map<String, Object> mcpToolChangePolicy = findProbe(items, "mcp_tool_change_policy");
        Map<String, Object> mcpRuntimeArgumentPolicy =
                findProbe(items, "mcp_runtime_argument_policy");
        Map<String, Object> mcpPackageSecurity = findProbe(items, "mcp_package_security");
        Map<String, Object> subprocessEnvironment = findProbe(items, "subprocess_environment");
        Map<String, Object> toolResultStorage = findProbe(items, "tool_result_storage");
        Map<String, Object> toolResultRetrievalRedaction =
                findProbe(items, "tool_result_retrieval_redaction");
        Map<String, Object> attachmentDownloadUrl = findProbe(items, "attachment_download_url");
        Map<String, Object> attachmentRedirectUrl = findProbe(items, "attachment_redirect_url");
        Map<String, Object> attachmentMediaCache = findProbe(items, "attachment_media_cache");
        Map<String, Object> attachmentTerminalPaste = findProbe(items, "attachment_terminal_paste");
        Map<String, Object> patchParserPath = findProbe(items, "patch_parser_path");
        Map<String, Object> credentialUpload = findProbe(items, "credential_upload");
        Map<String, Object> credentialClipboard = findProbe(items, "credential_clipboard");
        Map<String, Object> codeCredentialClipboard = findProbe(items, "code_credential_clipboard");
        Map<String, Object> hostFirewallDisable = findProbe(items, "host_firewall_disable");
        Map<String, Object> hostMacPolicyDisable = findProbe(items, "host_mac_policy_disable");
        Map<String, Object> hostServiceControl = findProbe(items, "host_service_control");
        Map<String, Object> hostCronChange = findProbe(items, "host_cron_change");
        Map<String, Object> hostAdminGroupChange = findProbe(items, "host_admin_group_change");
        Map<String, Object> containerPrivilegedHostMount =
                findProbe(items, "container_privileged_host_mount");
        Map<String, Object> containerSecretExposure =
                findProbe(items, "container_secret_exposure");
        Map<String, Object> kubernetesNetworkExposure =
                findProbe(items, "kubernetes_network_exposure");
        Map<String, Object> helmRepositoryChange = findProbe(items, "helm_repository_change");
        Map<String, Object> infrastructureAutoApproveApply =
                findProbe(items, "infrastructure_auto_approve_apply");
        Map<String, Object> codeExecutionSandbox = findProbe(items, "code_execution_sandbox");
        Map<String, Object> approvalSelector = findProbe(items, "approval_selector");
        Map<String, Object> approvalExpiryCleanup = findProbe(items, "approval_expiry_cleanup");
        Map<String, Object> approvalCardSelector = findProbe(items, "approval_card_selector");
        Map<String, Object> approvalCardPayload = findProbe(items, "approval_card_payload");
        Map<String, Object> approvalAuditRedaction = findProbe(items, "approval_audit_redaction");
        Map<String, Object> slashConfirmSelector = findProbe(items, "slash_confirm_selector");
        Map<String, Object> slashConfirmExpiry = findProbe(items, "slash_confirm_expiry");
        Map<String, Object> websitePolicy = findProbe(items, "website_policy_rule");
        Map<String, Object> websitePolicyNormalizedHost =
                findProbe(items, "website_policy_normalized_host");
        Map<String, Object> websitePolicyIdnSeparator =
                findProbe(items, "website_policy_idn_separator");
        Map<String, Object> websitePolicyWildcardChild =
                findProbe(items, "website_policy_wildcard_child");
        Map<String, Object> websitePolicyPrecedesCredentialQuery =
                findProbe(items, "website_policy_precedes_credential_query");
        Map<String, Object> tirithSecurity = findProbe(items, "tirith_security");
        assertThat(hardline.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hardline.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hardline.get("skipped")).isNull();
        assertThat(sudoRewrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(sudoRewrite.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(sudoRewrite.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(sudoRewrite.get("skipped")).isNull();
        assertThat(String.valueOf(sudoRewrite)).doesNotContain("dashboard-sudo-probe-secret");
        assertThat(terminal.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(terminal.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(terminal.get("skipped")).isNull();
        assertThat(terminalOutput.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(terminalOutput.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(terminalOutput.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(terminalOutput.get("skipped")).isNull();
        assertThat(String.valueOf(terminalOutput)).doesNotContain("sk-dashboardterminalprobe12345");
        assertThat(backgroundProcessGuard.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(backgroundProcessGuard.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(backgroundProcessGuard.get("skipped")).isNull();
        assertThat(String.valueOf(backgroundProcessGuard))
                .contains("Start-Process")
                .contains("systemd-run");
        assertThat(tirithSecurity.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(tirithSecurity.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(tirithSecurity.get("skipped")).isNull();
        assertThat(tirithSecurity.get("message")).isEqualTo("probe warning");
        assertThat(privateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(privateUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(privateUrl.get("skipped")).isNull();
        assertThat(loopbackUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(loopbackUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(loopbackUrl.get("skipped")).isNull();
        assertThat(String.valueOf(loopbackUrl)).contains("localhost");
        assertThat(ipv6LoopbackUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(ipv6LoopbackUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(ipv6LoopbackUrl.get("skipped")).isNull();
        assertThat(String.valueOf(ipv6LoopbackUrl)).contains("[::1]");
        assertThat(numericLoopbackUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(numericLoopbackUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(numericLoopbackUrl.get("skipped")).isNull();
        assertThat(String.valueOf(numericLoopbackUrl)).contains("2130706433");
        assertThat(ipv4MappedLoopbackUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(ipv4MappedLoopbackUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(ipv4MappedLoopbackUrl.get("skipped")).isNull();
        assertThat(String.valueOf(ipv4MappedLoopbackUrl)).contains("::ffff:127.0.0.1");
        assertThat(protocolRelativePrivateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(protocolRelativePrivateUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(protocolRelativePrivateUrl.get("skipped")).isNull();
        assertThat(String.valueOf(protocolRelativePrivateUrl)).contains("//127.0.0.1");
        assertThat(encodedPrivateHostUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(encodedPrivateHostUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(encodedPrivateHostUrl.get("skipped")).isNull();
        assertThat(String.valueOf(encodedPrivateHostUrl)).contains("%31%32%37.0.0.1");
        assertThat(unsupportedNetworkScheme.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(unsupportedNetworkScheme.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(unsupportedNetworkScheme.get("skipped")).isNull();
        assertThat(String.valueOf(unsupportedNetworkScheme))
                .contains("ftp://example.test")
                .contains("仅允许");
        assertThat(unsupportedSftpScheme.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(unsupportedSftpScheme.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(unsupportedSftpScheme.get("skipped")).isNull();
        assertThat(String.valueOf(unsupportedSftpScheme))
                .contains("sftp://example.test")
                .contains("仅允许");
        assertThat(unsupportedScpScheme.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(unsupportedScpScheme.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(unsupportedScpScheme.get("skipped")).isNull();
        assertThat(String.valueOf(unsupportedScpScheme))
                .contains("scp://example.test")
                .contains("仅允许");
        assertThat(sensitiveFragment.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(sensitiveFragment.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(sensitiveFragment.get("skipped")).isNull();
        assertThat(String.valueOf(sensitiveFragment))
                .contains("access_token=***")
                .doesNotContain("sk-dashboard-fragment-secret");
        assertThat(encodedSensitiveQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(encodedSensitiveQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(encodedSensitiveQuery.get("skipped")).isNull();
        assertThat(String.valueOf(encodedSensitiveQuery))
                .contains("api%255Fkey=***")
                .doesNotContain("sk-dashboard-encoded-secret");
        assertThat(repeatedEncodedSensitiveQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(repeatedEncodedSensitiveQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(repeatedEncodedSensitiveQuery.get("skipped")).isNull();
        assertThat(String.valueOf(repeatedEncodedSensitiveQuery))
                .contains("api%25255Fkey=***")
                .doesNotContain("dashboard-repeated-encoded-secret");
        assertThat(semicolonSensitiveQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(semicolonSensitiveQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(semicolonSensitiveQuery.get("skipped")).isNull();
        assertThat(String.valueOf(semicolonSensitiveQuery))
                .contains("client_secret=***")
                .doesNotContain("dashboard-semicolon-secret");
        assertThat(sensitiveQueryAlias.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(sensitiveQueryAlias.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(sensitiveQueryAlias.get("skipped")).isNull();
        assertThat(String.valueOf(sensitiveQueryAlias))
                .contains("api.key=***")
                .contains("private-key=***")
                .doesNotContain("dashboard-dot-secret")
                .doesNotContain("dashboard-dash-secret");
        assertThat(signedUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(signedUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(signedUrl.get("skipped")).isNull();
        assertThat(String.valueOf(signedUrl))
                .contains("Signature=***")
                .doesNotContain("dashboard-signature-secret");
        assertThat(nestedSignedUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(nestedSignedUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(nestedSignedUrl.get("skipped")).isNull();
        assertThat(String.valueOf(nestedSignedUrl))
                .doesNotContain("dashboard-nested-signature");
        assertThat(encodedUserinfoUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(encodedUserinfoUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(encodedUserinfoUrl.get("skipped")).isNull();
        assertThat(String.valueOf(encodedUserinfoUrl))
                .contains("user%253A***@")
                .doesNotContain("password");
        assertThat(schemelessUserinfoUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(schemelessUserinfoUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(schemelessUserinfoUrl.get("skipped")).isNull();
        assertThat(String.valueOf(schemelessUserinfoUrl))
                .contains("alice:***@example.test")
                .doesNotContain("dashboard-schemeless-password");
        assertThat(sensitivePathSegmentUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(sensitivePathSegmentUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(sensitivePathSegmentUrl.get("skipped")).isNull();
        assertThat(String.valueOf(sensitivePathSegmentUrl))
                .contains("[REDACTED_PATH]")
                .doesNotContain("secret123");
        assertThat(schemelessSensitiveQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(schemelessSensitiveQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(schemelessSensitiveQuery.get("skipped")).isNull();
        assertThat(String.valueOf(schemelessSensitiveQuery))
                .contains("access_token=***")
                .doesNotContain("schemeless-secret");
        assertThat(schemelessSensitivePath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(schemelessSensitivePath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(schemelessSensitivePath.get("skipped")).isNull();
        assertThat(String.valueOf(schemelessSensitivePath))
                .contains("[REDACTED_PATH]")
                .doesNotContain("schemeless-path-secret");
        assertThat(encodedSeparatorSensitiveQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(encodedSeparatorSensitiveQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(encodedSeparatorSensitiveQuery.get("skipped")).isNull();
        assertThat(String.valueOf(encodedSeparatorSensitiveQuery))
                .contains("page=***")
                .doesNotContain("separator-secret");
        assertThat(htmlEntitySensitiveQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(htmlEntitySensitiveQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(htmlEntitySensitiveQuery.get("skipped")).isNull();
        assertThat(String.valueOf(htmlEntitySensitiveQuery))
                .contains("client&#95;secret=***")
                .doesNotContain("entity-secret");
        assertThat(credentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(credentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(credentialPath.get("skipped")).isNull();
        assertThat(credentialFileName.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(credentialFileName.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(credentialFileName.get("skipped")).isNull();
        assertThat(credentialPathSuffix.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(credentialPathSuffix.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(credentialPathSuffix.get("skipped")).isNull();
        assertThat(encodedPathTraversal.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(encodedPathTraversal.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(encodedPathTraversal.get("skipped")).isNull();
        assertThat(String.valueOf(encodedPathTraversal)).contains("%252e%252e");
        assertThat(pathControlCharacter.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(pathControlCharacter.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(pathControlCharacter.get("skipped")).isNull();
        assertThat(String.valueOf(pathControlCharacter)).doesNotContain("\u0000");
        assertThat(devicePathRead.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(devicePathRead.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(devicePathRead.get("skipped")).isNull();
        assertThat(String.valueOf(devicePathRead)).contains("/dev/zero");
        assertThat(rawBlockDeviceWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(rawBlockDeviceWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(rawBlockDeviceWrite.get("skipped")).isNull();
        assertThat(String.valueOf(rawBlockDeviceWrite)).contains("/dev/sda");
        assertThat(skillsHubInternalPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(skillsHubInternalPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(skillsHubInternalPath.get("skipped")).isNull();
        assertThat(String.valueOf(skillsHubInternalPath))
                .contains("[REDACTED_PATH]")
                .doesNotContain("skills/.hub");
        assertThat(commandUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUrlPolicy.get("skipped")).isNull();
        assertThat(workdirTextPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(workdirTextPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(workdirTextPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(workdirTextPolicy)).contains("workspace|bad");
        assertThat(toolArgsRepeatedEncodedSensitiveUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsRepeatedEncodedSensitiveUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsRepeatedEncodedSensitiveUrl.get("skipped")).isNull();
        assertThat(String.valueOf(toolArgsRepeatedEncodedSensitiveUrl))
                .contains("api%25255Fkey=***")
                .doesNotContain("tool-args-repeated-encoded-secret");
        assertThat(toolArgsSemicolonSensitiveUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsSemicolonSensitiveUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsSemicolonSensitiveUrl.get("skipped")).isNull();
        assertThat(String.valueOf(toolArgsSemicolonSensitiveUrl))
                .contains("client_secret=***")
                .doesNotContain("tool-args-semicolon-secret");
        assertThat(toolArgsSensitiveQueryAlias.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsSensitiveQueryAlias.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsSensitiveQueryAlias.get("skipped")).isNull();
        assertThat(String.valueOf(toolArgsSensitiveQueryAlias))
                .contains("api.key=***")
                .contains("private-key=***")
                .doesNotContain("tool-args-dot-secret")
                .doesNotContain("tool-args-dash-secret");
        assertThat(toolArgsEndpointPrivateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsEndpointPrivateUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsEndpointPrivateUrl.get("skipped")).isNull();
        assertThat(String.valueOf(toolArgsEndpointPrivateUrl)).contains("base_url");
        assertThat(toolArgsNestedEndpointPrivateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsNestedEndpointPrivateUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsNestedEndpointPrivateUrl.get("skipped")).isNull();
        assertThat(String.valueOf(toolArgsNestedEndpointPrivateUrl))
                .contains("api_url")
                .contains("localhost:8080");
        assertThat(toolArgsHostTargetPrivateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsHostTargetPrivateUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsHostTargetPrivateUrl.get("skipped")).isNull();
        assertThat(String.valueOf(toolArgsHostTargetPrivateUrl))
                .contains("proxyHost")
                .contains("localhost:8081");
        assertThat(toolResultRedirectTarget.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolResultRedirectTarget.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolResultRedirectTarget.get("skipped")).isNull();
        assertThat(String.valueOf(toolResultRedirectTarget)).contains("Location");
        assertThat(commandWebsocketUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWebsocketUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWebsocketUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandWebsocketUrlPolicy))
                .contains("websocat")
                .contains("169.254.169.254");
        assertThat(commandUnsupportedFtpUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUnsupportedFtpUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUnsupportedFtpUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandUnsupportedFtpUrlPolicy))
                .contains("ftp://example.test")
                .contains("仅允许");
        assertThat(commandUnsupportedSftpUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUnsupportedSftpUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUnsupportedSftpUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandUnsupportedSftpUrlPolicy))
                .contains("sftp://example.test")
                .contains("仅允许");
        assertThat(commandUnsupportedScpUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUnsupportedScpUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUnsupportedScpUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandUnsupportedScpUrlPolicy))
                .contains("scp://example.test")
                .contains("仅允许");
        assertThat(commandUserinfoUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUserinfoUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUserinfoUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandUserinfoUrlPolicy))
                .contains("[REDACTED_PATH]")
                .doesNotContain("dashboard-password");
        assertThat(commandSchemelessUserinfoUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSchemelessUserinfoUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSchemelessUserinfoUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandSchemelessUserinfoUrlPolicy))
                .contains("alice:***@example.test")
                .doesNotContain("dashboard-command-password");
        assertThat(commandProtocolRelativeUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProtocolRelativeUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProtocolRelativeUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProtocolRelativeUrlPolicy))
                .contains("//169.254.169.254");
        assertThat(commandEncodedHostUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandEncodedHostUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandEncodedHostUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandEncodedHostUrlPolicy)).contains("%31%36%39.254.169.254");
        assertThat(commandSchemelessSensitiveUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSchemelessSensitiveUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSchemelessSensitiveUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandSchemelessSensitiveUrlPolicy))
                .contains("api%255Fkey=***")
                .doesNotContain("command-schemeless-secret");
        assertThat(commandRepeatedEncodedSensitiveUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRepeatedEncodedSensitiveUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRepeatedEncodedSensitiveUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandRepeatedEncodedSensitiveUrlPolicy))
                .contains("api%25255Fkey=***")
                .doesNotContain("command-repeated-encoded-secret");
        assertThat(commandSemicolonSensitiveUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSemicolonSensitiveUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSemicolonSensitiveUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandSemicolonSensitiveUrlPolicy))
                .contains("client_secret=***")
                .doesNotContain("command-semicolon-secret");
        assertThat(commandSensitiveQueryAliasPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSensitiveQueryAliasPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSensitiveQueryAliasPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandSensitiveQueryAliasPolicy))
                .contains("api.key=***")
                .contains("private-key=***")
                .doesNotContain("command-dot-secret")
                .doesNotContain("command-dash-secret");
        assertThat(commandCurlConnectToPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlConnectToPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlConnectToPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandCurlConnectToPolicy))
                .contains("--connect-to")
                .contains("169.254.169.254");
        assertThat(commandCurlResolvePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlResolvePolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlResolvePolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandCurlResolvePolicy))
                .contains("--resolve")
                .contains("169.254.169.254");
        assertThat(commandCurlDohPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlDohPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlDohPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandCurlDohPolicy))
                .contains("--doh-url")
                .contains("169.254.169.254");
        assertThat(commandCurlDnsServersPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlDnsServersPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlDnsServersPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandCurlDnsServersPolicy))
                .contains("--dns-servers")
                .contains("169.254.169.254");
        assertThat(commandPreproxyUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPreproxyUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPreproxyUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPreproxyUrlPolicy)).contains("--preproxy");
        assertThat(commandProxyOptionUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyOptionUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyOptionUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyOptionUrlPolicy))
                .contains("--proxy")
                .contains("169.254.169.254");
        assertThat(commandProxyServerUrlPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyServerUrlPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyServerUrlPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyServerUrlPolicy))
                .contains("--proxy-server")
                .contains("169.254.169.254");
        assertThat(commandJavaProxyPropertyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandJavaProxyPropertyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandJavaProxyPropertyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandJavaProxyPropertyPolicy))
                .contains("proxyHost")
                .contains("169.254.169.254");
        assertThat(commandJavaProxyOptionsPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandJavaProxyOptionsPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandJavaProxyOptionsPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandJavaProxyOptionsPolicy))
                .contains("MAVEN_OPTS")
                .contains("169.254.169.254");
        assertThat(commandProxyEnvPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyEnvPolicy))
                .contains("https_proxy")
                .contains("169.254.169.254");
        assertThat(commandProxyEnvSetitemPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvSetitemPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvSetitemPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyEnvSetitemPolicy))
                .contains("Set-Item")
                .contains("169.254.169.254");
        assertThat(commandProxyEnvSetenvironmentPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvSetenvironmentPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvSetenvironmentPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyEnvSetenvironmentPolicy))
                .contains("SetEnvironmentVariable")
                .contains("metadata.google.internal");
        assertThat(commandProxyEnvSetxPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvSetxPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyEnvSetxPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyEnvSetxPolicy))
                .contains("setx")
                .contains("169.254.169.254");
        assertThat(commandProxyBypassPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyBypassPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyBypassPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyBypassPolicy))
                .contains("NO_PROXY")
                .contains("169.254.169.254");
        assertThat(commandProxyBypassSetenvironmentPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyBypassSetenvironmentPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyBypassSetenvironmentPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyBypassSetenvironmentPolicy))
                .contains("SetEnvironmentVariable")
                .contains("metadata.google.internal");
        assertThat(commandProxyBypassSetxPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyBypassSetxPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandProxyBypassSetxPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandProxyBypassSetxPolicy))
                .contains("setx")
                .contains("metadata.google.internal");
        assertThat(commandPersistentProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPersistentProxyPolicy))
                .contains("git config")
                .contains("169.254.169.254");
        assertThat(commandPersistentProxyAssignmentPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentProxyAssignmentPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentProxyAssignmentPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPersistentProxyAssignmentPolicy))
                .contains("https.proxy=")
                .contains("169.254.169.254");
        assertThat(commandPersistentNoProxyAddPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentNoProxyAddPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentNoProxyAddPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPersistentNoProxyAddPolicy))
                .contains("--add")
                .contains("metadata.google.internal");
        assertThat(commandPersistentProxyReplacePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentProxyReplacePolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPersistentProxyReplacePolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPersistentProxyReplacePolicy))
                .contains("--replace-all")
                .contains("169.254.169.254");
        assertThat(commandWinhttpProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWinhttpProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWinhttpProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandWinhttpProxyPolicy))
                .contains("winhttp")
                .contains("169.254.169.254");
        assertThat(commandWinhttpBypassPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWinhttpBypassPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWinhttpBypassPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandWinhttpBypassPolicy))
                .contains("bypass-list")
                .contains("localhost");
        assertThat(commandMacosWebProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandMacosWebProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandMacosWebProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandMacosWebProxyPolicy))
                .contains("networksetup")
                .contains("169.254.169.254");
        assertThat(commandMacosSocksProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandMacosSocksProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandMacosSocksProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandMacosSocksProxyPolicy))
                .contains("setsocksfirewallproxy")
                .contains("metadata.google.internal");
        assertThat(commandPackageProxyBypassPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPackageProxyBypassPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPackageProxyBypassPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPackageProxyBypassPolicy))
                .contains("PNPM_***")
                .contains("pnpm install")
                .contains("metadata.google.internal");
        assertThat(commandPackageProxyBypassPowershellPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPackageProxyBypassPowershellPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPackageProxyBypassPowershellPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPackageProxyBypassPowershellPolicy))
                .contains("$env:NPM_***")
                .contains("169.254.169.254");
        assertThat(commandPackagePersistentProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPackagePersistentProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPackagePersistentProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandPackagePersistentProxyPolicy))
                .contains("pip config")
                .contains("169.254.169.254");
        assertThat(commandSystemDnsPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSystemDnsPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSystemDnsPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandSystemDnsPolicy))
                .contains("Set-DnsClientServerAddress")
                .contains("169.254.169.254");
        assertThat(commandRegistryProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistryProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistryProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandRegistryProxyPolicy))
                .contains("ProxyServer")
                .contains("169.254.169.254");
        assertThat(commandRegistrySplitProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistrySplitProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistrySplitProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandRegistrySplitProxyPolicy))
                .contains("ProxyServer")
                .contains("metadata.google.internal");
        assertThat(commandRegistryProxyOverridePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistryProxyOverridePolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistryProxyOverridePolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandRegistryProxyOverridePolicy))
                .contains("ProxyOverride")
                .contains("metadata.google.internal");
        assertThat(commandRegistryInlineProxyPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistryInlineProxyPolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRegistryInlineProxyPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(commandRegistryInlineProxyPolicy))
                .contains("New-ItemProperty")
                .contains("169.254.169.254:8080");
        assertThat(commandLocalManagementSocket.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementSocket.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementSocket.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementSocket))
                .contains("DOCKER_HOST")
                .contains("/var/run/docker.sock");
        assertThat(commandLocalManagementPipe.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPipe.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPipe.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementPipe))
                .contains("DOCKER_HOST")
                .contains("docker_engine");
        assertThat(commandLocalManagementEncodedPipe.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementEncodedPipe.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementEncodedPipe.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementEncodedPipe)).contains("docker%255fengine");
        assertThat(commandLocalManagementEntityPipe.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementEntityPipe.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementEntityPipe.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementEntityPipe)).contains("docker&#95;engine");
        assertThat(commandLocalManagementPowershellPipe.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPowershellPipe.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPowershellPipe.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementPowershellPipe))
                .contains("SetEnvironmentVariable")
                .contains("docker_engine");
        assertThat(commandLocalManagementPowershellSocket.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPowershellSocket.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPowershellSocket.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementPowershellSocket))
                .contains("DOCKER_HOST")
                .contains("/var/run/docker.sock");
        assertThat(commandLocalManagementPodmanSocket.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPodmanSocket.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementPodmanSocket.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementPodmanSocket))
                .contains("CONTAINER_HOST")
                .contains("/run/podman/podman.sock");
        assertThat(commandLocalManagementContainerdSocket.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementContainerdSocket.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementContainerdSocket.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementContainerdSocket))
                .contains("CONTAINER_HOST")
                .contains("/run/containerd/containerd.sock");
        assertThat(commandLocalManagementCriDockerdSocket.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementCriDockerdSocket.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementCriDockerdSocket.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementCriDockerdSocket))
                .contains("CONTAINER_HOST")
                .contains("/var/run/cri-dockerd.sock");
        assertThat(commandLocalManagementCrioSocket.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementCrioSocket.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandLocalManagementCrioSocket.get("skipped")).isNull();
        assertThat(String.valueOf(commandLocalManagementCrioSocket))
                .contains("CONTAINER_HOST")
                .contains("/var/run/crio/crio.sock");
        assertThat(fileToolCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(fileToolCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(fileToolCredentialPath.get("skipped")).isNull();
        assertThat(fileToolEntityCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(fileToolEntityCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(fileToolEntityCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(fileToolEntityCredentialPath))
                .contains("[REDACTED_PATH]")
                .doesNotContain("client&#95;secret.json");
        assertThat(patchToolCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(patchToolCredentialPath))
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env");
        assertThat(patchToolUnifiedCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolUnifiedCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolUnifiedCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(patchToolUnifiedCredentialPath))
                .contains("[REDACTED_PATH]")
                .doesNotContain(".ssh/authorized_keys");
        assertThat(patchToolMoveCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolMoveCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolMoveCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(patchToolMoveCredentialPath))
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env.local");
        assertThat(patchToolUnifiedAddCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolUnifiedAddCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(patchToolUnifiedAddCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(patchToolUnifiedAddCredentialPath))
                .contains("[REDACTED_PATH]")
                .doesNotContain(".env");
        assertThat(commandDownloadOutputPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandDownloadOutputPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandDownloadOutputPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandDownloadOutputPath))
                .contains("curl")
                .contains("[REDACTED_PATH]");
        assertThat(commandUploadSourcePath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUploadSourcePath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUploadSourcePath.get("skipped")).isNull();
        assertThat(String.valueOf(commandUploadSourcePath))
                .contains("upload-file")
                .contains("[REDACTED_PATH]");
        assertThat(commandArchiveCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandArchiveCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandArchiveCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandArchiveCredentialPath))
                .contains("tar")
                .contains("[REDACTED_PATH]");
        assertThat(commandCredentialOptionPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCredentialOptionPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCredentialOptionPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandCredentialOptionPath.get("target")))
                .contains("ssh")
                .contains("[REDACTED_PATH]")
                .doesNotContain("deploy_key");
        assertThat(commandCurlConfigCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlConfigCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlConfigCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandCurlConfigCredentialPath.get("target")))
                .contains("curl")
                .contains("[REDACTED_PATH]")
                .doesNotContain(".curlrc");
        assertThat(commandCurlCookieCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlCookieCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCurlCookieCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandCurlCookieCredentialPath.get("target")))
                .contains("-b")
                .contains("[REDACTED_PATH]")
                .doesNotContain("cookies.txt");
        assertThat(commandWgetCookieCredentialPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWgetCookieCredentialPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWgetCookieCredentialPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandWgetCookieCredentialPath.get("target")))
                .contains("load-cookies")
                .contains("[REDACTED_PATH]")
                .doesNotContain("cookies.txt");
        assertThat(commandKubectlKubeconfigPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandKubectlKubeconfigPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandKubectlKubeconfigPath.get("skipped")).isNull();
        assertThat(String.valueOf(commandKubectlKubeconfigPath.get("target")))
                .contains("kubectl")
                .contains("--kubeconfig")
                .contains("[REDACTED_PATH]")
                .doesNotContain(" kubeconfig get");
        assertThat(commandGcloudKeyFilePath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandGcloudKeyFilePath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandGcloudKeyFilePath.get("skipped")).isNull();
        assertThat(String.valueOf(commandGcloudKeyFilePath.get("target")))
                .contains("gcloud")
                .contains("[REDACTED_PATH]")
                .doesNotContain("service.json");
        assertThat(commandEncodedPathTraversal.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandEncodedPathTraversal.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandEncodedPathTraversal.get("skipped")).isNull();
        assertThat(String.valueOf(commandEncodedPathTraversal))
                .contains("cat")
                .contains("%252e%252e");
        assertThat(commandHostsFileWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandHostsFileWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandHostsFileWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandHostsFileWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/hosts");
        assertThat(commandResolverFileWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandResolverFileWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandResolverFileWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandResolverFileWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/resolv.conf");
        assertThat(commandPasswdFileWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPasswdFileWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPasswdFileWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandPasswdFileWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/passwd");
        assertThat(commandShadowFileWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandShadowFileWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandShadowFileWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandShadowFileWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/shadow");
        assertThat(commandSudoersFileWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSudoersFileWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSudoersFileWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandSudoersFileWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/sudoers");
        assertThat(commandSudoersDropinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSudoersDropinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSudoersDropinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandSudoersDropinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/sudoers.d/probe");
        assertThat(commandDockerSocketWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandDockerSocketWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandDockerSocketWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandDockerSocketWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/var/run/docker.sock");
        assertThat(commandRuntimeDockerSocketWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRuntimeDockerSocketWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRuntimeDockerSocketWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandRuntimeDockerSocketWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/run/docker.sock");
        assertThat(commandHomeProfileWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandHomeProfileWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandHomeProfileWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandHomeProfileWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain(".bashrc");
        assertThat(commandSystemdUnitWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSystemdUnitWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSystemdUnitWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandSystemdUnitWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/etc/systemd/system/probe.service");
        assertThat(commandBootLoaderWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBootLoaderWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBootLoaderWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandBootLoaderWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/boot/probe.cfg");
        assertThat(commandSbinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandSbinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandSbinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandSbinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/sbin/probe");
        assertThat(commandUsrSbinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrSbinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrSbinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandUsrSbinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/usr/sbin/probe");
        assertThat(commandBinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandBinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/bin/probe");
        assertThat(commandUsrBinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrBinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrBinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandUsrBinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/usr/bin/probe");
        assertThat(commandUsrLocalBinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrLocalBinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrLocalBinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandUsrLocalBinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/usr/local/bin/probe");
        assertThat(commandUsrLocalSbinWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrLocalSbinWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandUsrLocalSbinWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandUsrLocalSbinWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/usr/local/sbin/probe");
        assertThat(commandPrivateEtcWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPrivateEtcWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPrivateEtcWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandPrivateEtcWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/private/etc/probe.conf");
        assertThat(commandPrivateVarWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandPrivateVarWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandPrivateVarWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandPrivateVarWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("/private/var/db/probe");
        assertThat(commandWindowsSystemWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsSystemWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsSystemWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsSystemWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("C:/Windows/System32/drivers/etc/hosts");
        assertThat(commandWindowsProgramFilesWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsProgramFilesWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsProgramFilesWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsProgramFilesWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("C:/Program Files/Probe/probe.txt");
        assertThat(commandWindowsProgramFilesX86Write.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsProgramFilesX86Write.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsProgramFilesX86Write.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsProgramFilesX86Write.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("C:/Program Files (x86)/Probe/probe.txt");
        assertThat(commandWindowsEnvWindirWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsEnvWindirWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsEnvWindirWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsEnvWindirWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("$env:windir/System32/probe.txt");
        assertThat(commandWindowsPercentWindirWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsPercentWindirWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsPercentWindirWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsPercentWindirWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("%windir%/System32/probe.txt");
        assertThat(commandWindowsEnvProgramFilesWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsEnvProgramFilesWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsEnvProgramFilesWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsEnvProgramFilesWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("$env:ProgramFiles/Probe/probe.txt");
        assertThat(commandWindowsPercentProgramFilesWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsPercentProgramFilesWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsPercentProgramFilesWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsPercentProgramFilesWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("%ProgramFiles%/Probe/probe.txt");
        assertThat(commandWindowsBracedWindirWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsBracedWindirWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsBracedWindirWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsBracedWindirWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("${windir}/System32/probe.txt");
        assertThat(commandWindowsBracedProgramFilesWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsBracedProgramFilesWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsBracedProgramFilesWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsBracedProgramFilesWrite.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("${programfiles}/Probe/probe.txt");
        assertThat(commandWindowsPercentProgramFilesX86Write.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsPercentProgramFilesX86Write.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandWindowsPercentProgramFilesX86Write.get("skipped")).isNull();
        assertThat(String.valueOf(commandWindowsPercentProgramFilesX86Write.get("target")))
                .contains("[REDACTED_PATH]")
                .doesNotContain("%ProgramFiles(x86)%/Probe/probe.txt");
        assertThat(commandDevicePathRead.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandDevicePathRead.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandDevicePathRead.get("skipped")).isNull();
        assertThat(String.valueOf(commandDevicePathRead.get("target")))
                .contains("cat")
                .contains("/dev/zero");
        assertThat(commandRawBlockDeviceWrite.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandRawBlockDeviceWrite.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandRawBlockDeviceWrite.get("skipped")).isNull();
        assertThat(String.valueOf(commandRawBlockDeviceWrite.get("target")))
                .contains("dd")
                .contains("/dev/sda");
        assertThat(commandBarePackedIpv4Metadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBarePackedIpv4Metadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBarePackedIpv4Metadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandBarePackedIpv4Metadata)).contains("2852039166");
        assertThat(commandBareHexIpv4Metadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBareHexIpv4Metadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBareHexIpv4Metadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandBareHexIpv4Metadata)).contains("0xa9fea9fe");
        assertThat(commandBareIpv6MappedMetadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBareIpv6MappedMetadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBareIpv6MappedMetadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandBareIpv6MappedMetadata))
                .contains("::ffff:169.254.169.254");
        assertThat(commandBareIpv6ExpandedMetadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBareIpv6ExpandedMetadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBareIpv6ExpandedMetadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandBareIpv6ExpandedMetadata))
                .contains("0:0:0:0:0:ffff:a9fe:a9fe");
        assertThat(commandBitsPackedIpv4Metadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandBitsPackedIpv4Metadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandBitsPackedIpv4Metadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandBitsPackedIpv4Metadata))
                .contains("Start-BitsTransfer")
                .contains("0xa9fea9fe");
        assertThat(commandCertutilPackedIpv4Metadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandCertutilPackedIpv4Metadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandCertutilPackedIpv4Metadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandCertutilPackedIpv4Metadata))
                .contains("certutil")
                .contains("2852039166");
        assertThat(commandNetcatMetadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandNetcatMetadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandNetcatMetadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandNetcatMetadata)).contains("nc 169.254.169.254 80");
        assertThat(commandOpensslConnectMetadata.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(commandOpensslConnectMetadata.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(commandOpensslConnectMetadata.get("skipped")).isNull();
        assertThat(String.valueOf(commandOpensslConnectMetadata))
                .contains("openssl")
                .contains("169.254.169.254:443");
        assertThat(schemaSanitizer.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(schemaSanitizer.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(schemaSanitizer.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(schemaSanitizer.get("skipped")).isNull();
        assertThat(mcpOAuthPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpOAuthPolicy.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpOAuthPolicy.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(mcpOAuthPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(mcpOAuthPolicy))
                .contains("authorization_endpoint")
                .contains("token_endpoint");
        assertThat(mcpToolChangePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpToolChangePolicy.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpToolChangePolicy.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(mcpToolChangePolicy.get("skipped")).isNull();
        assertThat(String.valueOf(mcpToolChangePolicy))
                .contains("tool_changed_notification")
                .contains("provider_cache");
        assertThat(mcpRuntimeArgumentPolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpRuntimeArgumentPolicy.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpRuntimeArgumentPolicy.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(mcpRuntimeArgumentPolicy.get("skipped")).isNull();
        assertThat(String.valueOf(mcpRuntimeArgumentPolicy))
                .contains("tool args")
                .contains("resource uri");
        assertThat(mcpPackageSecurity.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpPackageSecurity.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(mcpPackageSecurity.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(mcpPackageSecurity.get("skipped")).isNull();
        assertThat(String.valueOf(mcpPackageSecurity))
                .doesNotContain("sk-dashboardmcppackageprobe12345");
        assertThat(subprocessEnvironment.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(subprocessEnvironment.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(subprocessEnvironment.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(subprocessEnvironment.get("skipped")).isNull();
        assertThat(toolResultStorage.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolResultStorage.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(toolResultStorage.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(toolResultStorage.get("skipped")).isNull();
        assertThat(toolResultRetrievalRedaction.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(toolResultRetrievalRedaction.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(toolResultRetrievalRedaction.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(toolResultRetrievalRedaction.get("skipped")).isNull();
        assertThat(String.valueOf(toolResultRetrievalRedaction))
                .doesNotContain("sk-dashboardtoolresultreadprobe12345");
        assertThat(attachmentDownloadUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentDownloadUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentDownloadUrl.get("skipped")).isNull();
        assertThat(String.valueOf(attachmentDownloadUrl))
                .contains("169.254.169.254")
                .contains("token=***")
                .doesNotContain("dashboard-probe-secret");
        assertThat(attachmentRedirectUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentRedirectUrl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentRedirectUrl.get("skipped")).isNull();
        assertThat(String.valueOf(attachmentRedirectUrl))
                .contains("169.254.169.254")
                .contains("token=***")
                .doesNotContain("dashboard-redirect-probe-secret");
        assertThat(attachmentMediaCache.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentMediaCache.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentMediaCache.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(attachmentMediaCache.get("skipped")).isNull();
        assertThat(String.valueOf(attachmentMediaCache))
                .doesNotContain("sk-dashboardattachmentprobe12345");
        assertThat(attachmentTerminalPaste.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentTerminalPaste.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(attachmentTerminalPaste.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(attachmentTerminalPaste.get("skipped")).isNull();
        assertThat(String.valueOf(attachmentTerminalPaste))
                .doesNotContain("ghp-dashboardterminalpasteprobe12345");
        assertThat(patchParserPath.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(patchParserPath.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(patchParserPath.get("skipped")).isNull();
        assertThat(credentialUpload.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(credentialUpload.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(credentialClipboard.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(credentialClipboard.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(codeCredentialClipboard.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(codeCredentialClipboard.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hostFirewallDisable.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostFirewallDisable.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hostFirewallDisable.get("skipped")).isNull();
        assertThat(String.valueOf(hostFirewallDisable)).contains("ufw disable");
        assertThat(hostMacPolicyDisable.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostMacPolicyDisable.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hostMacPolicyDisable.get("skipped")).isNull();
        assertThat(String.valueOf(hostMacPolicyDisable)).contains("setenforce 0");
        assertThat(hostServiceControl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostServiceControl.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hostServiceControl.get("skipped")).isNull();
        assertThat(String.valueOf(hostServiceControl)).contains("systemctl stop sshd");
        assertThat(hostCronChange.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostCronChange.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hostCronChange.get("skipped")).isNull();
        assertThat(String.valueOf(hostCronChange)).contains("crontab -e");
        assertThat(hostAdminGroupChange.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostAdminGroupChange.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(hostAdminGroupChange.get("skipped")).isNull();
        assertThat(String.valueOf(hostAdminGroupChange)).contains("usermod -aG sudo deploy");
        assertThat(containerPrivilegedHostMount.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(containerPrivilegedHostMount.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(containerPrivilegedHostMount.get("skipped")).isNull();
        assertThat(String.valueOf(containerPrivilegedHostMount)).contains("--privileged");
        assertThat(containerSecretExposure.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(containerSecretExposure.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(containerSecretExposure.get("skipped")).isNull();
        assertThat(String.valueOf(containerSecretExposure)).contains("--secret");
        assertThat(kubernetesNetworkExposure.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(kubernetesNetworkExposure.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(kubernetesNetworkExposure.get("skipped")).isNull();
        assertThat(String.valueOf(kubernetesNetworkExposure)).contains("--address 0.0.0.0");
        assertThat(helmRepositoryChange.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(helmRepositoryChange.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(helmRepositoryChange.get("skipped")).isNull();
        assertThat(String.valueOf(helmRepositoryChange)).contains("helm repo add");
        assertThat(infrastructureAutoApproveApply.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(infrastructureAutoApproveApply.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(infrastructureAutoApproveApply.get("skipped")).isNull();
        assertThat(String.valueOf(infrastructureAutoApproveApply)).contains("-auto-approve");
        assertThat(codeExecutionSandbox.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(codeExecutionSandbox.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(codeExecutionSandbox.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(codeExecutionSandbox.get("skipped")).isNull();
        assertThat(String.valueOf(codeExecutionSandbox))
                .doesNotContain("sk-dashboardcodesandboxprobe12345");
        assertThat(approvalSelector.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(approvalSelector.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalSelector.get("skipped")).isNull();
        assertThat(approvalExpiryCleanup.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(approvalExpiryCleanup.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalExpiryCleanup.get("skipped")).isNull();
        assertThat(approvalCardSelector.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(approvalCardSelector.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalCardSelector.get("skipped")).isNull();
        assertThat(approvalCardPayload.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(approvalCardPayload.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalCardPayload.get("skipped")).isNull();
        assertThat(approvalAuditRedaction.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(approvalAuditRedaction.get("allowed")).isEqualTo(Boolean.TRUE);
        assertThat(approvalAuditRedaction.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(approvalAuditRedaction.get("skipped")).isNull();
        assertThat(String.valueOf(approvalAuditRedaction))
                .doesNotContain("sk-dashboardapprovalauditprobe12345");
        assertThat(slashConfirmSelector.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(slashConfirmSelector.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(slashConfirmSelector.get("skipped")).isNull();
        assertThat(slashConfirmExpiry.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(slashConfirmExpiry.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(slashConfirmExpiry.get("skipped")).isNull();
        assertThat(websitePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicy.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicy.get("skipped")).isNull();
        assertThat(websitePolicyNormalizedHost.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyNormalizedHost.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyNormalizedHost.get("skipped")).isNull();
        assertThat(String.valueOf(websitePolicyNormalizedHost))
                .contains("WWW.Blocked.Example")
                .contains("token=***")
                .doesNotContain("dashboard-website-normalized-secret");
        assertThat(websitePolicyIdnSeparator.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyIdnSeparator.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyIdnSeparator.get("skipped")).isNull();
        assertThat(String.valueOf(websitePolicyIdnSeparator))
                .contains("blocked")
                .contains("token=***")
                .doesNotContain("dashboard-website-idn-secret");
        assertThat(websitePolicyWildcardChild.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyWildcardChild.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyWildcardChild.get("skipped")).isNull();
        assertThat(String.valueOf(websitePolicyWildcardChild))
                .contains("child.blocked.example")
                .contains("token=***")
                .doesNotContain("dashboard-website-wildcard-secret");
        assertThat(websitePolicyPrecedesCredentialQuery.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyPrecedesCredentialQuery.get("blocked")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicyPrecedesCredentialQuery.get("skipped")).isNull();
        assertThat(String.valueOf(websitePolicyPrecedesCredentialQuery))
                .contains("blocked.example")
                .contains("token=***")
                .doesNotContain("dashboard-website-token-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipWebsitePolicyProbeWhenWebsiteBlocklistHasNoRules() {
        AppConfig config = new AppConfig();
        File runtimeHome = new File("target/dashboard-security-probes-skip").getAbsoluteFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(runtimeHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(runtimeHome, "logs").getAbsolutePath());
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

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> security = (Map<String, Object>) diagnostics.get("security");
        Map<String, Object> probes = (Map<String, Object>) security.get("probes");
        assertThat(probes.get("passed")).isEqualTo(Boolean.TRUE);
        List<Map<String, Object>> items = (List<Map<String, Object>>) probes.get("items");
        Map<String, Object> websitePolicy = findProbe(items, "website_policy_rule");
        assertThat(websitePolicy.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(websitePolicy.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(websitePolicy.get("skipped")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipPrivateUrlProbeWhenPrivateUrlsAreAllowed() {
        AppConfig config = new AppConfig();
        config.getSecurity().setAllowPrivateUrls(true);
        File runtimeHome = new File("target/dashboard-private-url-probes-skip").getAbsoluteFile();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(runtimeHome, "state.db").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(runtimeHome, "logs").getAbsolutePath());
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

        Map<String, Object> diagnostics = diagnosticsService.diagnostics();

        Map<String, Object> security = (Map<String, Object>) diagnostics.get("security");
        Map<String, Object> probes = (Map<String, Object>) security.get("probes");
        assertThat(probes.get("passed")).isEqualTo(Boolean.TRUE);
        List<Map<String, Object>> items = (List<Map<String, Object>>) probes.get("items");
        Map<String, Object> privateUrl = findProbe(items, "private_url");
        Map<String, Object> nestedEndpoint =
                findProbe(items, "tool_args_nested_endpoint_private_url");
        Map<String, Object> hostTarget =
                findProbe(items, "tool_args_host_target_private_url");
        assertThat(privateUrl.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(privateUrl.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(privateUrl.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(nestedEndpoint.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(nestedEndpoint.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(nestedEndpoint.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(hostTarget.get("passed")).isEqualTo(Boolean.TRUE);
        assertThat(hostTarget.get("blocked")).isEqualTo(Boolean.FALSE);
        assertThat(hostTarget.get("skipped")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMatchSudoRewriteDiagnosticsForExplicitEmptyPassword() throws Exception {
        AppConfig config = new AppConfig();
        config.getTerminal().setSudoPassword("");
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
        Map<String, Object> terminal = (Map<String, Object>) policy.get("terminal");
        Map<String, Object> sudoPolicy =
                (Map<String, Object>) terminal.get("sudoRewritePolicy");
        assertThat(terminal.get("sudoPasswordConfigured")).isEqualTo(Boolean.TRUE);
        assertThat(sudoPolicy.get("configured")).isEqualTo(Boolean.TRUE);
        assertThat(sudoPolicy.get("stdinPasswordInjection")).isEqualTo(Boolean.TRUE);
        assertThat(sudoPolicy.get("passwordRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(ONode.serialize(result)).doesNotContain("sudoPassword\":\"\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeApprovalRuleSourcesAndPermanentDisableReason() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-approval");
        record.setSourceKey("source-approval");
        record.setTitle("审批会话");
        record.setBranchName("main");
        record.setUpdatedAt(1700000000000L);

        SqliteAgentSession securityScanSession = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                securityScanSession,
                "execute_shell",
                "tirith:homograph_url",
                "Security scan warn: unicode URL",
                "curl https://example.com");
        DangerousCommandApprovalService.PendingApproval pending =
                approvalService.getPendingApproval(securityScanSession);
        assertThat(pending).isNotNull();

        SessionRecord localRecord = new SessionRecord();
        localRecord.setSessionId("session-local-approval");
        localRecord.setSourceKey("source-local-approval");
        localRecord.setTitle("本地规则审批会话");
        localRecord.setBranchName("main");
        localRecord.setUpdatedAt(1700000000001L);
        SqliteAgentSession localSession = new SqliteAgentSession(localRecord);
        approvalService.storePendingApproval(
                localSession,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");

        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        config,
                        new FixedDeliveryService(null),
                        new LlmProviderService(config),
                        new FixedToolRegistry(),
                        new FixedSessionRepository(Arrays.asList(record, localRecord)),
                        null,
                        null,
                        null,
                        null,
                        approvalService,
                        new SecurityPolicyService(config),
                        null);

        Map<String, Object> result = diagnosticsService.pendingApprovals(10);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        Map<String, Object> item = findApprovalItem(items, "session-approval");
        Map<String, Object> localItem = findApprovalItem(items, "session-local-approval");

        assertThat((List<String>) item.get("rule_sources"))
                .containsExactly("security_scan");
        assertThat((List<String>) item.get("scope_options")).containsExactly("once", "session");
        assertThat(item.get("permanent_allowed")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(item.get("permanent_disabled_reason"))).contains("安全扫描");
        assertThat((List<String>) localItem.get("rule_sources")).containsExactly("local_policy");
        assertThat(localItem.get("permanent_allowed")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(localItem.get("permanent_disabled_reason"))).isEmpty();
        assertThat(localItem).doesNotContainKey("approval_key");
        assertThat(String.valueOf(localItem.get("selector")))
                .isEqualTo(String.valueOf(localItem.get("approval_id")))
                .doesNotContain("execute_shell:");
        assertThat(localItem.get("command_hash")).isEqualTo("***");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("action", "policy");
        Map<String, Object> audit = diagnosticsService.securityAudit(body);
        Map<String, Object> policy = (Map<String, Object>) audit.get("policy");
        Map<String, Object> approvals = (Map<String, Object>) policy.get("approvals");
        Map<String, Object> approvalPolicy = (Map<String, Object>) approvals.get("approvalPolicy");
        assertThat(approvalPolicy.get("urlPolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalPolicy.get("privateUrlPolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalPolicy.get("credentialUrlPolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalPolicy.get("websitePolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(approvalPolicy.get("unsafeUrlBlockedBeforeApproval")).isEqualTo(Boolean.TRUE);
        assertThat(approvalPolicy.get("unsafeUrlApprovalBypassAllowed")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(approvalPolicy.get("secretStoreRuleSamples")))
                .contains("secret_store_read")
                .contains("secret_store_destroy");
        Map<String, Object> approvalsCronPolicy =
                (Map<String, Object>) approvals.get("cronApprovalPolicy");
        assertThat(approvalsCronPolicy.get("scriptContentChecked")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsSubagentPolicy =
                (Map<String, Object>) approvals.get("subagentApprovalPolicy");
        assertThat(approvalsSubagentPolicy.get("terminalGuardrailPrechecked")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsSmartPolicy =
                (Map<String, Object>) approvals.get("smartApprovalPolicy");
        assertThat(approvalsSmartPolicy.get("tirithFindingsIncluded")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsScanPolicy =
                (Map<String, Object>) approvals.get("tirithApprovalPolicy");
        assertThat(approvalsScanPolicy.get("descriptionRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsSlashPolicy =
                (Map<String, Object>) approvals.get("slashConfirmPolicy");
        assertThat(approvalsSlashPolicy.get("approveAllSupported")).isEqualTo(Boolean.TRUE);
        assertThat(approvalsSlashPolicy.get("pendingListUsesSafeSelector")).isEqualTo(Boolean.TRUE);
        assertThat(approvalsSlashPolicy.get("approvalMetadataRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsCardPolicy =
                (Map<String, Object>) approvals.get("approvalCardPolicy");
        assertThat(approvalsCardPolicy.get("approvalIdSelectorSupported")).isEqualTo(Boolean.TRUE);
        assertThat(approvalsCardPolicy.get("rawCommandRedactedInExtras")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsAuditPolicy =
                (Map<String, Object>) approvals.get("auditLogPolicy");
        assertThat(approvalsAuditPolicy.get("manualRevocationAudited")).isEqualTo(Boolean.TRUE);
        assertThat(approvalsAuditPolicy.get("approvalKeyRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> approvalsMcpReloadPolicy =
                (Map<String, Object>) approvals.get("mcpReloadPolicy");
        assertThat(approvalsMcpReloadPolicy.get("persistentDisableSupported")).isEqualTo(Boolean.TRUE);
        assertThat(approvalsMcpReloadPolicy.get("encodedUrlParameterRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> coverage = (Map<String, Object>) policy.get("coverage");
        Map<String, Object> coverageApprovalPolicy =
                (Map<String, Object>) coverage.get("dangerousCommandApprovalPolicy");
        assertThat(coverageApprovalPolicy.get("urlPolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(coverageApprovalPolicy.get("privateUrlPolicyPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(coverageApprovalPolicy.get("unsafeUrlApprovalBypassAllowed")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(coverageApprovalPolicy.get("secretStoreRuleSamples")))
                .contains("secret_store_read")
                .contains("secret_store_destroy");
        Map<String, Object> hardlinePolicy = (Map<String, Object>) coverage.get("hardlinePolicy");
        assertThat(hardlinePolicy.get("ruleCount")).isEqualTo(approvalPolicy.get("hardlineRuleCount"));
        assertThat(hardlinePolicy.get("approvalBypassAllowed")).isEqualTo(Boolean.FALSE);
        assertThat(hardlinePolicy.get("commandPreviewRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> terminalGuardrailPolicy =
                (Map<String, Object>) coverage.get("terminalGuardrailPolicy");
        assertThat(terminalGuardrailPolicy.get("downloadOutputPathPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(terminalGuardrailPolicy.get("proxyUrlPrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(terminalGuardrailPolicy.get("sudoPasswordRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(terminalGuardrailPolicy.get("codeToolShellExtractionCovered")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(terminalGuardrailPolicy.get("codeToolShellSources")))
                .contains("execute_python")
                .contains("execute_js");
        Map<String, Object> credentialMountPolicyDetails =
                (Map<String, Object>) coverage.get("credentialMountPolicyDetails");
        assertThat(credentialMountPolicyDetails.get("runtimeRelativeOnly")).isEqualTo(Boolean.TRUE);
        assertThat(credentialMountPolicyDetails.get("absolutePathRejected")).isEqualTo(Boolean.TRUE);
        assertThat(credentialMountPolicyDetails.get("rejectedPathsRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> smartApprovalPolicy =
                (Map<String, Object>) coverage.get("smartApprovalPolicy");
        assertThat(smartApprovalPolicy.get("hardlinePrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(smartApprovalPolicy.get("commandPreviewRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> cronApprovalPolicy =
                (Map<String, Object>) coverage.get("cronApprovalPolicyDetails");
        assertThat(cronApprovalPolicy.get("hardlineAlwaysBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(cronApprovalPolicy.get("scriptContentChecked")).isEqualTo(Boolean.TRUE);
        Map<String, Object> subagentApprovalPolicy =
                (Map<String, Object>) coverage.get("subagentApprovalPolicyDetails");
        assertThat(subagentApprovalPolicy.get("hardlinePrechecked")).isEqualTo(Boolean.TRUE);
        assertThat(subagentApprovalPolicy.get("pendingApprovalCreatedWhenDenied")).isEqualTo(Boolean.FALSE);
        Map<String, Object> sudoRewritePolicy = (Map<String, Object>) coverage.get("sudoRewritePolicy");
        assertThat(sudoRewritePolicy.get("passwordRedacted")).isEqualTo(Boolean.TRUE);
        Map<String, Object> terminalOutputPolicy =
                (Map<String, Object>) coverage.get("terminalOutputPolicy");
        assertThat(terminalOutputPolicy.get("emptySuccessMessage")).isEqualTo("执行成功");
        assertThat(terminalOutputPolicy.get("oscSequencesStripped")).isEqualTo(Boolean.TRUE);
        assertThat(terminalOutputPolicy.get("bidiControlsStripped")).isEqualTo(Boolean.TRUE);
        assertThat(terminalOutputPolicy.get("exitCodeSemanticsAvailable")).isEqualTo(Boolean.TRUE);
        Map<String, Object> backgroundProcessPolicy =
                (Map<String, Object>) coverage.get("backgroundProcessPolicy");
        assertThat(backgroundProcessPolicy.get("startHardlineBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(backgroundProcessPolicy.get("stdinExecutionPayloadChecked")).isEqualTo(Boolean.TRUE);
        assertThat(backgroundProcessPolicy.get("stdinPrivilegeWrapperDetection")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(backgroundProcessPolicy.get("stdinExecutionTools")))
                .contains("execute_shell")
                .contains("execute_python");
        assertThat(String.valueOf(backgroundProcessPolicy.get("stdinWrapperFamilies")))
                .contains("sudo")
                .contains("nohup");
        Map<String, Object> toolArgsPolicy = (Map<String, Object>) coverage.get("toolArgsPolicy");
        assertThat(toolArgsPolicy.get("networkUploadSourcePathChecked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsPolicy.get("networkUploadCredentialOnlyBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsPolicy.get("systemDnsCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsPolicy.get("setxProxyEnvironmentChecked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsPolicy.get("systemProxyCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsPolicy.get("windowsRegistryProxyCommandChecked")).isEqualTo(Boolean.TRUE);
        assertThat(toolArgsPolicy.get("gitPersistentProxyConfigChecked")).isEqualTo(Boolean.TRUE);
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
                "rm -rf runtime/cache");
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
                    "rm -rf runtime/cache-" + i);
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
    void shouldReturnStructuredResolveApprovalFailureWhenDependenciesUnavailable() throws Exception {
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
        Map<String, Object> missingSessionResult =
                missingSessionRepository.resolveApproval(body);
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
        Map<String, Object> missingApprovalResult =
                missingApprovalService.resolveApproval(body);
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

        Map<String, Object> revokeResult = missingApprovalService.revokeAlwaysApproval(
                Collections.singletonMap("approvalId", "approval-missing-service"));
        assertThat(revokeResult.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(revokeResult.get("code")).isEqualTo("approval_unavailable");
    }

    @Test
    void shouldReturnStructuredApprovalHistoryFailureWhenRepositoryUnavailable()
            throws Exception {
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
    void shouldReturnStructuredSlashConfirmFailureWhenServiceUnavailable()
            throws Exception {
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
                "rm -rf runtime/cache --token ghp_pendingcommand123\u202E");

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
                .contains("command_preview\":\"rm -rf runtime/cache --token ***")
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
                .contains("\"command_preview\":\"/reload-mcp https://example.test/callback?api%255Fkey=***\"")
                .contains("\"prompt_preview\":\"确认执行 https://example.test/callback?api%255Fkey=***\"")
                .doesNotContain("slash-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseOpaqueSelectorForLegacyApprovalWithoutApprovalId() throws Exception {
        AppConfig config = new AppConfig();
        DangerousCommandApprovalService approvalService =
                new DangerousCommandApprovalService(
                        null, config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-legacy-approval");
        record.setSourceKey("source-legacy-approval");
        record.setTitle("旧审批会话");

        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
        DangerousCommandApprovalService.PendingApproval pending =
                approvalService.getPendingApproval(session);
        String approvalKey = pending.approvalKey();
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.put("toolName", pending.getToolName());
        legacy.put("patternKey", pending.getPatternKey());
        legacy.put("patternKeys", pending.effectivePatternKeys());
        legacy.put("description", pending.getDescription());
        legacy.put("command", pending.getCommand());
        legacy.put("commandHash", pending.getCommandHash());
        legacy.put("approvalKey", approvalKey);
        legacy.put("createdAt", Long.valueOf(pending.getCreatedAt()));
        legacy.put("expiresAt", Long.valueOf(pending.getExpiresAt()));
        List<Map<String, Object>> queue = new ArrayList<Map<String, Object>>();
        queue.add(legacy);
        session.getContext().put("_dangerous_command_pending_queue_", queue);
        session.getContext().put("_dangerous_command_pending_", legacy);
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
        body.put("sessionId", "session-legacy\u202E-approval");
        body.put("approvalId", selector.substring(0, 8) + "\u202E" + selector.substring(8));
        body.put("action", "deny");
        body.put("resume", Boolean.FALSE);
        Map<String, Object> resolve = diagnosticsService.resolveApproval(body);

        assertThat(resolve.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(approvalService.listPendingApprovals(record)).isEmpty();
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
                        new MemoryGlobalSettingRepository(), config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-revoke");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
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
        assertThat(event.getApprovalKey())
                .isEqualTo("execute_shell:***");
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
                        new MemoryGlobalSettingRepository(), config, new SecurityPolicyService(config));
        SessionRecord record = new SessionRecord();
        record.setSessionId("session-reject-raw-revoke");
        SqliteAgentSession session = new SqliteAgentSession(record);
        approvalService.storePendingApproval(
                session,
                "execute_shell",
                "recursive_delete",
                "recursive delete",
                "rm -rf runtime/cache");
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
        assertThat(approvalService.isAlwaysApproved("execute_shell", "recursive_delete", "rm -rf runtime/cache"))
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

    private static Map<String, Object> findProbe(
            List<Map<String, Object>> items, String key) {
        for (Map<String, Object> item : items) {
            if (key.equals(item.get("key"))) {
                return item;
            }
        }
        throw new AssertionError("security probe not found: " + key);
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

    private static class FixedDeliveryService implements DeliveryService {
        private final ChannelStatus status;

        private FixedDeliveryService(ChannelStatus status) {
            this.status = status;
        }

        @Override
        public void deliver(DeliveryRequest request) {}

        @Override
        public List<ChannelStatus> statuses() {
            return status == null ? Collections.<ChannelStatus>emptyList() : Collections.singletonList(status);
        }
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
        public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
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
