package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.io.IOException;

/** 校验运行时路径安全边界，阻止不符合约束的运行路径。 */
public class RuntimePathGuard {
    /** 记录运行时路径中的运行时主渠道。 */
    private final File runtimeHome;

    /** 记录运行时路径中的上下文目录。 */
    private final File contextDir;

    /** 记录运行时路径中的技能目录。 */
    private final File skillsDir;

    /** 记录运行时路径中的缓存目录。 */
    private final File cacheDir;

    /** 记录运行时路径中的媒体目录。 */
    private final File mediaDir;

    /** 记录运行时路径中的WebDist目录。 */
    private final File webDistDir;

    /** 记录运行时路径中的project目录。 */
    private final File projectDir;

    /**
     * 创建运行时路径保护实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public RuntimePathGuard(AppConfig appConfig) {
        this.runtimeHome = canonical(new File(appConfig.getRuntime().getHome()));
        this.contextDir = canonical(new File(appConfig.getRuntime().getContextDir()));
        this.skillsDir = canonical(new File(appConfig.getRuntime().getSkillsDir()));
        this.cacheDir = canonical(new File(appConfig.getRuntime().getCacheDir()));
        this.mediaDir = canonical(new File(cacheDir, "media"));
        this.projectDir = canonical(new File(System.getProperty("user.dir")));
        this.webDistDir = canonical(new File(projectDir, "web/dist"));
    }

    /**
     * 要求Allowed工具路径。
     *
     * @param path 文件或目录路径。
     * @return 返回Allowed工具路径。
     */
    public File requireAllowedToolPath(String path) {
        File file = canonical(resolve(path));
        requireUnderAny(file, projectDir, runtimeHome);
        return file;
    }

    /**
     * 要求Under上下文。
     *
     * @param file 文件或目录路径参数。
     * @return 返回Under上下文结果。
     */
    public File requireUnderContext(File file) {
        File canonical = canonical(file);
        requireUnder(canonical, contextDir);
        return canonical;
    }

    /**
     * 要求Under技能。
     *
     * @param file 文件或目录路径参数。
     * @return 返回Under技能结果。
     */
    public File requireUnderSkills(File file) {
        File canonical = canonical(file);
        requireUnder(canonical, skillsDir);
        return canonical;
    }

    /**
     * 要求Under缓存。
     *
     * @param file 文件或目录路径参数。
     * @return 返回Under缓存结果。
     */
    public File requireUnderCache(File file) {
        File canonical = canonical(file);
        requireUnder(canonical, cacheDir);
        return canonical;
    }

    /**
     * 要求Under媒体。
     *
     * @param file 文件或目录路径参数。
     * @return 返回Under媒体结果。
     */
    public File requireUnderMedia(File file) {
        File canonical = canonical(file);
        requireUnder(canonical, mediaDir);
        return canonical;
    }

    /**
     * 要求Under Web Dist。
     *
     * @param file 文件或目录路径参数。
     * @return 返回Under Web Dist结果。
     */
    public File requireUnderWebDist(File file) {
        File canonical = canonical(file);
        requireUnder(canonical, webDistDir);
        return canonical;
    }

    /**
     * 执行媒体目录相关逻辑。
     *
     * @return 返回媒体Dir结果。
     */
    public File mediaDir() {
        return mediaDir;
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param path 文件或目录路径。
     * @return 返回resolve结果。
     */
    private File resolve(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("Path is required");
        }
        File file = FileUtil.file(path);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(projectDir, path);
    }

    /**
     * 要求Under Any。
     *
     * @param file 文件或目录路径参数。
     * @param roots roots 参数。
     */
    private void requireUnderAny(File file, File... roots) {
        for (File root : roots) {
            if (isUnder(file, root)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Path is outside allowed roots: "
                        + safePath(file)
                        + ". Allowed roots: "
                        + allowedRoots(roots));
    }

    /**
     * 要求Under。
     *
     * @param file 文件或目录路径参数。
     * @param root root 参数。
     */
    private void requireUnder(File file, File root) {
        if (!isUnder(file, root)) {
            throw new IllegalArgumentException(
                    "Path is outside allowed root: "
                            + safePath(file)
                            + ". Allowed root: "
                            + safePath(root));
        }
    }

    /**
     * 判断是否Under。
     *
     * @param file 文件或目录路径参数。
     * @param root root 参数。
     * @return 如果Under满足条件则返回 true，否则返回 false。
     */
    private boolean isUnder(File file, File root) {
        String filePath = normalize(file);
        String rootPath = normalize(root);
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    /**
     * 执行规范化相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回规范化结果。
     */
    private String normalize(File file) {
        String path = canonical(file).getAbsolutePath();
        if (isWindows()) {
            return path.toLowerCase(java.util.Locale.ROOT);
        }
        return path;
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private boolean isWindows() {
        return File.separatorChar == '\\';
    }

    /**
     * 执行规范相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回规范结果。
     */
    private File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid path: " + safePath(file), e);
        }
    }

    /**
     * 执行allowedRoots相关逻辑。
     *
     * @param roots roots 参数。
     * @return 返回allowed Roots结果。
     */
    private String allowedRoots(File... roots) {
        StringBuilder buffer = new StringBuilder();
        for (File root : roots) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(safePath(root));
        }
        return buffer.toString();
    }

    /**
     * 生成安全展示用的路径。
     *
     * @param file 文件或目录路径参数。
     * @return 返回safe路径。
     */
    private String safePath(File file) {
        if (file == null) {
            return "";
        }
        String name = file.getName();
        if (StrUtil.isBlank(name)) {
            name = file.getPath();
        }
        // 凭据类文件名整体遮蔽，避免仅凭名称就暴露密钥文件位置。
        if (isCredentialFileName(name)) {
            return "[REDACTED_PATH]";
        }
        // 其他文件名仅遮蔽 token 形态的值，保留排障所需的文件名轮廓。
        return SecretRedactor.redactTokensOnly(name, 400);
    }

    /**
     * 判断是否凭据文件名称。
     *
     * @param name 名称参数。
     * @return 如果凭据文件名称满足条件则返回 true，否则返回 false。
     */
    private boolean isCredentialFileName(String name) {
        String lower = StrUtil.nullToEmpty(name).toLowerCase(java.util.Locale.ROOT).trim();
        // 去掉扩展名后再匹配 credentials、secrets 等敏感基名。
        int dot = lower.lastIndexOf('.');
        String base = dot > 0 ? lower.substring(0, dot) : lower;
        return "credentials".equals(base)
                || "credential".equals(base)
                || "secrets".equals(base)
                || "password".equals(base)
                || "passwd".equals(base)
                || base.startsWith("id_rsa")
                || base.startsWith("id_ed25519")
                || base.startsWith("id_dsa")
                || base.startsWith("id_ecdsa")
                || ".env".equals(lower)
                || lower.startsWith(".env.")
                || ".netrc".equals(lower)
                || ".pgpass".equals(lower)
                || "authorized_keys".equals(lower)
                || lower.contains("credential")
                || lower.contains("_secret")
                || lower.contains("-secret")
                || lower.contains("_password")
                || lower.contains("-password");
    }
}
