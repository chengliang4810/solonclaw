package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
import com.jimuqu.solon.claw.core.model.CronJobRunRecord;
import com.jimuqu.solon.claw.scheduler.CronJobService;
import com.jimuqu.solon.claw.scheduler.DefaultCronScheduler;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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
        return toDashboardView(cronJobService.pause(id, "paused from dashboard"));
    }

    public Map<String, Object> resume(String id) throws Exception {
        return toDashboardView(cronJobService.resume(id));
    }

    public Map<String, Object> trigger(String id) throws Exception {
        cronScheduler.runNow(id);
        return Collections.<String, Object>singletonMap("ok", true);
    }

    public Map<String, Object> apiRun(String id) throws Exception {
        cronScheduler.runNow(id);
        return get(id);
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
        validateApiRepeat(body.get("repeat"));
    }

    private void validateApiPatch(Map<String, Object> updates) {
        if (updates.containsKey("name")
                && updates.get("name") != null
                && String.valueOf(updates.get("name")).trim().length() > API_MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most 200 characters");
        }
        validatePromptLength(updates.get("prompt"));
        validateApiRepeat(updates.get("repeat"));
    }

    private void validatePromptLength(Object prompt) {
        if (prompt != null && String.valueOf(prompt).length() > API_MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("prompt must be at most 5000 characters");
        }
    }

    private void validateApiRepeat(Object repeat) {
        if (repeat == null) {
            return;
        }
        int value;
        if (repeat instanceof Number) {
            value = ((Number) repeat).intValue();
        } else {
            value = Integer.parseInt(String.valueOf(repeat));
        }
        if (value <= 0) {
            throw new IllegalArgumentException("repeat must be a positive integer");
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
        return result;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
