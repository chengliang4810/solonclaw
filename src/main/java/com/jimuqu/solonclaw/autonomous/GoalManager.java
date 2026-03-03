package com.jimuqu.solonclaw.autonomous;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 目标管理器
 * <p>
 * 管理 Agent 的目标生命周期
 *
 * @author SolonClaw
 */
@Component
public class GoalManager {

    private static final Logger log = LoggerFactory.getLogger(GoalManager.class);

    @Inject(required = false)
    private ChatModel chatModel;

    /**
     * 目标存储
     */
    private final Map<String, Goal> activeGoals = new ConcurrentHashMap<>();
    private final Map<String, Goal> completedGoals = new ConcurrentHashMap<>();
    private final Map<String, Goal> failedGoals = new ConcurrentHashMap<>();

    private long totalGoalsCreated = 0;
    private long totalGoalsCompleted = 0;

    /**
     * 初始化
     */
    public void init() {
        log.info("初始化目标管理器");

        // 创建初始目标
        createInitialGoals();

        log.info("目标管理器初始化完成，活跃目标: {}", activeGoals.size());
    }

    /**
     * 创建初始目标
     */
    private void createInitialGoals() {
        // 主目标：提升 AI 能力
        Goal mainGoal = new Goal(
            "提升 SolonClaw AI 能力",
            "通过自我学习和改进，不断增强 SolonClaw 的能力和效率",
            1,
            null
        );
        createGoal(mainGoal);

        // 子目标 1：学习新技能
        Goal learnSkillsGoal = new Goal(
            "学习新技能",
            "识别和获取新技能，扩展 Agent 的能力范围",
            2,
            mainGoal.getId()
        );
        createGoal(learnSkillsGoal);

        // 子目标 2：优化决策
        Goal optimizeDecisionGoal = new Goal(
            "优化决策过程",
            "改进决策引擎，提高决策质量和效率",
            2,
            mainGoal.getId()
        );
        createGoal(optimizeDecisionGoal);

        // 子目标 3：增强知识库
        Goal enhanceKnowledgeGoal = new Goal(
            "增强知识库",
            "持续扩展和优化知识库，提高知识质量和覆盖范围",
            2,
            mainGoal.getId()
        );
        createGoal(enhanceKnowledgeGoal);
    }

    /**
     * 创建目标
     */
    public Goal createGoal(Goal goal) {
        if (goal == null) {
            log.warn("目标为空，跳过");
            return null;
        }

        String goalId = generateGoalId();
        Goal newGoal = goal.withId(goalId)
            .withCreatedAt(LocalDateTime.now())
            .withStatus(GoalStatus.ACTIVE)
            .withProgress(0.0);

        activeGoals.put(goalId, newGoal);
        totalGoalsCreated++;

        log.info("创建目标: id={}, title={}, parent={}",
            goalId, goal.getTitle(), goal.getParentId());

        return newGoal;
    }

    /**
     * 完成目标
     */
    public boolean completeGoal(String goalId) {
        Goal goal = activeGoals.get(goalId);
        if (goal == null) {
            log.warn("目标不存在或已完成: goalId={}", goalId);
            return false;
        }

        try {
            Goal completedGoal = goal.withStatus(GoalStatus.COMPLETED)
                .withProgress(1.0)
                .withCompletedAt(LocalDateTime.now());

            activeGoals.remove(goalId);
            completedGoals.put(goalId, completedGoal);
            totalGoalsCompleted++;

            log.info("目标完成: id={}, title={}", goalId, goal.getTitle());

            // 完成目标后，使用 AI 生成总结
            generateGoalSummary(completedGoal);

            return true;

        } catch (Exception e) {
            log.error("完成目标失败: goalId={}", goalId, e);
            return false;
        }
    }

    /**
     * 失败目标
     */
    public boolean failGoal(String goalId, String reason) {
        Goal goal = activeGoals.get(goalId);
        if (goal == null) {
            return false;
        }

        try {
            Goal failedGoal = goal.withStatus(GoalStatus.FAILED)
                .withCompletedAt(LocalDateTime.now())
                .withFailureReason(reason);

            activeGoals.remove(goalId);
            failedGoals.put(goalId, failedGoal);

            log.warn("目标失败: id={}, title={}, reason={}", goalId, goal.getTitle(), reason);

            return true;

        } catch (Exception e) {
            log.error("标记目标失败: goalId={}", goalId, e);
            return false;
        }
    }

