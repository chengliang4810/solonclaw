package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

/** 校验新安装环境的阶段说明与工具进度默认值。 */
public class ProgressUpdateDefaultsTest {
    /** 新配置默认关闭逐工具进度，并把阶段说明节流设为五秒。 */
    @Test
    void shouldUseQuietProgressDefaults() {
        AppConfig config = new AppConfig();
        AppConfig loaded = AppConfig.loadDetached(new Props());

        assertThat(config.getDisplay().getToolProgress()).isEqualTo("off");
        assertThat(config.getDisplay().getProgressThrottleMs()).isEqualTo(5000);
        assertThat(loaded.getDisplay().getToolProgress()).isEqualTo("off");
        assertThat(loaded.getDisplay().getProgressThrottleMs()).isEqualTo(5000);
        assertThat(loaded.getChannels().getFeishu().getToolProgress()).isEqualTo("off");
        assertThat(loaded.getChannels().getDingtalk().getToolProgress()).isEqualTo("off");
        assertThat(loaded.getChannels().getWecom().getToolProgress()).isEqualTo("off");
        assertThat(loaded.getChannels().getWeixin().getToolProgress()).isEqualTo("off");
        assertThat(loaded.getChannels().getQqbot().getToolProgress()).isEqualTo("off");
        assertThat(loaded.getChannels().getYuanbao().getToolProgress()).isEqualTo("off");
    }

    /** 已有用户显式保存的工具进度与节流配置必须继续生效。 */
    @Test
    void shouldPreserveExplicitProgressOverrides() {
        Props properties = new Props();
        properties.setProperty("solonclaw.display.toolProgress", "all");
        properties.setProperty("solonclaw.display.progressThrottleMs", "1500");
        properties.setProperty("solonclaw.channels.feishu.toolProgress", "new");

        AppConfig loaded = AppConfig.loadDetached(properties);

        assertThat(loaded.getDisplay().getToolProgress()).isEqualTo("all");
        assertThat(loaded.getDisplay().getProgressThrottleMs()).isEqualTo(1500);
        assertThat(loaded.getChannels().getFeishu().getToolProgress()).isEqualTo("new");
    }

    /** 模型重试和恢复必须共享同一轮阶段说明的去重、节流和数量上限。 */
    @Test
    void shouldLimitProgressUpdatesAcrossAttempts() {
        AgentRunContext context = new AgentRunContext(null, "run-1", "session-1", "source-1");

        assertThat(context.tryRegisterProgressUpdate("第一阶段", 1000L, 5000L, 3)).isTrue();
        assertThat(context.tryRegisterProgressUpdate("第一阶段", 7000L, 5000L, 3)).isFalse();
        assertThat(context.tryRegisterProgressUpdate("第二阶段", 7000L, 5000L, 3)).isTrue();
        assertThat(context.tryRegisterProgressUpdate("过早阶段", 8000L, 5000L, 3)).isFalse();
        assertThat(context.tryRegisterProgressUpdate("第一阶段", 13000L, 5000L, 3)).isFalse();
        assertThat(context.tryRegisterProgressUpdate("第三阶段", 13000L, 5000L, 3)).isTrue();
        assertThat(context.tryRegisterProgressUpdate("第四阶段", 19000L, 5000L, 3)).isFalse();
    }
}
