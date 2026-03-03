package com.jimuqu.solonclaw.autonomous;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 决策引擎
 * <p>
 * 基于 AI 的决策系统，分析当前状态并决定下一步行动
 *
 * @author SolonClaw
 */
@Component
public class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);

    @Inject
    private ChatModel chatModel;

    @Inject
    private GoalManager goalManager;

    @Inject
    private ResourceManager resourceManager;

    /**
     * 决策提示词模板
     */
    private static final String DECISION_PROMPT = """
        你是 SolonClaw AI Agent 的决策引擎。请分析当前状态并决定下一步行动。

        ## 当前状态

        ### 活跃目标 (%d 个)
        %s

        ### 可用资源
        %s

        ### 最近完成的任务
        %s

        ## 决策要求

        1. 分析当前状态，评估优先级
        2. 决定下一步应该做什么（任务或目标）
        3. 如果需要创建新任务，提供任务描述和优先级
        4. 如果需要创建新目标，提供目标标题和描述

        ## 输出格式（JSON）
        {
            "analysis": "当前状态分析",
            "nextAction": {
                "type": "task|goal|wait",
                "description": "详细描述",
                "priority": 1-10,
                "estimatedDuration": "5m|15m|1h"
            },
            "followUpAction": {
                "description": "后续行动描述",
                "priority": 1-10
            },
            "followUpGoal": {
                "title": "目标标题",
                "description": "目标描述",
                "priority": 1-10,
                "deadline": "时间期限（可选）"
            },
            "resourceNeeds": ["需要1", "需要2"]
        }
        """;

    /**
     * 决定下一步行动
     * <p>
     * 基于当前状态使用 AI 决策
     *
     * @param completedGoal 最近完成的目标（可选）
     * @return 决策结果
     */
    public Decision decideNextAction(Goal completedGoal) {
        try {
            log.info("开始决策: completedGoal={}",
                completedGoal != null ? completedGoal.getTitle() : "无");

            // 如果没有 chatModel，使用默认决策
            if (chatModel == null) {
                log.warn("ChatModel 不可用，使用默认决策");
                return getDefaultDecision();
            }

            // 构建当前状态描述
            String currentState = buildCurrentState();

            // 使用 AI 决策
            String prompt = String.format(DECISION_PROMPT,
                goalManager.getActiveGoalCount(),
                formatGoals(goalManager.getActiveGoals()),
                formatResourcesAsObjects(resourceManager.getAvailableResources()),
                "最近未完成任务"
            );

            String fullPrompt = "你是 SolonClaw AI Agent 的决策引擎。\n\n" + prompt;
            ChatResponse response = chatModel.prompt(fullPrompt).call();

            String aiResponse = response.getContent();

            // 解析 AI 响应
            Map<String, Object> decisionData = parseJsonResponse(aiResponse);

            // 构建决策结果
            return buildDecision(decisionData);

        } catch (Exception e) {
            log.error("决策失败，使用默认策略", e);
            return getDefaultDecision();
        }
    }

    /**
     * 批量决策
     * <p>
     * 为多个任务决定执行顺序
     *
     * @param tasks 待决策的任务列表
     * @return 排序后的任务列表
     */
    public List<AutonomousTask> prioritizeTasks(List<AutonomousTask> tasks) {
        try {
            if (chatModel == null) {
                log.warn("ChatModel 不可用，使用原始顺序");
                return tasks;
            }

            if (tasks == null || tasks.isEmpty()) {
                return List.of();
            }

            // 构建任务描述
            String tasksDescription = tasks.stream()
                .map(t -> String.format("- %s (优先级: %d, 类型: %s): %s",
                    t.getId(), t.getPriority(), t.getType(), t.getDescription()))
                .collect(Collectors.joining("\n"));

            String prompt = String.format("""
                请为以下任务按优先级排序：

                %s

                返回排序后的任务ID列表（JSON格式）：
                {
                    "taskIds": ["id1", "id2", ...]
                }
                """, tasksDescription);

            String fullPrompt = "你是任务调度专家。\n\n" + prompt;
            ChatResponse response = chatModel.prompt(fullPrompt).call();

            Map<String, Object> result = parseJsonResponse(response.getContent());

            @SuppressWarnings("unchecked")
            List<String> sortedIds = (List<String>) result.get("taskIds");

            if (sortedIds == null || sortedIds.isEmpty()) {
                return tasks; // 返回原列表
            }

            // 按排序后的 ID 列表重新排序任务
            return tasks.stream()
                .sorted((a, b) -> {
                    int indexA = sortedIds.indexOf(a.getId());
                    int indexB = sortedIds.indexOf(b.getId());
                    return Integer.compare(indexA, indexB);
                })
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("批量决策失败，使用原始顺序", e);
            return tasks;
        }
    }

    /**
     * 评估资源需求
     * <p>
     * 分析任务所需的资源
     *
     * @param task 任务
     * @return 资源需求列表
     */
    public List<String> assessResourceNeeds(AutonomousTask task) {
        try {
            if (chatModel == null) {
                log.warn("ChatModel 不可用，返回空资源列表");
                return List.of();
            }

            String prompt = String.format("""
                分析以下任务需要的资源：

                任务类型: %s
                任务描述: %s

                可能需要的资源类型：
                - skills (技能)
                - tools (工具)
                - knowledge (知识)
                - time (时间)
                - compute (计算资源)

                返回所需资源列表（JSON格式）：
                {
                    "resources": ["resource1", "resource2", ...]
                }
                """, task.getType(), task.getDescription());

            String fullPrompt = "你是资源评估专家。\n\n" + prompt;
            ChatResponse response = chatModel.prompt(fullPrompt).call();

            Map<String, Object> result = parseJsonResponse(response.getContent());

            @SuppressWarnings("unchecked")
            List<String> resources = (List<String>) result.get("resources");

            return resources != null ? resources : List.of();

        } catch (Exception e) {
            log.error("评估资源需求失败", e);
            return List.of();
        }
    }

    /**
     * 构建当前状态描述
     */
    private String buildCurrentState() {
        StringBuilder sb = new StringBuilder();
        sb.append("活跃目标: ").append(goalManager.getActiveGoalCount()).append("\n");
        sb.append("待执行任务: ").append(goalManager.getActiveGoalCount()).append("\n");
        sb.append("可用资源: ").append(resourceManager.getAvailableResources().size()).append("\n");
        return sb.toString();
    }

    /**
     * 格式化目标列表
     */
    private String formatGoals(List<Goal> goals) {
        if (goals == null || goals.isEmpty()) {
            return "无活跃目标";
        }

        return goals.stream()
            .map(g -> String.format("- %s (优先级: %d, 进度: %.1f%%): %s",
                g.getTitle(), g.getPriority(), g.getProgress() * 100, g.getDescription()))
            .collect(Collectors.joining("\n"));
    }

    /**
     * 格式化资源列表
     */
    private String formatResources(Map<String, Object> resources) {
        if (resources == null || resources.isEmpty()) {
            return "无可用资源";
        }

        return resources.entrySet().stream()
            .map(e -> String.format("- %s: %s", e.getKey(), e.getValue()))
            .collect(Collectors.joining("\n"));
    }

    /**
     * 格式化资源列表（从 Map<String, Resource> 转换）
     */
    private String formatResourcesAsObjects(Map<String, Resource> resources) {
        if (resources == null || resources.isEmpty()) {
            return "无可用资源";
        }

        return resources.entrySet().stream()
            .map(e -> String.format("- %s: %s", e.getKey(), e.getValue().getDescription()))
            .collect(Collectors.joining("\n"));
    }

    /**
     * 构建决策结果
     */
    private Decision buildDecision(Map<String, Object> decisionData) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nextActionData = (Map<String, Object>) decisionData.get("nextAction");

        String actionType = nextActionData != null ? (String) nextActionData.getOrDefault("type", "wait") : "wait";
        String description = nextActionData != null ? (String) nextActionData.getOrDefault("description", "") : "";
        int priority = nextActionData != null ? (int) nextActionData.getOrDefault("priority", 5) : 5;

        NextAction nextAction = new NextAction(
            ActionType.valueOf(actionType.toUpperCase()),
            description,
            priority
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> followUpActionData = (Map<String, Object>) decisionData.get("followUpAction");

        NextAction followUpAction = null;
        if (followUpActionData != null) {
            followUpAction = new NextAction(
                ActionType.TASK,
                (String) followUpActionData.getOrDefault("description", ""),
                (int) followUpActionData.getOrDefault("priority", 5)
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> followUpGoalData = (Map<String, Object>) decisionData.get("followUpGoal");

        Goal followUpGoal = null;
        if (followUpGoalData != null) {
            followUpGoal = new Goal(
                (String) followUpGoalData.getOrDefault("title", ""),
                (String) followUpGoalData.getOrDefault("description", ""),
                (int) followUpGoalData.getOrDefault("priority", 5),
                null
            );
        }

        @SuppressWarnings("unchecked")
        List<String> resourceNeeds = (List<String>) decisionData.get("resourceNeeds");

        String analysis = (String) decisionData.getOrDefault("analysis", "");

        return new Decision(
            analysis,
            nextAction,
            followUpAction,
            followUpGoal,
            resourceNeeds != null ? resourceNeeds : List.of()
        );
    }

    /**
     * 获取默认决策
     */
    private Decision getDefaultDecision() {
        return new Decision(
            "使用默认决策策略",
            new NextAction(ActionType.WAIT, "等待新任务", 5),
            null,
            null,
            List.of()
        );
    }

    /**
     * 解析 JSON 响应
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonResponse(String jsonResponse) {
        try {
            // 简化实现：返回默认值
            // TODO: 使用合适的 JSON 库解析
            log.debug("JSON 响应: {}", jsonResponse);
            return Map.of(
                "actionType", "CONTINUE",
                "description", "默认继续执行",
                "nextAction", Map.of("type", "CONTINUE", "priority", 5),
                "followUpAction", Map.of("type", "NONE"),
                "resourceNeeds", List.of()
            );
        } catch (Exception e) {
            log.warn("解析 AI 响应失败", e);
            return Map.of();
        }
    }

    /**
     * 决策结果
     */
    public record Decision(
            String analysis,
            NextAction nextAction,
            NextAction followUpAction,
            Goal followUpGoal,
            List<String> resourceNeeds
    ) {
    }

    /**
     * 下一步行动
     */
    public record NextAction(
            ActionType type,
            String description,
            int priority
    ) {
    }

    /**
     * 行动类型
     */
    public enum ActionType {
        TASK,
        GOAL,
        WAIT
    }
}