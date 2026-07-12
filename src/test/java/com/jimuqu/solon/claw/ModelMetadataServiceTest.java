package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.ModelMetadata;
import com.jimuqu.solon.claw.support.LlmProviderService;
import com.jimuqu.solon.claw.support.ModelContextCacheStore;
import com.jimuqu.solon.claw.support.ModelMetadataService;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ModelMetadataServiceTest {
    @Test
    void shouldStripProviderPrefixWhenResolvingAliasesAndContextWindow() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(4096);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("openai/gpt-5.4");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.getModel()).isEqualTo("openai/gpt-5.4");
        assertThat(metadata.getAliases()).contains("gpt");
        assertThat(metadata.getContextWindow()).isEqualTo(1050000);
    }

    @Test
    void shouldStripColonProviderPrefixWhenResolvingAliasesAndContextWindow() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(4096);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("openai:gpt-5.4");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.getModel()).isEqualTo("openai:gpt-5.4");
        assertThat(metadata.getAliases()).contains("gpt");
        assertThat(metadata.getContextWindow()).isEqualTo(1050000);
        assertThat(metadata.isSupportsVision()).isTrue();
    }

    @Test
    void shouldPreserveOllamaModelTagsWhenPrefixLooksLikeProvider() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(4096);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("qwen:7b");
        provider.setDialect("ollama");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.getModel()).isEqualTo("qwen:7b");
        assertThat(metadata.getAliases()).contains("qwen");
        assertThat(metadata.getContextWindow()).isEqualTo(32768);
    }

    @Test
    void shouldPreserveOllamaLatestTagWhenPrefixLooksLikeProvider() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(4096);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("qwen:latest");
        provider.setDialect("ollama");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.getAliases()).contains("qwen");
        assertThat(metadata.getContextWindow()).isEqualTo(32768);
    }

    @Test
    void shouldResolveBroadContextFallbacksForCommonModelFamilies() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(4096);

        assertThat(resolveContext(config, "anthropic", "claude-sonnet-4.8")).isEqualTo(1000000);
        assertThat(resolveContext(config, "openai", "gpt-4.1-mini")).isEqualTo(1047576);
        assertThat(resolveContext(config, "openai", "gpt-5.4-mini")).isEqualTo(400000);
        assertThat(resolveContext(config, "gemini", "gemini-2.5-pro")).isEqualTo(1000000);
        assertThat(resolveContext(config, "openai", "deepseek-reasoner")).isEqualTo(1000000);
        assertThat(resolveContext(config, "openai", "grok-4-fast-reasoning")).isEqualTo(2000000);
    }

    /** 显式全局窗口必须覆盖运行时按模型解析。 */
    @Test
    void shouldKeepExplicitRuntimeContextWindowOverride() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(123456);

        int contextWindow =
                new ModelMetadataService(config)
                        .resolveContextWindowForRuntime(
                                "backup",
                                "anthropic",
                                "https://api.anthropic.com",
                                "anthropic/claude-sonnet-4");

        assertThat(contextWindow).isEqualTo(123456);
    }

    /** fallback 必须跳过主模型显式窗口，并继续使用注入服务的持久化缓存解析链。 */
    @Test
    void shouldResolveFallbackWindowThroughInjectedMetadataChain(@TempDir Path tempDir) {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(123456);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setBaseUrl("https://models.example.test/v1");
        provider.setDialect("openai");
        provider.setDefaultModel("unknown-runtime-model");
        config.getProviders().put("main", provider);
        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("main");
        fallback.setModel("unknown-runtime-model");
        config.setFallbackProviders(Collections.singletonList(fallback));
        ModelContextCacheStore cacheStore = new ModelContextCacheStore(tempDir.toFile());
        cacheStore.save("unknown-runtime-model", provider.getBaseUrl(), 777777);
        LlmProviderService service =
                new LlmProviderService(
                        config, new ModelMetadataService(config, null, cacheStore));

        assertThat(service.resolveProvider("main", null).getContextWindowTokens())
                .isEqualTo(123456);
        assertThat(service.resolveFallbackProviders().get(0).getContextWindowTokens())
                .isEqualTo(777777);
    }

    /** 非法兜底值不得泄漏到运行时预算。 */
    @Test
    void shouldNormalizeInvalidRuntimeFallbackContextWindow() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setContextFallbackTokens(-1);

        int contextWindow =
                new ModelMetadataService(config)
                        .resolveContextWindowForRuntime(
                                "custom",
                                "openai",
                                "https://models.example.test/v1",
                                "unknown-runtime-model");

        assertThat(contextWindow).isEqualTo(256000);
    }

    @Test
    void shouldUseConservativeFallbackContextTierForUnknownModels() {
        AppConfig config = new AppConfig();
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(4096);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("custom/unknown-small-model");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.getContextWindow()).isEqualTo(256000);
    }

    @Test
    void shouldUseConservativeVisionCapabilityForUnknownModels() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("custom/unknown-small-model");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsVision()).isFalse();
    }

    @Test
    void shouldLetProviderConfigOverrideVisionCapability() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("custom/unknown-small-model");
        provider.setDialect("openai");
        provider.setSupportsVision(Boolean.TRUE);

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsVision()).isTrue();

        provider.setDefaultModel("gpt-4o");
        provider.setSupportsVision(Boolean.FALSE);

        metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsVision()).isFalse();
    }

    @Test
    void shouldRecognizeKnownVisionModelsConservatively() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("gpt-4o");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsVision()).isTrue();
    }

    @Test
    void shouldRecognizeReasoningModelsConservatively() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("o1-mini");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsReasoning()).isTrue();

        provider.setDefaultModel("x-ai/grok-4.3");
        metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsReasoning()).isTrue();
    }

    @Test
    void shouldRecognizeQwenVlAsVisionModel() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("qwen2.5-vl-72b-instruct");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsVision()).isTrue();
    }

    @Test
    void shouldExposeMultimodalAttachmentAndSourceCapabilityFlags() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("gemini-2.5-pro");
        provider.setDialect("gemini");
        provider.setBaseUrl("https://generativelanguage.googleapis.com/v1beta");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("gemini-main", provider);

        assertThat(metadata.isSupportsVision()).isTrue();
        assertThat(metadata.isSupportsAudio()).isTrue();
        assertThat(metadata.isSupportsAttachment()).isTrue();
        assertThat(metadata.isSupportsMultimodal()).isTrue();
        assertThat(metadata.isSupportsPdf()).isTrue();
        assertThat(metadata.getInputModalities())
                .containsExactly("text", "image", "pdf", "audio", "file");
        assertThat(metadata.getOutputModalities()).containsExactly("text");
        assertThat(metadata.getApiUrl())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(metadata.getModelListUrl())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models");
        assertThat(metadata.getSource()).isEqualTo("provider_config");
        assertThat(metadata.getProvenance()).isEqualTo("provider_config:base_url");
    }

    @Test
    void shouldKeepTextOnlyUnknownModelsOutOfMultimodalFlags() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("custom/unknown-small-model");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.getInputModalities()).containsExactly("text");
        assertThat(metadata.getOutputModalities()).containsExactly("text");
        assertThat(metadata.isSupportsAudio()).isFalse();
        assertThat(metadata.isSupportsAttachment()).isFalse();
        assertThat(metadata.isSupportsMultimodal()).isFalse();
        assertThat(metadata.getSource()).isEqualTo("static_inference");
        assertThat(metadata.getProvenance()).isEqualTo("static_inference:model_family");
    }

    @Test
    void shouldResolveJimuquModelCapabilitiesFromProviderConfig() {
        AppConfig config = new AppConfig();
        config.getModel().setProviderKey("anthropic-main");
        config.getLlm().setContextWindowTokens(0);
        config.getLlm().setMaxTokens(8192);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("claude-sonnet-4");
        provider.setDialect("anthropic");

        ModelMetadata metadata =
                new ModelMetadataService(config).resolve("anthropic-main", provider);

        assertThat(metadata.getProvider()).isEqualTo("anthropic-main");
        assertThat(metadata.getModel()).isEqualTo("claude-sonnet-4");
        assertThat(metadata.getAliases()).contains("claude", "sonnet");
        assertThat(metadata.getContextWindow()).isEqualTo(200000);
        assertThat(metadata.getMaxOutput()).isEqualTo(8192);
        assertThat(metadata.isSupportsTools()).isTrue();
        assertThat(metadata.isSupportsVision()).isTrue();
        assertThat(metadata.isSupportsAttachment()).isTrue();
        assertThat(metadata.isSupportsMultimodal()).isTrue();
        assertThat(metadata.isSupportsReasoning()).isTrue();
        assertThat(metadata.isSupportsInterleaved()).isTrue();
        assertThat(metadata.isSupportsPromptCache()).isTrue();
        assertThat(metadata.getInputModalities()).containsExactly("text", "image", "pdf");
        assertThat(metadata.getOutputModalities()).containsExactly("text");
        assertThat(metadata.getApiUrl()).isEmpty();
        assertThat(metadata.getModelListUrl()).isEmpty();
        assertThat(metadata.getSource()).isEqualTo("static_inference");
        assertThat(metadata.getProvenance()).isEqualTo("static_inference:model_family");
        assertThat(metadata.isDefaultModel()).isTrue();
        assertThat(metadata.isSupported()).isTrue();
    }

    @Test
    void shouldKeepKnownVisionModelsStableAcrossStaticFlagsAndModalities() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("gpt-4o");
        provider.setDialect("openai");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsVision()).isTrue();
        assertThat(metadata.isSupportsAttachment()).isTrue();
        assertThat(metadata.isSupportsMultimodal()).isTrue();
        assertThat(metadata.isSupportsPdf()).isTrue();
        assertThat(metadata.isSupportsStructuredOutput()).isTrue();
        assertThat(metadata.getInputModalities()).containsExactly("text", "image", "pdf");
        assertThat(metadata.getOutputModalities()).containsExactly("text");
        assertThat(metadata.getProvenance()).isEqualTo("static_inference:model_family");
    }

    @Test
    void shouldResolveFineGrainedCapabilitiesForRepresentativeModels() {
        AppConfig config = new AppConfig();

        ModelMetadata gemini = resolveMetadata(config, "gemini", "gemini-2.5-pro");
        assertThat(gemini.isSupportsPdf()).isTrue();
        assertThat(gemini.isSupportsStructuredOutput()).isTrue();
        assertThat(gemini.getInputModalities())
                .containsExactly("text", "image", "pdf", "audio", "file");

        ModelMetadata qwen = resolveMetadata(config, "openai", "qwen2.5-vl-72b-instruct");
        assertThat(qwen.isSupportsPdf()).isTrue();
        assertThat(qwen.isSupportsOpenWeights()).isTrue();
        assertThat(qwen.isSupportsStructuredOutput()).isTrue();
        assertThat(qwen.isSupportsVision()).isTrue();
        assertThat(qwen.isSupportsAttachment()).isTrue();
        assertThat(qwen.getInputModalities()).containsExactly("text", "image", "pdf");

        ModelMetadata claude = resolveMetadata(config, "anthropic", "claude-sonnet-4.8");
        assertThat(claude.isSupportsPdf()).isTrue();
        assertThat(claude.isSupportsInterleaved()).isTrue();
        assertThat(claude.isSupportsVision()).isTrue();
        assertThat(claude.getInputModalities()).containsExactly("text", "image", "pdf");
    }

    @Test
    void shouldKeepUnknownTextModelsOutOfFineGrainedCapabilities() {
        AppConfig config = new AppConfig();

        ModelMetadata metadata = resolveMetadata(config, "openai", "custom/unknown-small-model");

        assertThat(metadata.isSupportsPdf()).isFalse();
        assertThat(metadata.isSupportsStructuredOutput()).isFalse();
        assertThat(metadata.isSupportsOpenWeights()).isFalse();
        assertThat(metadata.isSupportsInterleaved()).isFalse();
        assertThat(metadata.isSupportsVision()).isFalse();
        assertThat(metadata.isSupportsAudio()).isFalse();
        assertThat(metadata.isSupportsAttachment()).isFalse();
        assertThat(metadata.isSupportsMultimodal()).isFalse();
        assertThat(metadata.getInputModalities()).containsExactly("text");
    }

    @Test
    void shouldPreferExplicitProviderCapabilitiesOverModelNameInference() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("gpt-5.4");
        provider.setDialect("openai-responses");
        provider.getCapabilities().put("tool_calling", Boolean.FALSE);
        provider.getCapabilities().put("vision", Boolean.FALSE);
        provider.getCapabilities().put("audio", Boolean.TRUE);
        provider.getCapabilities().put("attachment", Boolean.FALSE);
        provider.getCapabilities().put("pdf", Boolean.FALSE);
        provider.getCapabilities().put("reasoning", Boolean.FALSE);
        provider.getCapabilities().put("structured_output", Boolean.FALSE);
        provider.getCapabilities().put("open_weights", Boolean.TRUE);
        provider.getCapabilities().put("interleaved", Boolean.TRUE);
        provider.getCapabilities().put("prompt_cache", Boolean.FALSE);
        provider.getCapabilities().put("streaming", Boolean.FALSE);

        ModelMetadata metadata = new ModelMetadataService(config).resolve("default", provider);

        assertThat(metadata.isSupportsTools()).isFalse();
        assertThat(metadata.isSupportsVision()).isFalse();
        assertThat(metadata.isSupportsAudio()).isTrue();
        assertThat(metadata.isSupportsAttachment()).isFalse();
        assertThat(metadata.isSupportsPdf()).isFalse();
        assertThat(metadata.isSupportsReasoning()).isFalse();
        assertThat(metadata.isSupportsStructuredOutput()).isFalse();
        assertThat(metadata.isSupportsOpenWeights()).isTrue();
        assertThat(metadata.isSupportsInterleaved()).isTrue();
        assertThat(metadata.isSupportsPromptCache()).isFalse();
        assertThat(metadata.isSupportsStreaming()).isFalse();
        assertThat(metadata.getInputModalities()).containsExactly("text", "audio");
        assertThat(metadata.getSource()).isEqualTo("provider_config");
        assertThat(metadata.getProvenance())
                .isEqualTo("provider_config:capabilities_with_static_fallback");
    }

    @Test
    void shouldResolveProviderAwareModelListUrlForOpenAiCompatibleHosts() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("moonshot-v1-8k");
        provider.setDialect("openai");
        provider.setBaseUrl("https://api.moonshot.ai/v1");

        ModelMetadata metadata =
                new ModelMetadataService(config).resolve("moonshot-main", provider);

        assertThat(metadata.getApiUrl()).isEqualTo("https://api.moonshot.ai/v1/chat/completions");
        assertThat(metadata.getModelListUrl()).isEqualTo("https://api.moonshot.ai/v1/models");
        assertThat(metadata.getSource()).isEqualTo("provider_config");
    }

    @Test
    void shouldUseProviderKeyWhenResolvingProviderAwareModelListUrl() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel("custom-model");
        provider.setDialect("openai");
        provider.setBaseUrl("https://proxy.example.test/openrouter");

        ModelMetadata metadata = new ModelMetadataService(config).resolve("openrouter", provider);

        assertThat(metadata.getModelListUrl())
                .isEqualTo("https://proxy.example.test/openrouter/models");
    }

    private int resolveContext(AppConfig config, String dialect, String model) {
        return resolveMetadata(config, dialect, model).getContextWindow();
    }

    private ModelMetadata resolveMetadata(AppConfig config, String dialect, String model) {
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDefaultModel(model);
        provider.setDialect(dialect);
        return new ModelMetadataService(config).resolve("default", provider);
    }
}
