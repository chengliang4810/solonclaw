package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;

/** Result returned by the smart approval auxiliary judge. */
public class SmartApprovalDecision {
    private final boolean approved;
    private final boolean denied;
    private final String reason;

    private SmartApprovalDecision(boolean approved, boolean denied, String reason) {
        this.approved = approved;
        this.denied = denied;
        this.reason = StrUtil.blankToDefault(reason, "").trim();
    }

    public static SmartApprovalDecision approve(String reason) {
        return new SmartApprovalDecision(true, false, reason);
    }

    public static SmartApprovalDecision deny(String reason) {
        return new SmartApprovalDecision(false, true, reason);
    }

    public static SmartApprovalDecision escalate(String reason) {
        return new SmartApprovalDecision(false, false, reason);
    }

    public boolean isApproved() {
        return approved;
    }

    public boolean isDenied() {
        return denied;
    }

    public String getReason() {
        return reason;
    }
}
