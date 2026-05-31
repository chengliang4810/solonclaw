package com.jimuqu.solon.claw.kanban;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.agent.AgentProfile;
import com.jimuqu.solon.claw.agent.AgentRuntimePolicy;
import com.jimuqu.solon.claw.agent.AgentRuntimeScope;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** Jimuqu Kanban dispatcher tick. */
public class KanbanDispatcherService {
    private static final long DEFAULT_CLAIM_TTL_SECONDS = 900L;
    private static final int DEFAULT_FAILURE_LIMIT = 3;
    private static final int DEFAULT_DAEMON_INTERVAL_SECONDS = 60;
    private static final String REVIEW_SKILL = "review";
    private static final long RESPAWN_GUARD_SUCCESS_WINDOW_MILLIS = 3600000L;
    private static final long RESPAWN_GUARD_PR_WINDOW_MILLIS = 86400000L;
    private static final Pattern RESPAWN_BLOCKER =
            Pattern.compile(
                    "\\b(quota|rate[\\s_\\-]?limit|429|403|auth\\w*|unauthorized|forbidden|billing|subscription|access[\\s_]denied|permission[\\s_]denied|invalid[\\s_]api[\\s_]key)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPAWN_PR_URL =
            Pattern.compile(
                    "https?://github\\.com/[^/\\s]+/[^/\\s]+/pull/\\d+",
                    Pattern.CASE_INSENSITIVE);

    private final KanbanRepository repository;
    private final KanbanService kanbanService;
    private final KanbanWorkerSpawner workerSpawner;
    private final AppConfig appConfig;
    private final Object daemonLock = new Object();
    private final AtomicBoolean daemonRunning = new AtomicBoolean(false);
    private volatile Thread daemonThread;
    private volatile String daemonBoard;
    private volatile int daemonMaxSpawn;
    private volatile int daemonMaxInProgress;
    private volatile String daemonDefaultAssignee;
    private volatile int daemonMaxInProgressPerProfile;
    private volatile int daemonFailureLimit = DEFAULT_FAILURE_LIMIT;
    private volatile long daemonStaleTimeoutSeconds;
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
        this(repository, kanbanService, workerSpawner, null);
    }

    public KanbanDispatcherService(
            KanbanRepository repository,
            KanbanService kanbanService,
            KanbanWorkerSpawner workerSpawner,
            AppConfig appConfig) {
        this.repository = repository;
        this.kanbanService = kanbanService;
        this.workerSpawner = workerSpawner;
        this.appConfig = appConfig;
        this.daemonTtlSeconds = defaultClaimTtlSeconds();
    }

    public Map<String, Object> dispatch(Map<String, Object> body) throws Exception {
        String board = text(body, "board");
        AppConfig.KanbanConfig defaults = kanbanConfig();
        int maxSpawn = intValue(body, "max_spawn", defaults.getMaxSpawn());
        int maxInProgress = intValue(body, "max_in_progress", defaults.getMaxInProgress());
        String defaultAssignee = text(body, "default_assignee");
        if (defaultAssignee == null) {
            defaultAssignee = defaults.getDefaultAssignee();
        }
        int maxInProgressPerProfile =
                intValue(body, "max_in_progress_per_profile", defaults.getMaxInProgressPerProfile());
        int failureLimit = intValue(body, "failure_limit", defaults.getFailureLimit());
        long staleTimeoutSeconds = longValue(body, "stale_timeout_seconds", 0L);
        long ttlSeconds = longValue(body, "ttl_seconds", defaultClaimTtlSeconds());
        boolean dryRun = booleanValue(body, "dry_run");
        return dispatchOnce(
                        board,
                        maxSpawn,
                        dryRun,
                        ttlSeconds,
                        failureLimit,
                        maxInProgress,
                        defaultAssignee,
                        maxInProgressPerProfile,
                        staleTimeoutSeconds)
                .toMap();
    }

    public KanbanDispatchResult dispatchOnce(
            String board, int maxSpawn, boolean dryRun, long ttlSeconds, int failureLimit)
            throws Exception {
        return dispatchOnce(board, maxSpawn, dryRun, ttlSeconds, failureLimit, 0);
    }

    public KanbanDispatchResult dispatchOnce(
            String board,
            int maxSpawn,
            boolean dryRun,
            long ttlSeconds,
            int failureLimit,
            int maxInProgress)
            throws Exception {
        return dispatchOnce(board, maxSpawn, dryRun, ttlSeconds, failureLimit, maxInProgress, null, 0);
    }

