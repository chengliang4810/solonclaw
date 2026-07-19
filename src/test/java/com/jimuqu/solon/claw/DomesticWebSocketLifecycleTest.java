package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.gateway.platform.qqbot.QQBotChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.yuanbao.YuanbaoChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.noear.snack4.ONode;

/** 验证国内 WebSocket 渠道的 ready 状态与运行期重连边界。 */
public class DomesticWebSocketLifecycleTest {
    /** QQBot 业务 Dispatch 只有在入站准入正常结束后才能推进 Resume 序号。 */
    @Test
    void shouldAdvanceQqSequenceOnlyAfterInboundAdmission() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config,
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        null);
        setField(
                adapter,
                QQBotChannelAdapter.class,
                "callbackExecutor",
                Executors.newSingleThreadExecutor());
        setField(adapter, QQBotChannelAdapter.class, "gatewaySequence", Long.valueOf(20L));
        RecordingWebSocket socket = new RecordingWebSocket();
        setField(adapter, QQBotChannelAdapter.class, "webSocket", socket);
        AtomicInteger admissions = new AtomicInteger();
        CountDownLatch handled = new CountDownLatch(1);
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    /** 首次模拟 SQLite 写入失败，第二次接受同一平台消息。 */
                    @Override
                    public boolean admit(GatewayMessage message) throws Exception {
                        if (admissions.incrementAndGet() == 1) {
                            throw new Exception("simulated persistence failure");
                        }
                        return true;
                    }

                    /** 记录第二次成功准入后的业务处理。 */
                    @Override
                    public void handle(GatewayMessage message) {
                        handled.countDown();
                    }
                });
        WebSocketListener socketListener = listener(QQBotChannelAdapter.class, adapter);
        String frame =
                "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"s\":21,\"d\":{"
                        + "\"id\":\"qq-sequence-message\",\"openid\":\"qq-user\","
                        + "\"content\":\"hello\"}}";

        try {
            assertThatThrownBy(() -> socketListener.onMessage(socket, frame))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inbound admission failed");
            assertThat(field(adapter, QQBotChannelAdapter.class, "gatewaySequence"))
                    .isEqualTo(Long.valueOf(20L));

            socketListener.onMessage(socket, frame);

            assertThat(field(adapter, QQBotChannelAdapter.class, "gatewaySequence"))
                    .isEqualTo(Long.valueOf(21L));
            assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(admissions.get()).isEqualTo(2);
        } finally {
            adapter.disconnect();
        }
    }

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

    /** QQBot 必须完成 Identify/READY，重连后再携带已有会话与序号 Resume。 */
    @Test
    void shouldAuthenticateAndResumeQQBotGateway() throws Exception {
        AppConfig config = new AppConfig();
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config,
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        null);
        setField(adapter, QQBotChannelAdapter.class, "accessToken", "test-access-token");
        setField(
                adapter,
                QQBotChannelAdapter.class,
                "accessTokenExpireAt",
                Long.valueOf(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)));
        RecordingWebSocket first = new RecordingWebSocket();
        setField(adapter, QQBotChannelAdapter.class, "webSocket", first);
        WebSocketListener firstListener = listener(QQBotChannelAdapter.class, adapter);

        try {
            firstListener.onOpen(first, null);
            ChannelStatus opening = adapter.statusSnapshot();
            assertThat(opening.isConnected()).isFalse();
            assertThat(opening.getSetupState()).isEqualTo("connecting");
            assertThat(opening.getLastErrorCode()).isNull();

            firstListener.onMessage(first, "{\"op\":10,\"d\":{\"heartbeat_interval\":125}}");
            ONode identify = ONode.ofJson(first.sentTexts.get(0));
            assertThat(identify.get("op").getInt()).isEqualTo(2);
            assertThat(identify.get("d").get("token").getString())
                    .isEqualTo("QQBot test-access-token");
            assertThat(identify.get("d").get("intents").getInt()).isPositive();

            firstListener.onMessage(
                    first,
                    "{\"op\":0,\"t\":\"READY\",\"s\":17,"
                            + "\"d\":{\"session_id\":\"session-1\"}}");
            ChannelStatus ready = adapter.statusSnapshot();
            assertThat(ready.isConnected()).isTrue();
            assertThat(ready.getSetupState()).isEqualTo("connected");

            RecordingWebSocket resumed = new RecordingWebSocket();
            setField(adapter, QQBotChannelAdapter.class, "webSocket", resumed);
            WebSocketListener resumedListener = listener(QQBotChannelAdapter.class, adapter);
            resumedListener.onOpen(resumed, null);
            resumedListener.onMessage(resumed, "{\"op\":10,\"d\":{\"heartbeat_interval\":125}}");
            ONode resume = ONode.ofJson(resumed.sentTexts.get(0));
            assertThat(resume.get("op").getInt()).isEqualTo(6);
            assertThat(resume.get("d").get("session_id").getString()).isEqualTo("session-1");
            assertThat(resume.get("d").get("seq").getLong()).isEqualTo(17L);

            resumedListener.onMessage(resumed, "{\"op\":0,\"t\":\"RESUMED\",\"s\":18,\"d\":{}}");
            assertThat(adapter.statusSnapshot().isConnected()).isTrue();
            awaitSentFrames(resumed, 2);
            ONode heartbeat = ONode.ofJson(resumed.sentTexts.get(1));
            assertThat(heartbeat.get("op").getInt()).isEqualTo(1);
            assertThat(heartbeat.get("d").getLong()).isEqualTo(18L);
        } finally {
            adapter.disconnect();
        }
    }

    /** QQBot 无效且不可恢复的会话必须清空，下一条 Hello 重新 Identify。 */
    @Test
    void shouldIdentifyAgainAfterQQBotInvalidSession() throws Exception {
        AppConfig config = new AppConfig();
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config,
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        null);
        setField(adapter, QQBotChannelAdapter.class, "accessToken", "test-access-token");
        setField(
                adapter,
                QQBotChannelAdapter.class,
                "accessTokenExpireAt",
                Long.valueOf(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)));
        setField(adapter, QQBotChannelAdapter.class, "gatewaySessionId", "session-old");
        setField(adapter, QQBotChannelAdapter.class, "gatewaySequence", Long.valueOf(9L));
        RecordingWebSocket rejected = new RecordingWebSocket();
        setField(adapter, QQBotChannelAdapter.class, "webSocket", rejected);
        WebSocketListener rejectedListener = listener(QQBotChannelAdapter.class, adapter);
        AtomicInteger reconnects = new AtomicInteger();
        adapter.setReconnectHandler(reconnects::incrementAndGet);

        try {
            rejectedListener.onMessage(rejected, "{\"op\":9,\"d\":false}");
            assertThat(reconnects.get()).isEqualTo(1);
            assertThat(rejected.closeCount.get()).isEqualTo(1);

            RecordingWebSocket replacement = new RecordingWebSocket();
            setField(adapter, QQBotChannelAdapter.class, "webSocket", replacement);
            WebSocketListener replacementListener = listener(QQBotChannelAdapter.class, adapter);
            replacementListener.onOpen(replacement, null);
            replacementListener.onMessage(
                    replacement, "{\"op\":10,\"d\":{\"heartbeat_interval\":30000}}");
            assertThat(ONode.ofJson(replacement.sentTexts.get(0)).get("op").getInt()).isEqualTo(2);
        } finally {
            adapter.disconnect();
        }
    }

    /** QQBot 关闭码必须分别刷新凭据、清理会话、限流退避或停止重连。 */
    @Test
    void shouldClassifyQQBotGatewayCloseCodes() throws Exception {
        QQBotChannelAdapter tokenAdapter = qqBotAdapterWithCachedToken();
        AtomicInteger tokenReconnects = new AtomicInteger();
        tokenAdapter.setReconnectHandler(tokenReconnects::incrementAndGet);
        RecordingWebSocket tokenSocket = new RecordingWebSocket();
        setField(tokenAdapter, QQBotChannelAdapter.class, "webSocket", tokenSocket);
        listener(QQBotChannelAdapter.class, tokenAdapter)
                .onClosed(tokenSocket, 4004, "invalid token");
        assertThat(field(tokenAdapter, QQBotChannelAdapter.class, "accessToken")).isNull();
        assertThat(tokenReconnects.get()).isEqualTo(1);

        QQBotChannelAdapter sessionAdapter = qqBotAdapterWithCachedToken();
        setField(sessionAdapter, QQBotChannelAdapter.class, "gatewaySessionId", "expired");
        setField(sessionAdapter, QQBotChannelAdapter.class, "gatewaySequence", Long.valueOf(7L));
        RecordingWebSocket sessionSocket = new RecordingWebSocket();
        setField(sessionAdapter, QQBotChannelAdapter.class, "webSocket", sessionSocket);
        listener(QQBotChannelAdapter.class, sessionAdapter)
                .onClosed(sessionSocket, 4009, "session timeout");
        assertThat(field(sessionAdapter, QQBotChannelAdapter.class, "gatewaySessionId")).isNull();
        assertThat(field(sessionAdapter, QQBotChannelAdapter.class, "gatewaySequence")).isNull();

        QQBotChannelAdapter limitedAdapter = qqBotAdapterWithCachedToken();
        AtomicInteger limitedReconnects = new AtomicInteger();
        limitedAdapter.setReconnectHandler(limitedReconnects::incrementAndGet);
        RecordingWebSocket limitedSocket = new RecordingWebSocket();
        setField(limitedAdapter, QQBotChannelAdapter.class, "webSocket", limitedSocket);
        listener(QQBotChannelAdapter.class, limitedAdapter)
                .onClosed(limitedSocket, 4008, "rate limited");
        assertThat(limitedReconnects.get()).isZero();
        Object reconnectTask = field(limitedAdapter, QQBotChannelAdapter.class, "reconnectFuture");
        assertThat(reconnectTask).isNotNull();

        QQBotChannelAdapter fatalAdapter = qqBotAdapterWithCachedToken();
        AtomicInteger fatalReconnects = new AtomicInteger();
        fatalAdapter.setReconnectHandler(fatalReconnects::incrementAndGet);
        RecordingWebSocket fatalSocket = new RecordingWebSocket();
        setField(fatalAdapter, QQBotChannelAdapter.class, "webSocket", fatalSocket);
        listener(QQBotChannelAdapter.class, fatalAdapter)
                .onClosed(fatalSocket, 4915, "private server detail");
        assertThat(fatalReconnects.get()).isZero();
        assertThat(fatalAdapter.statusSnapshot().getSetupState()).isEqualTo("error");
        assertThat(fatalAdapter.statusSnapshot().getLastErrorMessage()).isEqualTo("bot banned");
        assertThat(
                        invoke(
                                fatalAdapter,
                                QQBotChannelAdapter.class,
                                "isFatalCloseCode",
                                new Class<?>[] {Integer.TYPE},
                                Integer.valueOf(4014)))
                .isEqualTo(Boolean.TRUE);
        assertThat(
                        invoke(
                                fatalAdapter,
                                QQBotChannelAdapter.class,
                                "isFatalCloseCode",
                                new Class<?>[] {Integer.TYPE},
                                Integer.valueOf(4914)))
                .isEqualTo(Boolean.TRUE);

        tokenAdapter.disconnect();
        sessionAdapter.disconnect();
        limitedAdapter.disconnect();
        fatalAdapter.disconnect();
    }

    /** QQBot 握手超时必须公开错误并重连，旧连接迟到帧不得污染新连接。 */
    @Test
    void shouldTimeoutQQBotHandshakeAndIgnoreStaleSocket() throws Exception {
        QQBotChannelAdapter adapter = qqBotAdapterWithCachedToken();
        AtomicInteger reconnects = new AtomicInteger();
        adapter.setReconnectHandler(reconnects::incrementAndGet);
        RecordingWebSocket stale = new RecordingWebSocket();
        RecordingWebSocket current = new RecordingWebSocket();
        WebSocketListener staleListener = listener(QQBotChannelAdapter.class, adapter);
        setField(adapter, QQBotChannelAdapter.class, "webSocket", stale);
        staleListener.onOpen(stale, null);
        assertThat(field(adapter, QQBotChannelAdapter.class, "handshakeFuture")).isNotNull();

        setField(adapter, QQBotChannelAdapter.class, "webSocket", current);
        staleListener.onMessage(stale, "{\"op\":10,\"d\":{\"heartbeat_interval\":1000}}");
        staleListener.onMessage(
                stale, "{\"op\":0,\"t\":\"READY\",\"s\":1," + "\"d\":{\"session_id\":\"stale\"}}");
        assertThat(stale.sentTexts).isEmpty();
        assertThat(adapter.isConnected()).isFalse();

        invoke(
                adapter,
                QQBotChannelAdapter.class,
                "handleHandshakeTimeout",
                new Class<?>[] {WebSocket.class, String.class},
                current,
                "ready timeout");
        assertThat(reconnects.get()).isEqualTo(1);
        assertThat(adapter.statusSnapshot().getLastErrorCode())
                .isEqualTo("qqbot_gateway_handshake_timeout");
        adapter.disconnect();
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

    /** 读取私有状态以验证协议生命周期不会泄漏或串线。 */
    private static Object field(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** 调用私有协议回调，避免测试真实等待握手超时。 */
    private static Object invoke(
            Object target,
            Class<?> owner,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    /** 创建带有效缓存 token 的 QQBot 适配器，测试不访问外部网络。 */
    private static QQBotChannelAdapter qqBotAdapterWithCachedToken() throws Exception {
        AppConfig config = new AppConfig();
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config,
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        null);
        setField(adapter, QQBotChannelAdapter.class, "accessToken", "test-access-token");
        setField(
                adapter,
                QQBotChannelAdapter.class,
                "accessTokenExpireAt",
                Long.valueOf(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)));
        return adapter;
    }

    /** 等待异步心跳进入测试 socket 的发送队列。 */
    private static void awaitSentFrames(RecordingWebSocket socket, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (socket.sentTexts.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(socket.sentTexts).hasSizeGreaterThanOrEqualTo(expected);
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
        /** 记录协议层发出的文本帧。 */
        private final List<String> sentTexts = new CopyOnWriteArrayList<String>();

        /** 记录协议主动关闭次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

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
            sentTexts.add(text);
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
            closeCount.incrementAndGet();
            return true;
        }

        /** 测试取消无需执行动作。 */
        @Override
        public void cancel() {}
    }
}
