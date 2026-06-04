package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/** Jimuqu 风格的受管后台进程注册表。 */
public class ProcessRegistry {
    private static final int MAX_OUTPUT_CHARS = 200000;
    private static final long WATCH_MIN_INTERVAL_MILLIS = 15000L;
    private static final int WATCH_STRIKE_LIMIT = 3;
    private static final int WATCH_GLOBAL_MAX_PER_WINDOW = 15;
    private static final long WATCH_GLOBAL_WINDOW_MILLIS = 10000L;
    private static final long WATCH_GLOBAL_COOLDOWN_MILLIS = 30000L;
    private final AppConfig appConfig;
    private final long watchMinIntervalMillis;
    private final int watchStrikeLimit;
    private final int watchGlobalMaxPerWindow;
    private final long watchGlobalWindowMillis;
    private final long watchGlobalCooldownMillis;
    private final Map<String, ManagedProcess> processes =
            Collections.synchronizedMap(new LinkedHashMap<String, ManagedProcess>());
    private final ProcessLifecycleTracker lifecycleTracker = new ProcessLifecycleTracker();
    private final Queue<Map<String, Object>> processEvents =
            new ConcurrentLinkedQueue<Map<String, Object>>();
    private final Set<String> completionConsumed =
            Collections.synchronizedSet(new HashSet<String>());
    private final Object globalWatchLock = new Object();
    private long globalWatchWindowStart;
    private int globalWatchWindowHits;
    private long globalWatchTrippedUntil;
    private int globalWatchSuppressedDuringTrip;

    public ProcessRegistry() {
        this(null);
    }

    public ProcessRegistry(AppConfig appConfig) {
        this(
                appConfig,
                WATCH_MIN_INTERVAL_MILLIS,
                WATCH_STRIKE_LIMIT,
                WATCH_GLOBAL_MAX_PER_WINDOW,
                WATCH_GLOBAL_WINDOW_MILLIS,
                WATCH_GLOBAL_COOLDOWN_MILLIS);
    }

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

    public ManagedProcess start(String command, File workDir) throws Exception {
        return start(command, workDir, false, Collections.<String>emptyList());
    }

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

    public ManagedProcess get(String id) {
        ManagedProcess managed = processes.get(id);
        if (managed != null) {
            managed.refreshExitState();
        }
        return managed;
    }

    public boolean waitFor(String id, long timeoutMillis) throws InterruptedException {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        boolean finished = managed.waitFor(timeoutMillis);
        managed.refreshExitState();
        return finished;
    }

    public boolean stop(String id) {
        StopResult result = stopDetailed(id);
        return result.isStopped() || "already_exited".equals(result.getStatus());
    }

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

    public boolean writeStdin(String id, String data) throws Exception {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        managed.writeStdin(data);
        return true;
    }

    public boolean closeStdin(String id) throws Exception {
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        managed.closeStdin();
        return true;
    }

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

    public int runningCount() {
        int count = 0;
        for (ManagedProcess managed : snapshot().values()) {
            if (!managed.isExited()) {
                count++;
            }
        }
        return count;
    }

    public List<Map<String, Object>> drainEvents() {
        return drainEvents(100);
    }

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

    public List<Map<String, Object>> recentLifecycleEvents(int limit) {
        int safeLimit = limit <= 0 ? 100 : limit;
        return lifecycleTracker.recentEventsAsMap(safeLimit);
    }

    public List<Map<String, Object>> lifecycleEventsForProcess(String id, int limit) {
        int safeLimit = limit <= 0 ? 20 : limit;
        return lifecycleTracker.eventsForProcessAsMap(id, safeLimit);
    }

    Map<String, Object> lastLifecycleEventForProcess(String id) {
        return lifecycleTracker.lastEventForProcessAsMap(id);
    }

    public void markCompletionConsumed(String sessionId) {
        if (StrUtil.isNotBlank(sessionId)) {
            completionConsumed.add(sessionId);
        }
    }

