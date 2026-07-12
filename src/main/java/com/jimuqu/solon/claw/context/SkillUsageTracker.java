package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** 读取并维护技能整理器使用的统一用量状态。 */
public class SkillUsageTracker {
    /** 技能用量记录器的低敏日志记录器。 */
    private static final Logger LOG = Logger.getLogger(SkillUsageTracker.class.getName());

    /** 状态ACTIVE的统一常量值。 */
    public static final String STATE_ACTIVE = "active";

    /** 状态STALE的统一常量值。 */
    public static final String STATE_STALE = "stale";

    /** 状态ARCHIVED的统一常量值。 */
    public static final String STATE_ARCHIVED = "archived";

    /** 技能整理状态存储，负责跨统计与整理服务的共享锁和原子写入。 */
    private final CuratorStateStore stateStore;

    /**
     * 创建技能用量Tracker实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SkillUsageTracker(AppConfig appConfig) {
        this.stateStore =
                new CuratorStateStore(
                        new File(appConfig.getRuntime().getSkillsDir(), ".curator_state"));
    }

    /**
     * 执行bump视图相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void bumpView(String skillName) {
        bump(skillName, "loadCount", "lastActivityAt");
    }

    /**
     * 执行bumpInvoke相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void bumpInvoke(String skillName) {
        bump(skillName, "callCount", "lastActivityAt");
    }

    /**
     * 执行bumpManage相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param action 操作参数。
     */
    public synchronized void bumpManage(String skillName, String action) {
        updateSkills(
                data -> {
                    Map<String, Object> entry = ensureEntry(data, skillName);
                    long now = System.currentTimeMillis();
                    entry.put("lastManagedAt", Long.valueOf(now));
                    entry.put("lastManageAction", StrUtil.blankToDefault(action, "unknown"));
                    increment(entry, "manageCount");
                });
    }

    /**
     * 标记状态。
     *
     * @param skillName 技能名称参数。
     * @param state 状态参数。
     */
    public synchronized void markState(String skillName, String state) {
        updateSkills(
                data -> {
                    Map<String, Object> entry = ensureEntry(data, skillName);
                    entry.put("status", state);
                    entry.put("stateChangedAt", Long.valueOf(System.currentTimeMillis()));
                });
    }

    /**
     * 执行pin相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void pin(String skillName) {
        updateSkills(data -> ensureEntry(data, skillName).put("pinned", Boolean.TRUE));
    }

    /**
     * 执行unpin相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void unpin(String skillName) {
        updateSkills(data -> ensureEntry(data, skillName).remove("pinned"));
    }

    /**
     * 读取Entry。
     *
     * @param skillName 技能名称参数。
     * @return 返回读取到的Entry。
     */
    public synchronized Map<String, Object> getEntry(String skillName) {
        Map<String, Object> data = skillsFromState(stateStore.read());
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) data.get(skillName);
        return entry == null ? new LinkedHashMap<String, Object>() : normalizedEntry(entry);
    }

    /**
     * 读取全部Entries。
     *
     * @return 返回读取到的全部Entries。
     */
    public synchronized Map<String, Object> getAllEntries() {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : skillsFromState(stateStore.read()).entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                normalized.put(entry.getKey(), normalizedEntry(value));
            }
        }
        return normalized;
    }

    /**
     * 判断是否Pinned。
     *
     * @param skillName 技能名称参数。
     * @return 如果Pinned满足条件则返回 true，否则返回 false。
     */
    public synchronized boolean isPinned(String skillName) {
        Map<String, Object> entry = getEntry(skillName);
        return Boolean.TRUE.equals(entry.get("pinned"));
    }

    /**
     * 读取状态。
     *
     * @param skillName 技能名称参数。
     * @return 返回读取到的状态。
     */
    public synchronized String getState(String skillName) {
        Map<String, Object> entry = getEntry(skillName);
        String state = (String) entry.get("state");
        return StrUtil.blankToDefault(state, STATE_ACTIVE);
    }

    /**
     * 读取Last Activity时间。
     *
     * @param skillName 技能名称参数。
     * @return 返回读取到的Last Activity时间。
     */
    public synchronized long getLastActivityAt(String skillName) {
        Map<String, Object> entry = getEntry(skillName);
        long viewed = asLong(entry.get("lastActivityAt"));
        long invoked = asLong(entry.get("lastActivityAt"));
        long managed = asLong(entry.get("lastManagedAt"));
        return Math.max(viewed, Math.max(invoked, managed));
    }

    /**
     * 执行bump相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param countField countField 参数。
     * @param timestampField 时间戳Field参数。
     */
    private void bump(String skillName, String countField, String timestampField) {
        updateSkills(
                data -> {
                    Map<String, Object> entry = ensureEntry(data, skillName);
                    long now = System.currentTimeMillis();
                    entry.put(timestampField, Long.valueOf(now));
                    increment(entry, countField);
                    if (!entry.containsKey("status")) {
                        entry.put("status", STATE_ACTIVE);
                    }
                });
    }

    /**
     * 确保Entry。
     *
     * @param data 数据参数。
     * @param skillName 技能名称参数。
     * @return 返回Entry结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureEntry(Map<String, Object> data, String skillName) {
        Object existing = data.get(skillName);
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("status", STATE_ACTIVE);
        entry.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        data.put(skillName, entry);
        return entry;
    }

    /** 将整理器字段转换为控制台稳定展示字段。 */
    private Map<String, Object> normalizedEntry(Map<String, Object> entry) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>(entry);
        Object status = entry.get("status");
        normalized.put(
                "state",
                status == null
                        ? STATE_ACTIVE
                        : StrUtil.blankToDefault(String.valueOf(status), STATE_ACTIVE));
        normalized.put(
                "count",
                Long.valueOf(asLong(entry.get("loadCount")) + asLong(entry.get("callCount"))));
        return normalized;
    }

    /**
     * 执行increment相关逻辑。
     *
     * @param entry entry 参数。
     * @param field field 参数。
     */
    private void increment(Map<String, Object> entry, String field) {
        long current = asLong(entry.get(field));
        entry.put(field, Long.valueOf(current + 1));
    }

    /**
     * 执行as长整型相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Long结果。
     */
    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> skillsFromState(Map<String, Object> state) {
        Object skills = state == null ? null : state.get("skills");
        return skills instanceof Map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) skills)
                : new LinkedHashMap<String, Object>();
    }

    /** 在共享锁内修改技能统计，保留整理器管理的其他状态字段。 */
    private void updateSkills(SkillMutation mutation) {
        try {
            stateStore.update(
                    state -> {
                        Map<String, Object> skills = skillsFromState(state);
                        mutation.apply(skills);
                        state.put("skills", skills);
                        return null;
                    });
        } catch (RuntimeException e) {
            LOG.fine(
                    "技能用量状态写入失败，已跳过本次写入：file=.curator_state"
                            + ", errorType="
                            + e.getClass().getSimpleName());
        }
    }

    /** 在状态锁内修改单个技能统计字段的回调。 */
    private interface SkillMutation {
        /**
         * 修改 skills 节点。
         *
         * @param skills 技能统计映射。
         */
        void apply(Map<String, Object> skills);
    }
}
