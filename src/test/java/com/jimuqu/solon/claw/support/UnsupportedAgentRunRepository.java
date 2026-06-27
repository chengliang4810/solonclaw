package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import java.util.Collections;
import java.util.List;

/** 测试专用 AgentRunRepository 基类，未覆盖的方法默认抛错以暴露误调用。 */
public abstract class UnsupportedAgentRunRepository implements AgentRunRepository {
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
    public List<AgentRunRecord> listRecoverable(int limit) {
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
    public int countQueuedMessages(String sourceKey, String sessionId) {
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
    public List<ToolCallRecord> listToolCalls(String runId) {
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

    /** 返回未参与当前测试的仓储方法异常，避免测试误调用未覆盖路径。 */
    protected UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("测试仓储未实现该方法");
    }
}
