package com.jimuqu.solonclaw.gateway;

import com.jimuqu.solonclaw.autonomous.*;
import com.jimuqu.solonclaw.autonomous.DecisionEngine.Decision;
import com.jimuqu.solonclaw.common.Result;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Body;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 自主运行控制器
 * <p>
 * 提供 SolonClaw 自主运行系统的管理和监控接口
 *
 * @author SolonClaw
 */
@Controller
@Mapping("/api/autonomous")
public class AutonomousController {

    private static final Logger log = LoggerFactory.getLogger(AutonomousController.class);

    @Inject
    private AutonomousRunner autonomousRunner;

    @Inject
    private TaskScheduler taskScheduler;

    @Inject
    private GoalManager goalManager;

    @Inject
    private ResourceManager resourceManager;

    @Inject
    private DecisionEngine decisionEngine;

    @Inject
    private AutonomousConfig autonomousConfig;

    /**
     * 获取自主运行状态
     */
    @Get
    @Mapping("/status")
    public Result getStatus() {
        try {
            AutonomousRunner.AutonomousStatus status = autonomousRunner.getStatus();
            return Result.success(status);
        } catch (Exception e) {
            log.error("获取自主运行状态失败", e);
            return Result.failure("获取状态失败: " + e.getMessage());
        }
    }

