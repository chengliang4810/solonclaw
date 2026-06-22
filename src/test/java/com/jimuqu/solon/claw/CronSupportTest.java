package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.support.CronSupport;
import org.junit.jupiter.api.Test;

/** CronSupport 共享解析工具测试，锁定异常 fallback 的既有返回语义。 */
public class CronSupportTest {
    /** 固定起算时间，避免下一次运行时间断言随真实时间变化。 */
    private static final long BASE_TIME = 1_800_000_000_000L;

    /** 无效一次性时间解析失败时，仍按原语义返回非一次性任务并不给出绝对运行时间。 */
    @Test
    void shouldKeepOneShotFallbackForInvalidIsoSchedule() {
        String invalidSchedule = "not-a-date";

        assertThat(CronSupport.isOneShot(invalidSchedule)).isFalse();
        assertThat(CronSupport.absoluteRunAt(invalidSchedule)).isNull();
    }

    /** 无效 cron 字段触发解析异常时，周期估算仍按原语义返回 0。 */
    @Test
    void shouldKeepPeriodFallbackForInvalidCronField() {
        assertThat(CronSupport.periodMillis("60 * * * *", BASE_TIME)).isZero();
    }

    /** 非 cron 文本无法拆成有效字段时，下一次运行时间仍按原语义回退到一分钟后。 */
    @Test
    void shouldKeepNextRunFallbackForNonCronText() {
        assertThat(CronSupport.nextRunAt("not-a-date", BASE_TIME)).isEqualTo(BASE_TIME + 60_000L);
    }
}
