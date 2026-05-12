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
                        null,
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
        assertThat(diagnosticsJson).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(diagnosticsJson).doesNotContain(externalState.getParentFile().getAbsolutePath());
        assertThat(diagnosticsJson).doesNotContain("ghp_diagnosticexternal123");
        assertThat(diagnosticsJson).contains("https://user:***@example.com/v1?token=***");
        assertThat(diagnosticsJson).doesNotContain("provider-pass");
        assertThat(diagnosticsJson).doesNotContain("provider-token");
        assertThat(diagnosticsJson).doesNotContain("sk-test-providersecret");
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
