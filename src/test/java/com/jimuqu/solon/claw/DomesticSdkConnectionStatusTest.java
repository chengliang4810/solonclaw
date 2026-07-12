package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.lark.oapi.event.EventDispatcher;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** 验证国内渠道 SDK 生命周期信号不会被误判为真实连接健康。 */
public class DomesticSdkConnectionStatusTest {
    /** 验证飞书 SDK 自动重连回调已经接入适配器状态，并能在成功后恢复健康。 */
    @Test
    void shouldTrackFeishuSdkReconnectLifecycle() throws Exception {
        TestFeishuChannelAdapter adapter = new TestFeishuChannelAdapter(feishuConfig());
        com.lark.oapi.ws.Client client =
                adapter.createClient(EventDispatcher.newBuilder("", "").build());

        callback(client, "onReconnecting").run();

        ChannelStatus reconnecting = adapter.statusSnapshot();
        assertThat(reconnecting.isConnected()).isFalse();
        assertThat(reconnecting.getSetupState()).isEqualTo("reconnecting");
        assertThat(reconnecting.getDetail()).isEqualTo("websocket reconnecting");

        callback(client, "onReconnected").run();

        ChannelStatus reconnected = adapter.statusSnapshot();
        assertThat(reconnected.isConnected()).isTrue();
        assertThat(reconnected.getSetupState()).isEqualTo("connected");
        assertThat(reconnected.getDetail()).isEqualTo("websocket connected");
    }

    /** 验证钉钉 SDK 启动仅代表生命周期已开始，Doctor 状态仍明确表示连接健康未知。 */
    @Test
    void shouldKeepDingTalkHealthUnknownAfterStreamClientStarts() {
        TestDingTalkChannelAdapter adapter = new TestDingTalkChannelAdapter(dingTalkConfig());

        adapter.markStarted();

        ChannelStatus status = adapter.statusSnapshot();
        assertThat(status.isConnected()).isFalse();
        assertThat(status.getSetupState()).isEqualTo("configured");
        assertThat(status.getMissingConfig()).isEmpty();
        assertThat(status.getLastErrorCode()).isNull();
        assertThat(status.getDetail())
                .isEqualTo("stream client started; connection health unavailable");
    }

    /** 从飞书 SDK 客户端读取指定生命周期回调，验证生产 Builder 的实际接线。 */
    private Runnable callback(com.lark.oapi.ws.Client client, String fieldName) throws Exception {
        Field field = com.lark.oapi.ws.Client.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Runnable) field.get(client);
    }

    /** 构造启用状态的飞书测试配置，不建立真实平台连接。 */
    private AppConfig.ChannelConfig feishuConfig() {
        AppConfig.ChannelConfig config = new AppConfig.ChannelConfig();
        config.setEnabled(true);
        config.setAppId("app-id");
        config.setAppSecret("app-secret");
        return config;
    }

    /** 构造启用状态的钉钉测试配置，不建立真实平台连接。 */
    private AppConfig.ChannelConfig dingTalkConfig() {
        AppConfig.ChannelConfig config = new AppConfig.ChannelConfig();
        config.setEnabled(true);
        config.setClientId("client-id");
        config.setClientSecret("client-secret");
        config.setRobotCode("robot-code");
        return config;
    }

    /** 暴露飞书 WebSocket 客户端构造入口，用于验证 SDK 回调配置。 */
    private static final class TestFeishuChannelAdapter extends FeishuChannelAdapter {
        /** 使用测试配置创建飞书适配器。 */
        private TestFeishuChannelAdapter(AppConfig.ChannelConfig config) {
            super(config, new AttachmentCacheService(new AppConfig()));
        }

        /** 返回尚未启动网络连接的飞书 SDK 客户端。 */
        private com.lark.oapi.ws.Client createClient(EventDispatcher dispatcher) {
            return createWebsocketClient(dispatcher);
        }
    }

    /** 暴露钉钉 Stream 启动后的状态更新入口，避免单元测试访问真实网络。 */
    private static final class TestDingTalkChannelAdapter extends DingTalkChannelAdapter {
        /** 使用测试配置创建钉钉适配器。 */
        private TestDingTalkChannelAdapter(AppConfig.ChannelConfig config) {
            super(config, null, new AttachmentCacheService(new AppConfig()));
        }

        /** 模拟 SDK start 返回后的生产状态更新。 */
        private void markStarted() {
            markStreamClientStarted();
        }
    }
}
