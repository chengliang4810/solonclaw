package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;

/** 承载Heuristic目标Judge相关状态和辅助逻辑。 */
public class HeuristicGoalJudge implements GoalJudge {
    /** 表示整个 standing goal 已完成的强信号短语。 */
    private static final String[] COMPLETION_MARKERS = {
        "goal achieved",
        "goal complete",
        "standing goal complete",
        "overall goal complete",
        "entire goal complete",
        "目标已完成",
        "整个目标已完成",
        "整体目标已完成",
        "全部目标已完成"
    };

    /** 表示继续执行已无法推进，需要停止目标续跑的阻塞短语。 */
    private static final String[] BLOCKED_MARKERS = {
        "blocked",
        "need user",
        "need input",
        "cannot continue",
        "无法继续",
        "需要用户",
        "需要你",
        "需要输入"
    };

    /**
     * 执行judge相关逻辑。
     *
     * @param goal 目标参数。
     * @param lastResponse last响应响应或执行结果。
     * @return 返回judge结果。
     */
    @Override
    public GoalVerdict judge(String goal, String lastResponse) {
        if (StrUtil.isBlank(goal)) {
            return GoalVerdict.skipped("empty goal");
        }
        String text = StrUtil.nullToEmpty(lastResponse).trim();
        if (StrUtil.isBlank(text)) {
            return GoalVerdict.continueGoal("empty response");
        }
        String lower = text.toLowerCase();
        if (containsAny(lower, text, BLOCKED_MARKERS)
                || containsAny(lower, text, COMPLETION_MARKERS)) {
            return GoalVerdict.done(
                    "response explicitly indicates completion or a user/input blocker");
        }
        return GoalVerdict.continueGoal("response did not clearly complete the standing goal");
    }

    /**
     * 判断响应是否包含任一英文或中文信号短语，英文按小写文本匹配，中文按原文匹配。
     *
     * @param lowerResponse 小写响应文本。
     * @param rawResponse 原始响应文本。
     * @param markers 候选信号短语。
     * @return 命中任一短语时返回 true。
     */
    private boolean containsAny(String lowerResponse, String rawResponse, String[] markers) {
        for (String marker : markers) {
            String value = StrUtil.nullToEmpty(marker);
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (isAscii(value)) {
                if (lowerResponse.contains(value.toLowerCase())) {
                    return true;
                }
            } else if (rawResponse.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断短语是否为 ASCII，避免中文信号在小写转换后仍走英文分支。
     *
     * @param value 候选短语。
     * @return 全部为 ASCII 字符时返回 true。
     */
    private boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
