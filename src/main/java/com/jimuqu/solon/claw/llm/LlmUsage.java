package com.jimuqu.solon.claw.llm;

import com.jimuqu.solon.claw.core.model.LlmResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/** 单次 LLM 调用的 token 使用量快照。 */
public class LlmUsage {
    /** 记录大模型用量中的提示词 token。 */
    private final long promptTokens;

    /** 记录大模型用量中的输出 token。 */
    private final long completionTokens;

    /** 记录大模型用量中的缓存读取 token。 */
    private final long cacheReadTokens;

    /** 记录大模型用量中的缓存写入 token。 */
    private final long cacheWriteTokens;

    /** 记录大模型用量中的推理 token。 */
    private final long reasoningTokens;

    /** 记录大模型用量中的请求次数。 */
    private final long requestCount;

    /** 记录大模型用量中的延迟毫秒数。 */
    private final long latencyMs;

    /** 记录大模型用量中的原始用量 JSON。 */
    private final String rawUsageJson;

    /**
     * 创建大模型用量实例，并注入运行所需依赖。
     *
     * @param builder 构建器参数。
     */
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

    /**
     * 读取提示词 token。
     *
     * @return 返回读取到的提示词 token。
     */
    public long getPromptTokens() {
        return promptTokens;
    }

    /**
     * 读取输入 token。
     *
     * @return 返回读取到的输入 token。
     */
    public long getInputTokens() {
        return promptTokens;
    }

    /**
     * 读取规范输入 token。
     *
     * @return 返回读取到的规范输入 token。
     */
    public long getCanonicalInputTokens() {
        return promptTokens;
    }

    /**
     * 读取输出 token。
     *
     * @return 返回读取到的输出 token。
     */
    public long getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 读取输出 token。
     *
     * @return 返回读取到的输出 token。
     */
    public long getOutputTokens() {
        return completionTokens;
    }

    /**
     * 读取规范输出 token。
     *
     * @return 返回读取到的规范输出 token。
     */
    public long getCanonicalOutputTokens() {
        return completionTokens;
    }

    /**
     * 读取缓存读取 token。
     *
     * @return 返回读取到的缓存读取 token。
     */
    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 读取规范缓存读取 token。
     *
     * @return 返回读取到的规范缓存读取 token。
     */
    public long getCanonicalCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 读取缓存写入 token。
     *
     * @return 返回读取到的缓存写入 token。
     */
    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    /**
     * 读取规范缓存写入 token。
     *
     * @return 返回读取到的规范缓存写入 token。
     */
    public long getCanonicalCacheWriteTokens() {
        return cacheWriteTokens;
    }

    /**
     * 读取推理 token。
     *
     * @return 返回读取到的推理 token。
     */
    public long getReasoningTokens() {
        return reasoningTokens;
    }

    /**
     * 读取规范推理 token。
     *
     * @return 返回读取到的规范推理 token。
     */
    public long getCanonicalReasoningTokens() {
        return reasoningTokens;
    }

    /**
     * 读取请求次数。
     *
     * @return 返回读取到的请求次数。
     */
    public long getRequestCount() {
        return requestCount;
    }

    /**
     * 读取规范请求次数。
     *
     * @return 返回读取到的规范请求次数。
     */
    public long getCanonicalRequestCount() {
        return requestCount <= 0L ? 1L : requestCount;
    }

    /**
     * 读取Latency Ms。
     *
     * @return 返回读取到的Latency Ms。
     */
    public long getLatencyMs() {
        return latencyMs;
    }

    /**
     * 读取原始用量 JSON。
     *
     * @return 返回读取到的原始用量 JSON。
     */
    public String getRawUsageJson() {
        return rawUsageJson;
    }

    /**
     * 读取规范提示词 token。
     *
     * @return 返回读取到的规范提示词 token。
     */
    public long getCanonicalPromptTokens() {
        return promptTokens + cacheReadTokens + cacheWriteTokens;
    }

