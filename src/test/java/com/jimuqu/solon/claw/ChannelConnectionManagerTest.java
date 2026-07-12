package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    /** 延迟重连必须恢复管理器构造时的 Profile，而不是使用调度调用线程的空或错误作用域。 */
    @Test
    void shouldRestoreCapturedProfileForReconnectTask() throws Exception {
        Field backoffField = ChannelConnectionManager.class.getDeclaredField("BACKOFF_SECONDS");
        backoffField.setAccessible(true);
        long[] backoff = (long[]) backoffField.get(null);
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

    private static class FailingChannelAdapter implements ChannelAdapter {
        private final PlatformType platform;
        private final String failureMessage;

        private FailingChannelAdapter(PlatformType platform, String failureMessage) {
            this.platform = platform;
            this.failureMessage = failureMessage;
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

        /** 模拟运行期断线并请求统一重连。 */
        private void reportDisconnect() {
            connected = false;
            reconnectHandler.run();
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
