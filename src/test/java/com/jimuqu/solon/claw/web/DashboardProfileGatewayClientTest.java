package com.jimuqu.solon.claw.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证 Profile 独立网关回环转发的认证、正文和响应契约。 */
public class DashboardProfileGatewayClientTest {
    @Test
    void shouldForwardJsonToLoopbackAndUnwrapDashboardEnvelope() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/chat/runs",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    requestBody.set(new String(readAll(exchange), StandardCharsets.UTF_8));
                    respond(
                            exchange,
                            200,
                            "{\"success\":true,\"data\":{\"run_id\":\"run-worker\",\"session_id\":\"session-worker\"}}");
                });
        server.start();
        try {
            DashboardProfileGatewayClient client =
                    new DashboardProfileGatewayClient(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                            "worker-token",
                            "worker");
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("input", "hello");
            Map<String, Object> response =
                    client.request(
                            "POST",
                            Collections.<String, String>emptyMap(),
                            body,
                            "api",
                            "chat",
                            "runs");

            assertThat(response)
                    .containsEntry("run_id", "run-worker")
                    .containsEntry("session_id", "session-worker");
            assertThat(authorization.get()).isEqualTo("Bearer worker-token");
            assertThat(requestBody.get()).contains("\"input\":\"hello\"").doesNotContain("profile");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldPreserveTargetFailureAndRejectNonLoopbackEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/runs/missing",
                exchange ->
                        respond(exchange, 404, "{\"success\":false,\"error\":\"Run not found\"}"));
        server.start();
        try {
            DashboardProfileGatewayClient client =
                    new DashboardProfileGatewayClient(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                            "worker-token",
                            "worker");
            assertThatThrownBy(
                            () ->
                                    client.request(
                                            "GET",
                                            Collections.<String, String>emptyMap(),
                                            "api",
                                            "runs",
                                            "missing"))
                    .isInstanceOf(DashboardProfileGatewayException.class)
                    .satisfies(
                            error -> {
                                DashboardProfileGatewayException gatewayError =
                                        (DashboardProfileGatewayException) error;
                                assertThat(gatewayError.getStatus()).isEqualTo(404);
                                assertThat(gatewayError.getMessage()).contains("Run not found");
                            });
        } finally {
            server.stop(0);
        }

        assertThatThrownBy(
                        () ->
                                new DashboardProfileGatewayClient(
                                        "http://localhost:8080/", "token", "worker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1");
    }

    /** 写入固定 JSON 测试响应。 */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 使用 Java 8 API 读取测试请求正文。 */
    private static byte[] readAll(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }
}
