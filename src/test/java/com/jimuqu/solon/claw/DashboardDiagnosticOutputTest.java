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
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardGatewayDoctorService;
import java.io.File;
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
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setStateDb(new File(runtimeHome, "data/state.db").getAbsolutePath());
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
        assertThat(diagnosticsJson).contains("runtime://data/state.db");
        assertThat(diagnosticsJson).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(diagnosticsJson).contains("https://user:***@example.com/v1?token=***");
        assertThat(diagnosticsJson).doesNotContain("provider-pass");
        assertThat(diagnosticsJson).doesNotContain("provider-token");
        assertThat(diagnosticsJson).doesNotContain("sk-test-providersecret");
        assertThat(diagnosticsJson).doesNotContain("ghp_doctorerror123");
        assertThat(diagnosticsJson).doesNotContain("doctor-password");
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
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRedactApprovalHistoryOutput() throws Exception {
        AppConfig config = new AppConfig();
        ApprovalAuditEvent event = new ApprovalAuditEvent();
        event.setEventId("audit-1");
        event.setSessionId("session-audit");
        event.setEventType("response");
        event.setChoice("once");
        event.setApprover("operator token=ghp_approversecret123");
        event.setToolName("execute_shell");
        event.setApprovalId("approval-1");
        event.setApprovalKey("execute_shell:recursive_delete:hash");
        event.setCommandHash("hash");
        event.setCommandPreview("printf api_key=sk-history-secret");
        event.setDescription("history password=history-secret");
        event.setPatternKeysJson("[\"recursive_delete\"]");
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
        assertThat(json).doesNotContain("history-secret");
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
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("approval", "execute_shell:recursive_delete");
        body.put("approver", "dashboard token=ghp_revokeapprover123");

        Map<String, Object> result = diagnosticsService.revokeAlwaysApproval(body);

        assertThat(result.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(auditRepository.events).hasSize(1);
        ApprovalAuditEvent event = auditRepository.events.get(0);
        assertThat(event.getChoice()).isEqualTo("revoke");
        assertThat(event.getApprover()).doesNotContain("ghp_revokeapprover123");
        assertThat(event.getApprover()).contains("token=***");
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
            return records;
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            return records;
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
