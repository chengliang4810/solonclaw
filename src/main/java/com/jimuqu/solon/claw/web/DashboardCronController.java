package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 定时任务接口。 */
@Controller
public class DashboardCronController {
    private final DashboardCronService cronService;
    private final AppConfig appConfig;

    public DashboardCronController(DashboardCronService cronService, AppConfig appConfig) {
        this.cronService = cronService;
        this.appConfig = appConfig;
    }

    @Mapping(value = "/api/cron/jobs", method = MethodType.GET)
    public List<Map<String, Object>> jobs() throws Exception {
        return cronService.listJobs();
    }

    @Mapping(value = "/api/cron/jobs/guide", method = MethodType.GET)
    public Map<String, Object> guide() throws Exception {
        return DashboardResponse.ok(cronService.guide());
    }

    @Mapping(value = "/api/cron/jobs/policy", method = MethodType.GET)
    public Map<String, Object> policy() throws Exception {
        return DashboardResponse.ok(cronService.policy());
    }

    @Mapping(value = "/api/cron/jobs/next", method = MethodType.GET)
    public Map<String, Object> next(Context context, @Param(defaultValue = "5") Integer limit)
            throws Exception {
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

    @Mapping(value = "/api/cron/jobs/status", method = MethodType.GET)
    public Map<String, Object> status(Context context, @Param(defaultValue = "5") Integer limit)
            throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        return DashboardResponse.ok(
                cronService.status(includeDisabled, limit == null ? 5 : limit.intValue()));
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

    @Mapping(value = "/api/cron/jobs/{id}/inspect", method = MethodType.GET)
    public Map<String, Object> inspect(
            String id, @Param(defaultValue = "5") Integer limit, Context context) throws Exception {
        try {
            return DashboardResponse.ok(
                    cronService.inspect(id, limit == null ? 5 : limit.intValue()));
        } catch (IllegalArgumentException e) {
            context.status(400);
            return dashboardError("CRON_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            context.status(isNotFound(e) ? 404 : 400);
            return dashboardError(isNotFound(e) ? "CRON_NOT_FOUND" : "CRON_BAD_REQUEST", e);
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

    @Mapping(value = "/api/cron/jobs/{id}/pause", method = MethodType.POST)
    public Map<String, Object> pause(String id, Context context) throws Exception {
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

    @Mapping(value = "/api/cron/jobs/{id}/resume", method = MethodType.POST)
    public Map<String, Object> resume(String id, Context context) throws Exception {
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

    @Mapping(value = "/api/cron/jobs/{id}/trigger", method = MethodType.POST)
    public Map<String, Object> trigger(String id, Context context) throws Exception {
        return dashboardRunJob(id, context, false);
    }

    @Mapping(value = "/api/cron/jobs/{id}/retry", method = MethodType.POST)
    public Map<String, Object> retry(String id, Context context) throws Exception {
        return dashboardRunJob(id, context, true);
    }

    private Map<String, Object> dashboardRunJob(String id, Context context, boolean retry)
            throws Exception {
        try {
            Map<String, Object> requestBody = body(context);
            return DashboardResponse.ok(
                    retry
                            ? cronService.retry(id, requestBody)
                            : cronService.trigger(id, requestBody));
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

    @Mapping(value = "/api/cron/jobs/{id}/runs", method = MethodType.GET)
    public Map<String, Object> history(
            String id, @Param(defaultValue = "20") Integer limit, Context context)
            throws Exception {
        return dashboardHistoryData(id, limit, context);
    }

    private Map<String, Object> dashboardHistoryData(String id, Integer limit, Context context)
            throws Exception {
        try {
            List<Map<String, Object>> runs =
                    cronService.history(id, limit == null ? 20 : limit.intValue());
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

    private Map<String, Object> dashboardError(String code, Exception e) {
        String message;
        if (e == null) {
            message = "";
        } else if (e.getMessage() == null || e.getMessage().trim().length() == 0) {
            message = e.getClass().getSimpleName();
        } else {
            message = e.getMessage();
        }
        message = SecretRedactor.redact(redactHostPaths(message), 1000);
        return DashboardResponse.error(code, message);
    }

    private String redactHostPaths(String message) {
        if (StrUtil.isBlank(message) || appConfig == null || appConfig.getRuntime() == null) {
            return message;
        }
        String home = StrUtil.nullToEmpty(appConfig.getRuntime().getHome()).trim();
        if (StrUtil.isBlank(home)) {
            return message;
        }
        try {
            String canonical = new File(home).getCanonicalPath();
            if (!StrUtil.isBlank(canonical)) {
                message = message.replace(canonical, "[REDACTED_PATH]");
            }
        } catch (Exception ignored) {
        }
        message = message.replace(home, "[REDACTED_PATH]");
        return message;
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
