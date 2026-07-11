package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class DomesticQrSetupServiceTest {
    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCompleteDingTalkQrLoginAndPersistCredentials() throws Exception {
        AtomicInteger pollCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/app/registration/init",
                exchange -> writeJson(exchange, "{\"errcode\":0,\"nonce\":\"nonce-1\"}"));
        server.createContext(
                "/app/registration/begin",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"errcode\":0,\"device_code\":\"device-1\",\"verification_uri_complete\":\"https://login.dingtalk.test/qr?code=1\",\"expires_in\":60,\"interval\":1}"));
        server.createContext(
                "/app/registration/poll",
                exchange -> {
                    if (pollCount.incrementAndGet() < 2) {
                        writeJson(exchange, "{\"errcode\":0,\"status\":\"WAITING\"}");
                    } else {
                        writeJson(
                                exchange,
                                "{\"errcode\":0,\"status\":\"SUCCESS\",\"client_id\":\"ding-client\",\"client_secret\":\"ding-secret\"}");
                    }
                });
        server.start();

        AppConfig config = testConfig();
        File workspaceHome = new File(config.getRuntime().getHome());
        config.getChannels()
                .getDingtalk()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("dingtalk");
        assertThat(start.get("ticket")).isNotNull();
        assertThat(start.get("qr_url")).isEqualTo("https://login.dingtalk.test/qr?code=1");

        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("confirmed");
        assertThat(current.get("client_id")).isEqualTo("ding-client");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("clientId: ding-client")
                .contains("clientSecret: ding-secret")
                .contains("robotCode: ding-client")
                .contains("enabled: true");
    }

    @Test
    void shouldCompleteFeishuQrLoginAndPersistCredentials() throws Exception {
        AtomicInteger pollCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/oauth/v1/app/registration",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (body.contains("action=init")) {
                        writeJson(exchange, "{\"supported_auth_methods\":[\"client_secret\"]}");
                    } else if (body.contains("action=begin")) {
                        writeJson(
                                exchange,
                                "{\"device_code\":\"feishu-device\",\"verification_uri_complete\":\"https://accounts.feishu.test/qr?code=1\",\"user_code\":\"ABCD\",\"interval\":1,\"expires_in\":60}");
                    } else if (pollCount.incrementAndGet() < 2) {
                        writeJson(
                                exchange,
                                "{\"error\":\"authorization_pending\",\"user_info\":{\"tenant_brand\":\"feishu\"}}");
                    } else {
                        writeJson(
                                exchange,
                                "{\"client_id\":\"feishu-app\",\"client_secret\":\"feishu-secret\",\"user_info\":{\"open_id\":\"ou-owner\",\"tenant_brand\":\"feishu\"}}");
                    }
                });
        server.start();

        AppConfig config = testConfig();
        File workspaceHome = new File(config.getRuntime().getHome());
        config.getChannels()
                .getFeishu()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("feishu");
        assertThat(start.get("ticket")).isNotNull();
        assertThat(start.get("qr_url"))
                .isEqualTo("https://accounts.feishu.test/qr?code=1&from=solonclaw&tp=solonclaw");

        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("confirmed");
        assertThat(current.get("app_id")).isEqualTo("feishu-app");
        assertThat(current.get("open_id")).isEqualTo("ou-owner");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("appId: feishu-app")
                .contains("appSecret: feishu-secret")
                .contains("groupAllowedUsers:")
                .contains("- ou-owner")
                .contains("enabled: true");
    }

    @Test
    void shouldSwitchFeishuDomainWhenTenantBrandIsLark() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/oauth/v1/app/registration",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (body.contains("action=init")) {
                        writeJson(exchange, "{\"supported_auth_methods\":[\"client_secret\"]}");
                    } else if (body.contains("action=begin")) {
                        writeJson(
                                exchange,
                                "{\"device_code\":\"lark-device\",\"verification_uri_complete\":\"https://accounts.feishu.test/qr\",\"interval\":1,\"expire_in\":60}");
                    } else {
                        writeJson(
                                exchange,
                                "{\"client_id\":\"lark-app\",\"client_secret\":\"lark-secret\",\"user_info\":{\"open_id\":\"ou-lark\",\"tenant_brand\":\"lark\"}}");
                    }
                });
        server.start();

        AppConfig config = testConfig();
        File workspaceHome = new File(config.getRuntime().getHome());
        config.getChannels()
                .getFeishu()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("feishu");
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("confirmed");
        assertThat(current.get("domain")).isEqualTo("lark");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("domain: lark")
                .contains("appId: lark-app")
                .contains("appSecret: lark-secret");
    }

    @Test
    void shouldFailDingTalkUnknownPollStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/app/registration/init",
                exchange -> writeJson(exchange, "{\"errcode\":0,\"nonce\":\"nonce-1\"}"));
        server.createContext(
                "/app/registration/begin",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"errcode\":0,\"device_code\":\"device-1\",\"verification_uri_complete\":\"https://login.dingtalk.test/qr\",\"expires_in\":60,\"interval\":1}"));
        server.createContext(
                "/app/registration/poll",
                exchange -> writeJson(exchange, "{\"errcode\":0,\"status\":\"BROKEN\"}"));
        server.start();

        AppConfig config = testConfig();
        config.getChannels()
                .getDingtalk()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("dingtalk");
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("failed");
        assertThat(current.get("error_code")).isEqualTo("qr_failed");
        assertThat(String.valueOf(current.get("error_message"))).contains("BROKEN");
    }

    private DomesticQrSetupService service(AppConfig config) {
        GatewayRuntimeRefreshService refreshService = refreshService(config);
        return new DomesticQrSetupService(
                config,
                new DashboardConfigService(config, refreshService),
                refreshService,
                new AllowLocalButBlockMetadataSecurityPolicyService(config));
    }

    private Map<String, Object> waitForTerminal(DomesticQrSetupService service, String ticket)
            throws Exception {
        Map<String, Object> current = Collections.emptyMap();
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            current = service.get(ticket);
            if ("confirmed".equals(current.get("status"))
                    || "failed".equals(current.get("status"))) {
                return current;
            }
            Thread.sleep(200L);
        }
        return current;
    }

    private AppConfig testConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-domestic-qr").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(
                        new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        return config;
    }

    private GatewayRuntimeRefreshService refreshService(AppConfig config) {
        return new GatewayRuntimeRefreshService(
                config,
                new ChannelConnectionManager(
                        new LinkedHashMap<
                                com.jimuqu.solon.claw.core.enums.PlatformType,
                                com.jimuqu.solon.claw.core.service.ChannelAdapter>()));
    }

    private void writeJson(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream outputStream = exchange.getResponseBody();
        try {
            outputStream.write(bytes);
        } finally {
            outputStream.close();
        }
    }
}
