package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared file read/write state for stale-write warnings. */
public class SolonClawFileStateTracker {
    private static final int READ_TIMESTAMPS_CAP = 1000;

    private final Map<String, Long> readTimestamps = new LinkedHashMap<String, Long>();

    public synchronized void recordRead(Path path) {
        Long modifiedAt = modifiedAt(path);
        if (modifiedAt == null) {
            return;
        }
        readTimestamps.put(normalize(path), modifiedAt);
        capReadTimestamps();
    }

    public synchronized void recordWrite(Path path) {
        Long modifiedAt = modifiedAt(path);
        if (modifiedAt == null) {
            readTimestamps.remove(normalize(path));
            return;
        }
        readTimestamps.put(normalize(path), modifiedAt);
        capReadTimestamps();
    }

    public synchronized String checkStaleness(String displayPath, Path path) {
        String key = normalize(path);
        Long readAt = readTimestamps.get(key);
        if (readAt == null) {
            return null;
        }
        Long current = modifiedAt(path);
        if (current == null || current.longValue() == readAt.longValue()) {
            return null;
        }
        return "Warning: "
                + StrUtil.blankToDefault(displayPath, key)
                + " was modified since you last read it (external edit or concurrent agent). "
                + "The content you read may be stale. Consider re-reading the file to verify before writing.";
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private Long modifiedAt(Path path) {
        try {
            if (path == null || !Files.exists(path)) {
                return null;
            }
            return Long.valueOf(Files.getLastModifiedTime(path).toMillis());
        } catch (Exception e) {
            return null;
        }
    }

    private void capReadTimestamps() {
        while (readTimestamps.size() > READ_TIMESTAMPS_CAP) {
            readTimestamps.remove(readTimestamps.keySet().iterator().next());
        }
    }
}
