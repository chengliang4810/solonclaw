package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;

/**
 * Gateway runtime status service. Manages PID file and runtime state for detecting whether the
 * gateway is running.
 */
public class GatewayRuntimeStatusService {
    private static final String GATEWAY_KIND = "solon-claw-gateway";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private final File pidFile;
    private final File stateFile;
    private final AppConfig appConfig;
    private volatile long startedAt;

    public GatewayRuntimeStatusService(AppConfig appConfig) {
        this.appConfig = appConfig;
        File home = new File(appConfig.getRuntime().getHome());
        this.pidFile = new File(home, "gateway.pid");
        this.stateFile = new File(home, "gateway_state.json");
        this.startedAt = System.currentTimeMillis();
    }

    public void writePidFile() {
        try {
            FileUtil.mkParentDirs(pidFile);
            FileUtil.writeString(ONode.serialize(buildRuntimeRecord()), pidFile, UTF_8);
        } catch (Exception ignored) {
        }
    }

    public void removePidFile() {
        try {
            if (pidFile.isFile()) {
                Map<String, Object> record = readPidRecord();
                if (record == null
                        || matchesCurrentProcess(
                                record, Boolean.TRUE.equals(record.get("legacy")))) {
                    pidFile.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void writeState(String status, String detail) {
        try {
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.putAll(buildRuntimeRecord());
            state.put("status", StrUtil.blankToDefault(status, "running"));
            state.put("startedAt", Long.valueOf(startedAt));
            state.put("updatedAt", Long.valueOf(System.currentTimeMillis()));
            state.put("uptimeMs", Long.valueOf(System.currentTimeMillis() - startedAt));
            if (StrUtil.isNotBlank(detail)) {
                state.put("detail", detail);
            }
            FileUtil.mkParentDirs(stateFile);
            FileUtil.writeString(ONode.serialize(state), stateFile, UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isRunning() {
        if (!pidFile.isFile()) {
            return false;
        }
        try {
            Map<String, Object> record = readPidRecord();
            return record != null && isRunningRecord(record);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readState() {
        try {
            if (stateFile.isFile()) {
                String json = FileUtil.readString(stateFile, UTF_8);
                if (StrUtil.isNotBlank(json)) {
                    Object parsed = ONode.deserialize(json, Object.class);
                    if (parsed instanceof Map) {
                        return (Map<String, Object>) parsed;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    public Map<String, Object> currentStatus() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("running", Boolean.valueOf(isRunning()));
        status.put("pid", Long.valueOf(getCurrentPid()));
        status.put("startedAt", Long.valueOf(startedAt));
        status.put("startTime", Long.valueOf(getCurrentJvmStartTime()));
        status.put("startInstant", Instant.ofEpochMilli(getCurrentJvmStartTime()).toString());
        status.put("uptimeMs", Long.valueOf(System.currentTimeMillis() - startedAt));
        status.put("state", readState());
        return status;
    }

    private Map<String, Object> buildRuntimeRecord() {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        long startTime = getCurrentJvmStartTime();
        record.put("pid", Long.valueOf(getCurrentPid()));
        record.put("kind", GATEWAY_KIND);
        record.put("startTime", Long.valueOf(startTime));
        record.put("startInstant", Instant.ofEpochMilli(startTime).toString());
        record.put("command", safeCommandName());
        record.put("name", GATEWAY_KIND);
        return record;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPidRecord() {
        String raw = FileUtil.readString(pidFile, UTF_8).trim();
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            Object parsed = ONode.deserialize(raw, Object.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            Long pid = asLong(parsed);
            if (pid != null) {
                return legacyPidRecord(pid.longValue());
            }
        } catch (Exception ignored) {
        }
        Long pid = asLong(raw);
        if (pid == null) {
            return null;
        }
        return legacyPidRecord(pid.longValue());
    }

    private Map<String, Object> legacyPidRecord(long pid) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("pid", Long.valueOf(pid));
        record.put("legacy", Boolean.TRUE);
        return record;
    }

    private boolean isRunningRecord(Map<String, Object> record) {
        Long pid = asLong(record.get("pid"));
        if (pid == null || pid.longValue() <= 0L) {
            return false;
        }
        if (!isProcessAlive(pid.longValue())) {
            return false;
        }

        boolean legacy = Boolean.TRUE.equals(record.get("legacy"));
        String kind = safeText(record.get("kind"));
        if (!legacy && !GATEWAY_KIND.equals(kind)) {
            return false;
        }

        long currentPid = getCurrentPid();
        if (pid.longValue() == currentPid) {
            return matchesCurrentProcess(record, legacy);
        }

        return matchesOtherProcess(record, legacy, pid.longValue());
    }

    private boolean matchesCurrentProcess(Map<String, Object> record, boolean legacy) {
        Long recordedStartTime = asLong(record.get("startTime"));
        if (recordedStartTime != null
                && recordedStartTime.longValue() != getCurrentJvmStartTime()) {
            return false;
        }
        return legacy || GATEWAY_KIND.equals(safeText(record.get("kind")));
    }

    private boolean matchesOtherProcess(Map<String, Object> record, boolean legacy, long pid) {
        Long recordedStartTime = asLong(record.get("startTime"));
        Long processStartTime = readProcessStartTime(pid);
        if (recordedStartTime != null
                && processStartTime != null
                && recordedStartTime.longValue() / 1000L != processStartTime.longValue() / 1000L) {
            return false;
        }

        String liveCommand = readProcessCommand(pid);
        if (StrUtil.isNotBlank(liveCommand)) {
            return looksLikeGatewayCommand(liveCommand);
        }
        if (legacy) {
            return false;
        }
        return looksLikeGatewayCommand(safeText(record.get("command")));
    }

    private Long readProcessStartTime(long pid) {
        String output = runCommand(new String[] {"ps", "-p", String.valueOf(pid), "-o", "lstart="});
        if (StrUtil.isBlank(output)) {
            return null;
        }
        try {
            SimpleDateFormat format =
                    new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);
            Date date = format.parse(output.trim().replaceAll("\\s+", " "));
            return date == null ? null : Long.valueOf(date.getTime());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readProcessCommand(long pid) {
        File cmdlineFile = new File("/proc/" + pid + "/cmdline");
        if (cmdlineFile.isFile()) {
            try {
                byte[] bytes = FileUtil.readBytes(cmdlineFile);
                if (bytes.length > 0) {
                    return new String(bytes, UTF_8).replace('\0', ' ').trim();
                }
            } catch (Exception ignored) {
            }
        }
        return runCommand(new String[] {"ps", "-p", String.valueOf(pid), "-o", "command="});
    }

    private boolean looksLikeGatewayCommand(String command) {
        String normalized =
                StrUtil.nullToEmpty(command).replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("solon-claw")
                || normalized.contains("com.jimuqu.solon.claw")
                || normalized.contains("gateway");
    }

    private long getCurrentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
            return Long.parseLong(pid);
        } catch (Exception e) {
            return -1L;
        }
    }

    private long getCurrentJvmStartTime() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    private boolean isProcessAlive(long pid) {
        try {
            Process process =
                    Runtime.getRuntime().exec(new String[] {"kill", "-0", String.valueOf(pid)});
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runCommand(String[] command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            String stdout = IoUtil.read(process.getInputStream(), UTF_8);
            if (process.waitFor() != 0) {
                return "";
            }
            return StrUtil.nullToEmpty(stdout).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeCommandName() {
        String command = StrUtil.nullToEmpty(System.getProperty("sun.java.command")).trim();
        if (StrUtil.isBlank(command)) {
            return "java";
        }
        String first = command.split("\\s+")[0];
        return StrUtil.blankToDefault(new File(first).getName(), first);
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        String text = safeText(value);
        if (!text.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
