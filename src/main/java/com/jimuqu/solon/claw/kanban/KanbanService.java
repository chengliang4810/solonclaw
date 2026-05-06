package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** Shared service for dashboard and slash-command Kanban operations. */
public class KanbanService {
    public static final List<String> STATUSES =
            Collections.unmodifiableList(
                    Arrays.asList("triage", "todo", "ready", "running", "blocked", "done", "archived"));
    private static final Pattern PROSE_TASK_ID =
            Pattern.compile("\\b(?:KB-[0-9A-Fa-f]{8}|t_[0-9A-Fa-f]{6,})\\b");
    private static final int CONTEXT_MAX_TEXT = 4096;
    private static final long DEFAULT_CLAIM_TTL_SECONDS = 900L;

    private final KanbanRepository repository;
    private final AppConfig appConfig;
    private final AgentProfileService agentProfileService;
    private KanbanDispatcherService dispatcherService;

    public KanbanService(KanbanRepository repository) {
        this(repository, null);
    }

    public KanbanService(KanbanRepository repository, AppConfig appConfig) {
        this(repository, appConfig, null);
    }

    public KanbanService(
            KanbanRepository repository,
            AppConfig appConfig,
            AgentProfileService agentProfileService) {
        this.repository = repository;
        this.appConfig = appConfig;
        this.agentProfileService = agentProfileService;
    }

