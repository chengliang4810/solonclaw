package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.kanban.KanbanRunRecord;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentProfileRepository;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import cn.hutool.core.io.FileUtil;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

        Map<String, Object> guide = service.guide(null);
        assertThat(String.valueOf(guide))
                .contains("status_flow")
                .contains("drawer_sections")
                .contains("automation_actions")
                .contains("history_actions")
                .contains("notification_actions")
                .contains("maintenance_actions")
                .contains("创建或切换看板");
        assertThat(service.handleCommand("guide", "tester"))
                .contains("Kanban 操作指南")
                .contains("任务抽屉")
                .contains("恢复动作")
                .contains("历史与抽屉动作")
                .contains("通知动作")
                .contains("维护动作")
                .contains("/kanban dispatch");
        assertThat(service.handleCommand("guide --json", "tester"))
                .contains("\"drawer_sections\"")
                .contains("\"pipeline_overview\"")
                .contains("\"automation_actions\"")
                .contains("\"history_actions\"")
                .contains("\"notify-subscribe\"")
                .contains("--max-in-progress")
                .contains("--max-in-progress-per-profile")
                .contains("--default-assignee")
                .contains("--sort")
                .contains("\"maintenance_actions\"");
        assertThatThrownBy(() -> service.guide("missing-board"))
                .hasMessageContaining("Kanban board not found: missing-board");

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
        assertThat(child.get("workspace_path")).isEqualTo("path://child");
        assertThat(String.valueOf(child)).doesNotContain("C:\\\\work\\\\child");
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
    void shouldInferReadyStatusWhenServiceCreatesExecutableTasks() throws Exception {
        KanbanService service = service();

        Map<String, Object> standalone = new LinkedHashMap<String, Object>();
        standalone.put("title", "可直接执行任务");
        standalone.put("assignee", "alice");
        Map<String, Object> createdStandalone = service.createTask(standalone);
        String standaloneId = String.valueOf(createdStandalone.get("id"));

        assertThat(createdStandalone.get("status")).isEqualTo("ready");
        assertThat(service.task(standaloneId).get("status")).isEqualTo("ready");

        service.status(standaloneId, "done", "已完成");

        Map<String, Object> childOfDone = new LinkedHashMap<String, Object>();
        childOfDone.put("title", "父任务已完成子任务");
        childOfDone.put("assignee", "bob");
        childOfDone.put("parents", java.util.Collections.singletonList(standaloneId));
        assertThat(service.createTask(childOfDone).get("status")).isEqualTo("ready");

        String openParentId = createTask(service, "未完成父任务", "lead", "planner");
        Map<String, Object> childOfOpen = new LinkedHashMap<String, Object>();
        childOfOpen.put("title", "等待父任务子任务");
        childOfOpen.put("assignee", "worker");
        childOfOpen.put("parents", java.util.Collections.singletonList(openParentId));

        assertThat(service.createTask(childOfOpen).get("status")).isEqualTo("todo");
    }

    @Test
    void shouldListTasksByPriorityThenCreationOrder() throws Exception {
        KanbanService service = service();
        String firstId = createTask(service, "同优先级较早任务", "alice", "planner");
        String secondId = createTask(service, "同优先级较晚任务", "bob", "planner");

        service.comment(firstId, "tester", "更新较早任务不应改变同优先级排序");
        String highPriorityOutput =
                service.handleCommand("create 高优先级任务 --assignee carol --priority 2", "tester");
        String highPriorityId = firstTaskId(highPriorityOutput);

        java.util.List<Map<String, Object>> list = service.tasks(null, null, false);

        assertThat(list.get(0).get("id")).isEqualTo(highPriorityId);
        assertThat(list.get(1).get("id")).isEqualTo(firstId);
        assertThat(list.get(2).get("id")).isEqualTo(secondId);
    }

    @Test
    void shouldSupportTaskListSortOptions() throws Exception {
        KanbanService service = service();
        String alphaId = createTask(service, "alpha", "worker-b", "planner");
        String betaId = createTask(service, "beta", "worker-c", "planner");
        String gammaId = createTask(service, "gamma", "worker-a", "planner");

        String byTitle = service.handleCommand("list --sort title --json", "tester");
        assertThat(byTitle).containsSubsequence(
                "\"id\":\"" + alphaId + "\"",
                "\"id\":\"" + betaId + "\"",
                "\"id\":\"" + gammaId + "\"");

        String byAssignee = service.handleCommand("list --order-by assignee --json", "tester");
        assertThat(byAssignee).containsSubsequence(
                "\"id\":\"" + gammaId + "\"",
                "\"id\":\"" + alphaId + "\"",
                "\"id\":\"" + betaId + "\"");

        assertThatThrownBy(() -> service.handleCommand("list --sort bogus", "tester"))
                .hasMessageContaining("order_by must be one of");
    }

    @Test
    void shouldFilterTaskListByWorkflowTemplateAndStep() throws Exception {
        KanbanService service = service();
        String reviewId = createTask(service, "复核步骤任务", "alice", "planner");
        String shipId = createTask(service, "发布步骤任务", "bob", "planner");
        String otherId = createTask(service, "其他流程任务", "carol", "planner");
        service.step(reviewId, "review", "delivery", null, "planner");
        service.step(shipId, "ship", "delivery", null, "planner");
        service.step(otherId, "review", "ops", null, "planner");

        java.util.List<Map<String, Object>> deliveryTasks =
                service.tasks(null, null, false, null, null, null, "delivery", null);
        java.util.List<Map<String, Object>> reviewTasks =
                service.tasks(null, null, false, null, null, null, null, "review");
        java.util.List<Map<String, Object>> deliveryReviewTasks =
                service.tasks(null, null, false, null, null, null, "delivery", "review");

        assertThat(String.valueOf(deliveryTasks)).contains(reviewId).contains(shipId).doesNotContain(otherId);
        assertThat(String.valueOf(reviewTasks)).contains(reviewId).contains(otherId).doesNotContain(shipId);
        assertThat(deliveryReviewTasks).hasSize(1);
        assertThat(deliveryReviewTasks.get(0).get("id")).isEqualTo(reviewId);

        String workflowJson = service.handleCommand("list --workflow delivery --step review --json", "tester");
        assertThat(workflowJson)
                .contains("\"id\":\"" + reviewId + "\"")
                .doesNotContain("\"id\":\"" + shipId + "\"")
                .doesNotContain("\"id\":\"" + otherId + "\"");
    }

    @Test
    void shouldStampAndFilterTaskListByOriginatingSessionId() throws Exception {
        KanbanService service = service();
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("title", "会话任务一");
        first.put("tenant", "biz-a");
        first.put("session_id", "chat-session-a");
        String firstId = String.valueOf(service.createTask(first).get("id"));
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("title", "会话任务二");
        second.put("tenant", "biz-b");
        second.put("session_id", "chat-session-a");
        String secondId = String.valueOf(service.createTask(second).get("id"));
        Map<String, Object> otherSession = new LinkedHashMap<String, Object>();
        otherSession.put("title", "其他会话任务");
        otherSession.put("tenant", "biz-a");
        otherSession.put("session_id", "chat-session-b");
        String otherSessionId = String.valueOf(service.createTask(otherSession).get("id"));
        Map<String, Object> noSession = new LinkedHashMap<String, Object>();
        noSession.put("title", "无会话任务");
        String noSessionId = String.valueOf(service.createTask(noSession).get("id"));

        assertThat(service.task(firstId).get("session_id")).isEqualTo("chat-session-a");
        assertThat(service.task(noSessionId)).containsKey("session_id");
        assertThat(service.task(noSessionId).get("session_id")).isNull();

        java.util.List<Map<String, Object>> sessionTasks =
                service.tasks(null, null, false, null, null, null, null, null, "chat-session-a");
        java.util.List<Map<String, Object>> tenantSessionTasks =
                service.tasks(null, null, false, null, "biz-a", null, null, null, "chat-session-a");
        String commandList = service.handleCommand("list --session chat-session-a --json", "tester");

        assertThat(String.valueOf(sessionTasks))
                .contains(firstId)
                .contains(secondId)
                .doesNotContain(otherSessionId)
                .doesNotContain(noSessionId);
        assertThat(tenantSessionTasks).hasSize(1);
        assertThat(tenantSessionTasks.get(0).get("id")).isEqualTo(firstId);
        assertThat(commandList)
                .contains("\"id\":\"" + firstId + "\"")
                .contains("\"id\":\"" + secondId + "\"")
                .doesNotContain("\"id\":\"" + otherSessionId + "\"")
                .doesNotContain("\"id\":\"" + noSessionId + "\"");
    }

    @Test
    void shouldCreateTaskSessionIdIndex() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        new SqliteKanbanRepository(env.sqliteDatabase);

        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select name from sqlite_master where type = 'index' and tbl_name = 'kanban_tasks'");
            ResultSet resultSet = statement.executeQuery();
            try {
                java.util.List<String> names = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    names.add(resultSet.getString("name"));
                }
                assertThat(names).contains("idx_kanban_tasks_session_id");
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldRejectUnsupportedTaskWorkspaceKind() throws Exception {
        KanbanService service = service();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "非法工作区任务");
        body.put("workspace_kind", "cloud");

        assertThatThrownBy(() -> service.createTask(body))
                .hasMessageContaining("workspace_kind")
                .hasMessageContaining("scratch")
                .hasMessageContaining("dir")
                .hasMessageContaining("worktree");
    }

    @Test
    void shouldPersistWorktreeBranchNameAndRejectScratchBranchName() throws Exception {
        KanbanService service = service();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "工作树分支任务");
        body.put("workspace_kind", "worktree");
        body.put("workspace_path", "/tmp/solon-claw-worktrees/task-a");
        body.put("branch_name", " feature/task-a ");

        Map<String, Object> created = service.createTask(body);
        String taskId = String.valueOf(created.get("id"));
        Map<String, Object> detail = service.task(taskId);

        assertThat(created.get("branch_name")).isEqualTo("feature/task-a");
        assertThat(detail.get("branch_name")).isEqualTo("feature/task-a");
        assertThat(String.valueOf(detail.get("events"))).contains("branch_name=feature/task-a");
        assertThat(String.valueOf(detail.get("worker_context"))).contains("Branch:   feature/task-a");
        assertThat(service.handleCommand("create 命令分支任务 --workspace worktree --branch feature/cmd --json", "tester"))
                .contains("\"branch_name\":\"feature/cmd\"");

        Map<String, Object> invalid = new LinkedHashMap<String, Object>();
        invalid.put("title", "非法分支任务");
        invalid.put("workspace_kind", "scratch");
        invalid.put("branch_name", "feature/bad");

        assertThatThrownBy(() -> service.createTask(invalid))
                .hasMessageContaining("branch_name")
                .hasMessageContaining("worktree");
    }

    @Test
    void shouldFilterTaskListAssigneeCaseInsensitively() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "大小写过滤任务", "alice", "planner");
        createTask(service, "其他执行人任务", "bob", "planner");

        java.util.List<Map<String, Object>> filtered =
                service.tasks(null, null, false, "ALICE", null);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("id")).isEqualTo(taskId);
        assertThat(service.handleCommand("list --assignee ALICE --json", "tester"))
                .contains("\"id\":\"" + taskId + "\"")
                .doesNotContain("\"assignee\":\"bob\"");
    }

    @Test
    void shouldRecordCreatedEventWithTenantPayload() throws Exception {
        KanbanService service = service();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "租户事件任务");
        body.put("assignee", "alice");
        body.put("tenant", "biz-a");
        body.put("created_by", "planner");

        String taskId = String.valueOf(service.createTask(body).get("id"));

        Map<String, Object> detail = service.task(taskId);
        assertThat(String.valueOf(detail.get("events")))
                .contains("kind=created")
                .contains("tenant=biz-a")
                .contains("created_by=planner");
    }

    @Test
    void shouldValidateCommentsBeforePersistingThem() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String taskId = createTask(service, "评论校验任务", "alice", "planner");

        assertThatThrownBy(() -> service.comment(taskId, "tester", " "))
                .hasMessageContaining("comment body is required");
        assertRelatedRowCount(env.sqliteDatabase, "kanban_comments", taskId, 0);
        assertThatThrownBy(() -> service.comment("KB-NOTFOUND", "tester", "孤立评论"))
                .hasMessageContaining("Kanban task not found: KB-NOTFOUND");
        assertRelatedRowCount(env.sqliteDatabase, "kanban_comments", "KB-NOTFOUND", 0);
        assertRelatedRowCount(env.sqliteDatabase, "kanban_events", "KB-NOTFOUND", 0);

        Map<String, Object> detail = service.comment(taskId, "tester", "  有效评论  ");
        assertThat(String.valueOf(detail.get("comments"))).contains("有效评论");
        assertRelatedRowCount(env.sqliteDatabase, "kanban_comments", taskId, 1);
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
    void shouldAdvanceWorkflowStepAndRecordTransitionEvent() throws Exception {
        KanbanService service = service();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "流程任务");
        body.put("assignee", "alice");
        body.put("workflow_template_id", "delivery");
        body.put("current_step_key", "draft");
        String taskId = String.valueOf(service.createTask(body).get("id"));

        String output =
                service.handleCommand(
                        "step "
                                + taskId
                                + " review --workflow delivery-v2 --note 准备复核",
                        "planner");
        assertThat(output).contains("已推进任务步骤").contains("review").contains("delivery-v2");

        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("workflow_template_id")).isEqualTo("delivery-v2");
        assertThat(detail.get("current_step_key")).isEqualTo("review");
        assertThat(String.valueOf(detail.get("events")))
                .contains("step_changed")
                .contains("from_step=draft")
                .contains("to_step=review")
                .contains("from_workflow=delivery")
                .contains("to_workflow=delivery-v2")
                .contains("准备复核")
                .contains("planner");

        String json = service.handleCommand("pipeline " + taskId + " done --json", "planner");
        assertThat(json)
                .contains("\"current_step_key\":\"done\"")
                .contains("\"kind\":\"step_changed\"");
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
        assertThat(detail.get("status")).isEqualTo("ready");
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
        assertThat(service.task(parentId).get("status")).isEqualTo("ready");
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
        running.put("worker_pid", 424242L);
        service.updateTask(taskId, running);

        assertThatThrownBy(() -> service.reassign(taskId, "bob", false, null))
                .hasMessageContaining("still running");

        Map<String, Object> reclaimed = service.reclaim(taskId, "worker timeout");
        assertThat(reclaimed.get("status")).isEqualTo("ready");
        assertThat(reclaimed.get("claim_lock")).isNull();
        assertThat(reclaimed.get("worker_pid")).isNull();
        assertThat(String.valueOf(reclaimed.get("events"))).contains("reclaimed");

        Map<String, Object> reassigned = service.reassign(taskId, "bob", false, "handoff");
        assertThat(reassigned.get("assignee")).isEqualTo("bob");
        assertThat(String.valueOf(reassigned.get("events"))).contains("reassigned");
    }

    @Test
    void shouldRejectAssigningRunningTaskWithoutReclaim() throws Exception {
        KanbanService service = service();
        String taskId = createTask(service, "运行中禁止直接分配", "alice", "alice");
        Map<String, Object> running = new LinkedHashMap<String, Object>();
        running.put("status", "running");
        running.put("claim_lock", "lock-assign");
        running.put("worker_id", "worker-assign");
        service.updateTask(taskId, running);

        assertThatThrownBy(() -> service.assign(taskId, "bob"))
                .hasMessageContaining("currently running")
                .hasMessageContaining(taskId);
        assertThat(service.task(taskId).get("assignee")).isEqualTo("alice");

        assertThatThrownBy(() -> service.handleCommand("assign " + taskId + " bob", "tester"))
                .hasMessageContaining("currently running")
                .hasMessageContaining(taskId);
        assertThat(service.task(taskId).get("assignee")).isEqualTo("alice");
    }

    @Test
    void shouldExposeLatestNonBlankRunSummaryInTaskDetail() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String taskId = createTask(service, "最近摘要", "alice", "alice");
        long now = System.currentTimeMillis();

        KanbanRunRecord firstRun = new KanbanRunRecord();
        firstRun.setRunId("run_summary_real");
        firstRun.setTaskId(taskId);
        firstRun.setStatus("done");
        firstRun.setOutcome("completed");
        firstRun.setSummary("有效交接摘要");
        firstRun.setStartedAt(now - 2000L);
        firstRun.setEndedAt(now - 1000L);
        repository.addRun(firstRun);

        KanbanRunRecord emptyRun = new KanbanRunRecord();
        emptyRun.setRunId("run_summary_empty");
        emptyRun.setTaskId(taskId);
        emptyRun.setStatus("done");
        emptyRun.setOutcome("completed");
        emptyRun.setSummary("");
        emptyRun.setStartedAt(now + 1000L);
        emptyRun.setEndedAt(now + 2000L);
        repository.addRun(emptyRun);

        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("latest_summary")).isEqualTo("有效交接摘要");
        assertThat(String.valueOf(detail.get("latest_run"))).contains("run_summary_empty");

        Map<String, Object> drawer = service.taskDrawer(taskId, 128);
        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) drawer.get("execution_overview");
        assertThat(overview.get("latest_summary")).isEqualTo("有效交接摘要");
    }

    @Test
    void shouldExposeLatestNonBlankRunSummaryInTaskLists() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String firstId = createTask(service, "列表摘要 A", "alice", "alice");
        String secondId = createTask(service, "列表摘要 B", "bob", "bob");
        long now = System.currentTimeMillis();

        KanbanRunRecord firstRun = new KanbanRunRecord();
        firstRun.setRunId("run_list_real");
        firstRun.setTaskId(firstId);
        firstRun.setStatus("done");
        firstRun.setOutcome("completed");
        firstRun.setSummary("列表交接摘要");
        firstRun.setStartedAt(now - 2000L);
        firstRun.setEndedAt(now - 1000L);
        repository.addRun(firstRun);

        KanbanRunRecord emptyRun = new KanbanRunRecord();
        emptyRun.setRunId("run_list_empty");
        emptyRun.setTaskId(firstId);
        emptyRun.setStatus("done");
        emptyRun.setOutcome("completed");
        emptyRun.setSummary("");
        emptyRun.setStartedAt(now + 1000L);
        emptyRun.setEndedAt(now + 2000L);
        repository.addRun(emptyRun);

        Map<String, Object> first = findTaskView(service.tasks(null, null, false), firstId);
        Map<String, Object> second = findTaskView(service.tasks(null, null, false), secondId);

        assertThat(first.get("latest_summary")).isEqualTo("列表交接摘要");
        assertThat(second).containsKey("latest_summary");
        assertThat(second.get("latest_summary")).isNull();
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
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRun = (Map<String, Object>) running.get("active_run");
        assertThat(activeRun.get("running")).isEqualTo(Boolean.TRUE);
        assertThat(activeRun.get("finished")).isEqualTo(Boolean.FALSE);
        assertThat(((Number) activeRun.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0L);

        Map<String, Object> done =
                service.status(taskId, "done", "执行完成", "handoff summary", null);
        assertThat(done.get("status")).isEqualTo("done");
        assertThat(done.get("current_run_id")).isNull();
        assertThat(done.get("active_run")).isNull();
        assertThat(String.valueOf(done.get("latest_run")))
                .contains("completed")
                .contains("handoff summary")
                .contains("verified_cards");
        @SuppressWarnings("unchecked")
        Map<String, Object> completedRun = (Map<String, Object>) done.get("latest_run");
        assertThat(completedRun.get("running")).isEqualTo(Boolean.FALSE);
        assertThat(completedRun.get("finished")).isEqualTo(Boolean.TRUE);
        assertThat(completedRun.get("timed_out")).isEqualTo(Boolean.FALSE);
        assertThat(((Number) completedRun.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0L);

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
        assertThat(String.valueOf(service.runs(taskId, "outcome", "completed")))
                .contains("completed")
                .doesNotContain("reclaimed");
        assertThat(String.valueOf(service.runs(taskId, "outcome", "reclaimed")))
                .contains("reclaimed")
                .doesNotContain("completed");
        assertThat(service.runs(taskId, "outcome", "blocked")).isEmpty();
        assertThatThrownBy(() -> service.runs(taskId, "status", null))
                .hasMessageContaining("state_type and state_name must both be provided");
        assertThatThrownBy(() -> service.runs(taskId, null, "done"))
                .hasMessageContaining("state_type and state_name must both be provided");
        assertThatThrownBy(() -> service.runs(taskId, "nope", "done"))
                .hasMessageContaining("state_type must be status or outcome");
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
        assertThat(String.valueOf(drawer.get("pipeline_overview")))
                .contains("status=ready")
                .contains("stage=ready")
                .contains("assignee=alice")
                .contains("worker_id=worker-b")
                .contains("attempt_count=2")
                .contains("retry_count=1")
                .contains("supports_history=true")
                .contains("supports_retry=false")
                .contains("supports_reassign=true")
                .contains("supports_reclaim=false")
                .contains("supports_unblock=false")
                .contains("schema_task=false")
                .contains("latest_run={run_id=")
                .contains("outcome=reclaimed")
                .contains("summary=worker timeout");
        assertThat(String.valueOf(drawer.get("execution_overview")))
                .contains("stage=ready")
                .contains("attempt_count=2")
                .contains("latest_outcome=reclaimed")
                .contains("latest_summary=worker timeout")
                .contains("last_event_kind=reclaimed")
                .contains("next_action=dispatch");
        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) drawer.get("execution_overview");
        assertThat(overview.get("latest_summary")).isEqualTo("worker timeout");
        assertThat(((Number) overview.get("last_duration_ms")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(overview.get("last_timed_out")).isEqualTo(Boolean.FALSE);
        @SuppressWarnings("unchecked")
        Map<String, Object> pipeline = (Map<String, Object>) drawer.get("pipeline_overview");
        assertThat(pipeline.get("active_run")).isNull();
        assertThat(pipeline.get("next_action")).isEqualTo("dispatch");
        assertThat(service.handleCommand("runs " + taskId, "tester"))
                .contains("运行历史")
                .contains("reclaimed");
        assertThat(service.handleCommand("runs " + taskId + " --json", "tester"))
                .contains("\"outcome\":\"completed\"")
                .contains("\"outcome\":\"reclaimed\"");
        assertThat(service.handleCommand("runs " + taskId + " --state-type outcome --state reclaimed --json", "tester"))
                .contains("\"outcome\":\"reclaimed\"")
                .doesNotContain("\"outcome\":\"completed\"");
        assertThat(service.handleCommand("events " + taskId, "tester"))
                .contains("执行流水")
                .contains("retry");
        assertThat(service.handleCommand("drawer " + taskId, "tester"))
                .contains("任务抽屉")
                .contains("流水")
                .contains("可用动作")
                .contains("最近")
                .contains("worker-b");
        assertThat(service.handleCommand("inspect " + taskId + " --json", "tester"))
                .contains("\"pipeline_overview\"")
                .contains("\"supports_history\":true")
                .contains("\"latest_run\"")
                .contains("\"worker_id\":\"worker-b\"");
        assertThat(service.handleCommand("context " + taskId, "tester"))
                .contains("Task " + taskId)
                .contains("Prior attempts");
    }

    @Test
    void shouldRedactSecretsFromKanbanRunSummaries() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service = new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));
        String taskId = createTask(service, "脱敏任务", "alice", "tester");
        Map<String, Object> runningBody = new LinkedHashMap<String, Object>();
        runningBody.put("status", "running");
        runningBody.put("claim_lock", "lock-secret");
        runningBody.put("worker_id", "worker-secret");
        service.updateTask(taskId, runningBody);
        service.status(
                taskId,
                "done",
                "完成结果",
                "Authorization: Bearer ghp_kanbansummary12345 callback https://u:p@example.com/cb?token=kanban-token",
                null);

        String detail = String.valueOf(service.task(taskId));
        String runsText = service.handleCommand("runs " + taskId, "tester");
        String runsJson = service.handleCommand("runs " + taskId + " --json", "tester");
        String drawer = String.valueOf(service.taskDrawer(taskId, 128));
        String context = String.valueOf(service.context(taskId));

        assertRedactedRunSummary(detail);
        assertRedactedRunSummary(runsText);
        assertRedactedRunSummary(runsJson);
        assertRedactedRunSummary(drawer);
        assertRedactedRunSummary(context);
        assertRedactedRunSummary(readKanbanAuditStorage(env.sqliteDatabase, taskId));
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
    void shouldReturnBlockedChildToTodoWhenUnblockedBeforeParentsDone() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "未完成父任务", "lead", "planner");
        String childId = createTask(service, "等待依赖子任务", "worker", "planner");
        service.link(parentId, childId);
        service.status(childId, "blocked", "等待外部输入");

        Map<String, Object> unblocked = service.unblock(childId);

        assertThat(unblocked.get("status")).isEqualTo("todo");
        service.status(parentId, "done", "父任务完成");
        assertThat(service.task(childId).get("status")).isEqualTo("ready");
    }

    @Test
    void shouldClearSpawnFailuresWhenUnblocked() throws Exception {
        KanbanService service = service();
        String taskId = createReadyTask(service, "解除阻塞清理失败任务", "worker", "planner");
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-failure");
        service.claim(taskId, claim);
        Map<String, Object> failure = new LinkedHashMap<String, Object>();
        failure.put("error", "spawn failure before manual input");
        service.markSpawnFailure(taskId, failure);
        service.status(taskId, "blocked", "等待人工输入");

        Map<String, Object> blocked = service.task(taskId);
        assertThat(blocked.get("spawn_failures")).isEqualTo(Integer.valueOf(1));
        assertThat(blocked.get("last_spawn_error")).isEqualTo("spawn failure before manual input");

        Map<String, Object> unblocked = service.unblock(taskId);

        assertThat(unblocked.get("status")).isEqualTo("ready");
        assertThat(unblocked.get("spawn_failures")).isEqualTo(Integer.valueOf(0));
        assertThat(unblocked.get("last_spawn_error")).isNull();
    }

    @Test
    void shouldScheduleTasksAndUnblockThemThroughParentGate() throws Exception {
        KanbanService service = service();
        String delayedId = createReadyTask(service, "延后复查任务", "ops", "planner");

        Map<String, Object> scheduled = service.schedule(delayedId, "下周再检查");

        assertThat(scheduled.get("status")).isEqualTo("scheduled");
        assertThat(String.valueOf(scheduled.get("events")))
                .contains("scheduled")
                .contains("下周再检查");
        assertThatThrownBy(() -> service.claim(delayedId, new LinkedHashMap<String, Object>()))
                .hasMessageContaining("not ready");
        assertThatThrownBy(() -> service.schedule(delayedId, "二次延后"))
                .hasMessageContaining("not schedulable");
        String commandScheduledId = createReadyTask(service, "命令延后任务", "ops", "planner");
        assertThat(service.handleCommand("schedule " + commandScheduledId + " 命令延后", "planner"))
                .contains("已延后看板任务")
                .contains(commandScheduledId);

        String triageId = createTask(service, "待梳理任务", "ops", "planner");
        service.status(triageId, "triage", null);
        assertThatThrownBy(() -> service.schedule(triageId, "信息不完整"))
                .hasMessageContaining("not schedulable");
        assertThat(service.task(triageId).get("status")).isEqualTo("triage");
        String doneId = createReadyTask(service, "已完成任务", "ops", "planner");
        service.status(doneId, "done", "完成");
        assertThatThrownBy(() -> service.schedule(doneId, "完成后不延后"))
                .hasMessageContaining("not schedulable");
        assertThat(service.task(doneId).get("status")).isEqualTo("done");

        String parentId = createTask(service, "计划父任务", "lead", "planner");
        String childId = createTask(service, "计划子任务", "worker", "planner");
        service.link(parentId, childId);
        service.schedule(childId, "等待定时器");
        assertThat(service.unblock(childId).get("status")).isEqualTo("todo");

        service.status(parentId, "done", "父任务完成");
        service.schedule(childId, "第二次等待");
        assertThat(service.unblock(childId).get("status")).isEqualTo("ready");
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
    void shouldOnlyHardDeleteArchivedTasksAndCleanRelatedRows() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "保留父任务", "lead", "planner");
        String taskId = createTask(service, "待删除归档任务", "worker", "planner");
        service.link(parentId, taskId);
        service.comment(taskId, "tester", "删除前评论");

        assertThatThrownBy(() -> service.delete(taskId))
                .hasMessageContaining("archived");
        assertThat(service.task(taskId).get("id")).isEqualTo(taskId);

        service.status(taskId, "archived", "任务归档");
        Map<String, Object> deleted = service.delete(taskId);

        assertThat(deleted.get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(repository.findTask(taskId)).isNull();
        assertRelatedRowCount(env.sqliteDatabase, "kanban_comments", taskId, 0);
        assertRelatedRowCount(env.sqliteDatabase, "kanban_events", taskId, 0);
        assertRelatedRowCount(env.sqliteDatabase, "kanban_runs", taskId, 0);
        assertRelatedLinkCount(env.sqliteDatabase, taskId, 0);
    }

    @Test
    void shouldPromoteChildImmediatelyWhenArchivedParentIsHardDeleted() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "待删除父任务", "lead", "planner");
        String childId = createTask(service, "删除后可执行子任务", "worker", "planner");
        service.link(parentId, childId);
        service.status(parentId, "archived", "父任务归档");
        Map<String, Object> relinked = new LinkedHashMap<String, Object>();
        relinked.put("status", "todo");
        service.updateTask(childId, relinked);

        service.delete(parentId);

        assertThat(service.task(childId).get("status")).isEqualTo("ready");
        assertRelatedLinkCount(env.sqliteDatabase, parentId, 0);
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
                .contains("父任务完成")
                .contains("父任务交接摘要")
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
    void shouldRejectInvalidDependencyEdgesAtRepositoryBoundary() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String firstId = createTask(service, "仓储父任务", "lead", "lead");
        String secondId = createTask(service, "仓储子任务", "worker", "lead");
        String thirdId = createTask(service, "仓储孙任务", "worker", "lead");

        assertThatThrownBy(() -> repository.linkTasks(firstId, firstId))
                .hasMessageContaining("cannot link to itself");
        assertThat(repository.listParents(firstId)).isEmpty();

        repository.linkTasks(firstId, secondId);
        repository.linkTasks(secondId, thirdId);

        assertThatThrownBy(() -> repository.linkTasks(thirdId, firstId))
                .hasMessageContaining("dependency cycle");
        assertThat(repository.listParents(firstId)).isEmpty();
    }

    @Test
    void shouldRejectCreateTaskWithUnknownParentWithoutPersistingTask() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "悬空依赖子任务");
        body.put("assignee", "worker");
        body.put("parents", Arrays.asList("KB-NOTFOUND"));

        assertThatThrownBy(() -> service.createTask(body))
                .hasMessageContaining("parent task not found")
                .hasMessageContaining("KB-NOTFOUND");

        assertThat(repository.listTasks(null, null, true)).isEmpty();
    }

    @Test
    void shouldInferReadyWhenCreatingTaskWithDoneParentsThroughApi() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "已完成父任务", "lead", "planner");
        service.status(parentId, "done", "父任务完成");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "可立即执行子任务");
        body.put("assignee", "worker");
        body.put("parents", Arrays.asList(parentId));

        Map<String, Object> created = service.createTask(body);

        assertThat(created.get("status")).isEqualTo("ready");
        assertThat(String.valueOf(created.get("parents"))).contains(parentId);
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
    void shouldUseConfiguredDefaultClaimTtlForClaimsAndHeartbeats() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AppConfig config = new AppConfig();
        config.getKanban().setClaimTtlSeconds(3600);
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository, config);
        String taskId = createTask(service, "默认 TTL 任务", "alice", "planner");

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:ttl-default");
        long beforeClaim = System.currentTimeMillis();
        service.claim(taskId, claim);

        assertThat(repository.findTask(taskId).getClaimExpiresAt())
                .isGreaterThan(beforeClaim + 3000L * 1000L);

        Map<String, Object> rewind = new LinkedHashMap<String, Object>();
        rewind.put("claim_expires_at", 0L);
        service.updateTask(taskId, rewind);
        long beforeHeartbeat = System.currentTimeMillis();
        service.heartbeatClaim(taskId, claim);

        assertThat(repository.findTask(taskId).getClaimExpiresAt())
                .isGreaterThan(beforeHeartbeat + 3000L * 1000L);
    }

    @Test
    void shouldRejectClaimWhenReadyTaskStillHasUndoneParents() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "未完成父任务", "lead", "planner");
        String childId = createTask(service, "被错误推进的子任务", "worker", "planner");
        service.link(parentId, childId);
        Map<String, Object> forcedReady = new LinkedHashMap<String, Object>();
        forcedReady.put("status", "ready");
        service.updateTask(childId, forcedReady);

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-claim");

        assertThatThrownBy(() -> service.claim(childId, claim))
                .hasMessageContaining("not ready");
        Map<String, Object> detail = service.task(childId);
        assertThat(detail.get("status")).isEqualTo("todo");
        assertThat(String.valueOf(detail.get("events")))
                .contains("claim_rejected")
                .contains("parents_not_done");
    }

    @Test
    void shouldClaimNextSkipRejectedReadyCandidate() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "未完成调度父任务", "lead", "planner");
        String dirtyId = createTask(service, "脏就绪调度任务", "worker", "planner");
        String cleanId = createTask(service, "合法调度任务", "worker", "planner");
        service.link(parentId, dirtyId);
        Map<String, Object> dirtyReady = new LinkedHashMap<String, Object>();
        dirtyReady.put("status", "ready");
        dirtyReady.put("priority", Integer.valueOf(10));
        service.updateTask(dirtyId, dirtyReady);
        Map<String, Object> cleanReady = new LinkedHashMap<String, Object>();
        cleanReady.put("status", "ready");
        cleanReady.put("priority", Integer.valueOf(1));
        service.updateTask(cleanId, cleanReady);

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("assignee", "worker");
        claim.put("claimer", "host:claim-next");
        claim.put("worker_id", "claim-next");
        Map<String, Object> next = service.claimNext(claim);

        assertThat(next.get("claimed")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(next.get("task"))).contains(cleanId).doesNotContain(dirtyId);
        assertThat(service.task(dirtyId).get("status")).isEqualTo("todo");
        assertThat(String.valueOf(service.task(dirtyId).get("events")))
                .contains("claim_rejected")
                .contains("parents_not_done");
    }

    @Test
    void shouldRejectManualReadyMoveWhenParentsAreUndone() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "待完成父任务", "lead", "planner");
        String childId = createTask(service, "手动推进子任务", "worker", "planner");
        service.link(parentId, childId);

        assertThatThrownBy(() -> service.status(childId, "ready", "手动推进"))
                .hasMessageContaining("Cannot move to 'ready'")
                .hasMessageContaining(parentId)
                .hasMessageContaining("待完成父任务")
                .hasMessageContaining("status=ready");
        assertThat(service.task(childId).get("status")).isEqualTo("todo");

        service.status(parentId, "done", "父任务完成");
        assertThat(service.status(childId, "ready", "父任务已完成").get("status")).isEqualTo("ready");
    }

    @Test
    void shouldPromoteBlockedDependencyTaskWhenParentsAreDone() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "依赖父任务", "lead", "planner");
        String childId = createTask(service, "依赖子任务", "worker", "planner");
        service.link(parentId, childId);
        repository.updateTaskStatus(parentId, "done", "父任务完成");
        Map<String, Object> blocked = new LinkedHashMap<String, Object>();
        blocked.put("status", "blocked");
        service.updateTask(childId, blocked);

        int promoted = repository.recomputeReady(null);

        assertThat(promoted).isEqualTo(1);
        Map<String, Object> child = service.task(childId);
        assertThat(child.get("status")).isEqualTo("ready");
        assertThat(String.valueOf(child.get("events"))).contains("promoted");
    }

    @Test
    void shouldClearFailureStateWhenRecomputingBlockedTaskReady() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "重算父任务", "lead", "planner");
        String childId = createTask(service, "重算阻塞子任务", "worker", "planner");
        service.link(parentId, childId);
        repository.updateTaskStatus(parentId, "done", "父任务完成");
        Map<String, Object> failure = new LinkedHashMap<String, Object>();
        failure.put("status", "blocked");
        failure.put("spawn_failures", Integer.valueOf(5));
        failure.put("last_spawn_error", "persistent error");
        service.updateTask(childId, failure);

        int promoted = repository.recomputeReady(null);

        assertThat(promoted).isEqualTo(1);
        Map<String, Object> child = service.task(childId);
        assertThat(child.get("status")).isEqualTo("ready");
        assertThat(child.get("spawn_failures")).isEqualTo(Integer.valueOf(0));
        assertThat(child.get("last_spawn_error")).isNull();
    }

    @Test
    void shouldKeepManuallyBlockedTaskStickyDuringReadyRecompute() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String taskId = createReadyTask(service, "人工阻塞重算任务", "worker", "planner");
        service.status(taskId, "blocked", "等待人工复核");

        int promoted = repository.recomputeReady(null);

        assertThat(promoted).isEqualTo(0);
        Map<String, Object> task = service.task(taskId);
        assertThat(task.get("status")).isEqualTo("blocked");
        assertThat(String.valueOf(task.get("events"))).contains("blocked").doesNotContain("promoted_ready");
    }

    @Test
    void shouldTreatArchivedParentsAsSatisfiedWhenRecomputingReady() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "归档父任务", "lead", "planner");
        String childId = createTask(service, "归档依赖子任务", "worker", "planner");
        service.link(parentId, childId);
        updateTaskStatusDirectly(env.sqliteDatabase, parentId, "archived");

        int promoted = repository.recomputeReady(null);

        assertThat(promoted).isEqualTo(1);
        assertThat(service.task(childId).get("status")).isEqualTo("ready");
    }

    @Test
    void shouldPromoteChildImmediatelyWhenParentIsArchived() throws Exception {
        KanbanService service = service();
        String parentId = createTask(service, "立即归档父任务", "lead", "planner");
        String childId = createTask(service, "立即归档子任务", "worker", "planner");
        service.link(parentId, childId);

        service.status(parentId, "archived", "父任务归档");

        assertThat(service.task(childId).get("status")).isEqualTo("ready");
    }

    @Test
    void shouldPromoteChildImmediatelyWhenRepositoryArchivesParent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "仓储归档父任务", "lead", "planner");
        String childId = createTask(service, "仓储归档子任务", "worker", "planner");
        service.link(parentId, childId);

        repository.updateTaskStatus(parentId, "archived", "父任务归档");

        assertThat(service.task(childId).get("status")).isEqualTo("ready");
    }

    @Test
    void shouldDemoteReadyChildWhenRepositoryLinksUndoneParent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String parentId = createTask(service, "未完成父任务", "lead", "planner");
        String childId = createTask(service, "应退回子任务", "worker", "planner");
        assertThat(service.task(childId).get("status")).isEqualTo("ready");

        repository.linkTasks(parentId, childId);

        assertThat(service.task(childId).get("status")).isEqualTo("todo");
    }

    @Test
    void shouldPromoteChildWhenRepositoryUnlinksLastUndoneParent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String doneParentId = createTask(service, "已完成父任务", "lead", "planner");
        String runningParentId = createTask(service, "运行中父任务", "runner", "planner");
        String childId = createTask(service, "依赖解除子任务", "worker", "planner");
        repository.updateTaskStatus(doneParentId, "done", "父任务完成");
        repository.updateTaskStatus(runningParentId, "running", "父任务运行中");
        repository.linkTasks(doneParentId, childId);
        repository.linkTasks(runningParentId, childId);

        assertThat(service.task(childId).get("status")).isEqualTo("todo");

        boolean unlinked = repository.unlinkTasks(runningParentId, childId);

        assertThat(unlinked).isTrue();
        assertThat(service.task(childId).get("status")).isEqualTo("ready");
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
        assertThat(String.valueOf(reclaimed.get("events")))
                .contains("claim_expires_at")
                .contains("last_heartbeat_at")
                .contains("worker_pid");

        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("max_runtime_seconds", 1);
        service.updateTask(taskId, update);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-3");
        claim.put("worker_id", "worker-3");
        claim.put("ttl_seconds", 60);
        service.claim(taskId, claim);

        java.util.List<String> timedOut = repository.reclaimTimedOutWorkers(System.currentTimeMillis() + 2000L);
        assertThat(timedOut).contains(taskId);
        Map<String, Object> timedOutTask = service.task(taskId);
        assertThat(timedOutTask.get("status")).isEqualTo("ready");
        assertThat(String.valueOf(timedOutTask.get("latest_run"))).contains("timed_out");
    }

    @Test
    void shouldUseCurrentRunStartWhenReclaimingTimedOutRetry() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String taskId = createTask(service, "重试运行时限任务", "alice", "planner");
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("max_runtime_seconds", 10);
        service.updateTask(taskId, update);
        service.status(taskId, "ready", null);

        Map<String, Object> firstClaim = new LinkedHashMap<String, Object>();
        firstClaim.put("claimer", "host:first-run");
        firstClaim.put("worker_id", "first-run");
        firstClaim.put("ttl_seconds", 3600);
        service.claim(taskId, firstClaim);
        ageTaskAndActiveRun(env, taskId, 20000L);

        java.util.List<String> firstTimedOut = repository.reclaimTimedOutWorkers(System.currentTimeMillis());
        assertThat(firstTimedOut).contains(taskId);
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");

        Map<String, Object> secondClaim = new LinkedHashMap<String, Object>();
        secondClaim.put("claimer", "host:retry-run");
        secondClaim.put("worker_id", "retry-run");
        secondClaim.put("ttl_seconds", 3600);
        service.claim(taskId, secondClaim);

        java.util.List<String> retryTimedOut = repository.reclaimTimedOutWorkers(System.currentTimeMillis());

        assertThat(retryTimedOut).isEmpty();
        Map<String, Object> retryTask = service.task(taskId);
        assertThat(retryTask.get("status")).isEqualTo("running");
        assertThat(String.valueOf(retryTask.get("active_run"))).contains("retry-run");
    }

    @Test
    void shouldExtendExpiredClaimWhenWorkerPidIsStillAlive() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        String taskId = createTask(service, "慢速存活任务", "alice", "planner");
        service.status(taskId, "ready", null);

        long livePid = currentPid();
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-live");
        claim.put("worker_id", "worker-live");
        claim.put("worker_pid", livePid);
        claim.put("ttl_seconds", 1);
        service.claim(taskId, claim);
        long oldExpiresAt = System.currentTimeMillis() - 60000L;
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("claim_expires_at", oldExpiresAt);
        service.updateTask(taskId, update);

        int reclaimed = repository.releaseStaleClaims(System.currentTimeMillis());

        Map<String, Object> detail = service.task(taskId);
        assertThat(reclaimed).isEqualTo(0);
        assertThat(detail.get("status")).isEqualTo("running");
        assertThat(detail.get("claim_lock")).isEqualTo("host:worker-live");
        assertThat(detail.get("claim_expires_at")).isNotNull();
        assertThat(String.valueOf(detail.get("active_run"))).contains("worker-live");
        assertThat(String.valueOf(detail.get("events")))
                .contains("claim_extended")
                .contains("worker_pid_alive")
                .doesNotContain("reclaimed");
    }

    @Test
    void shouldUseConfiguredClaimTtlWhenExtendingLiveExpiredClaims() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AppConfig config = new AppConfig();
        config.getKanban().setClaimTtlSeconds(3600);
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase, config);
        KanbanService service = new KanbanService(repository, config);
        String taskId = createTask(service, "慢速配置续期任务", "alice", "planner");

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-live-ttl");
        claim.put("worker_id", "worker-live-ttl");
        claim.put("worker_pid", currentPid());
        claim.put("ttl_seconds", 1);
        service.claim(taskId, claim);
        Map<String, Object> expired = new LinkedHashMap<String, Object>();
        expired.put("claim_expires_at", System.currentTimeMillis() - 60000L);
        service.updateTask(taskId, expired);

        long beforeReclaim = System.currentTimeMillis();
        int reclaimed = repository.releaseStaleClaims(beforeReclaim);

        assertThat(reclaimed).isEqualTo(0);
        assertThat(repository.findTask(taskId).getClaimExpiresAt())
                .isGreaterThan(beforeReclaim + 3000L * 1000L);
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
    void shouldRedactKanbanErrorViewsAndHistoryText() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service = new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));
        String taskId = createTask(service, "脱敏任务", "worker", "planner");
        service.status(taskId, "ready", null);

        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:worker-secret");
        claim.put("worker_id", "worker-secret");
        service.claim(taskId, claim);

        String leakedToken = "ghp_kanbansecret12345";
        String leakedKey = "sk-kanban-error-secret12345";
        Map<String, Object> failure = new LinkedHashMap<String, Object>();
        failure.put("error", "spawn failed token=" + leakedToken + " api_key=" + leakedKey + "\u202E");
        Map<String, Object> detail = service.markSpawnFailure(taskId, failure);

        assertThat(String.valueOf(detail))
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain(leakedToken)
                .doesNotContain(leakedKey)
                .doesNotContain("\u202E");
        assertThat(String.valueOf(service.runs(taskId)))
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain(leakedToken)
                .doesNotContain(leakedKey)
                .doesNotContain("\u202E");
        assertThat(String.valueOf(service.taskDrawer(taskId, 256)))
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain(leakedToken)
                .doesNotContain(leakedKey)
                .doesNotContain("\u202E");
        assertThat(String.valueOf(service.context(taskId).get("worker_context")))
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain(leakedToken)
                .doesNotContain(leakedKey)
                .doesNotContain("\u202E");
        assertThat(readKanbanAuditStorage(env.sqliteDatabase, taskId))
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain(leakedToken)
                .doesNotContain(leakedKey)
                .doesNotContain("\u202E");
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
        Map<String, Object> log = service.log(taskId, 9);
        assertThat(String.valueOf(log)).contains("line-two");
        assertThat(String.valueOf(log.get("path")))
                .isEqualTo("runtime://logs/kanban/" + taskId + ".log");
        assertThat(log).doesNotContainKey("host_path");
        assertThat(String.valueOf(log.get("path"))).doesNotContain(env.appConfig.getRuntime().getHome());
        assertThat(service.handleCommand("log " + taskId + " 9", "tester")).contains("line-two");
        assertThat(service.handleCommand("log " + taskId + " --tail 9", "tester")).contains("line-two");
        String noLogTaskId = createTask(service, "暂无日志任务", "alice", "planner");
        assertThat(service.handleCommand("log " + noLogTaskId, "tester"))
                .contains("runtime://logs/kanban/" + noLogTaskId + ".log")
                .doesNotContain(env.appConfig.getRuntime().getHome());

        Map<String, Object> gc = new LinkedHashMap<String, Object>();
        gc.put("event_retention_days", 1);
        gc.put("log_retention_days", 1);
        assertThat(String.valueOf(service.gc(gc))).contains("removed_events").contains("removed_logs");
        assertThat(service.handleCommand("gc 1 1", "tester")).contains("Kanban GC 完成");
        assertThat(service.handleCommand("gc --event-retention-days 1 --log-retention-days 1", "tester"))
                .contains("Kanban GC 完成");
    }

    @Test
    void shouldExposeSpawnableReadyStatsUsingConfiguredAgentRegistry() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AgentProfileService agentService =
                new AgentProfileService(new SqliteAgentProfileRepository(env.sqliteDatabase), env.agentRuntimeService);
        agentService.createAgent("daily", "可派发执行者");
        KanbanService service =
                new KanbanService(
                        new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig, agentService);

        createReadyTask(service, "终端通道任务", "terminal-lane", "planner");
        Map<String, Object> onlyTerminalStats = service.stats();
        assertThat(onlyTerminalStats.get("has_spawnable_ready")).isEqualTo(Boolean.FALSE);
        assertThat(onlyTerminalStats.get("ready_spawnable")).isEqualTo(Integer.valueOf(0));
        assertThat(onlyTerminalStats.get("ready_nonspawnable")).isEqualTo(Integer.valueOf(1));

        createReadyTask(service, "真实执行者任务", "daily", "planner");
        Map<String, Object> mixedStats = service.stats();
        assertThat(mixedStats.get("has_spawnable_ready")).isEqualTo(Boolean.TRUE);
        assertThat(mixedStats.get("ready_spawnable")).isEqualTo(Integer.valueOf(1));
        assertThat(mixedStats.get("ready_nonspawnable")).isEqualTo(Integer.valueOf(1));
        assertThat(service.handleCommand("stats --json", "tester"))
                .contains("\"has_spawnable_ready\":true")
                .contains("\"ready_spawnable\":1")
                .contains("\"ready_nonspawnable\":1");
    }

    @Test
    void shouldExposeSpawnableReviewStatsUsingConfiguredAgentRegistry() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AgentProfileService agentService =
                new AgentProfileService(new SqliteAgentProfileRepository(env.sqliteDatabase), env.agentRuntimeService);
        agentService.createAgent("reviewer", "可复核执行者");
        KanbanService service =
                new KanbanService(
                        new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig, agentService);

        String terminalTaskId = createTask(service, "终端复核通道任务", "terminal-lane", "planner");
        service.status(terminalTaskId, "review", null);
        Map<String, Object> onlyTerminalStats = service.stats();
        assertThat(onlyTerminalStats.get("has_spawnable_review")).isEqualTo(Boolean.FALSE);
        assertThat(onlyTerminalStats.get("review_spawnable")).isEqualTo(Integer.valueOf(0));
        assertThat(onlyTerminalStats.get("review_nonspawnable")).isEqualTo(Integer.valueOf(1));

        String reviewerTaskId = createTask(service, "真实复核执行者任务", "reviewer", "planner");
        service.status(reviewerTaskId, "review", null);
        Map<String, Object> mixedStats = service.stats();
        assertThat(mixedStats.get("has_spawnable_review")).isEqualTo(Boolean.TRUE);
        assertThat(mixedStats.get("review_spawnable")).isEqualTo(Integer.valueOf(1));
        assertThat(mixedStats.get("review_nonspawnable")).isEqualTo(Integer.valueOf(1));
        assertThat(service.handleCommand("stats --json", "tester"))
                .contains("\"has_spawnable_review\":true")
                .contains("\"review_spawnable\":1")
                .contains("\"review_nonspawnable\":1");
    }

    @Test
    void shouldClaimRewindAndAdvanceNotifyEvents() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service =
                new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase), env.appConfig);
        String taskId = createTask(service, "可靠通知任务", "alice", "planner");
        Map<String, Object> subscription = new LinkedHashMap<String, Object>();
        subscription.put("task_id", taskId);
        subscription.put("platform", "feishu");
        subscription.put("chat_id", "chat-claim");
        subscription.put("thread_id", "thread-claim");
        service.notifySubscribe(subscription);

        service.status(taskId, "done", "ok", "完成", null);

        Map<String, Object> claimBody = new LinkedHashMap<String, Object>();
        claimBody.put("task_id", taskId);
        claimBody.put("platform", "feishu");
        claimBody.put("chat_id", "chat-claim");
        claimBody.put("thread_id", "thread-claim");
        claimBody.put("kinds", "completed,blocked");
        Map<String, Object> firstClaim = service.notifyClaim(claimBody);
        assertThat(firstClaim.get("claimed")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(firstClaim.get("events")))
                .contains("kind=completed")
                .doesNotContain("kind=created");

        Map<String, Object> duplicateClaim = service.notifyClaim(claimBody);
        assertThat(duplicateClaim.get("claimed")).isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(duplicateClaim.get("events"))).isEqualTo("[]");

        Map<String, Object> rewind = new LinkedHashMap<String, Object>(claimBody);
        rewind.put("claimed_cursor", firstClaim.get("new_cursor"));
        rewind.put("old_cursor", firstClaim.get("old_cursor"));
        assertThat(service.notifyRewind(rewind).get("rewound")).isEqualTo(Boolean.TRUE);

        Map<String, Object> retriedClaim = service.notifyClaim(claimBody);
        assertThat(retriedClaim.get("claimed")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(retriedClaim.get("events"))).contains("completed");

        Map<String, Object> advance = new LinkedHashMap<String, Object>(claimBody);
        advance.put("new_cursor", retriedClaim.get("new_cursor"));
        assertThat(service.notifyAdvance(advance).get("advanced")).isEqualTo(Boolean.TRUE);
        assertThat(service.notifyClaim(claimBody).get("claimed")).isEqualTo(Boolean.FALSE);
        assertThat(service.handleCommand(
                        "notify-claim "
                                + taskId
                                + " feishu chat-claim thread-claim --kinds completed,blocked",
                        "tester"))
                .contains("events=0");
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

    private String createReadyTask(KanbanService service, String title, String assignee, String createdBy)
            throws Exception {
        String taskId = createTask(service, title, assignee, createdBy);
        service.status(taskId, "ready", null);
        return taskId;
    }

    private void updateTaskStatusDirectly(SqliteDatabase database, String taskId, String status) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("update kanban_tasks set status = ? where task_id = ?");
            statement.setString(1, status);
            statement.setString(2, taskId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private void assertRelatedRowCount(SqliteDatabase database, String table, String taskId, int expected)
            throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select count(*) from " + table + " where task_id = ?");
            statement.setString(1, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(expected);
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private void assertRelatedLinkCount(SqliteDatabase database, String taskId, int expected) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select count(*) from kanban_task_links where parent_id = ? or child_id = ?");
            statement.setString(1, taskId);
            statement.setString(2, taskId);
            ResultSet resultSet = statement.executeQuery();
            try {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(expected);
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private void ageTaskAndActiveRun(TestEnvironment env, String taskId, long millisAgo) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement taskStatement =
                    connection.prepareStatement("update kanban_tasks set started_at = ? where task_id = ?");
            taskStatement.setLong(1, System.currentTimeMillis() - millisAgo);
            taskStatement.setString(2, taskId);
            taskStatement.executeUpdate();
            taskStatement.close();

            PreparedStatement runStatement =
                    connection.prepareStatement(
                            "update kanban_runs set started_at = ? where run_id = (select current_run_id from kanban_tasks where task_id = ?)");
            runStatement.setLong(1, System.currentTimeMillis() - millisAgo);
            runStatement.setString(2, taskId);
            runStatement.executeUpdate();
            runStatement.close();
        } finally {
            connection.close();
        }
    }

    private long currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return Long.parseLong(name.split("@", 2)[0]);
    }

    private static String firstTaskId(String value) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("KB-[0-9A-F]{8}").matcher(value);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private static Map<String, Object> findTaskView(java.util.List<Map<String, Object>> tasks, String taskId) {
        for (Map<String, Object> task : tasks) {
            if (taskId.equals(task.get("id"))) {
                return task;
            }
        }
        throw new AssertionError("task not found: " + taskId);
    }

    private static void assertRedactedRunSummary(String value) {
        assertThat(value)
                .contains("Bearer ***")
                .doesNotContain("ghp_kanbansummary12345")
                .doesNotContain("kanban-token")
                .doesNotContain("u:p@example.com");
    }

    private static String readKanbanAuditStorage(SqliteDatabase database, String taskId) throws Exception {
        StringBuilder buffer = new StringBuilder();
        Connection connection = database.openConnection();
        try {
            PreparedStatement runs =
                    connection.prepareStatement(
                            "select summary, metadata_json, error from kanban_runs where task_id = ?");
            runs.setString(1, taskId);
            ResultSet runSet = runs.executeQuery();
            try {
                while (runSet.next()) {
                    buffer.append(runSet.getString("summary")).append('\n');
                    buffer.append(runSet.getString("metadata_json")).append('\n');
                    buffer.append(runSet.getString("error")).append('\n');
                }
            } finally {
                runSet.close();
                runs.close();
            }
            PreparedStatement events =
                    connection.prepareStatement(
                            "select payload_json from kanban_events where task_id = ?");
            events.setString(1, taskId);
            ResultSet eventSet = events.executeQuery();
            try {
                while (eventSet.next()) {
                    buffer.append(eventSet.getString("payload_json")).append('\n');
                }
            } finally {
                eventSet.close();
                events.close();
            }
        } finally {
            connection.close();
        }
        return buffer.toString();
    }
}
