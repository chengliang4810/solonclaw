package com.jimuqu.solon.claw.plugin;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
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

    public AgentPluginManager(AgentHookRegistry hookRegistry, Set<String> enabledPlugins,
                              Set<String> disabledPlugins) {
        this.hookRegistry = hookRegistry;
        this.enabledPlugins = enabledPlugins != null ? enabledPlugins : Collections.emptySet();
        this.disabledPlugins = disabledPlugins != null ? disabledPlugins : Collections.emptySet();
        String home = System.getProperty("user.home");
        this.userPluginsDir = Paths.get(home, ".jimuqu", "plugins");
    }

    public AgentHookRegistry getHookRegistry() {
        return hookRegistry;
    }

    public void discoverAndLoad(PluginRegistrationSink sink) {
        List<AgentPluginManifest> manifests = new ArrayList<>();
        discoverBundled(manifests);
        discoverUserPlugins(manifests);

        for (AgentPluginManifest manifest : manifests) {
            if (disabledPlugins.contains(manifest.getName())) {
                log.debug("Plugin '{}' is disabled, skipping", manifest.getName());
                continue;
            }
            if (!manifest.isAutoLoad() && !enabledPlugins.contains(manifest.getName())) {
                log.debug("Plugin '{}' is standalone and not enabled, skipping", manifest.getName());
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
            children.filter(Files::isDirectory).forEach(child -> {
                Path yamlFile = child.resolve("plugin.yaml");
                Path ymlFile = child.resolve("plugin.yml");
                Path manifestFile = Files.exists(yamlFile) ? yamlFile : (Files.exists(ymlFile) ? ymlFile : null);
                if (manifestFile == null) {
                    return;
                }
                try {
                    AgentPluginManifest manifest = parseManifest(manifestFile, child, source);
                    if (manifest != null) {
                        manifests.add(manifest);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse plugin manifest at {}: {}", manifestFile, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan plugin directory {}: {}", dir, e.getMessage());
        }
    }

    private AgentPluginManifest parseManifest(Path manifestFile, Path pluginDir, String source) throws IOException {
        String content = Files.readString(manifestFile);
        Map<String, String> props = parseSimpleYaml(content);

        AgentPluginManifest manifest = new AgentPluginManifest();
        manifest.setName(props.getOrDefault("name", pluginDir.getFileName().toString()));
        manifest.setVersion(props.get("version"));
        manifest.setDescription(props.get("description"));
        manifest.setAuthor(props.get("author"));
        manifest.setKind(props.getOrDefault("kind", "standalone"));
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

    private void loadPlugin(AgentPluginManifest manifest, PluginRegistrationSink sink) {
        Path dir = manifest.getDirectory();
        try {
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(dir)) {
                javaFiles = walk.filter(p -> p.toString().endsWith(".java"))
                        .collect(Collectors.toList());
            }
            if (javaFiles.isEmpty()) {
                log.warn("Plugin '{}' has no .java files", manifest.getName());
                return;
            }

            DynamicCompiler compiler = new DynamicCompiler();
            for (Path javaFile : javaFiles) {
                String source = Files.readString(javaFile);
                String className = javaFile.getFileName().toString().replace(".java", "");
                compiler.addSource(className, source);
            }
            compiler.build();

            ClassLoader cl = compiler.getClassLoader();
            AgentPlugin pluginInstance = null;

            for (Path javaFile : javaFiles) {
                String className = javaFile.getFileName().toString().replace(".java", "");
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

            DefaultAgentPluginContext ctx = new DefaultAgentPluginContext(manifest, hookRegistry, sink);
            pluginInstance.register(ctx);
            manifest.setEnabled(true);

            loadedPlugins.put(manifest.getName(), new LoadedPlugin(manifest, pluginInstance, ctx));
            log.info("Loaded plugin: {} v{} [{}]", manifest.getName(), manifest.getVersion(), manifest.getKind());
        } catch (Exception e) {
            log.error("Failed to load plugin '{}': {}", manifest.getName(), e.getMessage(), e);
        }
    }

    public List<AgentPluginManifest> listPlugins() {
        return loadedPlugins.values().stream()
                .map(LoadedPlugin::getManifest)
                .collect(Collectors.toList());
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
                log.warn("Error destroying plugin '{}': {}", lp.getManifest().getName(), e.getMessage());
            }
        }
        loadedPlugins.clear();
        hookRegistry.clear();
    }

    private static class LoadedPlugin {
        private final AgentPluginManifest manifest;
        private final AgentPlugin plugin;
        private final DefaultAgentPluginContext context;

        LoadedPlugin(AgentPluginManifest manifest, AgentPlugin plugin, DefaultAgentPluginContext context) {
            this.manifest = manifest;
            this.plugin = plugin;
            this.context = context;
        }

        AgentPluginManifest getManifest() { return manifest; }
        AgentPlugin getPlugin() { return plugin; }
    }
}
