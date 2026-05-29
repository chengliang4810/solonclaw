package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import org.noear.snack4.ONode;

/** 将危险命令审批 request/response 写入审计仓储。 */
public class ApprovalAuditObserver implements DangerousCommandApprovalService.ApprovalObserver {
    private final ApprovalAuditRepository repository;

    public ApprovalAuditObserver(ApprovalAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onApprovalRequest(DangerousCommandApprovalService.ApprovalRequestEvent event) {
        append(event, "request", "", "");
    }

    @Override
    public void onApprovalResponse(DangerousCommandApprovalService.ApprovalResponseEvent event) {
        append(event, "response", event.getChoice(), event.getApprover());
    }

    private void append(
            DangerousCommandApprovalService.ApprovalRequestEvent event,
            String eventType,
            String choice,
            String approver) {
        if (repository == null || event == null || event.getPendingApproval() == null) {
            return;
        }
        DangerousCommandApprovalService.PendingApproval pending = event.getPendingApproval();
        ApprovalAuditEvent audit = new ApprovalAuditEvent();
        audit.setEventId(IdSupport.newId());
        audit.setSessionId(event.getSessionId());
        audit.setEventType(eventType);
        audit.setChoice(choice);
        audit.setApprover(SecretRedactor.redact(approver, 200));
        audit.setToolName(event.getToolName());
        audit.setApprovalId(pending.getApprovalId());
        audit.setApprovalKey(pending.approvalKey());
        audit.setCommandHash(pending.getCommandHash());
        audit.setCommandPreview(SecretRedactor.redact(event.getCommand(), 800));
        audit.setDescription(SecretRedactor.redact(event.getDescription(), 1000));
        audit.setPatternKeysJson(ONode.serialize(event.getPatternKeys()));
        audit.setCreatedAt(System.currentTimeMillis());
        audit.setApprovalCreatedAt(pending.getCreatedAt());
        audit.setApprovalExpiresAt(pending.getExpiresAt());
        try {
            repository.append(audit);
        } catch (Exception ignored) {
            // Audit persistence must not affect safety-critical approval handling.
        }
    }
}
