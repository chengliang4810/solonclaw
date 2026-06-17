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
     * 判断主应用侧是否已经存在指定工具名。
     *
     * @return 已存在时返回 true，插件管理器据此阻止重复注册。
     */
    default boolean hasTool(String name) {
        return false;
    }

    /**
     * 判断主应用侧是否已经存在指定命令名。
     *
     * @param name 名称参数。
     * @return 已存在时返回 true，插件管理器据此阻止重复注册。
     */
    default boolean hasCommand(String name) {
        return false;
    }

    /**
     * 接收插件注册的函数工具。
     *
     * @param registration 工具名称、schema 和执行函数。
     */
    void onToolRegistered(ToolRegistration registration);

    /**
     * 接收插件注册的对话命令。
     *
     * @param name 命令名称。
     * @param handler 命令执行函数。
     * @param description 展示说明。
     */
    void onCommandRegistered(String name, CommandHandler handler, String description);

    /**
     * 接收 Web 搜索提供方。
     *
     * @param provider 搜索或抽取 Provider。
     */
    void onWebSearchProviderRegistered(WebSearchProvider provider);

    /**
     * 接收图像生成提供方。
     *
     * @param provider 图像生成 Provider。
     */
    void onImageGenProviderRegistered(ImageGenProvider provider);

    /**
     * 接收视频生成提供方。
     *
     * @param provider 视频生成 Provider。
     */
    void onVideoGenProviderRegistered(VideoGenProvider provider);

    /**
     * 接收浏览器自动化提供方。
     *
     * @param provider 浏览器 Provider。
     */
    void onBrowserProviderRegistered(BrowserProvider provider);

    /**
     * 接收 TTS 语音合成提供方。
     *
     * @param provider 语音合成 Provider。
     */
    default void onSpeechProviderRegistered(SpeechProvider provider) {}

    /**
     * 接收独立语音转写提供方。
     *
     * @param provider 语音转写 Provider。
     */
    default void onTranscriptionProviderRegistered(TranscriptionProvider provider) {}

    /**
     * 接收长期记忆提供方。
     *
     * @param provider 记忆读写 Provider。
     */
    void onMemoryProviderRegistered(MemoryProvider provider);

    /**
     * 接收国内消息渠道平台适配器。
     *
     * @param registration 平台适配器注册信息。
     */
    void onPlatformRegistered(PlatformRegistration registration);
}
