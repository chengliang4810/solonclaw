package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.llm.LlmUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 承载用量成本Calculator相关状态和辅助逻辑。 */
public class UsageCostCalculator {
    /** 记录用量成本Calculator中的catalog。 */
    private final PriceCatalog catalog;

    /**
     * 创建用量成本Calculator实例，并注入运行所需依赖。
     *
     * @param catalog catalog 参数。
     */
    public UsageCostCalculator(PriceCatalog catalog) {
        this.catalog = catalog == null ? PriceCatalog.empty() : catalog;
    }

    /**
     * 执行calculate相关逻辑。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     * @param inputTokens 输入 token 数。
     * @param outputTokens 输出 token 数。
     * @param cacheReadTokens 缓存读取 token 数。
     * @param cacheWriteTokens 缓存写入 token 数。
     * @param reasoningTokens 推理 token 数。
     * @return 返回calculate结果。
     */
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

    /**
     * 执行calculate相关逻辑。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     * @param usage 用量参数。
     * @return 返回calculate结果。
     */
    public UsageCost calculate(String provider, String model, LlmUsage usage) {
        LlmUsage value = usage == null ? LlmUsage.empty() : usage;
        return calculate(
                provider,
                model,
                value.getInputTokens(),
                value.getOutputTokens(),
                value.getCacheReadTokens(),
                value.getCacheWriteTokens(),
                value.getReasoningTokens(),
                value.getRequestCount() <= 0L ? 1L : value.getRequestCount());
    }

    /**
     * 执行calculate相关逻辑。
     *
     * @param provider 模型或能力提供方。
     * @param model 模型名称。
     * @param inputTokens 输入 token 数。
     * @param outputTokens 输出 token 数。
     * @param cacheReadTokens 缓存读取 token 数。
     * @param cacheWriteTokens 缓存写入 token 数。
     * @param reasoningTokens 推理 token 数。
     * @param requestCount 请求Count请求载荷。
     * @return 返回calculate结果。
     */
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
        cost.setPriceSourceUrl(price.getSourceUrl());
        cost.setPricingVersion(price.getPricingVersion());
        cost.setPriceFetchedAt(price.getFetchedAt());
        cost.setPricedAt(System.currentTimeMillis());
        cost.setTotalMicros(
                tokenUsageMicros(
                                input,
                                price.inputMicrosPerTokenExact(),
                                output,
                                price.outputMicrosPerTokenExact(),
                                cacheRead,
                                price.cacheReadMicrosPerTokenExact(),
                                cacheWrite,
                                price.cacheWriteMicrosPerTokenExact(),
                                reasoning,
                                price.reasoningMicrosPerTokenExact())
                        + requests * price.getRequestMicrosPerRequest());
        return cost;
    }

    /**
     * 执行token用量Micros相关逻辑。
     *
     * @param input 输入参数。
     * @param inputPrice 输入价格参数。
     * @param output 命令执行输出文本。
     * @param outputPrice 输出价格参数。
     * @param cacheRead 缓存Read参数。
     * @param cacheReadPrice 缓存Read价格参数。
     * @param cacheWrite 缓存写入参数。
     * @param cacheWritePrice 缓存写入价格参数。
     * @param reasoning 推理参数。
     * @param reasoningPrice 推理价格参数。
     * @return 返回token用量Micros结果。
     */
    private long tokenUsageMicros(
            long input,
            BigDecimal inputPrice,
            long output,
            BigDecimal outputPrice,
            long cacheRead,
            BigDecimal cacheReadPrice,
            long cacheWrite,
            BigDecimal cacheWritePrice,
            long reasoning,
            BigDecimal reasoningPrice) {
        BigDecimal total = BigDecimal.ZERO;
        total = total.add(usageMicros(input, inputPrice));
        total = total.add(usageMicros(output, outputPrice));
        total = total.add(usageMicros(cacheRead, cacheReadPrice));
        total = total.add(usageMicros(cacheWrite, cacheWritePrice));
        total = total.add(usageMicros(reasoning, reasoningPrice));
        return total.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * 执行用量Micros相关逻辑。
     *
     * @param tokens token参数。
     * @param microsPerToken microsPertoken参数。
     * @return 返回用量Micros结果。
     */
    private BigDecimal usageMicros(long tokens, BigDecimal microsPerToken) {
        if (tokens <= 0L || microsPerToken == null || microsPerToken.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return microsPerToken.multiply(BigDecimal.valueOf(tokens));
    }
}
