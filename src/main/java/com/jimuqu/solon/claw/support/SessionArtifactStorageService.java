package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 提供会话Artifact Storage相关业务能力，封装调用方不需要感知的运行细节。 */
public class SessionArtifactStorageService {
    /** TRAJECTORYSUCCESS文件的统一常量值。 */
    private static final String TRAJECTORY_SUCCESS_FILE = "trajectory_samples.jsonl";

    /** TRAJECTORYFAILED文件的统一常量值。 */
    private static final String TRAJECTORY_FAILED_FILE = "failed_trajectories.jsonl";

    /** 会话轨迹产物使用UTC毫秒时间戳，保持原有 JSONL 字段格式。 */
    private static final DateTimeFormatter UTC_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 记录会话ArtifactStorage中的artifacts目录。 */
    private final File artifactsDir;

    /** 创建会话Artifact Storage服务实例。 */
    public SessionArtifactStorageService() {
        this(new File(RuntimePathConstants.WORKSPACE_HOME, RuntimePathConstants.ARTIFACTS_DIR_NAME));
    }

    /**
     * 创建会话Artifact Storage服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SessionArtifactStorageService(AppConfig appConfig) {
        this(
                new File(
                        StrUtil.blankToDefault(
                                appConfig == null || appConfig.getRuntime() == null
                                        ? null
                                        : appConfig.getRuntime().getHome(),
                                RuntimePathConstants.WORKSPACE_HOME),
                        RuntimePathConstants.ARTIFACTS_DIR_NAME));
    }

    /**
     * 创建会话Artifact Storage服务实例，并注入运行所需依赖。
     *
     * @param artifactsDir 会话产物存储目录。
     */
    public SessionArtifactStorageService(File artifactsDir) {
        this.artifactsDir =
                artifactsDir == null
                        ? new File(
                                RuntimePathConstants.WORKSPACE_HOME,
                                RuntimePathConstants.ARTIFACTS_DIR_NAME)
                        : artifactsDir;
    }

    /**
     * 追加Trajectory。
     *
     * @param trajectory trajectory 参数。
     * @param completed completed 参数。
     * @return 返回Trajectory结果。
     */
    public Map<String, Object> appendTrajectory(Map<String, Object> trajectory, boolean completed)
            throws Exception {
        Map<String, Object> entry = toTrajectoryEntry(trajectory, completed);
        File target =
                new File(
                        artifactsDir, completed ? TRAJECTORY_SUCCESS_FILE : TRAJECTORY_FAILED_FILE);
        FileUtil.mkParentDirs(target);
        String line = ONode.serialize(entry) + "\n";
        try (Writer writer =
                new OutputStreamWriter(
                        new FileOutputStream(target, true), StandardCharsets.UTF_8)) {
            writer.write(line);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "trajectory");
        result.put("format", "jsonl");
        result.put("completed", Boolean.valueOf(completed));
        result.put("file_name", target.getName());
        result.put("path", "workspace://artifacts/" + target.getName());
        result.put("bytes_appended", Integer.valueOf(line.getBytes(StandardCharsets.UTF_8).length));
        result.put("timestamp", entry.get("timestamp"));
        result.put("session_id", entry.get("session_id"));
        result.put("model", entry.get("model"));
        return result;
    }

    /**
     * 转换为Trajectory Entry。
     *
     * @param trajectory trajectory 参数。
     * @param completed completed 参数。
     * @return 返回转换后的Trajectory Entry。
     */
    private Map<String, Object> toTrajectoryEntry(
            Map<String, Object> trajectory, boolean completed) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("conversations", trajectory == null ? null : trajectory.get("conversations"));
        entry.put("timestamp", utcTimestamp());
        entry.put("model", trajectory == null ? null : trajectory.get("model"));
        entry.put("completed", Boolean.valueOf(completed));
        if (trajectory != null) {
            entry.put("session_id", trajectory.get("session_id"));
            entry.put("title", trajectory.get("title"));
            entry.put("provider", trajectory.get("provider"));
        }
        return entry;
    }

    /**
     * 执行utc时间戳相关逻辑。
     *
     * @return 返回utc时间戳结果。
     */
    private String utcTimestamp() {
        return UTC_TIMESTAMP_FORMATTER.format(Instant.now());
    }
}
