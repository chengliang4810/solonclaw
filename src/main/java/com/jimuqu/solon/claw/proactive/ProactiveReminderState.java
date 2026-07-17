package com.jimuqu.solon.claw.proactive;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 保存主动提醒最近一次运行结果、活跃度决策和投递状态。 */
@Getter
@Setter
@NoArgsConstructor
public class ProactiveReminderState {
    /** 尚未执行过调度检查。 */
    public static final String OUTCOME_NEVER_RUN = "NEVER_RUN";

    /** 主动提醒已关闭。 */
    public static final String OUTCOME_DISABLED = "DISABLED";

    /** 当前处于免打扰时段。 */
    public static final String OUTCOME_QUIET_HOURS = "QUIET_HOURS";

    /** 主动提醒投递配置无效。 */
    public static final String OUTCOME_CONFIG_INVALID = "CONFIG_INVALID";

    /** 没有可用的主对话。 */
    public static final String OUTCOME_NO_MAIN_CONVERSATION = "NO_MAIN_CONVERSATION";

    /** 当前活跃度累计额度不足。 */
    public static final String OUTCOME_ACTIVITY_CREDIT_LOW = "ACTIVITY_CREDIT_LOW";

    /** 模型决定本次保持静默。 */
    public static final String OUTCOME_MODEL_SILENT = "MODEL_SILENT";

    /** 相同话题仍处于冷却期。 */
    public static final String OUTCOME_TOPIC_COOLDOWN = "TOPIC_COOLDOWN";

    /** 主动提醒已经成功投递。 */
    public static final String OUTCOME_DELIVERED = "DELIVERED";

    /** 渠道投递失败。 */
    public static final String OUTCOME_DELIVERY_FAILED = "DELIVERY_FAILED";

    /** 调度检查出现未分类异常。 */
    public static final String OUTCOME_TICK_FAILED = "TICK_FAILED";

    /** 当前活跃度。 */
    private double activityLevel = 1D;

    /** 跨 tick 累计的发送额度。 */
    private double activityCredit;

    /** 连续未回应次数。 */
    private int unansweredCount;

    /** 最近一次调度检查时间。 */
    private long lastTickAt;

    /** 最近一次调度结果代码。 */
    private String lastOutcome = OUTCOME_NEVER_RUN;

    /** 最近一次调度结果的人类可读原因。 */
    private String lastReason = "尚未执行主动提醒检查。";

    /** 最近一次活跃度分析时间。 */
    private long lastAnalysisAt;

    /** 最近一次活跃度分析理由。 */
    private String analysisReason = "尚未执行活跃度分析。";

    /** 最近一次观察到的用户活动时间。 */
    private long lastUserActivityAt;

    /** 最近一次成功发送时间。 */
    private long lastSentAt;

    /** 最近一次主动提醒，用于避免重复话题。 */
    private String lastMessage;
}
