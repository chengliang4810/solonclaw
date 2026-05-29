package com.jimuqu.solon.claw.goal;

import lombok.Getter;
import lombok.Setter;

/** Post-turn decision returned by the standing goal loop. */
@Getter
@Setter
public class GoalDecision {
    private String status;
    private boolean shouldContinue;
    private String continuationPrompt;
    private String verdict;
    private String reason;
    private String message;
}
