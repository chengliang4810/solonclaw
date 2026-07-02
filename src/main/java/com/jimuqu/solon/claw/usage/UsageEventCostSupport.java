package com.jimuqu.solon.claw.usage;

import com.jimuqu.solon.claw.pricing.UsageCost;
import com.jimuqu.solon.claw.pricing.UsageCostCalculator;

/** 统一维护用量事件的成本计算与字段回填，避免多处拷贝价格字段时遗漏。 */
public final class UsageEventCostSupport {
    private UsageEventCostSupport() {}

    /**
     * 根据事件中的 token 数据计算成本。
     *
     * @param calculator 用量成本计算器。
     * @param event 用量事件。
     * @return 返回计算后的成本结果。
     */
    public static UsageCost calculate(UsageCostCalculator calculator, UsageEventRecord event) {
        return calculator.calculate(
                event.getProvider(),
                event.getModel(),
                event.getInputTokens(),
                event.getOutputTokens(),
                event.getCacheReadTokens(),
                event.getCacheWriteTokens(),
                event.getReasoningTokens(),
                event.getRequestCount());
    }

    /**
     * 将计算后的成本字段写回用量事件。
     *
     * @param event 用量事件。
     * @param cost 成本结果。
     */
    public static void apply(UsageEventRecord event, UsageCost cost) {
        if (event == null || cost == null) {
            return;
        }
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

    /**
     * 在成本计算器不可用时，将事件标记为未定价并保留各类 token 数。
     *
     * @param event 用量事件。
     */
    public static void markUnpriced(UsageEventRecord event) {
        if (event == null) {
            return;
        }
        event.setPricingAvailable(false);
        event.setUnpricedInputTokens(event.getInputTokens());
        event.setUnpricedOutputTokens(event.getOutputTokens());
        event.setUnpricedCacheReadTokens(event.getCacheReadTokens());
        event.setUnpricedCacheWriteTokens(event.getCacheWriteTokens());
        event.setUnpricedReasoningTokens(event.getReasoningTokens());
    }
}
