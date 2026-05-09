package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
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
    private static final int API_MAX_NAME_LENGTH = 200;
    private static final int API_MAX_PROMPT_LENGTH = 5000;

    private final CronJobService cronJobService;
    private final DefaultCronScheduler cronScheduler;

    public DashboardCronService(CronJobService cronJobService, DefaultCronScheduler cronScheduler) {
        this.cronJobService = cronJobService;
        this.cronScheduler = cronScheduler;
    }

    public List<Map<String, Object>> listJobs() throws Exception {
        return listJobs(true);
    }

    public List<Map<String, Object>> listJobs(boolean includeDisabled) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRecord record : cronJobService.listAll(includeDisabled)) {
            result.add(toDashboardView(record));
        }
        return result;
    }

    public Map<String, Object> get(String id) throws Exception {
        return toDashboardView(cronJobService.require(id));
    }

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

    public Map<String, Object> guide() {
        return cronJobService.guide();
    }

    public List<Map<String, Object>> nextJobs(int limit) throws Exception {
        return nextJobs(limit, true);
    }

    public List<Map<String, Object>> nextJobs(int limit, boolean includeDisabled) throws Exception {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 50);
        List<CronJobRecord> jobs = new ArrayList<CronJobRecord>();
        for (CronJobRecord record : cronJobService.listAll(includeDisabled)) {
            if (record.getNextRunAt() <= 0L) {
                continue;
            }
            if ("PAUSED".equalsIgnoreCase(record.getStatus()) || "COMPLETED".equalsIgnoreCase(record.getStatus())) {
                continue;
            }
            jobs.add(record);
        }
        Collections.sort(
                jobs,
                new Comparator<CronJobRecord>() {
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

    public Map<String, Object> create(Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.create("MEMORY:dashboard:cron", body));
    }

    public Map<String, Object> apiCreate(Map<String, Object> body) throws Exception {
        validateApiCreate(body);
        return create(body);
    }

    public Map<String, Object> update(String id, Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.update(id, body));
    }

    public Map<String, Object> apiPatch(String id, Map<String, Object> body) throws Exception {
        Map<String, Object> updates = sanitizeApiPatch(body);
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("No valid fields to update");
        }
        validateApiPatch(updates);
        return update(id, updates);
    }

    public Map<String, Object> pause(String id) throws Exception {
        return pause(id, Collections.<String, Object>emptyMap());
    }

    public Map<String, Object> pause(String id, Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.pause(id, pauseReason(body)));
    }

    public Map<String, Object> resume(String id) throws Exception {
        return toDashboardView(cronJobService.resume(id));
    }

    public Map<String, Object> trigger(String id) throws Exception {
        runOrTrigger(id);
        return get(id);
    }

    public Map<String, Object> apiRun(String id) throws Exception {
        runOrTrigger(id);
        return get(id);
    }

    private void runOrTrigger(String id) throws Exception {
        if (cronScheduler == null) {
            cronJobService.trigger(id);
            return;
        }
        cronScheduler.runNow(id);
    }

    public Map<String, Object> delete(String id) throws Exception {
        cronJobService.remove(id);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    public List<Map<String, Object>> history(String id, int limit) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRunRecord record : cronJobService.history(id, limit)) {
            Map<String, Object> view = new LinkedHashMap<String, Object>(cronJobService.runToView(record));
            convertTime(view, "started_at");
            convertTime(view, "finished_at");
            result.add(view);
        }
        return result;
    }

    private Map<String, Object> toDashboardView(CronJobRecord record) {
        Map<String, Object> view = new LinkedHashMap<String, Object>(cronJobService.toView(record));
        convertTime(view, "created_at");
        convertTime(view, "last_run_at");
        convertTime(view, "next_run_at");
        convertTime(view, "paused_at");
        return view;
    }

    private void convertTime(Map<String, Object> view, String key) {
        Object value = view.get(key);
        if (value instanceof Number) {
            long millis = ((Number) value).longValue();
            view.put(key, millis <= 0 ? null : iso(millis));
        }
    }

    private String iso(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(epochMillis));
    }

    private boolean isFailed(CronJobRecord record) {
        String lastStatus = record.getLastStatus() == null ? "" : record.getLastStatus();
        return "error".equalsIgnoreCase(lastStatus)
                || (record.getLastError() != null && record.getLastError().trim().length() > 0)
                || (record.getLastDeliveryError() != null && record.getLastDeliveryError().trim().length() > 0);
    }

    private Map<String, Object> failureView(CronJobRecord record) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", record.getJobId());
        result.put("job_id", record.getJobId());
        result.put("name", record.getName());
        result.put("last_status", record.getLastStatus());
        result.put("last_error", record.getLastError());
        result.put("last_delivery_error", record.getLastDeliveryError());
        result.put("last_run_at", record.getLastRunAt() <= 0L ? null : iso(record.getLastRunAt()));
        return result;
    }

    private List<Map<String, Object>> limitedViews(List<CronJobRecord> records, int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int count = Math.min(limit, records.size());
        for (int i = 0; i < count; i++) {
            result.add(toDashboardView(records.get(i)));
        }
        return result;
    }

    private List<Map<String, Object>> limitedMaps(List<Map<String, Object>> records, int limit) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int count = Math.min(limit, records.size());
        for (int i = 0; i < count; i++) {
            result.add(records.get(i));
        }
        return result;
    }

    private String safeId(CronJobRecord record) {
        return record.getJobId() == null ? "" : record.getJobId();
    }

    private void validateApiCreate(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Object name = body.get("name");
        if (name == null || String.valueOf(name).trim().length() == 0) {
            throw new IllegalArgumentException("name is required");
        }
        if (String.valueOf(name).trim().length() > API_MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most 200 characters");
        }
        Object schedule = body.containsKey("schedule") ? body.get("schedule") : body.get("cronExpr");
        if (schedule == null || String.valueOf(schedule).trim().length() == 0) {
            throw new IllegalArgumentException("schedule is required");
        }
        validatePromptLength(body.get("prompt"));
        validateApiRepeat(body.get("repeat"), false);
    }

    private String pauseReason(Map<String, Object> body) {
        if (body == null) {
            return "paused from dashboard";
        }
        Object value = body.containsKey("reason") ? body.get("reason") : body.get("paused_reason");
        String reason = value == null ? "" : String.valueOf(value).trim();
        return reason.length() == 0 ? "paused from dashboard" : reason;
    }

    private void validateApiPatch(Map<String, Object> updates) {
        if (updates.containsKey("name")
                && updates.get("name") != null
                && String.valueOf(updates.get("name")).trim().length() > API_MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most 200 characters");
        }
        validatePromptLength(updates.get("prompt"));
        validateApiRepeat(updates.get("repeat"), true);
    }

    private void validatePromptLength(Object prompt) {
        if (prompt != null && String.valueOf(prompt).length() > API_MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt must be at most 5000 characters");
        }
    }

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
                    allowZero ? "repeat must be a non-negative integer" : "repeat must be a positive integer");
        }
    }

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
        copyIfPresent(body, result, "repeat");
        copyIfPresent(body, result, "script");
        copyIfPresent(body, result, "workdir");
        copyIfPresent(body, result, "no_agent");
        copyIfPresent(body, result, "noAgent");
        copyIfPresent(body, result, "context_from");
        copyIfPresent(body, result, "depends_on");
        copyIfPresent(body, result, "enabled_toolsets");
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

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
