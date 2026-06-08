package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 驱动本地终端任务运行流程，连接命令入口与业务服务。 */
public class LocalTerminalTaskRunner implements AutoCloseable {
    /** 最大RECENTTASKS的统一常量值。 */
    private static final int MAX_RECENT_TASKS = 20;

    /** 记录本地终端任务中的writer。 */
    private final PrintWriter writer;

    /** 保存执行器服务执行组件，负责调度异步或定时任务。 */
    private final ExecutorService executorService;

    /** 记录本地终端任务中的running。 */
    private final AtomicInteger running = new AtomicInteger();

    /** 记录本地终端任务中的sequence。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 保存records集合，维持调用顺序或去重语义。 */
    private final List<TaskRecord> records = new ArrayList<TaskRecord>();

    /**
     * 创建本地终端任务Runner实例，并注入运行所需依赖。
     *
     * @param writer writer 参数。
     */
    public LocalTerminalTaskRunner(PrintWriter writer) {
        this(writer, Executors.newCachedThreadPool());
    }

    /**
     * 创建本地终端任务Runner实例，并注入运行所需依赖。
     *
     * @param writer writer 参数。
     * @param executorService executor服务依赖。
     */
    LocalTerminalTaskRunner(PrintWriter writer, ExecutorService executorService) {
        this.writer = writer;
        this.executorService = executorService;
    }

    /**
     * 执行running次数相关逻辑。
     *
     * @return 返回running次数结果。
     */
    public int runningCount() {
        return running.get();
    }

    /**
     * 判断是否存在Running。
     *
     * @return 如果Running满足条件则返回 true，否则返回 false。
     */
    public boolean hasRunning() {
        return running.get() > 0;
    }

    /**
     * 渲染Tasks。
     *
     * @return 返回render Tasks结果。
     */
    public String renderTasks() {
        List<TaskSnapshot> snapshots = snapshots();
        if (snapshots.isEmpty()) {
            return "暂无终端后台任务。";
        }
        StringBuilder buffer = new StringBuilder();
        buffer.append("终端后台任务：running=")
                .append(runningCount())
                .append(" recent=")
                .append(snapshots.size());
        for (TaskSnapshot snapshot : snapshots) {
            buffer.append('\n')
                    .append('#')
                    .append(snapshot.getId())
                    .append(' ')
                    .append(snapshot.getStatusLabel())
                    .append(" exit=")
                    .append(snapshot.getExitCodeText())
                    .append(" started=")
                    .append(formatTime(snapshot.getStartedAtMillis()));
            if (snapshot.getCompletedAtMillis() > 0L) {
                buffer.append(" completed=").append(formatTime(snapshot.getCompletedAtMillis()));
            }
            buffer.append(" input=").append(snapshot.getLabel());
        }
        buffer.append('\n')
                .append(
                        "操作：/queue <提示> 排队，/steer <提示> 注入运行中任务，"
                                + "/busy interrupt 切换为新输入中断当前任务，/busy reject 切换为忙时拒绝输入，"
                                + "/stop 停止当前 run，/busy status 查看策略。");
        return buffer.toString();
    }

    /**
     * 渲染退出保护。
     *
     * @param exitCommand 退出命令参数。
     * @return 返回render Exit保护结果。
     */
    public String renderExitGuard(String exitCommand) {
        String command = StrUtil.blankToDefault(exitCommand, "/exit").trim();
        String forceCommand = command.endsWith("!") ? command : command + "!";
        StringBuilder buffer = new StringBuilder();
        buffer.append("仍有终端后台任务运行中，已暂停退出。")
                .append('\n')
                .append("输入 ")
                .append(forceCommand)
                .append(" 将停止运行中的任务并退出，输入 /tasks 查看详情。")
                .append('\n')
                .append(renderTasks());
        return buffer.toString();
    }

    /**
     * 执行snapshots相关逻辑。
     *
     * @return 返回snapshots结果。
     */
    public List<TaskSnapshot> snapshots() {
        synchronized (records) {
            List<TaskSnapshot> snapshots = new ArrayList<TaskSnapshot>(records.size());
            for (TaskRecord record : records) {
                snapshots.add(record.snapshot());
            }
            return snapshots;
        }
    }

