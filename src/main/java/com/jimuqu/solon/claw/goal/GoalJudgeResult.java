package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Goal judge 的裁决结果：verdict（done/continue/wait）+ reason + 可选的 wait 指令。 */
@Getter
@Setter
@NoArgsConstructor
public class GoalJudgeResult {
    /** 裁决类型：done / continue / wait。 */
    private String verdict;

    /** 裁决原因。 */
    private String reason;

    /** wait 屏障：等待的进程 pid（仅 verdict=wait 时有意义）。 */
    private Integer waitOnPid;

    /** wait 屏障：等待秒数（仅 verdict=wait 时有意义）。 */
    private Long waitForSeconds;

    /**
     * 构造裁决结果。
     *
     * @param verdict 裁决类型参数。
     * @param reason 原因参数。
     * @param waitOnPid 等待进程 pid 参数。
     * @param waitForSeconds 等待秒数参数。
     */
    public GoalJudgeResult(String verdict, String reason, Integer waitOnPid, Long waitForSeconds) {
        this.verdict = verdict;
        this.reason = StrUtil.blankToDefault(reason, "no reason provided");
        this.waitOnPid = waitOnPid;
        this.waitForSeconds = waitForSeconds;
    }

    /**
     * 构造 done 裁决。
     *
     * @param reason 原因参数。
     * @return 返回 done 结果。
     */
    public static GoalJudgeResult done(String reason) {
        return new GoalJudgeResult(GoalVerdict.DONE, reason, null, null);
    }

    /**
     * 构造 continue 裁决。
     *
     * @param reason 原因参数。
     * @return 返回 continue 结果。
     */
    public static GoalJudgeResult continueGoal(String reason) {
        return new GoalJudgeResult(GoalVerdict.CONTINUE, reason, null, null);
    }

    /**
     * 构造 wait-on-pid 裁决。
     *
     * @param pid 进程 pid 参数。
     * @param reason 原因参数。
     * @return 返回 wait 结果。
     */
    public static GoalJudgeResult waitPid(int pid, String reason) {
        return new GoalJudgeResult(GoalVerdict.WAIT, reason, pid, null);
    }

    /**
     * 构造 wait-for-seconds 裁决。
     *
     * @param seconds 等待秒数参数。
     * @param reason 原因参数。
     * @return 返回 wait 结果。
     */
    public static GoalJudgeResult waitSeconds(long seconds, String reason) {
        return new GoalJudgeResult(GoalVerdict.WAIT, reason, null, seconds);
    }

    /**
     * 是否 done。
     *
     * @return verdict 为 done 时返回 true。
     */
    public boolean isDone() {
        return GoalVerdict.DONE.equals(verdict);
    }

    /**
     * 是否 continue。
     *
     * @return verdict 为 continue 时返回 true。
     */
    public boolean isContinue() {
        return GoalVerdict.CONTINUE.equals(verdict);
    }

    /**
     * 是否 wait。
     *
     * @return verdict 为 wait 时返回 true。
     */
    public boolean isWait() {
        return GoalVerdict.WAIT.equals(verdict);
    }
}
