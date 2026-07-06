package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalContinuationPromptTest {
    @Test
    void plainWhenNoContractNoSubgoals() {
        GoalState s = new GoalState();
        s.setGoal("g");
        String p = new GoalService(null).nextContinuationPrompt(s);
        assertThat(p)
                .contains("Goal: g")
                .doesNotContain("Completion contract")
                .doesNotContain("Additional criteria");
    }

    @Test
    void contractPriorityWhenBothPresent() {
        GoalState s = new GoalState();
        s.setGoal("g");
        s.addSubgoal("子目标1");
        GoalContract c = new GoalContract();
        c.setOutcome("完成");
        s.setContract(c);
        String p = new GoalService(null).nextContinuationPrompt(s);
        assertThat(p)
                .contains("Completion contract:")
                .contains("- Outcome: 完成")
                .contains("- Extra criterion 1: 子目标1");
    }

    @Test
    void subgoalsWhenNoContract() {
        GoalState s = new GoalState();
        s.setGoal("g");
        s.addSubgoal("子目标A");
        String p = new GoalService(null).nextContinuationPrompt(s);
        assertThat(p)
                .contains("Additional criteria")
                .contains("子目标A")
                .doesNotContain("Completion contract");
    }

    @Test
    void nullWhenNotActive() {
        GoalState s = new GoalState();
        s.setGoal("g");
        s.setStatus(GoalState.STATUS_PAUSED);
        assertThat(new GoalService(null).nextContinuationPrompt(s)).isNull();
    }
}
