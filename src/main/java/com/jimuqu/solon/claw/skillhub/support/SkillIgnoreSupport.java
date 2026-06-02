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

/** Simple per-skill ignore-file support for scan and discovery noise filtering. */
public final class SkillIgnoreSupport {
    private static final String LOWER_WINDOWS_DRIVE_PATTERN = "^[a-z]:/.*";

    private SkillIgnoreSupport() {}

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
                    @Override
                    public int compare(File left, File right) {
                        return relativePath(skillDir, left).compareTo(relativePath(skillDir, right));
                    }
                });
        return result;
    }

    public static boolean isIgnored(File skillDir, File file) {
        return load(skillDir).isIgnored(file);
    }

    public static String relativePath(File root, File file) {
        String relative = relativePathOrNull(root, file);
        return relative == null ? StrUtil.nullToEmpty(file == null ? null : file.getName()) : relative;
    }

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

    private static boolean isMainManifest(String relativePath) {
        String normalized = normalizePath(relativePath);
        return SkillConstants.SKILL_FILE_NAME.equalsIgnoreCase(normalized)
                || "skill.md".equalsIgnoreCase(normalized)
                || ContextFileConstants.FILE_AGENTS.equalsIgnoreCase(normalized);
    }

    private static boolean isIgnoreFile(String relativePath) {
        return SkillConstants.IGNORE_FILE_NAME.equals(normalizePath(relativePath));
    }

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
            String rootPrefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            if (!filePath.startsWith(rootPrefix)) {
                return null;
            }
            return filePath.substring(rootPrefix.length()).replace(File.separatorChar, '/');
        } catch (Exception e) {
            return null;
        }
    }

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

    private static boolean hasTraversal(String value) {
        String normalized = normalizePath(value);
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    private static boolean isAbsoluteRule(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().replace('\\', '/');
        return new File(normalized).isAbsolute()
                || normalized.startsWith("/")
                || normalized.toLowerCase(Locale.ROOT).matches(LOWER_WINDOWS_DRIVE_PATTERN);
    }

    private static class SkillIgnoreMatcher {
        private final File skillDir;
        private final List<Rule> rules;

        private SkillIgnoreMatcher(File skillDir, List<Rule> rules) {
            this.skillDir = skillDir;
            this.rules = rules;
        }

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

    private static class Rule {
        private final String pattern;
        private final boolean directoryOnly;

        private Rule(String pattern, boolean directoryOnly) {
            this.pattern = pattern;
            this.directoryOnly = directoryOnly;
        }

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

        private boolean matches(String relativePath) {
            if (directoryOnly) {
                return matchesDirectory(relativePath);
            }
            if (pattern.indexOf('/') >= 0) {
                return relativePath.equals(pattern) || relativePath.startsWith(pattern + "/");
            }
            return matchesPathComponent(relativePath, pattern);
        }

        private boolean matchesDirectory(String relativePath) {
            if (pattern.indexOf('/') >= 0) {
                return relativePath.equals(pattern) || relativePath.startsWith(pattern + "/");
            }
            return matchesPathComponent(relativePath, pattern);
        }

        private boolean matchesPathComponent(String relativePath, String component) {
            return relativePath.equals(component)
                    || relativePath.startsWith(component + "/")
                    || relativePath.endsWith("/" + component)
                    || relativePath.contains("/" + component + "/");
        }
    }
}
