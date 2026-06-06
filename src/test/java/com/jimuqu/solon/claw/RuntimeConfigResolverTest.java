package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.FileUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.gateway.service.ChannelConnectionManager;
import com.jimuqu.solon.claw.gateway.service.GatewayRuntimeRefreshService;
import com.jimuqu.solon.claw.web.DashboardRuntimeConfigService;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class RuntimeConfigResolverTest {
    @Test
    void shouldReadNestedValuesWithCfgGetAndPreserveTypes() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-runtime-config").toFile();
        FileUtil.writeUtf8String(
                "solonclaw:\n"
                        + "  display:\n"
                        + "    runtimeFooter:\n"
                        + "      enabled: true\n"
                        + "      fields:\n"
                        + "        - model\n"
                        + "        - cwd\n"
                        + "  skills:\n"
                        + "    curator:\n"
                        + "      intervalHours: 12\n",
                new File(runtimeHome, "config.yml"));

        RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());

        assertThat(RuntimeConfigResolver.cfgGet("solonclaw.display.runtimeFooter.enabled", false))
                .isEqualTo(Boolean.TRUE);
        assertThat(RuntimeConfigResolver.cfgGet("solonclaw.skills.curator.intervalHours", 0))
                .isEqualTo(12);
        assertThat(RuntimeConfigResolver.cfgGet("missing.path", "fallback")).isEqualTo("fallback");
        assertThat(RuntimeConfigResolver.getRawValue("solonclaw.display.runtimeFooter.fields"))
                .isEqualTo(java.util.Arrays.asList("model", "cwd"));
    }

    @Test
    void shouldNotExposeRuntimeHomeAsRuntimeConfigKey() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-runtime-home").toFile();
        FileUtil.writeUtf8String(
                "solonclaw:\n" + "  runtime:\n" + "    home: /tmp/other-runtime\n",
                new File(runtimeHome, "config.yml"));

        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());

        assertThat(resolver.get("solonclaw.runtime.home")).isNull();
        assertThatThrownBy(() -> resolver.setFileValue("solonclaw.runtime.home", "runtime2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported config key");
    }

    @Test
    void shouldReportConfigDriftWithoutLeakingSecrets() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-config-drift").toFile();
        FileUtil.writeUtf8String(
                "provider: stale-root-provider\n"
                        + "solonclaw:\n"
                        + "  runtime:\n"
                        + "    home: /tmp/ignored-runtime\n"
                        + "  scheduler:\n"
                        + "    tickSeconds: \"bad-number\"\n"
                        + "  mystery:\n"
                        + "    enabled: true\n"
                        + "providers:\n"
                        + "  default:\n"
                        + "    name: DefaultProvider\n"
                        + "    baseUrl: https://api.openai.com\n"
                        + "    apiKey: sk-configdriftdiagnostic12345\n"
                        + "    defaultModel: gpt-5.4\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "  default: gpt-5.4\n",
                new File(runtimeHome, "config.yml"));

        AppConfig config = new AppConfig();
        config.setScheduler(new AppConfig.SchedulerConfig());
        config.getScheduler().setTickSeconds(60);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-5.4");
        config.setProviders(new java.util.LinkedHashMap<String, AppConfig.ProviderConfig>());
        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());

        Map<String, Object> diagnostics = resolver.diagnostics(config);
        String text = String.valueOf(diagnostics);

        assertThat(diagnostics.get("config_file")).isEqualTo("runtime://config.yml");
        assertThat(text).doesNotContain(runtimeHome.getAbsolutePath());
        assertThat(text)
                .contains("solonclaw.mystery.enabled")
                .contains("provider")
                .contains("solonclaw.runtime.home")
                .contains("solonclaw.scheduler.tickSeconds")
                .contains("raw_value")
                .contains("effective_value")
                .doesNotContain("sk-configdriftdiagnostic12345");
        assertThat(diagnostics).containsEntry("unknown_count", 3);
        assertThat(diagnostics).doesNotContainKey("legacy_keys");
        assertThat(diagnostics).doesNotContainKey("legacy_count");
        assertThat(diagnostics).containsEntry("effective_diff_count", 1);
    }

    @Test
    void shouldRejectLegacyRuntimeConfigAliases() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-runtime-legacy").toFile();
        FileUtil.writeUtf8String(
                "jimuqu:\n"
                        + "  terminal:\n"
                        + "    sudoPassword: legacy-pass\n"
                        + "tool_output:\n"
                        + "  max_bytes: 9\n"
                        + "browser:\n"
                        + "  allow_private_urls: false\n",
                new File(runtimeHome, "config.yml"));

        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());

        assertThat(resolver.get("jimuqu.terminal.sudoPassword")).isNull();
        assertThat(resolver.get("tool_output.max_bytes")).isNull();
        assertThat(resolver.get("browser.allow_private_urls")).isNull();
        assertThatThrownBy(() -> resolver.setFileValue("tool_output.max_bytes", "10"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported config key");
    }

    @Test
    void shouldWriteFallbackProvidersAsYamlList() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-runtime-fallback-list").toFile();
        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());
        List<Map<String, String>> chain = new ArrayList<Map<String, String>>();
        Map<String, String> fallback = new LinkedHashMap<String, String>();
        fallback.put("provider", "backup");
        fallback.put("model", "backup-model");
        chain.add(fallback);

        resolver.setFileList("fallbackProviders", chain);

        Object raw = resolver.getRaw("fallbackProviders");
        String file = FileUtil.readUtf8String(new File(runtimeHome, "config.yml"));
        assertThat(raw).isInstanceOf(List.class);
        assertThat(file)
                .contains("fallbackProviders:")
                .contains("provider: backup")
                .contains("model: backup-model")
                .doesNotContain("0:");
    }

    @Test
    void shouldRejectFallbackProviderIndexedScalarWrites() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-runtime-fallback-indexed").toFile();
        RuntimeConfigResolver resolver =
                RuntimeConfigResolver.initialize(runtimeHome.getAbsolutePath());

        assertThatThrownBy(() -> resolver.setFileValue("fallbackProviders.0.provider", "backup"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported config key");
    }

    @Test
    void shouldSeparateRuntimeConfigNonSecretWritesSecretUpdatesAndReveal() throws Exception {
        File runtimeHome = Files.createTempDirectory("solonclaw-runtime-safety").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.getAbsolutePath());
        DashboardRuntimeConfigService service =
                new DashboardRuntimeConfigService(
                        config,
                        new GatewayRuntimeRefreshService(
                                config, new ChannelConnectionManager(Collections.emptyMap())));

        service.writeNonSecret("solonclaw.react.maxSteps", "9", false);
        assertThat(RuntimeConfigResolver.getValue("solonclaw.react.maxSteps")).isEqualTo("9");

        assertThatThrownBy(
                        () ->
                                service.writeNonSecret(
                                        "providers.default.apiKey", "sk-write-secret-12345", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密钥配置");

        assertThatThrownBy(() -> service.updateSecret("solonclaw.react.maxSteps", "10", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是密钥配置");

        service.updateSecret("providers.default.apiKey", "sk-runtime-secret-12345", false);
        assertThat(String.valueOf(service.getConfigItems().get("providers.default.apiKey")))
                .contains("redacted_value")
                .doesNotContain("sk-runtime-secret-12345");
        assertThat(service.reveal("providers.default.apiKey"))
                .containsEntry("value", "sk-runtime-secret-12345");

        assertThatThrownBy(
                        () ->
                                service.updateSecret(
                                        "providers.default.apiKey", "sk-runti...2345", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("占位符密钥");
        assertThatThrownBy(() -> service.reveal("solonclaw.react.maxSteps"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not revealable");
    }
}
