package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.Locale;

/** 承载审批响应事件结果字段和脱敏审批人。 */
class DangerousApprovalResponseEventBase extends DangerousApprovalRequestEventBase {
    /** OUTCOMEAPPROVED的统一常量值。 */
    public static final String OUTCOME_APPROVED = "APPROVED";

    /** OUTCOME拒绝的统一常量值。 */
    public static final String OUTCOME_DENIED = "DENIED";

    /** OUTCOMETIMEDOUT的统一常量值。 */
    public static final String OUTCOME_TIMED_OUT = "TIMED_OUT";

    /** OUTCOMEREVOKED的统一常量值。 */
    public static final String OUTCOME_REVOKED = "REVOKED";

    /** 记录审批响应事件中的choice。 */
    private final String choice;

    /** 记录审批响应事件中的outcome。 */
    private final String outcome;

    /** 记录审批响应事件中的状态。 */
    private final String status;

    /** 是否启用approved。 */
    private final boolean approved;

    /** 记录审批响应事件中的approver。 */
    private final String approver;

    /**
     * 创建审批响应事件实例。
     *
     * @param sessionId 当前会话标识。
     * @param pendingApproval 待恢复审批参数。
     * @param choice choice 参数。
     * @param approver approver 参数。
     */
    DangerousApprovalResponseEventBase(
            String sessionId,
            DangerousCommandApprovalService.PendingApproval pendingApproval,
            String choice,
            String approver) {
        super(sessionId, pendingApproval);
        this.choice = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(choice)).trim();
        this.outcome = approvalOutcome(this.choice);
        this.status = approvalStatus(outcome);
        this.approved = OUTCOME_APPROVED.equals(outcome);
        this.approver = redactedApprover(approver);
    }

    /** 读取Choice。 */
    public String getChoice() {
        return choice;
    }

    /** 读取Outcome。 */
    public String getOutcome() {
        return outcome;
    }

    /** 读取状态。 */
    public String getStatus() {
        return status;
    }

    /** 判断是否Approved。 */
    public boolean isApproved() {
        return approved;
    }

    /** 读取Approver。 */
    public String getApprover() {
        return approver;
    }

    /** 标准化审批响应结果。 */
    private static String approvalOutcome(String choice) {
        String normalized = StrUtil.nullToEmpty(choice).trim().toLowerCase(Locale.ROOT);
        if ("deny".equals(normalized) || "denied".equals(normalized)) {
            return OUTCOME_DENIED;
        }
        if ("timeout".equals(normalized) || "timed_out".equals(normalized)) {
            return OUTCOME_TIMED_OUT;
        }
        if ("revoke".equals(normalized) || "revoked".equals(normalized)) {
            return OUTCOME_REVOKED;
        }
        return OUTCOME_APPROVED;
    }

    /** 把审批响应结果转换成审计状态。 */
    private static String approvalStatus(String outcome) {
        if (OUTCOME_DENIED.equals(outcome)) {
            return "denied";
        }
        if (OUTCOME_TIMED_OUT.equals(outcome)) {
            return "timed_out";
        }
        if (OUTCOME_REVOKED.equals(outcome)) {
            return "revoked";
        }
        return "approved";
    }

    /** 生成安全展示用的审批人。 */
    private static String redactedApprover(String approver) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(approver).trim(), 200);
    }
}
