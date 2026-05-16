package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.CliRuntime;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.mcp.McpRuntimeService;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.storage.repository.SqliteCronJobRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tui.TuiEvent;
import com.jimuqu.solon.claw.tui.TuiExtensionProjector;
import com.jimuqu.solon.claw.web.DashboardCronService;
import com.jimuqu.solon.claw.web.DashboardKanbanService;
import com.jimuqu.solon.claw.web.DashboardMcpService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TuiExtensionProjectorTest {
    @Test
    void shouldProjectAutomationAndIntegrationSnapshots() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        CronJobService cronJobService =
                new CronJobService(env.appConfig, new SqliteCronJobRepository(env.sqliteDatabase));
        DashboardCronService cronService = new DashboardCronService(cronJobService, null);
        Map<String, Object> cronBody = new LinkedHashMap<String, Object>();
        cronBody.put("name", "日报");
        cronBody.put("schedule", "0 9 * * *");
        cronBody.put("prompt", "生成日报");
        cronService.create(cronBody);

        KanbanService kanbanCore =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService kanbanService = new DashboardKanbanService(kanbanCore);
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("title", "补齐终端状态");
        task.put("assignee", "local");
        Map<String, Object> created = kanbanService.createTask(task);
        kanbanService.status(String.valueOf(created.get("task_id")), body("status", "ready"));

        env.appConfig.getMcp().setEnabled(true);
        DashboardMcpService mcpService =
                new DashboardMcpService(
                        env.appConfig,
                        env.sqliteDatabase,
                        null,
                        new McpRuntimeService(env.appConfig, env.sqliteDatabase));

        TuiExtensionProjector projector =
                new TuiExtensionProjector(
                        cronService,
                        kanbanService,
                        mcpService,
                        new CliRuntime(env.commandService, env.conversationOrchestrator, env.agentRunControlService),
                        env.sessionRepository,
                        env.dangerousCommandApprovalService,
                        env.appConfig);

        Map<String, Object> snapshot = projector.snapshot();
        List<TuiEvent> events = projector.snapshotEvents("session-1");

        assertThat(snapshot).containsKeys("cron", "kanban", "mcp", "acp");
        assertThat(map(snapshot.get("cron")).get("status")).isIn("active", "due", "idle");
        assertThat(map(snapshot.get("kanban")).get("summary").toString()).contains("就绪");
        assertThat(map(snapshot.get("mcp")).get("summary").toString()).contains("服务");
        assertThat(map(snapshot.get("acp")).get("status")).isEqualTo("ready");
        assertThat(events)
                .extracting(TuiEvent::getType)
                .containsExactly("cron.snapshot", "kanban.snapshot", "mcp.snapshot", "acp.snapshot");
    }

    private Map<String, Object> body(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put(key, value);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
