package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.qqbot.QQBotChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.weixin.WeiXinChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.yuanbao.YuanbaoChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.lark.oapi.core.request.EventReq;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

public class DomesticChannelEnhancementTest {
    @Test
    void shouldParseQqbotInboundTextAndPlatformAsrText() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage text =
                adapter.parse(
                        "{\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"m1\",\"openid\":\"user-a\",\"content\":\"hello\"}}");
        GatewayMessage voice =
                adapter.parse(
                        "{\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"d\":{\"id\":\"m2\",\"group_openid\":\"group-a\",\"author\":{\"user_openid\":\"user-a\"},\"asr_refer_text\":\"语音文本\"}}");

        assertThat(text.getText()).isEqualTo("hello");
        assertThat(text.getChatId()).isEqualTo("user-a");
        assertThat(voice.getChatType()).isEqualTo("group");
        assertThat(voice.getText()).isEqualTo("语音文本");
    }

    @Test
    void shouldParseQqbotQuotedMessageTextAndAttachments() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{"
                                + "\"t\":\"GROUP_AT_MESSAGE_CREATE\","
                                + "\"d\":{"
                                + "\"id\":\"m3\","
                                + "\"group_openid\":\"group-a\","
                                + "\"author\":{\"user_openid\":\"user-a\"},"
                                + "\"message_type\":103,"
                                + "\"content\":\"请看这个\","
                                + "\"msg_elements\":[{"
                                + "\"content\":\"原消息\","
                                + "\"attachments\":[{"
                                + "\"content_type\":\"application/pdf\","
                                + "\"url\":\"https://cdn.qq/report\","
                                + "\"filename\":\"quarterly-report.pdf\""
                                + "}]"
                                + "}]"
                                + "}"
                                + "}");

        assertThat(message.getText())
                .isEqualTo("[Quoted message]:\n原消息\n\n请看这个");
        assertThat(message.getAttachments()).hasSize(1);
        assertThat(message.getAttachments().get(0).isFromQuote()).isTrue();
        assertThat(message.getAttachments().get(0).getOriginalName())
                .isEqualTo("quarterly-report.pdf");
        assertThat(message.getAttachments().get(0).getKind()).isEqualTo("file");
    }

    @Test
    void shouldSurfaceQqbotImageOnlyQuoteAsMessageContext() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{"
                                + "\"t\":\"C2C_MESSAGE_CREATE\","
                                + "\"d\":{"
                                + "\"id\":\"m4\","
                                + "\"openid\":\"user-a\","
                                + "\"message_type\":103,"
                                + "\"msg_elements\":[{"
                                + "\"attachments\":[{"
                                + "\"content_type\":\"image/png\","
                                + "\"url\":\"https://cdn.qq/image\","
                                + "\"filename\":\"screen.png\""
                                + "}]"
                                + "}]"
                                + "}"
                                + "}");

        assertThat(message.getText()).isEqualTo("[Quoted message]: (image)");
        assertThat(message.getAttachments()).hasSize(1);
        assertThat(message.getAttachments().get(0).getKind()).isEqualTo("image");
        assertThat(message.getAttachments().get(0).isFromQuote()).isTrue();
    }

    @Test
    void shouldParseQqbotMainAttachmentsWithoutText() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{"
                                + "\"t\":\"C2C_MESSAGE_CREATE\","
                                + "\"d\":{"
                                + "\"id\":\"m5\","
                                + "\"openid\":\"user-a\","
                                + "\"asr_refer_text\":\"语音转写\","
                                + "\"attachments\":[{"
                                + "\"content_type\":\"audio/silk\","
                                + "\"url\":\"https://cdn.qq/voice\","
                                + "\"filename\":\"voice.silk\""
                                + "}]"
                                + "}"
                                + "}");

        assertThat(message.getText()).isEqualTo("语音转写");
        assertThat(message.getAttachments()).hasSize(1);
        assertThat(message.getAttachments().get(0).getKind()).isEqualTo("voice");
        assertThat(message.getAttachments().get(0).isFromQuote()).isFalse();
        assertThat(message.getAttachments().get(0).getTranscribedText()).isEqualTo("语音转写");
    }

    @Test
    void shouldParseQqbotQuotedVoiceTranscriptOnce() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{"
                                + "\"t\":\"C2C_MESSAGE_CREATE\","
                                + "\"d\":{"
                                + "\"id\":\"m6\","
                                + "\"openid\":\"user-a\","
                                + "\"message_type\":103,"
                                + "\"msg_elements\":[{"
                                + "\"asr_refer_text\":\"引用语音文本\","
                                + "\"attachments\":[{"
                                + "\"content_type\":\"audio/silk\","
                                + "\"url\":\"https://cdn.qq/quoted-voice\","
                                + "\"filename\":\"quoted.silk\""
                                + "}]"
                                + "}]"
                                + "}"
                                + "}");

        assertThat(message.getText()).isEqualTo("[Quoted message]:\n引用语音文本");
        assertThat(message.getAttachments()).hasSize(1);
        assertThat(message.getAttachments().get(0).isFromQuote()).isTrue();
        assertThat(message.getAttachments().get(0).getTranscribedText()).isEqualTo("引用语音文本");
    }

    @Test
    void shouldBuildQqbotDangerousApprovalInlineKeyboard() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.QQBOT);
        request.setChatId("user-a");
        request.setChatType("dm");
        request.setThreadId("m1");
        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        extras.put("approvalId", "approval-123");
        extras.put("approvalCommand", "rm -rf runtime/cache");
        extras.put("approvalDescription", "recursive delete");
        extras.put("approvalToolName", "execute_shell");
        extras.put("approvalAllowAlways", Boolean.TRUE);
        request.setChannelExtras(extras);

        ONode body = adapter.buildApprovalBody(request);

        assertThat(body.get("msg_type").getInt()).isEqualTo(2);
        assertThat(body.get("markdown").get("content").getString()).contains("命令执行审批");
        assertThat(body.get("markdown").get("content").getString()).contains("rm -rf runtime/cache");
        assertThat(body.get("content").getString()).isNull();
        assertThat(body.get("msg_id").getString()).isEqualTo("m1");
        ONode buttons = body.get("keyboard").get("content").get("rows").get(0).get("buttons");
        assertThat(((List<?>) buttons.toData()).size()).isEqualTo(3);
        assertThat(buttons.get(0).get("action").get("data").getString())
                .isEqualTo("approve:approval-123:allow-once");
        assertThat(buttons.get(1).get("render_data").get("label").getString())
                .isEqualTo("⭐ 始终允许");
        assertThat(buttons.get(2).get("action").get("data").getString())
                .isEqualTo("approve:approval-123:deny");
        assertThat(buttons.get(2).get("group_id").getString()).isEqualTo("approval");
    }

    @Test
    void shouldBuildQqbotNativeApprovalKeyboardWithJimuquChoices() {
        AppConfig config = new AppConfig();
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.QQBOT);
        request.setChatId("user-a");
        request.setChatType("dm");
        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        extras.put("approvalId", "approval-456");
        extras.put("approvalAllowAlways", Boolean.FALSE);
        request.setChannelExtras(extras);

        ONode body = adapter.buildApprovalBody(request);

        ONode buttons = body.get("keyboard").get("content").get("rows").get(0).get("buttons");
        assertThat(((List<?>) buttons.toData()).size()).isEqualTo(3);
        assertThat(buttons.get(1).get("render_data").get("label").getString())
                .isEqualTo("⭐ 始终允许");
        assertThat(buttons.get(1).get("action").get("data").getString())
                .isEqualTo("approve:approval-456:allow-always");
    }

    @Test
    void shouldBuildQqbotPlainTextBodyWhenMarkdownDisabled() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setMarkdownSupport(false);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.QQBOT);
        request.setChatId("user-a");
        request.setChatType("dm");
        request.setText("纯文本审批");
        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", DangerousCommandApprovalService.DELIVERY_MODE_APPROVAL_CARD);
        extras.put("approvalId", "approval-plain");
        request.setChannelExtras(extras);

        ONode body = adapter.buildApprovalBody(request);

        assertThat(body.get("msg_type").getInt()).isEqualTo(0);
        assertThat(body.get("content").getString()).isEqualTo("纯文本审批");
        assertThat(body.get("markdown").isNull()).isTrue();
        assertThat(body.get("keyboard").get("content").get("rows").get(0).get("buttons").get(0)
                        .get("action")
                        .get("data")
                        .getString())
                .isEqualTo("approve:approval-plain:allow-once");
    }

    @Test
    void shouldParseQqbotInteractionApprovalButtonsAsCommands() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage approve =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-1\",\"scene\":\"group\",\"group_openid\":\"group-a\",\"operator\":{\"openid\":\"user-a\"},\"resolved\":{\"button_data\":\"approve:approval-123:allow-always\"}}}");
        GatewayMessage deny =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-2\",\"chat_type\":2,\"user_openid\":\"user-b\",\"data\":{\"resolved\":{\"button_data\":\"approve:approval-123:deny\"}}}}");

        assertThat(approve.getText()).isEqualTo("/approve approval-123 always");
        assertThat(approve.getPlatform()).isEqualTo(PlatformType.QQBOT);
        assertThat(approve.getChatType()).isEqualTo("group");
        assertThat(approve.getChatId()).isEqualTo("group-a");
        assertThat(approve.getUserId()).isEqualTo("user-a");
        assertThat(approve.getThreadId()).isEqualTo("int-1");
        assertThat(deny.getText()).isEqualTo("/deny approval-123");
        assertThat(deny.getChatType()).isEqualTo("dm");
        assertThat(deny.getChatId()).isEqualTo("user-b");
    }

    @Test
    void shouldRejectUnsafeQqbotApprovalButtonSelectors() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage separator =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-unsafe\",\"chat_type\":2,\"user_openid\":\"user-c\",\"resolved\":{\"button_data\":\"approve:approval-123;always:allow-always\"}}}");
        GatewayMessage pipe =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-unsafe2\",\"chat_type\":2,\"user_openid\":\"user-c\",\"resolved\":{\"button_data\":\"approve:approval-123|always:deny\"}}}");
        GatewayMessage colon =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-unsafe3\",\"chat_type\":2,\"user_openid\":\"user-c\",\"resolved\":{\"button_data\":\"approve:approval:123:allow-once\"}}}");

        assertThat(separator).isNull();
        assertThat(pipe).isNull();
        assertThat(colon).isNull();
    }

    @Test
    void shouldIgnoreQqbotNonJimuquApprovalDecision() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-3\",\"chat_type\":2,\"user_openid\":\"user-c\",\"resolved\":{\"button_data\":\"approve:approval-123:allow-session\"}}}");

        assertThat(message).isNull();
    }

    @Test
    void shouldBuildQqbotUpdatePromptInlineKeyboard() {
        AppConfig config = new AppConfig();
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);
        DeliveryRequest request = new DeliveryRequest();
        request.setPlatform(PlatformType.QQBOT);
        request.setChatId("user-a");
        request.setChatType("dm");
        request.setThreadId("m1");
        request.setText("是否继续升级？");
        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", QQBotChannelAdapter.DELIVERY_MODE_UPDATE_PROMPT);
        extras.put("updateDefault", "y");
        request.setChannelExtras(extras);

        ONode body = adapter.buildUpdatePromptBody(request);

        assertThat(body.get("msg_type").getInt()).isEqualTo(2);
        assertThat(body.get("markdown").get("content").getString()).contains("更新需要确认");
        assertThat(body.get("markdown").get("content").getString()).contains("是否继续升级？");
        assertThat(body.get("markdown").get("content").getString()).contains("默认: y");
        assertThat(body.get("msg_id").getString()).isEqualTo("m1");
        ONode buttons = body.get("keyboard").get("content").get("rows").get(0).get("buttons");
        assertThat(((List<?>) buttons.toData()).size()).isEqualTo(2);
        assertThat(buttons.get(0).get("action").get("data").getString())
                .isEqualTo("update_prompt:y");
        assertThat(buttons.get(1).get("action").get("data").getString())
                .isEqualTo("update_prompt:n");
    }

    @Test
    void shouldWriteQqbotUpdatePromptInteractionResponse(@TempDir File runtimeHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-1\",\"user_openid\":\"user-a\",\"resolved\":{\"button_data\":\"update_prompt:y\"}}}");

        assertThat(message).isNull();
        assertThat(new File(runtimeHome, QQBotChannelAdapter.UPDATE_RESPONSE_FILE_NAME))
                .hasContent("y");
    }

    @Test
    void shouldIgnoreMalformedQqbotInteractionButtonData() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setAllowAllUsers(true);
        TestQQBotAdapter adapter = new TestQQBotAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"int-1\",\"user_openid\":\"user-a\",\"resolved\":{\"button_data\":\"unknown:data\"}}}");

        assertThat(message).isNull();
    }

    @Test
    void shouldParseYuanbaoInboundTextAndPlatformAsrText() {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setAllowAllUsers(true);
        TestYuanbaoAdapter adapter = new TestYuanbaoAdapter(config);

        GatewayMessage message =
                adapter.parse(
                        "{\"body\":{\"chat_id\":\"room-a\",\"user_id\":\"user-a\",\"chat_type\":\"group\",\"voice\":{\"text\":\"平台转写\"},\"message_id\":\"m1\"}}");

        assertThat(message.getText()).isEqualTo("平台转写");
        assertThat(message.getChatId()).isEqualTo("room-a");
        assertThat(message.getThreadId()).isEqualTo("m1");
    }

    @Test
    void shouldParseFeishuDocumentCommentEvent() {
        AppConfig config = new AppConfig();
        config.getChannels().getFeishu().setCommentEnabled(true);
        config.getChannels().getFeishu().setAllowAllUsers(true);
        TestFeishuAdapter adapter = new TestFeishuAdapter(config);
        EventReq req = new EventReq();
        req.setPlain(
                "{\"event\":{\"comment_id\":\"c1\",\"reply_id\":\"r1\",\"notice_meta\":{\"notice_type\":\"add_reply\",\"file_token\":\"ft\",\"file_type\":\"docx\",\"from_user_id\":{\"open_id\":\"ou_1\"}},\"reply_content\":{\"elements\":[{\"type\":\"text_run\",\"text_run\":{\"text\":\"请总结这一段\"}}]}}}");

        GatewayMessage message = adapter.parseComment(req);

        assertThat(message.getChatId()).startsWith("comment|docx|ft|c1|r1|");
        assertThat(message.getSourceKeyOverride()).isEqualTo("FEISHU_COMMENT:docx:ft:c1");
        assertThat(message.getText()).contains("请总结这一段");
    }

    @Test
    void shouldDisableEnabledChannelsWithPlaceholderCredentialsAtGatewayStartup() {
        AppConfig config = new AppConfig();
        AttachmentCacheService attachmentCacheService = new AttachmentCacheService(config);
        InMemoryChannelStateRepository channelStateRepository =
                new InMemoryChannelStateRepository();

        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getFeishu().setAppId("cli_real_app");
        config.getChannels().getFeishu().setAppSecret("  ***  ");
        assertWeakCredentialRejected(
                new FeishuChannelAdapter(config.getChannels().getFeishu(), attachmentCacheService),
                "feishu_weak_credentials",
                "solonclaw.channels.feishu.appSecret");

        config.getChannels().getDingtalk().setEnabled(true);
        config.getChannels().getDingtalk().setClientId("ding_real_client");
        config.getChannels().getDingtalk().setClientSecret("changeme");
        config.getChannels().getDingtalk().setRobotCode("robot_real");
        assertWeakCredentialRejected(
                new DingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        channelStateRepository,
                        attachmentCacheService),
                "dingtalk_weak_credentials",
                "solonclaw.channels.dingtalk.clientSecret");

        config.getChannels().getWecom().setEnabled(true);
        config.getChannels().getWecom().setBotId("placeholder");
        config.getChannels().getWecom().setSecret("real_secret");
        assertWeakCredentialRejected(
                new WeComChannelAdapter(config.getChannels().getWecom(), attachmentCacheService),
                "wecom_weak_credentials",
                "solonclaw.channels.wecom.botId");

        config.getChannels().getWeixin().setEnabled(true);
        config.getChannels().getWeixin().setToken("your_api_key");
        config.getChannels().getWeixin().setAccountId("wx_real");
        assertWeakCredentialRejected(
                new WeiXinChannelAdapter(
                        config.getChannels().getWeixin(),
                        channelStateRepository,
                        attachmentCacheService),
                "weixin_weak_credentials",
                "solonclaw.channels.weixin.token");

        config.getChannels().getQqbot().setEnabled(true);
        config.getChannels().getQqbot().setAppId("qq_real");
        config.getChannels().getQqbot().setClientSecret("***");
        assertWeakCredentialRejected(
                new QQBotChannelAdapter(config.getChannels().getQqbot(), attachmentCacheService),
                "qqbot_weak_credentials",
                "solonclaw.channels.qqbot.clientSecret");

        config.getChannels().getYuanbao().setEnabled(true);
        config.getChannels().getYuanbao().setAppId("yb_real");
        config.getChannels().getYuanbao().setAppSecret("real_secret");
        config.getChannels().getYuanbao().setBotId("placeholder");
        assertWeakCredentialRejected(
                new YuanbaoChannelAdapter(config.getChannels().getYuanbao()),
                "yuanbao_weak_credentials",
                "solonclaw.channels.yuanbao.botId");
    }

    @Test
    void shouldKeepDisabledChannelPlaceholderCredentialsUnchecked() {
        AppConfig config = new AppConfig();
        config.getChannels().getFeishu().setEnabled(false);
        config.getChannels().getFeishu().setAppId("app");
        config.getChannels().getFeishu().setAppSecret("***");
        FeishuChannelAdapter adapter =
                new FeishuChannelAdapter(
                        config.getChannels().getFeishu(), new AttachmentCacheService(config));

        assertThat(adapter.connect()).isFalse();

        ChannelStatus status = adapter.statusSnapshot();
        assertThat(status.isEnabled()).isFalse();
        assertThat(status.getSetupState()).isEqualTo("disabled");
        assertThat(status.getLastErrorCode()).isNull();
    }

    @Test
    void shouldRedactChannelStatusDetailsAndErrors() {
        AppConfig config = new AppConfig();
        TestFeishuAdapter adapter = new TestFeishuAdapter(config);

        adapter.exposeDetail("connect failed: token=sk-test1234567890abcdef");
        adapter.exposeLastError(
                "feishu_connect_failed",
                "Authorization: Bearer sk-test1234567890abcdef");

        ChannelStatus status = adapter.statusSnapshot();
        assertThat(status.getDetail()).contains("token=***");
        assertThat(status.getDetail()).doesNotContain("sk-test1234567890abcdef");
        assertThat(status.getLastErrorMessage()).contains("Bearer ***");
        assertThat(status.getLastErrorMessage()).doesNotContain("sk-test1234567890abcdef");
    }

    @Test
    void shouldRedactChannelSafeErrorSummaries() {
        TestFeishuAdapter adapter = new TestFeishuAdapter(new AppConfig());

        String safe =
                adapter.exposeSafeError(
                        new IllegalStateException(
                                "connect failed token=sk-test-channelerror12345"));

        assertThat(safe).contains("token=***").doesNotContain("sk-test-channelerror12345");
    }

    @Test
    void shouldRedactFeishuPlatformErrorMessages() {
        TestFeishuAdapter adapter = new TestFeishuAdapter(new AppConfig());

        String safe =
                adapter.exposePlatformMessage(
                        "invalid token=ghp_feishuplatform12345 api_key=sk-feishu-platform");

        assertThat(safe)
                .contains("token=***")
                .contains("api_key=***")
                .doesNotContain("ghp_feishuplatform12345")
                .doesNotContain("sk-feishu-platform");
    }

    @Test
    void shouldRedactFeishuEnsureOkFailureMessages() {
        TestFeishuAdapter adapter = new TestFeishuAdapter(new AppConfig());

        assertThatThrownBy(
                        () ->
                                adapter.exposeEnsureOk(
                                        "{\"code\":999,\"msg\":\"bad token=ghp_feishuensure12345 api_key=sk-feishu-ensure\"}",
                                        "Feishu API failed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Feishu API failed")
                .hasMessageContaining("token=***")
                .hasMessageContaining("api_key=***")
                .hasMessageNotContaining("ghp_feishuensure12345")
                .hasMessageNotContaining("sk-feishu-ensure");
    }

    @Test
    void shouldRejectJimuquStyleWeakCredentialAliasesCaseInsensitively() {
        AppConfig config = new AppConfig();
        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getFeishu().setAppId("app");
        config.getChannels().getFeishu().setAppSecret("  Your-API-Key  ");
        FeishuChannelAdapter feishu =
                new FeishuChannelAdapter(
                        config.getChannels().getFeishu(), new AttachmentCacheService(config));

        assertWeakCredentialRejected(
                feishu,
                "feishu_weak_credentials",
                "solonclaw.channels.feishu.appSecret");

        config.getChannels().getWecom().setEnabled(true);
        config.getChannels().getWecom().setBotId("real_bot");
        config.getChannels().getWecom().setSecret("NONE");
        WeComChannelAdapter wecom =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(), new AttachmentCacheService(config));

        assertWeakCredentialRejected(
                wecom,
                "wecom_weak_credentials",
                "solonclaw.channels.wecom.secret");
    }

    @Test
    void shouldTreatEmptyCredentialsAsMissingNotWeakCredentialPlaceholders() {
        AppConfig config = new AppConfig();
        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getFeishu().setAppId("app");
        config.getChannels().getFeishu().setAppSecret("");
        FeishuChannelAdapter adapter =
                new FeishuChannelAdapter(
                        config.getChannels().getFeishu(), new AttachmentCacheService(config));

        assertThat(adapter.connect()).isFalse();

        ChannelStatus status = adapter.statusSnapshot();
        assertThat(status.isEnabled()).isTrue();
        assertThat(status.getSetupState()).isEqualTo("missing_config");
        assertThat(status.getLastErrorCode()).isEqualTo("feishu_missing_credentials");
    }

    @Test
    void shouldApplyDingTalkAllowedChatsAsHardGroupWhitelist() throws Throwable {
        AppConfig config = new AppConfig();
        config.getChannels().getDingtalk().setAllowedChats(Arrays.asList("cid-allowed"));
        config.getChannels().getDingtalk().setGroupPolicy("open");
        DingTalkChannelAdapter adapter =
                new DingTalkChannelAdapter(
                        config.getChannels().getDingtalk(),
                        new InMemoryChannelStateRepository(),
                        new AttachmentCacheService(config));
        Method allowInbound =
                DingTalkChannelAdapter.class.getDeclaredMethod(
                        "allowInbound",
                        ChatbotMessage.class,
                        String.class,
                        String.class,
                        String.class);
        allowInbound.setAccessible(true);
        ChatbotMessage mentioned = new ChatbotMessage();
        mentioned.setInAtList(Boolean.TRUE);

        assertThat((Boolean) invoke(allowInbound, adapter, mentioned, "cid-blocked", "group", "u1"))
                .isFalse();
        assertThat((Boolean) invoke(allowInbound, adapter, mentioned, "cid-allowed", "group", "u1"))
                .isTrue();
        assertThat((Boolean) invoke(allowInbound, adapter, mentioned, "cid-blocked", "dm", "u1"))
                .isTrue();
    }

    @Test
    void shouldBlockUnsafeYuanbaoConfiguredUrlsBeforeNetworkAccess() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setEnabled(true);
        config.getChannels().getYuanbao().setAppId("yb_real");
        config.getChannels().getYuanbao().setAppSecret("real_secret");
        config.getChannels().getYuanbao().setWebsocketUrl(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        YuanbaoChannelAdapter adapter =
                new YuanbaoChannelAdapter(
                        config.getChannels().getYuanbao(), new SecurityPolicyService(config));

        assertThat(adapter.connect()).isFalse();
        assertThat(adapter.statusSnapshot().getLastErrorMessage())
                .contains("Yuanbao websocket URL blocked")
                .contains("169.254.169.254")
                .contains("token=***");

        config.getChannels().getYuanbao().setWebsocketUrl(null);
        config.getChannels().getYuanbao().setApiDomain(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        Method postJson =
                YuanbaoChannelAdapter.class.getDeclaredMethod(
                        "postJson", String.class, String.class);
        postJson.setAccessible(true);

        assertThatThrownBy(() -> invoke(postJson, adapter, "/openapi/bot/messages", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Yuanbao API URL blocked")
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining("token=***");
    }

    @Test
    void shouldBlockUnsafeQqbotConfiguredUrlsBeforeNetworkAccess() throws Exception {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setEnabled(true);
        config.getChannels().getQqbot().setAppId("qq_real");
        config.getChannels().getQqbot().setClientSecret("real_secret");
        config.getChannels().getQqbot().setWebsocketUrl(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config));
        setField(adapter, "accessToken", "cached-token");
        setField(adapter, "accessTokenExpireAt", Long.valueOf(System.currentTimeMillis() + 120000L));

        assertThat(adapter.connect()).isFalse();
        assertThat(adapter.statusSnapshot().getLastErrorMessage())
                .contains("QQBot websocket URL blocked")
                .contains("169.254.169.254")
                .contains("token=***");

        config.getChannels().getQqbot().setApiDomain(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        Method postJson =
                QQBotChannelAdapter.class.getDeclaredMethod("postJson", String.class, String.class);
        postJson.setAccessible(true);

        assertThatThrownBy(() -> invoke(postJson, adapter, "/v2/users/u/messages", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QQBot API URL blocked")
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining("token=***");
    }

    @Test
    void shouldRedactYuanbaoHttpErrorBody() throws Throwable {
        HttpServer server = secretErrorServer("{\"error\":\"token=ghp_yuanbaohttp12345\"}");
        try {
            AppConfig config = new AppConfig();
            config.getSecurity().setAllowPrivateUrls(true);
            config.getChannels().getYuanbao().setAppId("yb_real");
            config.getChannels().getYuanbao().setAppSecret("real_secret");
            config.getChannels().getYuanbao().setApiDomain(localUrl(server));
            YuanbaoChannelAdapter adapter =
                    new YuanbaoChannelAdapter(
                            config.getChannels().getYuanbao(), new SecurityPolicyService(config));
            Method postJson =
                    YuanbaoChannelAdapter.class.getDeclaredMethod(
                            "postJson", String.class, String.class);
            postJson.setAccessible(true);

            assertThatThrownBy(() -> invoke(postJson, adapter, "/openapi/bot/messages", "{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Yuanbao HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageNotContaining("ghp_yuanbaohttp12345");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactQqbotHttpErrorBody() throws Throwable {
        HttpServer server = secretErrorServer("{\"message\":\"api_key=sk-qqbot-http-secret\"}");
        try {
            AppConfig config = new AppConfig();
            config.getSecurity().setAllowPrivateUrls(true);
            config.getChannels().getQqbot().setAppId("qq_real");
            config.getChannels().getQqbot().setClientSecret("real_secret");
            config.getChannels().getQqbot().setApiDomain(localUrl(server));
            QQBotChannelAdapter adapter =
                    new QQBotChannelAdapter(
                            config.getChannels().getQqbot(),
                            new AttachmentCacheService(config),
                            new SecurityPolicyService(config));
            setField(adapter, "accessToken", "cached-token");
            setField(
                    adapter,
                    "accessTokenExpireAt",
                    Long.valueOf(System.currentTimeMillis() + 120000L));
            Method postJson =
                    QQBotChannelAdapter.class.getDeclaredMethod(
                            "postJson", String.class, String.class);
            postJson.setAccessible(true);

            assertThatThrownBy(() -> invoke(postJson, adapter, "/v2/users/u/messages", "{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QQBot HTTP 500")
                    .hasMessageContaining("api_key=***")
                    .hasMessageNotContaining("sk-qqbot-http-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactQqbotGetErrorBody() throws Throwable {
        HttpServer server = secretErrorServer("{\"message\":\"token=ghp_qqbotget12345\"}");
        try {
            AppConfig config = new AppConfig();
            config.getSecurity().setAllowPrivateUrls(true);
            config.getChannels().getQqbot().setApiDomain(localUrl(server));
            QQBotChannelAdapter adapter =
                    new QQBotChannelAdapter(
                            config.getChannels().getQqbot(),
                            new AttachmentCacheService(config),
                            new SecurityPolicyService(config));
            setField(adapter, "accessToken", "cached-token");
            setField(
                    adapter,
                    "accessTokenExpireAt",
                    Long.valueOf(System.currentTimeMillis() + 120000L));
            Method getJson = QQBotChannelAdapter.class.getDeclaredMethod("getJson", String.class);
            getJson.setAccessible(true);

            assertThatThrownBy(() -> invoke(getJson, adapter, "/gateway"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QQBot HTTP 500")
                    .hasMessageContaining("token=***")
                    .hasMessageNotContaining("ghp_qqbotget12345");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRedactQqbotFailureJson() throws Throwable {
        AppConfig config = new AppConfig();
        QQBotChannelAdapter adapter =
                new QQBotChannelAdapter(
                        config.getChannels().getQqbot(),
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config));
        Method safeJson = QQBotChannelAdapter.class.getDeclaredMethod("safeJson", ONode.class);
        safeJson.setAccessible(true);
        ONode failure =
                new ONode()
                        .set("message", "missing token=ghp_qqbotjson12345")
                        .set("data", new ONode().set("api_key", "sk-qqbot-json-secret"));

        String message = String.valueOf(invoke(safeJson, adapter, failure));

        assertThat(message)
                .contains("token=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_qqbotjson12345")
                .doesNotContain("sk-qqbot-json-secret");
    }

    @Test
    void shouldBlockUnsafeWeComConfiguredWebsocketBeforeNetworkAccess() {
        AppConfig config = new AppConfig();
        config.getChannels().getWecom().setEnabled(true);
        config.getChannels().getWecom().setBotId("wecom_real");
        config.getChannels().getWecom().setSecret("real_secret");
        config.getChannels().getWecom().setWebsocketUrl(
                "http://169.254.169.254/latest/meta-data/?token=secret");
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(),
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config));

        assertThatThrownBy(adapter::connect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WeCom connect failed");
        assertThat(adapter.statusSnapshot().getLastErrorMessage())
                .contains("WeCom websocket URL blocked")
                .contains("169.254.169.254")
                .contains("token=***");
    }

    @Test
    void shouldRedactWeComFailureJson() throws Throwable {
        AppConfig config = new AppConfig();
        WeComChannelAdapter adapter =
                new WeComChannelAdapter(
                        config.getChannels().getWecom(),
                        new AttachmentCacheService(config),
                        new SecurityPolicyService(config));
        Method safeJson = WeComChannelAdapter.class.getDeclaredMethod("safeJson", ONode.class);
        safeJson.setAccessible(true);
        ONode failure =
                new ONode()
                        .set("ret", 40001)
                        .set("errmsg", "invalid token=ghp_wecomfail12345")
                        .set("body", new ONode().set("api_key", "sk-wecom-failure-secret"));

        String message = String.valueOf(invoke(safeJson, adapter, failure));

        assertThat(message)
                .contains("token=***")
                .contains("\"api_key\":\"***\"")
                .doesNotContain("ghp_wecomfail12345")
                .doesNotContain("sk-wecom-failure-secret");
    }

    private void assertWeakCredentialRejected(
            com.jimuqu.solon.claw.core.service.ChannelAdapter adapter,
            String expectedErrorCode,
            String expectedField) {
        assertThat(adapter.connect()).isFalse();

        ChannelStatus status = adapter.statusSnapshot();
        assertThat(status.isEnabled()).isFalse();
        assertThat(status.isConnected()).isFalse();
        assertThat(status.getSetupState()).isEqualTo("weak_credentials");
        assertThat(status.getLastErrorCode()).isEqualTo(expectedErrorCode);
        assertThat(status.getLastErrorMessage()).contains("placeholder credential");
        assertThat(status.getDetail()).contains(expectedField);
    }

    private Object invoke(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private HttpServer secretErrorServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private String localUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static class TestQQBotAdapter extends QQBotChannelAdapter {
        private TestQQBotAdapter(AppConfig config) {
            super(
                    config,
                    config.getChannels().getQqbot(),
                    new AttachmentCacheService(config),
                    null);
        }

        private GatewayMessage parse(String raw) {
            return toGatewayMessage(raw);
        }

        @Override
        protected MessageAttachment cacheRemoteAttachment(
                String url,
                String kind,
                String fileName,
                String mimeType,
                boolean fromQuote,
                String transcribedText) {
            MessageAttachment attachment = new MessageAttachment();
            attachment.setKind(AttachmentCacheService.normalizeKind(kind, fileName, mimeType));
            attachment.setLocalPath("runtime/cache/media/qqbot/" + fileName);
            attachment.setOriginalName(fileName);
            attachment.setMimeType(AttachmentCacheService.normalizeMimeType(mimeType, fileName));
            attachment.setFromQuote(fromQuote);
            attachment.setTranscribedText(transcribedText);
            return attachment;
        }

        private ONode buildApprovalBody(DeliveryRequest request) {
            return buildApprovalKeyboardBody(request);
        }

        private ONode buildUpdatePromptBody(DeliveryRequest request) {
            return buildUpdatePromptKeyboardBody(request);
        }
    }

    private static class TestYuanbaoAdapter extends YuanbaoChannelAdapter {
        private TestYuanbaoAdapter(AppConfig config) {
            super(config.getChannels().getYuanbao());
        }

        private GatewayMessage parse(String raw) {
            return toGatewayMessage(raw);
        }
    }

    private static class TestFeishuAdapter extends FeishuChannelAdapter {
        private TestFeishuAdapter(AppConfig config) {
            super(config.getChannels().getFeishu(), new AttachmentCacheService(config));
        }

        private GatewayMessage parseComment(EventReq req) {
            return toCommentGatewayMessage(req);
        }

        private void exposeDetail(String detail) {
            setDetail(detail);
        }

        private void exposeLastError(String code, String message) {
            setLastError(code, message);
        }

        private String exposeSafeError(Throwable throwable) {
            return safeError(throwable);
        }

        private String exposePlatformMessage(String value) {
            return safePlatformMessage(value);
        }

        private ONode exposeEnsureOk(String response, String defaultMessage) {
            return ensureOk(response, defaultMessage);
        }
    }

    private static class InMemoryChannelStateRepository implements ChannelStateRepository {
        @Override
        public String get(
                com.jimuqu.solon.claw.core.enums.PlatformType platform,
                String scopeKey,
                String stateKey) {
            return null;
        }

        @Override
        public void put(
                com.jimuqu.solon.claw.core.enums.PlatformType platform,
                String scopeKey,
                String stateKey,
                String stateValue) {}

        @Override
        public void delete(
                com.jimuqu.solon.claw.core.enums.PlatformType platform,
                String scopeKey,
                String stateKey) {}

        @Override
        public List<StateItem> list(
                com.jimuqu.solon.claw.core.enums.PlatformType platform, String scopeKey) {
            return Collections.emptyList();
        }
    }
}
