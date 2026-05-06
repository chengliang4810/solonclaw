package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.support.IdSupport;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        List<String> shellCommand = shellCommand(command);
        ProcessBuilder builder = new ProcessBuilder(shellCommand);
        if (workDir != null) {
            builder.directory(workDir);
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String id = "proc_" + IdSupport.newId();
        ManagedProcess managed =
                new ManagedProcess(
                        id,
                        command,
                        workDir == null ? null : workDir.getAbsolutePath(),
                        process,
                        System.currentTimeMillis(),
                        MAX_OUTPUT_CHARS);
        managed.setPid(resolvePid(process));
        processes.put(id, managed);
        managed.startReader();
        return managed;
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
        ManagedProcess managed = processes.get(id);
        if (managed == null) {
            return false;
        }
        managed.stop();
        return true;
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
            map.put("exited", Boolean.valueOf(exited));
            map.put("running", Boolean.valueOf(!exited));
            map.put("exit_code", exitCode);
            map.put("output", getOutput());
            map.put("truncated", Boolean.valueOf(truncated));
            map.put("stdin_closed", Boolean.valueOf(isStdinClosed()));
            return map;
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
    }
}
