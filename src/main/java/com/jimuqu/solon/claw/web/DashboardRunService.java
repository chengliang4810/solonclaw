package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.service.AgentRunControlService;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Dashboard Agent run 查询服务。 */
public class DashboardRunService {
    private final AgentRunRepository agentRunRepository;
    private final AgentRunControlService agentRunControlService;
    private final DelegationService delegationService;

    public DashboardRunService(AgentRunRepository agentRunRepository) {
        this(agentRunRepository, null, null);
    }

    public DashboardRunService(
            AgentRunRepository agentRunRepository, AgentRunControlService agentRunControlService) {
        this(agentRunRepository, agentRunControlService, null);
    }

    public DashboardRunService(
            AgentRunRepository agentRunRepository,
            AgentRunControlService agentRunControlService,
            DelegationService delegationService) {
        this.agentRunRepository = agentRunRepository;
        this.agentRunControlService = agentRunControlService;
        this.delegationService = delegationService;
    }

    public Map<String, Object> sessionRuns(String sessionId, int limit) throws Exception {
        List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        for (AgentRunRecord record :
                agentRunRepository.listBySession(sessionId, limit <= 0 ? 20 : limit)) {
            runs.add(toRun(record));
        }
        return Collections.singletonMap("runs", runs);
    }

    public Map<String, Object> run(String runId) throws Exception {
        AgentRunRecord record = agentRunRepository.findRun(runId);
        return record == null ? Collections.<String, Object>emptyMap() : toRun(record);
    }

    public Map<String, Object> detail(String runId) throws Exception {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("run", run(runId));
        map.put("events", events(runId).get("events"));
        map.put("tools", toolCalls(runId).get("tools"));
        map.put("subagents", subagents(runId).get("subagents"));
        map.put("recoveries", recoveries(runId).get("recoveries"));
        map.put("commands", commands(runId).get("commands"));
        return map;
    }

    public Map<String, Object> recoverable(int limit) throws Exception {
        List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        for (AgentRunRecord record : agentRunRepository.listRecoverable(limit <= 0 ? 50 : limit)) {
            runs.add(toRecoverableRun(record));
        }
        return Collections.singletonMap("runs", runs);
    }

