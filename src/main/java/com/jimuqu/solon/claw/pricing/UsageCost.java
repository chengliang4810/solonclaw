package com.jimuqu.solon.claw.pricing;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Result of pricing a usage event. */
@Getter
@Setter
@NoArgsConstructor
public class UsageCost {
    private boolean pricingAvailable;
    private String currency;
    private long totalMicros;
    private String priceSource;
    private long pricedAt;
    private long unpricedInputTokens;
    private long unpricedOutputTokens;
    private long unpricedCacheReadTokens;
    private long unpricedCacheWriteTokens;
    private long unpricedReasoningTokens;

    public long getUnpricedTotalTokens() {
        return unpricedInputTokens
                + unpricedOutputTokens
                + unpricedCacheReadTokens
                + unpricedCacheWriteTokens
                + unpricedReasoningTokens;
    }
}
