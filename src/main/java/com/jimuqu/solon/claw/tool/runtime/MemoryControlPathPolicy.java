package com.jimuqu.solon.claw.tool.runtime;

import com.jimuqu.solon.claw.support.constants.MemoryConstants;
import java.nio.file.Path;

/** 阻止模型通用文件工具绕过长期记忆审批流程的共享路径策略。 */
final class MemoryControlPathPolicy {
    /** 工具类不允许创建实例。 */
    private MemoryControlPathPolicy() {}

    /**
     * 判断写入目标是否属于当前运行目录或其 Profile 的长期记忆控制区域。
     *
     * @param runtimeHome 当前 Profile 的运行目录。
     * @param target 已解析的写入目标。
     * @return 命中根 MEMORY.md、USER.md、审批状态或 memory 每日目录时返回 true。
     */
    static boolean isProtectedWriteTarget(Path runtimeHome, Path target) {
        if (runtimeHome == null || target == null) {
            return false;
        }
        Path home = runtimeHome.toAbsolutePath().normalize();
        Path effectiveHome = effectiveTargetPath(home);
        Path effectiveTarget = effectiveTargetPath(target);
        return isProtectedInProfileRoot(home, target)
                || isProtectedInProfileRoot(home, effectiveTarget)
                || isProtectedInProfileRoot(effectiveHome, target)
                || isProtectedInProfileRoot(effectiveHome, effectiveTarget)
                || isProtectedInParentProfileRoot(home, target)
                || isProtectedInParentProfileRoot(home, effectiveTarget)
                || isProtectedInParentProfileRoot(effectiveHome, target)
                || isProtectedInParentProfileRoot(effectiveHome, effectiveTarget);
    }

    /** 判断当前运行目录本身作为 Profile 根时的受保护区域。 */
    private static boolean isProtectedInProfileRoot(Path profileRoot, Path target) {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(profileRoot)) {
            return false;
        }
        Path relative = profileRoot.relativize(normalizedTarget);
        if (relative.getNameCount() == 0) {
            return false;
        }
        if (isProtectedProfileRelativePath(relative)) {
            return true;
        }
        return relative.getNameCount() >= 3
                && "profiles".equalsIgnoreCase(relative.getName(0).toString())
                && isProtectedProfileRelativePath(relative.subpath(2, relative.getNameCount()));
    }

    /** 判断运行目录已经位于 profiles/<name> 时，其同级 Profile 根目录下的受保护区域。 */
    private static boolean isProtectedInParentProfileRoot(Path runtimeHome, Path target) {
        Path profiles = runtimeHome.getParent();
        if (profiles == null || !"profiles".equalsIgnoreCase(profiles.getFileName().toString())) {
            return false;
        }
        Path profileRoot = profiles.getParent();
        return profileRoot != null && isProtectedInProfileRoot(profileRoot, target);
    }

    /** 判断 Profile 根目录相对路径是否命中根控制文件或每日记忆目录。 */
    private static boolean isProtectedProfileRelativePath(Path relative) {
        String first = relative.getName(0).toString();
        return MemoryConstants.DAILY_MEMORY_DIR_NAME.equalsIgnoreCase(first)
                || (relative.getNameCount() == 1
                        && (MemoryConstants.MEMORY_FILE_NAME.equalsIgnoreCase(first)
                                || MemoryConstants.USER_FILE_NAME.equalsIgnoreCase(first)
                                || MemoryConstants.APPROVAL_STATE_FILE_NAME.equalsIgnoreCase(first)
                                || MemoryConstants.APPROVAL_LOCK_FILE_NAME.equalsIgnoreCase(first)));
    }

    /** 解析最近存在节点的符号链接，覆盖经工作区内链接进入记忆目录的写入。 */
    private static Path effectiveTargetPath(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        Path existing = ToolWorkspacePathSupport.nearestExistingPath(normalized);
        if (existing == null) {
            return normalized;
        }
        return ToolWorkspacePathSupport.safeRealPath(existing)
                .resolve(existing.relativize(normalized))
                .normalize();
    }
}
