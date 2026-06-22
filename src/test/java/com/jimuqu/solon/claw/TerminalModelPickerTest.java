package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import org.junit.jupiter.api.Test;

public class TerminalModelPickerTest {
    @Test
    void shouldRenderCurrentAndFallbackModels() {
        TerminalModelPicker picker =
                new TerminalModelPicker(config(), new LlmProviderService(config()));

        String text = picker.render();

        assertThat(text)
                .contains("1. default:gpt-main - Default Provider (当前)")
                .contains("2. backup:gpt-backup - Backup Provider")
                .contains("/model pick <编号>");
    }

    @Test
    void shouldResolvePickerNumberToModelCommand() {
        TerminalModelPicker picker =
                new TerminalModelPicker(config(), new LlmProviderService(config()));

        assertThat(picker.isPickerCommand("/models")).isTrue();
        assertThat(picker.isPickerCommand("/model pick 2")).isTrue();
        assertThat(picker.resolveCommand("/model pick 2")).isEqualTo("/model backup:gpt-backup");
        assertThat(picker.resolveCommand("/model pick 9")).isEmpty();
    }

    @Test
    void shouldRenderGroupedProviderChoicesWithoutChangingPickNumbers() {
        AppConfig config = config();
        config.getFallbackProviders().clear();
        AppConfig.ProviderConfig cnProvider = new AppConfig.ProviderConfig();
        cnProvider.setName("Backup China");
        cnProvider.setBaseUrl("https://api.openai.com");
        cnProvider.setDefaultModel("gpt-cn");
        cnProvider.setDialect("openai");
        cnProvider.setGroupId("backup-group");
        cnProvider.setGroupLabel("Backup Group");
        cnProvider.setGroupDescription("Global and China endpoints");
        cnProvider.setDisplayDescription("China endpoint");
        config.getProviders().put("backup-cn", cnProvider);

        AppConfig.ProviderConfig backup = config.getProviders().get("backup");
        backup.setGroupId("backup-group");
        backup.setGroupLabel("Backup Group");
        backup.setGroupDescription("Global and China endpoints");
        backup.setDisplayDescription("Global endpoint");
        AppConfig.FallbackProviderConfig first = new AppConfig.FallbackProviderConfig();
        first.setProvider("backup-cn");
        first.setModel("gpt-cn");
        AppConfig.FallbackProviderConfig second = new AppConfig.FallbackProviderConfig();
        second.setProvider("backup");
        second.setModel("gpt-backup");
        config.getFallbackProviders().add(first);
        config.getFallbackProviders().add(second);
        TerminalModelPicker picker =
                new TerminalModelPicker(config, new LlmProviderService(config));

        String text = picker.render();

        assertThat(text)
                .contains("2. Backup Group - Global and China endpoints")
                .contains("   2. backup:gpt-backup - Global endpoint")
                .contains("   3. backup-cn:gpt-cn - China endpoint");
        assertThat(picker.resolveCommand("/model pick 2")).isEqualTo("/model backup:gpt-backup");
        assertThat(picker.resolveCommand("/model pick 3")).isEqualTo("/model backup-cn:gpt-cn");
    }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        isolateRuntime(config, "terminal-model-picker");
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

    /**
     * 为模型选择器测试隔离 runtime 目录，避免读取本机 runtime/config.yml 覆盖内存配置。
     *
     * @param config 测试配置。
     * @param name runtime 目录名称前缀。
     */
    private void isolateRuntime(AppConfig config, String name) {
        String home = "target/test-runtime/" + name + "-" + System.nanoTime();
        config.getRuntime().setHome(home);
        config.getRuntime().setConfigFile(home + "/config.yml");
    }
}
