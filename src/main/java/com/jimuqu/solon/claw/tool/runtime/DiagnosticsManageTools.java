package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 Dashboard 诊断总览只读查询工具。 */
public class DiagnosticsManageTools {
    /** Dashboard 诊断服务，用于读取运行、工具、MCP 和安全诊断总览。 */
    private final Supplier<DashboardDiagnosticsService> diagnosticsService;

    /**
     * 创建诊断总览查询工具。
     *
     * @param diagnosticsService Dashboard 诊断服务供应器。
     */
    public DiagnosticsManageTools(Supplier<DashboardDiagnosticsService> diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    /**
     * 查询 Dashboard 诊断总览。
     *
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "diagnostics_manage",
            description =
                    "Inspect dashboard diagnostics. Actions: overview, subprocess_environment.")
    public String diagnosticsManage(
            @Param(
                            name = "action",
                            required = false,
                            description = "overview, subprocess_environment")
                    String action,
            @Param(
                            name = "names_json",
                            required = false,
                            description =
                                    "JSON array of environment variable names for subprocess_environment")
                    String namesJson) {
        try {
            DashboardDiagnosticsService service = resolveDiagnosticsService();
            if (service == null) {
                return ToolResultEnvelope.error("diagnostics service unavailable").toJson();
            }
            Map<String, Object> result = run(service, action, namesJson);
            return ToolResultEnvelope.ok("诊断总览查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /** 保留无参入口，兼容已有测试和内部调用。 */
    public String diagnosticsManage() {
        return diagnosticsManage("overview", null);
    }

    /**
     * 执行诊断查询动作。
     *
     * @param action 操作名称。
     * @param namesJson 环境变量名 JSON 数组。
     * @return 返回诊断结果。
     */
    private Map<String, Object> run(
            DashboardDiagnosticsService service, String action, String namesJson) {
        String normalized =
                action == null ? "overview" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("subprocess_environment".equals(normalized)
                || "subprocess-environment".equals(normalized)
                || "env".equals(normalized)) {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("names", parseNames(namesJson));
            return service.subprocessEnvironmentProbe(body);
        }
        return service.diagnostics();
    }

    /**
     * 延迟读取 Dashboard 诊断服务，避免工具注册表创建阶段形成循环依赖。
     *
     * @return 返回诊断服务实例，无法解析时返回 null。
     */
    private DashboardDiagnosticsService resolveDiagnosticsService() {
        return diagnosticsService == null ? null : diagnosticsService.get();
    }

    /**
     * 解析环境变量名列表。
     *
     * @param namesJson JSON 数组文本。
     * @return 返回解析后的名称集合或原始文本。
     */
    private Object parseNames(String namesJson) {
        if (namesJson == null || namesJson.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Object parsed = ONode.ofJson(namesJson).toData();
        return parsed instanceof java.util.List ? parsed : java.util.Collections.emptyList();
    }
}
