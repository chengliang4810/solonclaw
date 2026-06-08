package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 危险命令审批审计事件。 */
@Getter
@Setter
@NoArgsConstructor
public class ApprovalAuditEvent {
    /** 记录审批审计事件中的事件标识。 */
    private String eventId;

    /** 记录审批审计事件中的会话标识。 */
    private String sessionId;

    /** 记录审批审计事件中的事件类型。 */
    private String eventType;

    /** 记录审批审计事件中的choice。 */
    private String choice;

    /** 记录审批审计事件中的outcome。 */
    private String outcome;

    /** 记录审批审计事件中的状态。 */
    private String status;

    /** 是否启用approved。 */
    private boolean approved;

    /** 记录审批审计事件中的approver。 */
    private String approver;

    /** 记录审批审计事件中的工具名称。 */
    private String toolName;

    /** 记录审批审计事件中的审批标识。 */
    private String approvalId;

    /** 记录审批审计事件中的审批键。 */
    private String approvalKey;

    /** 记录审批审计事件中的命令哈希。 */
    private String commandHash;

    /** 记录审批审计事件中的命令预览。 */
    private String commandPreview;

    /** 记录审批审计事件中的描述。 */
    private String description;

    /** 记录审批审计事件中的patternKeysJSON。 */
    private String patternKeysJson;

    /** 记录审批审计事件中的创建时间。 */
    private long createdAt;

    /** 记录审批审计事件中的审批创建时间。 */
    private long approvalCreatedAt;

    /** 记录审批审计事件中的审批Expires时间。 */
    private long approvalExpiresAt;
}
