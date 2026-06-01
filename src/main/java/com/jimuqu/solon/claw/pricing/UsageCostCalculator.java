package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;

/** Calculates model usage cost with micros precision. */
public class UsageCostCalculator {
    private final PriceCatalog catalog;

    public UsageCostCalculator(PriceCatalog catalog) {
        this.catalog = catalog == null ? PriceCatalog.empty() : catalog;
    }

    public UsageCost calculate(
            String provider,
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long reasoningTokens) {
        return calculate(
                provider,
                model,
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                reasoningTokens,
                1L);
    }

    public UsageCost calculate(
            String provider,
            String model,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long reasoningTokens,
            long requestCount) {
        UsageCost cost = new UsageCost();
        ModelPrice price = catalog.find(provider, model);
        long input = Math.max(0L, inputTokens);
        long output = Math.max(0L, outputTokens);
        long cacheRead = Math.max(0L, cacheReadTokens);
        long cacheWrite = Math.max(0L, cacheWriteTokens);
        long reasoning = Math.max(0L, reasoningTokens);
        long requests = Math.max(0L, requestCount);
        cost.setRequestCount(requests);
        if (price == null) {
            cost.setPricingAvailable(false);
            cost.setUnpricedInputTokens(input);
            cost.setUnpricedOutputTokens(output);
            cost.setUnpricedCacheReadTokens(cacheRead);
            cost.setUnpricedCacheWriteTokens(cacheWrite);
            cost.setUnpricedReasoningTokens(reasoning);
            return cost;
        }
        cost.setPricingAvailable(true);
        cost.setCurrency(StrUtil.blankToDefault(price.getCurrency(), "USD"));
        cost.setPriceSource(StrUtil.blankToDefault(price.getSource(), "configured"));
        cost.setPricedAt(System.currentTimeMillis());
        cost.setTotalMicros(
                input * price.getInputMicrosPerToken()
                        + output * price.getOutputMicrosPerToken()
                        + cacheRead * price.getCacheReadMicrosPerToken()
                        + cacheWrite * price.getCacheWriteMicrosPerToken()
                        + reasoning * price.getReasoningMicrosPerToken()
                        + requests * price.getRequestMicrosPerRequest());
        return cost;
    }
}
