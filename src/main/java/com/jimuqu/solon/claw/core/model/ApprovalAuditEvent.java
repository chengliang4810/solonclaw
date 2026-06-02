package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 危险命令审批审计事件。 */
@Getter
@Setter
@NoArgsConstructor
public class ApprovalAuditEvent {
    private String eventId;
    private String sessionId;
    private String eventType;
    private String choice;
    private String outcome;
    private String status;
    private boolean approved;
    private String approver;
    private String toolName;
    private String approvalId;
    private String approvalKey;
    private String commandHash;
    private String commandPreview;
    private String description;
    private String patternKeysJson;
    private long createdAt;
    private long approvalCreatedAt;
    private long approvalExpiresAt;
}
