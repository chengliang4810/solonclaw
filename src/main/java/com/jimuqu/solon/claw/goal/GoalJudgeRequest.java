package com.jimuqu.solon.claw.goal;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Goal judge 的入参，携带目标、上轮回复、子目标和契约，供 judge 综合裁决。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoalJudgeRequest {
    /** 当前目标文本。 */
    private String goal;

    /** 上一轮 Agent 的回复内容。 */
    private String lastResponse;

    /** 用户补充的子目标准则（可空）。 */
    private List<String> subgoals;

    /** 完成契约（可空）。 */
    private GoalContract contract;
}
