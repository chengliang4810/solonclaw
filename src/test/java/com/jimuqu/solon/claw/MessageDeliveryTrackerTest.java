package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.tool.runtime.MessageDeliveryTracker;
import org.junit.jupiter.api.Test;

/** 验证当前来源会话直接投递的去重规则。 */
public class MessageDeliveryTrackerTest {
    /** 验证同一来源会话的直接投递标记只能被消费一次。 */
    @Test
    void shouldConsumeSameSourceDirectDeliveryOnlyOnce() {
        MessageDeliveryTracker.recordDirectDelivery(
                "WEIXIN:chat-a:user-a",
                PlatformType.WEIXIN,
                "chat-a",
                null,
                PlatformType.WEIXIN,
                "chat-a",
                null);

        boolean first = MessageDeliveryTracker.consumeDirectDelivery("WEIXIN:chat-a:user-a");
        boolean second = MessageDeliveryTracker.consumeDirectDelivery("WEIXIN:chat-a:user-a");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    /** 验证发送到其他聊天时不抑制当前聊天的最终答复。 */
    @Test
    void shouldIgnoreDifferentChatDelivery() {
        MessageDeliveryTracker.recordDirectDelivery(
                "WEIXIN:chat-b:user-b",
                PlatformType.WEIXIN,
                "chat-b",
                null,
                PlatformType.WEIXIN,
                "chat-c",
                null);

        boolean consumed = MessageDeliveryTracker.consumeDirectDelivery("WEIXIN:chat-b:user-b");

        assertThat(consumed).isFalse();
    }

    /** 验证发送到其他平台时不抑制当前平台的最终答复。 */
    @Test
    void shouldIgnoreDifferentPlatformDelivery() {
        MessageDeliveryTracker.recordDirectDelivery(
                "WEIXIN:chat-d:user-d",
                PlatformType.WEIXIN,
                "chat-d",
                null,
                PlatformType.FEISHU,
                "chat-d",
                null);

        boolean consumed = MessageDeliveryTracker.consumeDirectDelivery("WEIXIN:chat-d:user-d");

        assertThat(consumed).isFalse();
    }

    /** 验证发送到其他线程时不抑制当前线程的最终答复。 */
    @Test
    void shouldIgnoreDifferentThreadDelivery() {
        MessageDeliveryTracker.recordDirectDelivery(
                "FEISHU:chat-e:thread-a:user-e",
                PlatformType.FEISHU,
                "chat-e",
                "thread-a",
                PlatformType.FEISHU,
                "chat-e",
                "thread-b");

        boolean consumed =
                MessageDeliveryTracker.consumeDirectDelivery(
                        "FEISHU:chat-e:thread-a:user-e");

        assertThat(consumed).isFalse();
    }

    /** 验证新一轮运行开始前会清理上一轮异常遗留的标记。 */
    @Test
    void shouldClearStaleDirectDeliveryBeforeNextRun() {
        MessageDeliveryTracker.recordDirectDelivery(
                "WEIXIN:chat-f:user-f",
                PlatformType.WEIXIN,
                "chat-f",
                null,
                PlatformType.WEIXIN,
                "chat-f",
                null);

        MessageDeliveryTracker.clearDirectDelivery("WEIXIN:chat-f:user-f");

        assertThat(MessageDeliveryTracker.consumeDirectDelivery("WEIXIN:chat-f:user-f"))
                .isFalse();
    }
}
