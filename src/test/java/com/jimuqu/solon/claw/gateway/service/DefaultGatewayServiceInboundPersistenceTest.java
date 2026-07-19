package com.jimuqu.solon.claw.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.enums.ProcessingOutcome;
import com.jimuqu.solon.claw.core.model.ChannelInboundMessageRecord;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.ChannelInboundMessageRepository;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 验证真实渠道入站消息的持久化、幂等和安全恢复。 */
public class DefaultGatewayServiceInboundPersistenceTest {
    /** 相同平台原始消息只允许一个服务实例执行 Agent 主链。 */
    @Test
    void shouldPersistAuthorizedInboundMessageWithoutRerunningOrchestrator() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        AtomicReference<String> statusAtDelivery = new AtomicReference<String>();
        String messageKey = "WEIXIN:default:id:chat-1:message-1";
        DeliveryService deliveryService =
                recordingDeliveryService(
                        env.channelInboundMessageRepository,
                        messageKey,
                        deliveryCalls,
                        statusAtDelivery,
                        false);
        ConversationOrchestrator orchestrator = replyingOrchestrator(orchestratorCalls);
        DefaultGatewayService firstService = service(env, orchestrator, deliveryService);
        DefaultGatewayService secondService = service(env, orchestrator, deliveryService);

        GatewayReply first = firstService.handle(realMessage("message-1"));
        GatewayReply duplicate = secondService.handle(realMessage("message-1"));
        ChannelInboundMessageRecord record =
                env.channelInboundMessageRepository.findByMessageKey(messageKey);

        assertThat(first.getContent()).isEqualTo("ok");
        assertThat(duplicate).isNull();
        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(statusAtDelivery.get()).isEqualTo(ChannelInboundMessageRecord.STATUS_DELIVERING);
        assertThat(record.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(record.getReplyJson()).contains("\"content\":\"ok\"");
    }

