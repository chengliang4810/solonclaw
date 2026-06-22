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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dashboard 定时任务接口。 */
@Controller
public class DashboardCronController {
    /** 记录定时任务接口降级处理的低敏诊断日志，不输出完整主机路径。 */
    private static final Logger log = LoggerFactory.getLogger(DashboardCronController.class);

    /** 注入定时任务服务，用于调用对应业务能力。 */
    private final DashboardCronService cronService;

    /** 注入应用配置，用于控制台定时任务。 */
    private final AppConfig appConfig;

    /**
     * 创建控制台定时任务控制器实例，并注入运行所需依赖。
     *
     * @param cronService 定时任务服务依赖。
     * @param appConfig 应用运行配置。
     */
    public DashboardCronController(DashboardCronService cronService, AppConfig appConfig) {
        this.cronService = cronService;
        this.appConfig = appConfig;
    }

    /**
     * 执行jobs相关逻辑。
     *
     * @return 返回jobs结果。
     */
    @Mapping(value = "/api/cron/jobs", method = MethodType.GET)
    public List<Map<String, Object>> jobs() throws Exception {
        return cronService.listJobs();
    }

    /**
     * 执行guide相关逻辑。
     *
     * @return 返回guide结果。
     */
    @Mapping(value = "/api/cron/jobs/guide", method = MethodType.GET)
    public Map<String, Object> guide() throws Exception {
        return DashboardResponse.ok(cronService.guide());
    }

    /**
     * 执行策略相关逻辑。
     *
     * @return 返回策略结果。
     */
    @Mapping(value = "/api/cron/jobs/policy", method = MethodType.GET)
    public Map<String, Object> policy() throws Exception {
        return DashboardResponse.ok(cronService.policy());
    }

    /**
     * 执行next相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param limit 最大返回数量。
     * @return 返回next结果。
     */
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

    /**
     * 执行状态相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @param limit 最大返回数量。
     * @return 返回状态。
     */
    @Mapping(value = "/api/cron/jobs/status", method = MethodType.GET)
    public Map<String, Object> status(Context context, @Param(defaultValue = "5") Integer limit)
            throws Exception {
        boolean includeDisabled = Boolean.parseBoolean(context.param("include_disabled"));
        return DashboardResponse.ok(
                cronService.status(includeDisabled, limit == null ? 5 : limit.intValue()));
    }

    /**
     * 执行create，服务于控制台定时任务主流程相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回create结果。
     */
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

    /**
     * 获取当前注册项或配置项。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回get结果。
     */
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

    /**
     * 执行inspect相关逻辑。
     *
     * @param id 标识。
     * @param limit 最大返回数量。
     * @param context 当前请求或运行上下文。
     * @return 返回inspect结果。
     */
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

    /**
     * 执行更新相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回更新结果。
     */
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

    /**
     * 执行pause相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回pause结果。
     */
    @Mapping(value = "/api/cron/jobs/{id}/pause", method = MethodType.POST)
    public Map<String, Object> pause(String id, Context context) throws Exception {
        return dashboardPauseJob(id, context);
    }

    /**
     * 执行控制台Pause任务相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回控制台Pause任务结果。
     */
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

    /**
     * 执行resume相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回resume结果。
     */
    @Mapping(value = "/api/cron/jobs/{id}/resume", method = MethodType.POST)
    public Map<String, Object> resume(String id, Context context) throws Exception {
        return dashboardResumeJob(id, context);
    }

    /**
     * 执行控制台Resume任务相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回控制台Resume任务结果。
     */
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

    /**
     * 执行trigger相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回trigger结果。
     */
    @Mapping(value = "/api/cron/jobs/{id}/trigger", method = MethodType.POST)
    public Map<String, Object> trigger(String id, Context context) throws Exception {
        return dashboardRunJob(id, context, false);
    }

    /**
     * 执行重试相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回retry结果。
     */
    @Mapping(value = "/api/cron/jobs/{id}/retry", method = MethodType.POST)
    public Map<String, Object> retry(String id, Context context) throws Exception {
        return dashboardRunJob(id, context, true);
    }

    /**
     * 执行控制台运行任务相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @param retry 重试参数。
     * @return 返回控制台运行任务结果。
     */
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

    /**
     * 执行历史相关逻辑。
     *
     * @param id 标识。
     * @param limit 最大返回数量。
     * @param context 当前请求或运行上下文。
     * @return 返回历史结果。
     */
    @Mapping(value = "/api/cron/jobs/{id}/runs", method = MethodType.GET)
    public Map<String, Object> history(
            String id, @Param(defaultValue = "20") Integer limit, Context context)
            throws Exception {
        return dashboardHistoryData(id, limit, context);
    }

    /**
     * 执行控制台历史数据相关逻辑。
     *
     * @param id 标识。
     * @param limit 最大返回数量。
     * @param context 当前请求或运行上下文。
     * @return 返回控制台历史Data结果。
     */
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

    /**
     * 执行delete，服务于控制台定时任务主流程相关逻辑。
     *
     * @param id 标识。
     * @param context 当前请求或运行上下文。
     * @return 返回delete结果。
     */
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

    /**
     * 执行正文相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回body结果。
     */
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

    /**
     * 执行控制台错误相关逻辑。
     *
     * @param code code 参数。
     * @param e 捕获到的异常。
     * @return 返回控制台Error结果。
     */
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

    /**
     * 脱敏Host Paths。
     *
     * @param message 平台消息或错误消息。
     * @return 返回Host Paths结果。
     */
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
        } catch (Exception e) {
            log.debug("定时任务路径脱敏规范化失败，继续使用原始配置值替换 error={}", e.getClass().getSimpleName());
        }
        message = message.replace(home, "[REDACTED_PATH]");
        return message;
    }

    /**
     * 判断是否Not Found。
     *
     * @param e 捕获到的异常。
     * @return 如果Not Found满足条件则返回 true，否则返回 false。
     */
    private boolean isNotFound(IllegalStateException e) {
        return e.getMessage() != null && e.getMessage().startsWith("Job not found:");
    }

    /**
     * 生成安全展示用的限制。
     *
     * @param limit 最大返回数量。
     * @param defaultValue 默认值参数。
     * @param maxValue max值参数。
     * @return 返回safe限制结果。
     */
    private int safeLimit(Integer limit, int defaultValue, int maxValue) {
        int value = limit == null ? defaultValue : limit.intValue();
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    /** 表示Body Parse异常，用于向上层传递可识别的失败原因。 */
    private static final class BodyParseException extends IllegalArgumentException {
        /**
         * 创建Body Parse Exception实例，并注入运行所需依赖。
         *
         * @param message 平台消息或错误消息。
         */
        private BodyParseException(String message) {
            super(message);
        }
    }
}
