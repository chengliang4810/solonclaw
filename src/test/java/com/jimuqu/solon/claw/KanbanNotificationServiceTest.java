package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.kanban.KanbanNotificationResult;
import com.jimuqu.solon.claw.kanban.KanbanNotificationService;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class KanbanNotificationServiceTest {
    @Test
    void shouldDeliverCompletedEventAndRemoveSubscription() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingDeliveryService deliveryService = new RecordingDeliveryService();
        KanbanNotificationService notifier = new KanbanNotificationService(repository, deliveryService);

        String taskId = createTask(service, "完成通知任务");
        subscribe(service, taskId);
        service.status(taskId, "done", "已完成", "已完成摘要", null);

        KanbanNotificationResult result = notifier.deliverPending();

        assertThat(result.getDeliveredEvents()).isEqualTo(1);
        assertThat(result.getRemovedSubscriptions()).isEqualTo(1);
        assertThat(deliveryService.requests).hasSize(1);
        assertThat(deliveryService.requests.get(0).getText())
                .contains(taskId)
                .contains("completed")
                .contains("已完成摘要");
        assertThat(service.notifyList(taskId)).isEmpty();
    }

    @Test
    void shouldKeepBlockedSubscriptionAndDeliverNextBlockedCycle() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingDeliveryService deliveryService = new RecordingDeliveryService();
        KanbanNotificationService notifier = new KanbanNotificationService(repository, deliveryService);

        String taskId = createTask(service, "阻塞通知任务");
        subscribe(service, taskId);
        service.status(taskId, "blocked", "first block", "first block", null);
        notifier.deliverPending();

        assertThat(service.notifyList(taskId)).hasSize(1);
        service.unblock(taskId);
        service.status(taskId, "blocked", "second block", "second block", null);
        KanbanNotificationResult result = notifier.deliverPending();

        assertThat(result.getDeliveredEvents()).isEqualTo(1);
        assertThat(deliveryService.requests).hasSize(2);
        assertThat(deliveryService.requests.get(0).getText()).contains("first block");
        assertThat(deliveryService.requests.get(1).getText()).contains("second block");
    }

    @Test
    void shouldRewindCursorWhenDeliveryFails() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingDeliveryService deliveryService = new RecordingDeliveryService();
        deliveryService.fail = true;
        KanbanNotificationService notifier = new KanbanNotificationService(repository, deliveryService);

        String taskId = createTask(service, "失败重投任务");
        subscribe(service, taskId);
        service.status(taskId, "blocked", "need help", "need help", null);

        KanbanNotificationResult failed = notifier.deliverPending();
        assertThat(failed.getFailedEvents()).isEqualTo(1);
        deliveryService.fail = false;
        KanbanNotificationResult retried = notifier.deliverPending();

        assertThat(retried.getDeliveredEvents()).isEqualTo(1);
        assertThat(deliveryService.requests).hasSize(1);
        assertThat(deliveryService.requests.get(0).getText()).contains("need help");
    }

    private String createTask(KanbanService service, String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        body.put("assignee", "worker");
        body.put("created_by", "planner");
        return String.valueOf(service.createTask(body).get("id"));
    }

    private void subscribe(KanbanService service, String taskId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("task_id", taskId);
        body.put("platform", "memory");
        body.put("chat_id", "chat-1");
        body.put("thread_id", "thread-1");
        body.put("user_id", "user-1");
        service.notifySubscribe(body);
    }

    private static class RecordingDeliveryService implements DeliveryService {
        private final List<DeliveryRequest> requests = new ArrayList<DeliveryRequest>();
        private boolean fail;

        @Override
        public void deliver(DeliveryRequest request) throws Exception {
            if (fail) {
                throw new IllegalStateException("delivery failed");
            }
            requests.add(request);
        }

        @Override
        public List<ChannelStatus> statuses() {
            return Collections.emptyList();
        }
    }
}
