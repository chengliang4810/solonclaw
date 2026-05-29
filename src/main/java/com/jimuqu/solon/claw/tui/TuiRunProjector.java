package com.jimuqu.solon.claw.tui;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Projects persisted Agent run state into the browser terminal event stream. */
public class TuiRunProjector {
    private static final int MAX_RUNS = 8;
    private static final int MAX_EVENTS_PER_RUN = 80;
    private static final int MAX_CHILD_ITEMS = 50;

    private final AgentRunRepository agentRunRepository;

    public TuiRunProjector(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    public List<TuiEvent> replaySession(String sessionId, String sourceKey, long afterSeq) {
        if (agentRunRepository == null || StrUtil.isBlank(sessionId)) {
            return Collections.emptyList();
        }
        List<TuiEvent> events = new ArrayList<TuiEvent>();
        try {
            List<AgentRunRecord> runs = agentRunRepository.listBySession(sessionId, MAX_RUNS);
            Collections.reverse(runs);
            for (AgentRunRecord run : runs) {
                addIfAfter(events, "run.snapshot", sessionId, runSeq(run, 1), toRun(run), afterSeq);
                addRunEvents(events, run, afterSeq);
                addToolCalls(events, run, afterSeq);
                addSubagents(events, run, afterSeq);
                addRecoveries(events, run, afterSeq);
                addCommands(events, run, afterSeq);
            }
            if (events.isEmpty() && StrUtil.isNotBlank(sourceKey)) {
                for (AgentRunRecord active : agentRunRepository.listActiveBySource(sourceKey, MAX_RUNS)) {
                    addIfAfter(
                            events, "run.snapshot", sessionId, runSeq(active, 1), toRun(active), afterSeq);
                    addRunEvents(events, active, afterSeq);
                }
            }
        } catch (Exception ignored) {
        }
        return events;
    }

    public List<TuiEvent> projectRun(String sessionId, String runId, long afterSeq) {
        if (agentRunRepository == null || StrUtil.isBlank(runId)) {
            return Collections.emptyList();
        }
        List<TuiEvent> events = new ArrayList<TuiEvent>();
        try {
            AgentRunRecord run = agentRunRepository.findRun(runId);
            if (run == null) {
                return events;
            }
            String sid = StrUtil.blankToDefault(sessionId, run.getSessionId());
            addIfAfter(events, "run.snapshot", sid, runSeq(run, 1), toRun(run), afterSeq);
            addRunEvents(events, run, afterSeq);
            addToolCalls(events, run, afterSeq);
            addSubagents(events, run, afterSeq);
            addRecoveries(events, run, afterSeq);
            addCommands(events, run, afterSeq);
        } catch (Exception ignored) {
        }
        return events;
    }

    private void addRunEvents(List<TuiEvent> events, AgentRunRecord run, long afterSeq)
            throws Exception {
        List<AgentRunEventRecord> records = agentRunRepository.listEvents(run.getRunId());
        int start = Math.max(0, records.size() - MAX_EVENTS_PER_RUN);
        for (int i = start; i < records.size(); i++) {
            AgentRunEventRecord record = records.get(i);
            long seq = eventSeq(record, i);
            addIfAfter(events, "run.event", record.getSessionId(), seq, toEvent(record), afterSeq);
        }
    }

    private void addToolCalls(List<TuiEvent> events, AgentRunRecord run, long afterSeq)
            throws Exception {
        List<ToolCallRecord> records = agentRunRepository.listToolCalls(run.getRunId());
        int start = Math.max(0, records.size() - MAX_CHILD_ITEMS);
        for (int i = start; i < records.size(); i++) {
            ToolCallRecord record = records.get(i);
            long seq = childSeq(record.getStartedAt(), i, 200);
            addIfAfter(events, "tool.call", record.getSessionId(), seq, toToolCall(record), afterSeq);
        }
    }

    private void addSubagents(List<TuiEvent> events, AgentRunRecord run, long afterSeq)
            throws Exception {
        List<SubagentRunRecord> records = agentRunRepository.listSubagents(run.getRunId());
        int start = Math.max(0, records.size() - MAX_CHILD_ITEMS);
        for (int i = start; i < records.size(); i++) {
            SubagentRunRecord record = records.get(i);
            long seq = childSeq(record.getStartedAt(), i, 300);
            addIfAfter(
                    events, "subagent.updated", record.getSessionId(), seq, toSubagent(record), afterSeq);
        }
    }

    private void addRecoveries(List<TuiEvent> events, AgentRunRecord run, long afterSeq)
            throws Exception {
        List<RunRecoveryRecord> records = agentRunRepository.listRecoveries(run.getRunId());
        int start = Math.max(0, records.size() - MAX_CHILD_ITEMS);
        for (int i = start; i < records.size(); i++) {
            RunRecoveryRecord record = records.get(i);
            long seq = childSeq(record.getCreatedAt(), i, 400);
            addIfAfter(
                    events, "recovery.updated", record.getSessionId(), seq, toRecovery(record), afterSeq);
        }
    }

    private void addCommands(List<TuiEvent> events, AgentRunRecord run, long afterSeq)
            throws Exception {
        List<RunControlCommand> records = agentRunRepository.listRunControlCommands(run.getRunId());
        int start = Math.max(0, records.size() - MAX_CHILD_ITEMS);
        for (int i = start; i < records.size(); i++) {
            RunControlCommand record = records.get(i);
            long seq = childSeq(record.getCreatedAt(), i, 500);
            addIfAfter(events, "run.control", run.getSessionId(), seq, toCommand(record), afterSeq);
        }
    }

    private void addIfAfter(
            List<TuiEvent> events,
            String type,
            String sessionId,
            long seq,
            Map<String, Object> payload,
            long afterSeq) {
        if (seq <= afterSeq) {
            return;
        }
        payload.put("event_seq", Long.valueOf(seq));
        events.add(new TuiEvent(type, sessionId, seq, seq, payload));
    }

    private long runSeq(AgentRunRecord run, int offset) {
        long base = run == null ? 0L : Math.max(run.getStartedAt(), run.getQueuedAt());
        if (base <= 0L && run != null) {
            base = Math.max(run.getLastActivityAt(), run.getFinishedAt());
        }
        return base <= 0L ? System.currentTimeMillis() + offset : base * 1000L + offset;
    }

    private long eventSeq(AgentRunEventRecord event, int offset) {
        long base = event == null ? 0L : event.getCreatedAt();
        return base <= 0L ? System.currentTimeMillis() + offset : base * 1000L + 100 + offset;
    }

    private long childSeq(long timestamp, int offset, int bucket) {
        long base = timestamp <= 0L ? System.currentTimeMillis() : timestamp;
        return base * 1000L + bucket + offset;
    }

    private Map<String, Object> toRun(AgentRunRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("run_id", safe(record.getRunId(), 160));
        map.put("session_id", safe(record.getSessionId(), 160));
        map.put("source_key", safe(record.getSourceKey(), 240));
        map.put("run_kind", safe(record.getRunKind(), 120));
        map.put("parent_run_id", safe(record.getParentRunId(), 160));
        map.put("agent_name", safe(record.getAgentName(), 160));
        map.put("status", safe(record.getStatus(), 80));
        map.put("phase", safe(record.getPhase(), 80));
        map.put("busy_policy", safe(record.getBusyPolicy(), 80));
        map.put("backgrounded", Boolean.valueOf(record.isBackgrounded()));
        map.put("input_preview", safe(record.getInputPreview(), 1200));
        map.put("final_reply_preview", safe(record.getFinalReplyPreview(), 1200));
        map.put("provider", safe(record.getProvider(), 160));
        map.put("model", safe(record.getModel(), 400));
        map.put("attempts", Integer.valueOf(record.getAttempts()));
        map.put("tool_call_count", Integer.valueOf(record.getToolCallCount()));
        map.put("subtask_count", Integer.valueOf(record.getSubtaskCount()));
        map.put("input_tokens", Long.valueOf(record.getInputTokens()));
        map.put("output_tokens", Long.valueOf(record.getOutputTokens()));
        map.put("total_tokens", Long.valueOf(record.getTotalTokens()));
        map.put("queued_at", Long.valueOf(record.getQueuedAt()));
        map.put("started_at", Long.valueOf(record.getStartedAt()));
        map.put("heartbeat_at", Long.valueOf(record.getHeartbeatAt()));
        map.put("last_activity_at", Long.valueOf(record.getLastActivityAt()));
        map.put("finished_at", Long.valueOf(record.getFinishedAt()));
        map.put("exit_reason", safe(record.getExitReason(), 400));
        map.put("recoverable", Boolean.valueOf(record.isRecoverable()));
        map.put("recovery_hint", safe(record.getRecoveryHint(), 1000));
        map.put("error", safe(record.getError(), 1200));
        return map;
    }

    private Map<String, Object> toEvent(AgentRunEventRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("event_id", safe(record.getEventId(), 160));
        map.put("run_id", safe(record.getRunId(), 160));
        map.put("session_id", safe(record.getSessionId(), 160));
        map.put("source_key", safe(record.getSourceKey(), 240));
        map.put("event_type", safe(record.getEventType(), 160));
        map.put("phase", safe(record.getPhase(), 80));
        map.put("severity", safe(record.getSeverity(), 80));
        map.put("attempt_no", Integer.valueOf(record.getAttemptNo()));
        map.put("provider", safe(record.getProvider(), 160));
        map.put("model", safe(record.getModel(), 400));
        map.put("summary", safe(record.getSummary(), 1200));
        map.put("metadata", parseJson(record.getMetadataJson()));
        map.put("created_at", Long.valueOf(record.getCreatedAt()));
        return map;
    }

    private Map<String, Object> toToolCall(ToolCallRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("tool_call_id", safe(record.getToolCallId(), 160));
        map.put("run_id", safe(record.getRunId(), 160));
        map.put("session_id", safe(record.getSessionId(), 160));
        map.put("source_key", safe(record.getSourceKey(), 240));
        map.put("tool_name", safe(record.getToolName(), 160));
        map.put("status", safe(record.getStatus(), 80));
        map.put("args_preview", safe(record.getArgsPreview(), 1200));
        map.put("result_preview", safe(record.getResultPreview(), 1200));
        map.put("result_ref", safe(record.getResultRef(), 800));
        map.put("error", safe(record.getError(), 1200));
        map.put("read_only", Boolean.valueOf(record.isReadOnly()));
        map.put("interruptible", Boolean.valueOf(record.isInterruptible()));
        map.put("side_effecting", Boolean.valueOf(record.isSideEffecting()));
        map.put("execution_policy", safe(record.getExecutionPolicy(), 120));
        map.put("started_at", Long.valueOf(record.getStartedAt()));
        map.put("finished_at", Long.valueOf(record.getFinishedAt()));
        map.put("duration_ms", Long.valueOf(record.getDurationMs()));
        return map;
    }

    private Map<String, Object> toSubagent(SubagentRunRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("subagent_id", safe(record.getSubagentId(), 160));
        map.put("parent_run_id", safe(record.getParentRunId(), 160));
        map.put("child_run_id", safe(record.getChildRunId(), 160));
        map.put("session_id", safe(record.getSessionId(), 160));
        map.put("name", safe(record.getName(), 160));
        map.put("goal_preview", safe(record.getGoalPreview(), 1200));
        map.put("status", safe(record.getStatus(), 80));
        map.put("active", Boolean.valueOf(record.isActive()));
        map.put("interrupt_requested", Boolean.valueOf(record.isInterruptRequested()));
        map.put("depth", Integer.valueOf(record.getDepth()));
        map.put("task_index", Integer.valueOf(record.getTaskIndex()));
        map.put("output_tail", parseJson(record.getOutputTailJson()));
        map.put("error", safe(record.getError(), 1200));
        map.put("started_at", Long.valueOf(record.getStartedAt()));
        map.put("finished_at", Long.valueOf(record.getFinishedAt()));
        map.put("heartbeat_at", Long.valueOf(record.getHeartbeatAt()));
        return map;
    }

    private Map<String, Object> toRecovery(RunRecoveryRecord record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("recovery_id", safe(record.getRecoveryId(), 160));
        map.put("run_id", safe(record.getRunId(), 160));
        map.put("session_id", safe(record.getSessionId(), 160));
        map.put("source_key", safe(record.getSourceKey(), 240));
        map.put("recovery_type", safe(record.getRecoveryType(), 160));
        map.put("status", safe(record.getStatus(), 80));
        map.put("summary", safe(record.getSummary(), 1200));
        map.put("payload", parseJson(record.getPayloadJson()));
        map.put("created_at", Long.valueOf(record.getCreatedAt()));
        map.put("resolved_at", Long.valueOf(record.getResolvedAt()));
        return map;
    }

    private Map<String, Object> toCommand(RunControlCommand record) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("command_id", safe(record.getCommandId(), 160));
        map.put("run_id", safe(record.getRunId(), 160));
        map.put("source_key", safe(record.getSourceKey(), 240));
        map.put("command", safe(record.getCommand(), 160));
        map.put("status", safe(record.getStatus(), 80));
        map.put("payload", parseJson(record.getPayloadJson()));
        map.put("created_at", Long.valueOf(record.getCreatedAt()));
        map.put("handled_at", Long.valueOf(record.getHandledAt()));
        return map;
    }

    private Object parseJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return ONode.deserialize(json, Object.class);
        } catch (Exception e) {
            return safe(json, 1200);
        }
    }

    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }
}
