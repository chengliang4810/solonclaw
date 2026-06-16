package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ProactiveCandidateRecord;
import com.jimuqu.solon.claw.core.model.ProactiveDecisionRecord;
import com.jimuqu.solon.claw.proactive.ProactiveDiagnosticsService;
import com.jimuqu.solon.claw.proactive.ProactiveRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteProactiveRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import com.jimuqu.solon.claw.web.DashboardLogsService;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

/** 主动协作 Dashboard 状态、诊断、配置和日志过滤测试。 */
public class ProactiveDashboardDiagnosticTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeActionableProactiveStatusAndDiagnostics() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        ProactiveRepository repository = new SqliteProactiveRepository(env.sqliteDatabase);
        long now = System.currentTimeMillis();
        repository.saveCandidate(candidate("candidate-1", now - 10_000L));
        repository.saveDecision(decision("decision-1", "SKIP", "缺少 home channel，无法主动联系。", now - 8_000L));

        ProactiveDiagnosticsService proactiveDiagnosticsService =
                new ProactiveDiagnosticsService(env.appConfig, repository);
        DashboardStatusService statusService =
                new DashboardStatusService(
                        env.appConfig,
                        env.sessionRepository,
                        env.deliveryService,
                        env.gatewayRuntimeRefreshService,
                        new com.jimuqu.solon.claw.support.update.AppVersionService(env.appConfig),
                        new com.jimuqu.solon.claw.support.update.AppUpdateService(
                                env.appConfig,
                                new com.jimuqu.solon.claw.support.update.AppVersionService(
                                        env.appConfig)),
                        new com.jimuqu.solon.claw.support.LlmProviderService(env.appConfig),
                        proactiveDiagnosticsService);
        DashboardDiagnosticsService diagnosticsService =
                new DashboardDiagnosticsService(
                        env.appConfig,
                        env.deliveryService,
                        new com.jimuqu.solon.claw.support.LlmProviderService(env.appConfig),
                        env.toolRegistry,
                        env.sessionRepository,
                        env.conversationOrchestrator,
                        null,
                        env.slashConfirmService,
                        env.commandService,
                        env.dangerousCommandApprovalService,
                        new com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService(env.appConfig),
                        null,
                        null,
                        null,
                        null,
                        env.agentRunRepository,
                        env.processRegistry,
                        env.gatewayRuntimeRefreshService,
                        proactiveDiagnosticsService);

        Map<String, Object> status = (Map<String, Object>) statusService.getStatus(true).get("proactive");
        Map<String, Object> diagnostics =
                (Map<String, Object>) diagnosticsService.diagnostics().get("proactive");

        assertThat(status)
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("pending_candidate_count", Integer.valueOf(1))
                .containsEntry("sent_today", Integer.valueOf(0))
                .containsEntry("last_skip_reason", "缺少 home channel，无法主动联系。");
        assertThat(status).containsKeys("interval_minutes", "last_tick_at", "last_sent_at", "home_channel_ready");
        assertThat(diagnostics)
                .containsEntry("scheduler_ran", Boolean.TRUE)
                .containsEntry("candidates_generated", Boolean.TRUE)
                .containsEntry("missing_home_channel", Boolean.TRUE);
        assertThat(String.valueOf(diagnostics.get("why_none_sent"))).contains("home channel");
        assertThat(ONode.serialize(diagnostics)).contains("缺少 home channel").doesNotContain("token=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeOnlySafeProactiveConfigFields() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        DashboardConfigService service =
                new DashboardConfigService(env.appConfig, env.gatewayRuntimeRefreshService);

        Map<String, Object> schema = service.getSchema();
        Map<String, Object> fields = (Map<String, Object>) schema.get("fields");

        assertThat(ONode.serialize(schema.get("category_order"))).contains("proactive");
        assertThat(fields)
                .containsKeys(
                        "proactive.enabled",
                        "proactive.intervalMinutes",
                        "proactive.dailyMaxContacts",
                        "proactive.cooldownMinutes",
                        "proactive.quietStartHour",
                        "proactive.quietEndHour",
                        "proactive.minConfidenceToContact",
                        "proactive.llmDecisionEnabled",
                        "proactive.llmPolishEnabled",
                        "proactive.maxCandidatesPerTick",
                        "proactive.maxContactsPerTick",
                        "proactive.careCheckinEnabled",
                        "proactive.deliveryPreviewPrefix");
        assertThat(fields.keySet())
                .filteredOn(key -> String.valueOf(key).startsWith("proactive."))
                .noneMatch(key -> String.valueOf(key).contains("prompt"));
    }

    @Test
    void shouldFilterProactiveComponentLogs() throws Exception {
        AppConfig config = new AppConfig();
        File logsDir = new File("target/proactive-dashboard-logs").getAbsoluteFile();
        FileUtil.del(logsDir);
        FileUtil.mkdir(logsDir);
        config.getRuntime().setLogsDir(logsDir.getAbsolutePath());
        File agentLog = new File(logsDir, "agent.log");
        FileUtil.writeUtf8String(
                "2026-06-16 INFO com.jimuqu.solon.claw.proactive.ProactiveScheduler - tick ok\n"
                        + "2026-06-16 INFO com.jimuqu.solon.claw.gateway.GatewayService - gateway ok\n",
                agentLog);

        DashboardLogsService service = new DashboardLogsService(config);

        List<String> lines = service.read("agent", 10, "ALL", "proactive");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains(".proactive.");
    }

    private ProactiveCandidateRecord candidate(String candidateId, long createdAt) {
        ProactiveCandidateRecord candidate = new ProactiveCandidateRecord();
        candidate.setCandidateId(candidateId);
        candidate.setSourceType("session_continuation");
        candidate.setSourceRef("session-a");
        candidate.setSourceKey("MEMORY:chat:user");
        candidate.setSubjectType("session");
        candidate.setSubjectRef("session-a");
        candidate.setTopic("work_continuation");
        candidate.setTitle("继续处理未完成工作");
        candidate.setSummary("上次任务还在等待确认。");
        candidate.setReason("最近会话显示还有可继续的工作。");
        candidate.setActionOffer("要不要我继续整理下一步？");
        candidate.setConfidence(0.9D);
        candidate.setPriority(80);
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
        record.setTickId("tick-1");
        record.setCandidateId("candidate-1");
        record.setSourceKey("MEMORY:chat:user");
        record.setDecision(decision);
        record.setReason(reason);
        record.setCreatedAt(createdAt);
        return record;
    }
}
