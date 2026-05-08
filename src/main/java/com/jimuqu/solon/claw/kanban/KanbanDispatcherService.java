package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Jimuqu Kanban dispatcher tick. */
public class KanbanDispatcherService {
    private static final long DEFAULT_CLAIM_TTL_SECONDS = 900L;
    private static final int DEFAULT_FAILURE_LIMIT = 3;
    private static final int DEFAULT_DAEMON_INTERVAL_SECONDS = 60;

    private final KanbanRepository repository;
    private final KanbanService kanbanService;
    private final KanbanWorkerSpawner workerSpawner;
    private final Object daemonLock = new Object();
    private final AtomicBoolean daemonRunning = new AtomicBoolean(false);
    private volatile Thread daemonThread;
    private volatile String daemonBoard;
    private volatile int daemonMaxSpawn;
    private volatile int daemonFailureLimit = DEFAULT_FAILURE_LIMIT;
    private volatile long daemonTtlSeconds = DEFAULT_CLAIM_TTL_SECONDS;
    private volatile int daemonIntervalSeconds = DEFAULT_DAEMON_INTERVAL_SECONDS;
    private volatile boolean daemonDryRun;
    private volatile long daemonStartedAt;
    private volatile long daemonLastTickAt;
    private volatile long daemonTickCount;
    private volatile Map<String, Object> daemonLastResult;
    private volatile String daemonLastError;
    private volatile DaemonTickHook daemonTickHook;

    public KanbanDispatcherService(
            KanbanRepository repository,
            KanbanService kanbanService,
            KanbanWorkerSpawner workerSpawner) {
        this.repository = repository;
        this.kanbanService = kanbanService;
        this.workerSpawner = workerSpawner;
    }

    public Map<String, Object> dispatch(Map<String, Object> body) throws Exception {
        String board = text(body, "board");
        int maxSpawn = intValue(body, "max_spawn", 0);
        int failureLimit = intValue(body, "failure_limit", DEFAULT_FAILURE_LIMIT);
        long ttlSeconds = longValue(body, "ttl_seconds", DEFAULT_CLAIM_TTL_SECONDS);
        boolean dryRun = booleanValue(body, "dry_run");
        return dispatchOnce(board, maxSpawn, dryRun, ttlSeconds, failureLimit).toMap();
    }

    public KanbanDispatchResult dispatchOnce(
            String board, int maxSpawn, boolean dryRun, long ttlSeconds, int failureLimit)
            throws Exception {
        KanbanDispatchResult result = new KanbanDispatchResult();
        long now = System.currentTimeMillis();
        result.setReclaimed(repository.releaseStaleClaims(now));
        result.setTimedOut(repository.reclaimTimedOutWorkers(now));
        result.setPromoted(repository.recomputeReady(board));

        int spawned = 0;
        List<KanbanTaskRecord> readyTasks = repository.listReadyTasks(board);
        for (KanbanTaskRecord task : readyTasks) {
            if (maxSpawn > 0 && spawned >= maxSpawn) {
                break;
            }
            if (StrUtil.isBlank(task.getAssignee())) {
                result.getSkippedUnassigned().add(task.getTaskId());
                continue;
            }
            if (dryRun) {
                result.addSpawned(task, resolveWorkspacePath(task), 0L);
                continue;
            }
            String claimer = defaultClaimer(task);
            KanbanTaskRecord claimed =
                    repository.claimTask(
                            task.getTaskId(), claimer, ttlSeconds, task.getAssignee(), 0L);
            if (claimed == null) {
                continue;
            }
            try {
                String workspace = resolveWorkspacePath(claimed);
                repository.setWorkspacePath(claimed.getTaskId(), workspace);
                Map<String, Object> detail = kanbanService.task(claimed.getTaskId());
                String workerContext = String.valueOf(detail.get("worker_context"));
                long pid = workerSpawner.spawn(claimed, workspace, workerContext);
                repository.clearSpawnFailures(claimed.getTaskId(), pid);
                KanbanTaskRecord latest = repository.findTask(claimed.getTaskId());
                result.addSpawned(latest == null ? claimed : latest, workspace, pid);
                spawned++;
            } catch (Exception e) {
                String error = StrUtil.blankToDefault(e.getMessage(), e.toString());
                repository.markSpawnFailure(claimed.getTaskId(), error);
                result.getSpawnFailures().add(claimed.getTaskId());
                if (repository.autoBlockAfterSpawnFailure(claimed.getTaskId(), failureLimit, error)) {
                    result.getAutoBlocked().add(claimed.getTaskId());
                }
            }
        }
        return result;
    }

