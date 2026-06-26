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
}
