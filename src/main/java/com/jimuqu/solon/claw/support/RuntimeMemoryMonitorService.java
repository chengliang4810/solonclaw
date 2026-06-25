package com.jimuqu.solon.claw.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供运行时记忆Monitor相关业务能力，封装调用方不需要感知的运行细节。 */
public class RuntimeMemoryMonitorService {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(RuntimeMemoryMonitorService.class);

    /** 默认整型ERVALMS的统一常量值。 */
    private static final long DEFAULT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

    /** 字节PERMB的统一常量值。 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 运行时内存快照 ISO 时间格式，等价于旧 yyyy-MM-dd'T'HH:mm:ss.SSSZ 输出。 */
    private static final DateTimeFormatter SNAPSHOT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    .withZone(ZoneId.systemDefault());

    /** 记录运行时记忆Monitor中的生命周期Lock。 */
    private final Object lifecycleLock = new Object();

    /** 记录运行时记忆Monitor中的intervalMs。 */
    private final long intervalMs;

    /** 记录运行时记忆Monitor中的started时间。 */
    private volatile long startedAt;

    /** 记录运行时记忆Monitor中的baseline。 */
    private volatile MemorySnapshot baseline;

    /** 记录运行时记忆Monitor中的latest。 */
    private volatile MemorySnapshot latest;

    /** 保存调度器执行组件，负责调度异步或定时任务。 */
    private volatile ScheduledExecutorService scheduler;

    /** 创建运行时记忆Monitor服务实例。 */
    public RuntimeMemoryMonitorService() {
        this(DEFAULT_INTERVAL_MS);
    }

    /**
     * 创建运行时记忆Monitor服务实例，并注入运行所需依赖。
     *
     * @param intervalMs intervalMs 参数。
     */
    public RuntimeMemoryMonitorService(long intervalMs) {
        this.intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    }

