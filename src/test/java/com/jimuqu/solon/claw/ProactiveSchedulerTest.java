package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.proactive.ProactiveCandidateService;
import com.jimuqu.solon.claw.proactive.ProactiveDecisionService;
import com.jimuqu.solon.claw.proactive.ProactiveDispatchService;
import com.jimuqu.solon.claw.proactive.ProactiveMessageComposer;
import com.jimuqu.solon.claw.proactive.ProactiveObservationService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.proactive.ProactiveScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 主动协作调度器测试。 */
public class ProactiveSchedulerTest {
    @Test
    void shouldNotRunTickWhenDisabled() throws Exception {
        AppConfig config = config(false);
        RecordingObservationService observationService = new RecordingObservationService();

        new ProactiveScheduler(
                        config,
                        observationService,
                        new RecordingCandidateService(),
                        new RecordingDecisionService(),
                        new RecordingMessageComposer(),
                        new RecordingDispatchService(),
                        new RecordingRepository())
                .tick();

        assertThat(observationService.calls).isEqualTo(0);
    }

    @Test
    void shouldExecuteEnabledTickInOrder() throws Exception {
        List<String> events = new ArrayList<String>();
        AppConfig config = config(true);
        RecordingObservationService observationService = new RecordingObservationService(events);
        RecordingCandidateService candidateService = new RecordingCandidateService(events);
        RecordingDecisionService decisionService = new RecordingDecisionService(events);
        RecordingMessageComposer messageComposer = new RecordingMessageComposer(events);
        RecordingDispatchService dispatchService = new RecordingDispatchService(events);

        new ProactiveScheduler(
                        config,
                        observationService,
                        candidateService,
                        decisionService,
                        messageComposer,
                        dispatchService,
                        new RecordingRepository())
                .tick();

        assertThat(events).containsExactly("observe", "candidate", "decide", "compose", "dispatch");
        assertThat(observationService.lastContext.getTickId()).startsWith("proactive-");
        assertThat(candidateService.lastObservations).hasSize(1);
        assertThat(decisionService.lastCandidates).hasSize(1);
        assertThat(dispatchService.lastMessage).isEqualTo("主动协作：要不要继续？");
    }

    @Test
    void shouldPersistFailureObservationWhenTickThrows() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        AppConfig config = config(true);
        RecordingObservationService observationService = new RecordingObservationService();
        observationService.fail = true;

        new ProactiveScheduler(
                        config,
                        observationService,
                        new RecordingCandidateService(),
                        new RecordingDecisionService(),
                        new RecordingMessageComposer(),
                        new RecordingDispatchService(),
                        repository)
                .tickSafe();

