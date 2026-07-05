package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.TuiRuntimeProtocolService;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TuiRuntimeProtocolServiceTest {
    @Test
    void channelOptionsExposeQrSetupCapabilityForImplementedDomesticChannels() throws Exception {
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(testConfig());

        Map<String, Object> response = service.channelOptions();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels =
                (List<Map<String, Object>>) response.get("channels");
        assertThat(channel(channels, "weixin").get("qr_supported")).isEqualTo(Boolean.TRUE);
        assertThat(channel(channels, "feishu").get("qr_supported")).isEqualTo(Boolean.TRUE);
        assertThat(channel(channels, "dingtalk").get("qr_supported")).isEqualTo(Boolean.TRUE);
        assertThat(channel(channels, "wecom").get("qr_supported")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void channelQrStartReturnsSafeUnavailableErrorWhenBackendQrServicesAreNotInjected()
            throws Exception {
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(testConfig());

        Map<String, Object> response = service.channelQrStart("weixin", "session-qr");

        assertThat(response)
                .containsEntry("ok", Boolean.FALSE)
                .containsEntry("channel", "weixin")
                .containsEntry("error", "qr_setup_unavailable")
                .containsEntry("session_id", "session-qr");
    }

    @Test
    void setupAndModelOptionsWarnWhenCurrentOpenaiProviderUsesBlockedPrivateBaseUrl()
            throws Exception {
        AppConfig config = testConfig();
        config.getModel().setProviderKey("openai");
        config.getModel().setDefault("mimo-v2.5");
        config.getSecurity().setAllowPrivateUrls(false);
        AppConfig.ProviderConfig provider = new AppConfig.ProviderConfig();
        provider.setDialect("openai");
        provider.setBaseUrl("http://127.0.0.1:18080/v1/chat/completions");
        provider.setApiKey("tp-valid-local-policy-warning-key");
        provider.setDefaultModel("mimo-v2.5");
        config.getProviders().put("openai", provider);
        TuiRuntimeProtocolService service = new TuiRuntimeProtocolService(config);

        Map<String, Object> setup = service.setupStatus();
        Map<String, Object> options = service.modelOptions("session-local-provider");

        assertThat((String) setup.get("warning"))
                .contains("security.allowPrivateUrls")
                .contains("内网/私有地址");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) options.get("providers");
        assertThat((String) provider(providers, "openai").get("warning"))
                .contains("security.allowPrivateUrls")
                .contains("内网/私有地址");
    }

    private Map<String, Object> channel(List<Map<String, Object>> channels, String key) {
        return channels.stream()
                .filter(item -> key.equals(item.get("key")))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> provider(List<Map<String, Object>> providers, String key) {
        return providers.stream()
                .filter(item -> key.equals(item.get("slug")))
                .findFirst()
                .orElseThrow();
    }

    private AppConfig testConfig() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-tui-protocol").toFile();
        AppConfig config = new AppConfig();
        config.getRuntime().setHome(workspaceHome.getAbsolutePath());
        return config;
    }
}
