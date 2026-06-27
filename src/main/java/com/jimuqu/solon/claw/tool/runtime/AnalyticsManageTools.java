package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardAnalyticsService;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供用量分析查询工具，复用 Dashboard 分析服务。 */
public class AnalyticsManageTools {
    /** Dashboard 分析服务，用于复用 token 用量聚合逻辑。 */
    private final DashboardAnalyticsService dashboardAnalyticsService;

    /**
     * 创建用量分析管理工具。
     *
     * @param dashboardAnalyticsService Dashboard 分析服务。
     */
    public AnalyticsManageTools(DashboardAnalyticsService dashboardAnalyticsService) {
        this.dashboardAnalyticsService = dashboardAnalyticsService;
    }

    /**
     * 查询用量分析数据。
     *
     * @param action 操作名称。
     * @param days 统计天数。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "analytics_manage",
            description = "Inspect usage analytics. Actions: usage.")
    public String analyticsManage(
            @Param(name = "action", description = "usage") String action,
            @Param(
                            name = "days",
                            required = false,
                            defaultValue = "30",
                            description = "Usage window in days")
                    Integer days) {
        try {
            if (dashboardAnalyticsService == null) {
                return ToolResultEnvelope.error("analytics service unavailable").toJson();
            }
            Map<String, Object> result = run(action, days);
            return ToolResultEnvelope.ok("用量分析查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行用量分析动作。
     *
     * @param action 操作名称。
     * @param days 统计天数。
     * @return 返回服务结果。
     */
    private Map<String, Object> run(String action, Integer days) throws Exception {
        return dashboardAnalyticsService.getUsage(days == null ? 30 : days.intValue());
    }
}
