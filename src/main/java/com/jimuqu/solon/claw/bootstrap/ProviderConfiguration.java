package com.jimuqu.solon.claw.bootstrap;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.media.OpenAiImageProvider;
import com.jimuqu.solon.claw.media.XaiImageProvider;
import com.jimuqu.solon.claw.provider.BrowserProvider;
import com.jimuqu.solon.claw.provider.ImageGenProvider;
import com.jimuqu.solon.claw.provider.SpeechProvider;
import com.jimuqu.solon.claw.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.provider.WebSearchProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;

/** 注册内置能力提供方，不加载运行时扩展代码。 */
@Configuration
public class ProviderConfiguration {
    /** 注册当前内置的图片生成实现。 */
    @Bean
    public List<ImageGenProvider> imageGenProviders(AppConfig appConfig) {
        return Arrays.<ImageGenProvider>asList(
                new OpenAiImageProvider(() -> providerApiKey(appConfig, "openai")),
                new XaiImageProvider(() -> providerApiKey(appConfig, "xai")));
    }

    /**
     * 从当前运行时已经装配的全局 Provider 注册表读取图片能力密钥。
     *
     * @param appConfig 当前 Profile 配置快照。
     * @param providerKey Provider 键。
     * @return 已去除首尾空白的密钥；未配置时返回空文本。
     */
    private String providerApiKey(AppConfig appConfig, String providerKey) {
        AppConfig.ProviderConfig provider =
                appConfig == null || appConfig.getProviders() == null
                        ? null
                        : appConfig.getProviders().get(providerKey);
        return provider == null ? "" : StrUtil.nullToEmpty(provider.getApiKey()).trim();
    }

    /** 浏览器提供方改由内置实现显式注册；当前没有可用实现。 */
    @Bean
    public List<BrowserProvider> browserProviders() {
        return Collections.emptyList();
    }

    /** Web 搜索默认使用 Solon AI 内置后端，不注册额外实现。 */
    @Bean
    public List<WebSearchProvider> webSearchProviders() {
        return Collections.emptyList();
    }

    /** TTS 由 SpeechService 自身的内置实现提供。 */
    @Bean
    public List<SpeechProvider> speechProviders() {
        return Collections.emptyList();
    }

    /** 语音转写由 SpeechService 自身的内置实现提供。 */
    @Bean
    public List<TranscriptionProvider> transcriptionProviders() {
        return Collections.emptyList();
    }
}
