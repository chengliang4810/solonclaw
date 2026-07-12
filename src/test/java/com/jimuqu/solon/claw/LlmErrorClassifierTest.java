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
}