    /**
     * 获取统计信息
     */
    @Get
    @Mapping("/stats")
    public Result getStats() {
        try {
            AutonomousRunner.AutonomousStats stats = autonomousRunner.getStats();
            return Result.success("获取统计信息成功", stats);
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return Result.failure("获取统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 启动自主运行
     */
    @Post
    @Mapping("/start")
    public Result start() {
        try {
            autonomousRunner.start();
            return Result.success("自主运行系统已启动");
        } catch (Exception e) {
            log.error("启动自主运行失败", e);
            return Result.failure("启动失败: " + e.getMessage());
        }
    }

    /**
     * 停止自主运行
     */
    @Post
    @Mapping("/stop")
    public Result stop() {
        try {
            autonomousRunner.stop();
            return Result.success("自主运行系统已停止");
        } catch (Exception e) {
            log.error("停止自主运行失败", e);
            return Result.failure("停止失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发任务
     */
    @Post
    @Mapping("/tasks/{taskId}/trigger")
    public Result triggerTask(String taskId) {
        try {
            autonomousRunner.triggerTask(taskId);
            return Result.success("任务已触发", Map.of("taskId", taskId));
        } catch (Exception e) {
            log.error("触发任务失败: taskId={}", taskId, e);
            return Result.failure("触发任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取待执行任务列表
     */
    @Get
    @Mapping("/tasks/pending")
    public Result getPendingTasks() {
        try {
            List<AutonomousTask> tasks = taskScheduler.getPendingTasks();
            return Result.success("获取待执行任务成功", tasks);
        } catch (Exception e) {
            log.error("获取待执行任务失败", e);
            return Result.failure("获取任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取执行中的任务列表
     */
    @Get
    @Mapping("/tasks/executing")
    public Result getExecutingTasks() {
        try {
            List<AutonomousTask> tasks = taskScheduler.getExecutingTasks();
            return Result.success("获取执行中任务成功", tasks);
        } catch (Exception e) {
            log.error("获取执行中任务失败", e);
            return Result.failure("获取任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取已完成的任务列表
     */
    @Get
    @Mapping("/tasks/completed")
    public Result getCompletedTasks() {
        try {
            List<AutonomousTask> tasks = taskScheduler.getCompletedTasks();
            return Result.success("获取已完成任务成功", tasks);
        } catch (Exception e) {
            log.error("获取已完成任务失败", e);
            return Result.failure("获取任务失败: " + e.getMessage());
        }
    }

    /**
     * 获取任务统计
     */
    @Get
    @Mapping("/tasks/stats")
    public Result getTaskStats() {
        try {
            TaskStats stats = taskScheduler.getStats();
            return Result.success("获取任务统计成功", stats);
        } catch (Exception e) {
            log.error("获取任务统计失败", e);
            return Result.failure("获取统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取活跃目标列表
     */
    @Get
    @Mapping("/goals/active")
    public Result getActiveGoals() {
        try {
            List<Goal> goals = goalManager.getActiveGoals();
            return Result.success("获取活跃目标成功", goals);
        } catch (Exception e) {
            log.error("获取活跃目标失败", e);
            return Result.failure("获取目标失败: " + e.getMessage());
        }
    }

    /**
     * 获取已完成目标列表
     */
    @Get
    @Mapping("/goals/completed")
    public Result getCompletedGoals() {
        try {
            List<Goal> goals = goalManager.getCompletedGoals();
            return Result.success("获取已完成目标成功", goals);
        } catch (Exception e) {
            log.error("获取已完成目标失败", e);
            return Result.failure("获取目标失败: " + e.getMessage());
        }
    }

    /**
     * 获取失败目标列表
     */
    @Get
    @Mapping("/goals/failed")
    public Result getFailedGoals() {
        try {
            List<Goal> goals = goalManager.getFailedGoals();
            return Result.success("获取失败目标成功", goals);
        } catch (Exception e) {
            log.error("获取失败目标失败", e);
            return Result.failure("获取目标失败: " + e.getMessage());
        }
    }

    /**
     * 创建新目标
     */
    @Post
    @Mapping("/goals")
    public Result createGoal(@Body CreateGoalRequest request) {
        try {
            Goal goal = new Goal(
                request.title() != null ? request.title() : "新目标",
                request.description() != null ? request.description() : "",
                request.priority() != null ? request.priority() : 5,
                request.parentId()
            );
            Goal createdGoal = goalManager.createGoal(goal);
            if (createdGoal == null) {
                return Result.failure("创建目标失败");
            }
            return Result.success("目标创建成功", Map.of("goalId", createdGoal.getId()));
        } catch (Exception e) {
            log.error("创建目标失败", e);
            return Result.failure("创建目标失败: " + e.getMessage());
        }
    }

    /**
     * 完成目标
     */
    @Post
    @Mapping("/goals/{goalId}/complete")
    public Result completeGoal(String goalId) {
        try {
            boolean completed = goalManager.completeGoal(goalId);
            if (completed) {
                return Result.success("目标已完成", Map.of("goalId", goalId));
            } else {
                return Result.failure("完成目标失败");
            }
        } catch (Exception e) {
            log.error("完成目标失败: goalId={}", goalId, e);
            return Result.failure("完成目标失败: " + e.getMessage());
        }
    }

    /**
     * 更新目标进度
     */
    @Post
    @Mapping("/goals/{goalId}/progress")
    public Result updateGoalProgress(String goalId, double progress) {
        try {
            goalManager.updateGoalProgress(goalId, progress);
            return Result.success("目标进度已更新", Map.of("goalId", goalId, "progress", progress));
        } catch (Exception e) {
            log.error("更新目标进度失败: goalId={}", goalId, e);
            return Result.failure("更新进度失败: " + e.getMessage());
        }
    }

    /**
     * 获取目标统计
     */
    @Get
    @Mapping("/goals/stats")
    public Result getGoalStats() {
        try {
            GoalStats stats = goalManager.getStats();
            return Result.success("获取目标统计成功", stats);
        } catch (Exception e) {
            log.error("获取目标统计失败", e);
            return Result.failure("获取统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取可用资源列表
     */
    @Get
    @Mapping("/resources")
    public Result getResources() {
        try {
            Map<String, Resource> resources = resourceManager.getAvailableResources();
            return Result.success("获取资源成功", resources);
        } catch (Exception e) {
            log.error("获取资源失败", e);
            return Result.failure("获取资源失败: " + e.getMessage());
        }
    }

    /**
     * 获取资源统计
     */
    @Get
    @Mapping("/resources/stats")
    public Result getResourceStats() {
        try {
            Map<String, Object> stats = resourceManager.getResourceStats();
            return Result.success("获取资源统计成功", stats);
        } catch (Exception e) {
            log.error("获取资源统计失败", e);
            return Result.failure("获取统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取资源使用情况
     */
    @Get
    @Mapping("/resources/usage")
    public Result getResourceUsage() {
        try {
            Map<String, Object> usage = resourceManager.getResourceUsage();
            return Result.success("获取资源使用情况成功", usage);
        } catch (Exception e) {
            log.error("获取资源使用情况失败", e);
            return Result.failure("获取使用情况失败: " + e.getMessage());
        }
    }

    /**
     * 决策下一步行动
     */
    @Post
    @Mapping("/decision")
    public Result decideNextAction(
            @Param("completedGoalId") String completedGoalId) {
        try {
            DecisionEngine.Decision decision;
            if (completedGoalId != null && !completedGoalId.isEmpty()) {
                Goal completedGoal = goalManager.getGoal(completedGoalId);
                decision = decisionEngine.decideNextAction(completedGoal);
            } else {
                decision = decisionEngine.decideNextAction(null);
            }

            // 转换为 DTO 返回
            DecisionDto dto = DecisionDto.from(decision);
            return Result.success("决策完成", dto);
        } catch (Exception e) {
            log.error("决策失败", e);
            // 返回默认决策而不是错误
            DecisionDto defaultDto = new DecisionDto(
                "WAIT",
                "等待新任务",
                1.0,
                "系统正常，等待新任务或目标"
            );
            return Result.success("决策完成", defaultDto);
        }
    }

    /**
     * 获取决策状态（GET 方法，用于前端轮询）
     */
    @Get
    @Mapping("/decision")
    public Result getDecisionStatus() {
        try {
            DecisionEngine.Decision decision = decisionEngine.decideNextAction(null);
            DecisionDto dto = DecisionDto.from(decision);
            return Result.success("决策状态", dto);
        } catch (Exception e) {
            log.error("获取决策状态失败", e);
            DecisionDto defaultDto = new DecisionDto(
                "WAIT",
                "等待新任务",
                1.0,
                "系统正常，等待新任务或目标"
            );
            return Result.success("决策状态", defaultDto);
        }
    }

    /**
     * 获取配置
     */
    @Get
    @Mapping("/config")
    public Result getConfig() {
        try {
            return Result.success("获取配置成功", Map.of(
                "enabled", autonomousConfig.isEnabled(),
                "runCron", autonomousConfig.getRunCron(),
                "maxConcurrentTasks", autonomousConfig.getMaxConcurrentTasks(),
                "taskTimeoutSeconds", autonomousConfig.getTaskTimeoutSeconds(),
                "maxGoals", autonomousConfig.getMaxGoals(),
                "maxTaskQueueSize", autonomousConfig.getMaxTaskQueueSize(),
                "autoSkillInstall", autonomousConfig.isAutoSkillInstall(),
                "autoReflection", autonomousConfig.isAutoReflection(),
                "decisionConfidenceThreshold", autonomousConfig.getDecisionConfidenceThreshold(),
                "cleanupIntervalHours", autonomousConfig.getCleanupIntervalHours()
            ));
        } catch (Exception e) {
            log.error("获取配置失败", e);
            return Result.failure("获取配置失败: " + e.getMessage());
        }
    }

    /**
     * 更新配置
     */
    @Post
    @Mapping("/config")
    public Result updateConfig(Map<String, Object> config) {
        try {
            if (config.containsKey("enabled")) {
                boolean enabled = (Boolean) config.get("enabled");
                if (enabled) {
                    autonomousConfig.enable();
                } else {
                    autonomousConfig.disable();
                }
            }

            if (config.containsKey("runCron")) {
                autonomousConfig.setRunCron((String) config.get("runCron"));
            }

            if (config.containsKey("maxConcurrentTasks")) {
                autonomousConfig.setMaxConcurrentTasks((Integer) config.get("maxConcurrentTasks"));
            }

            if (config.containsKey("autoSkillInstall")) {
                autonomousConfig.setAutoSkillInstall((Boolean) config.get("autoSkillInstall"));
            }

            if (config.containsKey("autoReflection")) {
                autonomousConfig.setAutoReflection((Boolean) config.get("autoReflection"));
            }

            return Result.success("配置已更新");
        } catch (Exception e) {
            log.error("更新配置失败", e);
            return Result.failure("更新配置失败: " + e.getMessage());
        }
    }

    /**
     * 启用自主运行
     */
    @Post
    @Mapping("/enable")
    public Result enable() {
        try {
            autonomousConfig.enable();
            return Result.success("自主运行已启用");
        } catch (Exception e) {
            log.error("启用自主运行失败", e);
            return Result.failure("启用失败: " + e.getMessage());
        }
    }

    /**
     * 禁用自主运行
     */
    @Post
    @Mapping("/disable")
    public Result disable() {
        try {
            autonomousConfig.disable();
            return Result.success("自主运行已禁用");
        } catch (Exception e) {
            log.error("禁用自主运行失败", e);
            return Result.failure("禁用失败: " + e.getMessage());
        }
    }

    /**
     * 创建目标请求
     */
    public record CreateGoalRequest(
            String title,
            String description,
            Integer priority,
            String parentId
    ) {
    }
}