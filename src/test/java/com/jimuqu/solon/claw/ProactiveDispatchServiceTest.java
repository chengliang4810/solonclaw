package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ApprovedUserRecord;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.PairingRateLimitRecord;
import com.jimuqu.solon.claw.core.model.PairingRequestRecord;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecision;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.core.model.ProactiveObservationRecord;
import com.jimuqu.solon.claw.core.model.ProactiveSourceSnapshotRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.proactive.ProactiveDispatchService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 主动协作投递服务测试。 */
public class ProactiveDispatchServiceTest {
    @Test
    void shouldPreferSourcePlatformHomeChannelAndPreserveThreadId() throws Exception {
        InMemoryGatewayPolicyRepository gatewayRepository = new InMemoryGatewayPolicyRepository();
        gatewayRepository.saveHomeChannel(home(PlatformType.WEIXIN, "wx-room", "wx-thread"));
        gatewayRepository.saveHomeChannel(home(PlatformType.FEISHU, "fs-room", "fs-thread"));
        List<String> events = new ArrayList<String>();
        CapturingDeliveryService deliveryService = new CapturingDeliveryService(events);
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository(events);
        ProactiveDecision decision = decision("WEIXIN:source-room:user");

        ProactiveDecisionRecord record =
                new ProactiveDispatchService(
                                gatewayRepository, deliveryService, proactiveRepository)
                        .dispatch(decision, "主动协作：要不要我看一下？");

        assertThat(deliveryService.requests).hasSize(1);
        DeliveryRequest request = deliveryService.requests.get(0);
        assertThat(request.getPlatform()).isEqualTo(PlatformType.WEIXIN);
        assertThat(request.getChatId()).isEqualTo("wx-room");
        assertThat(request.getThreadId()).isEqualTo("wx-thread");
        assertThat(record.getDeliveryStatus()).isEqualTo("SENT");
        assertThat(record.getDeliveryPlatform()).isEqualTo("WEIXIN");
        assertThat(record.getMessage()).isEqualTo("主动协作：要不要我看一下？");
        assertThat(events)
                .containsExactly("save:DELIVERY_PENDING", "deliver", "save:SENT", "candidate:SENT");
    }

    @Test
    void shouldFallbackToDomesticOrderWhenSourceHomeMissing() throws Exception {
        InMemoryGatewayPolicyRepository gatewayRepository = new InMemoryGatewayPolicyRepository();
        gatewayRepository.saveHomeChannel(home(PlatformType.DINGTALK, "dt-room", "dt-thread"));
        gatewayRepository.saveHomeChannel(home(PlatformType.FEISHU, "fs-room", "fs-thread"));
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();

        ProactiveDecisionRecord record =
                new ProactiveDispatchService(
                                gatewayRepository,
                                deliveryService,
                                new InMemoryProactiveRepository())
                        .dispatch(decision("WEIXIN:source-room:user"), "主动协作：要不要继续？");

        DeliveryRequest request = deliveryService.requests.get(0);
        assertThat(request.getPlatform()).isEqualTo(PlatformType.FEISHU);
        assertThat(request.getChatId()).isEqualTo("fs-room");
        assertThat(request.getThreadId()).isEqualTo("fs-thread");
        assertThat(request.getReplyToMessageId()).isEqualTo("fs-thread");
        assertThat(record.getDeliveryStatus()).isEqualTo("SENT");
    }

