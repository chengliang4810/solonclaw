package com.jimuqu.solon.claw.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证错误文本摘要的异常类型保留和敏感信息脱敏。 */
class ErrorTextSupportTest {
    /** 摘要应保留异常类型，便于 debug 日志定位。 */
    @Test
    void shouldIncludeExceptionType() {
        String summary = ErrorTextSupport.summaryWithType(new IllegalArgumentException("bad value"));
        assertEquals("IllegalArgumentException: bad value", summary);
    }

    /** 摘要应脱敏明显密钥值，避免日志泄漏。 */
    @Test
    void shouldRedactSecretLikeMessage() {
        String summary =
                ErrorTextSupport.summaryWithType(
                        new IllegalStateException("token=sk-test-secret-value"));
        assertTrue(summary.startsWith("IllegalStateException: "));
        assertTrue(summary.contains("***"));
    }

    /** 仅类型摘要不能包含异常消息正文。 */
    @Test
    void shouldReturnOnlyExceptionType() {
        assertEquals(
                "IllegalStateException",
                ErrorTextSupport.typeOnly(new IllegalStateException("token=secret")));
        assertEquals("unknown", ErrorTextSupport.typeOnly(null));
    }
}