    public Map<String, Object> startDaemon(Map<String, Object> body) {
        synchronized (daemonLock) {
            if (daemonRunning.get()) {
                Map<String, Object> status = daemonStatus();
                status.put("already_running", Boolean.TRUE);
                return status;
            }
            daemonBoard = text(body, "board");
            daemonMaxSpawn = intValue(body, "max_spawn", 0);
            daemonFailureLimit = intValue(body, "failure_limit", DEFAULT_FAILURE_LIMIT);
            daemonTtlSeconds = longValue(body, "ttl_seconds", DEFAULT_CLAIM_TTL_SECONDS);
            daemonIntervalSeconds = Math.max(1, intValue(body, "interval_seconds", DEFAULT_DAEMON_INTERVAL_SECONDS));
            daemonDryRun = booleanValue(body, "dry_run");
            daemonStartedAt = System.currentTimeMillis();
            daemonLastTickAt = 0L;
            daemonTickCount = 0L;
            daemonLastResult = null;
            daemonLastError = null;
            daemonRunning.set(true);
            daemonThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    daemonLoop();
                }
            }, "jimuqu-kanban-dispatcher");
            daemonThread.setDaemon(true);
            daemonThread.start();
            Map<String, Object> status = daemonStatus();
            status.put("started", Boolean.TRUE);
            return status;
        }
    }

    public Map<String, Object> stopDaemon() {
        synchronized (daemonLock) {
            daemonRunning.set(false);
            Thread thread = daemonThread;
            if (thread != null) {
                thread.interrupt();
            }
            daemonLock.notifyAll();
            Map<String, Object> status = daemonStatus();
            status.put("stopping", thread != null && thread.isAlive());
            return status;
        }
    }

    public Map<String, Object> daemonStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        Thread thread = daemonThread;
        status.put("running", Boolean.valueOf(daemonRunning.get() && thread != null && thread.isAlive()));
        status.put("board", daemonBoard);
        status.put("max_spawn", Integer.valueOf(daemonMaxSpawn));
        status.put("failure_limit", Integer.valueOf(daemonFailureLimit));
        status.put("ttl_seconds", Long.valueOf(daemonTtlSeconds));
        status.put("interval_seconds", Integer.valueOf(daemonIntervalSeconds));
        status.put("dry_run", Boolean.valueOf(daemonDryRun));
        status.put("started_at", daemonStartedAt <= 0 ? null : Long.valueOf(daemonStartedAt));
        status.put("last_tick_at", daemonLastTickAt <= 0 ? null : Long.valueOf(daemonLastTickAt));
        status.put("tick_count", Long.valueOf(daemonTickCount));
        status.put("last_result", daemonLastResult);
        status.put("last_error", daemonLastError);
        return status;
    }

    public void shutdown() {
        stopDaemon();
    }

    public void setDaemonTickHook(DaemonTickHook daemonTickHook) {
        this.daemonTickHook = daemonTickHook;
    }

    private void daemonLoop() {
        while (daemonRunning.get()) {
            try {
                KanbanDispatchResult result =
                        dispatchOnce(daemonBoard, daemonMaxSpawn, daemonDryRun, daemonTtlSeconds, daemonFailureLimit);
                daemonLastResult = result.toMap();
                daemonLastError = null;
                DaemonTickHook hook = daemonTickHook;
                if (hook != null) {
                    try {
                        hook.onTick(result);
                    } catch (Exception ignored) {
                        // Test hooks must not break the daemon loop.
                    }
                }
            } catch (Exception e) {
                daemonLastError = StrUtil.blankToDefault(e.getMessage(), e.toString());
            } finally {
                daemonLastTickAt = System.currentTimeMillis();
                daemonTickCount++;
            }
            synchronized (daemonLock) {
                if (!daemonRunning.get()) {
                    break;
                }
                try {
                    daemonLock.wait(daemonIntervalSeconds * 1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        daemonRunning.set(false);
    }

    private String resolveWorkspacePath(KanbanTaskRecord task) {
        if (task != null && StrUtil.isNotBlank(task.getWorkspacePath())) {
            return task.getWorkspacePath();
        }
        return new File(System.getProperty("user.dir")).getAbsolutePath();
    }

    private String defaultClaimer(KanbanTaskRecord task) {
        String worker = task == null ? null : task.getAssignee();
        return "dispatcher:" + StrUtil.blankToDefault(worker, "local");
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StrUtil.isBlank(text) || "null".equalsIgnoreCase(text) ? null : text;
    }

    private int intValue(Map<String, Object> body, String key, int defaultValue) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private long longValue(Map<String, Object> body, String key, long defaultValue) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean booleanValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    public Map<String, Object> options(String raw) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            return body;
        }
        String[] tokens = text.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if ("--dry-run".equals(token)) {
                body.put("dry_run", Boolean.TRUE);
            } else if ("--max".equals(token) && i + 1 < tokens.length) {
                body.put("max_spawn", tokens[++i]);
            } else if ("--board".equals(token) && i + 1 < tokens.length) {
                body.put("board", tokens[++i]);
            } else if ("--ttl".equals(token) && i + 1 < tokens.length) {
                body.put("ttl_seconds", tokens[++i]);
            } else if ("--failure-limit".equals(token) && i + 1 < tokens.length) {
                body.put("failure_limit", tokens[++i]);
            } else if ("--interval".equals(token) && i + 1 < tokens.length) {
                body.put("interval_seconds", tokens[++i]);
            }
        }
        return body;
    }

    public interface DaemonTickHook {
        void onTick(KanbanDispatchResult result) throws Exception;
    }
}
