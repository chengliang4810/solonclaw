package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;

/** reference-style /goal manager backed by the session record. */
public class GoalService {
    public static final String CONTINUATION_PROMPT_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Continue working toward this goal. Take the next concrete step. "
                    + "If you believe the goal is complete, state so explicitly and stop. "
                    + "If you are blocked and need input from the user, say so clearly and stop.";

    private final SessionRepository sessionRepository;
    private final GoalJudge judge;
    private final int defaultMaxTurns;

    public GoalService(SessionRepository sessionRepository) {
        this(sessionRepository, new HeuristicGoalJudge(), GoalState.DEFAULT_MAX_TURNS);
    }

    public GoalService(SessionRepository sessionRepository, GoalJudge judge, int defaultMaxTurns) {
        this.sessionRepository = sessionRepository;
        this.judge = judge == null ? new HeuristicGoalJudge() : judge;
        this.defaultMaxTurns = defaultMaxTurns <= 0 ? GoalState.DEFAULT_MAX_TURNS : defaultMaxTurns;
    }

    public GoalState get(SessionRecord session) {
        return session == null ? null : GoalState.fromJson(session.getGoalStateJson());
    }

    public GoalState set(SessionRecord session, String goal, int maxTurns) throws Exception {
        String text = StrUtil.nullToEmpty(goal).trim();
        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("goal text is empty");
        }
        GoalState state = new GoalState();
        state.setGoal(text);
        state.setStatus(GoalState.STATUS_ACTIVE);
        state.setTurnsUsed(0);
        state.setMaxTurns(maxTurns > 0 ? maxTurns : defaultMaxTurns);
        state.setCreatedAt(System.currentTimeMillis());
        state.setLastTurnAt(0L);
        save(session, state);
        return state;
    }

    public GoalState pause(SessionRecord session, String reason) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return null;
        }
        state.setStatus(GoalState.STATUS_PAUSED);
        state.setPausedReason(StrUtil.blankToDefault(reason, "user-paused"));
        save(session, state);
        return state;
    }

    public GoalState resume(SessionRecord session, boolean resetBudget) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return null;
        }
        state.setStatus(GoalState.STATUS_ACTIVE);
        state.setPausedReason(null);
        if (resetBudget) {
            state.setTurnsUsed(0);
        }
        save(session, state);
        return state;
    }

    public boolean clear(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return false;
        }
        state.setStatus(GoalState.STATUS_CLEARED);
        save(session, state);
        return true;
    }

    public String statusLine(SessionRecord session) {
        GoalState state = get(session);
        if (state == null || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return "No active goal. Set one with /goal <text>.";
        }
        String turns = state.getTurnsUsed() + "/" + state.getMaxTurns() + " turns";
        if (GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return "⊙ Goal (active, " + turns + "): " + state.getGoal();
        }
        if (GoalState.STATUS_PAUSED.equals(state.getStatus())) {
            String extra = StrUtil.isBlank(state.getPausedReason()) ? "" : " — " + state.getPausedReason();
            return "⏸ Goal (paused, " + turns + extra + "): " + state.getGoal();
        }
        if (GoalState.STATUS_DONE.equals(state.getStatus())) {
            return "✓ Goal done (" + turns + "): " + state.getGoal();
        }
        return "Goal (" + state.getStatus() + ", " + turns + "): " + state.getGoal();
    }

    public GoalDecision evaluateAfterTurn(SessionRecord session, String lastResponse) throws Exception {
        GoalDecision decision = new GoalDecision();
        GoalState state = get(session);
        if (state == null || !GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            decision.setStatus(state == null ? null : state.getStatus());
            decision.setVerdict("inactive");
            decision.setReason("no active goal");
            return decision;
        }

        state.setTurnsUsed(state.getTurnsUsed() + 1);
        state.setLastTurnAt(System.currentTimeMillis());
        GoalVerdict verdict = judge.judge(state.getGoal(), lastResponse);
        state.setLastVerdict(verdict.getVerdict());
        state.setLastReason(verdict.getReason());

        if (verdict.isDone()) {
            state.setStatus(GoalState.STATUS_DONE);
            save(session, state);
            decision.setStatus(GoalState.STATUS_DONE);
            decision.setVerdict(GoalVerdict.DONE);
            decision.setReason(verdict.getReason());
            decision.setMessage("✓ Goal achieved: " + verdict.getReason());
            return decision;
        }

        if (state.getTurnsUsed() >= state.getMaxTurns()) {
            state.setStatus(GoalState.STATUS_PAUSED);
            state.setPausedReason(
                    "turn budget exhausted (" + state.getTurnsUsed() + "/" + state.getMaxTurns() + ")");
            save(session, state);
            decision.setStatus(GoalState.STATUS_PAUSED);
            decision.setVerdict(GoalVerdict.CONTINUE);
            decision.setReason(verdict.getReason());
            decision.setMessage(
                    "⏸ Goal paused — "
                            + state.getTurnsUsed()
                            + "/"
                            + state.getMaxTurns()
                            + " turns used. Use /goal resume to keep going, or /goal clear to stop.");
            return decision;
        }

        save(session, state);
        decision.setStatus(GoalState.STATUS_ACTIVE);
        decision.setShouldContinue(true);
        decision.setContinuationPrompt(nextContinuationPrompt(state));
        decision.setVerdict(GoalVerdict.CONTINUE);
        decision.setReason(verdict.getReason());
        decision.setMessage(
                "↻ Continuing toward goal ("
                        + state.getTurnsUsed()
                        + "/"
                        + state.getMaxTurns()
                        + "): "
                        + verdict.getReason());
        return decision;
    }

    public String nextContinuationPrompt(SessionRecord session) {
        GoalState state = get(session);
        return nextContinuationPrompt(state);
    }

    public String nextContinuationPrompt(GoalState state) {
        if (state == null || !GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return null;
        }
        return String.format(CONTINUATION_PROMPT_TEMPLATE, state.getGoal());
    }

    private void save(SessionRecord session, GoalState state) throws Exception {
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            throw new IllegalArgumentException("session is required");
        }
        String json = state == null ? null : state.toJson();
        session.setGoalStateJson(json);
        sessionRepository.setGoalState(session.getSessionId(), json);
    }
}
