package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.support.SecurityPolicyTestSupport.AllowLocalButBlockMetadataSecurityPolicyService;
import com.jimuqu.solon.claw.web.DashboardConfigService;
import com.jimuqu.solon.claw.web.DomesticQrSetupService;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

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
    void shouldCompleteWecomQrLoginAndPersistCredentials() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ai/qc/generate",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"data\":{\"scode\":\"wecom-code\",\"auth_url\":\"https://work.weixin.qq.com/ai/qc/c?s=wecom-code\"}}"));
        server.createContext(
                "/ai/qc/query_result",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"data\":{\"status\":\"success\",\"bot_info\":{\"botid\":\"wecom-bot\",\"secret\":\"wecom-secret\"}}}"));
        server.start();

        AppConfig config = testConfig();
        File workspaceHome = new File(config.getRuntime().getHome());
        config.getChannels()
                .getWecom()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("wecom");
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current)
                .containsEntry("status", "confirmed")
                .containsEntry("bot_id", "wecom-bot");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("botId: wecom-bot")
                .contains("secret: wecom-secret")
                .contains("enabled: true");
    }

    /** 显式 Profile 必须在任务创建时冻结，并只写入该 Profile 工作区。 */
    @Test
    void shouldPersistQrCredentialsToSelectedProfile() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ai/qc/generate",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"data\":{\"scode\":\"profile-code\",\"auth_url\":\"https://work.weixin.qq.com/qr\"}}"));
        server.createContext(
                "/ai/qc/query_result",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"data\":{\"status\":\"success\",\"bot_info\":{\"botid\":\"profile-bot\",\"secret\":\"profile-secret\"}}}"));
        server.start();

        AppConfig config = testConfig();
        Path root = Paths.get(config.getRuntime().getHome());
        Path profileHome = root.resolve("profiles/worker");
        Files.createDirectories(profileHome);
        Files.write(
                profileHome.resolve("config.yml"),
                ("solonclaw:\n  channels:\n    wecom:\n      baseUrl: http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        ProfileManager manager = new ProfileManager(root, root.resolve("bin"), "solonclaw");
        DashboardProfileContext profileContext = new DashboardProfileContext(manager, config);
        GatewayRuntimeRefreshService refreshService = refreshService(config);
        DomesticQrSetupService service =
                new DomesticQrSetupService(
                        config,
                        new DashboardConfigService(config, refreshService, profileContext),
                        refreshService,
                        new AllowLocalButBlockMetadataSecurityPolicyService(config),
                        profileContext,
                        null,
                        null);

        Map<String, Object> start = service.start("wecom", "worker");
        Map<String, Object> current =
                waitForTerminal(service, String.valueOf(start.get("ticket")), "worker");

        assertThat(current).containsEntry("status", "confirmed").containsEntry("profile", "worker");
        assertThat(
                        new String(
                                Files.readAllBytes(profileHome.resolve("config.yml")),
                                StandardCharsets.UTF_8))
                .contains("botId: profile-bot")
                .contains("secret: profile-secret");
        assertThat(new File(root.toFile(), "config.yml")).doesNotExist();
    }

    /** QQBot 扫码应在本地解密密钥，并把扫码者写入默认配对访问名单。 */
    @Test
    void shouldCompleteQqbotQrAndPersistScannerAccessPolicy() throws Exception {
        AtomicReference<byte[]> bindKey = new AtomicReference<byte[]>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/lite/create_bind_task",
                exchange -> {
                    ONode body =
                            ONode.ofJson(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                    bindKey.set(Base64.getDecoder().decode(body.get("key").getString()));
                    writeJson(exchange, "{\"retcode\":0,\"data\":{\"task_id\":\"qq-task-1\"}}");
                });
        server.createContext(
                "/lite/poll_bind_result",
                exchange ->
                        writeJson(
                                exchange,
                                "{\"retcode\":0,\"data\":{\"status\":2,\"bot_appid\":\"qq-app\",\"bot_encrypt_secret\":\""
                                        + encryptQqbotSecret("qq-secret", bindKey.get())
                                        + "\",\"user_openid\":\"qq-owner\"}}"));
        server.start();

        AppConfig config = testConfig();
        File workspaceHome = new File(config.getRuntime().getHome());
        config.getChannels()
                .getQqbot()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("qqbot");
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current)
                .containsEntry("status", "confirmed")
                .containsEntry("app_id", "qq-app")
                .containsEntry("user_openid", "qq-owner")
                .doesNotContainKey("client_secret");
        assertThat(ONode.serialize(current)).doesNotContain("qq-secret");
        assertThat(FileUtil.readUtf8String(new File(workspaceHome, "config.yml")))
                .contains("appId: qq-app")
                .contains("clientSecret: qq-secret")
                .contains("dmPolicy: pairing")
                .contains("allowedUsers:")
                .contains("- qq-owner")
                .contains("enabled: true");
    }

    /** QQBot 扫码服务地址命中元数据硬边界时必须在发起请求前阻断。 */
    @Test
    void shouldBlockUnsafeQqbotQrBaseUrl() throws Exception {
        AppConfig config = testConfig();
        config.getChannels().getQqbot().setBaseUrl("http://169.254.169.254/latest/meta-data");
        DomesticQrSetupService service = service(config);

        Map<String, Object> start = service.start("qqbot");
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current)
                .containsEntry("status", "failed")
                .containsEntry("error_code", "qr_failed");
        assertThat(String.valueOf(current.get("error_message"))).contains("安全策略阻断");
    }

    /** 同一 Profile 的新任务应替换旧任务，旧轮询不得继续占用执行线程。 */
    @Test
    void shouldReplaceActiveTicketForSameProfileAndPlatform() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/lite/create_bind_task",
                exchange ->
                        writeJson(
                                exchange, "{\"retcode\":0,\"data\":{\"task_id\":\"qq-waiting\"}}"));
        server.createContext(
                "/lite/poll_bind_result",
                exchange -> writeJson(exchange, "{\"retcode\":0,\"data\":{\"status\":0}}"));
        server.start();

        AppConfig config = testConfig();
        config.getChannels()
                .getQqbot()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);

        Map<String, Object> first = service.start("qqbot");
        Map<String, Object> second = service.start("qqbot");
        Map<String, Object> replaced = service.get(String.valueOf(first.get("ticket")));

        assertThat(second.get("ticket")).isNotEqualTo(first.get("ticket"));
        assertThat(replaced)
                .containsEntry("status", "failed")
                .containsEntry("error_code", "qr_replaced");
        service.shutdown();
    }

    /** 扫码凭据请求禁止自动跟随重定向，避免凭据跨主机泄露。 */
    @Test
    void shouldNotFollowQqbotQrRedirect() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/lite/create_bind_task",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/redirected");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/redirected",
                exchange -> {
                    redirected.incrementAndGet();
                    writeJson(exchange, "{\"retcode\":0,\"data\":{\"task_id\":\"unsafe\"}}");
                });
        server.start();

        AppConfig config = testConfig();
        config.getChannels()
                .getQqbot()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        DomesticQrSetupService service = service(config);
        Map<String, Object> start = service.start("qqbot");
        Map<String, Object> current = waitForTerminal(service, String.valueOf(start.get("ticket")));

        assertThat(current).containsEntry("status", "failed");
        assertThat(redirected).hasValue(0);
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
        return waitForTerminal(service, ticket, null);
    }

    /** 轮询明确 Profile 下的扫码任务，保持测试与生产隔离契约一致。 */
    private Map<String, Object> waitForTerminal(
            DomesticQrSetupService service, String ticket, String profile) throws Exception {
        Map<String, Object> current = Collections.emptyMap();
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            current = service.get(ticket, profile);
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

    /** 生成与 QQBot 扫码协议一致的 IV、密文和认证标签拼接值。 */
    private String encryptQqbotSecret(String secret, byte[] key) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
