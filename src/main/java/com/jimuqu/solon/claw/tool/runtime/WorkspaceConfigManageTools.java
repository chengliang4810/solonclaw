package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 Dashboard 工作区配置项只读查询工具。 */
public class WorkspaceConfigManageTools {
    /** Dashboard 工作区配置服务，用于读取已脱敏的配置项状态。 */
    private final DashboardRuntimeConfigService runtimeConfigService;

    /**
     * 创建工作区配置查询工具。
     *
     * @param runtimeConfigService Dashboard 工作区配置服务。
     */
    public WorkspaceConfigManageTools(DashboardRuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * 查询工作区配置项。
     *
     * @param action 操作名称。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "workspace_config_manage",
            description = "Inspect redacted workspace config items. Actions: items.")
    public String workspaceConfigManage(
            @Param(name = "action", description = "items") String action) {
        try {
            if (runtimeConfigService == null) {
                return ToolResultEnvelope.error("workspace config service unavailable").toJson();
            }
            Map<String, Object> result = run(action);
            return ToolResultEnvelope.ok("工作区配置查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行工作区配置只读查询动作。
     *
     * @param action 操作名称。
     * @return 返回已脱敏配置项集合。
     */
    private Map<String, Object> run(String action) {
        String normalized = action == null ? "items" : action.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ("items".equals(normalized) || "list".equals(normalized)) {
            result.put("items", runtimeConfigService.getConfigItems());
            return result;
        }
        result.put("items", runtimeConfigService.getConfigItems());
        return result;
    }
}
