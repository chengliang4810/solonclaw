package com.jimuqu.solonclaw.autonomous;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自主运行配置
 * <p>
 * 管理 SolonClaw 自主运行系统的配置参数
 *
 * @author SolonClaw
 */
@Configuration
public class AutonomousConfig {

    private static final Logger log = LoggerFactory.getLogger(AutonomousConfig.class);

    /**
     * 是否启用自主运行
     */
    @Inject("${solonclaw.autonomous.enabled:true}")
    private boolean enabled = true;

    /**
     * 运行循环 Cron 表达式
     */
    @Inject("${solonclaw.autonomous.runCron:0/30 * * * * ?}")
    private String runCron = "0/30 * * * * ?"; // 每30秒

    /**
     * 最大并发任务数
     */
    @Inject("${solonclaw.autonomous.maxConcurrentTasks:3}")
    private int maxConcurrentTasks = 3;

    /**
     * 任务超时时间（秒）
     */
    @Inject("${solonclaw.autonomous.taskTimeoutSeconds:300}")
    private int taskTimeoutSeconds = 300;

    /**
     * 目标最大数量
     */
    @Inject("${solonclaw.autonomous.maxGoals:20}")
    private int maxGoals = 20;

    /**
     * 任务队列最大大小
     */
    @Inject("${solonclaw.autonomous.maxTaskQueueSize:100}")
    private int maxTaskQueueSize = 100;

    /**
     * 是否启用自动技能安装
     */
    @Inject("${solonclaw.autonomous.autoSkillInstall:true}")
    private boolean autoSkillInstall = true;

    /**
     * 是否启用自动反思
     */
    @Inject("${solonclaw.autonomous.autoReflection:true}")
    private boolean autoReflection = true;

    /**
     * 决策引擎置信度阈值
     */
    @Inject("${solonclaw.autonomous.decisionConfidenceThreshold:0.7}")
    private double decisionConfidenceThreshold = 0.7;

    /**
     * 资源清理间隔（小时）
     */
    @Inject("${solonclaw.autonomous.cleanupIntervalHours:24}")
    private int cleanupIntervalHours = 24;

    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 启用自主运行
     */
    public void enable() {
        this.enabled = true;
        log.info("自主运行已启用");
    }

    /**
     * 禁用自主运行
     */
    public void disable() {
        this.enabled = false;
        log.info("自主运行已禁用");
    }

    // Getters
    public String getRunCron() {
        return runCron;
    }

    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public int getTaskTimeoutSeconds() {
        return taskTimeoutSeconds;
    }

    public int getMaxGoals() {
        return maxGoals;
    }

    public int getMaxTaskQueueSize() {
        return maxTaskQueueSize;
    }

    public boolean isAutoSkillInstall() {
        return autoSkillInstall;
    }

    public boolean isAutoReflection() {
        return autoReflection;
    }

    public double getDecisionConfidenceThreshold() {
        return decisionConfidenceThreshold;
    }

    public int getCleanupIntervalHours() {
        return cleanupIntervalHours;
    }

    // Setters
    public void setRunCron(String runCron) {
        this.runCron = runCron;
    }

    public void setMaxConcurrentTasks(int maxConcurrentTasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    public void setTaskTimeoutSeconds(int taskTimeoutSeconds) {
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    public void setMaxGoals(int maxGoals) {
        this.maxGoals = maxGoals;
    }

    public void setMaxTaskQueueSize(int maxTaskQueueSize) {
        this.maxTaskQueueSize = maxTaskQueueSize;
    }

    public void setAutoSkillInstall(boolean autoSkillInstall) {
        this.autoSkillInstall = autoSkillInstall;
    }

    public void setAutoReflection(boolean autoReflection) {
        this.autoReflection = autoReflection;
    }

    public void setDecisionConfidenceThreshold(double decisionConfidenceThreshold) {
        this.decisionConfidenceThreshold = decisionConfidenceThreshold;
    }

    public void setCleanupIntervalHours(int cleanupIntervalHours) {
        this.cleanupIntervalHours = cleanupIntervalHours;
    }
}