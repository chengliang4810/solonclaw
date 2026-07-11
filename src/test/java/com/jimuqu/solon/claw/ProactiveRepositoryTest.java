package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteProactiveRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 主动协作仓储测试。 */
public class ProactiveRepositoryTest {
    @Test
    void shouldCreateSchemaAndIndexes() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();

        assertThat(tableExists(env.sqliteDatabase.openConnection(), "proactive_observations"))
                .isTrue();
        assertThat(tableExists(env.sqliteDatabase.openConnection(), "proactive_candidates"))
                .isTrue();
        assertThat(tableExists(env.sqliteDatabase.openConnection(), "proactive_decisions"))
                .isTrue();
        assertThat(tableExists(env.sqliteDatabase.openConnection(), "proactive_source_snapshots"))
                .isTrue();
        assertThat(
                        indexExists(
                                env.sqliteDatabase.openConnection(),
                                "idx_proactive_candidates_pending"))
                .isTrue();
        assertThat(
                        indexExists(
                                env.sqliteDatabase.openConnection(),
                                "idx_proactive_candidates_dedup"))
                .isTrue();
        assertThat(
                        indexExists(
                                env.sqliteDatabase.openConnection(),
                                "idx_proactive_decisions_created"))
                .isTrue();
        assertThat(
                        indexExists(
                                env.sqliteDatabase.openConnection(),
                                "idx_proactive_decisions_source_created"))
                .isTrue();
    }

    @Test
    void shouldUpsertCandidateAndFindRecentDedup() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();

        ProactiveObservationRecord observation = new ProactiveObservationRecord();
        observation.setObservationId("obs-1");
        observation.setTickId("tick-1");
        observation.setCollector("calendar");
        observation.setSourceKey("MEMORY:team:a");
        observation.setSummary("发现新的主动协作信号");
        Map<String, Object> observationPayload = new LinkedHashMap<String, Object>();
        observationPayload.put("kind", "calendar");
        observationPayload.put("items", java.util.Arrays.asList("milestone"));
        observation.setPayload(observationPayload);
        observation.setStatus("OK");
        observation.setCreatedAt(now);
        repository.saveObservation(observation);

        ProactiveCandidateRecord candidate = baseCandidate("candidate-1", now);
        repository.saveCandidate(candidate);

        ProactiveCandidateRecord stored =
                repository.findRecentCandidateByDedup("dedup-1", "state-1", now);
        assertThat(stored).isNotNull();
        assertThat(stored.getCandidateId()).isEqualTo("candidate-1");
        assertThat(stored.getEvidence().get("signal")).isEqualTo("calendar");
        assertThat(repository.listPendingCandidates(10))
                .extracting(ProactiveCandidateRecord::getCandidateId)
                .containsExactly("candidate-1");

        candidate.setStatus("SKIPPED");
        candidate.setUpdatedAt(now + 500L);
        repository.saveCandidate(candidate);
        ProactiveCandidateRecord dedupCandidate =
                repository.findRecentCandidateByDedup("dedup-1", "state-1", now);
        assertThat(dedupCandidate).isNotNull();
        assertThat(dedupCandidate.getStatus()).isEqualTo("SKIPPED");

        candidate.setExpiresAt(now - 1L);
        candidate.setUpdatedAt(now + 700L);
        repository.saveCandidate(candidate);
        assertThat(repository.findRecentCandidateByDedup("dedup-1", "state-1", now)).isNull();

        candidate.setStatus("PENDING");
        candidate.setExpiresAt(now + 60_000L);
        candidate.setTitle("已更新标题");
        candidate.setPriority(99);
        candidate.setUpdatedAt(now + 1000L);
        repository.saveCandidate(candidate);

        List<ProactiveCandidateRecord> pending = repository.listPendingCandidates(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getTitle()).isEqualTo("已更新标题");
        assertThat(pending.get(0).getPriority()).isEqualTo(99);
    }

    @Test
    void shouldExcludeExpiredPendingCandidates() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();

        ProactiveCandidateRecord expired = baseCandidate("candidate-expired", now - 20_000L);
        expired.setExpiresAt(now - 1L);
        expired.setPriority(100);
        repository.saveCandidate(expired);
        ProactiveCandidateRecord valid = baseCandidate("candidate-valid", now - 10_000L);
        valid.setDedupKey("dedup-valid");
        valid.setStateHash("state-valid");
        valid.setExpiresAt(now + 60_000L);
        valid.setPriority(10);
        repository.saveCandidate(valid);

        assertThat(repository.listPendingCandidates(now, 10))
                .extracting(ProactiveCandidateRecord::getCandidateId)
                .containsExactly("candidate-valid");
    }

    @Test
    void shouldLogDecisionsUpdateCandidateStatusAndCountSentWindow() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();

        ProactiveCandidateRecord first = baseCandidate("candidate-1", now - 5000L);
        first.setPriority(10);
        ProactiveCandidateRecord second = baseCandidate("candidate-2", now - 3000L);
        second.setPriority(20);
        second.setDedupKey("dedup-2");
        second.setStateHash("state-2");
        repository.saveCandidate(first);
        repository.saveCandidate(second);

        ProactiveDecisionRecord sent = new ProactiveDecisionRecord();
        sent.setDecisionId("decision-1");
        sent.setTickId("tick-1");
        sent.setCandidateId(first.getCandidateId());
        sent.setSourceKey(first.getSourceKey());
        sent.setDecision("SEND");
        sent.setReason("窗口内可发送");
        sent.setMessage("请跟进这个里程碑");
        sent.setDeliveryPlatform("wecom");
        sent.setDeliveryChatId("chat-a");
        sent.setDeliveryThreadId("thread-a");
        sent.setDeliveryStatus("SUCCESS");
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("score", Integer.valueOf(91));
        sent.setMetadata(metadata);
        sent.setCreatedAt(now - 1000L);
        repository.saveDecision(sent);
        repository.markCandidateStatus(first.getCandidateId(), "SENT", sent.getDecisionId(), now);

        ProactiveDecisionRecord skipped = new ProactiveDecisionRecord();
        skipped.setDecisionId("decision-2");
        skipped.setTickId("tick-2");
        skipped.setCandidateId(second.getCandidateId());
        skipped.setSourceKey(second.getSourceKey());
        skipped.setDecision("SKIP");
        skipped.setReason("重复");
        skipped.setCreatedAt(now - 500L);
        repository.saveDecision(skipped);
        repository.markCandidateStatus(
                second.getCandidateId(), "SKIPPED", skipped.getDecisionId(), now);

        ProactiveDecisionRecord failed = new ProactiveDecisionRecord();
        failed.setDecisionId("decision-3");
        failed.setTickId("tick-3");
        failed.setCandidateId(null);
        failed.setSourceKey(first.getSourceKey());
        failed.setDecision("SEND");
        failed.setReason("投递失败");
        failed.setDeliveryStatus("FAILED");
        failed.setDeliveryError("network");
        failed.setCreatedAt(now - 100L);
        repository.saveDecision(failed);

        assertThat(repository.countSentSince(first.getSourceKey(), now - 2000L)).isEqualTo(1);
        assertThat(repository.countSentSince(null, now - 2000L)).isEqualTo(1);
        assertThat(repository.findLastSentAt(first.getSourceKey()))
                .isEqualTo(Long.valueOf(now - 1000L));
        assertThat(repository.findLastSentAt("MEMORY:missing")).isNull();
        assertThat(repository.listRecentDecisions(5))
                .extracting(ProactiveDecisionRecord::getDecisionId)
                .containsExactly("decision-3", "decision-2", "decision-1");

        List<ProactiveCandidateRecord> pending = repository.listPendingCandidates(10);
        assertThat(pending).isEmpty();
    }

    @Test
    void shouldTreatBlankOrNullDeliveryStatusAsSuccessfulSentDecision() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();

        ProactiveDecisionRecord pendingDelivery = new ProactiveDecisionRecord();
        pendingDelivery.setDecisionId("decision-pending-delivery");
        pendingDelivery.setTickId("tick-blank");
        pendingDelivery.setSourceKey("MEMORY:team:a");
        pendingDelivery.setDecision("SEND");
        pendingDelivery.setReason("状态尚未回写");
        pendingDelivery.setDeliveryStatus("");
        pendingDelivery.setCreatedAt(now - 100L);
        repository.saveDecision(pendingDelivery);
        ProactiveDecisionRecord missingStatus = new ProactiveDecisionRecord();
        missingStatus.setDecisionId("decision-null-delivery");
        missingStatus.setTickId("tick-null");
        missingStatus.setSourceKey("MEMORY:team:a");
        missingStatus.setDecision("SEND");
        missingStatus.setReason("状态字段为空");
        missingStatus.setDeliveryStatus(null);
        missingStatus.setCreatedAt(now - 50L);
        repository.saveDecision(missingStatus);

        assertThat(repository.countSentSince("MEMORY:team:a", now - 1000L)).isEqualTo(2);
        assertThat(repository.findLastSentAt("MEMORY:team:a")).isEqualTo(Long.valueOf(now - 50L));
    }

    @Test
    void shouldDowngradeMalformedJsonPayloadsDuringRead() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();
        ProactiveCandidateRecord candidate = baseCandidate("candidate-bad-json", now);
        candidate.setDedupKey("dedup-bad-json");
        candidate.setStateHash("state-bad-json");
        repository.saveCandidate(candidate);
        overwriteCandidateEvidenceJson(
                env.sqliteDatabase.openConnection(), candidate.getCandidateId(), "{bad-json");

        ProactiveCandidateRecord stored =
                repository.findRecentCandidateByDedup("dedup-bad-json", "state-bad-json", now);

        assertThat(stored).isNotNull();
        assertThat(stored.getEvidence()).isEmpty();
    }

    @Test
    void shouldReplaceSourceSnapshot() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);

        ProactiveSourceSnapshotRecord snapshot = new ProactiveSourceSnapshotRecord();
        snapshot.setSourceType("calendar");
        snapshot.setSourceRef("team-a");
        snapshot.setStateHash("hash-1");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("count", Integer.valueOf(1));
        snapshot.setPayload(payload);
        snapshot.setCheckedAt(100L);
        repository.saveSnapshot(snapshot);

        ProactiveSourceSnapshotRecord updated = new ProactiveSourceSnapshotRecord();
        updated.setSourceType("calendar");
        updated.setSourceRef("team-a");
        updated.setStateHash("hash-2");
        Map<String, Object> updatedPayload = new LinkedHashMap<String, Object>();
        updatedPayload.put("count", Integer.valueOf(2));
        updated.setPayload(updatedPayload);
        updated.setCheckedAt(200L);
        repository.saveSnapshot(updated);

        ProactiveSourceSnapshotRecord stored = repository.findSnapshot("calendar", "team-a");
        assertThat(stored).isNotNull();
        assertThat(stored.getStateHash()).isEqualTo("hash-2");
        assertThat(stored.getPayload().get("count")).isEqualTo(Integer.valueOf(2));
        assertThat(repository.findSnapshot("calendar", "missing")).isNull();
    }

    /**
     * 构造基础候选记录。
     *
     * @param candidateId 候选标识。
     * @param now 时间戳。
     * @return 返回候选记录。
     */
    private ProactiveCandidateRecord baseCandidate(String candidateId, long now) {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId(candidateId);
        candidate.setSourceType("calendar");
        candidate.setSourceRef("team-a");
        candidate.setSourceKey("MEMORY:team:a");
        candidate.setSubjectType("project");
        candidate.setSubjectRef("project-1");
        candidate.setTopic("milestone");
        candidate.setTitle("里程碑提醒");
        candidate.setSummary("需要主动跟进");
        candidate.setReason("临近截止时间");
        candidate.setActionOffer("生成一条提醒消息");
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("signal", "calendar");
        evidence.put("count", Integer.valueOf(1));
        candidate.setEvidence(evidence);
        candidate.setConfidence(0.82D);
        candidate.setPriority(50);
        candidate.setDedupKey("dedup-1");
        candidate.setStateHash("state-1");
        candidate.setCreatedAt(now);
        candidate.setExpiresAt(now + 60_000L);
        candidate.setStatus("PENDING");
        candidate.setUpdatedAt(now);
        return candidate;
    }

    /**
     * 判断表是否存在。
     *
     * @param connection 数据库连接。
     * @param tableName 表名。
     * @return 返回是否存在。
     */
    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select count(*) from sqlite_master where type = 'table' and name = ?");
            try {
                statement.setString(1, tableName);
                ResultSet resultSet = statement.executeQuery();
                try {
                    return resultSet.next() && resultSet.getInt(1) > 0;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 判断索引是否存在。
     *
     * @param connection 数据库连接。
     * @param indexName 索引名。
     * @return 返回是否存在。
     */
    private boolean indexExists(Connection connection, String indexName) throws Exception {
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select count(*) from sqlite_master where type = 'index' and name = ?");
            try {
                statement.setString(1, indexName);
                ResultSet resultSet = statement.executeQuery();
                try {
                    return resultSet.next() && resultSet.getInt(1) > 0;
                } finally {
                    resultSet.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 覆盖候选证据 JSON，用于模拟历史或手工写入的脏数据。
     *
     * @param connection 数据库连接。
     * @param candidateId 候选标识。
     * @param evidenceJson 证据 JSON 文本。
     */
    private void overwriteCandidateEvidenceJson(
            Connection connection, String candidateId, String evidenceJson) throws Exception {
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update proactive_candidates set evidence_json = ? where candidate_id = ?");
            try {
                statement.setString(1, evidenceJson);
                statement.setString(2, candidateId);
                statement.executeUpdate();
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }
}
