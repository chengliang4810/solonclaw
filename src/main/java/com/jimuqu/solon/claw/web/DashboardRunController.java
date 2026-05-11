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

    @Mapping(value = "/api/jimuqu/runs/{runId}", method = MethodType.GET)
    public Map<String, Object> JimuquRun(String runId) throws Exception {
        return run(runId);
    }

    @Mapping(value = "/api/runs/{runId}/detail", method = MethodType.GET)
    public Map<String, Object> detail(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.detail(runId));
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/detail", method = MethodType.GET)
    public Map<String, Object> JimuquDetail(String runId) throws Exception {
        return detail(runId);
    }

    @Mapping(value = "/api/runs/{runId}/events", method = MethodType.GET)
    public Map<String, Object> events(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.events(runId));
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/events", method = MethodType.GET)
    public Map<String, Object> JimuquEvents(String runId) throws Exception {
        return events(runId);
    }

    @Mapping(value = "/api/runs/{runId}/tools", method = MethodType.GET)
    public Map<String, Object> tools(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.toolCalls(runId));
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/tools", method = MethodType.GET)
    public Map<String, Object> JimuquTools(String runId) throws Exception {
        return tools(runId);
    }

    @Mapping(value = "/api/runs/{runId}/subagents", method = MethodType.GET)
    public Map<String, Object> subagents(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.subagents(runId));
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/subagents", method = MethodType.GET)
    public Map<String, Object> JimuquSubagents(String runId) throws Exception {
        return subagents(runId);
    }

    @Mapping(value = "/api/runs/{runId}/recoveries", method = MethodType.GET)
    public Map<String, Object> recoveries(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.recoveries(runId));
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/recoveries", method = MethodType.GET)
    public Map<String, Object> JimuquRecoveries(String runId) throws Exception {
        return recoveries(runId);
    }

    @Mapping(value = "/api/runs/{runId}/commands", method = MethodType.GET)
    public Map<String, Object> commands(String runId) throws Exception {
        return DashboardResponse.ok(dashboardRunService.commands(runId));
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/commands", method = MethodType.GET)
    public Map<String, Object> JimuquCommands(String runId) throws Exception {
        return commands(runId);
    }

    @Mapping(value = "/api/runs/{runId}/control", method = MethodType.POST)
    public Map<String, Object> control(String runId, Context context) throws Exception {
        return safeRun(
                context,
                new RunAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        ONode body = ONode.ofJson(context.body());
                        return dashboardRunService.control(
                                runId,
                                body.get("command").getString(),
                                ONode.deserialize(body.toJson(), LinkedHashMap.class));
                    }
                });
    }

    @Mapping(value = "/api/jimuqu/runs/{runId}/control", method = MethodType.POST)
    public Map<String, Object> JimuquControl(String runId, Context context) throws Exception {
        return control(runId, context);
    }

    @Mapping(value = "/api/sessions/{sessionId}/runs", method = MethodType.GET)
    public Map<String, Object> sessionRuns(String sessionId, Context context) throws Exception {
        return DashboardResponse.ok(
                dashboardRunService.sessionRuns(sessionId, context.paramAsInt("limit", 20)));
    }

    @Mapping(value = "/api/jimuqu/runs", method = MethodType.GET)
    public Map<String, Object> JimuquRuns(Context context) throws Exception {
        String sessionId = context.param("sessionId");
        if (sessionId == null || sessionId.trim().length() == 0) {
            sessionId = context.param("session_id");
        }
        if (sessionId == null || sessionId.trim().length() == 0) {
            return recoverable(context);
        }
        return DashboardResponse.ok(
                dashboardRunService.sessionRuns(sessionId, context.paramAsInt("limit", 20)));
    }

    @Mapping(value = "/api/runs/recoverable", method = MethodType.GET)
    public Map<String, Object> recoverable(Context context) throws Exception {
        return DashboardResponse.ok(
                dashboardRunService.recoverable(context.paramAsInt("limit", 50)));
    }

    @Mapping(value = "/api/jimuqu/runs/recoverable", method = MethodType.GET)
    public Map<String, Object> JimuquRecoverable(Context context) throws Exception {
        return recoverable(context);
    }

    @Mapping(value = "/api/jimuqu/runs/subagents/active", method = MethodType.GET)
    public Map<String, Object> activeSubagents() {
        return DashboardResponse.ok(dashboardRunService.activeSubagents());
    }

    @Mapping(value = "/api/jimuqu/runs/subagents/{subagentId}/control", method = MethodType.POST)
    public Map<String, Object> controlSubagent(String subagentId, Context context) throws Exception {
        ONode body = ONode.ofJson(context.body());
        return DashboardResponse.ok(
                dashboardRunService.controlSubagent(subagentId, body.get("command").getString()));
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

    private interface RunAction {
        Map<String, Object> run() throws Exception;
    }
}
