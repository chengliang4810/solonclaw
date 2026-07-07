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
        if (session == null) {
            return null;
        }
        GoalState state = GoalState.fromJson(session.getGoalStateJson());
        return state;
    }

    /**
     * 重载并惰性清屏障：读取持久化目标状态，若 pid 屏障的进程已退出或时间屏障已过期，
     * 则清除等待字段并回写，保证"死 pid / 过期屏障立即解除"语义。
     *
     * @param session 会话参数。
     * @return 返回目标状态；无目标返回 null。
     */
    public GoalState getAndLazyClearBarrier(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return null;
        }
        if (hasStaleBarrier(state)) {
            state.clearWaitBarrier();
            save(session, state);
        }
        return state;
    }

    /** 判断等待屏障字段是否仍残留但已失效（进程退出或时间过期）。 */
    private boolean hasStaleBarrier(GoalState state) {
        boolean hasField =
                state.getWaitingOnPid() != null
                        || state.getWaitingUntil() > 0
                        || StrUtil.isNotBlank(state.getWaitingReason())
                        || state.getWaitingSince() > 0;
        return hasField && !state.isWaiting();
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
        return set(session, goal, null, maxTurns);
    }

    /**
     * set 的 contract 重载：带完成契约设置目标。
     *
     * @param session 会话参数。
     * @param headline 目标主标题参数。
     * @param contract 完成契约参数，可为 null。
     * @param maxTurns maxTurns 参数。
     * @return 返回设置后的目标状态。
     */
    public GoalState set(SessionRecord session, String headline, GoalContract contract, int maxTurns)
            throws Exception {
        String text = StrUtil.nullToEmpty(headline).trim();
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
        if (contract != null) {
            state.setContract(contract);
        }
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
        state.clearWaitBarrier();
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
        state.clearWaitBarrier();
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
        state.clearWaitBarrier();
        save(session, state);
        return true;
    }

    /** 追加一条子目标准则。仅当存在 active 目标时生效。 */
    public void addSubgoal(SessionRecord session, String text) throws Exception {
        GoalState state = get(session);
        if (state == null || !state.isActive()) {
            return;
        }
        state.addSubgoal(text);
        save(session, state);
    }

    /** 删除第 n 条子目标（1-based）。删除成功返回 true。 */
    public boolean removeSubgoal(SessionRecord session, int oneBasedIndex) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return false;
        }
        boolean removed = state.removeSubgoal(oneBasedIndex);
        if (removed) {
            save(session, state);
        }
        return removed;
    }

    /** 清空全部子目标。 */
    public void clearSubgoals(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return;
        }
        state.clearSubgoals();
        save(session, state);
    }

    /** 列出当前子目标（返回副本，无目标时为空列表）。 */
    public java.util.List<String> listSubgoals(SessionRecord session) {
        GoalState state = get(session);
        return state == null || state.getSubgoals() == null
                ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(state.getSubgoals());
    }

    /** 设置 pid 等待屏障：循环暂停直到该进程退出。 */
    public void waitOnPid(SessionRecord session, int pid, String reason) throws Exception {
        GoalState state = get(session);
        if (state == null || !state.isActive()) {
            return;
        }
        // 校验 pid 合法性（存在则更佳，这里只校验 > 0）
        if (pid <= 0) {
            throw new IllegalArgumentException("invalid pid: " + pid);
        }
        state.setWaitingOnPid(pid);
        state.setWaitingReason(StrUtil.blankToDefault(reason, "waiting on pid " + pid));
        state.setWaitingSince(System.currentTimeMillis());
        save(session, state);
    }

    /** 清除等待屏障。 */
    public void stopWaiting(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return;
        }
        state.clearWaitBarrier();
        save(session, state);
    }

    /** 渲染当前契约块（/goal show 用）。无 active 目标或无契约时返回占位提示。 */
    public String renderContract(SessionRecord session) {
        GoalState state = get(session);
        if (state == null
                || state.getStatus() == null
                || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return "(no active goal)";
        }
        if (!state.hasContract()) {
            return "(no contract set)";
        }
        return state.getContract().renderBlock();
    }

    /**
     * 渲染目标状态行，含轮次预算、子目标数、契约标记、等待屏障等 meta。
     *
     * @param session 会话参数。
     * @return 返回状态Line结果；无目标或已清除返回提示。
     */
    public String statusLine(SessionRecord session) {
        GoalState state = get(session);
        if (state == null || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return "No active goal. Set one with /goal <text>.";
        }
        String turns = state.getTurnsUsed() + "/" + state.getMaxTurns() + " turns";
        String meta = turns;
        // 子目标数 meta
        if (state.getSubgoals() != null && !state.getSubgoals().isEmpty()) {
            meta += ", " + state.getSubgoals().size() + " subgoal(s)";
        }
        // 完成契约标记 meta
        if (state.hasContract()) {
            meta += ", contract";
        }
        // judge 摘要 meta（最近一次裁决的 verdict 与 reason）
        meta += judgeSummary(state);
        // 等待屏障状态（parked）：仍在等待时优先展示
        if (state.isActive() && state.isWaiting()) {
            String reason = StrUtil.blankToDefault(state.getWaitingReason(), "waiting");
            return "⏳ Goal (parked, " + meta + " — " + reason + "): " + state.getGoal();
        }
        if (GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return "⊙ Goal (active, " + meta + "): " + state.getGoal();
        }
        if (GoalState.STATUS_PAUSED.equals(state.getStatus())) {
            String extra =
                    StrUtil.isBlank(state.getPausedReason()) ? "" : " — " + state.getPausedReason();
            return "⏸ Goal (paused, " + meta + extra + "): " + state.getGoal();
        }
        if (GoalState.STATUS_DONE.equals(state.getStatus())) {
            return "✓ Goal done (" + meta + "): " + state.getGoal();
        }
        return "Goal (" + state.getStatus() + ", " + meta + "): " + state.getGoal();
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
        // 加载时惰性清理失效屏障（死 pid / 过期 deadline）并回写持久化，
        // 保证内存判定与持久化 JSON 一致；语义与 get() 一致（无目标返回 null）。
        GoalState state = getAndLazyClearBarrier(session);
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
