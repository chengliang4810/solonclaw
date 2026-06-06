package com.jimuqu.solon.claw.goal;

/** 定义Goal Judge的抽象契约，供不同运行时实现保持一致行为。 */
public interface GoalJudge {
    /**
     * 执行judge相关逻辑。
     *
     * @param goal 目标参数。
     * @param lastResponse last响应响应或执行结果。
     * @return 返回judge结果。
     */
    GoalVerdict judge(String goal, String lastResponse);
}
