package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 承载技能用量Tracker相关状态和辅助逻辑。 */
public class SkillUsageTracker {
    /** 状态ACTIVE的统一常量值。 */
    public static final String STATE_ACTIVE = "active";

    /** 状态STALE的统一常量值。 */
    public static final String STATE_STALE = "stale";

    /** 状态ARCHIVED的统一常量值。 */
    public static final String STATE_ARCHIVED = "archived";

    /** 记录技能用量Tracker中的用量文件。 */
    private final File usageFile;

    /**
     * 创建技能用量Tracker实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SkillUsageTracker(AppConfig appConfig) {
        this.usageFile = new File(appConfig.getRuntime().getSkillsDir(), ".usage.json");
    }

    /**
     * 执行bump视图相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void bumpView(String skillName) {
        bump(skillName, "viewCount", "lastViewedAt");
    }

    /**
     * 执行bumpInvoke相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void bumpInvoke(String skillName) {
        bump(skillName, "invokeCount", "lastInvokedAt");
    }

    /**
     * 执行bumpManage相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param action 操作参数。
     */
    public synchronized void bumpManage(String skillName, String action) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        long now = System.currentTimeMillis();
        entry.put("lastManagedAt", Long.valueOf(now));
        entry.put("lastManageAction", StrUtil.blankToDefault(action, "unknown"));
        increment(entry, "manageCount");
        saveData(data);
    }

    /**
     * 标记状态。
     *
     * @param skillName 技能名称参数。
     * @param state 状态参数。
     */
    public synchronized void markState(String skillName, String state) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        entry.put("state", state);
        entry.put("stateChangedAt", Long.valueOf(System.currentTimeMillis()));
        saveData(data);
    }

    /**
     * 执行pin相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void pin(String skillName) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        entry.put("pinned", Boolean.TRUE);
        saveData(data);
    }

    /**
     * 执行unpin相关逻辑。
     *
     * @param skillName 技能名称参数。
     */
    public synchronized void unpin(String skillName) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        entry.remove("pinned");
        saveData(data);
    }

    /**
     * 读取Entry。
     *
     * @param skillName 技能名称参数。
     * @return 返回读取到的Entry。
     */
    public synchronized Map<String, Object> getEntry(String skillName) {
        Map<String, Object> data = loadData();
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) data.get(skillName);
        return entry == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(entry);
    }

    /**
     * 读取全部Entries。
     *
     * @return 返回读取到的全部Entries。
     */
    public synchronized Map<String, Object> getAllEntries() {
        return new LinkedHashMap<String, Object>(loadData());
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
        long viewed = asLong(entry.get("lastViewedAt"));
        long invoked = asLong(entry.get("lastInvokedAt"));
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
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        long now = System.currentTimeMillis();
        entry.put(timestampField, Long.valueOf(now));
        increment(entry, countField);
        if (!entry.containsKey("state")) {
            entry.put("state", STATE_ACTIVE);
        }
        saveData(data);
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
        entry.put("state", STATE_ACTIVE);
        entry.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        data.put(skillName, entry);
        return entry;
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

    /**
     * 加载Data。
     *
     * @return 返回Data结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadData() {
        try {
            if (usageFile.isFile()) {
                String json = FileUtil.readString(usageFile, StandardCharsets.UTF_8);
                if (StrUtil.isNotBlank(json)) {
                    Object parsed = ONode.deserialize(json, Object.class);
                    if (parsed instanceof Map) {
                        return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    /**
     * 保存Data。
     *
     * @param data 数据参数。
     */
    private void saveData(Map<String, Object> data) {
        try {
            FileUtil.mkParentDirs(usageFile);
            String json = ONode.serialize(data);
            FileUtil.writeString(json, usageFile, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
