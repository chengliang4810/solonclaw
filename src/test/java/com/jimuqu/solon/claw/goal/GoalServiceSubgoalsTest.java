// src/test/java/com/jimuqu/solon/claw/goal/GoalServiceSubgoalsTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalServiceSubgoalsTest {
    @Test
    void addRemoveClearSubgoals() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("subgoal-chat");
        AppConfig.GoalConfig cfg = new AppConfig.GoalConfig();
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), cfg);
        svc.set(session, "完成测试", 5);

        svc.addSubgoal(session, "覆盖 goal 包");
        svc.addSubgoal(session, "覆盖工具");
        assertThat(svc.listSubgoals(session)).containsExactly("覆盖 goal 包", "覆盖工具");

        svc.removeSubgoal(session, 1);
        assertThat(svc.listSubgoals(session)).containsExactly("覆盖工具");

        svc.clearSubgoals(session);
        assertThat(svc.listSubgoals(session)).isEmpty();
    }
}
