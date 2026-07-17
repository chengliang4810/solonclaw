package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.LlmGateway;
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
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供技能技能维护相关业务能力，封装调用方不需要感知的运行细节。 */
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

    /** 真实会话证据采集器；无会话仓储时返回空证据窗口。 */
    private final SkillCuratorEvidenceCollector evidenceCollector;

    /** 无工具 AI 评估器；模型网关不可用时为空。 */
    private final SkillCuratorAiEvaluator aiEvaluator;

    /** 可选报告持久化出口，由 Dashboard 服务注册，后台和手动运行共用。 */
    private volatile ReportSink reportSink;

    /**
     * 创建仅使用确定性规则的兼容实例。
     *
     * @param appConfig 应用配置。
     * @param localSkillService 本地技能服务。
     */
    public SkillCuratorService(AppConfig appConfig, LocalSkillService localSkillService) {
        this(appConfig, localSkillService, null, null);
    }

    /**
     * 创建具备真实会话证据与 AI 评估能力的技能整理服务。
     *
     * @param appConfig 应用配置。
     * @param localSkillService 本地技能服务。
     * @param sessionRepository 会话仓储。
     * @param llmGateway 无工具模型网关。
     */
    public SkillCuratorService(
            AppConfig appConfig,
            LocalSkillService localSkillService,
            SessionRepository sessionRepository,
            LlmGateway llmGateway) {
        this.appConfig = appConfig;
        this.localSkillService = localSkillService;
        this.evidenceCollector = new SkillCuratorEvidenceCollector(sessionRepository);
        this.aiEvaluator =
                llmGateway == null ? null : new SkillCuratorAiEvaluator(appConfig, llmGateway);
    }

    /**
     * 运行Once。
     *
     * @param force force 参数。
     * @return 返回Once结果。
     */
    public synchronized Map<String, Object> runOnce(boolean force) throws Exception {
        Map<String, Object> evaluatedState = stateStore().read();
        Map<String, Object> report = runOnceFromSnapshot(evaluatedState, force);
        if ("ok".equals(report.get("status"))) {
            stateStore()
                    .update(
                            current -> {
                                mergeEvaluatedState(current, evaluatedState);
                                return null;
                            });
        }
        ReportSink sink = reportSink;
        if (sink != null) {
            sink.save(report);
        }
        return report;
    }

    /** 基于短暂读取的状态快照执行整理，模型调用期间不阻塞技能用量写入。 */
    private Map<String, Object> runOnceFromSnapshot(Map<String, Object> state, boolean force)
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
        int aiCandidates = 0;
        for (SkillDescriptor descriptor : localSkillService.listSkills(null)) {
            if (!"agent-created".equals(descriptor.getTrustLevel())) {
                continue;
            }
            boolean allowAi =
                    appConfig.getCurator().isAiEnabled()
                            && aiCandidates
                                    < Math.max(
                                            1, appConfig.getCurator().getAiMaxCandidatesPerRun());
            Map<String, Object> item = reviewSkill(descriptor, skillsState, now, allowAi);
            if (allowAi && asLong(item.get("evidenceSessionCount")) > 0L) {
                aiCandidates++;
            }
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

    /** 把评估字段合并回最新状态，同时保留评估期间新增的次数、会话引用和暂停状态。 */
    @SuppressWarnings("unchecked")
    private void mergeEvaluatedState(
            Map<String, Object> current, Map<String, Object> evaluatedState) {
        current.put("lastRunAt", evaluatedState.get("lastRunAt"));
        Map<String, Object> currentSkills = ensureMap(current, "skills");
        Object rawEvaluatedSkills = evaluatedState.get("skills");
        if (!(rawEvaluatedSkills instanceof Map)) {
            return;
        }
        for (Map.Entry<String, Object> entry :
                ((Map<String, Object>) rawEvaluatedSkills).entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> evaluated = (Map<String, Object>) entry.getValue();
            Map<String, Object> latest =
                    currentSkills.get(entry.getKey()) instanceof Map
                            ? (Map<String, Object>) currentSkills.get(entry.getKey())
                            : new LinkedHashMap<String, Object>();
            Map<String, Object> merged = new LinkedHashMap<String, Object>(evaluated);
            preserveLatestActivity(latest, merged);
            currentSkills.put(entry.getKey(), merged);
        }
    }

    /** 保存快照评估期间可能由工具调用更新的活动字段。 */
    private void preserveLatestActivity(Map<String, Object> latest, Map<String, Object> merged) {
        long evaluatedActivityAt = asLong(merged.get("lastActivityAt"));
        for (String counter : java.util.Arrays.asList("loadCount", "callCount", "manageCount")) {
            merged.put(
                    counter,
                    Long.valueOf(
                            Math.max(asLong(latest.get(counter)), asLong(merged.get(counter)))));
        }
        for (String timestamp : java.util.Arrays.asList("lastActivityAt", "lastManagedAt")) {
            long latestValue = asLong(latest.get(timestamp));
            long mergedValue = asLong(merged.get(timestamp));
            merged.put(timestamp, Long.valueOf(Math.max(latestValue, mergedValue)));
        }
        if (asLong(latest.get("lastManagedAt")) >= asLong(merged.get("lastManagedAt"))
                && latest.containsKey("lastManageAction")) {
            merged.put("lastManageAction", latest.get("lastManageAction"));
        }
        if (latest.containsKey("recentSessionEvidence")) {
            merged.put("recentSessionEvidence", latest.get("recentSessionEvidence"));
        }
        long loadCount = asLong(merged.get("loadCount"));
        long callCount = asLong(merged.get("callCount"));
        long manageCount = asLong(merged.get("manageCount"));
        merged.put("activityCount", Long.valueOf(loadCount + callCount + manageCount));
        merged.put("usageScore", Long.valueOf(loadCount * 3L + callCount + manageCount));
        long latestActivityAt = asLong(merged.get("lastActivityAt"));
        long latestManagedAt = asLong(merged.get("lastManagedAt"));
        merged.put(
                "lastRelevantActivityAt",
                Long.valueOf(
                        Math.max(
                                asLong(merged.get("lastTouchedAt")),
                                Math.max(latestActivityAt, latestManagedAt))));
        if (latestActivityAt > evaluatedActivityAt) {
            merged.put("status", "active");
            merged.put("archiveKind", "");
            merged.put("ageDays", Long.valueOf(0L));
            Object rawEvaluation = merged.get("evaluation");
            if (rawEvaluation instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> evaluation = (Map<String, Object>) rawEvaluation;
                String verdict = String.valueOf(evaluation.get("verdict"));
                if ("archive_candidate".equals(verdict) || "merge_candidate".equals(verdict)) {
                    evaluation.put("verdict", "keep");
                    evaluation.put("ruleOverride", "concurrent_real_usage");
                }
            }
        }
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
        result.put("aiEnabled", Boolean.valueOf(appConfig.getCurator().isAiEnabled()));
        result.put(
                "aiProvider", SecretRedactor.redact(appConfig.getCurator().getAiProvider(), 120));
        result.put("aiModel", SecretRedactor.redact(appConfig.getCurator().getAiModel(), 160));
        result.put(
                "aiTimeoutSeconds", Integer.valueOf(appConfig.getCurator().getAiTimeoutSeconds()));
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
            SkillDescriptor descriptor,
            Map<String, Object> skillsState,
            long now,
            boolean allowAi) {
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
        long manageCount = asLong(record.get("manageCount"));
        long activityCount = loadCount + callCount + manageCount;
        long usageScore = loadCount * 3L + callCount + manageCount;
        Object storedStatus = record.get("status");
        String previousStatus =
                storedStatus == null
                        ? "active"
                        : StrUtil.blankToDefault(String.valueOf(storedStatus), "active");
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
        SkillCuratorEvidenceCollector.EvidenceWindow evidence =
                evidenceCollector.collect(
                        name, record, Math.max(1, appConfig.getCurator().getRecentEvidenceLimit()));
        Map<String, Object> evaluation =
                evaluateSkill(
                        descriptor,
                        record,
                        status,
                        pinned,
                        contentFlags,
                        evidence,
                        allowAi,
                        ageDays,
                        now);
        @SuppressWarnings("unchecked")
        List<String> recommendations =
                evaluation.get("recommendations") instanceof List
                        ? (List<String>) evaluation.get("recommendations")
                        : new ArrayList<String>();
        suggestions.addAll(recommendations);

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
        record.put("manageCount", Long.valueOf(manageCount));
        record.put("activityCount", Long.valueOf(activityCount));
        record.put("suggestions", suggestions);
        record.put("evaluation", evaluation);
        record.put("lastEvaluatedAt", Long.valueOf(now));
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
        item.put("manageCount", Long.valueOf(manageCount));
        item.put("activityCount", Long.valueOf(activityCount));
        item.put("suggestions", suggestions);
        item.put("evaluation", evaluation);
        item.put("evidenceSessionCount", Integer.valueOf(evidence.getSessionCount()));
        item.put("evidenceSessions", evidence.getSessionIds());
        item.put("failedEvidenceSessionCount", Integer.valueOf(evidence.getFailedSessionCount()));
        item.put("path", skillReference(name));
        return item;
    }

    /** 使用真实正文、用量和会话证据执行 AI 评估，并保留确定性安全覆盖。 */
    private Map<String, Object> evaluateSkill(
            SkillDescriptor descriptor,
            Map<String, Object> record,
            String deterministicStatus,
            boolean pinned,
            List<String> contentFlags,
            SkillCuratorEvidenceCollector.EvidenceWindow evidence,
            boolean allowAi,
            long ageDays,
            long now) {
        String fallbackReason = "";
        Map<String, Object> evaluation = null;
        if (!allowAi) {
            fallbackReason =
                    appConfig.getCurator().isAiEnabled() ? "candidate_limit" : "ai_disabled";
        } else if (aiEvaluator == null) {
            fallbackReason = "model_gateway_unavailable";
        } else if (evidence.getSessionCount() <= 0) {
            fallbackReason = "conversation_evidence_unavailable";
        } else {
            try {
                Map<String, Object> usage = new LinkedHashMap<String, Object>();
                usage.put("loadCount", Long.valueOf(asLong(record.get("loadCount"))));
                usage.put("callCount", Long.valueOf(asLong(record.get("callCount"))));
                usage.put("manageCount", Long.valueOf(asLong(record.get("manageCount"))));
                usage.put("ageDays", Long.valueOf(ageDays));
                usage.put("lastActivityAt", Long.valueOf(asLong(record.get("lastActivityAt"))));
                evaluation =
                        aiEvaluator.evaluate(
                                descriptor.canonicalName(),
                                readSkillContent(descriptor),
                                usage,
                                evidence);
                evaluation.put("mode", "ai");
                evaluation.put("evaluatedAt", Long.valueOf(now));
            } catch (Exception e) {
                fallbackReason = safeError(e);
                log.warn(
                        "技能 AI 整理评估失败，使用确定性回退: skill={}, error={}",
                        skillReference(descriptor.canonicalName()),
                        fallbackReason);
            }
        }
        if (evaluation == null) {
            evaluation =
                    deterministicEvaluation(
                            descriptor.canonicalName(), deterministicStatus, contentFlags);
            evaluation.put("mode", "deterministic_fallback");
            evaluation.put("fallbackReason", SecretRedactor.redact(fallbackReason, 300));
            evaluation.put("evaluatedAt", Long.valueOf(now));
        }
        String verdict = String.valueOf(evaluation.get("verdict"));
        if (pinned && !"keep".equals(verdict)) {
            evaluation.put("verdict", "keep");
            evaluation.put("ruleOverride", "pinned_skill");
        } else if ("active".equals(deterministicStatus)
                && ("archive_candidate".equals(verdict) || "merge_candidate".equals(verdict))) {
            evaluation.put("verdict", "keep");
            evaluation.put("ruleOverride", "recent_real_usage");
        }
        return evaluation;
    }

    /** 根据文件时效、固定内容检查和用量生成模型失败时仍可使用的建议。 */
    private Map<String, Object> deterministicEvaluation(
            String skillName, String status, List<String> contentFlags) {
        Map<String, Object> evaluation = new LinkedHashMap<String, Object>();
        evaluation.put("skillName", skillName);
        String verdict;
        if ("archived".equals(status)) {
            verdict = "archive_candidate";
        } else if (!contentFlags.isEmpty() || "stale".equals(status)) {
            verdict = "improve";
        } else {
            verdict = "keep";
        }
        evaluation.put("verdict", verdict);
        evaluation.put("confidence", Double.valueOf(0.55D));
        evaluation.put("scores", new LinkedHashMap<String, Object>());
        evaluation.put("evidenceRefs", new ArrayList<String>());
        evaluation.put("issues", new ArrayList<String>(contentFlags));
        evaluation.put("recommendations", new ArrayList<String>(contentFlags));
        return evaluation;
    }

    /** 读取并脱敏技能主文件，模型只接收受限长度的真实正文。 */
    private String readSkillContent(SkillDescriptor descriptor) {
        try {
            String content =
                    FileUtil.readUtf8String(FileUtil.file(descriptor.getSkillDir(), "SKILL.md"));
            return SecretRedactor.redact(
                    MemoryContextBoundary.scrubVisibleText(StrUtil.nullToEmpty(content)), 6000);
        } catch (Exception e) {
            return "";
        }
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
                            Map<String, Object> skills = ensureMap(state, "skills");
                            Object rawSkill = skills.get(skillName);
                            if (!(rawSkill instanceof Map)) {
                                throw new IllegalArgumentException("未知技能整理建议。");
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> skillRecord = (Map<String, Object>) rawSkill;
                            Object rawSuggestions = skillRecord.get("suggestions");
                            if (!(rawSuggestions instanceof List)
                                    || !((List<?>) rawSuggestions).contains(suggestion)) {
                                throw new IllegalArgumentException("建议已过期或不属于该技能。");
                            }
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
        if (value == null) {
            return 0L;
        }
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

    /** 返回低敏异常摘要，避免报告和日志泄露模型或会话数据。 */
    private String safeError(Exception error) {
        if (error == null) {
            return "unknown";
        }
        return SecretRedactor.redact(
                error.getClass().getSimpleName()
                        + ": "
                        + StrUtil.blankToDefault(error.getMessage(), "unknown"),
                300);
    }

    /** 注册统一报告出口，使后台调度生成的报告也可被 Dashboard 查询。 */
    public void setReportSink(ReportSink reportSink) {
        this.reportSink = reportSink;
    }

    /** 关闭 AI 辅助执行器。 */
    public void shutdown() {
        if (aiEvaluator != null) {
            aiEvaluator.shutdown();
        }
    }

    /** 接收一次完整整理报告的持久化出口。 */
    public interface ReportSink {
        /**
         * 保存一次整理报告。
         *
         * @param report 完整整理报告。
         * @throws Exception 持久化失败时抛出异常。
         */
        void save(Map<String, Object> report) throws Exception;
    }
}
