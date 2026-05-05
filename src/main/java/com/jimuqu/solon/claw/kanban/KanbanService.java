package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.util.StrUtil;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared service for dashboard and slash-command Kanban operations. */
public class KanbanService {
    public static final List<String> STATUSES =
            Collections.unmodifiableList(
                    Arrays.asList("triage", "todo", "ready", "running", "blocked", "done", "archived"));

    private final KanbanRepository repository;

    public KanbanService(KanbanRepository repository) {
        this.repository = repository;
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
        task.setTitle(text(body, "title"));
        task.setBody(text(body, "body"));
        task.setAssignee(text(body, "assignee"));
        task.setStatus(StrUtil.blankToDefault(text(body, "status"), "todo"));
        task.setPriority(intValue(body, "priority", 0));
        task.setTenant(text(body, "tenant"));
        task.setWorkspaceKind(StrUtil.blankToDefault(text(body, "workspace_kind"), "scratch"));
        task.setWorkspacePath(text(body, "workspace_path"));
        task.setCreatedBy(StrUtil.blankToDefault(text(body, "created_by"), "user"));
        return taskView(repository.saveTask(task), true);
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
        return taskView(repository.saveTask(task), true);
    }

    public Map<String, Object> status(String taskId, String status, String result) throws Exception {
        if (!repository.updateTaskStatus(taskId, normalizeStatus(status), result)) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return task(taskId);
    }

    public Map<String, Object> assign(String taskId, String assignee) throws Exception {
        if (!repository.assignTask(taskId, assignee)) {
            throw new IllegalArgumentException("Kanban task not found: " + taskId);
        }
        return task(taskId);
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

    private String kanbanHelp() {
        return String.join(
                "\n",
                Arrays.asList(
                        "/kanban list - 查看当前看板任务",
                        "/kanban create <title> - 创建任务",
                        "/kanban show <task-id> - 查看任务详情",
                        "/kanban move <task-id> <status> - 移动任务状态",
                        "/kanban assign <task-id> <assignee> - 分配执行人",
                        "/kanban comment <task-id> <text> - 追加评论",
                        "/kanban done <task-id> - 标记完成",
                        "/kanban boards [list|create|switch] - 管理多看板"));
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

    private String iso(long epochMillis) {
        if (epochMillis <= 0) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(epochMillis));
    }
}
