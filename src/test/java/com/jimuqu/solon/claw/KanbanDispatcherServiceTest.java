package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ConversationOrchestrator;
import com.jimuqu.solon.claw.kanban.ConversationKanbanWorkerSpawner;
import com.jimuqu.solon.claw.kanban.KanbanDispatcherService;
import com.jimuqu.solon.claw.kanban.KanbanCommentRecord;
import com.jimuqu.solon.claw.kanban.KanbanRunRecord;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.kanban.KanbanTaskRecord;
import com.jimuqu.solon.claw.kanban.KanbanWorkerSpawner;
import com.jimuqu.solon.claw.storage.repository.SqliteAgentProfileRepository;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import java.io.File;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
        KanbanDispatcherService dispatcher =
                new KanbanDispatcherService(repository, service, spawner, env.appConfig);

        String taskId = createTask(service, "可派发任务", "worker", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_spawn", 1);
        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker");
        assertThat(String.valueOf(result.get("spawned")))
                .contains("workspace://kanban/" + taskId)
                .doesNotContain(System.getProperty("user.dir"));
        assertThat(result.get("skipped_unassigned")).asString().doesNotContain(taskId);
        assertThat(spawner.spawned).isEqualTo(1);
        File expectedWorkspace =
                new File(new File(env.appConfig.getRuntime().getHome(), "kanban/workspaces"), taskId)
                        .getCanonicalFile();
        assertThat(spawner.lastWorkspacePath).isEqualTo(expectedWorkspace.getAbsolutePath());
        assertThat(expectedWorkspace).isDirectory();
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("running");
        assertThat(detail.get("worker_pid")).isEqualTo(Long.valueOf(4242L));
        assertThat(detail.get("spawn_failures")).isEqualTo(Integer.valueOf(0));
        assertThat(String.valueOf(detail.get("events"))).contains("spawned");
    }

    @Test
    void shouldCreateExplicitDirWorkspaceBeforeInvokingSpawner() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher =
                new KanbanDispatcherService(repository, service, spawner, env.appConfig);

        File workspace = new File(env.appConfig.getRuntime().getHome(), "custom-workspace");
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("title", "目录工作区任务");
        task.put("assignee", "worker");
        task.put("created_by", "planner");
        task.put("status", "ready");
        task.put("workspace_kind", "dir");
        task.put("workspace_path", workspace.getAbsolutePath());
        String taskId = String.valueOf(service.createTask(task).get("id"));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_spawn", 1);

        dispatcher.dispatch(body);

        assertThat(spawner.spawned).isEqualTo(1);
        assertThat(spawner.lastWorkspacePath).isEqualTo(workspace.getAbsolutePath());
        assertThat(workspace).isDirectory();
        assertThat(repository.findTask(taskId).getWorkspacePath()).isEqualTo(workspace.getAbsolutePath());
    }

    @Test
    void shouldRespectGlobalMaxInProgressBeforeSpawningReadyTasks() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        createTask(service, "运行中任务", "worker-a", "planner", "running", null);
        String readyTaskId = createTask(service, "等待派发任务", "worker-b", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_spawn", 1);
        body.put("max_in_progress", 1);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(result.get("running_before_dispatch")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("max_in_progress")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(result.get("skipped_capacity"))).contains(readyTaskId);
        assertThat(result.get("spawned")).asString().doesNotContain(readyTaskId);
        assertThat(service.task(readyTaskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldTreatMaxSpawnAsLiveRunningCapacity() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        createTask(service, "已有运行任务", "worker-a", "planner", "running", null);
        String readyTaskId = createTask(service, "等待 max 派发任务", "worker-b", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_spawn", 1);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(result.get("running_before_dispatch")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("max_spawn")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(result.get("skipped_capacity"))).contains(readyTaskId);
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(readyTaskId);
        assertThat(service.task(readyTaskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
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
    void shouldSkipReadyTasksWhoseAssigneeIsNotAConfiguredAgent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        AgentProfileService agentService =
                new AgentProfileService(new SqliteAgentProfileRepository(env.sqliteDatabase), env.agentRuntimeService);
        KanbanService service = new KanbanService(repository, null, agentService);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "未知执行者任务", "missing-agent", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("skipped_nonspawnable"))).contains(taskId);
        assertThat(String.valueOf(result.get("skipped_unassigned"))).doesNotContain(taskId);
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(taskId);
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldDispatchReadyTasksForEnabledConfiguredAgentsOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        AgentProfileService agentService =
                new AgentProfileService(new SqliteAgentProfileRepository(env.sqliteDatabase), env.agentRuntimeService);
        agentService.createAgent("worker-enabled", "执行 Kanban 任务");
        agentService.createAgent("worker-disabled", "停用执行者");
        com.jimuqu.solon.claw.agent.AgentProfile disabled = agentService.findByName("worker-disabled");
        disabled.setEnabled(false);
        agentService.save(disabled);
        KanbanService service = new KanbanService(repository, null, agentService);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String enabledTask = createTask(service, "启用执行者任务", "worker-enabled", "planner", "ready", null);
        String disabledTask = createTask(service, "停用执行者任务", "worker-disabled", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(enabledTask).contains("worker-enabled");
        assertThat(String.valueOf(result.get("skipped_nonspawnable"))).contains(disabledTask);
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(disabledTask);
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldUseKanbanConfigDefaultsWhenDispatchBodyOmitsLimits() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        AppConfig config = new AppConfig();
        config.getKanban().setDefaultAssignee("worker-default");
        config.getKanban().setMaxInProgress(1);
        config.getKanban().setMaxInProgressPerProfile(1);
        KanbanDispatcherService dispatcher =
                new KanbanDispatcherService(repository, service, spawner, config);

        String fallbackTaskId = createTask(service, "配置默认执行者任务", null, "planner", "ready", null);
        String cappedTaskId = createTask(service, "配置 per profile 限制任务", "worker-default", "planner", "ready", null);

        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("auto_assigned_default"))).contains(fallbackTaskId);
        assertThat(String.valueOf(result.get("spawned"))).contains(fallbackTaskId).contains("worker-default");
        assertThat(String.valueOf(result.get("skipped_capacity"))).contains(cappedTaskId);
        assertThat(result.get("max_in_progress")).isEqualTo(Integer.valueOf(1));
        assertThat(result.get("max_in_progress_per_profile")).isEqualTo(Integer.valueOf(1));
        assertThat(service.task(fallbackTaskId).get("assignee")).isEqualTo("worker-default");
        assertThat(service.task(cappedTaskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldPreferDispatchBodyOverKanbanConfigDefaults() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        AppConfig config = new AppConfig();
        config.getKanban().setDefaultAssignee("worker-config");
        config.getKanban().setMaxInProgress(1);
        KanbanDispatcherService dispatcher =
                new KanbanDispatcherService(repository, service, spawner, config);

        String first = createTask(service, "请求体覆盖任务一", null, "planner", "ready", null);
        String second = createTask(service, "请求体覆盖任务二", "worker-b", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("default_assignee", "worker-body");
        body.put("max_in_progress", 2);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned")))
                .contains(first)
                .contains(second)
                .contains("worker-body");
        assertThat(String.valueOf(result.get("skipped_capacity"))).doesNotContain(second);
        assertThat(service.task(first).get("assignee")).isEqualTo("worker-body");
        assertThat(spawner.spawned).isEqualTo(2);
    }

    @Test
    void shouldApplyDefaultAssigneeToUnassignedReadyTaskBeforeDispatching() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "默认执行者任务", null, "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("default_assignee", "worker-default");
        body.put("max_spawn", 1);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("auto_assigned_default"))).contains(taskId);
        assertThat(String.valueOf(result.get("skipped_unassigned"))).doesNotContain(taskId);
        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker-default");
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("assignee")).isEqualTo("worker-default");
        assertThat(detail.get("status")).isEqualTo("running");
        assertThat(String.valueOf(detail.get("events")))
                .contains("assigned")
                .contains("dispatcher:worker-default")
                .contains("worker-default")
                .contains("default_assignee");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldReportDefaultAssigneeInDryRunWithoutMutatingTask() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "默认执行者预演任务", null, "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("default_assignee", "worker-default");
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("auto_assigned_default"))).contains(taskId);
        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker-default");
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("assignee")).isNull();
        assertThat(detail.get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldNotApplyMissingDefaultAssigneeWhenAgentRegistryIsAvailable() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        AgentProfileService agentService =
                new AgentProfileService(new SqliteAgentProfileRepository(env.sqliteDatabase), env.agentRuntimeService);
        KanbanService service = new KanbanService(repository, null, agentService);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "缺失默认执行者任务", null, "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("default_assignee", "missing-agent");

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("skipped_unassigned"))).contains(taskId);
        assertThat(String.valueOf(result.get("auto_assigned_default"))).doesNotContain(taskId);
        assertThat(String.valueOf(result.get("skipped_nonspawnable"))).doesNotContain(taskId);
        assertThat(service.task(taskId).get("assignee")).isNull();
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldRespectPerProfileMaxInProgressBeforeSpawningReadyTasks() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        createTask(service, "worker-a 运行中", "worker-a", "planner", "running", null);
        String cappedTaskId = createTask(service, "worker-a 等待任务", "worker-a", "planner", "ready", null);
        String spawnableTaskId = createTask(service, "worker-b 等待任务", "worker-b", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_in_progress_per_profile", 1);
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("skipped_per_profile_capped")))
                .contains(cappedTaskId)
                .contains("worker-a")
                .contains("current=1");
        assertThat(String.valueOf(result.get("spawned"))).contains(spawnableTaskId).contains("worker-b");
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(cappedTaskId);
        assertThat(service.task(cappedTaskId).get("status")).isEqualTo("ready");
        assertThat(service.task(spawnableTaskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldCountDryRunSpawnsAgainstPerProfileCapWithinOneTick() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String first = createTask(service, "第一个 worker-a 任务", "worker-a", "planner", "ready", null);
        String second = createTask(service, "第二个 worker-a 任务", "worker-a", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_in_progress_per_profile", 1);
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(first).doesNotContain(second);
        assertThat(String.valueOf(result.get("skipped_per_profile_capped")))
                .contains(second)
                .contains("worker-a")
                .contains("current=1");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldCountDryRunSpawnsAgainstGlobalCapsWithinOneTick() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String first = createTask(service, "第一个全局预演任务", "worker-a", "planner", "ready", null);
        String second = createTask(service, "第二个全局预演任务", "worker-b", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dry_run", true);
        body.put("max_in_progress", 1);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(first).doesNotContain(second);
        assertThat(String.valueOf(result.get("skipped_capacity"))).contains(second);
        assertThat(service.task(first).get("status")).isEqualTo("ready");
        assertThat(service.task(second).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldDispatchReviewTasksInDryRunWithoutMutatingStatus() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "待复核任务", "worker-a", "planner", "review", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker-a");
        assertThat(service.task(taskId).get("status")).isEqualTo("review");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldClaimReviewTaskAndInvokeSpawner() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "复核执行任务", "worker-a", "planner", "review", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_spawn", 1);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker-a");
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("running");
        assertThat(detail.get("worker_pid")).isEqualTo(Long.valueOf(4242L));
        assertThat(String.valueOf(detail.get("events"))).contains("claimed").contains("spawned");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldAttachReviewSkillWhenDispatchingReviewTask() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "复核技能任务", "worker-a", "planner", "review", null);

        dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(spawner.lastTask.getTaskId()).isEqualTo(taskId);
        assertThat(spawner.lastTask.getSkillsJson()).contains("review");
        assertThat(service.task(taskId).get("skills")).isNull();
    }

    @Test
    void shouldIncludeReviewSkillInWorkerPromptForReviewTask() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        KanbanDispatcherService dispatcher =
                new KanbanDispatcherService(
                        repository,
                        service,
                        new ConversationKanbanWorkerSpawner(orchestrator, service));

        createTask(service, "复核提示任务", "worker-a", "planner", "review", null);

        dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(orchestrator.awaitMessage().getText())
                .contains("请先加载并遵循这些技能")
                .contains("review");
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
    void shouldGuardReadyTaskAfterQuotaSpawnFailureWithoutAutoBlocking() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        FailingSpawner spawner = new FailingSpawner("API quota exceeded: rate limit hit");
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "配额保护任务", "worker", "planner", "ready", null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("failure_limit", 2);
        Map<String, Object> first = dispatcher.dispatch(body);
        assertThat(String.valueOf(first.get("spawn_failures"))).contains(taskId);

        repository.releaseStaleClaims(System.currentTimeMillis() + 1200000L);
        Map<String, Object> second = dispatcher.dispatch(body);

        assertThat(String.valueOf(second.get("respawn_guarded"))).contains(taskId).contains("blocker_auth");
        assertThat(String.valueOf(second.get("spawned"))).doesNotContain(taskId);
        assertThat(String.valueOf(second.get("auto_blocked"))).doesNotContain(taskId);
        Map<String, Object> detail = service.task(taskId);
        assertThat(detail.get("status")).isEqualTo("ready");
        assertThat(detail.get("spawn_failures")).isEqualTo(Integer.valueOf(1));
        assertThat(String.valueOf(service.events(taskId))).contains("respawn_guarded").contains("blocker_auth");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldReclaimCrashedWorkerPidBeforeDispatchingReadyTasks() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String crashedTaskId = createTask(service, "已崩溃执行任务", "worker-a", "planner", "ready", null);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:dead-worker");
        claim.put("worker_id", "dead-worker");
        claim.put("worker_pid", 99999999L);
        claim.put("ttl_seconds", 3600);
        service.claim(crashedTaskId, claim);
        ageStartedAt(env, crashedTaskId, 60000L);
        String readyTaskId = createTask(service, "等待执行任务", "worker-b", "planner", "ready", null);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_in_progress", 1);
        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("crashed"))).contains(crashedTaskId);
        assertThat(String.valueOf(result.get("spawned"))).contains(crashedTaskId).contains("worker-a");
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(readyTaskId);
        assertThat(String.valueOf(service.events(crashedTaskId))).contains("crashed").contains("pid 99999999 not alive");
        assertThat(service.task(crashedTaskId).get("status")).isEqualTo("running");
        assertThat(service.task(readyTaskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldExposeTimedOutTaskIdsFromDispatch() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "运行超时派发任务", "worker-a", "planner", "ready", null);
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("max_runtime_seconds", 1);
        service.updateTask(taskId, update);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:timed-out-worker");
        claim.put("worker_id", "timed-out-worker");
        claim.put("ttl_seconds", 3600);
        service.claim(taskId, claim);
        ageStartedAt(env, taskId, 2000L);

        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("timed_out"))).contains(taskId);
        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker-a");
        assertThat(service.task(taskId).get("status")).isEqualTo("running");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldSkipFreshlyClaimedDeadPidDuringCrashGraceWindow() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "刚启动保护任务", "worker-a", "planner", "ready", null);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:fresh-worker");
        claim.put("worker_id", "fresh-worker");
        claim.put("worker_pid", 99999999L);
        claim.put("ttl_seconds", 3600);
        service.claim(taskId, claim);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("max_in_progress", 1);
        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("crashed"))).doesNotContain(taskId);
        assertThat(String.valueOf(service.events(taskId))).doesNotContain("crashed");
        assertThat(service.task(taskId).get("status")).isEqualTo("running");
        assertThat(service.task(taskId).get("worker_pid")).isEqualTo(Long.valueOf(99999999L));
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldReclaimRunningTaskWhenHeartbeatIsStale() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "心跳停滞任务", "worker-a", "planner", "ready", null);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:stale-worker");
        claim.put("worker_id", "stale-worker");
        claim.put("ttl_seconds", 3600);
        service.claim(taskId, claim);
        ageStartedAt(env, taskId, 7200000L);
        ageLastHeartbeatAt(env, taskId, 7200000L);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("stale_timeout_seconds", 3600);
        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("stale"))).contains(taskId);
        assertThat(String.valueOf(result.get("spawned"))).contains(taskId).contains("worker-a");
        assertThat(String.valueOf(service.events(taskId)))
                .contains("stale")
                .contains("heartbeat stale")
                .contains("last_heartbeat_at");
        assertThat(service.task(taskId).get("status")).isEqualTo("running");
        assertThat(spawner.spawned).isEqualTo(1);
    }

    @Test
    void shouldNotRunHeartbeatStaleDetectionWhenTimeoutIsDisabled() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "停用心跳停滞检测任务", "worker-a", "planner", "ready", null);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:heartbeat-disabled-worker");
        claim.put("worker_id", "heartbeat-disabled-worker");
        claim.put("ttl_seconds", 3600);
        service.claim(taskId, claim);
        ageStartedAt(env, taskId, 7200000L);
        ageLastHeartbeatAt(env, taskId, 7200000L);

        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("stale"))).doesNotContain(taskId);
        assertThat(String.valueOf(service.events(taskId))).doesNotContain("stale");
        assertThat(service.task(taskId).get("status")).isEqualTo("running");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldSkipRecentlyStartedTaskDuringHeartbeatStaleDetection() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "刚启动心跳任务", "worker-a", "planner", "ready", null);
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("claimer", "host:fresh-worker");
        claim.put("worker_id", "fresh-worker");
        claim.put("ttl_seconds", 3600);
        service.claim(taskId, claim);
        ageStartedAt(env, taskId, 1800000L);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("stale_timeout_seconds", 3600);
        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("stale"))).doesNotContain(taskId);
        assertThat(String.valueOf(service.events(taskId))).doesNotContain("stale");
        assertThat(service.task(taskId).get("status")).isEqualTo("running");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldReportRespawnGuardInDryRunWithoutWritingEvent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "预演保护任务", "worker", "planner", "ready", null);
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("last_spawn_error", "403 forbidden: unauthorized");
        update.put("spawn_failures", 1);
        service.updateTask(taskId, update);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("dry_run", true);

        Map<String, Object> result = dispatcher.dispatch(body);

        assertThat(String.valueOf(result.get("respawn_guarded"))).contains(taskId).contains("blocker_auth");
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(taskId);
        assertThat(String.valueOf(service.events(taskId))).doesNotContain("respawn_guarded");
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldGuardReadyTaskWithRecentSuccessfulRun() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "近期成功任务", "worker", "planner", "ready", null);
        KanbanRunRecord run = new KanbanRunRecord();
        run.setTaskId(taskId);
        run.setProfile("worker");
        run.setStatus("done");
        run.setOutcome("completed");
        run.setStartedAt(System.currentTimeMillis() - 1000L);
        run.setEndedAt(System.currentTimeMillis());
        repository.addRun(run);

        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("respawn_guarded"))).contains(taskId).contains("recent_success");
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(taskId);
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldGuardReadyTaskWithRecentPullRequestComment() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String taskId = createTask(service, "已有变更请求任务", "worker", "planner", "ready", null);
        KanbanCommentRecord comment = new KanbanCommentRecord();
        comment.setTaskId(taskId);
        comment.setAuthor("worker");
        comment.setBody("Opened https://github.com/acme/example/pull/123 for review");
        comment.setCreatedAt(System.currentTimeMillis());
        repository.addComment(comment);

        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("respawn_guarded"))).contains(taskId).contains("active_pr");
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(taskId);
        assertThat(service.task(taskId).get("status")).isEqualTo("ready");
        assertThat(spawner.spawned).isEqualTo(0);
    }

    @Test
    void shouldStillDispatchCleanReadyTaskWhenOtherTaskIsGuarded() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);

        String guardedTaskId = createTask(service, "保护任务", "worker-a", "planner", "ready", null);
        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("last_spawn_error", "billing subscription required");
        update.put("spawn_failures", 1);
        service.updateTask(guardedTaskId, update);
        String cleanTaskId = createTask(service, "干净任务", "worker-b", "planner", "ready", null);

        Map<String, Object> result = dispatcher.dispatch(new LinkedHashMap<String, Object>());

        assertThat(String.valueOf(result.get("respawn_guarded"))).contains(guardedTaskId).contains("blocker_auth");
        assertThat(String.valueOf(result.get("spawned"))).contains(cleanTaskId).contains("worker-b");
        assertThat(String.valueOf(result.get("spawned"))).doesNotContain(guardedTaskId);
        assertThat(service.task(guardedTaskId).get("status")).isEqualTo("ready");
        assertThat(service.task(cleanTaskId).get("status")).isEqualTo("running");
        assertThat(spawner.spawned).isEqualTo(1);
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
    void shouldParseAndExposeDaemonMaxInProgress() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SqliteKanbanRepository repository = new SqliteKanbanRepository(env.sqliteDatabase);
        KanbanService service = new KanbanService(repository);
        RecordingSpawner spawner = new RecordingSpawner();
        KanbanDispatcherService dispatcher = new KanbanDispatcherService(repository, service, spawner);
        service.setDispatcherService(dispatcher);

        Map<String, Object> options =
                dispatcher.options(
                        "--max 2 --max-in-progress 4 --max-in-progress-per-profile 1 --default-assignee fallback --interval 10");
        assertThat(options.get("max_spawn")).isEqualTo("2");
        assertThat(options.get("max_in_progress")).isEqualTo("4");
        assertThat(options.get("max_in_progress_per_profile")).isEqualTo("1");
        assertThat(options.get("default_assignee")).isEqualTo("fallback");

        Map<String, Object> started = dispatcher.startDaemon(options);

        assertThat(started.get("max_spawn")).isEqualTo(Integer.valueOf(2));
        assertThat(started.get("max_in_progress")).isEqualTo(Integer.valueOf(4));
        assertThat(started.get("max_in_progress_per_profile")).isEqualTo(Integer.valueOf(1));
        assertThat(started.get("default_assignee")).isEqualTo("fallback");
        assertThat(String.valueOf(service.daemonStatus())).contains("max_in_progress=4");
        assertThat(String.valueOf(service.daemonStatus())).contains("max_in_progress_per_profile=1");
        assertThat(String.valueOf(service.daemonStatus())).contains("default_assignee=fallback");
        dispatcher.stopDaemon();
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
        KanbanTaskRecord lastTask;
        String lastWorkspacePath;

        @Override
        public long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) {
            spawned++;
            lastTask = task;
            lastWorkspacePath = workspacePath;
            assertThat(workerContext).contains(task.getTaskId()).contains(task.getTitle());
            return 4242L;
        }
    }

    private static class CapturingOrchestrator implements ConversationOrchestrator {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile GatewayMessage lastMessage;

        @Override
        public GatewayReply handleIncoming(GatewayMessage message) {
            return GatewayReply.ok("");
        }

        @Override
        public GatewayReply runScheduled(GatewayMessage syntheticMessage) {
            lastMessage = syntheticMessage;
            latch.countDown();
            return GatewayReply.ok("ok");
        }

        @Override
        public GatewayReply resumePending(String sourceKey) {
            return GatewayReply.ok("");
        }

        GatewayMessage awaitMessage() throws InterruptedException {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            return lastMessage;
        }
    }

    private void ageStartedAt(TestEnvironment env, String taskId, long millisAgo) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            long startedAt = System.currentTimeMillis() - millisAgo;
            PreparedStatement statement =
                    connection.prepareStatement("update kanban_tasks set started_at = ? where task_id = ?");
            statement.setLong(1, startedAt);
            statement.setString(2, taskId);
            statement.executeUpdate();
            statement.close();

            PreparedStatement runStatement =
                    connection.prepareStatement(
                            "update kanban_runs set started_at = ? where run_id = (select current_run_id from kanban_tasks where task_id = ?)");
            runStatement.setLong(1, startedAt);
            runStatement.setString(2, taskId);
            runStatement.executeUpdate();
            runStatement.close();
        } finally {
            connection.close();
        }
    }

    private void ageLastHeartbeatAt(TestEnvironment env, String taskId, long millisAgo) throws Exception {
        Connection connection = env.sqliteDatabase.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("update kanban_tasks set last_heartbeat_at = ? where task_id = ?");
            statement.setLong(1, System.currentTimeMillis() - millisAgo);
            statement.setString(2, taskId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private static class FailingSpawner implements KanbanWorkerSpawner {
        private final String message;
        int spawned;

        FailingSpawner() {
            this("spawn failed");
        }

        FailingSpawner(String message) {
            this.message = message;
        }

        @Override
        public long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) {
            spawned++;
            throw new IllegalStateException(message);
        }
    }

    private static class ExplodingSpawner implements KanbanWorkerSpawner {
        @Override
        public long spawn(KanbanTaskRecord task, String workspacePath, String workerContext) {
            throw new RuntimeException("boom");
        }
    }
}
