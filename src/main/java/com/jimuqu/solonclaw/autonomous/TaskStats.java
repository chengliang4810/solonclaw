package com.jimuqu.solonclaw.autonomous;

/**
 * 任务统计
 *
 * @author SolonClaw
 */
public record TaskStats(
        long totalCreated,
        long totalCompleted,
        long totalFailed,
        double successRate,
        int pendingCount,
        int executingCount,
        int completedCount
) {
}