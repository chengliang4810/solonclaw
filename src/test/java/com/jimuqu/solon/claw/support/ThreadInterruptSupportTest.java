package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThreadInterruptSupportTest {
    /** 每个用例后清掉当前线程中断标记，避免污染后续测试。 */
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    /** 异常链包含 InterruptedException 时应恢复当前线程中断标记。 */
    @Test
    void shouldRestoreInterruptWhenCauseContainsInterruptedException() {
        RuntimeException error = new RuntimeException(new InterruptedException("stop"));

        ThreadInterruptSupport.restoreIfCausedByInterrupted(error);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    /** 异常链不包含 InterruptedException 时不应设置中断标记。 */
    @Test
    void shouldIgnoreNonInterruptedCause() {
        ThreadInterruptSupport.restoreIfCausedByInterrupted(new RuntimeException("failed"));

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
