package com.jimuqu.solon.claw.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证 URL 同源判断对默认端口、大小写和无效输入的处理。 */
class UrlOriginSupportTest {
    /** 默认端口和大小写差异不应影响同源判断。 */
    @Test
    void shouldTreatDefaultPortAndCaseAsSameOrigin() {
        assertTrue(
                UrlOriginSupport.sameOrigin(
                        "HTTPS://Example.com/path", "https://example.COM:443/other"));
        assertTrue(UrlOriginSupport.sameOrigin("http://example.com/a", "HTTP://EXAMPLE.com:80/b"));
    }

    /** 协议、主机、端口不同或 URL 无效时不得视为同源。 */
    @Test
    void shouldRejectDifferentOrInvalidOrigin() {
        assertFalse(UrlOriginSupport.sameOrigin("https://example.com", "http://example.com"));
        assertFalse(UrlOriginSupport.sameOrigin("https://example.com", "https://example.com:444"));
        assertFalse(UrlOriginSupport.sameOrigin("not a url", "https://example.com"));
    }
}
