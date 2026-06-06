package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.TuiRuntimeProtocolService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TuiRuntimeProtocolServiceTest {
    @Test
    void shouldReportSetupStatusFromSharedProviderConfiguration() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-setup-status");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> missing = service.setupStatus();
        config.getProviders().get("default").setApiKey("Sk-Test-TuiStatusSecret123");
        Map<String, Object> configured = service.setupStatus();

        assertThat(missing)
                .containsEntry("provider_configured", Boolean.FALSE)
                .containsEntry("provider", "default")
                .containsEntry("model", "gpt-main")
                .containsEntry("runtime_config", runtimeHome.resolve("config.yml").toString());
        assertThat(configured)
                .containsEntry("provider_configured", Boolean.TRUE)
                .containsEntry("api_key", "configured");
        assertThat(String.valueOf(configured)).doesNotContain("Sk-Test-TuiStatusSecret123");
    }

    @Test
    void shouldExposeModelOptionsWithoutLeakingSecrets() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-model-options");
        AppConfig config = config(runtimeHome);
        config.getProviders().get("default").setApiKey("Sk-Test-TuiOptionsSecret123");
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> options = service.modelOptions(null);

        assertThat(options).containsEntry("model", "gpt-main");
        List<Map<String, Object>> providers = providers(options);
        assertThat(providers)
                .extracting(item -> item.get("slug"))
                .contains("default", "openai", "openai-responses", "ollama", "gemini", "anthropic");
        Map<String, Object> activeProvider =
                providers.stream()
                        .filter(item -> "default".equals(item.get("slug")))
                        .findFirst()
                        .orElseThrow();
        assertThat(activeProvider)
                .containsEntry("slug", "default")
                .containsEntry("name", "Default Provider")
                .containsEntry("authenticated", Boolean.TRUE)
                .containsEntry("auth_type", "api_key")
                .containsEntry("is_current", Boolean.TRUE);
        assertThat((List<String>) activeProvider.get("models")).contains("gpt-main");
        assertThat(String.valueOf(options)).doesNotContain("Sk-Test-TuiOptionsSecret123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeSupportedUnconfiguredProviderTemplatesForTuiPicker() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-provider-templates");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> options = service.modelOptions(null);

        List<Map<String, Object>> providers = providers(options);
        assertThat(providers)
                .extracting(item -> item.get("slug"))
                .contains("default", "openai", "openai-responses", "ollama", "gemini", "anthropic");
        Map<String, Object> gemini =
                providers.stream()
                        .filter(item -> "gemini".equals(item.get("slug")))
                        .findFirst()
                        .orElseThrow();
        assertThat(gemini)
                .containsEntry("name", "Gemini")
                .containsEntry("authenticated", Boolean.FALSE)
                .containsEntry("auth_type", "api_key")
                .containsEntry("key_env", "GEMINI_API_KEY")
                .containsEntry("dialect", "gemini")
                .containsEntry("base_url", "https://generativelanguage.googleapis.com");
        assertThat((List<String>) gemini.get("models")).contains("gemini-2.5-pro");
        assertThat(String.valueOf(gemini)).contains("paste GEMINI_API_KEY to activate");
    }

    @Test
    void shouldActivateUnconfiguredProviderTemplateWhenSavingKey() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-provider-template-save");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> result =
                service.modelSaveKey("gemini", "Sk-Test-TuiGeminiSecret123", null);

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(result).containsEntry("ok", Boolean.TRUE);
        assertThat((Map<String, Object>) result.get("provider"))
                .containsEntry("slug", "gemini")
                .containsEntry("authenticated", Boolean.TRUE)
                .containsEntry("default_model", "gemini-2.5-pro");
        assertThat(file)
                .contains("providerKey: gemini")
                .contains("default: gemini-2.5-pro")
                .contains("gemini:")
                .contains("name: Gemini")
                .contains("baseUrl: https://generativelanguage.googleapis.com")
                .contains("apiKey: Sk-Test-TuiGeminiSecret123")
                .contains("defaultModel: gemini-2.5-pro")
                .contains("dialect: gemini");
        assertThat(String.valueOf(result)).doesNotContain("Sk-Test-TuiGeminiSecret123");
    }

    @Test
    void shouldSaveProviderKeyThroughRuntimeSetupService() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-save-key");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> result =
                service.modelSaveKey("default", "Sk-Test-TuiSaveSecret123", null);

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat((Map<String, Object>) result.get("provider"))
                .containsEntry("slug", "default")
                .containsEntry("authenticated", Boolean.TRUE);
        assertThat(String.valueOf(result)).doesNotContain("Sk-Test-TuiSaveSecret123");
        assertThat(file).contains("apiKey: Sk-Test-TuiSaveSecret123");
    }

    @Test
    void shouldPreserveRuntimeSelectedModelWhenSavingProviderKey() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-save-key-current-model");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        service.configSet("model", "mimo-v2.5-pro --provider default", null);
        service.modelSaveKey("default", "Sk-Test-TuiCurrentModelSecret123", null);
        Map<String, Object> full = service.configGet("full");

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat((Map<String, Object>) full.get("config"))
                .containsEntry("model", "mimo-v2.5-pro");
        assertThat(file)
                .contains("default: mimo-v2.5-pro")
                .contains("defaultModel: mimo-v2.5-pro")
                .doesNotContain("defaultModel: gpt-main");
    }

    @Test
    void shouldReadAndWriteConfigValuesForTui() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-config");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> set = service.configSet("model", "mimo-v2.5-pro --provider default", null);
        Map<String, Object> model = service.configGet("model");
        Map<String, Object> full = service.configGet("full");

        assertThat(set).containsEntry("ok", Boolean.TRUE).containsEntry("key", "model");
        assertThat(model).containsEntry("value", "mimo-v2.5-pro");
        assertThat((Map<String, Object>) full.get("config"))
                .containsEntry("model", "mimo-v2.5-pro")
                .containsEntry("provider", "default");
        assertThat(Files.readString(runtimeHome.resolve("config.yml")))
                .contains("providerKey: default")
                .contains("default: mimo-v2.5-pro");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeRuntimeSelectedModelInCurrentProviderOptions() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-current-model");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        service.configSet("model", "mimo-v2.5-pro --provider default", null);
        Map<String, Object> options = service.modelOptions(null);

        assertThat(options).containsEntry("model", "mimo-v2.5-pro");
        List<Map<String, Object>> providers = providers(options);
        assertThat((List<String>) providers.get(0).get("models"))
                .contains("mimo-v2.5-pro")
                .contains("gpt-main");
        assertThat(providers.get(0)).containsEntry("default_model", "mimo-v2.5-pro");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeAndSaveDomesticGatewayChannelsForTui() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-tui-channel");
        AppConfig config = config(runtimeHome);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> options = service.channelOptions();
        Map<String, Object> save =
                service.channelSave(
                        "feishu",
                        mapOf(
                                "enabled",
                                "true",
                                "appId",
                                "tui_test_app",
                                "appSecret",
                                "Sk-Test-TuiChannelSecret123"),
                        null);
        Map<String, Object> status = service.channelStatus("feishu");

        assertThat((List<Map<String, Object>>) options.get("channels"))
                .extracting(item -> item.get("channel"))
                .containsExactly("feishu", "dingtalk", "wecom", "weixin", "qqbot", "yuanbao");
        assertThat((List<Map<String, Object>>) options.get("channels"))
                .allSatisfy(
                        item -> {
                            assertThat(item.get("key")).isInstanceOf(String.class);
                            assertThat(item.get("label")).isInstanceOf(String.class);
                            assertThat((List<Map<String, Object>>) item.get("fields"))
                                    .allSatisfy(
                                            field -> {
                                                assertThat(field.get("key")).isInstanceOf(String.class);
                                                assertThat(field.get("label")).isInstanceOf(String.class);
                                            });
                        });
        assertThat(String.valueOf(options)).doesNotContain("sms").doesNotContain("webhook");
        assertThat(save)
                .containsEntry("ok", Boolean.TRUE)
                .containsEntry("saved", Boolean.TRUE)
                .containsEntry("channel", "feishu")
                .containsEntry("status", "configured");
        assertThat((Map<String, Object>) save.get("values"))
                .containsEntry("enabled", "true")
                .containsEntry("appId", "tui_test_app")
                .containsEntry("appSecret", "***");
        assertThat(status)
                .containsEntry("channel", "feishu")
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("status", "configured");
        assertThat(String.valueOf(save)).doesNotContain("Sk-Test-TuiChannelSecret123");
        assertThat(Files.readString(runtimeHome.resolve("config.yml")))
                .contains("channels:")
                .contains("feishu:")
                .contains("appSecret: Sk-Test-TuiChannelSecret123");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> providers(Map<String, Object> options) {
        return (List<Map<String, Object>>) options.get("providers");
    }

    private AppConfig config(Path runtimeHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getRuntime().setConfigFile(runtimeHome.resolve("config.yml").toString());
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default Provider");
        provider.setBaseUrl("https://api.example.com/v1");
        provider.setDefaultModel("gpt-main");
        provider.setDialect("openai");
        provider.setApiKey("");
        config.getProviders().put("default", provider);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-main");
        config.getLlm().setProvider("default");
        config.getLlm().setApiUrl("https://api.example.com/v1");
        config.getLlm().setModel("gpt-main");
        return config;
    }

    private Map<String, String> mapOf(String... values) {
        Map<String, String> result = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }
}
