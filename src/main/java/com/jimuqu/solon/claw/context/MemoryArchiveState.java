package com.jimuqu.solon.claw.context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 保存旧每日记忆归档最近一次运行的低敏诊断状态。 */
@Getter
@Setter
@NoArgsConstructor
public class MemoryArchiveState {
    /** 尚未运行。 */
    public static final String OUTCOME_NEVER_RUN = "NEVER_RUN";

    /** 功能已关闭。 */
    public static final String OUTCOME_DISABLED = "DISABLED";

    /** 其他进程正在运行。 */
    public static final String OUTCOME_LOCKED = "LOCKED";

    /** 没有需要处理的文件。 */
    public static final String OUTCOME_NO_WORK = "NO_WORK";

    /** 全部处理成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    /** 部分文件处理失败。 */
    public static final String OUTCOME_PARTIAL_FAILURE = "PARTIAL_FAILURE";

    /** 本轮完全失败。 */
    public static final String OUTCOME_FAILED = "FAILED";

    /** 最近一次开始时间。 */
    private long lastStartedAt;

    /** 最近一次完成时间。 */
    private long lastCompletedAt;

    /** 最近一次结果代码。 */
    private String lastOutcome = OUTCOME_NEVER_RUN;

    /** 本轮选中的活动文件或缺失摘要数量。 */
    private int selectedCount;

    /** 本轮完成不可变原文归档的数量。 */
    private int archivedCount;

    /** 本轮由辅助模型生成摘要的数量。 */
    private int summarizedByAiCount;

    /** 本轮由数据回退生成摘要的数量。 */
    private int summarizedByFallbackCount;

    /** 本轮送入现有记忆写入或审批链路的候选数量。 */
    private int memoryCandidateCount;

    /** 本轮失败文件数量。 */
    private int failedCount;

    /** 最近一次脱敏错误摘要。 */
    private String lastError = "";

    /** 最近一次 AI 降级为数据摘要的脱敏原因。 */
    private String lastFallbackReason = "";

    /** 最近一次运行耗时，单位毫秒。 */
    private long durationMs;
}
