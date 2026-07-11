package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.TestEnvironment;
import com.jimuqu.solon.claw.tool.runtime.CronAutoDeliveryContext;
import com.jimuqu.solon.claw.tool.runtime.MessagingTools;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MessagingToolsAttachmentTest {
    @Test
    void shouldListSendMessageTargetsWithCanonicalAction() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1:thread-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        String result =
                tools.sendMessage(
                        "list", null, null, null, null, Collections.<String>emptyList(), null);

        Map<?, ?> payload = (Map<?, ?>) org.noear.snack4.ONode.ofJson(result).toData();
        assertThat(payload.get("status")).isEqualTo("success");
        assertThat(payload.get("targets").toString()).contains("current_source").contains("chat-1");
        assertThat(payload.get("current").toString()).contains("thread-1");
        assertThat(payload.get("explicitTargetsAllowed")).isEqualTo(Boolean.FALSE);
        assertThat(env.memoryChannelAdapter.getLastRequest()).isNull();
    }

    @Test
    void shouldSkipSendMessageToCronAutoDeliveryTarget() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        CronAutoDeliveryContext.set(PlatformType.MEMORY, "chat-1", null);
        String result;
        try {
            result = tools.sendMessage(null, null, "重复内容", Collections.<String>emptyList(), null);
        } finally {
            CronAutoDeliveryContext.clear();
        }

        Map<?, ?> payload = (Map<?, ?>) org.noear.snack4.ONode.ofJson(result).toData();
        assertThat(payload.get("status")).isEqualTo("success");
        assertThat(payload.get("skipped")).isEqualTo(Boolean.TRUE);
        assertThat(payload.get("reason")).isEqualTo("cron_auto_delivery_duplicate_target");
        assertThat(env.memoryChannelAdapter.getLastRequest()).isNull();
    }

    @Test
    void shouldRedactSecretsFromSkippedCronTargetToolResult() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-ghp_messagingsource12345:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        CronAutoDeliveryContext.set(PlatformType.MEMORY, "chat-ghp_messagingtarget12345", null);
        String result;
        try {
            result =
                    tools.sendMessage(
                            "MEMORY",
                            "chat-ghp_messagingtarget12345",
                            "重复内容",
                            Collections.<String>emptyList(),
                            null);
        } finally {
            CronAutoDeliveryContext.clear();
        }

        assertThat(result)
                .contains("chat-ghp_***")
                .doesNotContain("ghp_messagingsource12345")
                .doesNotContain("ghp_messagingtarget12345");
        assertThat(env.memoryChannelAdapter.getLastRequest()).isNull();
    }

    @Test
    void shouldDeliverSendMessageToDifferentCronTarget() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        CronAutoDeliveryContext.set(PlatformType.MEMORY, "chat-1", null);
        try {
            tools.sendMessage("MEMORY", "chat-2", "额外投递", Collections.<String>emptyList(), null);
        } finally {
            CronAutoDeliveryContext.clear();
        }

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getChatId()).isEqualTo("chat-2");
        assertThat(request.getText()).isEqualTo("额外投递");
    }

    @Test
    void shouldDeliverMediaPathsAsAttachments() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        Path tempDir = new File(env.appConfig.getRuntime().getCacheDir(), "tool-media").toPath();
        Files.createDirectories(tempDir);
        File image = tempDir.resolve("demo.png").toFile();
        File voice = tempDir.resolve("note.silk").toFile();
        Files.write(image.toPath(), new byte[] {1, 2, 3});
        Files.write(voice.toPath(), new byte[] {4, 5, 6});

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            tools.sendMessage(
                    null, null, "请发送附件", Arrays.asList("demo.png", voice.getAbsolutePath()), null);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getText()).isEqualTo("请发送附件");
        assertThat(request.getAttachments()).hasSize(2);
        assertThat(request.getAttachments().get(0).getKind()).isEqualTo("image");
        assertThat(request.getAttachments().get(0).getLocalPath()).endsWith("demo.png");
        assertThat(request.getAttachments().get(0).getSizeBytes()).isEqualTo(3L);
        assertThat(request.getAttachments().get(1).getKind()).isEqualTo("voice");
        assertThat(request.getAttachments().get(1).getLocalPath()).endsWith("note.silk");
        assertThat(request.getAttachments().get(1).getSizeBytes()).isEqualTo(3L);
    }

    @Test
    void shouldSniffImageMimeFromCachedBytesBeforeSuffixOrHeader() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AttachmentCacheService attachmentCacheService = new AttachmentCacheService(env.appConfig);

        MessageAttachment attachment =
                attachmentCacheService.cacheBytes(
                        PlatformType.MEMORY,
                        null,
                        "discord_cached.webp",
                        "image/webp",
                        false,
                        null,
                        pngBytes());

        assertThat(attachment.getKind()).isEqualTo("image");
        assertThat(attachment.getMimeType()).isEqualTo("image/png");
    }

    @Test
    void shouldSniffImageMimeFromCachedFileBeforeSuffix() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        AttachmentCacheService attachmentCacheService = new AttachmentCacheService(env.appConfig);
        File image =
                new File(attachmentCacheService.platformDir(PlatformType.MEMORY), "real_png.webp");
        Files.createDirectories(image.getParentFile().toPath());
        Files.write(image.toPath(), pngBytes());

        MessageAttachment attachment =
                attachmentCacheService.fromMediaCacheFile(
                        PlatformType.MEMORY, image, null, false, null);

        assertThat(attachment.getKind()).isEqualTo("image");
        assertThat(attachment.getMimeType()).isEqualTo("image/png");
    }

    @Test
    void shouldAllowTextOnlyWithoutAttachments() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        tools.sendMessage(null, null, "纯文本", Collections.<String>emptyList(), null);

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getText()).isEqualTo("纯文本");
        assertThat(request.getAttachments()).isEmpty();
    }

    @Test
    void shouldRedactSecretsFromSuccessfulMessagingToolResultOnly() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:source-ghp_messagingsource12345:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        String result =
                tools.sendMessage(
                        "MEMORY",
                        "room-ghp_messagingchat12345",
                        "Authorization: Bearer ghp_messagingtext12345",
                        Collections.<String>emptyList(),
                        null);

        assertThat(result)
                .contains("Authorization: Bearer ***")
                .contains("room-ghp_***")
                .contains("source-ghp_***")
                .doesNotContain("ghp_messagingchat12345")
                .doesNotContain("ghp_messagingtext12345")
                .doesNotContain("ghp_messagingsource12345");
        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getChatId()).isEqualTo("room-ghp_messagingchat12345");
        assertThat(request.getText()).isEqualTo("Authorization: Bearer ghp_messagingtext12345");
    }

    @Test
    void shouldPassChannelExtrasJson() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        tools.sendMessage(
                null,
                null,
                "发卡片",
                Collections.<String>emptyList(),
                "{\"mode\":\"ai_card\",\"cardTemplateId\":\"tpl-1\",\"cardData\":{\"title\":\"demo\"}}");

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getPlatform().name()).isEqualTo("MEMORY");
        assertThat(request.getChannelExtras()).containsEntry("mode", "ai_card");
        assertThat(request.getChannelExtras()).containsEntry("cardTemplateId", "tpl-1");
        assertThat(((Map<?, ?>) request.getChannelExtras().get("cardData")).get("title"))
                .isEqualTo("demo");
    }

    @Test
    void shouldRedactSecretsFromMessagingToolErrors() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        String result =
                tools.sendMessage(
                        "unknown-ghp_1234567890abcdef",
                        "chat-1",
                        "测试",
                        Collections.<String>emptyList(),
                        null);

        Map<?, ?> payload = (Map<?, ?>) org.noear.snack4.ONode.ofJson(result).toData();
        assertThat(payload.get("status")).isEqualTo("error");
        assertThat(String.valueOf(payload.get("error")))
                .contains("unknown-ghp_***")
                .doesNotContain("ghp_1234567890abcdef");
    }

    @Test
    void shouldResolveGeneratedPdfFromRuntimeCachePdfDir() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        File pdfDir = new File(env.appConfig.getRuntime().getCacheDir(), "pdf");
        assertThat(pdfDir.mkdirs() || pdfDir.isDirectory()).isTrue();
        File pdf = new File(pdfDir, "solon_research_summary.pdf");
        Files.write(pdf.toPath(), new byte[] {1, 2, 3});

        tools.sendMessage(
                null,
                null,
                "发送报告",
                Collections.singletonList("/app/solon_research_summary.pdf"),
                null);

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getAttachments()).hasSize(1);
        assertThat(request.getAttachments().get(0).getLocalPath()).isEqualTo(pdf.getAbsolutePath());
        assertThat(request.getAttachments().get(0).getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void shouldImportGeneratedPdfFromRuntimeRootIntoMediaCache() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        File pdf = new File(env.appConfig.getRuntime().getHome(), "jimuqu_agent_report.pdf");
        Files.write(pdf.toPath(), new byte[] {1, 2, 3});

        tools.sendMessage(
                null, null, "发送报告", Collections.singletonList(pdf.getAbsolutePath()), null);

        DeliveryRequest request = env.memoryChannelAdapter.getLastRequest();
        assertThat(request.getAttachments()).hasSize(1);
        assertThat(request.getAttachments().get(0).getLocalPath())
                .contains(File.separator + "cache" + File.separator + "media" + File.separator);
        assertThat(request.getAttachments().get(0).getOriginalName())
                .endsWith("jimuqu_agent_report.pdf");
        assertThat(request.getAttachments().get(0).getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void shouldRejectRuntimeInternalFilesAsAttachments() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        MessagingTools tools =
                new MessagingTools(
                        env.deliveryService,
                        "MEMORY:chat-1:user-1",
                        new AttachmentCacheService(env.appConfig),
                        env.appConfig);

        File config = new File(env.appConfig.getRuntime().getHome(), "config.yml");
        Files.write(config.toPath(), "secret: value".getBytes("UTF-8"));

        assertThatThrownBy(
                        () ->
                                tools.sendMessage(
                                        null,
                                        null,
                                        "发送配置",
                                        Collections.singletonList(config.getAbsolutePath()),
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside runtime cache");
    }

    private byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }
}
