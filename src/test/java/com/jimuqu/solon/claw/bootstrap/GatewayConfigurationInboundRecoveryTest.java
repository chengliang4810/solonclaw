package com.jimuqu.solon.claw.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelInboundMessageRecord;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.ChannelInboundMessageRepository;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.gateway.delivery.AdapterBackedDeliveryService;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRestartNotificationService;
import com.jimuqu.solon.claw.storage.repository.SqliteChannelInboundMessageRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 验证网关 Bean 启动阶段的渠道连接与入站回复恢复顺序。 */
public class GatewayConfigurationInboundRecoveryTest {
    /** 已持久化回复必须等渠道连接成功后再恢复投递。 */
    @Test
    void shouldConnectChannelsBeforeRecoveringInboundReplies() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        ConnectRequiredAdapter adapter = new ConnectRequiredAdapter(order);
        Map<PlatformType, ChannelAdapter> adapters =
                new EnumMap<PlatformType, ChannelAdapter>(PlatformType.class);
        adapters.put(PlatformType.WECOM, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(
                        env.appConfig,
                        adapters,
                        env.gatewayPolicyRepository,
                        env.sessionRepository);
        ChannelConnectionManager connectionManager = new ChannelConnectionManager(adapters);
        GatewayMessage message =
                new GatewayMessage(PlatformType.WECOM, "chat-recovery", "user-recovery", "hello");
        message.setProfile("default");
        message.setPlatformMessageId("platform-recovery-1");
        ChannelInboundMessageRecord record = recoveryRecord(message);
        env.channelInboundMessageRepository.saveIfAbsent(record);
        env.channelInboundMessageRepository.markProcessed(
                record.getIngressId(), "{\"content\":\"recovered reply\"}", 2L);

        try {
            new GatewayConfiguration()
                    .gatewayService(
                            env.commandService,
                            env.conversationOrchestrator,
                            deliveryService,
                            env.sessionRepository,
                            env.channelInboundMessageRepository,
                            env.gatewayAuthorizationService,
                            null,
                            null,
                            adapters,
                            connectionManager,
                            new GatewayRestartNotificationService(env.appConfig, deliveryService),
                            (AgentRunSupervisor) env.agentRunControlService,
                            env.appConfig);

            assertThat(adapter.awaitSend()).isTrue();
            ChannelInboundMessageRecord completed =
                    env.channelInboundMessageRepository.findByMessageKey(record.getMessageKey());
            assertThat(order).containsExactly("connect", "send");
            assertThat(completed.getStatus())
                    .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        } finally {
            connectionManager.shutdown();
        }
    }

    /** 旧 processing 必须在渠道开始接收新消息前收敛，不能误伤本次连接创建的运行。 */
    @Test
    void shouldConvergeInterruptedInboundsBeforeConnectingChannels() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        RecordingInboundRepository repository =
                new RecordingInboundRepository(env.sqliteDatabase, order);
        ProcessingOnConnectAdapter adapter = new ProcessingOnConnectAdapter(order, repository);
        Map<PlatformType, ChannelAdapter> adapters =
                new EnumMap<PlatformType, ChannelAdapter>(PlatformType.class);
        adapters.put(PlatformType.WECOM, adapter);
        ChannelConnectionManager connectionManager = new ChannelConnectionManager(adapters);

