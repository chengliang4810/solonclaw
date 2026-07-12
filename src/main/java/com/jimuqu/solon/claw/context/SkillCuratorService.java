package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供技能技能维护相关业务能力，封装调用方不需要感知的运行细节。 */
@RequiredArgsConstructor
public class SkillCuratorService {
    /** 记录技能整理过程中的非关键读取失败，便于排查状态退化。 */
    private static final Logger log = LoggerFactory.getLogger(SkillCuratorService.class);

    /** 技能整理工作目录时间戳格式，保持原有本地时间命名。 */
    private static final DateTimeFormatter RUN_DIR_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** 注入应用配置，用于技能技能维护。 */
    private final AppConfig appConfig;

    /** 注入本地技能服务，用于调用对应业务能力。 */
    private final LocalSkillService localSkillService;

    /**
     * 运行Once。
     *
     * @param force force 参数。
     * @return 返回Once结果。
     */
    public synchronized Map<String, Object> runOnce(boolean force) throws Exception {
        return stateStore()
                .update(
                        state -> {
                            try {
                                return runOnceLocked(state, force);
                            } catch (Exception e) {
                                throw new IllegalStateException("Failed to run skill curator", e);
                            }
                        });
    }

    /** 在共享状态锁内执行整理，保证统计更新不会被本轮巡检覆盖。 */
    private Map<String, Object> runOnceLocked(Map<String, Object> state, boolean force)
            throws Exception {
        long now = System.currentTimeMillis();
        if (Boolean.TRUE.equals(state.get("paused")) && !force) {
            return report(state, now, "paused", new ArrayList<Map<String, Object>>());
        }
        if (!force && !appConfig.getCurator().isEnabled()) {
            return report(state, now, "disabled", new ArrayList<Map<String, Object>>());
        }
        long lastRunAt = asLong(state.get("lastRunAt"));
        long intervalMillis =
                Math.max(1, appConfig.getCurator().getIntervalHours()) * 60L * 60L * 1000L;
        if (!force && lastRunAt > 0 && now - lastRunAt < intervalMillis) {
            return report(state, now, "interval_wait", new ArrayList<Map<String, Object>>());
        }

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Map<String, Object> skillsState = ensureMap(state, "skills");
        for (SkillDescriptor descriptor : localSkillService.listSkills(null)) {
            if (!"agent-created".equals(descriptor.getTrustLevel())) {
                continue;
            }
            Map<String, Object> item = reviewSkill(descriptor, skillsState, now);
            items.add(item);
        }
        items.sort(
                new java.util.Comparator<Map<String, Object>>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(Map<String, Object> left, Map<String, Object> right) {
                        return Long.compare(
                                asLong(right.get("usageScore")), asLong(left.get("usageScore")));
                    }
                });

