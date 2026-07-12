package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        assertThat(summary.get("contentTypeCaptured")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("defaultMaxBytes"))
                .isEqualTo(Long.valueOf(BoundedAttachmentIO.DEFAULT_MAX_BYTES));
        assertThat(summary.get("maxRedirects")).isEqualTo(Integer.valueOf(5));
        assertThat(String.valueOf(summary)).doesNotContain("token");
    }

    void shouldReturnHutoolContentTypeAndSendHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext(
                    "/media",
                    exchange -> {
                        if (!"Bearer token-a"
                                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
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
            String targetUrl = "http://localhost:" + target.getAddress().getPort() + "/media";
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

    /** Hutool 手工跳转在连接最终响应前必须先关闭上一跳连接。 */
    @Test
    void shouldCloseHutoolRedirectResponseBeforeFollowingNextHop() throws Exception {
        assertRedirectConnectionClosedBeforeFinalResponse(
                (url, policy) ->
                        BoundedAttachmentIO.downloadHutool(
                                url, 3000, BoundedAttachmentIO.DEFAULT_MAX_BYTES, policy));
    }

    /** OkHttp 手工跳转在连接最终响应前必须先关闭上一跳连接。 */
    @Test
    void shouldCloseOkHttpRedirectResponseBeforeFollowingNextHop() throws Exception {
        assertRedirectConnectionClosedBeforeFinalResponse(
                (url, policy) ->
                        BoundedAttachmentIO.downloadOkHttp(
                                new OkHttpClient(),
                                url,
                                BoundedAttachmentIO.DEFAULT_MAX_BYTES,
                                policy));
    }

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

    /**
     * 让末跳响应等待，验证客户端在继续读取末跳前已关闭首跳 302 连接。
     *
     * @param action 受测下载实现。
     */
    private void assertRedirectConnectionClosedBeforeFinalResponse(DownloadAction action)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch finalRequestReceived = new CountDownLatch(1);
        CountDownLatch redirectConnectionClosed = new CountDownLatch(1);
        CountDownLatch allowFinalResponse = new CountDownLatch(1);
        try (ServerSocket redirectServer = new ServerSocket(0);
                ServerSocket finalServer = new ServerSocket(0)) {
            Future<?> redirectTask =
                    executor.submit(
                            () -> {
                                try (Socket socket = redirectServer.accept()) {
                                    readHttpRequest(socket.getInputStream());
                                    String location =
                                            "http://127.0.0.1:"
                                                    + finalServer.getLocalPort()
                                                    + "/final";
                                    OutputStream output = socket.getOutputStream();
                                    output.write(
                                            ("HTTP/1.1 302 Found\r\n"
                                                            + "Location: "
                                                            + location
                                                            + "\r\nContent-Length: 0\r\n"
                                                            + "Connection: close\r\n\r\n")
                                                    .getBytes(StandardCharsets.US_ASCII));
                                    output.flush();
                                    socket.setSoTimeout(5000);
                                    while (socket.getInputStream().read() >= 0) {
                                        // 首跳在下一跳前关闭时会读到 EOF；未关闭时保持等待直到测试超时。
                                    }
                                    redirectConnectionClosed.countDown();
                                }
                                return null;
                            });
            Future<?> finalTask =
                    executor.submit(
                            () -> {
                                try (Socket socket = finalServer.accept()) {
                                    readHttpRequest(socket.getInputStream());
                                    finalRequestReceived.countDown();
                                    if (!allowFinalResponse.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "final response was not released");
                                    }
                                    OutputStream output = socket.getOutputStream();
                                    output.write(
                                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                                                    .getBytes(StandardCharsets.US_ASCII));
                                    output.flush();
                                }
                                return null;
                            });
            String url = "http://127.0.0.1:" + redirectServer.getLocalPort() + "/entry";
            SecurityPolicyService policy =
                    new AllowLocalButBlockMetadataSecurityPolicyService(new AppConfig());
            Future<byte[]> downloadTask = executor.submit(() -> action.download(url, policy));

            assertThat(finalRequestReceived.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(redirectConnectionClosed.await(2, TimeUnit.SECONDS)).isTrue();
            allowFinalResponse.countDown();
            assertThat(new String(downloadTask.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8))
                    .isEqualTo("ok");
            redirectTask.get(5, TimeUnit.SECONDS);
            finalTask.get(5, TimeUnit.SECONDS);
        } finally {
            allowFinalResponse.countDown();
            executor.shutdownNow();
        }
    }

    /** 统一声明 Hutool 与 OkHttp 下载路径的测试调用契约。 */
    private interface DownloadAction {
        /**
         * 调用受测下载实现。
         *
         * @param url 初始 URL。
         * @param policy URL 安全策略。
         * @return 下载到的响应字节。
         * @throws Exception 下载失败时抛出异常。
         */
        byte[] download(String url, SecurityPolicyService policy) throws Exception;
    }

    /**
     * 读取 HTTP 请求头，确保测试服务仅在完整收到请求后发送响应。
     *
     * @param input 客户端请求流。
     * @throws Exception 请求格式错误或读取失败时抛出异常。
     */
    private static void readHttpRequest(InputStream input) throws Exception {
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IllegalStateException("HTTP request ended before headers");
            }
            if ((matched == 0 || matched == 2) && value == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && value == '\n') {
                matched++;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
    }
}
