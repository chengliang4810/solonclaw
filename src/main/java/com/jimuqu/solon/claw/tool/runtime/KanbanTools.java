package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.kanban.KanbanService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** Hermes-style Kanban worker tools. */
@RequiredArgsConstructor
public class KanbanTools {
    private final KanbanService kanbanService;

    @ToolMapping(
            name = "kanban_show",
            description =
                    "Read a Kanban task's full state including parents, children, comments, runs, events, and worker_context. If task_id is omitted, uses JIMUQU_KANBAN_TASK or HERMES_KANBAN_TASK.")
    public String kanbanShow(
            @Param(name = "task_id", description = "Task id. Optional when the worker task env is set.", required = false)
                    String taskId) {
        String tid = defaultTaskId(taskId);
        if (StrUtil.isBlank(tid)) {
            return error("task_id is required or set JIMUQU_KANBAN_TASK");
        }
        try {
            Map<String, Object> task = kanbanService.task(tid);
            return ToolResultEnvelope.ok("Loaded Kanban task: " + tid)
                    .data("task", task)
                    .data("worker_context", task.get("worker_context"))
                    .preview(String.valueOf(task.get("worker_context")))
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
            return ToolResultEnvelope.ok("Completed Kanban task: " + tid)
                    .data("task", detail)
                    .data("metadata", parseMetadata(metadata))
                    .preview(String.valueOf(detail.get("worker_context")))
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
            return ToolResultEnvelope.ok("Blocked Kanban task: " + tid)
                    .data("task", detail)
                    .preview(reason)
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
            return ToolResultEnvelope.ok("Heartbeat recorded: " + tid)
                    .data("task", detail)
                    .preview("heartbeat " + tid)
                    .toJson();
        } catch (Exception e) {
            return error("kanban_heartbeat: " + e.getMessage());
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
            return ToolResultEnvelope.ok("Comment added: " + taskId)
                    .data("task", detail)
                    .preview(body)
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
            return ToolResultEnvelope.ok("Created Kanban task: " + detail.get("id"))
                    .data("task", detail)
                    .data("task_id", detail.get("id"))
                    .preview(detail.get("id") + " " + title)
                    .toJson();
        } catch (Exception e) {
            return error("kanban_create: " + e.getMessage());
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
                    .data("parent_id", parentId)
                    .data("child_id", childId)
                    .data("child", child)
                    .preview(parentId + " -> " + childId)
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
                    .data("parent_id", parentId)
                    .data("child_id", childId)
                    .data("child", child)
                    .preview(parentId + " -/-> " + childId)
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
        if (StrUtil.isBlank(env)) {
            env = System.getenv("HERMES_KANBAN_TASK");
        }
        return StrUtil.blankToDefault(env, null);
    }

    private String ownershipError(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            return error("task_id is required or set JIMUQU_KANBAN_TASK");
        }
        String scoped = System.getenv("JIMUQU_KANBAN_TASK");
        if (StrUtil.isBlank(scoped)) {
            scoped = System.getenv("HERMES_KANBAN_TASK");
        }
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
            name = System.getenv("HERMES_PROFILE");
        }
        if (StrUtil.isBlank(name)) {
            name = System.getenv("JIMUQU_KANBAN_WORKER");
        }
        if (StrUtil.isBlank(name)) {
            name = System.getenv("HERMES_KANBAN_WORKER");
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

    private String error(String message) {
        return ToolResultEnvelope.error(message).toJson();
    }
}
