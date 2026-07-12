package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.noear.snack4.ONode;

/**
 * 统一维护 .curator_state 的进程内读改写，避免用量统计和整理任务互相覆盖。
 *
 * <p>状态文件同时保存整理器运行信息和单技能统计，因此所有写入必须通过同一把按规范路径分配的锁完成。
 */
public class CuratorStateStore {
    /** 按状态文件规范路径共享的进程内锁。 */
    private static final ConcurrentHashMap<String, Object> STATE_LOCKS =
            new ConcurrentHashMap<String, Object>();

    /** 状态文件路径。 */
    private final File stateFile;

    /** 对应状态文件的共享锁。 */
    private final Object stateLock;

    /**
     * 创建状态存取服务。
     *
     * @param stateFile 技能整理状态文件。
     */
    public CuratorStateStore(File stateFile) {
        if (stateFile == null) {
            throw new IllegalArgumentException("curator state file is required");
        }
        this.stateFile = stateFile;
        this.stateLock = STATE_LOCKS.computeIfAbsent(lockKey(stateFile), ignored -> new Object());
    }

    /**
     * 读取当前完整状态快照。
     *
     * @return 可供调用方安全修改的状态副本。
     */
    public Map<String, Object> read() {
        synchronized (stateLock) {
            return loadState();
        }
    }

    /**
     * 在共享锁内执行完整读改写，并通过原子替换提交状态。
     *
     * @param mutation 状态变更函数。
     * @param <T> 调用方结果类型。
     * @return 变更函数返回值。
     */
    public <T> T update(StateMutation<T> mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("curator state mutation is required");
        }
        synchronized (stateLock) {
            Map<String, Object> state = loadState();
            T result = mutation.apply(state);
            writeAtomically(state);
            return result;
        }
    }

    /** 状态变更回调，调用期间持有对应状态文件的共享锁。 */
    public interface StateMutation<T> {
        /**
         * 修改完整状态内容。
         *
         * @param state 当前状态。
         * @return 调用方需要的结果。
         */
        T apply(Map<String, Object> state);
    }

    /** 读取状态文件；损坏或空文件按空状态处理，避免阻断正常技能调用。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadState() {
        if (!stateFile.isFile()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = ONode.deserialize(FileUtil.readUtf8String(stateFile), Object.class);
            if (parsed instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
            }
        } catch (Exception ignored) {
            // 状态统计不可用时不应影响技能使用，后续写入会恢复为有效 JSON。
        }
        return new LinkedHashMap<String, Object>();
    }

    /** 将完整状态写入同目录临时文件后原子替换，避免进程中断留下半份 JSON。 */
    private void writeAtomically(Map<String, Object> state) {
        Path target = stateFile.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        try {
            if (parent == null) {
                throw new IOException("curator state parent is required");
            }
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".curator-state-", ".tmp");
            boolean moved = false;
            try {
                Files.write(
                        temp,
                        StrUtil.nullToEmpty(ONode.serialize(state))
                                .getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(
                            temp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save curator state", e);
        }
    }

    /** 生成跨服务实例稳定一致的锁键。 */
    private static String lockKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
