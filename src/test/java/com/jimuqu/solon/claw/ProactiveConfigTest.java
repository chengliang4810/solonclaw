package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import org.junit.jupiter.api.Test;

/** 验证主动提醒配置采用页面约定的精简字段。 */
class ProactiveConfigTest {
    /** 验证默认配置与主动提醒页面一致。 */
    @Test
    void shouldExposeReminderDefaults() {
        AppConfig.ProactiveConfig config = new AppConfig.ProactiveConfig();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getIntervalHours()).isEqualTo(4D);
        assertThat(config.getDeliveryTarget()).isEqualTo("main");
        assertThat(config.getTopicCooldownHours()).isEqualTo(8D);
        assertThat(config.isQuietHoursEnabled()).isTrue();
        assertThat(config.getQuietStart()).isEqualTo("22:00");
        assertThat(config.getQuietEnd()).isEqualTo("08:00");
    }
}
