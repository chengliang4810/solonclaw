package com.jimuqu.solon.claw.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.AgentRunCancelledException;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageSupport;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.LoggerFactory;

/** 覆盖核心 Agent run 状态机的成功、失败与取消分支。 */
public class AgentRunSupervisorEngineTest {
    /** 验证正常模型调用会落库运行状态、事件、会话历史与用量信息。 */
    @Test
    void shouldPersistSuccessfulRunOutcomeAndUsage() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor = supervisor(fixture, new SuccessfulGateway("engine ok"));
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:engine:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "hello engine",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        null,
                        Collections.emptyList(),
                        null);

        AgentRunRecord stored =
                fixture.agentRunRepository.findRun(outcome.getRunRecord().getRunId());
        SessionRecord updated = fixture.sessionRepository.findById(session.getSessionId());

        assertThat(outcome.getFinalReply()).isEqualTo("engine ok");
        assertThat(stored.getStatus()).isEqualTo("success");
        assertThat(stored.getPhase()).isEqualTo("completed");
        assertThat(stored.getExitReason()).isEqualTo("success");
        assertThat(stored.getFinalReplyPreview()).isEqualTo("engine ok");
        assertThat(stored.getProvider()).isEqualTo("primary");
        assertThat(stored.getModel()).isEqualTo("engine-model");
        assertThat(stored.getInputTokens()).isEqualTo(11L);
        assertThat(stored.getOutputTokens()).isEqualTo(9L);
        assertThat(stored.getTotalTokens()).isEqualTo(20L);
        assertThat(updated.getNdjson()).contains("hello engine").contains("engine ok");
        assertThat(updated.getLastInputTokens()).isEqualTo(11L);
        assertThat(updated.getLastOutputTokens()).isEqualTo(9L);
        assertThat(updated.getLastTotalTokens()).isEqualTo(20L);
        assertThat(updated.getLastResolvedProvider()).isEqualTo("primary");
        assertThat(updated.getLastResolvedModel()).isEqualTo("engine-model");
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(stored.getRunId())))
                .contains("run.start", "attempt.start", "compression.skip", "attempt.success", "run.success");
        assertThat(supervisor.hasRunningRuns()).isFalse();
        assertThat(supervisor.lastRunFinishedAt()).isGreaterThan(0L);
    }

    /** 验证模型异常会标记 run failed，并保留失败事件方便 dashboard 诊断。 */
    @Test
    void shouldPersistFailedRunWhenGatewayThrows() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor =
                supervisor(fixture, new ThrowingGateway("upstream rejected engine request"));
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:engine-fail:user");

        assertThatThrownBy(
                        () ->
                                supervisor.run(
                                        session,
                                        "system",
                                        "fail now",
                                        Collections.emptyList(),
                                        ConversationFeedbackSink.noop(),
                                        ConversationEventSink.noop(),
                                        false,
                                        null,
                                        Collections.emptyList(),
                                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream rejected engine request");

        List<AgentRunRecord> runs =
                fixture.agentRunRepository.listBySession(session.getSessionId(), 10);
        assertThat(runs).hasSize(1);
        AgentRunRecord failed = runs.get(0);
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getPhase()).isEqualTo("failed");
        assertThat(failed.getExitReason()).isEqualTo("failed");
        assertThat(failed.getError()).contains("upstream rejected engine request");
        assertThat(failed.getFinishedAt()).isGreaterThan(0L);
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(failed.getRunId())))
                .contains("run.start", "attempt.start", "attempt.error", "run.failed");
        assertThat(supervisor.hasRunningRuns()).isFalse();
    }

    /** 验证 stop 能中断运行中模型调用，并最终清理 running 状态。 */
    @Test
    void shouldCancelRunningRunAndClearSupervisorState() throws Exception {
        Fixture fixture = fixture();
        BlockingGateway gateway = new BlockingGateway();
        AgentRunSupervisor supervisor = supervisor(fixture, gateway);
        SessionRecord session =
                fixture.sessionRepository.bindNewSession("MEMORY:engine-cancel:user");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<AgentRunOutcome> running =
                executor.submit(
                        () ->
                                supervisor.run(
                                        session,
                                        "system",
                                        "keep running",
                                        Collections.emptyList(),
                                        ConversationFeedbackSink.noop(),
                                        ConversationEventSink.noop(),
                                        false,
                                        null,
                                        Collections.emptyList(),
                                        null));

        assertThat(gateway.started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.isRunning(session.getSourceKey())).isTrue();
        assertThat(supervisor.activeRunSummary(session.getSourceKey()))
                .containsEntry("status", "running")
                .containsEntry("session_id", session.getSessionId());

        assertThat(supervisor.stop(session.getSourceKey()).isActiveRun()).isTrue();
        assertThatThrownBy(() -> running.get(3, TimeUnit.SECONDS))
                .hasCauseInstanceOf(AgentRunCancelledException.class);
        executor.shutdownNow();

        List<AgentRunRecord> runs =
                fixture.agentRunRepository.listBySession(session.getSessionId(), 10);
        assertThat(runs).hasSize(1);
        AgentRunRecord cancelled = runs.get(0);
        assertThat(cancelled.getStatus()).isEqualTo("cancelled");
        assertThat(cancelled.getPhase()).isEqualTo("cancelled");
        assertThat(cancelled.getExitReason()).isEqualTo("cancelled");
        assertThat(cancelled.getError()).contains("当前任务已停止");
        assertThat(gateway.interrupted).isTrue();
        assertThat(supervisor.isRunning(session.getSourceKey())).isFalse();
        assertThat(supervisor.runningRunCount()).isZero();
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(cancelled.getRunId())))
                .contains("run.start", "attempt.start", "run.cancelled");
    }

    /** 验证 steer 指令读取失败时保留降级语义，并通过 warn 日志暴露异常。 */
    @Test
    void shouldWarnAndReturnNullWhenSteerInstructionLookupFails() throws Exception {
        Fixture fixture = fixture();
        String leakedToken = "sk-steerfailure12345";
        fixture.agentRunRepository =
                repositoryFailingOn(
                        fixture.agentRunRepository,
                        "findLatestPendingCommand",
                        "steer lookup failed token=" + leakedToken);
        AgentRunSupervisor supervisor = supervisor(fixture, new SuccessfulGateway("unused"));
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRunSupervisor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThat(supervisor.consumeSteerInstruction("run-steer")).isNull();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anySatisfy(
                        event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .contains("consume steer instruction failed")
                                    .contains("runId=run-steer")
                                    .contains("token=***")
                                    .doesNotContain(leakedToken);
                        });
    }

    /** 验证非关键事件追加失败只记录 debug 日志，不影响 busy queue 入队结果。 */
    @Test
    void shouldDebugAndContinueWhenQueueEventAppendFails() throws Exception {
        Fixture fixture = fixture();
        fixture.agentRunRepository =
                repositoryFailingOn(
                        fixture.agentRunRepository, "appendEvent", "queue event append failed");
        AgentRunSupervisor supervisor = supervisor(fixture, new SuccessfulGateway("unused"));
        String sourceKey = "MEMORY:event-fail:user";
        SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
        GatewayMessage message = new GatewayMessage(PlatformType.MEMORY, "event-fail", "user", "queued");
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRunSupervisor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);

        RunBusyDecision decision;
        try {
            decision = supervisor.queueIncoming(sourceKey, session.getSessionId(), message);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        assertThat(decision.isQueued()).isTrue();
        assertThat(fixture.agentRunRepository.findRun(decision.getRunId())).isNotNull();
        assertThat(fixture.agentRunRepository.findNextQueuedMessage(sourceKey, session.getSessionId()))
                .isNotNull();
        assertThat(appender.list)
                .anySatisfy(
                        event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                            assertThat(event.getFormattedMessage())
                                    .contains("append run event failed")
                                    .contains("eventType=run.queued")
                                    .contains("queue event append failed");
                        });
    }

    /** 创建只依赖临时目录的核心引擎测试夹具。 */
    private static Fixture fixture() throws Exception {
        AppConfig config = new AppConfig();
        File workspaceHome = Files.createTempDirectory("solonclaw-engine-test").toFile();
        File dataDir = new File(workspaceHome, "data");
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime().setStateDb(new File(dataDir, "state.db").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        config.getWorkspace().setDir(workspaceHome.getAbsolutePath());
        config.getTrace().setMaxAttempts(1);
        config.getTrace().setRetentionDays(0);
        config.getTask().setBusyPolicy("queue");
        writeProviderConfig(workspaceHome);

        AppConfig.ProviderConfig primary = new AppConfig.ProviderConfig();
        primary.setName("Primary");
        primary.setBaseUrl("https://example.test");
        primary.setApiKey("test-key");
        primary.setDefaultModel("engine-model");
        primary.setDialect("openai-responses");
        config.getProviders().put("primary", primary);
        config.getModel().setProviderKey("primary");

        SqliteDatabase database = new SqliteDatabase(config);
        Fixture fixture = new Fixture();
        fixture.config = config;
        fixture.sessionRepository = new SqliteSessionRepository(database);
        fixture.agentRunRepository = new SqliteAgentRunRepository(database);
        return fixture;
    }

    /**
     * 写入工作区模型配置，避免 LlmProviderService 读取开发机全局 workspace。
     *
     * @param workspaceHome 测试工作区根目录。
     */
    private static void writeProviderConfig(File workspaceHome) throws Exception {
        Files.write(
                new File(workspaceHome, "config.yml").toPath(),
                Collections.singletonList(
                        "providers:\n"
                                + "  primary:\n"
                                + "    name: Primary\n"
                                + "    baseUrl: https://example.test\n"
                                + "    apiKey: test-key\n"
                                + "    defaultModel: engine-model\n"
                                + "    dialect: openai-responses\n"
                                + "model:\n"
                                + "  providerKey: primary\n"));
    }

    /**
     * 创建被测 Supervisor，并固定为不触发上下文压缩。
     *
     * @param fixture 测试夹具。
     * @param gateway 可控的大模型网关。
     * @return 返回可直接运行的 Supervisor。
     */
    private static AgentRunSupervisor supervisor(Fixture fixture, LlmGateway gateway) {
        return new AgentRunSupervisor(
                fixture.config,
                fixture.sessionRepository,
                fixture.agentRunRepository,
                new NoopCompressionService(),
                noCompressionBudget(),
                gateway,
                new LlmProviderService(fixture.config));
    }

    /**
     * 构造指定仓储方法必定失败的代理，用于覆盖 Supervisor 的异常降级分支。
     *
     * @param delegate 真实仓储实现。
     * @param methodName 需要注入异常的方法名。
     * @param message 固定异常消息。
     * @return 返回带故障注入的仓储代理。
     */
    private static AgentRunRepository repositoryFailingOn(
            AgentRunRepository delegate, String methodName, String message) {
        return (AgentRunRepository)
                Proxy.newProxyInstance(
                        AgentRunRepository.class.getClassLoader(),
                        new Class<?>[] {AgentRunRepository.class},
                        new InvocationHandler() {
                            /**
                             * 代理仓储调用，在目标方法上注入异常，其余方法保持真实仓储行为。
                             *
                             * @param proxy 当前代理实例。
                             * @param method 被调用的方法。
                             * @param args 调用参数。
                             * @return 返回真实仓储方法结果。
                             */
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                if (methodName.equals(method.getName())) {
                                    throw new IllegalStateException(message);
                                }
                                try {
                                    return method.invoke(delegate, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }
                        });
    }

    /** 构造不会触发压缩的预算服务，聚焦 run 状态机本身。 */
    private static ContextBudgetService noCompressionBudget() {
        return new ContextBudgetService() {
            /**
             * 固定返回预算内，避免压缩逻辑干扰核心运行状态断言。
             *
             * @param session 当前会话。
             * @param systemPrompt 系统提示词。
             * @param userMessage 用户输入。
             * @param resolved 已解析模型配置。
             * @return 返回不压缩的预算决策。
             */
            @Override
            public ContextBudgetDecision decide(
                    SessionRecord session,
                    String systemPrompt,
                    String userMessage,
                    AppConfig.LlmConfig resolved) {
                ContextBudgetDecision decision = new ContextBudgetDecision();
                decision.setShouldCompress(false);
                decision.setReason("within test budget");
                decision.setEstimatedTokens(32);
                decision.setThresholdTokens(4096);
                return decision;
            }
        };
    }

    /**
     * 提取事件类型序列，便于断言核心状态流。
     *
     * @param events 运行事件列表。
     * @return 返回事件类型列表。
     */
    private static List<String> eventTypes(List<AgentRunEventRecord> events) {
        List<String> types = new ArrayList<String>();
        for (AgentRunEventRecord event : events) {
            types.add(event.getEventType());
        }
        return types;
    }

    /** 汇总测试中复用的配置与仓储依赖。 */
    private static class Fixture {
        /** 应用配置快照。 */
        private AppConfig config;

        /** 会话仓储。 */
        private SessionRepository sessionRepository;

        /** Agent run 仓储。 */
        private AgentRunRepository agentRunRepository;
    }

    /** 不修改会话的压缩服务，占位满足 Supervisor 依赖。 */
    private static class NoopCompressionService implements ContextCompressionService {
        /**
         * 返回原会话，表示无需按需压缩。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param userMessage 用户输入。
         * @return 返回原会话。
         */
        @Override
        public SessionRecord compressIfNeeded(
                SessionRecord session, String systemPrompt, String userMessage) {
            return session;
        }

        /**
         * 返回原会话，表示强制压缩在本测试中无副作用。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @return 返回原会话。
         */
        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt) {
            return session;
        }

        /**
         * 返回原会话，保留 focus 参数入口。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param focus 压缩关注主题。
         * @return 返回原会话。
         */
        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt, String focus) {
            return session;
        }
    }

    /** 返回固定成功结果的大模型网关。 */
    private static class SuccessfulGateway implements LlmGateway {
        /** 固定回复文本。 */
        private final String reply;

        /**
         * 创建成功网关。
         *
         * @param reply 固定助手回复。
         */
        private SuccessfulGateway(String reply) {
            this.reply = reply;
        }

        /**
         * 生成包含用户输入和助手回复的模型结果。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param userMessage 用户输入。
         * @param toolObjects 工具对象列表。
         * @return 返回成功模型结果。
         */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
            messages.add(ChatMessage.ofUser(userMessage));
            messages.add(ChatMessage.ofAssistant(reply));
            LlmResult result = new LlmResult();
            result.setAssistantMessage(ChatMessage.ofAssistant(reply));
            result.setNdjson(MessageSupport.toNdjson(messages));
            result.setProvider("primary");
            result.setModel("engine-model");
            result.setRawResponse("ok");
            result.setInputTokens(11L);
            result.setOutputTokens(9L);
            result.setTotalTokens(20L);
            return result;
        }

        /**
         * 恢复路径在本测试中不应被调用。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param toolObjects 工具对象列表。
         * @return 不返回结果。
         */
        @Override
        public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException("resume is not used in this test");
        }
    }

    /** 始终抛出异常的大模型网关。 */
    private static class ThrowingGateway implements LlmGateway {
        /** 固定异常消息。 */
        private final String message;

        /**
         * 创建失败网关。
         *
         * @param message 固定异常消息。
         */
        private ThrowingGateway(String message) {
            this.message = message;
        }

        /**
         * 抛出固定异常，模拟模型请求失败。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param userMessage 用户输入。
         * @param toolObjects 工具对象列表。
         * @return 不返回结果。
         */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new IllegalStateException(message);
        }

        /**
         * 恢复路径在本测试中不应被调用。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param toolObjects 工具对象列表。
         * @return 不返回结果。
         */
        @Override
        public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException("resume is not used in this test");
        }
    }

    /** 阻塞直到被中断的大模型网关，用于验证取消分支。 */
    private static class BlockingGateway implements LlmGateway {
        /** 模型调用已进入阻塞段的信号。 */
        private final CountDownLatch started = new CountDownLatch(1);

        /** 是否观察到线程中断。 */
        private volatile boolean interrupted;

        /**
         * 阻塞当前调用，直到 Supervisor stop 中断线程。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param userMessage 用户输入。
         * @param toolObjects 工具对象列表。
         * @return 不返回结果。
         */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            }
        }

        /**
         * 恢复路径复用阻塞行为。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param toolObjects 工具对象列表。
         * @return 不返回结果。
         */
        @Override
        public LlmResult resume(SessionRecord session, String systemPrompt, List<Object> toolObjects)
                throws Exception {
            return chat(session, systemPrompt, null, toolObjects);
        }

        /**
         * 直接覆盖 executeOnce，确保取消测试进入可中断阻塞段。
         *
         * @param session 当前会话。
         * @param systemPrompt 系统提示词。
         * @param userMessage 用户输入。
         * @param toolObjects 工具对象列表。
         * @param feedbackSink 反馈输出。
         * @param eventSink 事件输出。
         * @param resume 是否恢复运行。
         * @param resolved 已解析模型配置。
         * @param runContext 当前运行上下文。
         * @return 不返回结果。
         */
        @Override
        public LlmResult executeOnce(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects,
                ConversationFeedbackSink feedbackSink,
                ConversationEventSink eventSink,
                boolean resume,
                AppConfig.LlmConfig resolved,
                AgentRunContext runContext)
                throws Exception {
            return chat(session, systemPrompt, userMessage, toolObjects);
        }
    }
}
