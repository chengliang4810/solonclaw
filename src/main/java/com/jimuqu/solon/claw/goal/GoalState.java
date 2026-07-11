package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 连续 JSON 解析失败次数，达到上限自动暂停（对标 consecutive_parse_failures）。 */
    private int consecutiveParseFailures;

    /** 用户补充的子目标准则列表（对标 subgoals）。 */
    private List<String> subgoals = new ArrayList<>();

    /** 等待的进程 pid，非空表示 pid 屏障（对标 waiting_on_pid）。 */
    private Integer waitingOnPid;

    /** 等待截止时间戳，>0 表示时间屏障（对标 waiting_until）。 */
    private long waitingUntil;

    /** 等待原因（对标 waiting_reason）。 */
    private String waitingReason;

    /** 等待开始时间戳（对标 waiting_since）。 */
    private long waitingSince;

    /** 完成契约（对标 contract）。 */
    private GoalContract contract = new GoalContract();

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
        data.put("consecutive_parse_failures", Integer.valueOf(consecutiveParseFailures));
        data.put("subgoals", subgoals == null ? new ArrayList<String>() : subgoals);
        data.put("waiting_on_pid", waitingOnPid);
        data.put("waiting_until", Long.valueOf(waitingUntil));
        data.put("waiting_reason", waitingReason);
        data.put("waiting_since", Long.valueOf(waitingSince));
        data.put("contract", contract == null ? new GoalContract().toMap() : contract.toMap());
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
        state.setConsecutiveParseFailures(intValue(data, "consecutive_parse_failures", 0));
        // subgoals 向前兼容：旧 JSON 没有该键时取空列表
        List<String> sg = new ArrayList<>();
        Object raw = data.get("subgoals");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                String s = o == null ? "" : String.valueOf(o).trim();
                if (StrUtil.isNotBlank(s)) {
                    sg.add(s);
                }
            }
        }
        state.setSubgoals(sg);
        // waiting_on_pid 仅在非空且非 0 时设置
        Object pidRaw = data.get("waiting_on_pid");
        if (pidRaw != null && !"0".equals(String.valueOf(pidRaw))) {
            try {
                state.setWaitingOnPid(Integer.parseInt(String.valueOf(pidRaw)));
            } catch (Exception ignored) {
            }
        }
        state.setWaitingUntil(longValue(data, "waiting_until", 0L));
        state.setWaitingReason(text(data, "waiting_reason"));
        state.setWaitingSince(longValue(data, "waiting_since", 0L));
        // contract 向前兼容：旧 JSON 没有该键时为空契约
        Object contractRaw = data.get("contract");
        if (contractRaw instanceof Map) {
            state.setContract(GoalContract.fromMap((Map<String, Object>) contractRaw));
        }
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

    /**
     * 判断目标是否处于 active 状态。
     *
     * @return status 为 active 时返回 true。
     */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /**
     * 判断是否设置了完成契约。
     *
     * @return 契约非空时返回 true。
     */
    public boolean hasContract() {
        return contract != null && !contract.isEmpty();
    }

    /**
     * 判断是否仍处于等待屏障中（pid 仍存活或未到截止时间）。 pid 屏障惰性自清：pid 不存活时返回 false（视为屏障已解除）。
     *
     * @return 仍在等待时返回 true。
     */
    public boolean isWaiting() {
        if (waitingOnPid != null) {
            try {
                if (ProcessHandle.of(waitingOnPid).isPresent()) {
                    return true;
                }
            } catch (Exception ignored) {
                // 无法判定 pid 存活时保守视为仍在等待
                return true;
            }
        }
        if (waitingUntil > 0) {
            return System.currentTimeMillis() < waitingUntil;
        }
        return false;
    }

    /** 清除所有等待屏障字段。 */
    public void clearWaitBarrier() {
        this.waitingOnPid = null;
        this.waitingUntil = 0L;
        this.waitingReason = null;
        this.waitingSince = 0L;
    }

    /**
     * 追加一条子目标准则。
     *
     * @param subgoal 子目标文本。
     */
    public void addSubgoal(String subgoal) {
        if (subgoals == null) {
            subgoals = new ArrayList<>();
        }
        String t = StrUtil.nullToEmpty(subgoal).trim();
        if (StrUtil.isNotBlank(t)) {
            subgoals.add(t);
        }
    }

    /**
     * 删除第 n 条子目标（1-based）。
     *
     * @param oneBasedIndex 1 起的序号。
     * @return 删除成功返回 true。
     */
    public boolean removeSubgoal(int oneBasedIndex) {
        if (subgoals == null || oneBasedIndex < 1 || oneBasedIndex > subgoals.size()) {
            return false;
        }
        subgoals.remove(oneBasedIndex - 1);
        return true;
    }

    /** 清空所有子目标。 */
    public void clearSubgoals() {
        if (subgoals != null) {
            subgoals.clear();
        }
    }
}
