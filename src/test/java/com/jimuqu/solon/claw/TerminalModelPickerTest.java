package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalModelPicker;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TerminalModelPickerTest {
    /** 隔离运行时配置目录，避免本机 runtime/config.yml 覆盖测试夹具中的 provider 展示名。 */
    @TempDir
    Path runtimeHome;

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
        config.getRuntime().setHome(runtimeHome.toString());
        config.getRuntime().setConfigFile(runtimeHome.resolve("config.yml").toString());
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
