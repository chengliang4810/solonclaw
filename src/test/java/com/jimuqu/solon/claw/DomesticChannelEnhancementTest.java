package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.dingtalk.DingTalkChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.feishu.FeishuChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.qqbot.QQBotChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.wecom.WeComChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.weixin.WeiXinChannelAdapter;
import com.jimuqu.solon.claw.gateway.platform.yuanbao.YuanbaoChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.lark.oapi.core.request.EventReq;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    void shouldRejectHermesStyleWeakCredentialAliasesCaseInsensitively() {
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

    private static class TestQQBotAdapter extends QQBotChannelAdapter {
        private TestQQBotAdapter(AppConfig config) {
            super(config.getChannels().getQqbot(), new AttachmentCacheService(config));
        }

        private GatewayMessage parse(String raw) {
            return toGatewayMessage(raw);
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
