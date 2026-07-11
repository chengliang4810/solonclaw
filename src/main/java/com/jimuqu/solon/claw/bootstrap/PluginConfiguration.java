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
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 承载插件配置并集中创建运行组件。 */
@Configuration
public class PluginConfiguration implements PluginRegistrationSink {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(PluginConfiguration.class);

    /** 记录插件中的钩子注册表。 */
    private final AgentHookRegistry hookRegistry = new AgentHookRegistry();

    /** 保存Web搜索Providers集合，维持调用顺序或去重语义。 */
    private final List<WebSearchProvider> webSearchProviders = new CopyOnWriteArrayList<>();

    /** 保存图片GenProviders集合，维持调用顺序或去重语义。 */
    private final List<ImageGenProvider> imageGenProviders = new CopyOnWriteArrayList<>();

    /** 保存videoGenProviders集合，维持调用顺序或去重语义。 */
    private final List<VideoGenProvider> videoGenProviders = new CopyOnWriteArrayList<>();

    /** 保存浏览器Providers集合，维持调用顺序或去重语义。 */
    private final List<BrowserProvider> browserProviders = new CopyOnWriteArrayList<>();

    /** 保存语音Providers集合，维持调用顺序或去重语义。 */
    private final List<SpeechProvider> speechProviders = new CopyOnWriteArrayList<>();

    /** 保存转写Providers集合，维持调用顺序或去重语义。 */
    private final List<TranscriptionProvider> transcriptionProviders = new CopyOnWriteArrayList<>();

    /** 保存插件记忆Providers集合，维持调用顺序或去重语义。 */
    private final List<MemoryProvider> pluginMemoryProviders = new CopyOnWriteArrayList<>();

    /** 保存插件工具集合，维持调用顺序或去重语义。 */
    private final List<ToolRegistration> pluginTools = new CopyOnWriteArrayList<>();

    /** 保存插件Commands映射，便于按键快速查询。 */
    private final Map<String, CommandEntry> pluginCommands = new LinkedHashMap<>();

    /**
     * 执行Agent钩子注册表相关逻辑。
     *
     * @return 返回Agent钩子注册表结果。
     */
    @Bean
    public AgentHookRegistry agentHookRegistry() {
        return hookRegistry;
    }

    /**
     * 执行钩子BridgeInterceptor相关逻辑。
     *
     * @return 返回钩子Bridge Interceptor结果。
     */
    @Bean
    public HookBridgeInterceptor hookBridgeInterceptor() {
        return new HookBridgeInterceptor(hookRegistry);
    }

    /**
     * 执行Agent插件管理器相关逻辑。
     *
     * @param appConfig 当前 Profile 的应用配置。
     * @return 返回Agent插件管理器结果。
     */
    @Bean
    public AgentPluginManager agentPluginManager(AppConfig appConfig) {
        Set<String> enabled =
                appConfig == null
                        ? Collections.<String>emptySet()
                        : normalizedSet(appConfig.getPlugins().getEnabled());
        Set<String> disabled =
                appConfig == null
                        ? Collections.<String>emptySet()
                        : normalizedSet(appConfig.getPlugins().getDisabled());
        String configuredHome =
                appConfig == null || appConfig.getRuntime() == null
                        ? null
                        : appConfig.getRuntime().getHome();
        Path profilePlugins =
                configuredHome == null || configuredHome.trim().length() == 0
                        ? Paths.get(RuntimePathConstants.WORKSPACE_HOME, "plugins")
                                .toAbsolutePath()
                                .normalize()
                        : Paths.get(configuredHome).toAbsolutePath().normalize().resolve("plugins");
        return new AgentPluginManager(hookRegistry, enabled, disabled, profilePlugins, true);
    }

    /**
     * 加载Plugins。
     *
     * @param manager manager 参数。
     */
    @Init
    public void loadPlugins(@Inject AgentPluginManager manager) {
        manager.discoverAndLoad(this);
        log.info(
                "Plugin providers registered: websearch={}, image_gen={}, video_gen={}, browser={},"
                        + " memory={}",
                webSearchProviders.size(),
                imageGenProviders.size(),
                videoGenProviders.size(),
                browserProviders.size(),
                pluginMemoryProviders.size());
    }

    /**
     * 执行Web搜索Providers相关逻辑。
     *
     * @return 返回Web搜索Providers结果。
     */
    @Bean
    public List<WebSearchProvider> webSearchProviders() {
        return webSearchProviders;
    }

    /**
     * 执行图片GenProviders相关逻辑。
     *
     * @return 返回图片Gen Providers结果。
     */
    @Bean
    public List<ImageGenProvider> imageGenProviders() {
        return imageGenProviders;
    }

    /**
     * 执行videoGenProviders相关逻辑。
     *
     * @return 返回video Gen Providers结果。
     */
    @Bean
    public List<VideoGenProvider> videoGenProviders() {
        return videoGenProviders;
    }

