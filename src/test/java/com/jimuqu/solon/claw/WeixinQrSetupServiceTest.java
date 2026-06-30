package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.WeixinQrSetupService;
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

public class WeixinQrSetupServiceTest {
    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCompleteQrLoginAndPersistCredentials() throws Exception {
        AtomicInteger statusPollCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_bot_qrcode",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"qrcode\":\"qr-123\",\"qrcode_img_content\":\"http://127.0.0.1/qr.png\"}"));
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange -> {
                    if (statusPollCount.incrementAndGet() < 2) {
                        writeJson(exchange, "{\"status\":\"wait\"}");
                    } else {
                        writeJson(
                                exchange,
                                "{\"status\":\"confirmed\",\"ilink_bot_id\":\"wx-bot\",\"bot_token\":\"wx-token\",\"baseurl\":\"http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "\",\"ilink_user_id\":\"wx-user\"}");
                    }
                });
        server.start();

        File workspaceHome = Files.createTempDirectory("solonclaw-weixin-qr").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        GatewayRuntimeRefreshService refreshService =
                new GatewayRuntimeRefreshService(
                        config,
                        new com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager(
                                new LinkedHashMap<
                                        com.jimuqu.solon.claw.core.enums.PlatformType,
                                        com.jimuqu.solon.claw.core.service.ChannelAdapter>()));
        WeixinQrSetupService service =
                new WeixinQrSetupService(
                        config,
                        new DashboardConfigService(config, refreshService),
                        refreshService,
                        new AllowLocalButBlockMetadataSecurityPolicyService(config));

        Map<String, Object> start = service.start();
        assertThat(start.get("ticket")).isNotNull();

        String ticket = String.valueOf(start.get("ticket"));
        Map<String, Object> current = Collections.emptyMap();
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            current = service.get(ticket);
            if ("confirmed".equals(current.get("status"))) {
                break;
            }
            Thread.sleep(200L);
        }

        assertThat(current.get("status")).isEqualTo("confirmed");
        assertThat(current.get("account_id")).isEqualTo("wx-bot");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("accountId: wx-bot")
                .contains("token: wx-token")
                .contains("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void shouldBlockUnsafeQrHttpRedirectTarget() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_bot_qrcode",
                exchange -> {
                    exchange.getResponseHeaders()
                            .set(
                                    "Location",
                                    "http://169.254.169.254/latest/meta-data/?token=secret");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.start();

        AppConfig config = testConfig();
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        GatewayRuntimeRefreshService refreshService = refreshService(config);
        WeixinQrSetupService service =
                new WeixinQrSetupService(
                        config,
                        new DashboardConfigService(config, refreshService),
                        refreshService,
                        new AllowLocalButBlockMetadataSecurityPolicyService(config));

        Map<String, Object> start = service.start();
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("failed");
        assertThat(current.get("error_message").toString())
                .contains("微信 iLink 请求地址 被安全策略阻断")
                .contains("169.254.169.254")
                .contains("token=***");
    }

    @Test
    void shouldBlockUnsafeConfirmedBaseUrlBeforePersisting() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/get_bot_qrcode",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"qrcode\":\"qr-unsafe\",\"qrcode_img_content\":\"http://127.0.0.1/qr.png\"}"));
        server.createContext(
                "/ilink/bot/get_qrcode_status",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"status\":\"confirmed\",\"ilink_bot_id\":\"wx-bot\",\"bot_token\":\"wx-token\",\"baseurl\":\"http://169.254.169.254/latest/meta-data/?token=secret\",\"ilink_user_id\":\"wx-user\"}"));
        server.start();

        AppConfig config = testConfig();
        File workspaceHome = new File(config.getRuntime().getHome());
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        GatewayRuntimeRefreshService refreshService = refreshService(config);
        WeixinQrSetupService service =
                new WeixinQrSetupService(
                        config,
                        new DashboardConfigService(config, refreshService),
                        refreshService,
                        new AllowLocalButBlockMetadataSecurityPolicyService(config));

        Map<String, Object> start = service.start();
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("failed");
        assertThat(current.get("error_message").toString())
                .contains("微信 iLink baseurl 被安全策略阻断")
                .contains("169.254.169.254")
                .contains("token=***");
        assertThat(new File(workspaceHome, "config.yml")).doesNotExist();
    }

    @Test
    void shouldRedactQrFailureMessages() throws Exception {
        AppConfig config = testConfig();
        config.getChannels().getWeixin().setBaseUrl("https://safe.example.test");
        GatewayRuntimeRefreshService refreshService = refreshService(config);
        WeixinQrSetupService service =
                new WeixinQrSetupService(
                        config,
                        new DashboardConfigService(config, refreshService),
                        refreshService,
                        new TokenMessageSecurityPolicyService(config));

        Map<String, Object> start = service.start();
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current.get("status")).isEqualTo("failed");
        assertThat(current.get("error_message").toString()).contains("token=***");
        assertThat(current.get("error_message").toString()).doesNotContain("sk-test-weixinqr12345");
    }

    @Test
    void shouldRedactRawQrFailureMessagesAtStatusBoundary() throws Exception {
        AppConfig config = testConfig();
        GatewayRuntimeRefreshService refreshService = refreshService(config);
        WeixinQrSetupService service =
                new WeixinQrSetupService(
                        config, new DashboardConfigService(config, refreshService), refreshService);
        Object state = state("qr-raw-token");

        java.lang.reflect.Method fail =
                WeixinQrSetupService.class.getDeclaredMethod(
                        "fail", state.getClass(), String.class, String.class);
        fail.setAccessible(true);
        fail.invoke(service, state, "qr_failed", "raw failure token=sk-test-weixinqrboundary12345");
        java.lang.reflect.Method toMap =
                WeixinQrSetupService.class.getDeclaredMethod("toMap", state.getClass());
        toMap.setAccessible(true);
        Map<String, Object> current = (Map<String, Object>) toMap.invoke(service, state);

        assertThat(current.get("error_message").toString()).contains("token=***");
        assertThat(current.get("message").toString()).contains("token=***");
        assertThat(String.valueOf(current)).doesNotContain("sk-test-weixinqrboundary12345");
    }

    private AppConfig testConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-weixin-qr").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(workspaceHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(workspaceHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(workspaceHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(workspaceHome, "config.yml").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(workspaceHome, "logs").getAbsolutePath());
        return config;
    }

    private GatewayRuntimeRefreshService refreshService(AppConfig config) {
        return new GatewayRuntimeRefreshService(
                config,
                new com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager(
                        new LinkedHashMap<
                                com.jimuqu.solon.claw.core.enums.PlatformType,
                                com.jimuqu.solon.claw.core.service.ChannelAdapter>()));
    }

    private Map<String, Object> waitForTerminal(WeixinQrSetupService service, String ticket)
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

    private Object state(String ticket) throws Exception {
        Class<?> stateType =
                Class.forName("com.jimuqu.solon.claw.web.WeixinQrSetupService$TicketState");
        java.lang.reflect.Constructor<?> constructor = stateType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        java.lang.reflect.Field ticketField = stateType.getDeclaredField("ticket");
        ticketField.setAccessible(true);
        ticketField.set(state, ticket);
        return state;
    }

    private static class TokenMessageSecurityPolicyService extends SecurityPolicyService {
        private TokenMessageSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        public UrlVerdict checkUrl(String url) {
            return UrlVerdict.block(url, "blocked token=sk-test-weixinqr12345");
        }
    }
}
