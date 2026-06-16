package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.MemorySnapshot;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.MemoryService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.proactive.RepositoryProbeService;
import com.jimuqu.solon.claw.proactive.RepositoryReferenceExtractor;
import com.jimuqu.solon.claw.proactive.collector.RepositoryUpdateCollector;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 仓库更新观测采集器测试，覆盖引用提取、快照去重和变更观测输出。 */
public class RepositoryUpdateCollectorTest {
    /** 测试固定当前时间，避免仓库检查间隔受系统时间影响。 */
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void shouldExtractRepositoryReferencesFromMemorySessionAndCronText() throws Exception {
        RepositoryReferenceExtractor extractor = new RepositoryReferenceExtractor();
        List<RepositoryReferenceExtractor.RepositoryReference> references =
                extractor.extract(
                        "memory",
                        "memory:1",
                        "持续关注 https://github.com/example/demo.git 和 /Users/chengliang/code-projects/demo 的更新");
        references.addAll(
                extractor.extract(
                        "session",
                        "session:1",
                        "用户让检查 gitee.com/org/app 的 release，并继续 /Users/chengliang/code-repositories/app"));
        references.addAll(
                extractor.extract(
                        "cron",
                        "cron:1",
                        "定期查看 https://gitlab.com/group/tool/-/releases"));

        assertThat(references).extracting(RepositoryReferenceExtractor.RepositoryReference::getRef)
                .contains(
                        "https://github.com/example/demo.git",
                        "/Users/chengliang/code-projects/demo",
                        "https://gitee.com/org/app",
                        "/Users/chengliang/code-repositories/app",
                        "https://gitlab.com/group/tool");
        assertThat(references).extracting(RepositoryReferenceExtractor.RepositoryReference::getSourceType)
                .contains("memory", "session", "cron");
    }

    @Test
    void shouldEmitObservationForFirstSeenRepositoryAndStoreSnapshot() throws Exception {
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();
        FakeRepositoryProbeService probeService = new FakeRepositoryProbeService();
        probeService.states.put(
                "https://github.com/example/demo",
                state("https://github.com/example/demo", "main", "abc123", "release-1"));
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 持续关注 https://github.com/example/demo 的更新");

        List<ProactiveObservation> observations =
                collector(proactiveRepository, probeService, memoryService).collect(context(true, 0));

        ProactiveObservation observation = observationOfType(observations, "project_update_opportunity");
        assertThat(observation.getCollector()).isEqualTo("repository_update");
        assertThat(observation.getSourceKey()).isEqualTo("repository_update:https://github.com/example/demo");
        assertThat(observation.getStatus()).isEqualTo("COLLECTED");
        assertThat(observation.getSummary()).contains("github.com/example/demo");
        assertThat(observation.getPayload()).containsEntry("sourceRef", "https://github.com/example/demo");
        assertThat(observation.getPayload()).containsEntry("branch", "main");
        assertThat(observation.getPayload()).containsEntry("stateHash", "abc123");
        assertThat(evidence(observation)).containsEntry("previousHash", null);
        assertThat(proactiveRepository.findSnapshot("repository", "https://github.com/example/demo"))
                .extracting(ProactiveSourceSnapshotRecord::getStateHash)
                .isEqualTo("abc123");
    }

    @Test
    void shouldNotEmitRepeatedObservationWhenSnapshotUnchanged() throws Exception {
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();
        ProactiveSourceSnapshotRecord snapshot = new ProactiveSourceSnapshotRecord();
        snapshot.setSourceType("repository");
        snapshot.setSourceRef("https://github.com/example/demo");
        snapshot.setStateHash("abc123");
        snapshot.setCheckedAt(NOW - 10_000L);
        proactiveRepository.saveSnapshot(snapshot);
        FakeRepositoryProbeService probeService = new FakeRepositoryProbeService();
        probeService.states.put(
                "https://github.com/example/demo",
                state("https://github.com/example/demo", "main", "abc123", "release-1"));
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 持续关注 https://github.com/example/demo 的更新");

        List<ProactiveObservation> observations =
                collector(proactiveRepository, probeService, memoryService).collect(context(true, 0));

        assertThat(observations).isEmpty();
        assertThat(proactiveRepository.findSnapshot("repository", "https://github.com/example/demo")
                        .getCheckedAt())
                .isEqualTo(NOW);
    }