    public void setDispatcherService(KanbanDispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    public List<Map<String, Object>> boards() throws Exception {
        return boards(false);
    }

    public List<Map<String, Object>> boards(boolean includeArchived) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanBoardRecord board : repository.listBoards(includeArchived)) {
            result.add(boardWithCounts(board));
        }
        return result;
    }

    public Map<String, Object> currentBoard() throws Exception {
        return boardView(repository.currentBoard());
    }

    public Map<String, Object> createBoard(Map<String, Object> body) throws Exception {
        KanbanBoardRecord board = new KanbanBoardRecord();
        board.setSlug(text(body, "slug"));
        board.setName(StrUtil.blankToDefault(text(body, "name"), board.getSlug()));
        board.setDescription(text(body, "description"));
        board.setColor(StrUtil.blankToDefault(text(body, "color"), "#2563eb"));
        board.setCurrent(booleanValue(body, "current") || booleanValue(body, "switch"));
        return boardView(repository.saveBoard(board));
    }

    public Map<String, Object> renameBoard(String slug, String name) throws Exception {
        if (!repository.renameBoard(slug, name)) {
            throw new IllegalArgumentException("Kanban board not found: " + slug);
        }
        return boardView(repository.findBoard(slug));
    }

    public Map<String, Object> removeBoard(String slug, boolean hardDelete) throws Exception {
        boolean removed = hardDelete ? repository.deleteBoard(slug) : repository.archiveBoard(slug);
        if (!removed) {
            throw new IllegalArgumentException("Kanban board not found: " + slug);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("slug", slug);
        result.put("action", hardDelete ? "deleted" : "archived");
        result.put("current", currentBoard());
        return result;
    }

    public Map<String, Object> switchBoard(String slug) throws Exception {
        repository.switchBoard(slug);
        return currentBoard();
    }

    public List<Map<String, Object>> tasks(String board, String status, boolean includeArchived)
            throws Exception {
        return tasks(board, status, includeArchived, null, null);
    }

    public List<Map<String, Object>> tasks(
            String board, String status, boolean includeArchived, String assignee, String tenant)
            throws Exception {
        String normalizedStatus = StrUtil.isBlank(status) ? null : normalizeStatus(status);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanTaskRecord task : repository.listTasks(board, normalizedStatus, includeArchived)) {
            if (StrUtil.isNotBlank(assignee) && !StrUtil.equals(assignee, task.getAssignee())) {
                continue;
            }
            if (StrUtil.isNotBlank(tenant) && !StrUtil.equals(tenant, task.getTenant())) {
                continue;
            }
            result.add(taskView(task, false));
        }
        return result;
    }

    public Map<String, Object> task(String taskId) throws Exception {
        KanbanTaskRecord task = repository.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return taskView(task, true);
    }

    public Map<String, Object> createTask(Map<String, Object> body) throws Exception {
        KanbanTaskRecord task = new KanbanTaskRecord();
        task.setBoardSlug(text(body, "board"));
        String idempotencyKey = text(body, "idempotency_key");
        KanbanTaskRecord existing =
                repository.findTaskByIdempotencyKey(task.getBoardSlug(), idempotencyKey);
        if (existing != null) {
            return taskView(existing, true);
        }
        task.setTitle(text(body, "title"));
        task.setBody(text(body, "body"));
        task.setAssignee(text(body, "assignee"));
        task.setStatus(StrUtil.blankToDefault(text(body, "status"), "todo"));
        task.setPriority(intValue(body, "priority", 0));
        task.setTenant(text(body, "tenant"));
        task.setWorkspaceKind(StrUtil.blankToDefault(text(body, "workspace_kind"), "scratch"));
        task.setWorkspacePath(text(body, "workspace_path"));
        task.setCreatedBy(StrUtil.blankToDefault(text(body, "created_by"), "user"));
        task.setIdempotencyKey(idempotencyKey);
        task.setMaxRuntimeSeconds(longValue(body, "max_runtime_seconds", 0));
        if (body.containsKey("skills") || body.containsKey("skills_json")) {
            Object skills = body.containsKey("skills") ? body.get("skills") : body.get("skills_json");
            task.setSkillsJson(skills == null ? null : ONode.serialize(skills));
        }
        if (body.containsKey("workflow_template_id")) {
            task.setWorkflowTemplateId(text(body, "workflow_template_id"));
        }
        if (body.containsKey("current_step_key")) {
            task.setCurrentStepKey(text(body, "current_step_key"));
        }
        KanbanTaskRecord saved = repository.saveTask(task);
        for (String parentId : normalizeTaskIds(body == null ? null : body.get("parents"))) {
            repository.linkTasks(parentId, saved.getTaskId());
        }
        return taskView(saved, true);
    }

    public Map<String, Object> updateTask(String taskId, Map<String, Object> body) throws Exception {
        KanbanTaskRecord task = repository.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        if (body.containsKey("title")) {
            task.setTitle(text(body, "title"));
        }
        if (body.containsKey("body")) {
            task.setBody(text(body, "body"));
        }
        if (body.containsKey("assignee")) {
            task.setAssignee(text(body, "assignee"));
        }
        if (body.containsKey("status")) {
            task.setStatus(normalizeStatus(text(body, "status")));
        }
        if (body.containsKey("priority")) {
            task.setPriority(intValue(body, "priority", task.getPriority()));
        }
        if (body.containsKey("tenant")) {
            task.setTenant(text(body, "tenant"));
        }
        if (body.containsKey("workspace_kind")) {
            task.setWorkspaceKind(text(body, "workspace_kind"));
        }
        if (body.containsKey("workspace_path")) {
            task.setWorkspacePath(text(body, "workspace_path"));
        }
        if (body.containsKey("result")) {
            task.setResult(text(body, "result"));
        }
        if (body.containsKey("claim_lock")) {
            task.setClaimLock(text(body, "claim_lock"));
        }
        if (body.containsKey("claim_expires_at")) {
            task.setClaimExpiresAt(longValue(body, "claim_expires_at", task.getClaimExpiresAt()));
        }
        if (body.containsKey("worker_id")) {
            task.setWorkerId(text(body, "worker_id"));
        }
        if (body.containsKey("worker_pid")) {
            task.setWorkerPid(longValue(body, "worker_pid", task.getWorkerPid()));
        }
        if (body.containsKey("last_spawn_error")) {
            task.setLastSpawnError(text(body, "last_spawn_error"));
        }
        if (body.containsKey("spawn_failures")) {
            task.setSpawnFailures(intValue(body, "spawn_failures", task.getSpawnFailures()));
        }
        if (body.containsKey("max_runtime_seconds")) {
            task.setMaxRuntimeSeconds(longValue(body, "max_runtime_seconds", task.getMaxRuntimeSeconds()));
        }
        if (body.containsKey("last_heartbeat_at")) {
            task.setLastHeartbeatAt(longValue(body, "last_heartbeat_at", task.getLastHeartbeatAt()));
        }
        if (body.containsKey("workflow_template_id")) {
            task.setWorkflowTemplateId(text(body, "workflow_template_id"));
        }
        if (body.containsKey("current_step_key")) {
            task.setCurrentStepKey(text(body, "current_step_key"));
        }
        if (body.containsKey("skills") || body.containsKey("skills_json")) {
            Object skills = body.containsKey("skills") ? body.get("skills") : body.get("skills_json");
            task.setSkillsJson(skills == null ? null : ONode.serialize(skills));
        }
        KanbanTaskRecord saved = repository.saveTask(task);
        if (body.containsKey("parents") || body.containsKey("parent_id")) {
            Object parents = body.containsKey("parents") ? body.get("parents") : body.get("parent_id");
            for (String parentId : normalizeTaskIds(parents)) {
                link(parentId, saved.getTaskId());
            }
        }
        return task(saved.getTaskId());
    }

    public Map<String, Object> link(String parentId, String childId) throws Exception {
        String parent = requireArg(parentId, "kanban_link parent_id");
        String child = requireArg(childId, "kanban_link child_id");
        if (StrUtil.equals(parent, child)) {
            throw new IllegalArgumentException("Kanban task cannot link to itself: " + parent);
        }
        KanbanTaskRecord parentTask = requireTask(parent);
        KanbanTaskRecord childTask = requireTask(child);
        if (!StrUtil.equals(parentTask.getBoardSlug(), childTask.getBoardSlug())) {
            throw new IllegalArgumentException(
                    "Kanban tasks must be on the same board: " + parent + " -> " + child);
        }
        if (wouldCreateCycle(parent, child)) {
            throw new IllegalArgumentException(
                    "Kanban link would create a dependency cycle: " + parent + " -> " + child);
        }
        repository.linkTasks(parent, child);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("parent_id", parent);
        payload.put("child_id", child);
        addEvent(child, "linked", payload);
        return task(child);
    }

    public Map<String, Object> unlink(String parentId, String childId) throws Exception {
        String parent = requireArg(parentId, "kanban_unlink parent_id");
        String child = requireArg(childId, "kanban_unlink child_id");
        KanbanTaskRecord parentTask = requireTask(parent);
        KanbanTaskRecord childTask = requireTask(child);
        if (!StrUtil.equals(parentTask.getBoardSlug(), childTask.getBoardSlug())) {
            throw new IllegalArgumentException(
                    "Kanban tasks must be on the same board: " + parent + " -> " + child);
        }
        if (!repository.unlinkTasks(parent, child)) {
            throw new IllegalArgumentException("Kanban dependency link not found: " + parent + " -> " + child);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("parent_id", parent);
        payload.put("child_id", child);
        addEvent(child, "unlinked", payload);
        repository.recomputeReady(childTask.getBoardSlug());
        return task(child);
    }

    public Map<String, Object> status(String taskId, String status, String result) throws Exception {
        return status(taskId, status, result, null, null);
    }

    public Map<String, Object> status(
            String taskId, String status, String result, String summary, Object createdCards)
            throws Exception {
        String normalized = normalizeStatus(status);
        List<String> verifiedCards = Collections.emptyList();
        KanbanTaskRecord task = repository.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        if ("done".equals(normalized)) {
            verifiedCards = verifyCreatedCards(task, createdCards);
        }
        if (!repository.updateTaskStatus(taskId, normalized, result)) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        if ("done".equals(normalized)) {
            recordCompleted(taskId, verifiedCards, summary, result);
            scanProseForPhantomIds(taskId, summary, result);
        }
        return task(taskId);
    }

    public Map<String, Object> assign(String taskId, String assignee) throws Exception {
        if (!repository.assignTask(taskId, assignee)) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> reclaim(String taskId, String reason) throws Exception {
        if (!repository.reclaimTask(taskId, reason)) {
            throw new IllegalArgumentException("Kanban task is not running or claimed: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> reassign(
            String taskId, String assignee, boolean reclaimFirst, String reason) throws Exception {
        if (!repository.reassignTask(taskId, assignee, reclaimFirst, reason)) {
            throw new IllegalArgumentException(
                    "Kanban task not found or still running: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> retry(String taskId, String reason) throws Exception {
        if (!repository.retryTask(taskId, reason)) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> unblock(String taskId) throws Exception {
        String id = requireArg(taskId, "kanban_unblock task_id");
        if (!repository.unblockTask(id)) {
            throw new IllegalArgumentException("Kanban task is not blocked or not found: " + id);
        }
        return task(id);
    }

    public Map<String, Object> edit(String taskId, String result, String summary, String metadataJson)
            throws Exception {
        String id = requireArg(taskId, "kanban_edit task_id");
        String output = requireArg(result, "kanban_edit --result");
        String metadata = validateMetadataObject(metadataJson);
        if (!repository.editCompletedTaskResult(id, output, summary, metadata)) {
            throw new IllegalArgumentException("Kanban task is not done or not found: " + id);
        }
        return task(id);
    }

    public List<Map<String, Object>> runs(String taskId) throws Exception {
        requireTask(taskId);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanRunRecord run : repository.listRuns(taskId, true)) {
            result.add(runView(run));
        }
        return result;
    }

    public List<Map<String, Object>> events(String taskId) throws Exception {
        requireTask(taskId);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanEventRecord event : repository.listEvents(taskId)) {
            result.add(eventView(event));
        }
        return result;
    }

    public Map<String, Object> context(String taskId) throws Exception {
        Map<String, Object> detail = task(taskId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("task_id", taskId);
        result.put("worker_context", detail.get("worker_context"));
        result.put("task", detail);
        return result;
    }

    public List<Map<String, Object>> diagnostics(String taskId) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (StrUtil.isNotBlank(taskId)) {
            addDiagnostics(result, repository.findTask(taskId));
            return result;
        }
        for (KanbanTaskRecord task : repository.listTasks(null, null, true)) {
            addDiagnostics(result, task);
        }
        return result;
    }

    public Map<String, Object> stats() throws Exception {
        List<KanbanTaskRecord> tasks = repository.listTasks(null, null, true);
        Map<String, Integer> byStatus = new LinkedHashMap<String, Integer>();
        for (String status : STATUSES) {
            byStatus.put(status, Integer.valueOf(0));
        }
        Map<String, Map<String, Integer>> byAssignee =
                new LinkedHashMap<String, Map<String, Integer>>();
        long oldestReady = 0;
        long now = System.currentTimeMillis();
        for (KanbanTaskRecord task : tasks) {
            String status = task.getStatus();
            Integer statusCount = byStatus.get(status);
            byStatus.put(status, Integer.valueOf(statusCount == null ? 1 : statusCount.intValue() + 1));
            String assignee = StrUtil.blankToDefault(task.getAssignee(), "-");
            Map<String, Integer> counts = byAssignee.get(assignee);
            if (counts == null) {
                counts = new LinkedHashMap<String, Integer>();
                byAssignee.put(assignee, counts);
            }
            Integer assigneeCount = counts.get(status);
            counts.put(status, Integer.valueOf(assigneeCount == null ? 1 : assigneeCount.intValue() + 1));
            if ("ready".equals(status) && task.getUpdatedAt() > 0) {
                oldestReady = oldestReady == 0 ? task.getUpdatedAt() : Math.min(oldestReady, task.getUpdatedAt());
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("by_status", byStatus);
        result.put("by_assignee", byAssignee);
        result.put(
                "oldest_ready_age_seconds",
                oldestReady <= 0 ? null : Long.valueOf(Math.max(0, (now - oldestReady) / 1000L)));
        result.put("total", Integer.valueOf(tasks.size()));
        return result;
    }

    public List<Map<String, Object>> assignees() throws Exception {
        return assignees(null);
    }

    public List<Map<String, Object>> assignees(String board) throws Exception {
        Map<String, Map<String, Integer>> counts = repository.countTasksByAssignee(board);
        Map<String, Map<String, Object>> entries = new LinkedHashMap<String, Map<String, Object>>();
        if (agentProfileService != null) {
            for (AgentProfile profile : agentProfileService.listAll()) {
                String name = profile.getAgentName();
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                Map<String, Object> entry = assigneeEntry(name);
                entry.put("configured", Boolean.TRUE);
                entry.put("on_disk", Boolean.TRUE);
                entry.put("enabled", Boolean.valueOf(profile.isEnabled()));
                entry.put(
                        "display_name",
                        StrUtil.blankToDefault(profile.getDisplayName(), profile.getAgentName()));
                entries.put(name, entry);
            }
        }
        for (Map.Entry<String, Map<String, Integer>> item : counts.entrySet()) {
            String name = item.getKey();
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Map<String, Object> entry = entries.get(name);
            if (entry == null) {
                entry = assigneeEntry(name);
                entries.put(name, entry);
            }
            entry.put("counts", item.getValue());
        }
        List<String> names = new ArrayList<String>(entries.keySet());
        Collections.sort(names);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (String name : names) {
            result.add(entries.get(name));
        }
        return result;
    }

    public List<Map<String, Object>> watch(String assignee, String tenant, String kinds, int limit)
            throws Exception {
        List<String> kindFilter = splitCsv(kinds);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanEventRecord event : repository.listRecentEvents(limit <= 0 ? 200 : limit)) {
            if (!kindFilter.isEmpty() && !kindFilter.contains(event.getKind())) {
                continue;
            }
            KanbanTaskRecord task = repository.findTask(event.getTaskId());
            if (task == null) {
                continue;
            }
            if (StrUtil.isNotBlank(assignee) && !StrUtil.equals(assignee, task.getAssignee())) {
                continue;
            }
            if (StrUtil.isNotBlank(tenant) && !StrUtil.equals(tenant, task.getTenant())) {
                continue;
            }
            Map<String, Object> item = eventView(event);
            item.put("assignee", task.getAssignee());
            item.put("tenant", task.getTenant());
            item.put("title", task.getTitle());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> notifySubscribe(Map<String, Object> body) throws Exception {
        String taskId = text(body, "task_id");
        requireTask(taskId);
        KanbanNotifySubscriptionRecord subscription = new KanbanNotifySubscriptionRecord();
        subscription.setTaskId(taskId);
        subscription.setPlatform(requireText(body, "platform"));
        subscription.setChatId(requireText(body, "chat_id"));
        subscription.setThreadId(StrUtil.nullToEmpty(text(body, "thread_id")));
        subscription.setUserId(text(body, "user_id"));
        subscription.setLastEventId(text(body, "last_event_id"));
        return notifySubscriptionView(repository.saveNotifySubscription(subscription));
    }

    public List<Map<String, Object>> notifyList(String taskId) throws Exception {
        if (StrUtil.isNotBlank(taskId)) {
            requireTask(taskId);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanNotifySubscriptionRecord subscription : repository.listNotifySubscriptions(taskId)) {
            result.add(notifySubscriptionView(subscription));
        }
        return result;
    }

    public Map<String, Object> notifyUnsubscribe(Map<String, Object> body) throws Exception {
        String taskId = requireText(body, "task_id");
        boolean removed =
                repository.removeNotifySubscription(
                        taskId,
                        requireText(body, "platform"),
                        requireText(body, "chat_id"),
                        StrUtil.nullToEmpty(text(body, "thread_id")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removed", Boolean.valueOf(removed));
        result.put("task_id", taskId);
        return result;
    }

    public Map<String, Object> gc(Map<String, Object> body) throws Exception {
        int eventDays = intValue(body, "event_retention_days", 30);
        int logDays = intValue(body, "log_retention_days", 30);
        long now = System.currentTimeMillis();
        int removedEvents =
                repository.deleteEventsOlderThan(now - Math.max(1, eventDays) * 24L * 3600L * 1000L);
        int removedLogs = gcWorkerLogs(now - Math.max(1, logDays) * 24L * 3600L * 1000L);
        int removedWorkspaces = gcArchivedScratchWorkspaces();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removed_events", Integer.valueOf(removedEvents));
        result.put("removed_logs", Integer.valueOf(removedLogs));
        result.put("removed_workspaces", Integer.valueOf(removedWorkspaces));
        return result;
    }

    public Map<String, Object> log(String taskId, int tailBytes) throws Exception {
        requireTask(taskId);
        File logFile = workerLogFile(taskId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("task_id", taskId);
        result.put("path", logFile.getAbsolutePath());
        if (!logFile.exists() || !logFile.isFile()) {
            result.put("exists", Boolean.FALSE);
            result.put("content", null);
            return result;
        }
        byte[] bytes = FileUtil.readBytes(logFile);
        int start = tailBytes > 0 && bytes.length > tailBytes ? bytes.length - tailBytes : 0;
        result.put("exists", Boolean.TRUE);
        result.put("tail_bytes", Integer.valueOf(tailBytes));
        result.put("size", Long.valueOf(logFile.length()));
        result.put("content", new String(bytes, start, bytes.length - start, java.nio.charset.StandardCharsets.UTF_8));
        return result;
    }

    public Map<String, Object> claim(String taskId, Map<String, Object> body) throws Exception {
        String claimer = StrUtil.blankToDefault(text(body, "claimer"), defaultClaimer());
        long ttl = longValue(body, "ttl_seconds", DEFAULT_CLAIM_TTL_SECONDS);
        String workerId = StrUtil.blankToDefault(text(body, "worker_id"), claimer);
        long workerPid = longValue(body, "worker_pid", 0L);
        KanbanTaskRecord claimed =
                repository.claimTask(taskId, claimer, ttl, workerId, workerPid);
        if (claimed == null) {
            throw new IllegalArgumentException("Kanban task is not ready or not found: " + taskId);
        }
        return task(claimed.getTaskId());
    }

    public Map<String, Object> claimNext(Map<String, Object> body) throws Exception {
        String claimer = StrUtil.blankToDefault(text(body, "claimer"), defaultClaimer());
        long ttl = longValue(body, "ttl_seconds", DEFAULT_CLAIM_TTL_SECONDS);
        String workerId = StrUtil.blankToDefault(text(body, "worker_id"), claimer);
        long workerPid = longValue(body, "worker_pid", 0L);
        KanbanTaskRecord claimed =
                repository.claimNextReady(
                        text(body, "board"),
                        text(body, "assignee"),
                        claimer,
                        ttl,
                        workerId,
                        workerPid);
        if (claimed == null) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("claimed", Boolean.FALSE);
            empty.put("task", null);
            return empty;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("claimed", Boolean.TRUE);
        result.put("task", task(claimed.getTaskId()));
        return result;
    }

    public Map<String, Object> heartbeatClaim(String taskId, Map<String, Object> body)
            throws Exception {
        String claimer = StrUtil.blankToDefault(text(body, "claimer"), defaultClaimer());
        long ttl = longValue(body, "ttl_seconds", DEFAULT_CLAIM_TTL_SECONDS);
        boolean ok = repository.heartbeatClaim(taskId, claimer, ttl);
        if (!ok) {
            throw new IllegalArgumentException("Kanban claim is not owned by claimer: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> heartbeatWorker(String taskId, Map<String, Object> body)
            throws Exception {
        boolean ok = repository.heartbeatWorker(taskId, text(body, "note"));
        if (!ok) {
            throw new IllegalArgumentException("Kanban task is not running: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> markSpawnFailure(String taskId, Map<String, Object> body)
            throws Exception {
        boolean ok = repository.markSpawnFailure(taskId, text(body, "error"));
        if (!ok) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> releaseStaleClaims() throws Exception {
        int reclaimed = repository.releaseStaleClaims(System.currentTimeMillis());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reclaimed", Integer.valueOf(reclaimed));
        return result;
    }

    public Map<String, Object> reclaimTimedOutWorkers() throws Exception {
        int reclaimed = repository.reclaimTimedOutWorkers(System.currentTimeMillis());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reclaimed", Integer.valueOf(reclaimed));
        return result;
    }

    public Map<String, Object> dispatch(Map<String, Object> body) throws Exception {
        if (dispatcherService == null) {
            throw new IllegalStateException("Kanban dispatcher is not configured.");
        }
        return dispatcherService.dispatch(body);
    }

    public Map<String, Object> daemonStatus() {
        if (dispatcherService == null) {
            throw new IllegalStateException("Kanban dispatcher is not configured.");
        }
        return dispatcherService.daemonStatus();
    }

    public Map<String, Object> startDaemon(Map<String, Object> body) {
        if (dispatcherService == null) {
            throw new IllegalStateException("Kanban dispatcher is not configured.");
        }
        return dispatcherService.startDaemon(body);
    }

    public Map<String, Object> stopDaemon() {
        if (dispatcherService == null) {
            throw new IllegalStateException("Kanban dispatcher is not configured.");
        }
        return dispatcherService.stopDaemon();
    }

    public Map<String, Object> comment(String taskId, String author, String body) throws Exception {
        KanbanCommentRecord comment = new KanbanCommentRecord();
        comment.setTaskId(taskId);
        comment.setAuthor(StrUtil.blankToDefault(author, "user"));
        comment.setBody(body);
        repository.addComment(comment);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("author", comment.getAuthor());
        payload.put("body", summaryPreview(comment.getBody()));
        addEvent(taskId, "comment", payload);
        return task(taskId);
    }

    public Map<String, Object> delete(String taskId) throws Exception {
        repository.deleteTask(taskId);
        return Collections.<String, Object>singletonMap("ok", Boolean.TRUE);
    }

    public String handleCommand(String args, String author) throws Exception {
        String raw = StrUtil.nullToEmpty(args).trim();
        if (StrUtil.isBlank(raw) || "list".equalsIgnoreCase(raw) || "ls".equalsIgnoreCase(raw)) {
            return formatTaskList(tasks(null, null, false));
        }
        String[] parts = raw.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        if ("boards".equals(action)) {
            return handleBoardsCommand(rest);
        }
        if ("show".equals(action)) {
            return formatTaskDetail(task(requireArg(rest, "/kanban show <task-id>")));
        }
        if ("create".equals(action) || "new".equals(action)) {
            return createCommand(rest, author);
        }
        if ("list".equals(action) || "ls".equals(action)) {
            return listCommand(rest, author);
        }
        if ("move".equals(action) || "status".equals(action)) {
            String[] tokens = rest.split("\\s+", 3);
            if (tokens.length < 2) {
                return "用法：/kanban move <task-id> <triage|todo|ready|running|blocked|done|archived>";
            }
            status(tokens[0], tokens[1], tokens.length > 2 ? tokens[2] : null);
            return "已更新任务状态：" + tokens[0] + " -> " + normalizeStatus(tokens[1]);
        }
        if ("assign".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            if (tokens.length < 2) {
                return "用法：/kanban assign <task-id> <assignee>";
            }
            assign(tokens[0], tokens[1]);
            return "已分配任务：" + tokens[0] + " -> " + tokens[1];
        }
        if ("link".equals(action) || "unlink".equals(action)) {
            String[] tokens = rest.split("\\s+", 3);
            if (tokens.length < 2) {
                return "link".equals(action)
                        ? "用法：/kanban link <parent-id> <child-id>"
                        : "用法：/kanban unlink <parent-id> <child-id>";
            }
            if ("link".equals(action)) {
                link(tokens[0], tokens[1]);
                return "已添加依赖：" + tokens[0] + " -> " + tokens[1];
            }
            unlink(tokens[0], tokens[1]);
            return "已移除依赖：" + tokens[0] + " -> " + tokens[1];
        }
        if ("reclaim".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            reclaim(requireArg(tokens[0], "/kanban reclaim <task-id> [reason]"),
                    tokens.length > 1 ? tokens[1] : null);
            return "已收回执行权：" + tokens[0];
        }
        if ("reassign".equals(action)) {
            String[] tokens = rest.split("\\s+", 4);
            if (tokens.length < 2) {
                return "用法：/kanban reassign <task-id> <assignee> [--reclaim] [reason]";
            }
            boolean reclaimFirst = false;
            String reason = null;
            if (tokens.length > 2) {
                if ("--reclaim".equals(tokens[2])) {
                    reclaimFirst = true;
                    reason = tokens.length > 3 ? tokens[3] : null;
                } else {
                    reason = tokens[2] + (tokens.length > 3 ? " " + tokens[3] : "");
                }
            }
            reassign(tokens[0], tokens[1], reclaimFirst, reason);
            return "已重新分配任务：" + tokens[0] + " -> " + tokens[1];
        }
        if ("retry".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            retry(requireArg(tokens[0], "/kanban retry <task-id> [reason]"),
                    tokens.length > 1 ? tokens[1] : null);
            return "已重试任务：" + tokens[0];
        }
        if ("unblock".equals(action)) {
            return unblockCommand(rest);
        }
        if ("edit".equals(action)) {
            return editCommand(rest);
        }
        if ("assignees".equals(action)) {
            return formatAssignees(assignees(StrUtil.blankToDefault(rest, null)));
        }
        if ("runs".equals(action) || "history".equals(action)) {
            return formatRuns(requireArg(rest, "/kanban runs <task-id>"));
        }
        if ("events".equals(action) || "tail".equals(action)) {
            return formatEvents(requireArg(rest, "/kanban events <task-id>"));
        }
        if ("context".equals(action)) {
            Map<String, Object> ctx = context(requireArg(rest, "/kanban context <task-id>"));
            return String.valueOf(ctx.get("worker_context"));
        }
        if ("diagnostics".equals(action) || "diag".equals(action)) {
            return formatDiagnostics(diagnostics(rest));
        }
        if ("stats".equals(action)) {
            return formatStats(stats());
        }
        if ("watch".equals(action)) {
            return formatEventViews(watch(null, null, rest, 50), "最近 Kanban 事件");
        }
        if ("notify-subscribe".equals(action)) {
            Map<String, Object> body = notifyBody(rest, true);
            return "已订阅任务通知：" + notifySubscribe(body).get("id");
        }
        if ("notify-list".equals(action)) {
            return formatNotifySubscriptions(notifyList(StrUtil.blankToDefault(rest, null)));
        }
        if ("notify-unsubscribe".equals(action)) {
            Map<String, Object> result = notifyUnsubscribe(notifyBody(rest, false));
            return Boolean.TRUE.equals(result.get("removed")) ? "已取消任务通知：" + result.get("task_id") : "没有匹配的任务通知订阅。";
        }
        if ("log".equals(action)) {
            String[] tokens = rest.split("\\s+", 3);
            String taskId = requireArg(tokens[0], "/kanban log <task-id> [tail_bytes]");
            int tailBytes = tokens.length > 1 ? parseInt(tokens[1], 0) : 0;
            return formatLog(log(taskId, tailBytes));
        }
        if ("gc".equals(action)) {
            return formatGc(gc(parseGcOptions(rest)));
        }
        if ("claim".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("claimer", author);
            claim(requireArg(tokens[0], "/kanban claim <task-id> [ttl_seconds]"), claimTtlBody(tokens, body));
            return "已认领任务：" + tokens[0];
        }
        if ("next".equals(action) || "claim-next".equals(action)) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("claimer", author);
            if (StrUtil.isNotBlank(rest)) {
                body.put("assignee", rest);
            }
            Map<String, Object> result = claimNext(body);
            if (!Boolean.TRUE.equals(result.get("claimed"))) {
                return "当前没有可认领的 ready 任务。";
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) result.get("task");
            return "已认领任务：" + task.get("id");
        }
        if ("heartbeat".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("claimer", author);
            if (tokens.length > 1) {
                body.put("note", tokens[1]);
            }
            heartbeatClaim(requireArg(tokens[0], "/kanban heartbeat <task-id> [note]"), body);
            heartbeatWorker(tokens[0], body);
            return "已刷新任务心跳：" + tokens[0];
        }
        if ("release-stale".equals(action)) {
            return "已收回过期认领：" + releaseStaleClaims().get("reclaimed");
        }
        if ("reclaim-timeouts".equals(action)) {
            return "已收回超时任务：" + reclaimTimedOutWorkers().get("reclaimed");
        }
        if ("dispatch".equals(action)) {
            Map<String, Object> result =
                    dispatch(
                            dispatcherService == null
                                    ? Collections.<String, Object>emptyMap()
                                    : dispatcherService.options(rest));
            return formatDispatchResult(result);
        }
        if ("daemon".equals(action)) {
            return handleDaemonCommand(rest);
        }
        if ("comment".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            if (tokens.length < 2) {
                return "用法：/kanban comment <task-id> <text>";
            }
            comment(tokens[0], author, tokens[1]);
            return "已追加评论：" + tokens[0];
        }
        if ("block".equals(action)) {
            return blockCommand(rest, author);
        }
        if ("done".equals(action) || "complete".equals(action)) {
            return bulkStatusCommand(rest, "done", "已完成任务", "/kanban done <task-id> [task-id...]");
        }
        if ("archive".equals(action)) {
            return bulkStatusCommand(rest, "archived", "已归档任务", "/kanban archive <task-id> [task-id...]");
        }
        return kanbanHelp();
    }

    private String createCommand(String rest, String author) throws Exception {
        String raw = requireArg(rest, "/kanban create <title> [--body text] [--assignee name] [--parent task-id]");
        ParsedKanbanOptions parsed = parseCommandOptions(raw);
        String title = parsed.positionalText();
        if (StrUtil.isBlank(title)) {
            return "用法：/kanban create <title> [--body text] [--assignee name] [--parent task-id]";
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        putOption(body, parsed, "body", "body");
        putOption(body, parsed, "assignee", "assignee");
        putOption(body, parsed, "tenant", "tenant");
        putOption(body, parsed, "priority", "priority");
        putOption(body, parsed, "idempotency_key", "idempotency-key");
        putOption(body, parsed, "created_by", "created-by");
        if (!body.containsKey("created_by")) {
            body.put("created_by", StrUtil.blankToDefault(author, "user"));
        }
        if (parsed.hasFlag("triage")) {
            body.put("status", "triage");
        } else {
            body.put("status", initialCommandStatus(parsed.values("parent")));
        }
        String workspace = parsed.value("workspace");
        if (StrUtil.isNotBlank(workspace)) {
            applyWorkspaceOption(body, workspace);
        }
        String maxRuntime = parsed.value("max-runtime");
        if (StrUtil.isNotBlank(maxRuntime)) {
            body.put("max_runtime_seconds", Long.valueOf(parseDurationSeconds(maxRuntime)));
        }
        List<String> parents = parsed.values("parent");
        if (!parents.isEmpty()) {
            body.put("parents", parents);
        }
        List<String> skills = parsed.values("skill");
        if (!skills.isEmpty()) {
            body.put("skills", skills);
        }
        Map<String, Object> created = createTask(body);
        if (parsed.hasFlag("json")) {
            return ONode.serialize(created);
        }
        return "已创建看板任务："
                + taskId(created)
                + "  ("
                + created.get("status")
                + ", assignee="
                + StrUtil.blankToDefault((String) created.get("assignee"), "-")
                + ")";
    }

    private String listCommand(String rest, String author) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        String status = parsed.value("status");
        String assignee = parsed.value("assignee");
        if (parsed.hasFlag("mine") && StrUtil.isBlank(assignee)) {
            assignee = author;
        }
        List<Map<String, Object>> list =
                tasks(
                        parsed.value("board"),
                        status,
                        parsed.hasFlag("archived"),
                        assignee,
                        parsed.value("tenant"));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatTaskList(list);
    }

    private String blockCommand(String rest, String author) throws Exception {
        String raw = requireArg(rest, "/kanban block <task-id> [reason] [--ids task-id...]");
        String[] split = raw.split("\\s+", 2);
        String firstTaskId = split[0];
        String reasonAndIds = split.length > 1 ? split[1] : "";
        String reason = reasonAndIds;
        List<String> taskIds = new ArrayList<String>();
        taskIds.add(firstTaskId);
        int idsIndex = reasonAndIds.indexOf("--ids");
        if (idsIndex >= 0) {
            reason = reasonAndIds.substring(0, idsIndex).trim();
            String extraIds = reasonAndIds.substring(idsIndex + "--ids".length()).trim();
            if (StrUtil.isNotBlank(extraIds)) {
                String[] parts = extraIds.split("\\s+");
                for (String taskId : parts) {
                    if (StrUtil.isNotBlank(taskId) && !taskIds.contains(taskId)) {
                        taskIds.add(taskId);
                    }
                }
            }
        }
        String result = StrUtil.blankToDefault(reason, null);
        if (StrUtil.isNotBlank(result)) {
            for (String taskId : taskIds) {
                comment(taskId, author, "BLOCKED: " + result);
            }
        }
        return bulkStatusCommand(taskIds, "blocked", result, "已阻塞任务");
    }

    private String unblockCommand(String rest) throws Exception {
        String raw = requireArg(rest, "/kanban unblock <task-id> [task-id...]");
        String[] taskIds = raw.split("\\s+");
        List<String> unblocked = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (String taskId : taskIds) {
            if (StrUtil.isBlank(taskId)) {
                continue;
            }
            try {
                unblock(taskId);
                unblocked.add(taskId);
            } catch (IllegalArgumentException e) {
                failed.add(taskId);
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Kanban task is not blocked or not found: " + String.join(", ", failed));
        }
        return "已解除阻塞任务：" + String.join(", ", unblocked);
    }

    private String editCommand(String rest) throws Exception {
        String raw = requireArg(rest, "/kanban edit <task-id> --result <text> [--summary <text>] [--metadata <json>]");
        String[] parts = raw.split("\\s+", 2);
        String taskId = parts[0];
        Map<String, String> options = parseLongOptions(parts.length > 1 ? parts[1] : "");
        String result = options.get("result");
        if (StrUtil.isBlank(result)) {
            return "用法：/kanban edit <task-id> --result <text> [--summary <text>] [--metadata <json>]";
        }
        edit(taskId, result, options.get("summary"), options.get("metadata"));
        return "已编辑任务结果：" + taskId;
    }

    private String initialCommandStatus(List<String> parents) throws Exception {
        if (parents == null || parents.isEmpty()) {
            return "ready";
        }
        for (String parentId : parents) {
            KanbanTaskRecord parent = repository.findTask(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("Kanban parent task not found: " + parentId);
            }
            if (!"done".equals(parent.getStatus())) {
                return "todo";
            }
        }
        return "ready";
    }

    private void putOption(
            Map<String, Object> body, ParsedKanbanOptions parsed, String bodyKey, String optionKey) {
        String value = parsed.value(optionKey);
        if (StrUtil.isNotBlank(value)) {
            body.put(bodyKey, value);
        }
    }

    private void applyWorkspaceOption(Map<String, Object> body, String workspace) {
        String value = StrUtil.nullToEmpty(workspace).trim();
        if (value.startsWith("dir:")) {
            body.put("workspace_kind", "dir");
            body.put("workspace_path", value.substring("dir:".length()).trim());
            return;
        }
        if ("scratch".equals(value) || "worktree".equals(value) || "dir".equals(value)) {
            body.put("workspace_kind", value);
            return;
        }
        throw new IllegalArgumentException("workspace must be scratch, worktree, or dir:<path>");
    }

    private long parseDurationSeconds(String value) {
        String text = StrUtil.nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(text)) {
            return 0L;
        }
        long multiplier = 1L;
        char last = text.charAt(text.length() - 1);
        if (last == 's' || last == 'm' || last == 'h' || last == 'd') {
            text = text.substring(0, text.length() - 1);
            if (last == 'm') {
                multiplier = 60L;
            } else if (last == 'h') {
                multiplier = 3600L;
            } else if (last == 'd') {
                multiplier = 86400L;
            }
        }
        try {
            return Long.parseLong(text) * multiplier;
        } catch (Exception e) {
            throw new IllegalArgumentException("max-runtime must be seconds or a duration like 90s, 30m, 2h, 1d");
        }
    }

    private String bulkStatusCommand(String rest, String status, String label, String usage)
            throws Exception {
        String raw = requireArg(rest, usage);
        List<String> taskIds = Arrays.asList(raw.split("\\s+"));
        return bulkStatusCommand(taskIds, status, null, label);
    }

    private String bulkStatusCommand(List<String> taskIds, String status, String result, String label)
            throws Exception {
        List<String> updated = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (String taskId : taskIds) {
            if (StrUtil.isBlank(taskId)) {
                continue;
            }
            try {
                status(taskId, status, result);
                updated.add(taskId);
            } catch (IllegalArgumentException e) {
                failed.add(taskId);
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Kanban task cannot be updated to " + status + ": " + String.join(", ", failed));
        }
        return label + "：" + String.join(", ", updated);
    }

    private Map<String, String> parseLongOptions(String rest) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        String raw = StrUtil.nullToEmpty(rest).trim();
        int index = 0;
        while (index < raw.length()) {
            int marker = raw.indexOf("--", index);
            if (marker < 0) {
                break;
            }
            int keyStart = marker + 2;
            int keyEnd = keyStart;
            while (keyEnd < raw.length()) {
                char ch = raw.charAt(keyEnd);
                if (Character.isWhitespace(ch)) {
                    break;
                }
                keyEnd++;
            }
            if (keyEnd <= keyStart) {
                index = keyStart;
                continue;
            }
            String key = raw.substring(keyStart, keyEnd).trim();
            int valueStart = keyEnd;
            while (valueStart < raw.length() && Character.isWhitespace(raw.charAt(valueStart))) {
                valueStart++;
            }
            int next = raw.indexOf(" --", valueStart);
            String value = next < 0 ? raw.substring(valueStart).trim() : raw.substring(valueStart, next).trim();
            if (StrUtil.isNotBlank(key)) {
                result.put(key, stripWrappingQuotes(value));
            }
            index = next < 0 ? raw.length() : next + 1;
        }
        return result;
    }

    private ParsedKanbanOptions parseCommandOptions(String rest) {
        ParsedKanbanOptions parsed = new ParsedKanbanOptions();
        String raw = StrUtil.nullToEmpty(rest).trim();
        int index = 0;
        while (index < raw.length()) {
            int marker = raw.indexOf("--", index);
            if (marker < 0) {
                addPositional(parsed, raw.substring(index));
                break;
            }
            addPositional(parsed, raw.substring(index, marker));
            int keyStart = marker + 2;
            int keyEnd = keyStart;
            while (keyEnd < raw.length()) {
                char ch = raw.charAt(keyEnd);
                if (Character.isWhitespace(ch)) {
                    break;
                }
                keyEnd++;
            }
            if (keyEnd <= keyStart) {
                index = keyStart;
                continue;
            }
            String key = raw.substring(keyStart, keyEnd).trim();
            int valueStart = keyEnd;
            while (valueStart < raw.length() && Character.isWhitespace(raw.charAt(valueStart))) {
                valueStart++;
            }
            if (isFlagOption(key)) {
                parsed.addFlag(key);
                index = valueStart <= marker ? keyEnd : valueStart;
                continue;
            }
            int next = raw.indexOf(" --", valueStart);
            String value = next < 0 ? raw.substring(valueStart).trim() : raw.substring(valueStart, next).trim();
            if (StrUtil.isBlank(value)) {
                parsed.addFlag(key);
            } else {
                parsed.addValue(key, stripWrappingQuotes(value));
            }
            index = next < 0 ? raw.length() : next + 1;
        }
        return parsed;
    }

    private void addPositional(ParsedKanbanOptions parsed, String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (StrUtil.isNotBlank(value)) {
            parsed.addPositional(stripWrappingQuotes(value));
        }
    }

    private boolean isFlagOption(String key) {
        return "json".equals(key)
                || "triage".equals(key)
                || "archived".equals(key)
                || "mine".equals(key);
    }

    private String handleDaemonCommand(String rest) {
        String raw = StrUtil.nullToEmpty(rest).trim();
        String[] parts = StrUtil.isBlank(raw) ? new String[0] : raw.split("\\s+", 2);
        String action = parts.length == 0 ? "status" : parts[0].toLowerCase(Locale.ROOT);
        String options = parts.length > 1 ? parts[1].trim() : "";
        if ("start".equals(action)) {
            Map<String, Object> status =
                    startDaemon(
                            dispatcherService == null
                                    ? Collections.<String, Object>emptyMap()
                                    : dispatcherService.options(options));
            return "Kanban 派发 daemon 已启动：" + formatDaemonStatus(status);
        }
        if ("stop".equals(action)) {
            Map<String, Object> status = stopDaemon();
            return "Kanban 派发 daemon 已停止：" + formatDaemonStatus(status);
        }
        if ("status".equals(action)) {
            return "Kanban 派发 daemon 状态：" + formatDaemonStatus(daemonStatus());
        }
        return "用法：/kanban daemon status|start|stop [--interval N] [--max N] [--board slug] [--failure-limit N]";
    }

    private String handleBoardsCommand(String rest) throws Exception {
        String raw = StrUtil.nullToEmpty(rest).trim();
        String firstToken = raw.split("\\s+", 2)[0];
        if (StrUtil.isBlank(raw) || "list".equalsIgnoreCase(firstToken) || "ls".equalsIgnoreCase(firstToken)) {
            StringBuilder buffer = new StringBuilder();
            for (Map<String, Object> board : boards(raw.contains("--all"))) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(Boolean.TRUE.equals(board.get("current")) ? "* " : "- ")
                        .append(board.get("slug"))
                        .append("  ")
                        .append(board.get("name"));
                if (Boolean.TRUE.equals(board.get("archived"))) {
                    buffer.append("  [archived]");
                }
            }
            return buffer.length() == 0 ? "当前没有看板。" : buffer.toString();
        }
        String[] parts = raw.split("\\s+", 3);
        if (("create".equalsIgnoreCase(parts[0]) || "new".equalsIgnoreCase(parts[0]))
                && parts.length >= 2) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("slug", parts[1]);
            body.put("name", parts.length > 2 ? parts[2] : parts[1]);
            return "已创建看板：" + createBoard(body).get("slug");
        }
        if (("switch".equalsIgnoreCase(parts[0]) || "use".equalsIgnoreCase(parts[0]))
                && parts.length >= 2) {
            return "已切换看板：" + switchBoard(parts[1]).get("slug");
        }
        if (("show".equalsIgnoreCase(parts[0]) || "current".equalsIgnoreCase(parts[0]))) {
            Map<String, Object> board = boardWithCounts(repository.currentBoard());
            return "当前看板："
                    + board.get("slug")
                    + "\n名称："
                    + board.get("name")
                    + "\n描述："
                    + StrUtil.blankToDefault((String) board.get("description"), "-")
                    + "\n任务数："
                    + board.get("counts");
        }
        if ("rename".equalsIgnoreCase(parts[0]) && parts.length >= 3) {
            return "已重命名看板：" + renameBoard(parts[1], parts[2]).get("name");
        }
        if (("rm".equalsIgnoreCase(parts[0])
                        || "remove".equalsIgnoreCase(parts[0])
                        || "delete".equalsIgnoreCase(parts[0]))
                && parts.length >= 2) {
            boolean hardDelete = raw.contains("--delete") || "delete".equalsIgnoreCase(parts[0]);
            String slug = parts[1].replace("--delete", "").trim();
            Map<String, Object> result = removeBoard(slug, hardDelete);
            return hardDelete ? "已删除看板：" + result.get("slug") : "已归档看板：" + result.get("slug");
        }
        return "用法：/kanban boards [list [--all]|create <slug> [name]|switch <slug>|show|rename <slug> <name>|rm <slug> [--delete]]";
    }

    private Map<String, Object> boardView(KanbanBoardRecord board) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", board.getBoardId());
        result.put("slug", board.getSlug());
        result.put("name", board.getName());
        result.put("description", board.getDescription());
        result.put("color", board.getColor());
        result.put("current", Boolean.valueOf(board.isCurrent()));
        result.put("archived", Boolean.valueOf(board.isArchived()));
        result.put("created_at", iso(board.getCreatedAt()));
        result.put("updated_at", iso(board.getUpdatedAt()));
        return result;
    }

    private Map<String, Object> boardWithCounts(KanbanBoardRecord board) throws Exception {
        Map<String, Object> item = boardView(board);
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String status : STATUSES) {
            counts.put(status, Integer.valueOf(0));
        }
        for (KanbanTaskRecord task : repository.listTasks(board.getSlug(), null, true)) {
            Integer old = counts.get(task.getStatus());
            counts.put(task.getStatus(), Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }
        item.put("counts", counts);
        return item;
    }

    private Map<String, Object> taskView(KanbanTaskRecord task, boolean includeComments)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", task.getTaskId());
        result.put("task_id", task.getTaskId());
        result.put("board", task.getBoardSlug());
        result.put("title", task.getTitle());
        result.put("body", task.getBody());
        result.put("assignee", task.getAssignee());
        result.put("status", task.getStatus());
        result.put("priority", Integer.valueOf(task.getPriority()));
        result.put("tenant", task.getTenant());
        result.put("workspace_kind", task.getWorkspaceKind());
        result.put("workspace_path", task.getWorkspacePath());
        result.put("created_by", task.getCreatedBy());
        result.put("result", task.getResult());
        result.put("idempotency_key", task.getIdempotencyKey());
        result.put("claim_lock", task.getClaimLock());
        result.put("claim_expires_at", task.getClaimExpiresAt() <= 0 ? null : iso(task.getClaimExpiresAt()));
        result.put("worker_id", task.getWorkerId());
        result.put("worker_pid", task.getWorkerPid() <= 0 ? null : Long.valueOf(task.getWorkerPid()));
        result.put("last_spawn_error", task.getLastSpawnError());
        result.put("spawn_failures", Integer.valueOf(task.getSpawnFailures()));
        result.put(
                "max_runtime_seconds",
                task.getMaxRuntimeSeconds() <= 0 ? null : Long.valueOf(task.getMaxRuntimeSeconds()));
        result.put("last_heartbeat_at", task.getLastHeartbeatAt() <= 0 ? null : iso(task.getLastHeartbeatAt()));
        result.put("current_run_id", task.getCurrentRunId());
        result.put("workflow_template_id", task.getWorkflowTemplateId());
        result.put("current_step_key", task.getCurrentStepKey());
        result.put("skills", parseJson(task.getSkillsJson()));
        result.put("created_at", iso(task.getCreatedAt()));
        result.put("updated_at", iso(task.getUpdatedAt()));
        result.put("started_at", task.getStartedAt() <= 0 ? null : iso(task.getStartedAt()));
        result.put("completed_at", task.getCompletedAt() <= 0 ? null : iso(task.getCompletedAt()));
        if (includeComments) {
            List<Map<String, Object>> comments = new ArrayList<Map<String, Object>>();
            for (KanbanCommentRecord comment : repository.listComments(task.getTaskId())) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", comment.getCommentId());
                item.put("task_id", comment.getTaskId());
                item.put("author", comment.getAuthor());
                item.put("body", comment.getBody());
                item.put("created_at", iso(comment.getCreatedAt()));
                comments.add(item);
            }
            result.put("comments", comments);
            List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
            for (KanbanEventRecord event : repository.listEvents(task.getTaskId())) {
                events.add(eventView(event));
            }
            result.put("events", events);
            result.put("warnings", activeWarnings(events));
            List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
            for (KanbanRunRecord run : repository.listRuns(task.getTaskId(), true)) {
                runs.add(runView(run));
            }
            result.put("runs", runs);
            result.put("active_run", runView(repository.activeRun(task.getTaskId())));
            result.put("latest_run", runView(repository.latestRun(task.getTaskId())));
            result.put("retry_count", Integer.valueOf(Math.max(0, runs.size() - 1)));
            result.put("parents", taskRefs(repository.listParents(task.getTaskId())));
            result.put("children", taskRefs(repository.listChildren(task.getTaskId())));
            result.put("worker_context", workerContext(task, comments, runs));
        }
        return result;
    }

    private String formatTaskList(List<Map<String, Object>> tasks) {
        if (tasks.isEmpty()) {
            return "当前看板没有任务。";
        }
        StringBuilder buffer = new StringBuilder();
        for (Map<String, Object> task : tasks) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(task.get("id"))
                    .append("  ")
                    .append(task.get("status"))
                    .append("  @")
                    .append(StrUtil.blankToDefault((String) task.get("assignee"), "-"))
                    .append("  ")
                    .append(task.get("title"));
        }
        return buffer.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatTaskDetail(Map<String, Object> task) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(task.get("id"))
                .append("  ")
                .append(task.get("status"))
                .append("  ")
                .append(task.get("title"))
                .append('\n');
        if (StrUtil.isNotBlank((String) task.get("body"))) {
            buffer.append(task.get("body")).append('\n');
        }
        buffer.append("assignee=")
                .append(StrUtil.blankToDefault((String) task.get("assignee"), "-"))
                .append(", priority=")
                .append(task.get("priority"));
        List<Map<String, Object>> comments =
                (List<Map<String, Object>>) task.get("comments");
        if (comments != null && !comments.isEmpty()) {
            buffer.append("\ncomments:");
            for (Map<String, Object> comment : comments) {
                buffer.append("\n- ")
                        .append(comment.get("author"))
                        .append(": ")
                        .append(comment.get("body"));
            }
        }
        return buffer.toString();
    }

    private String formatRuns(String taskId) throws Exception {
        List<Map<String, Object>> runs = runs(taskId);
        if (runs.isEmpty()) {
            return "任务 " + taskId + " 暂无执行历史。";
        }
        StringBuilder buffer = new StringBuilder("运行历史：").append(taskId);
        for (Map<String, Object> run : runs) {
            buffer.append('\n')
                    .append("- ")
                    .append(run.get("run_id"))
                    .append("  ")
                    .append(StrUtil.blankToDefault(String.valueOf(run.get("status")), "-"))
                    .append("  outcome=")
                    .append(StrUtil.blankToDefault(String.valueOf(run.get("outcome")), "-"))
                    .append("  worker=")
                    .append(StrUtil.blankToDefault(String.valueOf(run.get("worker_id")), "-"));
            if (StrUtil.isNotBlank(String.valueOf(run.get("summary")))) {
                buffer.append("\n  summary: ").append(run.get("summary"));
            }
            if (StrUtil.isNotBlank(String.valueOf(run.get("error")))) {
                buffer.append("\n  error: ").append(run.get("error"));
            }
        }
        return buffer.toString();
    }

    private String formatEvents(String taskId) throws Exception {
        List<Map<String, Object>> events = events(taskId);
        if (events.isEmpty()) {
            return "任务 " + taskId + " 暂无执行流水。";
        }
        return formatEventViews(events, "执行流水：" + taskId);
    }

    private String formatEventViews(List<Map<String, Object>> events, String title) {
        if (events.isEmpty()) {
            return title + "：暂无事件。";
        }
        StringBuilder buffer = new StringBuilder(title);
        for (Map<String, Object> event : events) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(event.get("created_at")), "-"))
                    .append("  ")
                    .append(event.get("task_id"))
                    .append("  ")
                    .append(event.get("kind"));
            if (event.get("assignee") != null) {
                buffer.append("  @").append(StrUtil.blankToDefault(String.valueOf(event.get("assignee")), "-"));
            }
            Object payload = event.get("payload");
            if (payload != null) {
                buffer.append("  ").append(ONode.serialize(payload));
            }
        }
        return buffer.toString();
    }

    private String formatStats(Map<String, Object> stats) {
        StringBuilder buffer = new StringBuilder("Kanban 统计：");
        buffer.append("\n按状态：").append(stats.get("by_status"));
        buffer.append("\n按执行人：").append(stats.get("by_assignee"));
        buffer.append("\nready 最长等待秒数：")
                .append(stats.get("oldest_ready_age_seconds") == null ? "-" : stats.get("oldest_ready_age_seconds"));
        return buffer.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatAssignees(List<Map<String, Object>> assignees) {
        if (assignees.isEmpty()) {
            return "当前没有可用执行人。请先创建 Agent 或给任务分配 assignee。";
        }
        StringBuilder buffer = new StringBuilder("Kanban 执行人：");
        for (Map<String, Object> assignee : assignees) {
            Map<String, Integer> counts = (Map<String, Integer>) assignee.get("counts");
            buffer.append('\n')
                    .append("- ")
                    .append(assignee.get("name"))
                    .append("  configured=")
                    .append(Boolean.TRUE.equals(assignee.get("configured")) ? "yes" : "no")
                    .append("  enabled=")
                    .append(Boolean.TRUE.equals(assignee.get("enabled")) ? "yes" : "no")
                    .append("  counts=")
                    .append(counts == null || counts.isEmpty() ? "(idle)" : counts);
        }
        return buffer.toString();
    }

    private String formatNotifySubscriptions(List<Map<String, Object>> subscriptions) {
        if (subscriptions.isEmpty()) {
            return "当前没有 Kanban 通知订阅。";
        }
        StringBuilder buffer = new StringBuilder("Kanban 通知订阅：");
        for (Map<String, Object> subscription : subscriptions) {
            buffer.append('\n')
                    .append("- ")
                    .append(subscription.get("task_id"))
                    .append("  ")
                    .append(subscription.get("platform"))
                    .append(":")
                    .append(subscription.get("chat_id"));
            if (StrUtil.isNotBlank(String.valueOf(subscription.get("thread_id")))) {
                buffer.append(":").append(subscription.get("thread_id"));
            }
        }
        return buffer.toString();
    }

    private String formatLog(Map<String, Object> log) {
        if (!Boolean.TRUE.equals(log.get("exists"))) {
            return "任务 " + log.get("task_id") + " 暂无 worker 日志：" + log.get("path");
        }
        return StrUtil.blankToDefault(String.valueOf(log.get("content")), "");
    }

    private String formatGc(Map<String, Object> result) {
        return "Kanban GC 完成：workspace="
                + result.get("removed_workspaces")
                + ", events="
                + result.get("removed_events")
                + ", logs="
                + result.get("removed_logs");
    }

    private String formatDiagnostics(List<Map<String, Object>> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "当前没有 Kanban 诊断项。";
        }
        StringBuilder buffer = new StringBuilder("Kanban 诊断：");
        for (Map<String, Object> diagnostic : diagnostics) {
            buffer.append('\n')
                    .append("- ")
                    .append(diagnostic.get("task_id"))
                    .append("  [")
                    .append(diagnostic.get("severity"))
                    .append("] ")
                    .append(diagnostic.get("kind"))
                    .append(": ")
                    .append(diagnostic.get("title"));
            if (StrUtil.isNotBlank(String.valueOf(diagnostic.get("suggestion")))) {
                buffer.append("\n  建议：").append(diagnostic.get("suggestion"));
            }
        }
        return buffer.toString();
    }

    private String kanbanHelp() {
        return String.join(
                "\n",
                Arrays.asList(
                        "/kanban list - 查看当前看板任务",
                        "/kanban create <title> - 创建任务",
                        "/kanban show <task-id> - 查看任务详情",
                        "/kanban move <task-id> <status> - 移动任务状态",
                        "/kanban assign <task-id> <assignee> - 分配执行人",
                        "/kanban reclaim <task-id> [reason] - 收回运行中的任务",
                        "/kanban reassign <task-id> <assignee> [--reclaim] - 重新分配任务",
                        "/kanban retry <task-id> [reason] - 将任务重置为 ready 并保留运行历史",
                        "/kanban unblock <task-id> [task-id...] - 将 blocked 任务恢复为 ready",
                        "/kanban edit <task-id> --result <text> [--summary <text>] [--metadata <json>] - 修正已完成任务的结果",
                        "/kanban assignees [board] - 查看 Agent 与看板执行人负载",
                        "/kanban link <parent-id> <child-id> - 添加任务依赖",
                        "/kanban unlink <parent-id> <child-id> - 移除任务依赖",
                        "/kanban runs <task-id> - 查看任务执行历史",
                        "/kanban events <task-id> - 查看任务执行流水",
                        "/kanban context <task-id> - 输出 worker 上下文",
                        "/kanban diagnostics [task-id] - 查看任务诊断与恢复提示",
                        "/kanban stats - 查看状态、执行人和 ready 老化统计",
                        "/kanban watch [kind1,kind2] - 查看最近 Kanban 事件",
                        "/kanban notify-subscribe <task-id> <platform> <chat-id> [thread-id] - 订阅任务终态通知",
                        "/kanban notify-list [task-id] - 查看通知订阅",
                        "/kanban notify-unsubscribe <task-id> <platform> <chat-id> [thread-id] - 取消通知订阅",
                        "/kanban log <task-id> [tail_bytes] - 查看 worker 日志",
                        "/kanban gc [event_days] [log_days] - 清理旧事件、旧日志和归档 scratch 工作区",
                        "/kanban claim <task-id> [ttl_seconds] - 认领 ready 任务并开始运行",
                        "/kanban next [assignee] - 认领下一个 ready 任务",
                        "/kanban heartbeat <task-id> [note] - 刷新认领和 worker 心跳",
                        "/kanban release-stale - 收回过期认领",
                        "/kanban reclaim-timeouts - 收回超过运行时限的任务",
                        "/kanban dispatch [--dry-run] [--max N] [--board slug] - 执行一次自动派发",
                        "/kanban daemon status|start|stop [--interval N] [--max N] [--board slug] - 管理后台派发循环",
                        "/kanban comment <task-id> <text> - 追加评论",
                        "/kanban block <task-id> [reason] [--ids task-id...] - 阻塞任务并可批量追加同一原因",
                        "/kanban done <task-id> [task-id...] - 标记完成",
                        "/kanban archive <task-id> [task-id...] - 归档任务",
                        "/kanban boards [list|create|switch] - 管理多看板"));
    }

    private String formatDispatchResult(Map<String, Object> result) {
        return "已执行 Kanban 派发："
                + "spawned="
                + sizeOf(result.get("spawned"))
                + ", promoted="
                + result.get("promoted")
                + ", reclaimed="
                + result.get("reclaimed")
                + ", timed_out="
                + result.get("timed_out")
                + ", skipped_unassigned="
                + sizeOf(result.get("skipped_unassigned"))
                + ", auto_blocked="
                + sizeOf(result.get("auto_blocked"));
    }

    private String formatDaemonStatus(Map<String, Object> status) {
        return "running="
                + status.get("running")
                + ", interval="
                + status.get("interval_seconds")
                + "s, max_spawn="
                + status.get("max_spawn")
                + ", board="
                + StrUtil.blankToDefault(String.valueOf(status.get("board")), "-")
                + ", ticks="
                + status.get("tick_count")
                + ", last_error="
                + StrUtil.blankToDefault(String.valueOf(status.get("last_error")), "-");
    }

    private int sizeOf(Object value) {
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).size();
        }
        return value == null ? 0 : 1;
    }

    private KanbanTaskRecord requireTask(String taskId) throws Exception {
        KanbanTaskRecord task = repository.findTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return task;
    }

    private void addDiagnostics(List<Map<String, Object>> result, KanbanTaskRecord task)
            throws Exception {
        if (task == null) {
            return;
        }
        if ("running".equals(task.getStatus())
                && task.getClaimExpiresAt() > 0
                && task.getClaimExpiresAt() < System.currentTimeMillis()) {
            result.add(
                    diagnostic(
                            task,
                            "error",
                            "stale_claim",
                            "运行任务的认领已过期",
                            "执行 /kanban reclaim " + task.getTaskId() + " 或等待 dispatcher 自动收回。"));
        }
        if (task.getMaxRuntimeSeconds() > 0
                && task.getStartedAt() > 0
                && "running".equals(task.getStatus())
                && (System.currentTimeMillis() - task.getStartedAt())
                        > task.getMaxRuntimeSeconds() * 1000L) {
            result.add(
                    diagnostic(
                            task,
                            "error",
                            "runtime_exceeded",
                            "运行任务超过 max_runtime_seconds",
                            "执行 /kanban reclaim-timeouts，或检查 worker 是否卡住。"));
        }
        if (task.getSpawnFailures() > 0) {
            result.add(
                    diagnostic(
                            task,
                            task.getSpawnFailures() >= 3 ? "critical" : "warning",
                            "spawn_failed",
                            "worker 启动失败 " + task.getSpawnFailures() + " 次",
                            StrUtil.blankToDefault(
                                    task.getLastSpawnError(),
                                    "检查 assignee、运行环境、技能和启动命令。")));
        }
        for (KanbanEventRecord event : repository.listEvents(task.getTaskId())) {
            String kind = event.getKind();
            if ("completion_blocked_hallucination".equals(kind)) {
                result.add(
                        diagnostic(
                                task,
                                "error",
                                kind,
                                "完成回报包含未验证的 created_cards",
                                "重新完成任务时只填写 kanban_create 返回的真实任务 id。"));
            } else if ("suspected_hallucinated_references".equals(kind)) {
                result.add(
                        diagnostic(
                                task,
                                "warning",
                                kind,
                                "完成文本疑似引用不存在的任务 id",
                                "核对 summary/result 中的任务 id，必要时补建任务或修正说明。"));
            }
        }
    }

    private Map<String, Object> diagnostic(
            KanbanTaskRecord task, String severity, String kind, String title, String suggestion) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("task_id", task.getTaskId());
        item.put("title", title);
        item.put("task_title", task.getTitle());
        item.put("status", task.getStatus());
        item.put("assignee", task.getAssignee());
        item.put("severity", severity);
        item.put("kind", kind);
        item.put("suggestion", suggestion);
        return item;
    }

    private Map<String, Object> notifySubscriptionView(KanbanNotifySubscriptionRecord subscription) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", subscription.getSubscriptionId());
        item.put("task_id", subscription.getTaskId());
        item.put("platform", subscription.getPlatform());
        item.put("chat_id", subscription.getChatId());
        item.put("thread_id", subscription.getThreadId());
        item.put("user_id", subscription.getUserId());
        item.put("last_event_id", subscription.getLastEventId());
        item.put("created_at", iso(subscription.getCreatedAt()));
        item.put("updated_at", iso(subscription.getUpdatedAt()));
        return item;
    }

    private Map<String, Object> notifyBody(String rest, boolean subscribe) {
        String[] tokens = StrUtil.nullToEmpty(rest).trim().split("\\s+");
        if (tokens.length < 3) {
            throw new IllegalArgumentException(
                    subscribe
                            ? "用法：/kanban notify-subscribe <task-id> <platform> <chat-id> [thread-id]"
                            : "用法：/kanban notify-unsubscribe <task-id> <platform> <chat-id> [thread-id]");
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("task_id", tokens[0]);
        body.put("platform", tokens[1]);
        body.put("chat_id", tokens[2]);
        if (tokens.length > 3) {
            body.put("thread_id", tokens[3]);
        }
        return body;
    }

    private Map<String, Object> parseGcOptions(String rest) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String[] tokens = StrUtil.nullToEmpty(rest).trim().split("\\s+");
        if (tokens.length > 0 && StrUtil.isNotBlank(tokens[0])) {
            body.put("event_retention_days", tokens[0]);
        }
        if (tokens.length > 1 && StrUtil.isNotBlank(tokens[1])) {
            body.put("log_retention_days", tokens[1]);
        }
        return body;
    }

    private String requireText(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isBlank(value)) {
            return result;
        }
        String[] tokens = value.split(",");
        for (String token : tokens) {
            String normalized = token.trim();
            if (StrUtil.isNotBlank(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private int gcWorkerLogs(long cutoffMillis) {
        File dir = workerLogDir();
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        int removed = 0;
        for (File file : FileUtil.loopFiles(dir)) {
            if (file.isFile() && file.lastModified() < cutoffMillis) {
                FileUtil.del(file);
                removed++;
            }
        }
        return removed;
    }

    private int gcArchivedScratchWorkspaces() throws Exception {
        File root = scratchWorkspaceRoot().getCanonicalFile();
        int removed = 0;
        for (KanbanTaskRecord task : repository.listTasks(null, "archived", true)) {
            if (!"scratch".equals(task.getWorkspaceKind())) {
                continue;
            }
            File path =
                    StrUtil.isBlank(task.getWorkspacePath())
                            ? FileUtil.file(root, task.getTaskId())
                            : FileUtil.file(task.getWorkspacePath());
            File canonical;
            try {
                canonical = path.getCanonicalFile();
            } catch (Exception e) {
                continue;
            }
            if (!isUnderRoot(canonical, root) || !canonical.exists() || !canonical.isDirectory()) {
                continue;
            }
            FileUtil.del(canonical);
            removed++;
        }
        return removed;
    }

    private File workerLogFile(String taskId) {
        return FileUtil.file(workerLogDir(), taskId + ".log");
    }

    private File workerLogDir() {
        return FileUtil.file(runtimeLogsDir(), "kanban");
    }

    private File scratchWorkspaceRoot() {
        return FileUtil.file(runtimeHome(), "kanban", "workspaces");
    }

    private File runtimeLogsDir() {
        if (appConfig != null && appConfig.getRuntime() != null && StrUtil.isNotBlank(appConfig.getRuntime().getLogsDir())) {
            return FileUtil.file(appConfig.getRuntime().getLogsDir());
        }
        return FileUtil.file(runtimeHome(), "logs");
    }

    private File runtimeHome() {
        if (appConfig != null && appConfig.getRuntime() != null && StrUtil.isNotBlank(appConfig.getRuntime().getHome())) {
            return FileUtil.file(appConfig.getRuntime().getHome());
        }
        return FileUtil.file("runtime");
    }

    private boolean isUnderRoot(File candidate, File root) {
        String candidatePath = candidate.getPath();
        String rootPath = root.getPath();
        return !candidatePath.equals(rootPath) && candidatePath.startsWith(rootPath + File.separator);
    }

    private Map<String, Object> claimTtlBody(String[] tokens, Map<String, Object> body) {
        if (tokens.length > 1 && StrUtil.isNotBlank(tokens[1])) {
            body.put("ttl_seconds", tokens[1]);
        }
        return body;
    }

    private String taskId(Map<String, Object> task) {
        return String.valueOf(task.get("id"));
    }

    private String requireArg(String value, String usage) {
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("用法：" + usage);
        }
        return value.trim();
    }

    private String normalizeStatus(String status) {
        String value = StrUtil.nullToEmpty(status).trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new IllegalArgumentException("状态必须是：" + String.join(", ", STATUSES));
        }
        return value;
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StrUtil.isBlank(text) || "null".equalsIgnoreCase(text) ? null : text;
    }

    private int intValue(Map<String, Object> body, String key, int defaultValue) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean booleanValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private long longValue(Map<String, Object> body, String key, long defaultValue) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String defaultClaimer() {
        String env = System.getenv("JIMUQU_KANBAN_WORKER");
        if (StrUtil.isBlank(env)) {
            env = System.getenv("HERMES_KANBAN_WORKER");
        }
        return StrUtil.blankToDefault(env, "local");
    }

    private List<String> verifyCreatedCards(KanbanTaskRecord parent, Object createdCards)
            throws Exception {
        List<String> ids = normalizeCreatedCards(createdCards);
        if (ids.isEmpty()) {
            return ids;
        }
        List<String> failures = new ArrayList<String>();
        List<String> verified = new ArrayList<String>();
        String worker = StrUtil.blankToDefault(parent.getAssignee(), parent.getWorkerId());
        worker = StrUtil.blankToDefault(worker, parent.getCreatedBy());
        for (String id : ids) {
            KanbanTaskRecord child = repository.findTask(id);
            if (child == null) {
                failures.add(id + ": missing");
                continue;
            }
            if (!StrUtil.equals(child.getBoardSlug(), parent.getBoardSlug())) {
                failures.add(id + ": different board");
                continue;
            }
            if (StrUtil.isNotBlank(worker)
                    && StrUtil.isNotBlank(child.getCreatedBy())
                    && !StrUtil.equals(worker, child.getCreatedBy())) {
                failures.add(id + ": created_by=" + child.getCreatedBy());
                continue;
            }
            verified.add(id);
        }
        if (!failures.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("created_cards", ids);
            payload.put("failures", failures);
            payload.put("worker", worker);
            addEvent(parent.getTaskId(), "completion_blocked_hallucination", payload);
            throw new HallucinatedCardsException(
                    "Kanban created_cards contains unverifiable cards: " + failures);
        }
        return verified;
    }

    private List<String> normalizeCreatedCards(Object createdCards) {
        List<String> ids = new ArrayList<String>();
        if (createdCards == null) {
            return ids;
        }
        if (createdCards instanceof Collection<?>) {
            for (Object item : (Collection<?>) createdCards) {
                addCardId(ids, item);
            }
            return ids;
        }
        if (createdCards.getClass().isArray()) {
            Object[] array = (Object[]) createdCards;
            for (Object item : array) {
                addCardId(ids, item);
            }
            return ids;
        }
        addCardId(ids, createdCards);
        return ids;
    }

    private List<String> normalizeTaskIds(Object value) {
        List<String> ids = new ArrayList<String>();
        if (value == null) {
            return ids;
        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                addCardId(ids, item);
            }
            return ids;
        }
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (Object item : array) {
                addCardId(ids, item);
            }
            return ids;
        }
        addCardId(ids, value);
        return ids;
    }

    private boolean wouldCreateCycle(String parentId, String childId) throws Exception {
        return reaches(childId, parentId, new ArrayList<String>());
    }

    private boolean reaches(String currentId, String targetId, List<String> visited) throws Exception {
        if (StrUtil.equals(currentId, targetId)) {
            return true;
        }
        if (visited.contains(currentId)) {
            return false;
        }
        visited.add(currentId);
        for (KanbanTaskRecord child : repository.listChildren(currentId)) {
            if (reaches(child.getTaskId(), targetId, visited)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void addCardId(List<String> ids, Object item) {
        if (item == null) {
            return;
        }
        if (item instanceof Map<?, ?>) {
            Object id = ((Map<String, Object>) item).get("id");
            if (id == null) {
                id = ((Map<String, Object>) item).get("task_id");
            }
            addCardId(ids, id);
            return;
        }
        String value = String.valueOf(item).trim();
        if (StrUtil.isNotBlank(value) && !ids.contains(value)) {
            ids.add(value);
        }
    }

    private void recordCompleted(String taskId, List<String> verifiedCards, String summary, String result)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("verified_cards", verifiedCards);
        payload.put("summary", summaryPreview(StrUtil.blankToDefault(summary, result)));
        addEvent(taskId, "completed", payload);
        repository.updateLatestRun(
                taskId,
                StrUtil.blankToDefault(summary, result),
                ONode.serialize(payload),
                null);
    }

    private void scanProseForPhantomIds(String taskId, String summary, String result) throws Exception {
        String text = StrUtil.nullToEmpty(summary) + "\n" + StrUtil.nullToEmpty(result);
        Matcher matcher = PROSE_TASK_ID.matcher(text);
        List<String> missing = new ArrayList<String>();
        while (matcher.find()) {
            String id = matcher.group();
            if (!missing.contains(id) && repository.findTask(id) == null) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("suspected_ids", missing);
            addEvent(taskId, "suspected_hallucinated_references", payload);
        }
    }

    private void addEvent(String taskId, String kind, Map<String, Object> payload) throws Exception {
        KanbanEventRecord event = new KanbanEventRecord();
        event.setTaskId(taskId);
        event.setKind(kind);
        event.setPayloadJson(payload == null ? null : ONode.serialize(payload));
        repository.addEvent(event);
    }

    private Map<String, Object> eventView(KanbanEventRecord event) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", event.getEventId());
        item.put("task_id", event.getTaskId());
        item.put("kind", event.getKind());
        item.put("payload", parseJson(event.getPayloadJson()));
        item.put("created_at", iso(event.getCreatedAt()));
        return item;
    }

    private Map<String, Object> runView(KanbanRunRecord run) {
        if (run == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", run.getRunId());
        item.put("run_id", run.getRunId());
        item.put("task_id", run.getTaskId());
        item.put("profile", run.getProfile());
        item.put("step_key", run.getStepKey());
        item.put("status", run.getStatus());
        item.put("claim_lock", run.getClaimLock());
        item.put("claim_expires_at", run.getClaimExpiresAt() <= 0 ? null : iso(run.getClaimExpiresAt()));
        item.put("worker_pid", run.getWorkerPid() <= 0 ? null : Long.valueOf(run.getWorkerPid()));
        item.put("worker_id", run.getWorkerId());
        item.put(
                "max_runtime_seconds",
                run.getMaxRuntimeSeconds() <= 0 ? null : Long.valueOf(run.getMaxRuntimeSeconds()));
        item.put("last_heartbeat_at", run.getLastHeartbeatAt() <= 0 ? null : iso(run.getLastHeartbeatAt()));
        item.put("started_at", iso(run.getStartedAt()));
        item.put("ended_at", run.getEndedAt() <= 0 ? null : iso(run.getEndedAt()));
        item.put("outcome", run.getOutcome());
        item.put("summary", run.getSummary());
        item.put("metadata", parseJson(run.getMetadataJson()));
        item.put("error", run.getError());
        return item;
    }

    private List<Map<String, Object>> taskRefs(List<KanbanTaskRecord> tasks) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanTaskRecord task : tasks) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", task.getTaskId());
            item.put("task_id", task.getTaskId());
            item.put("title", task.getTitle());
            item.put("status", task.getStatus());
            item.put("assignee", task.getAssignee());
            item.put("priority", Integer.valueOf(task.getPriority()));
            result.add(item);
        }
        return result;
    }

    private String workerContext(
            KanbanTaskRecord task,
            List<Map<String, Object>> comments,
            List<Map<String, Object>> runs)
            throws Exception {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Task ").append(task.getTaskId()).append(": ").append(task.getTitle()).append('\n');
        buffer.append("status=").append(task.getStatus())
                .append(", assignee=").append(StrUtil.blankToDefault(task.getAssignee(), "-"))
                .append(", priority=").append(task.getPriority())
                .append('\n');
        if (StrUtil.isNotBlank(task.getBody())) {
            buffer.append("\nBody:\n").append(capped(task.getBody(), CONTEXT_MAX_TEXT)).append('\n');
        }
        List<KanbanTaskRecord> parents = repository.listParents(task.getTaskId());
        if (!parents.isEmpty()) {
            buffer.append("\nParents:\n");
            for (KanbanTaskRecord parent : parents) {
                buffer.append("- ")
                        .append(parent.getTaskId())
                        .append(" [")
                        .append(parent.getStatus())
                        .append("] ")
                        .append(parent.getTitle())
                        .append('\n');
            }
        }
        List<KanbanTaskRecord> children = repository.listChildren(task.getTaskId());
        if (!children.isEmpty()) {
            buffer.append("\nChildren:\n");
            for (KanbanTaskRecord child : children) {
                buffer.append("- ")
                        .append(child.getTaskId())
                        .append(" [")
                        .append(child.getStatus())
                        .append("] @")
                        .append(StrUtil.blankToDefault(child.getAssignee(), "-"))
                        .append(" ")
                        .append(child.getTitle())
                        .append('\n');
            }
        }
        if (!runs.isEmpty()) {
            buffer.append("\nPrior attempts:\n");
            int start = Math.max(0, runs.size() - 10);
            if (start > 0) {
                buffer.append("- ").append(start).append(" older attempts omitted\n");
            }
            for (int i = start; i < runs.size(); i++) {
                Map<String, Object> run = runs.get(i);
                buffer.append("- ")
                        .append(run.get("run_id"))
                        .append(" status=")
                        .append(run.get("status"))
                        .append(", outcome=")
                        .append(run.get("outcome"))
                        .append('\n');
                appendContextField(buffer, "  summary", run.get("summary"));
                appendContextField(buffer, "  error", run.get("error"));
                Object metadata = run.get("metadata");
                if (metadata != null) {
                    appendContextField(buffer, "  metadata", ONode.serialize(metadata));
                }
            }
        }
        if (!comments.isEmpty()) {
            buffer.append("\nRecent comments:\n");
            int start = Math.max(0, comments.size() - 30);
            if (start > 0) {
                buffer.append("- ").append(start).append(" older comments omitted\n");
            }
            for (int i = start; i < comments.size(); i++) {
                Map<String, Object> comment = comments.get(i);
                buffer.append("- ")
                        .append(comment.get("author"))
                        .append(": ")
                        .append(capped(String.valueOf(comment.get("body")), CONTEXT_MAX_TEXT))
                        .append('\n');
            }
        }
        if (StrUtil.isNotBlank(task.getResult())) {
            buffer.append("\nCurrent result:\n").append(capped(task.getResult(), CONTEXT_MAX_TEXT)).append('\n');
        }
        return buffer.toString().trim();
    }

    private void appendContextField(StringBuilder buffer, String label, Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return;
        }
        buffer.append(label).append(": ").append(capped(String.valueOf(value), CONTEXT_MAX_TEXT)).append('\n');
    }

    private String capped(String value, int maxChars) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

    private List<Map<String, Object>> activeWarnings(List<Map<String, Object>> events) {
        List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> event : events) {
            String kind = String.valueOf(event.get("kind"));
            if ("completion_blocked_hallucination".equals(kind)
                    || "suspected_hallucinated_references".equals(kind)) {
                warnings.add(event);
            }
        }
        return warnings;
    }

    private Object parseJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return ONode.deserialize(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String validateMetadataObject(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        Object parsed = ONode.deserialize(json, Object.class);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("kanban edit --metadata must be a JSON object");
        }
        return ONode.serialize(parsed);
    }

    private Map<String, Object> assigneeEntry(String name) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("name", name);
        entry.put("on_disk", Boolean.FALSE);
        entry.put("configured", Boolean.FALSE);
        entry.put("enabled", Boolean.FALSE);
        entry.put("display_name", name);
        entry.put("counts", new LinkedHashMap<String, Integer>());
        return entry;
    }

    private String stripWrappingQuotes(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    private String summaryPreview(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() <= 300) {
            return text;
        }
        return text.substring(0, 300);
    }

    private String iso(long epochMillis) {
        if (epochMillis <= 0) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(epochMillis));
    }

    public static class HallucinatedCardsException extends IllegalArgumentException {
        public HallucinatedCardsException(String message) {
            super(message);
        }
    }

    private static class ParsedKanbanOptions {
        private final List<String> positional = new ArrayList<String>();
        private final List<String> flags = new ArrayList<String>();
        private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();

        void addPositional(String value) {
            if (StrUtil.isNotBlank(value)) {
                positional.add(value);
            }
        }

        void addFlag(String key) {
            if (StrUtil.isNotBlank(key) && !flags.contains(key)) {
                flags.add(key);
            }
        }

        void addValue(String key, String value) {
            if (StrUtil.isBlank(key)) {
                return;
            }
            List<String> list = values.get(key);
            if (list == null) {
                list = new ArrayList<String>();
                values.put(key, list);
            }
            list.add(value);
        }

        boolean hasFlag(String key) {
            return flags.contains(key);
        }

        String value(String key) {
            List<String> list = values.get(key);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
        }

        List<String> values(String key) {
            List<String> list = values.get(key);
            return list == null ? Collections.<String>emptyList() : list;
        }

        String positionalText() {
            if (positional.isEmpty()) {
                return "";
            }
            return String.join(" ", positional).trim();
        }
    }
}