    public Map<String, Object> activeSubagents() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(
                "subagents",
                delegationService == null
                        ? Collections.emptyList()
                        : redactParsed(delegationService.activeSubagents()));
        map.put(
                "spawn_paused",
                delegationService != null && delegationService.isSpawnPaused());
        return map;
    }

    public Map<String, Object> controlSubagent(String subagentId, String command) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("subagent_id", redact(subagentId, 1000));
        map.put("command", redact(command, 1000));
        if (delegationService == null) {
            map.put("ok", false);
            map.put("status", "delegation_unavailable");
            return map;
        }
        String normalized =
                command == null ? "" : command.trim().toLowerCase(java.util.Locale.ROOT);
        if ("pause_spawn".equals(normalized) || "pause".equals(normalized)) {
            delegationService.setSpawnPaused(true);
            map.put("ok", true);
            map.put("status", "spawn_paused");
            return map;
        }
        if ("resume_spawn".equals(normalized) || "resume".equals(normalized)) {
            delegationService.setSpawnPaused(false);
            map.put("ok", true);
            map.put("status", "spawn_resumed");
            return map;
        }
        if ("interrupt".equals(normalized)) {
            map.put("ok", delegationService.interruptSubagent(subagentId));
            map.put("status", Boolean.TRUE.equals(map.get("ok")) ? "interrupting" : "not_found");
            return map;
        }
        map.put("ok", false);
        map.put("status", "unsupported_command");
        return map;
    }

    public Map<String, Object> control(String runId, String command, Map<String, Object> payload)
            throws Exception {
        AgentRunRecord record = agentRunRepository.findRun(runId);
        if (record == null) {
            throw new IllegalArgumentException("Run not found: " + runId);
        }
        String normalized = command == null ? "" : command.trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("run_id", safeId(runId));
        result.put("command", normalized);
        if (agentRunControlService != null) {
            return agentRunControlService.controlRun(runId, normalized, payload);
        }
        result.put("ok", false);
        result.put("status", "control_unavailable");
        result.put("payload", redactParsed(payload));
        return result;
    }

    public Map<String, Object> events(String runId) throws Exception {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        for (AgentRunEventRecord event : agentRunRepository.listEvents(runId)) {
            events.add(toEvent(event));
        }
        return Collections.singletonMap("events", events);
    }

    public Map<String, Object> toolCalls(String runId) throws Exception {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        for (ToolCallRecord record : agentRunRepository.listToolCalls(runId)) {
            tools.add(toToolCall(record));
        }
        return Collections.singletonMap("tools", tools);
    }

    public Map<String, Object> subagents(String runId) throws Exception {
        List<Map<String, Object>> subagents = new ArrayList<Map<String, Object>>();
        for (SubagentRunRecord record : agentRunRepository.listSubagents(runId)) {
            subagents.add(toSubagent(record));
        }
        return Collections.singletonMap("subagents", subagents);
    }

    public Map<String, Object> recoveries(String runId) throws Exception {
        List<Map<String, Object>> recoveries = new ArrayList<Map<String, Object>>();
        for (RunRecoveryRecord record : agentRunRepository.listRecoveries(runId)) {
            recoveries.add(toRecovery(record));
        }
        return Collections.singletonMap("recoveries", recoveries);
    }

    public Map<String, Object> commands(String runId) throws Exception {
        List<Map<String, Object>> commands = new ArrayList<Map<String, Object>>();
        for (RunControlCommand record : agentRunRepository.listRunControlCommands(runId)) {
            commands.add(toCommand(record));
        }
        return Collections.singletonMap("commands", commands);
    }

    private Map<String, Object> toRun(AgentRunRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("run_id", safeId(record.getRunId()));
        map.put("session_id", safeId(record.getSessionId()));
        map.put("source_key", safeId(record.getSourceKey()));
        map.put("run_kind", record.getRunKind());
        map.put("parent_run_id", safeId(record.getParentRunId()));
        map.put("agent_name", safeId(record.getAgentName()));
        map.put(
                "agent_snapshot",
                redactParsed(
                        parseJsonField(
                                record.getAgentSnapshotJson(),
                                "agent_snapshot",
                                record.getRunId(),
                                null)));
        map.put("status", record.getStatus());
        map.put("phase", record.getPhase());
        map.put("busy_policy", record.getBusyPolicy());
        map.put("backgrounded", record.isBackgrounded());
        map.put("input_preview", redact(record.getInputPreview(), 8000));
        map.put("final_reply_preview", redact(record.getFinalReplyPreview(), 8000));
        map.put("provider", redact(record.getProvider(), 400));
        map.put("model", redact(record.getModel(), 400));
        map.put("attempts", record.getAttempts());
        map.put("context_estimate_tokens", nonNegative(record.getContextEstimateTokens()));
        map.put("context_window_tokens", nonNegative(record.getContextWindowTokens()));
        map.put("compression_count", record.getCompressionCount());
        map.put("fallback_count", record.getFallbackCount());
        map.put("tool_call_count", record.getToolCallCount());
        map.put("subtask_count", record.getSubtaskCount());
        map.put("input_tokens", record.getInputTokens());
        map.put("output_tokens", record.getOutputTokens());
        map.put("total_tokens", record.getTotalTokens());
        map.put("queued_at", record.getQueuedAt());
        map.put("started_at", record.getStartedAt());
        map.put("heartbeat_at", record.getHeartbeatAt());
        map.put("last_activity_at", record.getLastActivityAt());
        map.put("finished_at", record.getFinishedAt());
        map.put("exit_reason", record.getExitReason());
        map.put("recoverable", record.isRecoverable());
        map.put("recovery_hint", redact(record.getRecoveryHint(), 2000));
        map.put("error", redact(record.getError(), 2000));
        return map;
    }

    private Map<String, Object> toRecoverableRun(AgentRunRecord record) {
        Map<String, Object> map = toRun(record);
        map.put("recovery_diagnosis", recoveryDiagnosis(record));
        return map;
    }

    private Map<String, Object> recoveryDiagnosis(AgentRunRecord record) {
        Map<String, Object> diagnosis = new LinkedHashMap<String, Object>();
        diagnosis.put("reason", "recoverable_run_requires_operator_review");
        diagnosis.put("heartbeat_age_ms", Long.valueOf(heartbeatAge(record)));
        diagnosis.put("suggested_action", "review_recovery_hint_before_resume");
        diagnosis.put("auto_recoverable", Boolean.FALSE);
        diagnosis.put("safe_to_auto_resume", Boolean.FALSE);
        return diagnosis;
    }

    private long heartbeatAge(AgentRunRecord record) {
        long heartbeatAt = record.getHeartbeatAt();
        if (heartbeatAt <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - heartbeatAt);
    }

    private Map<String, Object> toEvent(AgentRunEventRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("event_id", safeId(record.getEventId()));
        map.put("run_id", safeId(record.getRunId()));
        map.put("session_id", safeId(record.getSessionId()));
        map.put("source_key", safeId(record.getSourceKey()));
        map.put("event_type", record.getEventType());
        map.put("phase", record.getPhase());
        map.put("severity", record.getSeverity());
        map.put("attempt_no", record.getAttemptNo());
        map.put("provider", redact(record.getProvider(), 400));
        map.put("model", redact(record.getModel(), 400));
        map.put("summary", redact(record.getSummary(), 2000));
        map.put("created_at", record.getCreatedAt());
        map.put(
                "metadata",
                redactParsed(
                        parseJsonField(
                                record.getMetadataJson(),
                                "metadata",
                                record.getRunId(),
                                record.getEventId())));
        return map;
    }

    private Integer nonNegative(int value) {
        return Integer.valueOf(Math.max(0, value));
    }

    private Map<String, Object> toToolCall(ToolCallRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("tool_call_id", safeId(record.getToolCallId()));
        map.put("run_id", safeId(record.getRunId()));
        map.put("session_id", safeId(record.getSessionId()));
        map.put("source_key", safeId(record.getSourceKey()));
        map.put("tool_name", record.getToolName());
        map.put("status", record.getStatus());
        map.put("args_preview", redact(record.getArgsPreview(), 8000));
        map.put("result_preview", redact(record.getResultPreview(), 8000));
        map.put("result_ref", redact(record.getResultRef(), 1000));
        map.put("error", redact(record.getError(), 2000));
        map.put("read_only", record.isReadOnly());
        map.put("interruptible", record.isInterruptible());
        map.put("side_effecting", record.isSideEffecting());
        map.put("result_indexable", record.isResultIndexable());
        map.put("output_limit_bytes", record.getOutputLimitBytes());
        map.put("result_size_bytes", record.getResultSizeBytes());
        map.put("execution_policy", record.getExecutionPolicy());
        map.put("started_at", record.getStartedAt());
        map.put("finished_at", record.getFinishedAt());
        map.put("duration_ms", record.getDurationMs());
        return map;
    }

    private Map<String, Object> toSubagent(SubagentRunRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("subagent_id", safeId(record.getSubagentId()));
        map.put("parent_run_id", safeId(record.getParentRunId()));
        map.put("child_run_id", safeId(record.getChildRunId()));
        map.put("parent_source_key", safeId(record.getParentSourceKey()));
        map.put("child_source_key", safeId(record.getChildSourceKey()));
        map.put("session_id", safeId(record.getSessionId()));
        map.put("name", safeId(record.getName()));
        map.put("goal_preview", redact(record.getGoalPreview(), 8000));
        map.put("status", record.getStatus());
        map.put("active", record.isActive());
        map.put("interrupt_requested", record.isInterruptRequested());
        map.put("depth", record.getDepth());
        map.put("task_index", record.getTaskIndex());
        map.put(
                "output_tail",
                redactParsed(
                        parseJsonField(
                                record.getOutputTailJson(),
                                "output_tail",
                                record.getParentRunId(),
                                null)));
        map.put("error", redact(record.getError(), 2000));
        map.put("started_at", record.getStartedAt());
        map.put("finished_at", record.getFinishedAt());
        map.put("heartbeat_at", record.getHeartbeatAt());
        return map;
    }

    private Map<String, Object> toCommand(RunControlCommand record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("command_id", safeId(record.getCommandId()));
        map.put("run_id", safeId(record.getRunId()));
        map.put("source_key", safeId(record.getSourceKey()));
        map.put("command", redact(record.getCommand(), 400));
        map.put("status", record.getStatus());
        map.put("created_at", record.getCreatedAt());
        map.put("handled_at", record.getHandledAt());
        map.put(
                "payload",
                redactParsed(
                        parseJsonField(
                                record.getPayloadJson(), "payload", record.getRunId(), null)));
        return map;
    }

    private Map<String, Object> toRecovery(RunRecoveryRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("recovery_id", safeId(record.getRecoveryId()));
        map.put("run_id", safeId(record.getRunId()));
        map.put("session_id", safeId(record.getSessionId()));
        map.put("source_key", safeId(record.getSourceKey()));
        map.put("recovery_type", record.getRecoveryType());
        map.put("status", record.getStatus());
        map.put("summary", redact(record.getSummary(), 2000));
        map.put(
                "payload",
                redactParsed(
                        parseJsonField(
                                record.getPayloadJson(), "payload", record.getRunId(), null)));
        map.put("created_at", record.getCreatedAt());
        map.put("resolved_at", record.getResolvedAt());
        return map;
    }

    private Object parseJsonField(String json, String field, String runId, String eventId) {
        if (json == null || json.trim().length() == 0) {
            return null;
        }
        try {
            return ONode.deserialize(json, Object.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("parse_error", true);
            fallback.put("field", field);
            fallback.put("message", redact(e.getMessage(), 1000));
            fallback.put("raw", redact(truncate(json, 4000), 4000));
            if (runId != null) {
                fallback.put("run_id", safeId(runId));
            }
            if (eventId != null) {
                fallback.put("event_id", safeId(eventId));
            }
            return fallback;
        }
    }

    private Object redactParsed(Object value) {
        if (value instanceof String) {
            return redact((String) value, 8000);
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> redacted = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                redacted.put(String.valueOf(entry.getKey()), redactParsed(entry.getValue()));
            }
            return redacted;
        }
        if (value instanceof Iterable) {
            List<Object> redacted = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                redacted.add(redactParsed(item));
            }
            return redacted;
        }
        return value;
    }

    private String redact(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    private String safeId(String value) {
        return SecretRedactor.redact(value, 400);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
