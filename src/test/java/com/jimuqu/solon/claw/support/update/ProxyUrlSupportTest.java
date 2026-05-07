package com.jimuqu.solon.claw.support.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ProxyUrlSupportTest {
    @Test
    void shouldParseHttpProxy() {
        Proxy proxy = ProxyUrlSupport.parseProxy("http://127.0.0.1:6153");

        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(6153);
    }

    @Test
    void shouldParseSocksAliasAsSocksProxy() {
        Proxy proxy = ProxyUrlSupport.parseProxy("socks://127.0.0.1:1080/");

        assertThat(proxy.type()).isEqualTo(Proxy.Type.SOCKS);
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(1080);
    }

    @Test
    void shouldRejectMalformedProxyPort() {
        assertThatThrownBy(() -> ProxyUrlSupport.parseProxy("http://127.0.0.1:6153export"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("代理地址");
    }

    @Test
    void shouldRejectUnsupportedProxyScheme() {
        assertThatThrownBy(() -> ProxyUrlSupport.parseProxy("ftp://proxy.example:21"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    void shouldValidateProxyEnvironmentValuesLikeHermes() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("HTTP_PROXY", "http://127.0.0.1:6153");
        env.put("HTTPS_PROXY", "https://proxy.example.com:8443");
        env.put("ALL_PROXY", "socks5://127.0.0.1:1080");
        env.put("IGNORED_PROXY", "http://127.0.0.1:1");

        Map<String, String> normalized = ProxyUrlSupport.validateProxyEnvironment(env);

        assertThat(normalized).containsEntry("HTTP_PROXY", "http://127.0.0.1:6153");
        assertThat(normalized).containsEntry("HTTPS_PROXY", "https://proxy.example.com:8443");
        assertThat(normalized).containsEntry("ALL_PROXY", "socks5://127.0.0.1:1080");
        assertThat(normalized).doesNotContainKey("IGNORED_PROXY");
    }

    @Test
    void shouldIgnoreBlankProxyEnvironmentValuesLikeHermes() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("HTTP_PROXY", " ");
        env.put("https_proxy", "");

        Map<String, String> normalized = ProxyUrlSupport.validateProxyEnvironment(env);

        assertThat(normalized).isEmpty();
    }

    @Test
    void shouldNormalizeSocksAliasInProxyEnvironmentLikeHermes() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ALL_PROXY", "socks://127.0.0.1:1080/");

        Map<String, String> normalized = ProxyUrlSupport.validateProxyEnvironment(env);

        assertThat(normalized).containsEntry("ALL_PROXY", "socks5://127.0.0.1:1080/");
    }

    @Test
    void shouldRejectMalformedProxyEnvironmentPortLikeHermes() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("http_proxy", "http://127.0.0.1:6153export");

        assertThatThrownBy(() -> ProxyUrlSupport.validateProxyEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed proxy environment variable http_proxy=")
                .hasMessageContaining("6153export");
    }
}
