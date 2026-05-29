package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.KanbanTools;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class KanbanToolsTest {
    @Test
    void shouldExposeJimuquStyleKanbanWorkerTools() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service = new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));
        KanbanTools tools = new KanbanTools(service);

        String parentId = task(service, "父任务", "lead");
        service.status(parentId, "ready", null);
        service.claim(parentId, claim("lead-worker"));

        String created =
                tools.kanbanCreate(
                        "子任务",
                        "worker",
                        "执行子任务",
                        parentId,
                        "child-key",
                        "kanban-worker",
                        Integer.valueOf(2),
                        Long.valueOf(90));
        assertThat(created).contains("\"success\":true").contains("子任务").contains("task_id").contains("max_retries");

        String childId = String.valueOf(service.createTask(childBody(parentId)).get("id"));
        String duplicate = tools.kanbanCreate("子任务", "worker", "执行子任务", parentId, "child-key", null, null, null);
        assertThat(duplicate).contains(childId);

        String schemaCreated =
                tools.kanbanSchemaCreate(
                        "{"
                                + "\"title\":\"结构化任务\","
                                + "\"assignee\":\"schema-worker\","
                                + "\"body\":\"按结构化字段执行\","
                                + "\"parents\":[\""
                                + parentId
                                + "\"],"
                                + "\"skills\":[\"schema-skill\",\"review\"],"
                                + "\"workflow_template_id\":\"delivery\","
                                + "\"current_step_key\":\"review\","
                                + "\"max_runtime_seconds\":120"
                                + "}");
        assertThat(schemaCreated)
                .contains("\"success\":true")
                .contains("结构化任务")
                .contains("schema-skill")
                .contains("workflow_template_id")
                .contains("current_step_key");

        String reviewId = task(service, "复核任务", "reviewer");
        String link = tools.kanbanLink(childId, reviewId);
        assertThat(link).contains("\"success\":true").contains(childId).contains(reviewId);
        assertThat(String.valueOf(service.task(reviewId).get("parents"))).contains(childId).contains("子任务");
        assertThat(tools.kanbanLink(reviewId, childId)).contains("\"success\":false").contains("dependency cycle");
        String unlink = tools.kanbanUnlink(childId, reviewId);
        assertThat(unlink).contains("\"success\":true").contains(childId).contains(reviewId);
        assertThat(String.valueOf(service.task(reviewId).get("parents"))).doesNotContain(childId);
        assertThat(tools.kanbanUnlink(childId, reviewId)).contains("\"success\":false").contains("not found");

        String comment = tools.kanbanComment(parentId, "交接信息", "worker");
        assertThat(comment).contains("\"success\":true").contains("交接信息");

        String show = tools.kanbanShow(parentId);
        assertThat(show).contains("worker_context").contains("父任务").contains("交接信息");

        String heartbeat = tools.kanbanHeartbeat(parentId, "working");
        assertThat(heartbeat).contains("\"success\":true").contains("heartbeat");

        String step = tools.kanbanStep(parentId, "review", "delivery", "准备复核");
        assertThat(step)
                .contains("\"success\":true")
                .contains("Advanced Kanban step")
                .contains("current_step_key")
                .contains("review");
        assertThat(String.valueOf(service.task(parentId).get("events"))).contains("step_changed").contains("准备复核");

        String blocked = tools.kanbanBlock(parentId, "需要人工确认");
        assertThat(blocked).contains("\"success\":true").contains("blocked");

        service.status(parentId, "ready", null);
        service.claim(parentId, claim("lead-worker"));
        String completed = tools.kanbanComplete(parentId, "完成摘要", "完成结果", "{\"tests\":\"ok\"}", null);
        assertThat(completed).contains("\"success\":true").contains("Completed Kanban task").contains("tests");
    }

    @Test
    void shouldRedactSecretsFromKanbanToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service = new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));
        KanbanTools tools = new KanbanTools(service);

        String secretTaskId = "missing-ghp_1234567890abcdef";
        String parentId = task(service, "父任务", "lead");
        String show = tools.kanbanShow(secretTaskId);
        String link = tools.kanbanLink(parentId, "child-ghp_1234567890abcdef");

        assertThat(show)
                .contains("\"success\":false")
                .contains("missing-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
        assertThat(link)
                .contains("\"success\":false")
                .contains("child-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldRedactSecretsFromKanbanToolSuccessResultsOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        KanbanService service = new KanbanService(new SqliteKanbanRepository(env.sqliteDatabase));
        KanbanTools tools = new KanbanTools(service);

        String created =
                tools.kanbanCreate(
                        "任务 token=ghp_kanbantitle12345",
                        "worker",
                        "正文 Authorization: Bearer ghp_kanbanbody12345",
                        null,
                        "key-ghp_kanbanidempotency12345",
                        null,
                        null,
                        null);

        assertThat(created)
                .contains("任务 token=***")
                .doesNotContain("ghp_kanbantitle12345")
                .doesNotContain("ghp_kanbanbody12345")
                .doesNotContain("ghp_kanbanidempotency12345");
        Map<?, ?> createdPayload = (Map<?, ?>) org.noear.snack4.ONode.ofJson(created).toData();
        String taskId = String.valueOf(createdPayload.get("task_id"));

        String comment = tools.kanbanComment(taskId, "评论 token=ghp_kanbancomment12345", "worker");
        String blocked =
                tools.kanbanBlock(taskId, "等待 Authorization: Bearer ghp_kanbanblock12345");
        service.status(taskId, "ready", null);
        service.claim(taskId, claim("worker"));
        String completed =
                tools.kanbanComplete(
                        taskId,
                        "摘要 token=ghp_kanbansummary12345",
                        "结果 token=ghp_kanbanresult12345",
                        "{\"access_token\":\"ghp_kanbanmetadata12345\"}",
                        null);
        String show = tools.kanbanShow(taskId);

        assertThat(comment)
                .contains("评论 token=***")
                .doesNotContain("ghp_kanbancomment12345");
        assertThat(blocked)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_kanbanblock12345");
        assertThat(completed)
                .contains("摘要 token=***")
                .contains("\"access_token\":\"***\"")
                .doesNotContain("ghp_kanbansummary12345")
                .doesNotContain("ghp_kanbanresult12345")
                .doesNotContain("ghp_kanbanmetadata12345");
        assertThat(show)
                .contains("Authorization: Bearer ***")
                .doesNotContain("ghp_kanbantitle12345")
                .doesNotContain("ghp_kanbanbody12345")
                .doesNotContain("ghp_kanbancomment12345")
                .doesNotContain("ghp_kanbanblock12345")
                .doesNotContain("ghp_kanbansummary12345")
                .doesNotContain("ghp_kanbanresult12345");

        String persisted = String.valueOf(service.task(taskId));
        assertThat(persisted)
                .contains("ghp_kanbantitle12345")
                .contains("ghp_kanbanbody12345")
                .contains("ghp_kanbancomment12345")
                .contains("ghp_kanbanresult12345");
    }

    private String task(KanbanService service, String title, String assignee) throws Exception {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        body.put("assignee", assignee);
        body.put("created_by", assignee);
        return String.valueOf(service.createTask(body).get("id"));
    }

    private Map<String, Object> claim(String worker) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("claimer", "host:" + worker);
        body.put("worker_id", worker);
        body.put("ttl_seconds", 60);
        return body;
    }

    private Map<String, Object> childBody(String parentId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", "子任务");
        body.put("assignee", "worker");
        body.put("body", "执行子任务");
        body.put("parents", java.util.Collections.singletonList(parentId));
        body.put("idempotency_key", "child-key");
        return body;
    }
}
