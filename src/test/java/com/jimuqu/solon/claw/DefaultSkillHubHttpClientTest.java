package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DefaultSkillHubHttpClientTest {
    @Test
    void shouldBlockPrivateSkillHubUrlsBeforeNetworkAccess() {
        DefaultSkillHubHttpClient client =
                new DefaultSkillHubHttpClient(
                        new FixedDnsSecurityPolicyService(new AppConfig(), "127.0.0.1"));

        assertThatThrownBy(
                        () ->
                                client.getText(
                                        "https://skills.example/.well-known/skills/index.json",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skills Hub HTTP URL blocked")
                .hasMessageContaining("内网");
    }

    @Test
    void shouldBlockUnsafeOsvEndpointBeforePostingJson() {
        DefaultSkillHubHttpClient client =
                new DefaultSkillHubHttpClient(
                        new FixedDnsSecurityPolicyService(new AppConfig(), "169.254.169.254"));

        assertThatThrownBy(() -> client.postJson("https://api.osv.dev/v1/query", null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skills Hub HTTP URL blocked")
                .hasMessageContaining("169.254.169.254");
    }

    @Test
    void shouldBlockUnsafeGetRedirectTargetBeforeFollowingIt() throws Exception {
        HttpServer server = redirectServer();
        try {
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/index";
            DefaultSkillHubHttpClient client =
                    new DefaultSkillHubHttpClient(
                            new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig()));

            assertThatThrownBy(() -> client.getText(url, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Skills Hub HTTP URL blocked")
                    .hasMessageContaining("169.254.169.254")
                    .hasMessageContaining("token=***");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldBlockUnsafePostRedirectTargetBeforeFollowingIt() throws Exception {
        HttpServer server = redirectServer();
        try {
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/index";
            DefaultSkillHubHttpClient client =
                    new DefaultSkillHubHttpClient(
                            new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig()));

            assertThatThrownBy(() -> client.postJson(url, null, "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Skills Hub HTTP URL blocked")
                    .hasMessageContaining("169.254.169.254")
                    .hasMessageContaining("token=***");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotForwardAuthorizationAcrossSkillHubRedirectOrigins() throws Exception {
        CaptureServer target = captureServer();
        HttpServer redirect = null;
        try {
            target.server.start();
            redirect = redirectServer(target.url("/target"));
            redirect.start();
            String url = "http://127.0.0.1:" + redirect.getAddress().getPort() + "/index";
            DefaultSkillHubHttpClient client =
                    new DefaultSkillHubHttpClient(
                            new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig()));
            Map<String, String> headers =
                    Collections.singletonMap("Authorization", "Bearer secret-token");

            String body = client.getText(url, headers);

            assertThat(body).isEqualTo("ok");
            assertThat(target.authorization).isNull();
        } finally {
            if (redirect != null) {
                redirect.stop(0);
            }
            target.server.stop(0);
        }
    }

    @Test
    void shouldRedactFailedGetTextUrl() throws Exception {
        HttpServer server = failedServer();
        try {
            server.start();
            String url =
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/index?token=ghp_skillhubtext12345&api_key=sk-skillhub-text";
            DefaultSkillHubHttpClient client = new DefaultSkillHubHttpClient();

            assertThatThrownBy(() -> client.getText(url, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageContaining("api_key=***")
                    .hasMessageNotContaining("ghp_skillhubtext12345")
                    .hasMessageNotContaining("sk-skillhub-text");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactFailedGetBytesUrl() throws Exception {
        HttpServer server = failedServer();
        try {
            server.start();
            String url =
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/archive.zip?token=ghp_skillhubbytes12345&api_key=sk-skillhub-bytes";
            DefaultSkillHubHttpClient client = new DefaultSkillHubHttpClient();

            assertThatThrownBy(() -> client.getBytes(url, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageContaining("api_key=***")
                    .hasMessageNotContaining("ghp_skillhubbytes12345")
                    .hasMessageNotContaining("sk-skillhub-bytes");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactFailedPostJsonUrl() throws Exception {
        HttpServer server = failedServer();
        try {
            server.start();
            String url =
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/query?token=ghp_skillhubpost12345&api_key=sk-skillhub-post";
            DefaultSkillHubHttpClient client = new DefaultSkillHubHttpClient();

            assertThatThrownBy(() -> client.postJson(url, null, "{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageContaining("api_key=***")
                    .hasMessageNotContaining("ghp_skillhubpost12345")
                    .hasMessageNotContaining("sk-skillhub-post");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer redirectServer() throws Exception {
        return redirectServer("http://169.254.169.254/latest/meta-data/?token=secret");
    }

    private static HttpServer redirectServer(String location) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/index",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        return server;
    }

    private static HttpServer failedServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] body = "failed".getBytes("UTF-8");
                    exchange.sendResponseHeaders(500, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        return server;
    }

    private static CaptureServer captureServer() throws Exception {
        CaptureServer capture = new CaptureServer();
        capture.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        capture.server.createContext(
                "/target",
                exchange -> {
                    capture.authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body = "ok".getBytes("UTF-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        return capture;
    }

    private static class CaptureServer {
        private HttpServer server;
        private volatile String authorization;

        private String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
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
        public UrlVerdict checkUrl(String url) {
            if (url != null && url.contains("127.0.0.1")) {
                return UrlVerdict.allow();
            }
            return super.checkUrl(url);
        }

        @Override
        public UrlVerdict checkUrlBlockingPrivate(String url) {
            if (url != null && url.contains("127.0.0.1")) {
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
