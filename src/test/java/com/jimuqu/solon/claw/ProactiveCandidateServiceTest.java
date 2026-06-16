package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.proactive.ProactiveCandidateService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 主动协作候选生成服务测试。 */
public class ProactiveCandidateServiceTest {
    /** 验证支持的观测类型会转换为稳定候选并按计划优先级排序。 */
    @Test
    void shouldGenerateDeterministicCandidatesForSupportedObservations() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateService service = new ProactiveCandidateService(repository);
        ProactiveTickContext context = context(1_700_000_000_000L);

        List<ProactiveCandidateRecord> candidates =
                service.generate(
                        context,
                        java.util.Arrays.asList(
                                observation("obs-run", "run_state", "run-state", runPayload()),
                                observation("obs-cron", "cron_followup", "cron-state", cronPayload()),
                                observation(
                                        "obs-repo",
                                        "repository_update",
                                        "repo-state",
                                        repositoryPayload("hash-1")),
                                observation(
                                        "obs-session",
                                        "session_continuation",
                                        "session-state",
                                        sessionPayload()),
                                observation(
                                        "obs-memory",
                                        "memory_followup",
                                        "memory-state",
                                        memoryPayload())));

        assertThat(candidates)
                .extracting(ProactiveCandidateRecord::getDedupKey)
                .containsExactly(
                        "run:run-1:recoverable",
                        "cron:job-1:delivery_failed:1700000001000",
                        "repo:https://github.com/acme/demo.git:main:hash-1",
                        "session:session-1:1700000003000",
                        "memory:7dee6982e09dcf169da9816b88777712f7ebb60a80cae7f1f9419633b55da182");
        assertThat(candidates)
                .extracting(ProactiveCandidateRecord::getPriority)
                .containsExactly(90, 90, 75, 60, 45);
        assertThat(candidates)
                .extracting(ProactiveCandidateRecord::getStatus)
                .containsOnly("PENDING");
        assertThat(candidates)
                .extracting(ProactiveCandidateRecord::getExpiresAt)
                .containsOnly(context.getNowMillis() + 72L * 60L * 60L * 1000L);
        assertThat(candidates.get(2).getStateHash()).isEqualTo("hash-1");
        assertThat(repository.savedCandidates).containsExactlyElementsOf(candidates);
    }

    /** 验证仅用于门控的观测不会生成用户可见候选。 */
    @Test
    void shouldSkipGateOnlyObservations() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateService service = new ProactiveCandidateService(repository);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "proactive_context");
        payload.put("gateOnly", Boolean.TRUE);
        payload.put("missingHomeChannel", Boolean.TRUE);

        List<ProactiveCandidateRecord> candidates =
                service.generate(
                        context(1_700_000_000_000L),
                        java.util.Arrays.asList(
                                observation("obs-gate", "quiet_context", "gate-state", payload)));

        assertThat(candidates).isEmpty();
        assertThat(repository.savedCandidates).isEmpty();
    }

    /** 验证同一去重键和源状态不会重复生成候选，但源状态变化会生成新候选。 */
    @Test
    void shouldSuppressDuplicateStateAndPersistChangedState() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateService service = new ProactiveCandidateService(repository);
        ProactiveTickContext context = context(1_700_000_000_000L);

        List<ProactiveCandidateRecord> first =
                service.generate(
                        context,
                        java.util.Arrays.asList(
                                observation(
                                        "obs-repo-a",
                                        "repository_update",
                                        "repo-state-a",
                                        repositoryPayload("hash-1"))));
        List<ProactiveCandidateRecord> duplicate =
                service.generate(
                        context,
                        java.util.Arrays.asList(
                                observation(
                                        "obs-repo-b",
                                        "repository_update",
                                        "repo-state-b",
                                        repositoryPayload("hash-1"))));
        List<ProactiveCandidateRecord> changed =
                service.generate(
                        context,
                        java.util.Arrays.asList(
                                observation(
                                        "obs-repo-c",
                                        "repository_update",
                                        "repo-state-c",
                                        repositoryPayload("hash-2"))));

        assertThat(first).hasSize(1);
        assertThat(duplicate).isEmpty();
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0).getStateHash()).isEqualTo("hash-2");
        assertThat(repository.savedCandidates).hasSize(2);
    }

    /** 验证采集 SPI 的非失败状态也能进入候选生成，避免只认单一状态值。 */
    @Test
    void shouldGenerateCandidateForNonFailedObservationStatus() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateService service = new ProactiveCandidateService(repository);
        ProactiveObservationRecord observation =
                observation("obs-new", "session_continuation", "session-state", sessionPayload());
        observation.setStatus("NEW");

        List<ProactiveCandidateRecord> candidates =
                service.generate(context(1_700_000_000_000L), java.util.Arrays.asList(observation));

        assertThat(candidates)
                .extracting(ProactiveCandidateRecord::getDedupKey)
                .containsExactly("session:session-1:1700000003000");
    }

    /**
     * 构造主动协作 tick 上下文。
     *
     * @param nowMillis 当前时间。
     * @return 返回带有主动协作配置的上下文。
     */
    private static ProactiveTickContext context(long nowMillis) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(true);
        config.getProactive().setMaxCandidatesPerTick(20);
        config.getProactive().setCandidateTtlHours(72);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("tick-candidate");
        context.setNowMillis(nowMillis);
        context.setConfig(config);
        return context;
    }

    /**
     * 构造观测记录。
     *
     * @param observationId 观测 ID。
     * @param collector 采集器名称。
     * @param stateHash 观测状态哈希。
     * @param payload 结构化载荷。
     * @return 返回观测记录。
     */
    private static ProactiveObservationRecord observation(
            String observationId, String collector, String stateHash, Map<String, Object> payload) {
        ProactiveObservationRecord observation = new ProactiveObservationRecord();
        observation.setObservationId(observationId);
        observation.setTickId("tick-candidate");
        observation.setCollector(collector);
        observation.setSourceKey(String.valueOf(payload.getOrDefault("sourceKey", collector)));
        observation.setSummary("观测摘要 " + observationId);
        observation.setPayload(payload);
        observation.setStatus("COLLECTED");
        observation.setCreatedAt(1_700_000_000_000L);
        if (!payload.containsKey("stateHash")) {
            payload.put("stateHash", stateHash);
        }
        return observation;
    }

    /** 构造运行状态观测载荷。 */
    private static Map<String, Object> runPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "run_recoverable");
        payload.put("runId", "run-1");
        payload.put("sessionId", "session-1");
        payload.put("sourceKey", "channel:work");
        payload.put("status", "recoverable");
        payload.put("recoveryHint", "可以从上一步继续");
        payload.put("stateHash", "run-state");
        return payload;
    }

    /** 构造定时任务跟进观测载荷。 */
    private static Map<String, Object> cronPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "cron_delivery_error");
        payload.put("jobId", "job-1");
        payload.put("name", "日报检查");
        payload.put("sourceKey", "cron:job-1");
        payload.put("lastStatus", "delivery_failed");
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("lastRunAt", Long.valueOf(1_700_000_001_000L));
        evidence.put("deliveryError", "投递失败");
        payload.put("evidence", evidence);
        return payload;
    }

    /**
     * 构造仓库更新观测载荷。
     *
     * @param stateHash 仓库状态哈希。
     * @return 返回观测载荷。
     */
    private static Map<String, Object> repositoryPayload(String stateHash) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "project_update_opportunity");
        payload.put("sourceRef", "https://github.com/acme/demo.git");
        payload.put("sourceKey", "repo:https://github.com/acme/demo.git");
        payload.put("branch", "main");
        payload.put("stateHash", stateHash);
        payload.put("commitHash", stateHash + "-commit");
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("referenceSourceType", "session");
        evidence.put("referenceSourceRef", "session-1");
        evidence.put("displayName", "demo");
        payload.put("evidence", evidence);
        return payload;
    }

    /** 构造会话续接观测载荷。 */
    private static Map<String, Object> sessionPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "session_continuation");
        payload.put("sessionId", "session-1");
        payload.put("title", "功能续接");
        payload.put("sourceKey", "channel:work");
        payload.put("updatedAt", Long.valueOf(1_700_000_003_000L));
        payload.put("finalReplyPreview", "等待用户确认后继续");
        payload.put("reasons", java.util.Arrays.asList("assistant_waiting_confirmation"));
        return payload;
    }

    /** 构造记忆知识跟进观测载荷。 */
    private static Map<String, Object> memoryPayload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "knowledge_followup");
        payload.put("section", "memory");
        payload.put("sourceRef", "MEMORY.md");
        payload.put("topic", "项目协作");
        payload.put("confidenceHint", "medium");
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("lineNumber", Integer.valueOf(12));
        evidence.put("lines", java.util.Arrays.asList("用户希望主动询问当前项目是否需要协作"));
        payload.put("evidence", evidence);
        return payload;
    }

    /** 用于候选生成测试的内存仓储。 */
    private static class InMemoryProactiveRepository implements ProactiveRepository {
        /** 已保存候选列表。 */
        private final List<ProactiveCandidateRecord> savedCandidates =
                new ArrayList<ProactiveCandidateRecord>();

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveCandidate(ProactiveCandidateRecord candidate) {
            savedCandidates.add(candidate);
        }

        @Override
        public ProactiveCandidateRecord findRecentCandidateByDedup(
                String dedupKey, String stateHash, long nowMillis) {
            for (ProactiveCandidateRecord candidate : savedCandidates) {
                boolean notExpired =
                        candidate.getExpiresAt() <= 0L || candidate.getExpiresAt() >= nowMillis;
                if (notExpired
                        && dedupKey.equals(candidate.getDedupKey())
                        && stateHash.equals(candidate.getStateHash())) {
                    return candidate;
                }
            }
            return null;
        }

        @Override
        public List<ProactiveCandidateRecord> listPendingCandidates(long nowMillis, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markCandidateStatus(
                String candidateId, String status, String decisionId, long updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveDecision(ProactiveDecisionRecord decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countSentSince(String sourceKey, long sinceMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long findLastSentAt(String sourceKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProactiveDecisionRecord> listRecentDecisions(int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
