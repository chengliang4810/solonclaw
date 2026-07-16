package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 协作任务单次执行记录，用于保留修改后的描述、失败原因和耗时。 */
@Getter
@Setter
@NoArgsConstructor
public class ProfileTaskAttemptRecord {
    /** 任务标识。 */
    private String taskId;

    /** 从一开始的执行序号。 */
    private int attempt;

    /** 本次执行采用的任务描述快照。 */
    private String prompt;

    /** 本次执行状态。 */
    private String status;

    /** 本次执行结果。 */
    private String result;

    /** 本次执行错误摘要。 */
    private String error;

    /** 开始时间。 */
    private long startedAt;

    /** 结束时间；运行中为零。 */
    private long completedAt;
}
