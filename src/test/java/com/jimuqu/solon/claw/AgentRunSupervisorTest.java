package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageSupport;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.File;
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
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.LoggerFactory;

public class AgentRunSupervisorTest {
    @Test
    void shouldFallbackAndPersistRunEvents() throws Exception {
        Fixture fixture = fixture();
        RecordingGateway gateway = new RecordingGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "hello",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false);

        assertThat(outcome.getFinalReply()).isEqualTo("backup ok");
        assertThat(outcome.getRunRecord().getStatus()).isEqualTo("success");
        assertThat(outcome.getRunRecord().getProvider()).isEqualTo("backup");
        assertThat(gateway.attempts)
                .containsExactly("primary:gpt-5-mini", "backup:claude-sonnet-4");

        List<AgentRunEventRecord> events =
                fixture.agentRunRepository.listEvents(outcome.getRunRecord().getRunId());
        assertThat(eventTypes(events))
                .contains(
                        "run.start",
                        "attempt.start",
                        "attempt.error",
                        "fallback",
                        "attempt.success",
                        "run.success");
    }

    @Test
    void shouldRecordCompressionDecisionBeforeAttempt() throws Exception {
        Fixture fixture = fixture();
        RecordingGateway gateway = new RecordingGateway();
        CountingCompressionService compressionService = new CountingCompressionService();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, compressingBudget(), compressionService);
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "hello",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false);

        assertThat(outcome.getRunRecord().getStatus()).isEqualTo("success");
        assertThat(compressionService.compressCount).isEqualTo(2);
        List<AgentRunEventRecord> events =
                fixture.agentRunRepository.listEvents(outcome.getRunRecord().getRunId());
        assertThat(eventTypes(events)).contains("compression.unchanged");
    }

    @Test
    void shouldRedactRunFailureEventsAndRecords() throws Exception {
        Fixture fixture = fixture();
        String leakedToken = "sk-supervisor12345";
        AgentRunSupervisor supervisor =
                supervisor(fixture, new ThrowingGateway(leakedToken), noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        try {
            supervisor.run(
                    session,
                    "system",
                    "hello",
                    Collections.emptyList(),
                    ConversationFeedbackSink.noop(),
                    ConversationEventSink.noop(),
                    false);
        } catch (Exception expected) {
            // The assertions below inspect the persisted run surface.
        }

        List<com.jimuqu.solon.claw.core.model.AgentRunRecord> runs =
                fixture.agentRunRepository.listBySession(session.getSessionId(), 10);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getStatus()).isEqualTo("failed");
        assertThat(runs.get(0).getError()).contains("***").doesNotContain(leakedToken);

        List<AgentRunEventRecord> events =
                fixture.agentRunRepository.listEvents(runs.get(0).getRunId());
        assertThat(eventTypes(events)).contains("attempt.error", "fallback", "run.failed");
        boolean sawRedactedEvent = false;
        for (AgentRunEventRecord event : events) {
            if (event.getSummary() != null) {
                assertThat(event.getSummary()).doesNotContain(leakedToken);
            }
            if (event.getMetadataJson() != null) {
                assertThat(event.getMetadataJson()).doesNotContain(leakedToken);
            }
            if ((event.getSummary() != null && event.getSummary().contains("***"))
                    || (event.getMetadataJson() != null && event.getMetadataJson().contains("***"))) {
                sawRedactedEvent = true;
            }
        }
        assertThat(sawRedactedEvent).isTrue();
    }

    @Test
    void shouldRedactRecoveryFailureLogs() throws Exception {
        Fixture fixture = fixture();
        String leakedToken = "sk-supervisor-recovery12345";
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new FailingRecoveryGateway(leakedToken),
                        noCompressionBudget(),
                        noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRunSupervisor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            supervisor.run(
                    session,
                    "system",
                    "hello",
                    Collections.emptyList(),
                    ConversationFeedbackSink.noop(),
                    ConversationEventSink.noop(),
                    false);
        } catch (Exception expected) {
            // The assertions below inspect the recovery failure log surface.
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("Agent recovery failed"))
                .anyMatch(message -> message.contains("token=***"))
                .noneMatch(message -> message.contains(leakedToken));
    }

    @Test
    void shouldMarkRunningSessionsResumePendingForRestartTimeoutStops() throws Exception {
        Fixture fixture = fixture();
        BlockingGateway gateway = new BlockingGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:restart:user");

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<AgentRunOutcome> running =
                executorService.submit(
                        () ->
                                supervisor.run(
                                        session,
                                        "system",
                                        "long task",
                                        Collections.emptyList(),
                                        ConversationFeedbackSink.noop(),
                                        ConversationEventSink.noop(),
                                        false));

        assertThat(gateway.started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.stopAllRunningRuns("restart_timeout")).isEqualTo(1);

        try {
            running.get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            executorService.shutdownNow();
        }

        SessionRecord reloaded = fixture.sessionRepository.findById(session.getSessionId());
        SqliteAgentSession agentSession = new SqliteAgentSession(reloaded);
        assertThat(agentSession.isPending()).isTrue();
        assertThat(agentSession.getPendingReason()).isEqualTo("restart_timeout");
        assertThat(gateway.interrupted).isTrue();
    }

    @Test
    void shouldNotPersistEmptyReplyRecoveryPromptInSessionHistory() throws Exception {
        Fixture fixture = fixture();
        RecoveryTranscriptGateway gateway =
                new RecoveryTranscriptGateway(
                        ChatMessage.toNdjson(
                                java.util.Arrays.asList(
                                        ChatMessage.ofUser("执行任务"),
                                        ChatMessage.ofTool("工具结果：已完成", "shell", "call_1"),
                                        ChatMessage.ofAssistant(""))),
                        "最终答复：工具已经执行完成。");
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "执行任务",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false);

        SessionRecord updated = fixture.sessionRepository.findById(session.getSessionId());
        String persisted = updated.getNdjson();
        List<ChatMessage> messages = MessageSupport.loadMessages(persisted);
        assertThat(outcome.getFinalReply()).contains("最终答复");
        assertThat(gateway.prompts).hasSize(2);
        assertThat(gateway.prompts.get(1)).contains("没有输出最终答复");
        assertThat(persisted).doesNotContain("工具结果：已完成");
        assertThat(persisted).contains("最终答复：工具已经执行完成。");
        assertThat(persisted).doesNotContain("没有输出最终答复");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getContent()).isEqualTo("最终答复：工具已经执行完成。");
    }

    @Test
    void shouldNotPersistMaxStepsRecoveryPromptOrTerminalSentinel() throws Exception {
        Fixture fixture = fixture();
        RecoveryTranscriptGateway gateway =
                new RecoveryTranscriptGateway(
                        ChatMessage.toNdjson(
                                java.util.Arrays.asList(
                                        ChatMessage.ofUser("处理复杂任务"),
                                        ChatMessage.ofAssistant(
                                                "Agent error: Maximum steps reached (12)."))),
                        "收敛总结：已经完成主要处理。");
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "处理复杂任务",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false);

        SessionRecord updated = fixture.sessionRepository.findById(session.getSessionId());
        String persisted = updated.getNdjson();
        List<ChatMessage> messages = MessageSupport.loadMessages(persisted);
        assertThat(outcome.getFinalReply()).contains("收敛总结");
        assertThat(gateway.prompts).hasSize(2);
        assertThat(gateway.prompts.get(1)).contains("最大推理步数限制");
        assertThat(persisted).contains("收敛总结：已经完成主要处理。");
        assertThat(persisted).doesNotContain("Maximum steps reached");
        assertThat(persisted).doesNotContain("最大推理步数限制");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getContent()).isEqualTo("收敛总结：已经完成主要处理。");
    }

    private static AgentRunSupervisor supervisor(
            Fixture fixture,
            LlmGateway gateway,
            ContextBudgetService budgetService,
            ContextCompressionService compressionService) {
        return new AgentRunSupervisor(
                fixture.config,
                fixture.sessionRepository,
                fixture.agentRunRepository,
                compressionService,
                budgetService,
                gateway,
                new LlmProviderService(fixture.config));
    }

    private static Fixture fixture() throws Exception {
        AppConfig config = new AppConfig();
        File runtimeHome = Files.createTempDirectory("solon-claw-supervisor").toFile();
        config.getRuntime()
                .setStateDb(new File(new File(runtimeHome, "data"), "state.db").getAbsolutePath());
        config.getTrace().setMaxAttempts(1);

        AppConfig.ProviderConfig primary = new AppConfig.ProviderConfig();
        primary.setName("Primary");
        primary.setBaseUrl("https://api.openai.com");
        primary.setApiKey("primary-key");
        primary.setDefaultModel("gpt-5-mini");
        primary.setDialect("openai-responses");
        config.getProviders().put("primary", primary);

        AppConfig.ProviderConfig backup = new AppConfig.ProviderConfig();
        backup.setName("Backup");
        backup.setBaseUrl("https://api.anthropic.com");
        backup.setApiKey("backup-key");
        backup.setDefaultModel("claude-sonnet-4");
        backup.setDialect("anthropic");
        config.getProviders().put("backup", backup);

        config.getModel().setProviderKey("primary");
        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("backup");
        config.getFallbackProviders().add(fallback);

        SqliteDatabase database = new SqliteDatabase(config);
        Fixture fixture = new Fixture();
        fixture.config = config;
        fixture.sessionRepository = new SqliteSessionRepository(database);
        fixture.agentRunRepository = new SqliteAgentRunRepository(database);
        return fixture;
    }

    private static ContextBudgetService noCompressionBudget() {
        return new ContextBudgetService() {
            @Override
            public ContextBudgetDecision decide(
                    SessionRecord session,
                    String systemPrompt,
                    String userMessage,
                    AppConfig.LlmConfig resolved) {
                ContextBudgetDecision decision = new ContextBudgetDecision();
                decision.setShouldCompress(false);
                decision.setReason("within budget");
                decision.setEstimatedTokens(100);
                decision.setThresholdTokens(1000);
                return decision;
            }
        };
    }

    private static ContextBudgetService compressingBudget() {
        return new ContextBudgetService() {
            @Override
            public ContextBudgetDecision decide(
                    SessionRecord session,
                    String systemPrompt,
                    String userMessage,
                    AppConfig.LlmConfig resolved) {
                ContextBudgetDecision decision = new ContextBudgetDecision();
                decision.setShouldCompress(true);
                decision.setReason("over budget");
                decision.setEstimatedTokens(2000);
                decision.setThresholdTokens(1000);
                return decision;
            }
        };
    }

    private static ContextCompressionService noCompressionService() {
        return new CountingCompressionService();
    }

    private static List<String> eventTypes(List<AgentRunEventRecord> events) {
        List<String> types = new ArrayList<String>();
        for (AgentRunEventRecord event : events) {
            types.add(event.getEventType());
        }
        return types;
    }

    private static class Fixture {
        private AppConfig config;
        private SessionRepository sessionRepository;
        private AgentRunRepository agentRunRepository;
    }

    private static class RecordingGateway implements LlmGateway {
        private final List<String> attempts = new ArrayList<String>();

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

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
                com.jimuqu.solon.claw.core.model.AgentRunContext runContext) {
            attempts.add(resolved.getProvider() + ":" + resolved.getModel());
            if ("primary".equals(resolved.getProvider())) {
                throw new IllegalStateException("HTTP 401 unauthorized");
            }
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setRawResponse("ok");
            result.setAssistantMessage(new AssistantMessage("backup ok"));
            result.setNdjson(session.getNdjson());
            return result;
        }
    }

    private static class ThrowingGateway implements LlmGateway {
        private final String leakedToken;

        private ThrowingGateway(String leakedToken) {
            this.leakedToken = leakedToken;
        }

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

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
                com.jimuqu.solon.claw.core.model.AgentRunContext runContext) {
            throw new IllegalStateException("upstream rejected api_key=" + leakedToken);
        }
    }

    private static class RecoveryTranscriptGateway implements LlmGateway {
        private final List<String> prompts = new ArrayList<String>();
        private final String firstNdjson;
        private final String recoveredText;

        private RecoveryTranscriptGateway(String firstNdjson, String recoveredText) {
            this.firstNdjson = firstNdjson;
            this.recoveredText = recoveredText;
        }

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

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
                com.jimuqu.solon.claw.core.model.AgentRunContext runContext)
                throws Exception {
            prompts.add(userMessage);
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            if (prompts.size() == 1) {
                List<ChatMessage> messages = MessageSupport.loadMessages(firstNdjson);
                ChatMessage last = messages.get(messages.size() - 1);
                result.setAssistantMessage(ChatMessage.ofAssistant(last.getContent()));
                result.setNdjson(firstNdjson);
                result.setInputTokens(10);
                result.setOutputTokens(5);
                result.setTotalTokens(15);
                return result;
            }

            List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
            messages.add(ChatMessage.ofUser(userMessage));
            messages.add(ChatMessage.ofAssistant(recoveredText));
            result.setAssistantMessage(ChatMessage.ofAssistant(recoveredText));
            result.setNdjson(ChatMessage.toNdjson(messages));
            result.setInputTokens(3);
            result.setOutputTokens(7);
            result.setTotalTokens(10);
            return result;
        }
    }

    private static class FailingRecoveryGateway implements LlmGateway {
        private final String leakedToken;
        private int attempts;

        private FailingRecoveryGateway(String leakedToken) {
            this.leakedToken = leakedToken;
        }

        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException();
        }

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
                com.jimuqu.solon.claw.core.model.AgentRunContext runContext)
                throws Exception {
            attempts++;
            if (userMessage != null && userMessage.contains("没有输出最终答复")) {
                throw new IllegalStateException("recovery failed token=" + leakedToken);
            }
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setAssistantMessage(new AssistantMessage(""));
            result.setNdjson(
                    ChatMessage.toNdjson(
                            java.util.Arrays.asList(
                                    ChatMessage.ofUser("hello"),
                                    ChatMessage.ofTool("tool done", "shell", "call_1"),
                                    ChatMessage.ofAssistant(""))));
            return result;
        }
    }

    private static class BlockingGateway implements LlmGateway {
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile boolean interrupted;

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

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects)
                throws Exception {
            return chat(session, systemPrompt, null, toolObjects);
        }
    }

    private static class CountingCompressionService implements ContextCompressionService {
        private int compressCount;

        @Override
        public SessionRecord compressIfNeeded(
                SessionRecord session, String systemPrompt, String userMessage) {
            return session;
        }

        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt) {
            compressCount++;
            return session;
        }

        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt, String focus) {
            compressCount++;
            return session;
        }
    }
}
