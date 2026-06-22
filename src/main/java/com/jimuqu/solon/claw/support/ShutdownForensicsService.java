package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供Shutdown Forensics相关业务能力，封装调用方不需要感知的运行细节。 */
public class ShutdownForensicsService {
    /** 记录关闭取证持久化失败等诊断日志。 */
    private static final Logger log = LoggerFactory.getLogger(ShutdownForensicsService.class);

    /** 关闭取证记录内的 ISO 时间格式，保持旧 yyyy-MM-dd'T'HH:mm:ss.SSSZ 输出。 */
    private static final DateTimeFormatter SNAPSHOT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    .withZone(ZoneId.systemDefault());

    /** 关闭取证文件名时间格式，保持 shutdown-yyyyMMdd-HHmmss.json 命名。 */
    private static final DateTimeFormatter FILE_NAME_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /** 注入应用配置，用于关闭Forensics。 */
    private final AppConfig appConfig;

    /** 记录关闭Forensics中的started时间。 */
    private volatile long startedAt;

    /**
     * 创建Shutdown Forensics服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public ShutdownForensicsService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * 执行snapshot关闭上下文相关逻辑。
     *
     * @param reason 原因参数。
     * @return 返回snapshot Shutdown上下文结果。
     */
    public Map<String, Object> snapshotShutdownContext(String reason) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis();
        snapshot.put("timestamp", Long.valueOf(now));
        snapshot.put("timestampIso", SNAPSHOT_TIME_FORMATTER.format(Instant.ofEpochMilli(now)));
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
        memory.put(
                "usedMb",
                Long.valueOf((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)));
        snapshot.put("memory", memory);

        Map<String, Object> threads = new LinkedHashMap<String, Object>();
        threads.put("active", Integer.valueOf(Thread.activeCount()));
        threads.put("currentName", Thread.currentThread().getName());
        snapshot.put("threads", threads);

        return snapshot;
    }

    /**
     * 执行persist关闭记录相关逻辑。
     *
     * @param reason 原因参数。
     */
    public void persistShutdownRecord(String reason) {
        try {
            Map<String, Object> snapshot = snapshotShutdownContext(reason);
            File forensicsDir = new File(appConfig.getRuntime().getHome(), "forensics");
            FileUtil.mkdir(forensicsDir);
            String fileName =
                    "shutdown-" + FILE_NAME_TIME_FORMATTER.format(Instant.now()) + ".json";
            File file = new File(forensicsDir, fileName);
            FileUtil.writeString(ONode.serialize(snapshot), file, StandardCharsets.UTF_8);
            cleanOldRecords(forensicsDir, 20);
        } catch (Exception e) {
            log.debug("Failed to persist shutdown forensics record: {}", e.toString());
        }
    }

    /** 执行persist生命周期关闭记录相关逻辑。 */
    public void persistLifecycleShutdownRecord() {
        persistShutdownRecord("lifecycle_shutdown");
    }

    /**
     * 执行last关闭记录相关逻辑。
     *
     * @return 返回last Shutdown记录结果。
     */
    public Map<String, Object> lastShutdownRecord() {
        try {
            File latest = lastShutdownRecordFile();
            if (latest == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data =
                    (Map<String, Object>)
                            ONode.deserialize(
                                    FileUtil.readString(latest, StandardCharsets.UTF_8),
                                    Object.class);
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行last关闭记录文件相关逻辑。
     *
     * @return 返回last Shutdown记录文件结果。
     */
    public File lastShutdownRecordFile() {
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
            return latest;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清理OldRecords。
     *
     * @param dir 文件或目录路径参数。
     * @param keepCount keepCount 参数。
     */
    private void cleanOldRecords(File dir, int keepCount) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keepCount) {
            return;
        }
        java.util.Arrays.sort(
                files,
                new java.util.Comparator<File>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param a a 参数。
                     * @param b b 参数。
                     * @return 返回compare结果。
                     */
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

    /**
     * 读取Pid。
     *
     * @return 返回读取到的Pid。
     */
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