    /**
     * 更新目标进度
     */
    public void updateGoalProgress(String goalId, double progress) {
        Goal goal = activeGoals.get(goalId);
        if (goal == null) {
            return;
        }

        Goal updatedGoal = goal.withProgress(Math.max(0.0, Math.min(1.0, progress)));
        activeGoals.put(goalId, updatedGoal);

        log.debug("更新目标进度: id={}, progress={}", goalId, progress);

        // 如果进度达到 100%，自动完成
        if (updatedGoal.getProgress() >= 1.0) {
            completeGoal(goalId);
        }
    }

    /**
     * 检查目标是否完成
     */
    public boolean isGoalComplete(Goal goal) {
        if (goal == null) {
            return false;
        }

        // 使用 AI 判断目标是否完成
        try {
            // 如果没有 chatModel，返回 false（未完成）
            if (chatModel == null) {
                log.warn("ChatModel 不可用，目标未完成");
                return false;
            }

            String prompt = String.format("""
                请判断以下目标是否已完成：

                目标标题: %s
                目标描述: %s
                当前进度: %.1f%%

                判断标准：
                1. 目标的主要需求是否已满足
                2. 关键指标是否已达成
                3. 是否可以认为目标已成功完成

                返回 JSON 格式：
                {
                    "isComplete": true/false,
                    "reason": "判断理由"
                }
                """, goal.getTitle(), goal.getDescription(), goal.getProgress() * 100);

            String fullPrompt = "你是目标评估专家。\n\n" + prompt;
            ChatResponse response = chatModel.prompt(fullPrompt).call();

            Map<String, Object> result = parseJsonResponse(response.getContent());

            return (boolean) result.getOrDefault("isComplete", false);

        } catch (Exception e) {
            log.warn("判断目标完成状态失败", e);
            return false;
        }
    }

    /**
     * 生成目标总结
     */
    private void generateGoalSummary(Goal goal) {
        try {
            // 如果没有 chatModel，生成简单的总结
            if (chatModel == null) {
                log.info("ChatModel 不可用，生成简单总结");
                log.info("目标总结: {} - {} (进度: {:.1f}%)",
                    goal.getTitle(), goal.getDescription(), goal.getProgress() * 100);
                return;
            }

            String prompt = String.format("""
                请为已完成的目标生成总结：

                目标标题: %s
                目标描述: %s
                创建时间: %s
                完成时间: %s

                生成总结应包括：
                1. 目标达成情况
                2. 学到的经验
                3. 可以改进的地方
                4. 后续建议

                返回 Markdown 格式的总结。
                """, goal.getTitle(), goal.getDescription(),
                goal.getCreatedAt(), goal.getCompletedAt());

            String fullPrompt = "你是目标总结专家。\n\n" + prompt;
            ChatResponse response = chatModel.prompt(fullPrompt).call();

            String summary = response.getContent();

            // TODO: 保存总结到知识库
            log.info("目标总结:\n{}", summary);

        } catch (Exception e) {
            log.error("生成目标总结失败", e);
        }
    }

    /**
     * 获取目标
     */
    public Goal getGoal(String goalId) {
        return activeGoals.getOrDefault(goalId,
               completedGoals.getOrDefault(goalId,
               failedGoals.get(goalId)));
    }

    /**
     * 获取所有活跃目标
     */
    public List<Goal> getActiveGoals() {
        return new ArrayList<>(activeGoals.values());
    }

    /**
     * 获取所有已完成目标
     */
    public List<Goal> getCompletedGoals() {
        return new ArrayList<>(completedGoals.values());
    }

    /**
     * 获取所有失败目标
     */
    public List<Goal> getFailedGoals() {
        return new ArrayList<>(failedGoals.values());
    }

    /**
     * 获取活跃目标数量
     */
    public int getActiveGoalCount() {
        return activeGoals.size();
    }

    /**
     * 获取子目标
     */
    public List<Goal> getChildGoals(String parentGoalId) {
        return activeGoals.values().stream()
            .filter(g -> parentGoalId.equals(g.getParentId()))
            .collect(Collectors.toList());
    }

    /**
     * 获取完成率
     */
    public double getCompletionRate() {
        if (totalGoalsCreated == 0) {
            return 0.0;
        }
        return (double) totalGoalsCompleted / totalGoalsCreated;
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
                "isComplete", false,
                "reason", "简化实现"
            );
        } catch (Exception e) {
            log.warn("解析 AI 响应失败", e);
            return Map.of();
        }
    }

    /**
     * 生成目标 ID
     */
    private String generateGoalId() {
        return "goal-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 获取初始目标列表
     */
    public List<Goal> getInitialGoals() {
        return List.of();
    }

    /**
     * 获取统计信息
     */
    public GoalStats getStats() {
        return new GoalStats(
            totalGoalsCreated,
            totalGoalsCompleted,
            getCompletionRate(),
            activeGoals.size(),
            completedGoals.size(),
            failedGoals.size()
        );
    }
}