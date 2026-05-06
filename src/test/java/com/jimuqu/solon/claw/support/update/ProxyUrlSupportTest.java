package com.jimuqu.solon.claw.support.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Proxy;
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
}
