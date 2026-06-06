package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard Agent run 接口。 */
@Controller
public class DashboardRunController {
    /** 注入控制台运行服务，用于调用对应业务能力。 */
    private final DashboardRunService dashboardRunService;

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
    public Map<String, Object> run(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.run(runId));
    }

    /**
     * 执行详情相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回detail结果。
     */
    @Mapping(value = "/api/runs/{runId}/detail", method = MethodType.GET)
    public Map<String, Object> detail(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.detail(runId));
    }

    /**
     * 执行events相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回events结果。
     */
    @Mapping(value = "/api/runs/{runId}/events", method = MethodType.GET)
    public Map<String, Object> events(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.events(runId));
    }

    /**
     * 执行工具相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回工具结果。
     */
    @Mapping(value = "/api/runs/{runId}/tools", method = MethodType.GET)
    public Map<String, Object> tools(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.toolCalls(runId));
    }

    /**
     * 执行subagents相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回subagents结果。
     */
    @Mapping(value = "/api/runs/{runId}/subagents", method = MethodType.GET)
    public Map<String, Object> subagents(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.subagents(runId));
    }

    /**
     * 执行recoveries相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回recoveries结果。
     */
    @Mapping(value = "/api/runs/{runId}/recoveries", method = MethodType.GET)
    public Map<String, Object> recoveries(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.recoveries(runId));
    }

    /**
     * 执行commands相关逻辑。
     *
     * @param runId 运行标识。
     * @return 返回commands结果。
     */
    @Mapping(value = "/api/runs/{runId}/commands", method = MethodType.GET)
    public Map<String, Object> commands(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.commands(runId));
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
                        ONode body = body(context);
                        return dashboardRunService.control(
                                runId,
                                body.get("command").getString(),
                                ONode.deserialize(body.toJson(), LinkedHashMap.class));
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
        return DashboardResponse.ok(
                dashboardRunService.sessionRuns(sessionId, context.paramAsInt("limit", 20)));
    }

    /**
     * 执行recoverable相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回recoverable结果。
     */
    @Mapping(value = "/api/runs/recoverable", method = MethodType.GET)
    public Map<String, Object> recoverable(Context context) throws Exception {
        return DashboardResponse.ok(
                dashboardRunService.recoverable(context.paramAsInt("limit", 50)));
    }

    /**
     * 执行activeSubagents相关逻辑。
     *
     * @return 返回active Subagents结果。
     */
    @Mapping(value = "/api/runs/subagents/active", method = MethodType.GET)
    public Map<String, Object> activeSubagents() {
        return DashboardResponse.ok(dashboardRunService.activeSubagents());
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
                        ONode body = body(context);
                        return dashboardRunService.controlSubagent(
                                subagentId, body.get("command").getString());
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
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("RUN_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("RUN_BAD_REQUEST", e.getMessage());
        }
    }

    /**
     * 执行正文相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回body结果。
     */
    private ONode body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new ONode();
        }
        try {
            ONode node = ONode.ofJson(raw);
            Object data = node.toData();
            if (data instanceof Map) {
                return node;
            }
            throw new IllegalArgumentException(
                    "请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
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
