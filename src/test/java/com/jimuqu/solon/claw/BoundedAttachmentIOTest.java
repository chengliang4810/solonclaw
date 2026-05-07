package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

public class BoundedAttachmentIOTest {
    @Test
    void shouldBlockPrivateDownloadUrlBeforeNetworkAccess() {
        SecurityPolicyService securityPolicyService =
                new FixedDnsSecurityPolicyService(new AppConfig(), "127.0.0.1");

        assertThatThrownBy(
                        () ->
                                BoundedAttachmentIO.downloadHutool(
                                        "https://cdn.example.test/file.png",
                                        1000,
                                        BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment download URL blocked")
                .hasMessageContaining("内网");
    }

    @Test
    void shouldBlockMetadataDownloadUrlBeforeNetworkAccess() {
        SecurityPolicyService securityPolicyService =
                new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254");

        assertThatThrownBy(
                        () ->
                                BoundedAttachmentIO.downloadHutool(
                                        "https://media.example.test/download?id=1",
                                        1000,
                                        BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                        securityPolicyService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment download URL blocked")
                .hasMessageContaining("169.254.169.254");
    }

    @Test
    void shouldBlockUnsafeRedirectTargetBeforeFollowingIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/media",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .add(
                                        "Location",
                                        "http://169.254.169.254/latest/meta-data/?token=secret");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/media";
            SecurityPolicyService securityPolicyService =
                    new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig());

            assertThatThrownBy(
                            () ->
                                    BoundedAttachmentIO.downloadHutool(
                                            url,
                                            1000,
                                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                            securityPolicyService))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Attachment download URL blocked")
                    .hasMessageContaining("169.254.169.254")
                    .hasMessageContaining("token=***");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldBlockUnsafeOkHttpRedirectTargetBeforeFollowingIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/media",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .add(
                                        "Location",
                                        "http://169.254.169.254/latest/meta-data/?token=secret");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/media";
            SecurityPolicyService securityPolicyService =
                    new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig());

            assertThatThrownBy(
                            () ->
                                    BoundedAttachmentIO.downloadOkHttp(
                                            new OkHttpClient(),
                                            url,
                                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                            securityPolicyService))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Attachment download URL blocked")
                    .hasMessageContaining("169.254.169.254")
                    .hasMessageContaining("token=***");
        } finally {
            server.stop(0);
        }
    }

    private static class FixedDnsSecurityPolicyService extends SecurityPolicyService {
        private final String ip;

        private FixedDnsSecurityPolicyService(AppConfig appConfig, String ip) {
            super(appConfig);
            this.ip = ip;
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }
    }

    private static class AllowLocalButBlockMetadataSecurityPolicyService
            extends SecurityPolicyService {
        private AllowLocalButBlockMetadataSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        protected InetAddress[] resolveHost(String host) throws Exception {
            if ("127.0.0.1".equals(host)) {
                return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            }
            return new InetAddress[] {InetAddress.getByName(host)};
        }
    }
}
