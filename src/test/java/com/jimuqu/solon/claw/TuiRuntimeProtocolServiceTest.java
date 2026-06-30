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

    private Map<String, Object> channel(List<Map<String, Object>> channels, String key) {
        return channels.stream()
                .filter(item -> key.equals(item.get("key")))
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
