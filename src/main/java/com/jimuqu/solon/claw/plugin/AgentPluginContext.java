package com.jimuqu.solon.claw.plugin;

import com.jimuqu.solon.claw.core.service.MemoryProvider;
import com.jimuqu.solon.claw.plugin.hook.HookCallback;
import com.jimuqu.solon.claw.plugin.provider.BrowserProvider;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.plugin.provider.VideoGenProvider;
import com.jimuqu.solon.claw.plugin.provider.WebSearchProvider;
import java.util.Map;

/** 插件注册门面。插件通过此接口注册工具、钩子、Provider 等。 */
public interface AgentPluginContext {

    /**
     * 注册工具。
     *
     * @param registration registration 参数。
     */
    void registerTool(ToolRegistration registration);

    /**
     * 注册钩子。
     *
     * @param hookName 钩子名称参数。
     * @param callback 回调参数。
     */
    void registerHook(String hookName, HookCallback callback);

    /**
     * 注册命令。
     *
     * @param name 名称参数。
     * @param handler handler 参数。
     * @param description 描述参数。
     */
    void registerCommand(String name, CommandHandler handler, String description);

    /**
     * 注册命令。
     *
     * @param name 名称参数。
     * @param handler handler 参数。
     */
    default void registerCommand(String name, CommandHandler handler) {
        registerCommand(name, handler, "");
    }

    /**
     * 注册Web搜索提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerWebSearchProvider(WebSearchProvider provider);

    /**
     * 注册图片Gen提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerImageGenProvider(ImageGenProvider provider);

    /**
     * 注册Video Gen提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerVideoGenProvider(VideoGenProvider provider);

    /**
     * 注册浏览器提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerBrowserProvider(BrowserProvider provider);

    /**
     * 注册语音提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerSpeechProvider(SpeechProvider provider);

    /**
     * 注册Transcription提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerTranscriptionProvider(TranscriptionProvider provider);

    /**
     * 注册记忆提供方。
     *
     * @param provider 模型或能力提供方。
     */
    void registerMemoryProvider(MemoryProvider provider);

    /**
     * 注册平台。
     *
     * @param registration registration 参数。
     */
    void registerPlatform(PlatformRegistration registration);

    /**
     * 读取插件配置。
     *
     * @return 返回读取到的插件配置。
     */
    Map<String, Object> getPluginConfig();

    /**
     * 读取Env。
     *
     * @param key 配置键或映射键。
     * @return 返回读取到的Env。
     */
    String getEnv(String key);

    /**
     * 读取Manifest。
     *
     * @return 返回读取到的Manifest。
     */
    AgentPluginManifest getManifest();
}
