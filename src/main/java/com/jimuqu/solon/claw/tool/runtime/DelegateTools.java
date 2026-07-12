package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.DelegationResult;
import com.jimuqu.solon.claw.core.model.DelegationTask;
import com.jimuqu.solon.claw.core.model.ToolResultEnvelope;
import com.jimuqu.solon.claw.core.service.DelegationService;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 子代理委托工具。 */
@RequiredArgsConstructor
public class DelegateTools {
    /** 委托服务。 */
    private final DelegationService delegationService;

    /** 当前来源键。 */
    private final String sourceKey;

    /** 支持单任务与批量委托。 */
    @ToolMapping(
            name = "delegate_task",
            description =
                    "Delegate one goal or a structured task list. Each task has goal, optional context, and optional role leaf/orchestrator.")
    public String delegateTask(
            @Param(name = "goal", description = "单任务目标；与 tasks 二选一", required = false) String goal,
            @Param(name = "context", description = "委托补充上下文", required = false) String context,
            @Param(name = "tasks", description = "批量结构化任务，每项必须包含 goal", required = false)
                    List<DelegateTaskInput> tasks,
            @Param(name = "role", description = "子代理角色：leaf 或 orchestrator", required = false)
                    String role,
            @Param(name = "background", description = "协议字段；执行方式由运行时决定，模型值不改变调度", required = false)
                    Boolean background)
            throws Exception {
        if (delegationService == null) {
            return error("Delegate tool is not ready");
        }

        try {
            String topRole = normalizeRole(role);
            if (topRole == null) {
                return error("role must be leaf or orchestrator");
            }
            if (tasks != null && !tasks.isEmpty()) {
                List<DelegationTask> items = toTasks(tasks, context, topRole);
                if (items == null) {
                    return error("each task requires a goal and role must be leaf or orchestrator");
                }
                if (delegationService.shouldRunInBackground()) {
                    return SecretRedactor.redact(
                            ONode.serialize(
                                    delegationService.delegateInBackground(sourceKey, items)),
                            20000);
                }
                List<DelegationResult> results = delegationService.delegateBatch(sourceKey, items);
                return SecretRedactor.redact(ONode.serialize(results), 20000);
            }
            if (StrUtil.isBlank(goal)) {
                return error("Provide either goal or tasks");
            }
            DelegationTask task = new DelegationTask();
            task.setName("delegate");
            task.setPrompt(goal.trim());
            task.setContext(context);
            task.setRole(topRole);
            if (delegationService.shouldRunInBackground()) {
                return SecretRedactor.redact(
                        ONode.serialize(
                                delegationService.delegateInBackground(
                                        sourceKey, java.util.Collections.singletonList(task))),
                        20000);
            }
            DelegationResult result = delegationService.delegateSingle(sourceKey, task);
            return SecretRedactor.redact(result == null ? null : result.getContent(), 20000);
        } catch (Exception e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 将模型传入的结构化任务转换为领域任务。
     *
     * @param tasks 结构化任务。
     * @param sharedContext 顶层共享上下文。
     * @param topRole 顶层角色。
     * @return 参数非法时返回 null，否则返回领域任务列表。
     */
    private List<DelegationTask> toTasks(
            List<DelegateTaskInput> tasks, String sharedContext, String topRole) {
        List<DelegationTask> items = new ArrayList<DelegationTask>();
        for (int i = 0; i < tasks.size(); i++) {
            DelegateTaskInput input = tasks.get(i);
            if (input == null || StrUtil.isBlank(input.getGoal())) {
                return null;
            }
            String itemRole = normalizeRole(StrUtil.blankToDefault(input.getRole(), topRole));
            if (itemRole == null) {
                return null;
            }
            DelegationTask task = new DelegationTask();
            task.setName("delegate-" + (i + 1));
            task.setPrompt(input.getGoal().trim());
            task.setContext(StrUtil.blankToDefault(input.getContext(), sharedContext));
            task.setRole(itemRole);
            items.add(task);
        }
        return items;
    }

    /**
     * 规范化子代理角色。
     *
     * @param role 原始角色。
     * @return leaf/orchestrator；非法值返回 null。
     */
    private String normalizeRole(String role) {
        String normalized =
                StrUtil.blankToDefault(role, "leaf").trim().toLowerCase(java.util.Locale.ROOT);
        if ("leaf".equals(normalized) || "orchestrator".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    /**
     * 执行错误相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回error结果。
     */
    private String error(String message) {
        return ToolResultEnvelope.error(
                        SecretRedactor.redact(
                                StrUtil.blankToDefault(message, "delegate failed"), 1000))
                .toJson();
    }

    /** 模型可见的结构化委派任务。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class DelegateTaskInput {
        /** 子任务目标，批量模式必填。 */
        @Param(description = "Task goal")
        private String goal;

        /** 子任务专属上下文；为空时使用顶层 context。 */
        @Param(description = "Task-specific context", required = false)
        private String context;

        /** 子任务角色；为空时使用顶层 role。 */
        @Param(description = "leaf or orchestrator", required = false)
        private String role;

    }
}
