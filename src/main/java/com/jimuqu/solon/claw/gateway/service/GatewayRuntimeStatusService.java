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

/** 提供消息网关运行时状态相关业务能力，封装调用方不需要感知的运行细节。 */
public class GatewayRuntimeStatusService {
    /** 消息网关KIND的统一常量值。 */
    private static final String GATEWAY_KIND = "solon-claw-gateway";

    /** UTF8的统一常量值。 */
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** 记录消息网关运行时状态中的进程ID文件。 */
    private final File pidFile;

    /** 记录消息网关运行时状态中的状态文件。 */
    private final File stateFile;

    /** 注入应用配置，用于消息网关运行时状态。 */
    private final AppConfig appConfig;

    /** 记录消息网关运行时状态中的started时间。 */
    private volatile long startedAt;

    /**
     * 创建消息网关运行时状态服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public GatewayRuntimeStatusService(AppConfig appConfig) {
        this.appConfig = appConfig;
        File home = new File(appConfig.getRuntime().getHome());
        this.pidFile = new File(home, "gateway.pid");
        this.stateFile = new File(home, "gateway_state.json");
        this.startedAt = System.currentTimeMillis();
    }

    /** 写入Pid文件。 */
    public void writePidFile() {
        try {
            FileUtil.mkParentDirs(pidFile);
            FileUtil.writeString(ONode.serialize(buildRuntimeRecord()), pidFile, UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** 移除Pid文件。 */
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

    /**
     * 写入状态。
     *
     * @param status 状态参数。
     * @param detail 详情参数。
     */
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

    /**
     * 判断是否Running。
     *
     * @return 如果Running满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 读取状态。
     *
     * @return 返回读取到的状态。
     */
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

    /**
     * 执行当前状态相关逻辑。
     *
     * @return 返回当前状态。
     */
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

    /**
     * 构建运行时记录。
     *
     * @return 返回创建好的运行时记录。
     */
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

    /**
     * 读取Pid记录。
     *
     * @return 返回读取到的Pid记录。
     */
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

    /**
     * 执行legacy进程ID记录相关逻辑。
     *
     * @param pid 系统进程标识。
     * @return 返回legacy Pid记录结果。
     */
    private Map<String, Object> legacyPidRecord(long pid) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("pid", Long.valueOf(pid));
        record.put("legacy", Boolean.TRUE);
        return record;
    }

    /**
     * 判断是否Running记录。
     *
     * @param record 记录参数。
     * @return 如果Running记录满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断状态记录是否匹配当前网关进程。
     *
     * @param record 记录参数。
     * @param legacy legacy 参数。
     * @return 返回matches当前进程结果。
     */
    private boolean matchesCurrentProcess(Map<String, Object> record, boolean legacy) {
        Long recordedStartTime = asLong(record.get("startTime"));
        if (recordedStartTime != null
                && recordedStartTime.longValue() != getCurrentJvmStartTime()) {
            return false;
        }
        return legacy || GATEWAY_KIND.equals(safeText(record.get("kind")));
    }

    /**
     * 判断状态记录是否匹配另一个仍存活的网关进程。
     *
     * @param record 记录参数。
     * @param legacy legacy 参数。
     * @param pid 系统进程标识。
     * @return 返回matches Other进程结果。
     */
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

    /**
     * 读取指定进程的启动时间。
     *
     * @param pid 系统进程标识。
     * @return 返回读取到的进程Start时间。
     */
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

    /**
     * 读取进程命令。
     *
     * @param pid 系统进程标识。
     * @return 返回读取到的进程命令。
     */
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

    /**
     * 判断是否具有消息网关命令特征。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回looks Like消息网关命令结果。
     */
    private boolean looksLikeGatewayCommand(String command) {
        String normalized =
                StrUtil.nullToEmpty(command).replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("solon-claw")
                || normalized.contains("com.jimuqu.solon.claw")
                || normalized.contains("gateway");
    }

    /**
     * 读取当前Pid。
     *
     * @return 返回读取到的当前Pid。
     */
    private long getCurrentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            String pid = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
            return Long.parseLong(pid);
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 读取当前Jvm Start时间。
     *
     * @return 返回读取到的当前Jvm Start时间。
     */
    private long getCurrentJvmStartTime() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    /**
     * 判断是否进程Alive。
     *
     * @param pid 系统进程标识。
     * @return 如果进程Alive满足条件则返回 true，否则返回 false。
     */
    private boolean isProcessAlive(long pid) {
        try {
            Process process =
                    Runtime.getRuntime().exec(new String[] {"kill", "-0", String.valueOf(pid)});
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 运行命令。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令结果。
     */
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

    /**
     * 生成安全展示用的命令名称。
     *
     * @return 返回safe命令名称结果。
     */
    private String safeCommandName() {
        String command = StrUtil.nullToEmpty(System.getProperty("sun.java.command")).trim();
        if (StrUtil.isBlank(command)) {
            return "java";
        }
        String first = command.split("\\s+")[0];
        return StrUtil.blankToDefault(new File(first).getName(), first);
    }

    /**
     * 执行as长整型相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Long结果。
     */
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

    /**
     * 生成安全展示用的文本。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Text结果。
     */
    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
