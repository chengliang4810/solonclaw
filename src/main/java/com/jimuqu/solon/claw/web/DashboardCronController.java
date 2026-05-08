package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jimuqu.solon.claw.support.SecretRedactor;
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

    @Mapping(value = "/api/cron/jobs/next", method = MethodType.GET)
    public Map<String, Object> next(@Param(defaultValue = "5") Integer limit) throws Exception {
        List<Map<String, Object>> jobs = cronService.nextJobs(limit == null ? 5 : limit.intValue());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("jobs", jobs);
        data.put("count", Integer.valueOf(jobs.size()));
        data.put("limit", Integer.valueOf(limit == null ? 5 : limit.intValue()));
        return DashboardResponse.ok(data);
    }

    @Mapping(value = "/api/jobs/next", method = MethodType.GET)
    public Map<String, Object> apiNext(@Param(defaultValue = "5") Integer limit) throws Exception {
        return apiJobsResponse(cronService.nextJobs(limit == null ? 5 : limit.intValue()));
    }

    @Mapping(value = "/api/cron/jobs", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.create(body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        }
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

    @Mapping(value = "/api/cron/jobs/{id}", method = MethodType.GET)
    public Map<String, Object> get(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.get(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
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
        try {
            return DashboardResponse.ok(cronService.update(id, body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
    }

    @Mapping(value = "/api/jobs/{id}", method = MethodType.PATCH)
    public Map<String, Object> apiPatch(String id, Context context) throws Exception {
        return apiUpdate(id, context);
    }

    @Mapping(value = "/api/jobs/{id}", method = MethodType.PUT)
    public Map<String, Object> apiPut(String id, Context context) throws Exception {
        return apiUpdate(id, context);
    }

    private Map<String, Object> apiUpdate(String id, Context context) throws Exception {
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
    public Map<String, Object> pause(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.pause(id, body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
    }

    @Mapping(value = "/api/jobs/{id}/pause", method = MethodType.POST)
    public Map<String, Object> apiPause(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.pause(id, body(context)));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return apiError(e.getMessage());
        } catch (IllegalStateException e) {
            context.status(404);
            return apiError(e.getMessage());
        }
    }

    @Mapping(value = "/api/cron/jobs/{id}/resume", method = MethodType.POST)
    public Map<String, Object> resume(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.resume(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
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
    public Map<String, Object> trigger(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.trigger(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
    }

    @Mapping(value = "/api/jobs/{id}/run", method = MethodType.POST)
    public Map<String, Object> apiRun(String id, Context context) throws Exception {
        return apiRunJob(id, context);
    }

    @Mapping(value = "/api/jobs/{id}/trigger", method = MethodType.POST)
    public Map<String, Object> apiTrigger(String id, Context context) throws Exception {
        return apiRunJob(id, context);
    }

    private Map<String, Object> apiRunJob(String id, Context context) throws Exception {
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
    public Map<String, Object> history(String id, @Param(defaultValue = "20") Integer limit, Context context)
            throws Exception {
        try {
            List<Map<String, Object>> runs = cronService.history(id, limit == null ? 20 : limit.intValue());
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("job_id", id);
            data.put("runs", runs);
            data.put("count", Integer.valueOf(runs.size()));
            return DashboardResponse.ok(data);
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
    }

    @Mapping(value = "/api/jobs/{id}/runs", method = MethodType.GET)
    public Map<String, Object> apiHistory(String id, @Param(defaultValue = "20") Integer limit, Context context)
            throws Exception {
        return apiHistoryData(id, limit, context);
    }

    @Mapping(value = "/api/jobs/{id}/history", method = MethodType.GET)
    public Map<String, Object> apiHistoryAlias(String id, @Param(defaultValue = "20") Integer limit, Context context)
            throws Exception {
        return apiHistoryData(id, limit, context);
    }

    private Map<String, Object> apiHistoryData(String id, Integer limit, Context context) throws Exception {
        try {
            validateApiJobId(id);
            List<Map<String, Object>> runs = cronService.history(id, limit == null ? 20 : limit.intValue());
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("job_id", id);
            data.put("runs", runs);
            data.put("count", Integer.valueOf(runs.size()));
            return data;
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

    @Mapping(value = "/api/cron/jobs/{id}", method = MethodType.DELETE)
    public Map<String, Object> delete(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.delete(id));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
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
        result.put("error", message == null ? "" : SecretRedactor.redact(message, 1000));
        return result;
    }

    private Map<String, Object> dashboardError(String code, Exception e) {
        String message;
        if (e == null) {
            message = "";
        } else if (e.getMessage() == null || e.getMessage().trim().length() == 0) {
            message = e.getClass().getSimpleName();
        } else {
            message = e.getMessage();
        }
        return DashboardResponse.error(code, message);
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
