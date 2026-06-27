package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardDiagnosticsService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供审批队列和确认队列只读查询工具，复用 Dashboard 诊断服务。 */
public class ApprovalQueueManageTools {
    /** Dashboard 诊断服务，用于读取 pending/history/always/slash confirm 队列。 */
    private final DashboardDiagnosticsService diagnosticsService;

    /**
     * 创建审批队列管理工具。
     *
     * @param diagnosticsService Dashboard 诊断服务。
     */
    public ApprovalQueueManageTools(DashboardDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    /**
     * 查询待处理审批、历史审批、长期授权或斜杠确认队列。
     *
     * @param action 操作名称。
     * @param limit 最大返回数量。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "approval_queue_manage",
            description =
                    "Inspect dashboard approval queues. Actions: pending, history, always, slash_confirms, summary.")
    public String approvalQueueManage(
            @Param(name = "action", description = "pending, history, always, slash_confirms, summary")
                    String action,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "50",
                            description = "Max queue items")
                    Integer limit) {
        try {
            if (diagnosticsService == null) {
                return ToolResultEnvelope.error("approval queue service unavailable").toJson();
            }
            Map<String, Object> result = run(action, limit);
            return ToolResultEnvelope.ok("审批队列查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 执行审批队列只读查询动作。
     *
     * @param action 操作名称。
     * @param limit 最大返回数量。
     * @return 返回 Dashboard 诊断服务结果。
     */
    private Map<String, Object> run(String action, Integer limit) throws Exception {
        String normalized =
                action == null ? "pending" : action.trim().toLowerCase(Locale.ROOT);
        int safeLimit = limit == null ? 50 : Math.min(Math.max(1, limit.intValue()), 200);
        if ("history".equals(normalized)) {
            return diagnosticsService.approvalHistory(safeLimit);
        }
        if ("always".equals(normalized)) {
            return diagnosticsService.alwaysApprovals(safeLimit);
        }
        if ("slash_confirms".equals(normalized) || "slash-confirms".equals(normalized)) {
            return diagnosticsService.pendingSlashConfirms(safeLimit);
        }
        if ("summary".equals(normalized)) {
            return summary(safeLimit);
        }
        return diagnosticsService.pendingApprovals(safeLimit);
    }

    /**
     * 聚合审批相关队列的数量，便于自然语言先判断是否需要深入查询。
     *
     * @param limit 每类队列的最大读取数量。
     * @return 返回聚合结果。
     */
    private Map<String, Object> summary(int limit) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("pending", diagnosticsService.pendingApprovals(limit));
        result.put("history", diagnosticsService.approvalHistory(limit));
        result.put("always", diagnosticsService.alwaysApprovals(limit));
        result.put("slash_confirms", diagnosticsService.pendingSlashConfirms(limit));
        return result;
    }
}
