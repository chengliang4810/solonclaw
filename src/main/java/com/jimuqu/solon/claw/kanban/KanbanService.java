package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentProfileService;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
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
                    Arrays.asList(
                            "triage",
                            "todo",
                            "scheduled",
                            "ready",
                            "review",
                            "running",
                            "blocked",
                            "done",
                            "archived"));
    public static final List<String> TASK_SORT_ORDERS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "created",
                            "created-desc",
                            "priority",
                            "priority-desc",
                            "status",
                            "assignee",
                            "title",
                            "updated"));
    private static final List<String> WORKSPACE_KINDS =
            Collections.unmodifiableList(Arrays.asList("scratch", "dir", "worktree"));
    private static final Pattern PROSE_TASK_ID =
            Pattern.compile("\\b(?:KB-[0-9A-Fa-f]{8}|t_[0-9A-Fa-f]{6,})\\b");
    private static final int CONTEXT_MAX_TEXT = 4096;
    private static final long DEFAULT_CLAIM_TTL_SECONDS = 900L;

    private final KanbanRepository repository;
    private final AppConfig appConfig;
    private final AgentProfileService agentProfileService;
    private KanbanDispatcherService dispatcherService;
    private KanbanNotificationService notificationService;

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

    public void setNotificationService(KanbanNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public AgentProfileService getAgentProfileService() {
        return agentProfileService;
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
        board.setDefaultWorkspacePath(text(body, "default_workspace_path"));
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
        return tasks(board, status, includeArchived, null, null, null);
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
        String normalizedStatus = StrUtil.isBlank(status) ? null : normalizeStatus(status);
        String normalizedOrderBy = normalizeTaskOrderBy(orderBy);
        List<KanbanTaskRecord> records = new ArrayList<KanbanTaskRecord>();
        List<String> taskIds = new ArrayList<String>();
        String normalizedSessionId = StrUtil.isBlank(sessionId) ? null : sessionId.trim();
        for (KanbanTaskRecord task :
                repository.listTasks(board, normalizedStatus, includeArchived, normalizedSessionId)) {
            if (StrUtil.isNotBlank(assignee) && !StrUtil.equalsIgnoreCase(assignee, task.getAssignee())) {
                continue;
            }
            if (StrUtil.isNotBlank(tenant) && !StrUtil.equals(tenant, task.getTenant())) {
                continue;
            }
            if (StrUtil.isNotBlank(workflowTemplateId)
                    && !StrUtil.equals(workflowTemplateId, task.getWorkflowTemplateId())) {
                continue;
            }
            if (StrUtil.isNotBlank(currentStepKey)
                    && !StrUtil.equals(currentStepKey, task.getCurrentStepKey())) {
                continue;
            }
            records.add(task);
            taskIds.add(task.getTaskId());
        }
        sortTasks(records, normalizedOrderBy);
        Map<String, String> latestSummaries = repository.latestSummaries(taskIds);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanTaskRecord task : records) {
            Map<String, Object> item = taskView(task, false);
            item.put("latest_summary", safeDisplayText(latestSummaries.get(task.getTaskId()), 1000));
            result.add(item);
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
        List<String> parentIds = normalizeTaskIds(body == null ? null : body.get("parents"));
        for (String parentId : parentIds) {
            KanbanTaskRecord parent = repository.findTask(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("Kanban parent task not found: " + parentId);
            }
        }
        task.setTitle(text(body, "title"));
        task.setBody(text(body, "body"));
        task.setAssignee(text(body, "assignee"));
        String defaultStatus = initialCommandStatus(parentIds);
        task.setStatus(StrUtil.blankToDefault(text(body, "status"), defaultStatus));
        task.setPriority(intValue(body, "priority", 0));
        task.setTenant(text(body, "tenant"));
        task.setSessionId(text(body, "session_id"));
        String workspaceKind = normalizeWorkspaceKind(text(body, "workspace_kind"));
        task.setWorkspaceKind(workspaceKind);
        task.setWorkspacePath(resolveWorkspacePath(task.getBoardSlug(), workspaceKind, text(body, "workspace_path")));
        task.setBranchName(normalizeBranchName(text(body, "branch_name"), workspaceKind));
        task.setCreatedBy(StrUtil.blankToDefault(text(body, "created_by"), "user"));
        task.setIdempotencyKey(idempotencyKey);
        task.setMaxRetries(optionalPositiveInt(body, "max_retries"));
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
        for (String parentId : parentIds) {
            link(parentId, saved.getTaskId());
        }
        return task(saved.getTaskId());
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
        if (body.containsKey("session_id")) {
            task.setSessionId(text(body, "session_id"));
        }
        if (body.containsKey("workspace_kind")) {
            String workspaceKind = normalizeWorkspaceKind(text(body, "workspace_kind"));
            if (!"worktree".equals(workspaceKind)
                    && StrUtil.isNotBlank(task.getBranchName())
                    && !body.containsKey("branch_name")) {
                throw new IllegalArgumentException("branch_name is only valid for worktree workspaces");
            }
            task.setWorkspaceKind(workspaceKind);
        }
        if (body.containsKey("workspace_path")) {
            task.setWorkspacePath(text(body, "workspace_path"));
        }
        if (body.containsKey("branch_name")) {
            task.setBranchName(normalizeBranchName(text(body, "branch_name"), task.getWorkspaceKind()));
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
        if (body.containsKey("max_retries") || body.containsKey("maxRetries")) {
            task.setMaxRetries(optionalPositiveInt(body, "max_retries"));
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

    public Map<String, Object> step(
            String taskId, String stepKey, String workflowTemplateId, String note, String actor)
            throws Exception {
        String tid = requireArg(taskId, "kanban_step task_id");
        String nextStep = requireArg(stepKey, "kanban_step step_key");
        KanbanTaskRecord task = requireTask(tid);
        String previousStep = task.getCurrentStepKey();
        String previousWorkflow = task.getWorkflowTemplateId();
        if (StrUtil.isNotBlank(workflowTemplateId)) {
            task.setWorkflowTemplateId(workflowTemplateId.trim());
        }
        task.setCurrentStepKey(nextStep.trim());
        repository.saveTask(task);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("from_step", previousStep);
        payload.put("to_step", task.getCurrentStepKey());
        payload.put("from_workflow", previousWorkflow);
        payload.put("to_workflow", task.getWorkflowTemplateId());
        payload.put("note", note);
        payload.put("actor", StrUtil.blankToDefault(actor, "user"));
        addEvent(tid, "step_changed", payload);
        return task(tid);
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
        if ("ready".equals(normalized)) {
            assertReadyMoveAllowed(taskId);
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
        } else if ("blocked".equals(normalized)) {
            recordBlocked(taskId, summary, result);
        }
        if ("done".equals(normalized) || "archived".equals(normalized)) {
            repository.recomputeReady(task.getBoardSlug());
        }
        return task(taskId);
    }

    private void assertReadyMoveAllowed(String taskId) throws Exception {
        List<KanbanTaskRecord> parents = repository.listParents(taskId);
        List<String> unsatisfied = new ArrayList<String>();
        for (KanbanTaskRecord parent : parents) {
            String status = StrUtil.blankToDefault(parent.getStatus(), "unknown");
            if (!"done".equals(status) && !"archived".equals(status)) {
                unsatisfied.add(parent.getTaskId()
                        + " '"
                        + StrUtil.blankToDefault(parent.getTitle(), "")
                        + "' status="
                        + status);
            }
        }
        if (!unsatisfied.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot move to 'ready': unsatisfied parent dependencies: " + String.join(", ", unsatisfied));
        }
    }

    public Map<String, Object> assign(String taskId, String assignee) throws Exception {
        KanbanTaskRecord existing = requireTask(taskId);
        if ("running".equals(existing.getStatus())) {
            throw new IllegalArgumentException(
                    "Kanban task is currently running; reclaim it before assigning: " + taskId);
        }
        if (!repository.assignTask(taskId, assignee)) {
            KanbanTaskRecord latest = repository.findTask(taskId);
            if (latest != null && "running".equals(latest.getStatus())) {
                throw new IllegalArgumentException(
                        "Kanban task is currently running; reclaim it before assigning: " + taskId);
            }
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
            throw new IllegalArgumentException("Kanban task is not blocked, scheduled, or not found: " + id);
        }
        return task(id);
    }

    public Map<String, Object> schedule(String taskId, String reason) throws Exception {
        String id = requireArg(taskId, "kanban_schedule task_id");
        if (!repository.scheduleTask(id, reason)) {
            throw new IllegalArgumentException("Kanban task is not schedulable or not found: " + id);
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
        return runs(taskId, null, null);
    }

    public List<Map<String, Object>> runs(String taskId, String stateType, String stateName) throws Exception {
        requireTask(taskId);
        String type = normalizeRunStateFilter(stateType, stateName);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanRunRecord run : repository.listRuns(taskId, true)) {
            if (type != null && !matchesRunState(run, type, stateName)) {
                continue;
            }
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

    public Map<String, Object> taskDrawer(String taskId, int logTailBytes) throws Exception {
        Map<String, Object> detail = task(taskId);
        List<Map<String, Object>> runList = runs(taskId);
        List<Map<String, Object>> eventList = events(taskId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("task_id", taskId);
        result.put("task", detail);
        result.put("runs", runList);
        result.put("events", eventList);
        result.put("execution_overview", drawerExecutionOverview(detail, runList, eventList));
        result.put("pipeline_overview", drawerPipelineOverview(detail, runList, eventList));
        result.put("context", context(taskId));
        result.put("notifications", notifyList(taskId));
        result.put("log", log(taskId, logTailBytes <= 0 ? 4096 : logTailBytes));
        result.put("actions", drawerActions(detail));
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
        int readySpawnable = 0;
        int readyNonspawnable = 0;
        int reviewSpawnable = 0;
        int reviewNonspawnable = 0;
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
            if ("ready".equals(status) && StrUtil.isNotBlank(task.getAssignee())) {
                if (isSpawnableAssignee(task.getAssignee())) {
                    readySpawnable++;
                } else {
                    readyNonspawnable++;
                }
            }
            if ("review".equals(status) && StrUtil.isNotBlank(task.getAssignee())) {
                if (isSpawnableAssignee(task.getAssignee())) {
                    reviewSpawnable++;
                } else {
                    reviewNonspawnable++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("by_status", byStatus);
        result.put("by_assignee", byAssignee);
        result.put("has_spawnable_ready", Boolean.valueOf(readySpawnable > 0));
        result.put("ready_spawnable", Integer.valueOf(readySpawnable));
        result.put("ready_nonspawnable", Integer.valueOf(readyNonspawnable));
        result.put("has_spawnable_review", Boolean.valueOf(reviewSpawnable > 0));
        result.put("review_spawnable", Integer.valueOf(reviewSpawnable));
        result.put("review_nonspawnable", Integer.valueOf(reviewNonspawnable));
        result.put(
                "oldest_ready_age_seconds",
                oldestReady <= 0 ? null : Long.valueOf(Math.max(0, (now - oldestReady) / 1000L)));
        result.put("total", Integer.valueOf(tasks.size()));
        return result;
    }

    public Map<String, Object> guide(String board) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> selectedBoard;
        if (StrUtil.isBlank(board)) {
            selectedBoard = currentBoard();
        } else {
            KanbanBoardRecord boardRecord = repository.findBoard(board);
            if (boardRecord == null) {
                throw new IllegalArgumentException("Kanban board not found: " + board);
            }
            selectedBoard = boardWithCounts(boardRecord);
        }
        Map<String, Object> stats = stats();
        result.put("board", selectedBoard);
        result.put(
                "status_flow",
                Arrays.asList("triage", "todo", "scheduled", "ready", "review", "running", "blocked", "done", "archived"));
        result.put("objective", "使用看板把需求拆成结构化任务，分配执行人，派发运行，并通过抽屉复核流水、历史、日志和通知。");
        result.put("steps", guideSteps());
        result.put("drawer_sections", Arrays.asList(
                "task",
                "pipeline_overview",
                "execution_overview",
                "runs",
                "events",
                "context",
                "notifications",
                "log",
                "actions"));
        result.put("recovery_actions", Arrays.asList(
                "diagnostics",
                "reclaim",
                "retry",
                "reassign",
                "unblock",
                "edit",
                "gc"));
        result.put("automation_actions", Arrays.asList(
                "dispatch",
                "daemon start",
                "daemon stop",
                "claim",
                "next",
                "heartbeat",
                "release-stale",
                "reclaim-timeouts"));
        result.put("history_actions", Arrays.asList(
                "drawer",
                "inspect",
                "runs",
                "history",
                "events",
                "tail",
                "context",
                "log"));
        result.put("notification_actions", Arrays.asList(
                "watch",
                "notify-subscribe",
                "notify-list",
                "notify-deliver",
                "notify-unsubscribe"));
        result.put("maintenance_actions", Arrays.asList(
                "diagnostics",
                "stats",
                "assignees",
                "gc"));
        result.put("stats", stats);
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

    private boolean isSpawnableAssignee(String assignee) throws Exception {
        if (agentProfileService == null) {
            return true;
        }
        String normalized = AgentRuntimeScope.normalizeName(assignee);
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            return true;
        }
        AgentProfile profile = agentProfileService.findByName(normalized);
        return profile != null && profile.isEnabled();
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

    public Map<String, Object> notifyClaim(Map<String, Object> body) throws Exception {
        String taskId = requireText(body, "task_id");
        requireTask(taskId);
        KanbanNotifyClaim claim =
                repository.claimNotifyEvents(
                        taskId,
                        requireText(body, "platform"),
                        requireText(body, "chat_id"),
                        StrUtil.nullToEmpty(text(body, "thread_id")),
                        splitCsv(firstNonBlank(text(body, "kinds"), text(body, "kind"))));
        return notifyClaimView(taskId, claim);
    }

    public Map<String, Object> notifyAdvance(Map<String, Object> body) throws Exception {
        String taskId = requireText(body, "task_id");
        boolean advanced =
                repository.advanceNotifyCursor(
                        taskId,
                        requireText(body, "platform"),
                        requireText(body, "chat_id"),
                        StrUtil.nullToEmpty(text(body, "thread_id")),
                        requireText(body, "new_cursor"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("advanced", Boolean.valueOf(advanced));
        result.put("task_id", taskId);
        return result;
    }

    public Map<String, Object> notifyRewind(Map<String, Object> body) throws Exception {
        String taskId = requireText(body, "task_id");
        boolean rewound =
                repository.rewindNotifyCursor(
                        taskId,
                        requireText(body, "platform"),
                        requireText(body, "chat_id"),
                        StrUtil.nullToEmpty(text(body, "thread_id")),
                        requireText(body, "claimed_cursor"),
                        requireText(body, "old_cursor"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("rewound", Boolean.valueOf(rewound));
        result.put("task_id", taskId);
        return result;
    }

    public Map<String, Object> notifyDeliver() throws Exception {
        if (notificationService == null) {
            throw new IllegalStateException("Kanban notification service is not available");
        }
        return notifyDeliveryView(notificationService.deliverPending());
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
        result.put("path", workerLogReference(taskId));
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
        long ttl = longValue(body, "ttl_seconds", defaultClaimTtlSeconds());
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
        long ttl = longValue(body, "ttl_seconds", defaultClaimTtlSeconds());
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
        long ttl = longValue(body, "ttl_seconds", defaultClaimTtlSeconds());
        boolean ok = repository.heartbeatClaim(taskId, claimer, ttl);
        if (!ok) {
            throw new IllegalArgumentException("Kanban claim is not owned by claimer: " + taskId);
        }
        return task(taskId);
    }

    private long defaultClaimTtlSeconds() {
        if (appConfig == null || appConfig.getKanban() == null) {
            return DEFAULT_CLAIM_TTL_SECONDS;
        }
        return Math.max(1L, appConfig.getKanban().getClaimTtlSeconds());
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
        List<String> reclaimed = repository.reclaimTimedOutWorkers(System.currentTimeMillis());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reclaimed", Integer.valueOf(reclaimed.size()));
        result.put("timed_out", reclaimed);
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
        String normalizedBody = StrUtil.nullToEmpty(body).trim();
        if (StrUtil.isBlank(normalizedBody)) {
            throw new IllegalArgumentException("comment body is required");
        }
        KanbanTaskRecord task = requireTask(taskId);
        KanbanCommentRecord comment = new KanbanCommentRecord();
        comment.setTaskId(task.getTaskId());
        comment.setAuthor(StrUtil.blankToDefault(author, "user"));
        comment.setBody(normalizedBody);
        repository.addComment(comment);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("author", comment.getAuthor());
        payload.put("body", summaryPreview(comment.getBody()));
        addEvent(task.getTaskId(), "comment", payload);
        return task(task.getTaskId());
    }

    public Map<String, Object> delete(String taskId) throws Exception {
        String id = requireArg(taskId, "kanban_delete task_id");
        KanbanTaskRecord task = requireTask(id);
        if (!"archived".equals(task.getStatus())) {
            throw new IllegalArgumentException("Kanban task must be archived before hard delete: " + id);
        }
        repository.deleteTask(id);
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
            return showCommand(rest);
        }
        if ("drawer".equals(action) || "inspect".equals(action)) {
            return drawerCommand(rest);
        }
        if ("create".equals(action) || "new".equals(action)) {
            return createCommand(rest, author);
        }
        if ("schema".equals(action) || "schema-create".equals(action) || "create-json".equals(action)) {
            return schemaCreateCommand(rest, author);
        }
        if ("list".equals(action) || "ls".equals(action)) {
            return listCommand(rest, author);
        }
        if ("move".equals(action) || "status".equals(action)) {
            String[] tokens = rest.split("\\s+", 3);
            if (tokens.length < 2) {
                return "用法：/kanban move <task-id> <triage|todo|scheduled|ready|review|running|blocked|done|archived>";
            }
            status(tokens[0], tokens[1], tokens.length > 2 ? tokens[2] : null);
            return "已更新任务状态：" + tokens[0] + " -> " + normalizeStatus(tokens[1]);
        }
        if ("assign".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            if (tokens.length < 2) {
                return "用法：/kanban assign <task-id> <assignee|none>";
            }
            String assignee = normalizeAssigneeOption(tokens[1]);
            assign(tokens[0], assignee);
            return "已分配任务：" + tokens[0] + " -> " + displayAssignee(assignee);
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
        if ("step".equals(action) || "pipeline".equals(action)) {
            return stepCommand(rest, author);
        }
        if ("reclaim".equals(action)) {
            ParsedKanbanOptions parsed = parseCommandOptions(rest);
            List<String> tokens = positionalTokens(parsed);
            String taskId = requireArg(firstToken(tokens), "/kanban reclaim <task-id> [--reason text]");
            reclaim(taskId, firstNonBlank(parsed.value("reason"), joinTokens(tokens, 1)));
            return "已收回执行权：" + taskId;
        }
        if ("reassign".equals(action)) {
            ParsedKanbanOptions parsed = parseCommandOptions(rest);
            List<String> tokens = positionalTokens(parsed);
            if (tokens.size() < 2) {
                return "用法：/kanban reassign <task-id> <assignee|none> [--reclaim] [--reason text]";
            }
            String assignee = normalizeAssigneeOption(tokens.get(1));
            reassign(tokens.get(0), assignee, parsed.hasFlag("reclaim"),
                    firstNonBlank(parsed.value("reason"), joinTokens(tokens, 2)));
            return "已重新分配任务：" + tokens.get(0) + " -> " + displayAssignee(assignee);
        }
        if ("retry".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            retry(requireArg(tokens[0], "/kanban retry <task-id> [reason]"),
                    tokens.length > 1 ? tokens[1] : null);
            return "已重试任务：" + tokens[0];
        }
        if ("schedule".equals(action) || "delay".equals(action)) {
            String[] tokens = rest.split("\\s+", 2);
            String taskId = requireArg(tokens[0], "/kanban schedule <task-id> [reason]");
            schedule(taskId, tokens.length > 1 ? tokens[1] : null);
            return "已延后看板任务：" + taskId;
        }
        if ("unblock".equals(action)) {
            return unblockCommand(rest);
        }
        if ("edit".equals(action)) {
            return editCommand(rest);
        }
        if ("assignees".equals(action)) {
            return assigneesCommand(rest);
        }
        if ("runs".equals(action) || "history".equals(action)) {
            return runsCommand(rest);
        }
        if ("events".equals(action) || "tail".equals(action)) {
            return formatEvents(requireArg(rest, "/kanban events <task-id>"));
        }
        if ("context".equals(action)) {
            Map<String, Object> ctx = context(requireArg(rest, "/kanban context <task-id>"));
            return String.valueOf(ctx.get("worker_context"));
        }
        if ("diagnostics".equals(action) || "diag".equals(action)) {
            return diagnosticsCommand(rest);
        }
        if ("guide".equals(action) || "tutorial".equals(action)) {
            return guideCommand(rest);
        }
        if ("stats".equals(action)) {
            return statsCommand(rest);
        }
        if ("watch".equals(action)) {
            return watchCommand(rest);
        }
        if ("notify-subscribe".equals(action)) {
            Map<String, Object> body = notifyBody(rest, true);
            return "已订阅任务通知：" + notifySubscribe(body).get("id");
        }
        if ("notify-list".equals(action)) {
            return notifyListCommand(rest);
        }
        if ("notify-claim".equals(action)) {
            Map<String, Object> result = notifyClaim(notifyClaimBody(rest));
            return "已领取任务通知事件：" + result.get("task_id") + ", events=" + sizeOf(result.get("events"));
        }
        if ("notify-deliver".equals(action)) {
            return formatNotifyDelivery(notifyDeliver());
        }
        if ("notify-unsubscribe".equals(action)) {
            Map<String, Object> result = notifyUnsubscribe(notifyBody(rest, false));
            return Boolean.TRUE.equals(result.get("removed")) ? "已取消任务通知：" + result.get("task_id") : "没有匹配的任务通知订阅。";
        }
        if ("log".equals(action)) {
            ParsedKanbanOptions parsed = parseCommandOptions(rest);
            List<String> tokens = positionalTokens(parsed);
            String taskId = requireArg(firstToken(tokens), "/kanban log <task-id> [--tail bytes]");
            int tailBytes = parseInt(firstNonBlank(parsed.value("tail"), tokenAt(tokens, 1)), 0);
            return formatLog(log(taskId, tailBytes));
        }
        if ("gc".equals(action)) {
            return formatGc(gc(parseGcOptions(rest)));
        }
        if ("claim".equals(action)) {
            ParsedKanbanOptions parsed = parseCommandOptions(rest);
            List<String> tokens = positionalTokens(parsed);
            String taskId = requireArg(firstToken(tokens), "/kanban claim <task-id> [--ttl seconds]");
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("claimer", author);
            String ttl = firstNonBlank(parsed.value("ttl"), tokenAt(tokens, 1));
            if (StrUtil.isNotBlank(ttl)) {
                body.put("ttl_seconds", ttl);
            }
            claim(taskId, body);
            return "已认领任务：" + taskId;
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
            ParsedKanbanOptions parsed = parseCommandOptions(rest);
            List<String> tokens = positionalTokens(parsed);
            String taskId = requireArg(firstToken(tokens), "/kanban heartbeat <task-id> [--note text]");
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("claimer", author);
            String note = firstNonBlank(parsed.value("note"), joinTokens(tokens, 1));
            if (StrUtil.isNotBlank(note)) {
                body.put("note", note);
            }
            heartbeatClaim(taskId, body);
            heartbeatWorker(taskId, body);
            return "已刷新任务心跳：" + taskId;
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

    private String stepCommand(String rest, String author) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        List<String> tokens = positionalTokens(parsed);
        if (tokens.size() < 2) {
            return "用法：/kanban step <task-id> <step-key> [--workflow template] [--note text] [--json]";
        }
        String taskId = tokens.get(0);
        String stepKey = tokens.get(1);
        String note = firstNonBlank(parsed.value("note"), joinTokens(tokens, 2));
        Map<String, Object> detail = step(taskId, stepKey, parsed.value("workflow"), note, author);
        if (parsed.hasFlag("json")) {
            return ONode.serialize(detail);
        }
        return "已推进任务步骤："
                + taskId
                + " -> "
                + detail.get("current_step_key")
                + "（流程："
                + StrUtil.blankToDefault(String.valueOf(detail.get("workflow_template_id")), "-")
                + "）";
    }

    private String createCommand(String rest, String author) throws Exception {
        String createUsage = "/kanban create <title> [--body text] [--assignee name] [--parent task-id] [--max-retries N]";
        String raw = requireArg(rest, createUsage);
        ParsedKanbanOptions parsed = parseCommandOptions(raw);
        String title = parsed.positionalText();
        if (StrUtil.isBlank(title)) {
            return "用法：" + createUsage;
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
        String branch = parsed.value("branch");
        if (StrUtil.isNotBlank(branch)) {
            body.put("branch_name", branch);
        }
        String maxRuntime = parsed.value("max-runtime");
        if (StrUtil.isNotBlank(maxRuntime)) {
            body.put("max_runtime_seconds", Long.valueOf(parseDurationSeconds(maxRuntime)));
        }
        String maxRetries = parsed.value("max-retries");
        if (StrUtil.isNotBlank(maxRetries)) {
            body.put("max_retries", Integer.valueOf(parsePositiveInt(maxRetries, "max-retries")));
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

    private String schemaCreateCommand(String rest, String author) throws Exception {
        String usage = "/kanban schema <task-json> [--json]";
        String raw = requireArg(rest, usage);
        ParsedKanbanOptions parsed = parseCommandOptions(raw);
        String json = parsed.positionalText();
        if (StrUtil.isBlank(json)) {
            return "用法：" + usage;
        }
        Map<String, Object> body = parseJsonObject(json, "kanban schema task");
        if (!body.containsKey("created_by")) {
            body.put("created_by", StrUtil.blankToDefault(author, "user"));
        }
        Map<String, Object> created = createTask(body);
        if (parsed.hasFlag("json")) {
            return ONode.serialize(created);
        }
        return "已创建结构化看板任务："
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
                        parsed.value("tenant"),
                        firstNonBlank(parsed.value("sort"), firstNonBlank(parsed.value("order-by"), parsed.value("order_by"))),
                        firstNonBlank(
                                parsed.value("workflow"),
                                firstNonBlank(
                                        parsed.value("workflow-template-id"),
                                        parsed.value("workflow_template_id"))),
                        firstNonBlank(
                                parsed.value("step"),
                                firstNonBlank(parsed.value("current-step-key"), parsed.value("current_step_key"))),
                        firstNonBlank(
                                parsed.value("session"),
                                firstNonBlank(parsed.value("session-id"), parsed.value("session_id"))));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatTaskList(list);
    }

    private String showCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        String taskId = parsed.positionalText();
        if (StrUtil.isBlank(taskId)) {
            return "用法：/kanban show <task-id> [--json]";
        }
        Map<String, Object> detail = task(taskId);
        if (parsed.hasFlag("json")) {
            return ONode.serialize(detail);
        }
        return formatTaskDetail(detail);
    }

    @SuppressWarnings("unchecked")
    private String drawerCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        List<String> tokens = positionalTokens(parsed);
        String taskId = firstToken(tokens);
        if (StrUtil.isBlank(taskId)) {
            return "用法：/kanban drawer <task-id> [--tail bytes] [--json]";
        }
        int tailBytes = intText(parsed.value("tail"), 4096);
        Map<String, Object> drawer = taskDrawer(taskId, tailBytes);
        if (parsed.hasFlag("json")) {
            return ONode.serialize(drawer);
        }
        return formatTaskDrawer(drawer);
    }

    private String runsCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        String taskId = parsed.positionalText();
        if (StrUtil.isBlank(taskId)) {
            return "用法：/kanban runs <task-id> [--state-type status|outcome --state name] [--json]";
        }
        List<Map<String, Object>> list =
                runs(
                        taskId,
                        firstNonBlank(parsed.value("state-type"), parsed.value("state_type")),
                        firstNonBlank(
                                parsed.value("state"),
                                firstNonBlank(parsed.value("state-name"), parsed.value("state_name"))));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatRuns(taskId, list);
    }

    private String statsCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        Map<String, Object> result = stats();
        if (parsed.hasFlag("json")) {
            return ONode.serialize(result);
        }
        return formatStats(result);
    }

    private String guideCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        Map<String, Object> result = guide(StrUtil.blankToDefault(parsed.value("board"), parsed.positionalText()));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(result);
        }
        return formatGuide(result);
    }

    private String notifyListCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        List<Map<String, Object>> list = notifyList(StrUtil.blankToDefault(parsed.positionalText(), null));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatNotifySubscriptions(list);
    }

    private String assigneesCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        List<Map<String, Object>> list = assignees(StrUtil.blankToDefault(parsed.positionalText(), null));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatAssignees(list);
    }

    private String diagnosticsCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        String taskId = parsed.value("task");
        if (StrUtil.isBlank(taskId)) {
            taskId = parsed.positionalText();
        }
        List<Map<String, Object>> list =
                filterDiagnosticsBySeverity(
                        diagnostics(StrUtil.blankToDefault(taskId, null)), parsed.value("severity"));
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatDiagnostics(list);
    }

    private String watchCommand(String rest) throws Exception {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        String kinds = parsed.value("kinds");
        if (StrUtil.isBlank(kinds)) {
            kinds = parsed.positionalText();
        }
        List<Map<String, Object>> list = watch(parsed.value("assignee"), parsed.value("tenant"), kinds, 50);
        if (parsed.hasFlag("json")) {
            return ONode.serialize(list);
        }
        return formatEventViews(list, "最近 Kanban 事件");
    }

    private List<Map<String, Object>> filterDiagnosticsBySeverity(
            List<Map<String, Object>> diagnostics, String minimumSeverity) {
        if (StrUtil.isBlank(minimumSeverity)) {
            return diagnostics;
        }
        int threshold = severityRank(minimumSeverity);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> diagnostic : diagnostics) {
            if (severityRank(String.valueOf(diagnostic.get("severity"))) >= threshold) {
                result.add(diagnostic);
            }
        }
        return result;
    }

    private int severityRank(String severity) {
        String value = StrUtil.nullToEmpty(severity).trim().toLowerCase(Locale.ROOT);
        if ("critical".equals(value)) {
            return 3;
        }
        if ("error".equals(value)) {
            return 2;
        }
        if ("warning".equals(value)) {
            return 1;
        }
        throw new IllegalArgumentException("severity must be warning, error, or critical");
    }

    private String normalizeAssigneeOption(String assignee) {
        String value = StrUtil.nullToEmpty(assignee).trim();
        if (StrUtil.isBlank(value) || "none".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private String displayAssignee(String assignee) {
        return StrUtil.blankToDefault(assignee, "-");
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
                    "Kanban task is not blocked, scheduled, or not found: " + String.join(", ", failed));
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

    private String resolveWorkspacePath(String boardSlug, String workspaceKind, String explicitPath)
            throws Exception {
        if (StrUtil.isNotBlank(explicitPath) || "scratch".equals(workspaceKind)) {
            return explicitPath;
        }
        KanbanBoardRecord board =
                StrUtil.isBlank(boardSlug) ? repository.currentBoard() : repository.findBoard(boardSlug);
        if (board == null) {
            return explicitPath;
        }
        return StrUtil.blankToDefault(board.getDefaultWorkspacePath(), explicitPath);
    }

    private void putOption(
            Map<String, Object> body, ParsedKanbanOptions parsed, String bodyKey, String optionKey) {
        String value = parsed.value(optionKey);
        if (StrUtil.isNotBlank(value)) {
            body.put(bodyKey, value);
        }
    }

    private Integer optionalPositiveInt(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.containsKey(key) ? body.get(key) : body.get("maxRetries");
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (StrUtil.isBlank(text)) {
            return null;
        }
        return Integer.valueOf(parsePositiveInt(text, key));
    }

    private int parsePositiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(label + " must be >= 1");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a positive integer");
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
                || "mine".equals(key)
                || "reclaim".equals(key);
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
        return "用法：/kanban daemon status|start|stop [--interval N] [--max N] [--max-in-progress N] [--max-in-progress-per-profile N] [--default-assignee name] [--board slug] [--failure-limit N]";
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
        result.put("default_workspace_path", board.getDefaultWorkspacePath());
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
        result.put("session_id", task.getSessionId());
        result.put("workspace_kind", task.getWorkspaceKind());
        result.put("workspace_path", workspaceReference(task));
        result.put("branch_name", task.getBranchName());
        result.put("created_by", task.getCreatedBy());
        result.put("result", task.getResult());
        result.put("idempotency_key", task.getIdempotencyKey());
        result.put("claim_lock", task.getClaimLock());
        result.put("claim_expires_at", task.getClaimExpiresAt() <= 0 ? null : iso(task.getClaimExpiresAt()));
        result.put("worker_id", task.getWorkerId());
        result.put("worker_pid", task.getWorkerPid() <= 0 ? null : Long.valueOf(task.getWorkerPid()));
        result.put("last_spawn_error", safeDisplayText(task.getLastSpawnError(), 1000));
        result.put("spawn_failures", Integer.valueOf(task.getSpawnFailures()));
        result.put("max_retries", task.getMaxRetries());
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
            result.put("latest_summary", safeDisplayText(repository.latestSummary(task.getTaskId()), 1000));
            result.put("retry_count", Integer.valueOf(Math.max(0, runs.size() - 1)));
            result.put("parents", taskRefs(repository.listParents(task.getTaskId())));
            result.put("children", taskRefs(repository.listChildren(task.getTaskId())));
            result.put("worker_context", workerContext(task, comments, runs));
        }
        return result;
    }

    private Map<String, Object> drawerActions(Map<String, Object> task) {
        String status = String.valueOf(task.get("status"));
        boolean running = "running".equals(status);
        boolean blocked = "blocked".equals(status);
        boolean scheduled = "scheduled".equals(status);
        boolean done = "done".equals(status);
        boolean claimed =
                task.get("claim_lock") != null
                        || task.get("current_run_id") != null
                        || task.get("active_run") != null;
        Map<String, Object> actions = new LinkedHashMap<String, Object>();
        actions.put("can_comment", Boolean.TRUE);
        actions.put("can_reassign", Boolean.TRUE);
        actions.put("can_reclaim", Boolean.valueOf(running || claimed));
        actions.put("can_retry", Boolean.valueOf(blocked || done || "archived".equals(status)));
        actions.put("can_unblock", Boolean.valueOf(blocked || scheduled));
        actions.put("can_edit_result", Boolean.valueOf(done));
        return actions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> drawerPipelineOverview(
            Map<String, Object> task, List<Map<String, Object>> runs, List<Map<String, Object>> events) {
        Map<String, Object> overview = new LinkedHashMap<String, Object>();
        String status = String.valueOf(task.get("status"));
        Map<String, Object> activeRun =
                task.get("active_run") instanceof Map<?, ?> ? (Map<String, Object>) task.get("active_run") : null;
        Map<String, Object> latestRun =
                task.get("latest_run") instanceof Map<?, ?> ? (Map<String, Object>) task.get("latest_run") : null;
        Map<String, Object> lastRun = activeRun != null ? activeRun : latestRun;
        List<?> warnings = task.get("warnings") instanceof List<?> ? (List<?>) task.get("warnings") : Collections.emptyList();
        Map<String, Object> actions = drawerActions(task);

        overview.put("workflow_template_id", task.get("workflow_template_id"));
        overview.put("current_step_key", task.get("current_step_key"));
        overview.put("status", status);
        overview.put("stage", drawerStage(status, task, activeRun, warnings));
        overview.put("assignee", task.get("assignee"));
        overview.put("worker_id", firstNonNull(task.get("worker_id"), lastRun == null ? null : lastRun.get("worker_id")));
        overview.put("claim_lock", task.get("claim_lock"));
        overview.put("claim_expires_at", task.get("claim_expires_at"));
        overview.put("attempt_count", Integer.valueOf(runs.size()));
        overview.put("retry_count", task.get("retry_count"));
        overview.put("event_count", Integer.valueOf(events.size()));
        overview.put("warning_count", Integer.valueOf(warnings.size()));
        overview.put("next_action", drawerNextAction(status, task, activeRun, warnings));
        overview.put("active_run", drawerRunSummary(activeRun));
        overview.put("latest_run", drawerRunSummary(latestRun));
        overview.put("supports_history", Boolean.TRUE);
        overview.put("supports_retry", actions.get("can_retry"));
        overview.put("supports_reassign", actions.get("can_reassign"));
        overview.put("supports_reclaim", actions.get("can_reclaim"));
        overview.put("supports_unblock", actions.get("can_unblock"));
        overview.put("supports_comment", actions.get("can_comment"));
        overview.put("schema_task", Boolean.valueOf(!isBlankObject(task.get("workflow_template_id"))
                || !isBlankObject(task.get("current_step_key"))
                || (task.get("skills") instanceof List<?> && !((List<?>) task.get("skills")).isEmpty())));
        return overview;
    }

    private Map<String, Object> drawerRunSummary(Map<String, Object> run) {
        if (run == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("run_id", run.get("run_id"));
        summary.put("step_key", run.get("step_key"));
        summary.put("status", run.get("status"));
        summary.put("outcome", run.get("outcome"));
        summary.put("worker_id", run.get("worker_id"));
        summary.put("started_at", run.get("started_at"));
        summary.put("ended_at", run.get("ended_at"));
        summary.put("duration_ms", run.get("duration_ms"));
        summary.put("timed_out", run.get("timed_out"));
        summary.put("summary", safeDisplayText(run.get("summary"), 1000));
        summary.put("error", safeDisplayText(run.get("error"), 1000));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> drawerExecutionOverview(
            Map<String, Object> task, List<Map<String, Object>> runs, List<Map<String, Object>> events) {
        Map<String, Object> overview = new LinkedHashMap<String, Object>();
        String status = String.valueOf(task.get("status"));
        Map<String, Object> activeRun =
                task.get("active_run") instanceof Map<?, ?> ? (Map<String, Object>) task.get("active_run") : null;
        Map<String, Object> latestRun =
                task.get("latest_run") instanceof Map<?, ?> ? (Map<String, Object>) task.get("latest_run") : null;
        Map<String, Object> lastEvent = events.isEmpty() ? null : events.get(events.size() - 1);
        List<?> warnings = task.get("warnings") instanceof List<?> ? (List<?>) task.get("warnings") : Collections.emptyList();

        overview.put("stage", drawerStage(status, task, activeRun, warnings));
        overview.put("status", status);
        overview.put("attempt_count", Integer.valueOf(runs.size()));
        overview.put("retry_count", task.get("retry_count"));
        overview.put("warning_count", Integer.valueOf(warnings.size()));
        overview.put("event_count", Integer.valueOf(events.size()));
        overview.put("active", Boolean.valueOf(activeRun != null || "running".equals(status)));
        overview.put("current_run_id", task.get("current_run_id"));
        overview.put("latest_run_id", latestRun == null ? null : latestRun.get("run_id"));
        overview.put("latest_outcome", latestRun == null ? null : latestRun.get("outcome"));
        overview.put("latest_summary", firstNonBlankObject(task.get("latest_summary"),
                latestRun == null ? null : safeDisplayText(latestRun.get("summary"), 1000)));
        overview.put("latest_error", latestRun == null ? null : safeDisplayText(latestRun.get("error"), 1000));
        overview.put("last_worker", firstNonNull(task.get("worker_id"), latestRun == null ? null : latestRun.get("worker_id")));
        overview.put("last_started_at", latestRun == null ? null : latestRun.get("started_at"));
        overview.put("last_ended_at", latestRun == null ? null : latestRun.get("ended_at"));
        overview.put("last_duration_ms", latestRun == null ? null : latestRun.get("duration_ms"));
        overview.put("last_timed_out", latestRun == null ? null : latestRun.get("timed_out"));
        overview.put(
                "last_heartbeat_at",
                firstNonNull(task.get("last_heartbeat_at"), latestRun == null ? null : latestRun.get("last_heartbeat_at")));
        overview.put("last_event_kind", lastEvent == null ? null : lastEvent.get("kind"));
        overview.put("last_event_at", lastEvent == null ? null : lastEvent.get("created_at"));
        overview.put("last_event_summary", lastEventSummary(lastEvent));
        overview.put("next_action", drawerNextAction(status, task, activeRun, warnings));
        return overview;
    }

    private String drawerStage(String status, Map<String, Object> task, Map<String, Object> activeRun, List<?> warnings) {
        if (!warnings.isEmpty()) {
            return "needs_review";
        }
        if (activeRun != null || "running".equals(status)) {
            return "running";
        }
        if ("blocked".equals(status)) {
            return "blocked";
        }
        if ("scheduled".equals(status)) {
            return "scheduled";
        }
        if ("ready".equals(status)) {
            return isBlankObject(task.get("assignee")) ? "waiting_assignee" : "ready";
        }
        if ("todo".equals(status) || "triage".equals(status)) {
            return "planning";
        }
        if ("done".equals(status)) {
            return "completed";
        }
        if ("archived".equals(status)) {
            return "archived";
        }
        return status;
    }

    private String drawerNextAction(String status, Map<String, Object> task, Map<String, Object> activeRun, List<?> warnings) {
        if (!warnings.isEmpty()) {
            return "review_warnings";
        }
        if (activeRun != null || "running".equals(status)) {
            return "watch_or_reclaim";
        }
        if ("blocked".equals(status)) {
            return "unblock_or_retry";
        }
        if ("scheduled".equals(status)) {
            return "unblock_when_due";
        }
        if ("ready".equals(status)) {
            return isBlankObject(task.get("assignee")) ? "assign" : "dispatch";
        }
        if ("todo".equals(status) || "triage".equals(status)) {
            return "promote_when_ready";
        }
        if ("done".equals(status)) {
            return "review_or_edit_result";
        }
        if ("archived".equals(status)) {
            return "restore_if_needed";
        }
        return "inspect";
    }

    private boolean isBlankObject(Object value) {
        return value == null || StrUtil.isBlank(String.valueOf(value));
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private Object firstNonBlankObject(Object first, Object second) {
        return isBlankObject(first) ? second : first;
    }

    @SuppressWarnings("unchecked")
    private String lastEventSummary(Map<String, Object> event) {
        if (event == null) {
            return null;
        }
        Object payloadObject = event.get("payload");
        Map<String, Object> payload =
                payloadObject instanceof Map<?, ?> ? (Map<String, Object>) payloadObject : Collections.<String, Object>emptyMap();
        String kind = String.valueOf(event.get("kind"));
        if ("completion_blocked_hallucination".equals(kind)) {
            return "completion blocked: " + payload.get("failures");
        }
        if ("suspected_hallucinated_references".equals(kind)) {
            return "suspected missing cards: " + payload.get("suspected_ids");
        }
        if ("reclaimed".equals(kind) || "retry".equals(kind) || "unblocked".equals(kind)) {
            return kind + ": " + payload.get("reason");
        }
        if ("reassigned".equals(kind)) {
            return "reassigned to " + payload.get("assignee");
        }
        if ("completed".equals(kind)) {
            return "completed: " + payload.get("summary");
        }
        if ("spawn_failed".equals(kind) || "gave_up".equals(kind)) {
            return kind + ": " + safeDisplayText(payload.get("error"), 1000);
        }
        return kind;
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
                .append(task.get("priority"))
                .append(", max_retries=")
                .append(task.get("max_retries") == null ? "-" : task.get("max_retries"));
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

    @SuppressWarnings("unchecked")
    private String formatTaskDrawer(Map<String, Object> drawer) {
        Map<String, Object> task = (Map<String, Object>) drawer.get("task");
        Map<String, Object> pipeline = (Map<String, Object>) drawer.get("pipeline_overview");
        Map<String, Object> execution = (Map<String, Object>) drawer.get("execution_overview");
        Map<String, Object> actions = (Map<String, Object>) drawer.get("actions");
        List<Map<String, Object>> runs =
                drawer.get("runs") instanceof List<?> ? (List<Map<String, Object>>) drawer.get("runs") : Collections.<Map<String, Object>>emptyList();
        List<Map<String, Object>> events =
                drawer.get("events") instanceof List<?> ? (List<Map<String, Object>>) drawer.get("events") : Collections.<Map<String, Object>>emptyList();
        List<Map<String, Object>> notifications =
                drawer.get("notifications") instanceof List<?> ? (List<Map<String, Object>>) drawer.get("notifications") : Collections.<Map<String, Object>>emptyList();
        Map<String, Object> log =
                drawer.get("log") instanceof Map<?, ?> ? (Map<String, Object>) drawer.get("log") : Collections.<String, Object>emptyMap();

        String taskId = String.valueOf(drawer.get("task_id"));
        StringBuilder buffer = new StringBuilder("任务抽屉：").append(taskId);
        if (task != null) {
            buffer.append('\n')
                    .append("- 标题：")
                    .append(task.get("title"))
                    .append("\n- 状态：")
                    .append(task.get("status"))
                    .append("，执行人：")
                    .append(StrUtil.blankToDefault(String.valueOf(task.get("assignee")), "未分配"));
        }
        if (pipeline != null) {
            buffer.append("\n- 流程：")
                    .append(StrUtil.blankToDefault(String.valueOf(pipeline.get("workflow_template_id")), "-"))
                    .append(" / ")
                    .append(StrUtil.blankToDefault(String.valueOf(pipeline.get("current_step_key")), "-"))
                    .append("，结构化任务=")
                    .append(Boolean.TRUE.equals(pipeline.get("schema_task")) ? "yes" : "no")
                    .append("\n- 流水：尝试 ")
                    .append(pipeline.get("attempt_count"))
                    .append(" 次，重试 ")
                    .append(pipeline.get("retry_count"))
                    .append(" 次，事件 ")
                    .append(pipeline.get("event_count"))
                    .append(" 条")
                    .append("\n- 可用动作：")
                    .append(formatDrawerSupports(pipeline));
        }
        if (execution != null) {
            buffer.append("\n- 阶段：")
                    .append(execution.get("stage"))
                    .append("，下一步：")
                    .append(execution.get("next_action"))
                    .append("，最后事件：")
                    .append(StrUtil.blankToDefault(String.valueOf(execution.get("last_event_kind")), "-"));
        }
        buffer.append("\n- 运行历史：").append(runs.size()).append(" 条");
        if (!runs.isEmpty()) {
            Map<String, Object> latest = runs.get(runs.size() - 1);
            buffer.append("，最近 ")
                    .append(latest.get("run_id"))
                    .append(" outcome=")
                    .append(StrUtil.blankToDefault(String.valueOf(latest.get("outcome")), "-"))
                    .append(" worker=")
                    .append(StrUtil.blankToDefault(String.valueOf(latest.get("worker_id")), "-"));
        }
        buffer.append("\n- 执行流水：").append(events.size()).append(" 条");
        if (!events.isEmpty()) {
            Map<String, Object> latestEvent = events.get(events.size() - 1);
            buffer.append("，最近 ").append(latestEvent.get("kind"));
        }
        buffer.append("\n- 通知订阅：").append(notifications.size()).append(" 条");
        buffer.append("\n- 日志：")
                .append(Boolean.TRUE.equals(log.get("exists")) ? "已记录" : "暂无")
                .append(" ")
                .append(StrUtil.blankToDefault(String.valueOf(log.get("path")), "-"));
        if (actions != null) {
            buffer.append("\n- 操作：").append(actions);
        }
        return buffer.toString();
    }

    private String formatDrawerSupports(Map<String, Object> pipeline) {
        List<String> parts = new ArrayList<String>();
        if (Boolean.TRUE.equals(pipeline.get("supports_history"))) {
            parts.add("history");
        }
        if (Boolean.TRUE.equals(pipeline.get("supports_retry"))) {
            parts.add("retry");
        }
        if (Boolean.TRUE.equals(pipeline.get("supports_reassign"))) {
            parts.add("reassign");
        }
        if (Boolean.TRUE.equals(pipeline.get("supports_reclaim"))) {
            parts.add("reclaim");
        }
        if (Boolean.TRUE.equals(pipeline.get("supports_unblock"))) {
            parts.add("unblock");
        }
        if (Boolean.TRUE.equals(pipeline.get("supports_comment"))) {
            parts.add("comment");
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private String formatRuns(String taskId) throws Exception {
        return formatRuns(taskId, runs(taskId));
    }

    private String formatRuns(String taskId, List<Map<String, Object>> runs) {
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
                buffer.append("\n  summary: ").append(safeDisplayText(run.get("summary"), 1000));
            }
            if (StrUtil.isNotBlank(String.valueOf(run.get("error")))) {
                buffer.append("\n  error: ").append(safeDisplayText(run.get("error"), 1000));
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
    private String formatGuide(Map<String, Object> guide) {
        Map<String, Object> board = guide.get("board") instanceof Map<?, ?>
                ? (Map<String, Object>) guide.get("board")
                : Collections.<String, Object>emptyMap();
        List<Map<String, Object>> steps = guide.get("steps") instanceof List<?>
                ? (List<Map<String, Object>>) guide.get("steps")
                : Collections.<Map<String, Object>>emptyList();
        StringBuilder buffer = new StringBuilder("Kanban 操作指南");
        buffer.append("\n当前看板：")
                .append(StrUtil.blankToDefault(String.valueOf(board.get("slug")), "-"))
                .append(" / ")
                .append(StrUtil.blankToDefault(String.valueOf(board.get("name")), "-"));
        buffer.append("\n目标：").append(guide.get("objective"));
        buffer.append("\n状态流：").append(guide.get("status_flow"));
        for (Map<String, Object> step : steps) {
            buffer.append('\n')
                    .append(step.get("order"))
                    .append(". ")
                    .append(step.get("title"))
                    .append(" - ")
                    .append(step.get("description"))
                    .append("\n   命令：")
                    .append(step.get("commands"));
        }
        buffer.append("\n抽屉区块：").append(guide.get("drawer_sections"));
        buffer.append("\n恢复动作：").append(guide.get("recovery_actions"));
        buffer.append("\n自动化动作：").append(guide.get("automation_actions"));
        buffer.append("\n历史与抽屉动作：").append(guide.get("history_actions"));
        buffer.append("\n通知动作：").append(guide.get("notification_actions"));
        buffer.append("\n维护动作：").append(guide.get("maintenance_actions"));
        return buffer.toString();
    }

    private List<Map<String, Object>> guideSteps() {
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        steps.add(guideStep(
                1,
                "创建或切换看板",
                "按项目拆分 board，避免不同项目的任务、执行人和自动派发互相干扰。",
                "/kanban boards list",
                "/kanban list --sort priority",
                "/kanban boards create <slug> <name>",
                "/kanban boards switch <slug>"));
        steps.add(guideStep(
                2,
                "拆分结构化任务",
                "用 schema 或 create 记录标题、正文、执行人、技能、流程模板、当前步骤和依赖。",
                "/kanban schema <task-json>",
                "/kanban create <title> --assignee <agent> --skill <skill> --parent <task-id>"));
        steps.add(guideStep(
                3,
                "推进到可派发状态",
                "通过 step、assign、move 把任务从 triage/todo 推进到 ready。",
                "/kanban step <task-id> <step-key> --workflow <template>",
                "/kanban assign <task-id> <assignee>",
                "/kanban move <task-id> ready"));
        steps.add(guideStep(
                4,
                "派发或认领执行",
                "手动派发、后台派发或 worker 认领 ready 任务，并持续心跳。",
                "/kanban dispatch --max 3 --max-in-progress 6 --max-in-progress-per-profile 2 --default-assignee worker",
                "/kanban daemon start --interval 60 --max 3 --max-in-progress 6 --max-in-progress-per-profile 2",
                "/kanban next <assignee>",
                "/kanban heartbeat <task-id>"));
        steps.add(guideStep(
                5,
                "复核抽屉与流水",
                "用任务抽屉查看 pipeline、execution overview、runs、events、context、通知和日志。",
                "/kanban drawer <task-id>",
                "/kanban runs <task-id>",
                "/kanban events <task-id>",
                "/kanban context <task-id>"));
        steps.add(guideStep(
                6,
                "恢复异常或修正结果",
                "对运行中、阻塞、超时或完成结果异常的任务执行诊断、收回、重试、改派、解阻和编辑。",
                "/kanban diagnostics <task-id>",
                "/kanban reclaim <task-id> [reason]",
                "/kanban retry <task-id> [reason]",
                "/kanban reassign <task-id> <assignee> --reclaim",
                "/kanban unblock <task-id>",
                "/kanban edit <task-id> --result <text>"));
        steps.add(guideStep(
                7,
                "通知和清理",
                "订阅终态通知，按需查看日志并清理旧事件、旧日志和归档工作区。",
                "/kanban notify-subscribe <task-id> <platform> <chat-id>",
                "/kanban log <task-id>",
                "/kanban gc"));
        return steps;
    }

    private Map<String, Object> guideStep(
            int order, String title, String description, String command, String... moreCommands) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        List<String> commands = new ArrayList<String>();
        commands.add(command);
        if (moreCommands != null) {
            commands.addAll(Arrays.asList(moreCommands));
        }
        step.put("order", Integer.valueOf(order));
        step.put("title", title);
        step.put("description", description);
        step.put("commands", commands);
        return step;
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
                        "/kanban list [--sort priority|created|status|assignee|title|updated] [--session id] - 查看当前看板任务",
                        "/kanban guide [--json] - 查看看板教程化操作流程",
                        "/kanban create <title> - 创建任务",
                        "/kanban schema <task-json> - 用 JSON 创建结构化任务",
                        "/kanban show <task-id> - 查看任务详情",
                        "/kanban drawer <task-id> [--json] - 查看任务抽屉、流水、运行历史和可用动作",
                        "/kanban move <task-id> <status> - 移动任务状态",
                        "/kanban assign <task-id> <assignee> - 分配执行人",
                        "/kanban step <task-id> <step-key> [--workflow template] [--note text] - 推进任务流程步骤",
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
                        "/kanban dispatch [--dry-run] [--max N] [--max-in-progress N] [--max-in-progress-per-profile N] [--default-assignee name] [--board slug] - 执行一次自动派发",
                        "/kanban daemon status|start|stop [--interval N] [--max N] [--max-in-progress N] [--max-in-progress-per-profile N] [--default-assignee name] [--board slug] - 管理后台派发循环",
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
                + ", skipped_capacity="
                + sizeOf(result.get("skipped_capacity"))
                + ", auto_assigned_default="
                + sizeOf(result.get("auto_assigned_default"))
                + ", skipped_per_profile_capped="
                + sizeOf(result.get("skipped_per_profile_capped"))
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
                + ", max_in_progress="
                + status.get("max_in_progress")
                + ", max_in_progress_per_profile="
                + status.get("max_in_progress_per_profile")
                + ", default_assignee="
                + StrUtil.blankToDefault(String.valueOf(status.get("default_assignee")), "-")
                + ", board="
                + StrUtil.blankToDefault(String.valueOf(status.get("board")), "-")
                + ", ticks="
                + status.get("tick_count")
                + ", last_error="
                + StrUtil.blankToDefault(safeDisplayText(status.get("last_error"), 1000), "-");
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

    private Map<String, Object> notifyClaimView(String taskId, KanbanNotifyClaim claim) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("task_id", taskId);
        result.put("old_cursor", claim.getOldCursor());
        result.put("new_cursor", claim.getNewCursor());
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        for (KanbanEventRecord event : claim.getEvents()) {
            events.add(eventView(event));
        }
        result.put("events", events);
        result.put("claimed", Boolean.valueOf(!events.isEmpty()));
        return result;
    }

    private Map<String, Object> notifyDeliveryView(KanbanNotificationResult delivery) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("subscriptions", Integer.valueOf(delivery.getSubscriptions()));
        result.put("claimed_events", Integer.valueOf(delivery.getClaimedEvents()));
        result.put("delivered_events", Integer.valueOf(delivery.getDeliveredEvents()));
        result.put("failed_events", Integer.valueOf(delivery.getFailedEvents()));
        result.put("removed_subscriptions", Integer.valueOf(delivery.getRemovedSubscriptions()));
        result.put("errors", delivery.getErrors());
        return result;
    }

    private String formatNotifyDelivery(Map<String, Object> result) {
        return "Kanban 通知投递：subscriptions="
                + result.get("subscriptions")
                + ", claimed="
                + result.get("claimed_events")
                + ", delivered="
                + result.get("delivered_events")
                + ", failed="
                + result.get("failed_events")
                + ", removed="
                + result.get("removed_subscriptions");
    }

    private Map<String, Object> notifyBody(String rest, boolean subscribe) {
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        List<String> tokens = positionalTokens(parsed);
        String taskId = firstToken(tokens);
        String platform = firstNonBlank(parsed.value("platform"), tokenAt(tokens, 1));
        String chatId = firstNonBlank(parsed.value("chat-id"), tokenAt(tokens, 2));
        String threadId = firstNonBlank(parsed.value("thread-id"), tokenAt(tokens, 3));
        String userId = parsed.value("user-id");
        if (StrUtil.isBlank(taskId) || StrUtil.isBlank(platform) || StrUtil.isBlank(chatId)) {
            throw new IllegalArgumentException(
                    subscribe
                            ? "用法：/kanban notify-subscribe <task-id> --platform <platform> --chat-id <chat-id> [--thread-id id]"
                            : "用法：/kanban notify-unsubscribe <task-id> --platform <platform> --chat-id <chat-id> [--thread-id id]");
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("task_id", taskId);
        body.put("platform", platform);
        body.put("chat_id", chatId);
        if (StrUtil.isNotBlank(threadId)) {
            body.put("thread_id", threadId);
        }
        if (StrUtil.isNotBlank(userId)) {
            body.put("user_id", userId);
        }
        return body;
    }

    private Map<String, Object> notifyClaimBody(String rest) {
        Map<String, Object> body = notifyBody(rest, true);
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        String kinds = firstNonBlank(parsed.value("kinds"), parsed.value("kind"));
        if (StrUtil.isNotBlank(kinds)) {
            body.put("kinds", kinds);
        }
        return body;
    }

    private Map<String, Object> parseGcOptions(String rest) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        ParsedKanbanOptions parsed = parseCommandOptions(rest);
        List<String> tokens = positionalTokens(parsed);
        String eventRetentionDays =
                firstNonBlank(parsed.value("event-retention-days"), tokenAt(tokens, 0));
        String logRetentionDays =
                firstNonBlank(parsed.value("log-retention-days"), tokenAt(tokens, 1));
        if (StrUtil.isNotBlank(eventRetentionDays)) {
            body.put("event_retention_days", eventRetentionDays);
        }
        if (StrUtil.isNotBlank(logRetentionDays)) {
            body.put("log_retention_days", logRetentionDays);
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

    private String workerLogReference(String taskId) {
        return "runtime://logs/kanban/" + SecretRedactor.redact(StrUtil.nullToEmpty(taskId), 200) + ".log";
    }

    private File workerLogDir() {
        return FileUtil.file(runtimeLogsDir(), "kanban");
    }

    private File scratchWorkspaceRoot() {
        return FileUtil.file(runtimeHome(), "kanban", "workspaces");
    }

    private String workspaceReference(KanbanTaskRecord task) {
        if (task == null) {
            return null;
        }
        if ("scratch".equals(task.getWorkspaceKind())) {
            return "workspace://kanban/" + SecretRedactor.redact(task.getTaskId(), 200);
        }
        String path = task.getWorkspacePath();
        if (StrUtil.isBlank(path)) {
            return null;
        }
        String name = FileUtil.file(path).getName();
        if (StrUtil.isBlank(name)) {
            name = "workspace";
        }
        return "path://" + SecretRedactor.redact(name, 200);
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

    private String taskId(Map<String, Object> task) {
        return String.valueOf(task.get("id"));
    }

    private List<String> positionalTokens(ParsedKanbanOptions parsed) {
        List<String> result = new ArrayList<String>();
        String text = parsed == null ? "" : parsed.positionalText();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        String[] tokens = text.trim().split("\\s+");
        for (String token : tokens) {
            if (StrUtil.isNotBlank(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private String firstToken(List<String> tokens) {
        return tokenAt(tokens, 0);
    }

    private String tokenAt(List<String> tokens, int index) {
        if (tokens == null || index < 0 || index >= tokens.size()) {
            return null;
        }
        return tokens.get(index);
    }

    private String joinTokens(List<String> tokens, int start) {
        if (tokens == null || start < 0 || start >= tokens.size()) {
            return null;
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = start; i < tokens.size(); i++) {
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(tokens.get(i));
        }
        return buffer.toString();
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : second;
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

    private String normalizeWorkspaceKind(String workspaceKind) {
        String value = StrUtil.blankToDefault(workspaceKind, "scratch").trim().toLowerCase(Locale.ROOT);
        if (!WORKSPACE_KINDS.contains(value)) {
            throw new IllegalArgumentException("workspace_kind must be one of " + WORKSPACE_KINDS);
        }
        return value;
    }

    private String normalizeBranchName(String branchName, String workspaceKind) {
        String value = StrUtil.nullToEmpty(branchName).trim();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        if (!"worktree".equals(workspaceKind)) {
            throw new IllegalArgumentException("branch_name is only valid for worktree workspaces");
        }
        return value;
    }

    private String normalizeRunStateFilter(String stateType, String stateName) {
        boolean hasType = StrUtil.isNotBlank(stateType);
        boolean hasName = StrUtil.isNotBlank(stateName);
        if (hasType != hasName) {
            throw new IllegalArgumentException("state_type and state_name must both be provided");
        }
        if (!hasType) {
            return null;
        }
        String type = stateType.trim().toLowerCase(Locale.ROOT);
        if (!"status".equals(type) && !"outcome".equals(type)) {
            throw new IllegalArgumentException("state_type must be status or outcome");
        }
        return type;
    }

    private boolean matchesRunState(KanbanRunRecord run, String stateType, String stateName) {
        String actual = "status".equals(stateType) ? run.getStatus() : run.getOutcome();
        return StrUtil.equals(actual, stateName);
    }

    private String normalizeTaskOrderBy(String orderBy) {
        if (StrUtil.isBlank(orderBy)) {
            return null;
        }
        String value = orderBy.trim().toLowerCase(Locale.ROOT);
        if (!TASK_SORT_ORDERS.contains(value)) {
            throw new IllegalArgumentException("order_by must be one of " + TASK_SORT_ORDERS);
        }
        return value;
    }

    private void sortTasks(List<KanbanTaskRecord> tasks, final String orderBy) {
        if (StrUtil.isBlank(orderBy) || tasks == null || tasks.size() < 2) {
            return;
        }
        Collections.sort(
                tasks,
                (left, right) -> {
                    int compared = compareTaskOrder(left, right, orderBy);
                    return compared == 0
                            ? compareText(left.getTaskId(), right.getTaskId())
                            : compared;
                });
    }

    private int compareTaskOrder(KanbanTaskRecord left, KanbanTaskRecord right, String orderBy) {
        if ("created".equals(orderBy)) {
            int compared = Long.compare(left.getCreatedAt(), right.getCreatedAt());
            return compared == 0 ? compareText(left.getTaskId(), right.getTaskId()) : compared;
        }
        if ("created-desc".equals(orderBy)) {
            int compared = Long.compare(right.getCreatedAt(), left.getCreatedAt());
            return compared == 0 ? compareText(right.getTaskId(), left.getTaskId()) : compared;
        }
        if ("priority".equals(orderBy)) {
            int compared = Integer.compare(right.getPriority(), left.getPriority());
            return compared == 0 ? Long.compare(left.getCreatedAt(), right.getCreatedAt()) : compared;
        }
        if ("priority-desc".equals(orderBy)) {
            int compared = Integer.compare(left.getPriority(), right.getPriority());
            return compared == 0 ? Long.compare(left.getCreatedAt(), right.getCreatedAt()) : compared;
        }
        if ("status".equals(orderBy)) {
            int compared = compareText(left.getStatus(), right.getStatus());
            return compared == 0 ? Long.compare(left.getCreatedAt(), right.getCreatedAt()) : compared;
        }
        if ("assignee".equals(orderBy)) {
            int compared = compareText(left.getAssignee(), right.getAssignee());
            return compared == 0 ? Long.compare(left.getCreatedAt(), right.getCreatedAt()) : compared;
        }
        if ("title".equals(orderBy)) {
            int compared = compareText(left.getTitle(), right.getTitle());
            return compared == 0 ? compareText(left.getTaskId(), right.getTaskId()) : compared;
        }
        if ("updated".equals(orderBy)) {
            int compared = compareLongDescZeroLast(left.getUpdatedAt(), right.getUpdatedAt());
            return compared == 0 ? Long.compare(right.getCreatedAt(), left.getCreatedAt()) : compared;
        }
        return 0;
    }

    private int compareText(String left, String right) {
        return StrUtil.nullToEmpty(left).compareToIgnoreCase(StrUtil.nullToEmpty(right));
    }

    private int compareLongDescZeroLast(long left, long right) {
        boolean leftBlank = left <= 0;
        boolean rightBlank = right <= 0;
        if (leftBlank && rightBlank) {
            return 0;
        }
        if (leftBlank) {
            return 1;
        }
        if (rightBlank) {
            return -1;
        }
        return Long.compare(right, left);
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

    private int intText(String value, int defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
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

    private void recordBlocked(String taskId, String summary, String result) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("summary", summaryPreview(StrUtil.blankToDefault(summary, result)));
        payload.put("reason", summaryPreview(StrUtil.blankToDefault(result, summary)));
        addEvent(taskId, "blocked", payload);
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
        item.put("payload", safeDisplayValue(parseJson(event.getPayloadJson())));
        if (StrUtil.isNotBlank(event.getNotifyCursor())) {
            item.put("cursor", event.getNotifyCursor());
        }
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
        item.put("finished", Boolean.valueOf(run.getEndedAt() > 0));
        item.put("running", Boolean.valueOf(isRunningRun(run)));
        item.put("duration_ms", runDurationMillis(run));
        item.put("timed_out", Boolean.valueOf(isTimedOutRun(run)));
        item.put("outcome", run.getOutcome());
        item.put("summary", safeDisplayText(run.getSummary(), 1000));
        item.put("metadata", parseJson(run.getMetadataJson()));
        item.put("error", safeDisplayText(run.getError(), 1000));
        return item;
    }

    private boolean isRunningRun(KanbanRunRecord run) {
        return run != null && run.getEndedAt() <= 0 && "running".equalsIgnoreCase(run.getStatus());
    }

    private Long runDurationMillis(KanbanRunRecord run) {
        if (run == null || run.getStartedAt() <= 0) {
            return null;
        }
        long end = run.getEndedAt() > 0 ? run.getEndedAt() : System.currentTimeMillis();
        return Long.valueOf(Math.max(0L, end - run.getStartedAt()));
    }

    private boolean isTimedOutRun(KanbanRunRecord run) {
        if (run == null) {
            return false;
        }
        if ("timed_out".equalsIgnoreCase(StrUtil.nullToEmpty(run.getOutcome()))) {
            return true;
        }
        if (!isRunningRun(run) || run.getMaxRuntimeSeconds() <= 0 || run.getStartedAt() <= 0) {
            return false;
        }
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - run.getStartedAt());
        return elapsedMillis > run.getMaxRuntimeSeconds() * 1000L;
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
        if (StrUtil.isNotBlank(task.getBranchName())) {
            buffer.append("Branch:   ").append(task.getBranchName()).append('\n');
        }
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
                if (StrUtil.isNotBlank(parent.getResult())) {
                    buffer.append("  result: ")
                            .append(capped(parent.getResult(), CONTEXT_MAX_TEXT))
                            .append('\n');
                }
                KanbanRunRecord latestParentRun = repository.latestRun(parent.getTaskId());
                if (latestParentRun != null && StrUtil.isNotBlank(latestParentRun.getSummary())) {
                    buffer.append("  summary: ")
                            .append(capped(latestParentRun.getSummary(), CONTEXT_MAX_TEXT))
                            .append('\n');
                }
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
                appendContextField(buffer, "  summary", safeDisplayText(run.get("summary"), 1000));
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
        buffer.append(label).append(": ").append(capped(safeDisplayText(value, CONTEXT_MAX_TEXT), CONTEXT_MAX_TEXT)).append('\n');
    }

    private String safeDisplayText(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        return SecretRedactor.redact(String.valueOf(value), maxLength);
    }

    private Object safeDisplayValue(Object value) {
        if (value instanceof String) {
            return safeDisplayText(value, CONTEXT_MAX_TEXT);
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), safeDisplayValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                result.add(safeDisplayValue(item));
            }
            return result;
        }
        return value;
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

    private Map<String, Object> parseJsonObject(String json, String label) {
        Object parsed = ONode.deserialize(json, Object.class);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
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
        String text = StrUtil.nullToEmpty(safeDisplayText(value, 1000)).trim();
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
