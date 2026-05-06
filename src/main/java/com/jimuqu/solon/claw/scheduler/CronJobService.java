package com.jimuqu.solon.claw.scheduler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.core.repository.CronJobRepository;
import com.jimuqu.solon.claw.support.CronSupport;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** Hermes 风格 Cron 任务管理服务。 */
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
        String schedule = string(body.get("schedule"), string(body.get("cronExpr"), null));
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
        validateWorkdir(string(body.get("workdir"), null));
        validateContextFrom(stringList(body.get("context_from")));

        long now = System.currentTimeMillis();
        CronJobRecord record = new CronJobRecord();
        record.setJobId(IdSupport.newId());
        record.setName(StrUtil.blankToDefault(string(body.get("name"), null), "job-" + record.getJobId().substring(0, 8)));
        record.setCronExpr(schedule);
        record.setPrompt(prompt);
        record.setSourceKey(StrUtil.blankToDefault(sourceKey, DEFAULT_SOURCE));
        record.setDeliverPlatform(deliverValue(body.get("deliver"), defaultDeliver(body)));
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
            String schedule = string(body.get("schedule"), string(body.get("cronExpr"), record.getCronExpr()));
            CronSupport.validate(schedule);
            record.setCronExpr(schedule);
            record.setNextRunAt(CronSupport.nextRunAt(schedule, System.currentTimeMillis()));
            if (!STATUS_PAUSED.equalsIgnoreCase(record.getStatus())) {
                record.setStatus(STATUS_ACTIVE);
            }
        }
        if (body.containsKey("deliver")) {
            record.setDeliverPlatform(deliverValue(body.get("deliver"), "local"));
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
                            record.getModel(),
                            record.getProvider(),
                            record.getBaseUrl());
            applyModelPin(record, modelOverride.model, modelOverride.provider, modelOverride.baseUrl);
        }
        if (body.containsKey("wrap_response") || body.containsKey("wrapResponse")) {
            record.setWrapResponse(bool(body.get("wrap_response"), bool(body.get("wrapResponse"), true)));
        }
        if (body.containsKey("enabled")) {
            record.setStatus(bool(body.get("enabled"), true) ? STATUS_ACTIVE : STATUS_PAUSED);
            record.setPausedAt(bool(body.get("enabled"), true) ? 0L : System.currentTimeMillis());
        }
        if (record.isNoAgent() && StrUtil.isBlank(record.getScript())) {
            throw new IllegalStateException("no_agent requires script");
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

    public Map<String, Object> runToView(CronJobRunRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("run_id", record.getRunId());
        result.put("job_id", record.getJobId());
        result.put("source_key", record.getSourceKey());
        result.put("trigger", StrUtil.blankToDefault(record.getTriggerType(), "scheduled"));
        result.put("attempt", Integer.valueOf(record.getAttempt()));
        result.put("started_at", record.getStartedAt() <= 0 ? null : Long.valueOf(record.getStartedAt()));
        result.put("finished_at", record.getFinishedAt() <= 0 ? null : Long.valueOf(record.getFinishedAt()));
        result.put("status", record.getStatus());
        result.put("output", record.getOutput());
        result.put("error", record.getError());
        result.put("delivery_error", record.getDeliveryError());
        result.put("summary", record.getSummary());
        return result;
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
        SecurityPolicyService.FileVerdict textVerdict =
                SecurityPolicyService.checkWorkdirText(workdir);
        if (!textVerdict.isAllowed()) {
            throw new IllegalStateException(
                    "workdir blocked by security policy: "
                            + textVerdict.getPath()
                            + " - "
                            + textVerdict.getMessage());
        }
        File file = FileUtil.file(workdir);
        if (!file.isAbsolute() || !file.exists() || !file.isDirectory()) {
            throw new IllegalStateException("workdir must be an existing absolute directory");
        }
        SecurityPolicyService.FileVerdict verdict =
                new SecurityPolicyService(appConfig).checkPath(file.getAbsolutePath(), false);
        if (!verdict.isAllowed()) {
            throw new IllegalStateException(
                    "workdir blocked by security policy: "
                            + verdict.getPath()
                            + " - "
                            + verdict.getMessage());
        }
    }

    private void scanPrompt(String prompt) {
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
