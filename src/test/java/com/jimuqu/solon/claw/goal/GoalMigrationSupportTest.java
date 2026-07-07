// src/test/java/com/jimuqu/solon/claw/goal/GoalMigrationSupportTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalMigrationSupportTest {
    @Test
    void migratesActiveGoalAndArchivesParent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord parent = env.sessionRepository.bindNewSession("parent-chat");
        GoalService svc =
                new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        svc.set(parent, "父目标", 5);

        SessionRecord child = env.sessionRepository.bindNewSession("child-chat");
        GoalMigrationSupport migrator = new GoalMigrationSupport(env.sessionRepository);
        boolean migrated = migrator.migrate(parent.getSessionId(), child.getSessionId(), "compression");
        assertThat(migrated).isTrue();

        // 从仓储重读，验证迁移确实落库
        GoalState parentState = svc.get(env.sessionRepository.findById(parent.getSessionId()));
        GoalState childState = svc.get(env.sessionRepository.findById(child.getSessionId()));
        assertThat(parentState.getStatus()).isEqualTo(GoalState.STATUS_CLEARED);
        assertThat(childState.getGoal()).isEqualTo("父目标");
        assertThat(childState.getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);
    }

    @Test
    void noActiveGoalReturnsFalse() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord parent = env.sessionRepository.bindNewSession("parent2-chat");
        SessionRecord child = env.sessionRepository.bindNewSession("child2-chat");
        GoalMigrationSupport migrator = new GoalMigrationSupport(env.sessionRepository);
        assertThat(migrator.migrate(parent.getSessionId(), child.getSessionId(), "compression"))
                .isFalse();
    }
}
