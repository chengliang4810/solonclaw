package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class AppConfigProviderLoadTest {
    @Test
    void shouldLoadRepositoryConfigExample() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-config-example").toFile();
        FileUtil.copy(new File("config.example.yml"), new File(workspaceHome, "config.yml"), true);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getProviders()).containsKeys("default", "local-ollama");
        assertThat(config.getModel().getProviderKey()).isEqualTo("default");
        assertThat(config.getSecurity().isTirithFailOpen()).isTrue();
        assertThat(config.getSecurity().getGuardrailMode()).isEqualTo("approval");
        assertThat(config.getSecurity().getGuardrailCronMode()).isEqualTo("strict");
        assertThat(config.getSecurity().getGuardrailCronScope()).isEqualTo("job");
        assertThat(config.getDashboard().getAccessToken()).isEmpty();
        assertThat(config.getApprovals().getTimeoutSeconds()).isEqualTo(180);
        assertThat(config.getTerminal().getMaxForegroundTimeoutSeconds()).isEqualTo(600);
        assertThat(config.getTask().getMediaCacheTtlHours()).isEqualTo(168);
        assertThat(config.getMcp().isEnabled()).isFalse();
        assertThat(config.getPricing().getPrices()).isEmpty();
    }

    @Test
    void shouldLoadProvidersAndFallbacksFromRuntimeConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-provider-load").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  openai-direct:\n"
                        + "    name: OpenAI渠道\n"
                        + "    baseUrl: https://api.openai.com\n"
                        + "    apiKey: test-key\n"
                        + "    defaultModel: gpt-5-mini\n"
                        + "    dialect: openai-responses\n"
                        + "    supportsVision: true\n"
                        + "    capabilities:\n"
                        + "      reasoning: false\n"
                        + "      prompt_cache: true\n"
                        + "  backup:\n"
                        + "    name: 备用渠道\n"
                        + "    baseUrl: https://backup.example.com#\n"
                        + "    apiKey: backup-key\n"
                        + "    defaultModel: claude-sonnet-4\n"
                        + "    dialect: anthropic\n"
                        + "    supportsVision: false\n"
                        + "model:\n"
                        + "  providerKey: openai-direct\n"
                        + "  default: \n"
                        + "fallbackProviders:\n"
                        + "  - provider: backup\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getProviders()).containsKeys("openai-direct", "backup");
        assertThat(config.getModel().getProviderKey()).isEqualTo("openai-direct");
        assertThat(config.getLlm().getProvider()).isEqualTo("openai-direct");
        assertThat(config.getLlm().getDialect()).isEqualTo("openai-responses");
        assertThat(config.getLlm().getApiUrl()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(config.getLlm().getModel()).isEqualTo("gpt-5-mini");
        assertThat(
                        com.jimuqu.solon.claw.llm.LlmProviderSupport.buildModelListUrl(
                                config.getProviders().get("openai-direct").getBaseUrl(),
                                config.getProviders().get("openai-direct").getDialect()))
                .isEqualTo("https://api.openai.com/v1/models");
        assertThat(
                        com.jimuqu.solon.claw.llm.LlmProviderSupport.buildModelListUrl(
                                config.getProviders().get("backup").getBaseUrl(),
                                config.getProviders().get("backup").getDialect()))
                .isEqualTo("https://backup.example.com/v1/models");
        assertThat(config.getProviders().get("openai-direct").getSupportsVision())
                .isEqualTo(Boolean.TRUE);
        assertThat(config.getProviders().get("openai-direct").getCapabilities())
                .containsEntry("reasoning", Boolean.FALSE)
                .containsEntry("prompt_cache", Boolean.TRUE);
        assertThat(config.getProviders().get("backup").getSupportsVision())
                .isEqualTo(Boolean.FALSE);
        assertThat(config.getFallbackProviders()).hasSize(1);
        assertThat(config.getFallbackProviders().get(0).getProvider()).isEqualTo("backup");
    }

    @Test
    void shouldResolveEffectiveProviderFromRuntimeConfigAfterTerminalSetupWrites()
            throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-provider-runtime").toFile();
        File configFile = new File(workspaceHome, "config.yml");
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    name: 启动配置\n"
                        + "    baseUrl: https://startup.example.com\n"
                        + "    apiKey: startup-key\n"
                        + "    defaultModel: gpt-5.4\n"
                        + "    dialect: openai-responses\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: \"\"\n",
                configFile);

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);
        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(workspaceHome.getAbsolutePath());
        resolver.setFileValue("providers.default.name", "本地工作区配置");
        resolver.setFileValue("providers.default.baseUrl", "https://api.xiaomimimo.com/v1");
        resolver.setFileValue("providers.default.apiKey", "runtime-key");
        resolver.setFileValue("providers.default.defaultModel", "mimo-v2.5-pro");
        resolver.setFileValue("providers.default.dialect", "openai");
        resolver.setFileValue("model.providerKey", "default");
        resolver.setFileValue("model.default", "mimo-v2.5-pro");

        LlmProviderService.ResolvedProvider resolved =
                new LlmProviderService(config).resolveEffectiveProvider(null);

        assertThat(resolved.getProviderKey()).isEqualTo("default");
        assertThat(resolved.getLabel()).isEqualTo("本地工作区配置");
        assertThat(resolved.getBaseUrl()).isEqualTo("https://api.xiaomimimo.com/v1");
        assertThat(resolved.getApiUrl())
                .isEqualTo("https://api.xiaomimimo.com/v1/chat/completions");
        assertThat(resolved.getApiKey()).isEqualTo("runtime-key");
        assertThat(resolved.getDialect()).isEqualTo("openai");
        assertThat(resolved.getModel()).isEqualTo("mimo-v2.5-pro");
    }

    /** 首次启动生成默认 config.yml 时，不能覆盖命令行传入的默认模型提供方配置。 */
    @Test
    void shouldKeepStartupProviderWhenWorkspaceConfigIsCreated() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-provider-startup").toFile();

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        props.put("providers.default.name", "启动提供方");
        props.put("providers.default.baseUrl", "https://startup.example.com/v1");
        props.put("providers.default.apiKey", "startup-provider-key");
        props.put("providers.default.defaultModel", "startup-model");
        props.put("providers.default.dialect", "openai-responses");

        AppConfig config = AppConfig.load(props);
        String runtimeConfig = FileUtil.readUtf8String(new File(workspaceHome, "config.yml"));

        assertThat(new File(workspaceHome, "config.yml")).exists();
        assertThat(runtimeConfig).contains("baseUrl: \"https://startup.example.com/v1\"");
        assertThat(runtimeConfig).contains("defaultModel: \"startup-model\"");
        assertThat(runtimeConfig).contains("dialect: \"openai-responses\"");
        assertThat(runtimeConfig).contains("apiKey: \"\"");
        assertThat(runtimeConfig).doesNotContain("https://api.openai.com");
        assertThat(runtimeConfig).doesNotContain("startup-provider-key");
        assertThat(config.getProviders().get("default").getName()).isEqualTo("启动提供方");
        assertThat(config.getProviders().get("default").getBaseUrl())
                .isEqualTo("https://startup.example.com/v1");
        assertThat(config.getProviders().get("default").getApiKey())
                .isEqualTo("startup-provider-key");
        assertThat(config.getProviders().get("default").getDefaultModel())
                .isEqualTo("startup-model");
        assertThat(config.getProviders().get("default").getDialect()).isEqualTo("openai-responses");

        LlmProviderService.ResolvedProvider resolved =
                new LlmProviderService(config).resolveEffectiveProvider(null);
        assertThat(resolved.getProviderKey()).isEqualTo("default");
        assertThat(resolved.getBaseUrl()).isEqualTo("https://startup.example.com/v1");
        assertThat(resolved.getApiUrl()).isEqualTo("https://startup.example.com/v1/responses");
        assertThat(resolved.getApiKey()).isEqualTo("startup-provider-key");
        assertThat(resolved.getDialect()).isEqualTo("openai-responses");
        assertThat(resolved.getModel()).isEqualTo("startup-model");
    }

    @Test
    void shouldRejectUnknownFallbackProvider() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-provider-load-invalid").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  openai-direct:\n"
                        + "    name: OpenAI渠道\n"
                        + "    baseUrl: https://api.openai.com\n"
                        + "    defaultModel: gpt-5-mini\n"
                        + "    dialect: openai-responses\n"
                        + "model:\n"
                        + "  providerKey: openai-direct\n"
                        + "fallbackProviders:\n"
                        + "  - provider: missing-provider\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallbackProviders");
    }

    @Test
    void shouldLoadPricingAliasesFromRuntimeConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-provider-load-pricing").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  openai-direct:\n"
                        + "    name: OpenAI渠道\n"
                        + "    baseUrl: https://api.openai.com\n"
                        + "    defaultModel: gpt-5-mini\n"
                        + "    dialect: openai-responses\n"
                        + "model:\n"
                        + "  providerKey: openai-direct\n"
                        + "pricing:\n"
                        + "  prices:\n"
                        + "    - provider: openai-direct\n"
                        + "      model: gpt-5-mini\n"
                        + "      input_cost_per_million: \"3.50\"\n"
                        + "      output_cost_per_million: \"15.25\"\n"
                        + "      request_micros_per_request: 100\n"
                        + "      source: alias-catalog\n"
                        + "      sourceUrl: https://pricing.example/config\n"
                        + "      pricingVersion: config-pricing-2026-06\n"
                        + "      fetchedAt: 1800000000000\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getPricing().getPrices()).hasSize(1);
        ModelPrice price = config.getPricing().getPrices().get(0);
        assertThat(price.getInputMicrosPerToken()).isEqualTo(4L);
        assertThat(price.getOutputMicrosPerToken()).isEqualTo(15L);
        assertThat(price.getPromptMicrosPerToken()).isEqualTo(4L);
        assertThat(price.getCompletionMicrosPerToken()).isEqualTo(15L);
        assertThat(price.inputMicrosPerTokenExact().toPlainString()).isEqualTo("3.50");
        assertThat(price.outputMicrosPerTokenExact().toPlainString()).isEqualTo("15.25");
        assertThat(price.getRequestMicrosPerRequest()).isEqualTo(100L);
        assertThat(price.getSource()).isEqualTo("alias-catalog");
        assertThat(price.getSourceUrl()).isEqualTo("https://pricing.example/config");
        assertThat(price.getPricingVersion()).isEqualTo("config-pricing-2026-06");
        assertThat(price.getFetchedAt()).isEqualTo(1800000000000L);
    }

    @Test
    void shouldRejectMalformedProviderBaseUrl() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-provider-load-bad-url").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  local:\n"
                        + "    name: 本地模型\n"
                        + "    baseUrl: http://127.0.0.1:6153export\n"
                        + "    defaultModel: test-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: local\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider.baseUrl")
                .hasMessageContaining("local");
    }

    @Test
    void shouldRejectProviderBaseUrlWithUserInfo() throws Exception {
        File workspaceHome = Files.createTempDirectory("jimuqu-provider-load-userinfo").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  custom:\n"
                        + "    name: 自定义渠道\n"
                        + "    baseUrl: https://user:pass@api.example.com/v1\n"
                        + "    defaultModel: test-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: custom\n",
                new File(workspaceHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider.baseUrl")
                .hasMessageContaining("userinfo");
    }
}
