package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 定时任务单次执行历史。 */
@Getter
@Setter
@NoArgsConstructor
public class CronJobRunRecord {
    /** 执行 ID。 */
    private String runId;

    /** 任务 ID。 */
    private String jobId;

    /** 来源键。 */
    private String sourceKey;

    /** scheduled 或 manual。 */
    private String triggerType;

    /** 第几次执行。 */
    private int attempt;

    /** 开始时间。 */
    private long startedAt;

    /** 结束时间。 */
    private long finishedAt;

    /** ok 或 error。 */
    private String status;

    /** 输出摘要。 */
    private String output;

    /** 执行错误。 */
    private String error;

    /** 投递错误。 */
    private String deliveryError;

    /** 兼容旧 summary 字段。 */
    private String summary;
}
