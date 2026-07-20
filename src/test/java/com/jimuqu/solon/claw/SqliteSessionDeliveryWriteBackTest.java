package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.PlatformAdminRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.delivery.AdapterBackedDeliveryService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.MemoryChannelAdapter;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 后台投递消息回写普通会话时的角色与多用户边界测试。 */
class SqliteSessionDeliveryWriteBackTest {
    /** 隔离测试数据库。 */
    private SqliteDatabase database;

    /** 被测会话仓储。 */
    private SqliteSessionRepository repository;

    /** 为每个测试创建独立数据库。 */
    @BeforeEach
    void setUp() throws Exception {
        Path home = Files.createTempDirectory("session-delivery-write-back-test");
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.toString());
        config.getRuntime().setStateDb(home.resolve("data/state.db").toString());
        database = new SqliteDatabase(config);
        repository = new SqliteSessionRepository(database);
    }

    /** 关闭测试数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 唯一且用户一致的来源会话应收到 assistant 角色的投递正文。 */
    @Test
    void shouldAppendDeliveredTextAsAssistant() throws Exception {
        SessionRecord session = repository.bindNewSession("FEISHU:room:user-a");

        boolean appended =
                repository.appendBoundOriginAssistantMessage(
                        PlatformType.FEISHU, "room", null, "user-a", "sub2api 已更新");

        assertThat(appended).isTrue();
        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(session.getSessionId()).getNdjson()))
                .singleElement()
                .satisfies(
                        message -> {
                            assertThat(message.getRole()).isEqualTo(ChatRole.ASSISTANT);
                            assertThat(message.getContent()).isEqualTo("sub2api 已更新");
                        });
    }

    /** 首次绑定欢迎语尚无会话时，应按明确用户和命名 Profile 创建可继续对话的会话。 */
    @Test
    void shouldCreateProfileSessionForFirstOutboundMessage() throws Exception {
        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                "worker", PlatformType.WEIXIN, "room", null, "user-a", "欢迎回来"))
                .isTrue();

        SessionRecord session = repository.getBoundSession("profile:worker:WEIXIN:room:user-a");
        assertThat(session).isNotNull();
        assertThat(MessageSupport.loadMessages(session.getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("欢迎回来");
    }

    /** 默认与命名 Profile 来源相同时，投递只能进入请求指定的 Profile 会话。 */
    @Test
    void shouldIsolateWriteBackByProfile() throws Exception {
        SessionRecord defaultSession = repository.bindNewSession("WEIXIN:room:user-a");
        SessionRecord workerSession =
                repository.bindNewSession("profile:worker:WEIXIN:room:user-a");

        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                "worker", PlatformType.WEIXIN, "room", null, "user-a", "worker 回复"))
                .isTrue();

        assertThat(repository.findById(defaultSession.getSessionId()).getNdjson()).isEmpty();
        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(workerSession.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("worker 回复");
    }

    /** 群聊实际投递目标与主人上下文不同时，必须按精确来源键回写主人私聊会话。 */
    @Test
    void shouldWriteBackToExactConversationSource() throws Exception {
        SessionRecord ownerSession = repository.bindNewSession("DINGTALK:owner-dm:owner-a");

        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                "DINGTALK:owner-dm:owner-a",
                                null,
                                PlatformType.DINGTALK,
                                "group-a",
                                null,
                                "owner-a",
                                "群聊阶段进展"))
                .isTrue();

        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(ownerSession.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("群聊阶段进展");
        assertThat(repository.getBoundSession("DINGTALK:group-a:owner-a")).isNull();
    }

    /** 任意跨会话目标不得因为存在平台管理员而误写到管理员私聊。 */
    @Test
    void shouldNotUseAdminConversationForUnrelatedTarget() throws Exception {
        SessionRecord adminSession = repository.bindNewSession("WEIXIN:admin-dm:admin-a");
        SessionRecord targetSession = repository.bindNewSession("WEIXIN:target-dm:target-a");
        SqliteGatewayPolicyRepository policyRepository =
                new SqliteGatewayPolicyRepository(database);
        PlatformAdminRecord admin = new PlatformAdminRecord();
        admin.setPlatform(PlatformType.WEIXIN);
        admin.setChatId("admin-dm");
        admin.setUserId("admin-a");
        policyRepository.savePlatformAdmin(admin);
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.WEIXIN, new MemoryChannelAdapter());
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, policyRepository, repository);
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.WEIXIN);
        request.setChatId("target-dm");
        request.setUserId("target-a");
        request.setText("目标消息");
        request.setRecordInConversation(true);

        deliveryService.deliver(request);

        assertThat(repository.findById(adminSession.getSessionId()).getNdjson()).isEmpty();
        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(targetSession.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("目标消息");
    }

    /** 心跳派生会话更新时间更近时，后台消息仍应回写真实用户会话。 */
    @Test
    void shouldIgnoreHeartbeatSessionWhenWritingBack() throws Exception {
        SessionRecord userSession = repository.bindNewSession("WEIXIN:room:user-a");
        SessionRecord heartbeatSession = repository.bindNewSession("WEIXIN:room:__heartbeat__");

        boolean appended =
                repository.appendBoundOriginAssistantMessage(
                        PlatformType.WEIXIN, "room", null, "user-a", "主动问候");

        assertThat(appended).isTrue();
        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(userSession.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("主动问候");
        assertThat(repository.findById(heartbeatSession.getSessionId()).getNdjson()).isEmpty();
    }

    /** 心跳投递未携带用户标识时，也应在排除派生会话后写入唯一真实会话。 */
    @Test
    void shouldResolveUniqueUserSessionForHeartbeatDelivery() throws Exception {
        SessionRecord userSession = repository.bindNewSession("WEIXIN:room:user-a");
        repository.bindNewSession("WEIXIN:room:__heartbeat__");

        boolean appended =
                repository.appendBoundOriginAssistantMessage(
                        PlatformType.WEIXIN, "room", null, null, "心跳提醒");

        assertThat(appended).isTrue();
        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(userSession.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("心跳提醒");
    }

    /** 指定用户不匹配时，即使聊天室只有一个会话也不得猜测回写。 */
    @Test
    void shouldRejectMismatchedUser() throws Exception {
        repository.bindNewSession("FEISHU:room:user-a");

        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                PlatformType.FEISHU, "room", null, "user-b", "不应写入"))
                .isFalse();
    }

    /** 未提供用户且聊天室存在多个绑定会话时不得选择最近会话。 */
    @Test
    void shouldRejectAmbiguousConversationWithoutUser() throws Exception {
        repository.bindNewSession("FEISHU:room:user-a");
        repository.bindNewSession("FEISHU:room:user-b");

        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                PlatformType.FEISHU, "room", null, null, "不应写入"))
                .isFalse();
    }

    /** 同一用户存在多个线程且请求未指定线程时不得猜测最近会话。 */
    @Test
    void shouldRejectAmbiguousThreadsForSameUser() throws Exception {
        repository.bindNewSession("FEISHU:room:thread-a:user-a");
        repository.bindNewSession("FEISHU:room:thread-b:user-a");

        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                PlatformType.FEISHU, "room", null, "user-a", "不应写入"))
                .isFalse();
        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                PlatformType.FEISHU, "room", "thread-a", "user-a", "精确线程"))
                .isTrue();
    }

    /** 普通会话旧快照保存时必须保留期间并发追加的后台消息。 */
    @Test
    void shouldPreserveConcurrentDeliveredMessageOnSave() throws Exception {
        SessionRecord created = repository.bindNewSession("FEISHU:room:user-a");
        SessionRecord running = repository.findById(created.getSessionId());
        running.setNdjson(
                MessageSupport.toNdjson(Collections.singletonList(ChatMessage.ofUser("当前用户问题"))));

        assertThat(
                        repository.appendBoundOriginAssistantMessage(
                                PlatformType.FEISHU, "room", null, "user-a", "后台通知"))
                .isTrue();
        repository.save(running);

        List<ChatMessage> messages =
                MessageSupport.loadMessages(
                        repository.findById(created.getSessionId()).getNdjson());
        assertThat(messages).extracting(ChatMessage::getContent).containsExactly("当前用户问题", "后台通知");
    }

    /** 统一投递服务仅在显式开启时于发送成功后回写会话。 */
    @Test
    void shouldWriteBackOnlyExplicitBackgroundDelivery() throws Exception {
        SessionRecord session = repository.bindNewSession("MEMORY:room:user-a");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, new MemoryChannelAdapter());
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);

        DeliveryRequest normal = request("普通回复", false);
        deliveryService.deliver(normal);
        assertThat(repository.findById(session.getSessionId()).getNdjson()).isEmpty();

        DeliveryRequest background = request("后台通知", true);
        deliveryService.deliver(background);
        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(session.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("后台通知");
    }

    /** 显式开启回写的文本和纯附件投递都必须留下 Agent 可读取的接收侧记录。 */
    @Test
    void shouldRecordTextAndAttachmentDeliveries() throws Exception {
        SessionRecord session = repository.bindNewSession("MEMORY:room:user-a");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, new MemoryChannelAdapter());
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);

        DeliveryRequest text = new DeliveryRequest();
        text.setPlatform(PlatformType.MEMORY);
        text.setChatId("room");
        text.setUserId("user-a");
        text.setText("阶段进展");
        text.setRecordInConversation(true);
        deliveryService.deliver(text);

        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("image");
        attachment.setOriginalName("result.png");
        DeliveryRequest media = new DeliveryRequest();
        media.setPlatform(PlatformType.MEMORY);
        media.setChatId("room");
        media.setUserId("user-a");
        media.setAttachments(Collections.singletonList(attachment));
        media.setRecordInConversation(true);
        deliveryService.deliver(media);

        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(session.getSessionId()).getNdjson()))
                .extracting(ChatMessage::getContent)
                .containsExactly("阶段进展", "[已发送附件] result.png");
    }

    /** 内容相同的两次真实投递都是独立事件，不得因为文本相同而丢失其中一次。 */
    @Test
    void shouldKeepRepeatedDeliveredMessages() throws Exception {
        SessionRecord session = repository.bindNewSession("MEMORY:room:user-a");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, new MemoryChannelAdapter());
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);

        deliveryService.deliver(request("仍在处理中", true));
        deliveryService.deliver(request("仍在处理中", true));

        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(session.getSessionId()).getNdjson()))
                .extracting(ChatMessage::getContent)
                .containsExactly("仍在处理中", "仍在处理中");
    }

    /** 缺少用户身份且没有既有会话的新目标，必须在调用渠道前拒绝投递。 */
    @Test
    void shouldRejectUntraceableTargetBeforeChannelSend() throws Exception {
        MemoryChannelAdapter adapter = new MemoryChannelAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);
        DeliveryRequest request = request("跨会话通知", true);
        request.setChatId("unknown-room");
        request.setUserId(null);

        assertThatThrownBy(() -> deliveryService.deliver(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已取消发送以避免上下文失联");
        assertThat(adapter.getRequests()).isEmpty();
    }

    /** 精确来源键含用户身份时，首次主动外发应创建会话、发送并回写。 */
    @Test
    void shouldCreateConversationFromExactSourceBeforeFirstDelivery() throws Exception {
        MemoryChannelAdapter adapter = new MemoryChannelAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);
        DeliveryRequest request = request("首次主动问候", true);
        request.setChatId("new-room");
        request.setUserId(null);
        request.setConversationSourceKey("MEMORY:new-room:user-new");

        deliveryService.deliver(request);

        SessionRecord session = repository.getBoundSession("MEMORY:new-room:user-new");
        assertThat(adapter.getRequests()).hasSize(1);
        assertThat(session).isNotNull();
        assertThat(MessageSupport.loadMessages(session.getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .isEqualTo("首次主动问候");
    }

    /** 渠道异常可能发生在部分发送之后，必须以不确定状态保留本次外发内容。 */
    @Test
    void shouldRecordUncertainDeliveryWhenChannelFails() throws Exception {
        SessionRecord session = repository.bindNewSession("MEMORY:room:user-a");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, new FailingMemoryChannelAdapter());
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);

        assertThatThrownBy(() -> deliveryService.deliver(request("发送失败", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("channel offline");

        assertThat(
                        MessageSupport.loadMessages(
                                repository.findById(session.getSessionId()).getNdjson()))
                .singleElement()
                .extracting(ChatMessage::getContent)
                .asString()
                .contains("投递失败", "可能未收到", "发送失败");
    }

    /** 回写失败属于发送后的旁路故障，不得让调用方重试已经成功的渠道投递。 */
    @Test
    void shouldKeepSuccessfulDeliveryWhenWriteBackFails() throws Exception {
        MemoryChannelAdapter adapter = new MemoryChannelAdapter();
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, adapter);
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(
                        null, adapters, null, new FailingWriteBackRepository(database));

        deliveryService.deliver(request("只发送一次", true));

        assertThat(adapter.getRequests()).hasSize(1);
        assertThat(adapter.getLastRequest().getText()).isEqualTo("只发送一次");
    }

    /** 创建指定回写行为的内存渠道投递请求。 */
    private DeliveryRequest request(String text, boolean recordInConversation) {
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.MEMORY);
        request.setChatId("room");
        request.setUserId("user-a");
        request.setText(text);
        request.setRecordInConversation(recordInConversation);
        return request;
    }

    /** 固定模拟渠道发送失败。 */
    private static class FailingMemoryChannelAdapter extends MemoryChannelAdapter {
        /** 抛出渠道离线异常，不记录请求。 */
        @Override
        public void send(DeliveryRequest request) {
            throw new IllegalStateException("channel offline");
        }
    }

    /** 固定模拟发送成功后的会话回写失败。 */
    private static class FailingWriteBackRepository extends SqliteSessionRepository {
        /** 创建使用现有测试数据库的失败仓储。 */
        private FailingWriteBackRepository(SqliteDatabase database) {
            super(database);
        }

        /** 模拟持久化层故障。 */
        @Override
        public boolean appendBoundOriginAssistantMessage(
                String conversationSourceKey,
                String profile,
                PlatformType platform,
                String chatId,
                String threadId,
                String userId,
                String content) {
            throw new IllegalStateException("database unavailable");
        }
    }
}
