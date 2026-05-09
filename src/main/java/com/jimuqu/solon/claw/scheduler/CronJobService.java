package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** Jimuqu 风格 Cron 任务管理服务。 */
public class CronJobService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String DEFAULT_SOURCE = "MEMORY:dashboard:cron";
    private static final CronPromptThreat[] CRON_PROMPT_THREATS =
            new CronPromptThreat[] {
                threat(
                        "prompt_injection",
                        "ignore\\s+(?:\\w+\\s+)*(?:previous|all|above|prior)\\s+(?:\\w+\\s+)*instructions"),
                threat("deception_hide", "do\\s+not\\s+tell\\s+the\\s+user"),
                threat("sys_prompt_override", "system\\s+prompt\\s+override"),
                threat(
                        "disregard_rules",
                        "disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)"),
                threat(
                        "exfil_curl",
                        "curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
                threat(
                        "exfil_wget",
                        "wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
                threat("read_secrets", "cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass)"),
                threat("ssh_backdoor", "authorized_keys"),
                threat("sudoers_mod", "/etc/sudoers|visudo"),
                threat("destructive_root_rm", "rm\\s+-rf\\s+/")
            };

    private final AppConfig appConfig;
    private final CronJobRepository cronJobRepository;

    public CronJobService(AppConfig appConfig, CronJobRepository cronJobRepository) {
        this.appConfig = appConfig;
        this.cronJobRepository = cronJobRepository;
    }

    public CronJobRecord create(String sourceKey, Map<String, Object> body) throws Exception {
        String schedule = scheduleValue(body.get("schedule"), body.get("cronExpr"), null);
        String prompt = string(body.get("prompt"), "");
        List<String> skills = canonicalSkills(body);
        String script = string(body.get("script"), null);
        boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
        ModelOverride modelOverride =
                modelOverride(
                        body.get("model"),
                        body.get("provider"),
                        body.get("base_url"),
                        body.get("baseUrl"),
                        null,
                        null,
                        null);
        if (StrUtil.isBlank(schedule)) {
            throw new IllegalStateException("schedule is required");
        }
        if (noAgent && StrUtil.isBlank(script)) {
            throw new IllegalStateException("no_agent requires script");
        }
        if (!noAgent && StrUtil.isBlank(prompt) && CollUtil.isEmpty(skills)) {
            throw new IllegalStateException("prompt or skills are required");
        }
        scanPrompt(prompt);
        validateScript(script);
        String workdir = normalizeWorkdir(string(body.get("workdir"), null));
        List<String> dependencyRefs = dependencyRefs(body);
        validateContextFrom(dependencyRefs);

        long now = System.currentTimeMillis();
        CronJobRecord record = new CronJobRecord();
        record.setJobId(IdSupport.newId());
        record.setName(defaultJobName(body, prompt, skills, script, noAgent));
        record.setCronExpr(schedule);
        record.setPrompt(prompt);
        record.setSourceKey(StrUtil.blankToDefault(sourceKey, DEFAULT_SOURCE));
        String deliver = deliverValue(body.get("deliver"), defaultDeliver(body));
        validateDeliverTargets(deliver);
        record.setDeliverPlatform(deliver);
        record.setDeliverChatId(string(body.get("deliver_chat_id"), string(body.get("deliverChatId"), null)));
        record.setDeliverThreadId(string(body.get("deliver_thread_id"), string(body.get("deliverThreadId"), null)));
        record.setOriginJson(json(body.get("origin")));
        record.setSkillsJson(json(skills));
        record.setRepeatTimes(intValue(body.get("repeat"), 0));
        record.setRepeatCompleted(0);
        record.setScript(script);
        record.setWorkdir(workdir);
        record.setNoAgent(noAgent);
        record.setContextFromJson(json(dependencyRefs));
        record.setEnabledToolsetsJson(json(stringList(body.get("enabled_toolsets"))));
        applyModelPin(record, modelOverride.model, modelOverride.provider, modelOverride.baseUrl);
        record.setWrapResponse(
                bool(body.get("wrap_response"), bool(body.get("wrapResponse"), appConfig.getScheduler().isWrapResponse())));
        record.setStatus(STATUS_ACTIVE);
        record.setNextRunAt(CronSupport.nextRunAt(schedule, now));
        record.setLastRunAt(0L);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return cronJobRepository.save(record);
    }

    public CronJobRecord update(String jobId, Map<String, Object> body) throws Exception {
        CronJobRecord record = require(jobId);
        if (body.containsKey("name")) {
            record.setName(string(body.get("name"), record.getName()));
        }
        if (body.containsKey("prompt")) {
            String prompt = string(body.get("prompt"), "");
            scanPrompt(prompt);
            record.setPrompt(prompt);
        }
        if (body.containsKey("schedule") || body.containsKey("cronExpr")) {
            String schedule = scheduleValue(body.get("schedule"), body.get("cronExpr"), record.getCronExpr());
            CronSupport.validate(schedule);
            record.setCronExpr(schedule);
            record.setNextRunAt(CronSupport.nextRunAt(schedule, System.currentTimeMillis()));
            if (!STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
                record.setStatus(STATUS_ACTIVE);
            }
        }
        if (body.containsKey("deliver")) {
            String deliver = deliverValue(body.get("deliver"), "local");
            validateDeliverTargets(deliver);
            record.setDeliverPlatform(deliver);
        }
        if (body.containsKey("deliver_chat_id") || body.containsKey("deliverChatId")) {
            record.setDeliverChatId(string(body.get("deliver_chat_id"), string(body.get("deliverChatId"), null)));
        }
        if (body.containsKey("deliver_thread_id") || body.containsKey("deliverThreadId")) {
            record.setDeliverThreadId(string(body.get("deliver_thread_id"), string(body.get("deliverThreadId"), null)));
        }
        if (body.containsKey("skills") || body.containsKey("skill")) {
            record.setSkillsJson(json(canonicalSkills(body)));
        }
        if (body.containsKey("repeat")) {
            int repeat = intValue(body.get("repeat"), 0);
            record.setRepeatTimes(Math.max(0, repeat));
        }
        if (body.containsKey("script")) {
            String script = string(body.get("script"), null);
            validateScript(script);
            record.setScript(script);
        }
        if (body.containsKey("workdir")) {
            record.setWorkdir(normalizeWorkdir(string(body.get("workdir"), null)));
        }
        if (body.containsKey("no_agent") || body.containsKey("noAgent")) {
            boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
            if (noAgent && StrUtil.isBlank(record.getScript())) {
                throw new IllegalStateException("no_agent requires script");
            }
            record.setNoAgent(noAgent);
        }
        if (containsDependencyRefs(body)) {
            List<String> refs = dependencyRefs(body);
            validateContextFrom(refs);
            record.setContextFromJson(json(refs));
        }
        if (body.containsKey("enabled_toolsets")) {
            record.setEnabledToolsetsJson(json(stringList(body.get("enabled_toolsets"))));
        }
        if (body.containsKey("model")
                || body.containsKey("provider")
                || body.containsKey("base_url")
                || body.containsKey("baseUrl")) {
            ModelOverride modelOverride =
                    modelOverride(
                            body.get("model"),
                            body.get("provider"),
                            body.get("base_url"),
                            body.get("baseUrl"),
                            defaultModelValue(body, record),
                            defaultProviderValue(body, record),
                            defaultBaseUrlValue(body, record));
            applyModelPin(record, modelOverride.model, modelOverride.provider, modelOverride.baseUrl);
        }
        if (body.containsKey("wrap_response") || body.containsKey("wrapResponse")) {
            record.setWrapResponse(bool(body.get("wrap_response"), bool(body.get("wrapResponse"), true)));
        }
        if (body.containsKey("enabled")) {
            boolean enabled = bool(body.get("enabled"), true);
            record.setStatus(enabled ? STATUS_ACTIVE : STATUS_PAUSED);
            record.setPausedAt(enabled ? 0L : System.currentTimeMillis());
            if (enabled) {
                record.setPausedReason(null);
            }
        }
        if (body.containsKey("status") || body.containsKey("state")) {
            applyEditableStatus(record, string(body.get("status"), string(body.get("state"), null)));
        }
        if (body.containsKey("paused_reason") || body.containsKey("pausedReason")) {
            String reason =
                    string(body.get("paused_reason"), string(body.get("pausedReason"), null));
            if (STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
                record.setPausedReason(StrUtil.blankToDefault(reason, "paused from edit"));
                if (record.getPausedAt() <= 0L) {
                    record.setPausedAt(System.currentTimeMillis());
                }
            }
        }
        if (record.isNoAgent() && StrUtil.isBlank(record.getScript())) {
            throw new IllegalStateException("no_agent requires script");
        }
        return cronJobRepository.update(record);
    }

    private void applyEditableStatus(CronJobRecord record, String rawStatus) {
        String status = StrUtil.nullToEmpty(rawStatus).trim().toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(status)) {
            return;
        }
        if ("active".equals(status)
                || "enabled".equals(status)
                || "enable".equals(status)
                || "running".equals(status)
                || "resume".equals(status)
                || "resumed".equals(status)) {
            record.setStatus(STATUS_ACTIVE);
            record.setPausedAt(0L);
            record.setPausedReason(null);
            if (record.getNextRunAt() <= System.currentTimeMillis()) {
                record.setNextRunAt(
                        CronSupport.nextRunAt(record.getCronExpr(), System.currentTimeMillis()));
            }
            return;
        }
        if ("paused".equals(status)
                || "pause".equals(status)
                || "disabled".equals(status)
                || "disable".equals(status)
                || "stopped".equals(status)
                || "stop".equals(status)) {
            record.setStatus(STATUS_PAUSED);
            record.setPausedAt(System.currentTimeMillis());
            return;
        }
        if ("completed".equals(status) || "complete".equals(status)) {
            record.setStatus(STATUS_COMPLETED);
            record.setNextRunAt(0L);
            return;
        }
        throw new IllegalStateException("unsupported cron job status: " + rawStatus);
    }

    public List<CronJobRecord> listAll(boolean includeDisabled) throws Exception {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : cronJobRepository.listAll()) {
            if (includeDisabled || !STATUS_PAUSED.equalsIgnoreCase(job.getStatus())) {
                result.add(job);
            }
        }
        return result;
    }

    public List<CronJobRecord> listBySource(String sourceKey, boolean includeDisabled) throws Exception {
        List<CronJobRecord> result = new ArrayList<CronJobRecord>();
        for (CronJobRecord job : cronJobRepository.listBySource(sourceKey)) {
            if (includeDisabled || !STATUS_PAUSED.equalsIgnoreCase(job.getStatus())) {
                result.add(job);
            }
        }
        return result;
    }

    public CronJobRecord require(String jobId) throws Exception {
        CronJobRecord record = cronJobRepository.findById(jobId);
        if (record == null) {
            throw new IllegalStateException("Job not found: " + jobId);
        }
        return record;
    }

    public CronJobRecord pause(String jobId, String reason) throws Exception {
        CronJobRecord record = require(jobId);
        record.setStatus(STATUS_PAUSED);
        record.setPausedAt(System.currentTimeMillis());
        record.setPausedReason(reason);
        return cronJobRepository.update(record);
    }

    public CronJobRecord resume(String jobId) throws Exception {
        CronJobRecord record = require(jobId);
        record.setStatus(STATUS_ACTIVE);
        record.setPausedAt(0L);
        record.setPausedReason(null);
        if (record.getNextRunAt() <= System.currentTimeMillis()) {
            record.setNextRunAt(CronSupport.nextRunAt(record.getCronExpr(), System.currentTimeMillis()));
        }
        return cronJobRepository.update(record);
    }

    public CronJobRecord trigger(String jobId) throws Exception {
        CronJobRecord record = require(jobId);
        record.setStatus(STATUS_ACTIVE);
        record.setNextRunAt(System.currentTimeMillis());
        return cronJobRepository.update(record);
    }

    public CronJobRecord remove(String jobId) throws Exception {
        CronJobRecord record = require(jobId);
        cronJobRepository.delete(jobId);
        return record;
    }

    public List<CronJobRunRecord> history(String jobId, int limit) throws Exception {
        require(jobId);
        return cronJobRepository.listRuns(jobId, limit);
    }

    public Map<String, Object> rewriteSkillRefs(
            Map<String, String> consolidated, List<String> pruned) throws Exception {
        Map<String, String> consolidatedMap = normalizedMap(consolidated);
        List<String> prunedList = normalizedList(pruned);
        for (String key : consolidatedMap.keySet()) {
            prunedList.remove(key);
        }
        if (consolidatedMap.isEmpty() && prunedList.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("rewrites", new ArrayList<Map<String, Object>>());
            empty.put("jobs_updated", Integer.valueOf(0));
            empty.put("jobs_scanned", Integer.valueOf(0));
            return empty;
        }

        List<CronJobRecord> jobs = cronJobRepository.listAll();
        List<Map<String, Object>> rewrites = new ArrayList<Map<String, Object>>();
        for (CronJobRecord job : jobs) {
            List<String> before = parseList(job.getSkillsJson());
            if (before.isEmpty()) {
                continue;
            }
            List<String> after = new ArrayList<String>();
            Map<String, String> mapped = new LinkedHashMap<String, String>();
            List<String> dropped = new ArrayList<String>();
            for (String skill : before) {
                String target = consolidatedMap.get(skill);
                if (StrUtil.isNotBlank(target)) {
                    mapped.put(skill, target);
                    if (!after.contains(target)) {
                        after.add(target);
                    }
                } else if (prunedList.contains(skill)) {
                    dropped.add(skill);
                } else if (!after.contains(skill)) {
                    after.add(skill);
                }
            }
            if (mapped.isEmpty() && dropped.isEmpty()) {
                continue;
            }
            job.setSkillsJson(json(after));
            cronJobRepository.update(job);

            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("job_id", job.getJobId());
            entry.put("job_name", StrUtil.blankToDefault(job.getName(), job.getJobId()));
            entry.put("before", before);
            entry.put("after", after);
            entry.put("mapped", mapped);
            entry.put("dropped", dropped);
            rewrites.add(entry);
        }

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("rewrites", rewrites);
        report.put("jobs_updated", Integer.valueOf(rewrites.size()));
        report.put("jobs_scanned", Integer.valueOf(jobs.size()));
        return report;
    }

    public Map<String, Object> runToView(CronJobRunRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("run_id", record.getRunId());
        result.put("job_id", record.getJobId());
        result.put("source_key", record.getSourceKey());
        result.put("trigger", StrUtil.blankToDefault(record.getTriggerType(), "scheduled"));
        result.put("attempt", Integer.valueOf(record.getAttempt()));
        result.put("started_at", record.getStartedAt() <= 0 ? null : Long.valueOf(record.getStartedAt()));
        result.put("finished_at", record.getFinishedAt() <= 0 ? null : Long.valueOf(record.getFinishedAt()));
        result.put("finished", Boolean.valueOf(record.getFinishedAt() > 0));
        result.put("duration_ms", durationMillis(record));
        result.put("status", record.getStatus());
        result.put("output", record.getOutput());
        result.put("error", record.getError());
        result.put("delivery_error", record.getDeliveryError());
        result.put("delivery_result", parse(record.getDeliveryResultJson()));
        result.put("summary", record.getSummary());
        return result;
    }

    private Long durationMillis(CronJobRunRecord record) {
        if (record == null || record.getStartedAt() <= 0 || record.getFinishedAt() <= 0) {
            return null;
        }
        return Long.valueOf(Math.max(0L, record.getFinishedAt() - record.getStartedAt()));
    }

    public Map<String, Object> toView(CronJobRecord record) {
        Map<String, Object> schedule = new LinkedHashMap<String, Object>();
        String scheduleKind = CronSupport.kind(record.getCronExpr());
        schedule.put("kind", scheduleKind);
        schedule.put("raw", record.getCronExpr());
        if ("interval".equals(scheduleKind)) {
            schedule.put("minutes", CronSupport.intervalMinutes(record.getCronExpr()));
        } else if ("once".equals(scheduleKind)) {
            Long absoluteRunAt = CronSupport.absoluteRunAt(record.getCronExpr());
            schedule.put(
                    "run_at",
                    absoluteRunAt == null
                            ? Long.valueOf(record.getNextRunAt())
                            : absoluteRunAt);
        } else {
            schedule.put("expr", record.getCronExpr());
        }
        schedule.put("display", scheduleDisplay(record));

        Map<String, Object> repeat = new LinkedHashMap<String, Object>();
        repeat.put("times", record.getRepeatTimes() <= 0 ? null : Integer.valueOf(record.getRepeatTimes()));
        repeat.put("completed", Integer.valueOf(record.getRepeatCompleted()));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getJobId());
        result.put("job_id", record.getJobId());
        result.put("name", record.getName());
        result.put("prompt", record.getPrompt());
        result.put("prompt_preview", StrUtil.maxLength(record.getPrompt(), 120));
        result.put("cron_expr", record.getCronExpr());
        result.put("schedule", schedule);
        result.put("schedule_display", schedule.get("display"));
        result.put("enabled", Boolean.valueOf(STATUS_ACTIVE.equalsIgnoreCase(record.getStatus())));
        result.put("state", state(record));
        result.put("actions", actions(record));
        result.put("deliver", StrUtil.blankToDefault(record.getDeliverPlatform(), "local"));
        result.put("deliver_chat_id", record.getDeliverChatId());
        result.put("deliver_thread_id", record.getDeliverThreadId());
        result.put("origin", parse(record.getOriginJson()));
        result.put("skills", parseList(record.getSkillsJson()));
        result.put("skill", first(parseList(record.getSkillsJson())));
        result.put("repeat", repeat);
        result.put("script", record.getScript());
        result.put("workdir", record.getWorkdir());
        result.put("no_agent", Boolean.valueOf(record.isNoAgent()));
        List<String> contextFrom = parseList(record.getContextFromJson());
        result.put("context_from", contextFrom);
        result.put("depends_on", contextFrom);
        result.put("enabled_toolsets", parseList(record.getEnabledToolsetsJson()));
        result.put("model", record.getModel());
        result.put("provider", record.getProvider());
        result.put("base_url", record.getBaseUrl());
        result.put("wrap_response", Boolean.valueOf(record.isWrapResponse()));
        result.put("last_run_at", record.getLastRunAt() <= 0 ? null : Long.valueOf(record.getLastRunAt()));
        result.put("next_run_at", record.getNextRunAt() <= 0 ? null : Long.valueOf(record.getNextRunAt()));
        result.put("last_status", record.getLastStatus());
        result.put("last_error", record.getLastError());
        result.put("last_delivery_error", record.getLastDeliveryError());
        result.put("last_output", record.getLastOutput());
        result.put("paused_at", record.getPausedAt() <= 0 ? null : Long.valueOf(record.getPausedAt()));
        result.put("paused_reason", record.getPausedReason());
        result.put("created_at", Long.valueOf(record.getCreatedAt()));
        return result;
    }

    public Map<String, Object> guide() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("objective", "通过 Cron 自动化创建、编辑、暂停、恢复、立即运行和复核任务，并把结果投递到本地或指定国内渠道。");
        result.put("schedule_types", Arrays.asList("interval", "cron", "once"));
        result.put(
                "editable_fields",
                Arrays.asList(
                        "name",
                        "schedule",
                        "prompt",
                        "skills",
                        "deliver",
                        "deliver_chat_id",
                        "deliver_thread_id",
                        "repeat",
                        "script",
                        "workdir",
                        "no_agent",
                        "context_from",
                        "depends_on",
                        "enabled_toolsets",
                        "model",
                        "provider",
                        "base_url",
                        "wrap_response",
                        "enabled",
                        "status",
                        "state",
                        "paused_reason"));
        result.put("actions", cronGuideActions());
        result.put("aliases", cronGuideAliases());
        result.put("skill_binding", cronGuideSkillBinding());
        result.put("delivery", cronGuideDelivery());
        result.put("runtime_modes", cronGuideRuntimeModes());
        result.put("history_and_status", cronGuideHistoryAndStatus());
        result.put("security", cronGuideSecurity());
        result.put(
                "slash_examples",
                Arrays.asList(
                        "/cron add \"every 2h\" \"Check server status\" --skill blogwatcher",
                        "/cron edit <job-id> --schedule \"every 4h\" --prompt \"New task\"",
                        "/cron edit <job-id> --skill blogwatcher --skill maps",
                        "/cron edit <job-id> --remove-skill blogwatcher",
                        "/cron edit <job-id> --clear-skills",
                        "/cron edit <job-id> --clear-repeat",
                        "/cron add \"every 2h\" \"task\" --deliver feishu --deliver-chat-id chat --deliver-thread-id thread",
                        "/cron edit <job-id> --no-agent --script collect.py --workdir runtime/projects/demo",
                        "/cron edit <job-id> --context-from upstream-job --enabled-toolsets web,terminal",
                        "/cron edit <job-id> --clear-context-from --clear-enabled-toolsets",
                        "/cron run <job-id>",
                        "/cron history <job-id> --limit 20"));
        result.put(
                "api_routes",
                Arrays.asList(
                        "GET /api/cron/jobs/guide",
                        "GET /api/cron/jobs",
                        "POST /api/cron/jobs",
                        "PUT /api/cron/jobs/{id}",
                        "POST /api/cron/jobs/{id}/pause",
                        "POST /api/cron/jobs/{id}/resume",
                        "POST /api/cron/jobs/{id}/run",
                        "GET /api/cron/jobs/{id}/runs",
                        "GET /api/jobs/guide"));
        return result;
    }

    private Map<String, Object> cronGuideActions() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("list", "查看当前会话或全部任务");
        result.put("inspect", "查看单个任务详情、动作标记和运行摘要");
        result.put("next", "按 next_run_at 查看即将运行的任务");
        result.put("status", "查看任务计数、到期任务、最近失败和下次运行");
        result.put("add", "创建自动化任务");
        result.put("edit", "编辑调度、提示词、技能、投递、脚本、模型和包装策略");
        result.put("pause", "暂停任务并保留暂停原因");
        result.put("resume", "恢复任务并重新计算下次运行时间");
        result.put("run", "立即触发任务");
        result.put("retry", "重跑最近失败或需要复核的任务");
        result.put("history", "查看执行历史、输出、错误和投递结果");
        result.put("remove", "删除任务");
        return result;
    }

    private Map<String, Object> cronGuideAliases() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("add", Arrays.asList("create"));
        result.put("edit", Arrays.asList("update"));
        result.put("pause", Arrays.asList("disable", "stop"));
        result.put("resume", Arrays.asList("enable", "start"));
        result.put("run", Arrays.asList("trigger", "retry", "rerun"));
        result.put("inspect", Arrays.asList("show", "detail"));
        result.put("remove", Arrays.asList("delete", "rm"));
        result.put("next", Arrays.asList("upcoming"));
        return result;
    }

    private Map<String, Object> cronGuideSkillBinding() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("fields", Arrays.asList("skill", "skills"));
        result.put("replace", Arrays.asList("--skill name", "--skills a,b"));
        result.put("append", Arrays.asList("--add-skill name", "--add-skills a,b"));
        result.put("remove", Arrays.asList("--remove-skill name", "--remove-skills a,b"));
        result.put("clear", Arrays.asList("--clear-skills"));
        result.put("dependency_fields", Arrays.asList("context_from", "depends_on"));
        result.put(
                "dependency_flags",
                Arrays.asList(
                        "--context-from job-id",
                        "--depends-on job-id",
                        "--clear-context-from",
                        "--clear-depends-on"));
        result.put("dedupe", Boolean.TRUE);
        return result;
    }

    private Map<String, Object> cronGuideDelivery() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targets", Arrays.asList("origin", "local", "feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao"));
        result.put("fields", Arrays.asList("deliver", "deliver_chat_id", "deliver_thread_id", "wrap_response"));
        result.put("default_from_slash", "origin");
        result.put("default_from_dashboard", "local");
        result.put("clear_flags", Arrays.asList("--clear-deliver-chat-id", "--clear-deliver-thread-id"));
        result.put("wrap_flags", Arrays.asList("--wrap-response", "--no-wrap-response", "--wrap", "--raw", "--no-wrap"));
        result.put(
                "target_forms",
                Arrays.asList(
                        "origin",
                        "local",
                        "platform",
                        "platform:chat_id",
                        "platform:chat_id:thread_id",
                        "target1,target2"));
        result.put("multi_target", "deliver 支持逗号分隔或平台:目标形式，创建和编辑时会校验平台名称。");
        return result;
    }

    private Map<String, Object> cronGuideRuntimeModes() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agent", "默认模式，使用 prompt 和 skills 进入 Agent 主循环。");
        result.put("no_agent", "脚本直投模式，必须提供 script，stdout 可按投递策略发送。");
        result.put("script_fields", Arrays.asList("script", "workdir", "enabled_toolsets"));
        result.put("dependency_fields", Arrays.asList("context_from", "depends_on"));
        result.put("model_pin_fields", Arrays.asList("model", "provider", "base_url"));
        result.put(
                "clear_flags",
                Arrays.asList(
                        "--clear-repeat",
                        "--clear-script",
                        "--clear-workdir",
                        "--clear-toolsets",
                        "--clear-enabled-toolsets",
                        "--clear-model",
                        "--clear-provider",
                        "--clear-base-url"));
        result.put("mode_flags", Arrays.asList("--no-agent", "--agent"));
        return result;
    }

    private Map<String, Object> cronGuideHistoryAndStatus() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status_fields", Arrays.asList("total", "active", "paused", "completed", "due", "next", "recent_failures"));
        result.put("run_fields", Arrays.asList("run_id", "trigger", "attempt", "status", "output", "error", "delivery_result", "summary"));
        result.put("action_flags", Arrays.asList("can_inspect", "can_edit", "can_pause", "can_resume", "can_run", "can_retry", "can_history"));
        result.put("limits", "status、next、history 和 inspect 的 limit 会限制到安全范围。");
        return result;
    }

    private Map<String, Object> cronGuideSecurity() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("prompt_scan", Arrays.asList("prompt_injection", "deception_hide", "sys_prompt_override", "disregard_rules", "exfil_curl", "exfil_wget", "read_secrets", "ssh_backdoor", "sudoers_mod", "destructive_root_rm"));
        result.put("script_validation", "script 禁止绝对路径、父目录跳转、shell 片段、控制字符和 URL。");
        result.put("workdir_validation", "workdir 会规范化到 runtime home 内部，禁止逃逸工作目录。");
        result.put("delivery_validation", "deliver 只允许本地、origin 或已支持平台。");
        result.put("approval_mode", "触发后的命令和工具调用继续走运行时审批与危险命令策略。");
        return result;
    }

    private String state(CronJobRecord record) {
        if (STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
            return "paused";
        }
        if (STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())) {
            return "completed";
        }
        return "scheduled";
    }

    private Map<String, Object> actions(CronJobRecord record) {
        String status = record == null || record.getStatus() == null ? "" : record.getStatus();
        boolean paused = STATUS_PAUSED.equalsIgnoreCase(status);
        boolean completed = STATUS_COMPLETED.equalsIgnoreCase(status);
        boolean failed =
                record != null
                        && ("error".equalsIgnoreCase(record.getLastStatus())
                                || StrUtil.isNotBlank(record.getLastError())
                                || StrUtil.isNotBlank(record.getLastDeliveryError()));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("can_inspect", Boolean.TRUE);
        result.put("can_edit", Boolean.TRUE);
        result.put("can_remove", Boolean.TRUE);
        result.put("can_history", Boolean.TRUE);
        result.put("can_pause", Boolean.valueOf(!paused && !completed));
        result.put("can_resume", Boolean.valueOf(paused || completed));
        result.put("can_run", Boolean.TRUE);
        result.put("can_retry", Boolean.valueOf(failed || completed));
        result.put("supports_enable_alias", Boolean.TRUE);
        result.put("supports_disable_alias", Boolean.TRUE);
        result.put("supports_start_alias", Boolean.TRUE);
        result.put("supports_stop_alias", Boolean.TRUE);
        result.put("supports_rerun_alias", Boolean.TRUE);
        return result;
    }

    private String scheduleDisplay(CronJobRecord record) {
        String expr = record.getCronExpr();
        String kind = CronSupport.kind(expr);
        if ("interval".equals(kind)) {
            Integer minutes = CronSupport.intervalMinutes(expr);
            return minutes == null ? expr : "every " + minutes + "m";
        }
        if ("once".equals(kind)) {
            Long absoluteRunAt = CronSupport.absoluteRunAt(expr);
            if (absoluteRunAt != null) {
                return "once at " + absoluteRunAt;
            }
            return "once in " + expr;
        }
        return expr;
    }

    private void validateContextFrom(List<String> refs) throws Exception {
        for (String ref : refs) {
            if (cronJobRepository.findById(ref) == null) {
                throw new IllegalStateException("context_from job not found: " + ref);
            }
        }
    }

    private boolean containsDependencyRefs(Map<String, Object> body) {
        return body.containsKey("context_from") || body.containsKey("depends_on");
    }

    private List<String> dependencyRefs(Map<String, Object> body) {
        if (body.containsKey("context_from")) {
            return stringList(body.get("context_from"));
        }
        return stringList(body.get("depends_on"));
    }

    private void validateScript(String script) {
        if (StrUtil.isBlank(script)) {
            return;
        }
        String value = script.trim();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalStateException("script path contains control character");
            }
        }
        if (value.startsWith("~")) {
            throw new IllegalStateException("script must stay within runtime/scripts");
        }
        try {
            File scriptsDir = FileUtil.file(appConfig.getRuntime().getHome(), "scripts").getCanonicalFile();
            File requested = new File(value);
            File target = (requested.isAbsolute() ? requested : new File(scriptsDir, value)).getCanonicalFile();
            if (!isUnderDirectory(scriptsDir, target)) {
                throw new IllegalStateException("script must stay within runtime/scripts");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("script path could not be validated: " + safeError(e));
        }
    }

    private boolean isUnderDirectory(File root, File target) throws java.io.IOException {
        java.nio.file.Path rootPath = root.getCanonicalFile().toPath().toAbsolutePath().normalize();
        java.nio.file.Path targetPath = target.getCanonicalFile().toPath().toAbsolutePath().normalize();
        if (targetPath.equals(rootPath)) {
            return false;
        }
        return targetPath.startsWith(rootPath);
    }

    private String normalizeWorkdir(String workdir) {
        String value = StrUtil.nullToEmpty(workdir).trim();
        if (StrUtil.isBlank(value)) {
            return null;
        }
        SecurityPolicyService.FileVerdict textVerdict =
                SecurityPolicyService.checkWorkdirText(value);
        if (!textVerdict.isAllowed()) {
            throw new IllegalStateException(
                    "workdir blocked by security policy: "
                            + SecretRedactor.redact(textVerdict.getPath(), 400)
                            + " - "
                            + textVerdict.getMessage());
        }
        try {
            File file = FileUtil.file(expandUserHome(value));
            if (!file.isAbsolute() || !file.exists() || !file.isDirectory()) {
                throw new IllegalStateException("workdir must be an existing absolute directory");
            }
            File canonical = file.getCanonicalFile();
            SecurityPolicyService.FileVerdict verdict =
                    new SecurityPolicyService(appConfig).checkPath(canonical.getAbsolutePath(), false);
            if (!verdict.isAllowed()) {
                throw new IllegalStateException(
                        "workdir blocked by security policy: "
                                + SecretRedactor.redact(verdict.getPath(), 400)
                                + " - "
                                + verdict.getMessage());
            }
            String normalized = file.getAbsoluteFile().toPath().normalize().toFile().getAbsolutePath();
            if (usesForwardSlash(value)) {
                return normalized.replace('\\', '/');
            }
            return normalized;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("workdir path could not be validated: " + safeError(e));
        }
    }

    private String safeError(Exception e) {
        if (e == null) {
            return "Exception";
        }
        return SecretRedactor.redact(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 1000);
    }

    private boolean usesForwardSlash(String path) {
        return path != null && path.indexOf('/') >= 0 && path.indexOf('\\') < 0;
    }

    private String expandUserHome(String path) {
        if (StrUtil.isBlank(path)) {
            return path;
        }
        String home = StrUtil.nullToEmpty(System.getProperty("user.home")).trim();
        if (StrUtil.isBlank(home)) {
            return path;
        }
        if ("~".equals(path)) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }

    public void scanPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return;
        }
        for (int i = 0; i < prompt.length(); i++) {
            char ch = prompt.charAt(i);
            if (isInvisibleInjectionChar(ch)) {
                throw new IllegalStateException(
                        "Blocked invisible unicode U+"
                                + String.format(Locale.ROOT, "%04X", Integer.valueOf(ch))
                                + " in cron prompt");
            }
        }
        for (CronPromptThreat threat : CRON_PROMPT_THREATS) {
            if (threat.pattern.matcher(prompt).find()) {
                throw new IllegalStateException(
                        "Blocked unsafe cron prompt pattern: " + threat.id);
            }
        }
    }

    private static boolean isInvisibleInjectionChar(char ch) {
        return ch == '\u200b'
                || ch == '\u200c'
                || ch == '\u200d'
                || ch == '\u2060'
                || ch == '\ufeff'
                || ch == '\u202a'
                || ch == '\u202b'
                || ch == '\u202c'
                || ch == '\u202d'
                || ch == '\u202e';
    }

    private static CronPromptThreat threat(String id, String regex) {
        return new CronPromptThreat(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static class CronPromptThreat {
        private final String id;
        private final Pattern pattern;

        private CronPromptThreat(String id, Pattern pattern) {
            this.id = id;
            this.pattern = pattern;
        }
    }

    private static class ModelOverride {
        private final String model;
        private final String provider;
        private final String baseUrl;

        private ModelOverride(String model, String provider, String baseUrl) {
            this.model = model;
            this.provider = provider;
            this.baseUrl = baseUrl;
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        return ONode.serialize(value);
    }

    private String defaultDeliver(Map<String, Object> body) {
        return body != null && body.get("origin") != null ? "origin" : "local";
    }

    private String deliverValue(Object value, String defaultValue) {
        List<String> targets = stringList(value);
        return targets.isEmpty() ? defaultValue : join(targets);
    }

    private void validateDeliverTargets(String deliver) {
        if (StrUtil.isBlank(deliver)) {
            return;
        }
        for (String rawTarget : deliver.split(",")) {
            String target = StrUtil.trim(rawTarget);
            if (StrUtil.isBlank(target)) {
                continue;
            }
            if ("local".equalsIgnoreCase(target) || "origin".equalsIgnoreCase(target)) {
                continue;
            }
            String platformName = target;
            int colon = target.indexOf(':');
            if (colon >= 0) {
                platformName = target.substring(0, colon);
            }
            if (PlatformType.fromName(platformName) == null) {
                throw new IllegalStateException("unknown cron delivery platform: " + platformName);
            }
        }
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private Object parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        return ONode.ofJson(json).toData();
    }

    private List<String> parseList(String json) {
        List<String> result = new ArrayList<String>();
        Object data = parse(json);
        if (data instanceof Iterable) {
            for (Object item : (Iterable<?>) data) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private Object first(List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value == null) {
            return result;
        }
        if (value instanceof Map) {
            addString(result, structuredTarget((Map<?, ?>) value));
            return result;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<Object>) value) {
                if (item instanceof Map) {
                    addString(result, structuredTarget((Map<?, ?>) item));
                } else {
                    addString(result, item);
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            Object data = ONode.ofJson(text).toData();
            if (data instanceof Iterable) {
                for (Object item : (Iterable<Object>) data) {
                    if (item instanceof Map) {
                        addString(result, structuredTarget((Map<?, ?>) item));
                    } else {
                        addString(result, item);
                    }
                }
                return result;
            }
        }
        for (String part : text.split(",")) {
            addString(result, part);
        }
        return result;
    }

    private String scheduleValue(Object scheduleValue, Object cronExprValue, String defaultValue) {
        String schedule = scheduleObjectValue(scheduleValue);
        if (StrUtil.isNotBlank(schedule)) {
            return schedule;
        }
        schedule = scheduleObjectValue(cronExprValue);
        if (StrUtil.isNotBlank(schedule)) {
            return schedule;
        }
        return defaultValue;
    }

    private String scheduleObjectValue(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            String text = firstString(map, "raw", "expr", "cron", "value", "display");
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
            Object runAt = map.get("run_at");
            if (runAt == null) {
                runAt = map.get("runAt");
            }
            if (runAt instanceof Number) {
                return Instant.ofEpochMilli(((Number) runAt).longValue()).toString();
            }
            return string(runAt, null);
        }
        return string(value, null);
    }

    private String structuredTarget(Map<?, ?> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String platform = firstString(value, "platform", "type", "channel");
        String chatId = firstString(value, "chat_id", "chatId", "target", "target_id", "targetId");
        String threadId = firstString(value, "thread_id", "threadId", "message_id", "messageId");
        if (StrUtil.isBlank(platform)) {
            return null;
        }
        if ("local".equalsIgnoreCase(platform) || "origin".equalsIgnoreCase(platform)) {
            return platform.trim();
        }
        StringBuilder builder = new StringBuilder(platform.trim());
        if (StrUtil.isNotBlank(chatId)) {
            builder.append(':').append(chatId.trim());
        }
        if (StrUtil.isNotBlank(threadId)) {
            builder.append(':').append(threadId.trim());
        }
        return builder.toString();
    }

    private void applyModelPin(CronJobRecord record, String model, String provider, String baseUrl) {
        String normalizedModel = normalizeBlank(model);
        String normalizedProvider = normalizeBlank(provider);
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        if ("custom".equals(normalizedProvider)) {
            normalizedProvider = null;
        }
        if (StrUtil.isNotBlank(normalizedProvider)
                && !appConfig.getProviders().containsKey(normalizedProvider)) {
            throw new IllegalStateException("provider not found: " + normalizedProvider);
        }
        if (StrUtil.isNotBlank(normalizedModel) && StrUtil.isBlank(normalizedProvider)) {
            normalizedProvider = StrUtil.nullToEmpty(appConfig.getModel().getProviderKey()).trim();
        }
        if (StrUtil.isNotBlank(normalizedModel)
                && StrUtil.isBlank(normalizedProvider)
                && appConfig.getProviders().size() == 1) {
            normalizedProvider = appConfig.getProviders().keySet().iterator().next();
        }
        record.setModel(normalizedModel);
        record.setProvider(normalizedProvider);
        record.setBaseUrl(normalizedBaseUrl);
    }

    private ModelOverride modelOverride(
            Object modelValue,
            Object providerValue,
            Object baseUrlValue,
            Object baseUrlAliasValue,
            String defaultModel,
            String defaultProvider,
            String defaultBaseUrl) {
        Map<?, ?> modelObject = objectMap(modelValue);
        String model =
                modelObject != null
                        ? firstString(modelObject, "model", "name", "id")
                        : string(modelValue, defaultModel);
        String provider =
                providerValue != null
                        ? string(providerValue, defaultProvider)
                        : modelObject != null
                                ? firstString(modelObject, "provider", "providerKey", "provider_key")
                                : defaultProvider;
        String baseUrl =
                baseUrlValue != null || baseUrlAliasValue != null
                        ? string(baseUrlValue, string(baseUrlAliasValue, defaultBaseUrl))
                        : modelObject != null
                                ? firstString(modelObject, "base_url", "baseUrl", "api_url", "apiUrl")
                                : defaultBaseUrl;
        return new ModelOverride(model, provider, baseUrl);
    }

    private String defaultModelValue(Map<String, Object> body, CronJobRecord record) {
        return body.containsKey("model") ? null : record.getModel();
    }

    private String defaultProviderValue(Map<String, Object> body, CronJobRecord record) {
        return body.containsKey("provider") ? null : record.getProvider();
    }

    private String defaultBaseUrlValue(Map<String, Object> body, CronJobRecord record) {
        return body.containsKey("base_url") || body.containsKey("baseUrl") ? null : record.getBaseUrl();
    }

    private Map<?, ?> objectMap(Object value) {
        if (value instanceof Map) {
            return (Map<?, ?>) value;
        }
        if (!(value instanceof String)) {
            return null;
        }
        String text = ((String) value).trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        try {
            Object data = ONode.ofJson(text).toData();
            return data instanceof Map ? (Map<?, ?>) data : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstString(Map<?, ?> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Object value = map.get(keys[i]);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = normalizeBlank(value);
        while (StrUtil.isNotBlank(normalized) && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() == 0 ? null : text;
    }

    private List<String> canonicalSkills(Map<String, Object> body) {
        List<String> result = stringList(body.get("skills"));
        for (String item : stringList(body.get("skill"))) {
            addString(result, item);
        }
        return result;
    }

    private String defaultJobName(
            Map<String, Object> body, String prompt, List<String> skills, String script, boolean noAgent) {
        String explicit = string(body.get("name"), null);
        if (StrUtil.isNotBlank(explicit)) {
            return explicit;
        }
        String labelSource = normalizeBlank(prompt);
        if (StrUtil.isBlank(labelSource) && CollUtil.isNotEmpty(skills)) {
            labelSource = normalizeBlank(skills.get(0));
        }
        if (StrUtil.isBlank(labelSource) && noAgent) {
            labelSource = normalizeBlank(script);
        }
        if (StrUtil.isBlank(labelSource)) {
            labelSource = "cron job";
        }
        if (labelSource.length() > 50) {
            labelSource = labelSource.substring(0, 50);
        }
        return labelSource.trim();
    }

    private Map<String, String> normalizedMap(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalizeBlank(entry.getKey());
            String value = normalizeBlank(entry.getValue());
            if (StrUtil.isNotBlank(key) && StrUtil.isNotBlank(value)) {
                result.put(key, value);
            }
        }
        return result;
    }

    private List<String> normalizedList(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            addString(result, value);
        }
        return result;
    }

    private void addString(List<String> result, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (StrUtil.isNotBlank(text) && !result.contains(text)) {
            result.add(text);
        }
    }

    private String string(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? defaultValue : text;
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
