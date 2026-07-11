package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** 承载技能目录Resolver相关状态和辅助逻辑。 */
public class SkillDirectoryResolver {
    /** 技能目录解析器的低敏日志记录器。 */
    private static final Logger LOG = Logger.getLogger(SkillDirectoryResolver.class.getName());

    /** 注入应用配置，用于技能目录Resolver。 */
    private final AppConfig appConfig;

    /** 记录技能目录Resolver中的工作区目录。 */
    private final File workspaceHome;

    /** 记录技能目录Resolver中的技能目录。 */
    private final File skillsDir;

    /** 承载外部技能目录摘要相关状态和辅助逻辑。 */
    public static final class ExternalSkillDirectorySummary {
        /** 记录外部技能目录摘要中的已配置路径。 */
        private final String configuredPath;

        /** 记录外部技能目录摘要中的目录。 */
        private final File directory;

        /** 是否启用本地。 */
        private final boolean local;

        /** 是否启用duplicate。 */
        private final boolean duplicate;

        /**
         * 创建外部技能Directory Summary实例，并注入运行所需依赖。
         *
         * @param configuredPath 文件或目录路径参数。
         * @param directory 文件或目录路径参数。
         * @param local 本地参数。
         * @param duplicate duplicate 参数。
         */
        public ExternalSkillDirectorySummary(
                String configuredPath, File directory, boolean local, boolean duplicate) {
            this.configuredPath = configuredPath;
            this.directory = directory;
            this.local = local;
            this.duplicate = duplicate;
        }

        /**
         * 读取Configured路径。
         *
         * @return 返回读取到的Configured路径。
         */
        public String getConfiguredPath() {
            return configuredPath;
        }

        /**
         * 读取Directory。
         *
         * @return 返回读取到的Directory。
         */
        public File getDirectory() {
            return directory;
        }

        /**
         * 读取Normalized路径。
         *
         * @return 返回读取到的Normalized路径。
         */
        public String getNormalizedPath() {
            return directory.getAbsolutePath();
        }

        /**
         * 判断是否本地。
         *
         * @return 如果本地满足条件则返回 true，否则返回 false。
         */
        public boolean isLocal() {
            return local;
        }

        /**
         * 判断是否Duplicate。
         *
         * @return 如果Duplicate满足条件则返回 true，否则返回 false。
         */
        public boolean isDuplicate() {
            return duplicate;
        }
    }

    /**
     * 创建技能Directory Resolver实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SkillDirectoryResolver(AppConfig appConfig) {
        this.appConfig = appConfig;
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? RuntimePathConstants.WORKSPACE_HOME
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getHome(),
                                RuntimePathConstants.WORKSPACE_HOME);
        this.workspaceHome = FileUtil.file(home).getAbsoluteFile();
        String skills =
                appConfig == null || appConfig.getRuntime() == null
                        ? new File(this.workspaceHome, "skills").getPath()
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getSkillsDir(),
                                new File(this.workspaceHome, "skills").getPath());
        this.skillsDir = FileUtil.file(skills).getAbsoluteFile();
    }

    /**
     * 执行本地技能目录相关逻辑。
     *
     * @return 返回本地技能Dir结果。
     */
    public File localSkillsDir() {
        return skillsDir;
    }

    /**
     * 执行全部技能Dirs相关逻辑。
     *
     * @return 返回全部技能Dirs结果。
     */
    public List<File> allSkillsDirs() {
        List<File> dirs = new ArrayList<File>();
        dirs.add(skillsDir);
        dirs.addAll(externalSkillsDirs());
        return dirs;
    }

    /**
     * 执行外部技能Dirs相关逻辑。
     *
     * @return 返回外部技能Dirs结果。
     */
    public List<File> externalSkillsDirs() {
        List<ExternalSkillDirectorySummary> summaries = externalSkillDirSummaries();
        if (summaries.isEmpty()) {
            return Collections.emptyList();
        }
        List<File> dirs = new ArrayList<File>();
        for (ExternalSkillDirectorySummary summary : summaries) {
            File dir = summary.getDirectory();
            if (!summary.isLocal() && !summary.isDuplicate() && dir.isDirectory()) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /**
     * 执行外部技能目录Summaries相关逻辑。
     *
     * @return 返回外部技能Dir Summaries结果。
     */
    public List<ExternalSkillDirectorySummary> externalSkillDirSummaries() {
        List<String> configured =
                appConfig == null || appConfig.getSkills() == null
                        ? Collections.<String>emptyList()
                        : appConfig.getSkills().getExternalDirs();
        if (configured == null || configured.isEmpty()) {
            return Collections.emptyList();
        }
        List<ExternalSkillDirectorySummary> summaries =
                new ArrayList<ExternalSkillDirectorySummary>();
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        File localSkills = canonicalOrAbsolute(skillsDir);
        for (String entry : configured) {
            String text = StrUtil.nullToEmpty(entry).trim();
            if (text.length() == 0) {
                continue;
            }
            File resolved = canonicalOrAbsolute(resolveExternalSkillsDir(text));
            String key = resolved.getAbsolutePath();
            boolean duplicate = seen.containsKey(key);
            boolean local = isSameFile(resolved, localSkills);
            summaries.add(new ExternalSkillDirectorySummary(text, resolved, local, duplicate));
            if (!duplicate) {
                seen.put(key, Boolean.TRUE);
            }
        }
        return summaries;
    }

    /**
     * 解析外部技能Dir。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回解析后的外部技能Dir。
     */
    private File resolveExternalSkillsDir(String rawPath) {
        String expanded = expandPathVariables(rawPath);
        File file = FileUtil.file(expanded);
        if (!file.isAbsolute()) {
            file = FileUtil.file(workspaceHome, expanded);
        }
        return file.getAbsoluteFile();
    }

    /**
     * 执行expand路径Variables相关逻辑。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回expand路径Variables结果。
     */
    private String expandPathVariables(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        if (value.equals("~")) {
            value = System.getProperty("user.home", "") + File.separator;
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", "") + value.substring(1);
        }
        return expandEnvironmentVariables(value);
    }

    /**
     * 执行expandEnvironmentVariables相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回expand Environment Variables结果。
     */
    private String expandEnvironmentVariables(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            char ch = value.charAt(i);
            if (ch == '$' && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                int end = value.indexOf('}', i + 2);
                if (end > i) {
                    String name = value.substring(i + 2, end);
                    String replacement = ProfileRuntimeScope.environmentValue(name);
                    result.append(replacement == null ? value.substring(i, end + 1) : replacement);
                    i = end + 1;
                    continue;
                }
            }
            result.append(ch);
            i++;
        }
        return result.toString();
    }

    /**
     * 执行规范OrAbsolute相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回规范Or Absolute结果。
     */
    private File canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException | SecurityException e) {
            LOG.fine(
                    "技能目录规范路径解析失败，已回退到绝对路径：file="
                            + file.getName()
                            + ", errorType="
                            + e.getClass().getSimpleName());
            return file.getAbsoluteFile();
        }
    }

    /**
     * 判断是否Same文件。
     *
     * @param left 左侧比较对象。
     * @param right 右侧比较对象。
     * @return 如果Same文件满足条件则返回 true，否则返回 false。
     */
    private boolean isSameFile(File left, File right) {
        return left != null
                && right != null
                && left.getAbsolutePath().equals(right.getAbsolutePath());
    }
}
