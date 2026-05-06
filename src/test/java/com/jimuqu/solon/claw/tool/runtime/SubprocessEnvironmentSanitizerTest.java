package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SubprocessEnvironmentSanitizerTest {
    @Test
    void shouldStripProviderToolAndGatewaySecretsFromSubprocessEnvLikeHermes() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("HOME", "/home/user");
        env.put("OPENAI_API_KEY", "sk-secret");
        env.put("OPENAI_BASE_URL", "https://proxy.example/v1");
        env.put("ANTHROPIC_TOKEN", "ant-secret");
        env.put("FEISHU_APP_SECRET", "feishu-secret");
        env.put("GATEWAY_ALLOWED_USERS", "alice,bob");
        env.put("MY_CUSTOM_VAR", "drop-by-default");

        SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(env).containsEntry("PATH", "/usr/bin").containsEntry("HOME", "/home/user");
        assertThat(env)
                .doesNotContainKeys(
                        "OPENAI_API_KEY",
                        "OPENAI_BASE_URL",
                        "ANTHROPIC_TOKEN",
                        "FEISHU_APP_SECRET",
                        "GATEWAY_ALLOWED_USERS",
                        "MY_CUSTOM_VAR");
    }

    @Test
    void shouldAllowExplicitForcePrefixForIntentionalSubprocessSecrets() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("_JIMUQU_FORCE_OPENAI_API_KEY", "sk-explicit");
        env.put("_HERMES_FORCE_CUSTOM_TOKEN", "token-explicit");

        SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(env)
                .containsEntry("OPENAI_API_KEY", "sk-explicit")
                .containsEntry("CUSTOM_TOKEN", "token-explicit");
        assertThat(env)
                .doesNotContainKeys("_JIMUQU_FORCE_OPENAI_API_KEY", "_HERMES_FORCE_CUSTOM_TOKEN");
    }

    @Test
    void shouldAllowConfiguredThirdPartyPassthroughButKeepProviderBlocklist() {
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("TENOR_API_KEY");
        config.getTerminal().getEnvPassthrough().add("OPENAI_API_KEY");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("TENOR_API_KEY", "tenor-secret");
        env.put("OPENAI_API_KEY", "sk-provider");

        SubprocessEnvironmentSanitizer.sanitize(env, config);

        assertThat(env).containsEntry("PATH", "/usr/bin");
        assertThat(env).containsEntry("TENOR_API_KEY", "tenor-secret");
        assertThat(env).doesNotContainKey("OPENAI_API_KEY");
    }
}