    public KanbanDispatchResult dispatchOnce(
            String board,
            int maxSpawn,
            boolean dryRun,
            long ttlSeconds,
            int failureLimit,
            int maxInProgress,
            String defaultAssignee,
            int maxInProgressPerProfile)
            throws Exception {
        return dispatchOnce(
                board,
                maxSpawn,
                dryRun,
                ttlSeconds,
                failureLimit,
                maxInProgress,
                defaultAssignee,
                maxInProgressPerProfile,
                0L);
    }

    public KanbanDispatchResult dispatchOnce(
            String board,
            int maxSpawn,
            boolean dryRun,
            long ttlSeconds,
            int failureLimit,
            int maxInProgress,
            String defaultAssignee,
            int maxInProgressPerProfile,
            long staleTimeoutSeconds)
            throws Exception {
        KanbanDispatchResult result = new KanbanDispatchResult();
        long now = System.currentTimeMillis();
        result.setReclaimed(repository.releaseStaleClaims(now));
        result.getStale().addAll(repository.reclaimStaleRunning(now, staleTimeoutSeconds));
        result.getCrashed().addAll(repository.reclaimCrashedWorkers(now));
        result.getTimedOut().addAll(repository.reclaimTimedOutWorkers(now));
        result.setPromoted(repository.recomputeReady(board));
        int liveCapacity = capacityLimit(maxSpawn, maxInProgress);
        int runningBeforeDispatch = liveCapacity > 0 ? runningTaskCount(board) : 0;
        if (maxSpawn > 0) {
            result.setMaxSpawn(Integer.valueOf(maxSpawn));
        }
        if (maxInProgress > 0) {
            result.setMaxInProgress(Integer.valueOf(maxInProgress));
        }
        if (liveCapacity > 0) {
            result.setRunningBeforeDispatch(Integer.valueOf(runningBeforeDispatch));
        }

        int spawned = 0;
        int perProfileCap = maxInProgressPerProfile > 0 ? maxInProgressPerProfile : 0;
        if (perProfileCap > 0) {
            result.setMaxInProgressPerProfile(Integer.valueOf(perProfileCap));
        }
        Map<String, Integer> perProfileRunning = perProfileCap > 0 ? runningTaskCountByAssignee(board) : null;
        String fallbackAssignee = StrUtil.blankToDefault(defaultAssignee, null);
        List<KanbanTaskRecord> readyTasks = repository.listReadyTasks(board);
        for (KanbanTaskRecord task : readyTasks) {
            if (liveCapacity > 0 && runningBeforeDispatch + spawned >= liveCapacity) {
                result.getSkippedCapacity().add(task.getTaskId());
                continue;
            }
            String taskAssignee = task.getAssignee();
            if (StrUtil.isBlank(taskAssignee)) {
                if (StrUtil.isBlank(fallbackAssignee)) {
                    result.getSkippedUnassigned().add(task.getTaskId());
                    continue;
                }
                if (!isSpawnableAssignee(fallbackAssignee)) {
                    result.getSkippedUnassigned().add(task.getTaskId());
                    continue;
                }
                taskAssignee = fallbackAssignee;
                result.getAutoAssignedDefault().add(task.getTaskId());
                if (!dryRun && !repository.assignTask(task.getTaskId(), taskAssignee, "default_assignee")) {
                    result.getSkippedUnassigned().add(task.getTaskId());
                    continue;
                }
            }
            if (!isSpawnableAssignee(taskAssignee)) {
                result.getSkippedNonspawnable().add(task.getTaskId());
                continue;
            }
            if (perProfileCap > 0) {
                int current = perProfileRunning.containsKey(taskAssignee)
                        ? perProfileRunning.get(taskAssignee).intValue()
                        : 0;
                if (current >= perProfileCap) {
                    result.addSkippedPerProfileCapped(task.getTaskId(), taskAssignee, current);
                    continue;
                }
            }
            String guardReason = respawnGuardReason(task, now);
            if (guardReason != null) {
                result.addRespawnGuarded(task.getTaskId(), guardReason);
                if (!dryRun) {
                    recordRespawnGuarded(task.getTaskId(), guardReason);
                }
                continue;
            }
            if (dryRun) {
                result.addSpawned(task, resolveWorkspacePath(task, false), 0L, taskAssignee);
                spawned++;
                if (perProfileCap > 0) {
                    perProfileRunning.put(
                            taskAssignee,
                            Integer.valueOf(
                                    (perProfileRunning.containsKey(taskAssignee)
                                                    ? perProfileRunning.get(taskAssignee).intValue()
                                                    : 0)
                                            + 1));
                }
                continue;
            }
            String claimer = defaultClaimer(taskAssignee);
            KanbanTaskRecord claimed =
                    repository.claimTask(
                            task.getTaskId(), claimer, ttlSeconds, taskAssignee, 0L);
            if (claimed == null) {
                continue;
            }
            try {
                String workspace = resolveWorkspacePath(claimed, true);
                repository.setWorkspacePath(claimed.getTaskId(), workspace);
                Map<String, Object> detail = kanbanService.task(claimed.getTaskId());
                String workerContext = String.valueOf(detail.get("worker_context"));
                long pid = workerSpawner.spawn(claimed, workspace, workerContext);
                repository.clearSpawnFailures(claimed.getTaskId(), pid);
                KanbanTaskRecord latest = repository.findTask(claimed.getTaskId());
                result.addSpawned(latest == null ? claimed : latest, workspace, pid);
                spawned++;
                if (perProfileCap > 0) {
                    perProfileRunning.put(
                            taskAssignee,
                            Integer.valueOf(
                                    (perProfileRunning.containsKey(taskAssignee)
                                                    ? perProfileRunning.get(taskAssignee).intValue()
                                                    : 0)
                                            + 1));
                }
            } catch (Exception e) {
                String error = StrUtil.blankToDefault(e.getMessage(), e.toString());
                repository.markSpawnFailure(claimed.getTaskId(), error);
                result.getSpawnFailures().add(claimed.getTaskId());
                if (repository.autoBlockAfterSpawnFailure(claimed.getTaskId(), failureLimit, error)) {
                    result.getAutoBlocked().add(claimed.getTaskId());
                }
            }
        }
        List<KanbanTaskRecord> reviewTasks = repository.listTasks(board, "review", false);
        for (KanbanTaskRecord task : reviewTasks) {
            if (StrUtil.isNotBlank(task.getClaimLock())) {
                continue;
            }
            if (liveCapacity > 0 && runningBeforeDispatch + spawned >= liveCapacity) {
                result.getSkippedCapacity().add(task.getTaskId());
                continue;
            }
            String taskAssignee = task.getAssignee();
            if (StrUtil.isBlank(taskAssignee)) {
                result.getSkippedUnassigned().add(task.getTaskId());
                continue;
            }
            if (!isSpawnableAssignee(taskAssignee)) {
                result.getSkippedNonspawnable().add(task.getTaskId());
                continue;
            }
            if (perProfileCap > 0) {
                int current = perProfileRunning.containsKey(taskAssignee)
                        ? perProfileRunning.get(taskAssignee).intValue()
                        : 0;
                if (current >= perProfileCap) {
                    result.addSkippedPerProfileCapped(task.getTaskId(), taskAssignee, current);
                    continue;
                }
            }
            if (dryRun) {
                result.addSpawned(task, resolveWorkspacePath(task, false), 0L, taskAssignee);
                spawned++;
                if (perProfileCap > 0) {
                    perProfileRunning.put(
                            taskAssignee,
                            Integer.valueOf(
                                    (perProfileRunning.containsKey(taskAssignee)
                                                    ? perProfileRunning.get(taskAssignee).intValue()
                                            : 0)
                                            + 1));
                }
                continue;
            }
            String claimer = defaultClaimer(taskAssignee);
            KanbanTaskRecord claimed =
                    repository.claimReviewTask(
                            task.getTaskId(), claimer, ttlSeconds, taskAssignee, 0L);
            if (claimed == null) {
                continue;
            }
            try {
                attachReviewSkill(claimed);
                String workspace = resolveWorkspacePath(claimed, true);
                repository.setWorkspacePath(claimed.getTaskId(), workspace);
                Map<String, Object> detail = kanbanService.task(claimed.getTaskId());
                String workerContext = String.valueOf(detail.get("worker_context"));
                long pid = workerSpawner.spawn(claimed, workspace, workerContext);
                repository.clearSpawnFailures(claimed.getTaskId(), pid);
                KanbanTaskRecord latest = repository.findTask(claimed.getTaskId());
                result.addSpawned(latest == null ? claimed : latest, workspace, pid);
                spawned++;
                if (perProfileCap > 0) {
                    perProfileRunning.put(
                            taskAssignee,
                            Integer.valueOf(
                                    (perProfileRunning.containsKey(taskAssignee)
                                                    ? perProfileRunning.get(taskAssignee).intValue()
                                                    : 0)
                                            + 1));
                }
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
            AppConfig.KanbanConfig defaults = kanbanConfig();
            daemonMaxSpawn = intValue(body, "max_spawn", defaults.getMaxSpawn());
            daemonMaxInProgress = intValue(body, "max_in_progress", defaults.getMaxInProgress());
            daemonDefaultAssignee = text(body, "default_assignee");
            if (daemonDefaultAssignee == null) {
                daemonDefaultAssignee = defaults.getDefaultAssignee();
            }
            daemonMaxInProgressPerProfile =
                    intValue(body, "max_in_progress_per_profile", defaults.getMaxInProgressPerProfile());
            daemonFailureLimit = intValue(body, "failure_limit", defaults.getFailureLimit());
            daemonStaleTimeoutSeconds = longValue(body, "stale_timeout_seconds", 0L);
            daemonTtlSeconds = longValue(body, "ttl_seconds", defaultClaimTtlSeconds());
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
        status.put("max_in_progress", Integer.valueOf(daemonMaxInProgress));
        status.put("default_assignee", daemonDefaultAssignee);
        status.put("max_in_progress_per_profile", Integer.valueOf(daemonMaxInProgressPerProfile));
        status.put("failure_limit", Integer.valueOf(daemonFailureLimit));
        status.put("stale_timeout_seconds", Long.valueOf(daemonStaleTimeoutSeconds));
        status.put("ttl_seconds", Long.valueOf(daemonTtlSeconds));
        status.put("interval_seconds", Integer.valueOf(daemonIntervalSeconds));
        status.put("dry_run", Boolean.valueOf(daemonDryRun));
        status.put("started_at", daemonStartedAt <= 0 ? null : Long.valueOf(daemonStartedAt));
        status.put("last_tick_at", daemonLastTickAt <= 0 ? null : Long.valueOf(daemonLastTickAt));
        status.put("tick_count", Long.valueOf(daemonTickCount));
        status.put("last_result", daemonLastResult);
        status.put("last_error", SecretRedactor.redact(daemonLastError, 1000));
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
                        dispatchOnce(
                                daemonBoard,
                                daemonMaxSpawn,
                                daemonDryRun,
                                daemonTtlSeconds,
                                daemonFailureLimit,
                                daemonMaxInProgress,
                                daemonDefaultAssignee,
                                daemonMaxInProgressPerProfile,
                                daemonStaleTimeoutSeconds);
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

    private String resolveWorkspacePath(KanbanTaskRecord task, boolean createDirectories) throws Exception {
        if (task != null && StrUtil.isNotBlank(task.getWorkspacePath())) {
            String workspacePath = task.getWorkspacePath();
            if (createDirectories && "dir".equals(task.getWorkspaceKind())) {
                FileUtil.mkdir(workspacePath);
            }
            return workspacePath;
        }
        if (task != null && "scratch".equals(task.getWorkspaceKind())) {
            File workspace = FileUtil.file(scratchWorkspaceRoot(), task.getTaskId()).getCanonicalFile();
            if (createDirectories) {
                FileUtil.mkdir(workspace);
            }
            return workspace.getAbsolutePath();
        }
        return new File(System.getProperty("user.dir")).getAbsolutePath();
    }

    private File scratchWorkspaceRoot() {
        return FileUtil.file(runtimeHome(), "kanban", "workspaces");
    }

    private File runtimeHome() {
        if (appConfig != null && appConfig.getRuntime() != null && StrUtil.isNotBlank(appConfig.getRuntime().getHome())) {
            return FileUtil.file(appConfig.getRuntime().getHome());
        }
        return FileUtil.file("runtime");
    }

    private String defaultClaimer(String assignee) {
        return "dispatcher:" + StrUtil.blankToDefault(assignee, "local");
    }

    private int runningTaskCount(String board) throws Exception {
        int count = 0;
        for (Integer running : runningTaskCountByAssignee(board).values()) {
            count += running.intValue();
        }
        return count;
    }

    private int capacityLimit(int maxSpawn, int maxInProgress) {
        if (maxSpawn > 0 && maxInProgress > 0) {
            return Math.min(maxSpawn, maxInProgress);
        }
        if (maxSpawn > 0) {
            return maxSpawn;
        }
        return Math.max(maxInProgress, 0);
    }

    private AppConfig.KanbanConfig kanbanConfig() {
        return appConfig == null ? new AppConfig.KanbanConfig() : appConfig.getKanban();
    }

    private long defaultClaimTtlSeconds() {
        AppConfig.KanbanConfig config = kanbanConfig();
        return Math.max(1L, config.getClaimTtlSeconds());
    }

    private boolean isSpawnableAssignee(String assignee) throws Exception {
        if (kanbanService == null || kanbanService.getAgentProfileService() == null) {
            return true;
        }
        String normalized = AgentRuntimeScope.normalizeName(assignee);
        if (AgentRuntimeScope.DEFAULT_AGENT.equals(normalized)) {
            return true;
        }
        AgentProfile profile = kanbanService.getAgentProfileService().findByName(normalized);
        return profile != null && profile.isEnabled();
    }

    private void attachReviewSkill(KanbanTaskRecord task) {
        if (task == null) {
            return;
        }
        LinkedHashSet<String> skills = new LinkedHashSet<String>();
        for (String skill : AgentRuntimePolicy.parseStringList(task.getSkillsJson())) {
            if (StrUtil.isNotBlank(skill)) {
                skills.add(skill.trim());
            }
        }
        skills.add(REVIEW_SKILL);
        task.setSkillsJson(ONode.serialize(new ArrayList<String>(skills)));
    }

    private Map<String, Integer> runningTaskCountByAssignee(String board) throws Exception {
        Map<String, Integer> runningByAssignee = new LinkedHashMap<String, Integer>();
        Map<String, Map<String, Integer>> counts = repository.countTasksByAssignee(board);
        for (Map.Entry<String, Map<String, Integer>> entry : counts.entrySet()) {
            Map<String, Integer> statusCounts = entry.getValue();
            if (statusCounts == null) {
                continue;
            }
            Integer running = statusCounts.get("running");
            if (running != null) {
                runningByAssignee.put(entry.getKey(), running);
            }
        }
        return runningByAssignee;
    }

    private String respawnGuardReason(KanbanTaskRecord task, long now) throws Exception {
        if (task == null || StrUtil.isBlank(task.getTaskId())) {
            return null;
        }
        String error = task.getLastSpawnError();
        if (StrUtil.isNotBlank(error) && RESPAWN_BLOCKER.matcher(error).find()) {
            return "blocker_auth";
        }
        long successCutoff = now - RESPAWN_GUARD_SUCCESS_WINDOW_MILLIS;
        for (KanbanRunRecord run : repository.listRuns(task.getTaskId(), false)) {
            long endedAt = run.getEndedAt() > 0 ? run.getEndedAt() : run.getStartedAt();
            if ("completed".equals(run.getOutcome()) && endedAt >= successCutoff) {
                return "recent_success";
            }
        }
        long prCutoff = now - RESPAWN_GUARD_PR_WINDOW_MILLIS;
        for (KanbanCommentRecord comment : repository.listComments(task.getTaskId())) {
            if (comment.getCreatedAt() >= prCutoff
                    && StrUtil.isNotBlank(comment.getBody())
                    && RESPAWN_PR_URL.matcher(comment.getBody()).find()) {
                return "active_pr";
            }
        }
        return null;
    }

    private void recordRespawnGuarded(String taskId, String reason) throws Exception {
        KanbanEventRecord event = new KanbanEventRecord();
        event.setTaskId(taskId);
        event.setKind("respawn_guarded");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("reason", reason);
        event.setPayloadJson(ONode.serialize(payload));
        repository.addEvent(event);
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
            } else if ("--max-in-progress".equals(token) && i + 1 < tokens.length) {
                body.put("max_in_progress", tokens[++i]);
            } else if ("--max-in-progress-per-profile".equals(token) && i + 1 < tokens.length) {
                body.put("max_in_progress_per_profile", tokens[++i]);
            } else if ("--default-assignee".equals(token) && i + 1 < tokens.length) {
                body.put("default_assignee", tokens[++i]);
            } else if ("--board".equals(token) && i + 1 < tokens.length) {
                body.put("board", tokens[++i]);
            } else if ("--ttl".equals(token) && i + 1 < tokens.length) {
                body.put("ttl_seconds", tokens[++i]);
            } else if ("--failure-limit".equals(token) && i + 1 < tokens.length) {
                body.put("failure_limit", tokens[++i]);
            } else if ("--stale-timeout".equals(token) && i + 1 < tokens.length) {
                body.put("stale_timeout_seconds", tokens[++i]);
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
