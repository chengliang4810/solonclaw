package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.RuntimeSetupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class RuntimeSetupServiceTest {
    @Test
    void shouldPersistModelProviderThroughSharedSetupService() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-runtime-setup-model");
        RuntimeSetupService service = new RuntimeSetupService(config(runtimeHome));

        RuntimeSetupService.ModelSetupRequest request =
                new RuntimeSetupService.ModelSetupRequest();
        request.setProviderKey("local-openai");
        request.setProviderName("Local OpenAI");
        request.setBaseUrl("http://127.0.0.1:9999/v1");
        request.setApiKey("Sk-Test-SharedModelSecret123");
        request.setModel("mimo-v2.5-pro");
        request.setDialect("openai");
        RuntimeSetupService.SetupResult result = service.configureModel(request);

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValues())
                .containsEntry("provider", "local-openai")
                .containsEntry("model", "mimo-v2.5-pro")
                .containsEntry("dialect", "openai");
        assertThat(result.getValues().get("apiKey")).isEqualTo("***");
        assertThat(file)
                .contains("providerKey: local-openai")
                .contains("default: mimo-v2.5-pro")
                .contains("local-openai:")
                .contains("name: Local OpenAI")
                .contains("baseUrl: http://127.0.0.1:9999/v1")
                .contains("apiKey: Sk-Test-SharedModelSecret123")
                .contains("defaultModel: mimo-v2.5-pro")
                .contains("dialect: openai");
    }

    @Test
    void shouldPersistDomesticChannelThroughSharedSetupService() throws Exception {
        Path runtimeHome = Files.createTempDirectory("solonclaw-runtime-setup-channel");
        RuntimeSetupService service = new RuntimeSetupService(config(runtimeHome));
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("enabled", "true");
        values.put("appId", "cli_test_app");
        values.put("appSecret", "Sk-Test-SharedFeishuSecret123");

        RuntimeSetupService.SetupResult result = service.configureGatewayChannel("feishu", values);

        String file = Files.readString(runtimeHome.resolve("config.yml"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getValues())
                .containsEntry("channel", "feishu")
                .containsEntry("enabled", "true")
                .containsEntry("appId", "cli_test_app")
                .containsEntry("appSecret", "***");
        assertThat(file)
                .contains("solonclaw:")
                .contains("channels:")
                .contains("feishu:")
                .contains("enabled: 'true'")
                .contains("appId: cli_test_app")
                .contains("appSecret: Sk-Test-SharedFeishuSecret123");
    }

    private AppConfig config(Path runtimeHome) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(runtimeHome.toString());
        config.getRuntime().setConfigFile(runtimeHome.resolve("config.yml").toString());
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setName("Default Provider");
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setDefaultModel("gpt-main");
        provider.setDialect("openai");
        provider.setApiKey("");
        config.getProviders().put("default", provider);
        config.getModel().setProviderKey("default");
        config.getModel().setDefault("gpt-main");
        config.getLlm().setProvider("default");
        config.getLlm().setApiUrl("https://api.openai.com/v1");
        config.getLlm().setModel("gpt-main");
        return config;
    }
}
