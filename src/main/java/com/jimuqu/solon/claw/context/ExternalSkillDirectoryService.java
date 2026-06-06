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

/** 提供外部技能Directory相关业务能力，封装调用方不需要感知的运行细节。 */
public class ExternalSkillDirectoryService {
    /** 保存外部Dirs集合，维持调用顺序或去重语义。 */
    private final List<File> externalDirs;

    /** 保存目录Summaries集合，维持调用顺序或去重语义。 */
    private final List<SkillDirectoryResolver.ExternalSkillDirectorySummary> directorySummaries;

    /** 保存cached技能集合，维持调用顺序或去重语义。 */
    private volatile List<SkillDescriptor> cachedSkills;

    /** 保存cached状态Directories映射，便于按键快速查询。 */
    private volatile List<Map<String, Object>> cachedStatusDirectories;

    /** 记录外部技能目录中的cached技能Fingerprint。 */
    private volatile long cachedSkillFingerprint = Long.MIN_VALUE;

    /** 记录外部技能目录中的cached状态Fingerprint。 */
    private volatile long cachedStatusFingerprint = Long.MIN_VALUE;

    /**
     * 创建外部技能Directory服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public ExternalSkillDirectoryService(AppConfig appConfig) {
        SkillDirectoryResolver resolver = new SkillDirectoryResolver(appConfig);
        this.externalDirs = resolver.externalSkillsDirs();
        this.directorySummaries = resolver.externalSkillDirSummaries();
    }

    /**
     * 执行scan外部技能相关逻辑。
     *
     * @return 返回scan外部技能结果。
     */
    public List<SkillDescriptor> scanExternalSkills() {
        long fingerprint = skillStateFingerprint();
        List<SkillDescriptor> cached = cachedSkills;
        if (cached != null && cachedSkillFingerprint == fingerprint) {
            return new ArrayList<SkillDescriptor>(cached);
        }
        return reloadSkills(fingerprint);
    }

    /**
     * 执行reload技能相关逻辑。
     *
     * @return 返回reload技能结果。
     */
    public synchronized List<SkillDescriptor> reloadSkills() {
        return reloadSkills(skillStateFingerprint());
    }

    /**
     * 执行reload技能相关逻辑。
     *
     * @param fingerprint fingerprint 参数。
     * @return 返回reload技能结果。
     */
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

    /**
     * 读取外部Dirs。
     *
     * @return 返回读取到的外部Dirs。
     */
    public List<File> getExternalDirs() {
        return Collections.unmodifiableList(externalDirs);
    }

    /**
     * 执行状态相关逻辑。
     *
     * @return 返回状态。
     */
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

    /**
     * 构建状态Directories。
     *
     * @return 返回创建好的状态Directories。
     */
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

    /**
     * 加载技能From Dir。
     *
     * @param dir 文件或目录路径参数。
     * @param parentDir 文件或目录路径参数。
     * @return 返回技能From Dir结果。
     */
    private SkillDescriptor loadSkillFromDir(File dir, File parentDir) {
        File skillFile = skillManifest(dir);
        if (skillFile == null || !skillFile.isFile()) {
            return null;
        }
        return buildDescriptor(dir.getName(), skillFile, parentDir);
    }

    /**
     * 加载技能From文件。
     *
     * @param file 文件或目录路径参数。
     * @param parentDir 文件或目录路径参数。
     * @return 返回技能From文件结果。
     */
    private SkillDescriptor loadSkillFromFile(File file, File parentDir) {
        return buildDescriptor(stripExtension(file.getName()), file, parentDir);
    }

    /**
     * 构建描述符。
     *
     * @param name 名称参数。
     * @param file 文件或目录路径参数。
     * @param sourceDir 文件或目录路径参数。
     * @return 返回创建好的描述符。
     */
    private SkillDescriptor buildDescriptor(String name, File file, File sourceDir) {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setName(name);
        descriptor.setSource("external:" + sourceDir.getAbsolutePath());
        descriptor.setTrustLevel("external");
        descriptor.setSkillDir(file.getParent());
        return descriptor;
    }

    /**
     * 判断是否技能文件。
     *
     * @param file 文件或目录路径参数。
     * @return 如果技能文件满足条件则返回 true，否则返回 false。
     */
    private boolean isSkillFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".md") && !name.equals("readme.md");
    }

    /**
     * 执行次数技能相关逻辑。
     *
     * @param files 文件或目录路径参数。
     * @return 返回次数技能结果。
     */
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

    /**
     * 执行次数Duplicates相关逻辑。
     *
     * @return 返回次数Duplicates结果。
     */
    private int countDuplicates() {
        int count = 0;
        for (SkillDirectoryResolver.ExternalSkillDirectorySummary summary : directorySummaries) {
            if (summary.isDuplicate()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 执行技能状态Fingerprint相关逻辑。
     *
     * @return 返回技能状态Fingerprint结果。
     */
    private long skillStateFingerprint() {
        long fingerprint = 17L;
        for (File dir : externalDirs) {
            fingerprint = 31L * fingerprint + fingerprint(dir);
        }
        return fingerprint;
    }

    /**
     * 执行状态状态Fingerprint相关逻辑。
     *
     * @return 返回状态状态Fingerprint结果。
     */
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

    /**
     * 执行fingerprint相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @return 返回fingerprint结果。
     */
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

    /**
     * 执行entryFingerprint相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回entry Fingerprint结果。
     */
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

    /**
     * 执行技能Manifest相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @return 返回技能Manifest结果。
     */
    private File skillManifest(File dir) {
        File skillFile = new File(dir, "SKILL.md");
        if (skillFile.isFile()) {
            return skillFile;
        }
        skillFile = new File(dir, "skill.md");
        return skillFile.isFile() ? skillFile : null;
    }

    /**
     * 列出Directory Entries。
     *
     * @param dir 文件或目录路径参数。
     * @return 返回Directory Entries列表。
     */
    private File[] listDirectoryEntries(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 1) {
            return files;
        }
        Arrays.sort(
                files,
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
                        return left.getName().compareTo(right.getName());
                    }
                });
        return files;
    }

    /**
     * 判断是否Under Directory。
     *
     * @param file 文件或目录路径参数。
     * @param dir 文件或目录路径参数。
     * @return 如果Under Directory满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 剥离扩展名。
     *
     * @param name 名称参数。
     * @return 返回strip Extension结果。
     */
    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
