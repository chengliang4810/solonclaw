package com.jimuqu.solon.claw.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** 执行器关闭辅助类，优先排空已接收任务并在超时后强制终止。 */
public final class ExecutorShutdownSupport {
    /** 默认排空等待时间，单位秒。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 10L;

    /** 工具类不允许实例化。 */
    private ExecutorShutdownSupport() {}

    /**
     * 在有限时间内排空执行器，超时或中断后强制终止剩余任务。
     *
     * @param executorService 待关闭执行器，可为空。
     */
    public static void drain(ExecutorService executorService) {
        drain(executorService, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 在指定时间内排空执行器，超时或中断后强制终止剩余任务。
     *
     * @param executorService 待关闭执行器，可为空。
     * @param timeout 最大等待时间。
     * @param unit 等待时间单位。
     */
    public static void drain(ExecutorService executorService, long timeout, TimeUnit unit) {
        if (executorService == null) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(Math.max(0L, timeout), unit)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
