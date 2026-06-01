package com.jimuqu.solon.claw.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jimuqu.solon.claw.config.AppConfig;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SubprocessEnvironmentSanitizerTest {
    @Test
    void shouldExposeEnvironmentSanitizerPolicyWithoutSecretNames() {
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("TENOR_API_KEY");
        config.getTerminal().getEnvPassthrough().add("OPENAI_API_KEY");

        Map<String, Object> summary = SubprocessEnvironmentSanitizer.policySummary(config);

        assertThat(summary.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("defaultDenyUnknownEnv")).isEqualTo(Boolean.TRUE);
        assertThat(((Integer) summary.get("providerBlocklistCount")).intValue()).isGreaterThan(50);
        assertThat(summary.get("configuredPassthroughCount")).isEqualTo(Integer.valueOf(2));
        assertThat(summary.get("providerBlocklistOverridesPassthrough")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("forcePrefix")).isEqualTo(SubprocessEnvironmentSanitizer.FORCE_PREFIX);
        assertThat(summary.get("decisionProbeSupported")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("decisionProbeValueRedacted")).isEqualTo(Boolean.TRUE);
        assertThat(summary.get("decisionCategories"))
                .isEqualTo(Arrays.asList("force", "allow", "provider-blocked", "high-risk", "block"));
        assertThat(String.valueOf(summary))
                .contains("skillScopedPassthroughSupported")
                .contains("toolBackendSecretsBlocked")
                .contains("pathFallbackEnabledForPosix")
                .doesNotContain("OPENAI_API_KEY")
                .doesNotContain("TENOR_API_KEY");
    }

    @Test
    void shouldProbePerVariableDecisionsWithoutValues() {
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("TENOR_API_KEY");
        config.getTerminal().getEnvPassthrough().add("OPENAI_API_KEY");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("TENOR_API_KEY", "tenor-secret");
        env.put("OPENAI_API_KEY", "sk-provider");
        env.put("CUSTOM_TOKEN", "custom-secret");
        env.put("MY_UNKNOWN_ENV", "drop-me");
        env.put(SubprocessEnvironmentSanitizer.FORCE_PREFIX + "CUSTOM_TOKEN", "forced-secret");
        env.put("BAD-NAME", "bad");

        Map<String, Map<String, Object>> decisions = decisionsByName(
                SubprocessEnvironmentSanitizer.probeDecisions(env, config));

        assertThat(decisions.get("PATH"))
                .containsEntry("decision", "allow")
                .containsEntry("reason", "safe-prefix-or-context")
                .containsEntry("allowed", Boolean.TRUE);
        assertThat(decisions.get("TENOR_API_KEY"))
                .containsEntry("decision", "allow")
                .containsEntry("reason", "configured-or-skill-passthrough")
                .containsEntry("configuredPassthrough", Boolean.TRUE);
        assertThat(decisions.get("OPENAI_API_KEY"))
                .containsEntry("decision", "provider-blocked")
                .containsEntry("reason", "provider-blocklist-overrides-passthrough")
                .containsEntry("providerBlocked", Boolean.TRUE);
        assertThat(decisions.get("CUSTOM_TOKEN"))
                .containsEntry("decision", "high-risk")
                .containsEntry("reason", "secret-name-substring")
                .containsEntry("highRisk", Boolean.TRUE);
        assertThat(decisions.get("MY_UNKNOWN_ENV"))
                .containsEntry("decision", "block")
                .containsEntry("reason", "default-deny-unknown")
                .containsEntry("blocked", Boolean.TRUE);
        assertThat(decisions.get(SubprocessEnvironmentSanitizer.FORCE_PREFIX + "CUSTOM_TOKEN"))
                .containsEntry("decision", "force")
                .containsEntry("reason", "force-prefix")
                .containsEntry("outputName", "CUSTOM_TOKEN");
        assertThat(decisions.get("BAD-NAME"))
                .containsEntry("decision", "block")
                .containsEntry("reason", "invalid-env-name")
                .containsEntry("validName", Boolean.FALSE);
        assertThat(String.valueOf(decisions))
                .doesNotContain("tenor-secret")
                .doesNotContain("sk-provider")
                .doesNotContain("forced-secret");
    }

    @Test
    void shouldProbeSkillScopedPassthroughDecision() throws Exception {
        AutoCloseable scope =
                SubprocessEnvironmentSanitizer.withSkillEnvironmentPassthrough(
                        Arrays.asList("MAPBOX_TOKEN"));
        try {
            Map<String, Object> decision =
                    SubprocessEnvironmentSanitizer.probeDecision("MAPBOX_TOKEN", null);

            assertThat(decision)
                    .containsEntry("decision", "allow")
                    .containsEntry("reason", "configured-or-skill-passthrough")
                    .containsEntry("configuredPassthrough", Boolean.TRUE)
                    .containsEntry("highRisk", Boolean.TRUE)
                    .containsEntry("valueIncluded", Boolean.FALSE);
        } finally {
            scope.close();
        }
    }

    private Map<String, Map<String, Object>> decisionsByName(List<Map<String, Object>> decisions) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> decision : decisions) {
            result.put(String.valueOf(decision.get("name")), decision);
        }
        return result;
    }

    @Test
    void shouldStripProviderToolAndGatewaySecretsFromSubprocessEnvLikeJimuqu() {
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
        env.put("_JIMUQU_FORCE_CUSTOM_TOKEN", "token-explicit");
        env.put("_JIMUQU_FORCE_BAD-NAME", "bad");

        SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(env)
                .containsEntry("OPENAI_API_KEY", "sk-explicit")
                .containsEntry("CUSTOM_TOKEN", "token-explicit");
        assertThat(env)
                .doesNotContainKeys(
                        "_JIMUQU_FORCE_OPENAI_API_KEY",
                        "_JIMUQU_FORCE_CUSTOM_TOKEN",
                        "_JIMUQU_FORCE_BAD-NAME",
                        "BAD-NAME");
    }

    @Test
    void shouldAllowConfiguredThirdPartyPassthroughButKeepProviderBlocklist() {
        AppConfig config = new AppConfig();
        config.getTerminal().getEnvPassthrough().add("TENOR_API_KEY");
        config.getTerminal().getEnvPassthrough().add("OPENAI_API_KEY");
        config.getTerminal().getEnvPassthrough().add("BAD-NAME");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("TENOR_API_KEY", "tenor-secret");
        env.put("OPENAI_API_KEY", "sk-provider");
        env.put("BAD-NAME", "bad");

        SubprocessEnvironmentSanitizer.sanitize(env, config);

        assertThat(env).containsEntry("PATH", "/usr/bin");
        assertThat(env).containsEntry("TENOR_API_KEY", "tenor-secret");
        assertThat(env).doesNotContainKeys("OPENAI_API_KEY", "BAD-NAME");
    }

    @Test
    void shouldRejectHighRiskConfiguredPassthroughNames() {
        SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                Arrays.asList("TENOR_API_KEY", "NOTION_TOKEN"), "terminal.envPassthrough");

        assertThatThrownBy(
                        () ->
                                SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                                        Arrays.asList("TENOR_API_KEY", " OPENAI_API_KEY "),
                                        "terminal.envPassthrough"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal.envPassthrough")
                .hasMessageContaining("OPENAI_API_KEY")
                .hasMessageContaining("high-risk");
    }

    @Test
    void shouldRejectHighRiskOperationalConfiguredPassthroughNames() {
        assertThatThrownBy(
                        () ->
                                SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                                        Arrays.asList("PATH"), "terminal.env_passthrough"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal.env_passthrough")
                .hasMessageContaining("PATH")
                .hasMessageContaining("high-risk");

        assertThatThrownBy(
                        () ->
                                SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                                        Arrays.asList("LD_PRELOAD"), "terminal.env_passthrough"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LD_PRELOAD")
                .hasMessageContaining("high-risk");

        assertThatThrownBy(
                        () ->
                                SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                                        Arrays.asList("PYTHONPATH"), "terminal.env_passthrough"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PYTHONPATH")
                .hasMessageContaining("high-risk");
    }

    @Test
    void shouldRejectInvalidAndForcePrefixedConfiguredPassthroughNames() {
        assertThatThrownBy(
                        () ->
                                SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                                        Arrays.asList("BAD-NAME"), "terminal.env_passthrough"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal.env_passthrough")
                .hasMessageContaining("invalid env var name");

        assertThatThrownBy(
                        () ->
                                SubprocessEnvironmentSanitizer.validateConfiguredEnvPassthrough(
                                        Arrays.asList("_JIMUQU_FORCE_OPENAI_API_KEY"),
                                        "terminal.env_passthrough"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal.env_passthrough")
                .hasMessageContaining("reserved force prefix");
    }

    @Test
    void shouldStripToolBackendSecretsAndGatewayRuntimeVarsLikeJimuqu() {
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
    void shouldKeepJimuquBlocklistAboveConfiguredPassthroughForToolBackendVars() {
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
    void shouldAllowScopedSkillEnvironmentPassthroughButKeepProviderSecretsBlocked()
            throws Exception {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("TENOR_API_KEY", "tenor-secret");
        env.put("OPENAI_API_KEY", "sk-provider");
        env.put("OpenAi_Api_Key", "sk-mixed-provider");
        env.put("BAD-NAME", "bad");

        AutoCloseable scope =
                SubprocessEnvironmentSanitizer.withSkillEnvironmentPassthrough(
                        Arrays.asList(
                                "TENOR_API_KEY",
                                "OPENAI_API_KEY",
                                "OpenAi_Api_Key",
                                "BAD-NAME"));
        try {
            SubprocessEnvironmentSanitizer.sanitize(env);
        } finally {
            scope.close();
        }

        assertThat(env).containsEntry("PATH", "/usr/bin");
        assertThat(env).containsEntry("TENOR_API_KEY", "tenor-secret");
        assertThat(env).doesNotContainKeys("OPENAI_API_KEY", "OpenAi_Api_Key", "BAD-NAME");

        Map<String, String> afterScope = new LinkedHashMap<String, String>();
        afterScope.put("PATH", "/usr/bin");
        afterScope.put("TENOR_API_KEY", "tenor-secret");
        SubprocessEnvironmentSanitizer.sanitize(afterScope);
        assertThat(afterScope).doesNotContainKey("TENOR_API_KEY");
    }

    @Test
    void shouldRestoreNestedSkillEnvironmentPassthroughScopes() throws Exception {
        Map<String, String> outerEnv = new LinkedHashMap<String, String>();
        outerEnv.put("PATH", "/usr/bin");
        outerEnv.put("TENOR_API_KEY", "tenor-secret");
        outerEnv.put("MAPBOX_TOKEN", "mapbox-token");

        AutoCloseable outer =
                SubprocessEnvironmentSanitizer.withSkillEnvironmentPassthrough(
                        Arrays.asList("TENOR_API_KEY"));
        try {
            AutoCloseable inner =
                    SubprocessEnvironmentSanitizer.withSkillEnvironmentPassthrough(
                            Arrays.asList("MAPBOX_TOKEN"));
            try {
                SubprocessEnvironmentSanitizer.sanitize(outerEnv);
            } finally {
                inner.close();
            }

            Map<String, String> restored = new LinkedHashMap<String, String>();
            restored.put("PATH", "/usr/bin");
            restored.put("TENOR_API_KEY", "tenor-secret");
            restored.put("MAPBOX_TOKEN", "mapbox-token");
            SubprocessEnvironmentSanitizer.sanitize(restored);
            assertThat(restored).containsEntry("TENOR_API_KEY", "tenor-secret");
            assertThat(restored).doesNotContainKey("MAPBOX_TOKEN");
        } finally {
            outer.close();
        }

        assertThat(outerEnv)
                .containsEntry("TENOR_API_KEY", "tenor-secret")
                .containsEntry("MAPBOX_TOKEN", "mapbox-token");
    }

    @Test
    void shouldRegisterAndClearSkillEnvironmentPassthroughForCurrentRun() {
        try {
            SubprocessEnvironmentSanitizer.registerSkillEnvironmentPassthrough(
                    Arrays.asList("TENOR_API_KEY", "OPENAI_API_KEY", "BAD-NAME"));
            Map<String, String> env = new LinkedHashMap<String, String>();
            env.put("PATH", "/usr/bin");
            env.put("TENOR_API_KEY", "tenor-secret");
            env.put("OPENAI_API_KEY", "sk-provider");
            env.put("BAD-NAME", "bad");

            SubprocessEnvironmentSanitizer.sanitize(env);

            assertThat(env).containsEntry("TENOR_API_KEY", "tenor-secret");
            assertThat(env).doesNotContainKeys("OPENAI_API_KEY", "BAD-NAME");
        } finally {
            SubprocessEnvironmentSanitizer.clearSkillEnvironmentPassthrough();
        }

        Map<String, String> afterClear = new LinkedHashMap<String, String>();
        afterClear.put("PATH", "/usr/bin");
        afterClear.put("TENOR_API_KEY", "tenor-secret");
        SubprocessEnvironmentSanitizer.sanitize(afterClear);
        assertThat(afterClear).doesNotContainKey("TENOR_API_KEY");
    }

    @Test
    void shouldKeepNonSecretJimuquExecutionContextButDropSafetyTogglesAndTokens() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin");
        env.put("JIMUQU_KANBAN_TASK", "task-123");
        env.put("jimuqu_kanban_worker", "worker-a");
        env.put("JIMUQU_PROFILE", "reviewer");
        env.put("JIMUQU_RPC_DIR", "/tmp/jimuqu-rpc");
        env.put("JIMUQU_ALLOW_PRIVATE_URLS", "true");
        env.put("JIMUQU_WRITE_SAFE_ROOT", "/");
        env.put("JIMUQU_DASHBOARD_ACCESS_TOKEN", "dashboard-secret");

        SubprocessEnvironmentSanitizer.sanitize(env);

        assertThat(env)
                .containsEntry("JIMUQU_KANBAN_TASK", "task-123")
                .containsEntry("jimuqu_kanban_worker", "worker-a")
                .containsEntry("JIMUQU_PROFILE", "reviewer");
        assertThat(env)
                .doesNotContainKeys(
                        "JIMUQU_RPC_DIR",
                        "JIMUQU_ALLOW_PRIVATE_URLS",
                        "JIMUQU_WRITE_SAFE_ROOT",
                        "JIMUQU_DASHBOARD_ACCESS_TOKEN");
    }

    @Test
    void shouldAppendSanePosixPathWhenPathIsTooNarrowLikeJimuqu() {
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
    void shouldKeepExistingPosixPathWhenUsrBinIsPresentLikeJimuqu() {
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
