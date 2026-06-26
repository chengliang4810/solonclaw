package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证时间换算辅助工具。 */
class TimeSupportTest {
    /** 过期时间剩余秒数应向上取整且不返回负数。 */
    @Test
    void shouldReturnRemainingSecondsUntilExpiry() {
        assertThat(TimeSupport.expiresInSeconds(0L)).isZero();
        assertThat(TimeSupport.expiresInSeconds(System.currentTimeMillis() - 1L)).isZero();
        assertThat(TimeSupport.expiresInSeconds(System.currentTimeMillis() + 1_100L))
                .isBetween(1L, 2L);
    }

    /** 毫秒时间解析失败时应回退当前时间。 */
    @Test
    void shouldParseMillisOrFallbackToNow() {
        assertThat(TimeSupport.millisOrNow(Long.valueOf(12L))).isEqualTo(12L);
        assertThat(TimeSupport.millisOrNow("34")).isEqualTo(34L);
        assertThat(TimeSupport.millisOrNow("bad")).isGreaterThan(0L);
    }
}
