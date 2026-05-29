package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/**
 * Shutdown forensics — captures context when the application receives shutdown signal.
 * Writes a durable record so "the gateway keeps dying" incidents can be diagnosed after the fact.
 */
public class ShutdownForensicsService {
    private final AppConfig appConfig;
    private volatile long startedAt;

    public ShutdownForensicsService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.startedAt = System.currentTimeMillis();
    }

    public Map<String, Object> snapshotShutdownContext(String reason) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis();
        snapshot.put("timestamp", Long.valueOf(now));
        snapshot.put("timestampIso", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date(now)));
        snapshot.put("reason", StrUtil.blankToDefault(reason, "unknown"));
        snapshot.put("uptimeMs", Long.valueOf(now - startedAt));
        snapshot.put("pid", getPid());
        snapshot.put("javaVersion", System.getProperty("java.version"));
        snapshot.put("osName", System.getProperty("os.name"));

        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<String, Object>();
        memory.put("maxMb", Long.valueOf(runtime.maxMemory() / (1024 * 1024)));
        memory.put("totalMb", Long.valueOf(runtime.totalMemory() / (1024 * 1024)));
        memory.put("freeMb", Long.valueOf(runtime.freeMemory() / (1024 * 1024)));
        memory.put("usedMb", Long.valueOf((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)));
        snapshot.put("memory", memory);

        Map<String, Object> threads = new LinkedHashMap<String, Object>();
        threads.put("active", Integer.valueOf(Thread.activeCount()));
        threads.put("currentName", Thread.currentThread().getName());
        snapshot.put("threads", threads);

        return snapshot;
    }

    public void persistShutdownRecord(String reason) {
        try {
            Map<String, Object> snapshot = snapshotShutdownContext(reason);
            File forensicsDir = new File(appConfig.getRuntime().getHome(), "forensics");
            FileUtil.mkdir(forensicsDir);
            String fileName = "shutdown-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".json";
            File file = new File(forensicsDir, fileName);
            FileUtil.writeString(ONode.serialize(snapshot), file, StandardCharsets.UTF_8);
            cleanOldRecords(forensicsDir, 20);
        } catch (Exception ignored) {
        }
    }

    public Map<String, Object> lastShutdownRecord() {
        try {
            File forensicsDir = new File(appConfig.getRuntime().getHome(), "forensics");
            if (!forensicsDir.isDirectory()) {
                return null;
            }
            File[] files = forensicsDir.listFiles();
            if (files == null || files.length == 0) {
                return null;
            }
            File latest = null;
            for (File file : files) {
                if (file.getName().startsWith("shutdown-") && file.getName().endsWith(".json")) {
                    if (latest == null || file.lastModified() > latest.lastModified()) {
                        latest = file;
                    }
                }
            }
            if (latest == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ONode.deserialize(
                    FileUtil.readString(latest, StandardCharsets.UTF_8), Object.class);
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void cleanOldRecords(File dir, int keepCount) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keepCount) {
            return;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int toDelete = files.length - keepCount;
        for (int i = 0; i < toDelete; i++) {
            files[i].delete();
        }
    }

    private String getPid() {
        try {
            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            String name = runtimeBean.getName();
            return name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
