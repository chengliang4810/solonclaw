package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import java.util.Collections;
import java.util.List;

/** 主动协作持久化仓储接口。 */
public interface ProactiveRepository {
    /** 保存单次观测结果。 */
    void saveObservation(ProactiveObservationRecord observation) throws Exception;

    /** 保存或更新候选记录。 */
    void saveCandidate(ProactiveCandidateRecord candidate) throws Exception;

    /** 根据去重键与状态哈希查找未过期候选。 */
    ProactiveCandidateRecord findRecentCandidateByDedup(
            String dedupKey, String stateHash, long nowMillis) throws Exception;

    /** 按指定时间和优先级列出仍有效的待处理候选。 */
    List<ProactiveCandidateRecord> listPendingCandidates(long nowMillis, int limit)
            throws Exception;

    /**
     * 按当前系统时间列出仍有效的待处理候选。
     *
     * @param limit 最大返回数量。
     * @return 返回待处理候选列表。
     */
    default List<ProactiveCandidateRecord> listPendingCandidates(int limit) throws Exception {
        return listPendingCandidates(System.currentTimeMillis(), limit);
    }

    /** 更新候选状态与最近决策。 */
    void markCandidateStatus(String candidateId, String status, String decisionId, long updatedAt)
            throws Exception;

    /** 仅当候选仍属于指定决策和预期状态时更新，避免用户忽略结果被并发投递覆盖。 */
    default boolean compareAndSetCandidateStatus(
            String candidateId,
            String expectedStatus,
            String expectedDecisionId,
            String status,
            long updatedAt)
            throws Exception {
        return false;
    }

    /** 按候选 ID 读取主动协作候选，供显式人工恢复使用。 */
    default ProactiveCandidateRecord findCandidate(String candidateId) throws Exception {
        return null;
    }

    /** 按决策 ID 读取投递审计记录，供人工重试复用原目标和原文案。 */
    default ProactiveDecisionRecord findDecision(String decisionId) throws Exception {
        return null;
    }

    /** 列出投递结果不确定且必须由用户决定是否重试的候选。 */
    default List<ProactiveCandidateRecord> listDeliveryUnknownCandidates(int limit)
            throws Exception {
        return Collections.emptyList();
    }

    /** 原子认领一次人工重试，并把候选绑定到新的重试决策，避免并发点击造成重复投递。 */
    default boolean claimDeliveryUnknownRetry(
            String candidateId,
            String expectedDecisionId,
            String expectedSourceKey,
            String retryDecisionId,
            long updatedAt)
            throws Exception {
        return false;
    }

    /** 保存决策记录。 */
    void saveDecision(ProactiveDecisionRecord decision) throws Exception;

    /** 恢复上次 tick 未完整收敛的已批准候选；返回修复数量。 */
    default int recoverInterruptedDeliveries(long recoveredAt) throws Exception {
        return 0;
    }

    /** 统计窗口内成功发送次数。 */
    int countSentSince(String sourceKey, long sinceMillis) throws Exception;

    /** 查询最近一次成功发送时间。 */
    Long findLastSentAt(String sourceKey) throws Exception;

    /** 查询来源快照。 */
    ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef)
            throws Exception;

    /** 保存来源快照。 */
    void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) throws Exception;

    /** 按时间倒序列出最近决策。 */
    List<ProactiveDecisionRecord> listRecentDecisions(int limit) throws Exception;
}
