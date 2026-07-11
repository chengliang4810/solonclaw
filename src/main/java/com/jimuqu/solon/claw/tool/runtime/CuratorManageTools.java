package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardCuratorService;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供技能维护报告和建议处理工具，复用 Dashboard 技能维护服务。 */
public class CuratorManageTools {
    /** Dashboard 技能维护服务，用于复用扫描、报告和建议处理逻辑。 */
    private final DashboardCuratorService dashboardCuratorService;

    /**
     * 创建技能维护管理工具。
     *
     * @param dashboardCuratorService Dashboard 技能维护服务。
     */
    public CuratorManageTools(DashboardCuratorService dashboardCuratorService) {
        this.dashboardCuratorService = dashboardCuratorService;
    }

    /**
     * 查询或管理技能维护任务。
     *
     * @param action 操作名称。
     * @param reportId 报告标识。
     * @param skillName 技能名称。
     * @param suggestion 维护建议内容。
     * @param force 是否强制运行维护扫描。
     * @param limit 返回数量上限。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "curator_manage",
            description =
                    "Manage skill curator reports and suggestions. Actions: status, run, pause, resume, list, detail, improvements, apply, ignore.")
    public String curatorManage(
            @Param(
                            name = "action",
                            description =
                                    "status, run, pause, resume, list, detail, improvements, apply, ignore")
                    String action,
            @Param(name = "report_id", required = false, description = "Curator report id")
                    String reportId,
            @Param(
                            name = "skill_name",
                            required = false,
                            description = "Skill name for apply or ignore")
                    String skillName,
            @Param(name = "suggestion", required = false, description = "Suggestion text")
                    String suggestion,
            @Param(
                            name = "force",
                            required = false,
                            defaultValue = "false",
                            description = "Force run")
                    Boolean force,
            @Param(name = "limit", required = false, defaultValue = "20", description = "Max rows")
                    Integer limit) {
        try {
            if (dashboardCuratorService == null) {
                return ToolResultEnvelope.error("curator service unavailable").toJson();
            }
            Map<String, Object> result = run(action, reportId, skillName, suggestion, force, limit);
            return ToolResultEnvelope.ok("技能维护管理完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行技能维护管理动作。
     *
     * @param action 操作名称。
     * @param reportId 报告标识。
     * @param skillName 技能名称。
     * @param suggestion 维护建议内容。
     * @param force 是否强制运行维护扫描。
     * @param limit 返回数量上限。
     * @return 返回服务结果。
     */
    private Map<String, Object> run(
            String action,
            String reportId,
            String skillName,
            String suggestion,
            Boolean force,
            Integer limit)
            throws Exception {
        String normalized =
                action == null ? "status" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("run".equals(normalized)) {
            return dashboardCuratorService.run(Boolean.TRUE.equals(force));
        }
        if ("pause".equals(normalized)) {
            return dashboardCuratorService.pause();
        }
        if ("resume".equals(normalized)) {
            return dashboardCuratorService.resume();
        }
        if ("list".equals(normalized)) {
            return dashboardCuratorService.list(limitValue(limit));
        }
        if ("detail".equals(normalized)) {
            return dashboardCuratorService.detail(reportId);
        }
        if ("improvements".equals(normalized)) {
            return dashboardCuratorService.improvements(limitValue(limit));
        }
        if ("apply".equals(normalized)) {
            return dashboardCuratorService.apply(skillName, suggestion);
        }
        if ("ignore".equals(normalized)) {
            return dashboardCuratorService.ignore(skillName, suggestion);
        }
        return dashboardCuratorService.status();
    }

    /**
     * 规范化列表数量上限。
     *
     * @param limit 原始上限。
     * @return 返回实际查询上限。
     */
    private int limitValue(Integer limit) {
        return limit == null ? 20 : limit.intValue();
    }
}
