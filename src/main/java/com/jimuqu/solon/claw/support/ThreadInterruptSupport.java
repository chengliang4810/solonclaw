package com.jimuqu.solon.claw.support;

/** 线程中断状态工具，避免异常链中的中断信号被吞掉。 */
public final class ThreadInterruptSupport {
    /** 异常链最大检查层数，避免异常 cause 环导致死循环。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** 工具类不允许创建实例。 */
    private ThreadInterruptSupport() {}

    /**
     * 当异常链中包含 InterruptedException 时恢复当前线程的中断标记。
     *
     * @param error 捕获到的异常。
     */
    public static void restoreIfCausedByInterrupted(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            current = current.getCause();
            depth++;
        }
    }
}
