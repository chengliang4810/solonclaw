package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.CheckpointRecord;
import com.jimuqu.solon.claw.core.service.CheckpointService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.constants.CheckpointConstants;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;

/** 默认文件快照服务。 */
@RequiredArgsConstructor
public class DefaultCheckpointService implements CheckpointService {
    /** 应用配置。 */
    private final AppConfig appConfig;

    /** 数据库访问对象。 */
    private final SqliteDatabase database;

    /**
     * 创建检查点。
     *
     * @param sourceKey 渠道来源键。
     * @param sessionId 当前会话标识。
     * @param files 文件或目录路径参数。
     * @return 返回创建好的检查点。
     */
    @Override
    public CheckpointRecord createCheckpoint(String sourceKey, String sessionId, List<File> files)
            throws Exception {
        if (!appConfig.getRollback().isEnabled()) {
            return null;
        }

        String checkpointId = IdSupport.newId();
        File rootDir =
                FileUtil.file(
                        appConfig.getRuntime().getCacheDir(),
                        CheckpointConstants.CHECKPOINT_DIR_NAME,
                        checkpointId);
        FileUtil.mkdir(rootDir);

        ONode manifest = new ONode().asObject();
        manifest.set("checkpointId", checkpointId);
        manifest.set("sourceKey", sourceKey);
        manifest.set("sessionId", sessionId);
        manifest.getOrNew("files").asArray();
        manifest.getOrNew("skipped").asArray();

        int index = 0;
        for (File file : files) {
            if (file == null) {
                continue;
            }
            File canonical = file.getCanonicalFile();
            String skipReason = skipReason(canonical);
            if (skipReason != null) {
                ONode skipped = new ONode().asObject();
                skipped.set("path", canonical.getAbsolutePath());
                skipped.set("reason", skipReason);
                skipped.set("exists", canonical.exists());
                if (canonical.exists() && canonical.isFile()) {
                    skipped.set("sizeBytes", canonical.length());
                }
                manifest.get("skipped").add(skipped);
                continue;
            }
            ONode item = new ONode().asObject();
            item.set("path", canonical.getAbsolutePath());
            item.set("exists", canonical.exists());
            if (canonical.exists()) {
                File snapshotFile = FileUtil.file(rootDir, "file-" + index + ".bak");
                FileUtil.copy(canonical, snapshotFile, true);
                item.set("snapshot", snapshotFile.getAbsolutePath());
                item.set("sizeBytes", canonical.length());
            }
            manifest.get("files").add(item);
            index++;
        }

        File manifestFile = FileUtil.file(rootDir, CheckpointConstants.MANIFEST_FILE_NAME);
        FileUtil.writeUtf8String(manifest.toJson(), manifestFile);

        CheckpointRecord record = new CheckpointRecord();
        record.setCheckpointId(checkpointId);
        record.setSourceKey(sourceKey);
        record.setSessionId(sessionId);
        record.setCheckpointDir(rootDir.getAbsolutePath());
        record.setManifestPath(manifestFile.getAbsolutePath());
        record.setCreatedAt(System.currentTimeMillis());
        saveRecord(record);
        pruneOldRecords(sourceKey);
        return record;
    }

    /**
     * 执行回滚Latest相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回回滚Latest结果。
     */
    @Override
    public CheckpointRecord rollbackLatest(String sourceKey) throws Exception {
        CheckpointRecord latest = findLatest(sourceKey);
        if (latest == null) {
            throw new IllegalStateException("当前来源键没有可回滚的 checkpoint。");
        }
        return rollback(latest.getCheckpointId());
    }

