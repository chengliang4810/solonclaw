package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.DashboardRequestBodies;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.jimuqu.solon.claw.web.profile.DashboardProfileNotFoundException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard Agent run 接口。 */
@Controller
public class DashboardRunController {
    /** 注入控制台运行服务，用于调用对应业务能力。 */
    private final DashboardRunService dashboardRunService;

    /** 解析请求指定的 Profile；旧构造路径为空时保持当前运行仓储。 */
    @Inject(required = false)
    private DashboardProfileContext profileContext;

    /**
     * 创建控制台运行控制器实例，并注入运行所需依赖。
     *
     * @param dashboardRunService dashboard运行服务依赖。
     */
    public DashboardRunController(DashboardRunService dashboardRunService) {
        this.dashboardRunService = dashboardRunService;
    }

    /**
     * 执行异步任务主体。
     *
     * @param runId 运行标识。
     * @return 返回运行结果。
     */
    @Mapping(value = "/api/runs/{runId}", method = MethodType.GET)
    public Map<String, Object> run(String runId, Context context) throws Exception {
        return readRun(context, "run", runId);
    }

    /**
     * 执行详情相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回detail结果。
     */
    @Mapping(value = "/api/runs/{runId}/detail", method = MethodType.GET)
    public Map<String, Object> detail(String runId, Context context) throws Exception {
        return readRun(context, "detail", runId);
    }

    /**
     * 执行events相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回events结果。
     */
    @Mapping(value = "/api/runs/{runId}/events", method = MethodType.GET)
    public Map<String, Object> events(String runId, Context context) throws Exception {
        return readRun(context, "events", runId);
    }

    /**
     * 执行工具相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回工具结果。
     */
    @Mapping(value = "/api/runs/{runId}/tools", method = MethodType.GET)
    public Map<String, Object> tools(String runId, Context context) throws Exception {
        return readRun(context, "tools", runId);
    }

    /**
     * 执行subagents相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回subagents结果。
     */
    @Mapping(value = "/api/runs/{runId}/subagents", method = MethodType.GET)
    public Map<String, Object> subagents(String runId, Context context) throws Exception {
        return readRun(context, "subagents", runId);
    }

    /**
     * 执行recoveries相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回recoveries结果。
     */
    @Mapping(value = "/api/runs/{runId}/recoveries", method = MethodType.GET)
    public Map<String, Object> recoveries(String runId, Context context) throws Exception {
        return readRun(context, "recoveries", runId);
    }

    /**
     * 执行commands相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回commands结果。
     */
    @Mapping(value = "/api/runs/{runId}/commands", method = MethodType.GET)
    public Map<String, Object> commands(String runId, Context context) throws Exception {
        return readRun(context, "commands", runId);
    }

