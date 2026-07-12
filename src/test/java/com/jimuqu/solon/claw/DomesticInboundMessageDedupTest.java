package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.qqbot.QQBotChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.yuanbao.YuanbaoChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.noear.snack4.ONode;

/** 验证五个国内渠道按平台原始消息标识抑制重复入站。 */
public class DomesticInboundMessageDedupTest {
    /**
     * 相同消息标识只允许进入统一处理器一次。
     *
     * @param platform 渠道名称，用于参数化用例展示。
     * @param scenario 对应渠道的真实入站入口场景。
     */
    @ParameterizedTest(name = "{0} duplicate message id")
    @MethodSource("duplicateScenarios")
    void shouldDispatchDuplicateMessageIdOnce(String platform, DuplicateScenario scenario)
            throws Throwable {
        assertThat(scenario.dispatchTwice()).isEqualTo(1);
    }

    /** 平台消息 ID 只作为回复目标，不能改变同一会话的来源键。 */
    @ParameterizedTest(name = "{0} reply identity")
    @MethodSource("replyIdentityScenarios")
    void shouldKeepMessageIdSeparateFromConversationThread(
            String platform, ReplyIdentityScenario scenario) throws Throwable {
        List<GatewayMessage> messages = scenario.receiveTwoMessages();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).sourceKey()).isEqualTo(messages.get(1).sourceKey());
        assertThat(messages).extracting(GatewayMessage::getThreadId).containsOnlyNulls();
        assertThat(messages)
                .extracting(GatewayMessage::getReplyToMessageId)
                .containsExactly(platform + "-message-1", platform + "-message-2");
    }

    /** 提供五个国内渠道的重复消息入站场景。 */
    private static Stream<Arguments> duplicateScenarios() {
        return Stream.of(
                Arguments.of("feishu", (DuplicateScenario) DomesticInboundMessageDedupTest::feishu),
                Arguments.of(
                        "dingtalk", (DuplicateScenario) DomesticInboundMessageDedupTest::dingtalk),
                Arguments.of("wecom", (DuplicateScenario) DomesticInboundMessageDedupTest::wecom),
                Arguments.of("qqbot", (DuplicateScenario) DomesticInboundMessageDedupTest::qqbot),
                Arguments.of(
                        "yuanbao", (DuplicateScenario) DomesticInboundMessageDedupTest::yuanbao));
    }

    /** 提供三个使用原消息回复能力的渠道场景。 */
    private static Stream<Arguments> replyIdentityScenarios() {
        return Stream.of(
                Arguments.of(
                        "dingtalk",
                        (ReplyIdentityScenario)
                                DomesticInboundMessageDedupTest::dingtalkReplyIdentity),
                Arguments.of(
                        "wecom",
                        (ReplyIdentityScenario)
                                DomesticInboundMessageDedupTest::wecomReplyIdentity),
                Arguments.of(
                        "yuanbao",
                        (ReplyIdentityScenario)
                                DomesticInboundMessageDedupTest::yuanbaoReplyIdentity));
    }

    /** 执行飞书重复入站场景。 */
    private static int feishu() {
        AppConfig config = new AppConfig();
        FeishuChannelAdapter adapter =
                new FeishuChannelAdapter(
                        config.getChannels().getFeishu(), new AttachmentCacheService(config));
        AtomicInteger handled = handlerCount(adapter);
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(
                EventMessage.newBuilder()
                        .messageId("feishu-message-1")
                        .chatId("feishu-chat")
                        .chatType("p2p")
                        .messageType("text")
                        .content("{\"text\":\"hello\"}")
                        .build());
        data.setSender(
                EventSender.newBuilder()
                        .senderId(UserId.newBuilder().openId("feishu-user").build())
                        .build());
        adapter.handleWebsocketEvent(data);
        adapter.handleWebsocketEvent(data);
        return handled.get();
    }

    /** 执行钉钉重复入站场景。 */
    private static int dingtalk() throws Throwable {
        AppConfig config = new AppConfig();
        DingTalkChannelAdapter adapter =
                new DingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        new EmptyChannelStateRepository(),
                        new AttachmentCacheService(config));
        setField(adapter, DingTalkChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        AtomicInteger handled = handlerCount(adapter);
        MessageContent text = new MessageContent();
        text.setContent("hello");
        ChatbotMessage message = new ChatbotMessage();
        message.setMsgId("dingtalk-message-1");
        message.setConversationId("dingtalk-chat");
        message.setConversationType("1");
        message.setSenderStaffId("dingtalk-user");
        message.setMsgtype("text");
        message.setText(text);
        Method handle =
                DingTalkChannelAdapter.class.getDeclaredMethod(
                        "handleInbound", ChatbotMessage.class);
        handle.setAccessible(true);
        invoke(handle, adapter, message);
        invoke(handle, adapter, message);
        return handled.get();
    }

    /** 执行企微重复入站场景。 */
    private static int wecom() throws Throwable {
        AppConfig config = new AppConfig();
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(), new AttachmentCacheService(config));
        setField(adapter, WeComChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        AtomicInteger handled = handlerCount(adapter);
        ONode payload =
                ONode.ofJson(
                        "{\"body\":{\"msgid\":\"wecom-message-1\","
                                + "\"chatid\":\"wecom-chat\",\"chattype\":\"single\","
                                + "\"from\":{\"userid\":\"wecom-user\"},"
                                + "\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}}}");
        Method handle = WeComChannelAdapter.class.getDeclaredMethod("handleInbound", ONode.class);
        handle.setAccessible(true);
        invoke(handle, adapter, payload);
        invoke(handle, adapter, payload);
        return handled.get();
    }

    /** 执行 QQBot 重复入站场景。 */
    private static int qqbot() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);
        setField(adapter, QQBotChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        AtomicInteger handled = handlerCount(adapter);
        String raw =
                "{\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"qq-message-1\","
                        + "\"openid\":\"qq-user\",\"content\":\"hello\"}}";
        adapter.dispatch(raw);
        adapter.dispatch(raw);
        return handled.get();
    }

    /** 执行元宝重复入站场景。 */
    private static int yuanbao() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setAllowAllUsers(true);
        TestYuanbaoAdapter adapter = new TestYuanbaoAdapter(config);
        setField(adapter, YuanbaoChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        AtomicInteger handled = handlerCount(adapter);
        String raw =
                "{\"body\":{\"message_id\":\"yuanbao-message-1\","
                        + "\"chat_id\":\"yuanbao-chat\",\"user_id\":\"yuanbao-user\","
                        + "\"content\":\"hello\"}}";
        adapter.dispatch(raw);
        adapter.dispatch(raw);
        return handled.get();
    }

    /** 接收两条钉钉消息并返回统一消息模型。 */
    private static List<GatewayMessage> dingtalkReplyIdentity() throws Throwable {
        AppConfig config = new AppConfig();
        DingTalkChannelAdapter adapter =
                new DingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        new EmptyChannelStateRepository(),
                        new AttachmentCacheService(config));
        setField(adapter, DingTalkChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        List<GatewayMessage> messages = captureMessages(adapter);
        Method handle =
                DingTalkChannelAdapter.class.getDeclaredMethod(
                        "handleInbound", ChatbotMessage.class);
        handle.setAccessible(true);
        invoke(handle, adapter, dingtalkMessage("dingtalk-message-1"));
        invoke(handle, adapter, dingtalkMessage("dingtalk-message-2"));
        return messages;
    }

    /** 创建固定会话中的钉钉文本消息。 */
    private static ChatbotMessage dingtalkMessage(String messageId) {
        MessageContent text = new MessageContent();
        text.setContent("hello");
        ChatbotMessage message = new ChatbotMessage();
        message.setMsgId(messageId);
        message.setConversationId("dingtalk-chat");
        message.setConversationType("1");
        message.setSenderStaffId("dingtalk-user");
        message.setMsgtype("text");
        message.setText(text);
        return message;
    }

    /** 接收两条企微消息并返回统一消息模型。 */
    private static List<GatewayMessage> wecomReplyIdentity() throws Throwable {
        AppConfig config = new AppConfig();
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(), new AttachmentCacheService(config));
        setField(adapter, WeComChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        List<GatewayMessage> messages = captureMessages(adapter);
        Method handle = WeComChannelAdapter.class.getDeclaredMethod("handleInbound", ONode.class);
        handle.setAccessible(true);
        invoke(handle, adapter, wecomMessage("wecom-message-1"));
        invoke(handle, adapter, wecomMessage("wecom-message-2"));
        return messages;
    }

    /** 创建固定会话中的企微文本消息。 */
    private static ONode wecomMessage(String messageId) {
        return ONode.ofJson(
                "{\"body\":{\"msgid\":\""
                        + messageId
                        + "\",\"chatid\":\"wecom-chat\",\"chattype\":\"single\","
                        + "\"from\":{\"userid\":\"wecom-user\"},"
                        + "\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}}}");
    }

    /** 接收两条元宝消息并返回统一消息模型。 */
    private static List<GatewayMessage> yuanbaoReplyIdentity() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setAllowAllUsers(true);
        TestYuanbaoAdapter adapter = new TestYuanbaoAdapter(config);
        setField(adapter, YuanbaoChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        List<GatewayMessage> messages = captureMessages(adapter);
        adapter.dispatch(yuanbaoMessage("yuanbao-message-1"));
        adapter.dispatch(yuanbaoMessage("yuanbao-message-2"));
        return messages;
    }

    /** 创建固定会话中的元宝文本消息。 */
    private static String yuanbaoMessage(String messageId) {
        return "{\"body\":{\"message_id\":\""
                + messageId
                + "\",\"chat_id\":\"yuanbao-chat\","
                + "\"user_id\":\"yuanbao-user\",\"content\":\"hello\"}}";
    }

    /** 安装计数型统一入站处理器。 */
    private static AtomicInteger handlerCount(
            com.jimuqu.solon.claw.core.service.ChannelAdapter adapter) {
        AtomicInteger handled = new AtomicInteger();
        adapter.setInboundMessageHandler((GatewayMessage message) -> handled.incrementAndGet());
        return handled;
    }

    /** 安装收集型统一入站处理器。 */
    private static List<GatewayMessage> captureMessages(
            com.jimuqu.solon.claw.core.service.ChannelAdapter adapter) {
        List<GatewayMessage> messages = new ArrayList<GatewayMessage>();
        adapter.setInboundMessageHandler(messages::add);
        return messages;
    }

    /** 写入适配器私有执行器，使参数化测试同步完成。 */
    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 调用私有入站入口并还原真实异常。 */
    private static Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** 允许参数化场景抛出其渠道反射调用产生的异常。 */
    private interface DuplicateScenario {
        /** 连续投递同一消息两次并返回处理次数。 */
        int dispatchTwice() throws Throwable;
    }

    /** 允许回复标识场景返回两条统一消息。 */
    private interface ReplyIdentityScenario {
        /** 连续接收两条不同消息标识的同会话消息。 */
        List<GatewayMessage> receiveTwoMessages() throws Throwable;
    }

    /** 同步执行回调，避免测试等待后台线程。 */
    private static class DirectExecutor extends AbstractExecutorService {
        /** 标记执行器是否关闭。 */
        private boolean shutdown;

        /** 关闭同步执行器。 */
        @Override
        public void shutdown() {
            shutdown = true;
        }

        /** 关闭同步执行器并返回空任务列表。 */
        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        /** 返回同步执行器关闭状态。 */
        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        /** 返回同步执行器终止状态。 */
        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        /** 同步执行器没有后台任务，直接返回当前终止状态。 */
        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        /** 在当前线程立即执行回调。 */
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    /** 测试用空渠道状态仓储。 */
    private static class EmptyChannelStateRepository implements ChannelStateRepository {
        /** 始终返回空状态。 */
        @Override
        public String get(PlatformType platform, String scopeKey, String stateKey) {
            return null;
        }

        /** 忽略测试状态写入。 */
        @Override
        public void put(
                PlatformType platform, String scopeKey, String stateKey, String stateValue) {}

        /** 忽略测试状态删除。 */
        @Override
        public void delete(PlatformType platform, String scopeKey, String stateKey) {}

        /** 返回空状态列表。 */
        @Override
        public List<StateItem> list(PlatformType platform, String scopeKey) {
            return Collections.emptyList();
        }
    }

    /** 暴露 QQBot 原始入站入口。 */
    private static class TestQQBotAdapter extends QQBotChannelAdapter {
        /** 创建 QQBot 测试适配器。 */
        private TestQQBotAdapter(AppConfig config) {
            super(
                    config,
                    config.getChannels().getQqbot(),
                    new AttachmentCacheService(config),
                    null);
        }

        /** 投递原始 QQBot 事件。 */
        private void dispatch(String raw) {
            dispatchInbound(raw);
        }
    }

    /** 暴露元宝原始入站入口。 */
    private static class TestYuanbaoAdapter extends YuanbaoChannelAdapter {
        /** 创建元宝测试适配器。 */
        private TestYuanbaoAdapter(AppConfig config) {
            super(config.getChannels().getYuanbao());
        }

        /** 投递原始元宝事件。 */
        private void dispatch(String raw) {
            dispatchInbound(raw);
        }
    }
}
