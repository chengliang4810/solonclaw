package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 承载审批请求事件的脱敏展示数据，避免审批服务主类继续膨胀。 */
class DangerousApprovalRequestEventBase {
    /** 记录审批请求事件中的会话标识。 */
    private final String sessionId;

    /** 记录审批请求事件中的待恢复审批。 */
    private final DangerousCommandApprovalService.PendingApproval pendingApproval;

    /** 记录审批请求事件中的redacted待恢复审批。 */
    private final DangerousCommandApprovalService.PendingApproval redactedPendingApproval;

    /**
     * 创建审批请求事件实例。
     *
     * @param sessionId 当前会话标识。
     * @param pendingApproval 待恢复审批参数。
     */
    DangerousApprovalRequestEventBase(
            String sessionId, DangerousCommandApprovalService.PendingApproval pendingApproval) {
        this.sessionId = SecretRedactor.redact(StrUtil.nullToEmpty(sessionId), 200);
        this.pendingApproval = pendingApproval;
        this.redactedPendingApproval = redactedPendingApproval(pendingApproval);
    }

    /** 读取会话标识。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 读取脱敏后的Pending审批。 */
    public DangerousCommandApprovalService.PendingApproval getPendingApproval() {
        return redactedPendingApproval;
    }

    /** 读取工具名称。 */
    public String getToolName() {
        return redactedPendingApproval == null
                ? ""
                : StrUtil.nullToEmpty(redactedPendingApproval.getToolName());
    }

    /** 读取命令。 */
    public String getCommand() {
        return redactedPendingApproval == null
                ? ""
                : StrUtil.nullToEmpty(redactedPendingApproval.getCommand());
    }

    /** 读取Description。 */
    public String getDescription() {
        return redactedPendingApproval == null
                ? ""
                : StrUtil.nullToEmpty(redactedPendingApproval.getDescription());
    }

    /** 读取Pattern Keys。 */
    public List<String> getPatternKeys() {
        return redactedPendingApproval == null
                ? Collections.<String>emptyList()
                : redactedPendingApproval.effectivePatternKeys();
    }

    /** 读取Primary Pattern键。 */
    public String getPrimaryPatternKey() {
        List<String> keys = getPatternKeys();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    /** 读取原始待审批对象，供子类扩展时保持上下文。 */
    DangerousCommandApprovalService.PendingApproval rawPendingApproval() {
        return pendingApproval;
    }

    /** 生成脱敏后的待审批对象副本。 */
    private static DangerousCommandApprovalService.PendingApproval redactedPendingApproval(
            DangerousCommandApprovalService.PendingApproval source) {
        if (source == null) {
            return null;
        }
        DangerousCommandApprovalService.PendingApproval copy =
                new DangerousCommandApprovalService.PendingApproval();
        copy.setApprovalId(SecretRedactor.redact(source.getApprovalId(), 200));
        copy.setToolName(SecretRedactor.redact(source.getToolName(), 200));
        copy.setPatternKey(SecretRedactor.redact(source.getPatternKey(), 400));
        copy.setPatternKeys(redactedTextList(source.getPatternKeys(), 400));
        copy.setDescription(SecretRedactor.redact(source.getDescription(), 1000));
        copy.setCommand(SecretRedactor.redact(source.getCommand(), 3000));
        copy.setCommandHash(SecretRedactor.redact(source.getCommandHash(), 200));
        copy.setApprovalKey(SecretRedactor.redact(source.getApprovalKey(), 1000));
        copy.setCreatedAt(source.getCreatedAt());
        copy.setExpiresAt(source.getExpiresAt());
        return copy;
    }

    /** 对文本列表逐项脱敏并去重。 */
    private static List<String> redactedTextList(List<String> source, int maxLength) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> values = new ArrayList<String>();
        for (String item : source) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            String redacted = SecretRedactor.redact(item, maxLength);
            if (StrUtil.isNotBlank(redacted) && !values.contains(redacted.trim())) {
                values.add(redacted.trim());
            }
        }
        return values;
    }
}
