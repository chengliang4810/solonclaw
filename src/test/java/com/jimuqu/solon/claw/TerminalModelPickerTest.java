package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import org.junit.jupiter.api.Test;

public class TerminalModelPickerTest {
    @Test
    void shouldRenderCurrentAndFallbackModels() {
        TerminalModelPicker picker = new TerminalModelPicker(config(), new LlmProviderService(config()));

        String text = picker.render();

        assertThat(text)
                .contains("1. default:gpt-main - Default Provider (当前)")
                .contains("2. backup:gpt-backup - Backup Provider")
                .contains("/model pick <编号>");
    }

    @Test
    void shouldResolvePickerNumberToModelCommand() {
        TerminalModelPicker picker = new TerminalModelPicker(config(), new LlmProviderService(config()));

        assertThat(picker.isPickerCommand("/models")).isTrue();
        assertThat(picker.isPickerCommand("/model pick 2")).isTrue();
        assertThat(picker.resolveCommand("/model pick 2")).isEqualTo("/model backup:gpt-backup");
        assertThat(picker.resolveCommand("/model pick 9")).isEmpty();
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default Provider");
        provider.setBaseUrl("https://api.openai.com");
        provider.setDefaultModel("gpt-main");
        provider.setDialect("openai");
        config.getProviders().put("default", provider);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-main");

        AppConfig.ProviderConfig backupProvider = new AppConfig.ProviderConfig();
        backupProvider.setName("Backup Provider");
        backupProvider.setBaseUrl("https://api.openai.com");
        backupProvider.setDefaultModel("gpt-backup");
        backupProvider.setDialect("openai");
        config.getProviders().put("backup", backupProvider);

        AppConfig.FallbackProviderConfig fallback = new AppConfig.FallbackProviderConfig();
        fallback.setProvider("backup");
        fallback.setModel("gpt-backup");
        config.getFallbackProviders().add(fallback);
        return config;
    }
}
