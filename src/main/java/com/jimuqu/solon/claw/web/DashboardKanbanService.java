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
                body == null || body.get("result") == null ? null : String.valueOf(body.get("result")),
                body == null || body.get("summary") == null ? null : String.valueOf(body.get("summary")),
                body == null ? null : body.get("created_cards"));
    }

    public Map<String, Object> reclaim(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.reclaim(
                taskId,
                body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason")));
    }

    public Map<String, Object> reassign(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.reassign(
                taskId,
                body == null || body.get("assignee") == null ? null : String.valueOf(body.get("assignee")),
                body != null && Boolean.parseBoolean(String.valueOf(body.get("reclaim_first"))),
                body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason")));
    }

    public Map<String, Object> retry(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.retry(
                taskId,
                body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason")));
    }

    public Map<String, Object> claim(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.claim(taskId, body);
    }

    public Map<String, Object> claimNext(Map<String, Object> body) throws Exception {
        return kanbanService.claimNext(body);
    }

    public Map<String, Object> heartbeatClaim(String taskId, Map<String, Object> body)
            throws Exception {
        return kanbanService.heartbeatClaim(taskId, body);
    }

    public Map<String, Object> heartbeatWorker(String taskId, Map<String, Object> body)
            throws Exception {
        return kanbanService.heartbeatWorker(taskId, body);
    }

    public Map<String, Object> markSpawnFailure(String taskId, Map<String, Object> body)
            throws Exception {
        return kanbanService.markSpawnFailure(taskId, body);
    }

    public Map<String, Object> releaseStaleClaims() throws Exception {
        return kanbanService.releaseStaleClaims();
    }

    public Map<String, Object> reclaimTimedOutWorkers() throws Exception {
        return kanbanService.reclaimTimedOutWorkers();
    }

    public Map<String, Object> dispatch(Map<String, Object> body) throws Exception {
        return kanbanService.dispatch(body);
    }

    public Map<String, Object> daemonStatus() {
        return kanbanService.daemonStatus();
    }

    public Map<String, Object> startDaemon(Map<String, Object> body) {
        return kanbanService.startDaemon(body);
    }

    public Map<String, Object> stopDaemon() {
        return kanbanService.stopDaemon();
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
