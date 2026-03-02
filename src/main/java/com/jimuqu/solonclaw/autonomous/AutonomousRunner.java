package com.jimuqu.solonclaw.autonomous;

import com.jimuqu.solonclaw.autonomous.DecisionEngine.Decision;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自主运行主控制器
 * <p>
 * 协调所有自主运行组件，管理 Agent 的生命周期
 *
 * @author SolonClaw
 */
@Component
public class AutonomousRunner {

    private static final Logger log = LoggerFactory.getLogger(AutonomousRunner.class);

    @Inject
    private TaskScheduler taskScheduler;

    @Inject
    private DecisionEngine decisionEngine;

    @Inject
    private GoalManager goalManager;

    @Inject
    private ResourceManager resourceManager;

    @Inject
    private AutonomousConfig config;

    /**
     * 运行状态
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private LocalDateTime startTime;
    private LocalDateTime lastActiveTime;
    private long totalTasksExecuted = 0;
    private long totalGoalsCompleted = 0;

    /**
     * 初始化
     */
    @Init
    public void init() {
        if (!config.isEnabled()) {
            log.info("自主运行系统已禁用");
            return;
        }

        log.info("初始化 SolonClaw 自主运行系统");

        // 初始化各组件
        taskScheduler.init();
        goalManager.init();
        resourceManager.init();

        // 加载持久化的运行状态
        loadRunningState();

        log.info("自主运行系统初始化完成");
    }

    /**
     * 启动自主运行
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            startTime = LocalDateTime.now();
            lastActiveTime = LocalDateTime.now();

            log.info("自主运行系统已启动");

            // 创建初始目标
            createInitialGoals();

            // 开始执行任务
            executeNextTask();
        } else {
            log.warn("自主运行系统已在运行中");
        }
    }

    /**
     * 停止自主运行
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("自主运行系统已停止");
            saveRunningState();
        } else {
            log.warn("自主运行系统未在运行");
        }
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * 定时任务：自主运行循环
     * <p>
     * 每 30 秒执行一次，检查并执行下一个任务
     */
    @Scheduled(cron = "${solonclaw.autonomous.runCron:0/30 * * * * ?}")
    public void runLoop() {
        if (!isRunning.get()) {
            return;
        }

        if (!config.isEnabled()) {
            log.warn("自主运行功能已禁用");
            return;
        }

        try {
            // 更新最后活跃时间
            lastActiveTime = LocalDateTime.now();

            // 执行下一个任务
            executeNextTask();

            // 检查和完成目标
            checkAndCompleteGoals();

            // 清理过期的资源和任务
            cleanup();

        } catch (Exception e) {
            log.error("自主运行循环执行失败", e);
        }
    }

    /**
     * 执行下一个任务
     */
    private void executeNextTask() {
        try {
            // 获取待执行任务
            AutonomousTask nextTask = taskScheduler.getNextTask();

            if (nextTask == null) {
                log.debug("没有待执行任务");
                return;
            }

            log.info("执行任务: id={}, type={}, description={}",
                nextTask.getId(), nextTask.getType(), nextTask.getDescription());

            // 执行任务
            boolean success = taskScheduler.executeTask(nextTask);

            if (success) {
                totalTasksExecuted++;
                log.info("任务执行成功: id={}", nextTask.getId());
            } else {
                log.warn("任务执行失败: id={}", nextTask.getId());
            }

        } catch (Exception e) {
            log.error("执行任务失败", e);
        }
    }

    /**
     * 检查和完成目标
     */
    private void checkAndCompleteGoals() {
        try {
            List<Goal> goals = goalManager.getActiveGoals();

            for (Goal goal : goals) {
                if (goalManager.isGoalComplete(goal)) {
                    boolean completed = goalManager.completeGoal(goal.getId());
                    if (completed) {
                        totalGoalsCompleted++;
                        log.info("目标完成: id={}, title={}", goal.getId(), goal.getTitle());

                        // 根据完成的自动创建新目标
                        createFollowUpGoals(goal);
                    }
                }
            }

        } catch (Exception e) {
            log.error("检查目标完成状态失败", e);
        }
    }

