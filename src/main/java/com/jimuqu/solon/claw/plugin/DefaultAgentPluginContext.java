package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.hook.HookCallback;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import java.util.Collections;
import java.util.Map;

/** AgentPluginContext 默认实现。将注册操作委托给 HookRegistry 和 RegistrationSink。 */
public class DefaultAgentPluginContext implements AgentPluginContext {
    /** 当前插件清单，供插件读取自身元数据。 */
    private final AgentPluginManifest manifest;

    /** 共享钩子注册表，插件钩子统一写入此处。 */
    private final AgentHookRegistry hookRegistry;

    /** 插件注册事件接收器，负责把组件交给主应用。 */
    private final PluginRegistrationSink sink;

    /**
     * 创建默认插件上下文。
     *
     * @param manifest 当前插件清单。
     * @param hookRegistry 钩子注册表依赖组件。
     * @param sink 注册事件接收器。
     */
    public DefaultAgentPluginContext(
            AgentPluginManifest manifest,
            AgentHookRegistry hookRegistry,
            PluginRegistrationSink sink) {
        this.manifest = manifest;
        this.hookRegistry = hookRegistry;
        this.sink = sink;
    }

    /**
     * 注册插件工具。
     *
     * @param registration 工具注册信息。
     */
    @Override
    public void registerTool(ToolRegistration registration) {
        sink.onToolRegistered(registration);
    }

    /**
     * 注册插件钩子回调。
     *
     * @param hookName 钩子名称。
     * @param callback 钩子回调。
     */
    @Override
    public void registerHook(String hookName, HookCallback callback) {
        hookRegistry.register(hookName, callback);
    }

    /**
     * 注册插件命令。
     *
     * @param name 命令名称。
     * @param handler 命令处理器。
     * @param description 命令展示说明。
     */
    @Override
    public void registerCommand(String name, CommandHandler handler, String description) {
        sink.onCommandRegistered(name, handler, description);
    }

    /**
     * 注册 Web 搜索提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerWebSearchProvider(WebSearchProvider provider) {
        sink.onWebSearchProviderRegistered(provider);
    }

    /**
     * 注册图像生成提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerImageGenProvider(ImageGenProvider provider) {
        sink.onImageGenProviderRegistered(provider);
    }

    /**
     * 注册视频生成提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerVideoGenProvider(VideoGenProvider provider) {
        sink.onVideoGenProviderRegistered(provider);
    }

    /**
     * 注册浏览器提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerBrowserProvider(BrowserProvider provider) {
        sink.onBrowserProviderRegistered(provider);
    }

    /**
     * 注册语音提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerSpeechProvider(SpeechProvider provider) {
        sink.onSpeechProviderRegistered(provider);
    }

    /**
     * 注册语音转写提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerTranscriptionProvider(TranscriptionProvider provider) {
        sink.onTranscriptionProviderRegistered(provider);
    }

    /**
     * 注册记忆提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerMemoryProvider(MemoryProvider provider) {
        sink.onMemoryProviderRegistered(provider);
    }

    /**
     * 注册国内渠道平台适配器。
     *
     * @param registration registration 参数。
     */
    @Override
    public void registerPlatform(PlatformRegistration registration) {
        sink.onPlatformRegistered(registration);
    }

    /**
     * 读取插件配置块。
     *
     * @return 当前版本暂未接入持久化插件配置，固定返回空 Map。
     */
    @Override
    public Map<String, Object> getPluginConfig() {
        return Collections.emptyMap();
    }

    /**
     * 从进程环境变量读取插件配置值。
     *
     * @param key 配置键或映射键。
     * @return 环境变量值，缺失时返回 null。
     */
    @Override
    public String getEnv(String key) {
        return System.getenv(key);
    }

    /**
     * 读取当前插件清单。
     *
     * @return 插件清单元数据。
     */
    @Override
    public AgentPluginManifest getManifest() {
        return manifest;
    }
}
