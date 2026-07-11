package com.jimuqu.solon.claw.support;

import java.net.URI;

/** HTTP 重定向工具，统一状态码判断与 Location 解析规则。 */
public final class HttpRedirectSupport {
    /** 工具类不需要实例化。 */
    private HttpRedirectSupport() {}

    /**
     * 判断 HTTP 状态码是否需要由调用方手动跟随重定向。
     *
     * @param status HTTP 状态码。
     * @return 标准重定向状态返回 true。
     */
    public static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 将 Location 头按当前请求地址解析为下一跳 URL。
     *
     * @param currentUrl 当前请求 URL。
     * @param location Location 响应头。
     * @param invalidMessage Location 无法解析时的业务上下文消息。
     * @return 解析后的下一跳 URL。
     */
    public static String resolveLocation(
            String currentUrl, String location, String invalidMessage) {
        try {
            return URI.create(currentUrl).resolve(location.trim()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    invalidMessage + ": " + SecretRedactor.maskUrl(location), e);
        }
    }
}
