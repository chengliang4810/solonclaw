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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import org.noear.snack4.ONode;

/** Jimuqu session artifact persistence under runtime/artifacts. */
public class SessionArtifactStorageService {
    private static final String TRAJECTORY_SUCCESS_FILE = "trajectory_samples.jsonl";
    private static final String TRAJECTORY_FAILED_FILE = "failed_trajectories.jsonl";

    private final File artifactsDir;

    public SessionArtifactStorageService() {
        this(new File(RuntimePathConstants.RUNTIME_HOME, RuntimePathConstants.ARTIFACTS_DIR_NAME));
    }

    public SessionArtifactStorageService(AppConfig appConfig) {
        this(
                new File(
                        StrUtil.blankToDefault(
                                appConfig == null || appConfig.getRuntime() == null
                                        ? null
                                        : appConfig.getRuntime().getHome(),
                                RuntimePathConstants.RUNTIME_HOME),
                        RuntimePathConstants.ARTIFACTS_DIR_NAME));
    }

    public SessionArtifactStorageService(File artifactsDir) {
        this.artifactsDir =
                artifactsDir == null
                        ? new File(
                                RuntimePathConstants.RUNTIME_HOME,
                                RuntimePathConstants.ARTIFACTS_DIR_NAME)
                        : artifactsDir;
    }

    public Map<String, Object> appendTrajectory(Map<String, Object> trajectory, boolean completed)
            throws Exception {
        Map<String, Object> entry = toTrajectoryEntry(trajectory, completed);
        File target =
                new File(
                        artifactsDir,
                        completed ? TRAJECTORY_SUCCESS_FILE : TRAJECTORY_FAILED_FILE);
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
        result.put("path", target.getAbsolutePath());
        result.put("bytes_appended", Integer.valueOf(line.getBytes(StandardCharsets.UTF_8).length));
        result.put("timestamp", entry.get("timestamp"));
        result.put("session_id", entry.get("session_id"));
        result.put("model", entry.get("model"));
        return result;
    }

    private Map<String, Object> toTrajectoryEntry(
            Map<String, Object> trajectory, boolean completed) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put(
                "conversations",
                trajectory == null ? null : trajectory.get("conversations"));
        entry.put("timestamp", utcTimestamp());
        entry.put(
                "model",
                trajectory == null ? null : trajectory.get("model"));
        entry.put("completed", Boolean.valueOf(completed));
        if (trajectory != null) {
            entry.put("session_id", trajectory.get("session_id"));
            entry.put("title", trajectory.get("title"));
            entry.put("provider", trajectory.get("provider"));
        }
        return entry;
    }

    private String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
