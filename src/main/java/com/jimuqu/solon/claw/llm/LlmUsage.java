package com.jimuqu.solon.claw.llm;

import com.jimuqu.solon.claw.core.model.LlmResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** 单次 LLM 调用的 token 使用量快照。 */
public class LlmUsage {
    private final long promptTokens;
    private final long completionTokens;
    private final long cacheReadTokens;
    private final long cacheWriteTokens;
    private final long reasoningTokens;
    private final long requestCount;
    private final long latencyMs;
    private final String rawUsageJson;

    private LlmUsage(Builder builder) {
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.cacheReadTokens = builder.cacheReadTokens;
        this.cacheWriteTokens = builder.cacheWriteTokens;
        this.reasoningTokens = builder.reasoningTokens;
        this.requestCount = builder.requestCount;
        this.latencyMs = builder.latencyMs;
        this.rawUsageJson = builder.rawUsageJson;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getInputTokens() {
        return promptTokens;
    }

    public long getCanonicalInputTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getOutputTokens() {
        return completionTokens;
    }

    public long getCanonicalOutputTokens() {
        return completionTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getCanonicalCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getCanonicalCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getReasoningTokens() {
        return reasoningTokens;
    }

    public long getCanonicalReasoningTokens() {
        return reasoningTokens;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getCanonicalRequestCount() {
        return requestCount <= 0L ? 1L : requestCount;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getRawUsageJson() {
        return rawUsageJson;
    }

    public long getCanonicalPromptTokens() {
        return promptTokens + cacheReadTokens + cacheWriteTokens;
    }

    public long getCanonicalTotalTokens() {
        return getCanonicalPromptTokens() + completionTokens;
    }

    public long getTotalTokens() {
        return promptTokens
                + completionTokens
                + cacheReadTokens
                + cacheWriteTokens
                + reasoningTokens;
    }

    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("input_tokens", Long.valueOf(promptTokens));
        data.put("output_tokens", Long.valueOf(completionTokens));
        data.put("cache_read_tokens", Long.valueOf(cacheReadTokens));
        data.put("cache_write_tokens", Long.valueOf(cacheWriteTokens));
        data.put("reasoning_tokens", Long.valueOf(reasoningTokens));
        data.put("request_count", Long.valueOf(getCanonicalRequestCount()));
        if (notBlank(rawUsageJson)) {
            data.put("raw_usage_json", rawUsageJson);
        }
        return data;
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
                .rawUsageJson(result.getRawUsageJson())
                .build();
    }

    /** 从 provider 原始 usage shape 中归一出 canonical token buckets。 */
    public static LlmUsage fromRawUsage(Object rawUsage, String provider, String apiMode) {
        java.util.List<Object> usageItems = asList(rawUsage);
        if (!usageItems.isEmpty()) {
            Builder total = builder().rawUsageJson(rawUsageJson(rawUsage));
            long inputTokens = 0L;
            long outputTokens = 0L;
            long cacheReadTokens = 0L;
            long cacheWriteTokens = 0L;
            long reasoningTokens = 0L;
            long requestCount = 0L;
            for (Object item : usageItems) {
                LlmUsage usage = fromRawUsage(item, provider, apiMode);
                inputTokens += usage.getInputTokens();
                outputTokens += usage.getOutputTokens();
                cacheReadTokens += usage.getCacheReadTokens();
                cacheWriteTokens += usage.getCacheWriteTokens();
                reasoningTokens += usage.getReasoningTokens();
                requestCount += usage.getCanonicalRequestCount();
            }
            return total.inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .cacheReadTokens(cacheReadTokens)
                    .cacheWriteTokens(cacheWriteTokens)
                    .reasoningTokens(reasoningTokens)
                    .requestCount(requestCount <= 0L ? 1L : requestCount)
                    .build();
        }
        Map<String, Object> usage = asMap(rawUsage);
        if (usage.isEmpty()) {
            return empty();
        }
        String providerName = normalize(provider);
        String mode = normalize(apiMode);
        boolean anthropic = "anthropic".equals(providerName) || "anthropic_messages".equals(mode);
        boolean responses = mode.contains("responses") || mode.contains("codex");

        long inputTokens;
        long outputTokens = firstLong(usage, "output_tokens", "completion_tokens");
        long cacheReadTokens;
        long cacheWriteTokens;
        if (anthropic) {
            inputTokens = firstLong(usage, "input_tokens", "prompt_tokens");
            cacheReadTokens = firstLong(usage, "cache_read_input_tokens", "cache_read_tokens");
            cacheWriteTokens = firstLong(
                    usage,
                    "cache_creation_input_tokens",
                    "cache_write_input_tokens",
                    "cache_write_tokens");
        } else if (responses || nestedMap(usage, "input_tokens_details") != null) {
            long inputTotal = firstLong(usage, "input_tokens", "prompt_tokens");
            Map<String, Object> details = nestedMap(usage, "input_tokens_details");
            cacheReadTokens = max(
                    longValue(details, "cached_tokens"),
                    longValue(usage, "cache_read_input_tokens"),
                    longValue(usage, "cache_read_tokens"));
            cacheWriteTokens = max(
                    longValue(details, "cache_creation_tokens"),
                    longValue(details, "cache_write_tokens"),
                    longValue(usage, "cache_creation_input_tokens"),
                    longValue(usage, "cache_write_tokens"));
            inputTokens = Math.max(0L, inputTotal - cacheReadTokens - cacheWriteTokens);
        } else if (hasKey(usage, "prompt_tokens")) {
            long promptTotal = longValue(usage, "prompt_tokens");
            Map<String, Object> details = nestedMap(usage, "prompt_tokens_details");
            cacheReadTokens = max(
                    longValue(details, "cached_tokens"),
                    longValue(usage, "cache_read_input_tokens"),
                    longValue(usage, "cache_read_tokens"));
            cacheWriteTokens = max(
                    longValue(details, "cache_write_tokens"),
                    longValue(details, "cache_creation_tokens"),
                    longValue(usage, "cache_creation_input_tokens"),
                    longValue(usage, "cache_write_tokens"));
            inputTokens = Math.max(0L, promptTotal - cacheReadTokens - cacheWriteTokens);
        } else {
            inputTokens = longValue(usage, "input_tokens");
            cacheReadTokens = firstLong(usage, "cache_read_input_tokens", "cache_read_tokens");
            cacheWriteTokens = firstLong(
                    usage,
                    "cache_creation_input_tokens",
                    "cache_write_input_tokens",
                    "cache_write_tokens");
        }

        long reasoningTokens = max(
                longValue(nestedMap(usage, "completion_tokens_details"), "reasoning_tokens"),
                longValue(nestedMap(usage, "output_tokens_details"), "reasoning_tokens"),
                longValue(usage, "reasoning_tokens"));
        long requestCount = firstPositiveLong(usage, "request_count", "requests");
        return builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheReadTokens(cacheReadTokens)
                .cacheWriteTokens(cacheWriteTokens)
                .reasoningTokens(reasoningTokens)
                .requestCount(requestCount <= 0L ? 1L : requestCount)
                .rawUsageJson(rawUsageJson(rawUsage))
                .build();
    }

    /** 空 usage 快照。 */
    public static LlmUsage empty() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> asList(Object rawUsage) {
        if (rawUsage == null) {
            return java.util.Collections.emptyList();
        }
        Object value = rawUsage;
        try {
            if (rawUsage instanceof ONode) {
                value = ONode.deserialize(((ONode) rawUsage).toJson(), Object.class);
            } else if (rawUsage instanceof String) {
                String text = ((String) rawUsage).trim();
                if (text.length() == 0 || !text.startsWith("[")) {
                    return java.util.Collections.emptyList();
                }
                value = ONode.deserialize(text, Object.class);
            }
        } catch (Exception ignored) {
            return java.util.Collections.emptyList();
        }
        if (value instanceof java.util.List) {
            return (java.util.List<Object>) value;
        }
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object rawUsage) {
        if (rawUsage == null) {
            return new LinkedHashMap<String, Object>();
        }
        Object value = rawUsage;
        try {
            if (rawUsage instanceof ONode) {
                value = ONode.deserialize(((ONode) rawUsage).toJson(), LinkedHashMap.class);
            } else if (rawUsage instanceof String) {
                String text = ((String) rawUsage).trim();
                if (text.length() == 0) {
                    return new LinkedHashMap<String, Object>();
                }
                value = ONode.deserialize(text, Object.class);
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        if (value instanceof ONode) {
            return asMap(value);
        }
        return null;
    }

    private static boolean hasKey(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key);
    }

    private static long firstLong(Map<String, Object> map, String... keys) {
        if (keys == null) {
            return 0L;
        }
        for (String key : keys) {
            long value = longValue(map, key);
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    private static long firstPositiveLong(Map<String, Object> map, String... keys) {
        return firstLong(map, keys);
    }

    private static long longValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return 0L;
        }
        Object value = map.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return Math.max(0L, ((Number) value).longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long max(long... values) {
        long result = 0L;
        if (values == null) {
            return result;
        }
        for (long value : values) {
            result = Math.max(result, value);
        }
        return result;
    }

    private static String rawUsageJson(Object rawUsage) {
        if (rawUsage == null) {
            return null;
        }
        try {
            if (rawUsage instanceof ONode) {
                return ((ONode) rawUsage).toJson();
            }
            if (rawUsage instanceof String) {
                String text = ((String) rawUsage).trim();
                return text.length() == 0 ? null : text;
            }
            return ONode.serialize(rawUsage);
        } catch (Exception ignored) {
            return String.valueOf(rawUsage);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String value) {
        return value != null && value.trim().length() > 0;
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
        private String rawUsageJson;

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

        public Builder rawUsageJson(String rawUsageJson) {
            this.rawUsageJson = rawUsageJson;
            return this;
        }

        public LlmUsage build() {
            return new LlmUsage(this);
        }
    }
}
