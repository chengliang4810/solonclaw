package com.jimuqu.solon.claw;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.proactive.ProactiveReminderScheduler;
import org.junit.jupiter.api.Test;

/** 验证主动提醒关闭时不会读取会话、调用模型或投递消息。 */
class ProactiveReminderSchedulerTest {
    /** 关闭主动提醒后一次 tick 应直接结束。 */
    @Test
    void shouldSkipEverythingWhenDisabled() throws Exception {
        AppConfig config = new AppConfig();
        config.getProactive().setEnabled(false);
        new ProactiveReminderScheduler(config, null, null, null, null, null, null).tick();
    }
}
