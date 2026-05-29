package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.hook.HookCallback;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import java.util.Collections;
import java.util.Map;

/** AgentPluginContext 默认实现。将注册操作委托给 HookRegistry 和 RegistrationSink。 */
public class DefaultAgentPluginContext implements AgentPluginContext {
    private final AgentPluginManifest manifest;
    private final AgentHookRegistry hookRegistry;
    private final PluginRegistrationSink sink;

    public DefaultAgentPluginContext(AgentPluginManifest manifest, AgentHookRegistry hookRegistry,
                                     PluginRegistrationSink sink) {
        this.manifest = manifest;
        this.hookRegistry = hookRegistry;
        this.sink = sink;
    }

    @Override
    public void registerTool(ToolRegistration registration) {
        sink.onToolRegistered(registration);
    }

    @Override
    public void registerHook(String hookName, HookCallback callback) {
        hookRegistry.register(hookName, callback);
    }

    @Override
    public void registerCommand(String name, CommandHandler handler, String description) {
        sink.onCommandRegistered(name, handler, description);
    }

    @Override
    public void registerWebSearchProvider(WebSearchProvider provider) {
        sink.onWebSearchProviderRegistered(provider);
    }

    @Override
    public void registerImageGenProvider(ImageGenProvider provider) {
        sink.onImageGenProviderRegistered(provider);
    }

    @Override
    public void registerVideoGenProvider(VideoGenProvider provider) {
        sink.onVideoGenProviderRegistered(provider);
    }

    @Override
    public void registerBrowserProvider(BrowserProvider provider) {
        sink.onBrowserProviderRegistered(provider);
    }

    @Override
    public void registerMemoryProvider(MemoryProvider provider) {
        sink.onMemoryProviderRegistered(provider);
    }

    @Override
    public void registerPlatform(PlatformRegistration registration) {
        sink.onPlatformRegistered(registration);
    }

    @Override
    public Map<String, Object> getPluginConfig() {
        return Collections.emptyMap();
    }

    @Override
    public String getEnv(String key) {
        return System.getenv(key);
    }

    @Override
    public AgentPluginManifest getManifest() {
        return manifest;
    }
}