    /**
     * 创建初始目标
     */
    private void createInitialGoals() {
        try {
            List<Goal> initialGoals = goalManager.getInitialGoals();

            for (Goal goal : initialGoals) {
                goalManager.createGoal(goal);
                log.info("创建初始目标: id={}, title={}", goal.getId(), goal.getTitle());
            }

        } catch (Exception e) {
            log.error("创建初始目标失败", e);
        }
    }

    /**
     * 创建后续目标
     * <p>
     * 根据已完成的自动创建新目标
     */
    private void createFollowUpGoals(Goal completedGoal) {
        try {
            // 使用决策引擎决定下一步行动
            Decision decision = decisionEngine.decideNextAction(completedGoal);

            if (decision.nextAction() != null) {
                AutonomousTask task = new AutonomousTask(
                    TaskType.FOLLOW_UP,
                    decision.nextAction().description(),
                    decision.nextAction().priority()
                );

                taskScheduler.scheduleTask(task);
                log.info("安排后续任务: {}", decision.nextAction().description());
            }

            if (decision.followUpGoal() != null) {
                goalManager.createGoal(decision.followUpGoal());
                log.info("创建后续目标: {}", decision.followUpGoal().getTitle());
            }

        } catch (Exception e) {
            log.error("创建后续目标失败", e);
        }
    }

    /**
     * 清理过期资源
     */
    private void cleanup() {
        try {
            // 清理过期任务
            taskScheduler.cleanup();

            // 清理过期资源
            resourceManager.cleanup();

        } catch (Exception e) {
            log.error("清理失败", e);
        }
    }

    /**
     * 手动触发任务执行
     */
    public void triggerTask(String taskId) {
        if (!isRunning.get()) {
            log.warn("自主运行系统未启动");
            return;
        }

        AutonomousTask task = taskScheduler.getTask(taskId);
        if (task != null) {
            taskScheduler.executeTask(task);
        } else {
            log.warn("任务不存在: taskId={}", taskId);
        }
    }

    /**
     * 获取运行状态
     */
    public AutonomousStatus getStatus() {
        return new AutonomousStatus(
            isRunning.get(),
            startTime,
            lastActiveTime,
            totalTasksExecuted,
            totalGoalsCompleted,
            taskScheduler.getPendingTaskCount(),
            goalManager.getActiveGoalCount(),
            resourceManager.getResourceStats()
        );
    }

    /**
     * 获取统计信息
     */
    public AutonomousStats getStats() {
        return new AutonomousStats(
            totalTasksExecuted,
            totalGoalsCompleted,
            taskScheduler.getSuccessRate(),
            goalManager.getCompletionRate(),
            resourceManager.getResourceUsage()
        );
    }

    /**
     * 保存运行状态
     */
    private void saveRunningState() {
        try {
            Map<String, Object> state = Map.of(
                "isRunning", isRunning.get(),
                "startTime", startTime != null ? startTime.toString() : null,
                "lastActiveTime", lastActiveTime != null ? lastActiveTime.toString() : null,
                "totalTasksExecuted", totalTasksExecuted,
                "totalGoalsCompleted", totalGoalsCompleted
            );

            // TODO: 持久化到文件或数据库
            log.debug("保存运行状态: {}", state);

        } catch (Exception e) {
            log.error("保存运行状态失败", e);
        }
    }

    /**
     * 加载运行状态
     */
    private void loadRunningState() {
        try {
            // TODO: 从文件或数据库加载
            log.debug("加载运行状态");

        } catch (Exception e) {
            log.error("加载运行状态失败", e);
        }
    }

    /**
     * 自主运行状态
     */
    public record AutonomousStatus(
            boolean running,
            LocalDateTime startTime,
            LocalDateTime lastActiveTime,
            long totalTasksExecuted,
            long totalGoalsCompleted,
            int pendingTasks,
            int activeGoals,
            Map<String, Object> resourceStats
    ) {
    }

    /**
     * 自主运行统计
     */
    public record AutonomousStats(
            long totalTasksExecuted,
            long totalGoalsCompleted,
            double taskSuccessRate,
            double goalCompletionRate,
            Map<String, Object> resourceUsage
    ) {
    }
}