package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;

/** 承载Heuristic目标Judge相关状态和辅助逻辑。 */
public class HeuristicGoalJudge implements GoalJudge {
    /**
     * 执行judge相关逻辑。
     *
     * @param goal 目标参数。
     * @param lastResponse last响应响应或执行结果。
     * @return 返回judge结果。
     */
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
