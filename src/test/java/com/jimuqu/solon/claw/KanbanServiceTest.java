package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class KanbanServiceTest {
    @Test
    void shouldPersistBoardsTasksCommentsAndSlashCommands() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service = new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));

        assertThat(service.boards()).hasSize(1);
        assertThat(service.currentBoard().get("slug")).isEqualTo("default");

        Map<String, Object> board = new LinkedHashMap<String, Object>();
        board.put("slug", "demo-board");
        board.put("name", "演示看板");
        board.put("switch", true);
        service.createBoard(board);
        assertThat(service.currentBoard().get("slug")).isEqualTo("demo-board");

        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("title", "补齐 CLI 看板");
        task.put("assignee", "local");
        task.put("priority", 2);
        Map<String, Object> created = service.createTask(task);
        String taskId = String.valueOf(created.get("id"));

        service.status(taskId, "ready", null);
        service.comment(taskId, "tester", "可以开始执行");

        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("ready");
        assertThat(detail.get("assignee")).isEqualTo("local");
        assertThat(String.valueOf(detail.get("comments"))).contains("可以开始执行");

        String commandResult = service.handleCommand("done " + taskId, "tester");
        assertThat(commandResult).contains("已完成任务");
        assertThat(service.task(taskId).get("status")).isEqualTo("done");
    }

    @Test
    void shouldVerifyWorkerCreatedCardsBeforeCompletion() throws Exception {
        KanbanService service = service();

        String parentId = createTask(service, "父任务", "alice", "alice");
        String childId = createTask(service, "子任务", null, "alice");

        Map<String, Object> detail =
                service.status(parentId, "done", "完成并创建 " + childId, "created child", Arrays.asList(childId));

        assertThat(detail.get("status")).isEqualTo("done");
        assertThat(String.valueOf(detail.get("events")))
                .contains("completed")
                .contains("verified_cards")
                .contains(childId);
    }

    @Test
    void shouldBlockCompletionWhenCreatedCardsAreMissing() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "父任务", "alice", "alice");

        assertThatThrownBy(
                        () ->
                                service.status(
                                        parentId,
                                        "done",
                                        "完成并创建 KB-DEADBEEF",
                                        "created phantom",
                                        Arrays.asList("KB-DEADBEEF")))
                .isInstanceOf(KanbanService.HallucinatedCardsException.class)
                .hasMessageContaining("unverifiable");

        Map<String, Object> detail = service.task(parentId);
        assertThat(detail.get("status")).isEqualTo("todo");
        assertThat(String.valueOf(detail.get("warnings")))
                .contains("completion_blocked_hallucination")
                .contains("KB-DEADBEEF");
    }

    @Test
    void shouldBlockCompletionWhenCreatedCardsBelongToAnotherWorker() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "父任务", "alice", "alice");
        String childId = createTask(service, "别人创建的子任务", null, "bob");

        assertThatThrownBy(
                        () ->
                                service.status(
                                        parentId,
                                        "done",
                                        "完成并创建 " + childId,
                                        "created child",
                                        Arrays.asList(childId)))
                .isInstanceOf(KanbanService.HallucinatedCardsException.class)
                .hasMessageContaining("created_by=bob");
        assertThat(service.task(parentId).get("status")).isEqualTo("todo");
    }

    @Test
    void shouldWarnWhenCompletionProseReferencesMissingCards() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "父任务", "alice", "alice");

        Map<String, Object> detail =
                service.status(parentId, "done", "完成，后续见 KB-DEADBEEF", "summary KB-DEADBEEF", null);

        assertThat(detail.get("status")).isEqualTo("done");
        assertThat(String.valueOf(detail.get("warnings")))
                .contains("suspected_hallucinated_references")
                .contains("KB-DEADBEEF");
    }

    @Test
    void shouldReclaimAndReassignRunningTasks() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "运行任务", "alice", "alice");
        Map<String, Object> running = new LinkedHashMap<String, Object>();
        running.put("status", "running");
        running.put("claim_lock", "lock-1");
        running.put("worker_id", "worker-a");
        service.updateTask(taskId, running);

        assertThatThrownBy(() -> service.reassign(taskId, "bob", false, null))
                .hasMessageContaining("still running");

        Map<String, Object> reclaimed = service.reclaim(taskId, "worker timeout");
        assertThat(reclaimed.get("status")).isEqualTo("ready");
        assertThat(reclaimed.get("claim_lock")).isNull();
        assertThat(String.valueOf(reclaimed.get("events"))).contains("reclaimed");

        Map<String, Object> reassigned = service.reassign(taskId, "bob", false, "handoff");
        assertThat(reassigned.get("assignee")).isEqualTo("bob");
        assertThat(String.valueOf(reassigned.get("events"))).contains("reassigned");
    }

    @Test
    void shouldTrackRunHistoryAcrossRunningCompletionReclaimAndRetry() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "有执行历史的任务", "alice", "alice");

        Map<String, Object> runningBody = new LinkedHashMap<String, Object>();
        runningBody.put("status", "running");
        runningBody.put("claim_lock", "lock-a");
        runningBody.put("worker_id", "worker-a");
        Map<String, Object> running = service.updateTask(taskId, runningBody);

        assertThat(running.get("status")).isEqualTo("running");
        assertThat(running.get("current_run_id")).isNotNull();
        assertThat(String.valueOf(running.get("active_run"))).contains("worker-a").contains("running");
        assertThat(String.valueOf(running.get("runs"))).contains("lock-a");

        Map<String, Object> done =
                service.status(taskId, "done", "执行完成", "handoff summary", null);
        assertThat(done.get("status")).isEqualTo("done");
        assertThat(done.get("current_run_id")).isNull();
        assertThat(done.get("active_run")).isNull();
        assertThat(String.valueOf(done.get("latest_run")))
                .contains("completed")
                .contains("handoff summary")
                .contains("verified_cards");

        Map<String, Object> retried = service.retry(taskId, "需要复核");
        assertThat(retried.get("status")).isEqualTo("ready");
        assertThat(retried.get("current_run_id")).isNull();
        assertThat(String.valueOf(retried.get("events"))).contains("retry").contains("需要复核");

        runningBody.put("claim_lock", "lock-b");
        runningBody.put("worker_id", "worker-b");
        service.updateTask(taskId, runningBody);
        Map<String, Object> reclaimed = service.reclaim(taskId, "worker timeout");
        assertThat(reclaimed.get("status")).isEqualTo("ready");
        assertThat(reclaimed.get("current_run_id")).isNull();
        assertThat(String.valueOf(reclaimed.get("latest_run")))
                .contains("reclaimed")
                .contains("worker timeout");
        assertThat(String.valueOf(reclaimed.get("runs"))).contains("completed").contains("reclaimed");

        assertThat(String.valueOf(service.runs(taskId))).contains("completed").contains("reclaimed");
        assertThat(String.valueOf(service.events(taskId))).contains("retry").contains("reclaimed");
        assertThat(String.valueOf(service.context(taskId).get("worker_context")))
                .contains("Prior attempts")
                .contains("worker timeout");
        assertThat(service.handleCommand("runs " + taskId, "tester"))
                .contains("运行历史")
                .contains("reclaimed");
        assertThat(service.handleCommand("events " + taskId, "tester"))
                .contains("执行流水")
                .contains("retry");
        assertThat(service.handleCommand("context " + taskId, "tester"))
                .contains("Task " + taskId)
                .contains("Prior attempts");
    }

    @Test
    void shouldExposePipelineLinksIdempotencyAndWorkerContext() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "父任务", "lead", "lead");
        service.comment(parentId, "lead", "父任务上下文");
        service.status(parentId, "done", "父任务完成", "父任务交接摘要", null);

        Map<String, Object> child = new LinkedHashMap<String, Object>();
        child.put("title", "子任务");
        child.put("body", "执行子任务");
        child.put("assignee", "worker");
        child.put("created_by", "lead");
        child.put("idempotency_key", "child-once");
        child.put("parents", Arrays.asList(parentId));
        child.put("max_runtime_seconds", 90);
        child.put("skills", Arrays.asList("kanban-worker", "translation"));
        Map<String, Object> created = service.createTask(child);
        String childId = String.valueOf(created.get("id"));

        Map<String, Object> duplicate = service.createTask(child);
        assertThat(duplicate.get("id")).isEqualTo(childId);

        service.comment(childId, "worker", "准备执行");
        Map<String, Object> detail = service.task(childId);
        assertThat(String.valueOf(detail.get("parents"))).contains(parentId).contains("父任务");
        assertThat(String.valueOf(service.task(parentId).get("children"))).contains(childId).contains("子任务");
        assertThat(detail.get("max_runtime_seconds")).isEqualTo(Long.valueOf(90));
        assertThat(String.valueOf(detail.get("skills"))).contains("translation");
        assertThat(String.valueOf(detail.get("worker_context")))
                .contains("Task " + childId)
                .contains("Parents:")
                .contains(parentId)
                .contains("Recent comments:")
                .contains("准备执行");
    }

    @Test
    void shouldClaimHeartbeatAndReleaseStaleKanbanTasks() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "可认领任务", "alice", "planner");
        service.status(taskId, "ready", null);

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-1");
        claim.put("worker_id", "worker-1");
        claim.put("worker_pid", 12345);
        claim.put("ttl_seconds", 1);
        Map<String, Object> running = service.claim(taskId, claim);

        assertThat(running.get("status")).isEqualTo("running");
        assertThat(running.get("claim_lock")).isEqualTo("host:worker-1");
        assertThat(running.get("worker_id")).isEqualTo("worker-1");
        assertThat(running.get("current_run_id")).isNotNull();
        assertThat(String.valueOf(running.get("events"))).contains("claimed");

        assertThatThrownBy(() -> service.claim(taskId, claim)).hasMessageContaining("not ready");

        Map<String, Object> heartbeat = new LinkedHashMap<String, Object>();
        heartbeat.put("claimer", "host:worker-1");
        heartbeat.put("ttl_seconds", 60);
        heartbeat.put("note", "still running");
        Map<String, Object> live = service.heartbeatClaim(taskId, heartbeat);
        live = service.heartbeatWorker(taskId, heartbeat);

        assertThat(live.get("last_heartbeat_at")).isNotNull();
        assertThat(String.valueOf(live.get("active_run"))).contains("worker-1");
        assertThat(String.valueOf(live.get("events"))).contains("heartbeat").contains("still running");
    }

    @Test
    void shouldClaimNextAndReclaimExpiredAndTimedOutTasks() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String taskId = createTask(service, "过期任务", "alice", "planner");
        service.status(taskId, "ready", null);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("assignee", "alice");
        body.put("claimer", "host:worker-2");
        body.put("worker_id", "worker-2");
        body.put("ttl_seconds", 60);
        Map<String, Object> next = service.claimNext(body);
        assertThat(next.get("claimed")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(next.get("task"))).contains(taskId).contains("worker-2");

        int stale = repository.releaseStaleClaims(System.currentTimeMillis() + 120000L);
        assertThat(stale).isEqualTo(1);
        Map<String, Object> reclaimed = service.task(taskId);
        assertThat(reclaimed.get("status")).isEqualTo("ready");
        assertThat(reclaimed.get("claim_lock")).isNull();
        assertThat(String.valueOf(reclaimed.get("latest_run"))).contains("reclaimed");

        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("max_runtime_seconds", 1);
        service.updateTask(taskId, update);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-3");
        claim.put("worker_id", "worker-3");
        claim.put("ttl_seconds", 60);
        service.claim(taskId, claim);

        int timedOut = repository.reclaimTimedOutWorkers(System.currentTimeMillis() + 2000L);
        assertThat(timedOut).isEqualTo(1);
        Map<String, Object> timedOutTask = service.task(taskId);
        assertThat(timedOutTask.get("status")).isEqualTo("ready");
        assertThat(String.valueOf(timedOutTask.get("latest_run"))).contains("timed_out");
    }

    @Test
    void shouldTrackSpawnFailuresOnClaimedTasks() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "启动失败任务", "worker", "planner");
        service.status(taskId, "ready", null);

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-4");
        claim.put("worker_id", "worker-4");
        service.claim(taskId, claim);

        Map<String, Object> failure = new LinkedHashMap<String, Object>();
        failure.put("error", "spawn command failed");
        Map<String, Object> detail = service.markSpawnFailure(taskId, failure);

        assertThat(detail.get("spawn_failures")).isEqualTo(Integer.valueOf(1));
        assertThat(detail.get("last_spawn_error")).isEqualTo("spawn command failed");
        assertThat(String.valueOf(detail.get("active_run"))).contains("spawn command failed");
        assertThat(String.valueOf(detail.get("events"))).contains("spawn_failed");
        assertThat(String.valueOf(service.diagnostics(taskId)))
                .contains("spawn_failed")
                .contains("spawn command failed");
        assertThat(service.handleCommand("diagnostics " + taskId, "tester"))
                .contains("Kanban 诊断")
                .contains("spawn_failed");
    }

    private KanbanService service() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        return new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));
    }

    private String createTask(KanbanService service, String title, String assignee, String createdBy)
            throws Exception {
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("title", title);
        task.put("assignee", assignee);
        task.put("created_by", createdBy);
        return String.valueOf(service.createTask(task).get("id"));
    }
}
