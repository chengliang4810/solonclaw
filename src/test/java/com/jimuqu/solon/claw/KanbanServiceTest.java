package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import cn.hutool.core.io.FileUtil;
import java.io.File;
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

        assertThat(service.handleCommand("boards show", "tester"))
                .contains("当前看板")
                .contains("demo-board");
        assertThat(service.handleCommand("boards rename demo-board 重命名看板", "tester"))
                .contains("重命名看板");
        assertThat(service.currentBoard().get("name")).isEqualTo("重命名看板");

        Map<String, Object> secondBoard = new LinkedHashMap<String, Object>();
        secondBoard.put("slug", "archive-me");
        secondBoard.put("name", "归档候选");
        service.createBoard(secondBoard);
        assertThat(service.handleCommand("boards rm archive-me", "tester")).contains("已归档看板");
        assertThat(String.valueOf(service.boards())).doesNotContain("archive-me");
        assertThat(String.valueOf(service.boards(true))).contains("archive-me").contains("archived=true");
        assertThat(service.handleCommand("boards list --all", "tester")).contains("archive-me").contains("[archived]");

        Map<String, Object> deleteBoard = new LinkedHashMap<String, Object>();
        deleteBoard.put("slug", "delete-me");
        service.createBoard(deleteBoard);
        assertThat(service.handleCommand("boards rm delete-me --delete", "tester")).contains("已删除看板");
        assertThat(String.valueOf(service.boards(true))).doesNotContain("delete-me");
        assertThatThrownBy(() -> service.removeBoard("default", false))
                .hasMessageContaining("default Kanban board cannot be archived");
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

        String linkedChildId = createTask(service, "后置检查", "reviewer", "lead");
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("parents", Arrays.asList(childId));
        Map<String, Object> linked = service.updateTask(linkedChildId, update);
        assertThat(String.valueOf(linked.get("parents"))).contains(childId).contains("子任务");
        assertThat(String.valueOf(service.task(childId).get("children"))).contains(linkedChildId).contains("后置检查");
    }

    @Test
    void shouldLinkExistingTasksAndRejectInvalidDependencyEdges() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "父任务", "lead", "lead");
        String childId = createTask(service, "子任务", "worker", "lead");

        Map<String, Object> linked = service.link(parentId, childId);

        assertThat(String.valueOf(linked.get("parents"))).contains(parentId).contains("父任务");
        assertThat(String.valueOf(linked.get("events"))).contains("linked").contains(parentId);
        assertThat(String.valueOf(service.task(parentId).get("children"))).contains(childId).contains("子任务");

        assertThatThrownBy(() -> service.link(parentId, parentId))
                .hasMessageContaining("cannot link to itself");
        assertThatThrownBy(() -> service.link(childId, parentId))
                .hasMessageContaining("dependency cycle");
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

    @Test
    void shouldExposeHermesStyleStatsWatchNotifyLogAndGc() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        String taskId = createTask(service, "协作通知任务", "alice", "planner");
        service.status(taskId, "ready", null);
        service.comment(taskId, "planner", "开始订阅");

        assertThat(String.valueOf(service.stats()))
                .contains("by_status")
                .contains("alice")
                .contains("ready");
        assertThat(service.handleCommand("stats", "tester")).contains("Kanban 统计");
        assertThat(String.valueOf(service.watch("alice", null, "comment", 20)))
                .contains("comment")
                .contains(taskId);
        assertThat(service.handleCommand("watch comment", "tester")).contains("最近 Kanban 事件");

        Map<String, Object> subscription = new LinkedHashMap<String, Object>();
        subscription.put("task_id", taskId);
        subscription.put("platform", "feishu");
        subscription.put("chat_id", "chat-1");
        subscription.put("thread_id", "thread-1");
        subscription.put("user_id", "user-1");
        assertThat(String.valueOf(service.notifySubscribe(subscription)))
                .contains("feishu")
                .contains("chat-1");
        assertThat(String.valueOf(service.notifyList(taskId))).contains("thread-1");
        assertThat(service.handleCommand(
                        "notify-subscribe " + taskId + " dingtalk chat-2 thread-2", "tester"))
                .contains("已订阅任务通知");
        assertThat(service.handleCommand("notify-list " + taskId, "tester")).contains("dingtalk");
        assertThat(service.handleCommand(
                        "notify-unsubscribe " + taskId + " dingtalk chat-2 thread-2", "tester"))
                .contains("已取消任务通知");

        File logFile = FileUtil.file(env.appConfig.getRuntime().getLogsDir(), "kanban", taskId + ".log");
        FileUtil.mkParentDirs(logFile);
        FileUtil.writeUtf8String("line-one\nline-two\n", logFile);
        assertThat(String.valueOf(service.log(taskId, 9))).contains("line-two");
        assertThat(service.handleCommand("log " + taskId + " 9", "tester")).contains("line-two");

        Map<String, Object> gc = new LinkedHashMap<String, Object>();
        gc.put("event_retention_days", 1);
        gc.put("log_retention_days", 1);
        assertThat(String.valueOf(service.gc(gc))).contains("removed_events").contains("removed_logs");
        assertThat(service.handleCommand("gc 1 1", "tester")).contains("Kanban GC 完成");
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