    /**
     * 执行控制相关逻辑。
     *
     * @param runId 运行标识。
     * @param context 当前请求或运行上下文。
     * @return 返回control结果。
     */
    @Mapping(value = "/api/runs/{runId}/control", method = MethodType.POST)
    public Map<String, Object> control(String runId, Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        DashboardProfileContext.Scope scope = resolve(context, body);
                        if (isTarget(scope)) {
                            return client(context, scope)
                                    .request(
                                            "POST",
                                            Collections.<String, String>emptyMap(),
                                            withoutProfile(body),
                                            "api",
                                            "runs",
                                            runId,
                                            "control");
                        }
                        return dashboardRunService.control(runId, text(body.get("command")), body);
                    }
                });
    }

    /**
     * 执行会话运行相关逻辑。
     *
     * @param sessionId 当前会话标识。
     * @param context 当前请求或运行上下文。
     * @return 返回会话运行结果。
     */
    @Mapping(value = "/api/sessions/{sessionId}/runs", method = MethodType.GET)
    public Map<String, Object> sessionRuns(String sessionId, Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    /** 读取当前或目标 Profile 的会话运行列表。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        int limit = context.paramAsInt("limit", 20);
                        return isTarget(scope)
                                ? client(context, scope)
                                        .request(
                                                "GET",
                                                query("limit", String.valueOf(limit)),
                                                "api",
                                                "sessions",
                                                sessionId,
                                                "runs")
                                : dashboardRunService.sessionRuns(sessionId, limit);
                    }
                });
    }

    /**
     * 执行recoverable相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回recoverable结果。
     */
    @Mapping(value = "/api/runs/recoverable", method = MethodType.GET)
    public Map<String, Object> recoverable(Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    /** 读取当前或目标 Profile 的可恢复运行。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        int limit = context.paramAsInt("limit", 50);
                        return isTarget(scope)
                                ? client(context, scope)
                                        .request(
                                                "GET",
                                                query("limit", String.valueOf(limit)),
                                                "api",
                                                "runs",
                                                "recoverable")
                                : dashboardRunService.recoverable(limit);
                    }
                });
    }

    /**
     * 执行activeSubagents相关逻辑。
     *
     * @return 返回active Subagents结果。
     */
    @Mapping(value = "/api/runs/subagents/active", method = MethodType.GET)
    public Map<String, Object> activeSubagents(Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    /** 读取当前或目标 Profile 的活动子 Agent。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        return isTarget(scope)
                                ? client(context, scope)
                                        .request(
                                                "GET",
                                                Collections.<String, String>emptyMap(),
                                                "api",
                                                "runs",
                                                "subagents",
                                                "active")
                                : dashboardRunService.activeSubagents();
                    }
                });
    }

    /**
     * 执行控制子Agent相关逻辑。
     *
     * @param subagentId 子Agent标识。
     * @param context 当前请求或运行上下文。
     * @return 返回control Subagent结果。
     */
    @Mapping(value = "/api/runs/subagents/{subagentId}/control", method = MethodType.POST)
    public Map<String, Object> controlSubagent(String subagentId, Context context)
            throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    /**
                     * 执行异步任务主体。
                     *
                     * @return 返回运行结果。
                     */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        Map<String, Object> body = DashboardRequestBodies.jsonObjectMap(context);
                        DashboardProfileContext.Scope scope = resolve(context, body);
                        if (isTarget(scope)) {
                            return client(context, scope)
                                    .request(
                                            "POST",
                                            Collections.<String, String>emptyMap(),
                                            withoutProfile(body),
                                            "api",
                                            "runs",
                                            "subagents",
                                            subagentId,
                                            "control");
                        }
                        return dashboardRunService.controlSubagent(
                                subagentId, text(body.get("command")));
                    }
                });
    }

    /**
     * 生成安全展示用的运行。
     *
     * @param context 当前请求或运行上下文。
     * @param action 操作参数。
     * @return 返回safe运行结果。
     */
    private Map<String, Object> safeRun(Context context, RunAction action) throws Exception {
        try {
            return DashboardResponse.ok(action.run());
        } catch (DashboardProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "PROFILE_NOT_FOUND", e);
        } catch (DashboardProfileGatewayException e) {
            return DashboardResponse.error(context, e.getStatus(), e.getCode(), e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "RUN_BAD_REQUEST", e);
        } catch (IllegalStateException e) {
            return DashboardResponse.error(context, 400, "RUN_BAD_REQUEST", e);
        }
    }

    /** 读取单个 run 子资源；非当前 Profile 由其独立网关提供。 */
    private Map<String, Object> readRun(Context context, String resource, String runId)
            throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    /** 执行单个运行资源读取。 */
                    @Override
                    public Map<String, Object> run() throws Exception {
                        DashboardProfileContext.Scope scope = resolve(context, null);
                        if (isTarget(scope)) {
                            if ("run".equals(resource)) {
                                return client(context, scope)
                                        .request(
                                                "GET",
                                                Collections.<String, String>emptyMap(),
                                                "api",
                                                "runs",
                                                runId);
                            }
                            return client(context, scope)
                                    .request(
                                            "GET",
                                            Collections.<String, String>emptyMap(),
                                            "api",
                                            "runs",
                                            runId,
                                            resource);
                        }
                        if ("run".equals(resource)) {
                            return dashboardRunService.run(runId);
                        }
                        if ("detail".equals(resource)) {
                            return dashboardRunService.detail(runId);
                        }
                        if ("events".equals(resource)) {
                            return dashboardRunService.events(runId);
                        }
                        if ("tools".equals(resource)) {
                            return dashboardRunService.toolCalls(runId);
                        }
                        if ("subagents".equals(resource)) {
                            return dashboardRunService.subagents(runId);
                        }
                        if ("recoveries".equals(resource)) {
                            return dashboardRunService.recoveries(runId);
                        }
                        return dashboardRunService.commands(runId);
                    }
                });
    }

    /** 解析 query 或非空 body.profile，body 优先。 */
    private DashboardProfileContext.Scope resolve(Context context, Map<String, Object> body) {
        String requested = DashboardProfileContext.requestedProfile(context, body);
        if (profileContext == null) {
            if (StrUtil.isBlank(requested) || "current".equalsIgnoreCase(requested)) {
                return null;
            }
            throw new IllegalStateException("Dashboard Profile scope is unavailable.");
        }
        return profileContext.resolve(requested);
    }

    /** 判断请求是否需要交给目标 Profile 独立网关。 */
    private boolean isTarget(DashboardProfileContext.Scope scope) {
        return scope != null && !scope.isCurrent();
    }

    /** 创建绑定目标 Profile 的回环客户端。 */
    private DashboardProfileGatewayClient client(
            Context context, DashboardProfileContext.Scope scope) {
        return new DashboardProfileGatewayClient(
                profileContext, scope, context == null ? null : context.header("Authorization"));
    }

    /** 复制写请求并移除机器级路由字段。 */
    private Map<String, Object> withoutProfile(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (body != null) {
            result.putAll(body);
        }
        result.remove("profile");
        return result;
    }

    /** 创建固定查询参数映射。 */
    private Map<String, String> query(String key, String value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put(key, value);
        return result;
    }

    /** 将请求字段转换为去空白文本。 */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 定义运行Action的抽象契约，供不同运行时实现保持一致行为。 */
    private interface RunAction {
        /**
         * 执行异步任务主体。
         *
         * @return 返回运行结果。
         */
        Map<String, Object> run() throws Exception;
    }
}
