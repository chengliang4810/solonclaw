package com.jimuqu.solon.claw.plugin;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.IOException;
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

/** 插件生命周期管理：发现、编译、加载、卸载。 */
public class AgentPluginManager {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(AgentPluginManager.class);

    /** 记录Agent插件中的钩子注册表。 */
    private final AgentHookRegistry hookRegistry;

    /** 保存loadedPlugins映射，便于按键快速查询。 */
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();

    /** 记录Agent插件中的用户Plugins目录。 */
    private final Path userPluginsDir;

    /** 标记是否启用Plugins。 */
    private final Set<String> enabledPlugins;

    /** 标记是否禁用Plugins。 */
    private final Set<String> disabledPlugins;

    /** 是否启用loadBundledPlugins。 */
    private final boolean loadBundledPlugins;

    /** 保存诊断集合，维持调用顺序或去重语义。 */
    private final List<PluginLoadDiagnostic> diagnostics = new ArrayList<>();

    /**
     * 创建Agent插件管理器实例，并注入运行所需依赖。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     * @param enabledPlugins 启用状态Plugins开关值。
     * @param disabledPlugins disabledPlugins 参数。
     */
    public AgentPluginManager(
            AgentHookRegistry hookRegistry,
            Set<String> enabledPlugins,
            Set<String> disabledPlugins) {
        this(
                hookRegistry,
                enabledPlugins,
                disabledPlugins,
                Paths.get(System.getProperty("user.home"), ".jimuqu", "plugins"),
                true);
    }

    /**
     * 创建Agent插件管理器实例，并注入运行所需依赖。
     *
     * @param hookRegistry 钩子注册表依赖组件。
     * @param enabledPlugins 启用状态Plugins开关值。
     * @param disabledPlugins disabledPlugins 参数。
     * @param pluginRoot 插件Root参数。
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
     * @param enabledPlugins 启用状态Plugins开关值。
     * @param disabledPlugins disabledPlugins 参数。
     * @param pluginRoot 插件Root参数。
     * @param loadBundledPlugins loadBundledPlugins 参数。
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
     * 读取钩子注册表。
     *
     * @return 返回读取到的钩子注册表。
     */
    public AgentHookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /**
     * 执行discoverAndLoad相关逻辑。
     *
     * @param sink sink 参数。
     */
    public void discoverAndLoad(PluginRegistrationSink sink) {
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
        log.info("Loaded {} plugin(s)", loadedPlugins.size());
    }

