package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.llm.LlmErrorClassifier;
import org.junit.jupiter.api.Test;

public class LlmErrorClassifierTest {
    @Test
    void shouldClassifyBillingBalanceErrorsAsFallbackOnly() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException(
                                "HTTP 402 insufficient balance, please top up your credits"));

        assertThat(result.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.BILLING);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.isShouldFallback()).isTrue();
    }

    @Test
    void shouldTreatTransientUsageLimitAsRetryableRateLimit() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException(
                                "status=402 usage limit reached, resets at next window, please retry"));

        assertThat(result.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.RATE_LIMIT);
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.isShouldFallback()).isTrue();
    }

    @Test
    void shouldRequestCompressionForContextOverflow() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalArgumentException(
                                "prompt exceeds max length: context window exceeded"));

        assertThat(result.getReason())
                .isEqualTo(LlmErrorClassifier.FailoverReason.CONTEXT_OVERFLOW);
        assertThat(result.isShouldCompress()).isTrue();
    }

    @Test
    void shouldNotRetryDeterministicBadRequestErrors() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalArgumentException(
                                "HTTP 400 invalid_request: unsupported parameter 'reasoning'"));

        assertThat(result.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.UNKNOWN);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.isShouldFallback()).isTrue();
    }

    @Test
    void shouldOnlyRetryUnknownErrorsWhenTransientPatternMatches() {
        LlmErrorClassifier.ClassifiedError unknown =
                LlmErrorClassifier.classify(new IllegalStateException("provider rejected request"));
        LlmErrorClassifier.ClassifiedError transientError =
                LlmErrorClassifier.classify(new IllegalStateException("connection reset by peer"));

        assertThat(unknown.isRetryable()).isFalse();
        assertThat(transientError.isRetryable()).isTrue();
    }

    /** 证书校验失败不能重试或切换提供方。 */
    @Test
    void shouldFailFastForCertificateVerificationFailure() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException("certificate_verify_failed"));

        assertThat(result.getReason())
                .isEqualTo(LlmErrorClassifier.FailoverReason.CERTIFICATE_VERIFICATION);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.isShouldFallback()).isFalse();
    }

    /** 提供方账户数据策略阻断不能重试或切换提供方。 */
    @Test
    void shouldFailFastForProviderDataPolicyBlock() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException(
                                "HTTP 404 no endpoints available matching your data policy"));

        assertThat(result.getReason())
                .isEqualTo(LlmErrorClassifier.FailoverReason.PROVIDER_POLICY_BLOCKED);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.isShouldFallback()).isFalse();
    }

    /** 内容过滤对未修改提示词没有重试价值，但允许尝试备用模型。 */
    @Test
    void shouldFallbackWithoutRetryForContentFilter() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException(
                                "HTTP 400 finish_reason=content_filter"));

        assertThat(result.getReason())
                .isEqualTo(LlmErrorClassifier.FailoverReason.CONTENT_POLICY_BLOCKED);
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.isShouldFallback()).isTrue();
    }

    /** 限流应直接切换备用模型，超时只允许当前提供方额外重试一次。 */
    @Test
    void shouldChooseTimelyProviderRecovery() {
        LlmErrorClassifier.ClassifiedError rateLimit =
                LlmErrorClassifier.classify(new IllegalStateException("HTTP 429 rate limit"));
        LlmErrorClassifier.ClassifiedError timeout =
                LlmErrorClassifier.classify(new IllegalStateException("connection timed out"));

        assertThat(rateLimit.isImmediateFallback()).isTrue();
        assertThat(rateLimit.shouldRetrySameProvider(1, 4)).isFalse();
        assertThat(timeout.shouldRetrySameProvider(1, 4)).isTrue();
        assertThat(timeout.shouldRetrySameProvider(2, 4)).isFalse();
    }

    /** HTTP 429 过载信号优先于通用限流，并保留当前提供方重试。 */
    @Test
    void shouldRetrySameProviderForOverloaded429() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException("HTTP 429 overloaded"));

        assertThat(result.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.OVERLOADED);
        assertThat(result.isImmediateFallback()).isFalse();
        assertThat(result.shouldRetrySameProvider(1, 4)).isTrue();
    }

    /** 兼容服务的状态码可能嵌在异常正文且尾部仍有包装字符，不能被归为未知错误。 */
    @Test
    void shouldClassifyEmbeddedProxyServiceStatusCodes() {
        LlmErrorClassifier.ClassifiedError gateway =
                LlmErrorClassifier.classify(
                        new IllegalStateException("Error code:502, message:error code: 502}"));
        LlmErrorClassifier.ClassifiedError unavailable =
                LlmErrorClassifier.classify(
                        new IllegalStateException("status_code: 503, Service Unavailable}"));
        LlmErrorClassifier.ClassifiedError timeout =
                LlmErrorClassifier.classify(
                        new IllegalStateException("HTTP/1.1 504 Gateway Timeout}"));

        assertThat(gateway.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.SERVER_ERROR);
        assertThat(gateway.getStatusCode()).isEqualTo(502);
        assertThat(gateway.isRetryable()).isTrue();
        assertThat(unavailable.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.OVERLOADED);
        assertThat(unavailable.getStatusCode()).isEqualTo(503);
        assertThat(unavailable.isRetryable()).isTrue();
        assertThat(timeout.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.SERVER_ERROR);
        assertThat(timeout.getStatusCode()).isEqualTo(504);
        assertThat(timeout.isRetryable()).isTrue();
    }

    /** 冒号形式的状态码提取不能改变认证错误的 fail-fast 决策。 */
    @Test
    void shouldKeepEmbeddedAuthenticationFailureNonRetryable() {
        LlmErrorClassifier.ClassifiedError result =
                LlmErrorClassifier.classify(
                        new IllegalStateException("Error code:401, message:unauthorized}"));

        assertThat(result.getReason()).isEqualTo(LlmErrorClassifier.FailoverReason.AUTH);
        assertThat(result.isRetryable()).isFalse();
    }
}
