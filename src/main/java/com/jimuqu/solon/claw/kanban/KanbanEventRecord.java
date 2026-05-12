package com.jimuqu.solon.claw.kanban;

/** Durable Kanban task event for audit, warning, and recovery UX. */
public class KanbanEventRecord {
    private String eventId;
    private String taskId;
    private String kind;
    private String payloadJson;
    private String notifyCursor;
    private long createdAt;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getNotifyCursor() {
        return notifyCursor;
    }

    public void setNotifyCursor(String notifyCursor) {
        this.notifyCursor = notifyCursor;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
