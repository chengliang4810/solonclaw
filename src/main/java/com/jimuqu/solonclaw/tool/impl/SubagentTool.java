package com.jimuqu.solonclaw.tool.impl;

import com.jimuqu.solonclaw.agent.subagent.SubagentSpawnService;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.ai.annotation.ToolMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 Agent 工具
 * <p>
 * 允许 Agent 生成子 Agent 来处理子任务
 * 支持任务分解和并行执行
 *
 * @author SolonClaw
 */
@Component
public class SubagentTool {

    private static final Logger log = LoggerFactory.getLogger(SubagentTool.class);

    @Inject(required = false)
    private SubagentSpawnService subagentSpawnService;

    /**
     * 生成子 Agent
     * <p>
     * 当需要将复杂任务分解为多个子任务时使用此工具
     * 子 Agent 将在独立的会话中执行，并将结果返回给父 Agent
     *
     * @param task 任务描述，详细说明子 Agent 需要完成的任务
     * @param taskLabel 任务标签，用于标识和跟踪任务（简短描述）
     * @param timeoutSeconds 超时时间（秒），默认 300 秒
     * @param replyInstruction 回复指令，指导子 Agent 如何回复结果
     * @param modelId 模型 ID，为子 Agent 选择特定的模型
     * @param thinkingLevel 思考级别，可选值: off, minimal, low, medium, high, xhigh, adaptive
     * @param threadRequested 是否启用线程绑定（支持多轮对话）
     * @param spawnMode 生成模式: run（一次性）或 session（持久会话）
     * @return 子 Agent 的执行结果
     */
    @ToolMapping(
            name = "spawn_subagent",
            description = """
                    生成一个子 Agent 来处理独立的子任务。
                    使用场景：
                    - 需要将复杂任务分解为多个小任务
                    - 需要并行执行多个独立任务
                    - 需要在隔离的上下文中执行任务

                    参数说明：
                    - task: 详细的任务描述
                    - taskLabel: 简短的任务标签（用于标识）
                    - timeoutSeconds: 超时时间（秒），默认 300
                    - replyInstruction: 回复指令，告诉子 Agent 如何输出结果
                    - modelId: 可选，为子 Agent 选择特定模型
                    - thinkingLevel: 可选，思考级别（off/minimal/low/medium/high/xhigh/adaptive）
                    - threadRequested: 可选，是否启用多轮对话支持
                    - spawnMode: 可选，run（一次性）或 session（持久会话）
                    """
    )
    public String spawnSubagent(
            String task,
            String taskLabel,
            Integer timeoutSeconds,
            String replyInstruction,
            String modelId,
            String thinkingLevel,
            Boolean threadRequested,
            String spawnMode
    ) {
        log.info("Agent 调用生成子 Agent: taskLabel={}, model={}, thinking={}, thread={}, mode={}",
                taskLabel, modelId, thinkingLevel, threadRequested, spawnMode);

        try {
            // 获取当前会话 ID（这里简化处理，实际应该从上下文获取）
            String parentSessionKey = getCurrentSessionKey();

            // 创建生成参数
            SubagentSpawnService.SpawnParams params = new SubagentSpawnService.SpawnParams(
                    parentSessionKey,
                    taskLabel,
                    task
            );

            // 设置可选参数
            if (replyInstruction != null && !replyInstruction.isEmpty()) {
                params.setReplyInstruction(replyInstruction);
            }

            if (modelId != null && !modelId.isEmpty()) {
                params.setModelId(modelId);
            }

            if (thinkingLevel != null && !thinkingLevel.isEmpty()) {
                params.setThinkingLevel(thinkingLevel);
            }

            if (timeoutSeconds != null && timeoutSeconds > 0) {
                params.setTimeoutSeconds(timeoutSeconds);
            }

            if (threadRequested != null && threadRequested) {
                params.setThreadRequested(true);
            }

            if ("session".equals(spawnMode)) {
                params.setSpawnMode(SubagentSpawnService.SpawnMode.SESSION);
            }

            // 同步生成子 Agent
            int timeout = timeoutSeconds != null && timeoutSeconds > 0 ?
                    timeoutSeconds : 300;

            SubagentSpawnService.SpawnResult result = subagentSpawnService.spawn(params, timeout);

            if (result.success()) {
                log.info("子 Agent 执行成功: taskLabel={}, resultLength={}",
                        taskLabel, result.result() != null ? result.result().length() : 0);
                return formatSuccessResult(taskLabel, result.childSessionKey(), result.result());
            } else {
                log.warn("子 Agent 执行失败: taskLabel={}, message={}",
                        taskLabel, result.message());
                return formatErrorResult(taskLabel, result.message());
            }

        } catch (Exception e) {
            log.error("生成子 Agent 异常: taskLabel={}", taskLabel, e);
            return formatErrorResult(taskLabel, "执行异常: " + e.getMessage());
        }
    }

    /**
     * 获取当前会话键
     * <p>
     * TODO: 实际实现应该从上下文或请求中获取
     */
    private String getCurrentSessionKey() {
        // 简化实现：使用默认会话
        // 在实际应用中，应该从请求上下文中获取
        return "default";
    }

    /**
     * 格式化成功结果
     */
    private String formatSuccessResult(String taskLabel, String childSessionKey, String result) {
        return String.format("""
                ## 子任务完成：%s

                子会话键：%s

                %s

                ---
                *此任务由子 Agent 在独立会话中完成*
                """, taskLabel, childSessionKey != null ? childSessionKey : "N/A", result != null ? result : "（无输出）");
    }

    /**
     * 格式化错误结果
     */
    private String formatErrorResult(String taskLabel, String errorMessage) {
        return String.format("""
                ## 子任务失败：%s

                错误：%s

                ---
                *子 Agent 执行失败，请检查任务描述或重试*
                """, taskLabel, errorMessage);
    }
}
