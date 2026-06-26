package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.List;

/** 承载危险命令检测结果数据，保留去重后的 pattern key 语义。 */
class DangerousDetectionResultBase {
    /** 记录Detection中的pattern键。 */
    private String patternKey;

    /** 保存patternKeys集合，维持调用顺序或去重语义。 */
    private List<String> patternKeys = new ArrayList<String>();

    /** 记录Detection中的描述。 */
    private String description;

    /** 记录Detection中的normalizedCode。 */
    private String normalizedCode;

    /** 是否启用hardline。 */
    private boolean hardline;

    /** 是否只允许本次审批。 */
    private boolean onceOnly;

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

    /** 读取Normalized Code。 */
    public String getNormalizedCode() {
        return normalizedCode;
    }

    /** 写入Normalized Code。 */
    public void setNormalizedCode(String normalizedCode) {
        this.normalizedCode = normalizedCode;
    }

    /** 判断是否Hardline。 */
    public boolean isHardline() {
        return hardline;
    }

    /** 写入Hardline。 */
    public void setHardline(boolean hardline) {
        this.hardline = hardline;
    }

    /** 判断是否只允许本次审批。 */
    public boolean isOnceOnly() {
        return onceOnly;
    }

    /** 写入只允许本次审批标记。 */
    public void setOnceOnly(boolean onceOnly) {
        this.onceOnly = onceOnly;
    }

    /** 返回去重后的有效 patternKeys。 */
    public List<String> effectivePatternKeys() {
        List<String> values = new ArrayList<String>();
        if (patternKeys != null) {
            for (String key : patternKeys) {
                if (StrUtil.isNotBlank(key) && !values.contains(key.trim())) {
                    values.add(key.trim());
                }
            }
        }
        if (values.isEmpty() && StrUtil.isNotBlank(patternKey)) {
            values.add(patternKey.trim());
        }
        return values;
    }
}
