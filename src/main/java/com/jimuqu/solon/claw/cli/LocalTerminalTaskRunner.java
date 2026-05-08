package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs local terminal requests in the background so control input stays responsive. */
public class LocalTerminalTaskRunner implements AutoCloseable {
    private final PrintWriter writer;
    private final ExecutorService executorService;
    private final Set<Future<Integer>> futures = Collections.synchronizedSet(new HashSet<Future<Integer>>());
    private final AtomicInteger running = new AtomicInteger();

    public LocalTerminalTaskRunner(PrintWriter writer) {
        this(writer, Executors.newCachedThreadPool());
    }

    LocalTerminalTaskRunner(PrintWriter writer, ExecutorService executorService) {
        this.writer = writer;
        this.executorService = executorService;
    }

    public int runningCount() {
        return running.get();
    }

    public boolean hasRunning() {
        return running.get() > 0;
    }

    public Future<Integer> submit(final String input, final Callable<Integer> task) {
        running.incrementAndGet();
        Future<Integer> future =
                executorService.submit(
                        new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                try {
                                    return task.call();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    printLine("终端任务已中断：" + label(input));
                                    return Integer.valueOf(130);
                                } catch (Exception e) {
                                    printLine(
                                            "终端任务失败："
                                                    + label(input)
                                                    + " - "
                                                    + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
                                    return Integer.valueOf(1);
                                } finally {
                                    running.decrementAndGet();
                                }
                            }
                        });
        futures.add(future);
        printLine("已提交到后台；运行中可继续输入 /queue、/steer、/stop 或 /busy status。");
        return future;
    }

    public void cancelAndClose(Runnable cancelActiveRun) {
        if (cancelActiveRun != null && hasRunning()) {
            try {
                cancelActiveRun.run();
            } catch (Exception ignored) {
                // best effort only during terminal shutdown
            }
        }
        executorService.shutdownNow();
        awaitTermination(2L, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        executorService.shutdown();
        awaitTermination(5L, TimeUnit.SECONDS);
    }

    private void awaitTermination(long timeout, TimeUnit unit) {
        try {
            if (!executorService.awaitTermination(timeout, unit)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String label(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        if (value.length() <= 40) {
            return StrUtil.blankToDefault(value, "-");
        }
        return value.substring(0, 40) + "...";
    }

    private void printLine(String text) {
        if (writer == null) {
            return;
        }
        synchronized (writer) {
            writer.println(text);
            writer.flush();
        }
    }
}