    /**
     * 执行discoverBundled相关逻辑。
     *
     * @param manifests manifests 参数。
     */
    private void discoverBundled(List<AgentPluginManifest> manifests) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            java.net.URL pluginsUrl = cl.getResource("plugins");
            if (pluginsUrl == null) {
                return;
            }
            Path pluginsPath = Paths.get(pluginsUrl.toURI());
            scanDirectory(pluginsPath, "bundled", manifests);
        } catch (Exception e) {
            log.debug("No bundled plugins found: {}", e.getMessage());
        }
    }

    /**
     * 执行discover用户Plugins相关逻辑。
     *
     * @param manifests manifests 参数。
     */
    private void discoverUserPlugins(List<AgentPluginManifest> manifests) {
        if (!Files.isDirectory(userPluginsDir)) {
            return;
        }
        scanDirectory(userPluginsDir, "user", manifests);
    }

    /**
     * 执行scan目录相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @param source 来源参数。
     * @param manifests manifests 参数。
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
     * 解析Manifest。
     *
     * @param manifestFile 文件或目录路径参数。
     * @param pluginDir 文件或目录路径参数。
     * @param source 来源参数。
     * @return 返回解析后的Manifest。
     */
    private AgentPluginManifest parseManifest(Path manifestFile, Path pluginDir, String source)
            throws IOException {
        String content = Files.readString(manifestFile);
        Map<String, String> props = parseSimpleYaml(content);

        AgentPluginManifest manifest = new AgentPluginManifest();
        manifest.setName(props.getOrDefault("name", pluginDir.getFileName().toString()));
        manifest.setVersion(props.get("version"));
        manifest.setDescription(props.get("description"));
        manifest.setAuthor(props.get("author"));
        manifest.setKind(props.getOrDefault("kind", "standalone"));
        manifest.setEnabled(Boolean.parseBoolean(props.getOrDefault("enabled", "true")));
        manifest.setEntry(props.get("entry"));
        manifest.setProvidesTools(parseStringList(content, "providesTools"));
        manifest.setRequiresEnv(parseEnvRequirements(content));
        manifest.setSource(source);
        manifest.setDirectory(pluginDir);
        return manifest;
    }

    /**
     * 解析Simple YAML。
     *
     * @param content 待处理内容。
     * @return 返回解析后的Simple YAML。
     */
    private Map<String, String> parseSimpleYaml(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * 解析String List。
     *
     * @param content 待处理内容。
     * @param key 配置键或映射键。
     * @return 返回解析后的String List。
     */
    private List<String> parseStringList(String content, String key) {
        List<String> values = new ArrayList<>();
        boolean inList = false;
        for (String raw : content.split("\n")) {
            String line = raw.replace("\r", "");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                inList = trimmed.equals(key + ":");
                continue;
            }
            if (inList && trimmed.startsWith("- ")) {
                values.add(unquote(trimmed.substring(2).trim()));
            }
        }
        return values;
    }

    /**
     * 解析Env Requirements。
     *
     * @param content 待处理内容。
     * @return 返回解析后的Env Requirements。
     */
    private List<AgentPluginManifest.EnvRequirement> parseEnvRequirements(String content) {
        List<AgentPluginManifest.EnvRequirement> requirements = new ArrayList<>();
        AgentPluginManifest.EnvRequirement current = null;
        boolean inList = false;
        for (String raw : content.split("\n")) {
            String line = raw.replace("\r", "");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                inList = trimmed.equals("requiresEnv:");
                continue;
            }
            if (!inList) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                current = new AgentPluginManifest.EnvRequirement();
                requirements.add(current);
                String inline = trimmed.substring(2).trim();
                setEnvField(current, inline);
            } else if (current != null) {
                setEnvField(current, trimmed);
            }
        }
        return requirements;
    }

    /**
     * 写入Env Field。
     *
     * @param requirement requirement 参数。
     * @param raw 原始输入值。
     */
    private void setEnvField(AgentPluginManifest.EnvRequirement requirement, String raw) {
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String key = raw.substring(0, colon).trim();
        String value = unquote(raw.substring(colon + 1).trim());
        if ("name".equals(key)) {
            requirement.setName(value);
        } else if ("description".equals(key)) {
            requirement.setDescription(value);
        } else if ("secret".equals(key)) {
            requirement.setSecret(Boolean.parseBoolean(value));
        }
    }

    /**
     * 执行unquote相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回unquote结果。
     */
    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 加载插件。
     *
     * @param manifest manifest 参数。
     * @param sink sink 参数。
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
                String source = Files.readString(javaFile);
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
                String source = Files.readString(javaFile);
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
     * 执行来源Class名称相关逻辑。
     *
     * @param javaFile 文件或目录路径参数。
     * @param source 来源参数。
     * @return 返回来源Class名称结果。
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

    /**
     * 列出Plugins。
     *
     * @return 返回Plugins列表。
     */
    public List<AgentPluginManifest> listPlugins() {
        return loadedPlugins.values().stream()
                .map(LoadedPlugin::getManifest)
                .sorted(Comparator.comparing(AgentPluginManifest::getName))
                .collect(Collectors.toList());
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @return 返回诊断结果。
     */
    public List<PluginLoadDiagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * 执行firstMissing环境变量相关逻辑。
     *
     * @param manifest manifest 参数。
     * @return 返回first Missing Env结果。
     */
    private String firstMissingEnv(AgentPluginManifest manifest) {
        for (AgentPluginManifest.EnvRequirement requirement : manifest.getRequiresEnv()) {
            if (requirement == null || StrUtil.isBlank(requirement.getName())) {
                continue;
            }
            if (StrUtil.isBlank(System.getenv(requirement.getName()))) {
                return requirement.getName();
            }
        }
        return null;
    }

    /**
     * 执行诊断相关逻辑。
     *
     * @param manifest manifest 参数。
     * @param status 状态参数。
     * @param reason 原因参数。
     * @param message 平台消息或错误消息。
     * @return 返回诊断结果。
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
     * @return 返回safe消息结果。
     */
    private String safeMessage(String message) {
        return SecretRedactor.redact(StrUtil.nullToDefault(message, ""), 1000);
    }

    /**
     * 重新加载目标服务端配置与工具清单。
     *
     * @param name 名称参数。
     */
    public void reload(String name) {
        LoadedPlugin existing = loadedPlugins.remove(name);
        if (existing != null) {
            existing.getPlugin().destroy();
        }
    }

    /** 关闭当前组件持有的运行资源。 */
    public void shutdown() {
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
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param error 错误参数。
     * @return 返回safe Error结果。
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

    /** 承载Loaded插件相关状态和辅助逻辑。 */
    private static class LoadedPlugin {
        /** 记录Loaded插件中的manifest。 */
        private final AgentPluginManifest manifest;

        /** 记录Loaded插件中的插件。 */
        private final AgentPlugin plugin;

        /** 记录Loaded插件中的上下文。 */
        private final DefaultAgentPluginContext context;

        /**
         * 创建Loaded插件实例，并注入运行所需依赖。
         *
         * @param manifest manifest 参数。
         * @param plugin 插件参数。
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
         * 读取Manifest。
         *
         * @return 返回读取到的Manifest。
         */
        AgentPluginManifest getManifest() {
            return manifest;
        }

        /**
         * 读取插件。
         *
         * @return 返回读取到的插件。
         */
        AgentPlugin getPlugin() {
            return plugin;
        }
    }

    /** 承载ConflictAware接收端相关状态和辅助逻辑。 */
    private class ConflictAwareSink implements PluginRegistrationSink {
        /** 记录ConflictAware接收端中的manifest。 */
        private final AgentPluginManifest manifest;

        /** 记录ConflictAware接收端中的委托。 */
        private final PluginRegistrationSink delegate;

        /** 保存插件工具集合，维持调用顺序或去重语义。 */
        private final Set<String> pluginTools = new LinkedHashSet<>();

        /** 保存插件Commands集合，维持调用顺序或去重语义。 */
        private final Set<String> pluginCommands = new LinkedHashSet<>();

        /**
         * 创建Conflict Aware接收端实例，并注入运行所需依赖。
         *
         * @param manifest manifest 参数。
         * @param delegate 委派参数。
         */
        ConflictAwareSink(AgentPluginManifest manifest, PluginRegistrationSink delegate) {
            this.manifest = manifest;
            this.delegate = delegate;
        }

        /**
         * 判断是否存在工具。
         *
         * @param name 名称参数。
         * @return 如果工具满足条件则返回 true，否则返回 false。
         */
        @Override
        public boolean hasTool(String name) {
            return delegate.hasTool(name) || pluginTools.contains(name);
        }

        /**
         * 判断是否存在命令。
         *
         * @param name 名称参数。
         * @return 如果命令满足条件则返回 true，否则返回 false。
         */
        @Override
        public boolean hasCommand(String name) {
            return delegate.hasCommand(name) || pluginCommands.contains(name);
        }

        /**
         * 响应工具Registered事件。
         *
         * @param registration registration 参数。
         */
        @Override
        public void onToolRegistered(ToolRegistration registration) {
            String name = registration == null ? null : registration.getName();
            if (StrUtil.isBlank(name) || hasTool(name)) {
                diagnostics.add(
                        diagnostic(
                                manifest,
                                PluginLoadStatus.SKIPPED,
                                "duplicate_tool_name",
                                "Plugin tool name already exists: "
                                        + StrUtil.nullToDefault(name, "")));
                return;
            }
            pluginTools.add(name);
            delegate.onToolRegistered(registration);
        }

        /**
         * 响应命令Registered事件。
         *
         * @param name 名称参数。
         * @param handler handler 参数。
         * @param description 描述参数。
         */
        @Override
        public void onCommandRegistered(String name, CommandHandler handler, String description) {
            if (StrUtil.isBlank(name) || hasCommand(name)) {
                diagnostics.add(
                        diagnostic(
                                manifest,
                                PluginLoadStatus.SKIPPED,
                                "duplicate_command_name",
                                "Plugin command name already exists: "
                                        + StrUtil.nullToDefault(name, "")));
                return;
            }
            pluginCommands.add(name);
            delegate.onCommandRegistered(name, handler, description);
        }

        /**
         * 响应Web搜索提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onWebSearchProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider) {
            delegate.onWebSearchProviderRegistered(provider);
        }

        /**
         * 响应图片Gen提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onImageGenProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.ImageGenProvider provider) {
            delegate.onImageGenProviderRegistered(provider);
        }

        /**
         * 响应VideoGen提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onVideoGenProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.VideoGenProvider provider) {
            delegate.onVideoGenProviderRegistered(provider);
        }

        /**
         * 响应浏览器提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onBrowserProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.BrowserProvider provider) {
            delegate.onBrowserProviderRegistered(provider);
        }

        /**
         * 响应语音提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onSpeechProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.SpeechProvider provider) {
            delegate.onSpeechProviderRegistered(provider);
        }

        /**
         * 响应转写提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onTranscriptionProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider provider) {
            delegate.onTranscriptionProviderRegistered(provider);
        }

        /**
         * 响应记忆提供方Registered事件。
         *
         * @param provider 模型或能力提供方。
         */
        @Override
        public void onMemoryProviderRegistered(
                com.jimuqu.solon.claw.core.service.MemoryProvider provider) {
            delegate.onMemoryProviderRegistered(provider);
        }

        /**
         * 响应平台Registered事件。
         *
         * @param registration registration 参数。
         */
        @Override
        public void onPlatformRegistered(PlatformRegistration registration) {
            delegate.onPlatformRegistered(registration);
        }
    }
}