    /**
     * 启动当前组件并准备运行资源。
     *
     * @return 返回start结果。
     */
    public boolean start() {
        synchronized (lifecycleLock) {
            if (isRunning()) {
                return false;
            }
            startedAt = System.currentTimeMillis();
            baseline = snapshot("baseline");
            latest = baseline;
            logSnapshot(baseline);
            scheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            new ThreadFactory() {
                                /**
                                 * 创建Thread。
                                 *
                                 * @param runnable runnable 参数。
                                 * @return 返回创建好的Thread。
                                 */
                                @Override
                                public Thread newThread(Runnable runnable) {
                                    Thread thread =
                                            new Thread(
                                                    runnable, "solonclaw-workspace-memory-monitor");
                                    thread.setDaemon(true);
                                    return thread;
                                }
                            });
            scheduler.scheduleAtFixedRate(
                    new Runnable() {
                        /** 执行异步任务主体。 */
                        @Override
                        public void run() {
                            try {
                                captureSnapshot("periodic");
                            } catch (Exception e) {
                                log.debug(
                                        "Runtime memory monitor snapshot failed: {}", e.toString());
                            }
                        }
                    },
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS);
            return true;
        }
    }

    /**
     * 执行captureSnapshot相关逻辑。
     *
     * @param tag tag 参数。
     * @return 返回capture Snapshot结果。
     */
    public Map<String, Object> captureSnapshot(String tag) {
        MemorySnapshot snapshot = snapshot(tag);
        latest = snapshot;
        logSnapshot(snapshot);
        return snapshot.toMap();
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        ScheduledExecutorService toShutdown;
        synchronized (lifecycleLock) {
            if (!isRunning()) {
                return;
            }
            captureSnapshot("shutdown");
            toShutdown = scheduler;
            scheduler = null;
        }
        toShutdown.shutdownNow();
    }

    /**
     * 执行状态相关逻辑。
     *
     * @return 返回状态。
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("enabled", Boolean.valueOf(startedAt > 0));
        status.put("running", Boolean.valueOf(isRunning()));
        status.put("interval_ms", Long.valueOf(intervalMs));
        status.put("baseline", baseline == null ? null : baseline.toMap());
        status.put("latest", latest == null ? null : latest.toMap());
        return status;
    }

    /**
     * 判断是否Running。
     *
     * @return 如果Running满足条件则返回 true，否则返回 false。
     */
    public boolean isRunning() {
        ScheduledExecutorService current = scheduler;
        return current != null && !current.isShutdown() && !current.isTerminated();
    }

    /**
     * 执行snapshot相关逻辑。
     *
     * @param tag tag 参数。
     * @return 返回snapshot结果。
     */
    private MemorySnapshot snapshot(String tag) {
        long now = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        long freeMb = runtime.freeMemory() / BYTES_PER_MB;
        long totalMb = runtime.totalMemory() / BYTES_PER_MB;
        long usedMb = Math.max(0L, totalMb - freeMb);
        long maxMb = runtime.maxMemory() / BYTES_PER_MB;
        long uptimeMs = startedAt <= 0 ? 0L : Math.max(0L, now - startedAt);
        return new MemorySnapshot(
                safeTag(tag),
                now,
                timestampIso(now),
                usedMb,
                maxMb,
                freeMb,
                threadCount(),
                uptimeMs);
    }

    /**
     * 执行thread次数相关逻辑。
     *
     * @return 返回thread次数结果。
     */
    private int threadCount() {
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            return threadBean.getThreadCount();
        } catch (Exception e) {
            return Thread.activeCount();
        }
    }

    /**
     * 执行时间戳Iso相关逻辑。
     *
     * @param timestamp 请求携带的时间戳。
     * @return 返回时间戳Iso结果。
     */
    private String timestampIso(long timestamp) {
        return SNAPSHOT_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * 生成安全展示用的Tag。
     *
     * @param tag tag 参数。
     * @return 返回safe Tag结果。
     */
    private String safeTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            return "snapshot";
        }
        return tag.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * 执行日志Snapshot相关逻辑。
     *
     * @param snapshot snapshot 参数。
     */
    private void logSnapshot(MemorySnapshot snapshot) {
        log.info(
                "[MEMORY] {} used={}MB max={}MB free={}MB threads={} uptime={}ms",
                snapshot.tag,
                Long.valueOf(snapshot.usedMb),
                Long.valueOf(snapshot.maxMb),
                Long.valueOf(snapshot.freeMb),
                Integer.valueOf(snapshot.threadCount),
                Long.valueOf(snapshot.uptimeMs));
    }

    /** 承载记忆快照相关状态和辅助逻辑。 */
    private static final class MemorySnapshot {
        /** 记录记忆快照中的tag。 */
        private final String tag;

        /** 记录记忆快照中的时间戳。 */
        private final long timestamp;

        /** 记录记忆快照中的时间戳Iso。 */
        private final String timestampIso;

        /** 记录记忆快照中的使用Mb。 */
        private final long usedMb;

        /** 记录记忆快照中的maxMb。 */
        private final long maxMb;

        /** 记录记忆快照中的freeMb。 */
        private final long freeMb;

        /** 记录记忆快照中的thread次数。 */
        private final int threadCount;

        /** 记录记忆快照中的uptimeMs。 */
        private final long uptimeMs;

        /**
         * 创建记忆Snapshot实例，并注入运行所需依赖。
         *
         * @param tag tag 参数。
         * @param timestamp 请求携带的时间戳。
         * @param timestampIso 时间戳Iso参数。
         * @param usedMb usedMb 参数。
         * @param maxMb maxMb 参数。
         * @param freeMb freeMb 参数。
         * @param threadCount threadCount 参数。
         * @param uptimeMs uptimeMs 参数。
         */
        private MemorySnapshot(
                String tag,
                long timestamp,
                String timestampIso,
                long usedMb,
                long maxMb,
                long freeMb,
                int threadCount,
                long uptimeMs) {
            this.tag = tag;
            this.timestamp = timestamp;
            this.timestampIso = timestampIso;
            this.usedMb = usedMb;
            this.maxMb = maxMb;
            this.freeMb = freeMb;
            this.threadCount = threadCount;
            this.uptimeMs = uptimeMs;
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("tag", tag);
            map.put("used_mb", Long.valueOf(usedMb));
            map.put("max_mb", Long.valueOf(maxMb));
            map.put("free_mb", Long.valueOf(freeMb));
            map.put("thread_count", Integer.valueOf(threadCount));
            map.put("uptime_ms", Long.valueOf(uptimeMs));
            map.put("timestamp", Long.valueOf(timestamp));
            map.put("timestamp_iso", timestampIso);
            return map;
        }
    }
}
