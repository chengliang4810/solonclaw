package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;

/** 表示Smart审批结果，携带调用方后续判断所需信息。 */
public class SmartApprovalDecision {
    /** 是否启用approved。 */
    private final boolean approved;

    /** 是否启用denied。 */
    private final boolean denied;

    /** 记录Smart审批中的原因。 */
    private final String reason;

    /**
     * 创建Smart审批Decision实例，并注入运行所需依赖。
     *
     * @param approved approved 参数。
     * @param denied denied 参数。
     * @param reason 原因参数。
     */
    private SmartApprovalDecision(boolean approved, boolean denied, String reason) {
        this.approved = approved;
        this.denied = denied;
        this.reason = StrUtil.blankToDefault(reason, "").trim();
    }

    /**
     * 创建审批通过决策。
     *
     * @param reason 原因参数。
     * @return 返回approve结果。
     */
    public static SmartApprovalDecision approve(String reason) {
        return new SmartApprovalDecision(true, false, reason);
    }

    /**
     * 创建审批拒绝决策。
     *
     * @param reason 原因参数。
     * @return 返回deny结果。
     */
    public static SmartApprovalDecision deny(String reason) {
        return new SmartApprovalDecision(false, true, reason);
    }

    /**
     * 创建需要人工升级处理的审批决策。
     *
     * @param reason 原因参数。
     * @return 返回escalate结果。
     */
    public static SmartApprovalDecision escalate(String reason) {
        return new SmartApprovalDecision(false, false, reason);
    }

    /**
     * 判断是否Approved。
     *
     * @return 如果Approved满足条件则返回 true，否则返回 false。
     */
    public boolean isApproved() {
        return approved;
    }

    /**
     * 判断是否Denied。
     *
     * @return 如果Denied满足条件则返回 true，否则返回 false。
     */
    public boolean isDenied() {
        return denied;
    }

    /**
     * 读取Reason。
     *
     * @return 返回读取到的Reason。
     */
    public String getReason() {
        return reason;
    }
}
