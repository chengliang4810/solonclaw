package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
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
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.noear.snack4.ONode;

/** 验证五个国内渠道按平台原始消息标识抑制重复入站。 */
public class DomesticInboundMessageDedupTest {
    /** 企业微信附件必须在下载前转换成可持久化原始引用，供总账恢复后再水化。 */
    @Test
    void shouldExposeDurableWeComAttachmentReferenceBeforeHydration() throws Throwable {
        AppConfig config = new AppConfig();
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(), new AttachmentCacheService(config));
        setField(adapter, WeComChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        List<GatewayMessage> messages = captureMessages(adapter);
        ONode payload =
                ONode.ofJson(
                        "{\"body\":{\"msgid\":\"wecom-attachment-1\","
                                + "\"chatid\":\"wecom-chat\",\"chattype\":\"single\","
                                + "\"from\":{\"userid\":\"wecom-user\"},"
                                + "\"msgtype\":\"mixed\",\"mixed\":{\"msg_item\":["
                                + "{\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}},"
                                + "{\"msgtype\":\"file\",\"file\":{"
                                + "\"url\":\"https://example.test/report.pdf\","
                                + "\"filename\":\"report.pdf\","
                                + "\"content_type\":\"application/pdf\","
                                + "\"aeskey\":\"attachment-key\"}}]}}}");
        Method handle = WeComChannelAdapter.class.getDeclaredMethod("handleInbound", ONode.class);
        handle.setAccessible(true);

        invoke(handle, adapter, payload);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo("hello");
        assertThat(messages.get(0).getAttachments()).hasSize(1);
        MessageAttachment attachment = messages.get(0).getAttachments().get(0);
        assertThat(attachment.getKind()).isEqualTo("file");
        assertThat(attachment.getOriginalName()).isEqualTo("report.pdf");
        assertThat(attachment.getMimeType()).isEqualTo("application/pdf");
        assertThat(attachment.getSourceReference()).isEqualTo("https://example.test/report.pdf");
        assertThat(attachment.getSourceEncryptionKey()).isEqualTo("attachment-key");
        assertThat(attachment.getSourceResourceType()).isEqualTo("remote_url");
        assertThat(attachment.getLocalPath()).isNull();
    }

    /** QQ 交互确认必须晚于 durable admission；准入异常时平台应看不到 ACK 并重投。 */
    @Test
    void shouldAcknowledgeQqInteractionOnlyAfterAdmissionReturnsNormally() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            AppConfig config = new AppConfig();
            config.getChannels().getQqbot().setAllowAllUsers(true);
            config.getChannels()
                    .getQqbot()
                    .setApiDomain("http://127.0.0.1:" + server.getAddress().getPort());
            TestQQBotAdapter adapter = new TestQQBotAdapter(config);
            setField(adapter, QQBotChannelAdapter.class, "callbackExecutor", new DirectExecutor());
            AtomicInteger admissions = new AtomicInteger();
            adapter.setInboundMessageHandler(
                    new InboundMessageHandler() {
                        /** 首次模拟持久化失败，第二次按重复或拒绝正常返回。 */
                        @Override
                        public boolean admit(GatewayMessage message) throws Exception {
                            if (admissions.incrementAndGet() == 1) {
                                throw new Exception("simulated persistence failure");
                            }
                            return false;
                        }

                        /** 本用例的两次消息都不应进入业务处理。 */
                        @Override
                        public void handle(GatewayMessage message) {
                            throw new AssertionError("interaction must not reach legacy handler");
                        }
                    });
            String raw =
                    "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"qq-interaction-retry\","
                            + "\"chat_type\":2,\"user_openid\":\"qq-user\","
                            + "\"resolved\":{\"button_data\":\"approve:approval-1:deny\"}}}";

            assertThatThrownBy(() -> adapter.dispatch(raw))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inbound admission failed");
            assertThat(requests.get()).isZero();

            adapter.dispatch(raw);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

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