    /**
     * 执行回滚相关逻辑。
     *
     * @param checkpointId checkpoint标识。
     * @return 返回回滚结果。
     */
    @Override
    public CheckpointRecord rollback(String checkpointId) throws Exception {
        CheckpointRecord record = findById(checkpointId);
        if (record == null) {
            throw new IllegalStateException("未找到 checkpoint：" + safeIdentifier(checkpointId));
        }

        ONode manifest =
                ONode.ofJson(FileUtil.readUtf8String(FileUtil.file(record.getManifestPath())));
        ONode filesNode = manifest.get("files");
        for (int i = 0; i < filesNode.size(); i++) {
            ONode item = filesNode.get(i);
            File target = requireSafeRollbackTarget(item.get("path").getString());
            boolean existed = item.get("exists").getBoolean();
            if (!existed) {
                if (target.exists()) {
                    FileUtil.del(target);
                }
                continue;
            }

            File snapshot = requireSafeSnapshot(record, item.get("snapshot").getString());
            FileUtil.mkParentDirs(target);
            FileUtil.copy(snapshot, target, true);
        }

        record.setRestoredAt(System.currentTimeMillis());
        updateRestoredAt(record);
        return record;
    }

    /**
     * 要求Safe回滚Target。
     *
     * @param path 文件或目录路径。
     * @return 返回Safe回滚Target结果。
     */
    private File requireSafeRollbackTarget(String path) throws Exception {
        File target = FileUtil.file(path).getCanonicalFile();
        File project = new File(System.getProperty("user.dir")).getCanonicalFile();
        File runtime = new File(appConfig.getRuntime().getHome()).getCanonicalFile();
        if (isUnder(target, project) || isUnder(target, runtime)) {
            return target;
        }
        throw new IllegalArgumentException(
                "Checkpoint target is outside allowed roots: " + safePath(path));
    }

    /**
     * 要求Safe Snapshot。
     *
     * @param record 记录参数。
     * @param path 文件或目录路径。
     * @return 返回Safe Snapshot结果。
     */
    private File requireSafeSnapshot(CheckpointRecord record, String path) throws Exception {
        File snapshot = FileUtil.file(path).getCanonicalFile();
        File checkpointDir = FileUtil.file(record.getCheckpointDir()).getCanonicalFile();
        if (!isUnder(snapshot, checkpointDir)) {
            throw new IllegalArgumentException(
                    "Checkpoint snapshot is outside checkpoint directory: " + safePath(path));
        }
        return snapshot;
    }

    /**
     * 生成安全展示用的路径。
     *
     * @param path 文件或目录路径。
     * @return 返回safe路径。
     */
    private String safePath(String path) {
        String value = path == null ? "" : SecretRedactor.stripDisplayControls(path).trim();
        if (value.length() == 0) {
            return "[unknown]";
        }
        File file = FileUtil.file(value);
        String name = file.getName();
        if (name == null || name.trim().length() == 0) {
            name = value;
        }
        return SecretRedactor.redact(name, 400);
    }

