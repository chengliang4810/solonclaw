// src/test/java/com/jimuqu/solon/claw/goal/GoalServiceParseFailureTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

/**
 * 连续解析失败自动暂停的集成测试：把一个恒抛 {@link GoalJudgeUnparseableException} 的桩
 * {@link GoalJudge} 接到真实 {@code SessionRepository} 的 {@link GoalService}，跨多轮驱动
 * {@link GoalService#evaluateAfterTurn}，验证计数自增并持久化、未达上限 fail-open 续轮、
 * 达到默认上限 3 次时产出 STATUS_PAUSED 与 pausedReason。
 */
class GoalServiceParseFailureTest {

    /** 恒抛 GoalJudgeUnparseableException 的裁决器桩，模拟「模型有返回但 JSON 不可解析」。 */
    static class UnparseableJudge implements GoalJudge {
        @Override
        public GoalJudgeResult judge(GoalJudgeRequest request) {
            throw new GoalJudgeUnparseableException("simulated unparseable response");
        }
    }

    /** 默认 goal 配置（maxConsecutiveParseFailures=3）。 */
    private AppConfig.GoalConfig goalConfig() {
        return new AppConfig.GoalConfig();
    }

    @Test
    void consecutiveParseFailuresAutoPauseAfterThreeCalls() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("parsefail-chat");
        GoalService svc = new GoalService(env.sessionRepository, new UnparseableJudge(), goalConfig());
        svc.set(session, "完成目标", 10);

        // 第一次失败：计数 1/3，fail-open 续轮
        GoalDecision first = svc.evaluateAfterTurn(session, "turn-1");
        assertThat(first.isShouldContinue()).isTrue();
        assertThat(first.getContinuationPrompt()).isNotBlank();
        assertThat(first.getVerdict()).isEqualTo(GoalVerdict.CONTINUE);
        // 计数已持久化：从仓储重读
        GoalState afterFirst = svc.get(session);
        assertThat(afterFirst.getConsecutiveParseFailures()).isEqualTo(1);
        assertThat(afterFirst.getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);

        // 第二次失败：计数 2/3，仍 fail-open 续轮
        GoalDecision second = svc.evaluateAfterTurn(session, "turn-2");
        assertThat(second.isShouldContinue()).isTrue();
        assertThat(second.getContinuationPrompt()).isNotBlank();
        assertThat(svc.get(session).getConsecutiveParseFailures()).isEqualTo(2);
        assertThat(svc.get(session).getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);

        // 第三次失败：达到默认上限 3，自动暂停
        GoalDecision third = svc.evaluateAfterTurn(session, "turn-3");
        assertThat(third.isShouldContinue()).isFalse();
        assertThat(third.getStatus()).isEqualTo(GoalState.STATUS_PAUSED);
        assertThat(third.getMessage()).contains("paused");
        // pausedReason 持久化并包含 "judge parse failures"
        GoalState afterThird = svc.get(session);
        assertThat(afterThird.getStatus()).isEqualTo(GoalState.STATUS_PAUSED);
        assertThat(afterThird.getPausedReason()).contains("judge parse failures");
        assertThat(afterThird.getConsecutiveParseFailures()).isEqualTo(3);
    }

    @Test
    void counterResetsOnSuccessfulJudge() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("parsefail-reset-chat");
        // 先用一个可切换的桩：前一次抛异常，后一次返回 continue
        ToggleJudge judge = new ToggleJudge();
        GoalService svc = new GoalService(env.sessionRepository, judge, goalConfig());
        svc.set(session, "完成目标", 10);

        // 一次失败：计数 1
        GoalDecision failed = svc.evaluateAfterTurn(session, "turn-fail");
        assertThat(failed.isShouldContinue()).isTrue();
        assertThat(svc.get(session).getConsecutiveParseFailures()).isEqualTo(1);

        // 切回成功裁决：计数应重置为 0
        judge.throwUnparseable = false;
        GoalDecision ok = svc.evaluateAfterTurn(session, "turn-ok");
        assertThat(ok.getVerdict()).isEqualTo(GoalVerdict.CONTINUE);
        assertThat(svc.get(session).getConsecutiveParseFailures()).isEqualTo(0);
        assertThat(svc.get(session).getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);
    }

    /** 可切换是否抛解析异常的桩裁决器。 */
    static class ToggleJudge implements GoalJudge {
        boolean throwUnparseable = true;

        @Override
        public GoalJudgeResult judge(GoalJudgeRequest request) {
            if (throwUnparseable) {
                throw new GoalJudgeUnparseableException("simulated");
            }
            return GoalJudgeResult.continueGoal("ok");
        }
    }
}
