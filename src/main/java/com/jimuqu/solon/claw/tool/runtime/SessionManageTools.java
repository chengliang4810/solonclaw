package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.web.DashboardSessionService;
import java.util.Collections;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 提供会话与检查点查询工具，复用 Dashboard 会话服务。 */
public class SessionManageTools {
    /** Dashboard 会话服务，用于复用会话列表、详情、轨迹和检查点预览逻辑。 */
    private final DashboardSessionService dashboardSessionService;

    /**
     * 创建会话管理工具。
     *
     * @param dashboardSessionService Dashboard 会话服务。
     */
    public SessionManageTools(DashboardSessionService dashboardSessionService) {
        this.dashboardSessionService = dashboardSessionService;
    }

    /**
     * 查询会话、轨迹、分支树、检查点预览并维护会话标题。
     *
     * @param action 操作名称。
     * @param sessionId 会话标识。
     * @param checkpointId 检查点标识。
     * @param userQuery 轨迹筛选查询。
     * @param completed 轨迹完成状态。
     * @param limit 列表数量上限。
     * @param offset 列表偏移量。
     * @param maxExchanges recap 最大轮次数。
     * @param title 会话新标题。
     * @return 返回工具结果 JSON。
     */
    @ToolMapping(
            name = "session_manage",
            description =
                    "Inspect and maintain sessions. Actions: list, messages, recap, trajectory, save_trajectory, update_title, tree, latest_descendant, checkpoints, checkpoint_preview, rollback_checkpoint, checkpoint_rollback, rollback.")
    public String sessionManage(
            @Param(
                            name = "action",
                            description =
                                    "list, messages, recap, trajectory, save_trajectory, update_title, tree, latest_descendant, checkpoints, checkpoint_preview, rollback_checkpoint, checkpoint_rollback, rollback")
                    String action,
            @Param(name = "session_id", required = false, description = "Session id")
                    String sessionId,
            @Param(name = "checkpoint_id", required = false, description = "Checkpoint id")
                    String checkpointId,
            @Param(name = "user_query", required = false, description = "Trajectory query")
                    String userQuery,
            @Param(
                            name = "completed",
                            required = false,
                            defaultValue = "false",
                            description = "Trajectory completed flag")
                    Boolean completed,
            @Param(
                            name = "limit",
                            required = false,
                            defaultValue = "20",
                            description = "Max rows for list")
                    Integer limit,
            @Param(
                            name = "offset",
                            required = false,
                            defaultValue = "0",
                            description = "List offset")
                    Integer offset,
            @Param(
                            name = "max_exchanges",
                            required = false,
                            defaultValue = "20",
                            description = "Max exchanges for recap")
                    Integer maxExchanges,
            @Param(name = "title", required = false, description = "New session title")
                    String title) {
        try {
            if (dashboardSessionService == null) {
                return ToolResultEnvelope.error("session service unavailable").toJson();
            }
            Map<String, Object> result =
                    run(action, sessionId, checkpointId, userQuery, completed, limit, offset,
                            maxExchanges, title);
            return ToolResultEnvelope.ok("会话查询完成")
                    .preview(SecretRedactor.redact(ONode.serialize(result), 3000))
                    .data("result", result)
                    .toJson();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResultEnvelope.error(SecretRedactor.redact(message, 1000)).toJson();
        }
    }

    /**
     * 保留现有测试和内部调用的旧签名，默认不修改标题。
     */
    public String sessionManage(
            String action,
            String sessionId,
            String checkpointId,
            String userQuery,
            Boolean completed,
            Integer limit,
            Integer offset,
            Integer maxExchanges) {
        return sessionManage(
                action, sessionId, checkpointId, userQuery, completed, limit, offset, maxExchanges,
                null);
    }

    /**
     * 执行会话管理动作。
     *
     * @param action 操作名称。
     * @param sessionId 会话标识。
     * @param checkpointId 检查点标识。
     * @param userQuery 轨迹筛选查询。
     * @param completed 轨迹完成状态。
     * @param limit 列表数量上限。
     * @param offset 列表偏移量。
     * @param maxExchanges recap 最大轮次数。
     * @param title 会话新标题。
     * @return 返回服务结果。
     */
    private Map<String, Object> run(
            String action,
            String sessionId,
            String checkpointId,
            String userQuery,
            Boolean completed,
            Integer limit,
            Integer offset,
            Integer maxExchanges,
            String title)
            throws Exception {
        String normalized =
                action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("messages".equals(normalized)) {
            return dashboardSessionService.getSessionMessages(sessionId);
        }
        if ("recap".equals(normalized)) {
            return dashboardSessionService.recap(sessionId, safeInt(maxExchanges, 20));
        }
        if ("trajectory".equals(normalized)) {
            return dashboardSessionService.trajectory(
                    sessionId, userQuery, completed != null && completed.booleanValue());
        }
        if ("save_trajectory".equals(normalized)) {
            return dashboardSessionService.saveTrajectory(
                    sessionId, userQuery, completed != null && completed.booleanValue());
        }
        if ("update_title".equals(normalized)) {
            return dashboardSessionService.updateSession(
                    sessionId, Collections.<String, Object>singletonMap("title", title));
        }
        if ("tree".equals(normalized)) {
            return dashboardSessionService.sessionTree(sessionId);
        }
        if ("latest_descendant".equals(normalized)) {
            return dashboardSessionService.latestDescendant(sessionId);
        }
        if ("checkpoints".equals(normalized)) {
            return dashboardSessionService.checkpoints(sessionId);
        }
        if ("checkpoint_preview".equals(normalized)) {
            return dashboardSessionService.checkpointPreview(checkpointId);
        }
        if ("rollback_checkpoint".equals(normalized)
                || "checkpoint_rollback".equals(normalized)
                || "rollback".equals(normalized)) {
            return dashboardSessionService.rollbackCheckpoint(checkpointId);
        }
        return dashboardSessionService.getSessions(safeInt(limit, 20), safeInt(offset, 0));
    }

    /**
     * 读取整数参数，空值时使用默认值。
     *
     * @param value 输入值。
     * @param defaultValue 默认值。
     * @return 返回安全整数。
     */
    private int safeInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value.intValue();
    }
}
