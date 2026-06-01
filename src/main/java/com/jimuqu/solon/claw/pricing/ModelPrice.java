package com.jimuqu.solon.claw.pricing;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-token model pricing in micros. */
@Getter
@Setter
@NoArgsConstructor
public class ModelPrice {
    private String provider;
    private String model;
    private String currency = "USD";
    private long inputMicrosPerToken;
    private long outputMicrosPerToken;
    private long cacheReadMicrosPerToken;
    private long cacheWriteMicrosPerToken;
    private long reasoningMicrosPerToken;
    private long requestMicrosPerRequest;
    private String source;

    public long getPromptMicrosPerToken() {
        return inputMicrosPerToken;
    }

    public void setPromptMicrosPerToken(long promptMicrosPerToken) {
        this.inputMicrosPerToken = Math.max(0L, promptMicrosPerToken);
    }

    public long getCompletionMicrosPerToken() {
        return outputMicrosPerToken;
    }

    public void setCompletionMicrosPerToken(long completionMicrosPerToken) {
        this.outputMicrosPerToken = Math.max(0L, completionMicrosPerToken);
    }

    public String key() {
        return normalize(provider) + "/" + normalize(model);
    }

    static String normalize(String value) {
        return StrUtil.nullToEmpty(value).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
