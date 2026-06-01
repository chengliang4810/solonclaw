package com.jimuqu.solon.claw.llm;

import com.jimuqu.solon.claw.core.model.LlmResult;

/** 单次 LLM 调用的 token 使用量快照。 */
public class LlmUsage {
    private final long promptTokens;
    private final long completionTokens;
    private final long cacheReadTokens;
    private final long cacheWriteTokens;
    private final long reasoningTokens;
    private final long requestCount;
    private final long latencyMs;

    private LlmUsage(Builder builder) {
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.cacheReadTokens = builder.cacheReadTokens;
        this.cacheWriteTokens = builder.cacheWriteTokens;
        this.reasoningTokens = builder.reasoningTokens;
        this.requestCount = builder.requestCount;
        this.latencyMs = builder.latencyMs;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getInputTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getOutputTokens() {
        return completionTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getReasoningTokens() {
        return reasoningTokens;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public long getTotalTokens() {
        return promptTokens
                + completionTokens
                + cacheReadTokens
                + cacheWriteTokens
                + reasoningTokens;
    }

    /** 从 LlmResult 中提取 usage 快照。 */
    public static LlmUsage from(LlmResult result) {
        if (result == null) {
            return empty();
        }
        return new Builder()
                .promptTokens(result.getInputTokens())
                .completionTokens(result.getOutputTokens())
                .cacheReadTokens(result.getCacheReadTokens())
                .cacheWriteTokens(result.getCacheWriteTokens())
                .reasoningTokens(result.getReasoningTokens())
                .requestCount(result.getRequestCount())
                .build();
    }

    /** 空 usage 快照。 */
    public static LlmUsage empty() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** LlmUsage 构建器。 */
    public static class Builder {
        private long promptTokens;
        private long completionTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private long reasoningTokens;
        private long requestCount;
        private long latencyMs;

        public Builder promptTokens(long promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        public Builder inputTokens(long inputTokens) {
            this.promptTokens = inputTokens;
            return this;
        }

        public Builder completionTokens(long completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder outputTokens(long outputTokens) {
            this.completionTokens = outputTokens;
            return this;
        }

        public Builder cacheReadTokens(long cacheReadTokens) {
            this.cacheReadTokens = cacheReadTokens;
            return this;
        }

        public Builder cacheWriteTokens(long cacheWriteTokens) {
            this.cacheWriteTokens = cacheWriteTokens;
            return this;
        }

        public Builder reasoningTokens(long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        public Builder requestCount(long requestCount) {
            this.requestCount = requestCount;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public LlmUsage build() {
            return new LlmUsage(this);
        }
    }
}
