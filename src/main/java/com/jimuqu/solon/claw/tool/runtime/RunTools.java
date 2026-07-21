package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardRunService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 Agent 运行查询和控制工具，复用 Dashboard 已有运行服务。 */
public class RunTools {
    /** Dashboard 运行服务，用于复用运行查询、控制和子 Agent 控制逻辑。 */
    private final DashboardRunService dashboardRunService;

    /**
     * 创建运行管理工具。
     *
     * @param dashboardRunService Dashboard 运行服务。
     */
    public RunTools(DashboardRunService dashboardRunService) {
        this.dashboardRunService = dashboardRunService;
    }

    /**
     * 查询或控制 Agent 运行。
     *
     * @param action 操作名称。
     * @param runId 运行标识。
     * @param command 控制命令。
     * @param subagentId 子 Agent 标识。
     * @param sessionId 会话标识。
     * @param payloadJson 控制载荷 JSON。
     * @param limit 返回数量上限。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "run_manage",
            description =
                    "Inspect and control Agent runs. Actions: run, detail, session_runs, events, tools, subagents, recoveries, commands, recoverable, control, active_subagents, control_subagent.")
    public String runManage(
            @Param(
                            name = "action",
                            description =
                                    "run, detail, session_runs, events, tools, subagents, recoveries, commands, recoverable, control, active_subagents, control_subagent")
                    String action,
            @Param(name = "run_id", required = false, description = "Run id for run actions")
                    String runId,
            @Param(name = "command", required = false, description = "Command for control actions")
                    String command,
            @Param(
                            name = "subagent_id",
                            required = false,
                            description = "Subagent id for control_subagent")
                    String subagentId,
            @Param(
                            name = "session_id",
                            required = false,
                            description = "Session id for session_runs")
                    String sessionId,
            @Param(
                            name = "payload_json",
                            required = false,
                            description = "Optional JSON payload for action=control")
                    String payloadJson,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "20",
                            description = "Max rows for recoverable")
                    Integer limit) {
        try {
            if (dashboardRunService == null) {
                return ToolResultEnvelope.error("run service unavailable").toJson();
            }
            Map<String, Object> result =
                    run(action, runId, command, subagentId, sessionId, payloadJson, limit);
            return ToolResultEnvelope.ok("运行管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行运行管理动作。
     *
     * @param action 操作名称。
     * @param runId 运行标识。
     * @param command 控制命令。
     * @param subagentId 子 Agent 标识。
     * @param sessionId 会话标识。
     * @param payloadJson 控制载荷 JSON。
     * @param limit 返回数量上限。
     * @return 返回服务结果。
     */
    private Map<String, Object> run(
            String action,
            String runId,
            String command,
            String subagentId,
            String sessionId,
            String payloadJson,
            Integer limit)
            throws Exception {
        String normalized =
                action == null ? "recoverable" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("run".equals(normalized)) {
            return dashboardRunService.run(runId);
        }
        if ("session_runs".equals(normalized) || "session-runs".equals(normalized)) {
            return dashboardRunService.sessionRuns(
                    sessionId, limit == null ? 20 : limit.intValue());
        }
        if ("recoverable".equals(normalized)) {
            return dashboardRunService.recoverable(limit == null ? 20 : limit.intValue());
        }
        if ("active_subagents".equals(normalized)) {
            return dashboardRunService.activeSubagents();
        }
        if ("control_subagent".equals(normalized)) {
            return dashboardRunService.controlSubagent(subagentId, command);
        }
        if ("control".equals(normalized)) {
            return dashboardRunService.control(runId, command, payload(payloadJson));
        }
        if ("events".equals(normalized)) {
            return dashboardRunService.events(runId);
        }
        if ("tools".equals(normalized)) {
            return dashboardRunService.toolCalls(runId);
        }
        if ("subagents".equals(normalized)) {
            return dashboardRunService.subagents(runId);
        }
        if ("recoveries".equals(normalized)) {
            return dashboardRunService.recoveries(runId);
        }
        if ("commands".equals(normalized)) {
            return dashboardRunService.commands(runId);
        }
        return dashboardRunService.detail(runId);
    }

    /**
     * 解析控制载荷 JSON。
     *
     * @param payloadJson 载荷 JSON。
     * @return 返回载荷 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return ONode.deserialize(payloadJson, LinkedHashMap.class);
    }
}
