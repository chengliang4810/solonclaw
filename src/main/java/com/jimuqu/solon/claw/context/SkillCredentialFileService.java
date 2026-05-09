package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validation for skill-declared credential files. */
public class SkillCredentialFileService {
    private static final String DEFAULT_CONTAINER_BASE = "/root/.jimuqu-agent";
    private static final List<CacheMountDirectory> CACHE_MOUNT_DIRS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new CacheMountDirectory("documents", "document_cache"),
                            new CacheMountDirectory("images", "image_cache"),
                            new CacheMountDirectory("audio", "audio_cache"),
                            new CacheMountDirectory("screenshots", "browser_screenshots"),
                            new CacheMountDirectory("media", null),
                            new CacheMountDirectory("pdf", null),
                            new CacheMountDirectory("tool-results", null)));

    private final AppConfig appConfig;
    private final File runtimeHome;
    private final File skillsDir;
    private final File cacheDir;
    private final SkillDirectoryResolver skillDirectoryResolver;

    public SkillCredentialFileService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.skillDirectoryResolver = new SkillDirectoryResolver(appConfig);
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
        String cache =
                appConfig == null || appConfig.getRuntime() == null
                        ? new File(this.runtimeHome, "cache").getPath()
                        : StrUtil.blankToDefault(
                                appConfig.getRuntime().getCacheDir(),
                                new File(this.runtimeHome, "cache").getPath());
        this.skillsDir = FileUtil.file(skills).getAbsoluteFile();
        this.cacheDir = FileUtil.file(cache).getAbsoluteFile();
    }

    public CredentialFilePlan plan(Object rawEntries) {
        return plan(rawEntries, DEFAULT_CONTAINER_BASE);
    }

    public CredentialFilePlan configPlan() {
        return configPlan(DEFAULT_CONTAINER_BASE);
    }

    public CredentialFilePlan configPlan(String containerBase) {
        List<String> credentialFiles =
                appConfig == null || appConfig.getTerminal() == null
                        ? Collections.<String>emptyList()
                        : appConfig.getTerminal().getCredentialFiles();
        return plan(new ArrayList<Object>(credentialFiles), containerBase);
    }

    public SandboxMountPlan sandboxMountPlan() {
        return sandboxMountPlan(DEFAULT_CONTAINER_BASE);
    }

    public SandboxMountPlan sandboxMountPlan(String containerBase) {
        String base = StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE);
        SandboxMountPlan plan = new SandboxMountPlan();
        plan.getCredentialFiles().addAll(configPlan(base).getMounts());
        plan.getSkillsDirectories().addAll(skillsDirectoryMounts(base));
        plan.getCacheDirectories().addAll(cacheDirectoryMounts(base));
        return plan;
    }

    public Map<String, Object> policySummary() {
        CredentialFilePlan credentialPlan = configPlan(DEFAULT_CONTAINER_BASE);
        SandboxMountPlan sandboxPlan = sandboxMountPlan(DEFAULT_CONTAINER_BASE);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("configCredentialFileCount", Integer.valueOf(configCredentialFileCount()));
        summary.put("configuredMountCount", Integer.valueOf(credentialPlan.getMounts().size()));
        summary.put("configuredMissingCount", Integer.valueOf(credentialPlan.getMissing().size()));
        summary.put("configuredRejectedCount", Integer.valueOf(credentialPlan.getRejected().size()));
        summary.put("sandboxCredentialMountCount", Integer.valueOf(sandboxPlan.getCredentialFiles().size()));
        summary.put("skillsDirectoryMountCount", Integer.valueOf(sandboxPlan.getSkillsDirectories().size()));
        summary.put("cacheDirectoryMountCount", Integer.valueOf(sandboxPlan.getCacheDirectories().size()));
        summary.put("runtimeRelativeOnly", Boolean.TRUE);
        summary.put("absolutePathRejected", Boolean.TRUE);
        summary.put("pathTraversalRejected", Boolean.TRUE);
        summary.put("controlCharacterRejected", Boolean.TRUE);
        summary.put("runtimeHomeEscapeRejected", Boolean.TRUE);
        summary.put("missingFilesNotMounted", Boolean.TRUE);
        summary.put("hostPathsOmittedFromMetadata", Boolean.TRUE);
        summary.put("rejectedPathsRedacted", Boolean.TRUE);
        summary.put("customContainerBaseSupported", Boolean.TRUE);
        summary.put("defaultContainerBase", DEFAULT_CONTAINER_BASE);
        summary.put("skillsSymlinkSafeCopy", Boolean.TRUE);
        summary.put("cacheSymlinksSkipped", Boolean.TRUE);
        summary.put("skillFrontmatterKey", "required_credential_files");
        summary.put("configKey", "terminal.credentialFiles");
        summary.put("cacheMountDirectories", cacheMountDirectoryNames());
        return summary;
    }

    public List<DirectoryMount> skillsDirectoryMounts(String containerBase) {
        String base = stripTrailingSlash(StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE)
                .replace('\\', '/'));
        List<DirectoryMount> mounts = new ArrayList<DirectoryMount>();
        if (skillsDir.isDirectory()) {
            File hostDir = symlinkSafeSkillsDir(skillsDir, "skills");
            mounts.add(new DirectoryMount(hostDir.getAbsolutePath(), base + "/skills"));
        }
        List<File> externalDirs = externalSkillsDirs();
        for (int i = 0; i < externalDirs.size(); i++) {
            File externalDir = externalDirs.get(i);
            File hostDir = symlinkSafeSkillsDir(externalDir, "external-skills-" + i);
            mounts.add(
                    new DirectoryMount(
                            hostDir.getAbsolutePath(), base + "/external_skills/" + i));
        }
        return mounts;
    }

    public List<FileMount> iterSkillsFiles() {
        return iterSkillsFiles(DEFAULT_CONTAINER_BASE);
    }

    public List<FileMount> iterSkillsFiles(String containerBase) {
        String base = stripTrailingSlash(StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE)
                .replace('\\', '/'));
        List<FileMount> files = new ArrayList<FileMount>();
        if (skillsDir.isDirectory() && !isSymbolicLink(skillsDir)) {
            collectFiles(skillsDir, skillsDir, base + "/skills", files);
        }
        List<File> externalDirs = externalSkillsDirs();
        for (int i = 0; i < externalDirs.size(); i++) {
            File externalDir = externalDirs.get(i);
            if (!externalDir.isDirectory() || isSymbolicLink(externalDir)) {
                continue;
            }
            collectFiles(externalDir, externalDir, base + "/external_skills/" + i, files);
        }
        return files;
    }

    public List<DirectoryMount> cacheDirectoryMounts() {
        return cacheDirectoryMounts(DEFAULT_CONTAINER_BASE);
    }

    public List<DirectoryMount> cacheDirectoryMounts(String containerBase) {
        String base = StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE);
        List<DirectoryMount> mounts = new ArrayList<DirectoryMount>();
        for (CacheMountDirectory directory : CACHE_MOUNT_DIRS) {
            File dir = resolveCacheDirectory(directory);
            if (dir.isDirectory() && !isSymbolicLink(dir)) {
                mounts.add(
                        new DirectoryMount(
                                dir.getAbsolutePath(),
                                stripTrailingSlash(base.replace('\\', '/'))
                                        + "/cache/"
                                        + directory.containerName));
            }
        }
        return mounts;
    }

    public List<FileMount> iterCacheFiles() {
        return iterCacheFiles(DEFAULT_CONTAINER_BASE);
    }

    public List<FileMount> iterCacheFiles(String containerBase) {
        String base = stripTrailingSlash(StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE)
                .replace('\\', '/'));
        List<FileMount> files = new ArrayList<FileMount>();
        for (CacheMountDirectory directory : CACHE_MOUNT_DIRS) {
            File dir = resolveCacheDirectory(directory);
            if (!dir.isDirectory() || isSymbolicLink(dir)) {
                continue;
            }
            collectFiles(dir, dir, base + "/cache/" + directory.containerName, files);
        }
        return files;
    }

    public CredentialFilePlan plan(Object rawEntries, String containerBase) {
        List<Object> entries = normalizeEntries(rawEntries);
        CredentialFilePlan plan = new CredentialFilePlan();
        if (entries.isEmpty()) {
            return plan;
        }

        String base = StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE);
        for (Object entry : entries) {
            String relativePath = relativePath(entry);
            if (StrUtil.isBlank(relativePath)) {
                continue;
            }
            CredentialFileMount mount = resolve(relativePath, base);
            if (mount.isRegistered()) {
                plan.getMounts().add(mount);
            } else if ("missing".equals(mount.getStatus())) {
                plan.getMissing().add(mount.getRelativePath());
            } else {
                plan.getRejected().add(mount);
            }
        }
        return plan;
    }

    private CredentialFileMount resolve(String rawPath, String containerBase) {
        String relativePath = normalizeRelativeText(rawPath);
        if (StrUtil.isBlank(relativePath)) {
            return CredentialFileMount.rejected(rawPath, "empty path");
        }
        if (containsControlCharacter(relativePath)) {
            return CredentialFileMount.rejected(rawPath, "path contains control character");
        }
        String trimmedRawPath = StrUtil.nullToEmpty(rawPath).trim();
        if (isAbsolutePath(trimmedRawPath)) {
            return CredentialFileMount.rejected(rawPath, "absolute path is not allowed");
        }
        if (hasTraversal(relativePath)) {
            return CredentialFileMount.rejected(rawPath, "path traversal is not allowed");
        }
        try {
            File candidate = FileUtil.file(runtimeHome, relativePath).getCanonicalFile();
            File home = runtimeHome.getCanonicalFile();
            if (!isInside(candidate, home)) {
                return CredentialFileMount.rejected(rawPath, "path escapes runtime home");
            }
            if (!candidate.isFile()) {
                return CredentialFileMount.missing(relativePath);
            }
            String containerPath = containerBase.replace('\\', '/').replaceAll("/+$", "")
                    + "/"
                    + relativePath.replace('\\', '/');
            return CredentialFileMount.registered(relativePath, candidate.getAbsolutePath(), containerPath);
        } catch (Exception e) {
            return CredentialFileMount.rejected(rawPath, "invalid credential file path");
        }
    }

    private File symlinkSafeSkillsDir(File source, String safeName) {
        if (!containsSymlink(source)) {
            return source;
        }
        File safeRoot = new File(cacheDir, "safe-skills");
        File safeDir = new File(safeRoot, safeName);
        FileUtil.del(safeDir);
        FileUtil.mkdir(safeDir);
        copyTreeSkippingSymlinks(source, safeDir);
        return safeDir;
    }

    private List<File> externalSkillsDirs() {
        return skillDirectoryResolver.externalSkillsDirs();
    }

    private boolean containsSymlink(File root) {
        if (root == null || !root.exists()) {
            return false;
        }
        if (isSymbolicLink(root)) {
            return true;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (containsSymlink(child)) {
                return true;
            }
        }
        return false;
    }

    private void copyTreeSkippingSymlinks(File source, File target) {
        if (source == null || !source.exists() || isSymbolicLink(source)) {
            return;
        }
        if (source.isDirectory()) {
            FileUtil.mkdir(target);
            File[] children = source.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                copyTreeSkippingSymlinks(child, new File(target, child.getName()));
            }
            return;
        }
        if (source.isFile()) {
            FileUtil.mkParentDirs(target);
            FileUtil.copy(source, target, true);
        }
    }

    private void collectFiles(File root, File current, String containerRoot, List<FileMount> files) {
        if (current == null || !current.exists() || isSymbolicLink(current)) {
            return;
        }
        if (current.isFile()) {
            String relativePath = relativePath(root, current);
            if (StrUtil.isBlank(relativePath)) {
                return;
            }
            files.add(
                    new FileMount(
                            current.getAbsolutePath(),
                            containerRoot + "/" + relativePath.replace('\\', '/')));
            return;
        }
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFiles(root, child, containerRoot, files);
        }
    }

    private String relativePath(File root, File file) {
        try {
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            Path filePath = file.toPath().toAbsolutePath().normalize();
            return rootPath.relativize(filePath).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    private File resolveCacheDirectory(CacheMountDirectory directory) {
        if (StrUtil.isNotBlank(directory.legacyName)) {
            File legacy = new File(runtimeHome, directory.legacyName);
            if (legacy.isDirectory()) {
                return legacy;
            }
        }
        return new File(cacheDir, directory.containerName);
    }

    private boolean isSymbolicLink(File file) {
        try {
            Path path = file.toPath();
            return Files.isSymbolicLink(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String stripTrailingSlash(String value) {
        String text = StrUtil.blankToDefault(value, DEFAULT_CONTAINER_BASE);
        while (text.endsWith("/") && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private boolean isInside(File child, File parent) {
        String childPath = child.getAbsolutePath();
        String parentPath = parent.getAbsolutePath();
        if (childPath.equals(parentPath)) {
            return true;
        }
        if (!parentPath.endsWith(File.separator)) {
            parentPath = parentPath + File.separator;
        }
        return childPath.startsWith(parentPath);
    }

    private boolean hasTraversal(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    private boolean isAbsolutePath(String rawPath) {
        return new File(rawPath).isAbsolute()
                || rawPath.startsWith("/")
                || rawPath.startsWith("\\");
    }

    private String normalizeRelativeText(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        while (value.startsWith("/") || value.startsWith("\\")) {
            value = value.substring(1);
        }
        return value;
    }

    private boolean containsControlCharacter(String value) {
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

    private int configCredentialFileCount() {
        if (appConfig == null || appConfig.getTerminal() == null
                || appConfig.getTerminal().getCredentialFiles() == null) {
            return 0;
        }
        return appConfig.getTerminal().getCredentialFiles().size();
    }

    private List<String> cacheMountDirectoryNames() {
        List<String> names = new ArrayList<String>();
        for (CacheMountDirectory directory : CACHE_MOUNT_DIRS) {
            names.add(directory.containerName);
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeEntries(Object rawEntries) {
        if (rawEntries == null) {
            return Collections.emptyList();
        }
        if (rawEntries instanceof List) {
            return (List<Object>) rawEntries;
        }
        List<Object> result = new ArrayList<Object>();
        result.add(rawEntries);
        return result;
    }

    private String relativePath(Object entry) {
        if (entry == null) {
            return "";
        }
        if (entry instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) entry;
            Object value = map.containsKey("path") ? map.get("path") : map.get("name");
            return value == null ? "" : String.valueOf(value).trim();
        }
        return String.valueOf(entry).trim();
    }

    private static class CacheMountDirectory {
        private final String containerName;
        private final String legacyName;

        private CacheMountDirectory(String containerName, String legacyName) {
            this.containerName = containerName;
            this.legacyName = legacyName;
        }
    }

    public static class CredentialFilePlan {
        private final List<CredentialFileMount> mounts = new ArrayList<CredentialFileMount>();
        private final List<String> missing = new ArrayList<String>();
        private final List<CredentialFileMount> rejected = new ArrayList<CredentialFileMount>();

        public List<CredentialFileMount> getMounts() {
            return mounts;
        }

        public List<String> getMissing() {
            return missing;
        }

        public List<CredentialFileMount> getRejected() {
            return rejected;
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> mountMaps = new ArrayList<Map<String, Object>>();
            for (CredentialFileMount mount : mounts) {
                mountMaps.add(mount.toMetadata());
            }
            List<Map<String, Object>> rejectedMaps = new ArrayList<Map<String, Object>>();
            for (CredentialFileMount mount : rejected) {
                rejectedMaps.add(mount.toMetadata());
            }
            map.put("mounts", mountMaps);
            map.put("missing", new ArrayList<String>(missing));
            map.put("rejected", rejectedMaps);
            return map;
        }
    }

    public static class SandboxMountPlan {
        private final List<CredentialFileMount> credentialFiles =
                new ArrayList<CredentialFileMount>();
        private final List<DirectoryMount> skillsDirectories = new ArrayList<DirectoryMount>();
        private final List<DirectoryMount> cacheDirectories = new ArrayList<DirectoryMount>();

        public List<CredentialFileMount> getCredentialFiles() {
            return credentialFiles;
        }

        public List<DirectoryMount> getSkillsDirectories() {
            return skillsDirectories;
        }

        public List<DirectoryMount> getCacheDirectories() {
            return cacheDirectories;
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> credentialMaps = new ArrayList<Map<String, Object>>();
            for (CredentialFileMount mount : credentialFiles) {
                credentialMaps.add(mount.toMetadata());
            }
            List<Map<String, Object>> skillsMaps = new ArrayList<Map<String, Object>>();
            for (DirectoryMount mount : skillsDirectories) {
                skillsMaps.add(mount.toMetadata());
            }
            List<Map<String, Object>> cacheMaps = new ArrayList<Map<String, Object>>();
            for (DirectoryMount mount : cacheDirectories) {
                cacheMaps.add(mount.toMetadata());
            }
            map.put("credential_files", credentialMaps);
            map.put("skills_directories", skillsMaps);
            map.put("cache_directories", cacheMaps);
            return map;
        }
    }

    public static class DirectoryMount {
        private final String hostPath;
        private final String containerPath;

        public DirectoryMount(String hostPath, String containerPath) {
            this.hostPath = hostPath;
            this.containerPath = containerPath;
        }

        public String getHostPath() {
            return hostPath;
        }

        public String getContainerPath() {
            return containerPath;
        }

        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("container_path", containerPath);
            return map;
        }
    }

    public static class FileMount {
        private final String hostPath;
        private final String containerPath;

        public FileMount(String hostPath, String containerPath) {
            this.hostPath = hostPath;
            this.containerPath = containerPath;
        }

        public String getHostPath() {
            return hostPath;
        }

        public String getContainerPath() {
            return containerPath;
        }

        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("container_path", containerPath);
            return map;
        }
    }

    public static class CredentialFileMount {
        private String relativePath;
        private String hostPath;
        private String containerPath;
        private String status;
        private String reason;

        private static CredentialFileMount registered(
                String relativePath, String hostPath, String containerPath) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.hostPath = hostPath;
            mount.containerPath = containerPath;
            mount.status = "registered";
            return mount;
        }

        private static CredentialFileMount missing(String relativePath) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.status = "missing";
            mount.reason = "file not found";
            return mount;
        }

        private static CredentialFileMount rejected(String relativePath, String reason) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.status = "rejected";
            mount.reason = reason;
            return mount;
        }

        public boolean isRegistered() {
            return "registered".equals(status);
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getHostPath() {
            return hostPath;
        }

        public String getContainerPath() {
            return containerPath;
        }

        public String getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("path", "registered".equals(status) ? relativePath : SecretRedactor.redact(relativePath, 400));
            map.put("container_path", containerPath);
            map.put("status", status);
            map.put("reason", reason);
            return map;
        }
    }
}
