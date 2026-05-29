package com.jimuqu.solon.claw.usage;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable token usage event. */
@Getter
@Setter
@NoArgsConstructor
public class UsageEventRecord {
    private String eventId;
    private String sessionId;
    private String runId;
    private String sourceKey;
    private String provider;
    private String model;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private long reasoningTokens;
    private long totalTokens;
    private long costMicros;
    private String currency;
    private String priceSource;
    private boolean pricingAvailable;
    private long unpricedInputTokens;
    private long unpricedOutputTokens;
    private long unpricedCacheReadTokens;
    private long unpricedCacheWriteTokens;
    private long unpricedReasoningTokens;
    private long pricedAt;
    private long createdAt;
    private boolean backfillApproximate;
}
