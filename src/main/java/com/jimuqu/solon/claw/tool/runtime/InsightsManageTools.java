package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardInsightsService;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供控制台洞察查询工具，复用 Dashboard 洞察服务。 */
public class InsightsManageTools {
    /** Dashboard 洞察服务，用于复用会话、技能和运行时概览逻辑。 */
    private final DashboardInsightsService insightsService;

    /**
     * 创建洞察管理工具。
     *
     * @param insightsService Dashboard 洞察服务。
     */
    public InsightsManageTools(DashboardInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    /**
     * 查询洞察概览或技能用量。
     *
     * @param action 操作名称。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "insights_manage",
            description = "Inspect dashboard insights. Actions: overview, skills.")
    public String insightsManage(
            @Param(name = "action", description = "overview, skills") String action) {
        try {
            if (insightsService == null) {
                return ToolResultEnvelope.error("insights service unavailable").toJson();
            }
            Map<String, Object> result = run(action);
            return ToolResultEnvelope.ok("洞察查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行洞察查询动作。
     *
     * @param action 操作名称。
     * @return 返回 Dashboard 洞察服务结果。
     */
    private Map<String, Object> run(String action) {
        String normalized =
                action == null ? "overview" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("skills".equals(normalized) || "skill_usage".equals(normalized)) {
            return insightsService.skillUsage();
        }
        return insightsService.overview();
    }
}
