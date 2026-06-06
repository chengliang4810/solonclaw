package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** 封装原子文件Write辅助逻辑，降低主流程中的重复实现。 */
final class AtomicFileWriteSupport {
    /** 创建原子文件Write辅助实例。 */
    private AtomicFileWriteSupport() {}

    /**
     * 写入Utf8。
     *
     * @param target target 参数。
     * @param content 待处理内容。
     */
    static void writeUtf8(Path target, String content) throws IOException {
        if (target == null) {
            throw new IOException("target path is required");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        } else {
            parent = target.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                parent = new java.io.File(".").toPath().toAbsolutePath().normalize();
            }
        }
        Path temp = Files.createTempFile(parent, ".solonclaw-tmp.", ".tmp");
        boolean moved = false;
        try {
            copyExistingPermissions(target, temp);
            Files.write(temp, StrUtil.nullToEmpty(content).getBytes(StandardCharsets.UTF_8));
            moveIntoPlace(temp, target);
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 执行moveIntoPlace相关逻辑。
     *
     * @param temp temp 参数。
     * @param target target 参数。
     */
    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 复制Existing Permissions。
     *
     * @param target target 参数。
     * @param temp temp 参数。
     */
    private static void copyExistingPermissions(Path target, Path temp) {
        if (target == null || temp == null || !Files.exists(target)) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
            Files.setPosixFilePermissions(temp, permissions);
        } catch (Exception ignored) {
            // 保留此处实现约束，避免后续维护时破坏既有行为。
        }
    }
}
