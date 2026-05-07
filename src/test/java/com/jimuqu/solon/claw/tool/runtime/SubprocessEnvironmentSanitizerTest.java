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

    @Test
    void shouldStripToolBackendSecretsAndGatewayRuntimeVarsLikeHermes() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("OPENAI_API_BASE", "https://legacy.example/v1");
        env.put("OPENAI_ORGANIZATION", "org-secret");
        env.put("ANTHROPIC_BASE_URL", "https://anthropic-proxy.example");
        env.put("NVIDIA_API_KEY", "nvidia-secret");
        env.put("PARALLEL_API_KEY", "parallel-secret");
        env.put("EXA_API_KEY", "exa-secret");
        env.put("FIRECRAWL_API_KEY", "firecrawl-secret");
        env.put("FIRECRAWL_API_URL", "https://firecrawl.example");
        env.put("TAVILY_API_KEY", "tavily-secret");
        env.put("SEARXNG_URL", "https://searxng.example");
        env.put("BROWSERBASE_PROJECT_ID", "browserbase-project");
        env.put("BROWSERBASE_API_KEY", "browserbase-secret");
        env.put("BROWSER_USE_API_KEY", "browser-use-secret");
        env.put("ELEVENLABS_API_KEY", "elevenlabs-secret");
        env.put("VOICE_TOOLS_OPENAI_KEY", "voice-secret");
        env.put("FAL_KEY", "fal-secret");
        env.put("TOOL_GATEWAY_USER_TOKEN", "gateway-token");
        env.put("TERMINAL_SSH_KEY", "ssh-secret");
        env.put("SUDO_PASSWORD", "sudo-secret");
        env.put("MODAL_TOKEN_ID", "modal-id");
        env.put("MODAL_TOKEN_SECRET", "modal-secret");
        env.put("DAYTONA_API_KEY", "daytona-secret");
        env.put("VERCEL_OIDC_TOKEN", "vercel-oidc");
        env.put("VERCEL_TOKEN", "vercel-secret");
        env.put("VERCEL_PROJECT_ID", "vercel-project");
        env.put("VERCEL_TEAM_ID", "vercel-team");

        SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(env).containsEntry("PATH", "/usr/bin");
        assertThat(env)
                .doesNotContainKeys(
                        "OPENAI_API_BASE",
                        "OPENAI_ORGANIZATION",
                        "ANTHROPIC_BASE_URL",
                        "NVIDIA_API_KEY",
                        "PARALLEL_API_KEY",
                        "EXA_API_KEY",
                        "FIRECRAWL_API_KEY",
                        "FIRECRAWL_API_URL",
                        "TAVILY_API_KEY",
                        "SEARXNG_URL",
                        "BROWSERBASE_PROJECT_ID",
                        "BROWSERBASE_API_KEY",
                        "BROWSER_USE_API_KEY",
                        "ELEVENLABS_API_KEY",
                        "VOICE_TOOLS_OPENAI_KEY",
                        "FAL_KEY",
                        "TOOL_GATEWAY_USER_TOKEN",
                        "TERMINAL_SSH_KEY",
                        "SUDO_PASSWORD",
                        "MODAL_TOKEN_ID",
                        "MODAL_TOKEN_SECRET",
                        "DAYTONA_API_KEY",
                        "VERCEL_OIDC_TOKEN",
                        "VERCEL_TOKEN",
                        "VERCEL_PROJECT_ID",
                        "VERCEL_TEAM_ID");
    }

    @Test
    void shouldStripUnsupportedForeignChannelVarsEvenWhenPresent() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("TELEGRAM_BOT_TOKEN", "telegram-secret");
        env.put("TELEGRAM_HOME_CHANNEL", "telegram-channel");
        env.put("DISCORD_BOT_TOKEN", "discord-secret");
        env.put("DISCORD_HOME_CHANNEL", "discord-channel");
        env.put("DISCORD_REQUIRE_MENTION", "true");
        env.put("SLACK_BOT_TOKEN", "slack-bot");
        env.put("SLACK_APP_TOKEN", "slack-app");
        env.put("SLACK_ALLOWED_USERS", "alice");
        env.put("WHATSAPP_ENABLED", "true");
        env.put("WHATSAPP_ALLOWED_USERS", "+15555550123");
        env.put("SIGNAL_ACCOUNT", "+15555550124");
        env.put("SIGNAL_HOME_CHANNEL", "signal-channel");
        env.put("HASS_TOKEN", "hass-secret");
        env.put("HASS_URL", "https://homeassistant.example");
        env.put("EMAIL_ADDRESS", "bot@example.com");
        env.put("EMAIL_PASSWORD", "email-secret");
        env.put("EMAIL_IMAP_HOST", "imap.example.com");
        env.put("EMAIL_SMTP_HOST", "smtp.example.com");

        SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(env).containsEntry("PATH", "/usr/bin");
        assertThat(env)
                .doesNotContainKeys(
                        "TELEGRAM_BOT_TOKEN",
                        "TELEGRAM_HOME_CHANNEL",
                        "DISCORD_BOT_TOKEN",
                        "DISCORD_HOME_CHANNEL",
                        "DISCORD_REQUIRE_MENTION",
                        "SLACK_BOT_TOKEN",
                        "SLACK_APP_TOKEN",
                        "SLACK_ALLOWED_USERS",
                        "WHATSAPP_ENABLED",
                        "WHATSAPP_ALLOWED_USERS",
                        "SIGNAL_ACCOUNT",
                        "SIGNAL_HOME_CHANNEL",
                        "HASS_TOKEN",
                        "HASS_URL",
                        "EMAIL_ADDRESS",
                        "EMAIL_PASSWORD",
                        "EMAIL_IMAP_HOST",
                        "EMAIL_SMTP_HOST");
    }

    @Test
    void shouldKeepHermesBlocklistAboveConfiguredPassthroughForToolBackendVars() {
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("TENOR_API_KEY");
        config.getTerminal().getEnvPassthrough().add("FIRECRAWL_API_KEY");
        config.getTerminal().getEnvPassthrough().add("BROWSERBASE_PROJECT_ID");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("TENOR_API_KEY", "tenor-secret");
        env.put("FIRECRAWL_API_KEY", "firecrawl-secret");
        env.put("BROWSERBASE_PROJECT_ID", "browserbase-project");

        SubprocessEnvironmentSanitizer.sanitize(env, config);

        assertThat(env).containsEntry("PATH", "/usr/bin");
        assertThat(env).containsEntry("TENOR_API_KEY", "tenor-secret");
        assertThat(env).doesNotContainKeys("FIRECRAWL_API_KEY", "BROWSERBASE_PROJECT_ID");
    }

    @Test
    void shouldAppendSanePosixPathWhenPathIsTooNarrowLikeHermes() {
        String result =
                SubprocessEnvironmentSanitizer.pathWithSaneFallback(
                        "/some/custom/bin", false);

        assertThat(result).startsWith("/some/custom/bin:");
        assertThat(result).contains("/opt/homebrew/bin");
        assertThat(result).contains("/opt/homebrew/sbin");
        assertThat(result).contains("/usr/local/bin");
        assertThat(result).contains("/usr/bin");
    }

    @Test
    void shouldKeepExistingPosixPathWhenUsrBinIsPresentLikeHermes() {
        String path = "/custom/bin:/usr/bin:/bin";

        String result = SubprocessEnvironmentSanitizer.pathWithSaneFallback(path, false);

        assertThat(result).isEqualTo(path);
    }

    @Test
    void shouldNotAppendPosixFallbackOnWindows() {
        String path = "C:\\Windows\\System32";

        String result = SubprocessEnvironmentSanitizer.pathWithSaneFallback(path, true);

        assertThat(result).isEqualTo(path);
    }
}