    /**
     * 判断是否Under。
     *
     * @param file 文件或目录路径参数。
     * @param root root 参数。
     * @return 如果Under满足条件则返回 true，否则返回 false。
     */
    private boolean isUnder(File file, File root) {
        String filePath = file.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    /**
     * 判断是否存在Recent检查点。
     *
     * @param sourceKey 渠道来源键。
     * @param sinceEpochMillis sinceEpochMillis 参数。
     * @return 如果Recent检查点满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean hasRecentCheckpoint(String sourceKey, long sinceEpochMillis) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select count(*) from checkpoints where source_key = ? and created_at >= ?");
            statement.setString(1, sourceKey);
            statement.setLong(2, sinceEpochMillis);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() && resultSet.getInt(1) > 0;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 列出Recent。
     *
     * @param sourceKey 渠道来源键。
     * @param limit 最大返回数量。
     * @return 返回Recent列表。
     */
    @Override
    public List<CheckpointRecord> listRecent(String sourceKey, int limit) throws Exception {
        List<CheckpointRecord> results = new ArrayList<CheckpointRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at from checkpoints where source_key = ? order by created_at desc limit ?");
            statement.setString(1, sourceKey);
            statement.setInt(2, Math.max(1, limit));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    results.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /**
     * 执行预览相关逻辑。
     *
     * @param checkpointId checkpoint标识。
     * @return 返回preview结果。
     */
    @Override
    public Map<String, Object> preview(String checkpointId) throws Exception {
        CheckpointRecord record = findById(checkpointId);
        if (record == null) {
            throw new IllegalStateException("未找到 checkpoint：" + safeIdentifier(checkpointId));
        }

        ONode manifest =
                ONode.ofJson(FileUtil.readUtf8String(FileUtil.file(record.getManifestPath())));
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        ONode filesNode = manifest.get("files");
        for (int i = 0; i < filesNode.size(); i++) {
            ONode item = filesNode.get(i);
            Map<String, Object> file = new LinkedHashMap<String, Object>();
            file.put("path", fileReference(item.get("path").getString()));
            file.put("exists", item.get("exists").getBoolean());
            file.put("snapshot", snapshotReference(record, item.get("snapshot").getString()));
            file.put("size_bytes", item.get("sizeBytes").getLong());
            files.add(file);
        }
        List<Map<String, Object>> skipped = new ArrayList<Map<String, Object>>();
        ONode skippedNode = manifest.get("skipped");
        if (skippedNode != null && skippedNode.isArray()) {
            for (int i = 0; i < skippedNode.size(); i++) {
                ONode item = skippedNode.get(i);
                Map<String, Object> skippedFile = new LinkedHashMap<String, Object>();
                skippedFile.put("path", fileReference(item.get("path").getString()));
                skippedFile.put("reason", item.get("reason").getString());
                skippedFile.put("exists", item.get("exists").getBoolean());
                skippedFile.put("size_bytes", item.get("sizeBytes").getLong());
                skipped.add(skippedFile);
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("checkpoint_id", record.getCheckpointId());
        result.put("source_key", safeIdentifier(record.getSourceKey()));
        result.put("session_id", safeIdentifier(record.getSessionId()));
        result.put("created_at", record.getCreatedAt());
        result.put("restored_at", record.getRestoredAt());
        result.put("files", files);
        result.put("skipped", skipped);
        return result;
    }

    /**
     * 执行文件引用相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回文件Reference结果。
     */
    private String fileReference(String path) {
        return "file://" + safePath(path);
    }

    /**
     * 生成安全展示用的Identifier。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe Identifier结果。
     */
    private String safeIdentifier(String value) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(value), 400);
    }

    /**
     * 执行snapshot引用相关逻辑。
     *
     * @param record 记录参数。
     * @param path 文件或目录路径。
     * @return 返回snapshot Reference结果。
     */
    private String snapshotReference(CheckpointRecord record, String path) {
        if (path == null || path.trim().length() == 0) {
            return "";
        }
        return "checkpoint://"
                + SecretRedactor.redact(record.getCheckpointId(), 200)
                + "/snapshots/"
                + safePath(path);
    }

    /**
     * 执行状态相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回状态。
     */
    @Override
    public Map<String, Object> status(String sourceKey) throws Exception {
        List<CheckpointRecord> all = listAll(sourceKey);
        long totalBytes = 0L;
        int missingDirs = 0;
        long latestCreatedAt = 0L;
        for (CheckpointRecord record : all) {
            File dir = FileUtil.file(record.getCheckpointDir());
            if (dir.exists()) {
                totalBytes += FileUtil.size(dir);
            } else {
                missingDirs++;
            }
            latestCreatedAt = Math.max(latestCreatedAt, record.getCreatedAt());
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("source_key", sourceKey);
        result.put("checkpoint_count", Integer.valueOf(all.size()));
        result.put("missing_dirs", Integer.valueOf(missingDirs));
        result.put("total_size_bytes", Long.valueOf(totalBytes));
        result.put("latest_created_at", Long.valueOf(latestCreatedAt));
        result.put(
                "max_checkpoints_per_source",
                Integer.valueOf(appConfig.getRollback().getMaxCheckpointsPerSource()));
        result.put("max_file_size_mb", Integer.valueOf(appConfig.getRollback().getMaxFileSizeMb()));
        result.put("enabled", Boolean.valueOf(appConfig.getRollback().isEnabled()));
        return result;
    }

    /**
     * 执行prune相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回prune结果。
     */
    @Override
    public Map<String, Object> prune(String sourceKey) throws Exception {
        List<CheckpointRecord> all = listAll(sourceKey);
        int deletedMissing = 0;
        int deletedOverflow = 0;
        long bytesFreed = 0L;
        int max = Math.max(0, appConfig.getRollback().getMaxCheckpointsPerSource());
        int liveSeen = 0;
        for (CheckpointRecord record : all) {
            File dir = FileUtil.file(record.getCheckpointDir());
            boolean missing = !dir.exists();
            boolean overflow = !missing && liveSeen >= max;
            if (missing || overflow) {
                bytesFreed += dir.exists() ? FileUtil.size(dir) : 0L;
                deleteRecord(record.getCheckpointId());
                FileUtil.del(dir);
                if (missing) {
                    deletedMissing++;
                } else {
                    deletedOverflow++;
                }
                continue;
            }
            liveSeen++;
        }

        Map<String, Object> result = status(sourceKey);
        result.put("deleted_missing", Integer.valueOf(deletedMissing));
        result.put("deleted_overflow", Integer.valueOf(deletedOverflow));
        result.put("bytes_freed", Long.valueOf(bytesFreed));
        return result;
    }

    /**
     * 执行clear相关逻辑。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回clear结果。
     */
    @Override
    public Map<String, Object> clear(String sourceKey) throws Exception {
        List<CheckpointRecord> all = listAll(sourceKey);
        int deleted = 0;
        long bytesFreed = 0L;
        for (CheckpointRecord record : all) {
            File dir = FileUtil.file(record.getCheckpointDir());
            bytesFreed += dir.exists() ? FileUtil.size(dir) : 0L;
            deleteRecord(record.getCheckpointId());
            FileUtil.del(dir);
            deleted++;
        }
        Map<String, Object> result = status(sourceKey);
        result.put("deleted", Integer.valueOf(deleted));
        result.put("bytes_freed", Long.valueOf(bytesFreed));
        return result;
    }

    /**
     * 执行skip原因相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回skip Reason结果。
     */
    private String skipReason(File file) throws Exception {
        String matchedPattern = matchedExcludePattern(file);
        if (matchedPattern != null) {
            return "excluded:" + matchedPattern;
        }
        if (file.exists() && file.isFile()) {
            int maxMb = appConfig.getRollback().getMaxFileSizeMb();
            long maxBytes = Math.max(1L, maxMb) * 1024L * 1024L;
            if (file.length() > maxBytes) {
                return "too_large:" + maxMb + "mb";
            }
        }
        return null;
    }

    /**
     * 执行matchedExcludePattern相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回matched Exclude Pattern结果。
     */
    private String matchedExcludePattern(File file) throws Exception {
        List<String> patterns = appConfig.getRollback().getExcludePatterns();
        if (patterns == null || patterns.isEmpty()) {
            return null;
        }
        String absolute = normalizePath(file.getCanonicalPath());
        String userDir = normalizePath(new File(System.getProperty("user.dir")).getCanonicalPath());
        String runtime =
                normalizePath(new File(appConfig.getRuntime().getHome()).getCanonicalPath());
        String projectRelative = relativize(userDir, absolute);
        String runtimeRelative = relativize(runtime, absolute);
        String name = file.getName();
        for (String raw : patterns) {
            String pattern = raw == null ? "" : raw.trim();
            if (pattern.length() == 0) {
                continue;
            }
            String normalized = normalizePath(pattern);
            if (matchesPattern(normalized, name)
                    || matchesPathPattern(normalized, projectRelative)
                    || matchesPathPattern(normalized, runtimeRelative)
                    || matchesPathPattern(normalized, absolute)) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * 判断是否匹配路径Pattern。
     *
     * @param pattern pattern 参数。
     * @param path 文件或目录路径。
     * @return 返回matches路径Pattern结果。
     */
    private boolean matchesPathPattern(String pattern, String path) {
        if (path == null || path.length() == 0) {
            return false;
        }
        if (pattern.endsWith("/")) {
            String dir = pattern.substring(0, pattern.length() - 1);
            return path.equals(dir) || path.startsWith(dir + "/") || path.contains("/" + dir + "/");
        }
        if (matchesPattern(pattern, path)) {
            return true;
        }
        return path.endsWith("/" + pattern);
    }

    /**
     * 判断是否匹配Pattern。
     *
     * @param pattern pattern 参数。
     * @param value 待规范化或校验的原始值。
     * @return 返回matches Pattern结果。
     */
    private boolean matchesPattern(String pattern, String value) {
        String regex = globToRegex(pattern);
        return value.toLowerCase(Locale.ROOT).matches(regex);
    }

    /**
     * 执行globToRegex相关逻辑。
     *
     * @param pattern pattern 参数。
     * @return 返回glob To Regex结果。
     */
    private String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        regex.append("(?i)^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        regex.append('$');
        return regex.toString();
    }

    /**
     * 规范化路径。
     *
     * @param path 文件或目录路径。
     * @return 返回路径。
     */
    private String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    /**
     * 执行relativize相关逻辑。
     *
     * @param root root 参数。
     * @param path 文件或目录路径。
     * @return 返回relativize结果。
     */
    private String relativize(String root, String path) {
        if (root == null || root.length() == 0 || path == null || path.length() == 0) {
            return null;
        }
        if (path.equals(root)) {
            return "";
        }
        return path.startsWith(root + "/") ? path.substring(root.length() + 1) : null;
    }

    /** 保存 checkpoint 记录。 */
    private void saveRecord(CheckpointRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into checkpoints (checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, record.getCheckpointId());
            statement.setString(2, record.getSourceKey());
            statement.setString(3, record.getSessionId());
            statement.setString(4, record.getCheckpointDir());
            statement.setString(5, record.getManifestPath());
            statement.setLong(6, record.getCreatedAt());
            statement.setLong(7, record.getRestoredAt());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** 更新恢复时间。 */
    private void updateRestoredAt(CheckpointRecord record) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update checkpoints set restored_at = ? where checkpoint_id = ?");
            statement.setLong(1, record.getRestoredAt());
            statement.setString(2, record.getCheckpointId());
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** 查询最新 checkpoint。 */
    private CheckpointRecord findLatest(String sourceKey) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at from checkpoints where source_key = ? order by created_at desc limit 1");
            statement.setString(1, sourceKey);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? map(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /** 通过 id 查询 checkpoint。 */
    private CheckpointRecord findById(String checkpointId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at from checkpoints where checkpoint_id = ?");
            statement.setString(1, checkpointId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? map(resultSet) : null;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /** 清理超额 checkpoint。 */
    private void pruneOldRecords(String sourceKey) throws Exception {
        int max = appConfig.getRollback().getMaxCheckpointsPerSource();
        if (max <= 0) {
            prune(sourceKey);
            return;
        }
        List<CheckpointRecord> records = listAll(sourceKey);
        for (int i = max; i < records.size(); i++) {
            CheckpointRecord record = records.get(i);
            deleteRecord(record.getCheckpointId());
            FileUtil.del(record.getCheckpointDir());
        }
    }

    /**
     * 列出全部。
     *
     * @param sourceKey 渠道来源键。
     * @return 返回全部列表。
     */
    private List<CheckpointRecord> listAll(String sourceKey) throws Exception {
        List<CheckpointRecord> results = new ArrayList<CheckpointRecord>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select checkpoint_id, source_key, session_id, checkpoint_dir, manifest_path, created_at, restored_at from checkpoints where source_key = ? order by created_at desc");
            statement.setString(1, sourceKey);
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    results.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return results;
    }

    /** 删除 checkpoint 记录。 */
    private void deleteRecord(String checkpointId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("delete from checkpoints where checkpoint_id = ?");
            statement.setString(1, checkpointId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    /** 结果集映射。 */
    private CheckpointRecord map(ResultSet resultSet) throws Exception {
        CheckpointRecord record = new CheckpointRecord();
        record.setCheckpointId(resultSet.getString("checkpoint_id"));
        record.setSourceKey(resultSet.getString("source_key"));
        record.setSessionId(resultSet.getString("session_id"));
        record.setCheckpointDir(resultSet.getString("checkpoint_dir"));
        record.setManifestPath(resultSet.getString("manifest_path"));
        record.setCreatedAt(resultSet.getLong("created_at"));
        record.setRestoredAt(resultSet.getLong("restored_at"));
        return record;
    }
}
