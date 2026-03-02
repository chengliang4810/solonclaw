package com.jimuqu.solonclaw.autonomous;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 自主任务
 * <p>
 * 代表 Agent 自主执行的任务
 *
 * @author SolonClaw
 */
public class AutonomousTask {

    private String id;
    private TaskType type;
    private String description;
    private int priority;
    private TaskStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Map<String, Object> metadata;
    private String result;

    public AutonomousTask() {
    }

    public AutonomousTask(TaskType type, String description, int priority) {
        this.type = type;
        this.description = description;
        this.priority = priority;
        this.status = TaskStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public TaskType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getResult() {
        return result;
    }

    // Builder methods
    public AutonomousTask withId(String id) {
        AutonomousTask task = copy();
        task.id = id;
        return task;
    }

    public AutonomousTask withType(TaskType type) {
        AutonomousTask task = copy();
        task.type = type;
        return task;
    }

    public AutonomousTask withDescription(String description) {
        AutonomousTask task = copy();
        task.description = description;
        return task;
    }

    public AutonomousTask withPriority(int priority) {
        AutonomousTask task = copy();
        task.priority = priority;
        return task;
    }

    public AutonomousTask withStatus(TaskStatus status) {
        AutonomousTask task = copy();
        task.status = status;
        return task;
    }

    public AutonomousTask withStartedAt(LocalDateTime startedAt) {
        AutonomousTask task = copy();
        task.startedAt = startedAt;
        return task;
    }

    public AutonomousTask withCompletedAt(LocalDateTime completedAt) {
        AutonomousTask task = copy();
        task.completedAt = completedAt;
        return task;
    }

    public AutonomousTask withMetadata(Map<String, Object> metadata) {
        AutonomousTask task = copy();
        task.metadata = metadata;
        return task;
    }

    public AutonomousTask withResult(String result) {
        AutonomousTask task = copy();
        task.result = result;
        return task;
    }

    private AutonomousTask copy() {
        AutonomousTask task = new AutonomousTask();
        task.id = this.id;
        task.type = this.type;
        task.description = this.description;
        task.priority = this.priority;
        task.status = this.status;
        task.createdAt = this.createdAt;
        task.startedAt = this.startedAt;
        task.completedAt = this.completedAt;
        task.metadata = this.metadata;
        task.result = this.result;
        return task;
    }
}