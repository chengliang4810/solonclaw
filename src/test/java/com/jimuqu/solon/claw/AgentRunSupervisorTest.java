package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunEventRecord;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.ContextBudgetDecision;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.model.QueuedRunMessage;
import com.jimuqu.solon.claw.core.model.RunBusyDecision;
import com.jimuqu.solon.claw.core.model.RunRecoveryRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.model.SubagentRunRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ContextBudgetService;
import com.jimuqu.solon.claw.core.service.ContextCompressionService;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.engine.AgentRunSupervisor;
import com.jimuqu.solon.claw.gateway.feedback.ConversationFeedbackSink;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentRunRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.storage.session.SqliteAgentSession;
import com.jimuqu.solon.claw.support.BlockingLlmGateway;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.MessageSupport;
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
import java.util.concurrent.atomic.AtomicReference;
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
                        false,
                        null,
                        Collections.emptyList(),
                        null);

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

    /** 限流时立即切换备用模型，并从已写入的工具结果继续而不是重放整轮。 */
    @Test
    void shouldFallbackImmediatelyAndResumeAfterToolActivity() throws Exception {
        Fixture fixture = fixture();
        fixture.config.getTrace().setMaxAttempts(4);
        SnapshotFailoverGateway gateway = new SnapshotFailoverGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:fallback:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "create file",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        null,
                        Collections.emptyList(),
                        null);

        assertThat(outcome.getFinalReply()).isEqualTo("backup continued");
        assertThat(gateway.attempts)
                .containsExactly("primary:gpt-5-mini", "backup:claude-sonnet-4");
        assertThat(gateway.resumeFlags).containsExactly(false, true);
        assertThat(MessageSupport.loadMessages(outcome.getResult().getNdjson()))
                .filteredOn(message -> message.getRole() == org.noear.solon.ai.chat.ChatRole.TOOL)
                .hasSize(1);
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
                        false,
                        null,
                        Collections.emptyList(),
                        null);

        assertThat(outcome.getRunRecord().getStatus()).isEqualTo("success");
        assertThat(compressionService.compressCount).isEqualTo(2);
        List<AgentRunEventRecord> events =
                fixture.agentRunRepository.listEvents(outcome.getRunRecord().getRunId());
        assertThat(eventTypes(events)).contains("compression.unchanged");
    }

    @Test
    void shouldKeepCompressionCountWhenCompressedRunCompletes() throws Exception {
        Fixture fixture = fixture();
        SuccessfulGateway gateway = new SuccessfulGateway("compressed ok");
        MutatingCompressionService compressionService = new MutatingCompressionService();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, compressingBudget(), compressionService);
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:compressed:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "hello",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        null,
                        Collections.emptyList(),
                        null);

        AgentRunRecord persisted =
                fixture.agentRunRepository.findRun(outcome.getRunRecord().getRunId());

        assertThat(outcome.getRunRecord().getCompressionCount()).isEqualTo(1);
        assertThat(persisted.getCompressionCount()).isEqualTo(1);
        assertThat(compressionService.compressCount).isEqualTo(1);
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(persisted.getRunId())))
                .contains("compression.done");
    }

    /** 上下文溢出后应先强制压缩一次，再由同一提供方重试。 */
    @Test
    void shouldCompressOnceBeforeRetryingContextOverflowOnSameProvider() throws Exception {
        Fixture fixture = fixture();
        fixture.config.getTrace().setMaxAttempts(3);
        ContextOverflowRetryGateway gateway = new ContextOverflowRetryGateway();
        CountingCompressionService compressionService = new CountingCompressionService();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), compressionService);
        SessionRecord session =
                fixture.sessionRepository.bindNewSession("MEMORY:context-overflow:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "hello",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        null,
                        Collections.emptyList(),
                        null);

        assertThat(outcome.getFinalReply()).isEqualTo("retry ok");
        assertThat(gateway.attempts).containsExactly("primary:gpt-5-mini", "primary:gpt-5-mini");
        assertThat(compressionService.compressCount).isEqualTo(1);
        assertThat(outcome.getRunRecord().getCompressionCount()).isEqualTo(1);
        assertThat(
                        eventTypes(
                                fixture.agentRunRepository.listEvents(
                                        outcome.getRunRecord().getRunId())))
                .contains("compression.retry.unchanged");
    }

    /** 413 且携带附件时优先移除附件后重试，避免无效重复压缩。 */
    @Test
    void shouldUnloadAttachmentsOnceBeforeRetryingPayloadTooLargeOnSameProvider() throws Exception {
        Fixture fixture = fixture();
        fixture.config.getTrace().setMaxAttempts(3);
        PayloadTooLargeRetryGateway gateway = new PayloadTooLargeRetryGateway();
        CountingCompressionService compressionService = new CountingCompressionService();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), compressionService);
        SessionRecord session =
                fixture.sessionRepository.bindNewSession("MEMORY:payload-too-large:user");
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("image");
        attachment.setMimeType("image/png");
        attachment.setData("iVBORw0KGgo=");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "describe image",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        null,
                        Collections.singletonList(attachment),
                        null);

        assertThat(outcome.getFinalReply()).isEqualTo("payload retry ok");
        assertThat(gateway.attachmentCounts)
                .containsExactly(Integer.valueOf(1), Integer.valueOf(0));
        assertThat(compressionService.compressCount).isEqualTo(0);
        assertThat(
                        eventTypes(
                                fixture.agentRunRepository.listEvents(
                                        outcome.getRunRecord().getRunId())))
                .contains("attachment.retry.unloaded");
    }

    @Test
    void shouldRedactSubagentAndRecoveryRecordsBeforeStorage() throws Exception {
        Fixture fixture = fixture();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-record-redaction-1");
        run.setSessionId("session-record-redaction-1");
        run.setSourceKey("MEMORY:record-redaction:user");
        run.setStatus("success");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        fixture.agentRunRepository.saveRun(run);

        SubagentRunRecord subagent = new SubagentRunRecord();
        subagent.setSubagentId("subagent-record-redaction-1");
        subagent.setParentRunId(run.getRunId());
        subagent.setSessionId(run.getSessionId());
        subagent.setName("delegate");
        subagent.setStatus("failed");
        subagent.setGoalPreview("inspect token=ghp_subagentrecord12345");
        subagent.setOutputTailJson(
                "[{\"preview\":\"Authorization: Bearer ghp_subagenttail12345\"}]");
        subagent.setError("password=subagent-error-secret");
        subagent.setStartedAt(System.currentTimeMillis());
        fixture.agentRunRepository.saveSubagentRun(subagent);

        RunRecoveryRecord recovery = new RunRecoveryRecord();
        recovery.setRecoveryId("recovery-record-redaction-1");
        recovery.setRunId(run.getRunId());
        recovery.setSessionId(run.getSessionId());
        recovery.setSourceKey(run.getSourceKey());
        recovery.setRecoveryType("manual");
        recovery.setStatus("recoverable");
        recovery.setSummary("recover token=ghp_recoverysummary12345");
        recovery.setPayloadJson("{\"api_key\":\"sk-recoverypayload-secret\"}");
        recovery.setCreatedAt(System.currentTimeMillis());
        fixture.agentRunRepository.saveRecovery(recovery);

        List<SubagentRunRecord> subagents =
                fixture.agentRunRepository.listSubagents(run.getRunId());
        assertThat(subagents).hasSize(1);
        String subagentPayload =
                subagents.get(0).getGoalPreview()
                        + "\n"
                        + subagents.get(0).getOutputTailJson()
                        + "\n"
                        + subagents.get(0).getError();
        assertThat(subagentPayload)
                .contains("token=***")
                .contains("Bearer ***")
                .contains("password=***")
                .doesNotContain("ghp_subagentrecord12345")
                .doesNotContain("ghp_subagenttail12345")
                .doesNotContain("subagent-error-secret");

        List<RunRecoveryRecord> recoveries =
                fixture.agentRunRepository.listRecoveries(run.getRunId());
        assertThat(recoveries).hasSize(1);
        String recoveryPayload =
                recoveries.get(0).getSummary() + "\n" + recoveries.get(0).getPayloadJson();
        assertThat(recoveryPayload)
                .contains("token=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_recoverysummary12345")
                .doesNotContain("sk-recoverypayload-secret");
    }

    @Test
    void shouldRedactRunPreviewAndErrorFieldsBeforeStorage() throws Exception {
        Fixture fixture = fixture();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-main-redaction-1");
        run.setSessionId("session-main-redaction-1");
        run.setSourceKey("MEMORY:main-redaction:user");
        run.setStatus("failed");
        run.setInputPreview("prompt api_key=sk-runinput-secret12345");
        run.setFinalReplyPreview("reply token=ghp_runreply12345");
        run.setRecoveryHint("retry password=run-recovery-secret");
        run.setError("failed Authorization: Bearer ghp_runerror12345");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());

        fixture.agentRunRepository.saveRun(run);

        AgentRunRecord stored = fixture.agentRunRepository.findRun(run.getRunId());
        String payload =
                stored.getInputPreview()
                        + "\n"
                        + stored.getFinalReplyPreview()
                        + "\n"
                        + stored.getRecoveryHint()
                        + "\n"
                        + stored.getError();
        assertThat(payload)
                .contains("api_key=***")
                .contains("token=***")
                .contains("password=***")
                .contains("Bearer ***")
                .doesNotContain("sk-runinput-secret12345")
                .doesNotContain("ghp_runreply12345")
                .doesNotContain("run-recovery-secret")
                .doesNotContain("ghp_runerror12345");
    }

    @Test
    void shouldRedactAgentRunEventAuditFieldsBeforeStorage() throws Exception {
        Fixture fixture = fixture();
        AgentRunRecord run = new AgentRunRecord();
        run.setRunId("run-event-redaction-1");
        run.setSessionId("session-event-redaction-1");
        run.setSourceKey("MEMORY:event-redaction:user");
        run.setStatus("running");
        run.setStartedAt(System.currentTimeMillis());
        run.setLastActivityAt(run.getStartedAt());
        fixture.agentRunRepository.saveRun(run);

        AgentRunEventRecord event = new AgentRunEventRecord();
        event.setEventId("event-redaction-1");
        event.setRunId(run.getRunId());
        event.setSessionId(run.getSessionId());
        event.setSourceKey(run.getSourceKey());
        event.setEventType("audit.secret");
        event.setSeverity("info");
        event.setSummary("summary Authorization: Bearer ghp_eventsummary12345");
        event.setMetadataJson("{\"api_key\":\"sk-event-metadata-secret12345\"}");
        event.setCreatedAt(System.currentTimeMillis());
        fixture.agentRunRepository.appendEvent(event);

        List<AgentRunEventRecord> events = fixture.agentRunRepository.listEvents(run.getRunId());
        assertThat(events).hasSize(1);
        String payload = events.get(0).getSummary() + "\n" + events.get(0).getMetadataJson();
        assertThat(payload)
                .contains("Authorization: Bearer ***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_eventsummary12345")
                .doesNotContain("sk-event-metadata-secret12345");
    }

    @Test
    void shouldRedactRunFailureEventsAndRecords() throws Exception {
        Fixture fixture = fixture();
        String leakedToken = "sk-supervisor12345";
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new ThrowingGateway(leakedToken),
                        noCompressionBudget(),
                        noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        try {
            supervisor.run(
                    session,
                    "system",
                    "hello",
                    Collections.emptyList(),
                    ConversationFeedbackSink.noop(),
                    ConversationEventSink.noop(),
                    false,
                    null,
                    Collections.emptyList(),
                    null);
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
                    || (event.getMetadataJson() != null
                            && event.getMetadataJson().contains("***"))) {
                sawRedactedEvent = true;
            }
        }
        assertThat(sawRedactedEvent).isTrue();
    }

    @Test
    void shouldFailRunWhenRequiredToolsWereNotActuallyCompleted() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new SuccessfulGateway("我已经调用 session_search 并完成验证"),
                        noCompressionBudget(),
                        noCompressionService());
        SessionRecord session =
                fixture.sessionRepository.bindNewSession("MEMORY:required-tool:user");

        boolean failed = false;
        try {
            supervisor.run(
                    session,
                    "system",
                    "必须调用 session_search",
                    Collections.emptyList(),
                    ConversationFeedbackSink.noop(),
                    ConversationEventSink.noop(),
                    false,
                    null,
                    Collections.emptyList(),
                    null,
                    Collections.singletonList("session_search"),
                    Collections.singletonList("session_search"),
                    Integer.valueOf(1));
        } catch (Exception expected) {
            failed = true;
            assertThat(expected.getMessage()).contains("必需工具未真实完成");
        }
        assertThat(failed).isTrue();

        List<AgentRunRecord> runs =
                fixture.agentRunRepository.listBySession(session.getSessionId(), 10);
        assertThat(runs).hasSize(1);
        AgentRunRecord run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getError()).contains("session_search");
        assertThat(run.getToolCallCount()).isEqualTo(0);
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(run.getRunId())))
                .contains("tool.required.missing", "attempt.error", "run.failed");
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(run.getRunId())))
                .doesNotContain("fallback");
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
                    false,
                    null,
                    Collections.emptyList(),
                    null);
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
        BlockingLlmGateway gateway = new BlockingLlmGateway();
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
                                        false,
                                        null,
                                        Collections.emptyList(),
                                        null));

        assertThat(gateway.awaitStarted(2, TimeUnit.SECONDS)).isTrue();
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
        assertThat(gateway.isInterrupted()).isTrue();
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
                        false,
                        null,
                        Collections.emptyList(),
                        null);

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
                        false,
                        null,
                        Collections.emptyList(),
                        null);

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

    @Test
    void shouldNotDenyCompletedToolResultDuringMaxStepsRecovery() throws Exception {
        Fixture fixture = fixture();
        RecoveryTranscriptGateway gateway =
                new RecoveryTranscriptGateway(
                        ChatMessage.toNdjson(
                                java.util.Arrays.asList(
                                        ChatMessage.ofUser("创建一次性提醒"),
                                        ChatMessage.ofTool(
                                                "{\"success\":true,\"job_id\":\"job_123\",\"next_run_at\":\"2026-06-15T02:13:43+08:00\",\"deliver\":\"origin\"}",
                                                "cronjob",
                                                "call_cron_1"),
                                        ChatMessage.ofAssistant(
                                                "Agent error: Maximum steps reached (12)."))),
                        "抱歉，任务尚未完成。我没有成功执行工具调用。");
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:room:user");

        AgentRunOutcome outcome =
                supervisor.run(
                        session,
                        "system",
                        "创建一次性提醒",
                        Collections.emptyList(),
                        ConversationFeedbackSink.noop(),
                        ConversationEventSink.noop(),
                        false,
                        null,
                        Collections.emptyList(),
                        null);

        SessionRecord updated = fixture.sessionRepository.findById(session.getSessionId());
        String persisted = updated.getNdjson();
        assertThat(outcome.getFinalReply()).contains("已执行工具调用");
        assertThat(outcome.getFinalReply()).contains("cronjob");
        assertThat(outcome.getFinalReply()).contains("job_123");
        assertThat(outcome.getFinalReply()).doesNotContain("没有成功执行工具");
        assertThat(persisted).contains("已执行工具调用");
        assertThat(persisted).contains("job_123");
        assertThat(persisted).doesNotContain("没有成功执行工具");
    }

    @Test
    void shouldMarkQueuedRunCompletedWhenBusyQueueDrains() throws Exception {
        Fixture fixture = fixture();
        RecordingGateway gateway = new RecordingGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        String sourceKey = "MEMORY:queue-room:queue-user";
        SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
        GatewayMessage message =
                new GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                        "queue-room",
                        "queue-user",
                        "queued task");

        RunBusyDecision decision =
                supervisor.queueIncoming(sourceKey, session.getSessionId(), message);
        QueuedRunMessage queued =
                fixture.agentRunRepository.findNextQueuedMessage(sourceKey, session.getSessionId());
        CountDownLatch drained = new CountDownLatch(1);

        supervisor.onRunFinished(
                sourceKey,
                session.getSessionId(),
                queuedMessage -> {
                    drained.countDown();
                    return GatewayReply.ok("queued done");
                });

        assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100L);
        AgentRunRecord run = fixture.agentRunRepository.findRun(decision.getRunId());
        QueuedRunMessage remaining =
                fixture.agentRunRepository.findNextQueuedMessage(sourceKey, session.getSessionId());

        assertThat(queued).isNotNull();
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo("success");
        assertThat(run.getPhase()).isEqualTo("completed");
        assertThat(run.getExitReason()).isEqualTo("success");
        assertThat(run.getFinalReplyPreview()).isEqualTo("queued done");
        assertThat(run.getFinishedAt()).isGreaterThan(0L);
        assertThat(remaining).isNull();
        assertThat(eventTypes(fixture.agentRunRepository.listEvents(run.getRunId())))
                .contains("run.queue.start", "run.queue.success");
    }

    /** reservation 前置失败释放后必须唤醒队列，且 drain 运行期间持续持有同来源占位。 */
    @Test
    void shouldDrainQueuedMessageUnderSourceReservationAfterReservationRelease() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new RecordingGateway(),
                        noCompressionBudget(),
                        noCompressionService());
        String sourceKey = "MEMORY:reservation-drain:user";
        SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
        GatewayMessage first =
                new GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                        "reservation-drain",
                        "user",
                        "first");
        GatewayMessage queued =
                new GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                        "reservation-drain",
                        "user",
                        "queued");
        assertThat(
                        supervisor
                                .coordinateIncoming(sourceKey, session.getSessionId(), first)
                                .isShouldRunNow())
                .isTrue();
        supervisor.queueIncoming(sourceKey, session.getSessionId(), queued);

        assertThat(supervisor.releaseIncomingReservation(sourceKey)).isTrue();
        CountDownLatch drained = new CountDownLatch(1);
        AtomicReference<Boolean> reservationObserved = new AtomicReference<Boolean>(false);
        supervisor.onRunFinished(
                sourceKey,
                session.getSessionId(),
                message -> {
                    reservationObserved.set(Boolean.valueOf(supervisor.isRunning(sourceKey)));
                    drained.countDown();
                    return GatewayReply.ok("done");
                });

        assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(reservationObserved.get()).isTrue();
        assertThat(
                        fixture.agentRunRepository.findNextQueuedMessage(
                                sourceKey, session.getSessionId()))
                .isNull();
    }

    /** stale 恢复只把 conversation/resume 的精确会话标为可续跑，排除 queued 与审批态。 */
    @Test
    void shouldMarkOnlyRunnableStaleSessionsPending() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new RecordingGateway(),
                        noCompressionBudget(),
                        noCompressionService());
        long staleAt = System.currentTimeMillis() - 120_000L;
        String[] kinds = {"conversation", "resume", "conversation", "conversation"};
        String[] statuses = {"running", "interrupting", "queued", "waiting_approval"};
        List<SessionRecord> sessions = new ArrayList<SessionRecord>();
        for (int index = 0; index < kinds.length; index++) {
            String sourceKey = "MEMORY:stale-kind-" + index + ":user";
            SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
            sessions.add(session);
            AgentRunRecord run = new AgentRunRecord();
            run.setRunId("stale-kind-run-" + index);
            run.setSessionId(session.getSessionId());
            run.setSourceKey(sourceKey);
            run.setRunKind(kinds[index]);
            run.setStatus(statuses[index]);
            run.setStartedAt(staleAt);
            run.setLastActivityAt(staleAt);
            fixture.agentRunRepository.saveRun(run);
        }

        supervisor.recoverStaleRuns(60_000L);

        assertThat(
                        new SqliteAgentSession(
                                        fixture.sessionRepository.findById(
                                                sessions.get(0).getSessionId()))
                                .getPendingReason())
                .isEqualTo("restart_interrupted");
        assertThat(
                        new SqliteAgentSession(
                                        fixture.sessionRepository.findById(
                                                sessions.get(1).getSessionId()))
                                .getPendingReason())
                .isEqualTo("restart_interrupted");
        assertThat(
                        new SqliteAgentSession(
                                        fixture.sessionRepository.findById(
                                                sessions.get(2).getSessionId()))
                                .isPending())
                .isFalse();
        assertThat(
                        new SqliteAgentSession(
                                        fixture.sessionRepository.findById(
                                                sessions.get(3).getSessionId()))
                                .isPending())
                .isFalse();
    }

    /** stale 恢复必须持续处理后续批次，不能把第 201 条以后永久留在活动态。 */
    @Test
    void shouldRecoverStaleRunsBeyondSingleBatch() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new RecordingGateway(),
                        noCompressionBudget(),
                        noCompressionService());
        long staleAt = System.currentTimeMillis() - 120_000L;
        for (int index = 0; index < 205; index++) {
            AgentRunRecord run = new AgentRunRecord();
            run.setRunId("stale-batch-" + index);
            run.setSessionId("stale-session-" + index);
            run.setSourceKey("MEMORY:stale-batch:" + index);
            run.setRunKind("scheduled");
            run.setStatus("running");
            run.setStartedAt(staleAt);
            run.setLastActivityAt(staleAt);
            fixture.agentRunRepository.saveRun(run);
        }

        supervisor.recoverStaleRuns(60_000L);

        assertThat(fixture.agentRunRepository.findRun("stale-batch-0").getStatus())
                .isEqualTo("recoverable");
        assertThat(fixture.agentRunRepository.findRun("stale-batch-204").getStatus())
                .isEqualTo("recoverable");
    }

    /** 队列领取与终态更新使用 CAS，启动恢复会把遗留 running 项退回 queued。 */
    @Test
    void shouldClaimAndRecoverQueuedMessageWithExpectedStatus() throws Exception {
        Fixture fixture = fixture();
        QueuedRunMessage queued = new QueuedRunMessage();
        queued.setQueueId("queue-cas");
        queued.setRunId("run-cas");
        queued.setSessionId("session-cas");
        queued.setSourceKey("MEMORY:queue-cas:user");
        queued.setMessageText("queued");
        queued.setMessageJson("{}");
        queued.setStatus("queued");
        queued.setBusyPolicy("queue");
        queued.setCreatedAt(System.currentTimeMillis() - 120_000L);
        fixture.agentRunRepository.saveQueuedMessage(queued);

        assertThat(
                        fixture.agentRunRepository.markQueuedMessage(
                                "queue-cas", "queued", "running", System.currentTimeMillis(), null))
                .isTrue();
        assertThat(
                        fixture.agentRunRepository.markQueuedMessage(
                                "queue-cas", "queued", "running", System.currentTimeMillis(), null))
                .isFalse();
        assertThat(
                        fixture.agentRunRepository.markQueuedMessage(
                                "queue-cas", "queued", "success", System.currentTimeMillis(), null))
                .isFalse();

        assertThat(
                        fixture.agentRunRepository.requeueStaleRunningMessages(
                                System.currentTimeMillis() + 1L))
                .isEqualTo(1);
        assertThat(
                        fixture.agentRunRepository.findNextQueuedMessage(
                                queued.getSourceKey(), queued.getSessionId()))
                .isNotNull();
    }

    @Test
    void shouldCarryProfileScopeIntoQueuedRunDrainThread() throws Exception {
        Fixture fixture = fixture();
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new RecordingGateway(),
                        noCompressionBudget(),
                        noCompressionService());
        String sourceKey = "MEMORY:profile-queue:profile-user";
        SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
        supervisor.queueIncoming(
                sourceKey,
                session.getSessionId(),
                new GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                        "profile-queue",
                        "profile-user",
                        "queued"));
        CountDownLatch drained = new CountDownLatch(1);
        AtomicReference<String> observed = new AtomicReference<String>();

        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a",
                        Files.createTempDirectory("supervisor-profile-a"),
                        Collections.singletonMap("PROFILE_ASYNC_MARKER", "env-a"),
                        null)) {
            supervisor.onRunFinished(
                    sourceKey,
                    session.getSessionId(),
                    message -> {
                        ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
                        observed.set(
                                (current == null ? "default" : current.getProfile())
                                        + ":"
                                        + ProfileRuntimeScope.environmentValue(
                                                "PROFILE_ASYNC_MARKER"));
                        drained.countDown();
                        return GatewayReply.ok("done");
                    });
        }

        assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(observed.get()).isEqualTo("a:env-a");
    }

    @Test
    void shouldUseProfileHomeWhenRunScopeHasNoWorkspace() throws Exception {
        Fixture fixture = fixture();
        fixture.config.getWorkspace().setDir("");
        AgentRunSupervisor supervisor =
                supervisor(
                        fixture,
                        new SuccessfulGateway("done"),
                        noCompressionBudget(),
                        noCompressionService());
        SessionRecord session = fixture.sessionRepository.bindNewSession("MEMORY:profile-cwd:user");
        java.nio.file.Path profileHome = Files.createTempDirectory("supervisor-profile-home");

        AgentRunOutcome outcome;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(
                        "a", profileHome, Collections.<String, String>emptyMap(), null)) {
            outcome =
                    supervisor.run(
                            session,
                            "system",
                            "hello",
                            Collections.emptyList(),
                            ConversationFeedbackSink.noop(),
                            ConversationEventSink.noop(),
                            false,
                            null,
                            Collections.emptyList(),
                            null);
        }

        assertThat(outcome.getCwd()).isEqualTo(profileHome.toAbsolutePath().normalize().toString());
    }

    @Test
    void hasPendingRealMessageReturnsFalseWhenQueueEmpty() throws Exception {
        // 队列为空 → 没有待处理真实消息
        Fixture fixture = fixture();
        RecordingGateway gateway = new RecordingGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        assertThat(supervisor.hasPendingRealMessage("MEMORY:empty:u")).isFalse();
    }

    @Test
    void hasPendingRealMessageReturnsTrueForRealQueuedMessage() throws Exception {
        // 真实用户消息入队 → 抢占成立返回 true
        Fixture fixture = fixture();
        RecordingGateway gateway = new RecordingGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        String sourceKey = "MEMORY:real:u";
        SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
        GatewayMessage message =
                new GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY,
                        "real",
                        "u",
                        "真实用户输入");
        supervisor.queueIncoming(sourceKey, session.getSessionId(), message);

        assertThat(supervisor.hasPendingRealMessage(sourceKey)).isTrue();
    }

    @Test
    void hasPendingRealMessageReturnsFalseForGoalContinuation() throws Exception {
        // goal 续轮合成消息（goalContinuation=true）入队 → 不算真实消息，抢占不成立
        Fixture fixture = fixture();
        RecordingGateway gateway = new RecordingGateway();
        AgentRunSupervisor supervisor =
                supervisor(fixture, gateway, noCompressionBudget(), noCompressionService());
        String sourceKey = "MEMORY:cont:u";
        SessionRecord session = fixture.sessionRepository.bindNewSession(sourceKey);
        GatewayMessage message =
                new GatewayMessage(
                        com.jimuqu.solon.claw.core.enums.PlatformType.MEMORY, "cont", "u", "续轮提示");
        message.setGoalContinuation(true);
        supervisor.queueIncoming(sourceKey, session.getSessionId(), message);

        // 序列化/反序列化后 goalContinuation 标志应存活（不再因 transient 丢失）
        QueuedRunMessage reloaded =
                fixture.agentRunRepository.findNextQueuedMessageBySourceKey(sourceKey);
        assertThat(reloaded).isNotNull();
        assertThat(supervisor.hasPendingRealMessage(sourceKey)).isFalse();
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
        File workspaceHome = Files.createTempDirectory("solonclaw-supervisor").toFile();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(
                        new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        writeProviderConfig(workspaceHome);
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

    /**
     * 写入测试专用工作区模型配置，避免全局 RuntimeConfigResolver 读取本机默认 workspace。
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
                                + "    baseUrl: https://api.openai.com\n"
                                + "    apiKey: primary-key\n"
                                + "    defaultModel: gpt-5-mini\n"
                                + "    dialect: openai-responses\n"
                                + "  backup:\n"
                                + "    name: Backup\n"
                                + "    baseUrl: https://api.anthropic.com\n"
                                + "    apiKey: backup-key\n"
                                + "    defaultModel: claude-sonnet-4\n"
                                + "    dialect: anthropic\n"
                                + "model:\n"
                                + "  providerKey: primary\n"
                                + "fallbackProviders:\n"
                                + "  - provider: backup\n"));
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

    /** 构造使用已解析模型配置的成功结果，保持 run supervisor 测试只关注运行状态。 */
    private static LlmResult resolvedResult(
            SessionRecord session, AppConfig.LlmConfig resolved, String reply) {
        LlmResult result = new LlmResult();
        result.setProvider(resolved.getProvider());
        result.setModel(resolved.getModel());
        result.setRawResponse("ok");
        result.setAssistantMessage(new AssistantMessage(reply));
        result.setNdjson(session.getNdjson());
        return result;
    }

    private static class Fixture {
        private AppConfig config;
        private SessionRepository sessionRepository;
        private AgentRunRepository agentRunRepository;
    }

    private abstract static class ExecuteOnceOnlyGateway implements LlmGateway {
        /** 当前测试桩只允许 supervisor 调用 executeOnce，避免误走旧 chat/resume 路径。 */
        @Override
        public LlmResult chat(
                SessionRecord session,
                String systemPrompt,
                String userMessage,
                List<Object> toolObjects) {
            throw new UnsupportedOperationException("executeOnce only");
        }

        @Override
        public LlmResult resume(
                SessionRecord session, String systemPrompt, List<Object> toolObjects) {
            throw new UnsupportedOperationException("executeOnce only");
        }
    }

    private static class RecordingGateway extends ExecuteOnceOnlyGateway {
        private final List<String> attempts = new ArrayList<String>();

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
            return resolvedResult(session, resolved, "backup ok");
        }
    }

    /** 模拟工具已执行后主提供方限流，备用提供方应继续现有快照。 */
    private static class SnapshotFailoverGateway extends ExecuteOnceOnlyGateway {
        /** 记录提供方执行顺序。 */
        private final List<String> attempts = new ArrayList<String>();

        /** 记录每次调用是否以恢复模式继续。 */
        private final List<Boolean> resumeFlags = new ArrayList<Boolean>();

        /** 主提供方写入工具结果后限流，备用提供方直接收敛答复。 */
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
            attempts.add(resolved.getProvider() + ":" + resolved.getModel());
            resumeFlags.add(Boolean.valueOf(resume));
            if ("primary".equals(resolved.getProvider())) {
                session.setNdjson(
                        ChatMessage.toNdjson(
                                java.util.Arrays.asList(
                                        ChatMessage.ofUser(userMessage),
                                        ChatMessage.ofAssistant("执行工具"),
                                        ChatMessage.ofTool("created", "write_file", "call_1"))));
                throw new IllegalStateException("HTTP 429 rate limit");
            }
            List<ChatMessage> messages = MessageSupport.loadMessages(session.getNdjson());
            messages.add(ChatMessage.ofAssistant("backup continued"));
            LlmResult result = new LlmResult();
            result.setProvider(resolved.getProvider());
            result.setModel(resolved.getModel());
            result.setAssistantMessage(new AssistantMessage("backup continued"));
            result.setNdjson(ChatMessage.toNdjson(messages));
            return result;
        }
    }

    private static class SuccessfulGateway extends ExecuteOnceOnlyGateway {
        private final String reply;

        private SuccessfulGateway(String reply) {
            this.reply = reply;
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
            return resolvedResult(session, resolved, reply);
        }
    }

    /** 首次上下文溢出、第二次成功的模型网关测试桩。 */
    private static class ContextOverflowRetryGateway extends ExecuteOnceOnlyGateway {
        private final List<String> attempts = new ArrayList<String>();

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
            if (attempts.size() == 1) {
                throw new IllegalStateException("HTTP 400 prompt exceeds max length");
            }
            return resolvedResult(session, resolved, "retry ok");
        }
    }

    /** 首次 413、移除附件后成功的模型网关测试桩。 */
    private static class PayloadTooLargeRetryGateway extends ExecuteOnceOnlyGateway {
        private final List<Integer> attachmentCounts = new ArrayList<Integer>();

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
            attachmentCounts.add(Integer.valueOf(runContext.getUserAttachments().size()));
            if (attachmentCounts.size() == 1) {
                throw new IllegalStateException("HTTP 413 payload too large");
            }
            return resolvedResult(session, resolved, "payload retry ok");
        }
    }

    private static class ThrowingGateway extends ExecuteOnceOnlyGateway {
        private final String leakedToken;

        private ThrowingGateway(String leakedToken) {
            this.leakedToken = leakedToken;
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

    private static class RecoveryTranscriptGateway extends ExecuteOnceOnlyGateway {
        private final List<String> prompts = new ArrayList<String>();
        private final String firstNdjson;
        private final String recoveredText;

        private RecoveryTranscriptGateway(String firstNdjson, String recoveredText) {
            this.firstNdjson = firstNdjson;
            this.recoveredText = recoveredText;
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

    private static class FailingRecoveryGateway extends ExecuteOnceOnlyGateway {
        private final String leakedToken;
        private int attempts;

        private FailingRecoveryGateway(String leakedToken) {
            this.leakedToken = leakedToken;
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

    private static class MutatingCompressionService implements ContextCompressionService {
        private int compressCount;

        @Override
        public SessionRecord compressIfNeeded(
                SessionRecord session, String systemPrompt, String userMessage) throws Exception {
            return mutate(session);
        }

        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt)
                throws Exception {
            return mutate(session);
        }

        @Override
        public SessionRecord compressNow(SessionRecord session, String systemPrompt, String focus)
                throws Exception {
            return mutate(session);
        }

        /**
         * 模拟一次真正改变会话历史的压缩，验证运行完成保存不会覆盖压缩次数。
         *
         * @param session 当前测试会话。
         * @return 返回已写入压缩摘要的会话。
         */
        private SessionRecord mutate(SessionRecord session) throws Exception {
            compressCount++;
            List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.ofAssistant("压缩摘要 #" + compressCount));
            session.setCompressedSummary("压缩摘要 #" + compressCount);
            session.setNdjson(MessageSupport.toNdjson(messages));
            session.setLastCompressionAt(System.currentTimeMillis());
            return session;
        }
    }
}
