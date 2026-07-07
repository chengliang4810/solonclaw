package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/** 承载目标判定相关状态和辅助逻辑。 */
@Getter
public class GoalVerdict {
    /** DONE的统一常量值。 */
    public static final String DONE = "done";

    /** CONTINUE的统一常量值。 */
    public static final String CONTINUE = "continue";

    /** SKIPPED的统一常量值。 */
    public static final String SKIPPED = "skipped";

    /** WAIT 的统一常量值。 */
    public static final String WAIT = "wait";

    /** 记录目标判定中的判定。 */
    private final String verdict;

    /** 记录目标判定中的原因。 */
    private final String reason;

    /**
     * 创建Goal Verdict实例，并注入运行所需依赖。
     *
     * @param verdict 判定参数。
     * @param reason 原因参数。
     */
    private GoalVerdict(String verdict, String reason) {
        this.verdict = verdict;
        this.reason = StrUtil.blankToDefault(reason, "no reason provided");
    }

    /**
     * 执行done相关逻辑。
     *
     * @param reason 原因参数。
     * @return 返回done结果。
     */
    public static GoalVerdict done(String reason) {
        return new GoalVerdict(DONE, reason);
    }

    /**
     * 执行continue目标相关逻辑。
     *
     * @param reason 原因参数。
     * @return 返回continue Goal结果。
     */
    public static GoalVerdict continueGoal(String reason) {
        return new GoalVerdict(CONTINUE, reason);
    }

    /**
     * 执行skipped相关逻辑。
     *
     * @param reason 原因参数。
     * @return 返回skipped结果。
     */
    public static GoalVerdict skipped(String reason) {
        return new GoalVerdict(SKIPPED, reason);
    }

    /**
     * 执行waiting相关逻辑。
     *
     * @param reason 原因参数。
     * @return 返回waiting结果。
     */
    public static GoalVerdict waiting(String reason) {
        return new GoalVerdict(WAIT, reason);
    }

    /**
     * 判断是否Done。
     *
     * @return 如果Done满足条件则返回 true，否则返回 false。
     */
    public boolean isDone() {
        return DONE.equals(verdict);
    }

    /**
     * 判断是否Waiting。
     *
     * @return verdict 为 wait 时返回 true。
     */
    public boolean isWait() {
        return WAIT.equals(verdict);
    }
}
