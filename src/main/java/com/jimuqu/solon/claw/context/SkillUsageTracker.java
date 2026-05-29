package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/**
 * Skill usage telemetry tracker.
 * Tracks per-skill usage metadata in a sidecar JSON file (skills/.usage.json).
 * Counters are bumped by skill tools (skill_view, skill_manage);
 * the curator reads derived activity timestamps for lifecycle transitions.
 */
public class SkillUsageTracker {
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_STALE = "stale";
    public static final String STATE_ARCHIVED = "archived";

    private final File usageFile;

    public SkillUsageTracker(AppConfig appConfig) {
        this.usageFile = new File(appConfig.getRuntime().getSkillsDir(), ".usage.json");
    }

    public synchronized void bumpView(String skillName) {
        bump(skillName, "viewCount", "lastViewedAt");
    }

    public synchronized void bumpInvoke(String skillName) {
        bump(skillName, "invokeCount", "lastInvokedAt");
    }

    public synchronized void bumpManage(String skillName, String action) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        long now = System.currentTimeMillis();
        entry.put("lastManagedAt", Long.valueOf(now));
        entry.put("lastManageAction", StrUtil.blankToDefault(action, "unknown"));
        increment(entry, "manageCount");
        saveData(data);
    }

    public synchronized void markState(String skillName, String state) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        entry.put("state", state);
        entry.put("stateChangedAt", Long.valueOf(System.currentTimeMillis()));
        saveData(data);
    }

    public synchronized void pin(String skillName) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        entry.put("pinned", Boolean.TRUE);
        saveData(data);
    }

    public synchronized void unpin(String skillName) {
        Map<String, Object> data = loadData();
        Map<String, Object> entry = ensureEntry(data, skillName);
        entry.remove("pinned");
        saveData(data);
    }

    public synchronized Map<String, Object> getEntry(String skillName) {
        Map<String, Object> data = loadData();
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) data.get(skillName);
        return entry == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(entry);
    }

    public synchronized Map<String, Object> getAllEntries() {
        return new LinkedHashMap<String, Object>(loadData());
    }

    public synchronized boolean isPinned(String skillName) {
        Map<String, Object> entry = getEntry(skillName);
        return Boolean.TRUE.equals(entry.get("pinned"));
    }

    public synchronized String getState(String skillName) {
        Map<String, Object> entry = getEntry(skillName);
        String state = (String) entry.get("state");
        return StrUtil.blankToDefault(state, STATE_ACTIVE);
    }

    public synchronized long getLastActivityAt(String skillName) {
        Map<String, Object> entry = getEntry(skillName);
        long viewed = asLong(entry.get("lastViewedAt"));
        long invoked = asLong(entry.get("lastInvokedAt"));
        long managed = asLong(entry.get("lastManagedAt"));
        return Math.max(viewed, Math.max(invoked, managed));
    }

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

    private void increment(Map<String, Object> entry, String field) {
        long current = asLong(entry.get(field));
        entry.put(field, Long.valueOf(current + 1));
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

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

    private void saveData(Map<String, Object> data) {
        try {
            FileUtil.mkParentDirs(usageFile);
            String json = ONode.serialize(data);
            FileUtil.writeString(json, usageFile, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
