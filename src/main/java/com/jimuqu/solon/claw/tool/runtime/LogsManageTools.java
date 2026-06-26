package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardLogsService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供日志查询工具，复用 Dashboard 日志服务的白名单、脱敏和索引查询逻辑。 */
public class LogsManageTools {
    /** Dashboard 日志服务，用于读取受控日志文件和结构化运行索引。 */
    private final DashboardLogsService dashboardLogsService;

    /**
     * 创建日志管理工具。
     *
     * @param dashboardLogsService Dashboard 日志服务。
     */
    public LogsManageTools(DashboardLogsService dashboardLogsService) {
        this.dashboardLogsService = dashboardLogsService;
    }

    /**
     * 查询受控日志内容。
     *
     * @param file 日志文件类型，仅由 Dashboard 服务接受 agent、gateway、errors。
     * @param lines 返回行数。
     * @param level 日志级别过滤。
     * @param component 组件过滤。
     * @param query 关键字过滤。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "logs_manage",
            description =
                    "Inspect redacted dashboard logs. Parameters: file(agent,gateway,errors), lines, level, component, query.")
    public String logsManage(
            @Param(
                            name = "file",
                            required = false,
                            defaultValue = "agent",
                            description = "Log file: agent, gateway, or errors")
                    String file,
            @Param(
                            name = "lines",
                            required = false,
                            defaultValue = "100",
                            description = "Max returned lines")
                    Integer lines,
            @Param(name = "level", required = false, description = "Log level filter")
                    String level,
            @Param(name = "component", required = false, description = "Component filter")
                    String component,
            @Param(name = "query", required = false, description = "Keyword query")
                    String query) {
        try {
            if (dashboardLogsService == null) {
                return ToolResultEnvelope.error("logs service unavailable").toJson();
            }
            Map<String, Object> result = run(file, lines, level, component, query);
            return ToolResultEnvelope.ok("日志查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行日志读取。
     *
     * @param file 日志文件类型。
     * @param lines 返回行数。
     * @param level 日志级别过滤。
     * @param component 组件过滤。
     * @param query 关键字过滤。
     * @return 返回 Dashboard 日志结果。
     */
    private Map<String, Object> run(
            String file, Integer lines, String level, String component, String query) {
        String selectedFile = file == null || file.trim().isEmpty() ? "agent" : file.trim();
        List<String> rows =
                dashboardLogsService.read(
                        selectedFile, lines == null ? 100 : lines.intValue(), level, component,
                        query);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("file", selectedFile);
        result.put("lines", rows);
        return result;
    }
}