        state.put("lastRunAt", Long.valueOf(now));
        return report(state, now, "ok", items);
    }

    /** 执行pause相关逻辑。 */
    public synchronized void pause() {
        stateStore()
                .update(
                        state -> {
                            state.put("paused", Boolean.TRUE);
                            return null;
                        });
    }

    /** 执行resume相关逻辑。 */
    public synchronized void resume() {
        stateStore()
                .update(
                        state -> {
                            state.put("paused", Boolean.FALSE);
                            return null;
                        });
    }

    /**
     * 执行状态相关逻辑。
     *
     * @return 返回状态。
     */
    public synchronized Map<String, Object> status() {
        Map<String, Object> state = stateStore().read();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", Boolean.valueOf(appConfig.getCurator().isEnabled()));
        result.put(
                "paused",
                Boolean.valueOf(SkillFrontmatterSupport.parseBoolean(state.get("paused"))));
        result.put("lastRunAt", Long.valueOf(asLong(state.get("lastRunAt"))));
        result.put("intervalHours", Integer.valueOf(appConfig.getCurator().getIntervalHours()));
        result.put("minIdleHours", Double.valueOf(appConfig.getCurator().getMinIdleHours()));
        result.put("staleAfterDays", Integer.valueOf(appConfig.getCurator().getStaleAfterDays()));
        result.put(
                "archiveAfterDays", Integer.valueOf(appConfig.getCurator().getArchiveAfterDays()));
        Object skills = state.get("skills");
        result.put(
                "trackedSkills",
                Integer.valueOf(skills instanceof Map ? ((Map<?, ?>) skills).size() : 0));
        return result;
    }

    /**
     * 执行review技能相关逻辑。
     *
     * @param descriptor descriptor 参数。
     * @param skillsState 技能状态参数。
     * @param now 当前时间戳。
     * @return 返回review技能结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> reviewSkill(
            SkillDescriptor descriptor, Map<String, Object> skillsState, long now) {
        String name = descriptor.canonicalName();
        Map<String, Object> record =
                skillsState.get(name) instanceof Map
                        ? (Map<String, Object>) skillsState.get(name)
                        : new LinkedHashMap<String, Object>();
        long touchedAt = lastTouchedAt(FileUtil.file(descriptor.getSkillDir()));
        long lastActivityAt =
                Math.max(asLong(record.get("lastActivityAt")), asLong(record.get("lastManagedAt")));
        long relevantActivityAt = Math.max(touchedAt, lastActivityAt);
        long ageDays = Math.max(0L, (now - relevantActivityAt) / (24L * 60L * 60L * 1000L));
        boolean pinned = isPinned(descriptor);
        long loadCount = asLong(record.get("loadCount"));
        long callCount = asLong(record.get("callCount"));
        long usageScore = loadCount * 3L + callCount;
        String previousStatus =
                StrUtil.nullToDefault(String.valueOf(record.get("status")), "active");
        String status = previousStatus;
        String action = "unchanged";
        String archiveKind = "";
        List<String> suggestions = new ArrayList<String>();
        if (pinned) {
            status = "pinned";
            action = "skipped_pinned";
        } else if (ageDays >= appConfig.getCurator().getArchiveAfterDays()) {
            status = "archived";
            archiveKind = usageScore <= 0 ? "pruned" : "consolidated";
            action = "marked_" + archiveKind;
            suggestions.add(archiveKind + ": archive candidate");
        } else if (ageDays >= appConfig.getCurator().getStaleAfterDays()) {
            status = "stale";
            action = "marked_stale";
            suggestions.add("stale: refresh or verify against current project behavior");
        } else {
            status = "active";
        }
        List<String> contentFlags = inspectContentFlags(descriptor);
        suggestions.addAll(contentFlags);

        record.put("status", status);
        record.put("lastSeenAt", Long.valueOf(now));
        record.put("lastTouchedAt", Long.valueOf(touchedAt));
        record.put("lastRelevantActivityAt", Long.valueOf(relevantActivityAt));
        record.put("ageDays", Long.valueOf(ageDays));
        record.put("pinned", Boolean.valueOf(pinned));
        record.put("archiveKind", archiveKind);
        record.put("usageScore", Long.valueOf(usageScore));
        record.put("loadCount", Long.valueOf(loadCount));
        record.put("callCount", Long.valueOf(callCount));
        record.put("suggestions", suggestions);
        skillsState.put(name, record);

        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("status", status);
        item.put("previousStatus", previousStatus);
        item.put("action", action);
        item.put("ageDays", Long.valueOf(ageDays));
        item.put("pinned", Boolean.valueOf(pinned));
        item.put("archiveKind", archiveKind);
        item.put("usageScore", Long.valueOf(usageScore));
        item.put("loadCount", Long.valueOf(loadCount));
        item.put("callCount", Long.valueOf(callCount));
        item.put("suggestions", suggestions);
        item.put("path", skillReference(name));
        return item;
    }

    /**
     * 应用Suggestion。
     *
     * @param skillName 技能名称参数。
     * @param suggestion suggestion 参数。
     * @return 返回apply Suggestion结果。
     */
    public synchronized Map<String, Object> applySuggestion(String skillName, String suggestion) {
        return recordSuggestionState(skillName, suggestion, "applied");
    }

    /**
     * 执行忽略Suggestion相关逻辑。
     *
     * @param skillName 技能名称参数。
     * @param suggestion suggestion 参数。
     * @return 返回忽略Suggestion结果。
     */
    public synchronized Map<String, Object> ignoreSuggestion(String skillName, String suggestion) {
        return recordSuggestionState(skillName, suggestion, "ignored");
    }

    /**
     * 记录Suggestion状态。
     *
     * @param skillName 技能名称参数。
     * @param suggestion suggestion 参数。
     * @param status 状态参数。
     * @return 返回Suggestion状态。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> recordSuggestionState(
            String skillName, String suggestion, String status) {
        return stateStore()
                .update(
                        state -> {
                            Map<String, Object> audit = ensureMap(state, "suggestionAudit");
                            List<Map<String, Object>> rows =
                                    audit.get(skillName) instanceof List
                                            ? (List<Map<String, Object>>) audit.get(skillName)
                                            : new ArrayList<Map<String, Object>>();
                            Map<String, Object> row = new LinkedHashMap<String, Object>();
                            row.put("suggestion", suggestion);
                            row.put("status", status);
                            row.put("at", Long.valueOf(System.currentTimeMillis()));
                            rows.add(row);
                            audit.put(skillName, rows);
                            Map<String, Object> result = new LinkedHashMap<String, Object>();
                            result.put("skill", skillName);
                            result.put("suggestion", suggestion);
                            result.put("status", status);
                            return result;
                        });
    }

    /**
     * 检查ContentFlags。
     *
     * @param descriptor descriptor 参数。
     * @return 返回inspect Content Flags结果。
     */
    private List<String> inspectContentFlags(SkillDescriptor descriptor) {
        List<String> flags = new ArrayList<String>();
        try {
            String content =
                    FileUtil.readUtf8String(FileUtil.file(descriptor.getSkillDir(), "SKILL.md"));
            String normalized = StrUtil.nullToEmpty(content).toLowerCase();
            if (normalized.contains("todo") || normalized.contains("待补充")) {
                flags.add("hollow: contains TODO/待补充");
            }
            if (normalized.contains("deprecated") || normalized.contains("过期")) {
                flags.add("stale_content: marked deprecated/过期");
            }
            if (normalized.indexOf("冲突") >= 0 || normalized.contains("conflict")) {
                flags.add("conflict: conflict marker text present");
            }
            if (content.length() < 300) {
                flags.add("hollow: content is too short");
            }
        } catch (Exception e) {
            log.debug(
                    "读取技能内容失败，跳过内容质量标记: skillDir={}, error={}",
                    descriptor == null ? null : descriptor.getSkillDir(),
                    e.toString());
        }
        return flags;
    }

    /**
     * 执行report相关逻辑。
     *
     * @param state 状态参数。
     * @param now 当前时间戳。
     * @param status 状态参数。
     * @param items items 参数。
     * @return 返回report结果。
     */
    private Map<String, Object> report(
            Map<String, Object> state, long now, String status, List<Map<String, Object>> items) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("status", status);
        report.put("startedAt", Long.valueOf(now));
        report.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
        report.put("items", items);
        report.put("stateFile", "curator://state");
        writeReport(report, now);
        return report;
    }

    /**
     * 执行技能引用相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回技能Reference结果。
     */
    private String skillReference(String name) {
        return "skill://" + SecretRedactor.redact(StrUtil.blankToDefault(name, "unknown"), 400);
    }

    /**
     * 写入Report。
     *
     * @param report report 参数。
     * @param now 当前时间戳。
     */
    private void writeReport(Map<String, Object> report, long now) {
        String stamp = RUN_DIR_TIME_FORMATTER.format(Instant.ofEpochMilli(now));
        File runDir = FileUtil.file(appConfig.getRuntime().getLogsDir(), "curator", stamp);
        FileUtil.mkdir(runDir);
        FileUtil.writeUtf8String(ONode.serialize(report), FileUtil.file(runDir, "run.json"));

        StringBuilder markdown = new StringBuilder();
        markdown.append("# Curator Report\n\n");
        markdown.append("- status: ").append(report.get("status")).append('\n');
        markdown.append("- items: ").append(((List<?>) report.get("items")).size()).append("\n\n");
        for (Object itemObj : (List<?>) report.get("items")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) itemObj;
            markdown.append("- ")
                    .append(item.get("name"))
                    .append(" -> ")
                    .append(item.get("status"))
                    .append(" (")
                    .append(item.get("action"))
                    .append(")\n");
        }
        FileUtil.writeUtf8String(markdown.toString(), FileUtil.file(runDir, "REPORT.md"));
    }

    /**
     * 确保Map。
     *
     * @param state 状态参数。
     * @param key 配置键或映射键。
     * @return 返回Map结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureMap(Map<String, Object> state, String key) {
        Object current = state.get(key);
        if (current instanceof Map) {
            return (Map<String, Object>) current;
        }
        Map<String, Object> created = new LinkedHashMap<String, Object>();
        state.put(key, created);
        return created;
    }

    /**
     * 执行状态文件相关逻辑。
     *
     * @return 返回状态文件结果。
     */
    private CuratorStateStore stateStore() {
        return new CuratorStateStore(
                FileUtil.file(appConfig.getRuntime().getSkillsDir(), ".curator_state"));
    }

    /**
     * 执行lastTouched时间相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @return 返回last Touched时间结果。
     */
    private long lastTouchedAt(File dir) {
        long latest = dir == null ? 0L : dir.lastModified();
        if (dir != null && dir.exists()) {
            for (File file : FileUtil.loopFiles(dir)) {
                latest = Math.max(latest, file.lastModified());
            }
        }
        return latest <= 0 ? System.currentTimeMillis() : latest;
    }

    /**
     * 判断是否Pinned。
     *
     * @param descriptor descriptor 参数。
     * @return 如果Pinned满足条件则返回 true，否则返回 false。
     */
    @SuppressWarnings("unchecked")
    private boolean isPinned(SkillDescriptor descriptor) {
        Map<String, Object> metadata = descriptor.getMetadata();
        if (metadata == null) {
            return false;
        }
        if (SkillFrontmatterSupport.parseBoolean(metadata.get("pinned"))) {
            return true;
        }
        Object curator = metadata.get("curator");
        if (curator instanceof Map
                && SkillFrontmatterSupport.parseBoolean(
                        ((Map<String, Object>) curator).get("pinned"))) {
            return true;
        }
        return false;
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
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.debug("技能整理状态数值解析失败，使用0: value={}, error={}", value, e.toString());
            return 0L;
        }
    }
}
