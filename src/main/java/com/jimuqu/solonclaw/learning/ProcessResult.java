package com.jimuqu.solonclaw.learning;

/**
 * 处理结果
 *
 * @author SolonClaw
 */
public record ProcessResult(
        int totalProcessed,
        int approved,
        int waitingConfirmation
) {
}