package com.jimuqu.solon.claw.goal;

/** 定义 Goal Judge 的抽象契约，供不同运行时实现保持一致行为。 */
public interface GoalJudge {
    /**
     * 综合目标、上轮回复、子目标和契约，裁决目标是否完成、继续还是等待。
     *
     * @param request 裁决请求，含 goal/lastResponse/subgoals/contract。
     * @return 裁决结果。
     */
    GoalJudgeResult judge(GoalJudgeRequest request);
}
