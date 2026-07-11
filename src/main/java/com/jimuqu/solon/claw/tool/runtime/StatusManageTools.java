package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardStatusService;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供运行状态和模型信息查询工具，复用 Dashboard 状态服务。 */
public class StatusManageTools {
    /** Dashboard 状态服务，用于复用运行状态、健康快照和模型信息聚合逻辑。 */
    private final DashboardStatusService dashboardStatusService;

    /**
     * 创建状态管理工具。
     *
     * @param dashboardStatusService Dashboard 状态服务。
     */
    public StatusManageTools(DashboardStatusService dashboardStatusService) {
        this.dashboardStatusService = dashboardStatusService;
    }

    /**
     * 查询运行状态、健康快照或模型信息。
     *
     * @param action 操作名称。
     * @param detailed 是否返回详细信息。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "status_manage",
            description = "Inspect dashboard runtime status. Actions: status, health, model_info.")
    public String statusManage(
            @Param(name = "action", description = "status, health, model_info") String action,
            @Param(
                            name = "detailed",
                            required = false,
                            defaultValue = "true",
                            description = "Whether to return detailed status")
                    Boolean detailed) {
        try {
            if (dashboardStatusService == null) {
                return ToolResultEnvelope.error("status service unavailable").toJson();
            }
            Map<String, Object> result = run(action, detailed == null || detailed.booleanValue());
            return ToolResultEnvelope.ok("状态查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行状态查询动作。
     *
     * @param action 操作名称。
     * @param detailed 是否返回详细信息。
     * @return 返回 Dashboard 状态服务结果。
     */
    private Map<String, Object> run(String action, boolean detailed) throws Exception {
        String normalized =
                action == null ? "status" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("health".equals(normalized)) {
            return dashboardStatusService.getHealthRuntimeSnapshot();
        }
        if ("model_info".equals(normalized) || "model".equals(normalized)) {
            return dashboardStatusService.getModelInfo(detailed);
        }
        return dashboardStatusService.getStatus(detailed);
    }
}