    /** 未授权渠道正文必须在同步 admission 阶段被拒绝，不能进入持久化总账。 */
    @Test
    void shouldRejectUnauthorizedInboundBeforePersistence() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls));
        GatewayMessage unauthorized =
                new GatewayMessage(
                        PlatformType.WEIXIN, "chat-unauthorized", "user-unauthorized", "私密正文");
        unauthorized.setPlatformMessageId("unauthorized-message");
        unauthorized.setReplyToMessageId("unauthorized-message");

        assertThat(service.admitInbound(unauthorized)).isFalse();

        assertThat(
                        env.channelInboundMessageRepository.findByMessageKey(
                                "WEIXIN:default:id:chat-unauthorized:unauthorized-message"))
                .isNull();
        assertThat(orchestratorCalls.get()).isZero();
        assertThat(deliveryCalls.get()).isZero();
    }

    /** 外发开始后结果未知必须失败关闭，自动恢复不得重投也不得重跑 Agent。 */
    @Test
    void shouldFailClosedWhenDeliveryOutcomeIsUnknown() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        AtomicReference<String> statusAtDelivery = new AtomicReference<String>();
        String messageKey = "WEIXIN:default:id:chat-1:message-2";
        ConversationOrchestrator orchestrator = replyingOrchestrator(orchestratorCalls);
        DefaultGatewayService failingService =
                service(
                        env,
                        orchestrator,
                        recordingDeliveryService(
                                env.channelInboundMessageRepository,
                                messageKey,
                                deliveryCalls,
                                statusAtDelivery,
                                true));

        failingService.handle(realMessage("message-2"));
        ChannelInboundMessageRecord failedDelivery =
                env.channelInboundMessageRepository.findByMessageKey(messageKey);
        assertThat(failedDelivery.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_FAILED);
        assertThat(failedDelivery.getLastError()).contains("禁止自动重放");

        DefaultGatewayService recoveredService =
                service(
                        env,
                        orchestrator,
                        recordingDeliveryService(
                                env.channelInboundMessageRepository,
                                messageKey,
                                deliveryCalls,
                                statusAtDelivery,
                                false));
        recoveredService.recoverFailedInboundDeliveries(
                recoveredService.profileName(), PlatformType.WEIXIN);

        ChannelInboundMessageRecord unchanged =
                env.channelInboundMessageRepository.findByMessageKey(messageKey);
        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(unchanged.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_FAILED);
    }

    /** 回复没有成功写入 processed 状态时必须禁止外发，避免重启后重复投递。 */
    @Test
    void shouldNotDeliverReplyWhenProcessedPersistenceFails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        AtomicInteger learningCalls = new AtomicInteger();
        AtomicInteger scheduledCalls = new AtomicInteger();
        CountDownLatch scheduled = new CountDownLatch(1);
        SessionRecord session =
                env.sessionRepository.bindNewSession(realMessage("session-seed").sourceKey());
        ChannelInboundMessageRepository repository =
                new FailingProcessedRepository(env.channelInboundMessageRepository);
        ConversationOrchestrator orchestrator =
                goalReplyingOrchestrator(
                        orchestratorCalls, scheduledCalls, scheduled, session.getSessionId());
        SkillLearningService learningService =
                (storedSession, message, reply) -> learningCalls.incrementAndGet();
        DefaultGatewayService service =
                new DefaultGatewayService(
                        env.commandService,
                        orchestrator,
                        countingDeliveryService(deliveryCalls),
                        env.sessionRepository,
                        repository,
                        env.gatewayAuthorizationService,
                        learningService,
                        null,
                        null,
                        env.appConfig);

        GatewayReply reply = service.handle(realMessage("message-persistence-failed"));
        ChannelInboundMessageRecord record =
                env.channelInboundMessageRepository.findByMessageKey(
                        "WEIXIN:default:id:chat-1:message-persistence-failed");

        assertThat(reply.getContent()).isEqualTo("ok");
        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isZero();
        assertThat(learningCalls.get()).isZero();
        assertThat(scheduled.await(200L, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(scheduledCalls.get()).isZero();
        assertThat(record.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_PROCESSING);
        assertThat(record.getReplyJson()).isNull();
    }

    /** 回复已外发但 completed 写入失败时必须转为不可重放，重启不得再次投递。 */
    @Test
    void shouldNotReplayReplyWhenCompletionPersistenceFails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        String messageKey = "WEIXIN:default:id:chat-1:message-completion-failed";
        ChannelInboundMessageRepository repository =
                new FailingCompletedRepository(env.channelInboundMessageRepository);
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls),
                        repository);

        service.handle(realMessage("message-completion-failed"));
        ChannelInboundMessageRecord failed =
                env.channelInboundMessageRepository.findByMessageKey(messageKey);
        service.recoverInboundDeliveries(service.profileName());

        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(failed.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_FAILED);
        assertThat(failed.getLastError()).contains("禁止自动重放");
        assertThat(env.channelInboundMessageRepository.listProcessed("default", 10)).isEmpty();
    }

    /** 编排器没有取得终态输出权时不得外发空回复，并把持久化入站收敛为不可重放取消态。 */
    @Test
    void shouldConvergeSuppressedInboundWithoutDeliveringEmptyReply() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger deliveryCalls = new AtomicInteger();
        HydratingAdapter adapter = new HydratingAdapter(false);
        ConversationOrchestrator suppressedOrchestrator =
                new ConversationOrchestrator() {
                    /** 模拟旧 run 在单写者租约检查中被抑制。 */
                    @Override
                    public GatewayReply handleIncoming(GatewayMessage message) {
                        return null;
                    }

                    /** 本测试不使用调度入口。 */
                    @Override
                    public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                        return null;
                    }

                    /** 本测试不使用恢复入口。 */
                    @Override
                    public GatewayReply resumePending(String sourceKey) {
                        return null;
                    }
                };
        DefaultGatewayService service =
                service(
                        env,
                        suppressedOrchestrator,
                        countingDeliveryService(deliveryCalls),
                        env.channelInboundMessageRepository,
                        adapters(adapter));

        GatewayReply reply = service.handle(realMessage("suppressed-no-terminal"));
        ChannelInboundMessageRecord record =
                env.channelInboundMessageRepository.findByMessageKey(
                        "WEIXIN:default:id:chat-1:suppressed-no-terminal");

        assertThat(reply).isNull();
        assertThat(deliveryCalls.get()).isZero();
        assertThat(record.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_FAILED);
        assertThat(record.getReplyJson()).isNull();
        assertThat(record.getLastError()).contains("输出所有权已撤销");
        assertThat(adapter.processingOutcome.get()).isEqualTo(ProcessingOutcome.CANCELLED);
    }

    /** 终态已经在租约内提交后，即使编排器外围再抛异常，也只能投递一次且不得改写完成态。 */
    @Test
    void shouldNotRedeliverWhenOrchestratorThrowsAfterTerminalCommit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger deliveryCalls = new AtomicInteger();
        ConversationOrchestrator commitThenFail =
                new ConversationOrchestrator() {
                    /** 先提交真实终态，再模拟提交后的外围异常。 */
                    @Override
                    public GatewayReply handleIncoming(GatewayMessage message) {
                        Boolean committed =
                                message.getReplyCommitter().apply(GatewayReply.ok("once"));
                        if (!Boolean.TRUE.equals(committed)) {
                            throw new IllegalStateException("terminal commit failed unexpectedly");
                        }
                        throw new IllegalStateException("failure after terminal commit");
                    }

                    /** 本测试不使用调度入口。 */
                    @Override
                    public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                        return null;
                    }

                    /** 本测试不使用恢复入口。 */
                    @Override
                    public GatewayReply resumePending(String sourceKey) {
                        return null;
                    }
                };
        DefaultGatewayService service =
                service(env, commitThenFail, countingDeliveryService(deliveryCalls));

        GatewayReply reply = service.handle(realMessage("terminal-then-failure"));
        service.recoverInboundDeliveries(service.profileName());
        ChannelInboundMessageRecord record =
                env.channelInboundMessageRepository.findByMessageKey(
                        "WEIXIN:default:id:chat-1:terminal-then-failure");

        assertThat(reply).isNull();
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(record.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(record.getReplyJson()).contains("\"content\":\"once\"");
        assertThat(env.channelInboundMessageRepository.listProcessed("default", 10)).isEmpty();
    }

    /** 恢复积压超过单批上限时必须持续翻页，不能静默遗漏后续回复。 */
    @Test
    void shouldRecoverEveryProcessedReplyAcrossBatches() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AtomicInteger deliveryCalls = new AtomicInteger();
        AtomicInteger orchestratorCalls = new AtomicInteger();
        for (int index = 0; index < 101; index++) {
            ChannelInboundMessageRecord record = recoveryRecord(index);
            env.channelInboundMessageRepository.saveIfAbsent(record);
            env.channelInboundMessageRepository.markProcessed(
                    record.getIngressId(), "{\"content\":\"recovered\"}", 100L + index);
        }
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls));

        service.recoverInboundDeliveries(service.profileName());

        assertThat(deliveryCalls.get()).isEqualTo(101);
        assertThat(orchestratorCalls.get()).isZero();
        assertThat(env.channelInboundMessageRepository.listProcessed("default", 1)).isEmpty();
    }

    /** 单条已投递回复无法收敛终态时，批次中尚未处理的 processed 认领仍必须释放。 */
    @Test
    void shouldReleaseRemainingClaimsWhenRecoveryCompletionCannotConverge() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ChannelInboundMessageRecord uncertain = recoveryRecord(151);
        ChannelInboundMessageRecord waiting = recoveryRecord(152);
        env.channelInboundMessageRepository.saveIfAbsent(uncertain);
        env.channelInboundMessageRepository.saveIfAbsent(waiting);
        env.channelInboundMessageRepository.markProcessed(
                uncertain.getIngressId(), "{\"content\":\"uncertain\"}", 100L);
        env.channelInboundMessageRepository.markProcessed(
                waiting.getIngressId(), "{\"content\":\"waiting\"}", 101L);
        ChannelInboundMessageRepository repository =
                new InterruptedRecoveryRepository(
                        env.channelInboundMessageRepository, uncertain.getIngressId());
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(new AtomicInteger()),
                        countingDeliveryService(new AtomicInteger()),
                        repository);

        String recoveryToken = service.claimStartupInboundDeliveries(service.profileName());
        service.recoverInboundDeliveries(service.profileName(), recoveryToken);

        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(uncertain.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_DELIVERING);
        assertThat(env.channelInboundMessageRepository.claimStartupDeliveries("default", "next"))
                .isEqualTo(1);
        assertThat(
                        env.channelInboundMessageRepository.listClaimedDeliveries(
                                "default", "next", -1L, "", 10))
                .extracting(ChannelInboundMessageRecord::getIngressId)
                .containsExactly(waiting.getIngressId());
    }

    /** 启动前认领的回复恢复批次不得扫入渠道启动后新产生的 processed 回复。 */
    @Test
    void shouldRecoverOnlyRepliesClaimedBeforeChannelStartup() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AtomicInteger deliveryCalls = new AtomicInteger();
        ChannelInboundMessageRecord oldRecord = recoveryRecord(201);
        env.channelInboundMessageRepository.saveIfAbsent(oldRecord);
        env.channelInboundMessageRepository.markProcessed(
                oldRecord.getIngressId(), "{\"content\":\"old\"}", 100L);
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(new AtomicInteger()),
                        countingDeliveryService(deliveryCalls));
        String recoveryToken = service.claimStartupInboundDeliveries(service.profileName());
        ChannelInboundMessageRecord newRecord = recoveryRecord(202);
        env.channelInboundMessageRepository.saveIfAbsent(newRecord);
        env.channelInboundMessageRepository.markProcessed(
                newRecord.getIngressId(), "{\"content\":\"new\"}", 100L);

        service.recoverInboundDeliveries(service.profileName(), recoveryToken);

        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(oldRecord.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(newRecord.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PROCESSED);
    }

    /** 旧回复真实投递完成前不得释放写者租约并让同来源新 run 插入输出。 */
    @Test
    void shouldKeepRealGatewayDeliveryInsideSingleWriterLease() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        env.appConfig.getTask().setBusyPolicy("interrupt");
        CountDownLatch oldDeliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseOldDelivery = new CountDownLatch(1);
        List<String> deliveryOrder = Collections.synchronizedList(new ArrayList<String>());
        DeliveryService blockingDelivery =
                new DeliveryService() {
                    /** 阻塞旧回复并记录真实完成顺序；忽略 interrupt 以稳定复现租约边界。 */
                    @Override
                    public void deliver(DeliveryRequest request) {
                        String text = request == null ? "" : String.valueOf(request.getText());
                        if (text.contains("old request")) {
                            oldDeliveryStarted.countDown();
                            boolean interrupted = false;
                            while (true) {
                                try {
                                    releaseOldDelivery.await();
                                    break;
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                }
                            }
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            deliveryOrder.add("old");
                            return;
                        }
                        if (text.contains("new request")) {
                            deliveryOrder.add("new");
                        }
                    }

                    /** 测试投递服务不暴露渠道状态。 */
                    @Override
                    public List<ChannelStatus> statuses() {
                        return Collections.emptyList();
                    }
                };
        DefaultGatewayService service =
                service(
                        env,
                        env.conversationOrchestrator,
                        blockingDelivery,
                        env.channelInboundMessageRepository);
        service.setAgentRunSupervisor(
                (com.jimuqu.solon.claw.engine.AgentRunSupervisor) env.agentRunControlService);
        GatewayMessage oldMessage = realMessage("single-writer-old");
        oldMessage.setText("old request");
        GatewayMessage newMessage = realMessage("single-writer-new");
        newMessage.setText("new request");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<GatewayReply> oldRun = executor.submit(() -> service.handle(oldMessage));
            assertThat(oldDeliveryStarted.await(3L, TimeUnit.SECONDS)).isTrue();
            Future<GatewayReply> newRun = executor.submit(() -> service.handle(newMessage));

            TimeUnit.MILLISECONDS.sleep(200L);
            assertThat(newRun.isDone()).isFalse();
            assertThat(deliveryOrder).isEmpty();

            releaseOldDelivery.countDown();
            assertThat(oldRun.get(5L, TimeUnit.SECONDS)).isNotNull();
            assertThat(newRun.get(5L, TimeUnit.SECONDS)).isNotNull();
            assertThat(deliveryOrder).containsExactly("old", "new");
            assertThat(env.channelInboundMessageRepository.listProcessed("default", 10)).isEmpty();
        } finally {
            releaseOldDelivery.countDown();
            executor.shutdownNow();
        }
    }

    /** 每个原始平台 ID 必须独立占位，部分重放或重新分批不能重复执行旧消息。 */
    @Test
    void shouldDeduplicateRawReceiptsAcrossPartialReplayAndRebatching() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls));

        GatewayMessage first = realMessage("raw-a");
        first.setText("A");
        GatewayMessage second = realMessage("raw-b");
        second.setText("B");
        GatewayMessage third = realMessage("raw-c");
        third.setText("C");
        assertThat(service.admitInbound(first)).isTrue();
        assertThat(service.admitInbound(second)).isTrue();
        assertThat(service.admitInbound(third)).isTrue();

        first.setText("A\nB\nC");
        first.setReplyToMessageId("raw-c");
        first.getInboundReceiptIds().addAll(second.getInboundReceiptIds());
        first.getInboundReceiptIds().addAll(third.getInboundReceiptIds());
        service.handleAdmitted(first);

        assertThat(service.admitInbound(realMessage("raw-b"))).isFalse();
        assertThat(service.admitInbound(realMessage("raw-a"))).isFalse();
        assertThat(service.admitInbound(realMessage("raw-c"))).isFalse();

        GatewayMessage newMessage = realMessage("raw-d");
        newMessage.setText("D");
        assertThat(service.admitInbound(newMessage)).isTrue();
        service.handleAdmitted(newMessage);

        assertThat(orchestratorCalls.get()).isEqualTo(2);
        assertThat(deliveryCalls.get()).isEqualTo(2);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey("WEIXIN:default:id:chat-1:raw-a")
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey("WEIXIN:default:id:chat-1:raw-b")
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey("WEIXIN:default:id:chat-1:raw-c")
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey("WEIXIN:default:id:chat-1:raw-d")
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
    }

    /** 启动时必须恢复尚未进入业务主链的 pending 原始消息。 */
    @Test
    void shouldRecoverPendingRawReceiptWithoutPlatformReplay() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        GatewayMessage pending = realMessage("pending-recovery");
        DefaultGatewayService admittingService =
                service(
                        env,
                        replyingOrchestrator(new AtomicInteger()),
                        countingDeliveryService(new AtomicInteger()));
        assertThat(admittingService.admitInbound(pending)).isTrue();
        ChannelInboundMessageRecord staged =
                env.channelInboundMessageRepository.findByMessageKey(
                        "WEIXIN:default:id:chat-1:pending-recovery");
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        DefaultGatewayService recoveredService =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls));
        long recoveryWatermark =
                recoveredService.capturePendingInboundWatermark(recoveredService.profileName());

        recoveredService.recoverPendingInbounds(recoveredService.profileName(), recoveryWatermark);

        ChannelInboundMessageRecord completed =
                env.channelInboundMessageRepository.findByMessageKey(staged.getMessageKey());
        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(completed.getStatus()).isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
    }

    /** 原始附件引用必须先落库，水化失败保持 pending，后续恢复成功后才进入业务主链。 */
    @Test
    void shouldKeepPendingAttachmentReferenceUntilHydrationRecovers() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        HydratingAdapter failingAdapter = new HydratingAdapter(true);
        DefaultGatewayService failingService =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls),
                        env.channelInboundMessageRepository,
                        adapters(failingAdapter));
        GatewayMessage message = realMessage("attachment-pending");
        MessageAttachment reference = new MessageAttachment();
        reference.setKind("image");
        reference.setOriginalName("photo.jpg");
        reference.setMimeType("image/jpeg");
        reference.setSourceReference("raw-download-reference");
        reference.setSourceContext("raw-message-context");
        reference.setSourceEncryptionKey("raw-encryption-key");
        reference.setSourceResourceType("test_reference");
        message.getAttachments().add(reference);

        assertThat(failingService.admitInbound(message)).isTrue();
        ChannelInboundMessageRecord staged =
                env.channelInboundMessageRepository.findByMessageKey(
                        "WEIXIN:default:id:chat-1:attachment-pending");
        GatewayMessage persisted = ONode.deserialize(staged.getMessageJson(), GatewayMessage.class);
        assertThat(persisted.getAttachments()).hasSize(1);
        assertThat(persisted.getAttachments().get(0).getSourceReference())
                .isEqualTo("raw-download-reference");
        assertThat(persisted.getAttachments().get(0).getSourceContext())
                .isEqualTo("raw-message-context");
        assertThat(persisted.getAttachments().get(0).getSourceEncryptionKey())
                .isEqualTo("raw-encryption-key");
        assertThat(persisted.getAttachments().get(0).getSourceResourceType())
                .isEqualTo("test_reference");
        assertThat(persisted.getAttachments().get(0).getLocalPath()).isNull();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> failingService.handleAdmitted(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated hydration failure");
        assertThat(orchestratorCalls.get()).isZero();
        assertThat(deliveryCalls.get()).isZero();
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(staged.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PENDING);

        HydratingAdapter recoveredAdapter = new HydratingAdapter(false);
        DefaultGatewayService recoveredService =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls),
                        env.channelInboundMessageRepository,
                        adapters(recoveredAdapter));
        long watermark =
                recoveredService.capturePendingInboundWatermark(recoveredService.profileName());
        recoveredService.recoverPendingInbounds(
                recoveredService.profileName(), PlatformType.WEIXIN, watermark);

        assertThat(recoveredAdapter.hydrationCalls.get()).isEqualTo(1);
        assertThat(recoveredAdapter.hydratedPath.get()).isEqualTo("/tmp/hydrated-photo.jpg");
        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(staged.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
    }

    /** 企业微信 pending receipt 恢复时必须由真实适配器下载并缓存持久化附件引用。 */
    @Test
    void shouldHydratePersistedAttachmentThroughRealWeComAdapter() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        env.gatewayAuthorizationService.setPlatformAdmin(
                PlatformType.WECOM, "wecom-user", "企微用户", "wecom-chat");
        byte[] attachmentBytes = "real wecom attachment".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/attachment",
                exchange -> {
                    exchange.sendResponseHeaders(200, attachmentBytes.length);
                    exchange.getResponseBody().write(attachmentBytes);
                    exchange.close();
                });
        server.start();
        AtomicReference<MessageAttachment> hydratedAttachment =
                new AtomicReference<MessageAttachment>();
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        env.appConfig.getChannels().getWecom(),
                        new AttachmentCacheService(env.appConfig));
        Map<PlatformType, ChannelAdapter> channelAdapters =
                new EnumMap<PlatformType, ChannelAdapter>(PlatformType.class);
        channelAdapters.put(PlatformType.WECOM, adapter);
        ConversationOrchestrator orchestrator =
                new ConversationOrchestrator() {
                    /** 记录进入业务主链前已经完成水化的企微附件。 */
                    @Override
                    public GatewayReply handleIncoming(GatewayMessage message) {
                        orchestratorCalls.incrementAndGet();
                        hydratedAttachment.set(message.getAttachments().get(0));
                        return GatewayReply.ok("ok");
                    }

                    /** 本测试不使用调度入口。 */
                    @Override
                    public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                        return GatewayReply.ok("scheduled");
                    }

                    /** 本测试不使用会话恢复入口。 */
                    @Override
                    public GatewayReply resumePending(String sourceKey) {
                        return GatewayReply.ok("resumed");
                    }
                };
        DefaultGatewayService service =
                service(
                        env,
                        orchestrator,
                        countingDeliveryService(deliveryCalls),
                        env.channelInboundMessageRepository,
                        channelAdapters);
        GatewayMessage message =
                new GatewayMessage(PlatformType.WECOM, "wecom-chat", "wecom-user", "附件");
        message.setPlatformMessageId("wecom-real-hydration");
        message.setReplyToMessageId("wecom-real-hydration");
        MessageAttachment reference = new MessageAttachment();
        reference.setKind("file");
        reference.setOriginalName("report.txt");
        reference.setMimeType("text/plain");
        reference.setSourceReference(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/attachment");
        reference.setSourceResourceType("remote_url");
        message.getAttachments().add(reference);

        try {
            assertThat(service.admitInbound(message)).isTrue();
            long watermark = service.capturePendingInboundWatermark(service.profileName());

            service.recoverPendingInbounds(service.profileName(), PlatformType.WECOM, watermark);

            MessageAttachment hydrated = hydratedAttachment.get();
            assertThat(hydrated).isNotNull();
            assertThat(hydrated.getSourceReference()).isNull();
            assertThat(hydrated.getLocalPath()).isNotBlank();
            assertThat(Files.readAllBytes(java.nio.file.Paths.get(hydrated.getLocalPath())))
                    .isEqualTo(attachmentBytes);
            assertThat(orchestratorCalls.get()).isEqualTo(1);
            assertThat(deliveryCalls.get()).isEqualTo(1);
            assertThat(
                            env.channelInboundMessageRepository
                                    .findByMessageKey(
                                            "WECOM:default:id:wecom-chat:wecom-real-hydration")
                                    .getStatus())
                    .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        } finally {
            server.stop(0);
        }
    }

    /** 单渠道重连恢复只能消费当前平台，其他渠道 pending 必须等待各自连接。 */
    @Test
    void shouldRecoverPendingReceiptsOnlyForConnectedPlatform() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ChannelInboundMessageRecord wecom =
                pendingRecoveryRecord(PlatformType.WECOM, "platform-pending-wecom");
        ChannelInboundMessageRecord weixin =
                pendingRecoveryRecord(PlatformType.WEIXIN, "platform-pending-weixin");
        env.channelInboundMessageRepository.saveIfAbsent(wecom);
        env.channelInboundMessageRepository.saveIfAbsent(weixin);
        AtomicInteger orchestratorCalls = new AtomicInteger();
        AtomicInteger deliveryCalls = new AtomicInteger();
        DefaultGatewayService service =
                service(
                        env,
                        replyingOrchestrator(orchestratorCalls),
                        countingDeliveryService(deliveryCalls));
        long watermark = service.capturePendingInboundWatermark(service.profileName());

        service.recoverPendingInbounds(service.profileName(), PlatformType.WECOM, watermark);

        assertThat(orchestratorCalls.get()).isEqualTo(1);
        assertThat(deliveryCalls.get()).isEqualTo(1);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(wecom.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        assertThat(
                        env.channelInboundMessageRepository
                                .findByMessageKey(weixin.getMessageKey())
                                .getStatus())
                .isEqualTo(ChannelInboundMessageRecord.STATUS_PENDING);
    }

    /** 启动主线程与微信重连同时恢复时必须串行消费旧 receipt，不能并发重排。 */
    @Test
    void shouldSerializeConcurrentPendingRecoveryPasses() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<String> started = Collections.synchronizedList(new ArrayList<String>());
        ConversationOrchestrator orchestrator =
                new ConversationOrchestrator() {
                    /** 阻塞首条恢复消息并记录恢复并发度与进入顺序。 */
                    @Override
                    public GatewayReply handleIncoming(GatewayMessage message) throws Exception {
                        int current = active.incrementAndGet();
                        maxActive.updateAndGet(value -> Math.max(value, current));
                        started.add(message.getText());
                        try {
                            if ("A".equals(message.getText())) {
                                firstStarted.countDown();
                                releaseFirst.await(5L, TimeUnit.SECONDS);
                            }
                            return GatewayReply.ok("ok");
                        } finally {
                            active.decrementAndGet();
                        }
                    }

                    /** 本测试不使用调度入口。 */
                    @Override
                    public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                        return GatewayReply.ok("scheduled");
                    }

                    /** 本测试不使用恢复运行入口。 */
                    @Override
                    public GatewayReply resumePending(String sourceKey) {
                        return GatewayReply.ok("resumed");
                    }
                };
        DefaultGatewayService service =
                service(env, orchestrator, countingDeliveryService(new AtomicInteger()));
        GatewayMessage first = realMessage("pending-serial-a");
        first.setText("A");
        GatewayMessage second = realMessage("pending-serial-b");
        second.setText("B");
        assertThat(service.admitInbound(first)).isTrue();
        assertThat(service.admitInbound(second)).isTrue();
        long watermark = service.capturePendingInboundWatermark(service.profileName());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstRecovery =
                    executor.submit(
                            () -> {
                                service.recoverPendingInbounds(service.profileName(), watermark);
                                return null;
                            });
            assertThat(firstStarted.await(3L, TimeUnit.SECONDS)).isTrue();
            Future<?> secondRecovery =
                    executor.submit(
                            () -> {
                                service.recoverPendingInbounds(service.profileName(), watermark);
                                return null;
                            });

            TimeUnit.MILLISECONDS.sleep(200L);
            assertThat(started).containsExactly("A");

            releaseFirst.countDown();
            firstRecovery.get(5L, TimeUnit.SECONDS);
            secondRecovery.get(5L, TimeUnit.SECONDS);
            assertThat(started).containsExactly("A", "B");
            assertThat(maxActive.get()).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    /** MEMORY、Heartbeat 和 goal 合成消息不进入真实渠道入站总账。 */
    @Test
    void shouldExcludeSyntheticAndMemoryMessagesFromInboundLedger() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        authorizeWeiXin(env);
        AtomicInteger calls = new AtomicInteger();
        ConversationOrchestrator orchestrator = replyingOrchestrator(calls);
        DefaultGatewayService service =
                service(
                        env,
                        orchestrator,
                        recordingDeliveryService(
                                env.channelInboundMessageRepository,
                                "unused",
                                new AtomicInteger(),
                                new AtomicReference<String>(),
                                false));

        GatewayMessage memory =
                new GatewayMessage(PlatformType.MEMORY, "chat-memory", "user-memory", "hello");
        memory.setReplyToMessageId("memory-message");
        service.handle(memory);

        GatewayMessage heartbeat = realMessage("heartbeat-message");
        heartbeat.setHeartbeat(true);
        service.handle(heartbeat);

        GatewayMessage goalContinuation = realMessage("goal-message");
        goalContinuation.setGoalContinuation(true);
        service.handle(goalContinuation);

        assertThat(
                        env.channelInboundMessageRepository.findByMessageKey(
                                "MEMORY:default:id:chat-memory:memory-message"))
                .isNull();
        assertThat(
                        env.channelInboundMessageRepository.findByMessageKey(
                                "WEIXIN:default:id:chat-1:heartbeat-message"))
                .isNull();
        assertThat(
                        env.channelInboundMessageRepository.findByMessageKey(
                                "WEIXIN:default:id:chat-1:goal-message"))
                .isNull();
        assertThat(memory.getIngressId()).isNull();
        assertThat(heartbeat.getIngressId()).isNull();
        assertThat(goalContinuation.getIngressId()).isNull();
    }

    /** 为测试中的真实微信消息配置平台管理员。 */
    private void authorizeWeiXin(TestEnvironment env) throws Exception {
        env.gatewayAuthorizationService.setPlatformAdmin(
                PlatformType.WEIXIN, "user-1", "用户一", "chat-1");
    }

    /** 创建带稳定平台消息 ID 的真实渠道消息。 */
    private GatewayMessage realMessage(String messageId) {
        GatewayMessage message =
                new GatewayMessage(PlatformType.WEIXIN, "chat-1", "user-1", "请回复一次");
        message.setPlatformMessageId(messageId);
        message.setReplyToMessageId(messageId);
        return message;
    }

    /** 创建一条可被恢复逻辑反序列化并投递的 processed 前置记录。 */
    private ChannelInboundMessageRecord recoveryRecord(int index) {
        GatewayMessage message = realMessage("recovery-" + index);
        message.setProfile("default");
        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId(String.format("recovery-%03d", Integer.valueOf(index)));
        record.setMessageKey("recovery-key-" + index);
        record.setProfile("default");
        record.setPlatform(PlatformType.WEIXIN.name());
        record.setSourceKey(message.sourceKey());
        record.setMessageJson(ONode.serialize(message));
        record.setStatus(ChannelInboundMessageRecord.STATUS_PROCESSING);
        record.setAttempts(1);
        record.setCreatedAt(1L + index);
        record.setUpdatedAt(1L + index);
        return record;
    }

    /** 创建指定平台等待同进程重连恢复的 pending receipt。 */
    private ChannelInboundMessageRecord pendingRecoveryRecord(
            PlatformType platform, String ingressId) {
        GatewayMessage message =
                new GatewayMessage(platform, "chat-" + platform.name(), "user-1", "recover me");
        message.setProfile("default");
        message.setPlatformMessageId(ingressId);
        message.setReplyToMessageId(ingressId);
        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId(ingressId);
        record.setMessageKey(
                platform.name() + ":default:id:" + message.getChatId() + ":" + ingressId);
        record.setProfile("default");
        record.setPlatform(platform.name());
        record.setSourceKey(message.sourceKey());
        record.setMessageJson(ONode.serialize(message));
        record.setStatus(ChannelInboundMessageRecord.STATUS_PENDING);
        record.setAttempts(0);
        record.setCreatedAt(1L);
        record.setUpdatedAt(1L);
        return record;
    }

    /** 创建只返回固定文本并记录调用次数的对话编排器。 */
    private ConversationOrchestrator replyingOrchestrator(final AtomicInteger calls) {
        return new ConversationOrchestrator() {
            /** 处理真实入站消息。 */
            @Override
            public GatewayReply handleIncoming(GatewayMessage message) {
                calls.incrementAndGet();
                return GatewayReply.ok("ok");
            }

            /** 处理合成调度消息。 */
            @Override
            public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                return GatewayReply.ok("scheduled");
            }

            /** 恢复待处理运行。 */
            @Override
            public GatewayReply resumePending(String sourceKey) {
                return GatewayReply.ok("resumed");
            }
        };
    }

    /** 创建携带全部目标后续动作元数据的回复编排器。 */
    private ConversationOrchestrator goalReplyingOrchestrator(
            final AtomicInteger incomingCalls,
            final AtomicInteger scheduledCalls,
            final CountDownLatch scheduled,
            final String sessionId) {
        return new ConversationOrchestrator() {
            /** 返回会触发通知、学习、kickoff 和 continuation 的主回复。 */
            @Override
            public GatewayReply handleIncoming(GatewayMessage message) {
                incomingCalls.incrementAndGet();
                GatewayReply reply = GatewayReply.ok("ok");
                reply.setSessionId(sessionId);
                reply.getRuntimeMetadata().put("goal_message", "goal notice");
                reply.getRuntimeMetadata().put("goal_kickoff", "goal kickoff");
                reply.getRuntimeMetadata().put("goal_should_continue", Boolean.TRUE);
                reply.getRuntimeMetadata().put("goal_continuation_prompt", "goal continuation");
                return reply;
            }

            /** 记录任何不应发生的目标调度。 */
            @Override
            public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                scheduledCalls.incrementAndGet();
                scheduled.countDown();
                return GatewayReply.ok("scheduled");
            }

            /** 本测试不使用恢复入口。 */
            @Override
            public GatewayReply resumePending(String sourceKey) {
                return GatewayReply.ok("resumed");
            }
        };
    }

    /** 创建启用入站总账的网关服务。 */
    private DefaultGatewayService service(
            TestEnvironment env,
            ConversationOrchestrator orchestrator,
            DeliveryService deliveryService) {
        return service(env, orchestrator, deliveryService, env.channelInboundMessageRepository);
    }

    /** 创建使用指定入站仓储的网关服务。 */
    private DefaultGatewayService service(
            TestEnvironment env,
            ConversationOrchestrator orchestrator,
            DeliveryService deliveryService,
            ChannelInboundMessageRepository repository) {
        return new DefaultGatewayService(
                env.commandService,
                orchestrator,
                deliveryService,
                env.sessionRepository,
                repository,
                env.gatewayAuthorizationService,
                null,
                null,
                null,
                env.appConfig);
    }

    /** 创建使用指定入站仓储和渠道适配器的网关服务。 */
    private DefaultGatewayService service(
            TestEnvironment env,
            ConversationOrchestrator orchestrator,
            DeliveryService deliveryService,
            ChannelInboundMessageRepository repository,
            Map<PlatformType, ChannelAdapter> adapters) {
        return new DefaultGatewayService(
                env.commandService,
                orchestrator,
                deliveryService,
                env.sessionRepository,
                repository,
                env.gatewayAuthorizationService,
                null,
                null,
                adapters,
                env.appConfig);
    }

    /** 创建只包含微信测试适配器的平台映射。 */
    private Map<PlatformType, ChannelAdapter> adapters(ChannelAdapter adapter) {
        Map<PlatformType, ChannelAdapter> adapters =
                new EnumMap<PlatformType, ChannelAdapter>(PlatformType.class);
        adapters.put(PlatformType.WEIXIN, adapter);
        return adapters;
    }

    /** 创建可注入首投失败并记录投递时总账状态的投递服务。 */
    private DeliveryService recordingDeliveryService(
            final ChannelInboundMessageRepository repository,
            final String messageKey,
            final AtomicInteger deliveryCalls,
            final AtomicReference<String> statusAtDelivery,
            final boolean failFirst) {
        return new DeliveryService() {
            /** 投递回复并记录投递前状态。 */
            @Override
            public void deliver(DeliveryRequest request) throws Exception {
                int attempt = deliveryCalls.incrementAndGet();
                ChannelInboundMessageRecord record = repository.findByMessageKey(messageKey);
                statusAtDelivery.set(record == null ? null : record.getStatus());
                if (failFirst && attempt == 1) {
                    throw new IllegalStateException("simulated delivery failure");
                }
            }

            /** 测试投递服务不暴露渠道状态。 */
            @Override
            public List<ChannelStatus> statuses() {
                return Collections.emptyList();
            }
        };
    }

    /** 创建只统计投递次数的投递服务。 */
    private DeliveryService countingDeliveryService(final AtomicInteger deliveryCalls) {
        return new DeliveryService() {
            /** 统计一次回复投递。 */
            @Override
            public void deliver(DeliveryRequest request) {
                deliveryCalls.incrementAndGet();
            }

            /** 测试投递服务不暴露渠道状态。 */
            @Override
            public List<ChannelStatus> statuses() {
                return Collections.emptyList();
            }
        };
    }

    /** 可注入水化失败并记录调用次数的微信测试适配器。 */
    private static final class HydratingAdapter implements ChannelAdapter {
        /** 是否模拟附件水化失败。 */
        private final boolean fail;

        /** 实际附件水化调用次数。 */
        private final AtomicInteger hydrationCalls = new AtomicInteger();

        /** 水化成功后生成的本地缓存路径。 */
        private final AtomicReference<String> hydratedPath = new AtomicReference<String>();

        /** 最近一次处理完成结果。 */
        private final AtomicReference<ProcessingOutcome> processingOutcome =
                new AtomicReference<ProcessingOutcome>();

        /** 创建可控附件水化适配器。 */
        private HydratingAdapter(boolean fail) {
            this.fail = fail;
        }

        /** 返回微信平台。 */
        @Override
        public PlatformType platform() {
            return PlatformType.WEIXIN;
        }

        /** 测试适配器始终启用。 */
        @Override
        public boolean isEnabled() {
            return true;
        }

        /** 测试适配器不建立真实连接。 */
        @Override
        public boolean connect() {
            return true;
        }

        /** 测试适配器没有连接资源需要关闭。 */
        @Override
        public void disconnect() {}

        /** 测试适配器视为已连接。 */
        @Override
        public boolean isConnected() {
            return true;
        }

        /** 返回测试状态说明。 */
        @Override
        public String detail() {
            return "test";
        }

        /** 测试适配器不执行真实消息发送。 */
        @Override
        public void send(DeliveryRequest request) {}

        /** 记录处理生命周期终态，供单写者抑制测试断言。 */
        @Override
        public void onProcessingComplete(GatewayMessage message, ProcessingOutcome outcome) {
            processingOutcome.set(outcome);
        }

        /** 按测试开关失败，或把原始引用转换为已缓存附件。 */
        @Override
        public void hydrateInboundAttachments(GatewayMessage message) {
            hydrationCalls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("simulated hydration failure");
            }
            for (MessageAttachment attachment : message.getAttachments()) {
                attachment.setLocalPath("/tmp/hydrated-" + attachment.getOriginalName());
                hydratedPath.set(attachment.getLocalPath());
                attachment.setSourceReference(null);
                attachment.setSourceContext(null);
                attachment.setSourceEncryptionKey(null);
                attachment.setSourceResourceType(null);
            }
        }
    }

    /** 为故障注入测试转发未覆盖的入站仓储方法。 */
    private static class DelegatingInboundRepository implements ChannelInboundMessageRepository {
        /** 负责其余状态操作的真实仓储。 */
        protected final ChannelInboundMessageRepository delegate;

        /** 创建转发到真实仓储的测试装饰器。 */
        private DelegatingInboundRepository(ChannelInboundMessageRepository delegate) {
            this.delegate = delegate;
        }

        /** 转发唯一键插入。 */
        @Override
        public boolean saveIfAbsent(ChannelInboundMessageRecord record) throws Exception {
            return delegate.saveIfAbsent(record);
        }

        /** 转发原始 receipt 批次领取。 */
        @Override
        public boolean startBatch(
                String leaderIngressId,
                List<String> memberIngressIds,
                String sourceKey,
                String messageJson,
                long startedAt)
                throws Exception {
            return delegate.startBatch(
                    leaderIngressId, memberIngressIds, sourceKey, messageJson, startedAt);
        }

        /** 转发幂等键查询。 */
        @Override
        public ChannelInboundMessageRecord findByMessageKey(String messageKey) throws Exception {
            return delegate.findByMessageKey(messageKey);
        }

        /** 转发回复状态迁移。 */
        @Override
        public boolean markProcessed(String ingressId, String replyJson, long processedAt)
                throws Exception {
            return delegate.markProcessed(ingressId, replyJson, processedAt);
        }

        /** 转发回复与正常投递 owner 的原子写入。 */
        @Override
        public boolean markProcessedForDelivery(
                String ingressId, String replyJson, String deliveryToken, long processedAt)
                throws Exception {
            return delegate.markProcessedForDelivery(
                    ingressId, replyJson, deliveryToken, processedAt);
        }

        /** 转发完成态迁移。 */
        @Override
        public boolean markCompleted(String ingressId, long completedAt) throws Exception {
            return delegate.markCompleted(ingressId, completedAt);
        }

        /** 转发投递失败记录。 */
        @Override
        public void markDeliveryFailed(String ingressId, String error, long updatedAt)
                throws Exception {
            delegate.markDeliveryFailed(ingressId, error, updatedAt);
        }

        /** 转发不可恢复失败记录。 */
        @Override
        public void markFailed(String ingressId, String error, long updatedAt) throws Exception {
            delegate.markFailed(ingressId, error, updatedAt);
        }

        /** 转发重启中断收敛。 */
        @Override
        public int markInterrupted(String profile, long before, String error) throws Exception {
            return delegate.markInterrupted(profile, before, error);
        }

        /** 转发 pending receipt 稳定恢复水位捕获。 */
        @Override
        public long capturePendingWatermark(String profile) throws Exception {
            return delegate.capturePendingWatermark(profile);
        }

        /** 转发 pending receipt 恢复查询。 */
        @Override
        public List<ChannelInboundMessageRecord> listPending(
                String profile, long maxSequence, long afterSequence, int limit) throws Exception {
            return delegate.listPending(profile, maxSequence, afterSequence, limit);
        }

        /** 转发指定平台 pending receipt 恢复查询。 */
        @Override
        public List<ChannelInboundMessageRecord> listPending(
                String profile, String platform, long maxSequence, long afterSequence, int limit)
                throws Exception {
            return delegate.listPending(profile, platform, maxSequence, afterSequence, limit);
        }

        /** 转发启动回复恢复批次认领。 */
        @Override
        public int claimStartupDeliveries(String profile, String recoveryToken) throws Exception {
            return delegate.claimStartupDeliveries(profile, recoveryToken);
        }

        /** 转发单平台全部未完成回复认领。 */
        @Override
        public int claimPlatformDeliveries(String profile, String platform, String recoveryToken)
                throws Exception {
            return delegate.claimPlatformDeliveries(profile, platform, recoveryToken);
        }

        /** 转发单平台投递失败恢复批次认领。 */
        @Override
        public int claimFailedDeliveries(String profile, String platform, String recoveryToken)
                throws Exception {
            return delegate.claimFailedDeliveries(profile, platform, recoveryToken);
        }

        /** 转发旧投递认领与结果未知外发的启动收敛。 */
        @Override
        public int convergeInterruptedDeliveries(
                String profile, long interruptedBefore, String error) throws Exception {
            return delegate.convergeInterruptedDeliveries(profile, interruptedBefore, error);
        }

        /** 转发 owner 限定的外发开始迁移。 */
        @Override
        public boolean markClaimedDeliveryStarted(
                String ingressId, String deliveryToken, long startedAt) throws Exception {
            return delegate.markClaimedDeliveryStarted(ingressId, deliveryToken, startedAt);
        }

        /** 转发 owner 限定的单条安全认领释放。 */
        @Override
        public boolean releaseClaimedDelivery(String ingressId, String deliveryToken)
                throws Exception {
            return delegate.releaseClaimedDelivery(ingressId, deliveryToken);
        }

        /** 转发 owner 限定的完成态迁移。 */
        @Override
        public boolean markClaimedCompleted(
                String ingressId, String recoveryToken, long completedAt) throws Exception {
            return delegate.markClaimedCompleted(ingressId, recoveryToken, completedAt);
        }

        /** 转发 owner 限定的可重试投递失败迁移。 */
        /** 转发 owner 限定的不可重放失败迁移。 */
        @Override
        public boolean markClaimedFailed(
                String ingressId, String recoveryToken, String error, long updatedAt)
                throws Exception {
            return delegate.markClaimedFailed(ingressId, recoveryToken, error, updatedAt);
        }

        /** 转发已认领回复稳定分页查询。 */
        @Override
        public List<ChannelInboundMessageRecord> listClaimedDeliveries(
                String profile,
                String recoveryToken,
                long afterProcessedAt,
                String afterIngressId,
                int limit)
                throws Exception {
            return delegate.listClaimedDeliveries(
                    profile, recoveryToken, afterProcessedAt, afterIngressId, limit);
        }

        /** 转发未完成回复恢复认领释放。 */
        @Override
        public void releaseDeliveryClaim(String profile, String recoveryToken) throws Exception {
            delegate.releaseDeliveryClaim(profile, recoveryToken);
        }

        /** 转发首批恢复查询。 */
        @Override
        public List<ChannelInboundMessageRecord> listProcessed(String profile, int limit)
                throws Exception {
            return delegate.listProcessed(profile, limit);
        }

        /** 转发游标恢复查询。 */
        @Override
        public List<ChannelInboundMessageRecord> listProcessed(
                String profile, long afterProcessedAt, String afterIngressId, int limit)
                throws Exception {
            return delegate.listProcessed(profile, afterProcessedAt, afterIngressId, limit);
        }

        /** 转发终态保留期清理。 */
        @Override
        public int pruneTerminal(String profile, long updatedBefore) throws Exception {
            return delegate.pruneTerminal(profile, updatedBefore);
        }
    }

    /** 模拟 processing 到 processed 状态冲突的入站仓储。 */
    private static final class FailingProcessedRepository extends DelegatingInboundRepository {
        /** 创建只拒绝保存回复的仓储装饰器。 */
        private FailingProcessedRepository(ChannelInboundMessageRepository delegate) {
            super(delegate);
        }

        /** 模拟回复状态写入未命中 processing 记录。 */
        @Override
        public boolean markProcessed(String ingressId, String replyJson, long processedAt) {
            return false;
        }

        /** 模拟回复与正常投递 owner 的原子写入未命中。 */
        @Override
        public boolean markProcessedForDelivery(
                String ingressId, String replyJson, String deliveryToken, long processedAt) {
            return false;
        }
    }

    /** 模拟回复外发后 completed 状态写入未命中的入站仓储。 */
    private static final class FailingCompletedRepository extends DelegatingInboundRepository {
        /** 创建只拒绝完成态迁移的仓储装饰器。 */
        private FailingCompletedRepository(ChannelInboundMessageRepository delegate) {
            super(delegate);
        }

        /** 模拟 completed 更新未命中 processed 记录。 */
        @Override
        public boolean markCompleted(String ingressId, long completedAt) {
            return false;
        }

        /** 模拟 owner 限定的完成态迁移未命中。 */
        @Override
        public boolean markClaimedCompleted(
                String ingressId, String recoveryToken, long completedAt) {
            return false;
        }
    }

    /** 模拟首条回复已投递后终态写入失败，并在下一页查询前中断恢复批次。 */
    private static final class InterruptedRecoveryRepository extends DelegatingInboundRepository {
        /** 首条无法确认终态的入站标识。 */
        private final String uncertainIngressId;

        /** 已执行的认领列表查询次数。 */
        private final AtomicInteger listCalls = new AtomicInteger();

        /** 创建恢复中断仓储装饰器。 */
        private InterruptedRecoveryRepository(
                ChannelInboundMessageRepository delegate, String uncertainIngressId) {
            super(delegate);
            this.uncertainIngressId = uncertainIngressId;
        }

        /** 首次只返回一条记录，随后模拟 SQLite 查询异常。 */
        @Override
        public List<ChannelInboundMessageRecord> listClaimedDeliveries(
                String profile,
                String recoveryToken,
                long afterProcessedAt,
                String afterIngressId,
                int limit)
                throws Exception {
            if (listCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("simulated recovery query failure");
            }
            return super.listClaimedDeliveries(
                    profile, recoveryToken, afterProcessedAt, afterIngressId, 1);
        }

        /** 模拟首条已外发回复的完成态 owner CAS 未命中。 */
        @Override
        public boolean markClaimedCompleted(
                String ingressId, String recoveryToken, long completedAt) throws Exception {
            return uncertainIngressId.equals(ingressId)
                    ? false
                    : super.markClaimedCompleted(ingressId, recoveryToken, completedAt);
        }

        /** 模拟首条已外发回复连不可重放失败态也无法落库。 */
        @Override
        public boolean markClaimedFailed(
                String ingressId, String recoveryToken, String error, long updatedAt)
                throws Exception {
            return uncertainIngressId.equals(ingressId)
                    ? false
                    : super.markClaimedFailed(ingressId, recoveryToken, error, updatedAt);
        }
    }
}
