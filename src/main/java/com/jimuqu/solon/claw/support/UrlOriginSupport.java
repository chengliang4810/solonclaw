package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.net.URI;

/** 提供 URL 同源判断，供重定向后的凭据和请求头转发决策复用。 */
public final class UrlOriginSupport {
    /** 工具类不允许创建实例。 */
    private UrlOriginSupport() {}

    /**
     * 判断两个 URL 是否具备相同 scheme、host 和有效端口。
     *
     * @param left 左侧 URL。
     * @param right 右侧 URL。
     * @return 同源时返回 true，任一 URL 无法解析时返回 false。
     */
    public static boolean sameOrigin(String left, String right) {
        try {
            URI a = URI.create(left);
            URI b = URI.create(right);
            return StrUtil.equalsIgnoreCase(a.getScheme(), b.getScheme())
                    && StrUtil.equalsIgnoreCase(a.getHost(), b.getHost())
                    && effectivePort(a) == effectivePort(b);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取 URL 的显式端口；缺省端口按 HTTP/HTTPS 协议补齐。
     *
     * @param uri 待判断的 URL。
     * @return 有效端口；未知协议且没有显式端口时返回 -1。
     */
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }
}
