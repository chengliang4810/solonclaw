package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard Kanban API. */
@Controller
public class DashboardKanbanController {
    private final DashboardKanbanService kanbanService;

    public DashboardKanbanController(DashboardKanbanService kanbanService) {
        this.kanbanService = kanbanService;
    }

    @Mapping(value = "/api/kanban", method = MethodType.GET)
    public Map<String, Object> currentBoard() throws Exception {
        return DashboardResponse.ok(kanbanService.currentBoard());
    }

    @Mapping(value = "/api/kanban/boards", method = MethodType.GET)
    public List<Map<String, Object>> boards() throws Exception {
        return kanbanService.boards();
    }

    @Mapping(value = "/api/kanban/boards", method = MethodType.POST)
    public Map<String, Object> createBoard(Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.createBoard(body(context)));
    }

    @Mapping(value = "/api/kanban/boards/{slug}/switch", method = MethodType.POST)
    public Map<String, Object> switchBoard(String slug) throws Exception {
        return DashboardResponse.ok(kanbanService.switchBoard(slug));
    }

    @Mapping(value = "/api/kanban/tasks", method = MethodType.GET)
    public List<Map<String, Object>> tasks(Context context) throws Exception {
        return kanbanService.tasks(
                context.param("board"),
                context.param("status"),
                Boolean.parseBoolean(String.valueOf(context.param("archived"))));
    }

    @Mapping(value = "/api/kanban/tasks", method = MethodType.POST)
    public Map<String, Object> createTask(Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.createTask(body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}", method = MethodType.GET)
    public Map<String, Object> task(String taskId) throws Exception {
        return DashboardResponse.ok(kanbanService.task(taskId));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}", method = MethodType.PUT)
    public Map<String, Object> updateTask(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.updateTask(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/status", method = MethodType.POST)
    public Map<String, Object> status(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.status(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/reclaim", method = MethodType.POST)
    public Map<String, Object> reclaim(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.reclaim(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/reassign", method = MethodType.POST)
    public Map<String, Object> reassign(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.reassign(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/retry", method = MethodType.POST)
    public Map<String, Object> retry(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.retry(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/claim", method = MethodType.POST)
    public Map<String, Object> claim(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.claim(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/claim-next", method = MethodType.POST)
    public Map<String, Object> claimNext(Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.claimNext(body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/heartbeat-claim", method = MethodType.POST)
    public Map<String, Object> heartbeatClaim(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.heartbeatClaim(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/heartbeat", method = MethodType.POST)
    public Map<String, Object> heartbeatWorker(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.heartbeatWorker(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/spawn-failure", method = MethodType.POST)
    public Map<String, Object> markSpawnFailure(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.markSpawnFailure(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/release-stale", method = MethodType.POST)
    public Map<String, Object> releaseStaleClaims() throws Exception {
        return DashboardResponse.ok(kanbanService.releaseStaleClaims());
    }

    @Mapping(value = "/api/kanban/reclaim-timeouts", method = MethodType.POST)
    public Map<String, Object> reclaimTimedOutWorkers() throws Exception {
        return DashboardResponse.ok(kanbanService.reclaimTimedOutWorkers());
    }

    @Mapping(value = "/api/kanban/dispatch", method = MethodType.POST)
    public Map<String, Object> dispatch(Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.dispatch(body(context)));
    }

    @Mapping(value = "/api/kanban/daemon", method = MethodType.GET)
    public Map<String, Object> daemonStatus() {
        return DashboardResponse.ok(kanbanService.daemonStatus());
    }

    @Mapping(value = "/api/kanban/daemon/start", method = MethodType.POST)
    public Map<String, Object> startDaemon(Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.startDaemon(body(context)));
    }

    @Mapping(value = "/api/kanban/daemon/stop", method = MethodType.POST)
    public Map<String, Object> stopDaemon() {
        return DashboardResponse.ok(kanbanService.stopDaemon());
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/comments", method = MethodType.POST)
    public Map<String, Object> comment(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.comment(taskId, body(context)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}", method = MethodType.DELETE)
    public Map<String, Object> delete(String taskId) throws Exception {
        return DashboardResponse.ok(kanbanService.delete(taskId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) throws Exception {
        String body = context.body();
        if (body == null || body.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return ONode.deserialize(ONode.ofJson(body).toJson(), LinkedHashMap.class);
    }
}
