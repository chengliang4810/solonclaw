package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证扫码注册 URL 安全策略校验的允许与阻断文案。 */
class QrSetupUrlPolicySupportTest {
    /** 允许的公网 HTTPS URL 应直接通过。 */
    @Test
    void shouldAllowSafeUrl() {
        SecurityPolicyService policy =
                new SecurityPolicyService(new AppConfig()) {
                    @Override
                    public UrlVerdict checkUrl(String url) {
                        return UrlVerdict.allow();
                    }
                };
        assertDoesNotThrow(
                () ->
                        QrSetupUrlPolicySupport.assertSafeUrl(
                                policy, "https://example.com/qr", "扫码注册请求地址"));
    }

    /** 被阻断时应保留中文场景文案和脱敏 URL。 */
    @Test
    void shouldUseQrSetupBlockedMessage() {
        SecurityPolicyService policy =
                new SecurityPolicyService(new AppConfig()) {
                    @Override
                    public UrlVerdict checkUrl(String url) {
                        return UrlVerdict.block(url, "blocked-by-test");
                    }
                };
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                QrSetupUrlPolicySupport.assertSafeUrl(
                                        policy, "file:///etc/passwd", "扫码注册请求地址"));
        assertTrue(error.getMessage().contains("扫码注册请求地址 被安全策略阻断："));
        assertTrue(error.getMessage().contains("file:///etc/passwd"));
    }
}
