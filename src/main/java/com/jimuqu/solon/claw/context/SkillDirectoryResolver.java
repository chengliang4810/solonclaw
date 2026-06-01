package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves local and configured external skill directories using Jimuqu-compatible rules. */
public class SkillDirectoryResolver {
    private final AppConfig appConfig;
    private final File runtimeHome;
    private final File skillsDir;

    public static final class ExternalSkillDirectorySummary {
        private final String configuredPath;
        private final File directory;
        private final boolean local;
        private final boolean duplicate;

        public ExternalSkillDirectorySummary(
                String configuredPath, File directory, boolean local, boolean duplicate) {
            this.configuredPath = configuredPath;
            this.directory = directory;
            this.local = local;
            this.duplicate = duplicate;
        }

        public String getConfiguredPath() {
            return configuredPath;
        }

        public File getDirectory() {
            return directory;
        }

        public String getNormalizedPath() {
            return directory.getAbsolutePath();
        }

        public boolean isLocal() {
            return local;
        }

        public boolean isDuplicate() {
            return duplicate;
        }
    }

    public SkillDirectoryResolver(AppConfig appConfig) {
        this.appConfig = appConfig;
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? "runtime"
                        : StrUtil.blankToDefault(appConfig.getRuntime().getHome(), "runtime");
        this.runtimeHome = FileUtil.file(home).getAbsoluteFile();
        String skills =
                appConfig == null || appConfig.getRuntime() == null
                        ? new File(this.runtimeHome, "skills").getPath()
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getSkillsDir(),
                                new File(this.runtimeHome, "skills").getPath());
        this.skillsDir = FileUtil.file(skills).getAbsoluteFile();
    }

    public File localSkillsDir() {
        return skillsDir;
    }

    public List<File> allSkillsDirs() {
        List<File> dirs = new ArrayList<File>();
        dirs.add(skillsDir);
        dirs.addAll(externalSkillsDirs());
        return dirs;
    }

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

    private File resolveExternalSkillsDir(String rawPath) {
        String expanded = expandPathVariables(rawPath);
        File file = FileUtil.file(expanded);
        if (!file.isAbsolute()) {
            file = FileUtil.file(runtimeHome, expanded);
        }
        return file.getAbsoluteFile();
    }

    private String expandPathVariables(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        if (value.equals("~")) {
            value = System.getProperty("user.home", "") + File.separator;
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", "") + value.substring(1);
        }
        return expandEnvironmentVariables(value);
    }

    private String expandEnvironmentVariables(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            char ch = value.charAt(i);
            if (ch == '$' && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                int end = value.indexOf('}', i + 2);
                if (end > i) {
                    String name = value.substring(i + 2, end);
                    String replacement = System.getenv(name);
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

    private File canonicalOrAbsolute(File file) {
        try {
            return file.getCanonicalFile();
        } catch (Exception ignored) {
            return file.getAbsoluteFile();
        }
    }

    private boolean isSameFile(File left, File right) {
        return left != null
                && right != null
                && left.getAbsolutePath().equals(right.getAbsolutePath());
    }
}
