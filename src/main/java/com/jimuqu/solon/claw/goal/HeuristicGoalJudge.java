package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;

/**
 * Fail-open local judge used until an auxiliary Solon AI judge is configured.
 *
 * <p>Jimuqu keeps progress moving when the judge is unavailable. This heuristic mirrors that
 * invariant: only explicit completion/blocking language marks the goal done; otherwise continue.
 */
public class HeuristicGoalJudge implements GoalJudge {
    @Override
    public GoalVerdict judge(String goal, String lastResponse) {
        if (StrUtil.isBlank(goal)) {
            return GoalVerdict.skipped("empty goal");
        }
        String text = StrUtil.nullToEmpty(lastResponse).trim();
        if (StrUtil.isBlank(text)) {
            return GoalVerdict.continueGoal("empty response");
        }
        String lower = text.toLowerCase();
        if (lower.contains("goal achieved")
                || lower.contains("goal complete")
                || lower.contains("completed")
                || text.contains("目标已完成")
                || text.contains("已完成")
                || text.contains("已经完成")
                || text.contains("无法继续")
                || text.contains("需要用户")
                || text.contains("需要你")) {
            return GoalVerdict.done(
                    "response explicitly indicates completion or a user/input blocker");
        }
        return GoalVerdict.continueGoal("response did not clearly complete the standing goal");
    }
}
