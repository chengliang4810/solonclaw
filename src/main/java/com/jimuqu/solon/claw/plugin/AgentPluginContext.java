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
     * 注册可被 Agent 调用的函数工具。
     *
     * @param registration 工具名称、schema 和执行函数的完整注册信息。
     */
    void registerTool(ToolRegistration registration);

    /**
     * 注册指定生命周期钩子的回调。
     *
     * @param hookName {@link AgentHookName} 中定义的钩子名称。
     * @param callback 钩子触发时执行的回调。
     */
    void registerHook(String hookName, HookCallback callback);

    /**
     * 注册对话内可触发的插件命令。
     *
     * @param name 命令名称。
     * @param handler 命令执行函数。
     * @param description 给 dashboard 或帮助入口展示的命令说明。
     */
    void registerCommand(String name, CommandHandler handler, String description);

    /**
     * 注册无描述文本的对话内命令。
     *
     * @param name 命令名称。
     * @param handler 命令执行函数。
     */
    default void registerCommand(String name, CommandHandler handler) {
        registerCommand(name, handler, "");
    }

    /**
     * 注册 Web 搜索或 Web 内容抽取提供方。
     *
     * @param provider 可用性检查和搜索执行都由插件自行实现的 Provider。
     */
    void registerWebSearchProvider(WebSearchProvider provider);

    /**
     * 注册图像生成提供方。
     *
     * @param provider 负责把提示词转换为缓存媒体引用的 Provider。
     */
    void registerImageGenProvider(ImageGenProvider provider);

    /**
     * 注册视频生成提供方。
     *
     * @param provider 负责把提示词转换为视频媒体引用的 Provider。
     */
    void registerVideoGenProvider(VideoGenProvider provider);

    /**
     * 注册浏览器自动化提供方。
     *
     * @param provider 执行浏览器任务并返回审计友好结果的 Provider。
     */
    void registerBrowserProvider(BrowserProvider provider);

    /**
     * 注册 TTS 语音合成提供方。
     *
     * @param provider 将文本合成为语音媒体引用的 Provider。
     */
    void registerSpeechProvider(SpeechProvider provider);

    /**
     * 注册独立语音转写提供方。
     *
     * @param provider 将缓存语音附件转写成文本的 Provider。
     */
    void registerTranscriptionProvider(TranscriptionProvider provider);

    /**
     * 注册长期记忆提供方。
     *
     * @param provider 面向 Agent 会话的记忆读写 Provider。
     */
    void registerMemoryProvider(MemoryProvider provider);

    /**
     * 注册国内消息渠道平台适配器。
     *
     * @param registration 平台名称、展示标签、必要环境变量和适配器工厂。
     */
    void registerPlatform(PlatformRegistration registration);

    /**
     * 读取插件配置块。
     *
     * @return 当前版本返回空 Map，后续可承载 dashboard-first setup 写入的配置。
     */
    Map<String, Object> getPluginConfig();

    /**
     * 读取插件运行所需环境变量。
     *
     * @return 环境变量值；缺失时返回 null，调用方不得把值写入日志。
     */
    String getEnv(String key);

    /**
     * 读取当前插件的清单元数据。
     *
     * @return 由 plugin.yaml 解析出的插件清单。
     */
    AgentPluginManifest getManifest();
}
