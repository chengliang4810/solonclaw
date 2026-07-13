package com.jimuqu.solon.claw.llm.dialect;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.llm.LlmErrorClassifier;
import org.junit.jupiter.api.Test;

/** 验证兼容服务原始响应在进入协议解析器前得到稳定归一化。 */
public class RawResponseLoggingChatDialectTest {
    /** 纯文本 502 必须转换成可触发备用模型的服务端错误。 */
    @Test
    void shouldNormalizePlainTextGatewayErrorBeforeJsonParsing() {
        assertThatThrownBy(
                        () ->
                                RawResponseLoggingChatDialect.rejectPlainTextUpstreamError(
                                        "error code: 502\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 502")
                .satisfies(
                        error -> {
                            LlmErrorClassifier.ClassifiedError classified =
                                    LlmErrorClassifier.classify(error);
                            org.assertj.core.api.Assertions.assertThat(classified.getReason())
                                    .isEqualTo(LlmErrorClassifier.FailoverReason.SERVER_ERROR);
                            org.assertj.core.api.Assertions.assertThat(
                                            classified.isShouldFallback())
                                    .isTrue();
                        });
    }

    /** 合法 JSON 与 SSE 仍由原协议方言继续处理。 */
    @Test
    void shouldLeaveStructuredResponsesUntouched() {
        assertThatCode(
                        () ->
                                RawResponseLoggingChatDialect.rejectPlainTextUpstreamError(
                                        "{\"error\":{\"code\":502}}"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                RawResponseLoggingChatDialect.rejectPlainTextUpstreamError(
                                        "data: {\"id\":\"chunk-1\"}"))
                .doesNotThrowAnyException();
    }
}
