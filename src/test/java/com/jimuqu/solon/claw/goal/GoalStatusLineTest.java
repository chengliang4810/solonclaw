package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalStatusLineTest {
    @Test
    void noGoalMessage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord s = env.sessionRepository.bindNewSession("sl-chat");
        GoalService svc =
                new GoalService(
                        env.sessionRepository,
                        new HeuristicGoalJudge(),
                        new AppConfig.GoalConfig());
        assertThat(svc.statusLine(s)).contains("No active goal").contains("/goal");
    }

    @Test
    void activeWithSubgoalsAndContractMeta() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord s = env.sessionRepository.bindNewSession("sl-chat2");
        GoalService svc =
                new GoalService(
                        env.sessionRepository,
                        new HeuristicGoalJudge(),
                        new AppConfig.GoalConfig());
        GoalContract c = new GoalContract();
        c.setOutcome("完成");
        svc.set(s, "目标X", c, 10);
        svc.addSubgoal(s, "子目标1");
        svc.addSubgoal(s, "子目标2");
        String line = svc.statusLine(s);
        assertThat(line)
                .contains("⊙ Goal (active")
                .contains("目标X")
                .contains("2 subgoal")
                .contains("contract");
    }

    @Test
    void parkedWaitingShowsReason() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord s = env.sessionRepository.bindNewSession("sl-chat3");
        GoalService svc =
                new GoalService(
                        env.sessionRepository,
                        new HeuristicGoalJudge(),
                        new AppConfig.GoalConfig());
        svc.set(s, "等待目标", 10);
        // 用当前进程 pid 保证 isWaiting() 为 true（进程必然存活），触发 parked 分支
        long currentPid = ProcessHandle.current().pid();
        svc.waitOnPid(s, (int) currentPid, "等编译");
        String line = svc.statusLine(s);
        assertThat(line).contains("⏳ Goal (parked").contains("等编译");
    }
}
