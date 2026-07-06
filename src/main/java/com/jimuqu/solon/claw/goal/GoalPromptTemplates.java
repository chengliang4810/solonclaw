package com.jimuqu.solon.claw.goal;

/** 集中存放 goal 续轮、judge、draft 用的提示词模板常量，对标仓库逐字对齐。 */
public final class GoalPromptTemplates {

    private GoalPromptTemplates() {}

    /** 普通续轮提示（无契约无子目标）。 */
    public static final String CONTINUATION_PROMPT_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Continue working toward this goal. Take the next concrete step. "
                    + "If you believe the goal is complete, state so explicitly and stop. "
                    + "If you are blocked and need input from the user, say so clearly and stop.";

    /** 带完成契约的续轮提示：契约块告知 agent "done" 的精确定义。 */
    public static final String CONTINUATION_PROMPT_WITH_CONTRACT_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Completion contract:\n"
                    + "%s\n\n"
                    + "Continue working toward the outcome above. Take the next concrete step. "
                    + "Stay within the stated boundaries and do not violate the constraints. "
                    + "Before claiming the goal is done, satisfy the Verification criterion and "
                    + "show the concrete evidence (command output, file contents, test result). "
                    + "If you hit the stated stop condition or are otherwise blocked and need "
                    + "user input, say so clearly and stop.";

    /** 带子目标的续轮提示：逐条呈现用户补充的准则。 */
    public static final String CONTINUATION_PROMPT_WITH_SUBGOALS_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Additional criteria the user added mid-loop:\n"
                    + "%s\n\n"
                    + "Continue working toward the goal AND all additional criteria. Take "
                    + "the next concrete step. If you believe the goal and every "
                    + "additional criterion are complete, state so explicitly and stop. "
                    + "If you are blocked and need input from the user, say so clearly "
                    + "and stop.";

    /** Judge 系统提示：定义 DONE/CONTINUE/WAIT 裁决契约。 */
    public static final String JUDGE_SYSTEM_PROMPT =
            "You are a strict goal-completion judge. Decide if the standing goal is DONE, "
                    + "should CONTINUE, or should WAIT. Output ONLY JSON:\n"
                    + "{\"verdict\": \"done\", \"reason\": \"...\"}\n"
                    + "{\"verdict\": \"continue\", \"reason\": \"...\"}\n"
                    + "{\"verdict\": \"wait\", \"wait_on_pid\": <int>, \"reason\": \"...\"}\n"
                    + "{\"verdict\": \"wait\", \"wait_for_seconds\": <int>, \"reason\": \"...\"}\n"
                    + "DONE: the last response satisfies the goal (and contract Verification if present). "
                    + "CONTINUE: more work needed. WAIT: genuinely blocked on a running process or time. "
                    + "If you cannot decide, return continue.";

    /** Judge 用户提示模板。 */
    public static final String JUDGE_USER_PROMPT_TEMPLATE =
            "Goal: %s\n\nLast assistant response:\n%s\n\nReturn your JSON verdict.";
}
