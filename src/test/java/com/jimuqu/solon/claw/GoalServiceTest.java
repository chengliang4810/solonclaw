package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.goal.GoalDecision;
import com.jimuqu.solon.claw.goal.GoalJudgeRequest;
import com.jimuqu.solon.claw.goal.GoalJudgeResult;
import com.jimuqu.solon.claw.goal.GoalService;
import com.jimuqu.solon.claw.goal.GoalState;
import com.jimuqu.solon.claw.goal.GoalVerdict;
import com.jimuqu.solon.claw.goal.HeuristicGoalJudge;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 验证目标状态、启发式判定和目标服务的持续运行语义。 */
public class GoalServiceTest {
    @Test
    void shouldRoundTripGoalStateJsonWithDefaultsAndVerdictMetadata() {
        GoalState state = new GoalState();
        state.setGoal("完成浏览器回归测试");
        state.setTurnsUsed(3);
        state.setMaxTurns(5);
        state.setCreatedAt(1782126000000L);
        state.setLastTurnAt(1782126060000L);
        state.setLastVerdict(GoalVerdict.CONTINUE);
        state.setLastReason("still running");
        state.setPausedReason("waiting for user");

        GoalState parsed = GoalState.fromJson(state.toJson());

        assertThat(parsed.getGoal()).isEqualTo("完成浏览器回归测试");
        assertThat(parsed.getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);
        assertThat(parsed.getTurnsUsed()).isEqualTo(3);
        assertThat(parsed.getMaxTurns()).isEqualTo(5);
        assertThat(parsed.getCreatedAt()).isEqualTo(1782126000000L);
        assertThat(parsed.getLastTurnAt()).isEqualTo(1782126060000L);
        assertThat(parsed.getLastVerdict()).isEqualTo(GoalVerdict.CONTINUE);
        assertThat(parsed.getLastReason()).isEqualTo("still running");
        assertThat(parsed.getPausedReason()).isEqualTo("waiting for user");
    }

    @ParameterizedTest
    @CsvSource({
        "'', 'done', done",
        "'修复问题', '', continue",
        "'修复问题', 'The goal complete path is verified', done",
        "'修复问题', '整个目标已完成，等待归档', done",
        "'修复问题', '已经完成当前文档，等待最终验证', continue",
        "'修复问题', '继续执行下一步', continue"
    })
    void shouldClassifyGoalVerdictsFromResponseSignals(
            String goal, String response, String expectedVerdict) {
        HeuristicGoalJudge judge = new HeuristicGoalJudge();

        GoalJudgeResult result = judge.judge(new GoalJudgeRequest(goal, response, null, null));

        assertThat(result.getVerdict()).isEqualTo(expectedVerdict);
        if (GoalVerdict.CONTINUE.equals(expectedVerdict)) {
            assertThat(result.getReason()).isNotBlank();
        }
    }

    @Test
    void shouldPersistGoalAndContinueUntilExplicitCompletion() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        SessionRecord session = session("s-goal-1");
        repository.save(session);
        GoalService service = new GoalService(repository);

        GoalState state = service.set(session, "修复安全默认配置", 3);
        GoalDecision first = service.evaluateAfterTurn(session, "第一步仍在验证中，还要继续跑测试");

        assertThat(state.getGoal()).isEqualTo("修复安全默认配置");
        assertThat(first.isShouldContinue()).isTrue();
        assertThat(first.getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);
        assertThat(first.getContinuationPrompt()).contains("修复安全默认配置");
        assertThat(service.statusLine(session)).contains("active, 1/3 turns");
        assertThat(repository.findById("s-goal-1").getGoalStateJson()).contains("turns_used");

        GoalDecision done = service.evaluateAfterTurn(session, "Goal achieved after verification");

