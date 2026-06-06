package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;

/** 提供Goal相关业务能力，封装调用方不需要感知的运行细节。 */
public class GoalService {
    /** CONTINUATION提示词TEMPLATE的统一常量值。 */
    public static final String CONTINUATION_PROMPT_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Continue working toward this goal. Take the next concrete step. "
                    + "If you believe the goal is complete, state so explicitly and stop. "
                    + "If you are blocked and need input from the user, say so clearly and stop.";

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录目标中的judge。 */
    private final GoalJudge judge;

    /** 记录目标中的默认MaxTurns。 */
    private final int defaultMaxTurns;

    /**
     * 创建Goal服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     */
    public GoalService(SessionRepository sessionRepository) {
        this(sessionRepository, new HeuristicGoalJudge(), GoalState.DEFAULT_MAX_TURNS);
    }

    /**
     * 创建Goal服务实例，并注入运行所需依赖。
     *
     * @param sessionRepository 会话仓储依赖。
     * @param judge judge 参数。
     * @param defaultMaxTurns 默认MaxTurns参数。
     */
    public GoalService(SessionRepository sessionRepository, GoalJudge judge, int defaultMaxTurns) {
        this.sessionRepository = sessionRepository;
        this.judge = judge == null ? new HeuristicGoalJudge() : judge;
        this.defaultMaxTurns = defaultMaxTurns <= 0 ? GoalState.DEFAULT_MAX_TURNS : defaultMaxTurns;
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param session 会话参数。
     * @return 返回get结果。
     */
    public GoalState get(SessionRecord session) {
        return session == null ? null : GoalState.fromJson(session.getGoalStateJson());
    }

    /**
     * 执行set相关逻辑。
     *
     * @param session 会话参数。
     * @param goal 目标参数。
     * @param maxTurns maxTurns 参数。
     * @return 返回set结果。
     */
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

    /**
     * 执行pause相关逻辑。
     *
     * @param session 会话参数。
     * @param reason 原因参数。
     * @return 返回pause结果。
     */
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

    /**
     * 执行resume相关逻辑。
     *
     * @param session 会话参数。
     * @param resetBudget reset预算参数。
     * @return 返回resume结果。
     */
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

    /**
     * 执行clear相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回clear结果。
     */
    public boolean clear(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return false;
        }
        state.setStatus(GoalState.STATUS_CLEARED);
        save(session, state);
        return true;
    }

    /**
     * 执行状态行相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回状态Line结果。
     */
    public String statusLine(SessionRecord session) {
        GoalState state = get(session);
        if (state == null || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return "No active goal. Set one with /goal <text>.";
        }
        String turns = state.getTurnsUsed() + "/" + state.getMaxTurns() + " turns";
        String judge = judgeSummary(state);
        if (GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return "⊙ Goal (active, " + turns + judge + "): " + state.getGoal();
        }
        if (GoalState.STATUS_PAUSED.equals(state.getStatus())) {
            String extra =
                    StrUtil.isBlank(state.getPausedReason()) ? "" : " — " + state.getPausedReason();
            return "⏸ Goal (paused, " + turns + judge + extra + "): " + state.getGoal();
        }
        if (GoalState.STATUS_DONE.equals(state.getStatus())) {
            return "✓ Goal done (" + turns + judge + "): " + state.getGoal();
        }
        return "Goal (" + state.getStatus() + ", " + turns + judge + "): " + state.getGoal();
    }

    /**
     * 执行judge摘要相关逻辑。
     *
     * @param state 状态参数。
     * @return 返回judge Summary结果。
     */
    private String judgeSummary(GoalState state) {
        if (state == null || StrUtil.isBlank(state.getLastVerdict())) {
            return "";
        }
        String reason =
                StrUtil.isBlank(state.getLastReason()) ? "" : ", reason=" + state.getLastReason();
        return ", judge=" + state.getLastVerdict() + reason;
    }

    /**
     * 执行evaluateAfterTurn相关逻辑。
     *
     * @param session 会话参数。
     * @param lastResponse last响应响应或执行结果。
     * @return 返回evaluate After Turn结果。
     */
    public GoalDecision evaluateAfterTurn(SessionRecord session, String lastResponse)
            throws Exception {
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
                    "turn budget exhausted ("
                            + state.getTurnsUsed()
                            + "/"
                            + state.getMaxTurns()
                            + ")");
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

    /**
     * 执行nextContinuation提示词相关逻辑。
     *
     * @param session 会话参数。
     * @return 返回next Continuation提示词结果。
     */
    public String nextContinuationPrompt(SessionRecord session) {
        GoalState state = get(session);
        return nextContinuationPrompt(state);
    }

    /**
     * 执行nextContinuation提示词相关逻辑。
     *
     * @param state 状态参数。
     * @return 返回next Continuation提示词结果。
     */
    public String nextContinuationPrompt(GoalState state) {
        if (state == null || !GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return null;
        }
        return String.format(CONTINUATION_PROMPT_TEMPLATE, state.getGoal());
    }

    /**
     * 执行save，服务于目标主流程相关逻辑。
     *
     * @param session 会话参数。
     * @param state 状态参数。
     */
    private void save(SessionRecord session, GoalState state) throws Exception {
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            throw new IllegalArgumentException("session is required");
        }
        String json = state == null ? null : state.toJson();
        session.setGoalStateJson(json);
        sessionRepository.setGoalState(session.getSessionId(), json);
    }
}
