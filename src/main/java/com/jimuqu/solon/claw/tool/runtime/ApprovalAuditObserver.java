package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import com.jimuqu.solon.claw.core.repository.ApprovalAuditRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将危险命令审批 request/response 写入审计仓储。 */
public class ApprovalAuditObserver implements DangerousCommandApprovalService.ApprovalObserver {
    /** 记录审批审计写入失败的低敏诊断日志，不输出命令正文或描述内容。 */
    private static final Logger log = LoggerFactory.getLogger(ApprovalAuditObserver.class);

    /** 保存仓储依赖，用于访问持久化数据。 */
    private final ApprovalAuditRepository repository;

    /**
     * 创建审批审计Observer实例，并注入运行所需依赖。
     *
     * @param repository repository依赖组件。
     */
    public ApprovalAuditObserver(ApprovalAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * 响应审批请求事件。
     *
     * @param event 事件参数。
     */
    @Override
    public void onApprovalRequest(DangerousCommandApprovalService.ApprovalRequestEvent event) {
        append(event, "request", "", "", "", false, "");
    }

    /**
     * 响应审批响应事件。
     *
     * @param event 事件参数。
     */
    @Override
    public void onApprovalResponse(DangerousCommandApprovalService.ApprovalResponseEvent event) {
        append(
                event,
                "response",
                event.getChoice(),
                event.getOutcome(),
                event.getStatus(),
                event.isApproved(),
                event.getApprover());
    }

    /**
     * 执行append相关逻辑。
     *
     * @param event 事件参数。
     * @param eventType 事件类型参数。
     * @param choice choice 参数。
     * @param outcome outcome 参数。
     * @param status 状态参数。
     * @param approved approved 参数。
     * @param approver approver 参数。
     */
    private void append(
            DangerousCommandApprovalService.ApprovalRequestEvent event,
            String eventType,
            String choice,
            String outcome,
            String status,
            boolean approved,
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
        audit.setOutcome(outcome);
        audit.setStatus(status);
        audit.setApproved(approved);
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
        } catch (Exception e) {
            log.warn("审批审计写入失败，不影响审批主流程 eventType={} tool={} status={} error={}",
                    SecretRedactor.redact(eventType, 80),
                    SecretRedactor.redact(event.getToolName(), 120),
                    SecretRedactor.redact(status, 80),
                    e.getClass().getSimpleName());
        }
    }
}