    private boolean isConsumedCompletion(Map<String, Object> event) {
        Object type = event.get("type");
        Object sessionId = event.get("session_id");
        return "completion".equals(type)
                && sessionId instanceof String
                && completionConsumed.contains(sessionId);
    }

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
                                    + "s across all processes. Suppressing further watch_match events for "
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
                        + " consecutive rate-limit windows. Falling back to notify_on_complete semantics.");
        enqueueEvent(event);
    }

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

    private void recordStarted(ManagedProcess managed) {
        if (managed == null) {
            return;
        }
        lifecycleTracker.recordStarted(managed.getId(), managed.getCommand(), managed.getCwd());
    }

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

    private void recordKilledIfNeeded(ManagedProcess managed) {
        if (managed == null) {
            return;
        }
        lifecycleTracker.recordKilled(managed.getId(), managed.getCommand());
    }

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

    private void enqueueEvent(Map<String, Object> event) {
        processEvents.offer(redactEvent(event));
    }

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

    private List<String> redactLines(List<String> lines) {
        List<String> redacted = new ArrayList<String>();
        for (String line : lines) {
            redacted.add(SecretRedactor.redact(line));
        }
        return redacted;
    }

    private static String tail(String text, int maxChars) {
        String value = StrUtil.nullToEmpty(text);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars);
    }

    private static String trimTrailingCarriageReturn(String value) {
        if (value != null && value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    static List<String> shellCommand(String command, List<String> shellInitFiles, boolean windows) {
        List<String> parts = new ArrayList<String>();
        if (windows) {
            parts.add("cmd");
            parts.add("/c");
            parts.add(command);
        } else {
            parts.add("/bin/sh");
            parts.add("-lc");
            parts.add(SolonClawShellSkill.prependShellInit(command, shellInitFiles, false));
        }
        return parts;
    }

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
                StrUtil.blankToDefault(System.getenv("HOME"), System.getProperty("user.home"));
        return SolonClawShellSkill.resolveShellInitFiles(
                configured,
                autoSource,
                isWindows(),
                home,
                System.getenv(),
                appConfig == null ? null : new SecurityPolicyService(appConfig));
    }

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

    private static boolean startsWith(String value, int offset, String prefix) {
        return offset >= 0
                && offset + prefix.length() <= value.length()
                && value.substring(offset, offset + prefix.length()).equals(prefix);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

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
        } catch (Exception ignored) {
        }
        return null;
    }

    public static class ManagedProcess {
        private final ProcessRegistry owner;
        private final String id;
        private final String command;
        private final String cwd;
        private final Process process;
        private final long startedAt;
        private final int maxOutputChars;
        private final StringBuilder output = new StringBuilder();
        private Long pid;
        private boolean exited;
        private Integer exitCode;
        private boolean truncated;
        private boolean stdinClosed;
        private boolean notifyOnComplete;
        private List<String> watchPatterns = Collections.emptyList();
        private int watchHits;
        private int watchSuppressed;
        private boolean watchDisabled;
        private long watchLastEmitAt;
        private long watchCooldownUntil;
        private boolean watchStrikeCandidate;
        private int watchConsecutiveStrikes;
        private boolean completionEventQueued;
        private boolean lifecycleExitEventRecorded;
        private boolean lifecycleKillEventRecorded;

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

        void startReader() {
            Thread reader =
                    new Thread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    readOutputLoop();
                                }
                            },
                            "jimuqu-process-" + id);
            reader.setDaemon(true);
            reader.start();
        }

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
            } catch (IllegalThreadStateException ignored) {
                exited = false;
                return false;
            }
        }

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

        void closeStdin() throws Exception {
            synchronized (this) {
                if (stdinClosed) {
                    return;
                }
                process.getOutputStream().close();
                stdinClosed = true;
            }
        }

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

        public Map<String, Object> toRedactedMap() {
            return toMap();
        }

        private List<Object> redactedStringList(List<String> values) {
            List<Object> redacted = new ArrayList<Object>();
            for (String item : values) {
                redacted.add(SecretRedactor.redact(item));
            }
            return redacted;
        }

        public String getId() {
            return id;
        }

        public String getCommand() {
            return command;
        }

        public String getCwd() {
            return cwd;
        }

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

        public Long getPid() {
            return pid;
        }

        void setPid(Long pid) {
            this.pid = pid;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public long uptimeSeconds() {
            return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        }

        public synchronized String outputPreview(int maxChars) {
            String value = output.toString();
            if (value.length() <= maxChars) {
                return value;
            }
            return value.substring(value.length() - maxChars);
        }

        public synchronized boolean isExited() {
            refreshExitState();
            return exited;
        }

        public synchronized Integer getExitCode() {
            refreshExitState();
            return exitCode;
        }

        synchronized Integer getExitCodeDirect() {
            return exitCode;
        }

        public synchronized String getOutput() {
            return output.toString();
        }

        public synchronized boolean isTruncated() {
            return truncated;
        }

        public synchronized boolean isStdinClosed() {
            return stdinClosed;
        }

        public synchronized boolean isNotifyOnComplete() {
            return notifyOnComplete;
        }

        public synchronized void setNotifyOnComplete(boolean notifyOnComplete) {
            this.notifyOnComplete = notifyOnComplete;
            if (notifyOnComplete && exited && owner != null) {
                owner.enqueueCompletionIfNeeded(this);
            }
        }

        public synchronized List<String> getWatchPatterns() {
            return new ArrayList<String>(watchPatterns);
        }

        public synchronized void setWatchPatterns(List<String> watchPatterns) {
            if (watchPatterns == null || watchPatterns.isEmpty()) {
                this.watchPatterns = Collections.emptyList();
            } else {
                this.watchPatterns =
                        Collections.unmodifiableList(new ArrayList<String>(watchPatterns));
            }
        }

        public synchronized int getWatchHits() {
            return watchHits;
        }

        public synchronized int getWatchSuppressed() {
            return watchSuppressed;
        }

        public synchronized boolean isWatchDisabled() {
            return watchDisabled;
        }

        private static String isoLocal(long timestamp) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            format.setTimeZone(TimeZone.getDefault());
            return format.format(new Date(timestamp));
        }
    }

    public static class StopResult {
        private final String status;
        private final boolean stopped;
        private final String sessionId;
        private final Integer exitCode;

        StopResult(String status, boolean stopped, String sessionId, Integer exitCode) {
            this.status = status;
            this.stopped = stopped;
            this.sessionId = sessionId;
            this.exitCode = exitCode;
        }

        public String getStatus() {
            return status;
        }

        public boolean isStopped() {
            return stopped;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Integer getExitCode() {
            return exitCode;
        }
    }
}
