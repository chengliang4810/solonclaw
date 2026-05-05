package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.util.StrUtil;
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
    private KanbanDispatcherService dispatcherService;

    public KanbanService(KanbanRepository repository) {
        this.repository = repository;
    }

    public void setDispatcherService(KanbanDispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    public List<Map<String, Object>> boards() throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanBoardRecord board : repository.listBoards()) {
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
            result.add(item);
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

    public Map<String, Object> switchBoard(String slug) throws Exception {
        repository.switchBoard(slug);
        return currentBoard();
    }

    public List<Map<String, Object>> tasks(String board, String status, boolean includeArchived)
            throws Exception {
        String normalizedStatus = StrUtil.isBlank(status) ? null : normalizeStatus(status);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (KanbanTaskRecord task : repository.listTasks(board, normalizedStatus, includeArchived)) {
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
        return taskView(repository.saveTask(task), true);
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
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("title", requireArg(rest, "/kanban create <title>"));
            body.put("created_by", author);
            return "已创建看板任务："
                    + taskId(createTask(body))
                    + "\n"
                    + formatTaskList(tasks(null, null, false));
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
        if ("done".equals(action) || "complete".equals(action)) {
            status(requireArg(rest, "/kanban done <task-id>"), "done", null);
            return "已完成任务：" + rest;
        }
        if ("archive".equals(action)) {
            status(requireArg(rest, "/kanban archive <task-id>"), "archived", null);
            return "已归档任务：" + rest;
        }
        return kanbanHelp();
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
        if (StrUtil.isBlank(raw) || "list".equalsIgnoreCase(raw) || "ls".equalsIgnoreCase(raw)) {
            StringBuilder buffer = new StringBuilder();
            for (Map<String, Object> board : boards()) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(Boolean.TRUE.equals(board.get("current")) ? "* " : "- ")
                        .append(board.get("slug"))
                        .append("  ")
                        .append(board.get("name"));
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
        return "用法：/kanban boards [list|create <slug> [name]|switch <slug>]";
    }

    private Map<String, Object> boardView(KanbanBoardRecord board) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", board.getBoardId());
        result.put("slug", board.getSlug());
        result.put("name", board.getName());
        result.put("description", board.getDescription());
        result.put("color", board.getColor());
        result.put("current", Boolean.valueOf(board.isCurrent()));
        result.put("created_at", iso(board.getCreatedAt()));
        result.put("updated_at", iso(board.getUpdatedAt()));
        return result;
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
        StringBuilder buffer = new StringBuilder("执行流水：").append(taskId);
        for (Map<String, Object> event : events) {
            buffer.append('\n')
                    .append("- ")
                    .append(StrUtil.blankToDefault(String.valueOf(event.get("created_at")), "-"))
                    .append("  ")
                    .append(event.get("kind"));
            Object payload = event.get("payload");
            if (payload != null) {
                buffer.append("  ").append(ONode.serialize(payload));
            }
        }
        return buffer.toString();
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
                        "/kanban runs <task-id> - 查看任务执行历史",
                        "/kanban events <task-id> - 查看任务执行流水",
                        "/kanban context <task-id> - 输出 worker 上下文",
                        "/kanban diagnostics [task-id] - 查看任务诊断与恢复提示",
                        "/kanban claim <task-id> [ttl_seconds] - 认领 ready 任务并开始运行",
                        "/kanban next [assignee] - 认领下一个 ready 任务",
                        "/kanban heartbeat <task-id> [note] - 刷新认领和 worker 心跳",
                        "/kanban release-stale - 收回过期认领",
                        "/kanban reclaim-timeouts - 收回超过运行时限的任务",
                        "/kanban dispatch [--dry-run] [--max N] [--board slug] - 执行一次自动派发",
                        "/kanban daemon status|start|stop [--interval N] [--max N] [--board slug] - 管理后台派发循环",
                        "/kanban comment <task-id> <text> - 追加评论",
                        "/kanban done <task-id> - 标记完成",
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
}
