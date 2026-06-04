package com.jimuqu.solon.claw.context;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External skill directories support. Loads skills from user-configured external directories
 * (outside runtime home).
 */
public class ExternalSkillDirectoryService {
    private final List<File> externalDirs;
    private final List<SkillDirectoryResolver.ExternalSkillDirectorySummary> directorySummaries;
    private volatile List<SkillDescriptor> cachedSkills;
    private volatile List<Map<String, Object>> cachedStatusDirectories;
    private volatile long cachedSkillFingerprint = Long.MIN_VALUE;
    private volatile long cachedStatusFingerprint = Long.MIN_VALUE;

    public ExternalSkillDirectoryService(AppConfig appConfig) {
        SkillDirectoryResolver resolver = new SkillDirectoryResolver(appConfig);
        this.externalDirs = resolver.externalSkillsDirs();
        this.directorySummaries = resolver.externalSkillDirSummaries();
    }

    public List<SkillDescriptor> scanExternalSkills() {
        long fingerprint = skillStateFingerprint();
        List<SkillDescriptor> cached = cachedSkills;
        if (cached != null && cachedSkillFingerprint == fingerprint) {
            return new ArrayList<SkillDescriptor>(cached);
        }
        return reloadSkills(fingerprint);
    }

    public synchronized List<SkillDescriptor> reloadSkills() {
        return reloadSkills(skillStateFingerprint());
    }