        assertThat(repository.savedObservations).extracting(ProactiveObservationRecord::getStatus)
                .containsExactly("FAILED");
        assertThat(repository.savedObservations.get(0).getError()).contains("boom");
    }

    /** 构造测试配置。 */
    private static AppConfig config(boolean enabled) {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(enabled);
        config.getProactive().setIntervalMinutes(enabled ? 30 : 0);
        config.getProactive().setInitialDelaySeconds(0);
        config.getProactive().setMaxContactsPerTick(1);
        return config;
    }

    /** 记录观测调用的测试服务。 */
    private static class RecordingObservationService extends ProactiveObservationService {
        /** 调用事件。 */
        private final List<String> events;

        /** 调用次数。 */
        private int calls;

        /** 最近上下文。 */
        private ProactiveTickContext lastContext;

        /** 是否抛出异常。 */
        private boolean fail;

        /** 创建无事件记录的观测服务。 */
        private RecordingObservationService() {
            this(new ArrayList<String>());
        }

        /** 创建带事件记录的观测服务。 */
        private RecordingObservationService(List<String> events) {
            super(null, Collections.emptyList());
            this.events = events;
        }

        @Override
        public List<ProactiveObservationRecord> collectAll(ProactiveTickContext context) throws Exception {
            calls++;
            lastContext = context;
            events.add("observe");
            if (fail) {
                throw new IllegalStateException("boom observation");
            }
            ProactiveObservationRecord record = new ProactiveObservationRecord();
            record.setObservationId("observation-a");
            record.setTickId(context.getTickId());
            record.setCollector("test");
            record.setStatus("COLLECTED");
            record.setCreatedAt(context.getNowMillis());
            return Collections.singletonList(record);
        }
    }

    /** 记录候选生成调用的测试服务。 */
    private static class RecordingCandidateService extends ProactiveCandidateService {
        /** 调用事件。 */
        private final List<String> events;

        /** 最近观测。 */
        private List<ProactiveObservationRecord> lastObservations;

        /** 创建无事件记录的候选服务。 */
        private RecordingCandidateService() {
            this(new ArrayList<String>());
        }

        /** 创建带事件记录的候选服务。 */
        private RecordingCandidateService(List<String> events) {
            super(null);
            this.events = events;
        }

        @Override
        public List<ProactiveCandidateRecord> generate(
                ProactiveTickContext context, List<ProactiveObservationRecord> observations) {
            events.add("candidate");
            lastObservations = observations;
            ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
            candidate.setCandidateId("candidate-a");
            candidate.setSourceKey("WEIXIN:room:user");
            return Collections.singletonList(candidate);
        }
    }

    /** 记录决策调用的测试服务。 */
    private static class RecordingDecisionService extends ProactiveDecisionService {
        /** 调用事件。 */
        private final List<String> events;

        /** 最近候选。 */
        private List<ProactiveCandidateRecord> lastCandidates;

        /** 创建无事件记录的决策服务。 */
        private RecordingDecisionService() {
            this(new ArrayList<String>());
        }

        /** 创建带事件记录的决策服务。 */
        private RecordingDecisionService(List<String> events) {
            super(null);
            this.events = events;
        }

        @Override
        public List<ProactiveDecision> decide(
                ProactiveTickContext context,
                List<ProactiveCandidateRecord> candidates,
                List<ProactiveObservationRecord> observations) {
            events.add("decide");
            lastCandidates = candidates;
            ProactiveDecision decision = new ProactiveDecision();
            decision.setDecisionId("decision-a");
            decision.setTickId(context.getTickId());
            decision.setCandidateId(candidates.get(0).getCandidateId());
            decision.setSourceKey(candidates.get(0).getSourceKey());
            decision.setDecision("SEND");
            decision.setCandidate(candidates.get(0));
            decision.setCreatedAt(context.getNowMillis());
            return Collections.singletonList(decision);
        }
    }

    /** 记录文案生成调用的测试服务。 */
    private static class RecordingMessageComposer extends ProactiveMessageComposer {
        /** 调用事件。 */
        private final List<String> events;

        /** 创建无事件记录的文案服务。 */
        private RecordingMessageComposer() {
            this(new ArrayList<String>());
        }

        /** 创建带事件记录的文案服务。 */
        private RecordingMessageComposer(List<String> events) {
            this.events = events;
        }

        @Override
        public String compose(ProactiveTickContext context, ProactiveDecision decision) {
            events.add("compose");
            return "主动协作：要不要继续？";
        }
    }

    /** 记录投递调用的测试服务。 */
    private static class RecordingDispatchService extends ProactiveDispatchService {
        /** 调用事件。 */
        private final List<String> events;

        /** 最近消息。 */
        private String lastMessage;

        /** 创建无事件记录的投递服务。 */
        private RecordingDispatchService() {
            this(new ArrayList<String>());
        }

        /** 创建带事件记录的投递服务。 */
        private RecordingDispatchService(List<String> events) {
            super(null, null, null);
            this.events = events;
        }

        @Override
        public ProactiveDecisionRecord dispatch(ProactiveDecision decision, String message) {
            events.add("dispatch");
            lastMessage = message;
            ProactiveDecisionRecord record = new ProactiveDecisionRecord();
            record.setDecisionId(decision.getDecisionId());
            record.setDeliveryStatus("SENT");
            record.setMessage(message);
            return record;
        }
    }

    /** 记录失败观测的测试仓储。 */
    private static class RecordingRepository implements ProactiveRepository {
        /** 保存的失败观测。 */
        private final List<ProactiveObservationRecord> savedObservations =
                new ArrayList<ProactiveObservationRecord>();

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {
            savedObservations.add(observation);
        }

        @Override
        public void saveCandidate(ProactiveCandidateRecord candidate) {}

        @Override
        public ProactiveCandidateRecord findRecentCandidateByDedup(
                String dedupKey, String stateHash, long nowMillis) {
            return null;
        }

        @Override
        public List<ProactiveCandidateRecord> listPendingCandidates(long nowMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void markCandidateStatus(
                String candidateId, String status, String decisionId, long updatedAt) {}

        @Override
        public void saveDecision(ProactiveDecisionRecord decision) {}

        @Override
        public int countSentSince(String sourceKey, long sinceMillis) {
            return 0;
        }

        @Override
        public Long findLastSentAt(String sourceKey) {
            return null;
        }

        @Override
        public com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord findSnapshot(
                String sourceType, String sourceRef) {
            return null;
        }

        @Override
        public void saveSnapshot(
                com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord snapshot) {}

        @Override
        public List<ProactiveDecisionRecord> listRecentDecisions(int limit) {
            return Collections.emptyList();
        }
    }
}
