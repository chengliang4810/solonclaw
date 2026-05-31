package com.jimuqu.solon.claw.kanban;

/** Durable Jimuqu Kanban task. */
public class KanbanTaskRecord {
    private String taskId;
    private String boardSlug;
    private String title;
    private String body;
    private String assignee;
    private String status;
    private int priority;
    private String tenant;
    private String sessionId;
    private String workspaceKind;
    private String workspacePath;
    private String branchName;
    private String createdBy;
    private String result;
    private String idempotencyKey;
    private String claimLock;
    private long claimExpiresAt;
    private String workerId;
    private long workerPid;
    private String lastSpawnError;
    private int spawnFailures;
    private Integer maxRetries;
    private long maxRuntimeSeconds;
    private long lastHeartbeatAt;
    private String currentRunId;
    private String workflowTemplateId;
    private String currentStepKey;
    private String skillsJson;
    private long createdAt;
    private long updatedAt;
    private long startedAt;
    private long completedAt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getBoardSlug() {
        return boardSlug;
    }

    public void setBoardSlug(String boardSlug) {
        this.boardSlug = boardSlug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getWorkspaceKind() {
        return workspaceKind;
    }

    public void setWorkspaceKind(String workspaceKind) {
        this.workspaceKind = workspaceKind;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getClaimLock() {
        return claimLock;
    }

    public void setClaimLock(String claimLock) {
        this.claimLock = claimLock;
    }

    public long getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public void setClaimExpiresAt(long claimExpiresAt) {
        this.claimExpiresAt = claimExpiresAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public long getWorkerPid() {
        return workerPid;
    }

    public void setWorkerPid(long workerPid) {
        this.workerPid = workerPid;
    }

    public String getLastSpawnError() {
        return lastSpawnError;
    }

    public void setLastSpawnError(String lastSpawnError) {
        this.lastSpawnError = lastSpawnError;
    }

    public int getSpawnFailures() {
        return spawnFailures;
    }

    public void setSpawnFailures(int spawnFailures) {
        this.spawnFailures = spawnFailures;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public void setMaxRuntimeSeconds(long maxRuntimeSeconds) {
        this.maxRuntimeSeconds = maxRuntimeSeconds;
    }

    public long getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(long lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public String getCurrentRunId() {
        return currentRunId;
    }

    public void setCurrentRunId(String currentRunId) {
        this.currentRunId = currentRunId;
    }

    public String getWorkflowTemplateId() {
        return workflowTemplateId;
    }

    public void setWorkflowTemplateId(String workflowTemplateId) {
        this.workflowTemplateId = workflowTemplateId;
    }

    public String getCurrentStepKey() {
        return currentStepKey;
    }

    public void setCurrentStepKey(String currentStepKey) {
        this.currentStepKey = currentStepKey;
    }

    public String getSkillsJson() {
        return skillsJson;
    }

    public void setSkillsJson(String skillsJson) {
        this.skillsJson = skillsJson;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(long completedAt) {
        this.completedAt = completedAt;
    }
}
