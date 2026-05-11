package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.kanban.KanbanService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Jimuqu Kanban worker tools. */
@RequiredArgsConstructor
public class KanbanTools {
    private final KanbanService kanbanService;

    @ToolMapping(
            name = "kanban_show",
            description =
                    "Read a Kanban task's full state including parents, children, comments, runs, events, and worker_context. If task_id is omitted, uses JIMUQU_KANBAN_TASK.")
    public String kanbanShow(
            @Param(name = "task_id", description = "Task id. Optional when the worker task env is set.", required = false)
                    String taskId) {
        String tid = defaultTaskId(taskId);
        if (StrUtil.isBlank(tid)) {
            return error("task_id is required or set JIMUQU_KANBAN_TASK");
        }
        try {
            Map<String, Object> task = kanbanService.task(tid);
            return ToolResultEnvelope.ok("Loaded Kanban task: " + safeText(tid))
                    .data("task", safeObject(task))
                    .data("worker_context", safeObject(task.get("worker_context")))
                    .preview(safeText(task.get("worker_context"), 2000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_show: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_complete",
            description =
                    "Mark the current Kanban task done with a structured handoff. At least one of summary or result is required. Worker-scoped calls may only mutate their own task.")
    public String kanbanComplete(
            @Param(name = "task_id", description = "Task id. Optional when worker env is set.", required = false)
                    String taskId,
            @Param(name = "summary", description = "Human-readable handoff summary.", required = false)
                    String summary,
            @Param(name = "result", description = "Detailed result or output.", required = false)
                    String result,
            @Param(name = "metadata", description = "Machine-readable metadata JSON/string.", required = false)
                    String metadata,
            @Param(name = "created_cards", description = "Comma-separated task ids created by this worker.", required = false)
                    String createdCards) {
        String tid = defaultTaskId(taskId);
        String ownership = ownershipError(tid);
        if (ownership != null) {
            return ownership;
        }
        if (StrUtil.isBlank(summary) && StrUtil.isBlank(result)) {
            return error("provide at least one of: summary or result");
        }
        try {
            Map<String, Object> detail =
                    kanbanService.status(
                            tid,
                            "done",
                            StrUtil.blankToDefault(result, summary),
                            summary,
                            splitList(createdCards));
            return ToolResultEnvelope.ok("Completed Kanban task: " + safeText(tid))
                    .data("task", safeObject(detail))
                    .data("metadata", safeObject(parseMetadata(metadata)))
                    .preview(safeText(detail.get("worker_context"), 2000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_complete: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_block",
            description =
                    "Transition a Kanban task to blocked with a human-readable reason. Worker-scoped calls may only mutate their own task.")
    public String kanbanBlock(
            @Param(name = "task_id", description = "Task id. Optional when worker env is set.", required = false)
                    String taskId,
            @Param(name = "reason", description = "Reason human input is required.") String reason) {
        String tid = defaultTaskId(taskId);
        String ownership = ownershipError(tid);
        if (ownership != null) {
            return ownership;
        }
        if (StrUtil.isBlank(reason)) {
            return error("reason is required");
        }
        try {
            Map<String, Object> detail = kanbanService.status(tid, "blocked", reason, reason, null);
            return ToolResultEnvelope.ok("Blocked Kanban task: " + safeText(tid))
                    .data("task", safeObject(detail))
                    .preview(safeText(reason, 1000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_block: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_heartbeat",
            description =
                    "Record worker liveness for a long-running Kanban task. Worker-scoped calls may only heartbeat their own task.")
    public String kanbanHeartbeat(
            @Param(name = "task_id", description = "Task id. Optional when worker env is set.", required = false)
                    String taskId,
            @Param(name = "note", description = "Optional heartbeat note.", required = false) String note) {
        String tid = defaultTaskId(taskId);
        String ownership = ownershipError(tid);
        if (ownership != null) {
            return ownership;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("note", note);
            Map<String, Object> detail = kanbanService.heartbeatWorker(tid, body);
            return ToolResultEnvelope.ok("Heartbeat recorded: " + safeText(tid))
                    .data("task", safeObject(detail))
                    .preview("heartbeat " + safeText(tid))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_heartbeat: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_step",
            description =
                    "Advance a Kanban task to a workflow/pipeline step and record a step_changed event. Worker-scoped calls may only mutate their own task.")
    public String kanbanStep(
            @Param(name = "task_id", description = "Task id. Optional when worker env is set.", required = false)
                    String taskId,
            @Param(name = "step_key", description = "Target workflow step key.") String stepKey,
            @Param(name = "workflow_template_id", description = "Optional workflow template id.", required = false)
                    String workflowTemplateId,
            @Param(name = "note", description = "Optional transition note.", required = false) String note) {
        String tid = defaultTaskId(taskId);
        String ownership = ownershipError(tid);
        if (ownership != null) {
            return ownership;
        }
        if (StrUtil.isBlank(stepKey)) {
            return error("step_key is required");
        }
        try {
            Map<String, Object> detail =
                    kanbanService.step(tid, stepKey, workflowTemplateId, note, defaultWorkerName());
            return ToolResultEnvelope.ok(
                            "Advanced Kanban step: "
                                    + safeText(tid)
                                    + " -> "
                                    + safeText(detail.get("current_step_key")))
                    .data("task", safeObject(detail))
                    .preview(safeText(detail.get("worker_context"), 2000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_step: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_comment",
            description =
                    "Append a durable comment to a Kanban task. Comments may target any task for handoffs, questions, or findings.")
    public String kanbanComment(
            @Param(name = "task_id", description = "Task id.") String taskId,
            @Param(name = "body", description = "Comment body.") String body,
            @Param(name = "author", description = "Comment author.", required = false) String author) {
        if (StrUtil.isBlank(taskId)) {
            return error("task_id is required");
        }
        if (StrUtil.isBlank(body)) {
            return error("body is required");
        }
        try {
            Map<String, Object> detail =
                    kanbanService.comment(taskId, StrUtil.blankToDefault(author, defaultWorkerName()), body);
            return ToolResultEnvelope.ok("Comment added: " + safeText(taskId))
                    .data("task", safeObject(detail))
                    .preview(safeText(body, 1000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_comment: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_create",
            description =
                    "Create a Kanban task, optionally with parent links, idempotency_key, skills, workflow step, and runtime limit.")
    public String kanbanCreate(
            @Param(name = "title", description = "Short task title.") String title,
            @Param(name = "assignee", description = "Profile or worker that should execute the task.") String assignee,
            @Param(name = "body", description = "Task body.", required = false) String body,
            @Param(name = "parents", description = "Comma-separated parent task ids.", required = false) String parents,
            @Param(name = "idempotency_key", description = "Optional idempotency key.", required = false)
                    String idempotencyKey,
            @Param(name = "skills", description = "Comma-separated skills.", required = false) String skills,
            @Param(name = "max_retries", description = "Per-task max spawn retries.", required = false)
                    Integer maxRetries,
            @Param(name = "max_runtime_seconds", description = "Runtime limit in seconds.", required = false)
                    Long maxRuntimeSeconds) {
        if (StrUtil.isBlank(title)) {
            return error("title is required");
        }
        if (StrUtil.isBlank(assignee)) {
            return error("assignee is required");
        }
        try {
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("title", title);
            request.put("assignee", assignee);
            request.put("body", body);
            request.put("created_by", defaultWorkerName());
            request.put("parents", splitList(parents));
            request.put("idempotency_key", idempotencyKey);
            request.put("skills", splitList(skills));
            if (maxRetries != null) {
                request.put("max_retries", maxRetries);
            }
            if (maxRuntimeSeconds != null) {
                request.put("max_runtime_seconds", maxRuntimeSeconds);
            }
            Map<String, Object> detail = kanbanService.createTask(request);
            return ToolResultEnvelope.ok("Created Kanban task: " + safeText(detail.get("id")))
                    .data("task", safeObject(detail))
                    .data("task_id", safeObject(detail.get("id")))
                    .preview(safeText(detail.get("id") + " " + title, 1000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_create: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_schema_create",
            description =
                    "Create a Kanban task from a structured JSON object. Supports title, body, assignee, board, status, priority, tenant, parents, skills, workflow_template_id, current_step_key, idempotency_key, max_retries, and max_runtime_seconds.")
    public String kanbanSchemaCreate(
            @Param(
                            name = "task_json",
                            description =
                                    "Task JSON object. Example: {\"title\":\"Review API\",\"assignee\":\"reviewer\",\"parents\":[\"task-1\"],\"skills\":[\"review\"],\"workflow_template_id\":\"delivery\",\"current_step_key\":\"review\"}.")
                    String taskJson) {
        if (StrUtil.isBlank(taskJson)) {
            return error("task_json is required");
        }
        try {
            Map<String, Object> request = parseTaskJson(taskJson);
            if (request == null || request.isEmpty()) {
                return error("task_json must be a JSON object");
            }
            if (StrUtil.isBlank(stringValue(request.get("title")))) {
                return error("title is required");
            }
            if (StrUtil.isBlank(stringValue(request.get("assignee")))) {
                return error("assignee is required");
            }
            if (!request.containsKey("created_by")) {
                request.put("created_by", defaultWorkerName());
            }
            Map<String, Object> detail = kanbanService.createTask(request);
            return ToolResultEnvelope.ok("Created Kanban schema task: " + safeText(detail.get("id")))
                    .data("task", safeObject(detail))
                    .data("task_id", safeObject(detail.get("id")))
                    .preview(safeText(detail.get("id") + " " + detail.get("title"), 1000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_schema_create: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_link",
            description = "Add a parent-child dependency edge between two existing Kanban tasks.")
    public String kanbanLink(
            @Param(name = "parent_id", description = "Parent task id.") String parentId,
            @Param(name = "child_id", description = "Child task id.") String childId) {
        if (StrUtil.hasBlank(parentId, childId)) {
            return error("both parent_id and child_id are required");
        }
        try {
            Map<String, Object> child = kanbanService.link(parentId, childId);
            return ToolResultEnvelope.ok("Linked Kanban tasks")
                    .data("parent_id", safeText(parentId))
                    .data("child_id", safeText(childId))
                    .data("child", safeObject(child))
                    .preview(safeText(parentId + " -> " + childId, 1000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_link: " + e.getMessage());
        }
    }

    @ToolMapping(
            name = "kanban_unlink",
            description = "Remove an existing parent-child dependency edge between two Kanban tasks.")
    public String kanbanUnlink(
            @Param(name = "parent_id", description = "Parent task id.") String parentId,
            @Param(name = "child_id", description = "Child task id.") String childId) {
        if (StrUtil.hasBlank(parentId, childId)) {
            return error("both parent_id and child_id are required");
        }
        try {
            Map<String, Object> child = kanbanService.unlink(parentId, childId);
            return ToolResultEnvelope.ok("Unlinked Kanban tasks")
                    .data("parent_id", safeText(parentId))
                    .data("child_id", safeText(childId))
                    .data("child", safeObject(child))
                    .preview(safeText(parentId + " -/-> " + childId, 1000))
                    .toJson();
        } catch (Exception e) {
            return error("kanban_unlink: " + e.getMessage());
        }
    }

    private String defaultTaskId(String taskId) {
        if (StrUtil.isNotBlank(taskId)) {
            return taskId.trim();
        }
        String env = System.getenv("JIMUQU_KANBAN_TASK");
        return StrUtil.blankToDefault(env, null);
    }

    private String ownershipError(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return error("task_id is required or set JIMUQU_KANBAN_TASK");
        }
        String scoped = System.getenv("JIMUQU_KANBAN_TASK");
        if (StrUtil.isNotBlank(scoped) && !StrUtil.equals(scoped, taskId)) {
            return error(
                    "worker is scoped to task "
                            + scoped
                            + "; refusing to mutate "
                            + taskId
                            + ". Use kanban_comment to hand off information or kanban_create to spawn follow-up work.");
        }
        return null;
    }

    private String defaultWorkerName() {
        String name = System.getenv("JIMUQU_PROFILE");
        if (StrUtil.isBlank(name)) {
            name = System.getenv("JIMUQU_KANBAN_WORKER");
        }
        return StrUtil.blankToDefault(name, "worker");
    }

    private List<String> splitList(String value) {
        java.util.ArrayList<String> result = new java.util.ArrayList<String>();
        if (StrUtil.isBlank(value)) {
            return result;
        }
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? null : parts[i].trim();
            if (StrUtil.isNotBlank(part) && !result.contains(part)) {
                result.add(part);
            }
        }
        return result;
    }

    private Object parseMetadata(String metadata) {
        if (StrUtil.isBlank(metadata)) {
            return null;
        }
        try {
            return ONode.deserialize(metadata, Object.class);
        } catch (Exception e) {
            return metadata;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTaskJson(String taskJson) {
        Object parsed = ONode.deserialize(taskJson, Object.class);
        if (!(parsed instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
            if (entry.getKey() != null) {
                request.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return request;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Object safeObject(Object value) {
        if (value instanceof String) {
            return safeText(value);
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    safe.put(String.valueOf(entry.getKey()), safeObject(entry.getValue()));
                }
            }
            return safe;
        }
        if (value instanceof List<?>) {
            java.util.ArrayList<Object> safe = new java.util.ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                safe.add(safeObject(item));
            }
            return safe;
        }
        return value;
    }

    private String safeText(Object value) {
        return safeText(value, 1000);
    }

    private String safeText(Object value, int maxLength) {
        return value == null ? null : SecretRedactor.redact(String.valueOf(value), maxLength);
    }

    private String error(String message) {
        return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
    }
}
