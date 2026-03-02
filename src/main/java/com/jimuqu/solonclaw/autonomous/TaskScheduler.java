package com.jimuqu.solonclaw.autonomous;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务调度器
 * <p>
 * 管理、调度和执行自主运行任务
 *
 * @author SolonClaw
 */
@Component
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    @Inject
    private AutonomousConfig config;

    /**
     * 任务队列
     */
    private final Map<String, AutonomousTask> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, AutonomousTask> executingTasks = new ConcurrentHashMap<>();
    private final Map<String, AutonomousTask> completedTasks = new ConcurrentHashMap<>();

    private long totalTasksCreated = 0;
    private long totalTasksCompleted = 0;
    private long totalTasksFailed = 0;

    /**
     * 初始化
     */
    public void init() {
        log.info("初始化任务调度器");

        // 创建初始任务
        createInitialTasks();

        log.info("任务调度器初始化完成，待执行任务: {}", pendingTasks.size());
    }

    /**
     * 创建初始任务
     */
    private void createInitialTasks() {
        // 创建自我检查任务
        AutonomousTask selfCheckTask = new AutonomousTask(
            TaskType.SELF_CHECK,
            "检查系统状态和资源可用性",
            1
        );
        scheduleTask(selfCheckTask);

        // 创建目标检查任务
        AutonomousTask goalCheckTask = new AutonomousTask(
            TaskType.GOAL_CHECK,
            "检查目标进度和完成状态",
            2
        );
        scheduleTask(goalCheckTask);

        // 创建知识更新任务
        AutonomousTask knowledgeUpdateTask = new AutonomousTask(
            TaskType.KNOWLEDGE_UPDATE,
            "更新和整合新知识",
            3
        );
        scheduleTask(knowledgeUpdateTask);
    }

    /**
     * 安排任务
     */
    public void scheduleTask(AutonomousTask task) {
        if (task == null) {
            log.warn("任务为空，跳过");
            return;
        }

        String taskId = generateTaskId();
        AutonomousTask newTask = task.withId(taskId);

        pendingTasks.put(taskId, newTask);
        totalTasksCreated++;

        log.info("安排任务: id={}, type={}, priority={}, description={}",
            taskId, task.getType(), task.getPriority(), task.getDescription());
    }

    /**
     * 获取下一个待执行任务
     * <p>
     * 按优先级和类型排序
     */
    public AutonomousTask getNextTask() {
        if (pendingTasks.isEmpty()) {
            return null;
        }

        // 按优先级排序（数字越小优先级越高）
        List<AutonomousTask> sortedTasks = pendingTasks.values().stream()
            .sorted(Comparator.comparing(AutonomousTask::getPriority))
            .collect(Collectors.toList());

        AutonomousTask nextTask = sortedTasks.get(0);

        // 从待执行队列移除
        pendingTasks.remove(nextTask.getId());

        // 添加到执行队列
        executingTasks.put(nextTask.getId(), nextTask);

        return nextTask;
    }

    /**
     * 执行任务
     */
    public boolean executeTask(AutonomousTask task) {
        if (task == null) {
            return false;
        }

        log.info("开始执行任务: id={}, type={}", task.getId(), task.getType());

        try {
            // 根据任务类型执行不同的逻辑
            boolean success = switch (task.getType()) {
                case SELF_CHECK -> executeSelfCheck(task);
                case GOAL_CHECK -> executeGoalCheck(task);
                case KNOWLEDGE_UPDATE -> executeKnowledgeUpdate(task);
                case SKILL_INSTALL -> executeSkillInstall(task);
                case REFLECTION -> executeReflection(task);
                case FOLLOW_UP -> executeFollowUp(task);
                case CUSTOM -> executeCustomTask(task);
            };

            // 更新任务状态
            if (success) {
                completeTask(task);
                totalTasksCompleted++;
                log.info("任务执行成功: id={}", task.getId());
            } else {
                failTask(task);
                totalTasksFailed++;
                log.warn("任务执行失败: id={}", task.getId());
            }

            return success;

        } catch (Exception e) {
            log.error("执行任务异常: id={}", task.getId(), e);
            failTask(task);
            totalTasksFailed++;
            return false;
        }
    }

    /**
     * 执行自我检查任务
     */
    private boolean executeSelfCheck(AutonomousTask task) {
        try {
            log.info("执行自我检查任务");

            // TODO: 实现自我检查逻辑
            // 1. 检查系统资源
            // 2. 检查依赖服务
            // 3. 检查技能状态
            // 4. 检查知识库状态

            return true;

        } catch (Exception e) {
            log.error("自我检查失败", e);
            return false;
        }
    }

    /**
     * 执行目标检查任务
     */
    private boolean executeGoalCheck(AutonomousTask task) {
        try {
            log.info("执行目标检查任务");

            // TODO: 实现目标检查逻辑
            // 1. 检查所有活跃目标的进度
            // 2. 识别已完成的目标
            // 3. 创建新任务以推进目标

            return true;

        } catch (Exception e) {
            log.error("目标检查失败", e);
            return false;
        }
    }

    /**
     * 执行知识更新任务
     */
    private boolean executeKnowledgeUpdate(AutonomousTask task) {
        try {
            log.info("执行知识更新任务");

            // TODO: 实现知识更新逻辑
            // 1. 检索新经验
            // 2. 整合到知识库
            // 3. 验证知识质量

            return true;

        } catch (Exception e) {
            log.error("知识更新失败", e);
            return false;
        }
    }

    /**
     * 执行技能安装任务
     */
    private boolean executeSkillInstall(AutonomousTask task) {
        try {
            log.info("执行技能安装任务");

            // TODO: 实现技能安装逻辑
            // 1. 分析需要的技能
            // 2. 下载或创建技能
            // 3. 注册技能
            // 4. 验证技能可用性

            return true;

        } catch (Exception e) {
            log.error("技能安装失败", e);
            return false;
        }
    }

    /**
     * 执行反思任务
     */
    private boolean executeReflection(AutonomousTask task) {
        try {
            log.info("执行反思任务");

            // TODO: 实现反思逻辑
            // 1. 分析最近的对话
            // 2. 提取成功和失败模式
            // 3. 生成经验条目

            return true;

        } catch (Exception e) {
            log.error("反思失败", e);
            return false;
        }
    }

    /**
     * 执行后续任务
     */
    private boolean executeFollowUp(AutonomousTask task) {
        try {
            log.info("执行后续任务");

            // TODO: 实现后续逻辑
            // 1. 根据任务结果决定后续行动
            // 2. 创建新任务或目标

            return true;

        } catch (Exception e) {
            log.error("后续任务执行失败", e);
            return false;
        }
    }

    /**
     * 执行自定义任务
     */
    private boolean executeCustomTask(AutonomousTask task) {
        try {
            log.info("执行自定义任务: {}", task.getDescription());

            // TODO: 实现自定义任务逻辑

            return true;

        } catch (Exception e) {
            log.error("自定义任务执行失败", e);
            return false;
        }
    }

    /**
     * 完成任务
     */
    private void completeTask(AutonomousTask task) {
        AutonomousTask completedTask = task.withStatus(TaskStatus.COMPLETED)
            .withCompletedAt(LocalDateTime.now());

        executingTasks.remove(task.getId());
        completedTasks.put(task.getId(), completedTask);

        log.debug("任务完成: id={}", task.getId());
    }

    /**
     * 失败任务
     */
    private void failTask(AutonomousTask task) {
        AutonomousTask failedTask = task.withStatus(TaskStatus.FAILED)
            .withCompletedAt(LocalDateTime.now());

        executingTasks.remove(task.getId());
        completedTasks.put(task.getId(), failedTask);

        log.debug("任务失败: id={}", task.getId());
    }

    /**
     * 获取任务
     */
    public AutonomousTask getTask(String taskId) {
        return pendingTasks.getOrDefault(taskId,
               executingTasks.getOrDefault(taskId,
               completedTasks.get(taskId)));
    }

    /**
     * 获取所有待执行任务
     */
    public List<AutonomousTask> getPendingTasks() {
        return new ArrayList<>(pendingTasks.values());
    }

    /**
     * 获取所有执行中的任务
     */
    public List<AutonomousTask> getExecutingTasks() {
        return new ArrayList<>(executingTasks.values());
    }

    /**
     * 获取所有已完成的任务
     */
    public List<AutonomousTask> getCompletedTasks() {
        return new ArrayList<>(completedTasks.values());
    }

    /**
     * 获取待执行任务数量
     */
    public int getPendingTaskCount() {
        return pendingTasks.size();
    }

    /**
     * 获取执行中任务数量
     */
    public int getExecutingTaskCount() {
        return executingTasks.size();
    }

    /**
     * 获取已完成任务数量
     */
    public int getCompletedTaskCount() {
        return completedTasks.size();
    }

    /**
     * 清理过期任务
     */
    public void cleanup() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

            // 清理已完成超过 24 小时的任务
            completedTasks.entrySet().removeIf(entry -> {
                AutonomousTask task = entry.getValue();
                if (task.getCompletedAt() != null && task.getCompletedAt().isBefore(cutoff)) {
                    log.debug("清理过期任务: id={}", task.getId());
                    return true;
                }
                return false;
            });

        } catch (Exception e) {
            log.error("清理任务失败", e);
        }
    }

    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        if (totalTasksCompleted + totalTasksFailed == 0) {
            return 0.0;
        }
        return (double) totalTasksCompleted / (totalTasksCompleted + totalTasksFailed);
    }

    /**
     * 生成任务 ID
     */
    private String generateTaskId() {
        return "task-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 获取统计信息
     */
    public TaskStats getStats() {
        return new TaskStats(
            totalTasksCreated,
            totalTasksCompleted,
            totalTasksFailed,
            getSuccessRate(),
            pendingTasks.size(),
            executingTasks.size(),
            completedTasks.size()
        );
    }
}