        assertThat(done.isShouldContinue()).isFalse();
        assertThat(done.getStatus()).isEqualTo(GoalState.STATUS_DONE);
        assertThat(done.getMessage()).contains("Goal achieved");
        assertThat(service.nextContinuationPrompt(session)).isNull();
    }

    @Test
    void shouldPauseGoalWhenTurnBudgetIsExhaustedAndSupportResumeAndClear() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        SessionRecord session = session("s-goal-2");
        repository.save(session);
        GoalService service = new GoalService(repository);

        service.set(session, "跑完剩余测试", 1);
        GoalDecision decision = service.evaluateAfterTurn(session, "测试仍在执行");

        assertThat(decision.getStatus()).isEqualTo(GoalState.STATUS_PAUSED);
        assertThat(decision.getMessage()).contains("Goal paused");
        assertThat(service.statusLine(session)).contains("paused, 1/1 turns");

        GoalState resumed = service.resume(session, true);
        assertThat(resumed.getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);
        assertThat(resumed.getTurnsUsed()).isZero();
        assertThat(resumed.getPausedReason()).isNull();

        assertThat(service.clear(session)).isTrue();
        assertThat(service.statusLine(session)).contains("No active goal");
    }

    @Test
    void shouldRejectBlankGoalAndMissingSession() {
        GoalService service = new GoalService(new InMemorySessionRepository());

        assertThatThrownBy(() -> service.set(session("s-goal-3"), "   ", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goal text is empty");
        assertThatThrownBy(() -> service.set(new SessionRecord(), "有效目标", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session is required");
    }

    private static SessionRecord session(String sessionId) {
        SessionRecord session = new SessionRecord();
        session.setSessionId(sessionId);
        return session;
    }

    /** 测试内使用的最小会话仓储，只覆盖目标状态持久化所需接口。 */
    private static class InMemorySessionRepository implements SessionRepository {
        private final Map<String, SessionRecord> sessions =
                new LinkedHashMap<String, SessionRecord>();

        @Override
        public SessionRecord getBoundSession(String sourceKey) {
            return null;
        }

        @Override
        public SessionRecord bindNewSession(String sourceKey) {
            throw unsupported();
        }

        @Override
        public void bindSource(String sourceKey, String sessionId) {}

        @Override
        public SessionRecord cloneSession(
                String sourceKey, String sourceSessionId, String branchName) {
            throw unsupported();
        }

        @Override
        public SessionRecord findById(String sessionId) {
            return sessions.get(sessionId);
        }

        @Override
        public SessionRecord findBySourceAndBranch(String sourceKey, String branchName) {
            return null;
        }

        @Override
        public List<SessionRecord> findResumeCandidates(String reference, int limit) {
            return Collections.emptyList();
        }

        @Override
        public void save(SessionRecord sessionRecord) {
            sessions.put(sessionRecord.getSessionId(), sessionRecord);
        }

        @Override
        public List<SessionRecord> search(String keyword, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listRecent(int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listRecent(int limit, int offset) {
            return Collections.emptyList();
        }

        @Override
        public List<SessionRecord> listPendingAgentSessions(long updatedAfterMillis, int limit) {
            return Collections.emptyList();
        }

        @Override
        public int countAll() {
            return sessions.size();
        }

        @Override
        public void delete(String sessionId) {
            sessions.remove(sessionId);
        }

        @Override
        public void setModelOverride(String sessionId, String modelOverride) {}

        @Override
        public void setServiceTierOverride(String sessionId, String serviceTierOverride) {}

        @Override
        public void setReasoningEffortOverride(String sessionId, String reasoningEffortOverride) {}

        @Override
        public void setGoalState(String sessionId, String goalStateJson) {
            SessionRecord session = sessions.get(sessionId);
            if (session == null) {
                session = session(sessionId);
                sessions.put(sessionId, session);
            }
            session.setGoalStateJson(goalStateJson);
        }

        @Override
        public void setLastLearningAt(String sessionId, long lastLearningAt) {}

        /** 明确标记测试未覆盖的仓储能力，避免误用时静默成功。 */
        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not needed by goal service tests");
        }
    }
}
