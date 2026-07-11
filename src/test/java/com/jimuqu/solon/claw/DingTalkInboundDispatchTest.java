package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.storage.repository.SqliteChannelStateRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 验证钉钉渠道入站消息与卡片回调解析为统一网关消息的关键路径。 */
public class DingTalkInboundDispatchTest {
    /** 验证被 @ 的钉钉群消息会转换为统一网关消息，并写入后续回复所需的会话状态。 */
    @Test
    void shouldDispatchMentionedGroupTextAndPersistConversationContext() throws Throwable {
        TestDingTalkFixture fixture = TestDingTalkFixture.create();
        fixture.adapter.setInboundMessageHandler(fixture::record);

        ChatbotMessage message = groupTextMessage();

        fixture.invokeHandleInbound(message);

        assertThat(fixture.messages).hasSize(1);
        GatewayMessage dispatched = fixture.messages.get(0);
        assertThat(dispatched.getPlatform()).isEqualTo(PlatformType.DINGTALK);
        assertThat(dispatched.getChatId()).isEqualTo("open-cid-1");
        assertThat(dispatched.getChatType()).isEqualTo(GatewayBehaviorConstants.CHAT_TYPE_GROUP);
        assertThat(dispatched.getChatName()).isEqualTo("研发值班群");
        assertThat(dispatched.getUserId()).isEqualTo("staff-001");
        assertThat(dispatched.getUserName()).isEqualTo("值班同事");
        assertThat(dispatched.getText()).isEqualTo("请检查 solonclaw 状态");
        assertThat(dispatched.getThreadId()).isEqualTo("msg-001");
        assertThat(dispatched.getAttachments()).isEmpty();
        assertThat(fixture.state.get(PlatformType.DINGTALK, "open-cid-1", "last_user_id"))
                .isEqualTo("staff-001");
        assertThat(fixture.state.get(PlatformType.DINGTALK, "open-cid-1", "last_union_id"))
                .isEqualTo("union-001");
        assertThat(fixture.state.get(PlatformType.DINGTALK, "open-cid-1", "session_webhook"))
                .isEqualTo("https://oapi.dingtalk.com/robot/send?access_token=unit-test");
        assertThat(
                        fixture.state.get(
                                PlatformType.DINGTALK, "open-cid-1", "session_webhook_expires_at"))
                .isEqualTo("1893456000000");
    }

    /** 验证钉钉 AI Card 回调会恢复群聊上下文并交给统一入站处理链。 */
    @Test
    void shouldConvertCardCallbackToGatewayMessageWithGroupContext() throws Throwable {
        TestDingTalkFixture fixture = TestDingTalkFixture.create();
        fixture.adapter.setInboundMessageHandler(fixture::record);
        fixture.markGroupConversation("card-cid-1");
        Map<String, Object> operator = new LinkedHashMap<String, Object>();
        operator.put("staffId", "staff-card-1");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("processQueryKey", "process-001");
        payload.put("openConversationId", "card-cid-1");
        payload.put("operator", operator);
        payload.put("actionValue", "approve");

        fixture.invokeHandleCardCallback(payload);

        assertThat(fixture.messages).hasSize(1);
        GatewayMessage dispatched = fixture.messages.get(0);
        assertThat(dispatched.getPlatform()).isEqualTo(PlatformType.DINGTALK);
        assertThat(dispatched.getChatId()).isEqualTo("card-cid-1");
        assertThat(dispatched.getChatType()).isEqualTo(GatewayBehaviorConstants.CHAT_TYPE_GROUP);
        assertThat(dispatched.getUserId()).isEqualTo("staff-card-1");
        assertThat(dispatched.getUserName()).isEqualTo("staff-card-1");
        assertThat(dispatched.getThreadId()).isEqualTo("process-001");
        assertThat(dispatched.getText()).contains("Card action:").contains("approve");
    }

    /** 验证默认配置下未 @ 的钉钉群消息不会进入统一入站处理链。 */
    @Test
    void shouldDropGroupMessageWithoutMentionByDefault() throws Throwable {
        TestDingTalkFixture fixture = TestDingTalkFixture.create();
        fixture.adapter.setInboundMessageHandler(fixture::record);
        ChatbotMessage message = groupTextMessage();
        message.setInAtList(Boolean.FALSE);

        fixture.invokeHandleInbound(message);

        assertThat(fixture.messages).isEmpty();
    }

    /** 验证配置免提及会话后，钉钉群消息即使未 @ 也会进入统一入站处理链。 */
    @Test
    void shouldDispatchFreeResponseGroupMessageWithoutMention() throws Throwable {
        TestDingTalkFixture fixture = TestDingTalkFixture.create();
        fixture.config.setFreeResponseChats(List.of("open-cid-1"));
        fixture.adapter.setInboundMessageHandler(fixture::record);
        ChatbotMessage message = groupTextMessage();
        message.setInAtList(Boolean.FALSE);

        fixture.invokeHandleInbound(message);

        assertThat(fixture.messages).hasSize(1);
        assertThat(fixture.messages.get(0).getChatId()).isEqualTo("open-cid-1");
        assertThat(fixture.messages.get(0).getText()).isEqualTo("请检查 solonclaw 状态");
    }

    /** 验证关闭强制提及后，钉钉群消息不需要 @ 也能进入统一入站处理链。 */
    @Test
    void shouldDispatchGroupMessageWhenMentionRequirementDisabled() throws Throwable {
        TestDingTalkFixture fixture = TestDingTalkFixture.create();
        fixture.config.setRequireMention(false);
        fixture.adapter.setInboundMessageHandler(fixture::record);
        ChatbotMessage message = groupTextMessage();
        message.setInAtList(Boolean.FALSE);

        fixture.invokeHandleInbound(message);

        assertThat(fixture.messages).hasSize(1);
        assertThat(fixture.messages.get(0).getChatId()).isEqualTo("open-cid-1");
        assertThat(fixture.messages.get(0).getText()).isEqualTo("请检查 solonclaw 状态");
    }

