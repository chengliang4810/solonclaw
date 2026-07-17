package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.gateway.delivery.AdapterBackedDeliveryService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
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

    /** 渠道投递失败时不得提前写回会话。 */
    @Test
    void shouldNotWriteBackWhenChannelDeliveryFails() throws Exception {
        SessionRecord session = repository.bindNewSession("MEMORY:room:user-a");
        Map<PlatformType, ChannelAdapter> adapters =
                new LinkedHashMap<PlatformType, ChannelAdapter>();
        adapters.put(PlatformType.MEMORY, new FailingMemoryChannelAdapter());
        AdapterBackedDeliveryService deliveryService =
                new AdapterBackedDeliveryService(null, adapters, null, repository);

        assertThatThrownBy(() -> deliveryService.deliver(request("发送失败", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("channel offline");

        assertThat(repository.findById(session.getSessionId()).getNdjson()).isEmpty();
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
                PlatformType platform,
                String chatId,
                String threadId,
                String userId,
                String content) {
            throw new IllegalStateException("database unavailable");
        }
    }
}
