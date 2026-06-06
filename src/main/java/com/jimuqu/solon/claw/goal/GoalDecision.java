package com.jimuqu.solon.claw.goal;

import lombok.Getter;
import lombok.Setter;

/** 表示目标结果，携带调用方后续判断所需信息。 */
@Getter
@Setter
public class GoalDecision {
    /** 记录目标中的状态。 */
    private String status;

    /** 标记是否需要Continue。 */
    private boolean shouldContinue;

    /** 记录目标中的continuation提示词。 */
    private String continuationPrompt;

    /** 记录目标中的判定。 */
    private String verdict;

    /** 记录目标中的原因。 */
    private String reason;

    /** 记录目标中的消息。 */
    private String message;
}