    @Test
    void shouldEmitObservationWhenRepositoryStateChanged() throws Exception {
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();
        ProactiveSourceSnapshotRecord snapshot = new ProactiveSourceSnapshotRecord();
        snapshot.setSourceType("repository");
        snapshot.setSourceRef("https://github.com/example/demo");
        snapshot.setStateHash("old123");
        snapshot.setCheckedAt(NOW - 24L * 60L * 60L * 1000L);
        proactiveRepository.saveSnapshot(snapshot);
        FakeRepositoryProbeService probeService = new FakeRepositoryProbeService();
        probeService.states.put(
                "https://github.com/example/demo",
                state("https://github.com/example/demo", "main", "new456", "release-2"));
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 持续关注 https://github.com/example/demo 的更新");

        List<ProactiveObservation> observations =
                collector(proactiveRepository, probeService, memoryService).collect(context(true, 360));

        ProactiveObservation observation = observationOfType(observations, "project_update_opportunity");
        assertThat(observation.getPayload()).containsEntry("stateHash", "new456");
        assertThat(evidence(observation)).containsEntry("previousHash", "old123");
        assertThat(proactiveRepository.findSnapshot("repository", "https://github.com/example/demo")
                        .getStateHash())
                .isEqualTo("new456");
    }

    @Test
    void shouldRespectRepositoryCheckSwitchAndInterval() throws Exception {
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();
        ProactiveSourceSnapshotRecord snapshot = new ProactiveSourceSnapshotRecord();
        snapshot.setSourceType("repository");
        snapshot.setSourceRef("https://github.com/example/demo");
        snapshot.setStateHash("old123");
        snapshot.setCheckedAt(NOW - 10_000L);
        proactiveRepository.saveSnapshot(snapshot);
        FakeRepositoryProbeService probeService = new FakeRepositoryProbeService();
        probeService.states.put(
                "https://github.com/example/demo",
                state("https://github.com/example/demo", "main", "new456", "release-2"));
        InMemoryMemoryService memoryService = new InMemoryMemoryService();
        memoryService.snapshot.setMemoryText("- 持续关注 https://github.com/example/demo 的更新");

        assertThat(collector(proactiveRepository, probeService, memoryService)
                        .collect(context(false, 360)))
                .isEmpty();
        assertThat(collector(proactiveRepository, probeService, memoryService)
                        .collect(context(true, 360)))
                .isEmpty();
        assertThat(probeService.probedRefs).isEmpty();
    }

    @Test
    void shouldCollectReferencesFromRecentSessionsAndCronJobs() throws Exception {
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();
        FakeRepositoryProbeService probeService = new FakeRepositoryProbeService();
        probeService.states.put(
                "https://gitee.com/org/app",
                state("https://gitee.com/org/app", "dev", "gitee123", ""));
        probeService.states.put(
                "https://gitlab.com/group/tool",
                state("https://gitlab.com/group/tool", "main", "gitlab123", ""));
        InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
        SessionRecord session = new SessionRecord();
        session.setSessionId("session-repo");
        session.setUpdatedAt(NOW - 1_000L);
        session.setNdjson(
                MessageSupport.toNdjson(
                        Arrays.asList(ChatMessage.ofUser("之前让我处理 https://gitee.com/org/app 的功能"))));
        sessionRepository.sessions.add(session);
        InMemoryCronJobRepository cronJobRepository = new InMemoryCronJobRepository();
        CronJobRecord job = new CronJobRecord();
        job.setJobId("cron-repo");
        job.setPrompt("定期观察 https://gitlab.com/group/tool 的更新");
        cronJobRepository.jobs.add(job);

        List<ProactiveObservation> observations =
                new RepositoryUpdateCollector(
                                proactiveRepository,
                                probeService,
                                new RepositoryReferenceExtractor(),
                                sessionRepository,
                                new InMemoryMemoryService(),
                                cronJobRepository)
                        .collect(context(true, 0));

        assertThat(observations).extracting(item -> item.getPayload().get("sourceRef"))
                .contains("https://gitee.com/org/app", "https://gitlab.com/group/tool");
    }

    /** 构造仓库更新采集器，复用默认空会话和空定时任务仓储。 */
    private static RepositoryUpdateCollector collector(
            ProactiveRepository proactiveRepository,
            RepositoryProbeService probeService,
            MemoryService memoryService) {
        return new RepositoryUpdateCollector(
                proactiveRepository,
                probeService,
                new RepositoryReferenceExtractor(),
                new InMemorySessionRepository(),
                memoryService,
                new InMemoryCronJobRepository());
    }

    /** 构造仓库探测结果。 */
    private static RepositoryProbeService.RepositoryState state(
            String ref, String branch, String hash, String releaseId) {
        RepositoryProbeService.RepositoryState state = new RepositoryProbeService.RepositoryState();
        state.setRef(ref);
        state.setDisplayName(ref);
        state.setBranch(branch);
        state.setStateHash(hash);
        state.setCommitHash(hash);
        state.setReleaseId(releaseId);
        return state;
    }