    /** 构造钉钉群聊文本消息，覆盖入站分发需要读取的平台字段。 */
    private ChatbotMessage groupTextMessage() {
        MessageContent text = new MessageContent();
        text.setContent("  请检查 solonclaw 状态  ");
        ChatbotMessage message = new ChatbotMessage();
        message.setConversationId("open-cid-1");
        message.setConversationType("2");
        message.setConversationTitle("研发值班群");
        message.setSenderStaffId("staff-001");
        message.setSenderId("union-001");
        message.setSenderNick("值班同事");
        message.setMsgId("msg-001");
        message.setMsgtype("text");
        message.setInAtList(Boolean.TRUE);
        message.setText(text);
        message.setSessionWebhook("https://oapi.dingtalk.com/robot/send?access_token=unit-test");
        message.setSessionWebhookExpiredTime(Long.valueOf(1893456000000L));
        return message;
    }

    /** 通过反射调用私有方法，并把被测异常还原成真实 cause。 */
    private static Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** 保存单个测试场景中的钉钉适配器、状态仓储和已分发消息。 */
    private static class TestDingTalkFixture {
        /** 被测钉钉渠道适配器。 */
        private final DingTalkChannelAdapter adapter;

        /** 当前测试夹具中的钉钉渠道配置，便于按用例调整策略开关。 */
        private final AppConfig.ChannelConfig config;

        /** 状态仓储用于断言入站上下文是否被写入。 */
        private final ChannelStateRepository state;

        /** 记录入站处理器收到的网关消息。 */
        private final List<GatewayMessage> messages = new ArrayList<GatewayMessage>();

        /** 创建测试夹具并安装同步执行器，避免单测等待异步线程。 */
        private TestDingTalkFixture(
                DingTalkChannelAdapter adapter,
                AppConfig.ChannelConfig config,
                ChannelStateRepository state)
                throws Exception {
            this.adapter = adapter;
            this.config = config;
            this.state = state;
            setField(adapter, "callbackExecutor", new DirectExecutorService());
        }

        /** 创建带临时工作区目录的钉钉测试夹具。 */
        private static TestDingTalkFixture create() throws Exception {
            AppConfig config = new AppConfig();
            File workspaceHome = Files.createTempDirectory("solonclaw-dingtalk-inbound").toFile();
            config.getRuntime().setHome(workspaceHome.getAbsolutePath());
            config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
            config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
            config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
            config.getRuntime()
                    .setStateDb(
                            new File(new File(workspaceHome, "data"), "state.db")
                                    .getAbsolutePath());
            config.getChannels().getDingtalk().setEnabled(true);
            config.getChannels().getDingtalk().setClientId("app-key");
            config.getChannels().getDingtalk().setClientSecret("app-secret");
            config.getChannels().getDingtalk().setRobotCode("robot-code");
            ChannelStateRepository stateRepository =
                    new SqliteChannelStateRepository(new SqliteDatabase(config));
            DingTalkChannelAdapter adapter =
                    new DingTalkChannelAdapter(
                            config.getChannels().getDingtalk(),
                            stateRepository,
                            new AttachmentCacheService(config));
            return new TestDingTalkFixture(
                    adapter, config.getChannels().getDingtalk(), stateRepository);
        }

        /** 记录入站处理器分发出来的消息。 */
        private void record(GatewayMessage message) {
            messages.add(message);
        }

        /** 标记某个钉钉会话为群聊，供卡片回调恢复统一消息类型。 */
        private void markGroupConversation(String conversationId) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> flags =
                    (Map<String, Boolean>) getField(adapter, "conversationGroupFlags");
            flags.put(conversationId, Boolean.TRUE);
        }

        /** 调用钉钉入站消息私有入口。 */
        private void invokeHandleInbound(ChatbotMessage message) throws Throwable {
            Method method =
                    DingTalkChannelAdapter.class.getDeclaredMethod(
                            "handleInbound", ChatbotMessage.class);
            method.setAccessible(true);
            invoke(method, adapter, message);
        }

        /** 调用钉钉卡片回调私有入口。 */
        private void invokeHandleCardCallback(Map<String, Object> payload) throws Throwable {
            Method method =
                    DingTalkChannelAdapter.class.getDeclaredMethod("handleCardCallback", Map.class);
            method.setAccessible(true);
            invoke(method, adapter, payload);
        }

        /** 写入被测对象私有字段，限定用于安装测试执行器。 */
        private static void setField(Object target, String name, Object value) throws Exception {
            Field field = DingTalkChannelAdapter.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        /** 读取被测对象私有字段，限定用于断言卡片回调上下文。 */
        private static Object getField(Object target, String name) throws Exception {
            Field field = DingTalkChannelAdapter.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }
    }

    /** 同步执行测试任务的最小 ExecutorService，保证私有回调入口立即完成。 */
    private static class DirectExecutorService extends AbstractExecutorService {
        /** 记录执行器是否已被关闭。 */
        private boolean shutdown;

        /** 标记测试执行器关闭。 */
        @Override
        public void shutdown() {
            shutdown = true;
        }

        /** 标记测试执行器关闭并返回空任务列表。 */
        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.Collections.emptyList();
        }

        /** 返回测试执行器是否已关闭。 */
        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        /** 返回测试执行器是否已完成终止。 */
        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        /** 同步执行器没有后台线程，等待终止直接返回当前状态。 */
        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        /** 立即执行提交的回调任务。 */
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
