package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.SessionRepository;

/** 提供Goal相关业务能力，封装调用方不需要感知的运行细节。 */
public class GoalService {
    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录目标中的judge。 */
    private final GoalJudge judge;

    /** 记录目标中的goal配置（超时、轮次预算、解析失败上限等）。 */
    private final AppConfig.GoalConfig goalConfig;

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
        this.goalConfig = new AppConfig.GoalConfig();
        this.defaultMaxTurns = defaultMaxTurns <= 0 ? GoalState.DEFAULT_MAX_TURNS : defaultMaxTurns;
    }

    /**
     * 创建 Goal 服务实例，注入 judge 与 goal 配置。
     *
     * @param sessionRepository 会话仓储。
     * @param judge 裁决器。
     * @param goalConfig goal 配置。
     */
    public GoalService(
            SessionRepository sessionRepository,
            GoalJudge judge,
            AppConfig.GoalConfig goalConfig) {
        this.sessionRepository = sessionRepository;
        this.judge = judge == null ? new HeuristicGoalJudge() : judge;
        this.goalConfig = goalConfig == null ? new AppConfig.GoalConfig() : goalConfig;
        this.defaultMaxTurns =
                this.goalConfig.getMaxTurns() > 0
                        ? this.goalConfig.getMaxTurns()
                        : GoalState.DEFAULT_MAX_TURNS;
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

        // 等待屏障：仍在等待时不消耗轮次、不调 judge，直接 quiesce
        if (state.isWaiting()) {
            decision.setStatus(GoalState.STATUS_ACTIVE);
            decision.setVerdict(GoalVerdict.WAIT);
            decision.setReason("waiting on barrier");
            decision.setShouldContinue(false);
            decision.setMessage("⏳ Goal parked — waiting: " + state.getWaitingReason());
            return decision;
        }

        state.setTurnsUsed(state.getTurnsUsed() + 1);
        state.setLastTurnAt(System.currentTimeMillis());
        GoalJudgeRequest judgeReq =
                new GoalJudgeRequest(
                        state.getGoal(), lastResponse, state.getSubgoals(), state.getContract());
        GoalJudgeResult result;
        try {
            result = judge.judge(judgeReq);
            state.setConsecutiveParseFailures(0); // 成功裁决（含 API 错误 fail-open）重置计数
        } catch (GoalJudgeUnparseableException e) {
            state.setConsecutiveParseFailures(state.getConsecutiveParseFailures() + 1);
            int limit =
                    goalConfig.getMaxConsecutiveParseFailures() > 0
                            ? goalConfig.getMaxConsecutiveParseFailures()
                            : 3;
            if (state.getConsecutiveParseFailures() >= limit) {
                state.setStatus(GoalState.STATUS_PAUSED);
                state.setPausedReason("judge parse failures (" + limit + ")");
                save(session, state);
                decision.setStatus(GoalState.STATUS_PAUSED);
                decision.setVerdict("skipped");
                decision.setReason(e.getMessage());
                decision.setMessage(
                        "⏸ Goal paused — the judge model isn't returning valid JSON "
                                + "("
                                + limit
                                + " times). Check auxiliary.goal_judge config or /goal clear.");
                return decision;
            }
            // 未达上限：保存计数，fail-open continue
            save(session, state);
            decision.setStatus(GoalState.STATUS_ACTIVE);
            decision.setShouldContinue(true);
            decision.setContinuationPrompt(nextContinuationPrompt(state));
            decision.setVerdict(GoalVerdict.CONTINUE);
            decision.setReason(
                    "judge parse failure "
                            + state.getConsecutiveParseFailures()
                            + "/"
                            + limit
                            + ", fail-open");
            decision.setMessage(
                    "↻ Continuing toward goal (judge parse fail "
                            + state.getConsecutiveParseFailures()
                            + "/"
                            + limit
                            + ")");
            return decision;
        }
        state.setLastVerdict(result.getVerdict());
        state.setLastReason(result.getReason());

        if (result.isDone()) {
            state.setStatus(GoalState.STATUS_DONE);
            save(session, state);
            decision.setStatus(GoalState.STATUS_DONE);
            decision.setVerdict(GoalVerdict.DONE);
            decision.setReason(result.getReason());
            decision.setMessage("✓ Goal achieved: " + result.getReason());
            return decision;
        }

        if (result.isWait()) {
            // wait 屏障：设 pid 或时间，不消耗"已完成轮次"判定，但计入 turns
            if (result.getWaitOnPid() != null) {
                state.setWaitingOnPid(result.getWaitOnPid());
                state.setWaitingSince(System.currentTimeMillis());
            }
            if (result.getWaitForSeconds() != null && result.getWaitForSeconds() > 0) {
                state.setWaitingUntil(
                        System.currentTimeMillis() + result.getWaitForSeconds() * 1000L);
                state.setWaitingSince(System.currentTimeMillis());
            }
            state.setWaitingReason(result.getReason());
            save(session, state);
            decision.setStatus(GoalState.STATUS_ACTIVE);
            decision.setVerdict(GoalVerdict.WAIT);
            decision.setReason(result.getReason());
            String tgt =
                    result.getWaitOnPid() != null
                            ? "pid " + result.getWaitOnPid()
                            : (result.getWaitForSeconds() != null
                                    ? result.getWaitForSeconds() + "s"
                                    : "");
            decision.setMessage("⏳ Goal parked — waiting on " + tgt + ": " + result.getReason());
            // 等待期间不发起续轮；屏障解除后由下一次 evaluateAfterTurn 推进
            return decision;
        }

        // CONTINUE 分支：保留原有 budget-exhausted 检查
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
            decision.setReason(result.getReason());
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
        decision.setReason(result.getReason());
        decision.setMessage(
                "↻ Continuing toward goal ("
                        + state.getTurnsUsed()
                        + "/"
                        + state.getMaxTurns()
                        + "): "
                        + result.getReason());
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
        if (state.hasContract()) {
            String contractBlock = state.getContract().renderBlock();
            if (state.getSubgoals() != null && !state.getSubgoals().isEmpty()) {
                StringBuilder extra = new StringBuilder();
                for (int i = 0; i < state.getSubgoals().size(); i++) {
                    extra.append("\n- Extra criterion ")
                            .append(i + 1)
                            .append(": ")
                            .append(state.getSubgoals().get(i));
                }
                contractBlock = contractBlock + extra.toString();
            }
            return String.format(
                    GoalPromptTemplates.CONTINUATION_PROMPT_WITH_CONTRACT_TEMPLATE,
                    state.getGoal(),
                    contractBlock);
        }
        if (state.getSubgoals() != null && !state.getSubgoals().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < state.getSubgoals().size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append("- ").append(state.getSubgoals().get(i));
            }
            return String.format(
                    GoalPromptTemplates.CONTINUATION_PROMPT_WITH_SUBGOALS_TEMPLATE,
                    state.getGoal(),
                    sb.toString());
        }
        return String.format(GoalPromptTemplates.CONTINUATION_PROMPT_TEMPLATE, state.getGoal());
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
