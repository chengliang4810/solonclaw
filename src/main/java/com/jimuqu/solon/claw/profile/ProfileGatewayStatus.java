package com.jimuqu.solon.claw.profile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提供单个 Profile 网关进程及其状态文件的只读视图。 */
public class ProfileGatewayStatus {
    /** Profile 名。 */
    private final String profile;

    /** Profile 工作区。 */
    private final Path home;

    /** 网关是否通过 PID、启动时间和命令校验。 */
    private final boolean running;

    /** 已验证网关进程 ID；停止时为空。 */
    private final Long pid;

    /** Profile 独立 Dashboard/API 监听端口。 */
    private final Integer port;

    /** 网关写入的运行状态快照。 */
    private final Map<String, Object> state;

    /** PID 记录文件。 */
    private final Path pidFile;

    /** 网关状态文件。 */
    private final Path stateFile;

    /** 后台网关合并日志文件。 */
    private final Path logFile;

    /**
     * 创建网关状态视图。
     *
     * @param profile Profile 名。
     * @param home Profile 工作区。
     * @param running 是否运行中。
     * @param pid 已验证进程 ID。
     * @param port Profile 独立监听端口。
     * @param state 状态快照。
     * @param pidFile PID 文件。
     * @param stateFile 状态文件。
     * @param logFile 日志文件。
     */
    public ProfileGatewayStatus(
            String profile,
            Path home,
            boolean running,
            Long pid,
            Integer port,
            Map<String, Object> state,
            Path pidFile,
            Path stateFile,
            Path logFile) {
        this.profile = profile;
        this.home = home;
        this.running = running;
        this.pid = pid;
        this.port = port;
        this.state =
                state == null
                        ? new LinkedHashMap<String, Object>()
                        : new LinkedHashMap<String, Object>(state);
        this.pidFile = pidFile;
        this.stateFile = stateFile;
        this.logFile = logFile;
    }

    /**
     * @return Profile 名。
     */
    public String getProfile() {
        return profile;
    }

    /**
     * @return Profile 工作区。
     */
    public Path getHome() {
        return home;
    }

    /**
     * @return 网关通过运行校验时返回 true。
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * @return 已验证网关进程 ID，停止时为空。
     */
    public Long getPid() {
        return pid;
    }

    /**
     * @return Profile 独立 Dashboard/API 监听端口。
     */
    public Integer getPort() {
        return port;
    }

    /**
     * @return 网关状态快照的防御性复制。
     */
    public Map<String, Object> getState() {
        return new LinkedHashMap<String, Object>(state);
    }

    /**
     * @return PID 记录文件。
     */
    public Path getPidFile() {
        return pidFile;
    }

    /**
     * @return 网关状态文件。
     */
    public Path getStateFile() {
        return stateFile;
    }

    /**
     * @return 后台网关合并日志文件。
     */
    public Path getLogFile() {
        return logFile;
    }

    /**
     * 转换为 Dashboard 和工具层可直接序列化的映射。
     *
     * @return 不包含凭据的状态映射。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("profile", profile);
        result.put("home", path(home));
        result.put("running", Boolean.valueOf(running));
        result.put("pid", pid);
        result.put("port", port);
        result.put("state", getState());
        result.put("pid_file", path(pidFile));
        result.put("state_file", path(stateFile));
        result.put("log_file", path(logFile));
        return result;
    }

    /** 将路径转换为稳定绝对文本。 */
    private static String path(Path value) {
        return value == null ? "" : value.toAbsolutePath().normalize().toString();
    }
}
