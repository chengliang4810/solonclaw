package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservation;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.core.model.ProactiveTickContext;
import com.jimuqu.solon.claw.proactive.ProactiveObservationCollector;
import com.jimuqu.solon.claw.proactive.ProactiveObservationService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 主动协作观测服务测试。 */
public class ProactiveObservationServiceTest {
    @Test
    void shouldContinueWhenCollectorFailsAndPersistFailureRecord() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        List<ProactiveObservationCollector> collectors =
                Arrays.asList(
                        new ProactiveObservationCollector() {
                            @Override
                            public String name() {
                                return "session-signals";
                            }

                            @Override
                            public boolean enabled(AppConfig config) {
                                return true;
                            }

                            @Override
                            public List<ProactiveObservation> collect(
                                    ProactiveTickContext context) {
                                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                                payload.put("token", "sk-test-abcdefghijklmnopqrstuvwxyz");
                                payload.put(
                                        "notes",
                                        "这是一个很长的上下文片段，应该被裁剪，"
                                                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
                                ProactiveObservation observation = new ProactiveObservation();
                                observation.setSourceKey("SESSION:alpha");
                                observation.setSummary("发现新会话线索");
                                observation.setPayload(payload);
                                observation.setStatus("NEW");
                                return Arrays.asList(observation);
                            }
                        },
                        new ProactiveObservationCollector() {
                            @Override
                            public String name() {
                                return "broken-collector";
                            }

                            @Override
                            public boolean enabled(AppConfig config) {
                                return true;
                            }

                            @Override
                            public List<ProactiveObservation> collect(ProactiveTickContext context)
                                    throws Exception {
                                throw new IllegalStateException(
                                        "Authorization: Bearer sk-test-broken-abcdefghijklmnopqrstuvwxyz");
                            }
                        },
                        new ProactiveObservationCollector() {
                            @Override
                            public String name() {
                                return "disabled-collector";
                            }

                            @Override
                            public boolean enabled(AppConfig config) {
                                return false;
                            }

                            @Override
                            public List<ProactiveObservation> collect(
                                    ProactiveTickContext context) {
                                throw new AssertionError("disabled collector should not run");
                            }
                        },
                        new ProactiveObservationCollector() {
                            @Override
                            public String name() {
                                return "calendar-signals";
                            }

                            @Override
                            public boolean enabled(AppConfig config) {
                                return true;
                            }

                            @Override
                            public List<ProactiveObservation> collect(
                                    ProactiveTickContext context) {
                                ProactiveObservation observation = new ProactiveObservation();
                                observation.setCollector("calendar-signals");
                                observation.setSourceKey("CAL:milestone");
                                observation.setSummary("里程碑仍未跟进");
                                observation.setPayload(new LinkedHashMap<String, Object>());
                                observation.setStatus("OPEN");
                                return Arrays.asList(observation);
                            }
                        });

        ProactiveObservationService service =
                new ProactiveObservationService(repository, collectors);

        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId("tick-001");
        context.setNowMillis(123456789L);
        context.setConfig(new AppConfig());
        context.setHomeChannels(Arrays.asList(homeChannel("home-a")));
        context.setLastDecisionSummaries(Arrays.asList(lastDecision("decision-1", "SEND")));

        List<ProactiveObservationRecord> records = service.collectAll(context);

        assertThat(records).hasSize(3);
        assertThat(repository.savedObservations).hasSize(3);
        assertThat(records)
                .extracting(ProactiveObservationRecord::getCollector)
                .containsExactly("session-signals", "broken-collector", "calendar-signals");

        ProactiveObservationRecord success = records.get(0);
        assertThat(success.getObservationId()).isNotBlank();
        assertThat(success.getTickId()).isEqualTo("tick-001");
        assertThat(success.getStatus()).isEqualTo("NEW");
        assertThat(String.valueOf(success.getPayload().get("token"))).isEqualTo("***");
        assertThat(String.valueOf(success.getPayload().get("notes"))).contains("[truncated");

        ProactiveObservationRecord failure = records.get(1);
        assertThat(failure.getStatus()).isEqualTo("FAILED");
        assertThat(failure.getSourceKey()).isEqualTo("broken-collector");
        assertThat(failure.getError()).contains("Authorization: Bearer ***");

        ProactiveObservationRecord secondSuccess = records.get(2);
        assertThat(secondSuccess.getStatus()).isEqualTo("OPEN");
        assertThat(secondSuccess.getError()).isNull();
    }

