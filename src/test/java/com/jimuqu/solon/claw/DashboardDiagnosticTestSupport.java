package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
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
import com.jimuqu.solon.claw.tool.runtime.TirithSecurityService;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仪表盘诊断测试共用的轻量仓储、服务替身与断言辅助方法。 */
final class DashboardDiagnosticTestSupport {
    private DashboardDiagnosticTestSupport() {}

    static Map<String, Object> findApprovalItem(List<Map<String, Object>> items, String sessionId) {
        for (Map<String, Object> item : items) {
            if (sessionId.equals(item.get("session_id"))) {
                return item;
            }
        }
        throw new AssertionError("approval item not found: " + sessionId);
    }

    static class RedactingResumeOrchestrator implements ConversationOrchestrator {
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

    static class RedactingCommandService implements CommandService {
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

    static Map<String, Object> findProbe(List<Map<String, Object>> items, String key) {
        for (Map<String, Object> item : items) {
            if (key.equals(item.get("key"))) {
                return item;
            }
        }
        throw new AssertionError("security probe not found: " + key);
    }

    static List<String> failedProbeKeys(List<Map<String, Object>> items) {
        List<String> failures = new ArrayList<String>();
        for (Map<String, Object> item : items) {
            if (!Boolean.TRUE.equals(item.get("passed"))) {
                failures.add(String.valueOf(item));
            }
        }
        return failures;
    }

    static TirithSecurityService.ScanResult scanResult(
            String action, List<TirithSecurityService.Finding> findings, String summary)
            throws Exception {
        java.lang.reflect.Constructor<TirithSecurityService.ScanResult> constructor =
                TirithSecurityService.ScanResult.class.getDeclaredConstructor(
                        String.class, List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(action, findings, summary);
    }

    static class FixedTirithSecurityService extends TirithSecurityService {
        final TirithSecurityService.ScanResult result;

        FixedTirithSecurityService(TirithSecurityService.ScanResult result) {
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

    static AppConfig enabledTirithConfig() {
        AppConfig config = new AppConfig();
        config.getSecurity().setTirithEnabled(true);
        config.getSecurity().setTirithPath("target/dashboard-tirith-probe");
        return config;
    }

    static String latestShutdownFile(Path workspaceHome) {
        File[] files = workspaceHome.resolve("forensics").toFile().listFiles();
        assertThat(files).isNotNull();
        return Arrays.stream(files)
                .filter(file -> file.getName().startsWith("shutdown-"))
                .findFirst()
                .map(File::getName)
                .orElseThrow(IllegalStateException::new);
    }

    static class FixedDeliveryService implements DeliveryService {
        final List<ChannelStatus> statuses;

        /**
         * 创建不返回任何渠道状态的测试投递服务，避免用 null 触发可变参数调用歧义。
         *
         * @return 无渠道状态的测试投递服务。
         */
        static FixedDeliveryService empty() {
            return new FixedDeliveryService();
        }

        /**
         * 创建返回多个渠道状态的测试投递服务。
         *
         * @param statuses 测试用渠道状态列表。
         */
        FixedDeliveryService(ChannelStatus... statuses) {
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

    static List<String> checkCodes(List<Map<String, Object>> checks) {
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

    static class FixedSessionRepository implements SessionRepository {
        final List<SessionRecord> records;

        FixedSessionRepository(List<SessionRecord> records) {
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
        public void setGoalState(String sessionId, String goalStateJson) {}

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {}
    }

    static class FixedApprovalAuditRepository implements ApprovalAuditRepository {
        final List<ApprovalAuditEvent> events;

        FixedApprovalAuditRepository(List<ApprovalAuditEvent> events) {
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

    static class FixedAgentRunRepository implements AgentRunRepository {
        final List<AgentRunRecord> runs = new ArrayList<AgentRunRecord>();

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
        public boolean markQueuedMessage(
                String queueId,
                String expectedStatus,
                String status,
                long timestamp,
                String error) {
            return false;
        }

        @Override
        public int requeueStaleRunningMessages(long beforeEpochMillis) {
            return 0;
        }

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

    static class MemoryGlobalSettingRepository
            implements com.jimuqu.solon.claw.core.repository.GlobalSettingRepository {
        final Map<String, String> values = new LinkedHashMap<String, String>();

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

    static class FixedToolRegistry implements ToolRegistry {
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