        try {
            new GatewayConfiguration()
                    .gatewayService(
                            env.commandService,
                            env.conversationOrchestrator,
                            env.deliveryService,
                            env.sessionRepository,
                            repository,
                            env.gatewayAuthorizationService,
                            null,
                            null,
                            adapters,
                            connectionManager,
                            new GatewayRestartNotificationService(
                                    env.appConfig, env.deliveryService),
                            (AgentRunSupervisor) env.agentRunControlService,
                            env.appConfig);

            assertThat(order).startsWith("converge", "connect");
            assertThat(repository.findByMessageKey("connect-created-message").getStatus())
                    .isEqualTo(ChannelInboundMessageRecord.STATUS_PROCESSING);
        } finally {
            connectionManager.shutdown();
        }
    }

    /** 即使微信未参与启动，其他渠道遗留的 pending receipt 也必须在连接后恢复。 */
    @Test
    void shouldRecoverPendingReceiptForNonWeixinChannelAfterConnect() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        ConnectRequiredAdapter adapter = new ConnectRequiredAdapter(order);
        Map<PlatformType, ChannelAdapter> adapters =
                new EnumMap<PlatformType, ChannelAdapter>(PlatformType.class);
        adapters.put(PlatformType.WECOM, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(
                        env.appConfig,
                        adapters,
                        env.gatewayPolicyRepository,
                        env.sessionRepository);
        ChannelConnectionManager connectionManager = new ChannelConnectionManager(adapters);
        GatewayMessage message =
                new GatewayMessage(PlatformType.WECOM, "chat-pending", "user-pending", "hello");
        message.setProfile("default");
        message.setPlatformMessageId("platform-pending-1");
        ChannelInboundMessageRecord record = recoveryRecord(message);
        record.setIngressId("startup-pending-ingress");
        record.setMessageKey("WECOM:default:id:chat-pending:platform-pending-1");
        record.setStatus(ChannelInboundMessageRecord.STATUS_PENDING);
        record.setAttempts(0);
        env.channelInboundMessageRepository.saveIfAbsent(record);

        try {
            new GatewayConfiguration()
                    .gatewayService(
                            env.commandService,
                            env.conversationOrchestrator,
                            deliveryService,
                            env.sessionRepository,
                            env.channelInboundMessageRepository,
                            env.gatewayAuthorizationService,
                            null,
                            null,
                            adapters,
                            connectionManager,
                            new GatewayRestartNotificationService(env.appConfig, deliveryService),
                            (AgentRunSupervisor) env.agentRunControlService,
                            env.appConfig);

            assertThat(adapter.awaitSend()).isTrue();
            ChannelInboundMessageRecord completed =
                    env.channelInboundMessageRepository.findByMessageKey(record.getMessageKey());
            assertThat(order).containsExactly("connect", "send");
            assertThat(completed.getStatus())
                    .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        } finally {
            connectionManager.shutdown();
        }
    }

    /** 真实服务的水位与恢复查询瞬时失败时，ready 屏障必须持续重试并最终恢复旧消息。 */
    @Test
    void shouldRetryTransientPendingRecoveryFailuresBeforeReleasingReadyBarrier() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        List<String> order = Collections.synchronizedList(new ArrayList<String>());
        TransientPendingRecoveryRepository repository =
                new TransientPendingRecoveryRepository(env.sqliteDatabase);
        ConnectRequiredAdapter adapter = new ConnectRequiredAdapter(order);
        Map<PlatformType, ChannelAdapter> adapters =
                new EnumMap<PlatformType, ChannelAdapter>(PlatformType.class);
        adapters.put(PlatformType.WECOM, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(
                        env.appConfig,
                        adapters,
                        env.gatewayPolicyRepository,
                        env.sessionRepository);
        ChannelConnectionManager connectionManager = new ChannelConnectionManager(adapters);
        GatewayMessage message =
                new GatewayMessage(PlatformType.WECOM, "chat-transient", "user-transient", "hello");
        message.setProfile("default");
        message.setPlatformMessageId("platform-transient-1");
        ChannelInboundMessageRecord record = recoveryRecord(message);
        record.setIngressId("transient-pending-ingress");
        record.setMessageKey("WECOM:default:id:chat-transient:platform-transient-1");
        record.setStatus(ChannelInboundMessageRecord.STATUS_PENDING);
        record.setAttempts(0);
        repository.saveIfAbsent(record);

        try {
            new GatewayConfiguration()
                    .gatewayService(
                            env.commandService,
                            env.conversationOrchestrator,
                            deliveryService,
                            env.sessionRepository,
                            repository,
                            env.gatewayAuthorizationService,
                            null,
                            null,
                            adapters,
                            connectionManager,
                            new GatewayRestartNotificationService(env.appConfig, deliveryService),
                            (AgentRunSupervisor) env.agentRunControlService,
                            env.appConfig);

            assertThat(adapter.awaitSend()).isTrue();
            assertThat(repository.captureCalls.get()).isGreaterThanOrEqualTo(3);
            assertThat(repository.platformListCalls.get()).isEqualTo(2);
            assertThat(order).containsExactly("connect", "send");
            assertThat(repository.findByMessageKey(record.getMessageKey()).getStatus())
                    .isEqualTo(ChannelInboundMessageRecord.STATUS_COMPLETED);
        } finally {
            connectionManager.shutdown();
        }
    }

    /** 创建一条等待启动恢复的已处理入站记录。 */
    private ChannelInboundMessageRecord recoveryRecord(GatewayMessage message) {
        ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
        record.setIngressId("startup-recovery-ingress");
        record.setMessageKey("WECOM:default:id:chat-recovery:platform-recovery-1");
        record.setProfile("default");
        record.setPlatform(PlatformType.WECOM.name());
        record.setSourceKey(message.sourceKey());
        record.setMessageJson(ONode.serialize(message));
        record.setStatus(ChannelInboundMessageRecord.STATUS_PROCESSING);
        record.setAttempts(1);
        record.setCreatedAt(1L);
        record.setUpdatedAt(1L);
        return record;
    }

    /** 只有 connect 成功后才允许发送的测试渠道适配器。 */
    private static class ConnectRequiredAdapter extends AbstractConfigurableChannelAdapter {
        /** 记录连接与发送的实际顺序。 */
        private final List<String> order;

        /** 与真实渠道一致的串行入站执行器。 */
        private ExecutorService inboundExecutor;

        /** 等待恢复回复真实外发，避免测试抢在异步连接回调之前断言。 */
        private final CountDownLatch sent = new CountDownLatch(1);

        /** 创建严格要求先连接后发送的测试适配器。 */
        private ConnectRequiredAdapter(List<String> order) {
            super(PlatformType.WECOM, enabledConfig());
            this.order = order;
        }

        /** 创建启用的测试渠道配置。 */
        private static AppConfig.ChannelConfig enabledConfig() {
            AppConfig.ChannelConfig config = new AppConfig.ChannelConfig();
            config.setEnabled(true);
            return config;
        }

        /** 建立连接并记录顺序。 */
        @Override
        public boolean connect() {
            order.add("connect");
            inboundExecutor = Executors.newSingleThreadExecutor();
            queuePlatformPendingInboundRecovery(inboundExecutor);
            setConnected(true);
            notifyConnectionReady();
            return true;
        }

        /** 断开测试连接。 */
        @Override
        public void disconnect() {
            setConnected(false);
            ExecutorService executor = inboundExecutor;
            inboundExecutor = null;
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        /** 只有连接完成后才接受恢复回复。 */
        @Override
        public void send(DeliveryRequest request) {
            if (!isConnected()) {
                throw new IllegalStateException("channel is not connected");
            }
            order.add("send");
            sent.countDown();
        }

        /** 等待异步连接回调完成一次恢复外发。 */
        private boolean awaitSend() throws InterruptedException {
            return sent.await(5, TimeUnit.SECONDS);
        }
    }

    /** 记录 processing 收敛发生顺序的 SQLite 入站仓储。 */
    private static final class RecordingInboundRepository
            extends SqliteChannelInboundMessageRepository {
        /** 记录启动关键动作。 */
        private final List<String> order;

        /** 创建共享测试数据库上的顺序记录仓储。 */
        private RecordingInboundRepository(SqliteDatabase database, List<String> order) {
            super(database);
            this.order = order;
        }

        /** 在执行真实收敛前记录启动顺序。 */
        @Override
        public int markInterrupted(String profile, long before, String error) throws Exception {
            order.add("converge");
            return super.markInterrupted(profile, before, error);
        }
    }

    /** 水位读取与按平台恢复查询分别瞬时失败一次的 SQLite 测试仓储。 */
    private static final class TransientPendingRecoveryRepository
            extends SqliteChannelInboundMessageRepository {
        /** 记录水位读取次数。 */
        private final AtomicInteger captureCalls = new AtomicInteger();

        /** 记录按平台恢复查询次数。 */
        private final AtomicInteger platformListCalls = new AtomicInteger();

        /** 创建共享测试数据库上的瞬时故障仓储。 */
        private TransientPendingRecoveryRepository(SqliteDatabase database) {
            super(database);
        }

        /** 第一次水位读取模拟 SQLite 瞬时失败。 */
        @Override
        public long capturePendingWatermark(String profile) throws Exception {
            if (captureCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated watermark failure");
            }
            return super.capturePendingWatermark(profile);
        }

        /** 第一次按平台恢复查询模拟 SQLite 瞬时失败。 */
        @Override
        public List<ChannelInboundMessageRecord> listPending(
                String profile, String platform, long maxSequence, long afterSequence, int limit)
                throws Exception {
            if (platformListCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated pending query failure");
            }
            return super.listPending(profile, platform, maxSequence, afterSequence, limit);
        }
    }

    /** 连接成功时模拟渠道立刻创建一条本次进程的 processing 入站记录。 */
    private static final class ProcessingOnConnectAdapter extends ConnectRequiredAdapter {
        /** 入站总账仓储。 */
        private final ChannelInboundMessageRepository repository;

        /** 创建连接时写入记录的测试适配器。 */
        private ProcessingOnConnectAdapter(
                List<String> order, ChannelInboundMessageRepository repository) {
            super(order);
            this.repository = repository;
        }

        /** 建立连接并写入一条本次启动产生的 processing 记录。 */
        @Override
        public boolean connect() {
            boolean connected = super.connect();
            long now = System.currentTimeMillis();
            ChannelInboundMessageRecord record = new ChannelInboundMessageRecord();
            record.setIngressId("connect-created-ingress");
            record.setMessageKey("connect-created-message");
            record.setProfile("default");
            record.setPlatform(PlatformType.WECOM.name());
            record.setSourceKey("WECOM:connect:user");
            record.setMessageJson("{}");
            record.setStatus(ChannelInboundMessageRecord.STATUS_PROCESSING);
            record.setAttempts(1);
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                repository.saveIfAbsent(record);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return connected;
        }
    }
}
