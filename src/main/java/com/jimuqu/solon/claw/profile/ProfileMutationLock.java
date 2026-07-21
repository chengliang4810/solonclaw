package com.jimuqu.solon.claw.profile;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 统一串行化同一 Profile 根目录下的 Provider 与 Cron 变更，避免跨进程 TOCTOU。 */
public final class ProfileMutationLock {
    /** 同一 JVM 内按锁文件路径隔离互斥对象。 */
    private static final Map<Path, Object> PROCESS_LOCKS = new ConcurrentHashMap<Path, Object>();

    /** 根目录级跨进程锁文件名。 */
    private static final String LOCK_FILE_NAME = "profiles/.provider-cron.lock";

    /** 当前 Profile 所属的根目录锁文件。 */
    private final Path lockPath;

    /**
     * 创建根目录变更锁。
     *
     * @param appConfig 当前 Profile 配置。
     */
    public ProfileMutationLock(AppConfig appConfig) {
        this(lockPath(appConfig));
    }

    /**
     * 创建根目录变更锁。
     *
     * @param lockPath 锁文件路径。
     */
    ProfileMutationLock(Path lockPath) {
        if (lockPath == null) {
            throw new IllegalArgumentException("Profile mutation lock path is required.");
        }
        this.lockPath = lockPath.toAbsolutePath().normalize();
    }

    /**
     * 在 JVM 互斥和跨进程文件锁内执行 Profile 变更。
     *
     * @param action 受保护动作。
     * @param <T> 返回值类型。
     * @return 动作返回值。
     * @throws Exception 加锁或动作执行失败时抛出异常。
     */
    public <T> T withLock(Action<T> action) throws Exception {
        Object processLock = PROCESS_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
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

    /**
     * 返回当前锁文件路径。
     *
     * @return 锁文件绝对路径。
     */
    public Path path() {
        return lockPath;
    }

    /**
     * 解析根目录级锁文件路径。
     *
     * @param appConfig 当前 Profile 配置。
     * @return 根目录级跨进程锁路径。
     */
    public static Path lockPath(AppConfig appConfig) {
        Path home =
                FileUtil.file(appConfig.getRuntime().getHome())
                        .toPath()
                        .toAbsolutePath()
                        .normalize();
        Path parent = home.getParent();
        if (parent != null && "profiles".equals(String.valueOf(parent.getFileName()))) {
            Path root = parent.getParent();
            if (root != null) {
                return root.resolve(LOCK_FILE_NAME).toAbsolutePath().normalize();
            }
        }
        return home.resolve(LOCK_FILE_NAME).toAbsolutePath().normalize();
    }

    /**
     * 直接按默认 Profile 根目录创建统一变更锁。
     *
     * @param root 默认 Profile 根目录。
     * @return 根目录级变更锁。
     */
    static ProfileMutationLock forRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Profile root is required.");
        }
        return new ProfileMutationLock(root.toAbsolutePath().normalize().resolve(LOCK_FILE_NAME));
    }

    /** 可在 Profile 变更锁内抛出受检异常的动作。 */
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