    @Test
    void shouldPropagateRepositoryFailureForSuccessfulObservation() {
        FailingObservationRepository repository = new FailingObservationRepository();
        ProactiveObservationService service =
                new ProactiveObservationService(
                        repository,
                        Arrays.asList(
                                new SingleObservationCollector(
                                        "healthy-collector", observationWithBlankSource())));

        assertThatThrownBy(() -> service.collectAll(context("tick-write-failed")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write failed");

        assertThat(repository.savedObservations).hasSize(1);
        assertThat(repository.savedObservations.get(0).getStatus()).isEqualTo("COLLECTED");
    }

    @Test
    void shouldNormalizeBlankCollectorNameAndNullObservationPayload() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveObservationService service =
                new ProactiveObservationService(
                        repository,
                        Arrays.asList(
                                new SingleObservationCollector("   ", null),
                                new SingleObservationCollector(
                                        "  ", observationWithBlankSource())));

        List<ProactiveObservationRecord> records = service.collectAll(context("tick-boundary"));

        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(ProactiveObservationRecord::getCollector)
                .containsExactly("unknown", "unknown");
        assertThat(records)
                .extracting(ProactiveObservationRecord::getSourceKey)
                .containsExactly("unknown", "unknown");
        assertThat(records.get(0).getStatus()).isEqualTo("COLLECTED");
        assertThat(records.get(0).getPayload()).isEmpty();
        assertThat(records.get(1).getPayload()).isEmpty();
    }

    private static HomeChannelRecord homeChannel(String chatId) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setChatId(chatId);
        return record;
    }

    private static ProactiveDecisionRecord lastDecision(String id, String decision) {
        ProactiveDecisionRecord record = new ProactiveDecisionRecord();
        record.setDecisionId(id);
        record.setDecision(decision);
        return record;
    }

    private static ProactiveTickContext context(String tickId) {
        ProactiveTickContext context = new ProactiveTickContext();
        context.setTickId(tickId);
        context.setNowMillis(123456789L);
        context.setConfig(new AppConfig());
        return context;
    }

    private static ProactiveObservation observationWithBlankSource() {
        ProactiveObservation observation = new ProactiveObservation();
        observation.setSourceKey(" ");
        observation.setSummary("边界观测");
        observation.setStatus(null);
        observation.setPayload(null);
        return observation;
    }

    /** 固定返回一个观测结果的测试采集器。 */
    private static final class SingleObservationCollector implements ProactiveObservationCollector {
        /** 测试采集器名称。 */
        private final String name;

        /** 测试采集器返回的观测结果。 */
        private final ProactiveObservation observation;

        /**
         * 创建固定观测采集器。
         *
         * @param name 采集器名称。
         * @param observation 返回的观测结果。
         */
        private SingleObservationCollector(String name, ProactiveObservation observation) {
            this.name = name;
            this.observation = observation;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean enabled(AppConfig config) {
            return true;
        }

        @Override
        public List<ProactiveObservation> collect(ProactiveTickContext context) {
            return Arrays.asList(observation);
        }
    }

    /** 仅用于本测试的内存主动协作仓储。 */
    private static class InMemoryProactiveRepository implements ProactiveRepository {
        /** 记录所有保存过的观测结果，便于断言。 */
        protected final List<ProactiveObservationRecord> savedObservations =
                new ArrayList<ProactiveObservationRecord>();

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {
            savedObservations.add(observation);
        }

        @Override
        public void saveCandidate(
                com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord candidate)
                throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord findRecentCandidateByDedup(
                String dedupKey, String stateHash, long nowMillis) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord>
                listPendingCandidates(long nowMillis, int limit) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markCandidateStatus(
                String candidateId, String status, String decisionId, long updatedAt)
                throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveDecision(ProactiveDecisionRecord decision) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countSentSince(String sourceKey, long sinceMillis) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long findLastSentAt(String sourceKey) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef)
                throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProactiveDecisionRecord> listRecentDecisions(int limit) {
            return new ArrayList<ProactiveDecisionRecord>();
        }
    }

    /** 仅用于验证写入失败会向上传播的仓储。 */
    private static final class FailingObservationRepository extends InMemoryProactiveRepository {
        @Override
        public void saveObservation(ProactiveObservationRecord observation) {
            savedObservations.add(observation);
            throw new IllegalStateException("write failed");
        }
    }
}
