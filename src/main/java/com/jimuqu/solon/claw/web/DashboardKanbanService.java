package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.repository.GatewayPolicyRepository;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.scheduler.KanbanNotificationScheduler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Dashboard Kanban application service. */
public class DashboardKanbanService {
    private final KanbanService kanbanService;
    private final GatewayPolicyRepository gatewayPolicyRepository;
    private final KanbanNotificationScheduler notificationScheduler;

    public DashboardKanbanService(KanbanService kanbanService) {
        this(kanbanService, null, null);
    }

    public DashboardKanbanService(KanbanService kanbanService, GatewayPolicyRepository gatewayPolicyRepository) {
        this(kanbanService, gatewayPolicyRepository, null);
    }

    public DashboardKanbanService(
            KanbanService kanbanService,
            GatewayPolicyRepository gatewayPolicyRepository,
            KanbanNotificationScheduler notificationScheduler) {
        this.kanbanService = kanbanService;
        this.gatewayPolicyRepository = gatewayPolicyRepository;
        this.notificationScheduler = notificationScheduler;
    }

    public Map<String, Object> currentBoard() throws Exception {
        return kanbanService.currentBoard();
    }

    public List<Map<String, Object>> boards() throws Exception {
        return boards(false);
    }

    public List<Map<String, Object>> boards(boolean includeArchived) throws Exception {
        return kanbanService.boards(includeArchived);
    }

    public Map<String, Object> createBoard(Map<String, Object> body) throws Exception {
        return kanbanService.createBoard(body);
    }

    public Map<String, Object> switchBoard(String slug) throws Exception {
        return kanbanService.switchBoard(slug);
    }

    public Map<String, Object> renameBoard(String slug, Map<String, Object> body) throws Exception {
        return kanbanService.renameBoard(
                slug,
                body == null || body.get("name") == null ? null : String.valueOf(body.get("name")));
    }

    public Map<String, Object> removeBoard(String slug, boolean hardDelete) throws Exception {
        return kanbanService.removeBoard(slug, hardDelete);
    }

    public List<Map<String, Object>> tasks(String board, String status, boolean includeArchived)
            throws Exception {
        return kanbanService.tasks(board, status, includeArchived);
    }

    public List<Map<String, Object>> tasks(
            String board, String status, boolean includeArchived, String assignee, String tenant)
            throws Exception {
        return tasks(board, status, includeArchived, assignee, tenant, null);
    }

    public List<Map<String, Object>> tasks(
            String board,
            String status,
            boolean includeArchived,
            String assignee,
            String tenant,
            String orderBy)
            throws Exception {
        return tasks(board, status, includeArchived, assignee, tenant, orderBy, null, null);
    }

    public List<Map<String, Object>> tasks(
            String board,
            String status,
            boolean includeArchived,
            String assignee,
            String tenant,
            String orderBy,
            String workflowTemplateId,
            String currentStepKey)
            throws Exception {
        return tasks(
                board,
                status,
                includeArchived,
                assignee,
                tenant,
                orderBy,
                workflowTemplateId,
                currentStepKey,
                null);
    }

    public List<Map<String, Object>> tasks(
            String board,
            String status,
            boolean includeArchived,
            String assignee,
            String tenant,
            String orderBy,
            String workflowTemplateId,
            String currentStepKey,
            String sessionId)
            throws Exception {
        return kanbanService.tasks(
                board,
                status,
                includeArchived,
                assignee,
                tenant,
                orderBy,
                workflowTemplateId,
                currentStepKey,
                sessionId);
    }

    public Map<String, Object> task(String taskId) throws Exception {
        return kanbanService.task(taskId);
    }

    public Map<String, Object> taskDrawer(String taskId, int logTailBytes) throws Exception {
        return kanbanService.taskDrawer(taskId, logTailBytes);
    }

    public Map<String, Object> createTask(Map<String, Object> body) throws Exception {
        return kanbanService.createTask(body);
    }

    public Map<String, Object> updateTask(String taskId, Map<String, Object> body)
            throws Exception {
        rejectDirectRunningStatus(body);
        return kanbanService.updateTask(taskId, body);
    }

    public Map<String, Object> link(Map<String, Object> body) throws Exception {
        return kanbanService.link(text(body, "parent_id"), text(body, "child_id"));
    }

