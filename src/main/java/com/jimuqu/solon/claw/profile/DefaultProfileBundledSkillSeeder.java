package com.jimuqu.solon.claw.profile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 基于发行目录或类路径正式资源，为 Profile 同步可更新且不覆盖用户修改的内置技能。 */
final class DefaultProfileBundledSkillSeeder implements ProfileBundledSkillSeeder {
    /** 显式指定正式内置技能目录的 JVM 属性。 */
    static final String BUNDLED_SKILLS_PROPERTY = "solonclaw.bundled.skills";

    /** 显式指定正式内置技能目录的环境变量。 */
    static final String BUNDLED_SKILLS_ENV = "SOLONCLAW_BUNDLED_SKILLS";

    /** 可选运行根目录属性；其 bundled-skills 子目录属于正式发行布局。 */
    private static final String HOME_PROPERTY = "solonclaw.home";

    /** Jar 内正式技能根，避免把 Profile 自身的 skills 目录误当成发行源。 */
    static final String CLASSPATH_SKILLS_ROOT = "META-INF/solonclaw/skills";

    /** Profile 明确拒绝后续内置技能同步的标记。 */
    private static final String NO_BUNDLED_SKILLS_FILE = ".no-bundled-skills";

    /** 记录发行副本原始哈希的清单。 */
    static final String MANIFEST_FILE = ".bundled_manifest";

    /** 技能入口文件名。 */
    private static final String SKILL_FILE = "SKILL.md";

    /** 分类说明文件名；只在目标不存在时复制。 */
    private static final String DESCRIPTION_FILE = "DESCRIPTION.md";

    /** 扫描时必须排除的依赖、缓存和历史目录。 */
    private static final Set<String> EXCLUDED_DIRECTORIES =
            Collections.unmodifiableSet(
                    new HashSet<String>(
                            Arrays.asList(
                                    ".git",
                                    ".github",
                                    ".hub",
                                    ".archive",
                                    ".venv",
                                    "venv",
                                    "node_modules",
                                    "site-packages",
                                    "__pycache__",
                                    ".tox",
                                    ".nox",
                                    ".pytest_cache",
                                    ".mypy_cache",
                                    ".ruff_cache")));

    /** 技能包内部渐进加载目录，其中出现的 SKILL.md 不能被识别成独立技能。 */
    private static final Set<String> SUPPORT_DIRECTORIES =
            Collections.unmodifiableSet(
                    new HashSet<String>(
                            Arrays.asList("references", "templates", "assets", "scripts")));

    /** 正式技能源定位策略。 */
    private final SourceResolver sourceResolver;

    /** 创建采用指定定位策略的同步器。 */
    private DefaultProfileBundledSkillSeeder(SourceResolver sourceResolver) {
        if (sourceResolver == null) {
            throw new IllegalArgumentException("Bundled skill source resolver is required.");
        }
        this.sourceResolver = sourceResolver;
    }

    /** 创建按生产发行布局发现正式技能源的同步器。 */
    static ProfileBundledSkillSeeder discover() {
        return new DefaultProfileBundledSkillSeeder(new DistributionSourceResolver());
    }

    /** 创建固定目录同步器。 */
    static ProfileBundledSkillSeeder fromDirectory(Path bundledSkillsRoot) {
        if (bundledSkillsRoot == null) {
            throw new IllegalArgumentException("Bundled skills root is required.");
        }
        return new DefaultProfileBundledSkillSeeder(
                new FixedSourceResolver(bundledSkillsRoot.toAbsolutePath().normalize()));
    }

