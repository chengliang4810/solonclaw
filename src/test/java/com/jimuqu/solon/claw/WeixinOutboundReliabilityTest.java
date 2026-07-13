package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.weixin.WeiXinChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.noear.snack4.ONode;

/** 验证微信出站请求串行、限流冷却和失效上下文 token 清理。 */
public class WeixinOutboundReliabilityTest {
    /** 限流后应等待冷却并重试，文字与附件均不得丢失或乱序。 */
    @Test
    void shouldRetryRateLimitedAttachmentWithoutDroppingOrderedDelivery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        ExecutorService client = Executors.newSingleThreadExecutor();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger sendRequests = new AtomicInteger();
        server.setExecutor(serverExecutor);
        String uploadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        server.createContext(
                "/ilink/bot/getuploadurl",
                exchange -> {
                    byte[] response =
                            new ONode()
                                    .set("upload_full_url", uploadUrl)
                                    .toJson()
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/upload",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("x-encrypted-param", "uploaded-file");
                    exchange.sendResponseHeaders(200, 0L);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendmessage",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    String responseText =
                            sendRequests.incrementAndGet() == 2 ? "{\"ret\":-2}" : "{}";
                    byte[] response =
                            responseText.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig(server);
        config.getChannels().getWeixin().setSendChunkRetries(0);
        WeiXinChannelAdapter adapter = newAdapter(config, new MemoryStateRepository());
        File attachmentFile = Files.createTempFile("solonclaw-weixin-rate-limit", ".txt").toFile();
        Files.writeString(attachmentFile.toPath(), "attachment", StandardCharsets.UTF_8);

        try {
            DeliveryRequest request = textRequest("wx-user", "text-before-attachment");
            request.getAttachments().add(attachmentRequest("wx-user", attachmentFile).getAttachments().get(0));
            Future<?> delivery = client.submit(() -> adapter.send(request));
            shortenRateLimitCooldown(adapter);
            delivery.get(2L, TimeUnit.SECONDS);

            assertThat(sendRequests.get()).isEqualTo(3);
            assertThat(bodies).hasSize(3);
            assertThat(bodies.get(0)).contains("\"type\":1");
            assertThat(bodies.get(1)).contains("\"type\":4");
            assertThat(bodies.get(2)).contains("\"type\":4");
        } finally {
            adapter.disconnect();
            client.shutdownNow();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    /** 将真实限流窗口缩短为测试窗口，避免测试等待平台固定冷却时长。 */
    private static void shortenRateLimitCooldown(WeiXinChannelAdapter adapter) throws Exception {
        Field field = WeiXinChannelAdapter.class.getDeclaredField("rateLimitCooldownUntilNanos");
        field.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (field.getLong(adapter) <= System.nanoTime() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5L);
        }
        assertThat(field.getLong(adapter)).isGreaterThan(System.nanoTime());
        field.setLong(adapter, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20L));
    }

    /** tokenless 降级成功后，下一条消息不得再次携带已失效的持久 token。 */
    @ParameterizedTest
    @ValueSource(strings = {"errcode", "ret"})
    void shouldClearExpiredContextTokenBeforeFollowingSend(String errorField) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger requests = new AtomicInteger();
        server.createContext(
                "/ilink/bot/sendmessage",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    String responseText =
                            requests.incrementAndGet() == 1 ? "{\"" + errorField + "\":-14}" : "{}";
                    byte[] response = responseText.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig(server);
        config.getChannels().getWeixin().setSendChunkRetries(0);
        MemoryStateRepository repository = new MemoryStateRepository();
        repository.value.set("expired-token");
        WeiXinChannelAdapter adapter = newAdapter(config, repository);

        try {
            adapter.send(textRequest("wx-user", "first"));
            adapter.send(textRequest("wx-user", "second"));

            assertThat(bodies).hasSize(3);
            assertThat(ONode.ofJson(bodies.get(0)).get("msg").get("context_token").getString())
                    .isEqualTo("expired-token");
            assertThat(ONode.ofJson(bodies.get(1)).get("msg").get("context_token").getString())
                    .isBlank();
            assertThat(ONode.ofJson(bodies.get(2)).get("msg").get("context_token").getString())
                    .isBlank();
            assertThat(repository.deletes.get()).isEqualTo(1);
            assertThat(repository.value.get()).isNull();
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 附件降级发送也必须清理旧 token，且后续文字发送不得再次携带。 */
    @ParameterizedTest
    @ValueSource(strings = {"errcode", "ret"})
    void shouldClearExpiredContextTokenAfterAttachmentFallback(String errorField) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger sendRequests = new AtomicInteger();
        String uploadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        server.createContext(
                "/ilink/bot/getuploadurl",
                exchange -> {
                    byte[] response =
                            new ONode()
                                    .set("upload_full_url", uploadUrl)
                                    .toJson()
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/upload",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("x-encrypted-param", "uploaded-file");
                    exchange.sendResponseHeaders(200, 0L);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendmessage",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    String responseText =
                            sendRequests.incrementAndGet() == 1
                                    ? "{\"" + errorField + "\":-14}"
                                    : "{}";
                    byte[] response = responseText.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig(server);
        MemoryStateRepository repository = new MemoryStateRepository();
        repository.value.set("expired-media-token");
        WeiXinChannelAdapter adapter = newAdapter(config, repository);
        File attachmentFile = Files.createTempFile("solonclaw-weixin-attachment", ".txt").toFile();
        Files.writeString(attachmentFile.toPath(), "attachment", StandardCharsets.UTF_8);

        try {
            adapter.send(attachmentRequest("wx-user", attachmentFile));
            adapter.send(textRequest("wx-user", "after attachment"));

            assertThat(bodies).hasSize(3);
            assertThat(ONode.ofJson(bodies.get(0)).get("msg").get("context_token").getString())
                    .isEqualTo("expired-media-token");
            assertThat(ONode.ofJson(bodies.get(1)).get("msg").get("context_token").getString())
                    .isBlank();
            assertThat(ONode.ofJson(bodies.get(2)).get("msg").get("context_token").getString())
                    .isBlank();
            assertThat(repository.deletes.get()).isEqualTo(1);
            assertThat(repository.value.get()).isNull();
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 创建指向本地假 iLink 服务的最小配置。 */
    private static AppConfig newConfig(HttpServer server) throws Exception {
        File home = Files.createTempDirectory("solonclaw-weixin-outbound-test").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(home.getAbsolutePath());
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setToken("test-token");
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return config;
    }

    /** 创建带内存状态仓储的微信适配器。 */
    private static WeiXinChannelAdapter newAdapter(
            AppConfig config, ChannelStateRepository repository) {
        return new WeiXinChannelAdapter(
                config.getChannels().getWeixin(), repository, new AttachmentCacheService(config));
    }

    /** 创建单条文字投递请求。 */
    private static DeliveryRequest textRequest(String chatId, String text) {
        DeliveryRequest request = new DeliveryRequest();
        request.setChatId(chatId);
        request.setText(text);
        return request;
    }

    /** 创建单个文件附件投递请求。 */
    private static DeliveryRequest attachmentRequest(String chatId, File file) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind("file");
        attachment.setLocalPath(file.getAbsolutePath());
        attachment.setOriginalName(file.getName());
        attachment.setMimeType("text/plain");
        DeliveryRequest request = new DeliveryRequest();
        request.setChatId(chatId);
        request.getAttachments().add(attachment);
        return request;
    }

    /** 保存单个上下文 token，便于验证删除后连续发送行为。 */
    private static final class MemoryStateRepository implements ChannelStateRepository {
        private final AtomicReference<String> value = new AtomicReference<String>();
        private final AtomicInteger deletes = new AtomicInteger();

        /** 读取当前 token。 */
        @Override
        public String get(PlatformType platform, String scopeKey, String stateKey) {
            return value.get();
        }

        /** 保存当前 token。 */
        @Override
        public void put(
                PlatformType platform, String scopeKey, String stateKey, String stateValue) {
            value.set(stateValue);
        }

        /** 删除当前 token。 */
        @Override
        public void delete(PlatformType platform, String scopeKey, String stateKey) {
            deletes.incrementAndGet();
            value.set(null);
        }

        /** 当前测试不需要枚举状态项。 */
        @Override
        public List<StateItem> list(PlatformType platform, String scopeKey) {
            return Collections.emptyList();
        }
    }
}
