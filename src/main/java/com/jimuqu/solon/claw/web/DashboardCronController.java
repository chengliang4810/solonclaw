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

    @Mapping(value = "/api/cron/jobs/guide", method = MethodType.GET)
    public Map<String, Object> guide() throws Exception {
        return DashboardResponse.ok(cronService.guide());
    }

    @Mapping(value = "/api/jobs/guide", method = MethodType.GET)
    public Map<String, Object> apiGuide() throws Exception {
        return cronService.guide();
    }

    @Mapping(value = "/api/cron/jobs/policy", method = MethodType.GET)
    public Map<String, Object> policy() throws Exception {
        return DashboardResponse.ok(cronService.policy());
    }

    @Mapping(value = "/api/jobs/policy", method = MethodType.GET)
    public Map<String, Object> apiPolicy() throws Exception {
        return cronService.policy();
    }

    @Mapping(value = "/api/cron/jobs/next", method = MethodType.GET)
    public Map<String, Object> next(Context context, @Param(defaultValue = "5") Integer limit) throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        int safeLimit = safeLimit(limit, 5, 50);
        List<Map<String, Object>> jobs = cronService.nextJobs(safeLimit, includeDisabled);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("jobs", jobs);
        data.put("count", Integer.valueOf(jobs.size()));
        data.put("limit", Integer.valueOf(safeLimit));
        data.put("include_disabled", Boolean.valueOf(includeDisabled));
        return DashboardResponse.ok(data);
    }

    @Mapping(value = "/api/jobs/next", method = MethodType.GET)
    public Map<String, Object> apiNext(Context context, @Param(defaultValue = "5") Integer limit) throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        int safeLimit = safeLimit(limit, 5, 50);
        List<Map<String, Object>> jobs = cronService.nextJobs(safeLimit, includeDisabled);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("jobs", jobs);
        data.put("count", Integer.valueOf(jobs.size()));
        data.put("limit", Integer.valueOf(safeLimit));
        data.put("include_disabled", Boolean.valueOf(includeDisabled));
        return data;
    }

    @Mapping(value = "/api/cron/jobs/status", method = MethodType.GET)
    public Map<String, Object> status(Context context, @Param(defaultValue = "5") Integer limit) throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        return DashboardResponse.ok(cronService.status(includeDisabled, limit == null ? 5 : limit.intValue()));
    }

    @Mapping(value = "/api/jobs/status", method = MethodType.GET)
    public Map<String, Object> apiStatus(Context context, @Param(defaultValue = "5") Integer limit) throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        return cronService.status(includeDisabled, limit == null ? 5 : limit.intValue());
    }

    @Mapping(value = "/api/cron/jobs", method = MethodType.POST)
    public Map<String, Object> create(Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.create(body(context)));
        } catch (BodyParseException e) {
            context.status(400);
            return DashboardResponse.error("CRON_BAD_REQUEST", e.getMessage());
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
        } catch (BodyParseException e) {
            context.status(400);
            return apiError(e.getMessage());
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

    @Mapping(value = "/api/cron/jobs/{id}/inspect", method = MethodType.GET)
    public Map<String, Object> inspect(String id, @Param(defaultValue = "5") Integer limit, Context context)
            throws Exception {
        try {
            return DashboardResponse.ok(cronService.inspect(id, limit == null ? 5 : limit.intValue()));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
        }
    }

    @Mapping(value = "/api/jobs/{id}/inspect", method = MethodType.GET)
    public Map<String, Object> apiInspect(String id, @Param(defaultValue = "5") Integer limit, Context context)
            throws Exception {
        return apiInspectData(id, limit, context);
    }

    @Mapping(value = "/api/jobs/{id}/show", method = MethodType.GET)
    public Map<String, Object> apiShow(String id, @Param(defaultValue = "5") Integer limit, Context context)
            throws Exception {
        return apiInspectData(id, limit, context);
    }

    private Map<String, Object> apiInspectData(String id, Integer limit, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return cronService.inspect(id, limit == null ? 5 : limit.intValue());
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

    @Mapping(value = "/api/cron/jobs/{id}", method = MethodType.PUT)
    public Map<String, Object> update(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.update(id, body(context)));
        } catch (BodyParseException e) {
            context.status(400);
            return DashboardResponse.error("CRON_BAD_REQUEST", e.getMessage());
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
        } catch (BodyParseException e) {
            context.status(400);
            return apiError(e.getMessage());
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
        return dashboardPauseJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/disable", method = MethodType.POST)
    public Map<String, Object> disable(String id, Context context) throws Exception {
        return dashboardPauseJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/stop", method = MethodType.POST)
    public Map<String, Object> stop(String id, Context context) throws Exception {
        return dashboardPauseJob(id, context);
    }

    private Map<String, Object> dashboardPauseJob(String id, Context context) throws Exception {
        try {
            return DashboardResponse.ok(cronService.pause(id, body(context)));
        } catch (BodyParseException e) {
            context.status(400);
            return DashboardResponse.error("CRON_BAD_REQUEST", e.getMessage());
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
        return apiPauseJob(id, context);
    }

    @Mapping(value = "/api/jobs/{id}/disable", method = MethodType.POST)
    public Map<String, Object> apiDisable(String id, Context context) throws Exception {
        return apiPauseJob(id, context);
    }

    @Mapping(value = "/api/jobs/{id}/stop", method = MethodType.POST)
    public Map<String, Object> apiStop(String id, Context context) throws Exception {
        return apiPauseJob(id, context);
    }

    private Map<String, Object> apiPauseJob(String id, Context context) throws Exception {
        try {
            validateApiJobId(id);
            return apiJobResponse(cronService.pause(id, body(context)));
        } catch (BodyParseException e) {
            context.status(400);
            return apiError(e.getMessage());
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
        return dashboardResumeJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/enable", method = MethodType.POST)
    public Map<String, Object> enable(String id, Context context) throws Exception {
        return dashboardResumeJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/start", method = MethodType.POST)
    public Map<String, Object> start(String id, Context context) throws Exception {
        return dashboardResumeJob(id, context);
    }

    private Map<String, Object> dashboardResumeJob(String id, Context context) throws Exception {
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
        return apiResumeJob(id, context);
    }

    @Mapping(value = "/api/jobs/{id}/enable", method = MethodType.POST)
    public Map<String, Object> apiEnable(String id, Context context) throws Exception {
        return apiResumeJob(id, context);
    }

    @Mapping(value = "/api/jobs/{id}/start", method = MethodType.POST)
    public Map<String, Object> apiStart(String id, Context context) throws Exception {
        return apiResumeJob(id, context);
    }

    private Map<String, Object> apiResumeJob(String id, Context context) throws Exception {
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
        return dashboardRunJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/run", method = MethodType.POST)
    public Map<String, Object> run(String id, Context context) throws Exception {
        return dashboardRunJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/retry", method = MethodType.POST)
    public Map<String, Object> retry(String id, Context context) throws Exception {
        return dashboardRunJob(id, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/rerun", method = MethodType.POST)
    public Map<String, Object> rerun(String id, Context context) throws Exception {
        return dashboardRunJob(id, context);
    }

    private Map<String, Object> dashboardRunJob(String id, Context context) throws Exception {
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

    @Mapping(value = "/api/jobs/{id}/retry", method = MethodType.POST)
    public Map<String, Object> apiRetry(String id, Context context) throws Exception {
        return apiRunJob(id, context);
    }

    @Mapping(value = "/api/jobs/{id}/rerun", method = MethodType.POST)
    public Map<String, Object> apiRerun(String id, Context context) throws Exception {
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
        return dashboardHistoryData(id, limit, context);
    }

    @Mapping(value = "/api/cron/jobs/{id}/history", method = MethodType.GET)
    public Map<String, Object> historyAlias(String id, @Param(defaultValue = "20") Integer limit, Context context)
            throws Exception {
        return dashboardHistoryData(id, limit, context);
    }

    private Map<String, Object> dashboardHistoryData(String id, Integer limit, Context context) throws Exception {
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
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new BodyParseException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object data = ONode.ofJson(raw).toData();
            if (data instanceof Map) {
                return (Map<String, Object>) data;
            }
            throw new BodyParseException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (BodyParseException e) {
            throw e;
        } catch (Exception e) {
            throw new BodyParseException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
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

    private int safeLimit(Integer limit, int defaultValue, int maxValue) {
        int value = limit == null ? defaultValue : limit.intValue();
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    private static final class BodyParseException extends IllegalArgumentException {
        private BodyParseException(String message) {
            super(message);
        }
    }
}
