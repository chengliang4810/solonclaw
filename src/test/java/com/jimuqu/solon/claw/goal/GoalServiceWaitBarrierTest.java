// src/test/java/com/jimuqu/solon/claw/goal/GoalServiceWaitBarrierTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalServiceWaitBarrierTest {
    @Test
    void waitOnPidAndStopWaiting() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("wait-chat");
        GoalService svc =
                new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        svc.set(session, "g", 5);

        svc.waitOnPid(session, Integer.MAX_VALUE, "等编译"); // 死 pid 立即解除
        // 重读状态确认持久化；惰性清屏障后 reason 已清
        GoalState reloaded = svc.getAndLazyClearBarrier(session);
        assertThat(reloaded.getWaitingReason()).isNull();
    }

    @Test
    void stopWaitingClearsBarrier() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("wait-chat2");
        GoalService svc =
                new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        svc.set(session, "g", 5);
        svc.waitOnPid(session, 99999, "等编译");
        svc.stopWaiting(session);
        GoalState reloaded = svc.get(session);
        assertThat(reloaded.getWaitingOnPid()).isNull();
        assertThat(reloaded.getWaitingUntil()).isEqualTo(0L);
    }
}
