package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** Hermes 风格 Cron 任务管理服务。 */
public class CronJobService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String DEFAULT_SOURCE = "MEMORY:dashboard:cron";

    private final AppConfig appConfig;
    private final CronJobRepository cronJobRepository;

    public CronJobService(AppConfig appConfig, CronJobRepository cronJobRepository) {
        this.appConfig = appConfig;
        this.cronJobRepository = cronJobRepository;
    }

    public CronJobRecord create(String sourceKey, Map<String, Object> body) throws Exception {
        String schedule = string(body.get("schedule"), string(body.get("cronExpr"), null));
        String prompt = string(body.get("prompt"), "");
        List<String> skills = stringList(body.get("skills"));
        String script = string(body.get("script"), null);
        boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
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
        validateWorkdir(string(body.get("workdir"), null));
        validateContextFrom(stringList(body.get("context_from")));

        long now = System.currentTimeMillis();
        CronJobRecord record = new CronJobRecord();
        record.setJobId(IdSupport.newId());
        record.setName(StrUtil.blankToDefault(string(body.get("name"), null), "job-" + record.getJobId().substring(0, 8)));
        record.setCronExpr(schedule);
        record.setPrompt(prompt);
        record.setSourceKey(StrUtil.blankToDefault(sourceKey, DEFAULT_SOURCE));
        record.setDeliverPlatform(string(body.get("deliver"), "local"));
        record.setDeliverChatId(string(body.get("deliver_chat_id"), string(body.get("deliverChatId"), null)));
        record.setDeliverThreadId(string(body.get("deliver_thread_id"), string(body.get("deliverThreadId"), null)));
        record.setOriginJson(json(body.get("origin")));
        record.setSkillsJson(json(skills));
        record.setRepeatTimes(intValue(body.get("repeat"), 0));
        record.setRepeatCompleted(0);
        record.setScript(script);
        record.setWorkdir(string(body.get("workdir"), null));
        record.setNoAgent(noAgent);
        record.setContextFromJson(json(stringList(body.get("context_from"))));
        record.setEnabledToolsetsJson(json(stringList(body.get("enabled_toolsets"))));
        record.setWrapResponse(bool(body.get("wrap_response"), bool(body.get("wrapResponse"), true)));
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
            String schedule = string(body.get("schedule"), string(body.get("cronExpr"), record.getCronExpr()));
            CronSupport.validate(schedule);
            record.setCronExpr(schedule);
            record.setNextRunAt(CronSupport.nextRunAt(schedule, System.currentTimeMillis()));
            if (!STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
                record.setStatus(STATUS_ACTIVE);
            }
        }
        if (body.containsKey("deliver")) {
            record.setDeliverPlatform(string(body.get("deliver"), "local"));
        }
        if (body.containsKey("deliver_chat_id") || body.containsKey("deliverChatId")) {
            record.setDeliverChatId(string(body.get("deliver_chat_id"), string(body.get("deliverChatId"), null)));
        }
        if (body.containsKey("deliver_thread_id") || body.containsKey("deliverThreadId")) {
            record.setDeliverThreadId(string(body.get("deliver_thread_id"), string(body.get("deliverThreadId"), null)));
        }
        if (body.containsKey("skills")) {
            record.setSkillsJson(json(stringList(body.get("skills"))));
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
            String workdir = string(body.get("workdir"), null);
            validateWorkdir(workdir);
            record.setWorkdir(workdir);
        }
        if (body.containsKey("no_agent") || body.containsKey("noAgent")) {
            boolean noAgent = bool(body.get("no_agent"), bool(body.get("noAgent"), false));
            if (noAgent && StrUtil.isBlank(record.getScript())) {
                throw new IllegalStateException("no_agent requires script");
            }
            record.setNoAgent(noAgent);
        }
        if (body.containsKey("context_from")) {
            List<String> refs = stringList(body.get("context_from"));
            validateContextFrom(refs);
            record.setContextFromJson(json(refs));
        }
        if (body.containsKey("enabled_toolsets")) {
            record.setEnabledToolsetsJson(json(stringList(body.get("enabled_toolsets"))));
        }
        if (body.containsKey("wrap_response") || body.containsKey("wrapResponse")) {
            record.setWrapResponse(bool(body.get("wrap_response"), bool(body.get("wrapResponse"), true)));
        }
        if (body.containsKey("enabled")) {
            record.setStatus(bool(body.get("enabled"), true) ? STATUS_ACTIVE : STATUS_PAUSED);
            record.setPausedAt(bool(body.get("enabled"), true) ? 0L : System.currentTimeMillis());
        }
        return cronJobRepository.update(record);
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
        record.setNextRunAt(0L);
        return cronJobRepository.update(record);
    }

    public CronJobRecord remove(String jobId) throws Exception {
        CronJobRecord record = require(jobId);
        cronJobRepository.delete(jobId);
        return record;
    }

    public Map<String, Object> toView(CronJobRecord record) {
        Map<String, Object> schedule = new LinkedHashMap<String, Object>();
        schedule.put("kind", CronSupport.isOneShot(record.getCronExpr()) ? "once" : "cron");
        schedule.put("expr", record.getCronExpr());
        schedule.put("display", record.getCronExpr());

        Map<String, Object> repeat = new LinkedHashMap<String, Object>();
        repeat.put("times", record.getRepeatTimes() <= 0 ? null : Integer.valueOf(record.getRepeatTimes()));
        repeat.put("completed", Integer.valueOf(record.getRepeatCompleted()));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getJobId());
        result.put("job_id", record.getJobId());
        result.put("name", record.getName());
        result.put("prompt", record.getPrompt());
        result.put("prompt_preview", StrUtil.maxLength(record.getPrompt(), 120));
        result.put("schedule", schedule);
        result.put("schedule_display", record.getCronExpr());
        result.put("enabled", Boolean.valueOf(STATUS_ACTIVE.equalsIgnoreCase(record.getStatus())));
        result.put("state", state(record));
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
        result.put("context_from", parseList(record.getContextFromJson()));
        result.put("enabled_toolsets", parseList(record.getEnabledToolsetsJson()));
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

    private String state(CronJobRecord record) {
        if (STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
            return "paused";
        }
        if (STATUS_COMPLETED.equalsIgnoreCase(record.getStatus())) {
            return "completed";
        }
        return "scheduled";
    }

    private void validateContextFrom(List<String> refs) throws Exception {
        for (String ref : refs) {
            if (cronJobRepository.findById(ref) == null) {
                throw new IllegalStateException("context_from job not found: " + ref);
            }
        }
    }

    private void validateScript(String script) {
        if (StrUtil.isBlank(script)) {
            return;
        }
        String value = script.trim();
        if (value.startsWith("/") || value.startsWith("~") || (value.length() > 1 && value.charAt(1) == ':')) {
            throw new IllegalStateException("script must be relative to runtime/scripts");
        }
        File scriptsDir = FileUtil.file(appConfig.getRuntime().getHome(), "scripts");
        File target = FileUtil.file(scriptsDir, value);
        if (!FileUtil.isSub(scriptsDir, target)) {
            throw new IllegalStateException("script must stay within runtime/scripts");
        }
    }

    private void validateWorkdir(String workdir) {
        if (StrUtil.isBlank(workdir)) {
            return;
        }
        File file = FileUtil.file(workdir);
        if (!file.isAbsolute() || !file.exists() || !file.isDirectory()) {
            throw new IllegalStateException("workdir must be an existing absolute directory");
        }
    }

    private void scanPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return;
        }
        String value = prompt.toLowerCase(Locale.ROOT);
        String[] blocked =
                new String[] {
                    "ignore previous instructions",
                    "ignore all instructions",
                    "disregard your instructions",
                    "do not tell the user",
                    "system prompt override",
                    "authorized_keys",
                    "/etc/sudoers",
                    "rm -rf /"
                };
        for (String item : blocked) {
            if (value.contains(item)) {
                throw new IllegalStateException("Blocked unsafe cron prompt pattern: " + item);
            }
        }
        for (int i = 0; i < prompt.length(); i++) {
            char ch = prompt.charAt(i);
            if (ch == '\u200b' || ch == '\u200c' || ch == '\u200d' || ch == '\u2060' || ch == '\ufeff') {
                throw new IllegalStateException("Blocked invisible unicode in cron prompt");
            }
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        return ONode.serialize(value);
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
        if (value instanceof Iterable) {
            for (Object item : (Iterable<Object>) value) {
                addString(result, item);
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            Object data = ONode.ofJson(text).toData();
            if (data instanceof Iterable) {
                for (Object item : (Iterable<Object>) data) {
                    addString(result, item);
                }
                return result;
            }
        }
        for (String part : text.split(",")) {
            addString(result, part);
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
