package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.gateway.command.DefaultCommandService;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteProactiveRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 主动协作用户反馈 slash 命令测试。 */
public class ProactiveCommandTest {
    @Test
    void shouldRouteProactiveCommandAndReportStatus() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DefaultCommandService commandService = commandService(env);

        GatewayReply reply =
                commandService.handle(
                        env.message("room", "user", "/proactive status"), "/proactive status");

        assertThat(commandService.supports("proactive")).isTrue();
        assertThat(reply.getContent()).contains("主动协作").contains("待处理候选");
        assertThat(reply.getRuntimeMetadata()).containsEntry("command", "proactive");
    }

    @Test
    void shouldPauseResumeAndTuneProactiveRuntimeSettings() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DefaultCommandService commandService = commandService(env);
        GatewayMessage message = env.message("room", "user", "/proactive pause");

        GatewayReply pauseReply = commandService.handle(message, "/proactive pause");
        assertThat(pauseReply.getContent()).contains("已暂停");
        assertThat(env.globalSettingRepository.get("proactive.enabled")).isEqualTo("false");

        GatewayReply resumeReply = commandService.handle(message, "/proactive resume");
        assertThat(resumeReply.getContent()).contains("已恢复");
        assertThat(env.globalSettingRepository.get("proactive.enabled")).isEqualTo("true");

        int cooldownBeforeLess = env.appConfig.getProactive().getCooldownMinutes();
        GatewayReply lessReply = commandService.handle(message, "/proactive less");
        assertThat(lessReply.getContent()).contains("已降低主动联系频率");
        assertThat(Integer.parseInt(env.globalSettingRepository.get("proactive.cooldownMinutes")))
                .isGreaterThan(cooldownBeforeLess);

        int dailyMaxBeforeMore = env.appConfig.getProactive().getDailyMaxContacts();
        GatewayReply moreReply = commandService.handle(message, "/proactive more");
        assertThat(moreReply.getContent()).contains("已提高主动联系频率");
        assertThat(Integer.parseInt(env.globalSettingRepository.get("proactive.dailyMaxContacts")))
                .isGreaterThanOrEqualTo(dailyMaxBeforeMore);
    }

    @Test
    void shouldExplainLatestDecisionAndIgnoreCandidate() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();
        repository.saveCandidate(candidate("candidate-ignore", now));
        repository.saveDecision(decision("decision-why", "SKIP", "冷却窗口还没有结束。", now + 1L));
        DefaultCommandService commandService = commandService(env, repository);
        GatewayMessage message = env.message("room", "user", "/proactive why");

        GatewayReply whyReply = commandService.handle(message, "/proactive why");
        GatewayReply ignoreReply =
                commandService.handle(message, "/proactive ignore candidate-ignore");

        assertThat(whyReply.getContent()).contains("冷却窗口还没有结束");
        assertThat(ignoreReply.getContent()).contains("已忽略主动协作候选");
        assertThat(repository.listPendingCandidates(System.currentTimeMillis(), 10)).isEmpty();
    }

    private DefaultCommandService commandService(TestEnvironment env) {
        return commandService(env, new SqliteProactiveRepository(env.sqliteDatabase));
    }

    private DefaultCommandService commandService(
            TestEnvironment env, ProactiveRepository proactiveRepository) {
        return new DefaultCommandService(
                env.sessionRepository,
                env.toolRegistry,
                env.localSkillService,
                env.cronJobRepository,
                env.conversationOrchestrator,
                env.conversationOrchestrator == null ? null : sourceKey -> "",
                env.contextCompressionService,
                env.deliveryService,
                env.gatewayAuthorizationService,
                env.checkpointService,
                env.skillHubService,
                env.appConfig,
                env.globalSettingRepository,
                env.processRegistry,
                env.runtimeSettingsService,
                new com.jimuqu.solon.claw.support.DisplaySettingsService(
                        env.appConfig, env.globalSettingRepository),
                new com.jimuqu.solon.claw.support.update.AppUpdateService(
                        env.appConfig,
                        new com.jimuqu.solon.claw.support.update.AppVersionService(env.appConfig)),
                env.dangerousCommandApprovalService,
                env.agentRunControlService,
                env.agentProfileService,
                env.agentRunRepository,
                null,
                null,
                null,
                null,
                null,
                env.gatewayRestartCoordinator,
                env.slashConfirmService,
                null,
                null,
                null,
                null,
                null,
                new ProactiveDiagnosticsService(env.appConfig, proactiveRepository),
                proactiveRepository);
    }

    private ProactiveCandidateRecord candidate(String candidateId, long createdAt) {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId(candidateId);
        candidate.setSourceType("session_continuation");
        candidate.setSourceRef("session-a");
        candidate.setSourceKey("MEMORY:room:user");
        candidate.setSubjectType("session");
        candidate.setSubjectRef("session-a");
        candidate.setTopic("work_continuation");
        candidate.setTitle("继续未完成工作");
        candidate.setSummary("等待确认。");
        candidate.setReason("上次会话还有后续。");
        candidate.setActionOffer("要不要继续？");
        candidate.setEvidence(Map.<String, Object>of("session", "session-a"));
        candidate.setConfidence(0.9D);
        candidate.setPriority(70);
        candidate.setDedupKey("session:session-a");
        candidate.setStateHash("state-a");
        candidate.setCreatedAt(createdAt);
        candidate.setExpiresAt(createdAt + 60_000L);
        candidate.setStatus("PENDING");
        candidate.setUpdatedAt(createdAt);
        return candidate;
    }

    private ProactiveDecisionRecord decision(
            String decisionId, String decision, String reason, long createdAt) {
        ProactiveDecisionRecord record = new ProactiveDecisionRecord();
        record.setDecisionId(decisionId);
        record.setTickId("tick-why");
        record.setCandidateId("candidate-ignore");
        record.setSourceKey("MEMORY:room:user");
        record.setDecision(decision);
        record.setReason(reason);
        record.setCreatedAt(createdAt);
        return record;
    }
}
