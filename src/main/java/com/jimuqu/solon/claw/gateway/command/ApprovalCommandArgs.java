package com.jimuqu.solon.claw.gateway.command;

import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;

/** 承载审批命令参数解析结果，避免默认命令服务继续堆积无状态值对象。 */
final class ApprovalCommandArgs {
    /** 记录审批命令参数中的审批项选择器。 */
    private String selector;

    /** 记录审批命令参数中声明的审批范围。 */
    private DangerousCommandApprovalService.ApprovalScope scope;

    /**
     * 读取审批项选择器。
     *
     * @return 返回审批项选择器。
     */
    String getSelector() {
        return selector;
    }

    /**
     * 写入审批项选择器。
     *
     * @param selector 审批项选择器。
     */
    void setSelector(String selector) {
        this.selector = selector;
    }

    /**
     * 读取审批范围。
     *
     * @return 返回审批范围。
     */
    DangerousCommandApprovalService.ApprovalScope getScope() {
        return scope;
    }

    /**
     * 写入审批范围。
     *
     * @param scope 审批范围。
     */
    void setScope(DangerousCommandApprovalService.ApprovalScope scope) {
        this.scope = scope;
    }
}
