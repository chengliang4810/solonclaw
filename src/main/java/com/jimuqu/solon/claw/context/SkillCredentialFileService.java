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

/** 提供技能凭据文件相关业务能力，封装调用方不需要感知的运行细节。 */
public class SkillCredentialFileService {
    /** 默认CONTAINER基础的统一常量值。 */
    private static final String DEFAULT_CONTAINER_BASE = "/root/.solon-claw";

    /** 缓存MOUNTDIRS的统一常量值。 */
    private static final List<CacheMountDirectory> CACHE_MOUNT_DIRS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new CacheMountDirectory("documents"),
                            new CacheMountDirectory("images"),
                            new CacheMountDirectory("audio"),
                            new CacheMountDirectory("screenshots"),
                            new CacheMountDirectory("media"),
                            new CacheMountDirectory("pdf"),
                            new CacheMountDirectory("tool-results")));

    /** 注入应用配置，用于技能凭据文件。 */
    private final AppConfig appConfig;

    /** 记录技能凭据文件中的运行时主渠道。 */
    private final File runtimeHome;

    /** 记录技能凭据文件中的技能目录。 */
    private final File skillsDir;

    /** 记录技能凭据文件中的缓存目录。 */
    private final File cacheDir;

    /** 记录技能凭据文件中的技能目录Resolver。 */
    private final SkillDirectoryResolver skillDirectoryResolver;

    /**
     * 创建技能凭据文件服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
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

    /**
     * 执行plan相关逻辑。
     *
     * @param rawEntries 原始Entries参数。
     * @return 返回plan结果。
     */
    public CredentialFilePlan plan(Object rawEntries) {
        return plan(rawEntries, DEFAULT_CONTAINER_BASE);
    }

    /**
     * 执行配置Plan相关逻辑。
     *
     * @return 返回配置Plan结果。
     */
    public CredentialFilePlan configPlan() {
        return configPlan(DEFAULT_CONTAINER_BASE);
    }

    /**
     * 执行配置Plan相关逻辑。
     *
     * @param containerBase container基础参数。
     * @return 返回配置Plan结果。
     */
    public CredentialFilePlan configPlan(String containerBase) {
        List<String> credentialFiles =
                appConfig == null || appConfig.getTerminal() == null
                        ? Collections.<String>emptyList()
                        : appConfig.getTerminal().getCredentialFiles();
        return plan(new ArrayList<Object>(credentialFiles), containerBase);
    }

    /**
     * 执行sandboxMountPlan相关逻辑。
     *
     * @return 返回sandbox Mount Plan结果。
     */
    public SandboxMountPlan sandboxMountPlan() {
        return sandboxMountPlan(DEFAULT_CONTAINER_BASE);
    }

    /**
     * 执行sandboxMountPlan相关逻辑。
     *
     * @param containerBase container基础参数。
     * @return 返回sandbox Mount Plan结果。
     */
    public SandboxMountPlan sandboxMountPlan(String containerBase) {
        String base = StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE);
        SandboxMountPlan plan = new SandboxMountPlan();
        plan.getCredentialFiles().addAll(configPlan(base).getMounts());
        plan.getSkillsDirectories().addAll(skillsDirectoryMounts(base));
        plan.getCacheDirectories().addAll(cacheDirectoryMounts(base));
        return plan;
    }

    /**
     * 构建当前策略配置摘要。
     *
     * @return 返回策略Summary结果。
     */
    public Map<String, Object> policySummary() {
        CredentialFilePlan credentialPlan = configPlan(DEFAULT_CONTAINER_BASE);
        SandboxMountPlan sandboxPlan = sandboxMountPlan(DEFAULT_CONTAINER_BASE);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("configCredentialFileCount", Integer.valueOf(configCredentialFileCount()));
        summary.put("configuredMountCount", Integer.valueOf(credentialPlan.getMounts().size()));
        summary.put("configuredMissingCount", Integer.valueOf(credentialPlan.getMissing().size()));
        summary.put(
                "configuredRejectedCount", Integer.valueOf(credentialPlan.getRejected().size()));
        summary.put(
                "sandboxCredentialMountCount",
                Integer.valueOf(sandboxPlan.getCredentialFiles().size()));
        summary.put(
                "skillsDirectoryMountCount",
                Integer.valueOf(sandboxPlan.getSkillsDirectories().size()));
        summary.put(
                "cacheDirectoryMountCount",
                Integer.valueOf(sandboxPlan.getCacheDirectories().size()));
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
        summary.put("configKey", "solonclaw.terminal.credentialFiles");
        summary.put("cacheMountDirectories", cacheMountDirectoryNames());
        return summary;
    }

    /**
     * 执行技能目录Mounts相关逻辑。
     *
     * @param containerBase container基础参数。
     * @return 返回技能Directory Mounts结果。
     */
    public List<DirectoryMount> skillsDirectoryMounts(String containerBase) {
        String base =
                stripTrailingSlash(
                        StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE)
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
                    new DirectoryMount(hostDir.getAbsolutePath(), base + "/external_skills/" + i));
        }
        return mounts;
    }

    /**
     * 执行iter技能Files相关逻辑。
     *
     * @return 返回iter技能Files结果。
     */
    public List<FileMount> iterSkillsFiles() {
        return iterSkillsFiles(DEFAULT_CONTAINER_BASE);
    }

    /**
     * 执行iter技能Files相关逻辑。
     *
     * @param containerBase container基础参数。
     * @return 返回iter技能Files结果。
     */
    public List<FileMount> iterSkillsFiles(String containerBase) {
        String base =
                stripTrailingSlash(
                        StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE)
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

    /**
     * 执行缓存目录Mounts相关逻辑。
     *
     * @return 返回缓存Directory Mounts结果。
     */
    public List<DirectoryMount> cacheDirectoryMounts() {
        return cacheDirectoryMounts(DEFAULT_CONTAINER_BASE);
    }

    /**
     * 执行缓存目录Mounts相关逻辑。
     *
     * @param containerBase container基础参数。
     * @return 返回缓存Directory Mounts结果。
     */
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

    /**
     * 执行iter缓存Files相关逻辑。
     *
     * @return 返回iter缓存Files结果。
     */
    public List<FileMount> iterCacheFiles() {
        return iterCacheFiles(DEFAULT_CONTAINER_BASE);
    }

    /**
     * 执行iter缓存Files相关逻辑。
     *
     * @param containerBase container基础参数。
     * @return 返回iter缓存Files结果。
     */
    public List<FileMount> iterCacheFiles(String containerBase) {
        String base =
                stripTrailingSlash(
                        StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE)
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

    /**
     * 执行plan相关逻辑。
     *
     * @param rawEntries 原始Entries参数。
     * @param containerBase container基础参数。
     * @return 返回plan结果。
     */
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

    /**
     * 解析运行时需要的目标对象。
     *
     * @param rawPath 文件或目录路径参数。
     * @param containerBase container基础参数。
     * @return 返回resolve结果。
     */
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
            String containerPath =
                    containerBase.replace('\\', '/').replaceAll("/+$", "")
                            + "/"
                            + relativePath.replace('\\', '/');
            return CredentialFileMount.registered(
                    relativePath, candidate.getAbsolutePath(), containerPath);
        } catch (Exception e) {
            return CredentialFileMount.rejected(rawPath, "invalid credential file path");
        }
    }

    /**
     * 执行symlink安全技能目录相关逻辑。
     *
     * @param source 来源参数。
     * @param safeName 安全名称参数。
     * @return 返回symlink Safe技能Dir结果。
     */
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

    /**
     * 执行外部技能Dirs相关逻辑。
     *
     * @return 返回外部技能Dirs结果。
     */
    private List<File> externalSkillsDirs() {
        return skillDirectoryResolver.externalSkillsDirs();
    }

    /**
     * 判断是否包含Symlink。
     *
     * @param root root 参数。
     * @return 返回contains Symlink结果。
     */
    private boolean containsSymlink(File root) {
        if (root == null) {
            return false;
        }
        if (isSymbolicLink(root)) {
            return true;
        }
        if (!root.exists()) {
            return false;
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

    /**
     * 复制Tree Skipping Symlinks。
     *
     * @param source 来源参数。
     * @param target target 参数。
     */
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

    /**
     * 收集Files。
     *
     * @param root root 参数。
     * @param current current 参数。
     * @param containerRoot containerRoot 参数。
     * @param files 文件或目录路径参数。
     */
    private void collectFiles(
            File root, File current, String containerRoot, List<FileMount> files) {
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

    /**
     * 执行relative路径相关逻辑。
     *
     * @param root root 参数。
     * @param file 文件或目录路径参数。
     * @return 返回relative路径。
     */
    private String relativePath(File root, File file) {
        try {
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            Path filePath = file.toPath().toAbsolutePath().normalize();
            return rootPath.relativize(filePath).toString();
        } catch (Exception e) {
            return file.getName();
        }
    }

    /**
     * 解析缓存Directory。
     *
     * @param directory 文件或目录路径参数。
     * @return 返回解析后的缓存Directory。
     */
    private File resolveCacheDirectory(CacheMountDirectory directory) {
        return new File(cacheDir, directory.containerName);
    }

    /**
     * 判断是否Symbolic Link。
     *
     * @param file 文件或目录路径参数。
     * @return 如果Symbolic Link满足条件则返回 true，否则返回 false。
     */
    private boolean isSymbolicLink(File file) {
        try {
            Path path = file.toPath();
            return Files.isSymbolicLink(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 剥离Trailing斜杠命令。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Trailing Slash结果。
     */
    private String stripTrailingSlash(String value) {
        String text = StrUtil.blankToDefault(value, DEFAULT_CONTAINER_BASE);
        while (text.endsWith("/") && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 判断是否Inside。
     *
     * @param child child 参数。
     * @param parent parent 参数。
     * @return 如果Inside满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否存在Traversal。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 如果Traversal满足条件则返回 true，否则返回 false。
     */
    private boolean hasTraversal(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    /**
     * 判断是否Absolute路径。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 如果Absolute路径满足条件则返回 true，否则返回 false。
     */
    private boolean isAbsolutePath(String rawPath) {
        return new File(rawPath).isAbsolute()
                || rawPath.startsWith("/")
                || rawPath.startsWith("\\");
    }

    /**
     * 规范化Relative Text。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回Relative Text结果。
     */
    private String normalizeRelativeText(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        while (value.startsWith("/") || value.startsWith("\\")) {
            value = value.substring(1);
        }
        return value;
    }

    /**
     * 判断是否包含控制Character。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Control Character结果。
     */
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

    /**
     * 执行配置凭据文件次数相关逻辑。
     *
     * @return 返回配置凭据文件次数结果。
     */
    private int configCredentialFileCount() {
        if (appConfig == null
                || appConfig.getTerminal() == null
                || appConfig.getTerminal().getCredentialFiles() == null) {
            return 0;
        }
        return appConfig.getTerminal().getCredentialFiles().size();
    }

    /**
     * 执行缓存Mount目录Names相关逻辑。
     *
     * @return 返回缓存Mount Directory Names结果。
     */
    private List<String> cacheMountDirectoryNames() {
        List<String> names = new ArrayList<String>();
        for (CacheMountDirectory directory : CACHE_MOUNT_DIRS) {
            names.add(directory.containerName);
        }
        return names;
    }

    /**
     * 规范化Entries。
     *
     * @param rawEntries 原始Entries参数。
     * @return 返回Entries结果。
     */
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

    /**
     * 执行relative路径相关逻辑。
     *
     * @param entry entry 参数。
     * @return 返回relative路径。
     */
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

    /** 承载缓存Mount目录相关状态和辅助逻辑。 */
    private static class CacheMountDirectory {
        /** 记录缓存Mount目录中的container名称。 */
        private final String containerName;

        /**
         * 创建缓存Mount Directory实例，并注入运行所需依赖。
         *
         * @param containerName container名称参数。
         */
        private CacheMountDirectory(String containerName) {
            this.containerName = containerName;
        }
    }

    /** 承载凭据文件Plan相关状态和辅助逻辑。 */
    public static class CredentialFilePlan {
        /** 保存mounts集合，维持调用顺序或去重语义。 */
        private final List<CredentialFileMount> mounts = new ArrayList<CredentialFileMount>();

        /** 保存missing集合，维持调用顺序或去重语义。 */
        private final List<String> missing = new ArrayList<String>();

        /** 保存拒绝集合，维持调用顺序或去重语义。 */
        private final List<CredentialFileMount> rejected = new ArrayList<CredentialFileMount>();

        /**
         * 读取Mounts。
         *
         * @return 返回读取到的Mounts。
         */
        public List<CredentialFileMount> getMounts() {
            return mounts;
        }

        /**
         * 读取Missing。
         *
         * @return 返回读取到的Missing。
         */
        public List<String> getMissing() {
            return missing;
        }

        /**
         * 读取拒绝。
         *
         * @return 返回读取到的拒绝。
         */
        public List<CredentialFileMount> getRejected() {
            return rejected;
        }

        /**
         * 转换为元数据。
         *
         * @return 返回转换后的元数据。
         */
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

    /** 承载SandboxMountPlan相关状态和辅助逻辑。 */
    public static class SandboxMountPlan {
        /** 保存凭据Files集合，维持调用顺序或去重语义。 */
        private final List<CredentialFileMount> credentialFiles =
                new ArrayList<CredentialFileMount>();

        /** 保存技能Directories集合，维持调用顺序或去重语义。 */
        private final List<DirectoryMount> skillsDirectories = new ArrayList<DirectoryMount>();

        /** 保存缓存Directories集合，维持调用顺序或去重语义。 */
        private final List<DirectoryMount> cacheDirectories = new ArrayList<DirectoryMount>();

        /**
         * 读取凭据Files。
         *
         * @return 返回读取到的凭据Files。
         */
        public List<CredentialFileMount> getCredentialFiles() {
            return credentialFiles;
        }

        /**
         * 读取技能Directories。
         *
         * @return 返回读取到的技能Directories。
         */
        public List<DirectoryMount> getSkillsDirectories() {
            return skillsDirectories;
        }

        /**
         * 读取缓存Directories。
         *
         * @return 返回读取到的缓存Directories。
         */
        public List<DirectoryMount> getCacheDirectories() {
            return cacheDirectories;
        }

        /**
         * 转换为元数据。
         *
         * @return 返回转换后的元数据。
         */
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

    /** 承载目录Mount相关状态和辅助逻辑。 */
    public static class DirectoryMount {
        /** 记录目录Mount中的主机路径。 */
        private final String hostPath;

        /** 记录目录Mount中的container路径。 */
        private final String containerPath;

        /**
         * 创建Directory Mount实例，并注入运行所需依赖。
         *
         * @param hostPath 文件或目录路径参数。
         * @param containerPath 文件或目录路径参数。
         */
        public DirectoryMount(String hostPath, String containerPath) {
            this.hostPath = hostPath;
            this.containerPath = containerPath;
        }

        /**
         * 读取Host路径。
         *
         * @return 返回读取到的Host路径。
         */
        public String getHostPath() {
            return hostPath;
        }

        /**
         * 读取Container路径。
         *
         * @return 返回读取到的Container路径。
         */
        public String getContainerPath() {
            return containerPath;
        }

        /**
         * 转换为元数据。
         *
         * @return 返回转换后的元数据。
         */
        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("container_path", containerPath);
            return map;
        }
    }

    /** 承载文件Mount相关状态和辅助逻辑。 */
    public static class FileMount {
        /** 记录文件Mount中的主机路径。 */
        private final String hostPath;

        /** 记录文件Mount中的container路径。 */
        private final String containerPath;

        /**
         * 创建文件Mount实例，并注入运行所需依赖。
         *
         * @param hostPath 文件或目录路径参数。
         * @param containerPath 文件或目录路径参数。
         */
        public FileMount(String hostPath, String containerPath) {
            this.hostPath = hostPath;
            this.containerPath = containerPath;
        }

        /**
         * 读取Host路径。
         *
         * @return 返回读取到的Host路径。
         */
        public String getHostPath() {
            return hostPath;
        }

        /**
         * 读取Container路径。
         *
         * @return 返回读取到的Container路径。
         */
        public String getContainerPath() {
            return containerPath;
        }

        /**
         * 转换为元数据。
         *
         * @return 返回转换后的元数据。
         */
        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("container_path", containerPath);
            return map;
        }
    }

    /** 承载凭据文件Mount相关状态和辅助逻辑。 */
    public static class CredentialFileMount {
        /** 记录凭据文件Mount中的relative路径。 */
        private String relativePath;

        /** 记录凭据文件Mount中的主机路径。 */
        private String hostPath;

        /** 记录凭据文件Mount中的container路径。 */
        private String containerPath;

        /** 记录凭据文件Mount中的状态。 */
        private String status;

        /** 记录凭据文件Mount中的原因。 */
        private String reason;

        /**
         * 执行registered相关逻辑。
         *
         * @param relativePath 文件或目录路径参数。
         * @param hostPath 文件或目录路径参数。
         * @param containerPath 文件或目录路径参数。
         * @return 返回registered结果。
         */
        private static CredentialFileMount registered(
                String relativePath, String hostPath, String containerPath) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.hostPath = hostPath;
            mount.containerPath = containerPath;
            mount.status = "registered";
            return mount;
        }

        /**
         * 执行missing相关逻辑。
         *
         * @param relativePath 文件或目录路径参数。
         * @return 返回missing结果。
         */
        private static CredentialFileMount missing(String relativePath) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.status = "missing";
            mount.reason = "file not found";
            return mount;
        }

        /**
         * 执行拒绝相关逻辑。
         *
         * @param relativePath 文件或目录路径参数。
         * @param reason 原因参数。
         * @return 返回拒绝结果。
         */
        private static CredentialFileMount rejected(String relativePath, String reason) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.status = "rejected";
            mount.reason = reason;
            return mount;
        }

        /**
         * 判断是否Registered。
         *
         * @return 如果Registered满足条件则返回 true，否则返回 false。
         */
        public boolean isRegistered() {
            return "registered".equals(status);
        }

        /**
         * 读取Relative路径。
         *
         * @return 返回读取到的Relative路径。
         */
        public String getRelativePath() {
            return relativePath;
        }

        /**
         * 读取Host路径。
         *
         * @return 返回读取到的Host路径。
         */
        public String getHostPath() {
            return hostPath;
        }

        /**
         * 读取Container路径。
         *
         * @return 返回读取到的Container路径。
         */
        public String getContainerPath() {
            return containerPath;
        }

        /**
         * 读取状态。
         *
         * @return 返回读取到的状态。
         */
        public String getStatus() {
            return status;
        }

        /**
         * 读取Reason。
         *
         * @return 返回读取到的Reason。
         */
        public String getReason() {
            return reason;
        }

        /**
         * 转换为元数据。
         *
         * @return 返回转换后的元数据。
         */
        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put(
                    "path",
                    "registered".equals(status)
                            ? relativePath
                            : SecretRedactor.redact(relativePath, 400));
            map.put("container_path", containerPath);
            map.put("status", status);
            map.put("reason", reason);
            return map;
        }
    }
}
