package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.support.DefaultSkillHubHttpClient;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    private static HttpServer redirectServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/index",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://169.254.169.254/latest/meta-data/?token=secret");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        return server;
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
