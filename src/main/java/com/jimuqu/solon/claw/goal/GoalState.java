package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;

/** 表示目标数据，在服务、仓储和接口之间传递。 */
@Getter
@Setter
@NoArgsConstructor
public class GoalState {
    /** 状态ACTIVE的统一常量值。 */
    public static final String STATUS_ACTIVE = "active";

    /** 状态PAUSED的统一常量值。 */
    public static final String STATUS_PAUSED = "paused";

    /** 状态DONE的统一常量值。 */
    public static final String STATUS_DONE = "done";

    /** 状态CLEARED的统一常量值。 */
    public static final String STATUS_CLEARED = "cleared";

    /** 默认最大TURNS的统一常量值。 */
    public static final int DEFAULT_MAX_TURNS = 20;

    /** 记录目标中的目标。 */
    private String goal;

    /** 记录目标中的状态。 */
    private String status = STATUS_ACTIVE;

    /** 记录目标中的turns使用。 */
    private int turnsUsed;

    /** 记录目标中的maxTurns。 */
    private int maxTurns = DEFAULT_MAX_TURNS;

    /** 记录目标中的创建时间。 */
    private long createdAt;

    /** 记录目标中的最近一次Turn时间。 */
    private long lastTurnAt;

    /** 记录目标中的最近一次判定。 */
    private String lastVerdict;

    /** 记录目标中的最近一次原因。 */
    private String lastReason;

    /** 记录目标中的paused原因。 */
    private String pausedReason;

    /**
     * 转换为JSON。
     *
     * @return 返回转换后的JSON。
     */
    public String toJson() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("goal", goal);
        data.put("status", status);
        data.put("turns_used", Integer.valueOf(turnsUsed));
        data.put("max_turns", Integer.valueOf(maxTurns));
        data.put("created_at", Long.valueOf(createdAt));
        data.put("last_turn_at", Long.valueOf(lastTurnAt));
        data.put("last_verdict", lastVerdict);
        data.put("last_reason", lastReason);
        data.put("paused_reason", pausedReason);
        return ONode.serialize(data);
    }

    /**
     * 从输入转换JSON。
     *
     * @param json JSON参数。
     * @return 返回JSON结果。
     */
    @SuppressWarnings("unchecked")
    public static GoalState fromJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        Map<String, Object> data = ONode.deserialize(json, LinkedHashMap.class);
        GoalState state = new GoalState();
        state.setGoal(text(data, "goal"));
        state.setStatus(StrUtil.blankToDefault(text(data, "status"), STATUS_ACTIVE));
        state.setTurnsUsed(intValue(data, "turns_used", 0));
        state.setMaxTurns(intValue(data, "max_turns", DEFAULT_MAX_TURNS));
        state.setCreatedAt(longValue(data, "created_at", 0L));
        state.setLastTurnAt(longValue(data, "last_turn_at", 0L));
        state.setLastVerdict(text(data, "last_verdict"));
        state.setLastReason(text(data, "last_reason"));
        state.setPausedReason(text(data, "paused_reason"));
        return state;
    }

    /**
     * 执行文本相关逻辑。
     *
     * @param data 数据参数。
     * @param key 配置键或映射键。
     * @return 返回text结果。
     */
    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 执行int值相关逻辑。
     *
     * @param data 数据参数。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回int Value结果。
     */
    private static int intValue(Map<String, Object> data, String key, int defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 将输入对象转换为长整型数值。
     *
     * @param data 数据参数。
     * @param key 配置键或映射键。
     * @param defaultValue 默认值参数。
     * @return 返回long Value结果。
     */
    private static long longValue(Map<String, Object> data, String key, long defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
