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
    public List<Map<String, Object>> boards(Context context) throws Exception {
        return kanbanService.boards(Boolean.parseBoolean(String.valueOf(context.param("archived"))));
    }

    @Mapping(value = "/api/kanban/boards", method = MethodType.POST)
    public Map<String, Object> createBoard(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.createBoard(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/boards/{slug}/switch", method = MethodType.POST)
    public Map<String, Object> switchBoard(String slug) throws Exception {
        return safeKanban(
                Context.current(),
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.switchBoard(slug);
                    }
                });
    }

    @Mapping(value = "/api/kanban/boards/{slug}", method = MethodType.PUT)
    public Map<String, Object> renameBoard(String slug, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.renameBoard(slug, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/boards/{slug}", method = MethodType.DELETE)
    public Map<String, Object> removeBoard(String slug, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.removeBoard(
                                slug,
                                Boolean.parseBoolean(String.valueOf(context.param("delete"))));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks", method = MethodType.GET)
    public List<Map<String, Object>> tasks(Context context) throws Exception {
        return kanbanService.tasks(
                context.param("board"),
                context.param("status"),
                Boolean.parseBoolean(String.valueOf(context.param("archived"))),
                context.param("assignee"),
                context.param("tenant"),
                taskOrderBy(context),
                firstParam(context, "workflow_template_id", "workflow"),
                firstParam(context, "current_step_key", "step"),
                firstParam(context, "session_id", "session"));
    }

    @Mapping(value = "/api/kanban/tasks", method = MethodType.POST)
    public Map<String, Object> createTask(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.createTask(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}", method = MethodType.GET)
    public Map<String, Object> task(String taskId) throws Exception {
        return DashboardResponse.ok(kanbanService.task(taskId));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/drawer", method = MethodType.GET)
    public Map<String, Object> taskDrawer(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.taskDrawer(taskId, intParam(context.param("tail"), 4096)));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}", method = MethodType.PUT)
    public Map<String, Object> updateTask(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.updateTask(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/links", method = MethodType.POST)
    public Map<String, Object> link(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.link(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/links/remove", method = MethodType.POST)
    public Map<String, Object> unlink(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.unlink(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/status", method = MethodType.POST)
    public Map<String, Object> status(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.status(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/bulk", method = MethodType.POST)
    public Map<String, Object> bulkTasks(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.bulkTasks(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/step", method = MethodType.POST)
    public Map<String, Object> step(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.step(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/reclaim", method = MethodType.POST)
    public Map<String, Object> reclaim(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.reclaim(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/reassign", method = MethodType.POST)
    public Map<String, Object> reassign(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.reassign(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/retry", method = MethodType.POST)
    public Map<String, Object> retry(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.retry(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/unblock", method = MethodType.POST)
    public Map<String, Object> unblock(String taskId) throws Exception {
        return safeKanban(
                Context.current(),
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.unblock(taskId);
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/edit", method = MethodType.POST)
    public Map<String, Object> edit(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.edit(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/runs", method = MethodType.GET)
    public List<Map<String, Object>> runs(String taskId, Context context) throws Exception {
        return kanbanService.runs(
                taskId,
                firstParam(context, "state_type", "stateType"),
                firstParam(context, "state_name", "state"));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/events", method = MethodType.GET)
    public List<Map<String, Object>> events(String taskId) throws Exception {
        return kanbanService.events(taskId);
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/context", method = MethodType.GET)
    public Map<String, Object> workerContext(String taskId) throws Exception {
        return DashboardResponse.ok(kanbanService.context(taskId));
    }

    @Mapping(value = "/api/kanban/diagnostics", method = MethodType.GET)
    public List<Map<String, Object>> diagnostics(Context context) throws Exception {
        return kanbanService.diagnostics(context.param("task"));
    }

    @Mapping(value = "/api/kanban/stats", method = MethodType.GET)
    public Map<String, Object> stats() throws Exception {
        return DashboardResponse.ok(kanbanService.stats());
    }

    @Mapping(value = "/api/kanban/guide", method = MethodType.GET)
    public Map<String, Object> guide(Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.guide(context.param("board")));
    }

    @Mapping(value = "/api/kanban/assignees", method = MethodType.GET)
    public List<Map<String, Object>> assignees(Context context) throws Exception {
        return kanbanService.assignees(context.param("board"));
    }

    @Mapping(value = "/api/kanban/watch", method = MethodType.GET)
    public List<Map<String, Object>> watch(Context context) throws Exception {
        return kanbanService.watch(
                context.param("assignee"),
                context.param("tenant"),
                context.param("kinds"),
                intParam(context.param("limit"), 200));
    }

    @Mapping(value = "/api/kanban/notify-subscriptions", method = MethodType.POST)
    public Map<String, Object> notifySubscribe(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifySubscribe(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/notify-subscriptions", method = MethodType.GET)
    public List<Map<String, Object>> notifyList(Context context) throws Exception {
        return kanbanService.notifyList(context.param("task"));
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/home-channels", method = MethodType.GET)
    public List<Map<String, Object>> notifyHomeChannels(Context context) throws Exception {
        return kanbanService.notifyHomeChannels(context.param("task"));
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/home-subscribe/{platform}", method = MethodType.POST)
    public Map<String, Object> notifyHomeSubscribe(String taskId, String platform, Context context)
            throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyHomeSubscribe(taskId, platform);
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/home-subscribe/{platform}", method = MethodType.DELETE)
    public Map<String, Object> notifyHomeUnsubscribe(String taskId, String platform, Context context)
            throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyHomeUnsubscribe(taskId, platform);
                    }
                });
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/claim", method = MethodType.POST)
    public Map<String, Object> notifyClaim(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyClaim(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/advance", method = MethodType.POST)
    public Map<String, Object> notifyAdvance(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyAdvance(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/rewind", method = MethodType.POST)
    public Map<String, Object> notifyRewind(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyRewind(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/deliver", method = MethodType.POST)
    public Map<String, Object> notifyDeliver() throws Exception {
        return safeKanban(
                Context.current(),
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyDeliver();
                    }
                });
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/delivery-status", method = MethodType.GET)
    public Map<String, Object> notifyDeliveryStatus() {
        return DashboardResponse.ok(kanbanService.notifyDeliveryStatus());
    }

    @Mapping(value = "/api/kanban/notify-subscriptions/remove", method = MethodType.POST)
    public Map<String, Object> notifyUnsubscribe(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.notifyUnsubscribe(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/log", method = MethodType.GET)
    public Map<String, Object> log(String taskId, Context context) throws Exception {
        return DashboardResponse.ok(kanbanService.log(taskId, intParam(context.param("tail"), 0)));
    }

    @Mapping(value = "/api/kanban/gc", method = MethodType.POST)
    public Map<String, Object> gc(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.gc(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/claim", method = MethodType.POST)
    public Map<String, Object> claim(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.claim(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/claim-next", method = MethodType.POST)
    public Map<String, Object> claimNext(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.claimNext(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/heartbeat-claim", method = MethodType.POST)
    public Map<String, Object> heartbeatClaim(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.heartbeatClaim(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/heartbeat", method = MethodType.POST)
    public Map<String, Object> heartbeatWorker(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.heartbeatWorker(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/spawn-failure", method = MethodType.POST)
    public Map<String, Object> markSpawnFailure(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.markSpawnFailure(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/release-stale", method = MethodType.POST)
    public Map<String, Object> releaseStaleClaims() throws Exception {
        return safeKanban(
                Context.current(),
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.releaseStaleClaims();
                    }
                });
    }

    @Mapping(value = "/api/kanban/reclaim-timeouts", method = MethodType.POST)
    public Map<String, Object> reclaimTimedOutWorkers() throws Exception {
        return safeKanban(
                Context.current(),
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.reclaimTimedOutWorkers();
                    }
                });
    }

    @Mapping(value = "/api/kanban/dispatch", method = MethodType.POST)
    public Map<String, Object> dispatch(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.dispatch(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/daemon", method = MethodType.GET)
    public Map<String, Object> daemonStatus() {
        return DashboardResponse.ok(kanbanService.daemonStatus());
    }

    @Mapping(value = "/api/kanban/daemon/start", method = MethodType.POST)
    public Map<String, Object> startDaemon(Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.startDaemon(body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/daemon/stop", method = MethodType.POST)
    public Map<String, Object> stopDaemon() {
        return DashboardResponse.ok(kanbanService.stopDaemon());
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}/comments", method = MethodType.POST)
    public Map<String, Object> comment(String taskId, Context context) throws Exception {
        return safeKanban(
                context,
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.comment(taskId, body(context));
                    }
                });
    }

    @Mapping(value = "/api/kanban/tasks/{taskId}", method = MethodType.DELETE)
    public Map<String, Object> delete(String taskId) throws Exception {
        return safeKanban(
                Context.current(),
                new KanbanAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return kanbanService.delete(taskId);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) throws Exception {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return ONode.deserialize(node.toJson(), LinkedHashMap.class);
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private int intParam(String value, int defaultValue) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Map<String, Object> safeKanban(Context context, KanbanAction action) throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("KANBAN_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            if (context != null) {
                context.status(400);
            }
            return DashboardResponse.error("KANBAN_BAD_REQUEST", e.getMessage());
        }
    }

    private String taskOrderBy(Context context) {
        String orderBy = context == null ? null : context.param("order_by");
        if (orderBy == null || orderBy.trim().isEmpty()) {
            orderBy = context == null ? null : context.param("sort");
        }
        return orderBy;
    }

    private String firstParam(Context context, String first, String second) {
        String value = context == null ? null : context.param(first);
        if (value == null || value.trim().isEmpty()) {
            value = context == null ? null : context.param(second);
        }
        return value;
    }

    private interface KanbanAction {
        Map<String, Object> run() throws Exception;
    }
}
