package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** 文件类工具共享的工作区路径边界辅助逻辑。 */
final class ToolWorkspacePathSupport {
    /** 工具类不允许创建实例。 */
    private ToolWorkspacePathSupport() {}

    /**
     * 折叠用户可见的 workspace 根目录前缀，避免在真实 workspace 下生成 workspace/workspace。
     *
     * @param rootPath 工具工作区根目录。
     * @param rawPath 用户传入路径。
     * @return 归一化后的相对路径文本。
     */
    static String normalizeWorkspacePath(Path rootPath, String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath);
        if (StrUtil.isBlank(value)) {
            return value;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            return value;
        }
        Path fileName = rootPath.getFileName();
        if (fileName == null) {
            return value;
        }
        String rootName = fileName.toString();
        if (!"workspace".equalsIgnoreCase(rootName)) {
            return value;
        }
        String prefix = rootName + "/";
        if (normalized.length() > prefix.length()
                && normalized.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return normalized.substring(prefix.length());
        }
        return value;
    }

    /**
     * 校验目标路径的最近已存在节点没有通过符号链接逃出工作区。
     *
     * @param target 已经按 rootPath 解析后的目标路径。
     * @param realRootPath 工作区真实根路径。
     */
    static void assertResolvedWithinRoot(Path target, Path realRootPath) {
        Path existing = nearestExistingPath(target);
        if (existing == null) {
            return;
        }
        Path real = safeRealPath(existing);
        if (!real.startsWith(realRootPath)) {
            throw new SecurityException("禁止通过符号链接访问沙箱外部");
        }
    }

    /**
     * 向父级查找最近的已存在路径。
     *
     * @param target 目标路径。
     * @return 最近已存在路径，找不到时返回 null。
     */
    static Path nearestExistingPath(Path target) {
        Path current = target;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 获取真实路径；失败时退回绝对归一化路径，保持错误展示稳定。
     *
     * @param path 文件或目录路径。
     * @return 真实路径或绝对归一化路径。
     */
    static Path safeRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
