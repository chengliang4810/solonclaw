package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;

/** 提供 Dashboard 诊断总览只读查询工具。 */
public class DiagnosticsManageTools {
    /** Dashboard 诊断服务，用于读取运行、工具、MCP 和安全诊断总览。 */
    private final DashboardDiagnosticsService diagnosticsService;

    /**
     * 创建诊断总览查询工具。
     *
     * @param diagnosticsService Dashboard 诊断服务。
     */
    public DiagnosticsManageTools(DashboardDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    /**
     * 查询 Dashboard 诊断总览。
     *
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "diagnostics_manage",
            description = "Inspect dashboard diagnostics overview.")
    public String diagnosticsManage() {
        try {
            if (diagnosticsService == null) {
                return ToolResultEnvelope.error("diagnostics service unavailable").toJson();
            }
            Map<String, Object> result = diagnosticsService.diagnostics();
            return ToolResultEnvelope.ok("诊断总览查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }
}
