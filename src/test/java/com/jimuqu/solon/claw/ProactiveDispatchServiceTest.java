package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

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
        CapturingDeliveryService deliveryService = new CapturingDeliveryService();
        InMemoryProactiveRepository proactiveRepository = new InMemoryProactiveRepository();
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
        assertThat(proactiveRepository.savedDecisions)
                .extracting(ProactiveDecisionRecord::getMessage)
                .containsExactly("主动协作：要不要我看一下？");
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

        assertThat(deliveryService.requests.get(0).getPlatform()).isEqualTo(PlatformType.FEISHU);
        assertThat(deliveryService.requests.get(0).getChatId()).isEqualTo("fs-room");
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
        assertThat(proactiveRepository.savedDecisions)
                .extracting(ProactiveDecisionRecord::getDeliveryStatus)
                .containsExactly("FAILED");
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

        @Override
        public void deliver(DeliveryRequest request) throws Exception {
            requests.add(request);
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
        public PairingRequestRecord getAdminClaimRequest(PlatformType platform) {
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
        public List<PairingRequestRecord> listPairingRequests(
                PlatformType platform, boolean includeAdminClaim) {
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
        /** 保存的决策记录。 */
        private final List<ProactiveDecisionRecord> savedDecisions =
                new ArrayList<ProactiveDecisionRecord>();

        @Override
        public void saveObservation(ProactiveObservationRecord observation) {}

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
        public void saveDecision(ProactiveDecisionRecord decision) {
            savedDecisions.add(decision);
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
