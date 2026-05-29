package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;

/** 插件注册事件接收器。主应用实现此接口以接收插件注册的组件。 */
public interface PluginRegistrationSink {
    void onToolRegistered(ToolRegistration registration);

    void onCommandRegistered(String name, CommandHandler handler, String description);

    void onWebSearchProviderRegistered(WebSearchProvider provider);

    void onImageGenProviderRegistered(ImageGenProvider provider);

    void onVideoGenProviderRegistered(VideoGenProvider provider);

    void onBrowserProviderRegistered(BrowserProvider provider);

    void onMemoryProviderRegistered(MemoryProvider provider);

    void onPlatformRegistered(PlatformRegistration registration);
}
