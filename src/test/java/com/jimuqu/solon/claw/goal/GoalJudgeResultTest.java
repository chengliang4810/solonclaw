package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalJudgeResultTest {
    @Test
    void doneResult() {
        GoalJudgeResult r = GoalJudgeResult.done("完成");
        assertThat(r.isDone()).isTrue();
        assertThat(r.getReason()).isEqualTo("完成");
        assertThat(r.getWaitOnPid()).isNull();
    }

    @Test
    void continueResult() {
        GoalJudgeResult r = GoalJudgeResult.continueGoal("继续");
        assertThat(r.isContinue()).isTrue();
    }

    @Test
    void waitPidResult() {
        GoalJudgeResult r = GoalJudgeResult.waitPid(1234, "等编译");
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitOnPid()).isEqualTo(1234);
        assertThat(r.getWaitForSeconds()).isNull();
    }

    @Test
    void waitSecondsResult() {
        GoalJudgeResult r = GoalJudgeResult.waitSeconds(60L, "等 60 秒");
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitForSeconds()).isEqualTo(60L);
    }

    @Test
    void heuristicJudgeAdaptsToNewSignature() {
        HeuristicGoalJudge j = new HeuristicGoalJudge();
        GoalJudgeRequest req = new GoalJudgeRequest("g", "goal achieved", null, null);
        GoalJudgeResult r = j.judge(req);
        assertThat(r.isDone()).isTrue();
    }
}
