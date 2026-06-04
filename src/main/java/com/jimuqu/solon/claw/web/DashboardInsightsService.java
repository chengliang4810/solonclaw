package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillUsageTracker;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dashboard insights service for usage analytics. */
public class DashboardInsightsService {
    private final AppConfig appConfig;
    private final SkillUsageTracker skillUsageTracker;
    private final SqliteSessionRepository sessionRepository;

    public DashboardInsightsService(
            AppConfig appConfig,
            SkillUsageTracker skillUsageTracker,
            SqliteSessionRepository sessionRepository) {
        this.appConfig = appConfig;
        this.skillUsageTracker = skillUsageTracker;
        this.sessionRepository = sessionRepository;
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessionStats());
        result.put("skills", skillStats());
        result.put("runtime", runtimeStats());
        return result;
    }

    public Map<String, Object> skillUsage() {
        if (skillUsageTracker == null) {
            return new LinkedHashMap<String, Object>();
        }
        return skillUsageTracker.getAllEntries();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionStats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        try {
            int total = sessionRepository == null ? 0 : sessionRepository.countAll();
            stats.put("total", Integer.valueOf(total));
        } catch (Exception e) {
            stats.put("total", Integer.valueOf(0));
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    private Map<String, Object> skillStats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        if (skillUsageTracker == null) {
            stats.put("tracked", Integer.valueOf(0));
            return stats;
        }
        Map<String, Object> entries = skillUsageTracker.getAllEntries();
        int active = 0;
        int stale = 0;
        int archived = 0;
        int pinned = 0;
        for (Object value : entries.values()) {
            if (!(value instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) value;
            String state = StrUtil.blankToDefault((String) entry.get("state"), "active");
            if ("active".equals(state)) {
                active++;
            } else if ("stale".equals(state)) {
                stale++;
            } else if ("archived".equals(state)) {
                archived++;
            }
            if (Boolean.TRUE.equals(entry.get("pinned"))) {
                pinned++;
            }
        }
        stats.put("tracked", Integer.valueOf(entries.size()));
        stats.put("active", Integer.valueOf(active));
        stats.put("stale", Integer.valueOf(stale));
        stats.put("archived", Integer.valueOf(archived));
        stats.put("pinned", Integer.valueOf(pinned));
        return stats;
    }

    private Map<String, Object> runtimeStats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        Runtime runtime = Runtime.getRuntime();
        stats.put("maxMemoryMb", Long.valueOf(runtime.maxMemory() / (1024 * 1024)));
        stats.put(
                "usedMemoryMb",
                Long.valueOf((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)));
        stats.put("availableProcessors", Integer.valueOf(runtime.availableProcessors()));
        stats.put(
                "uptimeMs",
                Long.valueOf(
                        java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()));
        return stats;
    }
}