    /** 创建只从指定类加载器发现正式技能资源的同步器。 */
    static ProfileBundledSkillSeeder fromClasspath(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new IllegalArgumentException("Bundled skill class loader is required.");
        }
        return new DefaultProfileBundledSkillSeeder(new ClasspathSourceResolver(classLoader));
    }

    /**
     * 同步发行技能；清单只授权覆盖仍与上次发行哈希一致的副本。
     *
     * @param profileHome 目标 Profile 工作区。
     * @return 本次新增或更新的技能规范名。
     * @throws Exception 正式资源无效或文件同步失败。
     */
    @Override
    public List<String> seed(Path profileHome) throws Exception {
        if (profileHome == null) {
            throw new IllegalArgumentException("Profile home is required.");
        }
        Path normalizedHome = profileHome.toAbsolutePath().normalize();
        if (Files.exists(normalizedHome.resolve(NO_BUNDLED_SKILLS_FILE), LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyList();
        }
        try (SourceHandle source = sourceResolver.resolve()) {
            if (source == null || !Files.isDirectory(source.root, LinkOption.NOFOLLOW_LINKS)) {
                return Collections.emptyList();
            }
            return synchronize(source.root, normalizedHome);
        }
    }

    /** 对一个已解析的正式技能根执行清单同步。 */
    private List<String> synchronize(Path sourceRoot, Path profileHome) throws Exception {
        Path skillsRoot = profileHome.resolve("skills").normalize();
        requireStrictChild(profileHome, skillsRoot, "Profile skills root");
        List<BundledSkill> bundledSkills = discoverSkills(sourceRoot);
        List<Path> descriptions = discoverDescriptions(sourceRoot);
        if (bundledSkills.isEmpty() && descriptions.isEmpty()) {
            return Collections.emptyList();
        }

        Files.createDirectories(skillsRoot);
        Path manifestPath = skillsRoot.resolve(MANIFEST_FILE);
        Map<String, String> manifest = readManifest(manifestPath);
        Set<String> currentEntries = new LinkedHashSet<String>();
        List<String> changed = new ArrayList<String>();

        for (BundledSkill skill : bundledSkills) {
            String relativeKey = toManifestKey(skill.relativePath);
            currentEntries.add(relativeKey);
            Path destination = skillsRoot.resolve(toManifestKey(skill.relativePath)).normalize();
            requireStrictChild(skillsRoot, destination, "Bundled skill destination");
            String bundledHash = hashDirectory(skill.sourceDirectory);
            String originHash = manifest.get(relativeKey);

            if (originHash == null) {
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    replaceDirectory(skill.sourceDirectory, destination, false);
                    manifest.put(relativeKey, bundledHash);
                    changed.add(skill.name);
                } else if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                        && bundledHash.equals(hashDirectory(destination))) {
                    manifest.put(relativeKey, bundledHash);
                }
                continue;
            }

            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                // 清单中存在但目标缺失表示用户主动删除，后续更新不能复活该技能。
                continue;
            }
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                // 同名用户文件不属于发行副本，始终保留。
                continue;
            }

            String userHash;
            try {
                userHash = hashDirectory(destination);
            } catch (IOException unsafeUserCopy) {
                // 用户副本含链接或特殊文件时无法安全计算哈希，按已修改处理并完整保留。
                continue;
            }
            if (originHash.length() == 0) {
                // 旧清单没有来源哈希时，以当前副本为基线，避免猜测并覆盖用户内容。
                manifest.put(relativeKey, userHash);
                continue;
            }
            if (!originHash.equals(userHash)) {
                // 用户已修改发行副本；即使新版发行内容改变也不得覆盖。
                continue;
            }
            if (!originHash.equals(bundledHash)) {
                replaceDirectory(skill.sourceDirectory, destination, true);
                manifest.put(relativeKey, bundledHash);
                changed.add(skill.name);
            }
        }

        manifest.keySet().retainAll(currentEntries);
        copyMissingDescriptions(sourceRoot, descriptions, skillsRoot);
        writeManifest(manifestPath, manifest);
        return Collections.unmodifiableList(changed);
    }

    /** 查找正式技能入口并保留分类相对路径。 */
    private List<BundledSkill> discoverSkills(Path sourceRoot) throws IOException {
        final List<BundledSkill> skills = new ArrayList<BundledSkill>();
        Files.walkFileTree(
                sourceRoot,
                new SimpleFileVisitor<Path>() {
                    /** 跳过依赖、缓存和版本控制目录。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                            throws IOException {
                        if (!directory.equals(sourceRoot)
                                && EXCLUDED_DIRECTORIES.contains(
                                        directory.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (attrs.isSymbolicLink()) {
                            throw new IOException(
                                    "Bundled skills cannot contain symbolic links: " + directory);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /** 将非支持目录中的 SKILL.md 识别为正式技能根。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (attrs.isSymbolicLink()) {
                            throw new IOException(
                                    "Bundled skills cannot contain symbolic links: " + file);
                        }
                        if (attrs.isRegularFile()
                                && SKILL_FILE.equals(file.getFileName().toString())
                                && !isNestedSupportSkill(file.getParent(), sourceRoot)) {
                            Path skillDirectory = file.getParent();
                            Path relative = sourceRoot.relativize(skillDirectory).normalize();
                            validateRelativePath(relative, "Bundled skill path");
                            skills.add(
                                    new BundledSkill(
                                            readSkillName(file, skillDirectory.getFileName().toString()),
                                            skillDirectory,
                                            relative));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        Collections.sort(
                skills,
                new Comparator<BundledSkill>() {
                    /** 使用稳定相对路径确保同步与清单输出可复现。 */
                    @Override
                    public int compare(BundledSkill left, BundledSkill right) {
                        return toManifestKey(left.relativePath)
                                .compareTo(toManifestKey(right.relativePath));
                    }
                });
        return skills;
    }

    /** 查找分类说明文件；说明只补缺，不参与发行副本覆盖。 */
    private List<Path> discoverDescriptions(Path sourceRoot) throws IOException {
        final List<Path> descriptions = new ArrayList<Path>();
        Files.walkFileTree(
                sourceRoot,
                new SimpleFileVisitor<Path>() {
                    /** 跳过不属于正式发行内容的目录。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                            throws IOException {
                        if (!directory.equals(sourceRoot)
                                && EXCLUDED_DIRECTORIES.contains(
                                        directory.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (attrs.isSymbolicLink()) {
                            throw new IOException(
                                    "Bundled skills cannot contain symbolic links: " + directory);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /** 收集普通分类说明文件。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (attrs.isSymbolicLink()) {
                            throw new IOException(
                                    "Bundled skills cannot contain symbolic links: " + file);
                        }
                        if (attrs.isRegularFile()
                                && DESCRIPTION_FILE.equals(file.getFileName().toString())) {
                            Path relative = sourceRoot.relativize(file).normalize();
                            validateRelativePath(relative, "Bundled skill description path");
                            descriptions.add(relative);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        Collections.sort(
                descriptions,
                new Comparator<Path>() {
                    /** 使用稳定路径顺序复制分类说明。 */
                    @Override
                    public int compare(Path left, Path right) {
                        return toManifestKey(left).compareTo(toManifestKey(right));
                    }
                });
        return descriptions;
    }

    /** 判断候选技能是否位于另一个技能包的渐进加载支持目录内。 */
    private boolean isNestedSupportSkill(Path skillDirectory, Path sourceRoot) {
        Path current = skillDirectory;
        while (current != null && !current.equals(sourceRoot)) {
            Path parent = current.getParent();
            if (parent == null || parent.equals(sourceRoot)) {
                return false;
            }
            if (SUPPORT_DIRECTORIES.contains(current.getFileName().toString())
                    && Files.isRegularFile(parent.resolve(SKILL_FILE), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    /** 从技能入口 YAML 前置区读取规范名，格式无效时使用目录名。 */
    private String readSkillName(Path skillFile, String fallback) {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                Files.newInputStream(skillFile), StandardCharsets.UTF_8))) {
            boolean frontmatter = false;
            int consumed = 0;
            String line;
            while ((line = reader.readLine()) != null && consumed < 4000) {
                consumed += line.length() + 1;
                String trimmed = line.trim();
                if ("---".equals(trimmed)) {
                    if (frontmatter) {
                        break;
                    }
                    frontmatter = true;
                    continue;
                }
                if (frontmatter && trimmed.startsWith("name:")) {
                    String value = trimmed.substring("name:".length()).trim();
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1).trim();
                    }
                    if (value.length() > 0) {
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
            // 名称只用于用户可见结果，读取失败不影响安全的目录同步。
        }
        return fallback;
    }

    /** 复制目标中尚不存在的分类说明，永远不覆盖用户修改。 */
    private void copyMissingDescriptions(
            Path sourceRoot, List<Path> descriptions, Path skillsRoot) throws IOException {
        for (Path relative : descriptions) {
            Path destination = skillsRoot.resolve(relative).normalize();
            requireStrictChild(skillsRoot, destination, "Bundled skill description destination");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.createDirectories(destination.getParent());
            try {
                Files.copy(sourceRoot.resolve(relative), destination);
            } catch (FileAlreadyExistsException ignored) {
                // 并发同步中另一调用已补齐说明时保留现有文件。
            }
        }
    }

    /** 读取路径到来源哈希的清单，并兼容没有哈希的旧行。 */
    private Map<String, String> readManifest(Path manifestPath) throws IOException {
        Map<String, String> manifest = new LinkedHashMap<String, String>();
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return manifest;
        }
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            String key = separator < 0 ? line : line.substring(0, separator).trim();
            String hash = separator < 0 ? "" : line.substring(separator + 1).trim();
            if (isSafeManifestKey(key)) {
                manifest.put(key, hash.toLowerCase(Locale.ROOT));
            }
        }
        return manifest;
    }

    /** 以稳定顺序和原子替换写入清单。 */
    private void writeManifest(Path manifestPath, Map<String, String> manifest) throws IOException {
        List<String> keys = new ArrayList<String>(manifest.keySet());
        Collections.sort(keys);
        StringBuilder content = new StringBuilder();
        for (String key : keys) {
            content.append(key).append(':').append(manifest.get(key)).append('\n');
        }
        Files.createDirectories(manifestPath.getParent());
        Path temporary =
                manifestPath.resolveSibling(
                        "." + MANIFEST_FILE + "." + UUID.randomUUID().toString() + ".tmp");
        try {
            Files.write(
                    temporary,
                    content.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            moveReplacing(temporary, manifestPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 对技能目录的稳定相对路径与全部普通文件内容计算 SHA-256。 */
    private String hashDirectory(Path directory) throws IOException {
        final List<Path> files = new ArrayList<Path>();
        Files.walkFileTree(
                directory,
                new SimpleFileVisitor<Path>() {
                    /** 哈希源和用户副本时都拒绝链接，避免读取技能根外内容。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attrs)
                            throws IOException {
                        if (attrs.isSymbolicLink()) {
                            throw new IOException("Skill directory cannot contain symbolic links: " + current);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    /** 收集普通文件并拒绝链接和特殊文件。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (attrs.isSymbolicLink()) {
                            throw new IOException("Skill directory cannot contain symbolic links: " + file);
                        }
                        if (!attrs.isRegularFile()) {
                            throw new IOException("Skill directory contains unsupported file: " + file);
                        }
                        files.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
        Collections.sort(
                files,
                new Comparator<Path>() {
                    /** 文件相对路径决定哈希输入顺序。 */
                    @Override
                    public int compare(Path left, Path right) {
                        return toManifestKey(directory.relativize(left))
                                .compareTo(toManifestKey(directory.relativize(right)));
                    }
                });
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        for (Path file : files) {
            byte[] relative =
                    toManifestKey(directory.relativize(file)).getBytes(StandardCharsets.UTF_8);
            updateLength(digest, relative.length);
            digest.update(relative);
            try (java.io.InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
        }
        return toHex(digest.digest());
    }

    /** 复制到同层临时目录后替换目标，并在失败时恢复原副本。 */
    private void replaceDirectory(Path source, Path destination, boolean replaceExisting)
            throws IOException {
        Files.createDirectories(destination.getParent());
        String nonce = UUID.randomUUID().toString();
        Path staging = destination.resolveSibling("." + destination.getFileName() + "." + nonce + ".tmp");
        Path backup = destination.resolveSibling("." + destination.getFileName() + "." + nonce + ".bak");
        try {
            copyDirectory(source, staging);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!replaceExisting) {
                    return;
                }
                moveWithoutReplacement(destination, backup);
            }
            try {
                moveWithoutReplacement(staging, destination);
            } catch (IOException failure) {
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                        && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    moveWithoutReplacement(backup, destination);
                }
                throw failure;
            }
            deleteTree(backup);
        } finally {
            deleteTree(staging);
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                moveWithoutReplacement(backup, destination);
            }
            deleteTree(backup);
        }
    }

    /** 复制普通目录树，拒绝任何链接或特殊文件。 */
    private void copyDirectory(final Path source, final Path destination) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<Path>() {
                    /** 创建对应目录并拒绝链接。 */
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                            throws IOException {
                        if (attrs.isSymbolicLink()) {
                            throw new IOException(
                                    "Bundled skills cannot contain symbolic links: " + directory);
                        }
                        Path relative = source.relativize(directory);
                        Files.createDirectories(destination.resolve(toManifestKey(relative)));
                        return FileVisitResult.CONTINUE;
                    }

                    /** 复制普通文件并拒绝链接或设备文件。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                            throw new IOException("Bundled skill contains unsupported file: " + file);
                        }
                        Files.copy(
                                file,
                                destination.resolve(toManifestKey(source.relativize(file))));
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 删除本同步器创建的同层临时目录。 */
    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<Path>() {
                    /** 删除普通文件或链接本身。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    /** 子项删除后删除目录。 */
                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error)
                            throws IOException {
                        if (error != null) {
                            throw error;
                        }
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 使用原子移动，文件系统不支持时回退到同盘普通移动。 */
    private void moveWithoutReplacement(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    /** 使用原子替换，文件系统不支持时回退到普通替换。 */
    private void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 验证路径是指定根的严格子路径。 */
    private void requireStrictChild(Path root, Path candidate, String label) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (normalizedCandidate.equals(normalizedRoot)
                || !normalizedCandidate.startsWith(normalizedRoot)) {
            throw new IOException(label + " escapes its root: " + candidate);
        }
    }

    /** 验证技能源相对路径非空且不含穿越片段。 */
    private void validateRelativePath(Path relative, String label) throws IOException {
        if (relative == null || relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IOException(label + " must be a non-empty relative path.");
        }
        for (Path part : relative) {
            String value = part.toString();
            if (value.length() == 0 || ".".equals(value) || "..".equals(value)) {
                throw new IOException(label + " contains an unsafe segment: " + relative);
            }
        }
    }

    /** 判断清单键能否安全地映射到 skills 根下。 */
    private boolean isSafeManifestKey(String key) {
        if (key == null || key.length() == 0 || key.indexOf('\\') >= 0) {
            return false;
        }
        try {
            Path relative = Paths.get(key);
            if (relative.isAbsolute() || relative.getNameCount() == 0) {
                return false;
            }
            for (Path part : relative) {
                if ("..".equals(part.toString()) || ".".equals(part.toString())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 将平台路径统一成清单中的正斜杠格式。 */
    private static String toManifestKey(Path path) {
        StringBuilder value = new StringBuilder();
        for (Path part : path) {
            if (value.length() > 0) {
                value.append('/');
            }
            value.append(part.toString());
        }
        return value.toString();
    }

    /** 创建 SHA-256 摘要器。 */
    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    /** 给摘要写入固定四字节长度，隔离路径边界。 */
    private void updateLength(MessageDigest digest, int length) {
        digest.update((byte) ((length >>> 24) & 0xff));
        digest.update((byte) ((length >>> 16) & 0xff));
        digest.update((byte) ((length >>> 8) & 0xff));
        digest.update((byte) (length & 0xff));
    }

    /** 把摘要转为小写十六进制。 */
    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    /** 读取非空系统属性。 */
    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null || value.trim().length() == 0 ? null : value.trim();
    }

    /** 读取非空环境变量。 */
    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.trim().length() == 0 ? null : value.trim();
    }

    /** 返回当前代码来源所在目录，开发 class 目录不能误判成发行目录。 */
    private static Path codeSourceBase() {
        try {
            URL location =
                    DefaultProfileBundledSkillSeeder.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
                return null;
            }
            Path codeSource = Paths.get(location.toURI()).toAbsolutePath().normalize();
            return Files.isRegularFile(codeSource, LinkOption.NOFOLLOW_LINKS)
                    ? codeSource.getParent()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 一个已发现的正式技能及其用户可见名称和分类相对路径。 */
    private static final class BundledSkill {
        /** 技能 frontmatter 名或目录名。 */
        private final String name;

        /** 正式发行源目录。 */
        private final Path sourceDirectory;

        /** 相对正式技能根的安装路径。 */
        private final Path relativePath;

        /** 保存一个正式技能描述。 */
        private BundledSkill(String name, Path sourceDirectory, Path relativePath) {
            this.name = name;
            this.sourceDirectory = sourceDirectory;
            this.relativePath = relativePath;
        }
    }

    /** 正式技能源定位接口。 */
    private interface SourceResolver {
        /**
         * 定位本次同步使用的正式技能根。
         *
         * @return 可关闭的路径句柄；没有正式资源时返回 null。
         * @throws Exception 路径或 Jar 文件系统解析失败。
         */
        SourceHandle resolve() throws Exception;
    }

    /** 固定目录定位器。 */
    private static final class FixedSourceResolver implements SourceResolver {
        /** 固定正式技能根。 */
        private final Path root;

        /** 保存固定技能根。 */
        private FixedSourceResolver(Path root) {
            this.root = root;
        }

        /** 返回无需关闭的固定路径句柄。 */
        @Override
        public SourceHandle resolve() {
            return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    ? new SourceHandle(root, null, false)
                    : null;
        }
    }

    /** 生产发行布局定位器。 */
    private static final class DistributionSourceResolver implements SourceResolver {
        /** 按显式配置、运行包目录、类路径顺序定位正式技能根。 */
        @Override
        public SourceHandle resolve() throws Exception {
            String override = property(BUNDLED_SKILLS_PROPERTY);
            if (override == null) {
                override = environment(BUNDLED_SKILLS_ENV);
            }
            if (override != null) {
                Path configured = Paths.get(override).toAbsolutePath().normalize();
                return Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)
                        ? new SourceHandle(configured, null, false)
                        : null;
            }

            List<Path> candidates = new ArrayList<Path>();
            String home = property(HOME_PROPERTY);
            if (home != null) {
                candidates.add(Paths.get(home).toAbsolutePath().normalize().resolve("bundled-skills"));
            }
            Path codeSourceBase = codeSourceBase();
            if (codeSourceBase != null) {
                candidates.add(codeSourceBase.resolve("bundled-skills"));
            }
            for (Path candidate : candidates) {
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return new SourceHandle(candidate, null, false);
                }
            }
            ClassLoader classLoader = DefaultProfileBundledSkillSeeder.class.getClassLoader();
            return new ClasspathSourceResolver(classLoader).resolve();
        }
    }

    /** 普通 classpath 目录和 Jar 资源定位器。 */
    private static final class ClasspathSourceResolver implements SourceResolver {
        /** 提供正式资源的类加载器。 */
        private final ClassLoader classLoader;

        /** 保存待查询类加载器。 */
        private ClasspathSourceResolver(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        /** 返回第一个有效的正式类路径技能根。 */
        @Override
        public SourceHandle resolve() throws Exception {
            Enumeration<URL> resources = classLoader.getResources(CLASSPATH_SKILLS_ROOT);
            while (resources.hasMoreElements()) {
                SourceHandle handle = open(resources.nextElement());
                if (handle != null && Files.isDirectory(handle.root, LinkOption.NOFOLLOW_LINKS)) {
                    return handle;
                }
                if (handle != null) {
                    handle.close();
                }
            }
            return null;
        }

        /** 把 file 或 jar URL 转换为 NIO 路径句柄。 */
        private SourceHandle open(URL resource) throws Exception {
            if (resource == null) {
                return null;
            }
            URI uri = resource.toURI();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return new SourceHandle(Paths.get(uri).toAbsolutePath().normalize(), null, false);
            }
            if (!"jar".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            FileSystem fileSystem;
            boolean owned = false;
            try {
                fileSystem = FileSystems.newFileSystem(uri, new HashMap<String, Object>());
                owned = true;
            } catch (FileSystemAlreadyExistsException e) {
                fileSystem = FileSystems.getFileSystem(uri);
            }
            Path root = fileSystem.getPath("/" + CLASSPATH_SKILLS_ROOT).normalize();
            return new SourceHandle(root, fileSystem, owned);
        }
    }

    /** 正式技能根及其可选 Jar 文件系统所有权。 */
    private static final class SourceHandle implements AutoCloseable {
        /** 正式技能根路径。 */
        private final Path root;

        /** Jar 文件系统；普通目录时为空。 */
        private final FileSystem fileSystem;

        /** 是否由当前句柄创建并负责关闭文件系统。 */
        private final boolean owned;

        /** 保存路径与文件系统生命周期。 */
        private SourceHandle(Path root, FileSystem fileSystem, boolean owned) {
            this.root = root;
            this.fileSystem = fileSystem;
            this.owned = owned;
        }

        /** 仅关闭当前句柄创建的 Jar 文件系统。 */
        @Override
        public void close() throws IOException {
            if (owned && fileSystem != null && fileSystem.isOpen()) {
                fileSystem.close();
            }
        }
    }
}
