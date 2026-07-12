package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Skills Hub 内容辅助。 */
public final class SkillHubContentSupport {
    /** 创建技能中心Content辅助实例。 */
    private SkillHubContentSupport() {}

    /**
     * 对已写入磁盘的技能内容计算稳定摘要。
     *
     * <p>目录摘要必须与 {@link #bundleContentHash(SkillBundle)} 保持同一口径：按相对路径排序，依次
     * 写入路径和 UTF-8 内容。安装记录会与远端技能包直接比较，不能只摘要文件内容。</p>
     *
     * @param path 文件或目录路径。
     * @return 返回content Hash结果。
     */
    public static String contentHash(File path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (path.isDirectory()) {
            List<File> files = FileUtil.loopFiles(path);
            files.sort(
                    (left, right) ->
                            relativePath(path, left).compareTo(relativePath(path, right)));
            for (File file : files) {
                if (file.isDirectory()) {
                    continue;
                }
                digest.update(relativePath(path, file).getBytes(StandardCharsets.UTF_8));
                digest.update(FileUtil.readBytes(file));
            }
        } else if (path.isFile()) {
            digest.update(FileUtil.readBytes(path));
        }
        return "sha256:"
                + cn.hutool.core.util.HexUtil.encodeHexStr(digest.digest()).substring(0, 16);
    }

    /**
     * 将目录中的文件转换为技能包使用的斜杠相对路径。
     *
     * @param root 技能目录根路径。
     * @param file 目录中的文件。
     * @return 用于跨平台稳定摘要的相对路径。
     */
    private static String relativePath(File root, File file) {
        return root.toPath()
                .relativize(file.toPath())
                .toString()
                .replace(File.separatorChar, '/');
    }

    /**
     * 执行包Content哈希相关逻辑。
     *
     * @param bundle bundle 参数。
     * @return 返回包Content Hash结果。
     */
    public static String bundleContentHash(SkillBundle bundle) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> keys = new ArrayList<String>(bundle.getFiles().keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update(
                    StrUtil.nullToEmpty(bundle.getFiles().get(key))
                            .getBytes(StandardCharsets.UTF_8));
        }
        return "sha256:"
                + cn.hutool.core.util.HexUtil.encodeHexStr(digest.digest()).substring(0, 16);
    }

    /**
     * 写入包。
     *
     * @param targetDir 文件或目录路径参数。
     * @param bundle bundle 参数。
     */
    public static void writeBundle(File targetDir, SkillBundle bundle) {
        FileUtil.mkdir(targetDir);
        for (java.util.Map.Entry<String, String> entry : bundle.getFiles().entrySet()) {
            String safePath = SkillBundlePathSupport.normalizeBundlePath(entry.getKey());
            File target =
                    SkillBundlePathSupport.requireCanonicalUnderRoot(
                            targetDir,
                            FileUtil.file(targetDir, safePath.replace('/', File.separatorChar)),
                            "bundle path");
            FileUtil.mkParentDirs(target);
            FileUtil.writeUtf8String(StrUtil.nullToEmpty(entry.getValue()), target);
        }
    }
}
