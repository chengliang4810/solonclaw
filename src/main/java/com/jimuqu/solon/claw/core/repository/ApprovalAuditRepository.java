package com.jimuqu.solon.claw.core.repository;

import com.jimuqu.solon.claw.core.model.ApprovalAuditEvent;
import java.util.List;

/** 危险命令审批审计仓储。 */
public interface ApprovalAuditRepository {
    /**
     * 执行append相关逻辑。
     *
     * @param event 事件参数。
     */
    void append(ApprovalAuditEvent event) throws Exception;

    /**
     * 列出Recent。
     *
     * @param limit 最大返回数量。
     * @return 返回Recent列表。
     */
    List<ApprovalAuditEvent> listRecent(int limit) throws Exception;
}
