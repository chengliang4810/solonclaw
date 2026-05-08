package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentProfileRepository;
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
    void shouldSupportJimuquCreateAndListCommandOptions() throws Exception {
        KanbanService service = service();

        String parentOutput =
                service.handleCommand(
                        "create 父任务 --body 父任务正文 --assignee alice --priority 7 --tenant demo --created-by planner --skill research --max-runtime 30m --max-retries 4",
                        "tester");
        String parentId = firstTaskId(parentOutput);
        Map<String, Object> parent = service.task(parentId);
        assertThat(parent.get("status")).isEqualTo("ready");
        assertThat(parent.get("body")).isEqualTo("父任务正文");
        assertThat(parent.get("assignee")).isEqualTo("alice");
        assertThat(parent.get("priority")).isEqualTo(7);
        assertThat(parent.get("tenant")).isEqualTo("demo");
        assertThat(parent.get("created_by")).isEqualTo("planner");
        assertThat(parent.get("max_retries")).isEqualTo(Integer.valueOf(4));
        assertThat(parent.get("max_runtime_seconds")).isEqualTo(1800L);
        assertThat(String.valueOf(parent.get("skills"))).contains("research");

        String childOutput =
                service.handleCommand(
                        "create 子任务 --assignee bob --parent "
                                + parentId
                                + " --tenant demo --workspace dir:C:\\\\work\\\\child",
                        "tester");
        String childId = firstTaskId(childOutput);
        Map<String, Object> child = service.task(childId);
        assertThat(child.get("status")).isEqualTo("todo");
        assertThat(child.get("workspace_kind")).isEqualTo("dir");
        assertThat(child.get("workspace_path")).isEqualTo("C:\\\\work\\\\child");
        assertThat(String.valueOf(child.get("parents"))).contains(parentId);

        String bobList = service.handleCommand("list --assignee bob --status todo --tenant demo", "tester");
        assertThat(bobList).contains(childId).contains("子任务").doesNotContain(parentId);

        String mineList = service.handleCommand("list --mine --status ready", "alice");
        assertThat(mineList).contains(parentId).doesNotContain(childId);

        String jsonList = service.handleCommand("list --assignee alice --json", "tester");
        assertThat(jsonList).contains("\"id\":\"" + parentId + "\"").contains("\"assignee\":\"alice\"");

        String triageJson = service.handleCommand("create 需要澄清 --triage --json", "tester");
        assertThat(triageJson).contains("\"status\":\"triage\"");
    }

    @Test
    void shouldSupportJimuquShowJsonAndUnassignCommands() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "待分配任务", "alice", "tester");
        service.comment(taskId, "tester", "补充上下文");

        String showJson = service.handleCommand("show " + taskId + " --json", "tester");
        assertThat(showJson)
                .contains("\"id\":\"" + taskId + "\"")
                .contains("\"comments\"")
                .contains("补充上下文");

        assertThat(service.handleCommand("assign " + taskId + " none", "tester"))
                .contains("-> -");
        Map<String, Object> unassigned = service.task(taskId);
        assertThat(unassigned.get("assignee")).isNull();
        assertThat(String.valueOf(unassigned.get("events"))).contains("assigned");

        assertThat(service.handleCommand("reassign " + taskId + " none", "tester"))
                .contains("-> -");
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("assignee")).isNull();
        assertThat(String.valueOf(detail.get("events"))).contains("reassigned");
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
        Map<String, Object> drawer = service.taskDrawer(taskId, 128);
        assertThat(String.valueOf(drawer.get("task"))).contains("worker timeout");
        assertThat(String.valueOf(drawer.get("runs"))).contains("completed").contains("reclaimed");
        assertThat(String.valueOf(drawer.get("events"))).contains("retry").contains("reclaimed");
        assertThat(String.valueOf(drawer.get("context"))).contains("worker_context").contains("Prior attempts");
        assertThat(String.valueOf(drawer.get("notifications"))).contains("[]");
        assertThat(String.valueOf(drawer.get("log"))).contains("exists=false");
        assertThat(String.valueOf(drawer.get("actions"))).contains("can_retry=false").contains("can_reassign=true");
        assertThat(service.handleCommand("runs " + taskId, "tester"))
                .contains("运行历史")
                .contains("reclaimed");
        assertThat(service.handleCommand("runs " + taskId + " --json", "tester"))
                .contains("\"outcome\":\"completed\"")
                .contains("\"outcome\":\"reclaimed\"");
        assertThat(service.handleCommand("events " + taskId, "tester"))
                .contains("执行流水")
                .contains("retry");
        assertThat(service.handleCommand("context " + taskId, "tester"))
                .contains("Task " + taskId)
                .contains("Prior attempts");
    }

    @Test
    void shouldUnblockOnlyBlockedTasks() throws Exception {
        KanbanService service = service();
        String blockedId = createTask(service, "等待人工确认", "alice", "alice");
        String readyId = createTask(service, "已经可执行", "alice", "alice");

        service.status(blockedId, "blocked", "需要补充信息");
        service.status(readyId, "ready", null);

        Map<String, Object> unblocked = service.unblock(blockedId);
        assertThat(unblocked.get("status")).isEqualTo("ready");
        assertThat(unblocked.get("claim_lock")).isNull();
        assertThat(unblocked.get("current_run_id")).isNull();
        assertThat(String.valueOf(unblocked.get("events")))
                .contains("unblocked")
                .contains("previous_status=blocked")
                .contains("需要补充信息");

        assertThatThrownBy(() -> service.unblock(readyId))
                .hasMessageContaining("not blocked");

        service.status(blockedId, "blocked", "再次等待确认");
        service.status(readyId, "blocked", "批量等待确认");
        assertThat(service.handleCommand("unblock " + blockedId + " " + readyId, "tester"))
                .contains("已解除阻塞任务")
                .contains(blockedId)
                .contains(readyId);
        assertThat(service.task(blockedId).get("status")).isEqualTo("ready");
        assertThat(service.task(readyId).get("status")).isEqualTo("ready");
        assertThatThrownBy(() -> service.handleCommand("unblock " + readyId, "tester"))
                .hasMessageContaining("not blocked");
    }

    @Test
    void shouldSupportJimuquStyleBulkLifecycleCommands() throws Exception {
        KanbanService service = service();
        String firstId = createTask(service, "批量任务一", "alice", "alice");
        String secondId = createTask(service, "批量任务二", "alice", "alice");
        String thirdId = createTask(service, "批量任务三", "alice", "alice");

        assertThat(service.handleCommand(
                        "block " + firstId + " 需要人工确认 --ids " + secondId + " " + thirdId,
                        "tester"))
                .contains("已阻塞任务")
                .contains(firstId)
                .contains(secondId)
                .contains(thirdId);
        assertThat(service.task(firstId).get("status")).isEqualTo("blocked");
        assertThat(service.task(secondId).get("status")).isEqualTo("blocked");
        assertThat(service.task(thirdId).get("status")).isEqualTo("blocked");
        assertThat(String.valueOf(service.task(secondId).get("comments")))
                .contains("BLOCKED: 需要人工确认");

        assertThat(service.handleCommand("complete " + firstId + " " + secondId, "tester"))
                .contains("已完成任务")
                .contains(firstId)
                .contains(secondId);
        assertThat(service.task(firstId).get("status")).isEqualTo("done");
        assertThat(service.task(secondId).get("status")).isEqualTo("done");

        assertThat(service.handleCommand("archive " + firstId + " " + secondId, "tester"))
                .contains("已归档任务")
                .contains(firstId)
                .contains(secondId);
        assertThat(service.task(firstId).get("status")).isEqualTo("archived");
        assertThat(service.task(secondId).get("status")).isEqualTo("archived");

        assertThatThrownBy(() -> service.handleCommand("archive " + firstId + " KB-NOTFOUND", "tester"))
                .hasMessageContaining("KB-NOTFOUND");
    }

    @Test
    void shouldEditCompletedTaskRecoveryFields() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "需要修正结果", "alice", "alice");
        String openId = createTask(service, "未完成任务", "alice", "alice");

        service.status(taskId, "done", "初版结果", "初版摘要", null);
        Map<String, Object> edited =
                service.edit(taskId, "修正后的结果", "修正后的摘要", "{\"tests_run\":2}");

        assertThat(edited.get("status")).isEqualTo("done");
        assertThat(edited.get("result")).isEqualTo("修正后的结果");
        assertThat(String.valueOf(edited.get("latest_run")))
                .contains("修正后的摘要")
                .contains("tests_run=2");
        assertThat(String.valueOf(edited.get("events")))
                .contains("edited")
                .contains("result_len")
                .contains("metadata");

        assertThat(service.handleCommand(
                        "edit " + taskId + " --result 命令修正结果 --summary 命令修正摘要 --metadata {\"ok\":true}",
                        "tester"))
                .contains("已编辑任务结果")
                .contains(taskId);
        assertThat(String.valueOf(service.task(taskId).get("latest_run"))).contains("ok=true");

        assertThatThrownBy(() -> service.edit(openId, "不能修正", null, null))
                .hasMessageContaining("not done");
        assertThatThrownBy(() -> service.edit(taskId, "bad metadata", null, "[1,2]"))
                .hasMessageContaining("JSON object");
    }

    @Test
    void shouldListConfiguredAndBoardAssignees() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AgentProfileService agentService =
                new AgentProfileService(new SqliteAgentProfileRepository(env.sqliteDatabase), env.agentRuntimeService);
        agentService.createAgent("alice", "负责实现");
        KanbanService service =
                new KanbanService(
                        new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig, agentService);

        createTask(service, "Alice task", "alice", "tester");
        String bobTask = createTask(service, "Bob task", "bob", "tester");
        service.status(bobTask, "ready", null);

        String assigneesText = service.handleCommand("assignees", "tester");
        assertThat(assigneesText)
                .contains("alice")
                .contains("configured=yes")
                .contains("bob")
                .contains("configured=no")
                .contains("ready=1");
        assertThat(service.handleCommand("assignees --json", "tester"))
                .contains("\"name\":\"alice\"")
                .contains("\"on_disk\":true")
                .contains("\"name\":\"bob\"");

        assertThat(String.valueOf(service.assignees()))
                .contains("name=alice")
                .contains("on_disk=true")
                .contains("enabled=true")
                .contains("name=bob")
                .contains("on_disk=false");
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
    void shouldPersistTaskMaxRetriesOverride() throws Exception {
        KanbanService service = service();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "需要自定义重试次数");
        body.put("assignee", "worker");
        body.put("max_retries", 5);

        Map<String, Object> created = service.createTask(body);
        String taskId = String.valueOf(created.get("id"));

        assertThat(created.get("max_retries")).isEqualTo(Integer.valueOf(5));
        assertThat(service.handleCommand("show " + taskId, "tester")).contains("max_retries=5");

        Map<String, Object> clear = new LinkedHashMap<String, Object>();
        clear.put("max_retries", null);
        Map<String, Object> updated = service.updateTask(taskId, clear);
        assertThat(updated.get("max_retries")).isNull();
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

        Map<String, Object> unlinked = service.unlink(parentId, childId);
        assertThat(String.valueOf(unlinked.get("parents"))).doesNotContain(parentId);
        assertThat(String.valueOf(unlinked.get("events"))).contains("unlinked").contains(parentId);
        assertThat(String.valueOf(service.task(parentId).get("children"))).doesNotContain(childId);
        assertThat(unlinked.get("status")).isEqualTo("ready");
        assertThat(service.handleCommand("link " + parentId + " " + childId, "tester"))
                .contains("已添加依赖");
        assertThat(service.handleCommand("unlink " + parentId + " " + childId, "tester"))
                .contains("已移除依赖");
        assertThatThrownBy(() -> service.unlink(parentId, childId))
                .hasMessageContaining("dependency link not found");
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
        assertThat(service.handleCommand("diagnostics --task " + taskId + " --json", "tester"))
                .contains("\"task_id\":\"" + taskId + "\"")
                .contains("\"severity\":\"warning\"")
                .contains("\"kind\":\"spawn_failed\"");
        assertThat(service.handleCommand("diagnostics --severity warning --json", "tester"))
                .contains("\"kind\":\"spawn_failed\"");
        assertThat(service.handleCommand("diagnostics --severity error --json", "tester"))
                .doesNotContain("spawn_failed");
    }

    @Test
    void shouldExposeJimuquStyleStatsWatchNotifyLogAndGc() throws Exception {
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
        assertThat(service.handleCommand("stats --json", "tester"))
                .contains("\"by_status\"")
                .contains("\"by_assignee\"");
        assertThat(String.valueOf(service.watch("alice", null, "comment", 20)))
                .contains("comment")
                .contains(taskId);
        assertThat(service.handleCommand("watch comment", "tester")).contains("最近 Kanban 事件");
        assertThat(service.handleCommand("watch --assignee alice --kinds comment --json", "tester"))
                .contains("\"kind\":\"comment\"")
                .contains("\"assignee\":\"alice\"")
                .contains("\"task_id\":\"" + taskId + "\"");
        assertThat(service.handleCommand("watch --tenant none --kinds comment --json", "tester"))
                .doesNotContain(taskId);

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
        assertThat(service.handleCommand("notify-list " + taskId + " --json", "tester"))
                .contains("\"platform\":\"dingtalk\"")
                .contains("\"task_id\":\"" + taskId + "\"");
        assertThat(service.handleCommand(
                        "notify-unsubscribe " + taskId + " dingtalk chat-2 thread-2", "tester"))
                .contains("已取消任务通知");
        assertThat(service.handleCommand(
                        "notify-subscribe "
                                + taskId
                                + " --platform feishu --chat-id chat-3 --thread-id thread-3 --user-id user-3",
                        "tester"))
                .contains("已订阅任务通知");
        assertThat(service.handleCommand("notify-list " + taskId + " --json", "tester"))
                .contains("\"platform\":\"feishu\"")
                .contains("\"chat_id\":\"chat-3\"")
                .contains("\"thread_id\":\"thread-3\"");
        assertThat(service.handleCommand(
                        "notify-unsubscribe "
                                + taskId
                                + " --platform feishu --chat-id chat-3 --thread-id thread-3",
                        "tester"))
                .contains("已取消任务通知");

        File logFile = FileUtil.file(env.appConfig.getRuntime().getLogsDir(), "kanban", taskId + ".log");
        FileUtil.mkParentDirs(logFile);
        FileUtil.writeUtf8String("line-one\nline-two\n", logFile);
        assertThat(String.valueOf(service.log(taskId, 9))).contains("line-two");
        assertThat(service.handleCommand("log " + taskId + " 9", "tester")).contains("line-two");
        assertThat(service.handleCommand("log " + taskId + " --tail 9", "tester")).contains("line-two");

        Map<String, Object> gc = new LinkedHashMap<String, Object>();
        gc.put("event_retention_days", 1);
        gc.put("log_retention_days", 1);
        assertThat(String.valueOf(service.gc(gc))).contains("removed_events").contains("removed_logs");
        assertThat(service.handleCommand("gc 1 1", "tester")).contains("Kanban GC 完成");
        assertThat(service.handleCommand("gc --event-retention-days 1 --log-retention-days 1", "tester"))
                .contains("Kanban GC 完成");
    }

    @Test
    void shouldSupportJimuquStyleKanbanRecoveryAndWorkerFlags() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "运行参数任务", "alice", "planner");
        service.status(taskId, "ready", null);

        assertThat(service.handleCommand("claim " + taskId + " --ttl 30", "worker-a"))
                .contains("已认领任务");
        assertThat(service.handleCommand("heartbeat " + taskId + " --note still-running", "worker-a"))
                .contains("已刷新任务心跳");
        assertThat(String.valueOf(service.task(taskId).get("events"))).contains("still-running");

        assertThat(service.handleCommand("reclaim " + taskId + " --reason worker timeout", "tester"))
                .contains("已收回执行权");
        assertThat(String.valueOf(service.task(taskId).get("events"))).contains("worker timeout");

        service.handleCommand("claim " + taskId + " --ttl 30", "worker-a");
        assertThat(service.handleCommand(
                        "reassign " + taskId + " bob --reclaim --reason handoff requested", "tester"))
                .contains("-> bob");
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("assignee")).isEqualTo("bob");
        assertThat(detail.get("status")).isEqualTo("ready");
        assertThat(String.valueOf(detail.get("events"))).contains("handoff requested");
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

    private static String firstTaskId(String value) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("KB-[0-9A-F]{8}").matcher(value);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }
}
