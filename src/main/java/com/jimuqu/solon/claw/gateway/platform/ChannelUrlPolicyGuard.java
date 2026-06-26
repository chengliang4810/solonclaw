package com.jimuqu.solon.claw.gateway.platform;

import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;

/** 渠道适配器共用 URL 安全策略校验，保持各平台出站请求的阻断消息一致。 */
public final class ChannelUrlPolicyGuard {
    /** 工具类不需要实例化。 */
    private ChannelUrlPolicyGuard() {
    }

    /**
     * 按安全策略校验渠道出站 URL。
     *
     * @param securityPolicyService 安全策略服务，未配置时跳过校验。
     * @param url 待校验的 URL。
     * @param purpose 当前请求用途，用于错误消息。
     */
    public static void assertSafeUrl(
            SecurityPolicyService securityPolicyService, String url, String purpose) {
        if (securityPolicyService == null) {
            return;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkUrl(url);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    purpose
                            + " blocked: "
                            + SecretRedactor.maskUrl(url)
                            + "，"
                            + verdict.getMessage());
        }
    }
}
