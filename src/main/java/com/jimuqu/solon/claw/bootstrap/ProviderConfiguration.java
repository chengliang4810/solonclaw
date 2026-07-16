package com.jimuqu.solon.claw.bootstrap;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.media.OpenAiImageProvider;
import com.jimuqu.solon.claw.media.XaiImageProvider;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
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
        RuntimeConfigResolver configResolver =
                RuntimeConfigResolver.open(appConfig.getRuntime().getHome());
        return Arrays.<ImageGenProvider>asList(
                new OpenAiImageProvider(
                        () ->
                                StrUtil.blankToDefault(
                                        configResolver.get("providers.openai.apiKey"),
                                        ProfileRuntimeScope.environmentValue("OPENAI_API_KEY"))),
                new XaiImageProvider(
                        () ->
                                StrUtil.blankToDefault(
                                        configResolver.get("providers.xai.apiKey"),
                                        ProfileRuntimeScope.environmentValue("XAI_API_KEY"))));
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
