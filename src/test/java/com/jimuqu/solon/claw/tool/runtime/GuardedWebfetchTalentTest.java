package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;

/** 验证受控网页抓取的重定向、响应体边界和格式输出契约。 */
class GuardedWebfetchTalentTest {
    /** 302 目标在访问前必须通过逐跳 URL 策略，不能由 HTTP 客户端自动访问。 */
    @Test
    void shouldBlockRedirectTargetBeforeSecretServerReceivesRequest() throws Exception {
        HttpServer secretServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger secretRequests = new AtomicInteger();
        try {
            secretServer.createContext(
                    "/secret",
                    exchange -> {
                        secretRequests.incrementAndGet();
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                    });
            secretServer.start();
            String secretUrl =
                    "http://localhost:" + secretServer.getAddress().getPort() + "/secret";
            redirectServer.createContext(
                    "/entry",
                    exchange -> {
                        exchange.getResponseHeaders().set("Location", secretUrl);
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            redirectServer.start();
            String entryUrl =
                    "http://127.0.0.1:" + redirectServer.getAddress().getPort() + "/entry";
            SolonClawWebTools.SafeWebfetchTool tool =
                    new SolonClawWebTools.SafeWebfetchTool(new RedirectBlockingPolicy());

            assertThatThrownBy(() -> tool.webfetch(entryUrl, "text", Integer.valueOf(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Attachment download URL blocked");

            assertThat(secretRequests.get()).isZero();
        } finally {
            redirectServer.stop(0);
            secretServer.stop(0);
        }
    }

    /** 未提供 Content-Length 的分块响应超过 5MB 时，读取必须在响应流中止而不是完整缓冲。 */
    @Test
    void shouldRejectChunkedResponseBeyondFiveMegabytes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/chunked",
                    exchange -> {
                        exchange.getResponseHeaders().set("Content-Type", "text/plain");
                        exchange.sendResponseHeaders(200, 0);
                        byte[] chunk = new byte[8192];
                        java.util.Arrays.fill(chunk, (byte) 'x');
                        try {
                            for (int written = 0;
                                    written <= 5 * 1024 * 1024;
                                    written += chunk.length) {
                                exchange.getResponseBody().write(chunk);
                                exchange.getResponseBody().flush();
                            }
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chunked";
            SolonClawWebTools.SafeWebfetchTool tool =
                    new SolonClawWebTools.SafeWebfetchTool(
                            new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig()));

            assertThatThrownBy(() -> tool.webfetch(url, "text", Integer.valueOf(3)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Download exceeds max size");
        } finally {
            server.stop(0);
        }
    }

    /** 正常 HTML 与图片响应必须继续提供 markdown、text、html 和 data URL 格式。 */
    @Test
    void shouldPreserveWebfetchFormatContractForBoundedResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/page",
                    exchange -> {
                        byte[] body =
                                "<html><body><h1>Heading</h1><p>Hello</p><script>bad()</script></body></html>"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders()
                                .set("Content-Type", "text/html; charset=UTF-8");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.createContext(
                    "/image",
                    exchange -> {
                        byte[] body = new byte[] {1, 2, 3};
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            SolonClawWebTools.SafeWebfetchTool tool =
                    new SolonClawWebTools.SafeWebfetchTool(
                            new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig()));

            Document markdown = tool.webfetch(baseUrl + "/page", "markdown", Integer.valueOf(1));
            Document text = tool.webfetch(baseUrl + "/page", "text", Integer.valueOf(1));
            Document html = tool.webfetch(baseUrl + "/page", "html", Integer.valueOf(1));
            Document image = tool.webfetch(baseUrl + "/image", "markdown", Integer.valueOf(1));

            assertThat(markdown.getContent()).contains("Heading", "Hello");
            assertThat(text.getContent()).isEqualTo("Heading Hello");
            assertThat(html.getContent()).contains("<h1>Heading</h1>");
            assertThat(image.getContent()).isEqualTo("data:image/png;base64,AQID");
        } finally {
            server.stop(0);
        }
    }

    /** 只允许入口测试服务器，并把 localhost 重定向目标模拟为内网敏感地址。 */
    private static class RedirectBlockingPolicy extends SecurityPolicyService {
        /** 创建用于验证重定向校验顺序的策略实例。 */
        private RedirectBlockingPolicy() {
            super(new AppConfig());
        }

        /**
         * 在工具预检阶段允许入口、拒绝敏感跳转目标。
         *
         * @param url 待检查 URL。
         * @param allowPrivateOverride 私网访问覆盖参数。
         * @return 当前 URL 的测试安全判定。
         */
        @Override
        public UrlVerdict checkUrlSafety(String url, Boolean allowPrivateOverride) {
            return redirectVerdict(url);
        }

        /**
         * 在下载器每跳访问前复用相同的入口与敏感目标规则。
         *
         * @param url 待检查 URL。
         * @return 当前 URL 的测试安全判定。
         */
        @Override
        public UrlVerdict checkUrlBlockingPrivate(String url) {
            return redirectVerdict(url);
        }

        /**
         * 解析测试 URL 主机名，入口使用 127.0.0.1，跳转目标使用 localhost。
         *
         * @param url 待判断 URL。
         * @return 当前 URL 的测试安全判定。
         */
        private UrlVerdict redirectVerdict(String url) {
            String host = URI.create(url).getHost();
            if ("127.0.0.1".equals(host)) {
                return UrlVerdict.allow();
            }
            if ("localhost".equals(host)) {
                return UrlVerdict.block(url, "test blocked redirect target");
            }
            return UrlVerdict.block(url, "unexpected test URL");
        }
    }
}
