package com.jimuqu.solon.claw.usage;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 表示用量事件数据，在服务、仓储和接口之间传递。 */
@Getter
@Setter
@NoArgsConstructor
public class UsageEventRecord {
    /** 记录用量事件中的事件标识。 */
    private String eventId;

    /** 记录用量事件中的会话标识。 */
    private String sessionId;

    /** 记录用量事件中的运行标识。 */
    private String runId;

    /** 记录用量事件中的来源键。 */
    private String sourceKey;

    /** 记录用量事件中的提供方。 */
    private String provider;

    /** 记录用量事件中的模型。 */
    private String model;

    /** 记录用量事件中的输入 token。 */
    private long inputTokens;

    /** 记录用量事件中的输出 token。 */
    private long outputTokens;

    /** 记录用量事件中的缓存读取 token。 */
    private long cacheReadTokens;

    /** 记录用量事件中的缓存写入 token。 */
    private long cacheWriteTokens;

    /** 记录用量事件中的推理 token。 */
    private long reasoningTokens;

    /** 记录用量事件中的totaltoken。 */
    private long totalTokens;

    /** 记录用量事件中的请求次数。 */
    private long requestCount;

    /** 记录用量事件中的成本Micros。 */
    private long costMicros;

    /** 记录用量事件中的currency。 */
    private String currency;

    /** 记录用量事件中的价格来源。 */
    private String priceSource;

    /** 记录用量事件中的价格来源URL。 */
    private String priceSourceUrl;

    /** 记录用量事件中的价格版本。 */
    private String pricingVersion;

    /** 记录用量事件中的价格Fetched时间。 */
    private long priceFetchedAt;

    /** 记录用量事件中的原始用量 JSON。 */
    private String rawUsageJson;

    /** 是否启用价格Available。 */
    private boolean pricingAvailable;

    /** 记录用量事件中的unpriced输入 token。 */
    private long unpricedInputTokens;

    /** 记录用量事件中的unpriced输出 token。 */
    private long unpricedOutputTokens;

    /** 记录用量事件中的unpriced缓存读取 token。 */
    private long unpricedCacheReadTokens;

    /** 记录用量事件中的unpriced缓存写入 token。 */
    private long unpricedCacheWriteTokens;

    /** 记录用量事件中的unpriced推理 token。 */
    private long unpricedReasoningTokens;

    /** 记录用量事件中的priced时间。 */
    private long pricedAt;

    /** 记录用量事件中的创建时间。 */
    private long createdAt;

    /** 是否启用backfillApproximate。 */
    private boolean backfillApproximate;
}
