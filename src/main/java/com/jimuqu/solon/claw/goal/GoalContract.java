// src/main/java/com/jimuqu/solon/claw/goal/GoalContract.java
package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;

/**
 * 目标完成契约：把"什么算完成"结构化为五个字段，供 judge 严格裁决、供续轮提示精确引导。
 * 对标外部对标仓库的 GoalContract，字段语义逐一对应。
 */
@Getter
@Setter
@NoArgsConstructor
public class GoalContract {
    /** 期望的最终产出，对应 Outcome。 */
    private String outcome = "";

    /** 完成的验证方式（命令输出、文件内容、测试结果等），对应 Verification。 */
    private String verification = "";

    /** 必须遵守的约束（不能破坏的东西），对应 Constraints。 */
    private String constraints = "";

    /** 作用范围边界（允许触碰的文件/模块等），对应 Boundaries。 */
    private String boundaries = "";

    /** 阻塞放弃条件，对应 Stop when blocked。 */
    private String stopWhen = "";

    /** renderBlock 显示用的字段标签，顺序固定。 */
    private static final String[] FIELD_KEYS = {"outcome", "verification", "constraints", "boundaries", "stopWhen"};

    /** renderBlock 显示用的字段中文/英文标签。 */
    private static final String[] FIELD_LABELS = {
        "Outcome", "Verification", "Constraints", "Boundaries", "Stop when blocked"
    };

    /**
     * 判断契约是否全空。
     *
     * @return 五字段全部为空时返回 true。
     */
    public boolean isEmpty() {
        return StrUtil.isBlank(outcome)
                && StrUtil.isBlank(verification)
                && StrUtil.isBlank(constraints)
                && StrUtil.isBlank(boundaries)
                && StrUtil.isBlank(stopWhen);
    }

    /**
     * 渲染非空字段为 "- Label: value" 的换行块，供续轮提示和 /goal show 使用。
     *
     * @return 渲染后的多行文本，全空时返回空串。
     */
    public String renderBlock() {
        String[] values = {outcome, verification, constraints, boundaries, stopWhen};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            String v = StrUtil.nullToEmpty(values[i]).trim();
            if (StrUtil.isBlank(v)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- ").append(FIELD_LABELS[i]).append(": ").append(v);
        }
        return sb.toString();
    }

    /**
     * 转为有序 Map，供 GoalState 序列化嵌套使用。
     *
     * @return 字段键值映射，键为 snake_case。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", StrUtil.nullToEmpty(outcome));
        m.put("verification", StrUtil.nullToEmpty(verification));
        m.put("constraints", StrUtil.nullToEmpty(constraints));
        m.put("boundaries", StrUtil.nullToEmpty(boundaries));
        m.put("stop_when", StrUtil.nullToEmpty(stopWhen));
        return m;
    }

    /**
     * 从 Map 还原契约，缺字段按空串处理。
     *
     * @param data 数据映射。
     * @return 还原后的契约；data 为 null 时返回空契约。
     */
    public static GoalContract fromMap(Map<String, Object> data) {
        GoalContract c = new GoalContract();
        if (data == null) {
            return c;
        }
        c.setOutcome(text(data, "outcome"));
        c.setVerification(text(data, "verification"));
        c.setConstraints(text(data, "constraints"));
        c.setBoundaries(text(data, "boundaries"));
        c.setStopWhen(text(data, "stop_when"));
        return c;
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @return JSON 文本。
     */
    public String toJson() {
        return ONode.serialize(toMap());
    }

    /**
     * 从 JSON 还原契约。
     *
     * @param json JSON 文本。
     * @return 契约；空白输入返回 null。
     */
    @SuppressWarnings("unchecked")
    public static GoalContract fromJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        Map<String, Object> data = ONode.deserialize(json, LinkedHashMap.class);
        return fromMap(data);
    }

    /**
     * 安全读取 Map 中的字符串字段。
     *
     * @param data 数据映射。
     * @param key 字段键。
     * @return 字符串值，空安全。
     */
    private static String text(Map<String, Object> data, String key) {
        Object v = data == null ? null : data.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
