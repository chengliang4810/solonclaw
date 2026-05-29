package com.jimuqu.solon.claw.goal;

/** Judge that decides whether a standing goal was satisfied by the last reply. */
public interface GoalJudge {
    GoalVerdict judge(String goal, String lastResponse);
}
