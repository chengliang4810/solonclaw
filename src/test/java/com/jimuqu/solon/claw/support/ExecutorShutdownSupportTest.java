package com.jimuqu.solon.claw.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 验证后台写入执行器关闭时优先排空已经接收的任务。 */
public class ExecutorShutdownSupportTest {
    /** 关闭调用必须等待已接收任务完成，不能像 shutdownNow 一样直接丢弃。 */
    @Test
    void shouldDrainAcceptedTaskBeforeReturning() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean queuedCompleted = new AtomicBoolean();
        executor.submit(
                () -> {
                    started.countDown();
                    try {
                        release.await();
                        completed.set(true);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        executor.submit(() -> queuedCompleted.set(true));
        assertThat(started.await(1L, TimeUnit.SECONDS)).isTrue();

        Thread closer =
                new Thread(
                        () -> ExecutorShutdownSupport.drain(executor, 2L, TimeUnit.SECONDS),
                        "executor-drain-test");
        try {
            closer.start();
            awaitShutdown(executor);
            assertThat(closer.isAlive()).isTrue();

            release.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(2L));

            assertThat(closer.isAlive()).isFalse();
            assertThat(completed.get()).isTrue();
            assertThat(queuedCompleted.get()).isTrue();
            assertThat(executor.isTerminated()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
            closer.join(TimeUnit.SECONDS.toMillis(2L));
        }
    }

    /** 排空超时后必须调用 shutdownNow 中断仍在执行的任务。 */
    @Test
    void shouldInterruptRunningTaskAfterDrainTimeout() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.submit(
                () -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(started.await(1L, TimeUnit.SECONDS)).isTrue();

        ExecutorShutdownSupport.drain(executor, 20L, TimeUnit.MILLISECONDS);

        assertThat(executor.isShutdown()).isTrue();
        assertThat(interrupted.await(1L, TimeUnit.SECONDS)).isTrue();
    }

    /** 等待线程被中断时必须强制关闭执行器并恢复调用线程的中断标记。 */
    @Test
    void shouldRestoreCallerInterruptStatus() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        executor.submit(
                () -> {
                    workerStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        workerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                });
        assertThat(workerStarted.await(1L, TimeUnit.SECONDS)).isTrue();
        Thread closer =
                new Thread(
                        () -> {
                            Thread.currentThread().interrupt();
                            ExecutorShutdownSupport.drain(executor, 1L, TimeUnit.SECONDS);
                            callerInterrupted.set(Thread.currentThread().isInterrupted());
                        },
                        "executor-interrupted-drain-test");

        closer.start();
        closer.join(TimeUnit.SECONDS.toMillis(2L));

        assertThat(closer.isAlive()).isFalse();
        assertThat(callerInterrupted.get()).isTrue();
        assertThat(workerInterrupted.await(1L, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.isShutdown()).isTrue();
    }

    /** 等待关闭线程执行到 shutdown，避免线程启动调度造成脆弱断言。 */
    private void awaitShutdown(ExecutorService executor) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(1L);
        while (!executor.isShutdown() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(executor.isShutdown()).isTrue();
    }
}
