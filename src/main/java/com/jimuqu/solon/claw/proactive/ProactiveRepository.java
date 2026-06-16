package com.jimuqu.solon.claw.proactive;

import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
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

    /** 保存决策记录。 */
    void saveDecision(ProactiveDecisionRecord decision) throws Exception;

    /** 统计窗口内成功发送次数。 */
    int countSentSince(String sourceKey, long sinceMillis) throws Exception;

    /** 查询最近一次成功发送时间。 */
    Long findLastSentAt(String sourceKey) throws Exception;

    /** 查询来源快照。 */
    ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef) throws Exception;

    /** 保存来源快照。 */
    void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) throws Exception;

    /** 按时间倒序列出最近决策。 */
    List<ProactiveDecisionRecord> listRecentDecisions(int limit) throws Exception;
}
