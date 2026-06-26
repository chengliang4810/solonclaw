package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.List;

/** 承载待恢复审批数据和自包含判断逻辑，减少审批服务主类体积。 */
class DangerousPendingApprovalBase {
    /** 记录待恢复审批中的审批标识。 */
    private String approvalId;

    /** 记录待恢复审批中的工具名称。 */
    private String toolName;

    /** 记录待恢复审批中的pattern键。 */
    private String patternKey;

    /** 保存patternKeys集合，维持调用顺序或去重语义。 */
    private List<String> patternKeys = new ArrayList<String>();

    /** 记录待恢复审批中的描述。 */
    private String description;

    /** 记录待恢复审批中的命令。 */
    private String command;

    /** 记录待恢复审批中的命令哈希。 */
    private String commandHash;

    /** 记录待恢复审批中的审批键。 */
    private String approvalKey;

    /** 是否只允许本次审批，禁止会话或永久复用。 */
    private boolean onceOnly;

    /** 记录待恢复审批中的创建时间。 */
    private long createdAt;

    /** 记录待恢复审批中的expires时间。 */
    private long expiresAt;

    /** 读取审批标识。 */
    public String getApprovalId() {
        return approvalId;
    }

    /** 写入审批标识。 */
    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    /** 读取工具名称。 */
    public String getToolName() {
        return toolName;
    }

    /** 写入工具名称。 */
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    /** 读取Pattern键。 */
    public String getPatternKey() {
        return patternKey;
    }

    /** 写入Pattern键。 */
    public void setPatternKey(String patternKey) {
        this.patternKey = patternKey;
    }

    /** 读取Pattern Keys。 */
    public List<String> getPatternKeys() {
        return patternKeys;
    }

    /** 写入Pattern Keys。 */
    public void setPatternKeys(List<String> patternKeys) {
        this.patternKeys =
                patternKeys == null ? new ArrayList<String>() : new ArrayList<String>(patternKeys);
    }

    /** 读取Description。 */
    public String getDescription() {
        return description;
    }

    /** 写入Description。 */
    public void setDescription(String description) {
        this.description = description;
    }

    /** 读取命令。 */
    public String getCommand() {
        return command;
    }

    /** 写入命令。 */
    public void setCommand(String command) {
        this.command = command;
    }

    /** 读取命令Hash。 */
    public String getCommandHash() {
        return commandHash;
    }

    /** 写入命令Hash。 */
    public void setCommandHash(String commandHash) {
        this.commandHash = commandHash;
    }

    /** 读取审批键。 */
    public String getApprovalKey() {
        return approvalKey;
    }

    /** 写入审批键。 */
    public void setApprovalKey(String approvalKey) {
        this.approvalKey = approvalKey;
    }

    /** 判断是否只允许本次审批。 */
    public boolean isOnceOnlyApproval() {
        return onceOnly;
    }

    /** 写入只允许本次审批标记。 */
    public void setOnceOnly(boolean onceOnly) {
        this.onceOnly = onceOnly;
    }

    /** 读取创建时间。 */
    public long getCreatedAt() {
        return createdAt;
    }

    /** 写入创建时间。 */
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /** 读取Expires时间。 */
    public long getExpiresAt() {
        return expiresAt;
    }

    /** 写入Expires时间。 */
    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** 生成稳定审批键。 */
    public String approvalKey() {
        return StrUtil.blankToDefault(
                cleanApprovalText(approvalKey),
                cleanApprovalText(toolName)
                        + ":"
                        + cleanApprovalText(patternKey)
                        + ":"
                        + cleanApprovalText(commandHash));
    }

    /** 返回去重后的有效 patternKeys。 */
    public List<String> effectivePatternKeys() {
        List<String> values = new ArrayList<String>();
        if (patternKeys != null) {
            for (String key : patternKeys) {
                String value = cleanApprovalText(key);
                if (StrUtil.isNotBlank(value) && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty() && StrUtil.isNotBlank(patternKey)) {
            values.add(cleanApprovalText(patternKey));
        }
        return values;
    }

    /** 判断该审批是否允许永久复用。 */
    public boolean isPermanentApprovalAllowed() {
        if (onceOnly) {
            return false;
        }
        for (String patternKey : effectivePatternKeys()) {
            if (StrUtil.nullToEmpty(patternKey).startsWith("tirith:")) {
                return false;
            }
        }
        return true;
    }

    /** 清理审批文本中的展示控制字符。 */
    private static String cleanApprovalText(Object value) {
        if (value == null) {
            return "";
        }
        return SecretRedactor.stripDisplayControls(String.valueOf(value)).trim();
    }
}
