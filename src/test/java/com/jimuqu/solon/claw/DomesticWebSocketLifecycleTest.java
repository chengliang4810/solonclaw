package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.gateway.platform.qqbot.QQBotChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.yuanbao.YuanbaoChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;

/** 验证国内 WebSocket 渠道的 ready 状态与运行期重连边界。 */
public class DomesticWebSocketLifecycleTest {
    /** 企微只有已订阅 ready 的当前连接断开时才请求一次重连。 */
    @Test
    void shouldRequestReconnectOnceWhenReadyWeComSocketFails() throws Exception {
        AppConfig config = new AppConfig();
        TestWeComAdapter adapter = new TestWeComAdapter(config, new AttachmentCacheService(config));
        AtomicInteger reconnects = new AtomicInteger();
        adapter.setReconnectHandler(reconnects::incrementAndGet);
        RecordingWebSocket socket = new RecordingWebSocket();
        setField(adapter, WeComChannelAdapter.class, "webSocket", socket);
        adapter.markReady();
        WebSocketListener listener = weComListener(adapter);

        listener.onFailure(socket, new IOException("connection lost"), null);
        listener.onFailure(socket, new IOException("stale callback"), null);

        assertThat(adapter.isConnected()).isFalse();
        assertThat(reconnects.get()).isEqualTo(1);
    }

    /** QQBot 缺少 Identify 协议时，WebSocket 打开不能对 Doctor 误报 connected。 */
    @Test
    void shouldKeepQQBotDisconnectedUntilIdentifyIsImplemented() throws Exception {
        AppConfig config = new AppConfig();
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config,
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        null);
        RecordingWebSocket socket = new RecordingWebSocket();
        setField(adapter, QQBotChannelAdapter.class, "webSocket", socket);

        listener(QQBotChannelAdapter.class, adapter).onOpen(socket, null);

        ChannelStatus status = adapter.statusSnapshot();
        assertThat(status.isConnected()).isFalse();
        assertThat(status.getSetupState()).isEqualTo("configured");
        assertThat(status.getLastErrorCode()).isEqualTo("qqbot_identify_not_implemented");
    }

    /** 元宝带签名的 WebSocket 升级成功后才可标记 ready。 */
    @Test
    void shouldMarkYuanbaoConnectedOnlyAfterSignedWebSocketOpens() throws Exception {
        AppConfig config = new AppConfig();
        YuanbaoChannelAdapter adapter =
                new YuanbaoChannelAdapter(
                        config.getChannels().getYuanbao(),
                        new AttachmentCacheService(config),
                        null);
        RecordingWebSocket socket = new RecordingWebSocket();
        setField(adapter, YuanbaoChannelAdapter.class, "webSocket", socket);

        assertThat(adapter.isConnected()).isFalse();
        listener(YuanbaoChannelAdapter.class, adapter).onOpen(socket, null);
        assertThat(adapter.isConnected()).isTrue();
    }

    /** 元宝重连后必须忽略旧 socket 迟到的打开和入站消息。 */
    @Test
    void shouldIgnoreStaleYuanbaoSocketCallbacks() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setAllowAllUsers(true);
        YuanbaoChannelAdapter adapter =
                new YuanbaoChannelAdapter(
                        config.getChannels().getYuanbao(),
                        new AttachmentCacheService(config),
                        null);
        RecordingWebSocket staleSocket = new RecordingWebSocket();
        RecordingWebSocket currentSocket = new RecordingWebSocket();
        WebSocketListener staleListener = listener(YuanbaoChannelAdapter.class, adapter);
        WebSocketListener currentListener = listener(YuanbaoChannelAdapter.class, adapter);
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch handled = new CountDownLatch(1);
        adapter.setInboundMessageHandler(message -> handled.countDown());
        setField(adapter, YuanbaoChannelAdapter.class, "callbackExecutor", callbackExecutor);
        setField(adapter, YuanbaoChannelAdapter.class, "webSocket", currentSocket);
        String raw =
                "{\"body\":{\"message_id\":\"yuanbao-current-1\","
                        + "\"chat_id\":\"yuanbao-chat\",\"user_id\":\"yuanbao-user\","
                        + "\"content\":\"hello\"}}";

        try {
            staleListener.onOpen(staleSocket, null);
            staleListener.onMessage(staleSocket, raw);
            assertThat(adapter.isConnected()).isFalse();
            assertThat(handled.getCount()).isEqualTo(1L);

            currentListener.onOpen(currentSocket, null);
            currentListener.onMessage(currentSocket, raw);
            assertThat(handled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(adapter.isConnected()).isTrue();
        } finally {
            callbackExecutor.shutdownNow();
        }
    }

    /** 构造企微私有监听器并保留真实的打开锁存器参数。 */
    private static WebSocketListener weComListener(WeComChannelAdapter adapter) throws Exception {
        Class<?> type = Class.forName(WeComChannelAdapter.class.getName() + "$Listener");
        Constructor<?> constructor =
                type.getDeclaredConstructor(WeComChannelAdapter.class, CountDownLatch.class);
        constructor.setAccessible(true);
        return (WebSocketListener) constructor.newInstance(adapter, new CountDownLatch(0));
    }

    /** 构造无额外参数的适配器私有 WebSocket 监听器。 */
    private static WebSocketListener listener(Class<?> owner, Object adapter) throws Exception {
        Class<?> type = Class.forName(owner.getName() + "$Listener");
        Constructor<?> constructor = type.getDeclaredConstructor(owner);
        constructor.setAccessible(true);
        return (WebSocketListener) constructor.newInstance(adapter);
    }

    /** 写入适配器当前 WebSocket，确保终止回调来自当前连接。 */
    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 暴露企微 ready 状态设置入口，模拟订阅成功后的运行期断线。 */
    private static final class TestWeComAdapter extends WeComChannelAdapter {
        /** 创建测试企微适配器。 */
        private TestWeComAdapter(AppConfig config, AttachmentCacheService attachmentCacheService) {
            super(config.getChannels().getWecom(), attachmentCacheService);
        }

        /** 模拟企微订阅成功。 */
        private void markReady() {
            setConnected(true);
        }
    }

    /** 不访问网络的测试 WebSocket。 */
    private static final class RecordingWebSocket implements WebSocket {
        /** 返回占位请求。 */
        @Override
        public Request request() {
            return new Request.Builder().url("http://127.0.0.1").build();
        }

        /** 测试连接没有待发送数据。 */
        @Override
        public long queueSize() {
            return 0L;
        }

        /** 测试文本发送始终成功。 */
        @Override
        public boolean send(String text) {
            return true;
        }

        /** 测试二进制发送始终成功。 */
        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        /** 测试关闭始终成功。 */
        @Override
        public boolean close(int code, String reason) {
            return true;
        }

        /** 测试取消无需执行动作。 */
        @Override
        public void cancel() {}
    }
}