    /**
     * 执行浏览器Providers相关逻辑。
     *
     * @return 返回浏览器Providers结果。
     */
    @Bean
    public List<BrowserProvider> browserProviders() {
        return browserProviders;
    }

    /**
     * 执行语音Providers相关逻辑。
     *
     * @return 返回语音Providers结果。
     */
    @Bean
    public List<SpeechProvider> speechProviders() {
        return speechProviders;
    }

    /**
     * 执行转写Providers相关逻辑。
     *
     * @return 返回transcription Providers结果。
     */
    @Bean
    public List<TranscriptionProvider> transcriptionProviders() {
        return transcriptionProviders;
    }

    /**
     * 执行插件记忆Providers相关逻辑。
     *
     * @return 返回插件记忆Providers结果。
     */
    @Bean
    public List<MemoryProvider> pluginMemoryProviders() {
        return pluginMemoryProviders;
    }

    /**
     * 执行插件工具相关逻辑。
     *
     * @return 返回插件工具结果。
     */
    @Bean
    public List<ToolRegistration> pluginTools() {
        return pluginTools;
    }

    /**
     * 执行插件Commands相关逻辑。
     *
     * @return 返回插件Commands结果。
     */
    @Bean
    public Map<String, CommandHandler> pluginCommands() {
        Map<String, CommandHandler> commands = new LinkedHashMap<>();
        for (Map.Entry<String, CommandEntry> entry : pluginCommands.entrySet()) {
            commands.put(entry.getKey(), entry.getValue().handler);
        }
        return Collections.unmodifiableMap(commands);
    }

    /**
     * 判断是否存在工具。
     *
     * @param name 名称参数。
     * @return 如果工具满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否存在命令。
     *
     * @param name 名称参数。
     * @return 如果命令满足条件则返回 true，否则返回 false。
     */
    @Override
    public boolean hasCommand(String name) {
        String normalized = normalizeCommandName(name);
        return CommandRegistry.resolve(normalized) != null
                || pluginCommands.containsKey(normalized);
    }

    /**
     * 响应工具Registered事件。
     *
     * @param registration registration 参数。
     */
    @Override
    public void onToolRegistered(ToolRegistration registration) {
        if (registration != null && !hasTool(registration.getName())) {
            pluginTools.add(registration);
        }
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
        String normalized = normalizeCommandName(name);
        if (!hasCommand(normalized)) {
            pluginCommands.put(normalized, new CommandEntry(normalized, handler, description));
        }
    }

    /**
     * 响应Web搜索提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void onWebSearchProviderRegistered(WebSearchProvider provider) {
        webSearchProviders.add(provider);
    }

    /**
     * 响应图片Gen提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void onImageGenProviderRegistered(ImageGenProvider provider) {
        imageGenProviders.add(provider);
    }

    /**
     * 响应VideoGen提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void onVideoGenProviderRegistered(VideoGenProvider provider) {
        videoGenProviders.add(provider);
    }

    /**
     * 响应浏览器提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void onBrowserProviderRegistered(BrowserProvider provider) {
        browserProviders.add(provider);
    }

    /**
     * 响应语音提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    public void onSpeechProviderRegistered(SpeechProvider provider) {
        speechProviders.add(provider);
    }

    /**
     * 响应转写提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    public void onTranscriptionProviderRegistered(TranscriptionProvider provider) {
        transcriptionProviders.add(provider);
    }

    /**
     * 响应记忆提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void onMemoryProviderRegistered(MemoryProvider provider) {
        pluginMemoryProviders.add(provider);
    }

    /**
     * 响应平台Registered事件。
     *
     * @param registration registration 参数。
     */
    @Override
    public void onPlatformRegistered(PlatformRegistration registration) {
        log.info("Platform plugin registered: {}", registration.getName());
    }

    /** 承载命令Entry相关状态和辅助逻辑。 */
    private static class CommandEntry {
        /** 记录命令Entry中的名称。 */
        final String name;

        /** 记录命令Entry中的handler。 */
        final CommandHandler handler;

        /** 记录命令Entry中的描述。 */
        final String description;

        /**
         * 创建命令Entry实例，并注入运行所需依赖。
         *
         * @param name 名称参数。
         * @param handler handler 参数。
         * @param description 描述参数。
         */
        CommandEntry(String name, CommandHandler handler, String description) {
            this.name = name;
            this.handler = handler;
            this.description = description;
        }
    }

    /**
     * 执行normalizedSet相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回normalized Set结果。
     */
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

    /**
     * 规范化命令名称。
     *
     * @param name 名称参数。
     * @return 返回命令名称结果。
     */
    private String normalizeCommandName(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("/") ? value.substring(1) : value;
    }

    /**
     * 执行builtin工具Names相关逻辑。
     *
     * @return 返回builtin工具Names结果。
     */
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
            } catch (Exception e) {
                log.debug(
                        "内置工具常量读取失败，跳过当前字段 field={}, error={}",
                        field.getName(),
                        e.getClass().getSimpleName());
            }
        }
        return result;
    }
}
