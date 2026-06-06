package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.ContextFileConstants;
import com.jimuqu.solon.claw.support.constants.SkillConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 封装技能忽略辅助逻辑，降低主流程中的重复实现。 */
public final class SkillIgnoreSupport {
    /** LOWERWindowsDRIVE正则的统一常量值。 */
    private static final String LOWER_WINDOWS_DRIVE_PATTERN = "^[a-z]:/.*";

    /** 创建技能忽略辅助实例。 */
    private SkillIgnoreSupport() {}

    /**
     * 执行includedFiles相关逻辑。
     *
     * @param skillDir 文件或目录路径参数。
     * @return 返回included Files结果。
     */
    public static List<File> includedFiles(File skillDir) {
        List<File> result = new ArrayList<File>();
        if (skillDir == null || !skillDir.exists()) {
            return result;
        }
        SkillIgnoreMatcher matcher = load(skillDir);
        for (File file : FileUtil.loopFiles(skillDir)) {
            if (file.isDirectory() || matcher.isIgnored(file)) {
                continue;
            }
            result.add(file);
        }
        Collections.sort(
                result,
                new Comparator<File>() {
                    /**
                     * 比较两个对象的排序位置。
                     *
                     * @param left 左侧比较对象。
                     * @param right 右侧比较对象。
                     * @return 返回compare结果。
                     */
                    @Override
                    public int compare(File left, File right) {
                        return relativePath(skillDir, left)
                                .compareTo(relativePath(skillDir, right));
                    }
                });
        return result;
    }

    /**
     * 判断是否Ignored。
     *
     * @param skillDir 文件或目录路径参数。
     * @param file 文件或目录路径参数。
     * @return 如果Ignored满足条件则返回 true，否则返回 false。
     */
    public static boolean isIgnored(File skillDir, File file) {
        return load(skillDir).isIgnored(file);
    }

    /**
     * 执行relative路径相关逻辑。
     *
     * @param root root 参数。
     * @param file 文件或目录路径参数。
     * @return 返回relative路径。
     */
    public static String relativePath(File root, File file) {
        String relative = relativePathOrNull(root, file);
        return relative == null
                ? StrUtil.nullToEmpty(file == null ? null : file.getName())
                : relative;
    }

