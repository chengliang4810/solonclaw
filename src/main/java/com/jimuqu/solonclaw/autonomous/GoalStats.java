package com.jimuqu.solonclaw.autonomous;

/**
 * 目标统计
 *
 * @author SolonClaw
 */
public record GoalStats(
        long totalCreated,
        long totalCompleted,
        double completionRate,
        int activeCount,
        int completedCount,
        int failedCount
) {
}