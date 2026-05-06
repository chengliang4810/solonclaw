package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/** Hermes 风格的受管后台进程注册表。 */
public class ProcessRegistry {
    private static final int MAX_OUTPUT_CHARS = 200000;
    private final Map<String, ManagedProcess> processes =
            Collections.synchronizedMap(new LinkedHashMap<String, ManagedProcess>());

    public String add(Process process) {
        String id = IdSupport.newId();
        ManagedProcess managed =
                new ManagedProcess(id, "", null, process, System.currentTimeMillis(), MAX_OUTPUT_CHARS);
        managed.setPid(resolvePid(process));
        processes.put(id, managed);
        return id;
    }

    public ManagedProcess start(String command, File workDir) throws Exception {
        validateCommand(command);
        String executableCommand = isWindows() ? command : rewriteCompoundBackground(command);
        List<String> shellCommand = shellCommand(executableCommand);
        ProcessBuilder builder = new ProcessBuilder(shellCommand);
        if (workDir != null) {
            builder.directory(workDir);
        }
        builder.redirectErrorStream(true);
        SubprocessEnvironmentSanitizer.sanitize(builder.environment());
        Process process = builder.start();
        String id = "proc_" + IdSupport.newId();
        ManagedProcess managed =
                new ManagedProcess(
                        id,
                        executableCommand,
                        workDir == null ? null : workDir.getAbsolutePath(),
                        process,
                        System.currentTimeMillis(),
                        MAX_OUTPUT_CHARS);
        managed.setPid(resolvePid(process));
        processes.put(id, managed);
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

    private static List<String> shellCommand(String command) {
        List<String> parts = new ArrayList<String>();
        if (isWindows()) {
            parts.add("cmd");
            parts.add("/c");
            parts.add(command);
        } else {
            parts.add("/bin/sh");
            parts.add("-lc");
            parts.add(command);
        }
        return parts;
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

        ManagedProcess(
                String id,
                String command,
                String cwd,
                Process process,
                long startedAt,
                int maxOutputChars) {
            this.id = id;
            this.command = command;
            this.cwd = cwd;
            this.process = process;
            this.startedAt = startedAt;
            this.maxOutputChars = maxOutputChars;
            refreshExitState();
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
                appendOutput("\n[process output reader failed: " + e.getMessage() + "]");
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
        }

        synchronized void refreshExitState() {
            if (exited) {
                return;
            }
            try {
                exitCode = Integer.valueOf(process.exitValue());
                exited = true;
            } catch (IllegalThreadStateException ignored) {
                exited = false;
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
            map.put("command", command);
            map.put("cwd", cwd);
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
            map.put("output", getOutput());
            map.put("output_preview", outputPreview(200));
            map.put("truncated", Boolean.valueOf(truncated));
            map.put("stdin_closed", Boolean.valueOf(isStdinClosed()));
            return map;
        }

        public Map<String, Object> toRedactedMap() {
            Map<String, Object> map = toMap();
            redactMapText(map, "command");
            redactMapText(map, "cwd");
            redactMapText(map, "output");
            redactMapText(map, "output_preview");
            return map;
        }

        private void redactMapText(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value instanceof String) {
                map.put(key, SecretRedactor.redact((String) value));
            }
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

        public synchronized String getOutput() {
            return output.toString();
        }

        public synchronized boolean isTruncated() {
            return truncated;
        }

        public synchronized boolean isStdinClosed() {
            return stdinClosed;
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
