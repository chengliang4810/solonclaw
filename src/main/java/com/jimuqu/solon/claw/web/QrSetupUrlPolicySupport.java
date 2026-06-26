package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;

/** 复用国内扫码注册流程的 URL 安全策略阻断文案。 */
final class QrSetupUrlPolicySupport {
    /** 工具类不允许创建实例。 */
    private QrSetupUrlPolicySupport() {}

    /**
     * 校验扫码注册请求 URL 是否被安全策略允许访问。
     *
     * @param securityPolicyService URL 安全策略服务。
     * @param url 待访问 URL。
     * @param purpose 当前 URL 用途。
     */
    static void assertSafeUrl(
            SecurityPolicyService securityPolicyService, String url, String purpose) {
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    purpose
                            + " 被安全策略阻断："
                            + SecretRedactor.maskUrl(url)
                            + "，"
                            + verdict.getMessage());
        }
    }
}
