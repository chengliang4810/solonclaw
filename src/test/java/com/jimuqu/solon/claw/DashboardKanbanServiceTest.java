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

    private String createTask(KanbanService kanbanService) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "home channel 通知任务");
        body.put("assignee", "worker");
        body.put("created_by", "planner");
        return String.valueOf(kanbanService.createTask(body).get("id"));
    }
}
