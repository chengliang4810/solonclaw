package com.jimuqu.solon.claw.pricing;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 承载用量成本相关状态和辅助逻辑。 */
@Getter
@Setter
@NoArgsConstructor
public class UsageCost {
    /** 是否启用价格Available。 */
    private boolean pricingAvailable;

    /** 记录用量成本中的currency。 */
    private String currency;

    /** 记录用量成本中的totalMicros。 */
    private long totalMicros;

    /** 记录用量成本中的价格来源。 */
    private String priceSource;

    /** 记录用量成本中的价格来源URL。 */
    private String priceSourceUrl;

    /** 记录用量成本中的价格版本。 */
    private String pricingVersion;

    /** 记录用量成本中的价格Fetched时间。 */
    private long priceFetchedAt;

    /** 记录用量成本中的priced时间。 */
    private long pricedAt;

    /** 记录用量成本中的unpriced输入 token。 */
    private long unpricedInputTokens;

    /** 记录用量成本中的unpriced输出 token。 */
    private long unpricedOutputTokens;

    /** 记录用量成本中的unpriced缓存读取 token。 */
    private long unpricedCacheReadTokens;

    /** 记录用量成本中的unpriced缓存写入 token。 */
    private long unpricedCacheWriteTokens;

    /** 记录用量成本中的unpriced推理 token。 */
    private long unpricedReasoningTokens;

    /** 记录用量成本中的请求次数。 */
    private long requestCount;

    /**
     * 读取Unpriced Total token。
     *
     * @return 返回读取到的Unpriced Total token。
     */
    public long getUnpricedTotalTokens() {
        return unpricedInputTokens
                + unpricedOutputTokens
                + unpricedCacheReadTokens
                + unpricedCacheWriteTokens
                + unpricedReasoningTokens;
    }
}
