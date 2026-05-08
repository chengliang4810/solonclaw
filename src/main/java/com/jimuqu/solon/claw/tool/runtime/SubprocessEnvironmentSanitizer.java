package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Hermes-style subprocess environment filtering for local tools. */
public final class SubprocessEnvironmentSanitizer {
    public static final String FORCE_PREFIX = "_JIMUQU_FORCE_";
    public static final String HERMES_FORCE_PREFIX = "_HERMES_FORCE_";

    private static final Pattern ENV_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final String[] SAFE_ENV_PREFIXES =
            new String[] {
                "PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "LANG", "LC_", "TERM",
                "TMPDIR", "TMP", "TEMP", "SHELL", "LOGNAME", "XDG_", "PYTHONPATH",
                "VIRTUAL_ENV", "CONDA", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"
            };
    private static final String[] SECRET_ENV_SUBSTRINGS =
            new String[] {"KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"};
    private static final String SANE_POSIX_PATH =
            "/opt/homebrew/bin:/opt/homebrew/sbin:"
                    + "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private static final Set<String> PROVIDER_ENV_BLOCKLIST = providerEnvBlocklist();
    private static final ThreadLocal<Set<String>> SKILL_ENV_PASSTHROUGH =
            new ThreadLocal<Set<String>>();

    private SubprocessEnvironmentSanitizer() {}

    public static void sanitize(Map<String, String> env) {
        sanitize(env, null);
    }