    @Test
    void shouldRecordFailureWhenDeliveryThrows() throws Exception {
        InMemoryGatewayPolicyRepository gatewayRepository = new InMemoryGatewayPolicyRepository();
        gatewayRepository.saveHomeChannel(home(PlatformType.WEIXIN, "wx-room", "wx-thread"));
        FailingDeliveryService deliveryService = new FailingDeliveryService();
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();

        ProactiveDecisionRecord record =
                new ProactiveDispatchService(
                                gatewayRepository, deliveryService, proactiveRepository)
                        .dispatch(decision("WEIXIN:source-room:user"), "主动协作：要不要继续？");

        assertThat(record.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(record.getDeliveryError()).contains("boom");
        assertThat(proactiveRepository.savedStatuses).containsExactly("DELIVERY_PENDING", "FAILED");
        assertThat(proactiveRepository.candidateStatuses).containsExactly("PENDING");
    }

    @Test
    void shouldReturnCandidateToPendingWhenMessageOrHomeIsMissing() throws Exception {
        InMemoryGatewayPolicyRepository gatewayRepository = new InMemoryGatewayPolicyRepository();
        InMemoryProactiveRepository blankRepository = new InMemoryProactiveRepository();
        InMemoryProactiveRepository missingHomeRepository = new InMemoryProactiveRepository();

        ProactiveDecisionRecord blank =
                new ProactiveDispatchService(
                                gatewayRepository, new CapturingDeliveryService(), blankRepository)
                        .dispatch(decision("WEIXIN:source-room:user"), "");
        ProactiveDecisionRecord missingHome =
                new ProactiveDispatchService(
                                gatewayRepository,
                                new CapturingDeliveryService(),
                                missingHomeRepository)
                        .dispatch(decision("WEIXIN:source-room:user"), "主动协作：继续吗？");

        assertThat(blank.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(blank.getDeliveryError()).isEqualTo("empty_message");
        assertThat(blankRepository.candidateStatuses).containsExactly("PENDING");
        assertThat(missingHome.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(missingHome.getDeliveryError()).isEqualTo("no_home_channel");
        assertThat(missingHomeRepository.candidateStatuses).containsExactly("PENDING");
    }

    @Test
    void shouldRetryUnknownDeliveryOnlyOnceWithOriginalTarget() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId("candidate-unknown");
        candidate.setSourceKey("WEIXIN:source-room:user");
        candidate.setStatus("DELIVERY_UNKNOWN");
        candidate.setLastDecisionId("decision-unknown");
        repository.saveCandidate(candidate);

        ProactiveDecisionRecord original = new ProactiveDecisionRecord();
        original.setDecisionId("decision-unknown");
        original.setCandidateId(candidate.getCandidateId());
        original.setSourceKey("WEIXIN:source-room:user");
        original.setDecision("SEND");
        original.setMessage("可能已经送达的消息");
        original.setDeliveryPlatform("WEIXIN");
        original.setDeliveryChatId("original-room");
        original.setDeliveryThreadId("original-thread");
        original.setDeliveryStatus("DELIVERY_PENDING");
        repository.saveDecision(original);
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();
        ProactiveDispatchService service =
                new ProactiveDispatchService(null, deliveryService, repository);

        ProactiveDecisionRecord retried =
                service.retryUnknown(candidate.getCandidateId(), original.getSourceKey());

        assertThat(retried.getDeliveryStatus()).isEqualTo("SENT");
        assertThat(deliveryService.requests).hasSize(1);
        assertThat(deliveryService.requests.get(0).getChatId()).isEqualTo("original-room");
        assertThat(deliveryService.requests.get(0).getText()).isEqualTo("可能已经送达的消息");
        assertThat(repository.candidate.getStatus()).isEqualTo("SENT");
        assertThatThrownBy(
                        () ->
                                service.retryUnknown(
                                        candidate.getCandidateId(), original.getSourceKey()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不再处于投递结果待确认状态");
        assertThat(deliveryService.requests).hasSize(1);
    }

    @Test
    void shouldRestoreUnknownWhenRetryAuditSaveFails() throws Exception {
        InMemoryProactiveRepository repository = new InMemoryProactiveRepository();
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId("candidate-audit-failure");
        candidate.setSourceKey("WEIXIN:room:user");
        candidate.setStatus("DELIVERY_UNKNOWN");
        candidate.setLastDecisionId("decision-audit-failure");
        repository.saveCandidate(candidate);
        ProactiveDecisionRecord original = new ProactiveDecisionRecord();
        original.setDecisionId(candidate.getLastDecisionId());
        original.setCandidateId(candidate.getCandidateId());
        original.setSourceKey(candidate.getSourceKey());
        original.setDecision("SEND");
        original.setMessage("重试审计失败测试");
        original.setDeliveryPlatform("WEIXIN");
        original.setDeliveryChatId("room");
        original.setDeliveryStatus("DELIVERY_PENDING");
        repository.saveDecision(original);
        repository.failPendingAudit = true;

        ProactiveDecisionRecord result =
                new ProactiveDispatchService(null, new CapturingDeliveryService(), repository)
                        .retryUnknown(candidate.getCandidateId(), candidate.getSourceKey());

        assertThat(result.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(repository.candidate.getStatus()).isEqualTo("DELIVERY_UNKNOWN");
    }

    /** 构造测试 home channel。 */
    private static HomeChannelRecord home(PlatformType platform, String chatId, String threadId) {
        HomeChannelRecord record = new HomeChannelRecord();
        record.setPlatform(platform);
        record.setChatId(chatId);
        record.setThreadId(threadId);
        record.setChatName("主动协作测试");
        record.setUpdatedAt(1_800_000_000_000L);
        return record;
    }

    /** 构造发送决策。 */
    private static ProactiveDecision decision(String sourceKey) {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId("candidate-dispatch");
        candidate.setSourceType("session");
        candidate.setSourceRef("session-a");
        candidate.setSourceKey(sourceKey);
        candidate.setTitle("投递测试");
        candidate.setTopic("work_continuation");
        candidate.setStatus("APPROVED");

        ProactiveDecision decision = new ProactiveDecision();
        decision.setDecisionId("decision-dispatch");
        decision.setTickId("tick-dispatch");
        decision.setCandidateId(candidate.getCandidateId());
        decision.setSourceKey(sourceKey);
        decision.setDecision("SEND");
        decision.setReason("deterministic_allow");
        decision.setMessageIntent("询问用户是否继续");
        decision.setSensitivity("normal");
        decision.setCandidate(candidate);
        decision.setMetadata(new LinkedHashMap<String, Object>());
        decision.setCreatedAt(1_800_000_000_000L);
        return decision;
    }

    /** 记录投递请求的测试投递服务。 */
    private static class CapturingDeliveryService implements DeliveryService {
        /** 收到的投递请求。 */
        private final List<DeliveryRequest> requests = new ArrayList<DeliveryRequest>();

        /** 投递状态机事件。 */
        private final List<String> events;

        /** 创建不记录事件的投递服务。 */
        private CapturingDeliveryService() {
            this(null);
        }

        /** 创建记录状态机事件的投递服务。 */
        private CapturingDeliveryService(List<String> events) {
            this.events = events;
        }

        @Override
        public void deliver(DeliveryRequest request) throws Exception {
            requests.add(request);
            if (events != null) {
                events.add("deliver");
            }
        }

        @Override
        public List<ChannelStatus> statuses() {
            return Collections.emptyList();
        }
    }

    /** 固定抛错的测试投递服务。 */
    private static class FailingDeliveryService extends CapturingDeliveryService {
        @Override
        public void deliver(DeliveryRequest request) throws Exception {
            throw new IllegalStateException("boom delivery token=ghp_dispatchsecret12345");
        }
    }

    /** 内存版 home channel 仓储。 */
    private static class InMemoryGatewayPolicyRepository implements GatewayPolicyRepository {
        /** home channel 记录。 */
        private final Map<PlatformType, HomeChannelRecord> homes =
                new LinkedHashMap<PlatformType, HomeChannelRecord>();

        @Override
        public HomeChannelRecord getHomeChannel(PlatformType platform) {
            return homes.get(platform);
        }

        @Override
        public List<HomeChannelRecord> listHomeChannels() {
            return new ArrayList<HomeChannelRecord>(homes.values());
        }

        @Override
        public void saveHomeChannel(HomeChannelRecord record) {
            homes.put(record.getPlatform(), record);
        }

        @Override
        public PlatformAdminRecord getPlatformAdmin(PlatformType platform) {
            return null;
        }

        @Override
        public boolean createPlatformAdminIfAbsent(PlatformAdminRecord record) {
            return false;
        }

        @Override
        public ApprovedUserRecord getApprovedUser(PlatformType platform, String userId) {
            return null;
        }

        @Override
        public void saveApprovedUser(ApprovedUserRecord record) {}

        @Override
        public void revokeApprovedUser(PlatformType platform, String userId) {}

        @Override
        public List<ApprovedUserRecord> listApprovedUsers(PlatformType platform) {
            return Collections.emptyList();
        }

        @Override
        public int countApprovedUsers(PlatformType platform) {
            return 0;
        }

        @Override
        public PairingRequestRecord getPairingRequest(PlatformType platform, String code) {
            return null;
        }

        @Override
        public PairingRequestRecord getLatestUserPairingRequest(
                PlatformType platform, String userId) {
            return null;
        }

        @Override
        public void savePairingRequest(PairingRequestRecord record) {}

        @Override
        public void deletePairingRequest(PlatformType platform, String code) {}

        @Override
        public void deleteExpiredPairingRequests(PlatformType platform, long nowEpochMillis) {}

        @Override
        public List<PairingRequestRecord> listPairingRequests(PlatformType platform) {
            return Collections.emptyList();
        }

        @Override
        public PairingRateLimitRecord getPairingRateLimit(PlatformType platform, String userId) {
            return null;
        }

        @Override
        public void savePairingRateLimit(PairingRateLimitRecord record) {}
    }

    /** 内存版主动协作仓储。 */
    private static class InMemoryProactiveRepository implements ProactiveRepository {
        /** 当前候选，供人工重试状态机测试。 */
        private ProactiveCandidateRecord candidate;

        /** 保存的决策记录。 */
        private final List<ProactiveDecisionRecord> savedDecisions =
                new ArrayList<ProactiveDecisionRecord>();

        /** 每次保存时的投递状态快照。 */
        private final List<String> savedStatuses = new ArrayList<String>();

        /** 候选状态变更快照。 */
        private final List<String> candidateStatuses = new ArrayList<String>();

        /** 投递状态机事件。 */
        private final List<String> events;

        /** 是否模拟人工重试进入投递前的审计写入失败。 */
        private boolean failPendingAudit;

        /** 创建不记录事件的仓储。 */
        private InMemoryProactiveRepository() {
            this(null);
        }

        /** 创建记录状态机事件的仓储。 */
        private InMemoryProactiveRepository(List<String> events) {
            this.events = events;
        }

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {}

        @Override
        public void saveCandidate(ProactiveCandidateRecord candidate) {
            this.candidate = candidate;
        }

        @Override
        public ProactiveCandidateRecord findCandidate(String candidateId) {
            return candidate != null && candidate.getCandidateId().equals(candidateId)
                    ? candidate
                    : null;
        }

        @Override
        public ProactiveDecisionRecord findDecision(String decisionId) {
            for (int index = savedDecisions.size() - 1; index >= 0; index--) {
                ProactiveDecisionRecord decision = savedDecisions.get(index);
                if (decisionId.equals(decision.getDecisionId())) {
                    return decision;
                }
            }
            return null;
        }

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
                String candidateId, String status, String decisionId, long updatedAt) {
            candidateStatuses.add(status);
            if (events != null) {
                events.add("candidate:" + status);
            }
        }

        @Override
        public boolean compareAndSetCandidateStatus(
                String candidateId,
                String expectedStatus,
                String expectedDecisionId,
                String status,
                long updatedAt) {
            if (candidate != null) {
                candidate.setStatus(status);
            }
            markCandidateStatus(candidateId, status, expectedDecisionId, updatedAt);
            return true;
        }

        @Override
        public boolean claimDeliveryUnknownRetry(
                String candidateId,
                String expectedDecisionId,
                String expectedSourceKey,
                String retryDecisionId,
                long updatedAt) {
            if (candidate == null
                    || !candidateId.equals(candidate.getCandidateId())
                    || !"DELIVERY_UNKNOWN".equals(candidate.getStatus())
                    || !expectedDecisionId.equals(candidate.getLastDecisionId())) {
                return false;
            }
            if (expectedSourceKey != null && !expectedSourceKey.equals(candidate.getSourceKey())) {
                return false;
            }
            candidate.setStatus("DELIVERY_RETRYING");
            candidate.setLastDecisionId(retryDecisionId);
            candidate.setUpdatedAt(updatedAt);
            return true;
        }

        @Override
        public void saveDecision(ProactiveDecisionRecord decision) {
            if (failPendingAudit
                    && "DELIVERY_PENDING".equalsIgnoreCase(decision.getDeliveryStatus())) {
                throw new IllegalStateException("audit unavailable");
            }
            savedDecisions.add(decision);
            savedStatuses.add(decision.getDeliveryStatus());
            if (events != null) {
                events.add("save:" + decision.getDeliveryStatus());
            }
        }

        @Override
        public int countSentSince(String sourceKey, long sinceMillis) {
            return 0;
        }

        @Override
        public Long findLastSentAt(String sourceKey) {
            return null;
        }

        @Override
        public ProactiveSourceSnapshotRecord findSnapshot(String sourceType, String sourceRef) {
            return null;
        }

        @Override
        public void saveSnapshot(ProactiveSourceSnapshotRecord snapshot) {}

        @Override
        public List<ProactiveDecisionRecord> listRecentDecisions(int limit) {
            return savedDecisions;
        }
    }
}
