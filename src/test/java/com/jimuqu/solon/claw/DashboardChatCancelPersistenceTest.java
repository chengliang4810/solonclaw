package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.MessageSupport;
import com.jimuqu.solon.claw.web.DashboardChatService;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;

/** 验证 Web 端运行取消后，会话历史仍能恢复用户输入和取消结果。 */
public class DashboardChatCancelPersistenceTest {
    /** 覆盖运行中取消的主路径，确保刷新页面后不会丢失取消轮次。 */
    @Test
    void shouldPersistCanceledWebRunForSessionRecovery() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-chat-cancel").toFile();
        SqliteDatabase database = null;
        DashboardChatService service = null;
        try {
            AppConfig config = testConfig(workspaceHome);
            database = new SqliteDatabase(config);
            SqliteSessionRepository sessionRepository = new SqliteSessionRepository(database);
            BlockingConversationOrchestrator orchestrator = new BlockingConversationOrchestrator();
            service = new DashboardChatService(sessionRepository, orchestrator, null, null, null);
            String sessionId = "dashboard-cancel-session";
            String input = "取消持久化验证 marker=web-loop-ui-cancel-persistence-test";

            Map<String, Object> start =
                    service.startRun(
                            ONode.ofJson(
                                    "{\"input\":\""
                                            + input
                                            + "\",\"session_id\":\""
                                            + sessionId
                                            + "\"}"));
            String runId = String.valueOf(start.get("run_id"));
            orchestrator.awaitStarted();

            Map<String, Object> canceled = service.cancelRun(runId);

            assertThat(canceled.get("status")).isEqualTo("canceled");
            assertThat(cancelError(service, runId)).isEqualTo("Run canceled");
            SessionRecord stored = sessionRepository.findById(sessionId);
            assertThat(stored).isNotNull();
            java.util.List<ChatMessage> messages = MessageSupport.loadMessages(stored.getNdjson());
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getRole().name()).isEqualTo("USER");
            assertThat(messages.get(0).getContent()).isEqualTo(input);
            assertThat(messages.get(1).getRole().name()).isEqualTo("ASSISTANT");
            assertThat(messages.get(1).getContent()).isEqualTo("当前 Web 运行已取消。");
        } finally {
            if (service != null) {
                service.shutdown();
            }
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(workspaceHome);
        }
    }

    /** 取消终态事件必须先于可能阻塞的会话持久化可见，避免 SSE 提前结束并丢失终态。 */
    @Test
    void shouldPublishCanceledEventBeforePersistingCanceledTurn() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-chat-cancel-event").toFile();
        SqliteDatabase database = null;
        DashboardChatService service = null;
        BlockingSaveSessionRepository sessionRepository = null;
        try {
            database = new SqliteDatabase(testConfig(workspaceHome));
            sessionRepository = new BlockingSaveSessionRepository(database);
            BlockingConversationOrchestrator orchestrator = new BlockingConversationOrchestrator();
            service = new DashboardChatService(sessionRepository, orchestrator, null, null, null);
            Map<String, Object> start =
                    service.startRun(
                            ONode.ofJson(
                                    "{\"input\":\"取消终态顺序验证\",\"session_id\":\"cancel-event-session\"}"));
            String runId = String.valueOf(start.get("run_id"));
            orchestrator.awaitStarted();
            sessionRepository.blockNextSave();
            DashboardChatService testedService = service;
            FutureTask<Map<String, Object>> cancel =
                    new FutureTask<Map<String, Object>>(() -> testedService.cancelRun(runId));
            new Thread(cancel, "dashboard-cancel-test").start();

            sessionRepository.awaitSaveBlocked();
            assertThat(eventNames(service, runId)).contains("run.failed");
            sessionRepository.releaseSave();
            assertThat(cancel.get(3, TimeUnit.SECONDS).get("status")).isEqualTo("canceled");
        } finally {
            if (sessionRepository != null) {
                sessionRepository.releaseSave();
            }
            if (service != null) {
                service.shutdown();
            }
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(workspaceHome);
        }
    }

    /** 覆盖 Web 端直连 slash 命令，确保刷新页面后能恢复命令输入和可见结果。 */
    @Test
    void shouldPersistDirectSlashCommandForSessionRecovery() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-chat-command").toFile();
        SqliteDatabase database = null;
        DashboardChatService service = null;
        try {
            AppConfig config = testConfig(workspaceHome);
            database = new SqliteDatabase(config);
            SqliteSessionRepository sessionRepository = new SqliteSessionRepository(database);
            service =
                    new DashboardChatService(
                            sessionRepository,
                            new BlockingConversationOrchestrator(),
                            new StubCommandService(),
                            null,
                            null);
            String sessionId = "dashboard-command-session";

            Map<String, Object> start =
                    service.startRun(
                            ONode.ofJson(
                                    "{\"input\":\"/status\",\"session_id\":\""
                                            + sessionId
                                            + "\"}"));
            String runId = String.valueOf(start.get("run_id"));

            assertThat(completedEvent(service, runId)).isEqualTo("run.completed");
            SessionRecord stored = sessionRepository.findById(sessionId);
            assertThat(stored).isNotNull();
            java.util.List<ChatMessage> messages = MessageSupport.loadMessages(stored.getNdjson());
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getRole().name()).isEqualTo("USER");
            assertThat(messages.get(0).getContent()).isEqualTo("/status");
            assertThat(messages.get(1).getRole().name()).isEqualTo("ASSISTANT");
            assertThat(messages.get(1).getContent()).contains("session=dashboard-command-session");
        } finally {
            if (service != null) {
                service.shutdown();
            }
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(workspaceHome);
        }
    }

    /** 已完成运行重复取消必须保持完成态，不能追加取消事件或覆盖已写入的会话轮次。 */
    @Test
    void shouldKeepCompletedRunUnchangedWhenCanceledAgain() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-chat-completed-cancel").toFile();
        SqliteDatabase database = null;
        DashboardChatService service = null;
        try {
            database = new SqliteDatabase(testConfig(workspaceHome));
            SqliteSessionRepository sessionRepository = new SqliteSessionRepository(database);
            service =
                    new DashboardChatService(
                            sessionRepository,
                            new BlockingConversationOrchestrator(),
                            new StubCommandService(),
                            null,
                            null);
            String sessionId = "dashboard-completed-session";
            Map<String, Object> start =
                    service.startRun(
                            ONode.ofJson(
                                    "{\"input\":\"/status\",\"session_id\":\""
                                            + sessionId
                                            + "\"}"));
            String runId = String.valueOf(start.get("run_id"));
            assertThat(completedEvent(service, runId)).isEqualTo("run.completed");

            Map<String, Object> canceled = service.cancelRun(runId);

            assertThat(canceled.get("status")).isEqualTo("completed");
            assertThat(eventQueue(service, runId)).isEmpty();
            assertThat(
                            MessageSupport.loadMessages(
                                    sessionRepository.findById(sessionId).getNdjson()))
                    .extracting(ChatMessage::getContent)
                    .containsExactly("/status", "status session=" + sessionId);
        } finally {
            if (service != null) {
                service.shutdown();
            }
            if (database != null) {
                database.shutdown();
            }
            FileUtil.del(workspaceHome);
        }
    }

    /** 同一 Dashboard 平台、chatId 和 userId 在不同 Profile 中必须写入各自 state.db，不能串用会话正文。 */
    @Test
    void shouldIsolateSameDashboardIdentityAcrossProfileDatabases() throws Exception {
        File defaultHome = Files.createTempDirectory("solonclaw-chat-profile-default").toFile();
        File workerHome = Files.createTempDirectory("solonclaw-chat-profile-worker").toFile();
        SqliteDatabase defaultDatabase = null;
        SqliteDatabase workerDatabase = null;
        DashboardChatService defaultService = null;
        DashboardChatService workerService = null;
        try {
            defaultDatabase = new SqliteDatabase(testConfig(defaultHome));
            workerDatabase = new SqliteDatabase(testConfig(workerHome));
            SqliteSessionRepository defaultRepository =
                    new SqliteSessionRepository(defaultDatabase);
            SqliteSessionRepository workerRepository = new SqliteSessionRepository(workerDatabase);
            defaultService =
                    new DashboardChatService(
                            defaultRepository,
                            new BlockingConversationOrchestrator(),
                            new StubCommandService(),
                            null,
                            null);
            workerService =
                    new DashboardChatService(
                            workerRepository,
                            new BlockingConversationOrchestrator(),
                            new StubCommandService(),
                            null,
                            null);
            String sharedSessionId = "same-platform-chat-user";

            Map<String, Object> defaultStart =
                    defaultService.startRun(
                            ONode.ofJson(
                                    "{\"input\":\"/default-profile\",\"session_id\":\""
                                            + sharedSessionId
                                            + "\"}"));
            assertThat(completedEvent(defaultService, String.valueOf(defaultStart.get("run_id"))))
                    .isEqualTo("run.completed");
            assertThat(workerRepository.findById(sharedSessionId)).isNull();

            Map<String, Object> workerStart =
                    workerService.startRun(
                            ONode.ofJson(
                                    "{\"input\":\"/worker-profile\",\"session_id\":\""
                                            + sharedSessionId
                                            + "\"}"));
            assertThat(completedEvent(workerService, String.valueOf(workerStart.get("run_id"))))
                    .isEqualTo("run.completed");

            SessionRecord defaultRecord = defaultRepository.findById(sharedSessionId);
            SessionRecord workerRecord = workerRepository.findById(sharedSessionId);
            assertThat(defaultRecord).isNotNull();
            assertThat(workerRecord).isNotNull();
            assertThat(defaultRecord.getSourceKey())
                    .isEqualTo("MEMORY:dashboard:" + sharedSessionId)
                    .isEqualTo(workerRecord.getSourceKey());
            assertThat(MessageSupport.loadMessages(defaultRecord.getNdjson()))
                    .extracting(ChatMessage::getContent)
                    .containsExactly("/default-profile", "status session=" + sharedSessionId);
            assertThat(MessageSupport.loadMessages(workerRecord.getNdjson()))
                    .extracting(ChatMessage::getContent)
                    .containsExactly("/worker-profile", "status session=" + sharedSessionId);
        } finally {
            if (defaultService != null) {
                defaultService.shutdown();
            }
            if (workerService != null) {
                workerService.shutdown();
            }
            if (defaultDatabase != null) {
                defaultDatabase.shutdown();
            }
            if (workerDatabase != null) {
                workerDatabase.shutdown();
            }
            FileUtil.del(defaultHome);
            FileUtil.del(workerHome);
        }
    }

    /**
     * 构造最小测试配置，只启用会话仓储所需的工作区目录和 SQLite 路径。
     *
     * @param workspaceHome 测试工作区目录。
     * @return 返回测试配置对象。
     */
    private static AppConfig testConfig(File workspaceHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(
                        new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        return config;
    }

    /**
     * 从运行状态中读取事件队列，便于测试未经过 HTTP SSE 时的事件内容。
     *
     * @param service Web chat 服务。
     * @param runId 运行标识。
     * @return 返回事件队列。
     */
    @SuppressWarnings("unchecked")
    private static BlockingQueue<Object> eventQueue(DashboardChatService service, String runId)
            throws Exception {
        Field runsField = DashboardChatService.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        ConcurrentMap<String, Object> runs = (ConcurrentMap<String, Object>) runsField.get(service);
        Object state = runs.get(runId);
        assertThat(state).isNotNull();

        Field eventsField = state.getClass().getDeclaredField("events");
        eventsField.setAccessible(true);
        return (BlockingQueue<Object>) eventsField.get(state);
    }

    /**
     * 读取当前尚未消费的事件名称，用于验证终态发布顺序。
     *
     * @param service Web chat 服务。
     * @param runId 运行标识。
     * @return 返回事件名称列表。
     */
    private static java.util.List<String> eventNames(DashboardChatService service, String runId)
            throws Exception {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (Object event : eventQueue(service, runId)) {
            Field nameField = event.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            names.add(String.valueOf(nameField.get(event)));
        }
        return names;
    }

    /**
     * 读取取消事件中的错误文本，验证前端兼容的 run.failed 事件仍然存在。
     *
     * @param service Web chat 服务。
     * @param runId 运行标识。
     * @return 返回取消错误文本。
     */
    @SuppressWarnings("unchecked")
    private static String cancelError(DashboardChatService service, String runId) throws Exception {
        for (Object event : eventQueue(service, runId)) {
            Field nameField = event.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            if (!"run.failed".equals(nameField.get(event))) {
                continue;
            }
            Field dataField = event.getClass().getDeclaredField("data");
            dataField.setAccessible(true);
            Map<String, Object> data = (Map<String, Object>) dataField.get(event);
            return String.valueOf(data.get("error"));
        }
        throw new AssertionError("run.failed event not found");
    }

    /**
     * 等待并返回运行完成事件名称，避免直接读取异步队列时碰到运行尚未结束的竞态。
     *
     * @param service Web chat 服务。
     * @param runId 运行标识。
     * @return 返回完成事件名称。
     */
    private static String completedEvent(DashboardChatService service, String runId)
            throws Exception {
        BlockingQueue<Object> queue = eventQueue(service, runId);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(3);
        while (System.currentTimeMillis() < deadline) {
            Object event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            Field nameField = event.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            String name = String.valueOf(nameField.get(event));
            if ("run.completed".equals(name) || "run.failed".equals(name)) {
                return name;
            }
        }
        throw new AssertionError("run completion event not found");
    }

    /** 阻塞式对话编排器，用来稳定制造 Web 运行中的取消窗口。 */
    private static class BlockingConversationOrchestrator implements ConversationOrchestrator {
        /** 标记 Web 运行已经进入模型编排阶段，测试才会触发取消。 */
        private final CountDownLatch started = new CountDownLatch(1);

        /** 兼容不带事件接收器的调用入口。 */
        @Override
        public GatewayReply handleIncoming(GatewayMessage message) throws Exception {
            return handleIncoming(message, ConversationEventSink.noop());
        }

        /** 模拟模型运行尚未完成，等待测试调用取消接口。 */
        @Override
        public GatewayReply handleIncoming(GatewayMessage message, ConversationEventSink eventSink)
                throws Exception {
            started.countDown();
            Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            GatewayReply reply = GatewayReply.ok("late reply");
            reply.setSessionId(message.getUserId());
            return reply;
        }

        /** 本测试不覆盖定时任务运行，返回空回复即可。 */
        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            return GatewayReply.ok("");
        }

        /** 本测试不覆盖 pending 恢复，返回空回复即可。 */
        @Override
        public GatewayReply resumePending(String sourceKey) {
            return GatewayReply.ok("");
        }

        /** 等待异步运行进入编排器，保证取消发生在真实运行窗口内。 */
        private void awaitStarted() throws InterruptedException {
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 会话保存代理，可稳定阻塞取消轮次写入以验证终态事件的发布顺序。 */
    private static class BlockingSaveSessionRepository extends SqliteSessionRepository {
        /** 标记下一次保存是否需要阻塞。 */
        private volatile boolean blockNextSave;

        /** 通知测试保存已进入阻塞点。 */
        private final CountDownLatch saveBlocked = new CountDownLatch(1);

        /** 允许被阻塞的保存继续执行。 */
        private final CountDownLatch saveReleased = new CountDownLatch(1);

        /** 注入测试数据库。 */
        private BlockingSaveSessionRepository(SqliteDatabase database) {
            super(database);
        }

        /** 让下一次保存停在写库之前。 */
        private void blockNextSave() {
            blockNextSave = true;
        }

        /** 等待取消流程进入会话保存。 */
        private void awaitSaveBlocked() throws InterruptedException {
            assertThat(saveBlocked.await(3, TimeUnit.SECONDS)).isTrue();
        }

        /** 释放被阻塞的会话保存。 */
        private void releaseSave() {
            saveReleased.countDown();
        }

        /** 保存会话，并在测试要求时放大终态发布与持久化之间的竞态窗口。 */
        @Override
        public void save(SessionRecord sessionRecord) throws Exception {
            if (blockNextSave) {
                blockNextSave = false;
                saveBlocked.countDown();
                if (!saveReleased.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待释放会话保存超时");
                }
            }
            super.save(sessionRecord);
        }
    }

    /** 测试用 slash 命令服务，返回可持久化的直连命令结果。 */
    private static class StubCommandService implements CommandService {
        /** 本测试只关心命令是否能进入持久化路径。 */
        @Override
        public boolean supports(String commandName) {
            return true;
        }

        /** 返回包含会话标识的可见命令结果。 */
        @Override
        public GatewayReply handle(GatewayMessage message, String commandLine) {
            GatewayReply reply = GatewayReply.ok("status session=" + message.getUserId());
            reply.setSessionId(message.getUserId());
            return reply;
        }
    }
}
