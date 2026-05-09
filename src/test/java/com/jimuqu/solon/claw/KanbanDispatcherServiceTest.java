package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.kanban.KanbanDispatcherService;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.kanban.KanbanTaskRecord;
import com.jimuqu.solon.claw.kanban.KanbanWorkerSpawner;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class KanbanDispatcherServiceTest {
    @Test
    void shouldPromoteTodoChildWhenParentsAreDoneAndDispatchDryRun() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);
        service.setDispatcherService(dispatcher);

        String parentId = createTask(service, "父任务", "lead", "lead", "todo", null);
        service.status(parentId, "done", "父任务完成", "父任务完成", null);
        String childId = createTask(service, "子任务", "worker", "lead", "todo", Arrays.asList(parentId));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dry_run", true);
        Map<String, Object> result = service.dispatch(body);

        assertThat(result.get("promoted")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(result.get("spawned"))).contains(childId).contains("worker");
        assertThat(service.task(childId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldClaimAssignedReadyTaskAndInvokeSpawner() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "可派发任务", "worker", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_spawn", 1);
        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker");
        assertThat(result.get("skipped_unassigned")).asString().doesNotContain(taskId);
        assertThat(spawner.spawned).isEqualTo(1);
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("running");
        assertThat(detail.get("worker_pid")).isEqualTo(Long.valueOf(4242L));
        assertThat(detail.get("spawn_failures")).isEqualTo(Integer.valueOf(0));
        assertThat(String.valueOf(detail.get("events"))).contains("spawned");
    }

    @Test
    void shouldSkipUnassignedReadyTasks() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "未分配任务", null, "planner", "ready", null);
        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("skipped_unassigned"))).contains(taskId);
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldAutoBlockAfterRepeatedSpawnFailures() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        FailingSpawner spawner = new FailingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "启动失败任务", "worker", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("failure_limit", 2);
        Map<String, Object> first = dispatcher.dispatch(body);
        assertThat(String.valueOf(first.get("spawn_failures"))).contains(taskId);
        assertThat(service.task(taskId).get("status")).isEqualTo("running");

        repository.releaseStaleClaims(System.currentTimeMillis() + 1200000L);
        Map<String, Object> second = dispatcher.dispatch(body);

        assertThat(String.valueOf(second.get("auto_blocked"))).contains(taskId);
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("blocked");
        assertThat(detail.get("spawn_failures")).isEqualTo(Integer.valueOf(2));
        assertThat(String.valueOf(detail.get("events")))
                .contains("gave_up")
                .contains("effective_limit=2")
                .contains("limit_source=dispatcher");
    }

    @Test
    void shouldUseTaskMaxRetriesBeforeDispatcherFailureLimit() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        FailingSpawner spawner = new FailingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "快速放弃任务", "worker", "planner", "ready", null);
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("max_retries", 1);
        service.updateTask(taskId, update);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("failure_limit", 99);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("auto_blocked"))).contains(taskId);
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("blocked");
        assertThat(String.valueOf(detail.get("events")))
                .contains("gave_up")
                .contains("effective_limit=1")
                .contains("limit_source=task");
    }

    @Test
    void shouldRunDaemonTicksAndStop() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);
        service.setDispatcherService(dispatcher);

        String taskId = createTask(service, "daemon 任务", "worker", "planner", "ready", null);
        final CountDownLatch latch = new CountDownLatch(1);
        dispatcher.setDaemonTickHook(new KanbanDispatcherService.DaemonTickHook() {
            @Override
            public void onTick(com.jimuqu.solon.claw.kanban.KanbanDispatchResult result) {
                latch.countDown();
            }
        });
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("interval_seconds", 1);
        body.put("max_spawn", 1);

        Map<String, Object> started = dispatcher.startDaemon(body);

        assertThat(started.get("running")).isEqualTo(Boolean.TRUE);
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        Map<String, Object> status = dispatcher.daemonStatus();
        assertThat(status.get("last_result")).isNotNull();
        assertThat(status.get("last_error")).isNull();
        assertThat(String.valueOf(status.get("last_result"))).contains(taskId);
        assertThat(spawner.spawned).isEqualTo(1);

        dispatcher.stopDaemon();
        Thread.sleep(100L);
        assertThat(dispatcher.daemonStatus().get("running")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void shouldNotStartDuplicateDaemonLoops() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);
        service.setDispatcherService(dispatcher);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("interval_seconds", 10);

        Map<String, Object> first = dispatcher.startDaemon(body);
        Map<String, Object> second = dispatcher.startDaemon(body);

        assertThat(first.get("started")).isEqualTo(Boolean.TRUE);
        assertThat(second.get("already_running")).isEqualTo(Boolean.TRUE);
        dispatcher.stopDaemon();
    }

    @Test
    void shouldKeepDaemonAliveAfterTickError() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        ExplodingSpawner spawner = new ExplodingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);
        service.setDispatcherService(dispatcher);

        createTask(service, "daemon 异常任务", "worker", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("interval_seconds", 1);
        body.put("failure_limit", 99);

        dispatcher.startDaemon(body);
        waitForTick(dispatcher, 1L);

        Map<String, Object> status = dispatcher.daemonStatus();
        assertThat(status.get("running")).isEqualTo(Boolean.TRUE);
        assertThat(status.get("last_result")).isNotNull();
        assertThat(String.valueOf(status.get("last_result"))).contains("spawn_failures");
        dispatcher.stopDaemon();
    }

    @Test
    void shouldRedactDaemonStatusLastError() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);
        service.setDispatcherService(dispatcher);

        final String leakedToken = "ghp_kanbandaemon12345";
        Field lastError = KanbanDispatcherService.class.getDeclaredField("daemonLastError");
        lastError.setAccessible(true);
        lastError.set(dispatcher, "daemon failed token=" + leakedToken + "\u202E");

        Map<String, Object> status = dispatcher.daemonStatus();
        assertThat(String.valueOf(status.get("last_error")))
                .contains("token=***")
                .doesNotContain(leakedToken)
                .doesNotContain("\u202E");
    }

    private String createTask(
            KanbanService service,
            String title,
            String assignee,
            String createdBy,
            String status,
            Object parents)
            throws Exception {
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("title", title);
        task.put("assignee", assignee);
        task.put("created_by", createdBy);
        task.put("status", status);
        if (parents != null) {
            task.put("parents", parents);
        }
        return String.valueOf(service.createTask(task).get("id"));
    }

    private void waitForTick(KanbanDispatcherService dispatcher, long ticks) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            Object tickCount = dispatcher.daemonStatus().get("tick_count");
            if (Long.parseLong(String.valueOf(tickCount)) >= ticks) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("daemon did not tick");
    }

    private static class RecordingSpawner implements KanbanWorkerSpawner {
        int spawned;

        @Override
        public long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) {
            spawned++;
            assertThat(workerContext).contains(task.getTaskId()).contains(task.getTitle());
            return 4242L;
        }
    }

    private static class FailingSpawner implements KanbanWorkerSpawner {
        @Override
        public long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) {
            throw new IllegalStateException("spawn failed");
        }
    }

    private static class ExplodingSpawner implements KanbanWorkerSpawner {
        @Override
        public long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) {
            throw new RuntimeException("boom");
        }
    }
}
