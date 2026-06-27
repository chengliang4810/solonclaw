package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.context.SkillUsageTracker;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提供控制台洞察相关业务能力，封装调用方不需要感知的运行细节。 */
public class DashboardInsightsService {
    /** 注入应用配置，用于控制台洞察。 */
    private final AppConfig appConfig;

    /** 记录控制台洞察中的技能用量Tracker。 */
    private final SkillUsageTracker skillUsageTracker;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /**
     * 创建控制台洞察服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param skillUsageTracker 技能用量Tracker参数。
     * @param sessionRepository 会话仓储依赖。
     */
    public DashboardInsightsService(
            AppConfig appConfig,
            SkillUsageTracker skillUsageTracker,
            SessionRepository sessionRepository) {
        this.appConfig = appConfig;
        this.skillUsageTracker = skillUsageTracker;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 执行overview相关逻辑。
     *
     * @return 返回overview结果。
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessionStats());
        result.put("skills", skillStats());
        result.put("runtime", runtimeStats());
        return result;
    }

    /**
     * 执行技能用量相关逻辑。
     *
     * @return 返回技能用量结果。
     */
    public Map<String, Object> skillUsage() {
        if (skillUsageTracker == null) {
            return new LinkedHashMap<String, Object>();
        }
        return skillUsageTracker.getAllEntries();
    }

    /**
     * 执行会话Stats相关逻辑。
     *
     * @return 返回会话Stats结果。
     */
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

    /**
     * 执行技能Stats相关逻辑。
     *
     * @return 返回技能Stats结果。
     */
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

    /**
     * 执行运行时Stats相关逻辑。
     *
     * @return 返回运行时Stats结果。
     */
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
