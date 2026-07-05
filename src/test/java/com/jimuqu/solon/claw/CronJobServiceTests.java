package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.scheduler.CronJobService;
import org.junit.jupiter.api.Test;

/** CronJobService 关键共享常量测试，避免运行时安全边界被外部代码改写。 */
public class CronJobServiceTests {
    /** 受保护的定时任务禁用工具集必须禁止外部改写元素，防止调度安全边界漂移。 */
    @Test
    void shouldRejectProtectedDisabledToolsetMutation() {
        assertThatThrownBy(
                        () ->
                                CronJobService.PROTECTED_CRON_DISABLED_TOOLSETS.set(
                                        0, "terminal"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
