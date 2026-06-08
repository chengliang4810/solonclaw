package com.jimuqu.solon.claw.support;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 创建受限执行器相关实例，统一封装构造参数与默认策略。 */
public final class BoundedExecutorFactory {
    /** 创建受限执行器工厂实例。 */
    private BoundedExecutorFactory() {}

    /**
     * 执行fixed相关逻辑。
     *
     * @param name 名称参数。
     * @param threads threads 参数。
     * @param queueCapacity 队列Capacity参数。
     * @return 返回fixed结果。
     */
    public static ExecutorService fixed(String name, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(Math.max(1, queueCapacity)),
                namedFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 执行scheduled相关逻辑。
     *
     * @param name 名称参数。
     * @param threads threads 参数。
     * @return 返回scheduled结果。
     */
    public static ScheduledExecutorService scheduled(String name, int threads) {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(Math.max(1, threads), namedFactory(name));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 执行指定名称工厂相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回指定名称工厂结果。
     */
    private static ThreadFactory namedFactory(final String name) {
        return new ThreadFactory() {
            /** 记录受限执行器中的sequence。 */
            private final AtomicInteger sequence = new AtomicInteger(1);

            /**
             * 创建Thread。
             *
             * @param runnable runnable 参数。
             * @return 返回创建好的Thread。
             */
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + sequence.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
