package com.jimuqu.solon.claw.support;

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
}
