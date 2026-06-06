package com.jimuqu.solon.claw.usage;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.pricing.UsageCost;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 提供用量Backfill相关业务能力，封装调用方不需要感知的运行细节。 */
public class UsageBackfillService {
    /** 保存用量事件仓储依赖，用于访问持久化数据。 */
    private final UsageEventRepository usageEventRepository;

    /** 保存Agent运行仓储依赖，用于访问持久化数据。 */
    private final AgentRunRepository agentRunRepository;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 记录用量Backfill中的calculator。 */
    private final UsageCostCalculator calculator;

    /**
     * 创建用量Backfill服务实例，并注入运行所需依赖。
     *
     * @param usageEventRepository 用量事件仓储依赖。
     * @param agentRunRepository Agent运行仓储依赖。
     * @param sessionRepository 会话仓储依赖。
     * @param calculator calculator 参数。
     */
    public UsageBackfillService(
            UsageEventRepository usageEventRepository,
            AgentRunRepository agentRunRepository,
            SessionRepository sessionRepository,
            UsageCostCalculator calculator) {
        this.usageEventRepository = usageEventRepository;
        this.agentRunRepository = agentRunRepository;
        this.sessionRepository = sessionRepository;
        this.calculator = calculator;
    }

    /**
     * 执行backfillApproximate相关逻辑。
     *
     * @return 返回backfill Approximate结果。
     */
    public int backfillApproximate() throws Exception {
        int inserted = 0;
        Set<String> sessionsWithRunUsage = new LinkedHashSet<String>();
        List<AgentRunRecord> runs = agentRunRepository.listFinishedWithUsage(10000);
        for (AgentRunRecord run : runs) {
            UsageEventRecord event = fromRun(run);
            if (event != null) {
                if (StrUtil.isNotBlank(event.getSessionId())) {
                    sessionsWithRunUsage.add(event.getSessionId());
                }
                if (usageEventRepository.insertIfAbsent(event)) {
                    inserted++;
                }
            }
        }
        int sessionCount = sessionRepository.countAll();
        List<SessionRecord> sessions = sessionRepository.listRecent(sessionCount);
        for (SessionRecord session : sessions) {
            if (session != null && sessionsWithRunUsage.contains(session.getSessionId())) {
                continue;
            }
            UsageEventRecord event = fromSession(session);
            if (event != null && usageEventRepository.insertIfAbsent(event)) {
                inserted++;
            }
        }
        return inserted;
    }

    /**
     * 从输入转换运行。
     *
     * @param run 运行参数。
     * @return 返回运行结果。
     */
    private UsageEventRecord fromRun(AgentRunRecord run) {
        if (run == null || StrUtil.isBlank(run.getRunId())) {
            return null;
        }
        long input = Math.max(0L, run.getInputTokens());
        long output = Math.max(0L, run.getOutputTokens());
        long total = Math.max(input + output, run.getTotalTokens());
        if (total <= 0) {
            return null;
        }
        UsageEventRecord event = base("backfill-run-" + run.getRunId());
        event.setRunId(run.getRunId());
        event.setSessionId(run.getSessionId());
        event.setSourceKey(run.getSourceKey());
        event.setProvider(run.getProvider());
        event.setModel(run.getModel());
        event.setInputTokens(input);
        event.setOutputTokens(output);
        event.setTotalTokens(total);
        event.setRequestCount(1L);
        event.setCreatedAt(run.getFinishedAt() > 0 ? run.getFinishedAt() : run.getStartedAt());
        applyCost(event);
        return event;
    }

    /**
     * 从输入转换会话。
     *
     * @param session 会话参数。
     * @return 返回会话结果。
     */
    private UsageEventRecord fromSession(SessionRecord session) {
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            return null;
        }
        long total = Math.max(0L, session.getCumulativeTotalTokens());
        if (total <= 0) {
            return null;
        }
        UsageEventRecord event = base("backfill-session-" + session.getSessionId());
        event.setSessionId(session.getSessionId());
        event.setSourceKey(session.getSourceKey());
        event.setProvider(session.getLastResolvedProvider());
        event.setModel(
                StrUtil.blankToDefault(session.getLastResolvedModel(), session.getModelOverride()));
        event.setInputTokens(Math.max(0L, session.getCumulativeInputTokens()));
        event.setOutputTokens(Math.max(0L, session.getCumulativeOutputTokens()));
        event.setCacheReadTokens(Math.max(0L, session.getCumulativeCacheReadTokens()));
        event.setCacheWriteTokens(Math.max(0L, session.getCumulativeCacheWriteTokens()));
        event.setReasoningTokens(Math.max(0L, session.getCumulativeReasoningTokens()));
        event.setTotalTokens(total);
        event.setRequestCount(1L);
        event.setCreatedAt(
                session.getLastUsageAt() > 0
                        ? session.getLastUsageAt()
                        : Math.max(session.getCreatedAt(), session.getUpdatedAt()));
        applyCost(event);
        return event;
    }

    /**
     * 执行基础相关逻辑。
     *
     * @param eventId 事件标识。
     * @return 返回base结果。
     */
    private UsageEventRecord base(String eventId) {
        UsageEventRecord event = new UsageEventRecord();
        event.setEventId(eventId);
        event.setBackfillApproximate(true);
        return event;
    }

    /**
     * 应用成本。
     *
     * @param event 事件参数。
     */
    private void applyCost(UsageEventRecord event) {
        UsageCost cost =
                calculator.calculate(
                        event.getProvider(),
                        event.getModel(),
                        event.getInputTokens(),
                        event.getOutputTokens(),
                        event.getCacheReadTokens(),
                        event.getCacheWriteTokens(),
                        event.getReasoningTokens(),
                        event.getRequestCount());
        event.setCostMicros(cost.getTotalMicros());
        event.setCurrency(cost.getCurrency());
        event.setPriceSource(cost.getPriceSource());
        event.setPriceSourceUrl(cost.getPriceSourceUrl());
        event.setPricingVersion(cost.getPricingVersion());
        event.setPriceFetchedAt(cost.getPriceFetchedAt());
        event.setPricingAvailable(cost.isPricingAvailable());
        event.setUnpricedInputTokens(cost.getUnpricedInputTokens());
        event.setUnpricedOutputTokens(cost.getUnpricedOutputTokens());
        event.setUnpricedCacheReadTokens(cost.getUnpricedCacheReadTokens());
        event.setUnpricedCacheWriteTokens(cost.getUnpricedCacheWriteTokens());
        event.setUnpricedReasoningTokens(cost.getUnpricedReasoningTokens());
        event.setPricedAt(cost.getPricedAt());
    }
}
