package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 同一 source/session busy 时的调度决策。 */
@Getter
@Setter
@NoArgsConstructor
public class RunBusyDecision {
    /** 记录运行Busy中的策略。 */
    private String policy;

    /** 记录运行Busy中的状态。 */
    private String status;

    /** 记录运行Busy中的消息。 */
    private String message;

    /** 记录运行Busy中的运行标识。 */
    private String runId;

    /** 记录运行Busy中的队列标识。 */
    private String queueId;

    /** 标记是否需要运行Now。 */
    private boolean shouldRunNow;

    /** 是否启用排队。 */
    private boolean queued;

    /** 是否启用拒绝。 */
    private boolean rejected;

    /**
     * 运行Now。
     *
     * @param policy 策略参数。
     * @return 返回Now结果。
     */
    public static RunBusyDecision runNow(String policy) {
        RunBusyDecision decision = new RunBusyDecision();
        decision.setPolicy(policy);
        decision.setStatus("run_now");
        decision.setShouldRunNow(true);
        return decision;
    }
}
