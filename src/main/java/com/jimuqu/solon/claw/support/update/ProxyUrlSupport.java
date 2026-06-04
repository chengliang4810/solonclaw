package com.jimuqu.solon.claw.support.update;

import cn.hutool.core.util.StrUtil;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 版本检查代理地址解析与校验。 */
public final class ProxyUrlSupport {
    private static final String[] PROXY_ENV_KEYS =
            new String[] {
                "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"
            };

    private ProxyUrlSupport() {}

    public static Proxy parseProxy(String proxyUrl) {
        String value = StrUtil.nullToEmpty(proxyUrl).trim();
        if (value.length() == 0) {
            return null;
        }
        URI uri = parseUri(value);
        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)
                && !"https".equals(scheme)
                && !"socks".equals(scheme)
                && !"socks4".equals(scheme)
                && !"socks5".equals(scheme)) {
            throw new IllegalArgumentException("代理地址仅支持 http/https/socks/socks4/socks5");
        }
        String host = uri.getHost();
        int port;
        try {
            port = uri.getPort();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("代理地址端口格式无效", e);
        }
        if (StrUtil.isBlank(host) || port <= 0) {
            throw new IllegalArgumentException("代理地址缺少主机名或端口");
        }
        Proxy.Type type = scheme.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, new InetSocketAddress(host, port));
    }

    public static Map<String, String> validateProxyEnvironment(Map<String, String> environment) {
        Map<String, String> normalized = new LinkedHashMap<String, String>();
        if (environment == null || environment.isEmpty()) {
            return normalized;
        }
        for (String key : PROXY_ENV_KEYS) {
            if (!environment.containsKey(key)) {
                continue;
            }
            String raw = StrUtil.nullToEmpty(environment.get(key)).trim();
            if (raw.length() == 0) {
                continue;
            }
            String normalizedUrl = normalizeProxyUrl(raw);
            try {
                parseProxy(normalizedUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Malformed proxy environment variable "
                                + key
                                + "="
                                + raw
                                + ": "
                                + e.getMessage(),
                        e);
            }
            normalized.put(key, normalizedUrl);
        }
        return normalized;
    }

    public static String normalizeProxyUrl(String proxyUrl) {
        String value = StrUtil.nullToEmpty(proxyUrl).trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("socks://")) {
            return "socks5://" + value.substring("socks://".length());
        }
        return value;
    }

    private static URI parseUri(String value) {
        try {
            URI uri = new URI(value).parseServerAuthority();
            String scheme = StrUtil.nullToEmpty(uri.getScheme());
            if (scheme.length() == 0) {
                throw new IllegalArgumentException("代理地址缺少协议");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("代理地址格式无效", e);
        }
    }
}
