package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.kanban.KanbanService;
import java.util.List;
import java.util.Map;

/** Dashboard Kanban application service. */
public class DashboardKanbanService {
    private final KanbanService kanbanService;

    public DashboardKanbanService(KanbanService kanbanService) {
        this.kanbanService = kanbanService;
    }

    public Map<String, Object> currentBoard() throws Exception {
        return kanbanService.currentBoard();
    }

    public List<Map<String, Object>> boards() throws Exception {
        return kanbanService.boards();
    }

    public Map<String, Object> createBoard(Map<String, Object> body) throws Exception {
        return kanbanService.createBoard(body);
    }

    public Map<String, Object> switchBoard(String slug) throws Exception {
        return kanbanService.switchBoard(slug);
    }

    public List<Map<String, Object>> tasks(String board, String status, boolean includeArchived)
            throws Exception {
        return kanbanService.tasks(board, status, includeArchived);
    }

    public Map<String, Object> task(String taskId) throws Exception {
        return kanbanService.task(taskId);
    }

    public Map<String, Object> createTask(Map<String, Object> body) throws Exception {
        return kanbanService.createTask(body);
    }

    public Map<String, Object> updateTask(String taskId, Map<String, Object> body)
            throws Exception {
        return kanbanService.updateTask(taskId, body);
    }

    public Map<String, Object> status(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.status(
                taskId,
                body == null ? null : String.valueOf(body.get("status")),
                body == null || body.get("result") == null ? null : String.valueOf(body.get("result")));
    }

    public Map<String, Object> comment(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.comment(
                taskId,
                body == null || body.get("author") == null ? "user" : String.valueOf(body.get("author")),
                body == null || body.get("body") == null ? "" : String.valueOf(body.get("body")));
    }

    public Map<String, Object> delete(String taskId) throws Exception {
        return kanbanService.delete(taskId);
    }
}
