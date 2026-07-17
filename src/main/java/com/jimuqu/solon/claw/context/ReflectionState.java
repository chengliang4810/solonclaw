package com.jimuqu.solon.claw.context;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 保存跨会话反思的最近运行结果和输入指纹。 */
@Getter
@Setter
@NoArgsConstructor
public class ReflectionState {
    /** 尚未运行。 */
    public static final String OUTCOME_NEVER_RUN = "NEVER_RUN";

    /** 功能已关闭。 */
    public static final String OUTCOME_DISABLED = "DISABLED";

    /** 近期没有足够的真实会话。 */
    public static final String OUTCOME_NO_EVIDENCE = "NO_EVIDENCE";

    /** 输入未变化。 */
    public static final String OUTCOME_UNCHANGED = "UNCHANGED";

    /** 已生成新的反思快照。 */
    public static final String OUTCOME_UPDATED = "UPDATED";

    /** 反思运行失败。 */
    public static final String OUTCOME_FAILED = "FAILED";

    /** 最近一次调度检查时间。 */
    private long lastTickAt;

    /** 最近一次成功更新时间。 */
    private long lastSuccessAt;

    /** 最近一次结果代码。 */
    private String lastOutcome = OUTCOME_NEVER_RUN;

    /** 最近一次脱敏结果说明。 */
    private String lastReason = "尚未执行跨会话反思。";

    /** 最近一次成功处理的输入摘要。 */
    private String lastInputDigest;

    /** 本轮扫描的会话数量。 */
    private int scannedSessionCount;

    /** 本轮实际采用的会话数量。 */
    private int selectedSessionCount;

    /** 本轮采用的用户消息数量。 */
    private int userMessageCount;

    /** 本轮采用的助手消息数量。 */
    private int assistantMessageCount;

    /** 最近一次快照中的洞察数量。 */
    private int insightCount;

    /** 连续失败次数。 */
    private int consecutiveFailureCount;

    /** 本轮输入是否因字符预算被截断。 */
    private boolean inputTruncated;
}
