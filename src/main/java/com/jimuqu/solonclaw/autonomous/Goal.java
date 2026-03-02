package com.jimuqu.solonclaw.autonomous;

import java.time.LocalDateTime;

/**
 * 目标
 * <p>
 * 代表 Agent 的长期目标
 *
 * @author SolonClaw
 */
public class Goal {

    private String id;
    private String parentId;
    private String title;
    private String description;
    private int priority;
    private double progress;
    private GoalStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private String deadline;
    private String failureReason;

    public Goal() {
    }

    public Goal(String title, String description, int priority, String parentId) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.parentId = parentId;
        this.progress = 0.0;
        this.status = GoalStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }

    public double getProgress() {
        return progress;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getDeadline() {
        return deadline;
    }

    public String getFailureReason() {
        return failureReason;
    }

    // Builder methods
    public Goal withId(String id) {
        Goal goal = copy();
        goal.id = id;
        return goal;
    }

    public Goal withParentId(String parentId) {
        Goal goal = copy();
        goal.parentId = parentId;
        return goal;
    }

    public Goal withProgress(double progress) {
        Goal goal = copy();
        goal.progress = progress;
        return goal;
    }

    public Goal withStatus(GoalStatus status) {
        Goal goal = copy();
        goal.status = status;
        return goal;
    }

    public Goal withCreatedAt(LocalDateTime createdAt) {
        Goal goal = copy();
        goal.createdAt = createdAt;
        return goal;
    }

    public Goal withCompletedAt(LocalDateTime completedAt) {
        Goal goal = copy();
        goal.completedAt = completedAt;
        return goal;
    }

    public Goal withFailureReason(String failureReason) {
        Goal goal = copy();
        goal.failureReason = failureReason;
        return goal;
    }

    private Goal copy() {
        Goal goal = new Goal();
        goal.id = this.id;
        goal.parentId = this.parentId;
        goal.title = this.title;
        goal.description = this.description;
        goal.priority = this.priority;
        goal.progress = this.progress;
        goal.status = this.status;
        goal.createdAt = this.createdAt;
        goal.completedAt = this.completedAt;
        goal.deadline = this.deadline;
        goal.failureReason = this.failureReason;
        return goal;
    }
}