package com.jimuqu.solon.claw.kanban;

/** One durable execution attempt for a Kanban task. */
public class KanbanRunRecord {
    private String runId;
    private String taskId;
    private String profile;
    private String stepKey;
    private String status;
    private String claimLock;
    private long claimExpiresAt;
    private long workerPid;
    private String workerId;
    private long maxRuntimeSeconds;
    private long lastHeartbeatAt;
    private long startedAt;
    private long endedAt;
    private String outcome;
    private String summary;
    private String metadataJson;
    private String error;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getStepKey() {
        return stepKey;
    }

    public void setStepKey(String stepKey) {
        this.stepKey = stepKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public long getWorkerPid() {
        return workerPid;
    }

    public void setWorkerPid(long workerPid) {
        this.workerPid = workerPid;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
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

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
