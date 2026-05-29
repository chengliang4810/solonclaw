package com.jimuqu.solon.claw.usage;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.model.AgentRunRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.repository.AgentRunRepository;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.pricing.UsageCost;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;
import java.util.List;

/** Creates approximate usage events from legacy run/session token totals. */
public class UsageBackfillService {
    private final UsageEventRepository usageEventRepository;
    private final AgentRunRepository agentRunRepository;
    private final SessionRepository sessionRepository;
    private final UsageCostCalculator calculator;

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

    public int backfillApproximate() throws Exception {
        int inserted = 0;
        List<AgentRunRecord> runs = agentRunRepository.listFinishedWithUsage(10000);
        for (AgentRunRecord run : runs) {
            UsageEventRecord event = fromRun(run);
            if (event != null && usageEventRepository.insertIfAbsent(event)) {
                inserted++;
            }
        }
        int sessionCount = sessionRepository.countAll();
        List<SessionRecord> sessions = sessionRepository.listRecent(sessionCount);
        for (SessionRecord session : sessions) {
            UsageEventRecord event = fromSession(session);
            if (event != null && usageEventRepository.insertIfAbsent(event)) {
                inserted++;
            }
        }
        return inserted;
    }

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
        event.setCreatedAt(run.getFinishedAt() > 0 ? run.getFinishedAt() : run.getStartedAt());
        applyCost(event);
        return event;
    }

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
        event.setCreatedAt(
                session.getLastUsageAt() > 0
                        ? session.getLastUsageAt()
                        : Math.max(session.getCreatedAt(), session.getUpdatedAt()));
        applyCost(event);
        return event;
    }

    private UsageEventRecord base(String eventId) {
        UsageEventRecord event = new UsageEventRecord();
        event.setEventId(eventId);
        event.setBackfillApproximate(true);
        return event;
    }

    private void applyCost(UsageEventRecord event) {
        UsageCost cost =
                calculator.calculate(
                        event.getProvider(),
                        event.getModel(),
                        event.getInputTokens(),
                        event.getOutputTokens(),
                        event.getCacheReadTokens(),
                        event.getCacheWriteTokens(),
                        event.getReasoningTokens());
        event.setCostMicros(cost.getTotalMicros());
        event.setCurrency(cost.getCurrency());
        event.setPriceSource(cost.getPriceSource());
        event.setPricingAvailable(cost.isPricingAvailable());
        event.setUnpricedInputTokens(cost.getUnpricedInputTokens());
        event.setUnpricedOutputTokens(cost.getUnpricedOutputTokens());
        event.setUnpricedCacheReadTokens(cost.getUnpricedCacheReadTokens());
        event.setUnpricedCacheWriteTokens(cost.getUnpricedCacheWriteTokens());
        event.setUnpricedReasoningTokens(cost.getUnpricedReasoningTokens());
        event.setPricedAt(cost.getPricedAt());
    }
}
