package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.Locale;

/** 文件路径判断工具，集中处理跨平台目录包含关系。 */
public final class FilePathSupport {
    /** 工具类不保存状态，禁止创建实例。 */
    private FilePathSupport() {}

    /**
     * 判断文件是否位于根目录内，包含根目录自身。
     *
     * @param file 待判断文件或目录。
     * @param root 根目录。
     * @return 位于根目录内或等于根目录时返回 true。
     */
    public static boolean isUnderPath(File file, File root) {
        String rootPath = root.toPath().toAbsolutePath().normalize().toString();
        String filePath = file.toPath().toAbsolutePath().normalize().toString();
        if (File.separatorChar == '\\') {
            rootPath = rootPath.toLowerCase(Locale.ROOT);
            filePath = filePath.toLowerCase(Locale.ROOT);
        }
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    /**
     * 展开以用户主目录开头的路径；无法取得主目录时保持原值。
     *
     * @param path 待展开的路径文本。
     * @return 展开后的路径文本。
     */
    public static String expandUserHome(String path) {
        if (StrUtil.isBlank(path)) {
            return path;
        }
        String home = StrUtil.nullToEmpty(System.getProperty("user.home")).trim();
        if (StrUtil.isBlank(home)) {
            return path;
        }
        if ("~".equals(path)) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }
}
