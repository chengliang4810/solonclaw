package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.hook.HookCallback;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import java.util.Map;

/** 插件注册门面。插件通过此接口注册工具、钩子、Provider 等。 */
public interface AgentPluginContext {

    void registerTool(ToolRegistration registration);

    void registerHook(String hookName, HookCallback callback);

    void registerCommand(String name, CommandHandler handler, String description);

    default void registerCommand(String name, CommandHandler handler) {
        registerCommand(name, handler, "");
    }

    void registerWebSearchProvider(WebSearchProvider provider);

    void registerImageGenProvider(ImageGenProvider provider);

    void registerVideoGenProvider(VideoGenProvider provider);

    void registerBrowserProvider(BrowserProvider provider);

    void registerMemoryProvider(MemoryProvider provider);

    void registerPlatform(PlatformRegistration registration);

    Map<String, Object> getPluginConfig();

    String getEnv(String key);

    AgentPluginManifest getManifest();
}
