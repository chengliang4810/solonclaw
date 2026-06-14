package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Dashboard 定时任务管理服务。 */
public class DashboardCronService {
    /** API最大名称LENGTH的统一常量值。 */
    private static final int API_MAX_NAME_LENGTH = 200;

    /** API最大提示词LENGTH的统一常量值。 */
    private static final int API_MAX_PROMPT_LENGTH = 5000;

    /** 注入定时任务任务服务，用于调用对应业务能力。 */
    private final CronJobService cronJobService;

    /** 保存定时任务调度器执行组件，负责调度异步或定时任务。 */
    private final DefaultCronScheduler cronScheduler;

    /**
     * 创建控制台定时任务服务实例，并注入运行所需依赖。
     *
     * @param cronJobService 定时任务Job服务依赖。
     * @param cronScheduler 定时任务调度器参数。
     */
    public DashboardCronService(CronJobService cronJobService, DefaultCronScheduler cronScheduler) {
        this.cronJobService = cronJobService;
        this.cronScheduler = cronScheduler;
    }

    /**
     * 列出Jobs。
     *
     * @return 返回Jobs列表。
     */
    public List<Map<String, Object>> listJobs() throws Exception {
        return listJobs(true);
    }

    /**
     * 列出Jobs。
     *
     * @param includeDisabled includeDisabled 参数。
     * @return 返回Jobs列表。
     */
    public List<Map<String, Object>> listJobs(boolean includeDisabled) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRecord record : cronJobService.listAll(includeDisabled)) {
            result.add(toDashboardView(record));
        }
        return result;
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param id 标识。
     * @return 返回get结果。
     */
    public Map<String, Object> get(String id) throws Exception {
        return toDashboardView(cronJobService.require(id));
    }

    /**
     * 执行inspect相关逻辑。
     *
     * @param id 标识。
     * @param limit 最大返回数量。
     * @return 返回inspect结果。
     */
    public Map<String, Object> inspect(String id, int limit) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("job", get(id));
        List<Map<String, Object>> runs = history(id, safeLimit);
        result.put("runs", runs);
        result.put("run_count", Integer.valueOf(runs.size()));
        result.put("limit", Integer.valueOf(safeLimit));
        return result;
    }

    /**
     * 执行guide相关逻辑。
     *
     * @return 返回guide结果。
     */
    public Map<String, Object> guide() {
        return cronJobService.guide();
    }

    /**
     * 执行策略相关逻辑。
     *
     * @return 返回策略结果。
     */
    public Map<String, Object> policy() {
        return cronJobService.policy();
    }

    /**
     * 执行nextJobs相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回next Jobs结果。
     */
    public List<Map<String, Object>> nextJobs(int limit) throws Exception {
        return nextJobs(limit, true);
    }

    /**
     * 执行nextJobs相关逻辑。
     *
     * @param limit 最大返回数量。
     * @param includeDisabled includeDisabled 参数。
     * @return 返回next Jobs结果。
     */
    public List<Map<String, Object>> nextJobs(int limit, boolean includeDisabled) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();
        for (CronJobRecord record : cronJobService.listAll(includeDisabled)) {
            if (record.getNextRunAt() <= 0L) {
                continue;
            }
            if ("PAUSED".equalsIgnoreCase(record.getStatus())
                    || "COMPLETED".equalsIgnoreCase(record.getStatus())) {
                continue;
            }
            jobs.add(record);
        }
        Collections.sort(
                jobs,
                new Comparator<CronJobRecord>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(CronJobRecord left, CronJobRecord right) {
                        long delta = left.getNextRunAt() - right.getNextRunAt();
                        if (delta < 0L) {
                            return -1;
                        }
                        if (delta > 0L) {
                            return 1;
                        }
                        String leftId = left.getJobId() == null ? "" : left.getJobId();
                        String rightId = right.getJobId() == null ? "" : right.getJobId();
                        return leftId.compareTo(rightId);
                    }
                });
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int count = Math.min(safeLimit, jobs.size());
        for (int i = 0; i < count; i++) {
            result.add(toDashboardView(jobs.get(i)));
        }
        return result;
    }

    /**
     * 执行状态相关逻辑。
     *
     * @param includeDisabled includeDisabled 参数。
     * @param limit 最大返回数量。
     * @return 返回状态。
     */
    public Map<String, Object> status(boolean includeDisabled, int limit) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        long now = System.currentTimeMillis();
        int total = 0;
        int active = 0;
        int paused = 0;
        int completed = 0;
        int due = 0;
        List<CronJobRecord> next = new ArrayList<CronJobRecord>();
        List<Map<String, Object>> recentFailures = new ArrayList<Map<String, Object>>();
        for (CronJobRecord record : cronJobService.listAll(true)) {
            String status = record.getStatus() == null ? "" : record.getStatus();
            boolean isPaused = "PAUSED".equalsIgnoreCase(status);
            boolean isCompleted = "COMPLETED".equalsIgnoreCase(status);
            if (!includeDisabled && (isPaused || isCompleted)) {
                continue;
            }
            total++;
            if (isPaused) {
                paused++;
            } else if (isCompleted) {
                completed++;
            } else {
                active++;
                if (record.getNextRunAt() > 0L) {
                    next.add(record);
                    if (record.getNextRunAt() <= now) {
                        due++;
                    }
                }
            }
            if (isFailed(record)) {
                recentFailures.add(failureView(record));
            }
        }
        Collections.sort(
                next,
                new Comparator<CronJobRecord>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(CronJobRecord left, CronJobRecord right) {
                        long delta = left.getNextRunAt() - right.getNextRunAt();
                        if (delta < 0L) {
                            return -1;
                        }
                        if (delta > 0L) {
                            return 1;
                        }
                        return safeId(left).compareTo(safeId(right));
                    }
                });

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("total", Integer.valueOf(total));
        result.put("active", Integer.valueOf(active));
        result.put("paused", Integer.valueOf(paused));
        result.put("completed", Integer.valueOf(completed));
        result.put("due", Integer.valueOf(due));
        result.put("include_disabled", Boolean.valueOf(includeDisabled));
        result.put("limit", Integer.valueOf(safeLimit));
        result.put("next", limitedViews(next, safeLimit));
        result.put("recent_failures", limitedMaps(recentFailures, safeLimit));
        return result;
    }

    /**
     * 执行create，服务于控制台定时任务主流程相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回create结果。
     */
    public Map<String, Object> create(Map<String, Object> body) throws Exception {
        CronJobRecord duplicate =
                cronJobService.findDuplicateCreateJob("MEMORY:dashboard:cron", body);
        if (duplicate != null) {
            Map<String, Object> view = toDashboardView(duplicate);
            view.put("deduped", Boolean.TRUE);
            return view;
        }
        Map<String, Object> view =
                toDashboardView(cronJobService.create("MEMORY:dashboard:cron", body));
        view.put("deduped", Boolean.FALSE);
        return view;
    }

    /**
     * 执行apiCreate相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回api Create结果。
     */
    public Map<String, Object> apiCreate(Map<String, Object> body) throws Exception {
        validateApiCreate(body);
        return create(body);
    }

    /**
     * 执行更新相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回更新结果。
     */
    public Map<String, Object> update(String id, Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.update(id, body));
    }

    /**
     * 执行api补丁相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回api Patch结果。
     */
    public Map<String, Object> apiPatch(String id, Map<String, Object> body) throws Exception {
        Map<String, Object> updates = sanitizeApiPatch(body);
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("No valid fields to update");
        }
        validateApiPatch(updates);
        return update(id, updates);
    }

    /**
     * 执行pause相关逻辑。
     *
     * @param id 标识。
     * @return 返回pause结果。
     */
    public Map<String, Object> pause(String id) throws Exception {
        return pause(id, Collections.<String, Object>emptyMap());
    }

    /**
     * 执行pause相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回pause结果。
     */
    public Map<String, Object> pause(String id, Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.pause(id, pauseReason(body)));
    }

    /**
     * 执行resume相关逻辑。
     *
     * @param id 标识。
     * @return 返回resume结果。
     */
    public Map<String, Object> resume(String id) throws Exception {
        return toDashboardView(cronJobService.resume(id));
    }

    /**
     * 执行trigger相关逻辑。
     *
     * @param id 标识。
     * @return 返回trigger结果。
     */
    public Map<String, Object> trigger(String id) throws Exception {
        runOrTrigger(id, "manual");
        return get(id);
    }

    /**
     * 执行trigger相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回trigger结果。
     */
    public Map<String, Object> trigger(String id, Map<String, Object> body) throws Exception {
        runOrTrigger(id, manualTriggerType(body));
        return get(id);
    }

    /**
     * 执行重试相关逻辑。
     *
     * @param id 标识。
     * @return 返回retry结果。
     */
    public Map<String, Object> retry(String id) throws Exception {
        runOrTrigger(id, "retry");
        return get(id);
    }

    /**
     * 执行重试相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回retry结果。
     */
    public Map<String, Object> retry(String id, Map<String, Object> body) throws Exception {
        runOrTrigger(id, retryTriggerType(body));
        return get(id);
    }

    /**
     * 执行api运行相关逻辑。
     *
     * @param id 标识。
     * @return 返回api运行结果。
     */
    public Map<String, Object> apiRun(String id) throws Exception {
        return apiRun(id, Collections.<String, Object>emptyMap());
    }

    /**
     * 执行api运行相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回api运行结果。
     */
    public Map<String, Object> apiRun(String id, Map<String, Object> body) throws Exception {
        runOrTrigger(id, manualTriggerType(body));
        return get(id);
    }

    /**
     * 执行api重试相关逻辑。
     *
     * @param id 标识。
     * @return 返回api Retry结果。
     */
    public Map<String, Object> apiRetry(String id) throws Exception {
        return apiRetry(id, Collections.<String, Object>emptyMap());
    }

    /**
     * 执行api重试相关逻辑。
     *
     * @param id 标识。
     * @param body 请求体或消息正文内容。
     * @return 返回api Retry结果。
     */
    public Map<String, Object> apiRetry(String id, Map<String, Object> body) throws Exception {
        runOrTrigger(id, retryTriggerType(body));
        return get(id);
    }

    /**
     * 运行Or Trigger。
     *
     * @param id 标识。
     * @param triggerType trigger类型参数。
     */
    private void runOrTrigger(String id, String triggerType) throws Exception {
        if (cronScheduler == null) {
            cronJobService.trigger(id, triggerType);
            return;
        }
        cronScheduler.runNow(id, triggerType);
    }

    /**
     * 执行manualTrigger类型相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回manual Trigger类型结果。
     */
    private String manualTriggerType(Map<String, Object> body) {
        return customTriggerType(body, "manual");
    }

    /**
     * 重试Trigger类型。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回retry Trigger类型结果。
     */
    private String retryTriggerType(Map<String, Object> body) {
        return customTriggerType(body, "retry");
    }

    /**
     * 执行customTrigger类型相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param fallback 兜底参数。
     * @return 返回custom Trigger类型结果。
     */
    private String customTriggerType(Map<String, Object> body, String fallback) {
        if (body == null || body.isEmpty()) {
            return fallback;
        }
        Object raw =
                body.containsKey("trigger_type")
                        ? body.get("trigger_type")
                        : body.get("triggerType");
        if (raw == null) {
            raw = body.get("reason");
        }
        String normalized =
                cronJobService.normalizeTriggerType(
                        raw == null ? null : String.valueOf(raw), fallback);
        if ("scheduled".equals(normalized)) {
            return fallback;
        }
        if ("retry".equals(fallback) && "manual".equals(normalized)) {
            return "retry";
        }
        return normalized;
    }

    /**
     * 执行delete，服务于控制台定时任务主流程相关逻辑。
     *
     * @param id 标识。
     * @return 返回delete结果。
     */
    public Map<String, Object> delete(String id) throws Exception {
        cronJobService.remove(id);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    /**
     * 执行历史相关逻辑。
     *
     * @param id 标识。
     * @param limit 最大返回数量。
     * @return 返回历史结果。
     */
    public List<Map<String, Object>> history(String id, int limit) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRunRecord record : cronJobService.history(id, limit)) {
            Map<String, Object> view =
                    new LinkedHashMap<String, Object>(cronJobService.runToView(record));
            convertTime(view, "started_at");
            convertTime(view, "finished_at");
            result.add(view);
        }
        return result;
    }

    /**
     * 转换为控制台视图。
     *
     * @param record 记录参数。
     * @return 返回转换后的控制台视图。
     */
    private Map<String, Object> toDashboardView(CronJobRecord record) {
        Map<String, Object> view = new LinkedHashMap<String, Object>(cronJobService.toView(record));
        convertTime(view, "created_at");
        convertTime(view, "last_run_at");
        convertTime(view, "next_run_at");
        convertTime(view, "paused_at");
        return view;
    }

    /**
     * 转换时间。
     *
     * @param view view 参数。
     * @param key 配置键或映射键。
     */
    private void convertTime(Map<String, Object> view, String key) {
        Object value = view.get(key);
        if (value instanceof Number) {
            long millis = ((Number) value).longValue();
            view.put(key, millis <= 0 ? null : iso(millis));
        }
    }

    /**
     * 执行iso相关逻辑。
     *
     * @param epochMillis epochMillis 参数。
     * @return 返回iso结果。
     */
    private String iso(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
    }

    /**
     * 判断是否Failed。
     *
     * @param record 记录参数。
     * @return 如果Failed满足条件则返回 true，否则返回 false。
     */
    private boolean isFailed(CronJobRecord record) {
        String lastStatus = record.getLastStatus() == null ? "" : record.getLastStatus();
        return "error".equalsIgnoreCase(lastStatus)
                || (record.getLastError() != null && record.getLastError().trim().length() > 0)
                || (record.getLastDeliveryError() != null
                        && record.getLastDeliveryError().trim().length() > 0);
    }

    /**
     * 执行failure视图相关逻辑。
     *
     * @param record 记录参数。
     * @return 返回failure视图。
     */
    private Map<String, Object> failureView(CronJobRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getJobId());
        result.put("job_id", record.getJobId());
        result.put("name", record.getName());
        result.put("last_status", record.getLastStatus());
        result.put("last_error", safeText(record.getLastError()));
        result.put("last_delivery_error", safeText(record.getLastDeliveryError()));
        result.put("diagnostics", cronJobService.toView(record).get("diagnostics"));
        result.put("last_run_at", record.getLastRunAt() <= 0L ? null : iso(record.getLastRunAt()));
        return result;
    }

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Text结果。
     */
    private String safeText(String value) {
        return SecretRedactor.redact(value);
    }

    /**
     * 执行limitedViews相关逻辑。
     *
     * @param records records 参数。
     * @param limit 最大返回数量。
     * @return 返回limited Views结果。
     */
    private List<Map<String, Object>> limitedViews(List<CronJobRecord> records, int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int count = Math.min(limit, records.size());
        for (int i = 0; i < count; i++) {
            result.add(toDashboardView(records.get(i)));
        }
        return result;
    }

    /**
     * 执行limited映射s相关逻辑。
     *
     * @param records records 参数。
     * @param limit 最大返回数量。
     * @return 返回limited Maps结果。
     */
    private List<Map<String, Object>> limitedMaps(List<Map<String, Object>> records, int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int count = Math.min(limit, records.size());
        for (int i = 0; i < count; i++) {
            result.add(records.get(i));
        }
        return result;
    }

    /**
     * 生成安全展示用的标识。
     *
     * @param record 记录参数。
     * @return 返回safe标识。
     */
    private String safeId(CronJobRecord record) {
        return record.getJobId() == null ? "" : record.getJobId();
    }

    /**
     * 校验Api Create。
     *
     * @param body 请求体或消息正文内容。
     */
    private void validateApiCreate(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Object name = body.get("name");
        if (name != null && String.valueOf(name).trim().length() > API_MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most 200 characters");
        }
        Object schedule =
                body.containsKey("schedule") ? body.get("schedule") : body.get("cronExpr");
        if (schedule == null || String.valueOf(schedule).trim().length() == 0) {
            throw new IllegalArgumentException("schedule is required");
        }
        validatePromptLength(body.get("prompt"));
        validateApiRepeat(body.get("repeat"), true);
    }

    /**
     * 执行pause原因相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回pause Reason结果。
     */
    private String pauseReason(Map<String, Object> body) {
        if (body == null) {
            return "paused from dashboard";
        }
        Object value = body.containsKey("reason") ? body.get("reason") : body.get("paused_reason");
        String reason = value == null ? "" : String.valueOf(value).trim();
        return reason.length() == 0 ? "paused from dashboard" : reason;
    }

    /**
     * 校验Api Patch。
     *
     * @param updates updates 参数。
     */
    private void validateApiPatch(Map<String, Object> updates) {
        if (updates.containsKey("name")
                && updates.get("name") != null
                && String.valueOf(updates.get("name")).trim().length() > API_MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most 200 characters");
        }
        validatePromptLength(updates.get("prompt"));
        validateApiRepeat(updates.get("repeat"), true);
    }

    /**
     * 校验提示词Length。
     *
     * @param prompt 提示词参数。
     */
    private void validatePromptLength(Object prompt) {
        if (prompt != null && String.valueOf(prompt).length() > API_MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt must be at most 5000 characters");
        }
    }

    /**
     * 校验Api Repeat。
     *
     * @param repeat repeat 参数。
     * @param allowZero allowZero开关值。
     */
    private void validateApiRepeat(Object repeat, boolean allowZero) {
        if (repeat == null) {
            return;
        }
        int value;
        if (repeat instanceof Number) {
            value = ((Number) repeat).intValue();
        } else {
            value = Integer.parseInt(String.valueOf(repeat));
        }
        if (value < 0 || (!allowZero && value == 0)) {
            throw new IllegalArgumentException(
                    allowZero
                            ? "repeat must be a non-negative integer"
                            : "repeat must be a positive integer");
        }
    }

    /**
     * 清理Api Patch。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回Api Patch结果。
     */
    private Map<String, Object> sanitizeApiPatch(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (body == null) {
            return result;
        }
        copyIfPresent(body, result, "name");
        copyIfPresent(body, result, "schedule");
        copyIfPresent(body, result, "cronExpr");
        copyIfPresent(body, result, "prompt");
        copyIfPresent(body, result, "deliver");
        copyIfPresent(body, result, "deliver_chat_id");
        copyIfPresent(body, result, "deliverChatId");
        copyIfPresent(body, result, "deliver_thread_id");
        copyIfPresent(body, result, "deliverThreadId");
        copyIfPresent(body, result, "origin");
        copyIfPresent(body, result, "skill");
        copyIfPresent(body, result, "skills");
        copyIfPresent(body, result, "add_skill");
        copyIfPresent(body, result, "addSkill");
        copyIfPresent(body, result, "add_skills");
        copyIfPresent(body, result, "addSkills");
        copyIfPresent(body, result, "remove_skill");
        copyIfPresent(body, result, "removeSkill");
        copyIfPresent(body, result, "remove_skills");
        copyIfPresent(body, result, "removeSkills");
        copyIfPresent(body, result, "clear_skills");
        copyIfPresent(body, result, "clearSkills");
        copyIfPresent(body, result, "skills_delta");
        copyIfPresent(body, result, "skillsDelta");
        copyIfPresent(body, result, "repeat");
        copyIfPresent(body, result, "script");
        copyIfPresent(body, result, "workdir");
        copyIfPresent(body, result, "no_agent");
        copyIfPresent(body, result, "noAgent");
        copyIfPresent(body, result, "context_from");
        copyIfPresent(body, result, "depends_on");
        copyIfPresent(body, result, "enabled_toolsets");
        copyIfPresent(body, result, "enabledToolsets");
        copyIfPresent(body, result, "model");
        copyIfPresent(body, result, "provider");
        copyIfPresent(body, result, "base_url");
        copyIfPresent(body, result, "baseUrl");
        copyIfPresent(body, result, "wrap_response");
        copyIfPresent(body, result, "wrapResponse");
        copyIfPresent(body, result, "enabled");
        copyIfPresent(body, result, "status");
        copyIfPresent(body, result, "state");
        copyIfPresent(body, result, "paused_reason");
        copyIfPresent(body, result, "pausedReason");
        return result;
    }

    /**
     * 复制If Present。
     *
     * @param source 来源参数。
     * @param target target 参数。
     * @param key 配置键或映射键。
     */
    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
