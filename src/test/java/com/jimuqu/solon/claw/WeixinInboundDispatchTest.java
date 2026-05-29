package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.gateway.platform.weixin.WeiXinChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class WeixinInboundDispatchTest {
    @Test
    void shouldSplitLongOutboundTextAtWeixinSafeLimit() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, repeat("a", 2001));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(2000);
        assertThat(chunks.get(1)).hasSize(1);
    }

    @Test
    void shouldStillSplitMultilineTextByLineWhenConfigured() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setSplitMultilineMessages(true);
        WeiXinChannelAdapter adapter = newAdapter(config);
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, "第一行\n\n第二行");

        assertThat(chunks).containsExactly("第一行", "第二行");
    }

    @Test
    void shouldNormalizeOutboundTextNewlinesToCrLfForWeixinClients() throws Exception {
        Method normalize =
                WeiXinChannelAdapter.class.getDeclaredMethod(
                        "normalizeOutboundTextForWeixin", String.class);
        normalize.setAccessible(true);

        assertThat((String) normalize.invoke(null, "第一行\n第二行"))
                .isEqualTo("第一行\r\n第二行");
        assertThat((String) normalize.invoke(null, "第一行\r第二行"))
                .isEqualTo("第一行\r\n第二行");
        assertThat((String) normalize.invoke(null, "第一行\r\n第二行"))
                .isEqualTo("第一行\r\n第二行");
    }

    @Test
    void shouldDispatchInboundOffThePollingThread() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");

        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config));

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> handlerThread = new AtomicReference<String>();
        final String callerThread = Thread.currentThread().getName();
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        handlerThread.set(Thread.currentThread().getName());
                        latch.countDown();
                    }
                });

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(
                adapter,
                ONode.ofJson(
                        "{"
                                + "\"from_user_id\":\"wx-user\","
                                + "\"message_id\":\"msg-1\","
                                + "\"room_id\":\"room-1\","
                                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"hello\"}}]"
                                + "}"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handlerThread.get()).isNotBlank();
        assertThat(handlerThread.get()).isNotEqualTo(callerThread);

        adapter.disconnect();
    }

    @Test
    void shouldBlockUnsafeWeixinApiBaseUrlBeforeNetworkAccess() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setBaseUrl(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config));
        Method apiPost =
                WeiXinChannelAdapter.class.getDeclaredMethod(
                        "apiPost", String.class, ONode.class);
        apiPost.setAccessible(true);

        assertThatThrownBy(() -> invoke(apiPost, adapter, "ilink/bot/sendmessage", new ONode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Weixin API URL blocked")
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining("token=***");
    }

    @Test
    void shouldBlockUnsafeWeixinCdnBaseUrlBeforeUpload() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setCdnBaseUrl(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config));
        Method resolveUploadUrl =
                WeiXinChannelAdapter.class.getDeclaredMethod(
                        "resolveUploadUrl", ONode.class, String.class);
        resolveUploadUrl.setAccessible(true);
        String uploadUrl =
                String.valueOf(
                        resolveUploadUrl.invoke(
                                adapter,
                                new ONode().set("upload_param", "abc").asObject(),
                                "file-1"));
        Method uploadCiphertext =
                WeiXinChannelAdapter.class.getDeclaredMethod(
                        "uploadCiphertext", String.class, byte[].class);
        uploadCiphertext.setAccessible(true);

        assertThat(uploadUrl).contains("169.254.169.254");
        assertThatThrownBy(() -> invoke(uploadCiphertext, adapter, uploadUrl, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Weixin CDN upload URL blocked")
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining("token=***");
    }

    @Test
    void shouldRedactWeixinFailureJson() throws Throwable {
        WeiXinChannelAdapter adapter = newAdapter();
        Method safeJson = WeiXinChannelAdapter.class.getDeclaredMethod("safeJson", ONode.class);
        safeJson.setAccessible(true);
        ONode failure =
                new ONode()
                        .set("errcode", 40001)
                        .set("errmsg", "invalid token=ghp_weixinfail12345")
                        .set("body", new ONode().set("api_key", "sk-weixin-failure-secret"));

        String message = String.valueOf(invoke(safeJson, adapter, failure));

        assertThat(message)
                .contains("token=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_weixinfail12345")
                .doesNotContain("sk-weixin-failure-secret");
    }

    private WeiXinChannelAdapter newAdapter() throws Exception {
        return newAdapter(newConfig());
    }

    private WeiXinChannelAdapter newAdapter(AppConfig config) {
        return new WeiXinChannelAdapter(
                config.getChannels().getWeixin(),
                new InMemoryChannelStateRepository(),
                new AttachmentCacheService(config));
    }

    private Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private String repeat(String text, int count) {
        StringBuilder builder = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(text);
        }
        return builder.toString();
    }

    private AppConfig newConfig() throws Exception {
        File runtimeHome = Files.createTempDirectory("jimuqu-weixin-dispatch-test").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getRuntime().setContextDir(new File(runtimeHome, "context").getAbsolutePath());
        config.getRuntime().setSkillsDir(new File(runtimeHome, "skills").getAbsolutePath());
        config.getRuntime().setCacheDir(new File(runtimeHome, "cache").getAbsolutePath());
        config.getRuntime()
                .setStateDb(new File(new File(runtimeHome, "data"), "state.db").getAbsolutePath());
        config.getRuntime().setConfigFile(new File(runtimeHome, "config.yml").getAbsolutePath());
        config.getRuntime().setLogsDir(new File(runtimeHome, "logs").getAbsolutePath());
        return config;
    }

    private static class InMemoryChannelStateRepository implements ChannelStateRepository {
        @Override
        public String get(PlatformType platform, String scopeKey, String stateKey) {
            return null;
        }

        @Override
        public void put(
                PlatformType platform, String scopeKey, String stateKey, String stateValue) {}

        @Override
        public void delete(PlatformType platform, String scopeKey, String stateKey) {}

        @Override
        public List<StateItem> list(PlatformType platform, String scopeKey) {
            return Collections.emptyList();
        }
    }
}
