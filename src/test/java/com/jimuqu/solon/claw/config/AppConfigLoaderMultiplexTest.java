package com.jimuqu.solon.claw.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证复用网关环境覆盖遵循环境、配置、默认值三层优先级。 */
class AppConfigLoaderMultiplexTest {
    /** 明确认可的 true/false 令牌覆盖配置值。 */
    @Test
    void recognizedEnvironmentTokensOverrideConfig() {
        assertThat(AppConfigLoader.resolveMultiplexProfiles("yes", false)).isTrue();
        assertThat(AppConfigLoader.resolveMultiplexProfiles("0", true)).isFalse();
    }

    /** 未设置、空白和未知值都回退配置，避免空 Secret 意外关闭复用。 */
    @Test
    void unsetBlankAndUnknownEnvironmentKeepConfig() {
        assertThat(AppConfigLoader.resolveMultiplexProfiles(null, true)).isTrue();
        assertThat(AppConfigLoader.resolveMultiplexProfiles("   ", true)).isTrue();
        assertThat(AppConfigLoader.resolveMultiplexProfiles("maybe", true)).isTrue();
        assertThat(AppConfigLoader.resolveMultiplexProfiles("maybe", false)).isFalse();
    }
}