    public Map<String, Object> unlink(Map<String, Object> body) throws Exception {
        return kanbanService.unlink(text(body, "parent_id"), text(body, "child_id"));
    }

    public Map<String, Object> status(String taskId, Map<String, Object> body) throws Exception {
        rejectDirectRunningStatus(body);
        return kanbanService.status(
                taskId,
                body == null ? null : String.valueOf(body.get("status")),
                body == null || body.get("result") == null ? null : String.valueOf(body.get("result")),
                body == null || body.get("summary") == null ? null : String.valueOf(body.get("summary")),
                body == null ? null : body.get("created_cards"));
    }

    public Map<String, Object> bulkTasks(Map<String, Object> body) throws Exception {
        List<String> taskIds = taskIds(body);
        if (taskIds.isEmpty()) {
            throw new IllegalArgumentException("ids must contain at least one kanban task id");
        }
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (String taskId : taskIds) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", taskId);
            try {
                if (body != null && body.containsKey("status")) {
                    status(taskId, body);
                } else if (body != null && body.containsKey("priority")) {
                    updateTask(taskId, body);
                } else if (body != null && body.containsKey("assignee")) {
                    reassign(taskId, body);
                } else if (body != null && Boolean.TRUE.equals(body.get("archive"))) {
                    Map<String, Object> archiveBody = new LinkedHashMap<String, Object>();
                    archiveBody.put("status", "archived");
                    status(taskId, archiveBody);
                } else {
                    throw new IllegalArgumentException("bulk task update requires status, priority, assignee, or archive");
                }
                item.put("ok", Boolean.TRUE);
            } catch (Exception e) {
                item.put("ok", Boolean.FALSE);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("results", results);
        return result;
    }

    public Map<String, Object> step(String taskId, Map<String, Object> body) throws Exception {
        return kanbanService.step(
                taskId,
                text(body, "step_key"),
                text(body, "workflow_template_id"),
                text(body, "note"),
                text(body, "actor"));
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

    public Map<String, Object> unblock(String taskId) throws Exception {
        return kanbanService.unblock(taskId);
    }

    public Map<String, Object> edit(String taskId, Map<String, Object> body) throws Exception {
        Object metadata = body == null ? null : body.get("metadata");
        return kanbanService.edit(
                taskId,
                body == null || body.get("result") == null ? null : String.valueOf(body.get("result")),
                body == null || body.get("summary") == null ? null : String.valueOf(body.get("summary")),
                metadata == null ? null : ONode.serialize(metadata));
    }

    public List<Map<String, Object>> runs(String taskId) throws Exception {
        return kanbanService.runs(taskId);
    }

    public List<Map<String, Object>> runs(String taskId, String stateType, String stateName)
            throws Exception {
        return kanbanService.runs(taskId, stateType, stateName);
    }

    public List<Map<String, Object>> events(String taskId) throws Exception {
        return kanbanService.events(taskId);
    }

    public Map<String, Object> context(String taskId) throws Exception {
        return kanbanService.context(taskId);
    }

    public List<Map<String, Object>> diagnostics(String taskId) throws Exception {
        return kanbanService.diagnostics(taskId);
    }

    public Map<String, Object> stats() throws Exception {
        return kanbanService.stats();
    }

    public Map<String, Object> guide(String board) throws Exception {
        return kanbanService.guide(board);
    }

    public List<Map<String, Object>> assignees(String board) throws Exception {
        return kanbanService.assignees(board);
    }

    public List<Map<String, Object>> watch(String assignee, String tenant, String kinds, int limit)
            throws Exception {
        return kanbanService.watch(assignee, tenant, kinds, limit);
    }

    public Map<String, Object> notifySubscribe(Map<String, Object> body) throws Exception {
        return kanbanService.notifySubscribe(body);
    }

    public List<Map<String, Object>> notifyHomeChannels(String taskId) throws Exception {
        if (gatewayPolicyRepository == null) {
            return new ArrayList<Map<String, Object>>();
        }
        List<HomeChannelRecord> homes = gatewayPolicyRepository.listHomeChannels();
        List<Map<String, Object>> subscriptions =
                StrUtil.isBlank(taskId) ? new ArrayList<Map<String, Object>>() : kanbanService.notifyList(taskId);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (HomeChannelRecord home : homes) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("platform", home.getPlatform() == null ? null : home.getPlatform().name().toLowerCase());
            item.put("chat_id", home.getChatId());
            item.put("thread_id", home.getThreadId());
            item.put("chat_name", home.getChatName());
            item.put("updated_at", Long.valueOf(home.getUpdatedAt()));
            item.put("subscribed", Boolean.valueOf(isSubscribed(home, subscriptions)));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> notifyHomeSubscribe(String taskId, String platformName) throws Exception {
        PlatformType platform = requirePlatform(platformName);
        HomeChannelRecord home = requireHome(platform);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("task_id", taskId);
        body.put("platform", platform.name().toLowerCase());
        body.put("chat_id", home.getChatId());
        body.put("thread_id", home.getThreadId());
        return kanbanService.notifySubscribe(body);
    }

    public Map<String, Object> notifyHomeUnsubscribe(String taskId, String platformName) throws Exception {
        PlatformType platform = requirePlatform(platformName);
        HomeChannelRecord home = requireHome(platform);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("task_id", taskId);
        body.put("platform", platform.name().toLowerCase());
        body.put("chat_id", home.getChatId());
        body.put("thread_id", home.getThreadId());
        return kanbanService.notifyUnsubscribe(body);
    }

    public Map<String, Object> notifyClaim(Map<String, Object> body) throws Exception {
        return kanbanService.notifyClaim(body);
    }

    public Map<String, Object> notifyAdvance(Map<String, Object> body) throws Exception {
        return kanbanService.notifyAdvance(body);
    }

    public Map<String, Object> notifyRewind(Map<String, Object> body) throws Exception {
        return kanbanService.notifyRewind(body);
    }

    public Map<String, Object> notifyDeliver() throws Exception {
        return kanbanService.notifyDeliver();
    }

    public Map<String, Object> notifyDeliveryStatus() {
        if (notificationScheduler == null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("available", Boolean.FALSE);
            return result;
        }
        Map<String, Object> result = notificationScheduler.status();
        result.put("available", Boolean.TRUE);
        return result;
    }

    public List<Map<String, Object>> notifyList(String taskId) throws Exception {
        return kanbanService.notifyList(taskId);
    }

    public Map<String, Object> notifyUnsubscribe(Map<String, Object> body) throws Exception {
        return kanbanService.notifyUnsubscribe(body);
    }

    public Map<String, Object> log(String taskId, int tailBytes) throws Exception {
        return kanbanService.log(taskId, tailBytes);
    }

    public Map<String, Object> gc(Map<String, Object> body) throws Exception {
        return kanbanService.gc(body);
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

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> taskIds(Map<String, Object> body) {
        List<String> result = new ArrayList<String>();
        Object value = body == null ? null : body.get("ids");
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
        } else if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private void rejectDirectRunningStatus(Map<String, Object> body) {
        if (body != null
                && body.get("status") != null
                && StrUtil.equalsIgnoreCase("running", String.valueOf(body.get("status")))) {
            throw new IllegalArgumentException(
                    "Dashboard cannot set kanban tasks to running directly; use claim or dispatch.");
        }
    }

    private PlatformType requirePlatform(String platformName) {
        PlatformType platform = PlatformType.fromName(platformName);
        if (platform == null) {
            throw new IllegalArgumentException("未知平台 / Unknown platform: " + platformName);
        }
        return platform;
    }

    private HomeChannelRecord requireHome(PlatformType platform) throws Exception {
        if (gatewayPolicyRepository == null) {
            throw new IllegalStateException("home channel repository is not available");
        }
        HomeChannelRecord home = gatewayPolicyRepository.getHomeChannel(platform);
        if (home == null || StrUtil.isBlank(home.getChatId())) {
            throw new IllegalArgumentException("未配置 home channel / Home channel is not configured: " + platform);
        }
        return home;
    }

    private boolean isSubscribed(HomeChannelRecord home, List<Map<String, Object>> subscriptions) {
        if (home == null || home.getPlatform() == null) {
            return false;
        }
        String platform = home.getPlatform().name().toLowerCase();
        for (Map<String, Object> subscription : subscriptions) {
            if (StrUtil.equals(platform, text(subscription, "platform"))
                    && StrUtil.equals(home.getChatId(), text(subscription, "chat_id"))
                    && StrUtil.equals(StrUtil.nullToEmpty(home.getThreadId()), StrUtil.nullToEmpty(text(subscription, "thread_id")))) {
                return true;
            }
        }
        return false;
    }
}
