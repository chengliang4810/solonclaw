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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        assertThat(chunks.get(0)).hasSize(1992);
        assertThat(chunks.get(0)).endsWith(" (1/2)");
        assertThat(chunks.get(1)).isEqualTo(repeat("a", 15) + " (2/2)");
        assertThat(chunks).allMatch(chunk -> codePointLength(chunk) <= 2000);
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
    void shouldPreserveOutboundTextNewlinesForWeixinPayload() throws Exception {
        Method normalize =
                WeiXinChannelAdapter.class.getDeclaredMethod(
                        "normalizeOutboundTextForWeixin", String.class);
        normalize.setAccessible(true);

        assertThat((String) normalize.invoke(null, "第一行\n第二行")).isEqualTo("第一行\n第二行");
        assertThat((String) normalize.invoke(null, "第一行\r第二行")).isEqualTo("第一行\n第二行");
        assertThat((String) normalize.invoke(null, "第一行\r\n第二行")).isEqualTo("第一行\n第二行");
    }

    @Test
    void shouldPreserveWeixinMarkdownTablesAndCodeBlocks() throws Exception {
        Method format =
                WeiXinChannelAdapter.class.getDeclaredMethod("formatTextForDelivery", String.class);
        format.setAccessible(true);
        String table =
                "| Setting | Value |\n"
                        + "| --- | --- |\n"
                        + "| Timeout | 30s |\n"
                        + "| Retries | 3 |\n";
        String code = "## Snippet\n\n```python\nprint('hi')\n```";

        assertThat((String) format.invoke(null, "# Title\n\n## Plan\n\nUse **bold**."))
                .isEqualTo("# Title\n\n## Plan\n\nUse **bold**.");
        assertThat((String) format.invoke(null, table)).isEqualTo(table.trim());
        assertThat((String) format.invoke(null, code)).isEqualTo(code);
    }

    @Test
    void shouldFormatWeixinTextLikeReferenceBeforeSplitting() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method format =
                WeiXinChannelAdapter.class.getDeclaredMethod("formatTextForDelivery", String.class);
        format.setAccessible(true);
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);
        String longLine =
                "Here is a long issue template line with many copyable fields "
                        + "field_0=value_0 field_1=value_1 field_2=value_2 field_3=value_3 "
                        + "field_4=value_4 field_5=value_5 field_6=value_6 field_7=value_7 "
                        + "field_8=value_8";

        String blankText = (String) format.invoke(null, "a\n\n\nb");
        String wrappedText = (String) format.invoke(null, longLine);
        @SuppressWarnings("unchecked")
        List<String> blankChunks = (List<String>) split.invoke(adapter, blankText);
        @SuppressWarnings("unchecked")
        List<String> wrappedChunks = (List<String>) split.invoke(adapter, wrappedText);

        assertThat(blankText).isEqualTo("a\n\nb");
        assertThat(blankChunks).containsExactly("a", "b");
        assertThat(wrappedChunks).hasSize(1);
        assertThat(wrappedChunks.get(0)).contains("\n");
        assertThat(wrappedChunks.get(0).split("\\n"))
                .allMatch(line -> codePointLength(line) <= 120);
        assertThat(wrappedChunks.get(0).replace("\n", " "))
                .isEqualTo(longLine.replaceAll("\\s+", " "));
    }

    @Test
    void shouldPreserveWeixinWrapSpacingLikeReference() throws Exception {
        Method format =
                WeiXinChannelAdapter.class.getDeclaredMethod("formatTextForDelivery", String.class);
        format.setAccessible(true);

        String formatted =
                (String)
                        format.invoke(
                                null,
                                "hello    world    "
                                        + repeat("x ", 80).trim());

        assertThat(formatted).startsWith("hello    world    x");
        assertThat(formatted.split("\\n")).allMatch(line -> codePointLength(line) <= 120);
    }

    @Test
    void shouldNotWrapLongWeixinCodeBlockLines() throws Exception {
        Method format =
                WeiXinChannelAdapter.class.getDeclaredMethod("formatTextForDelivery", String.class);
        format.setAccessible(true);
        String command = "solonclaw " + repeat("--option=value ", 30).trim();
        String content = "```bash\n" + command + "\n```";

        assertThat((String) format.invoke(null, content)).isEqualTo(content);
    }

    @Test
    void shouldSplitShortChattyWeixinRepliesByDefault() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, "第一行\n第二行\n第三行");

        assertThat(chunks).containsExactly("第一行", "第二行", "第三行");
    }

    @Test
    void shouldKeepStructuredWeixinBlocksTogetherByDefault() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks =
                (List<String>)
                        split.invoke(
                                adapter,
                                "今天结论：\n"
                                        + "- 留存下降 3%\n"
                                        + "- 转化上涨 8%\n"
                                        + "- 主要问题在首日激活");

        assertThat(chunks)
                .containsExactly(
                        "今天结论：\n"
                                + "- 留存下降 3%\n"
                                + "- 转化上涨 8%\n"
                                + "- 主要问题在首日激活");
    }

    @Test
    void shouldKeepWeixinHeadingWithBodyTogetherByDefault() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, "## 结论\n这是正文");

        assertThat(chunks).containsExactly("## 结论\n这是正文");
    }

    @Test
    void shouldReturnNoWeixinChunksForEmptyText() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);
        AppConfig splitConfig = newConfig();
        splitConfig.getChannels().getWeixin().setSplitMultilineMessages(true);
        WeiXinChannelAdapter splitAdapter = newAdapter(splitConfig);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, "");
        @SuppressWarnings("unchecked")
        List<String> splitPerLineChunks = (List<String>) split.invoke(splitAdapter, "");

        assertThat(chunks).isEmpty();
        assertThat(splitPerLineChunks).isEmpty();
    }

    @Test
    void shouldSafelySplitLongWeixinCodeBlocks() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append("line_").append(i).append(" = ").append(i);
        }

        @SuppressWarnings("unchecked")
        List<String> chunks =
                (List<String>) split.invoke(adapter, "```java\n" + lines + "\n```");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> codePointLength(chunk) <= 2000);
        assertThat(chunks).allMatch(chunk -> chunk.startsWith("```java\n"));
        assertThat(chunks.get(0)).contains("line_145 = 145");
        assertThat(chunks.get(0)).doesNotContain("line_146 = 146");
        assertThat(chunks.get(0)).endsWith("\n``` (1/2)");
        assertThat(chunks.get(1)).startsWith("```java\nline_146 = 146");
        assertThat(chunks.get(1)).endsWith("\n``` (2/2)");
    }

    @Test
    void shouldSplitWeixinTextByUnicodeCodePoints() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, repeat("😀", 2001));

        assertThat(chunks).hasSize(2);
        assertThat(codePointLength(chunks.get(0))).isEqualTo(1992);
        assertThat(codePointLength(chunks.get(1))).isEqualTo(21);
        assertThat(chunks.get(0)).endsWith(" (1/2)");
        assertThat(chunks.get(1)).endsWith(" (2/2)");
    }

    @Test
    void shouldSplitLongWeixinPlainTextAtNaturalBoundaries() throws Exception {
        WeiXinChannelAdapter adapter = newAdapter();
        Method split =
                WeiXinChannelAdapter.class.getDeclaredMethod("splitTextForDelivery", String.class);
        split.setAccessible(true);
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 420; i++) {
            if (words.length() > 0) {
                words.append(' ');
            }
            words.append("word").append(i);
        }

        @SuppressWarnings("unchecked")
        List<String> chunks = (List<String>) split.invoke(adapter, words.toString());

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).endsWith("word251 (1/2)");
        assertThat(chunks.get(1)).startsWith("word252 ");
        assertThat(chunks).allMatch(chunk -> codePointLength(chunk) <= 2000);
    }

    @Test
    void shouldDispatchInboundOffThePollingThread() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        config.getChannels().getWeixin().setTextBatchDelaySeconds(0.05D);
        config.getChannels().getWeixin().setTextBatchSplitDelaySeconds(0.05D);

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
    void shouldDebounceRapidInboundTextMessagesByConversation() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        config.getChannels().getWeixin().setTextBatchDelaySeconds(0.05D);
        config.getChannels().getWeixin().setTextBatchSplitDelaySeconds(0.05D);

        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config));

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        texts.add(message.getText());
                        latch.countDown();
                    }
                });

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(adapter, inboundText("msg-1", "room-1", "wx-user", "第一段"));
        processInbound.invoke(adapter, inboundText("msg-2", "room-1", "wx-user", "第二段"));
        processInbound.invoke(adapter, inboundText("msg-3", "room-1", "wx-user", "第三段"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(250L);
        assertThat(texts).containsExactly("第一段\n第二段\n第三段");

        adapter.disconnect();
    }

    @Test
    void shouldDeduplicateRepeatedInboundTextContentFromSameSender() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        config.getChannels().getWeixin().setTextBatchDelaySeconds(0.05D);
        config.getChannels().getWeixin().setTextBatchSplitDelaySeconds(0.05D);

        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config));

        final CountDownLatch latch = new CountDownLatch(1);
        final List<String> texts = Collections.synchronizedList(new ArrayList<String>());
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        texts.add(message.getText());
                        latch.countDown();
                    }
                });

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(adapter, inboundText("msg-dup-1", "room-1", "wx-user", "重复内容"));
        processInbound.invoke(adapter, inboundText("msg-dup-2", "room-1", "wx-user", "重复内容"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(250L);
        assertThat(texts).containsExactly("重复内容");

        adapter.disconnect();
    }

    @Test
    void shouldUseStableConversationSourceKeyAcrossDifferentWeixinMessageIds() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        config.getChannels().getWeixin().setTextBatchDelaySeconds(0.01D);
        config.getChannels().getWeixin().setTextBatchSplitDelaySeconds(0.01D);

        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config));

        final CountDownLatch latch = new CountDownLatch(2);
        final List<String> sourceKeys = Collections.synchronizedList(new ArrayList<String>());
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        sourceKeys.add(message.sourceKey());
                        latch.countDown();
                    }
                });

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(adapter, inboundText("msg-source-1", "room-1", "wx-user", "第一条"));
        TimeUnit.MILLISECONDS.sleep(100L);
        processInbound.invoke(adapter, inboundText("msg-source-2", "room-1", "wx-user", "第二条"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sourceKeys).containsExactly("WEIXIN:room-1:wx-user", "WEIXIN:room-1:wx-user");

        adapter.disconnect();
    }

    @Test
    void shouldBoundRecentMessageIdsAndStillRecognizeRecentDuplicates() throws Throwable {
        WeiXinChannelAdapter adapter = newAdapter();
        Method isDuplicate =
                WeiXinChannelAdapter.class.getDeclaredMethod("isDuplicate", String.class);
        isDuplicate.setAccessible(true);
        Field recentMessageIds = WeiXinChannelAdapter.class.getDeclaredField("recentMessageIds");
        recentMessageIds.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Long> entries = (Map<String, Long>) recentMessageIds.get(adapter);
        assertThat((Boolean) invoke(isDuplicate, adapter, "msg-bound-oldest")).isFalse();
        TimeUnit.MILLISECONDS.sleep(2L);
        for (int i = 0; i < 512; i++) {
            assertThat((Boolean) invoke(isDuplicate, adapter, "msg-bound-" + i)).isFalse();
        }

        assertThat(entries).hasSizeLessThanOrEqualTo(512);
        assertThat(entries).doesNotContainKey("msg-bound-oldest");
        assertThat((Boolean) invoke(isDuplicate, adapter, "msg-bound-511")).isTrue();
        assertThat(entries).hasSizeLessThanOrEqualTo(512);
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

    private int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    private AppConfig newConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-weixin-dispatch-test").toFile();
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

    private ONode inboundText(String messageId, String roomId, String userId, String text) {
        return ONode.ofJson(
                "{"
                        + "\"from_user_id\":\""
                        + userId
                        + "\","
                        + "\"message_id\":\""
                        + messageId
                        + "\","
                        + "\"room_id\":\""
                        + roomId
                        + "\","
                        + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\""
                        + text
                        + "\"}}]"
                        + "}");
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
