package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class ChannelConnectionManagerTest {
    @Test
    void shouldExposeReconnectStateWhenEnabledChannelConnectFails() {
        FailingChannelAdapter adapter =
                new FailingChannelAdapter(
                        PlatformType.FEISHU, "network failed token=ghp_channelretry12345");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);
        AtomicInteger connectedCallbacks = new AtomicInteger();
        manager.bindConnectedHandler(platform -> connectedCallbacks.incrementAndGet());

        try {
            manager.startAll();
            List<ChannelStatus> statuses = manager.statusSnapshots();
            String json = ONode.serialize(statuses.get(0));

            assertThat(json)
                    .contains("\"reconnecting\":true")
                    .contains("\"reconnectAttempt\":1")
                    .contains("\"nextReconnectAt\":")
                    .contains("\"lastReconnectError\":\"network failed token=***\"")
                    .doesNotContain("ghp_channelretry12345");
            assertThat(connectedCallbacks.get()).isZero();
        } finally {
            manager.shutdown();
        }
    }

    /** 已建立连接的适配器运行期断线后，应通过注册回调进入统一重连状态。 */
    @Test
    void shouldScheduleReconnectWhenAdapterReportsRuntimeDisconnect() {
        RuntimeDisconnectedAdapter adapter = new RuntimeDisconnectedAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        try {
            manager.startAll();
            adapter.reportDisconnect();

            ChannelStatus status = manager.statusSnapshots().get(0);
            assertThat(status.isReconnecting()).isTrue();
            assertThat(status.getReconnectAttempt()).isEqualTo(1);
        } finally {
            manager.shutdown();
        }
    }

    /** 首次连接和运行期重连成功后都必须异步通知对应平台，失败连接不得通知。 */
    @Test
    void shouldNotifyPlatformAfterInitialConnectAndReconnect() throws Exception {
        long[] backoff = reconnectBackoff();
        long previousDelay = backoff[0];
        backoff[0] = 0L;
        RuntimeDisconnectedAdapter adapter = new RuntimeDisconnectedAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);
        CountDownLatch callbacks = new CountDownLatch(2);
        List<PlatformType> platforms =
                java.util.Collections.synchronizedList(new java.util.ArrayList<PlatformType>());
        manager.bindConnectedHandler(
                platform -> {
                    platforms.add(platform);
                    callbacks.countDown();
                });

        try {
            manager.startAll();
            adapter.reportDisconnect();

            assertThat(callbacks.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(platforms).containsExactly(PlatformType.WECOM, PlatformType.WECOM);
        } finally {
            manager.shutdown();
            backoff[0] = previousDelay;
        }
    }

    /** 同一平台重复报告断线时必须合并到已有 Future，不能重复增加失败次数。 */
    @Test
    void shouldMergeRepeatedReconnectRequestsIntoSingleFuture() throws Exception {
        FailingChannelAdapter adapter =
                new FailingChannelAdapter(PlatformType.FEISHU, "network failed");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        try {
            manager.startAll();
            ScheduledFuture<?> first = reconnectFutures(manager).get(adapter.platform());

            for (int index = 0; index < 20; index++) {
                manager.scheduleReconnect(adapter.platform());
            }

            ChannelStatus status = manager.statusSnapshots().get(0);
            assertThat(reconnectFutures(manager)).hasSize(1);
            assertThat((Object) reconnectFutures(manager).get(adapter.platform())).isSameAs(first);
            assertThat(status.getReconnectAttempt()).isEqualTo(1);
            assertThat(status.getReconnectFailureCount()).isEqualTo(1);
        } finally {
            manager.shutdown();
        }
    }

    /** 连续五次连接失败后必须进入熔断冷却，避免无限快速重连。 */
    @Test
    void shouldOpenReconnectCircuitAfterFiveConsecutiveFailures() throws Exception {
        long[] backoff = reconnectBackoff();
        long[] previousBackoff = backoff.clone();
        for (int index = 0; index < backoff.length; index++) {
            backoff[index] = 0L;
        }
        FailingChannelAdapter adapter =
                new FailingChannelAdapter(PlatformType.FEISHU, "network failed", 5);
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        try {
            manager.startAll();
            assertThat(adapter.awaitAttempts(2, TimeUnit.SECONDS)).isTrue();
            awaitFailureCount(manager, 5);

            ChannelStatus status = manager.statusSnapshots().get(0);
            assertThat(status.getReconnectAttempt()).isEqualTo(5);
            assertThat(status.getReconnectFailureCount()).isEqualTo(5);
            assertThat(status.isReconnectCircuitOpen()).isTrue();
            assertThat(status.getReconnectCircuitOpenedAt()).isPositive();
            assertThat(status.getReconnectCircuitOpenUntil())
                    .isGreaterThan(status.getReconnectCircuitOpenedAt());
        } finally {
            manager.shutdown();
            System.arraycopy(previousBackoff, 0, backoff, 0, backoff.length);
        }
    }

    /** watchdog 必须为启用但断线且没有重连任务的渠道补回唯一任务。 */
    @Test
    void shouldRecoverMissingReconnectTaskFromWatchdog() throws Exception {
        RuntimeDisconnectedAdapter adapter = new RuntimeDisconnectedAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        try {
            manager.startAll();
            adapter.loseConnectionWithoutCallback();
            invokeWatchdog(manager);

            ChannelStatus status = manager.statusSnapshots().get(0);
            assertThat(status.isReconnecting()).isTrue();
            assertThat(status.getReconnectAttempt()).isEqualTo(1);
            assertThat(status.getReconnectWatchdogRecoveredAt()).isPositive();
            assertThat(reconnectFutures(manager)).containsKey(adapter.platform());
        } finally {
            manager.shutdown();
        }
    }

    /** SDK 健康不可观测渠道在自恢复窗口过期后，也必须由 watchdog 接管唯一重连。 */
    @Test
    void shouldRecoverUnobservableChannelAfterSdkGraceExpires() throws Exception {
        UnobservableSdkAdapter adapter = new UnobservableSdkAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        try {
            manager.startAll();
            adapter.reportUnavailable();
            expireSdkRecoveryGrace(manager, adapter.platform());
            invokeWatchdog(manager);

            ChannelStatus status = manager.statusSnapshots().get(0);
            assertThat(status.isReconnecting()).isTrue();
            assertThat(status.getReconnectAttempt()).isEqualTo(1);
            assertThat(status.getReconnectWatchdogRecoveredAt()).isPositive();
            assertThat(reconnectFutures(manager)).containsKey(adapter.platform());
        } finally {
            manager.shutdown();
        }
    }

    /** 管理器关闭后，回调和 watchdog 都不得再次创建任务或调用 connect。 */
    @Test
    void shouldNotReconnectAfterShutdown() throws Exception {
        RuntimeDisconnectedAdapter adapter = new RuntimeDisconnectedAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);

        manager.startAll();
        adapter.reportDisconnect();
        assertThat(reconnectFutures(manager)).containsKey(adapter.platform());

        manager.shutdown();
        adapter.reportDisconnect();
        manager.scheduleReconnect(adapter.platform());
        invokeWatchdog(manager);

        assertThat(reconnectFutures(manager)).isEmpty();
        assertThat(adapter.connectCallCount()).isEqualTo(1);
    }

    /** 阻塞中的 connect 在 shutdown 后才返回时，管理器必须再次断开该迟到连接。 */
    @Test
    void shouldDisconnectConnectionThatReturnsAfterShutdown() throws Exception {
        BlockingConnectAdapter adapter = new BlockingConnectAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager = new ChannelConnectionManager(adapters);
        Thread starter = new Thread(manager::startAll, "channel-connect-shutdown-test");

        try {
            starter.start();
            assertThat(adapter.connectEntered.await(2, TimeUnit.SECONDS)).isTrue();

            manager.shutdown();
            adapter.releaseConnect.countDown();
            starter.join(TimeUnit.SECONDS.toMillis(2L));

            assertThat(starter.isAlive()).isFalse();
            assertThat(adapter.isConnected()).isFalse();
            assertThat(adapter.disconnectCalls.get()).isGreaterThanOrEqualTo(2);
        } finally {
            adapter.releaseConnect.countDown();
            manager.shutdown();
            starter.join(TimeUnit.SECONDS.toMillis(2L));
        }
    }

    /** 延迟重连必须恢复管理器构造时的 Profile，而不是使用调度调用线程的空或错误作用域。 */
    @Test
    void shouldRestoreCapturedProfileForReconnectTask() throws Exception {
        long[] backoff = reconnectBackoff();
        long previousDelay = backoff[0];
        backoff[0] = 0L;
        Path home = Files.createTempDirectory("channel-profile-scope");
        ScopeRecordingAdapter adapter = new ScopeRecordingAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(adapter.platform(), adapter);
        ChannelConnectionManager manager;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open("worker", home, Collections.emptyMap(), null)) {
            manager = new ChannelConnectionManager(adapters);
        }

        try {
            manager.startAll();
            assertThat(adapter.reconnectAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(adapter.observedProfile).isEqualTo("worker");
        } finally {
            manager.shutdown();
            backoff[0] = previousDelay;
        }
    }

    /** 读取测试需要临时缩短的重连退避数组。 */
    private long[] reconnectBackoff() throws Exception {
        Field backoffField = ChannelConnectionManager.class.getDeclaredField("BACKOFF_SECONDS");
        backoffField.setAccessible(true);
        return (long[]) backoffField.get(null);
    }

    /** 读取当前按平台保存的重连 Future。 */
    @SuppressWarnings("unchecked")
    private Map<PlatformType, ScheduledFuture<?>> reconnectFutures(ChannelConnectionManager manager)
            throws Exception {
        Field futuresField = ChannelConnectionManager.class.getDeclaredField("reconnectFutures");
        futuresField.setAccessible(true);
        return (Map<PlatformType, ScheduledFuture<?>>) futuresField.get(manager);
    }

    /** 直接触发一次 watchdog 扫描，避免单元测试等待真实周期。 */
    private void invokeWatchdog(ChannelConnectionManager manager) throws Exception {
        Method watchdog = ChannelConnectionManager.class.getDeclaredMethod("watchdogReconnects");
        watchdog.setAccessible(true);
        watchdog.invoke(manager);
    }

    /** 把指定平台的 SDK 自恢复截止时间推进到过去。 */
    @SuppressWarnings("unchecked")
    private void expireSdkRecoveryGrace(ChannelConnectionManager manager, PlatformType platform)
            throws Exception {
        Field deadlinesField =
                ChannelConnectionManager.class.getDeclaredField("sdkRecoveryDeadlines");
        deadlinesField.setAccessible(true);
        Map<PlatformType, Long> deadlines = (Map<PlatformType, Long>) deadlinesField.get(manager);
        deadlines.put(platform, Long.valueOf(System.currentTimeMillis() - 1L));
    }

    /** 等待连接失败被管理器完成记账，避免适配器抛错与状态推进之间的竞态。 */
    private void awaitFailureCount(ChannelConnectionManager manager, int expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2L);
        while (System.currentTimeMillis() < deadline) {
            if (manager.statusSnapshots().get(0).getReconnectFailureCount() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    private static class FailingChannelAdapter implements ChannelAdapter {
        private final PlatformType platform;
        private final String failureMessage;

        /** 等待连接调用达到测试目标次数的信号。 */
        private final CountDownLatch attemptsReached;

        private FailingChannelAdapter(PlatformType platform, String failureMessage) {
            this(platform, failureMessage, 0);
        }

        /** 创建会在指定连接次数后释放等待信号的失败适配器。 */
        private FailingChannelAdapter(
                PlatformType platform, String failureMessage, int expectedAttempts) {
            this.platform = platform;
            this.failureMessage = failureMessage;
            this.attemptsReached = new CountDownLatch(Math.max(0, expectedAttempts));
        }

        /** 等待指定数量的连接调用完成。 */
        private boolean awaitAttempts(long timeout, TimeUnit unit) throws InterruptedException {
            return attemptsReached.await(timeout, unit);
        }

        @Override
        public PlatformType platform() {
            return platform;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean connect() {
            attemptsReached.countDown();
            throw new IllegalStateException(failureMessage);
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String detail() {
            return "connect failed";
        }

        @Override
        public void send(DeliveryRequest request) {}
    }

    /** 模拟先连接成功、随后由平台回调报告断线的渠道。 */
    private static final class RuntimeDisconnectedAdapter implements ChannelAdapter {
        /** 当前连接状态。 */
        private boolean connected;

        /** 连接管理器注入的重连处理器。 */
        private Runnable reconnectHandler;

        /** 连接方法累计调用次数。 */
        private final AtomicInteger connectCalls = new AtomicInteger();

        /** 模拟运行期断线并请求统一重连。 */
        private void reportDisconnect() {
            connected = false;
            reconnectHandler.run();
        }

        /** 模拟适配器丢失连接但没有触发断线回调。 */
        private void loseConnectionWithoutCallback() {
            connected = false;
        }

        /** 返回连接方法累计调用次数。 */
        private int connectCallCount() {
            return connectCalls.get();
        }

        /** 返回测试渠道平台。 */
        @Override
        public PlatformType platform() {
            return PlatformType.WECOM;
        }

        /** 测试渠道始终启用。 */
        @Override
        public boolean isEnabled() {
            return true;
        }

        /** 模拟连接成功。 */
        @Override
        public boolean connect() {
            connectCalls.incrementAndGet();
            connected = true;
            return true;
        }

        /** 模拟断开连接。 */
        @Override
        public void disconnect() {
            connected = false;
        }

        /** 返回当前模拟连接状态。 */
        @Override
        public boolean isConnected() {
            return connected;
        }

        /** 返回测试状态详情。 */
        @Override
        public String detail() {
            return connected ? "connected" : "disconnected";
        }

        /** 测试渠道不执行实际发送。 */
        @Override
        public void send(DeliveryRequest request) {}

        /** 保存连接管理器注入的运行期重连回调。 */
        @Override
        public void setReconnectHandler(Runnable reconnectHandler) {
            this.reconnectHandler = reconnectHandler;
        }
    }

    /** 模拟 DingTalk 这类只能观察 SDK ready/unavailable 回调、不能查询真实健康的渠道。 */
    private static final class UnobservableSdkAdapter implements ChannelAdapter {
        /** 管理器注入的 ready 回调。 */
        private Runnable readyHandler;

        /** 管理器注入的 SDK 不可用回调。 */
        private Runnable unavailableHandler;

        /** 模拟 SDK 进入内部自恢复。 */
        private void reportUnavailable() {
            unavailableHandler.run();
        }

        /** 返回钉钉平台以贴近真实不可观测适配器。 */
        @Override
        public PlatformType platform() {
            return PlatformType.DINGTALK;
        }

        /** 测试渠道始终启用。 */
        @Override
        public boolean isEnabled() {
            return true;
        }

        /** 模拟 SDK 启动成功并发出 ready，但不提供 connected 状态。 */
        @Override
        public boolean connect() {
            readyHandler.run();
            return true;
        }

        /** 测试不需要额外断开动作。 */
        @Override
        public void disconnect() {}

        /** SDK 不提供可查询连接状态。 */
        @Override
        public boolean isConnected() {
            return false;
        }

        /** 明确声明连接健康不可观测。 */
        @Override
        public boolean isConnectionHealthObservable() {
            return false;
        }

        /** 返回测试连接详情。 */
        @Override
        public String detail() {
            return "sdk started";
        }

        /** 测试渠道不执行实际发送。 */
        @Override
        public void send(DeliveryRequest request) {}

        /** 保存管理器注入的 ready 回调。 */
        @Override
        public void setConnectionReadyHandler(Runnable connectionReadyHandler) {
            this.readyHandler = connectionReadyHandler;
        }

        /** 保存管理器注入的 SDK 不可用回调。 */
        @Override
        public void setConnectionUnavailableHandler(Runnable connectionUnavailableHandler) {
            this.unavailableHandler = connectionUnavailableHandler;
        }
    }

    /** 模拟 connect 在 shutdown 的 disconnect 完成后才返回成功的渠道。 */
    private static final class BlockingConnectAdapter implements ChannelAdapter {
        /** 连接方法已经进入阻塞段。 */
        private final CountDownLatch connectEntered = new CountDownLatch(1);

        /** 测试允许连接方法返回。 */
        private final CountDownLatch releaseConnect = new CountDownLatch(1);

        /** 断开方法调用次数。 */
        private final AtomicInteger disconnectCalls = new AtomicInteger();

        /** 当前模拟连接状态。 */
        private volatile boolean connected;

        /** 返回测试渠道平台。 */
        @Override
        public PlatformType platform() {
            return PlatformType.DINGTALK;
        }

        /** 测试渠道始终启用。 */
        @Override
        public boolean isEnabled() {
            return true;
        }

        /** 等待测试放行后模拟连接成功。 */
        @Override
        public boolean connect() {
            connectEntered.countDown();
            try {
                releaseConnect.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            connected = true;
            return true;
        }

        /** 记录断开并清除连接状态。 */
        @Override
        public void disconnect() {
            disconnectCalls.incrementAndGet();
            connected = false;
        }

        /** 返回当前模拟连接状态。 */
        @Override
        public boolean isConnected() {
            return connected;
        }

        /** 返回测试连接详情。 */
        @Override
        public String detail() {
            return connected ? "connected" : "disconnected";
        }

        /** 测试渠道不执行实际发送。 */
        @Override
        public void send(DeliveryRequest request) {}
    }

    /** 首次连接失败、第二次记录执行作用域的测试渠道。 */
    private static final class ScopeRecordingAdapter implements ChannelAdapter {
        private final CountDownLatch reconnectAttempted = new CountDownLatch(1);
        private int attempts;
        private volatile String observedProfile;

        @Override
        public PlatformType platform() {
            return PlatformType.FEISHU;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public synchronized boolean connect() {
            attempts++;
            if (attempts > 1) {
                ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
                observedProfile = current == null ? null : current.getProfile();
                reconnectAttempted.countDown();
            }
            throw new IllegalStateException("retry");
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String detail() {
            return "retry";
        }

        @Override
        public void send(DeliveryRequest request) {}
    }
}
