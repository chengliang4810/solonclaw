package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.tui.TuiEvent;
import com.jimuqu.solon.claw.tui.TuiRunProjector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiRunProjectorTest {
    @Test
    void shouldReplayPersistedRunTimelineAfterSeq() {
        FakeRunRepository repository = new FakeRunRepository();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-1");
        run.setSessionId("session-1");
        run.setSourceKey("MEMORY:tui:session-1");
        run.setStatus("running");
        run.setPhase("tool");
        run.setStartedAt(100L);
        run.setLastActivityAt(120L);
        repository.runs.add(run);

        AgentRunEventRecord event = new AgentRunEventRecord();
        event.setEventId("event-1");
        event.setRunId("run-1");
        event.setSessionId("session-1");
        event.setEventType("tool.start");
        event.setSummary("调用工具：read_file");
        event.setCreatedAt(110L);
        repository.events.add(event);

        ToolCallRecord tool = new ToolCallRecord();
        tool.setToolCallId("tool-1");
        tool.setRunId("run-1");
        tool.setSessionId("session-1");
        tool.setToolName("read_file");
        tool.setStatus("completed");
        tool.setStartedAt(111L);
        tool.setResultPreview("ok");
        repository.tools.add(tool);

        TuiRunProjector projector = new TuiRunProjector(repository);

        List<TuiEvent> all = projector.replaySession("session-1", "MEMORY:tui:session-1", 0L);
        List<TuiEvent> afterRun = projector.replaySession("session-1", "MEMORY:tui:session-1", 100001L);

        assertThat(all).extracting(TuiEvent::getType).contains("run.snapshot", "run.event", "tool.call");
        assertThat(afterRun).extracting(TuiEvent::getType).doesNotContain("run.snapshot");
        assertThat(afterRun).extracting(TuiEvent::getType).contains("run.event", "tool.call");
    }

    @Test
    void shouldClampNegativeContextTokenSentinelInRunSnapshot() {
        FakeRunRepository repository = new FakeRunRepository();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-context");
        run.setSessionId("session-context");
        run.setSourceKey("MEMORY:tui:session-context");
        run.setStatus("running");
        run.setContextEstimateTokens(-1);
        run.setContextWindowTokens(-200000);
        run.setStartedAt(100L);
        repository.runs.add(run);

        TuiRunProjector projector = new TuiRunProjector(repository);

        List<TuiEvent> events =
                projector.replaySession("session-context", "MEMORY:tui:session-context", 0L);

        TuiEvent snapshot =
                events.stream()
                        .filter(event -> "run.snapshot".equals(event.getType()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        assertThat(snapshot.getPayload().get("context_estimate_tokens"))
                .isEqualTo(Integer.valueOf(0));
        assertThat(snapshot.getPayload().get("context_window_tokens"))
                .isEqualTo(Integer.valueOf(0));
    }

    private static class FakeRunRepository implements AgentRunRepository {
        private final List<AgentRunRecord> runs = new ArrayList<AgentRunRecord>();
        private final List<AgentRunEventRecord> events = new ArrayList<AgentRunEventRecord>();
        private final List<ToolCallRecord> tools = new ArrayList<ToolCallRecord>();

        @Override
        public void saveRun(AgentRunRecord record) {}

        @Override
        public AgentRunRecord findRun(String runId) {
            for (AgentRunRecord run : runs) {
                if (runId.equals(run.getRunId())) {
                    return run;
                }
            }
            return null;
        }

        @Override
        public List<AgentRunRecord> listBySession(String sessionId, int limit) {
            return runs;
        }

        @Override
        public List<AgentRunRecord> listFinishedWithUsage(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listRecoverable(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AgentRunRecord> listActiveBefore(long beforeEpochMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void markStaleRuns(long beforeEpochMillis, long now) {}

        @Override
        public List<AgentRunRecord> listActiveBySource(String sourceKey, int limit) {
            return runs;
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
            return events;
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
        public void markQueuedMessage(String queueId, String status, long timestamp, String error) {}

        @Override
        public void saveToolCall(ToolCallRecord record) {}

        @Override
        public List<ToolCallRecord> listToolCalls(String runId) {
            return tools;
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
}
