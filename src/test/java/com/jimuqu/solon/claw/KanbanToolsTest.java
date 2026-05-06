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
    void shouldExposeHermesStyleKanbanWorkerTools() throws Exception {
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
                        Long.valueOf(90));
        assertThat(created).contains("\"success\":true").contains("子任务").contains("task_id");

        String childId = String.valueOf(service.createTask(childBody(parentId)).get("id"));
        String duplicate = tools.kanbanCreate("子任务", "worker", "执行子任务", parentId, "child-key", null, null);
        assertThat(duplicate).contains(childId);

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

        String blocked = tools.kanbanBlock(parentId, "需要人工确认");
        assertThat(blocked).contains("\"success\":true").contains("blocked");

        service.status(parentId, "ready", null);
        service.claim(parentId, claim("lead-worker"));
        String completed = tools.kanbanComplete(parentId, "完成摘要", "完成结果", "{\"tests\":\"ok\"}", null);
        assertThat(completed).contains("\"success\":true").contains("Completed Kanban task").contains("tests");
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
