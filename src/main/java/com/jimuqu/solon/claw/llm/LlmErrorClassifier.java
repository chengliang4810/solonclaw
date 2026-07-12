package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.Getter;

/** 将模型调用异常归类为可重试、可降级或需要压缩上下文的运行决策。 */
public final class LlmErrorClassifier {
    /** 计费或账户余额不足相关错误特征，命中后通常不应立即重试同一提供方。 */
    private static final List<String> BILLING_PATTERNS =
            Arrays.asList(
                    "insufficient credits",
                    "insufficient_quota",
                    "insufficient balance",
                    "credit balance",
                    "credits have been exhausted",
                    "top up your credits",
                    "payment required",
                    "billing hard limit",
                    "exceeded your current quota",
                    "account is deactivated",
                    "plan does not include",
                    "billing_not_active");

    /** 频率限制或并发限制相关错误特征，命中后允许切换提供方或稍后重试。 */
    private static final List<String> RATE_LIMIT_PATTERNS =
            Arrays.asList(
                    "rate limit",
                    "rate_limit",
                    "too many requests",
                    "throttled",
                    "requests per minute",
                    "tokens per minute",
                    "requests per day",
                    "try again in",
                    "please retry after",
                    "resource_exhausted",
                    "rate increased too quickly",
                    "throttlingexception",
                    "too many concurrent requests",
                    "servicequotaexceededexception");

    /** 提供方服务端过载特征；部分提供方会将该状态编码为 HTTP 429。 */
    private static final List<String> OVERLOADED_PATTERNS =
            Arrays.asList(
                    "overloaded",
                    "temporarily overloaded",
                    "service is temporarily overloaded",
                    "service may be temporarily overloaded",
                    "server is overloaded",
                    "server overloaded",
                    "service overloaded",
                    "service is overloaded",
                    "upstream overloaded",
                    "currently overloaded",
                    "at capacity",
                    "over capacity");

    /** TLS 证书链校验失败特征；同一主机重复请求无法自行恢复。 */
    private static final List<String> CERTIFICATE_VERIFICATION_PATTERNS =
            Arrays.asList(
                    "certificate verify failed",
                    "certificate_verify_failed",
                    "unable to get local issuer certificate",
                    "self-signed certificate",
                    "self signed certificate",
                    "certificate has expired",
                    "hostname mismatch, certificate is not valid",
                    "unable to verify the first certificate");

    /** 提供方账户数据策略阻断特征。 */
    private static final List<String> PROVIDER_POLICY_BLOCKED_PATTERNS =
            Arrays.asList(
                    "no endpoints available matching your guardrail",
                    "no endpoints available matching your data policy",
                    "no endpoints found matching your data policy");

    /** 模型内容安全过滤特征；同一提示词重试会得到相同拒绝。 */
    private static final List<String> CONTENT_POLICY_BLOCKED_PATTERNS =
            Arrays.asList(
                    "flagged for possible cybersecurity risk",
                    "trusted access for cyber",
                    "violates our usage policies",
                    "violates openai's usage policies",
                    "your request was flagged by",
                    "prompt was flagged by our safety",
                    "responses cannot be generated due to safety",
                    "content_filter",
                    "responsibleaipolicyviolation",
                    "new_sensitive");

    /** 用量限制通用特征，需要结合 transient 信号区分余额耗尽和周期窗口限制。 */
    private static final List<String> USAGE_LIMIT_PATTERNS =
            Arrays.asList("usage limit", "quota", "limit exceeded", "key limit exceeded");

    /** 周期性用量窗口的临时性信号，命中后按限流处理而不是按计费失败处理。 */
    private static final List<String> USAGE_LIMIT_TRANSIENT_SIGNALS =
            Arrays.asList(
                    "try again",
                    "retry",
                    "resets at",
                    "reset in",
                    "wait",
                    "requests remaining",
                    "periodic",
                    "window");

