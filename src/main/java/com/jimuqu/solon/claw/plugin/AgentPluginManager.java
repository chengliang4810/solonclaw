package com.jimuqu.solon.claw.plugin;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.noear.liquor.DynamicCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** 插件生命周期管理：发现、编译、加载、卸载。 */
public class AgentPluginManager {
    /** 插件发现、编译和销毁过程的日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentPluginManager.class);

    /** 保护已挂载内置插件归档的引用计数，避免多 Profile 运行时互相关闭 ZIP 文件系统。 */
    private static final Object BUNDLED_ARCHIVE_LOCK = new Object();

    /** 已挂载内置插件归档的共享租约，归档只会在最后一个使用者退出时关闭。 */
    private static final Map<URI, BundledArchiveLease> BUNDLED_ARCHIVES = new HashMap<>();

    /** 插件注册钩子的共享注册表，随插件管理器生命周期一起清理。 */
    private final AgentHookRegistry hookRegistry;

    /** 已成功加载的插件实例，key 为插件名称。 */
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();

    /** 用户插件根目录，目录下每个子目录代表一个插件。 */
    private final Path userPluginsDir;

    /** 允许按需启用的 standalone 插件名称集合。 */
    private final Set<String> enabledPlugins;

    /** 显式禁用的插件名称集合，优先级高于 manifest 中的 enabled。 */
    private final Set<String> disabledPlugins;

    /** 是否扫描打包在 classpath 中的内置插件。 */
    private final boolean loadBundledPlugins;

    /** 本轮发现和加载产生的诊断记录，保持扫描顺序便于前端展示。 */
    private final List<PluginLoadDiagnostic> diagnostics = new ArrayList<>();

    /** 串行化单个管理器的发现、卸载和归档租约释放，避免并发重复注册 Hook。 */
    private final Object lifecycleLock = new Object();

    /** 当前管理器已持有的内置插件归档租约；同一归档每个管理器只持有一次。 */
    private final Set<URI> bundledArchiveLeases = new LinkedHashSet<>();

    /** 标记当前管理器是否已完成一次发现；重复调用保持幂等，避免重复注册 Hook。 */
    private boolean discovered;

    /** 标记当前管理器是否已关闭；关闭后不允许重新发现或再次释放租约。 */
    private boolean shutdown;

    /**
     * 创建Agent插件管理器实例，并注入运行所需依赖。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     * @param enabledPlugins 允许运行的 standalone 插件名称集合。
     * @param disabledPlugins 显式禁用的插件名称集合。
     */
    public AgentPluginManager(
            AgentHookRegistry hookRegistry,
            Set<String> enabledPlugins,
            Set<String> disabledPlugins) {
        this(hookRegistry, enabledPlugins, disabledPlugins, defaultPluginRoot(), true);
    }