    private synchronized List<SkillDescriptor> reloadSkills(long fingerprint) {
        if (cachedSkills != null && cachedSkillFingerprint == fingerprint) {
            return new ArrayList<SkillDescriptor>(cachedSkills);
        }
        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        for (File dir : externalDirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = listDirectoryEntries(dir);
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!isUnderDirectory(file, dir)) {
                    continue;
                }
                if (file.isDirectory()) {
                    SkillDescriptor skill = loadSkillFromDir(file, dir);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } else if (isSkillFile(file)) {
                    SkillDescriptor skill = loadSkillFromFile(file, dir);
                    if (skill != null) {
                        skills.add(skill);
                    }
                }
            }
        }
        cachedSkills = skills;
        cachedSkillFingerprint = fingerprint;
        return new ArrayList<SkillDescriptor>(skills);
    }

    public List<File> getExternalDirs() {
        return Collections.unmodifiableList(externalDirs);
    }

    public Map<String, Object> status() {
        long fingerprint = statusStateFingerprint();
        List<Map<String, Object>> dirs = cachedStatusDirectories;
        boolean cacheHit = dirs != null && cachedStatusFingerprint == fingerprint;
        if (!cacheHit) {
            synchronized (this) {
                if (cachedStatusDirectories == null || cachedStatusFingerprint != fingerprint) {
                    cachedStatusDirectories = buildStatusDirectories();
                    cachedStatusFingerprint = fingerprint;
                }
                dirs = cachedStatusDirectories;
                cacheHit = dirs != null && cachedStatusFingerprint == fingerprint;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("directories", new ArrayList<Map<String, Object>>(dirs));
        result.put("totalDirs", Integer.valueOf(externalDirs.size()));
        result.put("configuredDirs", Integer.valueOf(directorySummaries.size()));
        result.put("normalizedDirs", Integer.valueOf(externalDirs.size()));
        result.put("duplicateDirs", Integer.valueOf(countDuplicates()));
        result.put("cacheHit", Boolean.valueOf(cacheHit));
        return result;
    }

    private List<Map<String, Object>> buildStatusDirectories() {
        List<Map<String, Object>> dirs = new ArrayList<Map<String, Object>>();
        for (SkillDirectoryResolver.ExternalSkillDirectorySummary summary : directorySummaries) {
            File dir = summary.getDirectory();
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("path", dir.getAbsolutePath());
            entry.put(
                    "configuredPath",
                    StrUtil.blankToDefault(summary.getConfiguredPath(), dir.getAbsolutePath()));
            entry.put("normalizedPath", summary.getNormalizedPath());
            entry.put("exists", Boolean.valueOf(dir.isDirectory()));
            entry.put("local", Boolean.valueOf(summary.isLocal()));
            entry.put("duplicate", Boolean.valueOf(summary.isDuplicate()));
            entry.put(
                    "included",
                    Boolean.valueOf(
                            !summary.isLocal() && !summary.isDuplicate() && dir.isDirectory()));
            if (dir.isDirectory()) {
                File[] files = listDirectoryEntries(dir);
                entry.put("skillCount", Integer.valueOf(files == null ? 0 : countSkills(files)));
            } else {
                entry.put("skillCount", Integer.valueOf(0));
            }
            dirs.add(entry);
        }
        return dirs;
    }

    private SkillDescriptor loadSkillFromDir(File dir, File parentDir) {
        File skillFile = skillManifest(dir);
        if (skillFile == null || !skillFile.isFile()) {
            return null;
        }
        return buildDescriptor(dir.getName(), skillFile, parentDir);
    }

    private SkillDescriptor loadSkillFromFile(File file, File parentDir) {
        return buildDescriptor(stripExtension(file.getName()), file, parentDir);
    }

    private SkillDescriptor buildDescriptor(String name, File file, File sourceDir) {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setName(name);
        descriptor.setSource("external:" + sourceDir.getAbsolutePath());
        descriptor.setTrustLevel("external");
        descriptor.setSkillDir(file.getParent());
        return descriptor;
    }

    private boolean isSkillFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".md") && !name.equals("readme.md");
    }

    private int countSkills(File[] files) {
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                if (skillManifest(file) != null) {
                    count++;
                }
            } else if (isSkillFile(file)) {
                count++;
            }
        }
        return count;
    }

    private int countDuplicates() {
        int count = 0;
        for (SkillDirectoryResolver.ExternalSkillDirectorySummary summary : directorySummaries) {
            if (summary.isDuplicate()) {
                count++;
            }
        }
        return count;
    }

    private long skillStateFingerprint() {
        long fingerprint = 17L;
        for (File dir : externalDirs) {
            fingerprint = 31L * fingerprint + fingerprint(dir);
        }
        return fingerprint;
    }

    private long statusStateFingerprint() {
        long fingerprint = 17L;
        for (SkillDirectoryResolver.ExternalSkillDirectorySummary summary : directorySummaries) {
            fingerprint = 31L * fingerprint + summary.getNormalizedPath().hashCode();
            fingerprint = 31L * fingerprint + (summary.isLocal() ? 1L : 0L);
            fingerprint = 31L * fingerprint + (summary.isDuplicate() ? 1L : 0L);
            fingerprint = 31L * fingerprint + fingerprint(summary.getDirectory());
        }
        return fingerprint;
    }

    private long fingerprint(File dir) {
        long fingerprint = dir.getAbsolutePath().hashCode();
        if (!dir.isDirectory()) {
            return 31L * fingerprint + 1L;
        }
        File[] files = listDirectoryEntries(dir);
        if (files == null) {
            return 31L * fingerprint + 2L;
        }
        fingerprint = 31L * fingerprint + files.length;
        for (File file : files) {
            fingerprint = 31L * fingerprint + entryFingerprint(file);
        }
        return fingerprint;
    }

    private long entryFingerprint(File file) {
        long fingerprint = file.getName().hashCode();
        fingerprint = 31L * fingerprint + (file.isDirectory() ? 1L : 0L);
        if (file.isDirectory()) {
            File skillFile = skillManifest(file);
            if (skillFile != null) {
                fingerprint = 31L * fingerprint + skillFile.lastModified();
                fingerprint = 31L * fingerprint + skillFile.length();
            }
            return fingerprint;
        }
        if (isSkillFile(file)) {
            fingerprint = 31L * fingerprint + file.lastModified();
            fingerprint = 31L * fingerprint + file.length();
        }
        return fingerprint;
    }

    private File skillManifest(File dir) {
        File skillFile = new File(dir, "SKILL.md");
        if (skillFile.isFile()) {
            return skillFile;
        }
        skillFile = new File(dir, "skill.md");
        return skillFile.isFile() ? skillFile : null;
    }

    private File[] listDirectoryEntries(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 1) {
            return files;
        }
        Arrays.sort(
                files,
                new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        return left.getName().compareTo(right.getName());
                    }
                });
        return files;
    }

    private boolean isUnderDirectory(File file, File dir) {
        try {
            String canonicalFile = file.getCanonicalPath();
            String canonicalDir = dir.getCanonicalPath();
            return canonicalFile.startsWith(canonicalDir + File.separator)
                    || canonicalFile.equals(canonicalDir);
        } catch (Exception e) {
            return false;
        }
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