    /** 上下文窗口溢出相关错误特征，命中后触发压缩后重试。 */
    private static final List<String> CONTEXT_OVERFLOW_PATTERNS =
            Arrays.asList(
                    "context length",
                    "context size",
                    "maximum context",
                    "token limit",
                    "too many tokens",
                    "reduce the length",
                    "exceeds the limit",
                    "context window",
                    "prompt is too long",
                    "prompt exceeds max length",
                    "max_model_len",
                    "prompt length",
                    "input is too long",
                    "maximum model length",
                    "context length exceeded",
                    "上下文长度",
                    "超过最大长度",
                    "exceeds the maximum number of input tokens");

    /** 请求体过大错误特征，通常需要压缩或减少附件内容后再试。 */
    private static final List<String> PAYLOAD_TOO_LARGE_PATTERNS =
            Arrays.asList("request entity too large", "payload too large", "error code: 413");

    /** 模型不存在或当前账号不可用错误特征，命中后应切换模型或提供方。 */
    private static final List<String> MODEL_NOT_FOUND_PATTERNS =
            Arrays.asList(
                    "is not a valid model",
                    "invalid model",
                    "model not found",
                    "model_not_found",
                    "does not exist",
                    "no such model",
                    "unknown model",
                    "unsupported model",
                    "model_not_available",
                    "invalid_model");

    /** 鉴权失败错误特征，命中后不重试同一密钥。 */
    private static final List<String> AUTH_PATTERNS =
            Arrays.asList(
                    "invalid api key",
                    "invalid_api_key",
                    "authentication",
                    "unauthorized",
                    "forbidden",
                    "invalid token",
                    "token expired",
                    "token revoked",
                    "access denied");

    /** 网络或传输层错误特征，命中后允许重试或降级。 */
    private static final List<String> TRANSPORT_PATTERNS =
            Arrays.asList(
                    "timeout",
                    "timed out",
                    "connection reset",
                    "connection refused",
                    "connection aborted",
                    "broken pipe",
                    "eof",
                    "unreachable",
                    "network",
                    "remote protocol",
                    "server disconnected",
                    "ssl",
                    "tls");

    /** 工具类只提供静态分类方法，不允许实例化。 */
    private LlmErrorClassifier() {}

