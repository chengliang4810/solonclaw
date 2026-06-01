package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.pricing.ModelPrice;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.Props;

public class AppConfigProviderLoadTest {
    @Test
    void shouldLoadProvidersAndFallbacksFromRuntimeConfig() throws Exception {
        File runtimeHome = Files.createTempDirectory("jimuqu-provider-load").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  openai-direct:\n"
                        + "    name: OpenAI渠道\n"
                        + "    baseUrl: https://api.openai.com\n"
                        + "    apiKey: test-key\n"
                        + "    defaultModel: gpt-5-mini\n"
                        + "    dialect: openai-responses\n"
                        + "    supportsVision: true\n"
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
                new File(runtimeHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
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
        assertThat(config.getProviders().get("backup").getSupportsVision())
                .isEqualTo(Boolean.FALSE);
        assertThat(config.getFallbackProviders()).hasSize(1);
        assertThat(config.getFallbackProviders().get(0).getProvider()).isEqualTo("backup");
    }

    @Test
    void shouldRejectUnknownFallbackProvider() throws Exception {
        File runtimeHome = Files.createTempDirectory("jimuqu-provider-load-invalid").toFile();
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
                new File(runtimeHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallbackProviders");
    }

    @Test
    void shouldLoadPricingAliasesFromRuntimeConfig() throws Exception {
        File runtimeHome = Files.createTempDirectory("jimuqu-provider-load-pricing").toFile();
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
                        + "      prompt_micros_per_token: 3\n"
                        + "      completion_micros_per_token: 15\n"
                        + "      source: alias-catalog\n",
                new File(runtimeHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());
        AppConfig config = AppConfig.load(props);

        assertThat(config.getPricing().getPrices()).hasSize(1);
        ModelPrice price = config.getPricing().getPrices().get(0);
        assertThat(price.getInputMicrosPerToken()).isEqualTo(3L);
        assertThat(price.getOutputMicrosPerToken()).isEqualTo(15L);
        assertThat(price.getPromptMicrosPerToken()).isEqualTo(3L);
        assertThat(price.getCompletionMicrosPerToken()).isEqualTo(15L);
        assertThat(price.getSource()).isEqualTo("alias-catalog");
    }

    @Test
    void shouldRejectMalformedProviderBaseUrl() throws Exception {
        File runtimeHome = Files.createTempDirectory("jimuqu-provider-load-bad-url").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  local:\n"
                        + "    name: 本地模型\n"
                        + "    baseUrl: http://127.0.0.1:6153export\n"
                        + "    defaultModel: test-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: local\n",
                new File(runtimeHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider.baseUrl")
                .hasMessageContaining("local");
    }

    @Test
    void shouldRejectProviderBaseUrlWithUserInfo() throws Exception {
        File runtimeHome = Files.createTempDirectory("jimuqu-provider-load-userinfo").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  custom:\n"
                        + "    name: 自定义渠道\n"
                        + "    baseUrl: https://user:pass@api.example.com/v1\n"
                        + "    defaultModel: test-model\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: custom\n",
                new File(runtimeHome, "config.yml"));

        Props props = new Props();
        props.put("solonclaw.runtime.home", runtimeHome.getAbsolutePath());

        assertThatThrownBy(() -> AppConfig.load(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider.baseUrl")
                .hasMessageContaining("userinfo");
    }
}
