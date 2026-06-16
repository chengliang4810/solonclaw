package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.proactive.collector.RunStateCollector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Agent 运行状态主动观测采集器测试，覆盖失败、可恢复、验证失败与排队等待信号。 */
public class RunStateCollectorTest {
    /** 测试使用的固定当前时间，避免回看窗口判断随真实时间漂移。 */
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void shouldEmitRecoverableObservationWithRedactedStructuredPayload() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-recoverable", "recoverable");
        run.setRecoverable(true);
        run.setRecoveryHint("继续前请刷新 token=secret-token-1234567890");
        run.setError("Authorization: Bearer sk-test-abcdefghijklmnopqrstuvwxyz");
        repository.recentRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        ProactiveObservation observation = observationOfType(observations, "run_recoverable");
        assertThat(observation.getCollector()).isEqualTo("run_state");
        assertThat(observation.getStatus()).isEqualTo("COLLECTED");
        assertThat(observation.getSourceKey()).isEqualTo("source-run-recoverable");
        assertThat(observation.getPayload()).containsEntry("type", "run_recoverable");
        assertThat(observation.getPayload()).containsEntry("runId", "run-recoverable");
        assertThat(observation.getPayload()).containsEntry("recoverable", Boolean.TRUE);
        assertThat(String.valueOf(observation.getPayload().get("recoveryHint")))
                .contains("token=***");
        assertThat(String.valueOf(observation.getPayload().get("error")))
                .contains("Authorization: Bearer ***");
    }

    @Test
    void shouldNotEmitForFinishedSuccessfulRun() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-success", "completed");
        run.setExitReason("success");
        run.setFinishedAt(NOW - 1_000L);
        run.setFinalReplyPreview("已完成并验证通过");
        repository.recentRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldNotEmitVerificationFailureForSuccessfulVerificationText() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-success-verify", "success");
        run.setExitReason("success");
        run.setFinishedAt(NOW - 1_000L);
        run.setInputPreview("请运行 mvn test 并做 verification");
        run.setFinalReplyPreview("verification passed，测试通过");
        repository.recentRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldNotEmitRecoverableForSuccessfulFinishedRunWithStaleRecoveryHint()
            throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-success-recoverable", "success");
        run.setExitReason("success");
        run.setFinishedAt(NOW - 1_000L);
        run.setRecoverable(true);
        run.setRecoveryHint("旧的可恢复提示，已经成功收尾");
        repository.recentRuns.add(run);
        repository.recoverableRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldInspectSameRunOnlyOnceWhenReturnedByMultipleSources() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-duplicate", "recoverable");
        run.setRecoverable(true);
        repository.recentRuns.add(run);
        repository.recoverableRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).extracting(item -> item.getPayload().get("type"))
                .containsExactly("run_recoverable");
        assertThat(repository.toolCallReads).containsEntry("run-duplicate", Integer.valueOf(1));
        assertThat(repository.queuedReads)
                .containsEntry("source-run-duplicate|session-run-duplicate", Integer.valueOf(1));
    }

    @Test
    void shouldEmitVerificationFailureWithFailedToolCalls() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-verify", "failed");
        run.setError("测试失败：mvn test returned 1");
        run.setFinalReplyPreview("verification failed after build check");
        repository.recentRuns.add(run);
        repository.toolCalls.put(
                "run-verify",
                Arrays.asList(
                        tool("tool-1", "shell", "error", "mvn test", "测试失败 token=secret-token-1234567890"),
                        tool("tool-2", "read_file", "success", "读取文件", "")));

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).extracting(item -> item.getPayload().get("type"))
                .contains("verification_failed");
        ProactiveObservation verification = observationOfType(observations, "verification_failed");
        assertThat(verification.getPayload()).containsEntry("runId", "run-verify");
        assertThat(verification.getPayload()).containsKey("toolErrors");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolErrors =
                (List<Map<String, Object>>) verification.getPayload().get("toolErrors");
        assertThat(toolErrors).hasSize(1);
        assertThat(toolErrors.get(0)).containsEntry("toolCallId", "tool-1");
        assertThat(toolErrors.get(0)).containsEntry("toolName", "shell");
        assertThat(String.valueOf(toolErrors.get(0).get("error"))).contains("token=***");
    }

    @Test
    void shouldEmitQueuedWorkWaitingWhenBusyRunHasQueuedMessages() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-running", "running");
        run.setBusyPolicy("queue");
        repository.recentRuns.add(run);
        repository.queuedCounts.put("source-run-running|session-run-running", Integer.valueOf(2));

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        ProactiveObservation queued = observationOfType(observations, "queued_work_waiting");
        assertThat(queued.getPayload()).containsEntry("queuedMessages", Integer.valueOf(2));
    }

    @Test
    void shouldNotEmitQueuedWorkForSuccessfulFinishedRunEvenWhenQueueCountExists()
            throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-success-queue", "success");
        run.setFinishedAt(NOW - 1_000L);
        run.setExitReason("success");
        repository.recentRuns.add(run);
        repository.queuedCounts.put(
                "source-run-success-queue|session-run-success-queue", Integer.valueOf(3));

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).isEmpty();
    }

    @Test
    void shouldEmitFailedRunNeedsFollowup() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-failed", "error");
        run.setExitReason("exception");
        run.setError("Unhandled exception from tool execution");
        repository.recentRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        ProactiveObservation failed = observationOfType(observations, "run_failed_needs_followup");
        assertThat(failed.getPayload()).containsEntry("runId", "run-failed");
        assertThat(failed.getSummary()).contains("run_failed_needs_followup");
    }

    @Test
    void shouldTreatFinishedFailedAsFailureNotSuccessfulFinish() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-finished-failed", "finished_failed");
        run.setFinishedAt(NOW - 1_000L);
        run.setExitReason("failed");
        repository.recentRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        ProactiveObservation failed = observationOfType(observations, "run_failed_needs_followup");
        assertThat(failed.getPayload()).containsEntry("runId", "run-finished-failed");
    }

    @Test
    void shouldEmitFollowupForInterruptedRun() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentRunRecord run = run("run-interrupted", "interrupting");
        run.setExitReason("busy_interrupt");
        run.setFinalReplyPreview("运行被打断，等待后续处理");
        repository.recentRuns.add(run);

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        ProactiveObservation failed = observationOfType(observations, "run_failed_needs_followup");
        assertThat(failed.getPayload()).containsEntry("runId", "run-interrupted");
        assertThat(failed.getPayload()).containsEntry("exitReason", "busy_interrupt");
    }

    @Test
    void shouldHandleNullRepositoryReturnsDefensively() throws Exception {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        repository.returnNullLists = true;

        List<ProactiveObservation> observations =
                new RunStateCollector(repository).collect(context());

        assertThat(observations).isEmpty();
    }

    /** 构造启用主动协作和运行回看窗口的测试 tick 上下文。 */
    private static ProactiveTickContext context() {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(true);
        config.getProactive().setRunLookbackDays(14);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setConfig(config);
        context.setNowMillis(NOW);
        context.setTickId("tick-run-state");
        return context;
    }

    /** 构造带有稳定来源和会话标识的运行记录，便于验证 payload 结构。 */
    private static AgentRunRecord run(String runId, String status) {
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId(runId);
        run.setSessionId("session-" + runId);
        run.setSourceKey("source-" + runId);
        run.setStatus(status);
        run.setPhase("agent_loop");
        run.setInputPreview("请继续处理本次任务");
        run.setLastActivityAt(NOW - 5_000L);
        return run;
    }

    /** 构造工具调用记录，用于验证失败工具调用证据会被纳入观测载荷。 */
    private static ToolCallRecord tool(
            String toolCallId, String toolName, String status, String argsPreview, String error) {
        ToolCallRecord toolCall = new ToolCallRecord();
        toolCall.setToolCallId(toolCallId);
        toolCall.setRunId("run-verify");
        toolCall.setSessionId("session-run-verify");
        toolCall.setSourceKey("source-run-verify");
        toolCall.setToolName(toolName);
        toolCall.setStatus(status);
        toolCall.setArgsPreview(argsPreview);
        toolCall.setError(error);
        toolCall.setFinishedAt(NOW - 2_000L);
        return toolCall;
    }

    /** 从观测列表中按类型取出目标观测，缺失时让断言输出完整列表。 */
    private static ProactiveObservation observationOfType(
            List<ProactiveObservation> observations, String type) {
        assertThat(observations).extracting(item -> item.getPayload().get("type")).contains(type);
        for (ProactiveObservation observation : observations) {
            if (type.equals(observation.getPayload().get("type"))) {
                return observation;
            }
        }
        throw new AssertionError("missing observation type: " + type);
    }

    /** 面向采集器测试的内存 Agent 运行仓储，只实现本任务会调用的查询方法。 */
    private static final class InMemoryAgentRunRepository implements AgentRunRepository {
        /** searchRuns 返回的近期运行记录。 */
        private final List<AgentRunRecord> recentRuns = new ArrayList<AgentRunRecord>();

        /** listRecoverable 返回的可恢复运行记录。 */
        private final List<AgentRunRecord> recoverableRuns = new ArrayList<AgentRunRecord>();

        /** 按 runId 保存的工具调用记录。 */
        private final Map<String, List<ToolCallRecord>> toolCalls =
                new HashMap<String, List<ToolCallRecord>>();

        /** 按 sourceKey 和 sessionId 保存的待处理消息数量。 */
        private final Map<String, Integer> queuedCounts = new HashMap<String, Integer>();

        /** 按 runId 记录工具调用查询次数，用于验证同一运行不会重复检查。 */
        private final Map<String, Integer> toolCallReads = new HashMap<String, Integer>();

        /** 按 sourceKey 和 sessionId 记录排队消息查询次数，用于验证候选运行去重。 */
        private final Map<String, Integer> queuedReads = new HashMap<String, Integer>();

        /** 是否模拟仓储返回 null 列表，验证采集器防御式处理。 */
        private boolean returnNullLists;

        @Override
        public List<AgentRunRecord> searchRuns(
                String sourceKey,
                String sessionId,
                String runId,
                String query,
                long timeFrom,
                long timeTo,
                int limit) {
            if (returnNullLists) {
                return null;
            }
            return recentRuns;
        }

        @Override
        public List<AgentRunRecord> listRecoverable(int limit) {
            if (returnNullLists) {
                return null;
            }
            return recoverableRuns;
        }

        @Override
        public List<ToolCallRecord> listToolCalls(String runId) {
            if (returnNullLists) {
                return null;
            }
            toolCallReads.put(
                    runId,
                    Integer.valueOf(
                            toolCallReads.getOrDefault(runId, Integer.valueOf(0)).intValue()
                                    + 1));
            return toolCalls.getOrDefault(runId, Collections.<ToolCallRecord>emptyList());
        }

        @Override
        public int countQueuedMessages(String sourceKey, String sessionId) {
            String key = sourceKey + "|" + sessionId;
            queuedReads.put(
                    key,
                    Integer.valueOf(
                            queuedReads.getOrDefault(key, Integer.valueOf(0)).intValue() + 1));
            return queuedCounts.getOrDefault(key, Integer.valueOf(0));
        }

        @Override
        public void saveRun(AgentRunRecord record) {
            throw unsupported();
        }

        @Override
        public AgentRunRecord findRun(String runId) {
            throw unsupported();
        }

        @Override
        public List<AgentRunRecord> listBySession(String sessionId, int limit) {
            throw unsupported();
        }

        @Override
        public List<AgentRunRecord> listFinishedWithUsage(int limit) {
            throw unsupported();
        }

        @Override
        public List<AgentRunRecord> listActiveBefore(long beforeEpochMillis, int limit) {
            throw unsupported();
        }

        @Override
        public void markStaleRuns(long beforeEpochMillis, long now) {
            throw unsupported();
        }

        @Override
        public List<AgentRunRecord> listActiveBySource(String sourceKey, int limit) {
            throw unsupported();
        }

        @Override
        public void appendEvent(AgentRunEventRecord event) {
            throw unsupported();
        }

        @Override
        public List<AgentRunEventRecord> listEvents(String runId) {
            throw unsupported();
        }

        @Override
        public void saveRunControlCommand(RunControlCommand command) {
            throw unsupported();
        }

        @Override
        public List<RunControlCommand> listRunControlCommands(String runId) {
            throw unsupported();
        }

        @Override
        public RunControlCommand findLatestPendingCommand(String runId, String command) {
            throw unsupported();
        }

        @Override
        public void markRunControlCommandHandled(String commandId, String status, long handledAt) {
            throw unsupported();
        }

        @Override
        public void saveQueuedMessage(QueuedRunMessage message) {
            throw unsupported();
        }

        @Override
        public QueuedRunMessage findNextQueuedMessage(String sourceKey, String sessionId) {
            throw unsupported();
        }

        @Override
        public void markQueuedMessage(String queueId, String status, long timestamp, String error) {
            throw unsupported();
        }

        @Override
        public void saveToolCall(ToolCallRecord record) {
            throw unsupported();
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
            throw unsupported();
        }

        @Override
        public void saveSubagentRun(SubagentRunRecord record) {
            throw unsupported();
        }

        @Override
        public List<SubagentRunRecord> listSubagents(String parentRunId) {
            throw unsupported();
        }

        @Override
        public void saveRecovery(RunRecoveryRecord record) {
            throw unsupported();
        }

        @Override
        public List<RunRecoveryRecord> listRecoveries(String runId) {
            throw unsupported();
        }

        @Override
        public void pruneBefore(long beforeEpochMillis) {
            throw unsupported();
        }

        /** 返回未参与本任务的仓储方法异常，避免测试误调用未覆盖路径。 */
        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("测试仓储未实现该方法");
        }
    }
}