    /**
     * 根据异常链文本和 HTTP 状态码推导模型调用失败处置策略。
     *
     * @param error 模型调用抛出的异常。
     * @return 返回分类后的失败原因与处置标记。
     */
    public static ClassifiedError classify(Throwable error) {
        String message = collectErrorText(error).toLowerCase(Locale.ROOT);
        int status = extractStatusCode(message);

        if (containsAny(message, CONTENT_POLICY_BLOCKED_PATTERNS)) {
            return result(
                    FailoverReason.CONTENT_POLICY_BLOCKED, status, false, true, false, message);
        }
        if (containsAny(message, CERTIFICATE_VERIFICATION_PATTERNS)) {
            return result(
                    FailoverReason.CERTIFICATE_VERIFICATION, status, false, false, false, message);
        }
        if (containsAny(message, PROVIDER_POLICY_BLOCKED_PATTERNS)) {
            return result(
                    FailoverReason.PROVIDER_POLICY_BLOCKED, status, false, false, false, message);
        }
        if (status == 402) {
            if (containsAny(message, USAGE_LIMIT_PATTERNS)
                    && containsAny(message, USAGE_LIMIT_TRANSIENT_SIGNALS)) {
                return result(FailoverReason.RATE_LIMIT, status, true, true, false, message);
            }
            return result(FailoverReason.BILLING, status, false, true, false, message);
        }
        if (status == 413 || containsAny(message, PAYLOAD_TOO_LARGE_PATTERNS)) {
            return result(FailoverReason.PAYLOAD_TOO_LARGE, status, true, false, true, message);
        }
        if (status == 401 || status == 403 || containsAny(message, AUTH_PATTERNS)) {
            return result(FailoverReason.AUTH, status, false, true, false, message);
        }
        if (status == 404 || containsAny(message, MODEL_NOT_FOUND_PATTERNS)) {
            return result(FailoverReason.MODEL_NOT_FOUND, status, false, true, false, message);
        }
        if (status == 429 && containsAny(message, OVERLOADED_PATTERNS)) {
            return result(FailoverReason.OVERLOADED, status, true, true, false, message);
        }
        if (status == 429 || containsAny(message, RATE_LIMIT_PATTERNS)) {
            return result(FailoverReason.RATE_LIMIT, status, true, true, false, message);
        }
        if (containsAny(message, BILLING_PATTERNS)) {
            return result(FailoverReason.BILLING, status, false, true, false, message);
        }
        if (containsAny(message, USAGE_LIMIT_PATTERNS)) {
            if (containsAny(message, USAGE_LIMIT_TRANSIENT_SIGNALS)) {
                return result(FailoverReason.RATE_LIMIT, status, true, true, false, message);
            }
            return result(FailoverReason.BILLING, status, false, true, false, message);
        }
        if (containsAny(message, CONTEXT_OVERFLOW_PATTERNS)) {
            return result(FailoverReason.CONTEXT_OVERFLOW, status, true, false, true, message);
        }
        if (containsAny(message, OVERLOADED_PATTERNS)) {
            return result(FailoverReason.OVERLOADED, status, true, true, false, message);
        }
        if (status == 500 || status == 502) {
            return result(FailoverReason.SERVER_ERROR, status, true, true, false, message);
        }
        if (status == 503 || status == 529) {
            return result(FailoverReason.OVERLOADED, status, true, true, false, message);
        }
        if (containsAny(message, TRANSPORT_PATTERNS)) {
            return result(FailoverReason.TIMEOUT, status, true, true, false, message);
        }
        return result(FailoverReason.UNKNOWN, status, false, true, false, message);
    }

    /**
     * 构造分类结果对象，集中表达重试、降级和压缩三个运行决策。
     *
     * @param reason 失败原因。
     * @param statusCode 解析到的 HTTP 状态码，无法识别时为 0。
     * @param retryable 是否允许同一链路稍后重试。
     * @param fallback 是否允许切换模型或提供方。
     * @param compress 是否建议先压缩上下文再重试。
     * @param message 已归一化的小写错误文本。
     * @return 返回分类结果。
     */
    private static ClassifiedError result(
            FailoverReason reason,
            int statusCode,
            boolean retryable,
            boolean fallback,
            boolean compress,
            String message) {
        return new ClassifiedError(reason, statusCode, retryable, fallback, compress, message);
    }

