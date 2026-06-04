package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.gateway.service.MessageCanonicalizationService;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MessageCanonicalizationServiceTest {
    private final MessageCanonicalizationService service = new MessageCanonicalizationService();

    @Test
    void shouldNormalizeWhitespace() {
        GatewayMessage msg =
                new GatewayMessage(
                        PlatformType.MEMORY, "room", "user", "hello  \r\n  world\n\n\n\n\nend");
        service.canonicalize(msg);
        // \r\n -> \n, trailing spaces on lines stripped, 4+ consecutive newlines -> 3
        assertThat(msg.getText()).doesNotContain("\r");
        assertThat(msg.getText()).contains("hello");
        assertThat(msg.getText()).contains("world");
        assertThat(msg.getText()).contains("end");
        assertThat(msg.getText()).doesNotContain("\n\n\n\n");
    }

    @Test
    void shouldStripFeishuMentions() {
        GatewayMessage msg =
                new GatewayMessage(
                        PlatformType.FEISHU,
                        "room",
                        "user",
                        "<at user_id=\"ou_abc123\">张三</at> 帮我查一下");
        service.canonicalize(msg);
        assertThat(msg.getText()).isEqualTo("帮我查一下");
    }

    @Test
    void shouldStripDingTalkMentions() {
        GatewayMessage msg = new GatewayMessage(PlatformType.DINGTALK, "room", "user", "@机器人 执行任务");
        service.canonicalize(msg);
        assertThat(msg.getText()).isEqualTo("执行任务");
    }

    @Test
    void shouldStripQQBotMentions() {
        GatewayMessage msg =
                new GatewayMessage(PlatformType.QQBOT, "room", "user", "<@!123456789> 你好");
        service.canonicalize(msg);
        assertThat(msg.getText()).isEqualTo("你好");
    }

    @Test
    void shouldTruncateLongMessages() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40000; i++) {
            sb.append('x');
        }
        GatewayMessage msg = new GatewayMessage(PlatformType.MEMORY, "room", "user", sb.toString());
        service.canonicalize(msg);
        assertThat(msg.getText().length()).isLessThan(33000);
        assertThat(msg.getText()).contains("...[消息已截断]");
    }

    @Test
    void shouldReturnPolicySummary() {
        Map<String, Object> policy = service.policy();
        assertThat(policy).containsKey("maxTextLength");
        assertThat(policy).containsKey("mentionStripping");
    }
}
