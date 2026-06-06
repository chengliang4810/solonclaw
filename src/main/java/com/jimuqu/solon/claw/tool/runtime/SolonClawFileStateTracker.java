package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 承载Solon项目文件状态Tracker相关状态和辅助逻辑。 */
public class SolonClawFileStateTracker {
    /** READTIMESTAMPSCAP的统一常量值。 */
    private static final int READ_TIMESTAMPS_CAP = 1000;

    /** 保存readTimestamps映射，便于按键快速查询。 */
    private final Map<String, Long> readTimestamps = new LinkedHashMap<String, Long>();

    /**
     * 记录Read。
     *
     * @param path 文件或目录路径。
     */
    public synchronized void recordRead(Path path) {
        Long modifiedAt = modifiedAt(path);
        if (modifiedAt == null) {
            return;
        }
        readTimestamps.put(normalize(path), modifiedAt);
        capReadTimestamps();
    }

    /**
     * 记录Write。
     *
     * @param path 文件或目录路径。
     */
    public synchronized void recordWrite(Path path) {
        Long modifiedAt = modifiedAt(path);
        if (modifiedAt == null) {
            readTimestamps.remove(normalize(path));
            return;
        }
        readTimestamps.put(normalize(path), modifiedAt);
        capReadTimestamps();
    }

    /**
     * 检查Staleness。
     *
     * @param displayPath 文件或目录路径参数。
     * @param path 文件或目录路径。
     * @return 返回Staleness结果。
     */
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

    /**
     * 执行规范化相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回规范化结果。
     */
    private String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * 执行modified时间相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回modified时间结果。
     */
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

    /** 执行capReadTimestamps相关逻辑。 */
    private void capReadTimestamps() {
        while (readTimestamps.size() > READ_TIMESTAMPS_CAP) {
            readTimestamps.remove(readTimestamps.keySet().iterator().next());
        }
    }
}
