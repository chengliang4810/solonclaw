package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.gateway.platform.weixin.WeiXinChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
                (String) format.invoke(null, "hello    world    " + repeat("x ", 80).trim());

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
                                adapter, "今天结论：\n" + "- 留存下降 3%\n" + "- 转化上涨 8%\n" + "- 主要问题在首日激活");

        assertThat(chunks)
                .containsExactly("今天结论：\n" + "- 留存下降 3%\n" + "- 转化上涨 8%\n" + "- 主要问题在首日激活");
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
        List<String> chunks = (List<String>) split.invoke(adapter, "```java\n" + lines + "\n```");

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
    void shouldKeepRepeatedInboundTextWhenPlatformMessageIdsDiffer() throws Exception {
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
        assertThat(texts).containsExactly("重复内容\n重复内容");

        adapter.disconnect();
    }

    @Test
    void shouldDeduplicateRepeatedInboundTextWithoutPlatformMessageId() throws Exception {
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
        CountDownLatch latch = new CountDownLatch(1);
        List<String> texts = Collections.synchronizedList(new ArrayList<String>());
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(GatewayMessage message) {
                        texts.add(message.getText());
                        latch.countDown();
                    }
                });

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(adapter, inboundText("", "room-1", "wx-user", "重复内容"));
        processInbound.invoke(adapter, inboundText("", "room-1", "wx-user", "重复内容"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(250L);
        assertThat(texts).containsExactly("重复内容");

        adapter.disconnect();
    }

    /** 缺少平台消息标识时，相同文字附带的不同附件仍必须分别进入入站处理。 */
    @Test
    void shouldKeepDistinctAttachmentsWithSameTextWithoutPlatformMessageId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/image-a",
                exchange -> {
                    byte[] response = "image-a".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/image-b",
                exchange -> {
                    byte[] response = "image-b".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();

        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        WeiXinChannelAdapter adapter = newAdapter(config);
        CountDownLatch latch = new CountDownLatch(2);
        List<GatewayMessage> messages =
                Collections.synchronizedList(new ArrayList<GatewayMessage>());
        adapter.setInboundMessageHandler(
                message -> {
                    messages.add(message);
                    latch.countDown();
                });

        try {
            Method processInbound =
                    WeiXinChannelAdapter.class.getDeclaredMethod(
                            "processInboundMessage", ONode.class);
            processInbound.setAccessible(true);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            processInbound.invoke(
                    adapter,
                    inboundTextWithImage("", "room-1", "wx-user", "查看附件", baseUrl + "/image-a"));
            processInbound.invoke(
                    adapter,
                    inboundTextWithImage("", "room-1", "wx-user", "查看附件", baseUrl + "/image-b"));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(messages).hasSize(2);
            assertThat(messages)
                    .allSatisfy(message -> assertThat(message.getAttachments()).hasSize(1));
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    @Test
    void shouldDispatchPendingTextBeforeFollowingAttachmentMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/image",
                exchange -> {
                    byte[] response = "image-bytes".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();

        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        config.getChannels().getWeixin().setTextBatchDelaySeconds(2.0D);
        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config));
        CountDownLatch latch = new CountDownLatch(2);
        List<GatewayMessage> messages =
                Collections.synchronizedList(new ArrayList<GatewayMessage>());
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(GatewayMessage message) {
                        messages.add(message);
                        latch.countDown();
                    }
                });

        try {
            Method processInbound =
                    WeiXinChannelAdapter.class.getDeclaredMethod(
                            "processInboundMessage", ONode.class);
            processInbound.setAccessible(true);
            processInbound.invoke(adapter, inboundText("msg-text", "room-1", "wx-user", "请分析附件"));
            processInbound.invoke(
                    adapter,
                    inboundImage(
                            "msg-image",
                            "room-1",
                            "wx-user",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/image"));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getText()).isEqualTo("请分析附件");
            assertThat(messages.get(0).getAttachments()).isEmpty();
            assertThat(messages.get(1).getAttachments()).hasSize(1);
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    @Test
    void shouldDispatchSameInboundTextFromSameSenderAcrossDifferentChats() throws Exception {
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

        final CountDownLatch latch = new CountDownLatch(2);
        final List<String> chatIds = Collections.synchronizedList(new ArrayList<String>());
        adapter.setInboundMessageHandler(
                new InboundMessageHandler() {
                    @Override
                    public void handle(com.jimuqu.solon.claw.core.model.GatewayMessage message) {
                        chatIds.add(message.getChatId());
                        latch.countDown();
                    }
                });

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(adapter, inboundText("msg-chat-1", "room-1", "wx-user", "重复内容"));
        processInbound.invoke(adapter, inboundText("msg-chat-2", "room-2", "wx-user", "重复内容"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(chatIds).containsExactlyInAnyOrder("room-1", "room-2");

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

    @Test
    void shouldStartTypingBeforeBatchAndKeepItUntilHandlerCompletes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch firstTyping = new CountDownLatch(1);
        CountDownLatch refreshedTyping = new CountDownLatch(2);
        CountDownLatch stoppedTyping = new CountDownLatch(1);
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    byte[] response =
                            "{\"typing_ticket\":\"ticket-1\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendtyping",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    int status = ONode.ofJson(body).get("status").getInt();
                    if (status == 1) {
                        firstTyping.countDown();
                        refreshedTyping.countDown();
                    } else if (status == 2) {
                        stoppedTyping.countDown();
                    }
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();

        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setToken("token-1");
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.getChannels().getWeixin().setTextBatchDelaySeconds(2.0D);
        WeiXinChannelAdapter adapter = newAdapter(config);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        adapter.setInboundMessageHandler(
                message -> {
                    handlerStarted.countDown();
                    try {
                        TimeUnit.MILLISECONDS.sleep(2500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("模拟处理异常");
                });

        try {
            Method processInbound =
                    WeiXinChannelAdapter.class.getDeclaredMethod(
                            "processInboundMessage", ONode.class);
            processInbound.setAccessible(true);
            processInbound.invoke(adapter, inboundText("msg-typing", "", "wx-user", "hello"));

            assertThat(firstTyping.await(1500L, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(handlerStarted.getCount()).isEqualTo(1L);
            assertThat(handlerStarted.await(3L, TimeUnit.SECONDS)).isTrue();
            assertThat(refreshedTyping.await(3L, TimeUnit.SECONDS)).isTrue();
            assertThat(stoppedTyping.await(3L, TimeUnit.SECONDS)).isTrue();
            Field lifecycles =
                    WeiXinChannelAdapter.class.getDeclaredField("activeTypingLifecycles");
            lifecycles.setAccessible(true);
            assertThat((Map<?, ?>) lifecycles.get(adapter)).isEmpty();
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 合并同一私聊文本批次时，输入状态心跳必须使用最近一条消息的上下文 token。 */
    @Test
    void shouldUpdateTypingLifecycleContextTokenWhenMergingTextBatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();

        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setToken("token-1");
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.getChannels().getWeixin().setTextBatchDelaySeconds(5.0D);
        WeiXinChannelAdapter adapter = newAdapter(config);

        try {
            Method processInbound =
                    WeiXinChannelAdapter.class.getDeclaredMethod(
                            "processInboundMessage", ONode.class);
            processInbound.setAccessible(true);
            processInbound.invoke(
                    adapter,
                    inboundTextWithContext("msg-token-1", "", "wx-user", "第一段", "context-token-1"));
            processInbound.invoke(
                    adapter,
                    inboundTextWithContext("msg-token-2", "", "wx-user", "第二段", "context-token-2"));

            @SuppressWarnings("unchecked")
            Map<String, Object> lifecycles =
                    (Map<String, Object>) fieldValue(adapter, "activeTypingLifecycles");
            Object lifecycle = lifecycles.get("wx-user");
            Field contextToken = lifecycle.getClass().getDeclaredField("contextToken");
            contextToken.setAccessible(true);
            assertThat(contextToken.get(lifecycle)).isEqualTo("context-token-2");
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 当前消息缺少 token 时，typing ticket 应复用该用户已持久化的上下文 token。 */
    @Test
    void shouldUsePersistedContextTokenWhenFetchingTypingTicket() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<String>();
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            "{\"typing_ticket\":\"ticket-persisted\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ChannelStateRepository repository =
                new InMemoryChannelStateRepository() {
                    @Override
                    public String get(PlatformType platform, String scopeKey, String stateKey) {
                        return "wx-bot:wx-user".equals(scopeKey) ? "persisted-token" : null;
                    }
                };
        WeiXinChannelAdapter adapter =
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        repository,
                        new AttachmentCacheService(config));

        try {
            invokePrivate(adapter, "maybeFetchTypingTicket", "wx-user", "");
            assertThat(ONode.ofJson(requestBody.get()).get("context_token").getString())
                    .isEqualTo("persisted-token");
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 瞬时传输失败不得丢弃仍有效的 ticket，下一次心跳应直接复用。 */
    @Test
    void shouldKeepTypingTicketAfterTransientSendFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger configRequests = new AtomicInteger();
        AtomicInteger typingRequests = new AtomicInteger();
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    configRequests.incrementAndGet();
                    byte[] response =
                            "{\"typing_ticket\":\"ticket-stable\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendtyping",
                exchange -> {
                    int attempt = typingRequests.incrementAndGet();
                    if (attempt == 1) {
                        try {
                            TimeUnit.SECONDS.sleep(2L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try {
                        exchange.getResponseBody().write(response);
                    } catch (java.io.IOException ignored) {
                        // 首次请求由客户端超时关闭，模拟瞬时传输失败。
                    }
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig();
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WeiXinChannelAdapter adapter = newAdapter(config);

        try {
            invokePrivate(adapter, "maybeFetchTypingTicket", "wx-user", "context-token");
            assertThat((Boolean) invokePrivate(adapter, "sendTyping", "wx-user", 1)).isFalse();
            invokePrivate(adapter, "maybeFetchTypingTicket", "wx-user", "context-token");
            assertThat((Boolean) invokePrivate(adapter, "sendTyping", "wx-user", 1)).isTrue();
            assertThat(configRequests.get()).isEqualTo(1);
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 首次取票失败后，下一次心跳应重新取票并恢复输入状态。 */
    @Test
    void shouldRecoverTypingOnNextHeartbeatAfterInitialTicketFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger configRequests = new AtomicInteger();
        CountDownLatch typingStarted = new CountDownLatch(1);
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    int attempt = configRequests.incrementAndGet();
                    byte[] response =
                            (attempt == 1 ? "{}" : "{\"typing_ticket\":\"ticket-recovered\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendtyping",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (ONode.ofJson(body).get("status").getInt() == 1) {
                        typingStarted.countDown();
                    }
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig();
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WeiXinChannelAdapter adapter = newAdapter(config);

        try {
            Object lifecycle = invokePrivate(adapter, "startTypingLifecycle", "wx-user", "token");

            assertThat(typingStarted.await(3L, TimeUnit.SECONDS)).isTrue();
            assertThat(configRequests.get()).isGreaterThanOrEqualTo(2);
            invokePrivate(adapter, "stopTypingLifecycle", lifecycle, false);
        } finally {
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** stop 与 ticket 刷新并发时，已停止的心跳不得在刷新完成后补发 START。 */
    @Test
    void shouldNotSendTypingStartAfterLifecycleStopsDuringTicketFetch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch fetchingTicket = new CountDownLatch(1);
        CountDownLatch releaseTicket = new CountDownLatch(1);
        AtomicInteger startRequests = new AtomicInteger();
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    fetchingTicket.countDown();
                    try {
                        releaseTicket.await(3L, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response =
                            "{\"typing_ticket\":\"ticket-race\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendtyping",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (ONode.ofJson(body).get("status").getInt() == 1) {
                        startRequests.incrementAndGet();
                    }
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig();
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WeiXinChannelAdapter adapter = newAdapter(config);

        try {
            Object lifecycle = invokePrivate(adapter, "startTypingLifecycle", "wx-user", "token");
            assertThat(fetchingTicket.await(2L, TimeUnit.SECONDS)).isTrue();
            Thread stopper =
                    new Thread(
                            () -> {
                                try {
                                    invokePrivate(adapter, "stopTypingLifecycle", lifecycle, false);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            });
            stopper.start();
            releaseTicket.countDown();
            stopper.join(3_000L);
            TimeUnit.MILLISECONDS.sleep(200L);
            assertThat(startRequests.get()).isZero();
        } finally {
            releaseTicket.countDown();
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 已进入网络请求的 START 必须先完成，STOP 随后发送，避免 STOP 后出现迟到的 START。 */
    @Test
    void shouldSerializeInFlightTypingStartBeforeStop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<Integer>());
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    byte[] response =
                            "{\"typing_ticket\":\"ticket-serialized\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendtyping",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    int status = ONode.ofJson(body).get("status").getInt();
                    if (status == 1) {
                        startEntered.countDown();
                        try {
                            releaseStart.await(3L, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    statuses.add(status);
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig();
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WeiXinChannelAdapter adapter = newAdapter(config);

        try {
            Object lifecycle = invokePrivate(adapter, "startTypingLifecycle", "wx-user", "token");
            assertThat(startEntered.await(2L, TimeUnit.SECONDS)).isTrue();
            Thread stopper =
                    new Thread(
                            () -> {
                                try {
                                    invokePrivate(adapter, "stopTypingLifecycle", lifecycle, false);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            });
            stopper.start();
            TimeUnit.MILLISECONDS.sleep(100L);
            assertThat(stopper.isAlive()).isTrue();
            releaseStart.countDown();
            stopper.join(3_000L);
            assertThat(stopper.isAlive()).isFalse();
            assertThat(statuses).containsExactly(1, 2);
        } finally {
            releaseStart.countDown();
            adapter.disconnect();
            server.stop(0);
        }
    }

    /** 慢用户的 sendtyping 请求不得阻塞其他私聊用户及时显示输入状态。 */
    @Test
    void shouldKeepTypingResponsiveAcrossUsersWhenSendTypingIsSlow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        CountDownLatch typingStarted = new CountDownLatch(2);
        server.createContext(
                "/ilink/bot/getconfig",
                exchange -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(400L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response =
                            ("{\"typing_ticket\":\"ticket-" + System.nanoTime() + "\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext(
                "/ilink/bot/sendtyping",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (ONode.ofJson(body).get("status").getInt() == 1) {
                        typingStarted.countDown();
                        try {
                            TimeUnit.MILLISECONDS.sleep(1200L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try {
                        exchange.getResponseBody().write(response);
                    } catch (java.io.IOException ignored) {
                        // 客户端可能在请求超时后关闭连接。
                    }
                    exchange.close();
                });
        server.start();
        AppConfig config = newConfig();
        config.getChannels()
                .getWeixin()
                .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WeiXinChannelAdapter adapter = newAdapter(config);

        try {
            Object first = invokePrivate(adapter, "startTypingLifecycle", "wx-user-1", "token-1");
            Object second = invokePrivate(adapter, "startTypingLifecycle", "wx-user-2", "token-2");

            assertThat(typingStarted.await(1000L, TimeUnit.MILLISECONDS)).isTrue();
            invokePrivate(adapter, "stopTypingLifecycle", first, false);
            invokePrivate(adapter, "stopTypingLifecycle", second, false);
        } finally {
            adapter.disconnect();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void shouldPreserveQuotedPlainTextContext() throws Exception {
        AppConfig config = newConfig();
        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setAccountId("wx-bot");
        config.getChannels().getWeixin().setGroupPolicy("open");
        config.getChannels().getWeixin().setTextBatchDelaySeconds(0.01D);
        WeiXinChannelAdapter adapter = newAdapter(config);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedText = new AtomicReference<String>();
        adapter.setInboundMessageHandler(
                message -> {
                    receivedText.set(message.getText());
                    latch.countDown();
                });

        try {
            Method processInbound =
                    WeiXinChannelAdapter.class.getDeclaredMethod(
                            "processInboundMessage", ONode.class);
            processInbound.setAccessible(true);
            processInbound.invoke(
                    adapter,
                    ONode.ofJson(
                            "{"
                                    + "\"from_user_id\":\"wx-user\","
                                    + "\"message_id\":\"msg-quote\","
                                    + "\"room_id\":\"room-1\","
                                    + "\"item_list\":[{\"type\":1,"
                                    + "\"text_item\":{\"text\":\"当前问题\"},"
                                    + "\"ref_msg\":{\"title\":\"张三\","
                                    + "\"message_item\":{\"type\":1,"
                                    + "\"text_item\":{\"text\":\"被引用内容\"}}}}]}"));

            assertThat(latch.await(5L, TimeUnit.SECONDS)).isTrue();
            assertThat(receivedText.get()).isEqualTo("[引用: 张三 | 被引用内容]\n当前问题");
        } finally {
            adapter.disconnect();
        }
    }

    @Test
    void shouldRejectLateInboundAndExecutorRecreationAfterDisconnect() throws Throwable {
        WeiXinChannelAdapter adapter = newAdapter();
        adapter.disconnect();
        AtomicReference<GatewayMessage> received = new AtomicReference<GatewayMessage>();
        adapter.setInboundMessageHandler(received::set);

        Method processInbound =
                WeiXinChannelAdapter.class.getDeclaredMethod("processInboundMessage", ONode.class);
        processInbound.setAccessible(true);
        processInbound.invoke(adapter, inboundText("msg-late", "room-1", "wx-user", "late"));

        Method enqueue =
                WeiXinChannelAdapter.class.getDeclaredMethod(
                        "enqueueTextBatch",
                        GatewayMessage.class,
                        String.class,
                        String.class,
                        String.class);
        enqueue.setAccessible(true);
        enqueue.invoke(
                adapter,
                new GatewayMessage(PlatformType.WEIXIN, "room-1", "wx-user", "late"),
                "group",
                "room-1",
                "context-token");

        for (String methodName :
                List.of(
                        "ensureInboundExecutor",
                        "ensureTextBatchExecutor",
                        "ensureTypingHeartbeatExecutor")) {
            Method ensure = WeiXinChannelAdapter.class.getDeclaredMethod(methodName);
            ensure.setAccessible(true);
            assertThatThrownBy(() -> invoke(ensure, adapter))
                    .isInstanceOf(RejectedExecutionException.class);
        }

        assertThat(received.get()).isNull();
        assertThat(fieldValue(adapter, "inboundExecutor")).isNull();
        assertThat(fieldValue(adapter, "textBatchExecutor")).isNull();
        assertThat(fieldValue(adapter, "typingHeartbeatExecutor")).isNull();
        assertThat((Map<?, ?>) fieldValue(adapter, "pendingTextBatches")).isEmpty();
        assertThat((Map<?, ?>) fieldValue(adapter, "pendingTextBatchTasks")).isEmpty();
        assertThat((Map<?, ?>) fieldValue(adapter, "activeTypingLifecycles")).isEmpty();
    }

    private Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** 按方法名调用适配器私有方法，供 typing 生命周期回归测试复用。 */
    private Object invokePrivate(WeiXinChannelAdapter adapter, String name, Object... args)
            throws Exception {
        for (Method method : WeiXinChannelAdapter.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                try {
                    return method.invoke(adapter, args);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Exception) {
                        throw (Exception) e.getCause();
                    }
                    throw e;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** 读取适配器内部状态，验证断连后不会遗留或重建执行资源。 */
    private Object fieldValue(WeiXinChannelAdapter adapter, String fieldName) throws Exception {
        Field field = WeiXinChannelAdapter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(adapter);
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
                .setStateDb(
                        new File(new File(workspaceHome, "data"), "state.db").getAbsolutePath());
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

    /** 构造带图片附件的微信入站消息，用于验证文本批处理与媒体即时分派的先后顺序。 */
    private ONode inboundImage(String messageId, String roomId, String userId, String fullUrl) {
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
                        + "\"item_list\":[{\"type\":2,\"image_item\":{\"media\":{\"full_url\":\""
                        + fullUrl
                        + "\"}}}]"
                        + "}");
    }

    /** 构造同时携带文本和图片附件的微信入站消息，用于验证无消息标识时的附件去重边界。 */
    private ONode inboundTextWithImage(
            String messageId, String roomId, String userId, String text, String fullUrl) {
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
                        + "\"}},{\"type\":2,\"image_item\":{\"media\":{\"full_url\":\""
                        + fullUrl
                        + "\"}}}]"
                        + "}");
    }

    /** 构造携带上下文 token 的微信文本消息，用于验证输入状态心跳的批次上下文更新。 */
    private ONode inboundTextWithContext(
            String messageId, String roomId, String userId, String text, String contextToken) {
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
                        + "\"context_token\":\""
                        + contextToken
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
