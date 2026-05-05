package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.storage.repository.SqliteKanbanRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
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
}
