package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 统一串行化同一 Profile 的记忆文件变更，避免审批、归档和恢复互相覆盖。 */
public class MemoryFileLock {
    /** 同一 JVM 内按锁文件路径隔离互斥对象。 */
    private static final Map<String, Object> PROCESS_LOCKS =
            new ConcurrentHashMap<String, Object>();

    /** 当前 Profile 的跨进程锁文件。 */
    private final Path lockPath;

    /**
     * 创建记忆文件锁。
     *
     * @param appConfig 当前 Profile 配置。
     */
    public MemoryFileLock(AppConfig appConfig) {
        this.lockPath =
                FileUtil.file(
                                appConfig.getRuntime().getHome(),
                                MemoryConstants.APPROVAL_LOCK_FILE_NAME)
                        .toPath()
                        .toAbsolutePath()
                        .normalize();
    }

    /**
     * 在 JVM 互斥和跨进程文件锁内执行记忆变更。
     *
     * @param action 受保护动作。
     * @param <T> 返回值类型。
     * @return 动作返回值。
     * @throws Exception 加锁或动作执行失败时抛出异常。
     */
    public <T> T withLock(Action<T> action) throws Exception {
        Object processLock =
                PROCESS_LOCKS.computeIfAbsent(lockPath.toString(), ignored -> new Object());
        synchronized (processLock) {
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel =
                            FileChannel.open(
                                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return action.run();
            }
        }
    }

    /** 可在记忆文件锁内抛出受检异常的动作。 */
    public interface Action<T> {
        /**
         * 执行受保护动作。
         *
         * @return 动作结果。
         * @throws Exception 动作失败时抛出异常。
         */
        T run() throws Exception;
    }
}