    public static void sanitize(Map<String, String> env, AppConfig appConfig) {
        if (env == null) {
            return;
        }
        Set<String> passthrough = envPassthrough(appConfig);
        passthrough.addAll(currentSkillEnvironmentPassthrough());
        Map<String, String> raw = new LinkedHashMap<String, String>(env);
        env.clear();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey();
            String forcedName = forcedName(name);
            if (forcedName != null) {
                env.put(forcedName, entry.getValue());
                continue;
            }
            if (isEnvPassthrough(name, passthrough) && !isProviderEnvBlocked(name)) {
                env.put(name, entry.getValue());
                continue;
            }
            if (isSecretEnvName(name)) {
                continue;
            }
            if (isSafeEnvName(name)) {
                env.put(name, entry.getValue());
            }
        }
        String path = pathWithSaneFallback(env.get("PATH"), isWindows());
        if (path != null) {
            env.put("PATH", path);
        }
    }

    public static boolean isSafeEnvName(String name) {
        String value = StrUtil.nullToEmpty(name);
        for (String prefix : SAFE_ENV_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSecretEnvName(String name) {
        String upper = StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT);
        for (String marker : SECRET_ENV_SUBSTRINGS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProviderEnvBlocked(String name) {
        return PROVIDER_ENV_BLOCKLIST.contains(StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT));
    }

    public static AutoCloseable withSkillEnvironmentPassthrough(List<String> names) {
        final Set<String> previous = SKILL_ENV_PASSTHROUGH.get();
        Set<String> next =
                previous == null
                        ? new HashSet<String>()
                        : new HashSet<String>(previous);
        if (names != null) {
            for (String name : names) {
                String normalized = normalizeEnvName(name);
                if (normalized != null && !isProviderEnvBlocked(normalized)) {
                    next.add(normalized);
                }
            }
        }
        if (next.isEmpty()) {
            SKILL_ENV_PASSTHROUGH.remove();
        } else {
            SKILL_ENV_PASSTHROUGH.set(next);
        }
        return new AutoCloseable() {
            @Override
            public void close() {
                if (previous == null || previous.isEmpty()) {
                    SKILL_ENV_PASSTHROUGH.remove();
                } else {
                    SKILL_ENV_PASSTHROUGH.set(previous);
                }
            }
        };
    }

    private static boolean isEnvPassthrough(String name, Set<String> passthrough) {
        return passthrough != null
                && passthrough.contains(StrUtil.nullToEmpty(name).trim().toUpperCase(Locale.ROOT));
    }

    private static Set<String> envPassthrough(AppConfig appConfig) {
        Set<String> values = new HashSet<String>();
        if (appConfig == null || appConfig.getTerminal() == null) {
            return values;
        }
        List<String> configured = appConfig.getTerminal().getEnvPassthrough();
        if (configured == null) {
            return values;
        }
        for (String item : configured) {
            String value = StrUtil.nullToEmpty(item).trim();
            if (StrUtil.isNotBlank(value)) {
                values.add(value.toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    private static Set<String> currentSkillEnvironmentPassthrough() {
        Set<String> values = SKILL_ENV_PASSTHROUGH.get();
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<String>(values);
    }

    private static String normalizeEnvName(String name) {
        String value = StrUtil.nullToEmpty(name).trim();
        if (StrUtil.isBlank(value) || !ENV_NAME_PATTERN.matcher(value).matches()) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    static String pathWithSaneFallback(String path, boolean windows) {
        if (windows) {
            return path;
        }
        String value = StrUtil.nullToEmpty(path);
        if (value.length() == 0) {
            return SANE_POSIX_PATH;
        }
        String[] parts = value.split(":");
        for (String part : parts) {
            if ("/usr/bin".equals(part)) {
                return value;
            }
        }
        return value + ":" + SANE_POSIX_PATH;
    }

    private static String forcedName(String name) {
        String value = StrUtil.nullToEmpty(name);
        if (value.startsWith(FORCE_PREFIX) && value.length() > FORCE_PREFIX.length()) {
            return value.substring(FORCE_PREFIX.length());
        }
        if (value.startsWith(HERMES_FORCE_PREFIX) && value.length() > HERMES_FORCE_PREFIX.length()) {
            return value.substring(HERMES_FORCE_PREFIX.length());
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Set<String> providerEnvBlocklist() {
        Set<String> values = new HashSet<String>();
        String[] names =
                new String[] {
                    "OPENAI_API_KEY",
                    "OPENAI_BASE_URL",
                    "OPENAI_API_BASE",
                    "OPENAI_ORG_ID",
                    "OPENAI_ORGANIZATION",
                    "ANTHROPIC_API_KEY",
                    "ANTHROPIC_BASE_URL",
                    "ANTHROPIC_TOKEN",
                    "CLAUDE_CODE_OAUTH_TOKEN",
                    "OLLAMA_HOST",
                    "GEMINI_API_KEY",
                    "GOOGLE_API_KEY",
                    "OPENROUTER_API_KEY",
                    "ZAI_API_KEY",
                    "Z_AI_API_KEY",
                    "GLM_API_KEY",
                    "KIMI_API_KEY",
                    "MOONSHOT_API_KEY",
                    "MINIMAX_API_KEY",
                    "MINIMAX_CN_API_KEY",
                    "DEEPSEEK_API_KEY",
                    "NVIDIA_API_KEY",
                    "MISTRAL_API_KEY",
                    "GROQ_API_KEY",
                    "TOGETHER_API_KEY",
                    "PERPLEXITY_API_KEY",
                    "COHERE_API_KEY",
                    "FIREWORKS_API_KEY",
                    "XAI_API_KEY",
                    "HELICONE_API_KEY",
                    "PARALLEL_API_KEY",
                    "LLM_MODEL",
                    "EXA_API_KEY",
                    "FIRECRAWL_API_KEY",
                    "FIRECRAWL_API_URL",
                    "FIRECRAWL_GATEWAY_URL",
                    "TAVILY_API_KEY",
                    "SEARXNG_URL",
                    "BROWSERBASE_API_KEY",
                    "BROWSERBASE_PROJECT_ID",
                    "BROWSER_USE_API_KEY",
                    "FAL_KEY",
                    "ELEVENLABS_API_KEY",
                    "VOICE_TOOLS_OPENAI_KEY",
                    "TOOL_GATEWAY_DOMAIN",
                    "TOOL_GATEWAY_SCHEME",
                    "TOOL_GATEWAY_USER_TOKEN",
                    "TERMINAL_SSH_HOST",
                    "TERMINAL_SSH_USER",
                    "TERMINAL_SSH_KEY",
                    "SUDO_PASSWORD",
                    "FEISHU_APP_ID",
                    "FEISHU_APP_SECRET",
                    "DINGTALK_CLIENT_ID",
                    "DINGTALK_CLIENT_SECRET",
                    "DINGTALK_ROBOT_CODE",
                    "WECOM_BOT_ID",
                    "WECOM_SECRET",
                    "WEIXIN_TOKEN",
                    "QQBOT_APP_ID",
                    "QQBOT_CLIENT_SECRET",
                    "YUANBAO_APP_ID",
                    "YUANBAO_APP_SECRET",
                    "TELEGRAM_BOT_TOKEN",
                    "TELEGRAM_HOME_CHANNEL",
                    "TELEGRAM_HOME_CHANNEL_NAME",
                    "DISCORD_BOT_TOKEN",
                    "DISCORD_HOME_CHANNEL",
                    "DISCORD_HOME_CHANNEL_NAME",
                    "DISCORD_REQUIRE_MENTION",
                    "DISCORD_FREE_RESPONSE_CHANNELS",
                    "DISCORD_AUTO_THREAD",
                    "SLACK_BOT_TOKEN",
                    "SLACK_APP_TOKEN",
                    "SLACK_HOME_CHANNEL",
                    "SLACK_HOME_CHANNEL_NAME",
                    "SLACK_ALLOWED_USERS",
                    "WHATSAPP_ENABLED",
                    "WHATSAPP_MODE",
                    "WHATSAPP_ALLOWED_USERS",
                    "SIGNAL_HTTP_URL",
                    "SIGNAL_ACCOUNT",
                    "SIGNAL_ALLOWED_USERS",
                    "SIGNAL_GROUP_ALLOWED_USERS",
                    "SIGNAL_HOME_CHANNEL",
                    "SIGNAL_HOME_CHANNEL_NAME",
                    "SIGNAL_IGNORE_STORIES",
                    "HASS_TOKEN",
                    "HASS_URL",
                    "EMAIL_ADDRESS",
                    "EMAIL_PASSWORD",
                    "EMAIL_IMAP_HOST",
                    "EMAIL_SMTP_HOST",
                    "EMAIL_HOME_ADDRESS",
                    "EMAIL_HOME_ADDRESS_NAME",
                    "GATEWAY_ALLOWED_USERS",
                    "GATEWAY_ALLOW_ALL_USERS",
                    "GATEWAY_INJECTION_SECRET",
                    "GH_TOKEN",
                    "GITHUB_TOKEN",
                    "GITHUB_APP_ID",
                    "GITHUB_APP_PRIVATE_KEY_PATH",
                    "GITHUB_APP_INSTALLATION_ID",
                    "MODAL_TOKEN_ID",
                    "MODAL_TOKEN_SECRET",
                    "DAYTONA_API_KEY",
                    "VERCEL_OIDC_TOKEN",
                    "VERCEL_TOKEN",
                    "VERCEL_PROJECT_ID",
                    "VERCEL_TEAM_ID",
                    "HONCHO_API_KEY",
                    "WANDB_API_KEY",
                    "TINKER_API_KEY"
                };
        for (String name : names) {
            values.add(name);
        }
        return values;
    }
}
