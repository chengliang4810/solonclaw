package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.core.model.CronJobRecord;
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
    private final CronJobService cronJobService;
    private final DefaultCronScheduler cronScheduler;

    public DashboardCronService(CronJobService cronJobService, DefaultCronScheduler cronScheduler) {
        this.cronJobService = cronJobService;
        this.cronScheduler = cronScheduler;
    }

    public List<Map<String, Object>> listJobs() throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (CronJobRecord record : cronJobService.listAll(true)) {
            result.add(toDashboardView(record));
        }
        return result;
    }

    public Map<String, Object> create(Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.create("MEMORY:dashboard:cron", body));
    }

    public Map<String, Object> update(String id, Map<String, Object> body) throws Exception {
        return toDashboardView(cronJobService.update(id, body));
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

    public Map<String, Object> delete(String id) throws Exception {
        cronJobService.remove(id);
        return Collections.<String, Object>singletonMap("ok", true);
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
}
