package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.HomeChannelRecord;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.proactive.ProactiveReminderScheduler;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** 验证主动提醒关闭时不会读取会话、调用模型或投递消息。 */
class ProactiveReminderSchedulerTest {
    /** 关闭主动提醒后一次 tick 应直接结束。 */
    @Test
    void shouldSkipEverythingWhenDisabled() throws Exception {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(false);
        new ProactiveReminderScheduler(config, null, null, null, null, null, null, null).tick();
    }

    /** 主动提醒只能选择与显式 home channel 完全匹配的平台、聊天和线程。 */
    @Test
    void shouldMatchOnlyConfiguredHomeConversation() throws Exception {
        HomeChannelRecord home = new HomeChannelRecord();
        home.setPlatform(PlatformType.FEISHU);
        home.setChatId("chat-main");
        home.setThreadId("thread-main");
        SessionRecord matching = new SessionRecord();
        matching.setSourceKey("FEISHU:chat-main:thread-main:user-1");
        SessionRecord otherChat = new SessionRecord();
        otherChat.setSourceKey("FEISHU:chat-other:thread-main:user-1");

        Method matcher =
                ProactiveReminderScheduler.class.getDeclaredMethod(
                        "matchesHome", SessionRecord.class, HomeChannelRecord.class);
        matcher.setAccessible(true);
        org.assertj.core.api.Assertions.assertThat(matcher.invoke(null, matching, home))
                .isEqualTo(Boolean.TRUE);
        org.assertj.core.api.Assertions.assertThat(matcher.invoke(null, otherChat, home))
                .isEqualTo(Boolean.FALSE);
    }
}