    /**
     * 读取规范Total token。
     *
     * @return 返回读取到的规范Total token。
     */
    public long getCanonicalTotalTokens() {
        return getCanonicalPromptTokens() + completionTokens;
    }

    /**
     * 读取Total token。
     *
     * @return 返回读取到的Total token。
     */
    public long getTotalTokens() {
        return promptTokens
                + completionTokens
                + cacheReadTokens
                + cacheWriteTokens
                + reasoningTokens;
    }

    /**
     * 转换为规范Map。
     *
     * @return 返回转换后的规范Map。
     */
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
            cacheWriteTokens =
                    firstLong(
                            usage,
                            "cache_creation_input_tokens",
                            "cache_write_input_tokens",
                            "cache_write_tokens");
        } else if (responses || nestedMap(usage, "input_tokens_details") != null) {
            long inputTotal = firstLong(usage, "input_tokens", "prompt_tokens");
            Map<String, Object> details = nestedMap(usage, "input_tokens_details");
            cacheReadTokens =
                    max(
                            longValue(details, "cached_tokens"),
                            longValue(usage, "cache_read_input_tokens"),
                            longValue(usage, "cache_read_tokens"));
            cacheWriteTokens =
                    max(
                            longValue(details, "cache_creation_tokens"),
                            longValue(details, "cache_write_tokens"),
                            longValue(usage, "cache_creation_input_tokens"),
                            longValue(usage, "cache_write_tokens"));
            inputTokens = Math.max(0L, inputTotal - cacheReadTokens - cacheWriteTokens);
        } else if (hasKey(usage, "prompt_tokens")) {
            long promptTotal = longValue(usage, "prompt_tokens");
            Map<String, Object> details = nestedMap(usage, "prompt_tokens_details");
            cacheReadTokens =
                    max(
                            longValue(details, "cached_tokens"),
                            longValue(usage, "cache_read_input_tokens"),
                            longValue(usage, "cache_read_tokens"));
            cacheWriteTokens =
                    max(
                            longValue(details, "cache_write_tokens"),
                            longValue(details, "cache_creation_tokens"),
                            longValue(usage, "cache_creation_input_tokens"),
                            longValue(usage, "cache_write_tokens"));
            inputTokens = Math.max(0L, promptTotal - cacheReadTokens - cacheWriteTokens);
        } else {
            inputTokens = longValue(usage, "input_tokens");
            cacheReadTokens = firstLong(usage, "cache_read_input_tokens", "cache_read_tokens");
            cacheWriteTokens =
                    firstLong(
                            usage,
                            "cache_creation_input_tokens",
                            "cache_write_input_tokens",
                            "cache_write_tokens");
        }

        long reasoningTokens =
                max(
                        longValue(
                                nestedMap(usage, "completion_tokens_details"), "reasoning_tokens"),
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

    /**
     * 创建当前类型的构建器。
     *
     * @return 返回builder结果。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 将输入对象归一为列表视图。
     *
     * @param rawUsage 提供方返回的原始用量对象。
     * @return 返回as List结果。
     */
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

    /**
     * 将输入对象归一为映射视图。
     *
     * @param rawUsage 提供方返回的原始用量对象。
     * @return 返回as Map结果。
     */
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

    /**
     * 从嵌套对象中读取映射值。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回nested Map结果。
     */
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

    /**
     * 判断是否存在键。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 如果键满足条件则返回 true，否则返回 false。
     */
    private static boolean hasKey(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key);
    }

    /**
     * 按候选键顺序读取第一个长整型数值。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first Long结果。
     */
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

    /**
     * 按候选键顺序读取第一个正长整型数值。
     *
     * @param map 待读取的映射对象。
     * @param keys 候选键列表。
     * @return 返回first Positive Long结果。
     */
    private static long firstPositiveLong(Map<String, Object> map, String... keys) {
        return firstLong(map, keys);
    }

    /**
     * 将输入对象转换为长整型数值。
     *
     * @param map 待读取的映射对象。
     * @param key 配置键或映射键。
     * @return 返回long Value结果。
     */
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

    /**
     * 返回多个候选值中的最大值。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回max结果。
     */
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

    /**
     * 执行原始用量 JSON相关逻辑。
     *
     * @param rawUsage 提供方返回的原始用量对象。
     * @return 返回原始用量 JSON结果。
     */
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

    /**
     * 执行规范化相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回规范化结果。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 判断文本是否包含非空白内容。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回not Blank结果。
     */
    private static boolean notBlank(String value) {
        return value != null && value.trim().length() > 0;
    }

    /** LlmUsage 构建器。 */
    public static class Builder {
        /** 记录构建器中的提示词 token。 */
        private long promptTokens;

        /** 记录构建器中的输出 token。 */
        private long completionTokens;

        /** 记录构建器中的缓存读取 token。 */
        private long cacheReadTokens;

        /** 记录构建器中的缓存写入 token。 */
        private long cacheWriteTokens;

        /** 记录构建器中的推理 token。 */
        private long reasoningTokens;

        /** 记录构建器中的请求次数。 */
        private long requestCount;

        /** 记录构建器中的延迟毫秒数。 */
        private long latencyMs;

        /** 记录构建器中的原始用量 JSON。 */
        private String rawUsageJson;

        /**
         * 执行提示词 token相关逻辑。
         *
         * @param promptTokens 提示词 token 数。
         * @return 返回提示词 token结果。
         */
        public Builder promptTokens(long promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        /**
         * 执行输入 token相关逻辑。
         *
         * @param inputTokens 输入 token 数。
         * @return 返回输入 token结果。
         */
        public Builder inputTokens(long inputTokens) {
            this.promptTokens = inputTokens;
            return this;
        }

        /**
         * 执行输出 token相关逻辑。
         *
         * @param completionTokens 输出 token 数。
         * @return 返回输出 token结果。
         */
        public Builder completionTokens(long completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        /**
         * 执行输出 token相关逻辑。
         *
         * @param outputTokens 输出 token 数。
         * @return 返回输出 token结果。
         */
        public Builder outputTokens(long outputTokens) {
            this.completionTokens = outputTokens;
            return this;
        }

        /**
         * 执行缓存读取 token相关逻辑。
         *
         * @param cacheReadTokens 缓存读取 token 数。
         * @return 返回缓存读取 token结果。
         */
        public Builder cacheReadTokens(long cacheReadTokens) {
            this.cacheReadTokens = cacheReadTokens;
            return this;
        }

        /**
         * 执行缓存写入 token相关逻辑。
         *
         * @param cacheWriteTokens 缓存写入 token 数。
         * @return 返回缓存写入 token结果。
         */
        public Builder cacheWriteTokens(long cacheWriteTokens) {
            this.cacheWriteTokens = cacheWriteTokens;
            return this;
        }

        /**
         * 执行推理 token相关逻辑。
         *
         * @param reasoningTokens 推理 token 数。
         * @return 返回推理 token结果。
         */
        public Builder reasoningTokens(long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        /**
         * 执行请求次数相关逻辑。
         *
         * @param requestCount 请求Count请求载荷。
         * @return 返回请求次数结果。
         */
        public Builder requestCount(long requestCount) {
            this.requestCount = requestCount;
            return this;
        }

        /**
         * 执行延迟毫秒数相关逻辑。
         *
         * @param latencyMs 调用延迟毫秒数。
         * @return 返回延迟毫秒数结果。
         */
        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        /**
         * 执行原始用量 JSON相关逻辑。
         *
         * @param rawUsageJson 原始用量 JSON。
         * @return 返回原始用量 JSON结果。
         */
        public Builder rawUsageJson(String rawUsageJson) {
            this.rawUsageJson = rawUsageJson;
            return this;
        }

        /**
         * 构建当前对象并返回不可变结果。
         *
         * @return 返回build结果。
         */
        public LlmUsage build() {
            return new LlmUsage(this);
        }
    }
}