    /**
     * 判断错误文本是否包含任一分类特征。
     *
     * @param message 已归一化的小写错误文本。
     * @param patterns 分类特征集合。
     * @return 命中任一特征时返回 true。
     */
    private static boolean containsAny(String message, List<String> patterns) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        for (String pattern : patterns) {
            if (message.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集异常链上的错误消息，避免外层包装异常吞掉提供方原始原因。
     *
     * @return 返回以分隔符串联后的错误文本。
     */
    private static String collectErrorText(Throwable error) {
        StringBuilder buffer = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (StrUtil.isNotBlank(current.getMessage())) {
                if (buffer.length() > 0) {
                    buffer.append(" | ");
                }
                buffer.append(current.getMessage());
            }
            current = current.getCause();
        }
        return buffer.toString();
    }

    /**
     * 从错误文本中提取常见 HTTP 状态码。
     *
     * @return 命中状态码时返回对应整数，否则返回 0。
     */
    private static int extractStatusCode(String message) {
        for (int code : new int[] {400, 401, 402, 403, 404, 413, 429, 500, 502, 503, 529}) {
            String value = String.valueOf(code);
            if (message.contains(" " + value + " ")
                    || message.contains("http " + value)
                    || message.contains("status=" + value)
                    || message.contains("status_code=" + value)
                    || message.contains("code=" + value)
                    || message.contains("[" + value + "]")
                    || message.contains("\"" + value + "\"")
                    || message.endsWith(value)) {
                return code;
            }
        }
        return 0;
    }

    /** 故障切换原因枚举，保证网关、运行日志和调度重试使用同一套状态表达。 */
    public enum FailoverReason {
        /** API Key、Token 或权限认证失败。 */
        AUTH,
        /** 账户余额、套餐或计费状态不满足请求要求。 */
        BILLING,
        /** 请求频率、并发数或周期用量窗口触顶。 */
        RATE_LIMIT,
        /** 提供方临时过载。 */
        OVERLOADED,
        /** 提供方 5xx 服务端错误。 */
        SERVER_ERROR,
        /** 网络超时或连接被中断。 */
        TIMEOUT,
        /** TLS 证书链或主机名校验失败。 */
        CERTIFICATE_VERIFICATION,
        /** 请求上下文超出模型窗口。 */
        CONTEXT_OVERFLOW,
        /** 请求体或附件载荷超过提供方限制。 */
        PAYLOAD_TOO_LARGE,
        /** 模型不存在、不可用或名称非法。 */
        MODEL_NOT_FOUND,
        /** 提供方账户的数据或安全策略阻断了模型路由。 */
        PROVIDER_POLICY_BLOCKED,
        /** 提供方内容安全过滤拒绝了当前提示词。 */
        CONTENT_POLICY_BLOCKED,
        /** 无法归入已知类型的错误。 */
        UNKNOWN
    }

    /** 模型失败分类结果，供运行编排器决定是否重试、降级或压缩上下文。 */
    @Getter
    public static class ClassifiedError {
        /** 失败原因。 */
        private final FailoverReason reason;

        /** 解析到的 HTTP 状态码，无法识别时为 0。 */
        private final int statusCode;

        /** 是否允许同一链路稍后重试。 */
        private final boolean retryable;

        /** 是否允许切换模型或提供方兜底。 */
        private final boolean shouldFallback;

        /** 是否建议先压缩上下文再重试。 */
        private final boolean shouldCompress;

        /** 已归一化的小写错误文本，用于运行日志和诊断。 */
        private final String message;

        /**
         * 判断是否应跳过当前提供方剩余重试并立即切换备用模型。
         *
         * @return 限流、计费或不可重试且允许降级时返回 true。
         */
        public boolean isImmediateFallback() {
            return shouldFallback
                    && (!retryable
                            || reason == FailoverReason.RATE_LIMIT
                            || reason == FailoverReason.BILLING);
        }

        /**
         * 判断当前提供方是否还应重试；传输故障最多额外重试一次，避免长时间卡在不可用提供方。
         *
         * @param attempt 当前提供方已执行次数，从 1 开始。
         * @param maxAttempts 配置允许的最大执行次数。
         * @return 仍应重试当前提供方时返回 true。
         */
        public boolean shouldRetrySameProvider(int attempt, int maxAttempts) {
            if (!retryable || attempt >= maxAttempts || isImmediateFallback()) {
                return false;
            }
            if (reason == FailoverReason.TIMEOUT || reason == FailoverReason.OVERLOADED) {
                return attempt < 2;
            }
            return true;
        }

        /**
         * 创建模型失败分类结果。
         *
         * @param reason 失败原因。
         * @param statusCode HTTP 状态码。
         * @param retryable 是否允许重试。
         * @param shouldFallback 是否允许兜底。
         * @param shouldCompress 是否建议压缩上下文。
         * @param message 已归一化的小写错误文本。
         */
        private ClassifiedError(
                FailoverReason reason,
                int statusCode,
                boolean retryable,
                boolean shouldFallback,
                boolean shouldCompress,
                String message) {
            this.reason = reason;
            this.statusCode = statusCode;
            this.retryable = retryable;
            this.shouldFallback = shouldFallback;
            this.shouldCompress = shouldCompress;
            this.message = message;
        }
    }
}
