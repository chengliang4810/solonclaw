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
    private static final Logger log = LoggerFactory.getLogger(AgentPluginManager.class);

    private final AgentHookRegistry hookRegistry;
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();
    private final Path userPluginsDir;
    private final Set<String> enabledPlugins;
    private final Set<String> disabledPlugins;
    private final boolean loadBundledPlugins;
    private final List<PluginLoadDiagnostic> diagnostics = new ArrayList<>();

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

    public AgentPluginManager(
            AgentHookRegistry hookRegistry,
            Set<String> enabledPlugins,
            Set<String> disabledPlugins,
            Path pluginRoot) {
        this(hookRegistry, enabledPlugins, disabledPlugins, pluginRoot, false);
    }

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

    public AgentHookRegistry getHookRegistry() {
        return hookRegistry;
    }

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

    private void discoverUserPlugins(List<AgentPluginManifest> manifests) {
        if (!Files.isDirectory(userPluginsDir)) {
            return;
        }
        scanDirectory(userPluginsDir, "user", manifests);
    }

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

    public List<AgentPluginManifest> listPlugins() {
        return loadedPlugins.values().stream()
                .map(LoadedPlugin::getManifest)
                .sorted(Comparator.comparing(AgentPluginManifest::getName))
                .collect(Collectors.toList());
    }

    public List<PluginLoadDiagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

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

    private PluginLoadDiagnostic diagnostic(
            AgentPluginManifest manifest, PluginLoadStatus status, String reason, String message) {
        return new PluginLoadDiagnostic(
                manifest == null ? null : manifest.getName(), status, reason, safeMessage(message));
    }

    private String safeMessage(String message) {
        return SecretRedactor.redact(StrUtil.nullToDefault(message, ""), 1000);
    }

    public void reload(String name) {
        LoadedPlugin existing = loadedPlugins.remove(name);
        if (existing != null) {
            existing.getPlugin().destroy();
        }
    }

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

    private String safeError(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        String value =
                error.getClass().getSimpleName() + (StrUtil.isBlank(message) ? "" : ": " + message);
        return SecretRedactor.redact(value, 1000);
    }

    private static class LoadedPlugin {
        private final AgentPluginManifest manifest;
        private final AgentPlugin plugin;
        private final DefaultAgentPluginContext context;

        LoadedPlugin(
                AgentPluginManifest manifest,
                AgentPlugin plugin,
                DefaultAgentPluginContext context) {
            this.manifest = manifest;
            this.plugin = plugin;
            this.context = context;
        }

        AgentPluginManifest getManifest() {
            return manifest;
        }

        AgentPlugin getPlugin() {
            return plugin;
        }
    }

    private class ConflictAwareSink implements PluginRegistrationSink {
        private final AgentPluginManifest manifest;
        private final PluginRegistrationSink delegate;
        private final Set<String> pluginTools = new LinkedHashSet<>();
        private final Set<String> pluginCommands = new LinkedHashSet<>();

        ConflictAwareSink(AgentPluginManifest manifest, PluginRegistrationSink delegate) {
            this.manifest = manifest;
            this.delegate = delegate;
        }

        @Override
        public boolean hasTool(String name) {
            return delegate.hasTool(name) || pluginTools.contains(name);
        }

        @Override
        public boolean hasCommand(String name) {
            return delegate.hasCommand(name) || pluginCommands.contains(name);
        }

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

        @Override
        public void onWebSearchProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.WebSearchProvider provider) {
            delegate.onWebSearchProviderRegistered(provider);
        }

        @Override
        public void onImageGenProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.ImageGenProvider provider) {
            delegate.onImageGenProviderRegistered(provider);
        }

        @Override
        public void onVideoGenProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.VideoGenProvider provider) {
            delegate.onVideoGenProviderRegistered(provider);
        }

        @Override
        public void onBrowserProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.BrowserProvider provider) {
            delegate.onBrowserProviderRegistered(provider);
        }

        @Override
        public void onSpeechProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.SpeechProvider provider) {
            delegate.onSpeechProviderRegistered(provider);
        }

        @Override
        public void onTranscriptionProviderRegistered(
                com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider provider) {
            delegate.onTranscriptionProviderRegistered(provider);
        }

        @Override
        public void onMemoryProviderRegistered(
                com.jimuqu.solon.claw.core.service.MemoryProvider provider) {
            delegate.onMemoryProviderRegistered(provider);
        }

        @Override
        public void onPlatformRegistered(PlatformRegistration registration) {
            delegate.onPlatformRegistered(registration);
        }
    }
}
