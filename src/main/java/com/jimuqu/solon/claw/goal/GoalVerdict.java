package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/** Result from the /goal judge. */
@Getter
public class GoalVerdict {
    public static final String DONE = "done";
    public static final String CONTINUE = "continue";
    public static final String SKIPPED = "skipped";

    private final String verdict;
    private final String reason;

    private GoalVerdict(String verdict, String reason) {
        this.verdict = verdict;
        this.reason = StrUtil.blankToDefault(reason, "no reason provided");
    }

    public static GoalVerdict done(String reason) {
        return new GoalVerdict(DONE, reason);
    }

    public static GoalVerdict continueGoal(String reason) {
        return new GoalVerdict(CONTINUE, reason);
    }

    public static GoalVerdict skipped(String reason) {
        return new GoalVerdict(SKIPPED, reason);
    }

    public boolean isDone() {
        return DONE.equals(verdict);
    }
}
