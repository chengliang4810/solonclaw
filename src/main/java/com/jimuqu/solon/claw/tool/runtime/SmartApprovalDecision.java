package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;

/** Result returned by the smart approval auxiliary judge. */
public class SmartApprovalDecision {
    private final boolean approved;
    private final String reason;

    private SmartApprovalDecision(boolean approved, String reason) {
        this.approved = approved;
        this.reason = StrUtil.blankToDefault(reason, "").trim();
    }

    public static SmartApprovalDecision approve(String reason) {
        return new SmartApprovalDecision(true, reason);
    }

    public static SmartApprovalDecision escalate(String reason) {
        return new SmartApprovalDecision(false, reason);
    }

    public boolean isApproved() {
        return approved;
    }

    public String getReason() {
        return reason;
    }
}
