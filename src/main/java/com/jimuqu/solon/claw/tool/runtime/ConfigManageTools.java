package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供 Dashboard 配置元数据只读查询工具。 */
public class ConfigManageTools {
    /** Dashboard 配置服务，用于读取配置结构、默认值和诊断信息。 */
    private final DashboardConfigService configService;

    /**
     * 创建配置管理工具。
     *
     * @param configService Dashboard 配置服务。
     */
    public ConfigManageTools(DashboardConfigService configService) {
        this.configService = configService;
    }

    /**
     * 查询配置结构、默认值或诊断信息。
     *
     * @param action 操作名称。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "config_manage",
            description = "Inspect dashboard config metadata. Actions: schema, defaults, diagnostics.")
    public String configManage(
            @Param(name = "action", description = "schema, defaults, diagnostics") String action) {
        try {
            if (configService == null) {
                return ToolResultEnvelope.error("config service unavailable").toJson();
            }
            Map<String, Object> result = run(action);
            return ToolResultEnvelope.ok("配置元数据查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行配置元数据只读查询。
     *
     * @param action 操作名称。
     * @return 返回 Dashboard 配置服务结果。
     */
    private Map<String, Object> run(String action) {
        String normalized = action == null ? "schema" : action.trim().toLowerCase(Locale.ROOT);
        if ("defaults".equals(normalized)) {
            return configService.getDefaults();
        }
        if ("diagnostics".equals(normalized) || "diagnostic".equals(normalized)) {
            return configService.diagnostics();
        }
        return configService.getSchema();
    }
}
