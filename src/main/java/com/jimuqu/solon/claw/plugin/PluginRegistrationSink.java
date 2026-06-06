package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;

/** 插件注册事件接收器。主应用实现此接口以接收插件注册的组件。 */
public interface PluginRegistrationSink {
    /**
     * 判断是否存在工具。
     *
     * @param name 名称参数。
     * @return 如果工具满足条件则返回 true，否则返回 false。
     */
    default boolean hasTool(String name) {
        return false;
    }

    /**
     * 判断是否存在命令。
     *
     * @param name 名称参数。
     * @return 如果命令满足条件则返回 true，否则返回 false。
     */
    default boolean hasCommand(String name) {
        return false;
    }

    /**
     * 响应工具Registered事件。
     *
     * @param registration registration 参数。
     */
    void onToolRegistered(ToolRegistration registration);

    /**
     * 响应命令Registered事件。
     *
     * @param name 名称参数。
     * @param handler handler 参数。
     * @param description 描述参数。
     */
    void onCommandRegistered(String name, CommandHandler handler, String description);

    /**
     * 响应Web搜索提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    void onWebSearchProviderRegistered(WebSearchProvider provider);

    /**
     * 响应图片Gen提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    void onImageGenProviderRegistered(ImageGenProvider provider);

    /**
     * 响应VideoGen提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    void onVideoGenProviderRegistered(VideoGenProvider provider);

    /**
     * 响应浏览器提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    void onBrowserProviderRegistered(BrowserProvider provider);

    /**
     * 响应语音提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    default void onSpeechProviderRegistered(SpeechProvider provider) {}

    /**
     * 响应转写提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    default void onTranscriptionProviderRegistered(TranscriptionProvider provider) {}

    /**
     * 响应记忆提供方Registered事件。
     *
     * @param provider 模型或能力提供方。
     */
    void onMemoryProviderRegistered(MemoryProvider provider);

    /**
     * 响应平台Registered事件。
     *
     * @param registration registration 参数。
     */
    void onPlatformRegistered(PlatformRegistration registration);
}
