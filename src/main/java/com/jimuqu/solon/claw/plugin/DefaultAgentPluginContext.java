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
    /** 记录默认Agent插件上下文中的manifest。 */
    private final AgentPluginManifest manifest;

    /** 记录默认Agent插件上下文中的钩子注册表。 */
    private final AgentHookRegistry hookRegistry;

    /** 记录默认Agent插件上下文中的接收端。 */
    private final PluginRegistrationSink sink;

    /**
     * 创建默认Agent插件上下文实例，并注入运行所需依赖。
     *
     * @param manifest manifest 参数。
     * @param hookRegistry 钩子注册表依赖组件。
     * @param sink sink 参数。
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
     * 注册工具。
     *
     * @param registration registration 参数。
     */
    @Override
    public void registerTool(ToolRegistration registration) {
        sink.onToolRegistered(registration);
    }

    /**
     * 注册钩子。
     *
     * @param hookName 钩子名称参数。
     * @param callback 回调参数。
     */
    @Override
    public void registerHook(String hookName, HookCallback callback) {
        hookRegistry.register(hookName, callback);
    }

    /**
     * 注册命令。
     *
     * @param name 名称参数。
     * @param handler handler 参数。
     * @param description 描述参数。
     */
    @Override
    public void registerCommand(String name, CommandHandler handler, String description) {
        sink.onCommandRegistered(name, handler, description);
    }

    /**
     * 注册Web搜索提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerWebSearchProvider(WebSearchProvider provider) {
        sink.onWebSearchProviderRegistered(provider);
    }

    /**
     * 注册图片Gen提供方。
     *
     * @param provider 模型或能力提供方。
     */
    @Override
    public void registerImageGenProvider(ImageGenProvider provider) {
        sink.onImageGenProviderRegistered(provider);
    }

    /**
     * 注册Video Gen提供方。
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
     * 注册Transcription提供方。
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
     * 注册平台。
     *
     * @param registration registration 参数。
     */
    @Override
    public void registerPlatform(PlatformRegistration registration) {
        sink.onPlatformRegistered(registration);
    }

    /**
     * 读取插件配置。
     *
     * @return 返回读取到的插件配置。
     */
    @Override
    public Map<String, Object> getPluginConfig() {
        return Collections.emptyMap();
    }

    /**
     * 读取Env。
     *
     * @param key 配置键或映射键。
     * @return 返回读取到的Env。
     */
    @Override
    public String getEnv(String key) {
        return System.getenv(key);
    }

    /**
     * 读取Manifest。
     *
     * @return 返回读取到的Manifest。
     */
    @Override
    public AgentPluginManifest getManifest() {
        return manifest;
    }
}
