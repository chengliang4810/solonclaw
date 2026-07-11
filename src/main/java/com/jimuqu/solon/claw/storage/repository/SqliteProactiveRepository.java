package com.jimuqu.solon.claw.storage.repository;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;

/** SQLite 主动协作仓储实现。 */
@RequiredArgsConstructor
public class SqliteProactiveRepository implements ProactiveRepository {
    /** 主动协作仓储依赖的 SQLite 数据库。 */
    private final SqliteDatabase database;

    @Override
    public void saveObservation(ProactiveObservationRecord observation) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into proactive_observations (observation_id, tick_id, collector, source_key, summary, payload_json, status, error, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, observation.getObservationId());
            statement.setString(2, observation.getTickId());
            statement.setString(3, observation.getCollector());
            statement.setString(4, observation.getSourceKey());
            statement.setString(5, observation.getSummary());
            statement.setString(6, toJson(observation.getPayload()));
            statement.setString(7, observation.getStatus());
            statement.setString(8, observation.getError());
            statement.setLong(9, observation.getCreatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public void saveCandidate(ProactiveCandidateRecord candidate) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into proactive_candidates (candidate_id, source_type, source_ref, source_key, subject_type, subject_ref, topic, title, summary, reason, action_offer, evidence_json, confidence, priority, dedup_key, state_hash, created_at, expires_at, status, last_decision_id, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, candidate.getCandidateId());
            statement.setString(2, candidate.getSourceType());
            statement.setString(3, candidate.getSourceRef());
            statement.setString(4, candidate.getSourceKey());
            statement.setString(5, candidate.getSubjectType());
            statement.setString(6, candidate.getSubjectRef());
            statement.setString(7, candidate.getTopic());
            statement.setString(8, candidate.getTitle());
            statement.setString(9, candidate.getSummary());
            statement.setString(10, candidate.getReason());
            statement.setString(11, candidate.getActionOffer());
            statement.setString(12, toJson(candidate.getEvidence()));
            statement.setDouble(13, candidate.getConfidence());
            statement.setInt(14, candidate.getPriority());
            statement.setString(15, candidate.getDedupKey());
            statement.setString(16, candidate.getStateHash());
            statement.setLong(17, candidate.getCreatedAt());
            statement.setLong(18, candidate.getExpiresAt());
            statement.setString(19, StrUtil.blankToDefault(candidate.getStatus(), "PENDING"));
            statement.setString(20, candidate.getLastDecisionId());
            statement.setLong(21, candidate.getUpdatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public ProactiveCandidateRecord findRecentCandidateByDedup(
            String dedupKey, String stateHash, long nowMillis) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from proactive_candidates where dedup_key = ? and state_hash = ? and (expires_at is null or expires_at = 0 or expires_at > ?) order by created_at desc limit 1");
            statement.setString(1, dedupKey);
            statement.setString(2, stateHash);
            statement.setLong(3, nowMillis);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapCandidate(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public List<ProactiveCandidateRecord> listPendingCandidates(long nowMillis, int limit)
            throws Exception {
        List<ProactiveCandidateRecord> candidates = new ArrayList<ProactiveCandidateRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from proactive_candidates where status = 'PENDING' and (expires_at is null or expires_at = 0 or expires_at > ?) order by priority desc, created_at asc limit ?");
            statement.setLong(1, nowMillis);
            statement.setInt(2, Math.max(1, Math.min(limit <= 0 ? 100 : limit, 1000)));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    candidates.add(mapCandidate(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return candidates;
    }

    @Override
    public void markCandidateStatus(
            String candidateId, String status, String decisionId, long updatedAt) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update proactive_candidates set status = ?, last_decision_id = ?, updated_at = ? where candidate_id = ?");
            statement.setString(1, status);
            statement.setString(2, decisionId);
            statement.setLong(3, updatedAt);
            statement.setString(4, candidateId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public void saveDecision(ProactiveDecisionRecord decision) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into proactive_decisions (decision_id, tick_id, candidate_id, source_key, decision, reason, message, delivery_platform, delivery_chat_id, delivery_thread_id, delivery_status, delivery_error, metadata_json, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, decision.getDecisionId());
            statement.setString(2, decision.getTickId());
            statement.setString(3, decision.getCandidateId());
            statement.setString(4, decision.getSourceKey());
            statement.setString(5, decision.getDecision());
            statement.setString(6, decision.getReason());
            statement.setString(7, decision.getMessage());
            statement.setString(8, decision.getDeliveryPlatform());
            statement.setString(9, decision.getDeliveryChatId());
            statement.setString(10, decision.getDeliveryThreadId());
            statement.setString(11, decision.getDeliveryStatus());
            statement.setString(12, decision.getDeliveryError());
            statement.setString(13, toJson(decision.getMetadata()));
            statement.setLong(14, decision.getCreatedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public int countSentSince(String sourceKey, long sinceMillis) throws Exception {
        Connection connection = database.openConnection();
        try {
            boolean filterSource = StrUtil.isNotBlank(sourceKey);
            PreparedStatement statement =
                    connection.prepareStatement(
                            filterSource
                                    ? "select count(*) from proactive_decisions where source_key = ? and decision = 'SEND' and created_at >= ? and (delivery_status is null or delivery_status = '' or upper(delivery_status) in ('SUCCESS', 'SENT', 'DELIVERED', 'OK'))"
                                    : "select count(*) from proactive_decisions where decision = 'SEND' and created_at >= ? and (delivery_status is null or delivery_status = '' or upper(delivery_status) in ('SUCCESS', 'SENT', 'DELIVERED', 'OK'))");
            if (filterSource) {
                statement.setString(1, sourceKey);
                statement.setLong(2, sinceMillis);
            } else {
                statement.setLong(1, sinceMillis);
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public Long findLastSentAt(String sourceKey) throws Exception {
        Connection connection = database.openConnection();
        try {
            boolean filterSource = StrUtil.isNotBlank(sourceKey);
            PreparedStatement statement =
                    connection.prepareStatement(
                            filterSource
                                    ? "select max(created_at) from proactive_decisions where source_key = ? and decision = 'SEND' and (delivery_status is null or delivery_status = '' or upper(delivery_status) in ('SUCCESS', 'SENT', 'DELIVERED', 'OK'))"
                                    : "select max(created_at) from proactive_decisions where decision = 'SEND' and (delivery_status is null or delivery_status = '' or upper(delivery_status) in ('SUCCESS', 'SENT', 'DELIVERED', 'OK'))");
            if (filterSource) {
                statement.setString(1, sourceKey);
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                if (!resultSet.next()) {
                    return null;
                }
                long value = resultSet.getLong(1);
                return resultSet.wasNull() ? null : Long.valueOf(value);
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from proactive_source_snapshots where source_type = ? and source_ref = ?");
            statement.setString(1, sourceType);
            statement.setString(2, sourceRef);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? mapSnapshot(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Override
    public void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into proactive_source_snapshots (source_type, source_ref, state_hash, payload_json, checked_at) values (?, ?, ?, ?, ?)");
            statement.setString(1, snapshot.getSourceType());
            statement.setString(2, snapshot.getSourceRef());
            statement.setString(3, snapshot.getStateHash());
            statement.setString(4, toJson(snapshot.getPayload()));
            statement.setLong(5, snapshot.getCheckedAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    @Override
    public List<ProactiveDecisionRecord> listRecentDecisions(int limit) throws Exception {
        List<ProactiveDecisionRecord> decisions = new ArrayList<ProactiveDecisionRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from proactive_decisions order by created_at desc limit ?");
            statement.setInt(1, Math.max(1, Math.min(limit <= 0 ? 100 : limit, 1000)));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    decisions.add(mapDecision(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return decisions;
    }

    /**
     * 将对象序列化为 JSON。
     *
     * @param value 待序列化对象。
     * @return 返回 JSON 文本。
     */
    private String toJson(Object value) {
        return value == null ? null : ONode.serialize(value);
    }

    /**
     * 将 JSON 反序列化为 Map。
     *
     * @param json JSON 文本。
     * @return 返回 Map 结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            Object value = ONode.deserialize(json, Object.class);
            return value instanceof Map
                    ? (Map<String, Object>) value
                    : new LinkedHashMap<String, Object>();
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    /**
     * 映射候选记录。
     *
     * @param resultSet 查询结果。
     * @return 返回候选记录。
     */
    private ProactiveCandidateRecord mapCandidate(ResultSet resultSet) throws Exception {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId(resultSet.getString("candidate_id"));
        candidate.setSourceType(resultSet.getString("source_type"));
        candidate.setSourceRef(resultSet.getString("source_ref"));
        candidate.setSourceKey(resultSet.getString("source_key"));
        candidate.setSubjectType(resultSet.getString("subject_type"));
        candidate.setSubjectRef(resultSet.getString("subject_ref"));
        candidate.setTopic(resultSet.getString("topic"));
        candidate.setTitle(resultSet.getString("title"));
        candidate.setSummary(resultSet.getString("summary"));
        candidate.setReason(resultSet.getString("reason"));
        candidate.setActionOffer(resultSet.getString("action_offer"));
        candidate.setEvidence(toMap(resultSet.getString("evidence_json")));
        candidate.setConfidence(resultSet.getDouble("confidence"));
        candidate.setPriority(resultSet.getInt("priority"));
        candidate.setDedupKey(resultSet.getString("dedup_key"));
        candidate.setStateHash(resultSet.getString("state_hash"));
        candidate.setCreatedAt(resultSet.getLong("created_at"));
        candidate.setExpiresAt(resultSet.getLong("expires_at"));
        candidate.setStatus(resultSet.getString("status"));
        candidate.setLastDecisionId(resultSet.getString("last_decision_id"));
        candidate.setUpdatedAt(resultSet.getLong("updated_at"));
        return candidate;
    }

    /**
     * 映射决策记录。
     *
     * @param resultSet 查询结果。
     * @return 返回决策记录。
     */
    private ProactiveDecisionRecord mapDecision(ResultSet resultSet) throws Exception {
        ProactiveDecisionRecord decision = new ProactiveDecisionRecord();
        decision.setDecisionId(resultSet.getString("decision_id"));
        decision.setTickId(resultSet.getString("tick_id"));
        decision.setCandidateId(resultSet.getString("candidate_id"));
        decision.setSourceKey(resultSet.getString("source_key"));
        decision.setDecision(resultSet.getString("decision"));
        decision.setReason(resultSet.getString("reason"));
        decision.setMessage(resultSet.getString("message"));
        decision.setDeliveryPlatform(resultSet.getString("delivery_platform"));
        decision.setDeliveryChatId(resultSet.getString("delivery_chat_id"));
        decision.setDeliveryThreadId(resultSet.getString("delivery_thread_id"));
        decision.setDeliveryStatus(resultSet.getString("delivery_status"));
        decision.setDeliveryError(resultSet.getString("delivery_error"));
        decision.setMetadata(toMap(resultSet.getString("metadata_json")));
        decision.setCreatedAt(resultSet.getLong("created_at"));
        return decision;
    }

    /**
     * 映射来源快照记录。
     *
     * @param resultSet 查询结果。
     * @return 返回来源快照记录。
     */
    private ProactiveSourceSnapshotRecord mapSnapshot(ResultSet resultSet) throws Exception {
        ProactiveSourceSnapshotRecord snapshot = new ProactiveSourceSnapshotRecord();
        snapshot.setSourceType(resultSet.getString("source_type"));
        snapshot.setSourceRef(resultSet.getString("source_ref"));
        snapshot.setStateHash(resultSet.getString("state_hash"));
        snapshot.setPayload(toMap(resultSet.getString("payload_json")));
        snapshot.setCheckedAt(resultSet.getLong("checked_at"));
        return snapshot;
    }
}
