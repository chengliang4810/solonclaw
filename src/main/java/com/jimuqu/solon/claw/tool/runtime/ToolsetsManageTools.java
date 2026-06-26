package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardSkillsService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;

/** 提供 Dashboard 工具集只读查询工具。 */
public class ToolsetsManageTools {
    /** Dashboard 技能服务，用于读取与前端一致的工具集分组。 */
    private final DashboardSkillsService skillsService;

    /**
     * 创建工具集查询工具。
     *
     * @param skillsService Dashboard 技能服务。
     */
    public ToolsetsManageTools(DashboardSkillsService skillsService) {
        this.skillsService = skillsService;
    }

    /**
     * 查询 Dashboard 工具集分组。
     *
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(name = "toolsets_manage", description = "Inspect dashboard toolset groups.")
    public String toolsetsManage() {
        try {
            if (skillsService == null) {
                return ToolResultEnvelope.error("toolsets service unavailable").toJson();
            }
            List<Map<String, Object>> toolsets = skillsService.getToolsets();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("toolsets", toolsets);
            result.put("count", Integer.valueOf(toolsets.size()));
            return ToolResultEnvelope.ok("工具集查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }
}