    /** 构造主动协作 tick 上下文。 */
    private static ProactiveTickContext context(boolean enabled, int checkIntervalMinutes) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(enabled);
        config.getProactive().setRepositoryCheckEnabled(enabled);
        config.getProactive().setRepositoryCheckIntervalMinutes(checkIntervalMinutes);
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("tick-repository-update");
        context.setNowMillis(NOW);
        context.setConfig(config);
        return context;
    }

    /** 从观测列表中查找指定类型，缺失时让断言输出包含实际类型，便于定位。 */
    private static ProactiveObservation observationOfType(
            List<ProactiveObservation> observations, String type) {
        for (ProactiveObservation observation : observations) {
            if (type.equals(observation.getPayload().get("type"))) {
                return observation;
            }
        }
        assertThat(observations).extracting(item -> item.getPayload().get("type")).contains(type);
        return null;
    }

    /** 读取观测中的 evidence 子载荷。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(ProactiveObservation observation) {
        Object value = observation.getPayload().get("evidence");
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** 测试用仓库探测服务，只返回预置状态并记录探测请求。 */
    private static final class FakeRepositoryProbeService implements RepositoryProbeService {
        /** 预置仓库状态，key 为规范化后的仓库引用。 */
        private final Map<String, RepositoryState> states =
                new LinkedHashMap<String, RepositoryState>();

        /** 已探测的仓库引用，用于验证检查间隔是否生效。 */
        private final List<String> probedRefs = new ArrayList<String>();

        @Override
        public RepositoryState probe(RepositoryReferenceExtractor.RepositoryReference reference) {
            probedRefs.add(reference.getRef());
            return states.get(reference.getRef());
        }
    }

    /** 测试用内存主动协作仓储。 */
    private static final class InMemoryProactiveRepository implements ProactiveRepository {
        /** 来源快照存储，key 为来源类型和来源引用。 */
        private final Map<String, ProactiveSourceSnapshotRecord> snapshots =
                new LinkedHashMap<String, ProactiveSourceSnapshotRecord>();

        @Override
        public ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef) {
            return snapshots.get(sourceType + ":" + sourceRef);
        }

        @Override
        public void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) {
            snapshots.put(snapshot.getSourceType() + ":" + snapshot.getSourceRef(), snapshot);
        }

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {
            throw new UnsupportedOperationException("测试仓储不保存观测记录");
        }

        @Override
        public void saveCandidate(ProactiveCandidateRecord candidate) {
            throw new UnsupportedOperationException("测试仓储不保存候选记录");
        }

        @Override
        public ProactiveCandidateRecord findRecentCandidateByDedup(
                String dedupKey, String stateHash, long nowMillis) {
            throw new UnsupportedOperationException("测试仓储不查询候选记录");
        }

        @Override
        public List<ProactiveCandidateRecord> listPendingCandidates(long nowMillis, int limit) {
            throw new UnsupportedOperationException("测试仓储不列出候选记录");
        }

        @Override
        public void markCandidateStatus(
                String candidateId, String status, String decisionId, long updatedAt) {
            throw new UnsupportedOperationException("测试仓储不更新候选记录");
        }

        @Override
        public void saveDecision(ProactiveDecisionRecord decision) {
            throw new UnsupportedOperationException("测试仓储不保存决策记录");
        }

        @Override
        public int countSentSince(String sourceKey, long sinceMillis) {
            throw new UnsupportedOperationException("测试仓储不统计发送次数");
        }

        @Override
        public Long findLastSentAt(String sourceKey) {
            throw new UnsupportedOperationException("测试仓储不查询最近发送时间");
        }

        @Override
        public List<ProactiveDecisionRecord> listRecentDecisions(int limit) {
            return new ArrayList<ProactiveDecisionRecord>();
        }
    }

    /** 测试用记忆服务。 */
    private static final class InMemoryMemoryService implements MemoryService {
        /** 采集器读取的测试记忆快照。 */
        private MemorySnapshot snapshot = new MemorySnapshot();

        @Override
        public MemorySnapshot loadSnapshot() {
            return snapshot;
        }

        @Override
        public String read(String target) {
            throw new UnsupportedOperationException("测试记忆服务不支持读取单个目标");
        }

        @Override
        public String add(String target, String content) {
            throw new UnsupportedOperationException("测试记忆服务不支持追加记忆");
        }

        @Override
        public String replace(String target, String oldText, String newContent) {
            throw new UnsupportedOperationException("测试记忆服务不支持替换记忆");
        }

        @Override
        public String remove(String target, String matchText) {
            throw new UnsupportedOperationException("测试记忆服务不支持删除记忆");
        }
    }

    /** 测试用会话仓储，只实现最近会话读取。 */
    private static final class InMemorySessionRepository implements SessionRepository {
        /** 采集器读取的测试会话列表。 */
        private final List<SessionRecord> sessions = new ArrayList<SessionRecord>();

        @Override
        public List<SessionRecord> listRecent(int limit) {
            return sessions;
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            return sessions;
        }

        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            throw new UnsupportedOperationException("测试会话仓储不支持绑定查询");
        }

        @Override
        public SessionRecord bindNewSession(String sourceKey) {
            throw new UnsupportedOperationException("测试会话仓储不支持新建会话");
        }

        @Override
        public void bindSource(String sourceKey, String sessionId) {
            throw new UnsupportedOperationException("测试会话仓储不支持绑定来源");
        }

        @Override
        public SessionRecord cloneSession(String sourceKey, String sourceSessionId, String branchName) {
            throw new UnsupportedOperationException("测试会话仓储不支持克隆会话");
        }

        @Override
        public SessionRecord findById(String sessionId) {
            throw new UnsupportedOperationException("测试会话仓储不支持按 ID 查询");
        }

        @Override
        public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
            throw new UnsupportedOperationException("测试会话仓储不支持按来源查询");
        }

        @Override
        public List<SessionRecord> findResumeCandidates(String reference, int limit) {
            throw new UnsupportedOperationException("测试会话仓储不支持恢复候选查询");
        }

        @Override
        public void save(SessionRecord sessionRecord) {
            throw new UnsupportedOperationException("测试会话仓储不支持保存");
        }

        @Override
        public List<SessionRecord> search(String keyword, int limit) {
            throw new UnsupportedOperationException("测试会话仓储不支持搜索");
        }

        @Override
        public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
            throw new UnsupportedOperationException("测试会话仓储不支持 pending 查询");
        }

        @Override
        public int countAll() {
            return sessions.size();
        }

        @Override
        public void delete(String sessionId) {
            throw new UnsupportedOperationException("测试会话仓储不支持删除");
        }

        @Override
        public void setModelOverride(String sessionId, String modelOverride) {
            throw new UnsupportedOperationException("测试会话仓储不支持模型覆盖");
        }

        @Override
        public void setServiceTierOverride(String sessionId, String serviceTierOverride) {
            throw new UnsupportedOperationException("测试会话仓储不支持服务层级覆盖");
        }

        @Override
        public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {
            throw new UnsupportedOperationException("测试会话仓储不支持推理强度覆盖");
        }

        @Override
        public void setActiveAgentName(String sessionId, String agentName) {
            throw new UnsupportedOperationException("测试会话仓储不支持 Agent 设置");
        }

        @Override
        public void clearActiveAgentName(String agentName) {
            throw new UnsupportedOperationException("测试会话仓储不支持 Agent 清理");
        }

        @Override
        public void setGoalState(String sessionId, String goalStateJson) {
            throw new UnsupportedOperationException("测试会话仓储不支持目标状态");
        }

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {
            throw new UnsupportedOperationException("测试会话仓储不支持学习时间");
        }
    }

    /** 测试用定时任务仓储，只实现任务列表读取。 */
    private static final class InMemoryCronJobRepository implements CronJobRepository {
        /** 采集器读取的测试任务列表。 */
        private final List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();

        @Override
        public List<CronJobRecord> listAll() {
            return jobs;
        }

        @Override
        public CronJobRecord save(CronJobRecord job) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持保存");
        }

        @Override
        public CronJobRecord findById(String jobId) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持按 ID 查询");
        }

        @Override
        public List<CronJobRecord> listBySource(String sourceKey) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持按来源查询");
        }

        @Override
        public List<CronJobRecord> listDue(long nowEpochMillis) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持到期查询");
        }

        @Override
        public void delete(String jobId) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持删除");
        }

        @Override
        public void updateStatus(String jobId, String status) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持状态更新");
        }

        @Override
        public CronJobRecord update(CronJobRecord job) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持更新");
        }

        @Override
        public void markRun(String jobId, long lastRunAt, long nextRunAt) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持运行标记");
        }

        @Override
        public void markRunResult(
                String jobId,
                long lastRunAt,
                long nextRunAt,
                String status,
                String error,
                String output,
                int repeatCompleted,
                String nextStatus) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持运行结果");
        }

        @Override
        public void markDeliveryError(String jobId, String error) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持投递错误");
        }

        @Override
        public com.jimuqu.solon.claw.core.model.CronJobRunRecord saveRun(
                com.jimuqu.solon.claw.core.model.CronJobRunRecord run) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持运行历史保存");
        }

        @Override
        public List<com.jimuqu.solon.claw.core.model.CronJobRunRecord> listRuns(
                String jobId, int limit) {
            throw new UnsupportedOperationException("测试定时任务仓储不支持运行历史查询");
        }
    }
}
