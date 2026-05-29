package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/**
 * Gateway runtime status service.
 * Manages PID file and runtime state for detecting whether the gateway is running.
 */
public class GatewayRuntimeStatusService {
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
            FileUtil.writeString(getPid(), pidFile, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public void removePidFile() {
        try {
            if (pidFile.isFile()) {
                pidFile.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public void writeState(String status, String detail) {
        try {
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.put("pid", getPid());
            state.put("status", StrUtil.blankToDefault(status, "running"));
            state.put("startedAt", Long.valueOf(startedAt));
            state.put("updatedAt", Long.valueOf(System.currentTimeMillis()));
            state.put("uptimeMs", Long.valueOf(System.currentTimeMillis() - startedAt));
            if (StrUtil.isNotBlank(detail)) {
                state.put("detail", detail);
            }
            FileUtil.mkParentDirs(stateFile);
            FileUtil.writeString(ONode.serialize(state), stateFile, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isRunning() {
        if (!pidFile.isFile()) {
            return false;
        }
        try {
            String pidStr = FileUtil.readString(pidFile, StandardCharsets.UTF_8).trim();
            if (StrUtil.isBlank(pidStr)) {
                return false;
            }
            return isProcessAlive(pidStr);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readState() {
        try {
            if (stateFile.isFile()) {
                String json = FileUtil.readString(stateFile, StandardCharsets.UTF_8);
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
        status.put("pid", getPid());
        status.put("startedAt", Long.valueOf(startedAt));
        status.put("uptimeMs", Long.valueOf(System.currentTimeMillis() - startedAt));
        status.put("state", readState());
        return status;
    }

    private String getPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            return name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean isProcessAlive(String pid) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"kill", "-0", pid});
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
