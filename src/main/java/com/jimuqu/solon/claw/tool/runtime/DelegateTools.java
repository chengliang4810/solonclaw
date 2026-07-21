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
                    "Delegate one goal or a structured task list to one-time leaf agents. Provide the model, necessary context, and an explicit tool whitelist.")
    public String delegateTask(
            @Param(name = "goal", description = "单任务目标；与 tasks 二选一", required = false) String goal,
            @Param(name = "context", description = "委托补充上下文", required = false) String context,
            @Param(name = "tasks", description = "批量结构化任务，每项必须包含 goal", required = false)
                    List<DelegateTaskInput> tasks,
            @Param(name = "model", description = "单任务使用的模型", required = false) String model,
            @Param(name = "allowed_tools", description = "单任务工具白名单", required = false)
                    List<String> allowedTools,
            @Param(name = "background", description = "协议字段；执行方式由运行时决定，模型值不改变调度", required = false)
                    Boolean background)
            throws Exception {
        if (delegationService == null) {
            return error("Delegate tool is not ready");
        }

        try {
            if (tasks != null && !tasks.isEmpty()) {
                List<DelegationTask> items = toTasks(tasks, context, model, allowedTools);
                if (items == null) {
                    return error("each task requires a goal, model, and explicit tool whitelist");
                }
                if (shouldRunInBackground()) {
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
            task.setModel(model);
            task.setAllowedTools(allowedTools);
            if (!validTask(task)) {
                return error("model and explicit tool whitelist are required");
            }
            if (shouldRunInBackground()) {
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

    /** 根据委派服务策略决定是否在后台运行。 */
    private boolean shouldRunInBackground() {
        return delegationService.shouldRunInBackground();
    }

    /**
     * 将模型传入的结构化任务转换为领域任务。
     *
     * @param tasks 结构化任务。
     * @param sharedContext 顶层共享上下文。
     * @param model 顶层模型。
     * @param allowedTools 顶层工具白名单。
     * @return 参数非法时返回 null，否则返回领域任务列表。
     */
    private List<DelegationTask> toTasks(
            List<DelegateTaskInput> tasks,
            String sharedContext,
            String model,
            List<String> allowedTools) {
        List<DelegationTask> items = new ArrayList<DelegationTask>();
        for (int i = 0; i < tasks.size(); i++) {
            DelegateTaskInput input = tasks.get(i);
            if (input == null || StrUtil.isBlank(input.getGoal())) {
                return null;
            }
            DelegationTask task = new DelegationTask();
            task.setName("delegate-" + (i + 1));
            task.setPrompt(input.getGoal().trim());
            task.setContext(StrUtil.blankToDefault(input.getContext(), sharedContext));
            task.setModel(StrUtil.blankToDefault(input.getModel(), model));
            task.setAllowedTools(
                    input.getAllowedTools() == null ? allowedTools : input.getAllowedTools());
            if (!validTask(task)) {
                return null;
            }
            items.add(task);
        }
        return items;
    }

    /**
     * 校验委托任务是否已显式指定模型和工具白名单。
     *
     * @param task 待校验的委托任务。
     * @return 参数完整时返回 true，否则返回 false。
     */
    private boolean validTask(DelegationTask task) {
        return task != null
                && StrUtil.isNotBlank(task.getModel())
                && task.getAllowedTools() != null;
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

        /** 子任务模型；为空时使用顶层 model。 */
        @Param(description = "Model for this task", required = false)
        private String model;

        /** 子任务工具白名单；为空时使用顶层 allowed_tools。 */
        @Param(description = "Explicit tool whitelist", required = false)
        private List<String> allowedTools;
    }
}
