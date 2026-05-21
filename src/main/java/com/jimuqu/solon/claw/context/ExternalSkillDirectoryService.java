package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SkillDescriptor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External skill directories support.
 * Loads skills from user-configured external directories (outside runtime home).
 */
public class ExternalSkillDirectoryService {
    private final AppConfig appConfig;
    private final List<File> externalDirs;

    public ExternalSkillDirectoryService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.externalDirs = resolveExternalDirs();
    }

    public List<SkillDescriptor> scanExternalSkills() {
        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        for (File dir : externalDirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
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
        return skills;
    }

    public List<File> getExternalDirs() {
        return Collections.unmodifiableList(externalDirs);
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> dirs = new ArrayList<Map<String, Object>>();
        for (File dir : externalDirs) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("path", dir.getAbsolutePath());
            entry.put("exists", Boolean.valueOf(dir.isDirectory()));
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                entry.put("skillCount", Integer.valueOf(files == null ? 0 : countSkills(files)));
            }
            dirs.add(entry);
        }
        result.put("directories", dirs);
        result.put("totalDirs", Integer.valueOf(externalDirs.size()));
        return result;
    }

    private List<File> resolveExternalDirs() {
        List<File> dirs = new ArrayList<File>();
        if (appConfig.getSkills() == null) {
            return dirs;
        }
        List<String> paths = appConfig.getSkills().getExternalDirs();
        if (paths == null) {
            return dirs;
        }
        for (String path : paths) {
            if (StrUtil.isNotBlank(path)) {
                String expanded = expandHome(path.trim());
                dirs.add(new File(expanded));
            }
        }
        return dirs;
    }

    private SkillDescriptor loadSkillFromDir(File dir, File parentDir) {
        File skillFile = new File(dir, "SKILL.md");
        if (!skillFile.isFile()) {
            skillFile = new File(dir, "skill.md");
        }
        if (!skillFile.isFile()) {
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
                File skillFile = new File(file, "SKILL.md");
                if (skillFile.isFile() || new File(file, "skill.md").isFile()) {
                    count++;
                }
            } else if (isSkillFile(file)) {
                count++;
            }
        }
        return count;
    }

    private String expandHome(String path) {
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
