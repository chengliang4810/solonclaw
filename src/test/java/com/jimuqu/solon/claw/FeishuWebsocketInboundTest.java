package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证飞书 WebSocket 入站事件转换为统一网关消息的关键过滤与字段归一化行为。 */
public class FeishuWebsocketInboundTest {
    @Test
    void shouldConvertWebsocketMessageEventToGatewayMessage() {
        FeishuChannelAdapter adapter = adapter(channelConfig());

        AtomicReference<GatewayMessage> captured = new AtomicReference<GatewayMessage>();
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    /** 记录转换后的网关消息，便于断言飞书字段归一化结果。 */
                    @Override
                    public void handle(GatewayMessage message) {
                        captured.set(message);
                    }
                });

        EventMessage eventMessage =
                EventMessage.newBuilder()
                        .messageId("om_ws_1")
                        .chatId("oc_chat")
                        .chatType("p2p")
                        .messageType("text")
                        .content("{\"text\":\"hello websocket\"}")
                        .build();
        UserId userId =
                UserId.newBuilder().openId("ou_user").userId("u_user").unionId("on_union").build();
        EventSender sender = EventSender.newBuilder().senderId(userId).build();
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(eventMessage);
        data.setSender(sender);

        adapter.handleWebsocketEvent(data);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getChatId()).isEqualTo("oc_chat");
        assertThat(captured.get().getUserId()).isEqualTo("ou_user");
        assertThat(captured.get().getText()).isEqualTo("hello websocket");
        assertThat(captured.get().getThreadId()).isEqualTo("om_ws_1");
    }

    @Test
    void shouldAllowMentionedAllowedGroupMessage() {
        AppConfig.ChannelConfig channelConfig = channelConfig();
        channelConfig.setGroupAllowedUsers(List.of("oc_group"));
        channelConfig.setBotOpenId("ou_bot");
        FeishuChannelAdapter adapter = adapter(channelConfig);
        AtomicReference<GatewayMessage> captured = new AtomicReference<GatewayMessage>();
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    /** 捕获允许通过的群聊消息，验证群聊来源键所需字段保持稳定。 */
                    @Override
                    public void handle(GatewayMessage message) {
                        captured.set(message);
                    }
                });

        adapter.handleWebsocketEvent(
                eventData(
                        groupMessage(
                                "om_group_1",
                                "{\"text\":\"@_user_1 帮我查一下\"}",
                                mention("ou_bot", "solonclaw Bot")),
                        sender("ou_sender", "u_sender")));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getChatType()).isEqualTo("group");
        assertThat(captured.get().getChatId()).isEqualTo("oc_group");
        assertThat(captured.get().getUserId()).isEqualTo("ou_sender");
        assertThat(captured.get().getText()).isEqualTo("@_user_1 帮我查一下");
        assertThat(captured.get().sourceKey()).isEqualTo("FEISHU:oc_group:om_group_1:ou_sender");
    }

    @Test
    void shouldDropAllowedGroupMessageWithoutAnyMention() {
        AppConfig.ChannelConfig channelConfig = channelConfig();
        channelConfig.setGroupAllowedUsers(List.of("oc_group"));
        channelConfig.setBotOpenId("ou_bot");
        FeishuChannelAdapter adapter = adapter(channelConfig);
        AtomicReference<GatewayMessage> captured = new AtomicReference<GatewayMessage>();
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    /** 只在过滤失效时才会写入消息，用来证明未提及机器人时不会进入主链路。 */
                    @Override
                    public void handle(GatewayMessage message) {
                        captured.set(message);
                    }
                });

        adapter.handleWebsocketEvent(
                eventData(
                        groupMessage(
                                "om_group_2",
                                "{\"text\":\"群内普通聊天\"}",
                                null),
                        sender("ou_sender", "u_sender")));

        assertThat(captured.get()).isNull();
    }

    @Test
    void shouldAllowFreeResponseGroupMessageWithoutMention() {
        AppConfig.ChannelConfig channelConfig = channelConfig();
        channelConfig.setGroupAllowedUsers(List.of("oc_group"));
        channelConfig.setBotOpenId("ou_bot");
        channelConfig.setFreeResponseChats(List.of("oc_group"));
        FeishuChannelAdapter adapter = adapter(channelConfig);
        AtomicReference<GatewayMessage> captured = new AtomicReference<GatewayMessage>();
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    /** 捕获免提及群聊消息，验证指定会话可跳过机器人提及检查。 */
                    @Override
                    public void handle(GatewayMessage message) {
                        captured.set(message);
                    }
                });

        adapter.handleWebsocketEvent(
                eventData(
                        groupMessage("om_group_free", "{\"text\":\"群内免提及问题\"}", null),
                        sender("ou_sender", "u_sender")));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getChatId()).isEqualTo("oc_group");
        assertThat(captured.get().getText()).isEqualTo("群内免提及问题");
    }

    @Test
    void shouldAllowGroupMessageWhenMentionRequirementDisabled() {
        AppConfig.ChannelConfig channelConfig = channelConfig();
        channelConfig.setGroupAllowedUsers(List.of("oc_group"));
        channelConfig.setBotOpenId("ou_bot");
        channelConfig.setRequireMention(false);
        FeishuChannelAdapter adapter = adapter(channelConfig);
        AtomicReference<GatewayMessage> captured = new AtomicReference<GatewayMessage>();
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    /** 捕获关闭强制提及后的群聊消息，验证开关确实影响入站过滤。 */
                    @Override
                    public void handle(GatewayMessage message) {
                        captured.set(message);
                    }
                });

        adapter.handleWebsocketEvent(
                eventData(
                        groupMessage("om_group_open", "{\"text\":\"无需提及也响应\"}", null),
                        sender("ou_sender", "u_sender")));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getChatId()).isEqualTo("oc_group");
        assertThat(captured.get().getText()).isEqualTo("无需提及也响应");
    }

    /** 构造启用状态的飞书渠道基础配置，避免每个用例重复填写凭据字段。 */
    private AppConfig.ChannelConfig channelConfig() {
        AppConfig.ChannelConfig channelConfig = new AppConfig.ChannelConfig();
        channelConfig.setEnabled(true);
        channelConfig.setAppId("app");
        channelConfig.setAppSecret("secret");
        return channelConfig;
    }

    /** 基于给定渠道配置创建飞书适配器，测试中不启动真实 WebSocket 连接。 */
    private FeishuChannelAdapter adapter(AppConfig.ChannelConfig channelConfig) {
        return new FeishuChannelAdapter(
                channelConfig, new AttachmentCacheService(new AppConfig()));
    }

    /** 组装飞书 SDK 回调数据对象，保持测试入口与真实 WebSocket 回调一致。 */
    private P2MessageReceiveV1Data eventData(EventMessage message, EventSender sender) {
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(message);
        data.setSender(sender);
        return data;
    }

    /** 构造群聊文本消息，并按需写入提及列表以覆盖群聊过滤分支。 */
    private EventMessage groupMessage(String messageId, String content, MentionEvent mention) {
        return EventMessage.newBuilder()
                .messageId(messageId)
                .chatId("oc_group")
                .chatType("group")
                .messageType("text")
                .content(content)
                .mentions(mention == null ? new MentionEvent[0] : new MentionEvent[] {mention})
                .build();
    }

    /** 构造飞书提及对象，重点写入机器人 openId 供适配器识别。 */
    private MentionEvent mention(String openId, String name) {
        return MentionEvent.newBuilder()
                .id(UserId.newBuilder().openId(openId).userId("u_bot").build())
                .name(name)
                .build();
    }

    /** 构造发送者身份，验证适配器优先使用 openId 并回退 userId 的规则。 */
    private EventSender sender(String openId, String userId) {
        return EventSender.newBuilder()
                .senderId(UserId.newBuilder().openId(openId).userId(userId).build())
                .build();
    }
}
