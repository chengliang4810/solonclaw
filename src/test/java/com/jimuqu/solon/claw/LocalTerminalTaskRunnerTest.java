package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.LocalTerminalTaskRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class LocalTerminalTaskRunnerTest {
    @Test
    void shouldSubmitTerminalWorkWithoutBlockingInputLoop() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        LocalTerminalTaskRunner runner =
                new LocalTerminalTaskRunner(
                        new PrintWriter(
                                new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8),
                                true));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> future =
                runner.submit(
                        "long request",
                        new Callable<Integer>() {
                            @Override
                            public Integer call() throws Exception {
                                started.countDown();
                                release.await(2, TimeUnit.SECONDS);
                                return Integer.valueOf(0);
                            }
                        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isFalse();
        assertThat(runner.hasRunning()).isTrue();
        assertThat(runner.snapshots()).hasSize(1);
        assertThat(runner.snapshots().get(0).getStatusLabel()).isEqualTo("running");
        assertThat(runner.renderTasks())
                .contains("终端后台任务")
                .contains("running=1")
                .contains("long request")
                .contains("exit=-");
        assertThat(buffer.toString(StandardCharsets.UTF_8.name())).contains("已提交到后台");

        release.countDown();
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(0);
        assertThat(runner.snapshots().get(0).getStatusLabel()).isEqualTo("success");
        assertThat(runner.snapshots().get(0).getExitCodeText()).isEqualTo("0");
        assertThat(runner.renderTasks()).contains("success").contains("exit=0");
        runner.close();
    }

    @Test
    void shouldCancelRunningTerminalWorkOnShutdown() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        LocalTerminalTaskRunner runner =
                new LocalTerminalTaskRunner(
                        new PrintWriter(
                                new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8),
                                true));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean stopCalled = new AtomicBoolean(false);

        runner.submit(
                "long request",
                new Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        started.countDown();
                        Thread.sleep(5000L);
                        return Integer.valueOf(0);
                    }
                });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        runner.cancelAndClose(
                new Runnable() {
                    @Override
                    public void run() {
                        stopCalled.set(true);
                    }
                });

        assertThat(stopCalled).isTrue();
        assertThat(runner.snapshots().get(0).getStatusLabel()).isEqualTo("interrupted");
        assertThat(runner.snapshots().get(0).getExitCodeText()).isEqualTo("130");
        assertThat(buffer.toString(StandardCharsets.UTF_8.name())).contains("终端任务已中断");
    }

    @Test
    void shouldRetainOnlyRecentTerminalTasks() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        LocalTerminalTaskRunner runner =
                new LocalTerminalTaskRunner(
                        new PrintWriter(
                                new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8),
                                true));

        for (int i = 1; i <= 22; i++) {
            final int index = i;
            runner.submit(
                            "task " + index,
                            new Callable<Integer>() {
                                @Override
                                public Integer call() {
                                    return Integer.valueOf(index == 22 ? 2 : 0);
                                }
                            })
                    .get(1, TimeUnit.SECONDS);
        }

        assertThat(runner.snapshots()).hasSize(20);
        assertThat(runner.snapshots().get(0).getLabel()).isEqualTo("task 3");
        assertThat(runner.snapshots().get(19).getLabel()).isEqualTo("task 22");
        assertThat(runner.snapshots().get(19).getStatusLabel()).isEqualTo("failed");
        assertThat(runner.snapshots().get(19).getExitCodeText()).isEqualTo("2");
        runner.close();
    }
}
