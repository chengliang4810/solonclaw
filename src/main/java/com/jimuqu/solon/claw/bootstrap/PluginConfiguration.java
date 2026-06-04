package com.jimuqu.solon.claw.bootstrap;

import com.jimuqu.solon.claw.command.CommandRegistry;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.*;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class PluginConfiguration implements PluginRegistrationSink {
    private static final Logger log = LoggerFactory.getLogger(PluginConfiguration.class);

    @Inject private AppConfig appConfig;

    private final AgentHookRegistry hookRegistry = new AgentHookRegistry();
    private final List<WebSearchProvider> webSearchProviders = new CopyOnWriteArrayList<>();
    private final List<ImageGenProvider> imageGenProviders = new CopyOnWriteArrayList<>();
    private final List<VideoGenProvider> videoGenProviders = new CopyOnWriteArrayList<>();
    private final List<BrowserProvider> browserProviders = new CopyOnWriteArrayList<>();
    private final List<SpeechProvider> speechProviders = new CopyOnWriteArrayList<>();
    private final List<TranscriptionProvider> transcriptionProviders = new CopyOnWriteArrayList<>();
    private final List<MemoryProvider> pluginMemoryProviders = new CopyOnWriteArrayList<>();
    private final List<ToolRegistration> pluginTools = new CopyOnWriteArrayList<>();
    private final Map<String, CommandEntry> pluginCommands = new LinkedHashMap<>();

    @Bean
    public AgentHookRegistry agentHookRegistry() {
        return hookRegistry;
    }

    @Bean
    public HookBridgeInterceptor hookBridgeInterceptor() {
        return new HookBridgeInterceptor(hookRegistry);
    }

    @Bean
    public AgentPluginManager agentPluginManager() {
        Set<String> enabled =
                appConfig == null
                        ? Collections.<String>emptySet()
                        : normalizedSet(appConfig.getPlugins().getEnabled());
        Set<String> disabled =
                appConfig == null
                        ? Collections.<String>emptySet()
                        : normalizedSet(appConfig.getPlugins().getDisabled());
        return new AgentPluginManager(hookRegistry, enabled, disabled);
    }

    @Init
    public void loadPlugins(@Inject AgentPluginManager manager) {
        manager.discoverAndLoad(this);
        log.info(
                "Plugin providers registered: web_search={}, image_gen={}, video_gen={}, browser={}, memory={}",
                webSearchProviders.size(),
                imageGenProviders.size(),
                videoGenProviders.size(),
                browserProviders.size(),
                pluginMemoryProviders.size());
    }

    @Bean
    public List<WebSearchProvider> webSearchProviders() {
        return webSearchProviders;
    }

    @Bean
    public List<ImageGenProvider> imageGenProviders() {
        return imageGenProviders;
    }

    @Bean
    public List<VideoGenProvider> videoGenProviders() {
        return videoGenProviders;
    }

    @Bean
    public List<BrowserProvider> browserProviders() {
        return browserProviders;
    }

    @Bean
    public List<SpeechProvider> speechProviders() {
        return speechProviders;
    }

    @Bean
    public List<TranscriptionProvider> transcriptionProviders() {
        return transcriptionProviders;
    }

    @Bean
    public List<MemoryProvider> pluginMemoryProviders() {
        return pluginMemoryProviders;
    }

    @Bean
    public List<ToolRegistration> pluginTools() {
        return pluginTools;
    }

    @Bean
    public Map<String, CommandHandler> pluginCommands() {
        Map<String, CommandHandler> commands = new LinkedHashMap<>();
        for (Map.Entry<String, CommandEntry> entry : pluginCommands.entrySet()) {
            commands.put(entry.getKey(), entry.getValue().handler);
        }
        return Collections.unmodifiableMap(commands);
    }

    @Override
    public boolean hasTool(String name) {
        if (builtinToolNames().contains(name)) {
            return true;
        }
        for (ToolRegistration registration : pluginTools) {
            if (registration != null && Objects.equals(registration.getName(), name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasCommand(String name) {
        String normalized = normalizeCommandName(name);
        return CommandRegistry.resolve(normalized) != null
                || pluginCommands.containsKey(normalized);
    }

    @Override
    public void onToolRegistered(ToolRegistration registration) {
        if (registration != null && !hasTool(registration.getName())) {
            pluginTools.add(registration);
        }
    }

    @Override
    public void onCommandRegistered(String name, CommandHandler handler, String description) {
        String normalized = normalizeCommandName(name);
        if (!hasCommand(normalized)) {
            pluginCommands.put(normalized, new CommandEntry(normalized, handler, description));
        }
    }

    @Override
    public void onWebSearchProviderRegistered(WebSearchProvider provider) {
        webSearchProviders.add(provider);
    }

    @Override
    public void onImageGenProviderRegistered(ImageGenProvider provider) {
        imageGenProviders.add(provider);
    }

    @Override
    public void onVideoGenProviderRegistered(VideoGenProvider provider) {
        videoGenProviders.add(provider);
    }

    @Override
    public void onBrowserProviderRegistered(BrowserProvider provider) {
        browserProviders.add(provider);
    }

    public void onSpeechProviderRegistered(SpeechProvider provider) {
        speechProviders.add(provider);
    }

    public void onTranscriptionProviderRegistered(TranscriptionProvider provider) {
        transcriptionProviders.add(provider);
    }

    @Override
    public void onMemoryProviderRegistered(MemoryProvider provider) {
        pluginMemoryProviders.add(provider);
    }

    @Override
    public void onPlatformRegistered(PlatformRegistration registration) {
        log.info("Platform plugin registered: {}", registration.getName());
    }

    private static class CommandEntry {
        final String name;
        final CommandHandler handler;
        final String description;

        CommandEntry(String name, CommandHandler handler, String description) {
            this.name = name;
            this.handler = handler;
            this.description = description;
        }
    }

    private Set<String> normalizedSet(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (value != null && value.trim().length() > 0) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String normalizeCommandName(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("/") ? value.substring(1) : value;
    }

    @SuppressWarnings("unchecked")
    private Set<String> builtinToolNames() {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (Field field : ToolNameConstants.class.getFields()) {
            if (!String.class.equals(field.getType())) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value != null) {
                    result.add(String.valueOf(value));
                }
            } catch (Exception ignored) {
                // Constant reflection is best-effort for plugin diagnostics.
            }
        }
        return result;
    }
}
