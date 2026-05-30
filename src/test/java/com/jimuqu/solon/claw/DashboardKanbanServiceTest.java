package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.scheduler.KanbanNotificationScheduler;
import com.jimuqu.solon.claw.storage.repository.SqliteGatewayPolicyRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.web.DashboardKanbanService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DashboardKanbanServiceTest {
    @Test
    void shouldSubscribeKanbanTaskToConfiguredHomeChannel() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        SqliteGatewayPolicyRepository gatewayRepository =
                new SqliteGatewayPolicyRepository(env.sqliteDatabase);
        DashboardKanbanService dashboardKanbanService =
                new DashboardKanbanService(kanbanService, gatewayRepository);

        HomeChannelRecord home = new HomeChannelRecord();
        home.setPlatform(PlatformType.FEISHU);
        home.setChatId("home-chat");
        home.setThreadId("topic-1");
        home.setChatName("看板群");
        home.setUpdatedAt(System.currentTimeMillis());
        gatewayRepository.saveHomeChannel(home);

        String taskId = createTask(kanbanService);
        assertThat(String.valueOf(dashboardKanbanService.notifyHomeChannels(taskId)))
                .contains("feishu")
                .contains("subscribed=false");

        dashboardKanbanService.notifyHomeSubscribe(taskId, "feishu");
        dashboardKanbanService.notifyHomeSubscribe(taskId, "feishu");

        assertThat(kanbanService.notifyList(taskId)).hasSize(1);
        assertThat(String.valueOf(dashboardKanbanService.notifyHomeChannels(taskId)))
                .contains("subscribed=true")
                .contains("home-chat")
                .contains("topic-1");

        assertThat(dashboardKanbanService.notifyHomeUnsubscribe(taskId, "feishu").get("removed"))
                .isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(dashboardKanbanService.notifyHomeChannels(taskId)))
                .contains("subscribed=false");
    }

    @Test
    void shouldExposeNotificationDeliverySchedulerStatus() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService withoutScheduler = new DashboardKanbanService(kanbanService);

        assertThat(withoutScheduler.notifyDeliveryStatus().get("available"))
                .isEqualTo(Boolean.FALSE);

        KanbanNotificationScheduler scheduler = new KanbanNotificationScheduler(env.appConfig, null);
        DashboardKanbanService withScheduler =
                new DashboardKanbanService(kanbanService, null, scheduler);

        assertThat(withScheduler.notifyDeliveryStatus().get("available"))
                .isEqualTo(Boolean.TRUE);
        assertThat(withScheduler.notifyDeliveryStatus().get("enabled"))
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    void shouldRejectDashboardDirectRunningStatus() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String taskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("status", "running");

        assertThatThrownBy(() -> dashboardKanbanService.status(taskId, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("running");

        Map<String, Object> task = kanbanService.task(taskId);
        assertThat(task.get("status")).isEqualTo("ready");
        assertThat(task.get("current_run_id")).isNull();
    }

    @Test
    void shouldRejectDashboardEditRunningStatus() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String taskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "renamed by dashboard");
        body.put("status", "running");

        assertThatThrownBy(() -> dashboardKanbanService.updateTask(taskId, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("running");

        Map<String, Object> task = kanbanService.task(taskId);
        assertThat(task.get("status")).isEqualTo("ready");
        assertThat(task.get("title")).isEqualTo("home channel 通知任务");
        assertThat(task.get("current_run_id")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBulkUpdateDashboardTaskStatusAndKeepPartialFailuresIsolated() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String firstTaskId = createTask(kanbanService);
        String secondTaskId = createTask(kanbanService);
        kanbanService.status(firstTaskId, "blocked", "等待批量恢复");
        kanbanService.status(secondTaskId, "blocked", "等待批量恢复");

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ids", java.util.Arrays.asList(firstTaskId, "KB-NOTFOUND", secondTaskId));
        body.put("status", "ready");

        Map<String, Object> result = dashboardKanbanService.bulkTasks(body);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("id")).isEqualTo(firstTaskId);
        assertThat(results.get(0).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(results.get(1).get("id")).isEqualTo("KB-NOTFOUND");
        assertThat(results.get(1).get("ok")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(results.get(1).get("error"))).contains("not found");
        assertThat(results.get(2).get("id")).isEqualTo(secondTaskId);
        assertThat(results.get(2).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(kanbanService.task(firstTaskId).get("status")).isEqualTo("ready");
        assertThat(kanbanService.task(secondTaskId).get("status")).isEqualTo("ready");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectDashboardBulkRunningStatusPerTask() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String taskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ids", java.util.Collections.singletonList(taskId));
        body.put("status", "running");

        Map<String, Object> result = dashboardKanbanService.bulkTasks(body);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("id")).isEqualTo(taskId);
        assertThat(results.get(0).get("ok")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(results.get(0).get("error"))).contains("running");
        assertThat(kanbanService.task(taskId).get("status")).isEqualTo("ready");
        assertThat(kanbanService.task(taskId).get("current_run_id")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBulkUpdateDashboardTaskPriorityAndKeepPartialFailuresIsolated() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String firstTaskId = createTask(kanbanService);
        String secondTaskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ids", java.util.Arrays.asList(firstTaskId, "KB-NOTFOUND", secondTaskId));
        body.put("priority", Integer.valueOf(7));

        Map<String, Object> result = dashboardKanbanService.bulkTasks(body);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("id")).isEqualTo(firstTaskId);
        assertThat(results.get(0).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(results.get(1).get("id")).isEqualTo("KB-NOTFOUND");
        assertThat(results.get(1).get("ok")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(results.get(1).get("error"))).contains("not found");
        assertThat(results.get(2).get("id")).isEqualTo(secondTaskId);
        assertThat(results.get(2).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(kanbanService.task(firstTaskId).get("priority")).isEqualTo(7);
        assertThat(kanbanService.task(secondTaskId).get("priority")).isEqualTo(7);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBulkReassignDashboardTasksAndKeepPartialFailuresIsolated() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String firstTaskId = createTask(kanbanService);
        String secondTaskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ids", java.util.Arrays.asList(firstTaskId, "KB-NOTFOUND", secondTaskId));
        body.put("assignee", "reviewer");

        Map<String, Object> result = dashboardKanbanService.bulkTasks(body);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(results.get(1).get("id")).isEqualTo("KB-NOTFOUND");
        assertThat(results.get(1).get("ok")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(results.get(1).get("error"))).contains("not found");
        assertThat(results.get(2).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(kanbanService.task(firstTaskId).get("assignee")).isEqualTo("reviewer");
        assertThat(kanbanService.task(secondTaskId).get("assignee")).isEqualTo("reviewer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBulkUnassignDashboardTasksWithBlankAssignee() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String taskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ids", java.util.Collections.singletonList(taskId));
        body.put("assignee", "");

        Map<String, Object> result = dashboardKanbanService.bulkTasks(body);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("id")).isEqualTo(taskId);
        assertThat(results.get(0).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(kanbanService.task(taskId).get("assignee")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBulkArchiveDashboardTasksAndKeepPartialFailuresIsolated() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService kanbanService =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        DashboardKanbanService dashboardKanbanService = new DashboardKanbanService(kanbanService);
        String firstTaskId = createTask(kanbanService);
        String secondTaskId = createTask(kanbanService);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ids", java.util.Arrays.asList(firstTaskId, "KB-NOTFOUND", secondTaskId));
        body.put("archive", Boolean.TRUE);

        Map<String, Object> result = dashboardKanbanService.bulkTasks(body);
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(results.get(1).get("id")).isEqualTo("KB-NOTFOUND");
        assertThat(results.get(1).get("ok")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(results.get(1).get("error"))).contains("not found");
        assertThat(results.get(2).get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(kanbanService.task(firstTaskId).get("status")).isEqualTo("archived");
        assertThat(kanbanService.task(secondTaskId).get("status")).isEqualTo("archived");
        assertThat(String.valueOf(kanbanService.tasks(null, null, false))).doesNotContain(firstTaskId, secondTaskId);
        assertThat(String.valueOf(kanbanService.tasks(null, null, true))).contains(firstTaskId, secondTaskId);
    }

    private String createTask(KanbanService kanbanService) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "home channel 通知任务");
        body.put("assignee", "worker");
        body.put("created_by", "planner");
        return String.valueOf(kanbanService.createTask(body).get("id"));
    }
}
