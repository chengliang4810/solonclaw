package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.goal.GoalJudge;
import com.jimuqu.solon.claw.goal.GoalDecision;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.goal.GoalVerdict;
import com.jimuqu.solon.claw.storage.repository.SqliteSessionRepository;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

public class GoalServiceTest {
    @Test
    void shouldPersistGoalStateAndResumeBySession() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GoalService service = new GoalService(env.sessionRepository);
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:goal:user");

        GoalState state = service.set(session, "完成发布流程", 3);

        assertThat(state.getStatus()).isEqualTo("active");
        SessionRecord loaded = env.sessionRepository.findById(session.getSessionId());
        assertThat(service.statusLine(loaded)).contains("完成发布流程").contains("0/3");
    }

    @Test
    void shouldEvaluateContinueDoneAndBudgetPause() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GoalService service =
                new GoalService(
                        env.sessionRepository,
                        new QueueJudge(
                                GoalVerdict.continueGoal("not yet"),
                                GoalVerdict.continueGoal("still not"),
                                GoalVerdict.done("done")),
                        2);
        SessionRecord session = env.sessionRepository.bindNewSession("MEMORY:goal-budget:user");
        service.set(session, "做完任务", 2);

        GoalDecision first = service.evaluateAfterTurn(session, "处理中");
        assertThat(first.isShouldContinue()).isTrue();
        assertThat(first.getContinuationPrompt()).contains("做完任务");

        SessionRecord afterFirst = env.sessionRepository.findById(session.getSessionId());
        GoalDecision second = service.evaluateAfterTurn(afterFirst, "继续处理中");
        assertThat(second.isShouldContinue()).isFalse();
        assertThat(second.getStatus()).isEqualTo("paused");
        assertThat(service.statusLine(env.sessionRepository.findById(session.getSessionId())))
                .contains("paused")
                .contains("2/2");

        service.resume(env.sessionRepository.findById(session.getSessionId()), true);
        GoalDecision done = service.evaluateAfterTurn(env.sessionRepository.findById(session.getSessionId()), "已完成");
        assertThat(done.getStatus()).isEqualTo("done");
        assertThat(done.getMessage()).contains("Goal achieved");
    }

    private static class QueueJudge implements GoalJudge {
        private final GoalVerdict[] verdicts;
        private int index;

        private QueueJudge(GoalVerdict... verdicts) {
            this.verdicts = verdicts;
        }

        @Override
        public GoalVerdict judge(String goal, String lastResponse) {
            if (index >= verdicts.length) {
                return verdicts[verdicts.length - 1];
            }
            return verdicts[index++];
        }
    }
}
