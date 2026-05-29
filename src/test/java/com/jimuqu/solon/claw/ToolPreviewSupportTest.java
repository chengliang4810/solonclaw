package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.gateway.feedback.ToolPreviewSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ToolPreviewSupportTest {
    @Test
    void shouldRedactConfigSecretValuesFromToolPreviews() {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("key", "providers.default.apiKey");
        args.put("value", "sk-test-preview-secret-12345");

        String compact = ToolPreviewSupport.buildPreview("config_set_secret", args, 120, false);
        String verbose = ToolPreviewSupport.buildPreview("config_set_secret", args, 120, true);
        String aliasVerbose = ToolPreviewSupport.buildPreview("config_update_secret", args, 120, true);

        assertThat(compact).isEqualTo("key=providers.default.apiKey");
        assertThat(verbose).contains("\"value\":\"***\"");
        assertThat(aliasVerbose).contains("\"value\":\"***\"");
        assertThat(compact + verbose + aliasVerbose)
                .doesNotContain("sk-test-preview-secret-12345");
    }

    @Test
    void shouldRedactSensitiveNestedArgsFromVerbosePreview() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("access_token", "token-secret-12345");
        nested.put("url", "https://example.com/callback?client_secret=client-secret-12345");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("payload", nested);

        String preview = ToolPreviewSupport.buildPreview("send_message", args, 240, true);

        assertThat(preview)
                .contains("\"access_token\":\"***\"")
                .contains("client_secret=***")
                .doesNotContain("token-secret-12345")
                .doesNotContain("client-secret-12345");
    }
}
