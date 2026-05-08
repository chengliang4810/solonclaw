package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;

/** reference-style persistent standing goal state for one session. */
@Getter
@Setter
@NoArgsConstructor
public class GoalState {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CLEARED = "cleared";
    public static final int DEFAULT_MAX_TURNS = 20;

    private String goal;
    private String status = STATUS_ACTIVE;
    private int turnsUsed;
    private int maxTurns = DEFAULT_MAX_TURNS;
    private long createdAt;
    private long lastTurnAt;
    private String lastVerdict;
    private String lastReason;
    private String pausedReason;

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

    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? null : String.valueOf(value);
    }

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