    /**
     * 执行submit相关逻辑。
     *
     * @param input 输入参数。
     * @param task 任务参数。
     * @return 返回submit结果。
     */
    public Future<Integer> submit(final String input, final Callable<Integer> task) {
        final TaskRecord record = addRecord(input);
        running.incrementAndGet();
        Future<Integer> future =
                executorService.submit(
                        new Callable<Integer>() {
                            /**
                             * 执行回调调用并返回结果。
                             *
                             * @return 返回call结果。
                             */
                            @Override
                            public Integer call() {
                                try {
                                    Integer exitCode = task.call();
                                    int code = exitCode == null ? 0 : exitCode.intValue();
                                    record.complete(statusForExitCode(code), code);
                                    return Integer.valueOf(code);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    record.complete(TaskStatus.INTERRUPTED, 130);
                                    printLine("终端任务已中断：" + label(input));
                                    return Integer.valueOf(130);
                                } catch (Exception e) {
                                    record.complete(TaskStatus.FAILED, 1);
                                    printLine(
                                            "终端任务失败："
                                                    + label(input)
                                                    + " - "
                                                    + StrUtil.blankToDefault(
                                                            e.getMessage(),
                                                            e.getClass().getSimpleName()));
                                    return Integer.valueOf(1);
                                } finally {
                                    running.decrementAndGet();
                                }
                            }
                        });
        printLine("已提交到后台；运行中可继续输入 /queue、/steer、/stop 或 /busy status。");
        return future;
    }

