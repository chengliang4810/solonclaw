package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.RuntimeProcessSupport;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供消息网关运行时状态相关业务能力，封装调用方不需要感知的运行细节。 */
public class GatewayRuntimeStatusService {
    /** 网关运行状态日志，避免诊断文件和进程探测失败时静默丢失上下文。 */
    private static final Logger log = LoggerFactory.getLogger(GatewayRuntimeStatusService.class);

    /** 消息网关KIND的统一常量值。 */
    private static final String GATEWAY_KIND = "solonclaw-gateway";

    /** UTF8的统一常量值。 */
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** 解析 ps lstart 输出的英文时间格式，保持原有 EEE MMM d HH:mm:ss yyyy 兼容。 */
    private static final DateTimeFormatter PROCESS_START_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);

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
        } catch (Exception e) {
            log.warn("消息网关 PID 文件写入失败，运行状态可能无法被外部进程识别: error={}", exceptionSummary(e));
        }
    }

    /** 移除Pid文件。 */
    public void removePidFile() {
        try {
            if (pidFile.isFile()) {
                Map<String, Object> record = readPidRecord();
                if (record == null || matchesCurrentProcess(record)) {
                    pidFile.delete();
                }
            }
        } catch (Exception e) {
            log.debug("消息网关 PID 文件清理失败，跳过本次清理: {}", exceptionSummary(e));
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
        } catch (Exception e) {
            log.warn("消息网关状态文件写入失败，Dashboard 状态可能短暂不准确: error={}", exceptionSummary(e));
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
        } catch (Exception e) {
            log.debug("消息网关状态文件读取失败，返回空状态: {}", exceptionSummary(e));
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
        status.put("pid", Long.valueOf(RuntimeProcessSupport.currentPidOrUnknown()));
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
        record.put("pid", Long.valueOf(RuntimeProcessSupport.currentPidOrUnknown()));
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
        } catch (Exception e) {
            log.debug("消息网关 PID 文件解析失败，忽略过期或损坏记录: {}", exceptionSummary(e));
        }
        return null;
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

        String kind = safeText(record.get("kind"));
        if (!GATEWAY_KIND.equals(kind)) {
            return false;
        }

        long currentPid = RuntimeProcessSupport.currentPidOrUnknown();
        if (pid.longValue() == currentPid) {
            return matchesCurrentProcess(record);
        }

        return matchesOtherProcess(record, pid.longValue());
    }

    /**
     * 判断状态记录是否匹配当前网关进程。
     *
     * @param record 记录参数。
     * @return 返回matches当前进程结果。
     */
    private boolean matchesCurrentProcess(Map<String, Object> record) {
        Long recordedStartTime = asLong(record.get("startTime"));
        if (recordedStartTime != null
                && recordedStartTime.longValue() != getCurrentJvmStartTime()) {
            return false;
        }
        return GATEWAY_KIND.equals(safeText(record.get("kind")));
    }

    /**
     * 判断状态记录是否匹配另一个仍存活的网关进程。
     *
     * @param record 记录参数。
     * @param pid 系统进程标识。
     * @return 返回matches Other进程结果。
     */
    private boolean matchesOtherProcess(Map<String, Object> record, long pid) {
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
            LocalDateTime dateTime =
                    LocalDateTime.parse(
                            output.trim().replaceAll("\\s+", " "), PROCESS_START_TIME_FORMATTER);
            return Long.valueOf(dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        } catch (Exception e) {
            log.debug("系统进程启动时间解析失败，跳过跨进程时间校验: {}", exceptionSummary(e));
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
            } catch (Exception e) {
                log.debug("读取 /proc cmdline 失败，回退到 ps 命令探测: {}", exceptionSummary(e));
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
        return normalized.contains("solonclaw")
                || normalized.contains("com.jimuqu.solon.claw")
                || normalized.contains("gateway");
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
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            log.debug("网关运行状态系统命令执行失败，返回空输出: {}", exceptionSummary(e));
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
        } catch (Exception e) {
            log.debug("消息网关状态长整型字段解析失败，忽略该字段: {}", exceptionSummary(e));
            return null;
        }
    }

    /**
     * 在进程探测等待被中断时恢复中断标记，避免上层调度逻辑误判线程状态。
     *
     * @param error 进程探测捕获到的异常。
     */
    private static void restoreInterruptIfNeeded(Exception error) {
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 生成运行状态异常摘要；只记录异常类型，避免状态文件中的细节被带入日志。
     *
     * @param error 运行状态读取、写入或系统探测异常。
     * @return 可写入日志的异常摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
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