    /**
     * 执行load相关逻辑。
     *
     * @param skillDir 文件或目录路径参数。
     * @return 返回load结果。
     */
    private static SkillIgnoreMatcher load(File skillDir) {
        List<Rule> rules = new ArrayList<Rule>();
        File ignoreFile = FileUtil.file(skillDir, SkillConstants.IGNORE_FILE_NAME);
        if (!ignoreFile.isFile() || relativePathOrNull(skillDir, ignoreFile) == null) {
            return new SkillIgnoreMatcher(skillDir, rules);
        }
        List<String> lines = FileUtil.readLines(ignoreFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            Rule rule = Rule.parse(line);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return new SkillIgnoreMatcher(skillDir, rules);
    }

    /**
     * 判断是否Main Manifest。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 如果Main Manifest满足条件则返回 true，否则返回 false。
     */
    private static boolean isMainManifest(String relativePath) {
        String normalized = normalizePath(relativePath);
        return SkillConstants.SKILL_FILE_NAME.equalsIgnoreCase(normalized)
                || "skill.md".equalsIgnoreCase(normalized)
                || ContextFileConstants.FILE_AGENTS.equalsIgnoreCase(normalized);
    }

    /**
     * 判断是否忽略文件。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 如果忽略文件满足条件则返回 true，否则返回 false。
     */
    private static boolean isIgnoreFile(String relativePath) {
        return SkillConstants.IGNORE_FILE_NAME.equals(normalizePath(relativePath));
    }

    /**
     * 执行relative路径Or空值相关逻辑。
     *
     * @param root root 参数。
     * @param file 文件或目录路径参数。
     * @return 返回relative路径Or Null结果。
     */
    private static String relativePathOrNull(File root, File file) {
        if (root == null || file == null) {
            return null;
        }
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            String rootPath = canonicalRoot.getAbsolutePath();
            String filePath = canonicalFile.getAbsolutePath();
            if (filePath.equals(rootPath)) {
                return "";
            }
            String rootPrefix =
                    rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            if (!filePath.startsWith(rootPrefix)) {
                return null;
            }
            return filePath.substring(rootPrefix.length()).replace(File.separatorChar, '/');
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 规范化路径。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回路径。
     */
    private static String normalizePath(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    /**
     * 判断是否包含控制Character。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Control Character结果。
     */
    private static boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否存在Traversal。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Traversal满足条件则返回 true，否则返回 false。
     */
    private static boolean hasTraversal(String value) {
        String normalized = normalizePath(value);
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    /**
     * 判断是否Absolute Rule。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Absolute Rule满足条件则返回 true，否则返回 false。
     */
    private static boolean isAbsoluteRule(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().replace('\\', '/');
        return new File(normalized).isAbsolute()
                || normalized.startsWith("/")
                || normalized.toLowerCase(Locale.ROOT).matches(LOWER_WINDOWS_DRIVE_PATTERN);
    }

    /** 承载技能忽略Matcher相关状态和辅助逻辑。 */
    private static class SkillIgnoreMatcher {
        /** 记录技能忽略Matcher中的技能目录。 */
        private final File skillDir;

        /** 保存rules集合，维持调用顺序或去重语义。 */
        private final List<Rule> rules;

        /**
         * 创建技能忽略Matcher实例，并注入运行所需依赖。
         *
         * @param skillDir 文件或目录路径参数。
         * @param rules rules 参数。
         */
        private SkillIgnoreMatcher(File skillDir, List<Rule> rules) {
            this.skillDir = skillDir;
            this.rules = rules;
        }

        /**
         * 判断是否Ignored。
         *
         * @param file 文件或目录路径参数。
         * @return 如果Ignored满足条件则返回 true，否则返回 false。
         */
        private boolean isIgnored(File file) {
            String relativePath = relativePathOrNull(skillDir, file);
            if (relativePath == null) {
                return true;
            }
            String normalized = normalizePath(relativePath);
            if (isMainManifest(normalized)) {
                return false;
            }
            if (isIgnoreFile(normalized)) {
                return true;
            }
            for (Rule rule : rules) {
                if (rule.matches(normalized)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 承载Rule相关状态和辅助逻辑。 */
    private static class Rule {
        /** 记录Rule中的pattern。 */
        private final String pattern;

        /** 是否启用目录Only。 */
        private final boolean directoryOnly;

        /**
         * 创建Rule实例，并注入运行所需依赖。
         *
         * @param pattern pattern 参数。
         * @param directoryOnly 文件或目录路径参数。
         */
        private Rule(String pattern, boolean directoryOnly) {
            this.pattern = pattern;
            this.directoryOnly = directoryOnly;
        }

        /**
         * 执行解析相关逻辑。
         *
         * @param rawLine 原始行参数。
         * @return 返回parse结果。
         */
        private static Rule parse(String rawLine) {
            String line = StrUtil.nullToEmpty(rawLine).trim();
            if (line.length() == 0 || line.startsWith("#")) {
                return null;
            }
            boolean directoryOnly = line.replace('\\', '/').endsWith("/");
            String pattern = normalizePath(line);
            while (pattern.endsWith("/") && pattern.length() > 0) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.length() == 0
                    || containsControlCharacter(pattern)
                    || isAbsoluteRule(line)
                    || hasTraversal(pattern)) {
                return null;
            }
            return new Rule(pattern, directoryOnly);
        }

        /**
         * 执行matches相关逻辑。
         *
         * @param relativePath 文件或目录路径参数。
         * @return 返回matches结果。
         */
        private boolean matches(String relativePath) {
            if (directoryOnly) {
                return matchesDirectory(relativePath);
            }
            if (pattern.indexOf('/') >= 0) {
                return relativePath.equals(pattern) || relativePath.startsWith(pattern + "/");
            }
            return matchesPathComponent(relativePath, pattern);
        }

        /**
         * 判断是否匹配目录。
         *
         * @param relativePath 文件或目录路径参数。
         * @return 返回matches Directory结果。
         */
        private boolean matchesDirectory(String relativePath) {
            if (pattern.indexOf('/') >= 0) {
                return relativePath.equals(pattern) || relativePath.startsWith(pattern + "/");
            }
            return matchesPathComponent(relativePath, pattern);
        }

        /**
         * 判断是否匹配路径Component。
         *
         * @param relativePath 文件或目录路径参数。
         * @param component component 参数。
         * @return 返回matches路径Component结果。
         */
        private boolean matchesPathComponent(String relativePath, String component) {
            return relativePath.equals(component)
                    || relativePath.startsWith(component + "/")
                    || relativePath.endsWith("/" + component)
                    || relativePath.contains("/" + component + "/");
        }
    }
}
