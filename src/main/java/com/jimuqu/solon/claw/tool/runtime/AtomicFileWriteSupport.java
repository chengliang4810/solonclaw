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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 封装原子文件Write辅助逻辑，降低主流程中的重复实现。 */
final class AtomicFileWriteSupport {
    /** 记录原子写入中的可降级清理异常，日志不输出目标路径或文件内容。 */
    private static final Logger log = LoggerFactory.getLogger(AtomicFileWriteSupport.class);

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
                } catch (IOException e) {
                    logRecoverableFailure("delete-temp-file", e);
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
        } catch (Exception e) {
            logRecoverableFailure("copy-existing-permissions", e);
        }
    }

    /**
     * 记录可恢复写入异常，只写阶段和异常类型，避免泄露路径或文件内容。
     *
     * @param stage 降级阶段。
     * @param error 异常对象。
     */
    private static void logRecoverableFailure(String stage, Exception error) {
        if (log.isDebugEnabled()) {
            log.debug("atomic file write fallback. stage={} error={}", stage, exceptionSummary(error));
        }
    }

    /**
     * 生成低敏异常摘要，仅保留异常类型。
     *
     * @param error 异常对象。
     * @return 返回异常类型摘要。
     */
    private static String exceptionSummary(Exception error) {
        return error == null ? "unknown" : error.getClass().getName();
    }
}
