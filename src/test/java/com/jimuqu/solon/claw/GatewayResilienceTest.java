package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 覆盖 gateway 的异常隔离与重复消息抑制行为。 */
public class GatewayResilienceTest {
    @Test
    void shouldIgnoreLearningSchedulerFailureAndStillReturnReply() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room:user");
        DefaultGatewayService service =
                new DefaultGatewayService(
                        unsupportedCommandService(),
                        replyingOrchestrator(session, "ok"),
                        env.deliveryService,
                        env.sessionRepository,
                        allowAllAuthorization(env),
                        new SkillLearningService() {
                            @Override
                            public void schedulePostReplyLearning(
                                    SessionRecord session,
                                    GatewayMessage message,
                                    GatewayReply reply) {
                                throw new IllegalStateException("learning scheduler boom");
                            }
                        });

        GatewayReply reply = service.handle(env.message("room", "user", "hello"));

        assertThat(reply.getContent()).isEqualTo("ok");
        assertThat(reply.isError()).isFalse();
        assertThat(env.memoryChannelAdapter.getLastRequest().getText()).isEqualTo("ok");
    }

    @Test
    void shouldExtractGatewayReplyMediaTagsIntoDeliveryAttachments() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:media-room:user");
        File attachment = new File(env.appConfig.getRuntime().getCacheDir(), "gateway-media/report.txt");
        Files.createDirectories(attachment.getParentFile().toPath());
        Files.write(attachment.toPath(), "report body".getBytes("UTF-8"));
        DefaultGatewayService service =
                new DefaultGatewayService(
                        unsupportedCommandService(),
                        replyingOrchestrator(
                                session,
                                "网关报告\nMEDIA:\"" + attachment.getAbsolutePath() + "\"\n请查收"),
                        env.deliveryService,
                        env.sessionRepository,
                        allowAllAuthorization(env),
                        noopLearningService(),
                        new AttachmentCacheService(env.appConfig));

        GatewayReply reply = service.handle(env.message("media-room", "user", "send report"));

        assertThat(reply.getContent()).contains("MEDIA:");
        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getText()).contains("网关报告").contains("请查收");
        assertThat(request.getText()).doesNotContain("MEDIA:");
        assertThat(request.getAttachments()).hasSize(1);
        assertThat(request.getAttachments().get(0).getOriginalName()).isEqualTo("report.txt");
        assertThat(request.getAttachments().get(0).getLocalPath())
                .isEqualTo(attachment.getAbsolutePath());
    }

    @Test
    void shouldPreserveGatewayReplyTextWhenNoMediaTagResolves() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:plain-media-room:user");
        String content = "  第一行\n\n\n第二行  ";
        DefaultGatewayService service =
                new DefaultGatewayService(
                        unsupportedCommandService(),
                        replyingOrchestrator(session, content),
                        env.deliveryService,
                        env.sessionRepository,
                        allowAllAuthorization(env),
                        noopLearningService(),
                        new AttachmentCacheService(env.appConfig));

        service.handle(env.message("plain-media-room", "user", "send text"));

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getText()).isEqualTo(content);
        assertThat(request.getAttachments()).isEmpty();
    }

    @Test
    void shouldKeepGatewayReplyMediaTagVisibleWhenAttachmentIsMissing() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:missing-media-room:user");
        File missing = new File(env.appConfig.getRuntime().getCacheDir(), "missing-report.txt");
        DefaultGatewayService service =
                new DefaultGatewayService(
                        unsupportedCommandService(),
                        replyingOrchestrator(
                                session,
                                "网关报告\nMEDIA:\"" + missing.getAbsolutePath() + "\"\n请查收"),
                        env.deliveryService,
                        env.sessionRepository,
                        allowAllAuthorization(env),
                        noopLearningService(),
                        new AttachmentCacheService(env.appConfig));

        service.handle(env.message("missing-media-room", "user", "send report"));

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getText()).contains("MEDIA:").contains("missing-report.txt");
        assertThat(request.getAttachments()).isEmpty();
    }

    @Test
    void shouldKeepGatewayReplyMediaTagVisibleWhenAttachmentIsBlocked() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:blocked-media-room:user");
        File config = new File(env.appConfig.getRuntime().getHome(), "config.yml");
        Files.write(config.toPath(), "secret: value".getBytes("UTF-8"));
        DefaultGatewayService service =
                new DefaultGatewayService(
                        unsupportedCommandService(),
                        replyingOrchestrator(
                                session,
                                "网关报告\nMEDIA:\"" + config.getAbsolutePath() + "\"\n请查收"),
                        env.deliveryService,
                        env.sessionRepository,
                        allowAllAuthorization(env),
                        noopLearningService(),
                        new AttachmentCacheService(env.appConfig));

        service.handle(env.message("blocked-media-room", "user", "send report"));

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request).isNotNull();
        assertThat(request.getText()).contains("MEDIA:").contains("config.yml");
        assertThat(request.getAttachments()).isEmpty();
    }

    @Test
    void shouldSuppressDuplicateThreadMessages() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:room:user");
        final AtomicInteger calls = new AtomicInteger();
        DefaultGatewayService service =
                new DefaultGatewayService(
                        unsupportedCommandService(),
                        new ConversationOrchestrator() {
                            @Override
                            public GatewayReply handleIncoming(GatewayMessage message) {
                                calls.incrementAndGet();
                                GatewayReply reply = GatewayReply.ok("ok");
                                reply.setSessionId(session.getSessionId());
                                return reply;
                            }

                            @Override
                            public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                                return handleIncoming(syntheticMessage);
                            }

                            @Override
                            public GatewayReply resumePending(String sourceKey) {
                                return GatewayReply.ok("ok");
                            }
                        },
                        env.deliveryService,
                        env.sessionRepository,
                        allowAllAuthorization(env),
                        new SkillLearningService() {
                            @Override
                            public void schedulePostReplyLearning(
                                    SessionRecord session,
                                    GatewayMessage message,
                                    GatewayReply reply) {}
                        });

        GatewayMessage firstMessage = env.message("room", "user", "hello");
        firstMessage.setThreadId("msg-1");
        GatewayReply first = service.handle(firstMessage);
        GatewayReply second = service.handle(firstMessage);

        assertThat(first.getContent()).isEqualTo("ok");
        assertThat(second).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldApplyBusyQueuePolicyAtRuntime() throws Exception {
        BlockingFirstLlmGateway llm = new BlockingFirstLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llm);
        bootstrapAdmin(env);
        String sourceKey = env.message("room", "user", "").sourceKey();
        env.appConfig.getTask().setBusyPolicy("queue");
        Thread first = startAsync(env, "first");
        assertThat(llm.awaitFirst()).isTrue();
        SessionRecord session = env.sessionRepository.getBoundSession(sourceKey);

        GatewayReply reply = env.gatewayService.handle(env.message("room", "user", "queued follow-up"));
        QueuedRunMessage queued =
                env.agentRunRepository.findNextQueuedMessage(
                        sourceKey, session.getSessionId());

        assertThat(reply.getContent()).contains("已排队");
        assertThat(reply.getRuntimeMetadata()).containsEntry("busy_policy", "queue");
        assertThat(queued).isNotNull();
        assertThat(queued.getMessageText()).isEqualTo("queued follow-up");
        assertThat(queued.getMessageJson()).contains("queued follow-up");
        assertThat(queued.getBusyPolicy()).isEqualTo("queue");
        assertThat(env.agentRunRepository.findRun(queued.getRunId()).getBusyPolicy())
                .isEqualTo("queue");
        llm.releaseFirst();
        first.join(5000L);
    }

    @Test
    void shouldRedactQueuedRunMessageErrorsWithoutMutatingQueuedInput() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        QueuedRunMessage queued = new QueuedRunMessage();
        queued.setQueueId("queue-secret-error");
        queued.setRunId("run-secret-error");
        queued.setSessionId("session-secret-error");
        queued.setSourceKey("MEMORY:queue-secret:user");
        queued.setMessageText("please use token=ghp_queueinput12345");
        queued.setMessageJson("{\"text\":\"please use token=ghp_queueinput12345\"}");
        queued.setStatus("queued");
        queued.setBusyPolicy("queue");
        queued.setCreatedAt(System.currentTimeMillis());
        queued.setError("initial failure api_key=sk-queue-initial-secret12345\u202E");
        env.agentRunRepository.saveQueuedMessage(queued);

        QueuedRunMessage stored =
                env.agentRunRepository.findNextQueuedMessage(
                        queued.getSourceKey(), queued.getSessionId());
        assertThat(stored).isNotNull();
        assertThat(stored.getMessageText()).contains("ghp_queueinput12345");
        assertThat(stored.getMessageJson()).contains("ghp_queueinput12345");
        assertThat(stored.getError())
                .contains("api_key=***")
                .doesNotContain("sk-queue-initial-secret12345")
                .doesNotContain("\u202E");

        env.agentRunRepository.markQueuedMessage(
                queued.getQueueId(),
                "failed",
                System.currentTimeMillis(),
                "final failure token=ghp_queueerror12345\u202E");
        QueuedRunMessage failed =
                env.agentRunRepository.findNextQueuedMessage(
                        queued.getSourceKey(), queued.getSessionId());
        assertThat(failed).isNull();
        assertThat(readQueuedError(env, queued.getQueueId()))
                .contains("token=***")
                .doesNotContain("ghp_queueerror12345")
                .doesNotContain("\u202E");
    }

    @Test
    void shouldApplyBusySteerPolicyAtRuntime() throws Exception {
        BlockingFirstLlmGateway llm = new BlockingFirstLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llm);
        bootstrapAdmin(env);
        String sourceKey = env.message("room", "user", "").sourceKey();
        env.appConfig.getTask().setBusyPolicy("steer");
        Thread first = startAsync(env, "first");
        assertThat(llm.awaitFirst()).isTrue();

        GatewayReply reply = env.gatewayService.handle(env.message("room", "user", "adjust plan"));
        String runId = String.valueOf(reply.getRuntimeMetadata().get("run_id"));
        RunControlCommand command =
                env.agentRunRepository.findLatestPendingCommand(runId, "steer");

        assertThat(reply.getContent()).contains("注入当前长任务");
        assertThat(reply.getRuntimeMetadata()).containsEntry("busy_policy", "steer");
        assertThat(command).isNotNull();
        assertThat(command.getPayloadJson()).contains("adjust plan");
        assertThat(env.agentRunRepository.findRun(runId).getBusyPolicy()).isEqualTo("steer");
        llm.releaseFirst();
        first.join(5000L);
    }

    @Test
    void shouldApplyBusyInterruptPolicyAtRuntime() throws Exception {
        BlockingFirstLlmGateway llm = new BlockingFirstLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llm);
        bootstrapAdmin(env);
        String sourceKey = env.message("room", "user", "").sourceKey();
        env.appConfig.getTask().setBusyPolicy("interrupt");
        Thread first = startAsync(env, "first");
        assertThat(llm.awaitFirst()).isTrue();
        SessionRecord session = env.sessionRepository.getBoundSession(sourceKey);

        GatewayReply reply = env.gatewayService.handle(env.message("room", "user", "replacement"));

        assertThat(reply.getContent()).contains("echo:replacement");
        assertThat(env.agentRunControlService.isRunning(sourceKey)).isFalse();
        assertThat(env.agentRunRepository.searchRuns(
                        sourceKey, session.getSessionId(), null, null, 0L, 0L, 10))
                .anySatisfy(
                        run ->
                                assertThat(run.getExitReason())
                                        .isIn("busy_interrupt", "cancelled"))
                .anySatisfy(run -> assertThat(run.getBusyPolicy()).isEqualTo("interrupt"));
        llm.releaseFirst();
        first.join(5000L);
    }

    @Test
    void shouldLetHeartbeatBypassActiveRunBusyPolicy() throws Exception {
        BlockingFirstLlmGateway llm = new BlockingFirstLlmGateway();
        TestEnvironment env = TestEnvironment.withLlm(llm);
        bootstrapAdmin(env);
        String sourceKey = env.message("room", "user", "").sourceKey();
        env.appConfig.getTask().setBusyPolicy("interrupt");
        Thread first = startAsync(env, "first");
        assertThat(llm.awaitFirst()).isTrue();
        SessionRecord session = env.sessionRepository.getBoundSession(sourceKey);
        String runId = String.valueOf(env.agentRunControlService.activeRunSummary(sourceKey).get("run_id"));
        long beforeHeartbeat =
                env.agentRunRepository.findRun(runId).getHeartbeatAt();

        Thread.sleep(5L);
        GatewayMessage heartbeat = env.message("room", "user", "heartbeat");
        heartbeat.setHeartbeat(true);
        GatewayReply reply = env.gatewayService.handle(heartbeat);

        assertThat(reply.getContent()).isEqualTo("HEARTBEAT_OK");
        assertThat(reply.getRuntimeMetadata()).containsEntry("busy_status", "heartbeat");
        assertThat(env.agentRunControlService.isRunning(sourceKey)).isTrue();
        assertThat(env.agentRunRepository.findNextQueuedMessage(sourceKey, session.getSessionId()))
                .isNull();
        assertThat(env.agentRunRepository.findLatestPendingCommand(runId, "interrupt"))
                .isNull();
        assertThat(env.agentRunRepository.findRun(runId).getHeartbeatAt())
                .isGreaterThanOrEqualTo(beforeHeartbeat);
        assertThat(env.agentRunRepository.listEvents(runId))
                .anySatisfy(
                        event ->
                                assertThat(event.getEventType()).isEqualTo("run.heartbeat"));
        llm.releaseFirst();
        first.join(5000L);
    }

    private CommandService unsupportedCommandService() {
        return new CommandService() {
            @Override
            public boolean supports(String commandName) {
                return false;
            }

            @Override
            public GatewayReply handle(GatewayMessage message, String commandLine) {
                throw new UnsupportedOperationException("commands are not expected in this test");
            }
        };
    }

    private ConversationOrchestrator replyingOrchestrator(
            final SessionRecord session, final String content) {
        return new ConversationOrchestrator() {
            @Override
            public GatewayReply handleIncoming(GatewayMessage message) {
                GatewayReply reply = GatewayReply.ok(content);
                reply.setSessionId(session.getSessionId());
                return reply;
            }

            @Override
            public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
                return handleIncoming(syntheticMessage);
            }

            @Override
            public GatewayReply resumePending(String sourceKey) {
                return GatewayReply.ok(content);
            }
        };
    }

    private GatewayAuthorizationService allowAllAuthorization(TestEnvironment env) {
        return new GatewayAuthorizationService(env.gatewayPolicyRepository, env.appConfig) {
            @Override
            public GatewayReply preAuthorize(GatewayMessage message) {
                return null;
            }

            @Override
            public boolean isAuthorized(GatewayMessage message) {
                return true;
            }
        };
    }

    private SkillLearningService noopLearningService() {
        return new SkillLearningService() {
            @Override
            public void schedulePostReplyLearning(
                    SessionRecord session, GatewayMessage message, GatewayReply reply) {}
        };
    }

    private void bootstrapAdmin(TestEnvironment env) throws Exception {
        env.send("room", "user", "hello");
        env.send("room", "user", "/pairing claim-admin");
    }

    private Thread startAsync(final TestEnvironment env, final String text) {
        Thread thread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    env.gatewayService.handle(env.message("room", "user", text));
                                } catch (Exception ignored) {
                                }
                            }
                        },
                        "busy-policy-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private String readQueuedError(TestEnvironment env, String queueId) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select error from queued_run_messages where queue_id = ?");
            statement.setString(1, queueId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getString("error") : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private static class BlockingFirstLlmGateway extends FakeLlmGateway {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public com.jimuqu.solon.claw.core.model.LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects)
                throws Exception {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                releaseFirst.await(5L, TimeUnit.SECONDS);
                if (Thread.currentThread().isInterrupted()) {
                    throw new com.jimuqu.solon.claw.core.service.AgentRunCancelledException();
                }
            }
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }

        private boolean awaitFirst() throws InterruptedException {
            return firstEntered.await(5L, TimeUnit.SECONDS);
        }

        private void releaseFirst() {
            releaseFirst.countDown();
        }
    }
}
