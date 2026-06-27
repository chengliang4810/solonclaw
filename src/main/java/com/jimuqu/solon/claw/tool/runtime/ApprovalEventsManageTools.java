package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardApprovalEventsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供审批事件只读查询工具，复用 Dashboard 审批事件服务。 */
public class ApprovalEventsManageTools {
    /** Dashboard 审批事件服务，用于复用最近事件和统计聚合逻辑。 */
    private final DashboardApprovalEventsService approvalEventsService;

    /**
     * 创建审批事件管理工具。
     *
     * @param approvalEventsService Dashboard 审批事件服务。
     */
    public ApprovalEventsManageTools(DashboardApprovalEventsService approvalEventsService) {
        this.approvalEventsService = approvalEventsService;
    }

    /**
     * 查询审批事件或统计。
     *
     * @param action 操作名称。
     * @param limit 最大返回数量。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "approval_events_manage",
            description = "Inspect dashboard approval events. Actions: events, stats.")
    public String approvalEventsManage(
            @Param(name = "action", description = "events, stats") String action,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "50",
                            description = "Max events for action=events")
                    Integer limit) {
        try {
            if (approvalEventsService == null) {
                return ToolResultEnvelope.error("approval events service unavailable").toJson();
            }
            Map<String, Object> result = run(action, limit);
            return ToolResultEnvelope.ok("审批事件查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行审批事件查询动作。
     *
     * @param action 操作名称。
     * @param limit 最大返回数量。
     * @return 返回 Dashboard 审批事件服务结果。
     */
    private Map<String, Object> run(String action, Integer limit) {
        String normalized =
                action == null ? "events" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("stats".equals(normalized)) {
            return approvalEventsService.stats();
        }
        int safeLimit = limit == null ? 50 : Math.min(Math.max(1, limit.intValue()), 200);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        java.util.List<Map<String, Object>> events = approvalEventsService.recentEvents(safeLimit);
        result.put("events", events);
        result.put("count", Integer.valueOf(events.size()));
        return result;
    }
}
