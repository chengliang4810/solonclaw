package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.solon.annotation.Param;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 定时任务接口。 */
@Controller
public class DashboardCronController {
    private final DashboardCronService cronService;

    public DashboardCronController(DashboardCronService cronService) {
        this.cronService = cronService;
    }

    @Mapping(value = "/api/cron/jobs", method = MethodType.GET)
    public List<Map<String, Object>> jobs() throws Exception {
        return cronService.listJobs();
    }

    @Mapping(value = "/api/jobs", method = MethodType.GET)
    public Map<String, Object> apiJobs(Context context) throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        return apiJobsResponse(cronService.listJobs(includeDisabled));
    }

    @Mapping(value = "/api/cron/jobs", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        return DashboardResponse.ok(
                cronService.create(
                        body(context)));
    }

    @Mapping(value = "/api/jobs", method = MethodType.POST)
    public Map<String, Object> apiCreate(Context context) throws Exception {
        try {
            return apiJobResponse(cronService.apiCreate(body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/jobs/{id}", method = MethodType.GET)
    public Map<String, Object> apiGet(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.get(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(404);
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/cron/jobs/{id}", method = MethodType.PUT)
    public Map<String, Object> update(String id, Context context) throws Exception {
        return DashboardResponse.ok(
                cronService.update(
                        id,
                        body(context)));
    }

    @Mapping(value = "/api/jobs/{id}", method = MethodType.PATCH)
    public Map<String, Object> apiPatch(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.apiPatch(id, body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            if (isNotFound(e)) {
                context.status(404);
            } else {
                context.status(400);
            }
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/cron/jobs/{id}/pause", method = MethodType.POST)
    public Map<String, Object> pause(String id) throws Exception {
        return DashboardResponse.ok(cronService.pause(id));
    }

    @Mapping(value = "/api/jobs/{id}/pause", method = MethodType.POST)
    public Map<String, Object> apiPause(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.pause(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(404);
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/cron/jobs/{id}/resume", method = MethodType.POST)
    public Map<String, Object> resume(String id) throws Exception {
        return DashboardResponse.ok(cronService.resume(id));
    }

    @Mapping(value = "/api/jobs/{id}/resume", method = MethodType.POST)
    public Map<String, Object> apiResume(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.resume(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(404);
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/cron/jobs/{id}/trigger", method = MethodType.POST)
    public Map<String, Object> trigger(String id) throws Exception {
        return DashboardResponse.ok(cronService.trigger(id));
    }

    @Mapping(value = "/api/jobs/{id}/run", method = MethodType.POST)
    public Map<String, Object> apiRun(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.apiRun(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(404);
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/cron/jobs/{id}/runs", method = MethodType.GET)
    public Map<String, Object> history(String id, @Param(defaultValue = "20") Integer limit)
            throws Exception {
        List<Map<String, Object>> runs = cronService.history(id, limit == null ? 20 : limit.intValue());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("job_id", id);
        data.put("runs", runs);
        data.put("count", Integer.valueOf(runs.size()));
        return DashboardResponse.ok(data);
    }

    @Mapping(value = "/api/cron/jobs/{id}", method = MethodType.DELETE)
    public Map<String, Object> delete(String id) throws Exception {
        return DashboardResponse.ok(cronService.delete(id));
    }

    @Mapping(value = "/api/jobs/{id}", method = MethodType.DELETE)
    public Map<String, Object> apiDelete(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            cronService.delete(id);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("ok", Boolean.TRUE);
            return result;
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(404);
            return apiError(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Context context) throws Exception {
        String raw = context.body();
        if (raw == null || raw.trim().length() == 0) {
            return new LinkedHashMap<String, Object>();
        }
        Object data = ONode.ofJson(raw).toData();
        return data instanceof Map ? (Map<String, Object>) data : new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> apiJobsResponse(List<Map<String, Object>> jobs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("jobs", jobs);
        return result;
    }

    private Map<String, Object> apiJobResponse(Map<String, Object> job) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("job", job);
        return result;
    }

    private Map<String, Object> apiError(String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("error", message == null ? "" : message);
        return result;
    }

    private void validateApiJobId(String id) {
        if (id == null || !id.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Invalid job id");
        }
    }

    private boolean isNotFound(IllegalStateException e) {
        return e.getMessage() != null && e.getMessage().startsWith("Job not found:");
    }
}
