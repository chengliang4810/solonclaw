package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 维护进程注册信息，供运行时按需查询和装配。 */
public class ProcessRegistry {
    /** 记录进程注册表中的可降级异常，日志不输出命令、工作目录或进程输出内容。 */
    private static final Logger log = LoggerFactory.getLogger(ProcessRegistry.class);

    /** 最大输出CHARS的统一常量值。 */
    private static final int MAX_OUTPUT_CHARS = 200000;

    /** 进程事件本地时间格式，保持原有无时区后缀的展示语义。 */
    private static final DateTimeFormatter LOCAL_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault());

    /** WATCH最小整型ERVALMILLIS的统一常量值。 */
    private static final long WATCH_MIN_INTERVAL_MILLIS = 15000L;

    /** WATCHSTRIKE限制的统一常量值。 */
    private static final int WATCH_STRIKE_LIMIT = 3;

    /** WATCHGLOBAL最大PER窗口的统一常量值。 */
    private static final int WATCH_GLOBAL_MAX_PER_WINDOW = 15;

    /** WATCHGLOBAL窗口MILLIS的统一常量值。 */
    private static final long WATCH_GLOBAL_WINDOW_MILLIS = 10000L;

    /** WATCHGLOBALCOOLDOWNMILLIS的统一常量值。 */
    private static final long WATCH_GLOBAL_COOLDOWN_MILLIS = 30000L;

    /** 注入应用配置，用于进程。 */
    private final AppConfig appConfig;

    /** 记录进程中的watchMinIntervalMillis。 */
    private final long watchMinIntervalMillis;

    /** 记录进程中的watchStrike限制。 */
    private final int watchStrikeLimit;

    /** 记录进程中的watchGlobalMaxPer窗口。 */
    private final int watchGlobalMaxPerWindow;

    /** 记录进程中的watchGlobal窗口Millis。 */
    private final long watchGlobalWindowMillis;

    /** 记录进程中的watchGlobalCooldownMillis。 */
    private final long watchGlobalCooldownMillis;

    /** 保存processes映射，便于按键快速查询。 */
    private final Map<String, ManagedProcess> processes =
            Collections.synchronizedMap(new LinkedHashMap<String, ManagedProcess>());

    /** 记录进程中的生命周期Tracker。 */
    private final ProcessLifecycleTracker lifecycleTracker = new ProcessLifecycleTracker();

    /** 保存进程Events映射，便于按键快速查询。 */
    private final Queue<Map<String, Object>> processEvents =
            new ConcurrentLinkedQueue<Map<String, Object>>();

    /** 保存补全文本Consumed集合，维持调用顺序或去重语义。 */
    private final Set<String> completionConsumed =
            Collections.synchronizedSet(new HashSet<String>());

    /** 记录进程中的globalWatchLock。 */
    private final Object globalWatchLock = new Object();

    /** 记录进程中的globalWatch窗口Start。 */
    private long globalWatchWindowStart;

    /** 记录进程中的globalWatch窗口Hits。 */
    private int globalWatchWindowHits;

    /** 记录进程中的globalWatchTrippedUntil。 */
    private long globalWatchTrippedUntil;

    /** 记录进程中的globalWatchSuppressedDuringTrip。 */
    private int globalWatchSuppressedDuringTrip;

    /** 创建进程注册表实例。 */
    public ProcessRegistry() {
        this(null);
    }

    /**
     * 创建进程注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public ProcessRegistry(AppConfig appConfig) {
        this(
                appConfig,
                WATCH_MIN_INTERVAL_MILLIS,
                WATCH_STRIKE_LIMIT,
                WATCH_GLOBAL_MAX_PER_WINDOW,
                WATCH_GLOBAL_WINDOW_MILLIS,
                WATCH_GLOBAL_COOLDOWN_MILLIS);
    }

    /**
     * 创建进程注册表实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param watchMinIntervalMillis watchMinIntervalMillis 参数。
     * @param watchStrikeLimit watchStrikeLimit 参数。
     * @param watchGlobalMaxPerWindow watchGlobalMaxPer窗口参数。
     * @param watchGlobalWindowMillis watchGlobal窗口Millis参数。
     * @param watchGlobalCooldownMillis watchGlobalCooldownMillis 参数。
     */
    public ProcessRegistry(
            AppConfig appConfig,
            long watchMinIntervalMillis,
            int watchStrikeLimit,
            int watchGlobalMaxPerWindow,
            long watchGlobalWindowMillis,
            long watchGlobalCooldownMillis) {
        this.appConfig = appConfig;
        this.watchMinIntervalMillis = Math.max(1L, watchMinIntervalMillis);
        this.watchStrikeLimit = Math.max(1, watchStrikeLimit);
        this.watchGlobalMaxPerWindow = Math.max(1, watchGlobalMaxPerWindow);
        this.watchGlobalWindowMillis = Math.max(1L, watchGlobalWindowMillis);
        this.watchGlobalCooldownMillis = Math.max(1L, watchGlobalCooldownMillis);
    }

    /**
     * 执行add相关逻辑。
     *
     * @param process 进程参数。
     * @return 返回add结果。
     */
    public String add(Process process) {
        String id = IdSupport.newId();
        ManagedProcess managed =
                new ManagedProcess(
                        this, id, "", null, process, System.currentTimeMillis(), MAX_OUTPUT_CHARS);
        managed.setPid(resolvePid(process));
        processes.put(id, managed);
        recordStarted(managed);
        return id;
    }

    /**
     * 启动当前组件并准备运行资源。
     *
     * @param command 待执行或解析的命令文本。
     * @param workDir 命令执行工作目录。
     * @return 返回start结果。
     */
    public ManagedProcess start(String command, File workDir) throws Exception {
        return start(command, workDir, false, Collections.<String>emptyList());
    }

    /**
     * 启动当前组件并准备运行资源。
     *
     * @param command 待执行或解析的命令文本。
     * @param workDir 命令执行工作目录。
     * @param notifyOnComplete 后台任务完成后是否通知。。
     * @param watchPatterns 需要监听并提示的输出模式。。
     * @return 返回start结果。
     */
    public ManagedProcess start(
            String command, File workDir, boolean notifyOnComplete, List<String> watchPatterns)
            throws Exception {
        validateCommand(command);
        String executableCommand = isWindows() ? command : rewriteCompoundBackground(command);
        List<String> shellCommand =
                shellCommand(executableCommand, resolveShellInitFiles(), isWindows());
        ProcessBuilder builder = new ProcessBuilder(shellCommand);
        if (workDir != null) {
            builder.directory(workDir);
        }
        builder.redirectErrorStream(true);
        ProfileRuntimeScope.replaceProcessEnvironment(builder.environment());
        SubprocessEnvironmentSanitizer.sanitize(builder.environment(), appConfig);
        Process process = builder.start();
        String id = "proc_" + IdSupport.newId();
        ManagedProcess managed =
                new ManagedProcess(
                        this,
                        id,
                        executableCommand,
                        workDir == null ? null : workDir.getAbsolutePath(),
                        process,
                        System.currentTimeMillis(),
                        MAX_OUTPUT_CHARS);
        managed.setPid(resolvePid(process));
        managed.setNotifyOnComplete(notifyOnComplete);
        managed.setWatchPatterns(watchPatterns);
        processes.put(id, managed);
        recordStarted(managed);
        managed.startReader();
        return managed;
    }

    /**
     * 校验命令。
     *
     * @param command 待执行或解析的命令文本。
     */
    private static void validateCommand(String command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "Invalid terminal command: expected string, got NoneType/null.");
        }
        if (StrUtil.isBlank(command)) {
            throw new IllegalArgumentException(
                    "Invalid terminal command: expected non-empty string.");
        }
    }

    /**
     * 执行snapshot相关逻辑。
     *
     * @return 返回snapshot结果。
     */
    public Map<String, ManagedProcess> snapshot() {
        Map<String, ManagedProcess> snapshot = new LinkedHashMap<String, ManagedProcess>();
        synchronized (processes) {
            for (Map.Entry<String, ManagedProcess> entry : processes.entrySet()) {
                ManagedProcess managed = entry.getValue();
                managed.refreshExitState();
                snapshot.put(entry.getKey(), managed);
            }
        }
        return snapshot;
    }

    /**
     * 获取当前注册项或配置项。
     *
     * @param id 标识。
     * @return 返回get结果。
     */
    public ManagedProcess get(String id) {
        ManagedProcess managed = processes.get(id);
        if (managed != null) {
            managed.refreshExitState();
        }
        return managed;
    }

    /**
     * 执行waitFor相关逻辑。
     *
     * @param id 标识。
     * @param timeoutMillis timeoutMillis 参数。
     * @return 返回wait For结果。
     */
    public boolean waitFor(String id, long timeoutMillis) throws InterruptedException {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        boolean finished = managed.waitFor(timeoutMillis);
        managed.refreshExitState();
        return finished;
    }

    /**
     * 停止当前组件并释放运行状态。
     *
     * @param id 标识。
     * @return 返回stop结果。
     */
    public boolean stop(String id) {
        StopResult result = stopDetailed(id);
        return result.isStopped() || "already_exited".equals(result.getStatus());
    }

    /**
     * 停止Detailed。
     *
     * @param id 标识。
     * @return 返回Detailed结果。
     */
    public StopResult stopDetailed(String id) {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return new StopResult("not_found", false, null, null);
        }
        managed.refreshExitState();
        if (managed.isExited()) {
            return new StopResult("already_exited", false, managed.getId(), managed.getExitCode());
        }
        managed.stop();
        return new StopResult("killed", true, managed.getId(), managed.getExitCode());
    }

    /**
     * 写入Stdin。
     *
     * @param id 标识。
     * @param data 数据参数。
     * @return 返回Stdin结果。
     */
    public boolean writeStdin(String id, String data) throws Exception {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        managed.writeStdin(data);
        return true;
    }

    /**
     * 关闭Stdin。
     *
     * @param id 标识。
     * @return 返回Stdin结果。
     */
    public boolean closeStdin(String id) throws Exception {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        managed.closeStdin();
        return true;
    }

    /**
     * 停止全部。
     *
     * @return 返回全部结果。
     */
    public int stopAll() {
        Map<String, ManagedProcess> snapshot = snapshot();
        int stopped = 0;
        for (ManagedProcess managed : snapshot.values()) {
            if (!managed.isExited() && stop(managed.getId())) {
                stopped++;
            }
        }
        return stopped;
    }

    /**
     * 执行running次数相关逻辑。
     *
     * @return 返回running次数结果。
     */
    public int runningCount() {
        int count = 0;
        for (ManagedProcess managed : snapshot().values()) {
            if (!managed.isExited()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 清空Events。
     *
     * @return 返回drain Events结果。
     */
    public List<Map<String, Object>> drainEvents() {
        return drainEvents(100);
    }

    /**
     * 清空Events。
     *
     * @param maxEvents maxEvents 参数。
     * @return 返回drain Events结果。
     */
    public List<Map<String, Object>> drainEvents(int maxEvents) {
        int safeMax = maxEvents <= 0 ? 100 : maxEvents;
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        while (events.size() < safeMax) {
            Map<String, Object> event = processEvents.poll();
            if (event == null) {
                break;
            }
            if (isConsumedCompletion(event)) {
                continue;
            }
            events.add(new LinkedHashMap<String, Object>(event));
        }
        return events;
    }

    /**
     * 执行recent生命周期Events相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回recent生命周期Events结果。
     */
    public List<Map<String, Object>> recentLifecycleEvents(int limit) {
        int safeLimit = limit <= 0 ? 100 : limit;
        return lifecycleTracker.recentEventsAsMap(safeLimit);
    }

    /**
     * 执行生命周期EventsFor进程相关逻辑。
     *
     * @param id 标识。
     * @param limit 最大返回数量。
     * @return 返回生命周期Events For进程结果。
     */
    public List<Map<String, Object>> lifecycleEventsForProcess(String id, int limit) {
        int safeLimit = limit <= 0 ? 20 : limit;
        return lifecycleTracker.eventsForProcessAsMap(id, safeLimit);
    }

    /**
     * 执行last生命周期事件For进程相关逻辑。
     *
     * @param id 标识。
     * @return 返回last生命周期事件For进程结果。
     */
    Map<String, Object> lastLifecycleEventForProcess(String id) {
        return lifecycleTracker.lastEventForProcessAsMap(id);
    }

    /**
     * 标记Completion Consumed。
     *
     * @param sessionId 当前会话标识。
     */
    public void markCompletionConsumed(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            completionConsumed.add(sessionId);
        }
    }

    /**
     * 判断是否Consumed Completion。
     *
     * @param event 事件参数。
     * @return 如果Consumed Completion满足条件则返回 true，否则返回 false。
     */
    private boolean isConsumedCompletion(Map<String, Object> event) {
        Object type = event.get("type");
        Object sessionId = event.get("session_id");
        return "completion".equals(type)
                && sessionId instanceof String
                && completionConsumed.contains(sessionId);
    }

    /**
     * 检查Watch Patterns。
     *
     * @param managed managed 参数。
     * @param newText new文本参数。
     */
    private void checkWatchPatterns(ManagedProcess managed, String newText) {
        if (managed == null || StrUtil.isBlank(newText)) {
            return;
        }
        List<String> patterns;
        synchronized (managed) {
            if (managed.exited || managed.watchDisabled || managed.watchPatterns.isEmpty()) {
                return;
            }
            patterns = new ArrayList<String>(managed.watchPatterns);
        }

        List<String> matchedLines = new ArrayList<String>();
        String matchedPattern = null;
        String[] lines = newText.split("\\r?\\n");
        for (String line : lines) {
            for (String pattern : patterns) {
                if (line.contains(pattern)) {
                    matchedLines.add(trimTrailingCarriageReturn(line));
                    if (matchedPattern == null) {
                        matchedPattern = pattern;
                    }
                    break;
                }
            }
        }
        if (matchedLines.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean returnEarly;
        boolean shouldDisable = false;
        int suppressed = 0;
        synchronized (managed) {
            if (managed.exited || managed.watchDisabled || managed.watchPatterns.isEmpty()) {
                return;
            }
            if (managed.watchCooldownUntil > 0L && now < managed.watchCooldownUntil) {
                managed.watchSuppressed += matchedLines.size();
                if (!managed.watchStrikeCandidate) {
                    managed.watchStrikeCandidate = true;
                    managed.watchConsecutiveStrikes++;
                    if (managed.watchConsecutiveStrikes >= watchStrikeLimit) {
                        managed.watchDisabled = true;
                        managed.notifyOnComplete = true;
                        shouldDisable = true;
                    }
                }
                returnEarly = true;
            } else {
                if (managed.watchCooldownUntil > 0L && !managed.watchStrikeCandidate) {
                    managed.watchConsecutiveStrikes = 0;
                }
                managed.watchStrikeCandidate = false;
                managed.watchLastEmitAt = now;
                managed.watchCooldownUntil = now + watchMinIntervalMillis;
                managed.watchHits++;
                suppressed = managed.watchSuppressed;
                managed.watchSuppressed = 0;
                returnEarly = false;
            }
        }

        if (returnEarly) {
            if (shouldDisable) {
                enqueueWatchDisabled(managed);
            }
            return;
        }

        if (!globalWatchAdmit(now)) {
            return;
        }

        Map<String, Object> event = baseEvent("watch_match", managed);
        event.put("pattern", SecretRedactor.redact(matchedPattern));
        event.put("output", SecretRedactor.redact(limitWatchOutput(matchedLines)));
        event.put("lines", redactLines(matchedLines));
        event.put("suppressed", Integer.valueOf(suppressed));
        enqueueEvent(event);
    }

    /**
     * 执行globalWatchAdmit相关逻辑。
     *
     * @param now 当前时间戳。
     * @return 返回global Watch Admit结果。
     */
    private boolean globalWatchAdmit(long now) {
        Map<String, Object> releaseEvent = null;
        Map<String, Object> tripEvent = null;
        boolean admit;
        synchronized (globalWatchLock) {
            if (globalWatchTrippedUntil > 0L && now >= globalWatchTrippedUntil) {
                int suppressed = globalWatchSuppressedDuringTrip;
                globalWatchTrippedUntil = 0L;
                globalWatchSuppressedDuringTrip = 0;
                globalWatchWindowStart = now;
                globalWatchWindowHits = 0;
                if (suppressed > 0) {
                    releaseEvent = summaryEvent("watch_overflow_released");
                    releaseEvent.put("suppressed", Integer.valueOf(suppressed));
                    releaseEvent.put(
                            "message",
                            "Watch-pattern notifications resumed. "
                                    + suppressed
                                    + " match event(s) were suppressed during the flood.");
                }
            }

            if (globalWatchTrippedUntil > 0L && now < globalWatchTrippedUntil) {
                globalWatchSuppressedDuringTrip++;
                admit = false;
            } else {
                if (now - globalWatchWindowStart >= watchGlobalWindowMillis) {
                    globalWatchWindowStart = now;
                    globalWatchWindowHits = 0;
                }
                if (globalWatchWindowHits >= watchGlobalMaxPerWindow) {
                    globalWatchTrippedUntil = now + watchGlobalCooldownMillis;
                    globalWatchSuppressedDuringTrip++;
                    tripEvent = summaryEvent("watch_overflow_tripped");
                    tripEvent.put(
                            "message",
                            "Watch-pattern overflow: >"
                                    + watchGlobalMaxPerWindow
                                    + " notifications in "
                                    + (watchGlobalWindowMillis / 1000L)
                                    + "s across all processes. Suppressing further watch_match"
                                    + " events for "
                                    + (watchGlobalCooldownMillis / 1000L)
                                    + "s.");
                    admit = false;
                } else {
                    globalWatchWindowHits++;
                    admit = true;
                }
            }
        }
        if (releaseEvent != null) {
            enqueueEvent(releaseEvent);
        }
        if (tripEvent != null) {
            enqueueEvent(tripEvent);
        }
        return admit;
    }

    /**
     * 执行enqueueWatchDisabled相关逻辑。
     *
     * @param managed managed 参数。
     */
    private void enqueueWatchDisabled(ManagedProcess managed) {
        Map<String, Object> event = baseEvent("watch_disabled", managed);
        event.put("suppressed", Integer.valueOf(managed.getWatchSuppressed()));
        event.put("watch_disabled", Boolean.TRUE);
        event.put("notify_on_complete", Boolean.TRUE);
        event.put(
                "message",
                "Watch patterns disabled for process "
                        + managed.getId()
                        + " after "
                        + watchStrikeLimit
                        + " consecutive rate-limit windows. Falling back to notify_on_complete"
                        + " semantics.");
        enqueueEvent(event);
    }

    /**
     * 执行enqueue补全文本IfNeeded相关逻辑。
     *
     * @param managed managed 参数。
     */
    private void enqueueCompletionIfNeeded(ManagedProcess managed) {
        boolean shouldQueue;
        synchronized (managed) {
            shouldQueue =
                    managed.notifyOnComplete && managed.exited && !managed.completionEventQueued;
            if (shouldQueue) {
                managed.completionEventQueued = true;
            }
        }
        if (!shouldQueue) {
            return;
        }
        Map<String, Object> event = baseEvent("completion", managed);
        event.put("exit_code", managed.getExitCode());
        event.put(
                "output",
                SecretRedactor.redact(
                        TerminalAnsiSanitizer.stripAnsi(tail(managed.getOutput(), 2000))));
        enqueueEvent(event);
    }

    /**
     * 记录Started。
     *
     * @param managed managed 参数。
     */
    private void recordStarted(ManagedProcess managed) {
        if (managed == null) {
            return;
        }
        lifecycleTracker.recordStarted(managed.getId(), managed.getCommand(), managed.getCwd());
    }

    /**
     * 记录Exit If Needed。
     *
     * @param managed managed 参数。
     */
    private void recordExitIfNeeded(ManagedProcess managed) {
        if (managed == null) {
            return;
        }
        Integer exitCode = managed.getExitCodeDirect();
        if (exitCode != null && exitCode.intValue() == 0) {
            lifecycleTracker.recordCompleted(
                    managed.getId(), managed.getCommand(), exitCode.intValue());
        } else {
            lifecycleTracker.recordFailed(
                    managed.getId(),
                    managed.getCommand(),
                    exitCode == null ? -1 : exitCode.intValue(),
                    exitCode == null ? "process exited" : "exit code " + exitCode);
        }
    }

    /**
     * 记录Killed If Needed。
     *
     * @param managed managed 参数。
     */
    private void recordKilledIfNeeded(ManagedProcess managed) {
        if (managed == null) {
            return;
        }
        lifecycleTracker.recordKilled(managed.getId(), managed.getCommand());
    }

    /**
     * 执行基础事件相关逻辑。
     *
     * @param type 类型参数。
     * @param managed managed 参数。
     * @return 返回base事件结果。
     */
    private Map<String, Object> baseEvent(String type, ManagedProcess managed) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("session_id", managed.getId());
        event.put("id", managed.getId());
        event.put("command", SecretRedactor.redact(managed.getCommand()));
        event.put("status", managed.isExited() ? "exited" : "running");
        event.put("pid", managed.getPid());
        event.put("created_at", Long.valueOf(System.currentTimeMillis()));
        return event;
    }

    /**
     * 执行摘要事件相关逻辑。
     *
     * @param type 类型参数。
     * @return 返回summary事件结果。
     */
    private Map<String, Object> summaryEvent(String type) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("session_id", "");
        event.put("id", "");
        event.put("command", "");
        event.put("status", "summary");
        event.put("created_at", Long.valueOf(System.currentTimeMillis()));
        return event;
    }

    /**
     * 执行enqueue事件相关逻辑。
     *
     * @param event 事件参数。
     */
    private void enqueueEvent(Map<String, Object> event) {
        processEvents.offer(redactEvent(event));
    }

    /**
     * 脱敏事件。
     *
     * @param event 事件参数。
     * @return 返回事件结果。
     */
    private Map<String, Object> redactEvent(Map<String, Object> event) {
        Map<String, Object> redacted = new LinkedHashMap<String, Object>();
        if (event == null) {
            return redacted;
        }
        for (Map.Entry<String, Object> entry : event.entrySet()) {
            redacted.put(entry.getKey(), redactEventValue(entry.getValue()));
        }
        return redacted;
    }

    /**
     * 脱敏事件Value。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回事件Value结果。
     */
    @SuppressWarnings("unchecked")
    private Object redactEventValue(Object value) {
        if (value instanceof String) {
            return SecretRedactor.redact((String) value);
        }
        if (value instanceof List) {
            List<Object> redacted = new ArrayList<Object>();
            for (Object item : (List<Object>) value) {
                redacted.add(redactEventValue(item));
            }
            return redacted;
        }
        if (value instanceof Map) {
            Map<Object, Object> redacted = new LinkedHashMap<Object, Object>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                redacted.put(entry.getKey(), redactEventValue(entry.getValue()));
            }
            return redacted;
        }
        return value;
    }

    /**
     * 执行限制Watch输出相关逻辑。
     *
     * @param matchedLines matchedLines 参数。
     * @return 返回限制Watch输出结果。
     */
    private String limitWatchOutput(List<String> matchedLines) {
        StringBuilder buffer = new StringBuilder();
        int count = Math.min(20, matchedLines.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                buffer.append('\n');
            }
            buffer.append(matchedLines.get(i));
        }
        String output = buffer.toString();
        if (output.length() <= 2000) {
            return output;
        }
        return output.substring(0, 2000) + "\n...(truncated)";
    }

    /**
     * 脱敏Lines。
     *
     * @param lines lines 参数。
     * @return 返回Lines结果。
     */
    private List<String> redactLines(List<String> lines) {
        List<String> redacted = new ArrayList<String>();
        for (String line : lines) {
            redacted.add(SecretRedactor.redact(line));
        }
        return redacted;
    }

    /**
     * 执行tail相关逻辑。
     *
     * @param text 待处理文本。
     * @param maxChars maxChars 参数。
     * @return 返回tail结果。
     */
    private static String tail(String text, int maxChars) {
        String value = StrUtil.nullToEmpty(text);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars);
    }

    /**
     * 执行trimTrailingCarriageReturn相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回trim Trailing Carriage Return结果。
     */
    private static String trimTrailingCarriageReturn(String value) {
        if (value != null && value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 执行终端命令相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @param shellInitFiles 文件或目录路径参数。
     * @param windows Windows参数。
     * @return 返回Shell命令结果。
     */
    static List<String> shellCommand(String command, List<String> shellInitFiles, boolean windows) {
        List<String> parts = new ArrayList<String>();
        if (windows) {
            parts.add("cmd");
            parts.add("/c");
            parts.add(windowsUtf8Command(command));
        } else {
            parts.add("/bin/sh");
            parts.add("-lc");
            parts.add(SolonClawShellSkill.prependShellInit(command, shellInitFiles, false));
        }
        return parts;
    }

    /**
     * 包装 Windows 后台命令，避免系统代码页导致中文输出被 UTF-8 读取时出现乱码。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回带 UTF-8 代码页初始化的命令文本。
     */
    private static String windowsUtf8Command(String command) {
        return "if exist \"%SystemRoot%\\System32\\chcp.com\" "
                + "\"%SystemRoot%\\System32\\chcp.com\" 65001 >nul 2>nul & "
                + command;
    }

    /**
     * 解析Shell Init Files。
     *
     * @return 返回解析后的Shell Init Files。
     */
    private List<String> resolveShellInitFiles() {
        List<String> configured = Collections.emptyList();
        boolean autoSource = true;
        if (appConfig != null
                && appConfig.getTerminal() != null
                && appConfig.getTerminal().getShellInitFiles() != null) {
            configured = appConfig.getTerminal().getShellInitFiles();
            autoSource = appConfig.getTerminal().isAutoSourceBashrc();
        }
        String home =
                StrUtil.blankToDefault(
                        ProfileRuntimeScope.environmentValue("HOME"),
                        System.getProperty("user.home"));
        return SolonClawShellSkill.resolveShellInitFiles(
                configured,
                autoSource,
                isWindows(),
                home,
                ProfileRuntimeScope.environmentSnapshot(),
                appConfig == null ? null : new SecurityPolicyService(appConfig));
    }

    /**
     * 执行rewriteCompoundBackground相关逻辑。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回rewrite Compound Background结果。
     */
    static String rewriteCompoundBackground(String command) {
        if (command == null || command.length() == 0) {
            return command;
        }
        int n = command.length();
        int i = 0;
        int parenDepth = 0;
        int braceDepth = 0;
        int lastChainOpEnd = -1;
        List<int[]> rewrites = new ArrayList<int[]>();
        while (i < n) {
            char ch = command.charAt(i);
            if (ch == '\n' && parenDepth == 0 && braceDepth == 0) {
                lastChainOpEnd = -1;
                i++;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '#') {
                int nl = command.indexOf('\n', i);
                if (nl < 0) {
                    break;
                }
                i = nl;
                continue;
            }
            if (ch == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                i = Math.max(readShellTokenEnd(command, i), i + 1);
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                i++;
                continue;
            }
            if (ch == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
                i++;
                continue;
            }
            if (ch == '{' && i + 1 < n && Character.isWhitespace(command.charAt(i + 1))) {
                braceDepth++;
                i++;
                continue;
            }
            if (ch == '}' && braceDepth > 0) {
                braceDepth--;
                lastChainOpEnd = -1;
                i++;
                continue;
            }
            if (parenDepth > 0 || braceDepth > 0) {
                i++;
                continue;
            }
            if (startsWith(command, i, "&&") || startsWith(command, i, "||")) {
                lastChainOpEnd = i + 2;
                i += 2;
                continue;
            }
            if (ch == ';') {
                lastChainOpEnd = -1;
                i++;
                continue;
            }
            if (ch == '|') {
                lastChainOpEnd = -1;
                i++;
                continue;
            }
            if (ch == '&') {
                if (i + 1 < n && command.charAt(i + 1) == '>') {
                    i += 2;
                    continue;
                }
                int j = i - 1;
                while (j >= 0 && Character.isWhitespace(command.charAt(j))) {
                    j--;
                }
                if (j >= 0 && (command.charAt(j) == '<' || command.charAt(j) == '>')) {
                    i++;
                    continue;
                }
                if (lastChainOpEnd >= 0) {
                    rewrites.add(new int[] {lastChainOpEnd, i});
                }
                lastChainOpEnd = -1;
                i++;
                continue;
            }
            i = Math.max(readShellTokenEnd(command, i), i + 1);
        }
        if (rewrites.isEmpty()) {
            return command;
        }
        String result = command;
        for (int r = rewrites.size() - 1; r >= 0; r--) {
            int chainEnd = rewrites.get(r)[0];
            int ampPos = rewrites.get(r)[1];
            int insertPos = chainEnd;
            while (insertPos < ampPos && Character.isWhitespace(result.charAt(insertPos))) {
                insertPos++;
            }
            result =
                    result.substring(0, insertPos)
                            + "{ "
                            + result.substring(insertPos, ampPos)
                            + "& }"
                            + result.substring(ampPos + 1);
        }
        return result;
    }

    /**
     * 读取Shell token End。
     *
     * @param command 待执行或解析的命令文本。
     * @param start start 参数。
     * @return 返回读取到的Shell token End。
     */
    private static int readShellTokenEnd(String command, int start) {
        int i = start;
        int n = command.length();
        char quote = 0;
        while (i < n) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (ch == '\\' && quote == '"' && i + 1 < n) {
                    i += 2;
                    continue;
                }
                if (ch == quote) {
                    i++;
                    quote = 0;
                    continue;
                }
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                i++;
                continue;
            }
            if (ch == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (Character.isWhitespace(ch)
                    || ch == ';'
                    || ch == '&'
                    || ch == '|'
                    || ch == '('
                    || ch == ')'
                    || ch == '{'
                    || ch == '}'
                    || ch == '#') {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * 执行startsWith相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @param offset 分页偏移量。
     * @param prefix prefix 参数。
     * @return 返回starts With结果。
     */
    private static boolean startsWith(String value, int offset, String prefix) {
        return offset >= 0
                && offset + prefix.length() <= value.length()
                && value.substring(offset, offset + prefix.length()).equals(prefix);
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * 解析Pid。
     *
     * @param process 进程参数。
     * @return 返回解析后的Pid。
     */
    private static Long resolvePid(Process process) {
        if (process == null) {
            return null;
        }
        try {
            Method method = process.getClass().getMethod("pid");
            Object value = method.invoke(process);
            if (value instanceof Number) {
                return Long.valueOf(((Number) value).longValue());
            }
        } catch (Exception e) {
            logRecoverableFailure("resolve-pid", e);
        }
        return null;
    }

    /**
     * 记录可恢复进程异常，只写阶段和异常类型，避免泄露命令、路径或输出内容。
     *
     * @param stage 降级阶段。
     * @param error 异常对象。
     */
    private static void logRecoverableFailure(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "process registry fallback. stage={} error={}", stage, exceptionSummary(error));
        }
    }

    /**
     * 生成低敏异常摘要，仅保留异常类型。
     *
     * @param error 异常对象。
     * @return 返回异常类型摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    /** 承载Managed进程相关状态和辅助逻辑。 */
    public static class ManagedProcess {
        /** 记录Managed进程中的owner。 */
        private final ProcessRegistry owner;

        /** 记录Managed进程中的标识。 */
        private final String id;

        /** 记录Managed进程中的命令。 */
        private final String command;

        /** 记录Managed进程中的工作目录。 */
        private final String cwd;

        /** 记录Managed进程中的进程。 */
        private final Process process;

        /** 记录Managed进程中的started时间。 */
        private final long startedAt;

        /** 记录Managed进程中的max输出Chars。 */
        private final int maxOutputChars;

        /** 记录Managed进程中的输出。 */
        private final StringBuilder output = new StringBuilder();

        /** 记录Managed进程中的进程ID。 */
        private Long pid;

        /** 是否启用exited。 */
        private boolean exited;

        /** 记录Managed进程中的退出Code。 */
        private Integer exitCode;

        /** 是否启用truncated。 */
        private boolean truncated;

        /** 是否启用stdinClosed。 */
        private boolean stdinClosed;

        /** 是否启用notifyOnComplete。 */
        private boolean notifyOnComplete;

        /** 保存watchPatterns集合，维持调用顺序或去重语义。 */
        private List<String> watchPatterns = Collections.emptyList();

        /** 记录Managed进程中的watchHits。 */
        private int watchHits;

        /** 记录Managed进程中的watchSuppressed。 */
        private int watchSuppressed;

        /** 是否启用watchDisabled。 */
        private boolean watchDisabled;

        /** 记录Managed进程中的watch最近一次Emit时间。 */
        private long watchLastEmitAt;

        /** 记录Managed进程中的watchCooldownUntil。 */
        private long watchCooldownUntil;

        /** 是否启用watchStrikeCandidate。 */
        private boolean watchStrikeCandidate;

        /** 记录Managed进程中的watchConsecutiveStrikes。 */
        private int watchConsecutiveStrikes;

        /** 是否启用补全文本事件排队。 */
        private boolean completionEventQueued;

        /** 是否启用生命周期退出事件Recorded。 */
        private boolean lifecycleExitEventRecorded;

        /** 是否启用生命周期Kill事件Recorded。 */
        private boolean lifecycleKillEventRecorded;

        /**
         * 创建Managed进程实例，并注入运行所需依赖。
         *
         * @param owner owner 参数。
         * @param id 标识。
         * @param command 待执行或解析的命令文本。
         * @param cwd 工作目录参数。
         * @param process 进程参数。
         * @param startedAt startedAt 参数。
         * @param maxOutputChars max输出Chars参数。
         */
        ManagedProcess(
                ProcessRegistry owner,
                String id,
                String command,
                String cwd,
                Process process,
                long startedAt,
                int maxOutputChars) {
            this.owner = owner;
            this.id = id;
            this.command = command;
            this.cwd = cwd;
            this.process = process;
            this.startedAt = startedAt;
            this.maxOutputChars = maxOutputChars;
        }

        /** 启动Reader。 */
        void startReader() {
            Thread reader =
                    new Thread(
                            ProfileRuntimeScope.capture(
                                    new Runnable() {
                                        /** 执行异步任务主体。 */
                                        @Override
                                        public void run() {
                                            readOutputLoop();
                                        }
                                    }),
                            "jimuqu-process-" + id);
            reader.setDaemon(true);
            reader.start();
        }

        /** 读取输出循环。 */
        private void readOutputLoop() {
            try {
                InputStreamReader reader =
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
                char[] chars = new char[4096];
                int read;
                while ((read = reader.read(chars)) != -1) {
                    appendOutput(new String(chars, 0, read));
                }
            } catch (Exception e) {
                String message =
                        StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
                appendOutput(
                        "\n[process output reader failed: "
                                + SecretRedactor.redact(message, 1000)
                                + "]");
            } finally {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                refreshExitState();
            }
        }

        /**
         * 追加输出。
         *
         * @param text 待处理文本。
         */
        private synchronized void appendOutput(String text) {
            output.append(text);
            if (output.length() > maxOutputChars) {
                int overflow = output.length() - maxOutputChars;
                output.delete(0, overflow);
                truncated = true;
            }
            if (owner != null) {
                owner.checkWatchPatterns(this, text);
            }
        }

        /**
         * 刷新Exit状态。
         *
         * @return 返回Exit状态。
         */
        synchronized boolean refreshExitState() {
            if (exited) {
                return false;
            }
            try {
                exitCode = Integer.valueOf(process.exitValue());
                exited = true;
                if (owner != null) {
                    if (!lifecycleExitEventRecorded && !lifecycleKillEventRecorded) {
                        lifecycleExitEventRecorded = true;
                        owner.recordExitIfNeeded(this);
                    }
                    owner.enqueueCompletionIfNeeded(this);
                }
                return true;
            } catch (IllegalThreadStateException e) {
                if (log.isTraceEnabled()) {
                    log.trace(
                            "process registry fallback. stage=refresh-exit-state error={}",
                            exceptionSummary(e));
                }
                exited = false;
                return false;
            }
        }

        /**
         * 执行waitFor相关逻辑。
         *
         * @param timeoutMillis timeoutMillis 参数。
         * @return 返回wait For结果。
         */
        boolean waitFor(long timeoutMillis) throws InterruptedException {
            boolean finished;
            if (timeoutMillis < 0) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            refreshExitState();
            return finished;
        }

        /** 停止当前组件并释放运行状态。 */
        void stop() {
            refreshExitState();
            if (!isExited()) {
                synchronized (this) {
                    if (owner != null && !lifecycleKillEventRecorded) {
                        lifecycleKillEventRecorded = true;
                        owner.recordKilledIfNeeded(this);
                    }
                }
                process.destroy();
                try {
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            refreshExitState();
        }

        /**
         * 写入Stdin。
         *
         * @param data 数据参数。
         */
        void writeStdin(String data) throws Exception {
            refreshExitState();
            if (isExited()) {
                throw new IllegalStateException("Process has already exited");
            }
            synchronized (this) {
                if (stdinClosed) {
                    throw new IllegalStateException("Process stdin is already closed");
                }
                OutputStreamWriter writer =
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(data == null ? "" : data);
                writer.flush();
            }
        }

        /** 关闭Stdin。 */
        void closeStdin() throws Exception {
            synchronized (this) {
                if (stdinClosed) {
                    return;
                }
                process.getOutputStream().close();
                stdinClosed = true;
            }
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        public Map<String, Object> toMap() {
            refreshExitState();
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("session_id", id);
            map.put("id", id);
            map.put("command", SecretRedactor.redact(command));
            map.put("cwd", displayCwd());
            map.put("pid", pid);
            map.put("started_at", Long.valueOf(startedAt));
            map.put("started_at_iso", isoLocal(startedAt));
            map.put("uptime_seconds", Long.valueOf(uptimeSeconds()));
            map.put("status", exited ? "exited" : "running");
            map.put("exited", Boolean.valueOf(exited));
            map.put("running", Boolean.valueOf(!exited));
            map.put("exit_code", exitCode);
            String exitCodeMeaning = TerminalExitCodeSemantics.interpret(command, exitCode);
            if (exitCodeMeaning != null) {
                map.put("exit_code_meaning", exitCodeMeaning);
            }
            map.put("notify_on_complete", Boolean.valueOf(notifyOnComplete));
            if (!watchPatterns.isEmpty()) {
                map.put("watch_patterns", redactedStringList(watchPatterns));
            }
            map.put("watch_hits", Integer.valueOf(watchHits));
            map.put("watch_suppressed", Integer.valueOf(watchSuppressed));
            map.put("watch_disabled", Boolean.valueOf(watchDisabled));
            map.put("output", SecretRedactor.redact(getOutput()));
            map.put("output_preview", SecretRedactor.redact(outputPreview(200)));
            map.put("truncated", Boolean.valueOf(truncated));
            map.put("stdin_closed", Boolean.valueOf(isStdinClosed()));
            Map<String, Object> lifecycleLastEvent =
                    owner == null
                            ? Collections.<String, Object>emptyMap()
                            : owner.lastLifecycleEventForProcess(id);
            if (!lifecycleLastEvent.isEmpty()) {
                map.put("lifecycle_last_event", lifecycleLastEvent);
            }
            return map;
        }

        /**
         * 转换为Redacted Map。
         *
         * @return 返回转换后的Redacted Map。
         */
        public Map<String, Object> toRedactedMap() {
            return toMap();
        }

        /**
         * 执行redacted字符串列表相关逻辑。
         *
         * @param values 待规范化或校验的原始值集合。
         * @return 返回redacted String List结果。
         */
        private List<Object> redactedStringList(List<String> values) {
            List<Object> redacted = new ArrayList<Object>();
            for (String item : values) {
                redacted.add(SecretRedactor.redact(item));
            }
            return redacted;
        }

        /**
         * 读取标识。
         *
         * @return 返回读取到的标识。
         */
        public String getId() {
            return id;
        }

        /**
         * 读取命令。
         *
         * @return 返回读取到的命令。
         */
        public String getCommand() {
            return command;
        }

        /**
         * 读取Cwd。
         *
         * @return 返回读取到的Cwd。
         */
        public String getCwd() {
            return cwd;
        }

        /**
         * 执行展示工作目录相关逻辑。
         *
         * @return 返回展示Cwd结果。
         */
        public String displayCwd() {
            if (StrUtil.isBlank(cwd)) {
                return "";
            }
            String name = new File(cwd).getName();
            if (StrUtil.isBlank(name)) {
                name = "workspace";
            }
            return "path://" + SecretRedactor.redact(name, 200);
        }

        /**
         * 读取Pid。
         *
         * @return 返回读取到的Pid。
         */
        public Long getPid() {
            return pid;
        }

        /**
         * 写入Pid。
         *
         * @param pid 系统进程标识。
         */
        void setPid(Long pid) {
            this.pid = pid;
        }

        /**
         * 读取Started时间。
         *
         * @return 返回读取到的Started时间。
         */
        public long getStartedAt() {
            return startedAt;
        }

        /**
         * 执行uptimeSeconds相关逻辑。
         *
         * @return 返回uptime Seconds结果。
         */
        public long uptimeSeconds() {
            return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        }

        /**
         * 执行输出预览相关逻辑。
         *
         * @param maxChars maxChars 参数。
         * @return 返回输出Preview结果。
         */
        public synchronized String outputPreview(int maxChars) {
            String value = output.toString();
            if (value.length() <= maxChars) {
                return value;
            }
            return value.substring(value.length() - maxChars);
        }

        /**
         * 判断是否Exited。
         *
         * @return 如果Exited满足条件则返回 true，否则返回 false。
         */
        public synchronized boolean isExited() {
            refreshExitState();
            return exited;
        }

        /**
         * 读取退出码。
         *
         * @return 返回读取到的退出码。
         */
        public synchronized Integer getExitCode() {
            refreshExitState();
            return exitCode;
        }

        /**
         * 读取退出码 Direct。
         *
         * @return 返回读取到的退出码 Direct。
         */
        synchronized Integer getExitCodeDirect() {
            return exitCode;
        }

        /**
         * 读取输出。
         *
         * @return 返回读取到的输出。
         */
        public synchronized String getOutput() {
            return output.toString();
        }

        /**
         * 判断是否Truncated。
         *
         * @return 如果Truncated满足条件则返回 true，否则返回 false。
         */
        public synchronized boolean isTruncated() {
            return truncated;
        }

        /**
         * 判断是否Stdin Closed。
         *
         * @return 如果Stdin Closed满足条件则返回 true，否则返回 false。
         */
        public synchronized boolean isStdinClosed() {
            return stdinClosed;
        }

        /**
         * 判断是否Notify On Complete。
         *
         * @return 如果Notify On Complete满足条件则返回 true，否则返回 false。
         */
        public synchronized boolean isNotifyOnComplete() {
            return notifyOnComplete;
        }

        /**
         * 写入Notify On Complete。
         *
         * @param notifyOnComplete 后台任务完成后是否通知。。
         */
        public synchronized void setNotifyOnComplete(boolean notifyOnComplete) {
            this.notifyOnComplete = notifyOnComplete;
            if (notifyOnComplete && exited && owner != null) {
                owner.enqueueCompletionIfNeeded(this);
            }
        }

        /**
         * 读取Watch Patterns。
         *
         * @return 返回读取到的Watch Patterns。
         */
        public synchronized List<String> getWatchPatterns() {
            return new ArrayList<String>(watchPatterns);
        }

        /**
         * 写入Watch Patterns。
         *
         * @param watchPatterns 需要监听并提示的输出模式。。
         */
        public synchronized void setWatchPatterns(List<String> watchPatterns) {
            if (watchPatterns == null || watchPatterns.isEmpty()) {
                this.watchPatterns = Collections.emptyList();
            } else {
                this.watchPatterns =
                        Collections.unmodifiableList(new ArrayList<String>(watchPatterns));
            }
        }

        /**
         * 读取Watch Hits。
         *
         * @return 返回读取到的Watch Hits。
         */
        public synchronized int getWatchHits() {
            return watchHits;
        }

        /**
         * 读取Watch Suppressed。
         *
         * @return 返回读取到的Watch Suppressed。
         */
        public synchronized int getWatchSuppressed() {
            return watchSuppressed;
        }

        /**
         * 判断是否Watch Disabled。
         *
         * @return 如果Watch Disabled满足条件则返回 true，否则返回 false。
         */
        public synchronized boolean isWatchDisabled() {
            return watchDisabled;
        }

        /**
         * 执行iso本地相关逻辑。
         *
         * @param timestamp 请求携带的时间戳。
         * @return 返回iso本地结果。
         */
        private static String isoLocal(long timestamp) {
            return LOCAL_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp));
        }
    }

    /** 表示Stop结果，携带调用方后续判断所需信息。 */
    public static class StopResult {
        /** 记录Stop中的状态。 */
        private final String status;

        /** 是否启用stopped。 */
        private final boolean stopped;

        /** 记录Stop中的会话标识。 */
        private final String sessionId;

        /** 记录Stop中的退出Code。 */
        private final Integer exitCode;

        /**
         * 创建Stop结果实例，并注入运行所需依赖。
         *
         * @param status 状态参数。
         * @param stopped stopped 参数。
         * @param sessionId 当前会话标识。
         * @param exitCode 命令退出码。
         */
        StopResult(String status, boolean stopped, String sessionId, Integer exitCode) {
            this.status = status;
            this.stopped = stopped;
            this.sessionId = sessionId;
            this.exitCode = exitCode;
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        public String getStatus() {
            return status;
        }

        /**
         * 判断是否Stopped。
         *
         * @return 如果Stopped满足条件则返回 true，否则返回 false。
         */
        public boolean isStopped() {
            return stopped;
        }

        /**
         * 读取会话标识。
         *
         * @return 返回读取到的会话标识。
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 读取退出码。
         *
         * @return 返回读取到的退出码。
         */
        public Integer getExitCode() {
            return exitCode;
        }
    }
}
