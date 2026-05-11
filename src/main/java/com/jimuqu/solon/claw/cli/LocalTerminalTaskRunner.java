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

/** Runs local terminal requests in the background so control input stays responsive. */
public class LocalTerminalTaskRunner implements AutoCloseable {
    private static final int MAX_RECENT_TASKS = 20;

    private final PrintWriter writer;
    private final ExecutorService executorService;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<TaskRecord> records = new ArrayList<TaskRecord>();

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
                .append("操作：/queue <提示> 排队，/steer <提示> 注入运行中任务，/stop 停止当前 run，/busy status 查看策略。");
        return buffer.toString();
    }

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

    public List<TaskSnapshot> snapshots() {
        synchronized (records) {
            List<TaskSnapshot> snapshots = new ArrayList<TaskSnapshot>(records.size());
            for (TaskRecord record : records) {
                snapshots.add(record.snapshot());
            }
            return snapshots;
        }
    }

    public Future<Integer> submit(final String input, final Callable<Integer> task) {
        final TaskRecord record = addRecord(input);
        running.incrementAndGet();
        Future<Integer> future =
                executorService.submit(
                        new Callable<Integer>() {
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
                                                    + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
                                    return Integer.valueOf(1);
                                } finally {
                                    running.decrementAndGet();
                                }
                            }
                        });
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
        String value =
                SecretRedactor.redact(
                        StrUtil.nullToEmpty(input).replace('\r', ' ').replace('\n', ' ').trim(),
                        1200);
        if (value.length() <= 40) {
            return StrUtil.blankToDefault(value, "-");
        }
        return value.substring(0, 40) + "...";
    }

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

    private TaskStatus statusForExitCode(int exitCode) {
        if (exitCode == 0) {
            return TaskStatus.SUCCESS;
        }
        if (exitCode == 130) {
            return TaskStatus.INTERRUPTED;
        }
        return TaskStatus.FAILED;
    }

    private String formatTime(long millis) {
        if (millis <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("HH:mm:ss").format(new Date(millis));
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

    private enum TaskStatus {
        RUNNING("running"),
        SUCCESS("success"),
        FAILED("failed"),
        INTERRUPTED("interrupted");

        private final String label;

        TaskStatus(String label) {
            this.label = label;
        }
    }

    private static final class TaskRecord {
        private final int id;
        private final String label;
        private final long startedAtMillis;
        private TaskStatus status = TaskStatus.RUNNING;
        private long completedAtMillis;
        private Integer exitCode;

        private TaskRecord(int id, String label) {
            this.id = id;
            this.label = label;
            this.startedAtMillis = System.currentTimeMillis();
        }

        private synchronized void complete(TaskStatus status, int exitCode) {
            this.status = status;
            this.exitCode = Integer.valueOf(exitCode);
            this.completedAtMillis = System.currentTimeMillis();
        }

        private synchronized TaskSnapshot snapshot() {
            return new TaskSnapshot(id, label, status.label, startedAtMillis, completedAtMillis, exitCode);
        }
    }

    public static final class TaskSnapshot {
        private final int id;
        private final String label;
        private final String statusLabel;
        private final long startedAtMillis;
        private final long completedAtMillis;
        private final Integer exitCode;

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

        public int getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public String getStatusLabel() {
            return statusLabel;
        }

        public long getStartedAtMillis() {
            return startedAtMillis;
        }

        public long getCompletedAtMillis() {
            return completedAtMillis;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public String getExitCodeText() {
            return exitCode == null ? "-" : String.valueOf(exitCode.intValue());
        }
    }
}
