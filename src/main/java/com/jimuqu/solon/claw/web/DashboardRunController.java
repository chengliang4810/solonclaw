package com.jimuqu.solon.claw.web;

import java.util.Map;
import java.util.LinkedHashMap;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard Agent run 接口。 */
@Controller
public class DashboardRunController {
    private final DashboardRunService dashboardRunService;

    public DashboardRunController(DashboardRunService dashboardRunService) {
        this.dashboardRunService = dashboardRunService;
    }

    @Mapping(value = "/api/runs/{runId}", method = MethodType.GET)
    public Map<String, Object> run(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.run(runId));
    }

    @Mapping(value = "/api/runs/{runId}/detail", method = MethodType.GET)
    public Map<String, Object> detail(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.detail(runId));
    }

    @Mapping(value = "/api/runs/{runId}/events", method = MethodType.GET)
    public Map<String, Object> events(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.events(runId));
    }

    @Mapping(value = "/api/runs/{runId}/tools", method = MethodType.GET)
    public Map<String, Object> tools(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.toolCalls(runId));
    }

    @Mapping(value = "/api/runs/{runId}/subagents", method = MethodType.GET)
    public Map<String, Object> subagents(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.subagents(runId));
    }

    @Mapping(value = "/api/runs/{runId}/recoveries", method = MethodType.GET)
    public Map<String, Object> recoveries(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.recoveries(runId));
    }

    @Mapping(value = "/api/runs/{runId}/commands", method = MethodType.GET)
    public Map<String, Object> commands(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.commands(runId));
    }

    @Mapping(value = "/api/runs/{runId}/control", method = MethodType.POST)
    public Map<String, Object> control(String runId, Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
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

    @Mapping(value = "/api/sessions/{sessionId}/runs", method = MethodType.GET)
    public Map<String, Object> sessionRuns(String sessionId, Context context) throws Exception {
        return DashboardResponse.ok(
                dashboardRunService.sessionRuns(sessionId, context.paramAsInt("limit", 20)));
    }

    @Mapping(value = "/api/runs/recoverable", method = MethodType.GET)
    public Map<String, Object> recoverable(Context context) throws Exception {
        return DashboardResponse.ok(
                dashboardRunService.recoverable(context.paramAsInt("limit", 50)));
    }

    @Mapping(value = "/api/runs/subagents/active", method = MethodType.GET)
    public Map<String, Object> activeSubagents() {
        return DashboardResponse.ok(dashboardRunService.activeSubagents());
    }

    @Mapping(value = "/api/runs/subagents/{subagentId}/control", method = MethodType.POST)
    public Map<String, Object> controlSubagent(String subagentId, Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        ONode body = body(context);
                        return dashboardRunService.controlSubagent(
                                subagentId, body.get("command").getString());
                    }
                });
    }

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
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }

    private interface RunAction {
        Map<String, Object> run() throws Exception;
    }
}