    /**
     * 执行cancelAnd关闭相关逻辑。
     *
     * @param cancelActiveRun cancelActive运行参数。
     */
    public void cancelAndClose(Runnable cancelActiveRun) {
        if (cancelActiveRun != null && hasRunning()) {
            try {
                cancelActiveRun.run();
            } catch (Exception ignored) {
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
        executorService.shutdownNow();
        awaitTermination(2L, TimeUnit.SECONDS);
    }

    /** 关闭当前组件持有的运行资源。 */
    @Override
    public void close() {
        executorService.shutdown();
        awaitTermination(5L, TimeUnit.SECONDS);
    }

    /**
     * 执行awaitTermination相关逻辑。
     *
     * @param timeout 超时时间或等待上限。
     * @param unit unit 参数。
     */
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

    /**
     * 执行label相关逻辑。
     *
     * @param input 输入参数。
     * @return 返回label结果。
     */
    private String label(String input) {
        String value =
                SecretRedactor.redact(
                        StrUtil.nullToEmpty(input).replace('\r', ' ').replace('\n', ' ').trim(),
                        1200);
        if (value.length() <= 40) {
            return StrUtil.blankToDefault(value, "-");
        }
        return value.substring(0, 40) + "...";
    }

    /**
     * 追加记录。
     *
     * @param input 输入参数。
     * @return 返回add记录结果。
     */
    private TaskRecord addRecord(String input) {
        TaskRecord record = new TaskRecord(sequence.incrementAndGet(), label(input));
        synchronized (records) {
            records.add(record);
            while (records.size() > MAX_RECENT_TASKS) {
                records.remove(0);
            }
        }
        return record;
    }

    /**
     * 执行状态For退出Code相关逻辑。
     *
     * @param exitCode 命令退出码。
     * @return 返回状态For 退出码结果。
     */
    private TaskStatus statusForExitCode(int exitCode) {
        if (exitCode == 0) {
            return TaskStatus.SUCCESS;
        }
        if (exitCode == 130) {
            return TaskStatus.INTERRUPTED;
        }
        return TaskStatus.FAILED;
    }

    /**
     * 格式化时间。
     *
     * @param millis millis 参数。
     * @return 返回时间结果。
     */
    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("HH:mm:ss").format(new Date(millis));
    }

    /**
     * 执行print行相关逻辑。
     *
     * @param text 待处理文本。
     */
    private void printLine(String text) {
        if (writer == null) {
            return;
        }
        synchronized (writer) {
            writer.println(text);
            writer.flush();
        }
    }

    /** 枚举任务状态的可选值，保证状态表达在各模块间一致。 */
    private enum TaskStatus {
        /** 表示RUNNING枚举值。 */
        RUNNING("running"),
        /** 表示SUCCESS枚举值。 */
        SUCCESS("success"),
        /** 表示FAILED枚举值。 */
        FAILED("failed"),
        /** 表示整型ERRUPTED枚举值。 */
        INTERRUPTED("interrupted");

        /** 记录本地终端任务中的label。 */
        private final String label;

        /**
         * 创建任务状态实例，并注入运行所需依赖。
         *
         * @param label label 参数。
         */
        TaskStatus(String label) {
            this.label = label;
        }
    }

    /** 表示任务数据，在服务、仓储和接口之间传递。 */
    private static final class TaskRecord {
        /** 记录任务中的标识。 */
        private final int id;

        /** 记录任务中的label。 */
        private final String label;

        /** 记录任务中的started时间Millis。 */
        private final long startedAtMillis;

        /** 记录任务中的状态。 */
        private TaskStatus status = TaskStatus.RUNNING;

        /** 记录任务中的completed时间Millis。 */
        private long completedAtMillis;

        /** 记录任务中的退出Code。 */
        private Integer exitCode;

        /**
         * 创建任务记录实例，并注入运行所需依赖。
         *
         * @param id 标识。
         * @param label label 参数。
         */
        private TaskRecord(int id, String label) {
            this.id = id;
            this.label = label;
            this.startedAtMillis = System.currentTimeMillis();
        }

        /**
         * 执行complete相关逻辑。
         *
         * @param status 状态参数。
         * @param exitCode 命令退出码。
         */
        private synchronized void complete(TaskStatus status, int exitCode) {
            this.status = status;
            this.exitCode = Integer.valueOf(exitCode);
            this.completedAtMillis = System.currentTimeMillis();
        }

        /**
         * 执行snapshot相关逻辑。
         *
         * @return 返回snapshot结果。
         */
        private synchronized TaskSnapshot snapshot() {
            return new TaskSnapshot(
                    id, label, status.label, startedAtMillis, completedAtMillis, exitCode);
        }
    }

    /** 承载任务快照相关状态和辅助逻辑。 */
    public static final class TaskSnapshot {
        /** 记录任务快照中的标识。 */
        private final int id;

        /** 记录任务快照中的label。 */
        private final String label;

        /** 记录任务快照中的状态Label。 */
        private final String statusLabel;

        /** 记录任务快照中的started时间Millis。 */
        private final long startedAtMillis;

        /** 记录任务快照中的completed时间Millis。 */
        private final long completedAtMillis;

        /** 记录任务快照中的退出Code。 */
        private final Integer exitCode;

        /**
         * 创建任务Snapshot实例，并注入运行所需依赖。
         *
         * @param id 标识。
         * @param label label 参数。
         * @param statusLabel 状态Label参数。
         * @param startedAtMillis startedAtMillis 参数。
         * @param completedAtMillis completedAtMillis 参数。
         * @param exitCode 命令退出码。
         */
        private TaskSnapshot(
                int id,
                String label,
                String statusLabel,
                long startedAtMillis,
                long completedAtMillis,
                Integer exitCode) {
            this.id = id;
            this.label = label;
            this.statusLabel = statusLabel;
            this.startedAtMillis = startedAtMillis;
            this.completedAtMillis = completedAtMillis;
            this.exitCode = exitCode;
        }

        /**
         * 读取标识。
         *
         * @return 返回读取到的标识。
         */
        public int getId() {
            return id;
        }

        /**
         * 读取Label。
         *
         * @return 返回读取到的Label。
         */
        public String getLabel() {
            return label;
        }

        /**
         * 读取状态Label。
         *
         * @return 返回读取到的状态Label。
         */
        public String getStatusLabel() {
            return statusLabel;
        }

        /**
         * 读取Started时间Millis。
         *
         * @return 返回读取到的Started时间Millis。
         */
        public long getStartedAtMillis() {
            return startedAtMillis;
        }

        /**
         * 读取Completed时间Millis。
         *
         * @return 返回读取到的Completed时间Millis。
         */
        public long getCompletedAtMillis() {
            return completedAtMillis;
        }

        /**
         * 读取退出码。
         *
         * @return 返回读取到的退出码。
         */
        public Integer getExitCode() {
            return exitCode;
        }

        /**
         * 读取退出码 Text。
         *
         * @return 返回读取到的退出码 Text。
         */
        public String getExitCodeText() {
            return exitCode == null ? "-" : String.valueOf(exitCode.intValue());
        }
    }
}