    /** 准入写库失败不能提前污染内存去重，同一平台消息重投后必须仍能进入已准入处理入口。 */
    @ParameterizedTest(name = "{0} admission retry")
    @MethodSource("admissionRetryScenarios")
    void shouldRetrySameMessageAfterAdmissionFailure(
            String platform, String messageId, AdmissionRetryScenario scenario) throws Throwable {
        AdmissionRetryResult result = scenario.dispatchTwice();

        assertThat(result.admitCalls).isEqualTo(2);
        assertThat(result.admittedHandleCalls).isEqualTo(1);
        assertThat(result.legacyHandleCalls).isZero();
        assertThat(result.platformMessageId).isEqualTo(messageId);
        assertThat(result.replyToMessageId).isEqualTo(messageId);
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

    /** 提供五个国内渠道的准入失败重投场景。 */
    private static Stream<Arguments> admissionRetryScenarios() {
        return Stream.of(
                Arguments.of(
                        "feishu",
                        "feishu-message-retry",
                        (AdmissionRetryScenario)
                                DomesticInboundMessageDedupTest::feishuAdmissionRetry),
                Arguments.of(
                        "dingtalk",
                        "dingtalk-message-retry",
                        (AdmissionRetryScenario)
                                DomesticInboundMessageDedupTest::dingtalkAdmissionRetry),
                Arguments.of(
                        "wecom",
                        "wecom-message-retry",
                        (AdmissionRetryScenario)
                                DomesticInboundMessageDedupTest::wecomAdmissionRetry),
                Arguments.of(
                        "qqbot",
                        "qq-message-retry",
                        (AdmissionRetryScenario)
                                DomesticInboundMessageDedupTest::qqbotAdmissionRetry),
                Arguments.of(
                        "yuanbao",
                        "yuanbao-message-retry",
                        (AdmissionRetryScenario)
                                DomesticInboundMessageDedupTest::yuanbaoAdmissionRetry));
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

    /** 执行飞书准入失败后的同消息重投场景。 */
    private static AdmissionRetryResult feishuAdmissionRetry() throws Throwable {
        AppConfig config = new AppConfig();
        FeishuChannelAdapter adapter =
                new FeishuChannelAdapter(
                        config.getChannels().getFeishu(), new AttachmentCacheService(config));
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        data.setMessage(
                EventMessage.newBuilder()
                        .messageId("feishu-message-retry")
                        .chatId("feishu-chat")
                        .chatType("p2p")
                        .messageType("text")
                        .content("{\"text\":\"hello\"}")
                        .build());
        data.setSender(
                EventSender.newBuilder()
                        .senderId(UserId.newBuilder().openId("feishu-user").build())
                        .build());
        return executeAdmissionRetry(adapter, () -> adapter.handleWebsocketEvent(data));
    }

    /** 执行钉钉准入失败后的同消息重投场景。 */
    private static AdmissionRetryResult dingtalkAdmissionRetry() throws Throwable {
        AppConfig config = new AppConfig();
        DingTalkChannelAdapter adapter =
                new DingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        new EmptyChannelStateRepository(),
                        new AttachmentCacheService(config));
        setField(adapter, DingTalkChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        Method handle =
                DingTalkChannelAdapter.class.getDeclaredMethod(
                        "handleInbound", ChatbotMessage.class);
        handle.setAccessible(true);
        ChatbotMessage message = dingtalkMessage("dingtalk-message-retry");
        return executeAdmissionRetry(adapter, () -> invoke(handle, adapter, message));
    }

    /** 执行企微准入失败后的同消息重投场景。 */
    private static AdmissionRetryResult wecomAdmissionRetry() throws Throwable {
        AppConfig config = new AppConfig();
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(), new AttachmentCacheService(config));
        setField(adapter, WeComChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        Method handle = WeComChannelAdapter.class.getDeclaredMethod("handleInbound", ONode.class);
        handle.setAccessible(true);
        ONode message = wecomMessage("wecom-message-retry");
        return executeAdmissionRetry(adapter, () -> invoke(handle, adapter, message));
    }

    /** 执行 QQBot 准入失败后的同消息重投场景。 */
    private static AdmissionRetryResult qqbotAdmissionRetry() throws Throwable {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);
        setField(adapter, QQBotChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        String raw =
                "{\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"qq-message-retry\","
                        + "\"openid\":\"qq-user\",\"content\":\"hello\"}}";
        return executeAdmissionRetry(adapter, () -> adapter.dispatch(raw));
    }

    /** 执行元宝准入失败后的同消息重投场景。 */
    private static AdmissionRetryResult yuanbaoAdmissionRetry() throws Throwable {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setAllowAllUsers(true);
        TestYuanbaoAdapter adapter = new TestYuanbaoAdapter(config);
        setField(adapter, YuanbaoChannelAdapter.class, "callbackExecutor", new DirectExecutor());
        String raw = yuanbaoMessage("yuanbao-message-retry");
        return executeAdmissionRetry(adapter, () -> adapter.dispatch(raw));
    }

    /** 安装首次失败的两阶段处理器，连续执行两次相同平台回调并返回观测结果。 */
    private static AdmissionRetryResult executeAdmissionRetry(
            ChannelAdapter adapter, ThrowingDispatch dispatch) throws Throwable {
        RetryingAdmissionHandler handler = new RetryingAdmissionHandler();
        adapter.setInboundMessageHandler(handler);
        boolean failed = false;
        try {
            dispatch.run();
        } catch (IllegalStateException expected) {
            failed = true;
            assertThat(expected).hasMessageContaining("inbound admission failed");
        }
        assertThat(failed).isTrue();
        dispatch.run();
        return handler.result();
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

    /** 允许准入重投场景执行渠道私有回调。 */
    private interface AdmissionRetryScenario {
        /** 连续投递同一消息并返回两阶段处理观测。 */
        AdmissionRetryResult dispatchTwice() throws Throwable;
    }

    /** 允许测试场景包装会抛出渠道异常的单次分发。 */
    private interface ThrowingDispatch {
        /** 执行一次真实渠道入站入口。 */
        void run() throws Throwable;
    }

    /** 首次准入失败、第二次成功的测试处理器。 */
    private static class RetryingAdmissionHandler implements InboundMessageHandler {
        /** 准入调用次数。 */
        private int admitCalls;

        /** 旧入口调用次数。 */
        private int legacyHandleCalls;

        /** 已准入入口调用次数。 */
        private int admittedHandleCalls;

        /** 最近一次平台消息标识。 */
        private String platformMessageId;

        /** 最近一次原消息回复标识。 */
        private String replyToMessageId;

        /** 首次抛出模拟持久化异常，第二次接受相同消息。 */
        @Override
        public boolean admit(GatewayMessage message) throws Exception {
            admitCalls++;
            platformMessageId = message.getPlatformMessageId();
            replyToMessageId = message.getReplyToMessageId();
            if (admitCalls == 1) {
                throw new Exception("simulated persistence failure");
            }
            return true;
        }

        /** 记录错误使用旧入口的次数。 */
        @Override
        public void handle(GatewayMessage message) {
            legacyHandleCalls++;
        }

        /** 记录正确使用已准入入口的次数。 */
        @Override
        public void handleAdmitted(GatewayMessage message) {
            admittedHandleCalls++;
        }

        /** 返回当前处理器的不可变数值快照。 */
        private AdmissionRetryResult result() {
            return new AdmissionRetryResult(
                    admitCalls,
                    legacyHandleCalls,
                    admittedHandleCalls,
                    platformMessageId,
                    replyToMessageId);
        }
    }

    /** 保存单个渠道两阶段准入重投的观测结果。 */
    private static class AdmissionRetryResult {
        /** 准入调用次数。 */
        private final int admitCalls;

        /** 旧入口调用次数。 */
        private final int legacyHandleCalls;

        /** 已准入入口调用次数。 */
        private final int admittedHandleCalls;

        /** 平台稳定消息标识。 */
        private final String platformMessageId;

        /** 原消息回复锚点。 */
        private final String replyToMessageId;

        /** 创建两阶段准入观测结果。 */
        private AdmissionRetryResult(
                int admitCalls,
                int legacyHandleCalls,
                int admittedHandleCalls,
                String platformMessageId,
                String replyToMessageId) {
            this.admitCalls = admitCalls;
            this.legacyHandleCalls = legacyHandleCalls;
            this.admittedHandleCalls = admittedHandleCalls;
            this.platformMessageId = platformMessageId;
            this.replyToMessageId = replyToMessageId;
        }
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
