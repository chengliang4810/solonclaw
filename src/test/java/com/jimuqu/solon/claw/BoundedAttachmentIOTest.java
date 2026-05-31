package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

public class BoundedAttachmentIOTest {
    @Test
    void shouldExposeAttachmentDownloadPolicySummary() {
        Map<String, Object> summary = BoundedAttachmentIO.policySummary();

        assertThat(summary.get("hutoolDownloadGuarded")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("okHttpDownloadGuarded")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("initialUrlChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("redirectUrlCheckedBeforeFollow")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("crossHostHeaderForwardingBlocked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("contentLengthChecked")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("streamReadBounded")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("defaultMaxBytes"))
                .isEqualTo(Long.valueOf(BoundedAttachmentIO.DEFAULT_MAX_BYTES));
        assertThat(summary.get("maxRedirects")).isEqualTo(Integer.valueOf(5));
        assertThat(String.valueOf(summary)).doesNotContain("token");
    }

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
    void shouldReturnHutoolContentTypeAndSendHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/media",
                    exchange -> {
                        if (!"Bearer token-a".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(401, -1);
                            exchange.close();
                            return;
                        }
                        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "image/png");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/media";
            SecurityPolicyService securityPolicyService =
                    new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig());
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer token-a");

            BoundedAttachmentIO.HutoolDownloadResult result =
                    BoundedAttachmentIO.downloadHutoolResult(
                            url,
                            1000,
                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                            securityPolicyService,
                            headers);

            assertThat(result.getContentType()).isEqualTo("image/png");
            assertThat(new String(result.getData(), StandardCharsets.UTF_8)).isEqualTo("hello");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotForwardHeadersAcrossRedirectHosts() throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> redirectedAuthorization = new AtomicReference<String>();
        try {
            target.createContext(
                    "/media",
                    exchange -> {
                        redirectedAuthorization.set(
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "text/plain");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            target.start();
            String targetUrl =
                    "http://localhost:" + target.getAddress().getPort() + "/media";
            redirector.createContext(
                    "/media",
                    exchange -> {
                        exchange.getResponseHeaders().add("Location", targetUrl);
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            redirector.start();
            String url = "http://127.0.0.1:" + redirector.getAddress().getPort() + "/media";
            SecurityPolicyService securityPolicyService =
                    new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig());
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer token-a");

            BoundedAttachmentIO.HutoolDownloadResult result =
                    BoundedAttachmentIO.downloadHutoolResult(
                            url,
                            1000,
                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                            securityPolicyService,
                            headers);

            assertThat(new String(result.getData(), StandardCharsets.UTF_8)).isEqualTo("ok");
            assertThat(redirectedAuthorization.get()).isNull();
        } finally {
            redirector.stop(0);
            target.stop(0);
        }
    }

    @Test
    void shouldBlockUnsafeHutoolResultRedirectTargetBeforeFollowingIt() throws Exception {
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
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer token-a");

            assertThatThrownBy(
                            () ->
                                    BoundedAttachmentIO.downloadHutoolResult(
                                            url,
                                            1000,
                                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                            securityPolicyService,
                                            headers))
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

    @Test
    void shouldRedactHutoolFailureResponsePreview() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/media",
                    exchange -> {
                        byte[] body =
                                "{\"error\":\"token=ghp_downloadfail12345 api_key=sk-download-secret\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/media";

            assertThatThrownBy(
                            () ->
                                    BoundedAttachmentIO.downloadHutool(
                                            url, 1000, BoundedAttachmentIO.DEFAULT_MAX_BYTES))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageContaining("api_key=***")
                    .hasMessageNotContaining("ghp_downloadfail12345")
                    .hasMessageNotContaining("sk-download-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactOkHttpFailureResponsePreview() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/media",
                    exchange -> {
                        byte[] body =
                                "{\"error\":\"token=ghp_okdownloadfail12345 api_key=sk-okdownload-secret\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/media";

            assertThatThrownBy(
                            () ->
                                    BoundedAttachmentIO.downloadOkHttp(
                                            new OkHttpClient(),
                                            url,
                                            BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                            null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageContaining("api_key=***")
                    .hasMessageNotContaining("ghp_okdownloadfail12345")
                    .hasMessageNotContaining("sk-okdownload-secret");
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
            appConfig.getSecurity().setAllowPrivateUrls(true);
        }

        @Override
        public UrlVerdict checkUrlBlockingPrivate(String url) {
            if (url != null && (url.contains("127.0.0.1") || url.contains("localhost"))) {
                return UrlVerdict.allow();
            }
            return super.checkUrlBlockingPrivate(url);
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
