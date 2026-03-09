package com.jimuqu.solonclaw.autonomous;

import com.jimuqu.solonclaw.autonomous.DecisionEngine.Decision;
import com.jimuqu.solonclaw.config.WorkspaceConfig.WorkspaceInfo;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
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

    @Inject(required = false)
    private WorkspaceInfo workspaceInfo;

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
                // 状态已变更，保存状态
                saveRunningState();
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

                        // 状态已变更，保存状态
                        saveRunningState();
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
        if (workspaceInfo == null || workspaceInfo.autonomousStateFile() == null) {
            log.warn("工作目录配置未初始化，无法保存运行状态");
            return;
        }

        Path stateFile = workspaceInfo.autonomousStateFile();
        try {
            // 构建状态数据
            Map<String, Object> state = new HashMap<>();
            state.put("isRunning", isRunning.get());
            state.put("startTime", startTime != null ? startTime.toString() : null);
            state.put("lastActiveTime", lastActiveTime != null ? lastActiveTime.toString() : null);
            state.put("totalTasksExecuted", totalTasksExecuted);
            state.put("totalGoalsCompleted", totalGoalsCompleted);
            state.put("savedAt", LocalDateTime.now().toString());

            // 转换为 JSON 格式
            String jsonContent = toJsonString(state);

            // 确保父目录存在
            Files.createDirectories(stateFile.getParent());

            // 写入文件
            try (BufferedWriter writer = Files.newBufferedWriter(stateFile)) {
                writer.write(jsonContent);
            }

            log.info("运行状态已保存: file={}, isRunning={}, tasksExecuted={}, goalsCompleted={}",
                stateFile.getFileName(), isRunning.get(), totalTasksExecuted, totalGoalsCompleted);

        } catch (IOException e) {
            log.error("保存运行状态到文件失败: file={}", stateFile, e);
        } catch (Exception e) {
            log.error("保存运行状态失败", e);
        }
    }

    /**
     * 加载运行状态
     */
    private void loadRunningState() {
        if (workspaceInfo == null || workspaceInfo.autonomousStateFile() == null) {
            log.warn("工作目录配置未初始化，无法加载运行状态");
            return;
        }

        Path stateFile = workspaceInfo.autonomousStateFile();
        if (!Files.exists(stateFile)) {
            log.info("运行状态文件不存在，使用默认状态: file={}", stateFile);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(stateFile)) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }

            // 解析 JSON 状态
            Map<String, Object> state = parseJsonString(content.toString());

            if (state != null) {
                // 恢复运行状态
                Boolean wasRunning = (Boolean) state.get("isRunning");
                if (wasRunning != null && wasRunning) {
                    log.info("检测到上次运行状态为运行中，重置为停止状态");
                    // 不自动恢复运行状态，需要手动启动
                }

                String startTimeStr = (String) state.get("startTime");
                if (startTimeStr != null) {
                    try {
                        startTime = LocalDateTime.parse(startTimeStr);
                    } catch (Exception e) {
                        log.warn("解析启动时间失败: {}", startTimeStr);
                    }
                }

                String lastActiveTimeStr = (String) state.get("lastActiveTime");
                if (lastActiveTimeStr != null) {
                    try {
                        lastActiveTime = LocalDateTime.parse(lastActiveTimeStr);
                    } catch (Exception e) {
                        log.warn("解析最后活跃时间失败: {}", lastActiveTimeStr);
                    }
                }

                Object tasksExecuted = state.get("totalTasksExecuted");
                if (tasksExecuted instanceof Number) {
                    totalTasksExecuted = ((Number) tasksExecuted).longValue();
                }

                Object goalsCompleted = state.get("totalGoalsCompleted");
                if (goalsCompleted instanceof Number) {
                    totalGoalsCompleted = ((Number) goalsCompleted).longValue();
                }

                log.info("运行状态已加载: tasksExecuted={}, goalsCompleted={}, startTime={}, lastActiveTime={}",
                    totalTasksExecuted, totalGoalsCompleted, startTime, lastActiveTime);
            }

        } catch (IOException e) {
            log.error("从文件加载运行状态失败: file={}", stateFile, e);
        } catch (Exception e) {
            log.error("加载运行状态失败", e);
        }
    }

    /**
     * 简单的 Map 转 JSON 字符串
     */
    private String toJsonString(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof Number) {
                sb.append(value);
            } else {
                sb.append("\"").append(escapeJsonString(value.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 简单的 JSON 字符串解析为 Map
     */
    private Map<String, Object> parseJsonString(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty() || !json.startsWith("{") || !json.endsWith("}")) {
            return result;
        }

        // 移除首尾大括号
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) {
            return result;
        }

        // 简单解析键值对
        int i = 0;
        while (i < json.length()) {
            // 找 key
            while (i < json.length() && json.charAt(i) != '"') i++;
            if (i >= json.length()) break;
            i++; // 跳过开始的引号
            int keyStart = i;
            while (i < json.length() && json.charAt(i) != '"') i++;
            String key = json.substring(keyStart, i);
            i++; // 跳过结束的引号

            // 找冒号
            while (i < json.length() && json.charAt(i) != ':') i++;
            i++; // 跳过冒号

            // 找 value
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;

            int valueStart = i;
            Object value;
            if (json.charAt(i) == '"') {
                // 字符串值
                i++; // 跳过开始的引号
                int strStart = i;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++; // 跳过转义字符
                    i++;
                }
                value = unescapeJsonString(json.substring(strStart, i));
                i++; // 跳过结束的引号
            } else if (json.charAt(i) == 't' || json.charAt(i) == 'f') {
                // 布尔值
                if (json.substring(i).startsWith("true")) {
                    value = true;
                    i += 4;
                } else if (json.substring(i).startsWith("false")) {
                    value = false;
                    i += 5;
                } else {
                    value = null;
                    while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                }
            } else if (json.charAt(i) == 'n') {
                // null
                value = null;
                i += 4;
            } else if (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-') {
                // 数字
                int numStart = i;
                while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.' || json.charAt(i) == '-')) {
                    i++;
                }
                String numStr = json.substring(numStart, i);
                if (numStr.contains(".")) {
                    value = Double.parseDouble(numStr);
                } else {
                    value = Long.parseLong(numStr);
                }
            } else {
                value = null;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
            }

            result.put(key, value);

            // 找下一个逗号
            while (i < json.length() && json.charAt(i) != ',') i++;
            if (i < json.length()) i++; // 跳过逗号
        }

        return result;
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 反转义 JSON 字符串
     */
    private String unescapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'b' -> { sb.append('\b'); i++; }
                    case 'f' -> { sb.append('\f'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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