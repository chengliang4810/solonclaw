package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
import java.io.File;

/** Skills Hub 路径辅助。 */
public final class SkillHubPathSupport {
    /** 创建技能中心路径辅助实例。 */
    private SkillHubPathSupport() {}

    /**
     * 执行中心目录相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回中心Dir结果。
     */
    public static File hubDir(File skillsDir) {
        return FileUtil.file(skillsDir, ".hub");
    }

    /**
     * 执行quarantine目录相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回quarantine Dir结果。
     */
    public static File quarantineDir(File skillsDir) {
        return FileUtil.file(hubDir(skillsDir), "quarantine");
    }

    /**
     * 执行imported目录相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回imported Dir结果。
     */
    public static File importedDir(File skillsDir) {
        return FileUtil.file(hubDir(skillsDir), "imported");
    }

    /**
     * 执行索引缓存目录相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回index缓存Dir结果。
     */
    public static File indexCacheDir(File skillsDir) {
        return FileUtil.file(hubDir(skillsDir), "index-cache");
    }

    /**
     * 执行lock文件相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回lock文件结果。
     */
    public static File lockFile(File skillsDir) {
        return FileUtil.file(hubDir(skillsDir), "lock.json");
    }

    /**
     * 执行taps文件相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回taps文件结果。
     */
    public static File tapsFile(File skillsDir) {
        return FileUtil.file(hubDir(skillsDir), "taps.json");
    }

    /**
     * 执行审计日志相关逻辑。
     *
     * @param skillsDir 文件或目录路径参数。
     * @return 返回审计日志结果。
     */
    public static File auditLog(File skillsDir) {
        return FileUtil.file(hubDir(skillsDir), "audit.log");
    }

    /**
     * 确保中心Dirs。
     *
     * @param skillsDir 文件或目录路径参数。
     */
    public static void ensureHubDirs(File skillsDir) {
        FileUtil.mkdir(hubDir(skillsDir));
        FileUtil.mkdir(quarantineDir(skillsDir));
        FileUtil.mkdir(importedDir(skillsDir));
        FileUtil.mkdir(indexCacheDir(skillsDir));
    }
}
