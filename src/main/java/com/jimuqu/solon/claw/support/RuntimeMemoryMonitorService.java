package com.jimuqu.solon.claw.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records lightweight JVM memory snapshots for runtime diagnostics. */
public class RuntimeMemoryMonitorService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMemoryMonitorService.class);
    private static final long DEFAULT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final Object lifecycleLock = new Object();
    private final long intervalMs;
    private volatile long startedAt;
    private volatile MemorySnapshot baseline;
    private volatile MemorySnapshot latest;
    private volatile ScheduledExecutorService scheduler;

    public RuntimeMemoryMonitorService() {
        this(DEFAULT_INTERVAL_MS);
    }

    public RuntimeMemoryMonitorService(long intervalMs) {
        this.intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    }

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
                                @Override
                                public Thread newThread(Runnable runnable) {
                                    Thread thread =
                                            new Thread(
                                                    runnable, "solon-claw-runtime-memory-monitor");
                                    thread.setDaemon(true);
                                    return thread;
                                }
                            });
            scheduler.scheduleAtFixedRate(
                    new Runnable() {
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

    public Map<String, Object> captureSnapshot(String tag) {
        MemorySnapshot snapshot = snapshot(tag);
        latest = snapshot;
        logSnapshot(snapshot);
        return snapshot.toMap();
    }

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

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("enabled", Boolean.valueOf(startedAt > 0));
        status.put("running", Boolean.valueOf(isRunning()));
        status.put("interval_ms", Long.valueOf(intervalMs));
        status.put("baseline", baseline == null ? null : baseline.toMap());
        status.put("latest", latest == null ? null : latest.toMap());
        return status;
    }

    public boolean isRunning() {
        ScheduledExecutorService current = scheduler;
        return current != null && !current.isShutdown() && !current.isTerminated();
    }

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

    private int threadCount() {
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            return threadBean.getThreadCount();
        } catch (Exception e) {
            return Thread.activeCount();
        }
    }

    private String timestampIso(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date(timestamp));
    }

    private String safeTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            return "snapshot";
        }
        return tag.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

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

    private static final class MemorySnapshot {
        private final String tag;
        private final long timestamp;
        private final String timestampIso;
        private final long usedMb;
        private final long maxMb;
        private final long freeMb;
        private final int threadCount;
        private final long uptimeMs;

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
