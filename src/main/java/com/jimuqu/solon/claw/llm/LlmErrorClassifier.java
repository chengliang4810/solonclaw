package com.jimuqu.solon.claw.llm;

import cn.hutool.core.util.StrUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.Getter;

/** 承载大模型错误Classifier相关状态和辅助逻辑。 */
public final class LlmErrorClassifier {
    /** BILLING正则S的统一常量值。 */
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

    /** 频率限制正则S的统一常量值。 */
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

    /** 用量限制正则S的统一常量值。 */
    private static final List<String> USAGE_LIMIT_PATTERNS =
            Arrays.asList("usage limit", "quota", "limit exceeded", "key limit exceeded");

    /** 用量限制TRANSIENTSIGNALS的统一常量值。 */
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

    /** 上下文OVERFLOW正则S的统一常量值。 */
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

    /** 载荷TOOLARGE正则S的统一常量值。 */
    private static final List<String> PAYLOAD_TOO_LARGE_PATTERNS =
            Arrays.asList("request entity too large", "payload too large", "error code: 413");

    /** 模型NOTFOUND正则S的统一常量值。 */
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

    /** 认证正则S的统一常量值。 */
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

    /** TRANSPORT正则S的统一常量值。 */
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

    /** 创建大模型Error Classifier实例。 */
    private LlmErrorClassifier() {}

    /**
     * 执行classify相关逻辑。
     *
     * @param error 错误参数。
     * @return 返回classify结果。
     */
    public static ClassifiedError classify(Throwable error) {
        String message = collectErrorText(error).toLowerCase(Locale.ROOT);
        int status = extractStatusCode(message);

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
     * 执行结果相关逻辑。
     *
     * @param reason 原因参数。
     * @param statusCode 状态Code参数。
     * @param retryable retryable 参数。
     * @param fallback 兜底参数。
     * @param compress compress 参数。
     * @param message 平台消息或错误消息。
     * @return 返回结果。
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
     * 判断是否包含Any。
     *
     * @param message 平台消息或错误消息。
     * @param patterns patterns 参数。
     * @return 返回contains Any结果。
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
     * 收集Error Text。
     *
     * @param error 错误参数。
     * @return 返回Error Text结果。
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
     * 提取状态Code。
     *
     * @param message 平台消息或错误消息。
     * @return 返回状态Code结果。
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

    /** 枚举故障切换Reason的可选值，保证状态表达在各模块间一致。 */
    public enum FailoverReason {
        /** 表示认证枚举值。 */
        AUTH,
        /** 表示BILLING枚举值。 */
        BILLING,
        /** 表示频率限制枚举值。 */
        RATE_LIMIT,
        /** 表示OVERLOADED枚举值。 */
        OVERLOADED,
        /** 表示SERVER ERROR枚举值。 */
        SERVER_ERROR,
        /** 表示TIMEOUT枚举值。 */
        TIMEOUT,
        /** 表示上下文OVERFLOW枚举值。 */
        CONTEXT_OVERFLOW,
        /** 表示PAYLOAD TOO LARGE枚举值。 */
        PAYLOAD_TOO_LARGE,
        /** 表示模型NOT FOUND枚举值。 */
        MODEL_NOT_FOUND,
        /** 表示UNKNOWN枚举值。 */
        UNKNOWN
    }

    /** 承载Classified错误相关状态和辅助逻辑。 */
    @Getter
    public static class ClassifiedError {
        /** 记录Classified错误中的原因。 */
        private final FailoverReason reason;

        /** 记录Classified错误中的状态Code。 */
        private final int statusCode;

        /** 是否启用retryable。 */
        private final boolean retryable;

        /** 标记是否需要兜底。 */
        private final boolean shouldFallback;

        /** 标记是否需要压缩。 */
        private final boolean shouldCompress;

        /** 记录Classified错误中的消息。 */
        private final String message;

        /**
         * 创建Classified Error实例，并注入运行所需依赖。
         *
         * @param reason 原因参数。
         * @param statusCode 状态Code参数。
         * @param retryable retryable 参数。
         * @param shouldFallback should兜底参数。
         * @param shouldCompress shouldCompress 参数。
         * @param message 平台消息或错误消息。
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
