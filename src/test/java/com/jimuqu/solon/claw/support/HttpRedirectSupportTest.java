package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证 HTTP 重定向状态与 Location 解析的共享规则。 */
class HttpRedirectSupportTest {
    /** 标准重定向状态应统一识别，避免各调用点维护重复判断。 */
    @Test
    void shouldRecognizeRedirectStatuses() {
        assertTrue(HttpRedirectSupport.isRedirectStatus(301));
        assertTrue(HttpRedirectSupport.isRedirectStatus(302));
        assertTrue(HttpRedirectSupport.isRedirectStatus(303));
        assertTrue(HttpRedirectSupport.isRedirectStatus(307));
        assertTrue(HttpRedirectSupport.isRedirectStatus(308));
        assertFalse(HttpRedirectSupport.isRedirectStatus(300));
        assertFalse(HttpRedirectSupport.isRedirectStatus(304));
        assertFalse(HttpRedirectSupport.isRedirectStatus(400));
    }

    /** Location 可为绝对地址或相对路径，并按当前请求地址解析。 */
    @Test
    void shouldResolveLocationAgainstCurrentUrl() {
        assertEquals(
                "https://example.com/next",
                HttpRedirectSupport.resolveLocation(
                        "https://example.com/api/list", " /next ", "测试重定向"));
        assertEquals(
                "https://cdn.example.com/file",
                HttpRedirectSupport.resolveLocation(
                        "https://example.com/api/list", "https://cdn.example.com/file", "测试重定向"));
    }

    /** 非法 Location 应返回带业务上下文且已脱敏的异常。 */
    @Test
    void shouldRejectInvalidLocationWithContext() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HttpRedirectSupport.resolveLocation(
                                        "https://example.com/api",
                                        "http://[bad-host]/?token=secret",
                                        "测试重定向 URL 无效"));
        assertTrue(error.getMessage().startsWith("测试重定向 URL 无效"));
        assertTrue(error.getMessage().contains("***"));
    }
}
