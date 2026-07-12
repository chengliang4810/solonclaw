package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.model.ToolCallRecord;
import java.util.List;

/** Agent 运行轨迹仓储。 */
public interface AgentRunRepository {
    /**
     * 保存运行。
     *
     * @param record 记录参数。
     */
    void saveRun(AgentRunRecord record) throws Exception;

    /**
     * 查找运行。
     *
     * @param runId 运行标识。
     * @return 返回运行结果。
     */
    AgentRunRecord findRun(String runId) throws Exception;

    /**
     * 列出根据会话。
     *
     * @param sessionId 当前会话标识。
     * @param limit 最大返回数量。
     * @return 返回根据会话列表。
     */
    List<AgentRunRecord> listBySession(String sessionId, int limit) throws Exception;

    /**
     * 统计指定会话中已有模型用量的运行次数，用于 TUI 展示真实 API 调用数。
     *
     * @param sessionId 当前会话标识。
     * @return 返回带用量的运行次数。
     */
    default long countUsageRunsBySession(String sessionId) throws Exception {
        long count = 0L;
        for (AgentRunRecord run : listBySession(sessionId, Integer.MAX_VALUE)) {
            if (run != null
                    && (run.getInputTokens() > 0L
                            || run.getOutputTokens() > 0L
                            || run.getTotalTokens() > 0L
                            || (run.getAttempts() > 0
                                    && run.getFinishedAt() > 0L
                                    && run.getProvider() != null
                                    && !run.getProvider().trim().isEmpty()
                                    && run.getModel() != null
                                    && !run.getModel().trim().isEmpty()))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 列出Finished With用量。
     *
     * @param limit 最大返回数量。
     * @return 返回Finished With用量列表。
     */
    List<AgentRunRecord> listFinishedWithUsage(int limit) throws Exception;

    /**
     * 列出Recoverable。
     *
     * @param limit 最大返回数量。
     * @return 返回Recoverable列表。
     */
    List<AgentRunRecord> listRecoverable(int limit) throws Exception;

    /**
     * 列出Active Before。
     *
     * @param beforeEpochMillis beforeEpochMillis 参数。
     * @param limit 最大返回数量。
     * @return 返回Active Before列表。
     */
    List<AgentRunRecord> listActiveBefore(long beforeEpochMillis, int limit) throws Exception;

    /**
     * 标记Stale运行。
     *
     * @param beforeEpochMillis beforeEpochMillis 参数。
     * @param now 当前时间戳。
     */
    void markStaleRuns(long beforeEpochMillis, long now) throws Exception;

    /**
     * 列出Active根据来源。
     *
     * @param sourceKey 渠道来源键。
     * @param limit 最大返回数量。
     * @return 返回Active根据来源列表。
     */
    List<AgentRunRecord> listActiveBySource(String sourceKey, int limit) throws Exception;

    /**
     * 搜索运行。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param runId 运行标识。
     * @param query 查询参数。
     * @param timeFrom 时间From参数。
     * @param timeTo 时间To参数。
     * @param limit 最大返回数量。
     * @return 返回运行结果。
     */
    List<AgentRunRecord> searchRuns(
            String sourceKey,
            String sessionId,
            String runId,
            String query,
            long timeFrom,
            long timeTo,
            int limit)
            throws Exception;

    /**
     * 追加事件。
     *
     * @param event 事件参数。
     */
    void appendEvent(AgentRunEventRecord event) throws Exception;

    /**
     * 列出Events。
     *
     * @param runId 运行标识。
     * @return 返回Events列表。
     */
    List<AgentRunEventRecord> listEvents(String runId) throws Exception;

    /**
     * 搜索运行事件。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param runId 运行标识。
     * @param query 查询参数。
     * @param timeFrom 时间From参数。
     * @param timeTo 时间To参数。
     * @param limit 最大返回数量。
     * @return 返回运行事件结果。
     */
    List<AgentRunEventRecord> searchEvents(
            String sourceKey,
            String sessionId,
            String runId,
            String query,
            long timeFrom,
            long timeTo,
            int limit)
            throws Exception;

    /**
     * 保存运行Control命令。
     *
     * @param command 待执行或解析的命令文本。
     */
    void saveRunControlCommand(RunControlCommand command) throws Exception;

    /**
     * 列出运行Control Commands。
     *
     * @param runId 运行标识。
     * @return 返回运行Control Commands列表。
     */
    List<RunControlCommand> listRunControlCommands(String runId) throws Exception;

    /**
     * 查找Latest Pending命令。
     *
     * @param runId 运行标识。
     * @param command 待执行或解析的命令文本。
     * @return 返回Latest Pending命令结果。
     */
    RunControlCommand findLatestPendingCommand(String runId, String command) throws Exception;

    /**
     * 标记运行Control命令Handled。
     *
     * @param commandId 命令标识。
     * @param status 状态参数。
     * @param handledAt handledAt 参数。
     */
    void markRunControlCommandHandled(String commandId, String status, long handledAt)
            throws Exception;

    /**
     * 保存Queued消息。
     *
     * @param message 平台消息或错误消息。
     */
    void saveQueuedMessage(QueuedRunMessage message) throws Exception;

    /**
     * 查找Next Queued消息。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @return 返回Next Queued消息结果。
     */
    QueuedRunMessage findNextQueuedMessage(String sourceKey, String sessionId) throws Exception;

    /**
     * 仅按来源键查找Next Queued消息（不限会话），用于 goal 续轮抢占判定。
     *
     * <p>抢占检查只持有 sourceKey（如 {@code MEMORY:room:user}），而真实用户消息可能跨会话排队。 本方法忽略
     * sessionId，返回该来源键下最早入队的一条 queued 消息。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回该来源键下最早的 queued 消息；无待处理消息返回 null。
     */
    default QueuedRunMessage findNextQueuedMessageBySourceKey(String sourceKey) throws Exception {
        return null;
    }

    /**
     * 执行次数排队Messages相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @return 返回次数Queued Messages结果。
     */
    int countQueuedMessages(String sourceKey, String sessionId) throws Exception;

    /**
     * 标记Queued消息。
     *
     * @param queueId 队列标识。
     * @param status 状态参数。
     * @param timestamp 请求携带的时间戳。
     * @param error 错误参数。
     */
    void markQueuedMessage(String queueId, String status, long timestamp, String error)
            throws Exception;

    /**
     * 按预期状态原子更新排队消息，避免多个 drain 重复领取或覆盖终态。
     *
     * @param queueId 队列标识。
     * @param expectedStatus 更新前必须匹配的状态。
     * @param status 目标状态。
     * @param timestamp 状态变更时间戳。
     * @param error 失败原因；无失败时传 null。
     * @return 成功更新一条记录时返回 true。
     */
    boolean markQueuedMessage(
            String queueId, String expectedStatus, String status, long timestamp, String error)
            throws Exception;

    /**
     * 将服务异常退出前遗留的 running 队列项退回 queued，供启动恢复后重新领取。
     *
     * @param beforeEpochMillis 仅恢复早于该时间的队列项。
     * @return 恢复为 queued 的记录数量。
     */
    int requeueStaleRunningMessages(long beforeEpochMillis) throws Exception;

    /**
     * 保存工具Call。
     *
     * @param record 记录参数。
     */
    void saveToolCall(ToolCallRecord record) throws Exception;

    /**
     * 列出工具Calls。
     *
     * @param runId 运行标识。
     * @return 返回工具Calls列表。
     */
    List<ToolCallRecord> listToolCalls(String runId) throws Exception;

    /**
     * 搜索工具Calls。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param runId 运行标识。
     * @param toolName 工具名称。
     * @param query 查询参数。
     * @param timeFrom 时间From参数。
     * @param timeTo 时间To参数。
     * @param limit 最大返回数量。
     * @return 返回工具Calls结果。
     */
    List<ToolCallRecord> searchToolCalls(
            String sourceKey,
            String sessionId,
            String runId,
            String toolName,
            String query,
            long timeFrom,
            long timeTo,
            int limit)
            throws Exception;

    /**
     * 保存Subagent运行。
     *
     * @param record 记录参数。
     */
    void saveSubagentRun(SubagentRunRecord record) throws Exception;

    /**
     * 列出Subagents。
     *
     * @param parentRunId parent运行标识。
     * @return 返回Subagents列表。
     */
    List<SubagentRunRecord> listSubagents(String parentRunId) throws Exception;

    /**
     * 将当前进程无法继续控制的活动子 Agent 收敛为已中断状态。
     *
     * @param now 当前时间戳，用作遗留记录的结束时间和最后心跳时间。
     * @return 被收敛的子 Agent 记录数量。
     */
    default int markActiveSubagentsInterrupted(long now) throws Exception {
        return 0;
    }

    /**
     * 保存Recovery。
     *
     * @param record 记录参数。
     */
    void saveRecovery(RunRecoveryRecord record) throws Exception;

    /**
     * 列出Recoveries。
     *
     * @param runId 运行标识。
     * @return 返回Recoveries列表。
     */
    List<RunRecoveryRecord> listRecoveries(String runId) throws Exception;

    /**
     * 执行pruneBefore相关逻辑。
     *
     * @param beforeEpochMillis beforeEpochMillis 参数。
     */
    void pruneBefore(long beforeEpochMillis) throws Exception;
}
