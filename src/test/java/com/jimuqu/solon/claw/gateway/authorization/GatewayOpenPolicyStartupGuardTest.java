package com.jimuqu.solon.claw.gateway.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import org.junit.jupiter.api.Test;

/** 验证开放访问策略只有在当前 Profile 明确确认全量放行后才能启动。 */
class GatewayOpenPolicyStartupGuardTest {
    /** 自有策略渠道以 open 启动但未确认全量放行时必须失败，且错误不得泄露凭据。 */
    @Test
    void rejectsOpenDomesticPolicyWithoutExplicitOptIn() {
        AppConfig config = new AppConfig();
        config.getChannels().getWecom().setEnabled(true);
        config.getChannels().getWecom().setDmPolicy("open");
        config.getChannels().getWecom().setGroupPolicy("disabled");
        config.getChannels().getWecom().setSecret("profile-secret-value");

        assertThat(GatewayOpenPolicyStartupGuard.firstViolation(config))
                .isEqualTo(PlatformType.WECOM);
        assertThatThrownBy(() -> GatewayOpenPolicyStartupGuard.requireAllowed(config, "worker"))
                .isInstanceOf(GatewayOpenPolicyStartupGuard.ViolationException.class)
                .hasMessageContaining("worker")
                .hasMessageContaining("wecom")
                .hasMessageNotContaining("profile-secret-value");
    }

    /** 全局或当前渠道显式 allowAllUsers 均可确认开放策略。 */
    @Test
    void acceptsGlobalOrChannelOptIn() {
        AppConfig config = new AppConfig();
        config.getChannels().getYuanbao().setEnabled(true);
        config.getChannels().getYuanbao().setDmPolicy("open");

        config.getGateway().setAllowAllUsers(true);
        assertThat(GatewayOpenPolicyStartupGuard.firstViolation(config)).isNull();

        config.getGateway().setAllowAllUsers(false);
        config.getChannels().getYuanbao().setAllowAllUsers(true);
        assertThat(GatewayOpenPolicyStartupGuard.firstViolation(config)).isNull();
    }

    /** 未启用渠道及不属于自有策略门禁的渠道不应误阻断启动。 */
    @Test
    void ignoresDisabledAndNonGuardedChannels() {
        AppConfig config = new AppConfig();
        config.getChannels().getQqbot().setEnabled(false);
        config.getChannels().getQqbot().setDmPolicy("open");
        config.getChannels().getFeishu().setEnabled(true);
        config.getChannels().getFeishu().setDmPolicy("open");
        config.getChannels().getDingtalk().setEnabled(true);
        config.getChannels().getDingtalk().setGroupPolicy("open");

        assertThat(GatewayOpenPolicyStartupGuard.firstViolation(config)).isNull();
    }
}