    /** 返回当前 Profile 的默认插件目录，未进入 Profile 作用域时使用默认工作区。 */
    private static Path defaultPluginRoot() {
        ProfileRuntimeScope.Context current = ProfileRuntimeScope.current();
        if (current != null && current.getHome() != null) {
            return current.getHome().resolve("plugins").toAbsolutePath().normalize();
        }
        return Paths.get(RuntimePathConstants.WORKSPACE_HOME, "plugins")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * @return 当前管理器扫描的 Profile 用户插件目录。
     */
    public Path getUserPluginsDir() {
        return userPluginsDir;
    }

    /**
     * 创建Agent插件管理器实例，并注入运行所需依赖。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     * @param enabledPlugins 允许运行的 standalone 插件名称集合。
     * @param disabledPlugins 显式禁用的插件名称集合。
     * @param pluginRoot 用户插件根目录。
     */
    public AgentPluginManager(
            AgentHookRegistry hookRegistry,
            Set<String> enabledPlugins,
            Set<String> disabledPlugins,
            Path pluginRoot) {
        this(hookRegistry, enabledPlugins, disabledPlugins, pluginRoot, false);
    }

    /**
     * 创建Agent插件管理器实例，并注入运行所需依赖。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     * @param enabledPlugins 允许运行的 standalone 插件名称集合。
     * @param disabledPlugins 显式禁用的插件名称集合。
     * @param pluginRoot 用户插件根目录。
     * @param loadBundledPlugins 是否同时加载内置插件目录。
     */
    public AgentPluginManager(
            AgentHookRegistry hookRegistry,
            Set<String> enabledPlugins,
            Set<String> disabledPlugins,
            Path pluginRoot,
            boolean loadBundledPlugins) {
        this.hookRegistry = hookRegistry;
        this.enabledPlugins = enabledPlugins != null ? enabledPlugins : Collections.emptySet();
        this.disabledPlugins = disabledPlugins != null ? disabledPlugins : Collections.emptySet();
        this.userPluginsDir = pluginRoot;
        this.loadBundledPlugins = loadBundledPlugins;
    }

    /**
     * 读取共享钩子注册表。
     *
     * @return 插件注册和运行时桥接共用的钩子注册表。
     */
    public AgentHookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /**
     * 扫描内置和用户插件目录，并按启用策略完成加载。
     *
     * @param sink 主应用提供的注册接收器，用于接收工具、Provider 和平台适配器。
     */
    public void discoverAndLoad(PluginRegistrationSink sink) {
        synchronized (lifecycleLock) {
            if (shutdown || discovered) {
                return;
            }
            diagnostics.clear();
            List<AgentPluginManifest> manifests = new ArrayList<>();
            if (loadBundledPlugins) {
                discoverBundled(manifests);
            }
            discoverUserPlugins(manifests);
            manifests.sort(Comparator.comparing(m -> m.getDirectory().toString()));
            Set<String> seenPluginNames = new LinkedHashSet<>();

            for (AgentPluginManifest manifest : manifests) {
                if (!seenPluginNames.add(manifest.getName())) {
                    diagnostics.add(
                            diagnostic(
                                    manifest,
                                    PluginLoadStatus.SKIPPED,
                                    "duplicate_plugin_name",
                                    "Plugin name already loaded: " + manifest.getName()));
                    continue;
                }
                if (disabledPlugins.contains(manifest.getName())) {
                    diagnostics.add(
                            diagnostic(
                                    manifest,
                                    PluginLoadStatus.SKIPPED,
                                    "disabled_by_configuration",
                                    "Plugin disabled by configuration: " + manifest.getName()));
                    log.debug("Plugin '{}' is disabled, skipping", manifest.getName());
                    continue;
                }
                if (!manifest.isEnabled()) {
                    diagnostics.add(
                            diagnostic(
                                    manifest,
                                    PluginLoadStatus.SKIPPED,
                                    "disabled_by_manifest",
                                    "Plugin disabled by manifest: " + manifest.getName()));
                    continue;
                }
                if (!manifest.isAutoLoad() && !enabledPlugins.contains(manifest.getName())) {
                    diagnostics.add(
                            diagnostic(
                                    manifest,
                                    PluginLoadStatus.SKIPPED,
                                    "not_enabled",
                                    "Plugin is not enabled: " + manifest.getName()));
                    log.debug(
                            "Plugin '{}' is standalone and not enabled, skipping", manifest.getName());
                    continue;
                }
                String missingEnv = firstMissingEnv(manifest);
                if (missingEnv != null) {
                    diagnostics.add(
                            diagnostic(
                                    manifest,
                                    PluginLoadStatus.SKIPPED,
                                    "missing_required_env",
                                    "Missing required environment variable: " + missingEnv));
                    continue;
                }
                loadPlugin(manifest, sink);
            }
            discovered = true;
            log.info("Loaded {} plugin(s)", loadedPlugins.size());
        }
    }

    /**
     * 扫描 classpath 中打包的内置插件目录。
     *
     * @param manifests 承接解析成功的插件清单。
     */
    private void discoverBundled(List<AgentPluginManifest> manifests) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            java.net.URL pluginsUrl = cl.getResource("plugins");
            if (pluginsUrl == null) {
                diagnostics.add(
                        diagnostic(
                                null,
                                PluginLoadStatus.FAILED,
                                "bundled_plugins_missing",
                                "Bundled plugin directory is missing from classpath"));
                return;
            }
            Path pluginsPath = bundledPluginsPath(pluginsUrl);
            scanDirectory(pluginsPath, "bundled", manifests);
        } catch (Exception e) {
            diagnostics.add(
                    diagnostic(
                            null,
                            PluginLoadStatus.FAILED,
                            "bundled_discovery_failed",
                            "Bundled plugin discovery failed: " + safeError(e)));
            log.warn("Bundled plugin discovery failed: {}", safeError(e));
        }
    }

    /**
     * 返回内置插件目录。开发目录直接使用文件路径；发布 Jar 则挂载 ZIP 文件系统，避免发布后内置插件全部失效。
     *
     * @param pluginsUrl Classpath 中 plugins 目录的地址。
     * @return 可遍历的插件目录路径。
     */
    private Path bundledPluginsPath(java.net.URL pluginsUrl) throws Exception {
        if (!"jar".equalsIgnoreCase(pluginsUrl.getProtocol())) {
            return Paths.get(pluginsUrl.toURI());
        }
        JarURLConnection connection = (JarURLConnection) pluginsUrl.openConnection();
        URI archiveUri = URI.create("jar:" + connection.getJarFileURL().toURI().toString());
        if (bundledArchiveLeases.add(archiveUri)) {
            try {
                acquireBundledArchive(archiveUri);
            } catch (IOException e) {
                bundledArchiveLeases.remove(archiveUri);
                throw e;
            }
        }
        return bundledArchive(archiveUri).getPath("/plugins");
    }

    /** 获取内置归档租约；同一归档在多个 Profile 间共享，最后一个释放者才关闭自建文件系统。 */
    private static void acquireBundledArchive(URI archiveUri) throws IOException {
        synchronized (BUNDLED_ARCHIVE_LOCK) {
            BundledArchiveLease lease = BUNDLED_ARCHIVES.get(archiveUri);
            if (lease == null || !lease.fileSystem.isOpen()) {
                lease = openBundledArchive(archiveUri);
                BUNDLED_ARCHIVES.put(archiveUri, lease);
            }
            lease.references++;
        }
    }

    /** 获取已持有的内置归档文件系统。 */
    private static FileSystem bundledArchive(URI archiveUri) {
        synchronized (BUNDLED_ARCHIVE_LOCK) {
            BundledArchiveLease lease = BUNDLED_ARCHIVES.get(archiveUri);
            if (lease == null || !lease.fileSystem.isOpen()) {
                throw new IllegalStateException("Bundled plugin archive is unavailable");
            }
            return lease.fileSystem;
        }
    }

    /** 打开内置归档；外部已挂载的归档只借用，不由本组件关闭。 */
    private static BundledArchiveLease openBundledArchive(URI archiveUri) throws IOException {
        try {
            return new BundledArchiveLease(FileSystems.getFileSystem(archiveUri), false);
        } catch (java.nio.file.FileSystemNotFoundException ignored) {
            try {
                return new BundledArchiveLease(
                        FileSystems.newFileSystem(archiveUri, Collections.<String, Object>emptyMap()),
                        true);
            } catch (java.nio.file.FileSystemAlreadyExistsException raced) {
                return new BundledArchiveLease(FileSystems.getFileSystem(archiveUri), false);
            }
        }
    }

    /**
     * 扫描用户插件目录。
     *
     * @param manifests 承接解析成功的插件清单。
     */
    private void discoverUserPlugins(List<AgentPluginManifest> manifests) {
        if (!Files.isDirectory(userPluginsDir)) {
            return;
        }
        scanDirectory(userPluginsDir, "user", manifests);
    }

    /**
     * 扫描单个插件根目录下的一层子目录并解析 plugin.yaml/plugin.yml。
     *
     * @param dir 插件根目录。
     * @param source 插件来源标识，如 bundled 或 user。
     * @param manifests 承接解析成功的插件清单。
     */
    private void scanDirectory(Path dir, String source, List<AgentPluginManifest> manifests) {
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .forEach(
                            child -> {
                                Path yamlFile = child.resolve("plugin.yaml");
                                Path ymlFile = child.resolve("plugin.yml");
                                Path manifestFile =
                                        Files.exists(yamlFile)
                                                ? yamlFile
                                                : (Files.exists(ymlFile) ? ymlFile : null);
                                if (manifestFile == null) {
                                    return;
                                }
                                try {
                                    AgentPluginManifest manifest =
                                            parseManifest(manifestFile, child, source);
                                    if (manifest != null) {
                                        manifests.add(manifest);
                                    }
                                } catch (Exception e) {
                                    log.warn(
                                            "Failed to parse plugin manifest at {}: {}",
                                            manifestFile,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.warn("Failed to scan plugin directory {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 解析插件清单文件，使用 snakeyaml 加载后再映射到 AgentPluginManifest。
     *
     * <p>字段映射与原手写解析保持一致：顶层标量取字符串值；布尔字段（enabled、env.secret）沿用 Boolean.parseBoolean
     * 语义；列表字段保留出现顺序。name/kind/enabled 维持原有默认值。
     *
     * @param manifestFile plugin.yaml 或 plugin.yml 路径。
     * @param pluginDir 插件所在目录。
     * @param source 插件来源标识。
     * @return 解析后的插件清单。
     */
    @SuppressWarnings("unchecked")
    private AgentPluginManifest parseManifest(Path manifestFile, Path pluginDir, String source)
            throws IOException {
        String content = readUtf8(manifestFile);
        Object root = new Yaml().load(content);
        Map<String, Object> props =
                root instanceof Map ? (Map<String, Object>) root : Collections.emptyMap();

        AgentPluginManifest manifest = new AgentPluginManifest();
        manifest.setName(
                props.containsKey("name")
                        ? scalar(props.get("name"))
                        : pluginDir.getFileName().toString());
        manifest.setVersion(scalar(props.get("version")));
        manifest.setDescription(scalar(props.get("description")));
        manifest.setAuthor(scalar(props.get("author")));
        manifest.setKind(props.containsKey("kind") ? scalar(props.get("kind")) : "standalone");
        manifest.setEnabled(
                props.containsKey("enabled")
                        ? Boolean.parseBoolean(scalar(props.get("enabled")))
                        : true);
        manifest.setEntry(scalar(props.get("entry")));
        manifest.setProvidesTools(parseStringList(props.get("providesTools")));
        manifest.setRequiresEnv(parseEnvRequirements(props.get("requiresEnv")));
        manifest.setSource(source);
        manifest.setDirectory(pluginDir);
        return manifest;
    }

    /**
     * 解析 providesTools 字符串列表块。
     *
     * @param value YAML 解析出的 providesTools 节点。
     * @return 按顺序保留的字符串值。
     */
    private List<String> parseStringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (Object item : (List<?>) value) {
            values.add(scalar(item));
        }
        return values;
    }

    /**
     * 解析 requiresEnv 环境变量要求列表。
     *
     * @param value YAML 解析出的 requiresEnv 节点。
     * @return 按清单顺序保留的环境变量要求。
     */
    private List<AgentPluginManifest.EnvRequirement> parseEnvRequirements(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<AgentPluginManifest.EnvRequirement> requirements = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> fields = (Map<?, ?>) item;
            AgentPluginManifest.EnvRequirement requirement =
                    new AgentPluginManifest.EnvRequirement();
            requirement.setName(scalar(fields.get("name")));
            requirement.setDescription(scalar(fields.get("description")));
            Object secret = fields.get("secret");
            requirement.setSecret(secret != null && Boolean.parseBoolean(scalar(secret)));
            requirements.add(requirement);
        }
        return requirements;
    }

    /**
     * 将 YAML 标量转为字符串，保持与原手写解析一致的取值。
     *
     * @param value YAML 解析出的标量值。
     * @return 字符串形式或 null。
     */
    private String scalar(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 编译插件 Java 源码，实例化 AgentPlugin 并注册到主应用。
     *
     * @param manifest 已通过启用策略和环境变量检查的插件清单。
     * @param sink 主应用注册接收器。
     */
    private void loadPlugin(AgentPluginManifest manifest, PluginRegistrationSink sink) {
        Path dir = manifest.getDirectory();
        try {
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(dir)) {
                javaFiles =
                        walk.filter(p -> p.toString().endsWith(".java"))
                                .collect(Collectors.toList());
            }
            if (javaFiles.isEmpty()) {
                log.warn("Plugin '{}' has no .java files", manifest.getName());
                return;
            }

            DynamicCompiler compiler = new DynamicCompiler();
            for (Path javaFile : javaFiles) {
                String source = readUtf8(javaFile);
                String className = sourceClassName(javaFile, source);
                compiler.addSource(className, source);
            }
            compiler.build();

            ClassLoader cl = compiler.getClassLoader();
            AgentPlugin pluginInstance = null;

            List<String> classNames = new ArrayList<>();
            if (StrUtil.isNotBlank(manifest.getEntry())) {
                classNames.add(manifest.getEntry());
            }
            for (Path javaFile : javaFiles) {
                String source = readUtf8(javaFile);
                String className = sourceClassName(javaFile, source);
                if (!classNames.contains(className)) {
                    classNames.add(className);
                }
            }
            for (String className : classNames) {
                try {
                    Class<?> clazz = cl.loadClass(className);
                    if (AgentPlugin.class.isAssignableFrom(clazz)) {
                        pluginInstance = (AgentPlugin) clazz.getDeclaredConstructor().newInstance();
                        break;
                    }
                } catch (ClassNotFoundException e) {
                    // 尝试带包名
                }
            }

            if (pluginInstance == null) {
                log.warn("Plugin '{}' has no class implementing AgentPlugin", manifest.getName());
                return;
            }

            ConflictAwareSink scopedSink = new ConflictAwareSink(manifest, sink);
            DefaultAgentPluginContext ctx =
                    new DefaultAgentPluginContext(manifest, hookRegistry, scopedSink);
            pluginInstance.register(ctx);

            loadedPlugins.put(manifest.getName(), new LoadedPlugin(manifest, pluginInstance, ctx));
            diagnostics.add(
                    diagnostic(
                            manifest,
                            PluginLoadStatus.LOADED,
                            "loaded",
                            "Plugin loaded: " + manifest.getName()));
            log.info(
                    "Loaded plugin: {} v{} [{}]",
                    manifest.getName(),
                    manifest.getVersion(),
                    manifest.getKind());
        } catch (Exception e) {
            diagnostics.add(
                    diagnostic(
                            manifest,
                            PluginLoadStatus.FAILED,
                            "load_failed",
                            "Plugin load failed: " + safeError(e)));
            log.error("Failed to load plugin '{}': {}", manifest.getName(), safeError(e));
        }
    }

    /**
     * 从插件 Java 源码中推导可加载类名。
     *
     * @param javaFile 插件源码文件。
     * @param source 源码文本。
     * @return 带包名的类名；无 package 声明时返回文件基础名。
     */
    private String sourceClassName(Path javaFile, String source) {
        String simpleName = javaFile.getFileName().toString().replace(".java", "");
        String packageName = null;
        for (String raw : source.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("package ") && line.endsWith(";")) {
                packageName = line.substring("package ".length(), line.length() - 1).trim();
                break;
            }
        }
        return StrUtil.isBlank(packageName) ? simpleName : packageName + "." + simpleName;
    }

    /** 读取普通目录或运行 Jar ZIP 文件系统中的 UTF-8 插件文件。 */
    private String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * 列出已成功加载的插件清单。
     *
     * @return 按插件名称排序的已加载插件清单。
     */
    public List<AgentPluginManifest> listPlugins() {
        synchronized (lifecycleLock) {
            return loadedPlugins.values().stream()
                    .map(LoadedPlugin::getManifest)
                    .sorted(Comparator.comparing(AgentPluginManifest::getName))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 读取最近一次扫描和加载产生的诊断记录。
     *
     * @return 不可变诊断记录列表。
     */
    public List<PluginLoadDiagnostic> diagnostics() {
        synchronized (lifecycleLock) {
            return Collections.unmodifiableList(new ArrayList<PluginLoadDiagnostic>(diagnostics));
        }
    }

    /**
     * 查找插件运行前缺失的第一个必需环境变量。
     *
     * @param manifest 插件清单。
     * @return 第一个缺失环境变量名；都满足时返回 null。
     */
    private String firstMissingEnv(AgentPluginManifest manifest) {
        for (AgentPluginManifest.EnvRequirement requirement : manifest.getRequiresEnv()) {
            if (requirement == null || StrUtil.isBlank(requirement.getName())) {
                continue;
            }
            if (StrUtil.isBlank(ProfileRuntimeScope.environmentValue(requirement.getName()))) {
                return requirement.getName();
            }
        }
        return null;
    }

    /**
     * 创建并脱敏插件诊断消息。
     *
     * @param manifest 插件清单。
     * @param status 状态参数。
     * @param reason 机器可读原因。
     * @param message 人类可读消息。
     * @return 可安全展示的诊断记录。
     */
    private PluginLoadDiagnostic diagnostic(
            AgentPluginManifest manifest, PluginLoadStatus status, String reason, String message) {
        return new PluginLoadDiagnostic(
                manifest == null ? null : manifest.getName(), status, reason, safeMessage(message));
    }

    /**
     * 生成安全展示用的消息。
     *
     * @param message 平台消息或错误消息。
     * @return 已脱敏且限制长度的消息。
     */
    private String safeMessage(String message) {
        return SecretRedactor.redact(StrUtil.nullToDefault(message, ""), 1000);
    }

    /**
     * 当前运行时不支持单插件热重载；Provider 与 Hook 没有反注册契约，保留实例直到管理器整体关闭，避免残留旧 Hook。
     *
     * @param name 请求重载的插件名称。
     */
    public void reload(String name) {
        synchronized (lifecycleLock) {
            if (!shutdown && loadedPlugins.containsKey(name)) {
                log.warn("Plugin reload is ignored until runtime restart: {}", name);
            }
        }
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            for (LoadedPlugin lp : loadedPlugins.values()) {
                try {
                    lp.getPlugin().destroy();
                } catch (Exception e) {
                    log.warn(
                            "Error destroying plugin '{}': {}",
                            lp.getManifest().getName(),
                            e.getMessage());
                }
            }
            loadedPlugins.clear();
            hookRegistry.clear();
            for (URI archiveUri : bundledArchiveLeases) {
                releaseBundledArchive(archiveUri);
            }
            bundledArchiveLeases.clear();
        }
    }

    /** 释放一个内置归档租约；只有本组件创建且引用归零的文件系统才会关闭。 */
    private static void releaseBundledArchive(URI archiveUri) {
        synchronized (BUNDLED_ARCHIVE_LOCK) {
            BundledArchiveLease lease = BUNDLED_ARCHIVES.get(archiveUri);
            if (lease == null || lease.references > 1) {
                if (lease != null) {
                    lease.references--;
                }
                return;
            }
            BUNDLED_ARCHIVES.remove(archiveUri);
            if (!lease.closeWhenUnused) {
                return;
            }
            try {
                lease.fileSystem.close();
            } catch (IOException e) {
                log.debug("Failed to close bundled plugin filesystem: {}", e.getMessage());
            }
        }
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 已脱敏的异常类型和消息。
     */
    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        String value =
                error.getClass().getSimpleName() + (StrUtil.isBlank(message) ? "" : ": " + message);
        return SecretRedactor.redact(value, 1000);
    }

    /** 记录共享内置插件归档的文件系统、关闭责任和当前引用数。 */
    private static class BundledArchiveLease {
        /** 内置插件归档对应的 ZIP 文件系统。 */
        private final FileSystem fileSystem;

        /** 仅当该管理器族创建了文件系统时才允许在引用归零后关闭。 */
        private final boolean closeWhenUnused;

        /** 当前持有该归档的管理器数量。 */
        private int references;

        /** 创建内置插件归档共享租约。 */
        BundledArchiveLease(FileSystem fileSystem, boolean closeWhenUnused) {
            this.fileSystem = fileSystem;
            this.closeWhenUnused = closeWhenUnused;
        }
    }

    /** 已加载插件的运行时句柄。 */
    private static class LoadedPlugin {
        /** 插件清单元数据。 */
        private final AgentPluginManifest manifest;

        /** 插件实例，用于后续销毁。 */
        private final AgentPlugin plugin;

        /** 插件注册上下文，保留供调试和后续扩展使用。 */
        private final DefaultAgentPluginContext context;

        /**
         * 创建已加载插件运行时句柄。
         *
         * @param manifest 插件清单。
         * @param plugin 插件实例。
         * @param context 当前请求或运行上下文。
         */
        LoadedPlugin(
                AgentPluginManifest manifest,
                AgentPlugin plugin,
                DefaultAgentPluginContext context) {
            this.manifest = manifest;
            this.plugin = plugin;
            this.context = context;
        }

        /**
         * 读取插件清单。
         *
         * @return 插件清单。
         */
        AgentPluginManifest getManifest() {
            return manifest;
        }

        /**
         * 读取插件实例。
         *
         * @return 插件实例。
         */
        AgentPlugin getPlugin() {
            return plugin;
        }
    }

    /** 为单个插件包装注册接收器，负责阻止工具名和命令名冲突。 */
    private class ConflictAwareSink implements PluginRegistrationSink {
        /** 当前正在注册的插件清单。 */
        private final AgentPluginManifest manifest;

        /** 主应用提供的真实注册接收器。 */
        private final PluginRegistrationSink delegate;

        /** 当前插件本次注册过的工具名，避免同一插件内部重复注册。 */
        private final Set<String> pluginTools = new LinkedHashSet<>();

        /** 当前插件本次注册过的命令名，避免同一插件内部重复注册。 */
        private final Set<String> pluginCommands = new LinkedHashSet<>();

        /**
         * 创建带冲突检查的注册接收器。
         *
         * @param manifest 当前插件清单。
         * @param delegate 主应用注册接收器。
         */
        ConflictAwareSink(AgentPluginManifest manifest, PluginRegistrationSink delegate) {
            this.manifest = manifest;
            this.delegate = delegate;
        }

        /**
         * 判断工具名是否已被主应用或当前插件占用。
         *
         * @return 已占用时返回 true。
         */
        @Override
        public boolean hasTool(String name) {
            return delegate.hasTool(name) || pluginTools.contains(name);
        }

        /**
         * 判断命令名是否已被主应用或当前插件占用。
         *
         * @return 已占用时返回 true。
         */
        @Override
        public boolean hasCommand(String name) {
            return delegate.hasCommand(name) || pluginCommands.contains(name);
        }

        /** 注册工具，并在名称为空或冲突时写入跳过诊断。 */
        @Override
        public void onToolRegistered(ToolRegistration registration) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isBlank(name) || hasTool(name)) {
                recordSkippedRegistration(
                        "duplicate_tool_name", "Plugin tool name already exists: ", name);
                return;
            }
            pluginTools.add(name);
            delegate.onToolRegistered(registration);
        }

        /**
         * 注册命令，并在名称为空或冲突时写入跳过诊断。
         *
         * @param handler handler 参数。
         * @param description 描述参数。
         */
        @Override
        public void onCommandRegistered(String name, CommandHandler handler, String description) {
            if (StrUtil.isBlank(name) || hasCommand(name)) {
                recordSkippedRegistration(
                        "duplicate_command_name", "Plugin command name already exists: ", name);
                return;
            }
            pluginCommands.add(name);
            delegate.onCommandRegistered(name, handler, description);
        }

        /**
         * 转发 Web 搜索 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onWebSearchProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider) {
            delegate.onWebSearchProviderRegistered(provider);
        }

        /**
         * 转发图片生成 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onImageGenProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.ImageGenProvider provider) {
            delegate.onImageGenProviderRegistered(provider);
        }

        /**
         * 转发视频生成 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onVideoGenProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.VideoGenProvider provider) {
            delegate.onVideoGenProviderRegistered(provider);
        }

        /**
         * 转发浏览器自动化 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onBrowserProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.BrowserProvider provider) {
            delegate.onBrowserProviderRegistered(provider);
        }

        /**
         * 转发语音合成 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onSpeechProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.SpeechProvider provider) {
            delegate.onSpeechProviderRegistered(provider);
        }

        /**
         * 转发语音转写 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onTranscriptionProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider provider) {
            delegate.onTranscriptionProviderRegistered(provider);
        }

        /**
         * 转发长期记忆 Provider 注册事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onMemoryProviderRegistered(
                com.jimuqu.solon.claw.core.service.MemoryProvider provider) {
            delegate.onMemoryProviderRegistered(provider);
        }

        /**
         * 转发国内渠道平台适配器注册事件。
         *
         * @param registration registration 参数。
         */
        @Override
        public void onPlatformRegistered(PlatformRegistration registration) {
            delegate.onPlatformRegistered(registration);
        }

        /**
         * 记录因重复或空名称被跳过的注册项。
         *
         * @param reason 机器可读原因。
         * @param messagePrefix 展示消息前缀。
         * @param name 注册项名称。
         */
        private void recordSkippedRegistration(String reason, String messagePrefix, String name) {
            diagnostics.add(
                    diagnostic(
                            manifest,
                            PluginLoadStatus.SKIPPED,
                            reason,
                            messagePrefix + StrUtil.nullToDefault(name, "")));
        }
    }
}
