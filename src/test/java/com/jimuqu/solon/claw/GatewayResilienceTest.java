package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunControlCommand;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.CommandService;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.core.service.SkillLearningService;
import com.jimuqu.solon.claw.gateway.authorization.GatewayAuthorizationService;
import com.jimuqu.solon.claw.gateway.service.DefaultGatewayService;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import com.jimuqu.solon.claw.support.TestEnvironment;
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
        assertThat(queued.getBusyPolicy()).isEqualTo("queue");
        assertThat(env.agentRunRepository.findRun(queued.getRunId()).getBusyPolicy())
                .isEqualTo("queue");
        llm.releaseFirst();
        first.join(5000L);
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
