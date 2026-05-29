package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import java.util.List;

/** 危险命令审批审计仓储。 */
public interface ApprovalAuditRepository {
    void append(ApprovalAuditEvent event) throws Exception;

    List<ApprovalAuditEvent> listRecent(int limit) throws Exception;
}
